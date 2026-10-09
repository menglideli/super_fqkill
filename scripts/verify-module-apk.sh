#!/usr/bin/env bash
# verify-module-apk.sh — 校验产出的 APK 是否是一个"能被 LSPosed 正确加载"的 Xposed 模块。
#
# 为什么需要这个脚本:两个上游仓库的多数事故都属于**静默失败**——APK 构建成功、安装成功、
# LSPosed 里也勾了作用域,但模块根本没加载,而且**没有任何错误**。这类问题在 CI 里就能挡住,
# 不需要设备。逐条对应 RESEARCH.md §11 的陷阱清单:
#
#   java_init.list 的 FQCN 写错          → 陷阱 #11  (本脚本 check 4)
#   R8 把入口类重命名                     → 陷阱 #9   (check 4,对 release APK)
#   libdexkit.so 没打进 APK               → 陷阱 #2   (check 8)
#   java_init.list 混入经典 Xposed 入口    → 陷阱 #12  (check 5)
#   scope.list 漏包                       → 陷阱 #14  (check 7)
#   module.prop 缺 id/name/version        → squemaFQH 的实际状态,只有 4 个键 (check 6)
#   三处版本号不同步                       → FanqieHook 的 build.gradle.kts / module.prop / update.json (check 6)
#
# 用法: ./scripts/verify-module-apk.sh <apk 路径> <variant 名>
# 退出码: 0 = 全部通过, 1 = 有 FAIL

set -uo pipefail

APK="${1:?用法: $0 <apk> <variant>}"
VARIANT="${2:-unknown}"

# 可按需调整的阈值
MAX_APK_KB="${MAX_APK_KB:-1536}"          # 体积预算。基线:FanqieHook 0.47 MB
REQUIRED_SCOPE="com.dragon.read com.phoenix.read"
BUILD_GRADLE="app/build.gradle.kts"

PASS=0; FAIL=0; WARN=0; SKIP=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; WARN=$((WARN+1)); }
skip() { printf '  \033[36mSKIP\033[0m  %s\n' "$1"; SKIP=$((SKIP+1)); }
hdr()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

echo "════════════════════════════════════════════════════════════"
echo " 模块契约校验: $APK  (variant=$VARIANT)"
echo "════════════════════════════════════════════════════════════"

# ── 工具可用性 ──────────────────────────────────────────────────
# apkanalyzer 由 android-actions/setup-android 加进 PATH;本地则可能在 cmdline-tools 下
if ! command -v apkanalyzer >/dev/null 2>&1; then
  CAND=$(find "${ANDROID_HOME:-$HOME/Android/Sdk}" -name apkanalyzer -type f 2>/dev/null | head -1)
  [ -n "$CAND" ] && PATH="$(dirname "$CAND"):$PATH"
fi
HAVE_APKANALYZER=0
command -v apkanalyzer >/dev/null 2>&1 && HAVE_APKANALYZER=1

APKSIGNER=""
for d in "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/; do
  [ -x "${d}apksigner" ] && APKSIGNER="${d}apksigner"
done

# python3(ubuntu runner)或 python(Windows Git Bash 常见的是这个名字),都没有就降级
PYTHON=""
for c in python3 python; do command -v "$c" >/dev/null 2>&1 && { PYTHON="$c"; break; }; done

hdr "0. 前置"
[ -f "$APK" ] || { bad "APK 不存在: $APK"; echo; echo "结果: FAIL=$FAIL"; exit 1; }
ok "APK 存在"
[ "$HAVE_APKANALYZER" = 1 ] && ok "apkanalyzer 可用" || skip "apkanalyzer 不可用 —— dex 相关检查将跳过(这会让本脚本失去主要价值,请修好)"

# ── 1. 体积 ────────────────────────────────────────────────────
hdr "1. 体积预算"
SIZE_KB=$(( $(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK") / 1024 ))
echo "       实际 ${SIZE_KB} KB / 预算 ${MAX_APK_KB} KB"
if [ "$SIZE_KB" -le "$MAX_APK_KB" ]; then ok "体积在预算内"
else bad "体积超预算 —— 检查 abiFilters 是否只有 arm64-v8a、minifyEnabled 是否开启"; fi

# ── 2. Xposed 元数据文件存在 ───────────────────────────────────
hdr "2. META-INF/xposed/ 三件套"
for f in java_init.list module.prop scope.list; do
  if unzip -l "$APK" "META-INF/xposed/$f" 2>/dev/null | grep -q "META-INF/xposed/$f"; then
    ok "META-INF/xposed/$f 存在"
  else
    bad "META-INF/xposed/$f **缺失** —— 模块不会被 LSPosed 识别。检查 build.gradle 的 packaging.resources.merges += \"META-INF/xposed/*\""
  fi
done

# 取出来供后续检查
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
unzip -p "$APK" META-INF/xposed/java_init.list > "$TMP/java_init.list" 2>/dev/null || true
unzip -p "$APK" META-INF/xposed/module.prop    > "$TMP/module.prop"    2>/dev/null || true
unzip -p "$APK" META-INF/xposed/scope.list     > "$TMP/scope.list"     2>/dev/null || true

# ── 3. java_init.list 格式 ─────────────────────────────────────
# 陷阱 #11:FQCN 写错 → 模块永不加载,无错误、无日志。这是全项目最致命的静默失败。
hdr "3. java_init.list 格式"
ENTRIES=$(grep -cve '^[[:space:]]*$' "$TMP/java_init.list" 2>/dev/null || echo 0)
if [ "$ENTRIES" -eq 1 ]; then
  ok "恰好 1 个入口类"
elif [ "$ENTRIES" -eq 0 ]; then
  bad "java_init.list 为空"
else
  bad "有 $ENTRIES 个入口类 —— 必须合并成 1 个,否则各入口会各自建 DexKit 桥、各自扫全 dex"
fi

ENTRY=$(grep -ve '^[[:space:]]*$' "$TMP/java_init.list" 2>/dev/null | head -1 | tr -d '\r' | xargs)
echo "       入口 FQCN: ${ENTRY:-<空>}"
if [[ "$ENTRY" =~ ^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_$][a-zA-Z0-9_$]*)+$ ]]; then
  ok "FQCN 格式合法"
else
  bad "FQCN 格式非法(注意不要有多余空格/引号/CRLF)"
fi

# ── 4. 入口类真的存在于 dex 中(且是 defined,不只是 referenced) ──
# 这一条同时挡住两件事:(a) FQCN 拼错 (b) R8 把入口类重命名了。
# 上游 FanqieHook 靠 proguard-rules.pro:6 的 -keep 保住入口类名;
# squemaFQH 的 proguard-rules.pro 是空模板,只是因为它 minifyEnabled false 才没炸。
hdr "4. 入口类在 dex 中已定义"
if [ "$HAVE_APKANALYZER" = 1 ] && [ -n "$ENTRY" ]; then
  # --defined-only 只列**定义**在本 dex 里的类,所以命中即证明不是"仅被引用"
  if apkanalyzer dex packages --defined-only "$APK" 2>/dev/null | grep -qF "$ENTRY"; then
    ok "类 $ENTRY 在 dex 中已定义"
  else
    bad "类 $ENTRY **未在 dex 中定义** → LSPosed 会静默跳过整个模块。"
    echo "         排查顺序:"
    echo "           1) java_init.list 的 FQCN 是否和源码 package+类名完全一致"
    echo "           2) release 变体:proguard 是否有 -keep class * extends io.github.libxposed.api.XposedModule"
    echo "           3) release 变体:是否需要 -adaptresourcefilecontents META-INF/xposed/java_init.list"
    echo "         当前 dex 里 XposedModule 的子类有:"
    apkanalyzer dex packages --defined-only "$APK" 2>/dev/null | grep -iE 'ModuleEntry|XposedModule' | sed 's/^/           /' | head -10
  fi
else
  skip "apkanalyzer 不可用或入口为空"
fi

# ── 5. 没有混入经典 Xposed API ─────────────────────────────────
# 陷阱 #12:LSPosed v2.2.0 在 Modern 模式下找不到 de.robv.android.xposed.IXposedHookLoadPackage
# 会**静默失败整个模块**,不只是那一条。squemaFQH 就因为一个死文件留着 compileOnly 依赖。
hdr "5. 纯 Modern API(无 de.robv 残留)"
# 注意:不能用 `strings` —— Git Bash 默认没有这个命令,会让管道产出空串、grep -c 得 0,
# 从而在**有残留时也报 PASS**(实测踩过)。grep -a 直接把二进制当文本匹配,无需外部工具。
ROBV=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -ac 'de/robv/android/xposed' 2>/dev/null || true)
if [ -z "${ROBV//[^0-9]/}" ]; then
  skip "无法读取 dex 内容(unzip 失败或 APK 里没有 classes*.dex)"
elif [ "$ROBV" -eq 0 ]; then
  ok "dex 中无 de/robv/android/xposed 引用"
else
  bad "dex 中出现 $ROBV 处 de/robv/android/xposed 引用 —— 删掉 compileOnly(\"de.robv.android.xposed:api:82\") 及其唯一消费者"
fi

# ── 6. module.prop 完整性 + 版本号三处同步 ─────────────────────
hdr "6. module.prop 完整性与版本同步"
for key in id name version versionCode author minApiVersion targetApiVersion; do
  if grep -q "^${key}=" "$TMP/module.prop" 2>/dev/null; then
    ok "module.prop 有 $key"
  else
    bad "module.prop **缺 $key** —— squemaFQH 就只有 4 个键(minApiVersion/targetApiVersion/staticScope/exceptionMode),缺 id/name/version/versionCode/author"
  fi
done

# targetApiVersion 必须是 102,且 minApiVersion 不能低于 102
# (FanqieHook 声明 minApiVersion=101 但代码用了 @SinceApi(API_102) 的 setId() 和热重载回调)
MINAPI=$(sed -n 's/^minApiVersion=//p' "$TMP/module.prop" 2>/dev/null | tr -d '\r')
TGTAPI=$(sed -n 's/^targetApiVersion=//p' "$TMP/module.prop" 2>/dev/null | tr -d '\r')
echo "       minApiVersion=${MINAPI:-?}  targetApiVersion=${TGTAPI:-?}"
if [ "${TGTAPI:-0}" = 102 ]; then ok "targetApiVersion=102"; else bad "targetApiVersion 应为 102"; fi
if [ "${MINAPI:-0}" -ge 102 ] 2>/dev/null; then
  ok "minApiVersion>=102(与实际使用的 API 一致)"
else
  bad "minApiVersion=$MINAPI < 102 —— 若代码用了 setId()/onHotReloading/onHotReloaded 这些 @SinceApi(API_102) 的东西,在严格 101 框架上入口类会引用不存在的类型"
fi

# 版本号同步:build.gradle.kts vs module.prop
if [ -f "$BUILD_GRADLE" ]; then
  GVC=$(sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9]\+\).*/\1/p' "$BUILD_GRADLE" | head -1)
  GVN=$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$BUILD_GRADLE" | head -1)
  PVC=$(sed -n 's/^versionCode=//p' "$TMP/module.prop" | tr -d '\r')
  PVN=$(sed -n 's/^version=//p'    "$TMP/module.prop" | tr -d '\r' | sed 's/^v//')
  echo "       build.gradle.kts: versionCode=$GVC versionName=$GVN"
  echo "       module.prop:      versionCode=$PVC version=$PVN"
  [ "$GVC" = "$PVC" ] && ok "versionCode 一致" || bad "versionCode 不一致(build.gradle.kts=$GVC vs module.prop=$PVC)"
  [ "$GVN" = "$PVN" ] && ok "versionName 一致" || bad "versionName 不一致(build.gradle.kts=$GVN vs module.prop=$PVN)"
  if [ -f update.json ]; then
    grep -q "\"versionCode\"[[:space:]]*:[[:space:]]*$GVC" update.json \
      && ok "update.json versionCode 一致" \
      || warn "update.json 的 versionCode 与 $GVC 不一致(LSPosed 模块中心的更新提示会错)"
  fi
else
  skip "$BUILD_GRADLE 不存在,跳过版本同步检查"
fi

# ── 7. scope.list ──────────────────────────────────────────────
# 陷阱 #14:漏包 → LSPosed Manager 里根本不显示该目标 → 无法勾选 → hook 永不触发。
hdr "7. scope.list 覆盖两个目标"
for pkg in $REQUIRED_SCOPE; do
  if grep -qx "$pkg" "$TMP/scope.list" 2>/dev/null; then
    ok "scope.list 含 $pkg"
  else
    bad "scope.list **缺 $pkg**"
  fi
done
# android/system 条目是给 system_server 通道用的;本项目已删除该通道(squemaFQH 里它是死代码)
if grep -qxE 'android|system' "$TMP/scope.list" 2>/dev/null; then
  warn "scope.list 含 android/system 条目 —— 本项目已删除 system_server 通道,这条是多余的(squemaFQH 的死代码 SystemBootstrap 就是配它的)"
fi

# ── 8. DexKit native 库 ────────────────────────────────────────
# 陷阱 #2:DexKit 必须 implementation,否则 APK 里没有 libdexkit.so → UnsatisfiedLinkError。
hdr "8. libdexkit.so 已打包"
SO=$(unzip -l "$APK" 'lib/*/libdexkit.so' 2>/dev/null | grep -oE 'lib/[a-z0-9_-]+/libdexkit\.so' | sort -u)
if [ -z "$SO" ]; then
  bad "APK 里没有 libdexkit.so —— DexKit 是不是被写成 compileOnly 了?"
else
  echo "$SO" | sed 's/^/       /'
  ok "libdexkit.so 已打包"
  if [ "$VARIANT" = release ]; then
    # release 必须有 arm64-v8a:宿主是 arm64-only(番茄 73532/73732 的 117/116 个 .so
    # 全在 arm64-v8a 下),缺它则 libdexkit.so 在真机上根本加载不了 —— 这是 FAIL 不是 WARN。
    N=$(echo "$SO" | wc -l | tr -d ' ')
    if ! echo "$SO" | grep -q 'arm64-v8a'; then
      bad "release **缺 arm64-v8a**(实际只有:$(echo "$SO" | tr '\n' ' '))—— 宿主是 arm64-only,缺它则真机上 UnsatisfiedLinkError"
    elif [ "$N" -gt 1 ]; then
      warn "release 打了 $N 个 ABI —— 设 ndk.abiFilters += \"arm64-v8a\" 可显著减小体积"
      ok "release 含 arm64-v8a"
    else
      ok "release 只打 arm64-v8a(体积最优)"
    fi
  else
    # 测试变体:arm64-v8a 供真机手工验证,x86_64 供 CI 的 x86_64 模拟器
    echo "$SO" | grep -q 'arm64-v8a' \
      && ok "debug 含 arm64-v8a(可真机验证)" \
      || warn "debug 缺 arm64-v8a —— 无法在真机上手工验证"
    echo "$SO" | grep -q 'x86_64' \
      && ok "debug 含 x86_64(instrumented 测试可在 x86_64 模拟器上跑)" \
      || warn "debug 不含 x86_64 —— CI 的 resolver-test job 会因 UnsatisfiedLinkError 失败"
  fi
fi

# ── 9. 签名 ────────────────────────────────────────────────────
hdr "9. 签名"
if [ -n "$APKSIGNER" ]; then
  if "$APKSIGNER" verify --print-certs "$APK" > "$TMP/sig.txt" 2>&1; then
    ok "APK 已签名"
    grep -m1 'SHA-256 digest' "$TMP/sig.txt" | sed 's/^/       /'
    if [ "$VARIANT" = release ]; then
      # squemaFQH 完全没有 signingConfigs,release 产出未签名 APK
      grep -q 'Android Debug' "$TMP/sig.txt" \
        && warn "release 用的是 **debug 签名** —— 设 KEYSTORE_B64 secret 以获得稳定签名(否则用户 adb install -r 升级会失败)" \
        || ok "release 使用非 debug 签名"
    fi
  else
    bad "APK 未通过签名校验:$(head -3 "$TMP/sig.txt" | tr '\n' ' ')"
  fi
else
  skip "找不到 apksigner(装 build-tools 后可用)"
fi

# ── 10. Manifest 攻击面 ────────────────────────────────────────
# squemaFQH 导出了 3 个组件且**无 android:permission 保护**,其中 QuarkConfigProvider.call()
# 还看不到调用方包名检查 —— 设备上任何 App 都能翻它的开关。移植时不要继承这个。
hdr "10. Manifest 导出组件"
if [ "$HAVE_APKANALYZER" = 1 ]; then
  apkanalyzer manifest print "$APK" > "$TMP/manifest.xml" 2>/dev/null || true
  if [ -s "$TMP/manifest.xml" ]; then
    NPROV=$(grep -c '<provider' "$TMP/manifest.xml" || true)
    echo "       provider 数: ${NPROV:-0}"
    if [ "${NPROV:-0}" -gt 0 ]; then
      if [ -z "$PYTHON" ]; then
        skip "找不到 python3/python,无法解析 manifest 的 provider 块(这条检查只影响第 10 项)"
      else
      # 粗略检查:每个 provider 块里 exported="true" 却没有 android:permission
      "$PYTHON" - "$TMP/manifest.xml" > "$TMP/prov.txt" <<'PY'
import re, sys
x = open(sys.argv[1], encoding='utf-8', errors='replace').read()
bad = []
for m in re.finditer(r'<provider\b.*?(?:/>|</provider>)', x, re.S):
    blk = m.group(0)
    hit = re.search(r'android:name="([^"]+)"', blk)
    name = hit.group(1) if hit else '?'
    exported = 'exported="true"' in blk
    protected = re.search(r'android:(?:permission|readPermission|writePermission)=', blk)
    if exported and not protected:
        bad.append(name)
print('\n'.join(bad))
PY
      if [ -s "$TMP/prov.txt" ]; then
        while read -r p; do bad "导出 provider 无权限保护: $p —— 加 android:permission 并在 call() 里校验 getCallingPackage()"; done < "$TMP/prov.txt"
      else
        ok "无「导出且无权限保护」的 provider"
      fi
      fi
    else
      ok "无 provider(AIDL/ContentProvider 状态通道已删除,这是预期的)"
    fi
    # 目标 App 的 package visibility
    for pkg in $REQUIRED_SCOPE; do
      grep -q "$pkg" "$TMP/manifest.xml" \
        && ok "<queries> 含 $pkg" \
        || warn "<queries> 缺 $pkg —— 模块 App 侧可能无法解析目标(不影响 hook 本身)"
    done
  else
    skip "无法打印 manifest"
  fi
else
  skip "apkanalyzer 不可用"
fi

# ── 汇总 ───────────────────────────────────────────────────────
echo
echo "════════════════════════════════════════════════════════════"
printf ' %s: PASS=%d  FAIL=%d  WARN=%d  SKIP=%d\n' "$VARIANT" "$PASS" "$FAIL" "$WARN" "$SKIP"
echo "════════════════════════════════════════════════════════════"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
