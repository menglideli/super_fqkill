# GitHub Actions CI 方案

> 配套:`PLAN.md`(实施计划)、`RESEARCH.md`(调研)。
>
> 已生成的文件:
> - `.github/workflows/build.yml` — 工作流本体
> - `scripts/verify-module-apk.sh` — 模块契约校验(已过 `bash -n`,失败路径已实测)

---

## 1. 先说结论:CI 能解决什么,不能解决什么

**能彻底解决"编译环境问题"。** 你本机缺 Android SDK 且只有 JDK 11(两项目都要 17)——CI 全部代办,而且比本机更稳定:每次都是干净的 `ubuntu-latest`,不受 Windows/Git Bash 路径与编码问题影响(squemaFQH 那 13 处 mojibake `????` 就是本机编码事故的产物)。

**能解决一类更值钱的问题:静默失败。** 这类模块最恶劣的故障模式是——APK 构建成功、安装成功、LSPosed 里也勾了作用域,**但模块根本没加载,且没有任何报错**。两个上游都栽过:

| 静默失败 | 后果 | 上游证据 |
|---|---|---|
| `java_init.list` 的 FQCN 写错 | 模块永不加载,**无错误无日志** | squemaFQH 陷阱 #11 |
| R8 把入口类重命名 | 同上 | FanqieHook 靠 `proguard-rules.pro:6` 的 `-keep` 才没炸;squemaFQH 的 proguard 文件是**空模板**,只因 `minifyEnabled false` 才侥幸 |
| `libdexkit.so` 没打进 APK | `UnsatisfiedLinkError` | squemaFQH 陷阱 #2,AGENTS.md:100 明写"绝不能改成 compileOnly" |
| `java_init.list` 混入经典 `IXposedHookLoadPackage` | LSPosed Modern 模式**静默失败整个模块** | squemaFQH 陷阱 #3/#12 |
| `scope.list` 漏包 | Manager 里不显示该目标 → 无法勾选 → hook 永不触发 | squemaFQH MEMORY.md 陷阱 #4 |
| 版本号三处不同步 | 模块中心更新提示错乱 | FanqieHook 要在 `build.gradle.kts` / `module.prop` / `update.json` 三处手工同步 |

**这六类全部可以在 CI 里用 `apkanalyzer` 挡住,不需要任何设备。** 这就是 `scripts/verify-module-apk.sh` 干的事。

**不能解决"装了 ≠ 生效"。** 这是本项目最核心的风险,而 CI 只能部分缓解(见 §4 的 Tier 3)。原因很硬:要证明一条 hook 真的在番茄的调用链上,必须有 root + LSPosed + **番茄本体 APK**。而番茄 APK 是字节跳动的受版权保护分发物,**不能放进 CI、也不该在 workflow 里下载**。所以最终的实机回归(PLAN.md §7.3)仍然只能手工做,CI 替代不了。

---

## 2. CI 改变了三个技术决策

搭 CI 的过程中查出三件事,直接影响 `PLAN.md` 的结论,这里更正并说明。

### 2.1 compileSdk 定 35,不用 37 —— 最大的未知数直接消失

`PLAN.md` 阶段 0.1 把"Kotlin 2.0.21 × AGP 9.2.0 是否兼容"列为**会让方案返工的最大未知数**。这个风险是**自找的**:squemaFQH 需要 AGP 9.x **仅仅因为它选了 compileSdk 37**(其陷阱 #4:AGP 8.x 消费不了 `android-37.0` 新格式 SDK platform)。

而 **compileSdk 37 对这两个目标 App 并非必需**:FanqieHook 用 `compileSdk 35` 在番茄 73932 上工作正常,其 `app/build.gradle.kts:11-13` 的注释说明选 35 的理由是"匹配宿主声明的 compileSdkVersion=35"。更根本的是,我们的 hook 目标是**运行时反射/DexKit 解析的宿主类**,不是编译期依赖 —— compileSdk 版本对 hook 能力几乎没有影响。

**决策:采用 FanqieHook 的已知良好组合 —— AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.11.1 + compileSdk/targetSdk 35。**

收益:
- 阶段 0.1 的未知数**直接消除**(这套组合上游已经在跑)
- 不需要 `android.suppressUnsupportedCompileSdk=37`
- CI 不用装 bleeding-edge SDK,`sdkmanager "platforms;android-35"` 稳定可得
- AGP 9 升级留作后续独立事项,不阻塞主线

### 2.2 DexKit 定 2.3.0 —— 这不是版本升级,是**修 bug**

`PLAN.md` 原写"统一到 2.2.0"。查证 DexKit 上游后,**应该是 2.3.0**(GitHub releases 最新为 2.3.0;Maven Central 页面上那个"4.0.0"是 Gradle Module Metadata 的格式版本号,不是 DexKit 版本,别被骗)。

两个上游用的版本都旧,而且旧版本带着**已知缺陷**:

| 版本 | 关键变更 | 对本项目的影响 |
|---|---|---|
| **2.0.4**(FanqieHook 现用) | — | 缺下面所有修复 |
| 2.0.6 | **Support 16k pagesize** | FanqieHook 在 2.0.4 上**不支持 16KB 页大小** → 在 Android 15+ 的 16KB 页设备(新 Pixel 等)上可能直接加载失败 |
| **2.1.0** | **新增 `DexKitCacheBridge`**:按 `appTag` 复用 bridge、**查询结果缓存**、空闲自动释放 | **直接治两个上游共有的病**:squemaFQH 从不 close bridge(DexKit 官方明确警告会内存泄漏,番茄是多 dex 应用),两边都不缓存结果(每个进程启动都重扫全 dex)。⚠️ 标记为 `@DexKitExperimentalApi`,API 形态可能变 |
| **2.2.0** | **修复 `findMethod` 对"没有实际 dex 定义的方法"的错误行为** —— 以前 `findMethod { declaredClass = xxxView; name = "setVisibility" }` 会把**调用目标**误当成命中返回,即使该方法并未定义在这个类/这个 dex 里 | **这是正确性 bug,而 FanqieHook 在 2.0.4 上正带着它。** 其 `findClassImplementingInterface`(`ClassResolver.kt:423-448`)用的正是 `interfaces { add(iface) }` + `methods { add { name = m } }` 这个模式 —— 可能命中并非真实定义的方法,然后 hook 上去。**这条本身就足以论证必须升级** |
| 2.2.0 | 允许**并发访问同一个 `DexKitBridge`** + 共享调度与并发限制;新增 `allOf`/`anyOf`/`noneOf`/`not` 组合匹配器;native `.so` 体积减小 | 并发访问让"多 pack 共享一个桥"变安全;组合匹配器可以让"拒绝猜测"策略表达得更精确 |
| **2.3.0** | 新增 `accessFlags` 属性与匹配条件、`DexAccessFlags` 常量类;字符串查询优化;**对未压缩 dex 条目启用 zero-copy 加载**(APK 与 ClassLoader 内存 dex 均可) | `accessFlags` 匹配**直接替掉** squemaFQH 的 `findFirstNonAbstractMethodByName`(`HookInit.java:479-490`)那种"查完再手工跳过 abstract"的做法 —— 可以在查询里就表达。zero-copy 降低启动开销 |
| 2.3.0 | 实验性 `MethodData.usingNumbers`(读方法用到的数字字面量,保留位模式与 opcode) | 潜在能力:可以按数字常量定位。squemaFQH 全仓**从未用过** `usingNumber`(`RESEARCH.md` §4 已记录),这是它定位手段单一的一个原因 |

**行动项:**
1. 依赖统一到 `implementation("org.luckypray:dexkit:2.3.0")`
2. 桥构造统一到 `DexKitBridge.create(apkPath)`(弃用 squemaFQH 的 `create(classLoader, true)` —— 该重载在 loader 不是 `BaseDexClassLoader` 时抛 `IllegalStateException`)
3. 用 try-with-resources / `use {}` 确保 close
4. **评估 `DexKitCacheBridge`**:若能稳定工作,它同时解决"泄漏"和"每进程重扫"两个问题。但因为是 `@DexKitExperimentalApi`,建议**先不用**,把 bridge 生命周期管对即可,留作 v1.1 优化
5. 把 `ClassResolver` 的 abstract 过滤改成 `accessFlags` 查询条件(2.3.0 起可用)

### 2.3 native 库加载:上游文档印证了我们的分析

DexKit 官方 quick-start 明确写了:minSdk < 23 时 `System.loadLibrary("dexkit")` 可能抛 `UnsatisfiedLinkError`,因为打包默认压缩 `lib/` 下的 so;解决方案是 `packagingOptions { jniLibs { useLegacyPackaging true } }`,**或者手动解压 APK 里的 `libdexkit.so` 到可写目录再 `System.load()` 绝对路径**。

我们 minSdk 26(≥23),所以第一条不必需。但这**印证了 `RESEARCH.md` §5.2 的判断**:squemaFQH 那个 `nativeLibraryDir` 兜底很可能是死路(AGP 对 minSdk≥23 默认 `extractNativeLibs=false`,`nativeLibraryDir` 里没有物理文件),而 **FanqieHook 的"从自己 APK 里解压 so"正是官方文档给的第二条路**。融合后统一采用 FanqieHook 的方案。

---

## 3. 工作流结构

`.github/workflows/build.yml`,两个 job:

```
build          每次 push / PR / 手动触发     ~5-8 min(有缓存)
  ├─ checkout@v7
  ├─ gradle/actions/wrapper-validation@v6      供应链:校验 wrapper jar 的 SHA-256
  ├─ setup-java@v6 (temurin 17)
  ├─ android-actions/setup-android@v4          cmdline-tools 22.0 + 接受许可 + apkanalyzer 上 PATH
  ├─ sdkmanager platforms;android-35 build-tools;35.0.0
  ├─ gradle/actions/setup-gradle@v6            cache-provider: basic
  ├─ 解码签名 keystore(有 secret 才做)
  ├─ ./gradlew assembleDebug assembleRelease
  ├─ scripts/verify-module-apk.sh ×2           ← Tier 2 的核心
  ├─ upload-artifact@v4                        两个 APK,保留 30 天
  └─ 把体积写进 Job Summary

resolver-test  仅 main / 手动触发(PR 阶段默认不跑)   ~20-40 min
  └─ 模拟器里跑 instrumented 测试                ← Tier 3,见 §4
```

### 3.1 两个容易踩的 Actions 细节

**(a) `gradle/actions/setup-gradle@v6` 的默认缓存是专有组件。** v6.0.0 起,缓存功能被抽成 `gradle-actions-caching`,**不再是 MIT**,受 Gradle Terms of Use 约束(公开仓库免费,私有仓库需接受条款)。本项目是 GPL-3.0,CI 里混一个专有组件不合适,所以显式设 `cache-provider: 'basic'` —— 那是 v6.1.0 引入的 MIT 替代实现,基于 `@actions/cache`,缓存 `~/.gradle/caches` 和 `~/.gradle/wrapper`。代价:没有缓存清理和去重,但对这种规模的项目无所谓。

**(b) runner 自带的 SDK 组件版本不可控。** `ubuntu-latest` 确实预装了 Android SDK 并设了 `ANDROID_HOME`,但:预装的 cmdline-tools 是 **12.0**(当前是 22.0,见 actions/runner-images#14484),且 **ARM64 larger runner 根本没装 SDK**(actions/runner-images#11460)。所以不依赖预装版本,用 `android-actions/setup-android@v4` + 显式 `sdkmanager` 装我们要的两个包,保证可复现。

### 3.2 需要配的 Secrets

| Secret | 必需 | 说明 |
|---|---|---|
| `KEYSTORE_B64` | 否 | release 签名的 keystore,base64。**不配也能跑**,release 回退 debug 签名,verify 脚本第 9 项给 WARN |
| `KEYSTORE_PASSWORD` | 否 | 同上 |
| `KEYSTORE_ALIAS` | 否 | 同上 |
| `KEYSTORE_KEY_PASSWORD` | 否 | 同上 |

生成与编码:

```bash
keytool -genkeypair -v -keystore release.jks -alias module \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks        # Linux;macOS 用 base64 -i release.jks
```

**为什么值得配稳定签名:** Xposed 模块 APK 靠 `adb install -r` 覆盖升级,**签名不一致会直接失败**。squemaFQH 完全没有 `signingConfigs`,release 产未签名 APK;FanqieHook 有 env 注入的 `ci` config 并在缺失时回退 debug —— 我们沿用 FanqieHook 的模式。

`release.jks` 本身**不要提交**(两个上游的 `.gitignore` 都排除了 `*.jks`/`*.keystore`)。

---

## 4. 三层验证架构

### Tier 1 — 编译(必过,无信息量)

`assembleDebug` + `assembleRelease`。**注意 release 必须单独跑**:squemaFQH 的 `proguard-rules.pro` 是 100% 注释掉的空模板,它"安全"仅仅因为 `minifyEnabled false`。一旦开启而没补 keep 规则,**模块会在加载期静默坏掉,而 debug 构建完全正常**。所以 CI 必须构建 release 并对 release 做 Tier 2 校验。

### Tier 2 — 模块契约静态校验(`scripts/verify-module-apk.sh`)

**这是本方案性价比最高的部分。** 10 组检查,全部只需 APK 文件,不需要设备:

| # | 检查 | 挡住的事故 |
|---|---|---|
| 0 | APK 存在、`apkanalyzer` 可用 | — |
| 1 | 体积 ≤ 预算(默认 1536 KB,基线 FanqieHook 0.47 MB) | abiFilters 漏设成 4 个 ABI、minify 被关掉 |
| 2 | `META-INF/xposed/` 三件套都在 | `packaging.resources.merges` 漏配 |
| 3 | `java_init.list` **恰好 1 个**入口 + FQCN 格式合法 | 多入口各自建 DexKit 桥重扫全 dex;CRLF/空格污染 |
| 4 | **入口类在 dex 中「已定义」**(用 `apkanalyzer dex packages --defined-only`,所以不是"仅被引用") | ★ FQCN 拼错、**R8 重命名入口类** —— 全项目最致命的静默失败 |
| 5 | dex 中无 `de/robv/android/xposed` 引用 | 经典 API 混入 → LSPosed Modern 模式静默失败整个模块 |
| 6 | `module.prop` 有 `id/name/version/versionCode/author/min/target`;`targetApiVersion=102`;`minApiVersion>=102`;版本号与 `build.gradle.kts`、`update.json` **三处同步** | squemaFQH 只有 4 个键;FanqieHook 声明 101 却用了 102-only API;三处版本号手工同步 |
| 7 | `scope.list` 含两个目标;若含 `android`/`system` 给 WARN | 漏包 → Manager 不显示 → hook 永不触发 |
| 8 | `lib/arm64-v8a/libdexkit.so` 存在;release 只该有 1 个 ABI;debug 该含 `x86_64` | DexKit 被写成 compileOnly;测试变体缺 x86_64 → 模拟器上 `UnsatisfiedLinkError` |
| 9 | APK 已签名(`apksigner verify --print-certs`);release 用 debug 签名给 WARN | squemaFQH 产未签名 release |
| 10 | 无「`exported="true"` 且无 `android:permission`」的 provider;`<queries>` 含两个目标 | squemaFQH 的 `QuarkConfigProvider` 导出无保护、`call()` 无调用方检查 → 设备上任何 App 都能翻它的开关 |

**设计上的一条原则:工具不可用时给 `SKIP` 并大声说明,绝不静默 PASS。** `apkanalyzer` 找不到时,第 4 项(最有价值的那条)会退化成 SKIP 并明确告知"这会让本脚本失去主要价值,请修好"。宁可 CI 变吵,也不要假绿。

退出码:任何 FAIL → 非 0 → job 红。WARN 不阻断。

**脚本已用 fixture APK 实测(本机无 Android SDK,`apkanalyzer`/`apksigner` 走 SKIP 路径):**

| fixture | 结果 |
|---|---|
| 合规 release APK(单入口 / 完整 module.prop / 两个 scope / `lib/arm64-v8a/libdexkit.so` / 无 de.robv) | `PASS=21 FAIL=0 WARN=0 SKIP=5` → **exit 0** |
| 故意做坏的 release APK(双入口 / module.prop 只 4 键 / `minApiVersion=101` / 漏红果 / 只有 `armeabi-v7a` / dex 里有 `de/robv/android/xposed`) | `PASS=11 FAIL=10 WARN=0 SKIP=5` → **exit 1**,10 条 FAIL 全部命中预期 |
| debug 变体缺 `x86_64` | 正确给 WARN(不阻断) |
| 非 APK 垃圾输入 | exit 1,无未捕获错误 |

实测中发现并修掉了两个真 bug,记录在此以免重犯:

1. **check 5 曾经静默假 PASS。** 原实现用 `unzip -p … | strings | grep -c`,而 **Git Bash 默认没有 `strings` 命令** → 管道产出空串 → `grep -c` 得 0 → **有 `de/robv` 残留时也报 PASS**。已改成 `grep -a`(直接把二进制当文本匹配,无外部工具依赖),并在读不到 dex 时给 SKIP 而不是 PASS。这恰好是本脚本设计原则("工具不可用时给 SKIP,绝不静默 PASS")自己要防的那类错误 —— 说明这条原则必须靠实跑而不是靠自觉。
2. **check 8 的 ABI 判定把 FAIL 降级成了 WARN。** 原逻辑是"ABI 数量 >1 才警告",于是"只有 `armeabi-v7a`、根本没有 `arm64-v8a`"这种**真机上必然 `UnsatisfiedLinkError`** 的情况只拿到一条语焉不详的 WARN。已改成:release 缺 `arm64-v8a` → **FAIL**;ABI 多于 1 个 → WARN(体积);debug 变体分别检查 `arm64-v8a`(真机手工验证)与 `x86_64`(CI 模拟器)→ WARN。

> ⚠️ **仍未被实测覆盖的是 check 4、9、10** —— 它们依赖 `apkanalyzer` 和 `apksigner`,本机没有 Android SDK 所以只验证了 SKIP 降级路径,**没有验证过它们在真实 APK 上的判定是否正确**。check 4 是整个脚本最有价值的一条,首次 CI 运行时必须人工核对它的输出。这也是 `§7` 步 4 要求"拿 FanqieHook 的 release APK 试跑校准"的原因。

### Tier 3 — 模拟器 + fixture dex(真跑 ClassResolver 的 DexKit 查询)

**这是 CI 能做到的验证上限,也是我认为最值得投入的一层。**

关键洞察:**验证"语义查找能不能命中"并不需要番茄 APK,也不需要 LSPosed。** `ClassResolver` 需要的是两样东西:
1. 一个覆盖着"目标形状类"的 `ClassLoader`
2. 一个 apkPath 供 DexKit 解析 dex

这两样都能在 CI 里**自己造**:写一个极小的 fixture 模块,里面放几个模仿番茄类形状的类,编译成 dex,然后用 `DexClassLoader` 加载它、把 fixture APK 路径喂给 `ClassResolver`,断言五级查找各自命中预期的方法。

**能测到什么(价值很高):**

| 测试 | 断言 |
|---|---|
| Level 1-3(纯反射) | 精确 FQCN / 类名+方法名+参数 / 忽略参数只看返回类型,各自命中 |
| **Level 4(字段读取边)** | 造两个 fixture 变体:A 里开关是 `q0()Z` 读 `landscapeInsertAdEnable`;B 里 `q0` 改成返回 `long` 的别的配置、开关搬到 `s0()Z`。**断言 resolver 在 A 和 B 上返回同一个语义目标** —— 这正是 FanqieHook 声称的"73732→73967 原地升级零代码改动自动跟上"(`FanqieModule.kt:284-286`),但上游从未有过回归测试保护它 |
| Level 5(接口) | 实现类改名后仍命中 |
| **拒绝猜测** | 造一个有 2 个候选(都不是 static)的 fixture,断言返回 `null` + WARN,而不是随便取第一个 |
| 返回类型守卫 | 把 fixture 里的 boolean 开关改成 `int`,断言 `HookManager` skip 而不是 hook 上去 |
| DexKit 2.0.4→2.3.0 行为差异 | 特别针对 §2.2 那个 `findMethod` 误命中"未实际定义的方法"的修复,造一个 fixture 验证新行为 |

**这一层的意义:** 上游两个仓库都是**零测试**(squemaFQH AGENTS.md:28/:131 明写"没有测试、没有 lint、没有 formatter,不要假设 `test`/`lint` task 存在")。而这类模块的核心资产恰恰是那套抗混淆查找逻辑,它每次宿主升级都在被重新考验,却从来没有回归保护。Tier 3 是**把上游口口声声的"版本无关"变成可执行断言**的唯一办法。

**实施要点(有个坑必须提前说):**

- **测试变体必须带 `x86_64` abi。** GitHub runner 是 x86_64,arm64 模拟器在上面慢到不可用;但我们的 release 只打 `arm64-v8a`。所以 `abiFilters` 要按变体分开:release = `arm64-v8a`,debug/androidTest = 追加 `x86_64`。否则模拟器上 `UnsatisfiedLinkError: libdexkit.so`。verify 脚本第 8 项会分别检查这两种情况。
- **需要 KVM。** workflow 里用 udev 规则放开 `/dev/kvm`(GitHub-hosted Linux runner 的标准做法),否则模拟器只能软件渲染,必然超时。**首次运行要确认 KVM 真的可用**,这是本 job 最大的不确定点。
- **`ClassResolver` 需要一点解耦。** 它现在直接依赖 `ModuleLog`(→ `XposedModule`)和 `android.os.Build`(`ClassResolver.kt` 的 imports)。抽一个 `ResolverLog` 接口注入,并把 native 加载路径做成可跳过,才能在 instrumented 环境里干净地实例化。改动很小,但**必须先做**,否则测试写不出来。
- 模拟器 job 贵且脆,所以默认只在 `main` push 和手动触发时跑;PR 阶段注释掉 `if:` 那行即可全跑。

### Tier 4 — 模拟器 + LSPosed + 真实宿主:**不做**

明确放弃,理由三条,都是硬的:

1. **番茄/红果的 APK 是受版权保护的商业分发物。** 不能提交进仓库,也不该在 workflow 里从第三方镜像拉取。这一条本身就否掉了整个方案。
2. **LSPosed 需要 Zygisk + Magisk + 可写 system 分区。** 在 CI 里搭这套(rooted userdebug 镜像 → patch boot image → 装 Magisk → 装 LSPosed → 处理版本矩阵)是数小时级的 yak shaving,而且 LSPosed × Android × Magisk 的版本矩阵极脆,维护成本远超收益。
3. **即使搭起来,也测不到真正要测的东西。** 上游最惨的教训不是"hook 没装上",而是"hook 装上了但不在调用链上"(FanqieHook `HookManager.kt:183-191` 的原话,背后是"激励秒领"功能上线后因实机金币不涨被回滚)。这必须在真实宿主 + 真实账号 + 真实网络下观察,CI 给不了。

**所以:实机回归(PLAN.md §7.3 的 3 版本组合 × 13 项检查)仍然手工做,不可压缩。** CI 的作用是保证你**带到实机上的那个 APK 至少是个结构正确的模块**,把实机调试的时间全留给真正只有实机能回答的问题。

### 一个待查证的可能捷径

DexKit 自己的 AGENTS.md 说核心引擎"built as `dexkit_static` and linked into **both desktop and Android** targets,所以 native 改动必须在两侧都验证"。**说明 DexKit 有桌面(JVM)构建目标。**

但 Maven Central 上发布的坐标 `org.luckypray:dexkit` 是 **aar**(Android),quick-start 也只给这一个坐标。**是否存在 JVM 可直接消费的产物(独立坐标、classifier、或需要从源码构建)未查证。**

若存在 → **Tier 3 可以从模拟器降级成普通 JVM 测试**,快一个数量级、便宜得多、也不受 KVM 可用性摆布。这将是本 CI 方案里性价比最高的一次改进。

**查证方法(约 15 分钟):**
```bash
# 看 Maven Central 上该 group 下有没有非 aar 的产物或 classifier
curl -s 'https://repo1.maven.org/maven2/org/luckypray/' | grep -oE 'href="[^"]+"'
curl -s 'https://repo1.maven.org/maven2/org/luckypray/dexkit/2.3.0/' | grep -oE 'href="[^"]+"'
# 看上游仓库的 desktop 构建脚本发布到哪
git clone --depth 1 https://github.com/LuckyPray/DexKit && grep -rn "desktop\|jvm" DexKit/settings.gradle* DexKit/build.gradle* 2>/dev/null
```
若确认没有 JVM 产物,也可以考虑在 CI 里从源码构建 `dexkit_static` + JNI 包装 —— 但那是重活,只在 Tier 3 的模拟器路径被证明不可行时才值得。

---

## 5. 本地复现 CI

CI 能做的检查,本地全都能做,而且**应该在推上去之前做**(省 Actions 分钟数,反馈也快得多)。

### 5.1 补齐本机环境(Windows)

```powershell
# 1. 装 JDK 17(现在只有 Corretto 11)
winget install EclipseAdoptium.Temurin.17.JDK

# 2. 装 Android SDK cmdline-tools,然后:
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 3. 设环境变量(永久)
setx ANDROID_HOME "C:\Users\<你>\AppData\Local\Android\Sdk"
```

**Git Bash 下每次都要 `export ANDROID_HOME=...`**,否则 `:app:compileDebugJavaWithJavac` 直接失败(squemaFQH 陷阱 #5)。

### 5.2 跑完整校验

```bash
export ANDROID_HOME="/c/Users/<你>/AppData/Local/Android/Sdk"
./gradlew --no-daemon assembleDebug assembleRelease
./scripts/verify-module-apk.sh app/build/outputs/apk/release/app-release.apk release
./scripts/verify-module-apk.sh app/build/outputs/apk/debug/app-debug.apk   debug
```

`verify-module-apk.sh` 只依赖 `unzip` / `python3` / `apkanalyzer` / `apksigner`,Git Bash 里前两个有,后两个由 `ANDROID_HOME` 推导(脚本会自己 find)。**在写第一行 Kotlin 之前就可以先拿上游的 APK 试跑它** —— 把 FanqieHook 的 release APK 喂进去,看它对上游报出哪些 WARN,是个很好的校准手段。

### 5.3 用 `act` 在本地跑 workflow(可选)

```bash
# 注意:act 的默认镜像不含 Android SDK,需要自定义镜像或临时加 setup-android 步骤
act -j build -P ubuntu-latest=catthehacker/ubuntu:act-latest
```
实践中**不推荐**——Android SDK 在 act 里折腾起来比直接 push 到 CI 更慢。Tier 2 的脚本本地直接跑就够了。

---

## 6. 已知限制与风险

| 项 | 状态 |
|---|---|
| **KVM 在 GitHub-hosted runner 上是否可用** | ⚠️ **首次运行必须验证**。udev 规则那套是标准做法,但可用性随 runner 镜像变化。若不可用,Tier 3 整个 job 要去掉,只保留 Tier 1+2 |
| **`apkanalyzer dex packages --defined-only` 的输出列格式** | 脚本用 `grep -qF "$ENTRY"` 做子串匹配,**故意不依赖列布局**(该格式随 cmdline-tools 版本变化)。代价:理论上可能误匹配到同名前缀的类。实际入口 FQCN 足够长,风险可忽略 |
| **第 10 项 provider 检查是正则粗查** | 只看 `exported="true"` 与 `android:permission` 是否同时出现在同一个 `<provider>` 块里,不理解 manifest 合并与 `targetSdk` 隐含的 exported 默认值。**当 WARN 级别的提示用,不要当权威** |
| **`cache-provider: basic` 没有缓存清理** | 长期可能积累陈旧条目。规模大了再考虑换回 enhanced(公开仓库免费) |
| **wrapper-validation 需要仓库里有 `gradle/wrapper/gradle-wrapper.jar`** | 两个上游都提交了 wrapper jar,新项目也要提交(否则 `./gradlew` 在 CI 里跑不起来) |
| **`resolver-test` job 的成本** | 模拟器 job 单次 20-40 min。所以默认排除 PR。若 Actions 分钟数吃紧,改成只在 `main` 的定时 schedule(如每周一)跑 |
| **fixture dex 的保真度** | Tier 3 测的是"resolver 逻辑对不对",不是"番茄的真实混淆形状对不对"。fixture 与真实宿主的差距要靠实机回归补。**不要把 Tier 3 绿灯当成适配成功的证据** |
| **CI 无法验证 hook 是否真的生效** | 见 §1 与 Tier 4。这是结构性限制,不是配置问题 |

---

## 7. 落地顺序

| 步 | 动作 | 依赖 |
|---|---|---|
| 1 | 按 `PLAN.md` 阶段 1 建骨架(**compileSdk 35 / AGP 8.7.3 / Kotlin 2.0.21 / DexKit 2.3.0**) | — |
| 2 | 先让 `build` job 的 **Tier 1** 绿(只编译,不做校验) | 步 1 |
| 3 | 接入 `scripts/verify-module-apk.sh`,让 **Tier 2** 绿。这一步会逼你把 `module.prop` 写全(squemaFQH 只有 4 个键)、把 R8 keep 规则配对 | 步 2 |
| 4 | **拿 FanqieHook 的 release APK 试跑 verify 脚本**,校准阈值与检查项 | 步 3 |
| 5 | 配 `KEYSTORE_*` secrets,让 release 有稳定签名 | 步 3 |
| 6 | 解耦 `ClassResolver`(抽 `ResolverLog` 接口 + native 加载可跳过) | PLAN.md 阶段 2 |
| 7 | 写 fixture 模块 + instrumented 测试,开 **Tier 3**;**首次运行先确认 KVM 可用** | 步 6 |
| 8 | 查证 DexKit 是否有 JVM 产物;若有,把 Tier 3 从模拟器迁到 JVM | 步 7 |

**步 1-5 可以完全离线于实机推进**,这正好绕开你当前"没有设备也没有 SDK"的困境:CI 补齐了编译与结构校验,等你拿到实机时,手上的 APK 已经保证是个结构正确的模块,实机时间可以全部花在只有实机能回答的问题上。
