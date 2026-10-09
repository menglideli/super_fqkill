# 实机测试指南

> 本文所有日志字符串都是从代码里逐字抓出来的,不是示意。行内标注了产生它的文件。
>
> 模块信息:包名 `dev.superfqkill` · 日志 tag **`SuperFqKill`** · 状态文件 `<宿主 dataDir>/cache/superfqkill.log` · 作用域 `com.dragon.read` + `com.phoenix.read`

---

## 1. 安装

```bash
# 从 CI 的 Artifacts 下载 module-apks,或本地构建
adb install -r -t app-release.apk
```

然后在 LSPosed Manager 里:

1. 启用模块 **番茄 红果 增强**
2. 作用域勾选 **番茄小说** (`com.dragon.read`) 和 **红果免费短剧** (`com.phoenix.read`)
3. **整机重启** —— 不要用 LSPosed 的软重启

> ⚠️ 为什么必须整机重启:入口类用了双构造器兼容 LSPosed 1.9.2~2.2.0,那条路径走的是 zygote init(squemaFQH README.md:73 记录的同一件事)。
>
> ⚠️ `adb install -r` 覆盖安装后,**LSPosed 偶尔会把 `enabled` 重置为 0**(squemaFQH README.md:77)。装完请回 Manager 确认模块仍是启用状态,并强停一次目标 App。

---

## 2. 抓日志

```bash
# 主命令:清空后启动番茄,全程记录
adb logcat -c
adb logcat -s SuperFqKill > fanqie.log &
adb shell am force-stop com.dragon.read
adb shell monkey -p com.dragon.read -c android.intent.category.LAUNCHER 1
sleep 20 && kill %1

# 红果同理
adb logcat -c && adb logcat -s SuperFqKill > hongguo.log &
adb shell am force-stop com.phoenix.read
adb shell monkey -p com.phoenix.read -c android.intent.category.LAUNCHER 1
```

**如果 logcat 什么都没有**,用文件通道兜底(部分设备系统级关掉日志,root 下 `logcat -d` 也返回零行 —— 这正是 `ModuleLog` 保留三条通道的原因):

```bash
adb shell su -c 'cat /data/data/com.dragon.read/cache/superfqkill.log'
adb shell su -c 'cat /data/data/com.phoenix.read/cache/superfqkill.log'
```

---

## 3. 期望看到的日志序列

### 3.1 启动与门控

```
[INFO] onModuleLoaded process=com.dragon.read
[INFO] target ready via=packageReady package=com.dragon.read process=com.dragon.read versionCode=73932
```

`versionCode` 那一项有三种可能(`ModuleEntry.installAll`):

| 日志 | 含义 |
|---|---|
| `host version 73932 is in the audited set for com.dragon.read` | 版本在审计集内 |
| `unverified host version <N> for <pkg>; installing anyway — individual hooks that miss will be reported in the summary` | 版本没审计过,**但照常安装** |
| `host versionCode unavailable; installing anyway (gate is advisory only)` | 读不到版本号 |

> ★ **版本门是咨询性的,永不阻断安装。** 这是刻意设计:上游早期是 fail-closed,结果未知 versionCode 意味着**一条 hook 都不装**,每次宿主升级后模块看起来彻底坏了(FanqieHook CHANGELOG 0.8.1 修的就是这个"装了但完全没效果")。

### 3.2 逐条 hook 安装

每装上一条(`HookManager.installInternal`):

```
[INFO] hook installed: reader-video-ad -> com.dragon.read.component.biz.impl.NsAdImpl#canReaderVideoAdShow
```

**这行的价值在于它打印了实际命中的类与方法名** —— AdPack 里那条改成"按接口查找"的 `fullscreen-ad-depend-gate`,以及 PurifyPack 的 ⑤b 动态 id,都要靠这行才知道真正挂到了哪里。

每条 hook 首次被调用时(命中审计,默认开启):

```
[INFO] hook hit[1]: position-filter:com.dragon.read.component.biz.impl.NsAdImpl
[INFO] hook hit[2]: ...
[INFO] hook hit[3]: ...
```

之后每 200 次采样一条。

> ★ **这是全项目最重要的一行。** "静态看着对、实机不在链路上"是这类模块的致命失败模式 —— 上游 FanqieHook 的"激励秒领"功能就是静态正确、实机从未被调用,上线后因金币不涨被回滚(CHANGELOG 0.7.0/0.7.1)。**一条 hook 只有出现 `hook hit[...]` 才算真的生效。**

### 3.3 安装摘要(最关键的一行)

```
[INFO] hooks installed=36 skipped=1 known-missing=[hongguo-banner-join-revert]
```

结构是 `installed=N skipped=M`,后面按需追加:

| 后缀 | 含义 | 出现条件 |
|---|---|---|
| `lost=[...]` | **真·丢失** —— 宿主升级把目标挪走了 | 应当为空 |
| `known-missing=[...]` | 该宿主上本来就没有的 hook | 正常,见下表 |
| `failed=[...]` | 某个 category 整段抛异常中止 | 应当不出现 |
| `hook-failed=[...]` | 某条 hook 的 `module.hook()` 本身抛异常 | 应当不出现 |

紧接着还有一行结构化明细(`ModuleEntry`,DEBUG 级):

```
[DEBUG] install summary detail: InstallSummary(installed=36, skipped=1, lost=[], knownMissing=[...], hooks=[HookRecord(id=..., outcome=INSTALLED, strategy=..., hits=1234), ...])
```

**这一行里有每条 hook 的 `hits` 计数**,是在没有 UI 详情页的情况下唯一的逐条命中数据。

### 3.4 装机 Toast(可见确认)

hook 装完后约 4 秒,宿主界面上会弹一条:

```
番茄红果增强:番茄 hook 完成(installed=36 skipped=1)
```

对应日志:

```
[INFO] hook toast shown: 番茄红果增强:番茄 hook 完成(installed=36 skipped=1) [via=packageReady]
```

**开关与延迟**都在 `ModuleEntry.kt` 的 companion object 里,改一个常量重新构建即可:

| 常量 | 默认 | 说明 |
|---|---|---|
| `SHOW_HOOK_TOAST` | `true` | 关掉整条 Toast |
| `TOAST_DELAY_MS` | `4000` | 延迟毫秒数 |

**为什么必须延迟:** `onPackageReady` 时宿主的 Application **还没创建** —— 上游实测过 `ActivityThread.currentApplication()` 在那个阶段返回 null(这正是 `ApkVersion` 那个手写 AXML 解析器存在的原因)。没有 Context 就没有 Toast,所以 post 到主线程并延迟,等宿主 UI 起来再取。

若日志出现:

```
[WARN] hook toast 跳过:延迟 4000ms 后仍取不到宿主 Context (ActivityThread.currentApplication() == null)。hook 本身不受影响。
```

说明宿主冷启动比 4 秒还慢(DexKit 全 dex 扫描占用启动路径),把 `TOAST_DELAY_MS` 调大即可。**这条 WARN 不代表 hook 失败** —— Toast 的调度全程 try/catch,任何失败都不影响安装。

> ⚠️ **Toast 只证明"安装流程跑完了",不证明任何一条 hook 真的在调用链上。** 后者只有 §3.2 的 `hook hit[...]` 能证明。
>
> 上游 squemaFQH 的 README.md:74 曾声称首启会弹 `番茄红果 VIP Hook 成功`,但它仓库唯一的 commit `3f8e69a`("chore: drop all automatic Toast notifications from hook entries")就把所有 Toast 删了,文档没跟着改 —— 那是它 17 处文档失实之一。我们把提示加回来,但措辞上只声称"装完了"。

---

## 4. 验收基线

### 4.1 判定标准

> ★ **钉 `lost=[]` 和 `known-missing` 集合,不要钉 `installed` 的具体数字。**

原因:PurifyPack 的 ⑤b 会按"持有 `CommentUserStrInfo` 字段的类"动态生成 hook id,数量 K 取决于真实 APK,`0 ≤ K ≤ 32`,无法预先钉死。而 ③ 是一次"拒绝猜测"的全 dex 按名扫描 —— **若 `canThisPositionShow` 在真机上存在于多个类,它会按设计落进 `lost`,那是正确行为而不是回归**(宁可不 hook 也不 hook 错)。

### 4.2 期望值

| 宿主 | 尝试总数 | `known-missing`(确定性) | 验收 |
|---|---|---|---|
| 番茄 73932 | `37 + K` | `[hongguo-banner-join-revert]` | **`lost=[]`** |
| 红果 73932 | `34 + K` | `[purify-search-ai-float-button, purify-search-ai-banner-entry, purify-search-ai-box-entry]` | **`lost=[]`** |

最好情况:番茄 `installed=36+K skipped=1`,红果 `installed=31+K skipped=3`。

**只装 AdPack 时**的上游基线(可用于隔离问题):番茄 `installed=32 skipped=1`,红果 `installed=30 skipped=3`。

### 4.3 K 怎么读

```
[INFO] purify ⑤b: 找到 3 个持有类(上限 8)
[INFO] purify ⑤b installed: classes=2 methods=5 (holders found=3)
```

`methods=` 后面的数字就是 K。

> 这条日志是刻意加的:上游的 ⑤b 对**所有**持有类的**所有**自类型参数方法无上限安装,是热路径上的宽拦截,而它的 README 完全没警告爆炸半径。这里加了双上限(8 个类 / 每类 4 个方法)并把实测计数打出来,**第一次装机请先看清这个数字**,再决定上限是否要调。

### 4.4 等式核对

```
installed + skipped == 尝试总数            (正常)
installed + skipped + hook-failed == 尝试总数   (若出现 hook-failed)
```

---

## 5. 屏幕上应该看到的现象

### 5.1 广告(AdPack,33 条)

打开番茄小说,依次走一遍:

| 场景 | 期望 |
|---|---|
| 冷启动 | **无开屏广告**(品牌/IMC/自然量三种都被 void 空实现拦掉) |
| 首页 | 无 TopView、无信息流广告 |
| 进阅读器 | 底部 banner、翻页信息流、章节断开处广告、文字链接**全部消失** |
| 阅读器内视频广告 | 不出现 |
| 听书 | 无信息流广告、无贴片广告 |
| 搜索页 | AI 悬浮按钮 / banner 入口 / 搜索框入口**消失**(仅番茄;红果本来就没有) |
| VIP 购买入口 | 隐藏(`hide-vip-entrance` / `hide-vip-entrance-in-ad`,纯装饰性) |

红果免费短剧:

| 场景 | 期望 |
|---|---|
| 短剧播放中暂停 | **无暂停广告** |
| 短剧 banner | 消失 |
| 横屏播放 | 无插入广告 |
| 多集信息流 | 无广告 |

### 5.2 界面净化(PurifyPack)

| 功能 | 在哪看 | 期望 |
|---|---|---|
| ⑤ 昵称 | 个人页 / 评论区自己的用户名 | 显示为 **云朵**(或你在 `PurifyConfig.NICKNAME` 里改的值) |
| ④ 社交数字 | 个人主页 | 关注 **5200000** / 粉丝 **13140000** / 获赞 **9990000** |
| ⑥ 推荐用户 | 个人页的"推荐用户"区 | **整个列表为空** |
| ③ 个人页推广位 | 个人页的 VIP 推广条 | **文案为空**(渲染成一条空 banner) |

> 这四项**只改本地显示**,不影响服务端:别人看你的主页看到的仍是真实数据。

**改这些值:** 编辑 `app/src/main/java/dev/superfqkill/packs/PurifyConfig.kt` 一个文件,重新构建即可。任意一项置 `null` 就单独关闭该功能。

### 5.3 ★ 会失效的东西(设计使然,不是 bug)

以下入口点下去**没有广告可播,因此不生效**:

- 看视频得金币
- 看广告免广告
- 看视频催更
- 听书激励
- 看视频解锁章节

原因:AdPack 的零广告闸门对宿主的中央查询 `checkAdAvailable(String position, String source)` **无条件返回 false**,不区分被动广告位和用户主动的激励位。这样做的收益是**宿主以后新增的广告位同样自动被拦**(实证:红果 73932 新增 23 个广告类含一个全新的 `IDrawRewardAdService`,零登记全自动拦住)。

**发奖是服务端校验的,本地拦广告不会让金币或权益白得。** 这一点两个上游都明确披露过,FanqieHook 还为此上线又回滚过一个"激励秒领"功能。

### 5.4 状态面板

打开模块 App(桌面图标"番茄 红果 增强"):

- 两张卡片:番茄小说 / 红果免费短剧
- 每张显示:名称、包名、chip(**LIVE** / **SCOPE** / **IDLE**)、已安装的宿主版本号
- chip 含义:`IDLE` = LSPosed 里没勾作用域;`SCOPE` = 勾了但目标进程没在跑;`LIVE` = 勾了且主进程在跑
- 每张卡片下方有可直接复制的 logcat / cat 命令
- 底部有一条金色边框的提示,说明激励入口会失效(即 §5.3)

> `LIVE` 依赖 `XposedService.getRunningTargets()`,这是 `libxposed:service` **102.0.0** 才有的方法。这正是我们没有退回 101.0.0 的原因 —— squemaFQH 用 101,它的反射探测永远 `NoSuchMethodException`,所以番茄/红果卡片**永远只显示 SCOPE**,LIVE 灯点不亮。
>
> **请特别确认这个 chip 能不能变成 LIVE** —— 上游两个仓库都没做到这件事。

---

## 6. 出问题怎么定位

### 6.1 完全没有日志

| 检查 | 命令 |
|---|---|
| 模块是否被 LSPosed 认出来 | Manager 里能否看到"番茄 红果 增强" |
| 作用域是否勾了 | Manager → 模块 → 作用域 |
| `enabled` 是否被重置 | 覆盖安装后常见,重新勾选 + 强停目标 App |
| 是否整机重启过 | 软重启无效 |
| `java_init.list` 的 FQCN | 必须是 `dev.superfqkill.ModuleEntry`(CI 的 check 4 已验证它在 release dex 中定义) |

### 6.2 有 `onModuleLoaded` 但没有 `target ready`

门控挡掉了。看这三行哪一条出现:

```
[DEBUG] not first package: <pkg>, skip                 ← 门控 2
[INFO]  skip non-main process: <pkg>:push (package=…)  ← 门控 3(正常,:push 等子进程本就该跳过)
(无任何输出)                                            ← 门控 1:包名不在 TARGET_PACKAGES
```

**只有主进程(进程名 == 包名)才会安装 hook**,这是刻意的:squemaFQH 没有进程过滤,于是多进程宿主每起一个进程就在启动路径上重扫一遍全 dex。

### 6.3 `lost=[...]` 非空

宿主升级把目标挪走了。逐个 id 处理:

1. 先看它前面的定位日志。DexKit 查找会打印候选:
   ```
   [INFO] using-string lookup called: 引用 "doSyncInitUserInfo:%s"
   [INFO] using-string lookup raw hit count for …: 0
   [WARN] using-string lookup found nothing for …
   ```
2. 若是 `refusing to guess` / `ambiguous`,说明命中了多个 —— **这是保护机制生效**,不是 bug。按提示传 `className=` 收窄。
3. 若是 `field-scoped lookup found no no-arg boolean getter reading …`,说明字段名或方法形状变了,需要重新审计该 hook 的目标。

`ClassResolver` 的诊断会列出同类下同参数数量的最多 12 个方法名,**按与目标名的公共前缀长度降序**排 —— 所以 `disableAdGift` → `disableAdGiftV2` 这种改名会排在第一个,读一行日志就能定位,不用重新审整个 dex。

### 6.4 `hook-failed=[...]` 出现

`module.hook()` 本身抛了异常。往前找这一行拿到完整堆栈:

```
[ERROR] hook install failed: <id> (<类>#<方法>)
```

### 6.5 装了但没效果(**最危险的一类**)

`installed` 数字正常、`lost=[]`,但广告还在 —— 说明 hook **装上了却不在调用链上**。

**唯一可靠的判据是 `hook hit[...]`。** 用 §2 的命令抓一段真实使用(进阅读器翻几页),然后:

```bash
grep "hook hit" fanqie.log | sed 's/.*hook hit\[[0-9]*\]: //' | sort | uniq -c | sort -rn
```

**没出现在这个列表里的 id,就是装了但从未被调用的。** 常见原因是 ART 把目标方法内联到了调用点 —— AdPack 对 14 条 hook 传了 `deoptimize=true`,但 `deoptimize` 的语义本身存疑(`HookManager` 的注释说"force callers to not inline the callee",而 libxposed javadoc 是以**调用方**为框的:"To force A to call the hooked B, you can deoptimize A")。若确认是内联问题,需要改成 deopt 调用方而不是被 hook 的方法。

### 6.6 PurifyPack 的字段写入失败

上游把反射失败**完全静默吞掉**(`catch (ReflectiveOperationException ignored)`),字段名写错是隐形的 —— 它的 ④ 就有一个 `recDiggNum` vs `recvDiggNum` 的疑点从未被发现。我们改成一律记日志:

```
[WARN] purify-social-stats: <类名> 上没有字段 followUserNum,跳过该字段
[INFO] purify-social-stats: 获赞字段实际生效的是 recvDiggNum(候选 [recvDiggNum, recDiggNum])
```

**请特别抓一下第二行** —— 它会告诉我们上游那个疑点里哪个字段名才是对的,然后把 `PurifyConfig.DIGG_FIELD_CANDIDATES` 里另一个删掉。

---

## 7. 热重载(可选,但很有价值)

如果 LSPosed 支持 service 触发热重载,这是**验证我们修的三个缺陷的唯一途径**:

```bash
adb logcat -c && adb logcat -s SuperFqKill > hotreload.log
# 在 LSPosed Manager 里触发热重载,或更新模块 APK(autoHotReload=false,所以更新不会自动触发)
```

期望看到:

```
[INFO] onHotReloading: 本代装有 36 条 hook,安装目标=com.dragon.read
[INFO] onHotReloaded: 摘除上一代 36 条 hook
[INFO] onHotReloaded: 为 com.dragon.read 重装 hook
[INFO] target ready via=hotReload package=com.dragon.read process=com.dragon.read versionCode=73932
[INFO] hooks installed=36 skipped=1 known-missing=[...]      ← 与热重载前一致
```

**重点 grep 这两条**(它们会证明或否证 `libdexkit.so` 的跨世代修复):

```bash
grep -E "DexKit native lib|DexKit init failed|No implementation found" hotreload.log
```

| 看到 | 含义 |
|---|---|
| `DexKit native lib loaded from …/libdexkit-arm64-v8a-<令牌>.so (abi=…, generation=<令牌>)` 且**两代令牌不同** | ✅ 修复生效,新一代映射了自己的一份 |
| `DexKit native lib 路径被另一个 classloader 占用 …… 本世代 native 未绑定` | ❌ 令牌没起作用,DexKit 在新世代降级 |
| `DexKit init failed` / `No implementation found for … nativeInitDexKit` | ❌ 同上,后果是 **PurifyPack 五条全部失效** |

**为什么这条最值得测:** 一个 native 库只能被一个 classloader 加载。热重载后新一代用的是新模块 classloader,若沿用固定文件名就会撞上 ART 的 "already loaded in another classloader",而**新世代的 JNI native 并没有绑定**。上游把 `contains("already")` 一律当成功,于是这个失效是完全静默的。这是假框架测不出来的部分 —— 只能在真 ART 上验证。

同时确认:热重载后**广告仍然是被拦住的**(即 hook 数量没有翻倍也没有归零)。

---

## 8. 尚未被任何验证覆盖的部分

诚实清单 —— 这些只有实机能回答:

| 项 | 状态 |
|---|---|
| 任何一条 hook 是否真的在调用链上 | ❌ 未验证(假框架只证明记账正确,不证明 ART hooking) |
| `getRunningTargets()` 在你的 LSPosed 上是否真的返回宿主主进程 | ❌ 未验证 —— **这就是 LIVE 灯能否点亮的关键** |
| `libdexkit.so` 跨世代加载 | ❌ 未验证(§7)。机制在 HotSpot 上验证过,ART 上没有 |
| `ActivityThread.currentApplication()` 在热重载时是否非 null | ❌ 未验证(隐藏 API;上游只在 `onPackageReady` 阶段实测过它返回 null) |
| SELinux 是否允许从 `<dataDir>/cache/superfqkill/` 执行 `System.load` | ❌ 未验证(上游的策略 2 也从没在设备上证明过) |
| ⑤b 的真实爆炸半径(K 值) | ❌ 未验证 |
| ③ 的按名扫描在真机上是否唯一 | ❌ 未验证 |
| ④ 的获赞字段名到底是 `recvDiggNum` 还是 `recDiggNum` | ❌ 未验证(§6.6) |
| ⑥ 的 `args[0] = null` 是否安全(若该参数是基元类型会在链内 NPE) | ❌ 未验证。`ExceptionMode.PROTECTIVE` 会兜住宿主不崩,但那条 hook 会失效 |
| 启动耗时开销 | ❌ 未测量。DexKit 全 dex 扫描在启动路径上,squemaFQH 完全没有计时插桩,这是它的盲区之一 |

**建议顺手测启动耗时:**

```bash
for i in 1 2 3; do adb shell am force-stop com.dragon.read; sleep 2;
  adb shell am start -W -n com.dragon.read/.SplashActivity | grep -E "TotalTime|WaitTime"; done
```
装模块前后各测三次对比。
