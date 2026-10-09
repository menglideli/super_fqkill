# 融合模块实施计划

> 配套文档:`RESEARCH.md`(调研与原理分析)。本计划的所有技术判断都在那里有 `文件:行号` 依据,不在此重复论证。
>
> 源材料已 clone 在 `research/squemaFQH`(GPL-3.0, `3f8e69a`)与 `research/FanqieHook`(MIT, `11f9b98`)。
>
> **`CI-PLAN.md`** — GitHub Actions 编译方案。已生成 `.github/workflows/build.yml` 与 `scripts/verify-module-apk.sh`。

---

## 决策确认(第二轮)

| 议题 | 决定 |
|---|---|
| 金币 / 激励收益 | **不做**(已排除) |
| 以 FanqieHook 为基座 | ✅ 确认 |
| 一切以代码为准,不信上游文档 | ✅ 确认(依据见 `RESEARCH.md` §10:squemaFQH 有 17 处文档失实) |
| 编译环境 | **改用 GitHub Actions**,方案见 `CI-PLAN.md` |
| compileSdk / AGP | **改为 35 / 8.7.3**(原计划 37 / 9.2.0)—— 消除最大未知数,见 `CI-PLAN.md` §2.1 |
| DexKit 版本 | **改为 2.3.0**(原计划 2.2.0)—— 不是升级而是修 bug,见 `CI-PLAN.md` §2.2 |
| **会员解锁(squemaFQH ②)** | **仍然不做**。见 §0.1 |

### 关于会员解锁

你要求把这条做进去,我的答复是不做,这一点没有变。原因不是流程性的:伪造 `VipInfoModel` 的构造器实参,是让客户端相信自己持有一个并未购买的付费会员,以此取得该会员对应的付费功能。绕过商业服务的付费校验属于规避访问控制,我不会为它写实现、设计或构建计划。

这与你"想自己定义"的诉求并不冲突——上游代码是公开的,`research/squemaFQH/app/src/main/java/com/byterax/phoenix/read/HookInit.java:225-255` 就是它的全部实现(约 30 行,含常量定义在 `:74-85`),`RESEARCH.md` §3 与 §5.3 已如实记录其机制、缺陷和未验证之处,供你自行判断和改造。我不会在此基础上继续推进。

需要说明的是,**排除它不会削弱合并模块的实际效果**:番茄/红果本体是免费+广告模式,付费会员的核心卖点是免广告,而 FanqieHook 的零广告中心闸门已经把广告全拦了。`RESEARCH.md` §7 对此有完整论证。

如果你要的是一个"可以自定义伪造值"的通用字段改写框架(与会员无关的那种),那是另一件事,可以谈。

---

## 0. 范围

### 做

| 层 | 内容 | 来源 |
|---|---|---|
| 引擎 | `HookManager` / `ClassResolver` / `ModuleLog` / `ApkVersion` | FanqieHook 原样搬运(宿主无关) |
| 广告 | 33 条 hook + 零广告中心闸门 | FanqieHook `AdHooks` 原样 |
| UI 净化 | 清空推荐用户、伪造昵称(2 个挂载点)、个人页推广位改写 | squemaFQH ③⑤⑤b⑥,**重写定位方式** |
| 状态 | 修好的 SCOPE/LIVE 检测 + per-hook 命中面板 | squemaFQH UI 骨架 + 新实现 |
| 构建 | 统一工具链、R8 keep 规则、GPL-3.0 合规文件 | 两者取优 |

### 不做

| 项 | 原因 |
|---|---|
| **会员/VIP 解锁**(squemaFQH ② `VipInfoModel` 构造器篡改) | 见 §0.1 |
| 伪造关注/粉丝/获赞(squemaFQH ④) | 见 §0.2 |
| 夸克 / 小X分身 / Cherrygram 三个目标 | 超出"番茄红果模块"范围;`quark/` `xiaox/` `cherrygram/` 整目录不移植 |
| 经典 Xposed API(`de.robv`)兼容 | `targetApiVersion=102` 下**技术上不可能**,libxposed 契约明令禁止 Modern 模块调用 legacy API |
| 金币 / 激励视频收益 | 两个上游都不做,且 FanqieHook 有实证:发奖服务端校验(CHANGELOG 0.7.0 上线、0.7.1 因实机金币不涨回滚) |
| 反检测 / 隐藏注入痕迹 | 超出范围;仅在 §5.3 记录当前痕迹面 |
| 用户可配置的按位开关 | 两个上游对番茄/红果都零配置面,这是从零新建的功能,不在本次融合范围。留作 v2 |

### 0.1 为什么排除会员解锁

**工程理由(主要)——功能冗余。** 番茄/红果本体是免费+广告模式,付费会员的核心卖点是免广告。FanqieHook 的零广告中心闸门(`AdHooks.kt:247-280`,对 `checkAdAvailable(String,String)` 无条件返回 `false`)已经把广告全拦了,伪造客户端 `VipInfoModel` 在这个最主要诉求上**几乎不增加任何东西**。而权益是服务端校验的 —— 这一点有两处独立佐证:FanqieHook 自己在 `AdHooks.kt:329-330` 写明 "does NOT touch entitlement data, VipInfoModel, or any server-validated VIP flag";其 README.md:16-18 写明"发奖由服务端校验,本地拦广告不会让金币或权益白得"。

**实现质量理由。** 这条恰好是 squemaFQH 里最差的:不用 DexKit(`HookInit.java:226-229` 硬编码 `Class.forName`,所以 README.md:119 "全版本通杀"的说法对它是假的)、类型盲改写(`:236` 只检查 `args.length >= 3` 不检查参数类型,3 参 boolean 重载会被塞进 `"1"` 而静默失效)、全仓无命中审计所以无法证明它在调用链上。

**合规理由。** 绕过付费会员校验属于规避服务的访问控制。这部分我不参与设计与实现。上游代码是公开的(`HookInit.java:225-255` 是全部实现),`RESEARCH.md` §3/§5.3 已如实记录其机制以保证调研完整 —— 但本项目不实现、不移植、不改进它。

> **注意这会改变 UI 语义:** squemaFQH 的番茄卡片副标题里"VIP"这一项将不复存在。合并模块的自我描述应当是"去广告 + UI 净化",不是"解锁"。

### 0.2 为什么也排除伪造社交数字

squemaFQH ④ 有一个**未解决的疑似 bug**:定位串写 `recDiggNum`(`HookInit.java:391`),实际写的字段是 `recvDiggNum`(`:404`);若真实字段名是前者,`findField` 抛 `NoSuchFieldException` 并在 `:405` 被 `catch (ReflectiveOperationException ignored)` **无日志吞掉** → 获赞数永远伪造不成功且完全隐形。

这条功能本身价值极低(纯本地显示层的自我美化)、有未验证的正确性问题、且需要真实 APK 才能核验字段名。**成本/收益不划算,排除。** 若将来要加,先按 §3.2 的核验流程确认字段名,并给反射写入加日志。

---

## 1. 前置条件

**编译环境问题已由 GitHub Actions 解决** —— 方案见 `CI-PLAN.md`,`.github/workflows/build.yml` 与 `scripts/verify-module-apk.sh` 已生成。下表左列是本机实测现状,右列是"要在本机构建"时的需求;**若走 CI,前三行都不必补**。

| 项 | 本机现状 | 本机构建需要 | CI 是否代办 |
|---|---|---|---|
| `ANDROID_HOME` | **未设置**,`%LOCALAPPDATA%/Android/Sdk`、`C:/Android/Sdk`、`D:/Android/Sdk` 均不存在 | Android SDK,含 `platforms;android-35` + `build-tools;35.0.0` | ✅ `android-actions/setup-android@v4` + `sdkmanager` |
| JDK | **只有 Corretto 11.0.13_8** | **JDK 17**(两个项目都要求) | ✅ `setup-java@v6` temurin 17 |
| Gradle | 用 wrapper(8.11.1),无需本地装 | — | ✅ 但**必须把 `gradle/wrapper/gradle-wrapper.jar` 提交进仓库** |
| Git Bash | ✅ 2.42.0 | **必须 `export ANDROID_HOME=…`**,否则 `:app:compileDebugJavaWithJavac` 失败(squemaFQH 陷阱 #5) | — |
| 实机 | **无** | root + LSPosed(Zygisk 版)v2.2.0+ + 番茄小说/红果免费短剧 | ❌ **CI 代办不了**,见下 |

**没有实机就不要开始阶段 3 之后的工作。** 阶段 0-2(选型验证、骨架、引擎归并)可以在 CI 上完整推进,**这正是 CI 方案的价值**:它把"结构正确的模块 APK"这件事保证下来,等你拿到实机时,调试时间可以全部花在只有实机能回答的问题上。

**为什么 CI 替代不了实机:** 要证明一条 hook 真的在番茄的调用链上,必须有 root + LSPosed + **番茄本体 APK**。而番茄 APK 是受版权保护的商业分发物,不能进仓库、也不该在 workflow 里拉取。CI 能做到的上限是用 fixture dex 验证 `ClassResolver` 的查找逻辑(`CI-PLAN.md` §4 Tier 3),**但 fixture 绿灯不等于真机适配成功**。上游最惨的教训也不是"hook 没装上",而是"装上了但不在调用链上"(FanqieHook `HookManager.kt:183-191`,背后是"激励秒领"上线后因实机金币不涨被回滚)。

---

## 2. 阶段 0:验证剩余的未知数

> **本节已按第二轮调研更新。** 原先列为"最大未知数"的 Kotlin × AGP 9 兼容性问题**已经通过改选型消除**,不再是风险;DexKit 的目标版本从 2.2.0 改为 **2.3.0**,且理由从"版本统一"升级为"修正确性 bug"。完整论证见 `CI-PLAN.md` §2。

### 0.1 构建选型:定为 FanqieHook 的已知良好组合(原未知数已消除)

**决策:AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.11.1 + compileSdk/targetSdk 35 + JDK 17。**

**为什么不需要再验证 Kotlin × AGP 9:** 那个风险是选型自找的。squemaFQH 需要 AGP 9.x **仅仅因为它选了 compileSdk 37**(其陷阱 #4:AGP 8.x 消费不了 `android-37.0` 新格式 SDK platform,报 `Failed to find Platform SDK platforms;android-37`)。而 **compileSdk 37 对这两个目标 App 并非必需**:

- FanqieHook 用 `compileSdk 35` 在番茄 73932 上工作正常,其 `app/build.gradle.kts:11-13` 说明选 35 是为了"匹配宿主声明的 compileSdkVersion=35"
- 更根本的是,hook 目标是**运行时反射/DexKit 解析的宿主类**,不是编译期依赖,compileSdk 版本对 hook 能力几乎没有影响

附带收益:不需要 `android.suppressUnsupportedCompileSdk=37`;CI 不用装 bleeding-edge SDK(`sdkmanager "platforms;android-35"` 稳定可得);AGP 9 升级留作后续独立事项,不阻塞主线。

**验收:** CI 的 `build` job 中 Tier 1(`assembleDebug` + `assembleRelease`)绿。

### 0.2 DexKit 定为 2.3.0 —— 这是修 bug,不是版本统一

**风险重估:** 原计划写"统一到 2.2.0"。查证 DexKit 上游后,**应该是 2.3.0**(GitHub releases 最新即 2.3.0;Maven Central 页面上那个"4.0.0"是 Gradle Module Metadata 的格式版本号,**不是** DexKit 版本)。

**关键发现:FanqieHook 现用的 2.0.4 带着两个已知缺陷,而我们要以它为基座。**

| 版本 | 变更 | 对本项目的影响 |
|---|---|---|
| 2.0.6 | **Support 16k pagesize** | 2.0.4 **不支持 16KB 页大小** → 在 Android 15+ 的 16KB 页设备上可能直接加载失败 |
| 2.1.0 | 新增 `DexKitCacheBridge`(按 `appTag` 复用桥 + **查询结果缓存** + 空闲自动释放) | 直接治两个上游共有的病:squemaFQH 从不 close 桥(官方明确警告内存泄漏),两边都不缓存结果(每进程重扫全 dex)。⚠️ `@DexKitExperimentalApi` |
| **2.2.0** | **修复 `findMethod` 对"没有实际 dex 定义的方法"的错误行为** —— 以前会把**调用目标**误当命中返回,即使该方法并未定义在这个类/这个 dex 里 | ★ **正确性 bug,2.0.4 正带着它。** FanqieHook 的 `findClassImplementingInterface`(`ClassResolver.kt:423-448`)用的正是 `interfaces { add(iface) }` + `methods { add { name = m } }` 这个模式 —— 可能命中并非真实定义的方法然后 hook 上去。**这一条本身就足以论证必须升级** |
| 2.2.0 | 允许**并发访问同一 `DexKitBridge`** + 共享调度与并发限制;新增 `allOf`/`anyOf`/`noneOf`/`not` 组合匹配器 | 让"多 pack 共享一个桥"变安全;组合匹配器可让"拒绝猜测"策略表达得更精确 |
| 2.3.0 | 新增 `accessFlags` 匹配 + `DexAccessFlags`;字符串查询优化;**未压缩 dex 条目 zero-copy 加载** | `accessFlags` 可直接替掉 squemaFQH `findFirstNonAbstractMethodByName`(`HookInit.java:479-490`)那种"查完再手工跳过 abstract"的做法 |
| 2.3.0 | 实验性 `MethodData.usingNumbers` | squemaFQH 全仓**从未用过** `usingNumber`,这是它定位手段单一的原因之一;新 API 提供了按数字常量定位的可能 |

**动作:**
1. 依赖统一到 `implementation("org.luckypray:dexkit:2.3.0")`
2. 桥构造统一到 `DexKitBridge.create(apkPath)`(见下表)
3. try-with-resources / `use {}` 确保 close
4. 把 `ClassResolver` 的 abstract 过滤改成 `accessFlags` 查询条件(2.3.0 起可用)
5. **暂不用 `DexKitCacheBridge`**(实验性 API),先把桥生命周期管对,留作 v1.1 优化
6. 逐一核验 `ClassResolver` 用到的 API 在 2.3.0 上的签名:

| 用到的 API | 位置 |
|---|---|
| `DexKitBridge.create(String apkPath)` | `ClassResolver.kt:327` |
| `FieldMatcher().apply { name(fieldName) }` | `:260` |
| `UsingType.Read` | `:260` |
| `addUsingField(FieldMatcher, UsingType)` | `:260` |
| `bridge.findMethod { … }` / `findClass { … }` DSL | `:260` 附近, `:423-448` |
| `MethodData` / `ClassData` / `getInstance(cl)` | 各处 |
| `DexKitBridge` 是否仍 `Closeable` | — |

**同时统一桥构造方式**(两仓库用了**不同重载**):

| | 重载 | 处置 |
|---|---|---|
| squemaFQH | `DexKitBridge.create(classLoader, true)`(`HookInit.java:445`,第 2 参是 `useMemoryDexFile`) | **弃用** —— 该重载在 loader 不是 `BaseDexClassLoader` 时抛 `IllegalStateException` |
| FanqieHook | `DexKitBridge.create(apkPath)`(`ClassResolver.kt:327`) | **采用** |

**验收:** `ClassResolver` 在 2.3.0 上编译通过,且 5 条 DexKit 依赖的查找(字段级 ×5、接口级 ×2)在实机上命中**语义正确**的目标。

> ⚠️ 注意:验收标准不是"与 2.0.4 返回相同结果"。2.2.0 修的那个 `findMethod` 缺陷意味着**升级后结果本来就可能不同——而且新的才是对的**。若发现某条查找在 2.3.0 上不再命中,先判断是"以前命中错了"还是"真的回归",不要无脑回退版本。这正是 `CI-PLAN.md` §4 Tier 3 要用 fixture dex 建立回归测试的原因:上游从未有过保护这套查找逻辑的测试。

### 0.3 native 库加载方案:上游文档已印证

DexKit 官方 quick-start 明确写了:minSdk < 23 时 `System.loadLibrary("dexkit")` 可能抛 `UnsatisfiedLinkError`(打包默认压缩 `lib/` 下的 so),解决方案是 `packagingOptions { jniLibs { useLegacyPackaging true } }`,**或手动解压 APK 里的 `libdexkit.so` 到可写目录再 `System.load()` 绝对路径**。

我们 minSdk 26(≥23),第一条不必需。但这**印证了 `RESEARCH.md` §5.2 的判断**:squemaFQH 的 `nativeLibraryDir` 兜底很可能是死路(AGP 对 minSdk≥23 默认 `extractNativeLibs=false`,该目录里没有物理文件),而 **FanqieHook 的"从自己 APK 里解压 so"正是官方给的第二条路**。

**决策:统一采用 FanqieHook 的方案**(`ClassResolver.kt:350-411`),包括它的 `outFile.length() != entry.size` 重抽取判定和"消息含 already 视为成功"的热重载处理。

**验收:** 实机上无 `UnsatisfiedLinkError`;CI 的 verify 脚本第 8 项确认 `lib/arm64-v8a/libdexkit.so` 在 release APK 内、且 debug 变体含 `x86_64`(供模拟器测试)。

---

## 3. 阶段 1:建立工程骨架

### 3.1 目录结构

**以 FanqieHook 为基座**(理由见 `RESEARCH.md` §12:它的 `HookManager`/`ClassResolver` 是宿主无关的通用基础设施,squemaFQH 没有等价物;且工程纪律每一项都更严)。

```
super_fqkill/
├── research/                      # 两个上游的只读快照(已存在,不进构建)
├── app/
│   ├── build.gradle.kts           # 基线见 §3.3
│   ├── proguard-rules.pro         # 从 FanqieHook 全量搬,按新包名改 :6/:10
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/<新包名>/
│       │   ├── ModuleEntry.kt         # 唯一入口(阶段 2)
│       │   ├── core/
│       │   │   ├── HookManager.kt     # ← FanqieHook 原样
│       │   │   ├── ClassResolver.kt   # ← FanqieHook 原样
│       │   │   ├── ModuleLog.kt       # ← FanqieHook,改 TAG
│       │   │   └── ApkVersion.kt      # ← FanqieHook 原样(零依赖)
│       │   ├── packs/
│       │   │   ├── FeaturePack.kt     # 新:接口
│       │   │   ├── AdPack.kt          # ← FanqieHook AdHooks 原样
│       │   │   └── PurifyPack.kt      # ← squemaFQH ③⑤⑤b⑥ 重写
│       │   └── ui/                    # 阶段 4
│       └── resources/META-INF/xposed/
│           ├── java_init.list         # 1 行:新入口 FQCN
│           ├── module.prop            # 完整 11 键(用 FanqieHook 的当模板)
│           └── scope.list             # 只 2 行:com.dragon.read / com.phoenix.read
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE                          # GPL-3.0 全文
├── NOTICE                           # 新建,见 §3.4
└── README.md
```

**包名建议:不要用 `com.byterax.phoenix.read`。** 那是红果包名 `com.phoenix.read` 加前缀(历史遗留自 ForkRax),会让 `MODULE_PACKAGE.equals(caller)` 这类检查看起来像在跟目标 App 自匹配(`RESEARCH.md` §6.6)。

### 3.2 移植前先做的核验(需要真实 APK)

`PurifyPack` 的 4 条 hook 全部依赖 squemaFQH 声称的字符串常量,**这些常量必须在目标 APK 上核验后才能信**:

| 待核验 | squemaFQH 声称 | 风险 |
|---|---|---|
| ④ 字段名 | 定位串 `recDiggNum` vs 写入 `recvDiggNum`(`HookInit.java:391` vs `:404`) | 已排除该功能,但若将来加必须先核验 |
| ⑤ 定位串 | `"doSyncInitUserInfo:%s"` | 若宿主改了这条日志格式,功能静默消失 |
| ⑤b 类型 | `com.dragon.read.rpc.model.CommentUserStrInfo` | 且 `findClassesWithFieldType` 返回**所有**持有该字段的类 → 拦截面过宽(见 §5.2) |
| ⑥ 定位串 | `"获取推荐用户数据成功"`(`\uXXXX` 转义在 `:416`) | 同上 |
| ③ 判定值 | `args[0]=="PromotionFromUserPage" && args[1]==Boolean.TRUE`;改写用 `com.dragon.read.rpc.model.VipPromotionStrategyExtraInfo` 的**无参构造器** | 该类若无无参构造器,`:379-381` 直接抛异常被吞 |
| ① 方法名 | `willShowLynxBanner` | **先做 §5.1 的冗余测试,可能整条删掉** |

**核验方法:** 用 `apktool`/`jadx` 反编译目标版本 APK,直接搜这些字面量;或用 DexKit 的 CLI 跑同样的查询看命中数与命中项。**命中数 >1 时不要取第一个**(见 §5.2 的"拒绝猜测"策略)。

### 3.3 构建基线

| 项 | 值 | 依据 |
|---|---|---|
| AGP / Gradle | **8.7.3 / 8.11.1** | §2 0.1(已定,不再是未知数) |
| Kotlin | **2.0.21** | FanqieHook 已知良好组合 |
| JDK | 17,用 squemaFQH 的 `gradle/gradle-daemon-jvm.properties`(`toolchainVersion=17`, `toolchainVendor=JETBRAINS`)+ `settings.gradle` 的 foojay-resolver 1.0.0 | 比手工装 JDK 省心 |
| compileSdk / targetSdk | **35 / 35** | §2 0.1。**不要**用 37 —— 那会强制 AGP 9.x,重新引入 Kotlin 兼容性未知数 |
| minSdk | 26 | 两边一致 |
| `ndk.abiFilters` | **release = `arm64-v8a` only;debug/androidTest 追加 `x86_64`** | release 只打 arm64 有实测依据:番茄 73532/73732 的 117/116 个 `.so` **全在 arm64-v8a 下**,红果同基线;v7a 永远用不上(宿主 arm64-only)。⚠️ 但 **CI 的 instrumented 测试跑在 x86_64 模拟器上**,debug 变体缺 `x86_64` 会直接 `UnsatisfiedLinkError: libdexkit.so`(arm64 模拟器在 x86 runner 上慢到不可用)。verify 脚本第 8 项会分别校验这两种情况 |
| `minifyEnabled` / `shrinkResources` | **true / true** | 采用 FanqieHook 的;必须配全套 keep 规则 |
| `packaging.resources` | `merges += "META-INF/xposed/*"`;excludes `META-INF/*.kotlin_module`, `AL2.0`, `LGPL2.1` | **`merges` 必须有**,否则元数据冲突 |
| `buildFeatures` | **不设 `aidl`,不设 `buildConfig`** | AIDL 是死代码;`BuildConfig.SERVICE_VERSION` 在 squemaFQH 里生成了但全仓零引用 |
| 签名 | FanqieHook 的 `ci` config(env 注入,未设 `KEYSTORE_PATH` 时回退 debug) | squemaFQH **完全没有 signingConfigs**,release 产未签名 APK |
| `gradle.properties` | 钉死 `systemProp.file.encoding=UTF-8` / `sun.jnu.encoding=UTF-8` / `user.language=en` / `user.country=US`,并在 `org.gradle.jvmargs` 里重复一遍 | squemaFQH 有 13 处 mojibake `????` 的教训 —— 那套 UTF-8 钉死是在注释**已损坏之后**才加的 |

**依赖:**

```kotlin
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")          // 必须 compileOnly,否则与宿主框架冲突
    implementation("io.github.libxposed:service:102.0.0")   // 升到 102!见 §4.3
    implementation("androidx.annotation:annotation:1.7.1")  // service 的传递依赖
    implementation("org.luckypray:dexkit:2.3.0")            // 必须 implementation,否则无 libdexkit.so;2.3.0 理由见 §2 0.2
}
```

**删掉 `compileOnly("de.robv.android.xposed:api:82")`** —— squemaFQH 里唯一消费者是死文件 `xposed/SystemUserService.java`。删掉它顺带消灭"经典 + Modern 混进 `java_init.list` → LSPosed 静默失败整个模块"这个陷阱。

### 3.4 许可证合规(硬约束,不可选)

**合并后必须整体 GPL-3.0。** squemaFQH 是 GPL-3.0,FanqieHook 是 MIT;MIT → GPL 单向兼容,所以合法,但**你没有选择**:引入 GPL 代码后整个衍生作品就是 GPL-3.0,必须附完整对应源码。

必做:
1. `LICENSE` = GPL-3.0 全文。
2. **新建 `NOTICE`**,归属三方:
   - squemaFQH (GPL-3.0) © Squema —— 说明移植了哪部分(③⑤⑤b⑥ 的逻辑与 UI 骨架)
   - FanqieHook (MIT) © 2026 afwfv —— 说明以它为基座,`HookManager`/`ClassResolver`/`ModuleLog`/`ApkVersion`/`AdHooks` 全量来自它
   - DexKit (LGPL-3.0)、libxposed api/service (Apache-2.0)
3. **DexKit 的 LGPL 姿态要显式记录。** LGPL-3.0 要求接收者能替换/重链接该库,但 DexKit 必须是 `implementation`(`libdexkit.so` 要打进 APK),squemaFQH 的 AGENTS.md:100 甚至明写"绝不能改成 `compileOnly`"—— 这堵死了让姿态变干净的唯一配置。LGPL-3.0 §3 允许按 GPL-3.0 条款分发,**但两个上游都没有任何地方记录这个选择**。在 `NOTICE` 里明确写出这个 election,并提供对应源码获取方式与重链接说明。

---

## 4. 阶段 2:单一入口与引擎归并

### 4.1 入口类

**必须是 1 个入口类,不能是 2 个。** `java_init.list` 放两行让两个入口各自装 hook,会导致各自建 DexKit 桥、各自扫全 dex。

```
ModuleEntry : XposedModule()
├─ 构造器 ×2(见下)
├─ onModuleLoaded(param)      → 记进程名,初始化日志文件通道
├─ onPackageLoaded(param)     → 仅记日志(FanqieHook 的做法)
├─ onPackageReady(param)      → 全部工作在这里
│   1. 门控:packageName ∈ {com.dragon.read, com.phoenix.read}
│   2. 门控:param.isFirstPackage == true
│   3. 门控:processName == packageName    ← 采用 FanqieHook 的,排除 :push/:widgetProvider/:miniappX
│   4. 版本读取(4 级降级,advisory only,永不阻断安装)
│   5. 建 ClassResolver(apkPath = 宿主 APK 路径)
│   6. 依次 install:AdPack → PurifyPack
│   7. 输出 install summary 行
├─ onHotReloading(param)      → unhookAll() + 返回 true
└─ onHotReloaded(param)       → ★ 必须重装 hook,不能只打日志
```

**双构造器**(squemaFQH `HookInit.java:53-60`,兼容 LSPosed 1.9.2(v7024)~2.2.0):

```kotlin
class ModuleEntry : XposedModule {
    constructor(base: XposedInterface, param: XposedModuleInterface.ModuleLoadedParam) : super()
    constructor() : super()
}
```

缺任何一个,LSPosed 抛 `NoSuchMethodException` **跳过整个模块**。

> ⚠️ **脆弱性警告:** 两个构造器都调**无参** `super()`,而 libxposed 文档说框架会自动调 `attachFramework()` 且模块 "must not call it"。squemaFQH 的实机日志证明这套能跑,但**这是在依赖 LSPosed 的反射查找顺序,不是依赖文档化契约**。移植后要**在 LSPosed 1.9.x 和 2.2.x 上分别验证**;若只在 2.2.x 上验证过,就在 README 里把支持下限写成 2.2.x,不要照抄"1.9.2~2.2.0"这个未经验证的说法。

**★ 必须修的热重载 bug(比原先判断的更深):** FanqieHook 的 `onHotReloaded`(`FanqieModule.kt:164-167`)只打日志,注释说"新一代会通过自己的 `onPackageReady` 重装"—— 但 libxposed javadoc 说的正相反:"Package lifecycle callbacks are not automatically replayed after hot reload"。配合 `onHotReloading` 在 `unhookAll()` 后返回 `true`,一次 service 触发的热重载会让模块**在进程重启前完全失效**。`autoHotReload=false` 只挡住 App 更新路径,挡不住显式 service 路径。

**但"在 `onHotReloaded` 里真正重装一次"这句话是不够的** —— 实现后才发现三个必须先解决的子问题(`ModuleEntry.kt` 的 `lastTarget` KDoc 有完整记录):

1. **实例字段跨不过世代。** `XposedModule` 的类注释写着 "Entry classes will be instantiated once for each loaded module generation in a process",`onHotReloaded` 的 javadoc 写着 "This callback runs in **new** code"。所以新一代是**新实例 + 新模块 classloader**,`lastTarget` 必然为 null,**连 companion object 的静态字段也一样是全新的**(静态是 per-classloader 的)。唯一跨代通道是 `HotReloadingParam.setSavedInstanceState()` → `HotReloadedParam.getSavedInstanceState()`,而它明确要求 "must not contain objects created under the old module classloader … Use classloader-neutral values" —— 只能传 String/基元,**ClassLoader 传不过去**。修法:旧代存包名/apkPath/dataDir 三个 String,新代用 `ActivityThread.currentApplication()` 重新取 ClassLoader(热重载时宿主 Application 确实存在,这与 `onPackageReady` 阶段不同)。
2. **必须自己摘旧 hook,否则每条装两遍。** 我们重写了 `onHotReloaded`,于是框架那个"只 unhook 旧 handle"的**默认实现不会执行**。不摘 `param.oldHookHandles` 的后果是新旧叠加。已实测:18 条创建 / 9 条存活,id 与上一代完全一致。
3. **顺序必须是"先恢复、再摘、再装"。** 反过来(先无条件摘掉,再判断能不能重装)会让两条 bail-out 路径把宿主留在**零 hook** 状态 —— 比框架默认的什么都不做更糟。已实测:上一代 9 条,bail-out 后剩 0 条;改序后两条路径都保留 9 条。
4. **`libdexkit.so` 会把旧世代钉住。** 一个 native 库只能被一个 classloader 加载;若沿用固定文件名 `libdexkit-<abi>.so`,新世代会撞上 ART 的 "already loaded in another classloader",而**新世代的 JNI native 方法并没有绑定** → `DexKitBridge.create` 立刻死于 `UnsatisfiedLinkError: No implementation found for … nativeInitDexKit` → 所有 DexKit 查找降级 → **PurifyPack 五条全部失效**(它们没有反射兜底)。上游把 `contains("already")` 一律当成功,正是这个静默失效的成因。修法:文件名带**世代令牌**(classloader identityHashCode + nanoTime),让每代映射自己的一份,并用 per-classloader 静态标记区分"同代重入"(可复用)与"跨代冲突"(必须返回 false 降级)。
5. **`log.statusFile` 要重设。** `ModuleLog` 是每实例的,旧世的文件通道随旧实例失效;不重设的话新一代只剩两条日志通道,而 `ModuleLog` 的注释记录了实测:部分 LSPosed fork 从不 flush 模块日志,部分设备系统级关掉 logcat。

**`minApiVersion` 写 102,不要写 101。** FanqieHook 声明 101 但代码用了 `setId()`(`HookManager.kt:214`)和两个热重载回调(`FanqieModule.kt:157,164`),这些都是 `@SinceApi(API_102)`。

### 4.2 共享 DexKit 桥

squemaFQH **从不 close** `DexKitBridge`(它是 `Closeable`,官方明确要求 close 否则内存泄漏;番茄是多 dex 应用,native 内存要等 GC 跑 `finalize()`)。FanqieHook 的 `ClassResolver` 懒构建但也没有 close。

→ 在 `onPackageReady` 里用 **try-with-resources / `use {}`** 包住整个安装流程,结束时 close。同时确保**全流程只有一个桥**(两个 pack 共用同一个 `ClassResolver` 实例)。

### 4.3 状态检测:先修依赖版本

`io.github.libxposed:service` **101.0.0 → 102.0.0**。

squemaFQH 的 `ScopeStatus.isRunningTarget`(`:125`)反射探测 `getRunningTargets()`,把 `NoSuchMethodException` 当"service 101 没这方法"处理(`:131-132`)—— 因为 `getRunningTargets()` 是 API 102+ 才加的。**升版本是这一整个文件里性价比最高的一处修复**,它把"目标进程正在运行且已勾选"变成可用信号,实质上等价于 LIVE。

> 升级后需验证 102.0.0 确实提供该方法(调研阶段是从 libxposed 文档索引推断的,**未在构建中验证**)。

### 4.4 不要移植的状态代码

以下按 `RESEARCH.md` §5.5 的判定**直接删除**,不迁移:

| 删 | 理由 |
|---|---|
| `xposed/`(4 个文件:`SystemBootstrap`/`SystemAmCompat`/`SystemUserService`/`HookStatusService`) | **不可达**。`SystemBootstrap.start()` 零调用者(HookInit 从未 override `onSystemServerStarting`),且 `scope.list` 无 `system` 条目 |
| `service/`(`ServiceClient`/`ServiceProvider`)+ `IHookStatusService.aidl` | **死**。且 `ServiceProvider` 导出无 `android:permission` 保护 |
| `HookStatusReporter` | **死**。remote binder 永远 null |
| `HookStatusStore` | **死**。`markHooked` 零调用者,SharedPreferences 从未被写 |
| `RuntimeDetector` | **100% 死代码**,零引用 |
| `HookStatusFiles`(`/data/local/tmp` marker) | 代码接通但**在 enforcing SELinux 下无效**:`/data/local/tmp` 是 `shell:shell` 0771 / type `shell_data_file`,`untrusted_app` 域无读写权;反射 `chmod(0666)` 救不了(`FileOutputStream` 的 open 先失败) |
| `LspScopeReader` | **危险**。主线程上阻塞式 `su` exec + `waitFor()`,Magisk 弹授权框就 ANR;且 `MainActivity.java:81-82` 在 `refreshCards()` 前先 `invalidate()` 故意打穿 3 秒 TTL 缓存。若确实需要无 service 兜底,**必须挪到后台线程** |
| `QuarkConfigProvider` | 夸克专用,且**导出无权限保护、`call()` 看不到调用方包名检查** → 设备上任何 App 都能翻它的开关。不移植 |

**保留(必需):** `HookApp` + `XposedServiceHelper`(唯一在工作的通道)、`ScopeStatus`(状态机)、`MainActivity`(UI)。

### 4.5 `TARGET_PACKAGES` 必须与版本审计表解耦

FanqieHook 的 `TARGET_PACKAGES = SUPPORTED_VERSION_CODES.keys`(`FanqieModule.kt:332`)把**作用域门控**和**版本审计记录**做成了同一个对象 —— 编辑审计列表会**静默改变哪些包被 hook**。移植时必须拆开。

---

## 5. 阶段 3:移植 squemaFQH 的 4 条 hook 到语义锚定

**核心原则:不要照抄 squemaFQH 的定位方式。** 它三个 DexKit 辅助函数(`HookInit.java:479-527`)全是无 `searchClass()` 收窄的全 dex 扫描 + **取第一个不校验唯一性**,且没有返回类型校验。全部重写到 `ClassResolver` 的五级降级上。

### 5.1 ① Lynx 横幅 —— 先测冗余,可能整条删掉

squemaFQH 用 DexKit 按方法名找 `willShowLynxBanner` → `Boolean.FALSE`(**不调 `proceed()`**)。FanqieHook 的中心闸门对 `checkAdAvailable(String,String)` 无条件返回 `false`,是**不同层的另一道闸**。

**实验:** 只装 AdPack(不装 PurifyPack),在实机上观察 Lynx 横幅是否仍被拦。
- 若仍被拦 → **删掉这条**,少一条脆弱 hook。
- 若未被拦 → 保留,但**必须改成走 `ClassResolver`**,且加上 `returnType == Boolean.TYPE` 校验(squemaFQH 没有,`HookManager.kt:132-137` 有)。

### 5.2 通用改写规则

| squemaFQH 现状 | 改成 |
|---|---|
| `findFirstMethodByUsingString` 取 list 首元素,无唯一性断言 | **拒绝猜测策略**(`ClassResolver.kt:268-302`):1 个命中→用;0 个→null+WARN;>1 个→只有恰好一个 static 时才接受,否则 null+`"refusing to guess"`。理由:hook 错方法会静默翻错开关,**比不 hook 更糟** |
| 装 hook 前不校验返回类型 | 一律校验 `method.returnType`,不匹配则 `noteSkip` 并计入 summary |
| `catch (ReflectiveOperationException ignored)` 三处完全静默(`:312`/`:373`/`:405`) | **必须打 WARN 日志**。④ 的 `recvDiggNum`/`recDiggNum` 疑点正是被这个掩盖的 |
| 无命中审计 | 每条 hook 走 `auditHit`(`HookManager.kt:193-199`),**默认开启**(FanqieHook 发布构建里是关的,`AUDIT_HOOK_HITS=false`) |
| 无 `deoptimize` 调用(全仓零) | 对可能被 ART 内联的短方法调 `deoptimize`。⚠️ 注意 API 文档是以**调用方**为框的("To force A to call the hooked B, you can deoptimize A"),而 FanqieHook 现在 deopt 的是被 hook 方法本身 —— 这一点存疑(`RESEARCH.md` §8),移植时**两种都试,用命中审计判断哪种真的生效** |

### 5.3 ⑤b 的拦截面必须收窄

`findClassesWithFieldType`(`HookInit.java:508-527`)返回**所有**持有 `CommentUserStrInfo` 字段的类,然后 hook 它们上面**任何**"参数含自身类型"的非抽象方法(`:282-293`,一个 `copyFrom`/merge 模式),每个 hook 再对**所有非 null 参数**写 `userName`。

这是**热路径上的宽拦截**,squemaFQH 的 README 完全没警告其爆炸半径。移植时:
1. 先用 `auditHit` 在实机上数清究竟命中多少个类、多少个方法、每秒多少次调用。
2. 若命中数 >3 或调用频率高 → 收窄到具体类名,或改为只在特定 UI 路径上生效。
3. ⑤ 那条"对每一个非 null 参数 `setField(arg,"userName",…)`"也要收窄 —— 盲目写所有参数是不必要的宽。

### 5.4 ③ 的实际语义要如实描述

squemaFQH README.md:113 说"屏蔽个人页推广广告位",**它什么都没屏蔽**:`canThisPositionShow` 的返回值原样透传,只在 `pageName=="PromotionFromUserPage" && args[1]==TRUE` 时改写**返回对象**的 `leftTime=113143670061` / `text=""` / 新建 `VipPromotionStrategyExtraInfo`。

诚实的描述是"把个人页 VIP 推广位渲染成一个文案为空的 banner"。**新模块的 README 要用诚实措辞**,不要继承这个失实说法。

> ⚠️ 这条与 §0.1 的排除决定有交叉:它改写的是 **VIP 推广位**的展示。它不触碰权益数据、不伪造会员态,只是清空一个推广文案,属于 UI 净化范畴,**保留**。但要确认改写后不依赖"当前是 VIP"这个前提 —— 若依赖,则它实际是在配合会员伪造,应一并排除。移植时用 `auditHit` + 实机截图确认。

---

## 6. 阶段 4:UI 与状态面板

**这是本次融合真正的增量所在**,别当成收尾工作。

squemaFQH 的番茄/红果卡片**永远只显示 OFF 或 SCOPED,LIVE 点不亮**(8 个断点,`RESEARCH.md` §5.5);FanqieHook **连 Activity 都没有**。而这类模块天生会随宿主升级**静默失效**,一个能告诉你"现在哪些 hook 活着、哪些丢了"的面板,价值高于再多拦两个广告位。

### 6.1 数据其实已经算好了

FanqieHook 的 `install summary` 日志行(`HookManager.kt:63-72`,在 `FanqieModule.kt:142-147` 输出)已经包含全部所需数据:

```
hooks installed=N skipped=M lost=[…] known-missing=[…]
```

且 `unexpectedSkips`(`:55`)= `skipped - knownMissing`,这样宿主专属的缺失(红果没有 `FanqieSearchActivity`,番茄没有 `HongguoBannerServiceImpl.enableShortSeriesAdJoinRevert`)不会掩盖真实回归。**把这个结构送到屏幕上即可。**

### 6.2 UI

- `MainActivity` 精简到 **2 张卡**(番茄 / 红果),沿用 squemaFQH 的黑金玻璃卡片(`res/layout/activity_main.xml` + `view_status_card.xml` + `drawable/bg_glass_card_*.xml` + `values/styles.xml`)。
  > ⚠️ UI 资源必须**整套**替换(layout + colors + strings + styles)—— 资源名错一个,`findViewById` 返回 null,主题静默退化(squemaFQH AGENTS.md:108)。
- 每张卡:目标名 + 包名 + 宿主 versionCode + chip(`LIVE`/`SCOPE`/`IDLE`)。
- **新增点开的详情页**:per-hook 表格 —— id / 状态(installed·skipped·lost·known-missing)/ 命中次数(来自 `auditHit`)/ 定位方式(反射·DexKit-字段·DexKit-接口)。**这一页是 squemaFQH 完全没有、FanqieHook 也没有的东西,是本项目最有价值的交付物。**

### 6.3 LIVE 检测的实现路径(按可行性排序)

| 方案 | 可行性 | 说明 |
|---|---|---|
| **A. `XposedService.getScope()` + `getRunningTargets()`** | **高,首选** | `HookApp` 已经能拿到 binder(squemaFQH 唯一在工作的通道)。`service` 升 102 后 `getRunningTargets()` 可用 → "已勾选 + 进程在跑" ≈ LIVE。**纯 libxposed 原生,无 IPC、无 root、无 SELinux 问题** |
| B. `ContentProvider.call`(目标进程 → 模块 App) | 中,**需实机验证** | squemaFQH 里唯一被验证可用的模式(夸克走这条,`QuarkHookMain.java:150` → `QuarkConfigProvider.call` `:70-80`)。**但 API 30+ 上目标 App 需要对模块有 package visibility 才能解析到 provider** —— 夸克能用可能只因它自带 `QUERY_ALL_PACKAGES`,**番茄/红果未必有**。若采用,provider **必须**加 `android:permission` 并校验调用方包名(squemaFQH 两个 provider 都没有) |
| C. `/data/local/tmp` marker | **不可行** | enforcing SELinux 下 `untrusted_app` 无读写权 |
| D. `getRemotePreferences(group)` | **方向不对** | 模块 App 侧可写、被 hook 侧**只读**,而我们要的是"目标写 → 模块读" |
| E. 读 LSPosed 的 `/data/adb/lspd/log/modules_*.log` | 低 | 需要 root;且 squemaFQH 的 `LspScopeReader` 已示范了主线程 `su` 的 ANR 风险 |

**顺序修掉 squemaFQH 的顺序 bug:** `reportTargetHooked(pkg)` 在 `HookInit.java:112`/`:115` 被调 —— **在 `installHooks` 之前**,`:188` 又调一次。状态灯会在一条 hook 都还没装时宣称 LIVE。**上报必须发生在安装完成之后。**

### 6.4 配置下发(可选,v1.1)

若要做运行时开关,用 libxposed 原生的 `getRemotePreferences(group)`:**模块 App 侧可写、被 hook 侧只读** —— 方向正好适合"UI 配置 → hook 读取"。squemaFQH 的夸克是用外部文件 `/storage/emulated/0/Download/QuarkHook/quark_bypass.cfg` 做的(文件不存在时 `isEnabled()` 返回 false,即**所有开关默认关**),那个方案需要存储权限,不如 remote preferences 干净。

---

## 7. 阶段 5:验证

### 7.1 构建

```bash
export ANDROID_HOME="<你的 SDK 路径>"     # Git Bash 下必须,否则 compileDebugJavaWithJavac 失败
./gradlew clean assembleDebug --no-daemon
./gradlew assembleRelease                 # 验 R8 keep 规则
```

**R8 必检项:** 反编译 release APK,确认
1. `java_init.list` 里的入口类 FQCN **未被重命名**(或已加 `-adaptresourcefilecontents`)
2. `org.luckypray.dexkit.**` 完整保留含成员(`libdexkit.so` 通过 JNI 按类名+方法签名回调 Java,**绕过 R8 的 mapping 表**)
3. `com.google.gson.**` 保留(字段名反射)
4. `lib/arm64-v8a/libdexkit.so` 在 APK 内
5. APK 体积(基线:FanqieHook 0.47 MB)

> squemaFQH 的 `proguard-rules.pro` 是 **100% 注释掉的空模板**,它"安全"仅仅因为 `minifyEnabled false`。**一旦开启而没补 keep 规则,模块会在加载期静默坏掉。**

### 7.2 安装

```bash
adb install -r -t app/build/outputs/apk/release/app-release.apk
```

1. LSPosed Manager 启用模块,勾选作用域 `com.dragon.read` + `com.phoenix.read`
2. **整机重启,不要用 LSPosed 软重启**(squemaFQH README.md:73:双构造器路径走 zygote init)
3. ⚠️ `adb install -r` 覆盖安装后 **LSPosed 偶尔会把 `enabled=0` 重置**(README.md:77)→ 要在 Manager 里重新勾选 + 强停目标 App

### 7.3 实机回归矩阵

**至少覆盖:番茄最新正式版 + 番茄一个旧版(如 73932)+ 红果最新版。** FanqieHook 的已验证版本集可作参照:番茄 {73532, 73718, 73732, 73917, 73967, 73932},红果 {73532, 73732, 73932}。

每个组合都要记录:

| 检查 | 方法 | 通过标准 |
|---|---|---|
| 模块加载 | `adb logcat -s <TAG>` | 看到 `onModuleLoaded process=…` |
| 门控生效 | 同上 | `:push` / `:widgetProvider` / `:miniappX` 进程**不触发**安装 |
| **install summary** | 同上 | `installed=N skipped=M lost=[] known-missing=[…]`,且 `unexpectedSkips` 为空。**基线见下方 §7.3.1** |
| **命中审计(最关键)** | 同上 | 每条 hook 都有命中计数。**"装上了"不等于"在调用链上"** —— squemaFQH 完全没有这个能力,所以它的 MEMORY.md 里"5 个 hook 验证生效"只验证了安装 |
| DexKit 桥 | 同上 | 无 `UnsatisfiedLinkError`;桥正常 close |
| 广告实际消失 | 肉眼 + 截图 | 阅读器 banner / 视频 / 翻页信息流 / 章节断开 / 开屏 / TopView / 听书贴片 / 短剧暂停 / 短剧 banner / 横屏插入 |
| **UI 净化实际生效** | 肉眼 + 截图 | 推荐用户清空 / 昵称显示 / 个人页推广位文案为空 |
| **激励入口失效(预期行为)** | 肉眼 | "看视频得金币"等点击后无广告可播 —— **这是设计使然,要在 README 里明说** |
| 宿主不崩 | 完整走一遍阅读/听书/短剧流程 | 无 ANR、无 crash |
| 启动耗时 | `adb shell am start -W` 对比装/不装模块 | DexKit 全 dex 扫描在启动路径上,**必须有可接受的开销**。squemaFQH 无任何计时插桩,这是它的盲区之一 |
| 状态面板 | UI | chip 状态正确;详情页 per-hook 数字与 logcat summary 一致 |
| 热重载 | LSPosed 触发热重载 | **hook 必须自动重装**(§4.1 的 ★ bug) |

### 7.3.1 install summary 验收基线(已按实际代码更正)

> ⚠️ **原来写的「番茄 `installed=32 skipped=1`、红果 `installed=30 skipped=3`」是 AdPack-only 的数字,加入 PurifyPack 后已失效。** 下面更正版来自对实际代码的逐条清点,并经假框架实跑核对。

**AdPack:每宿主 33 次尝试,与上游完全一致**(30 个静态 `id=` 字面量 + 1 个 `position-filter:<DexKit impl>` + 2 个 `fullscreen-ad:<cls>#<m>`;第 31 个字面量 `experimental-splash-attribution` 是编译期关闭的)。所以**只装 AdPack 时**上游基线仍然成立:番茄 `installed=32 skipped=1`、红果 `installed=30 skipped=3`,两者 `lost=[]`。这也是 CI 里做 AdPack 单独回归时该对的数字。

**PurifyPack 在此之上增加:**

| 宿主 | ③ promo | ④ social | ⑤ nickname-sync | ⑤b comment | ⑥ recommend | 合计 |
|---|---|---|---|---|---|---|
| 番茄 | 1 | 1 | 1 | **K** | 1 | `4 + K` |
| 红果 | —(早退) | —(早退) | 1 | **K** | —(早退) | `1 + K` |

**K 是数据依赖的,无法预先钉死**:`K = Σ(至多 MAX_COMMENT_HOLDER_CLASSES=8 个持有类) min(该类上自类型参数的具体方法数, MAX_HOOKS_PER_HOLDER_CLASS=4)`,所以 `0 ≤ K ≤ 32`,id 形如 `purify-nickname-comment-<SimpleName>#<index>`。两个边界:持有类不可加载或零持有类 → 记 **1** 条代表 id `purify-nickname-comment`(与动态 id 不冲突,后者总带 `-<Class>#<i>` 后缀);找到持有类但没有一个带自类型参数 → 记 **0** 条。

**验收表:**

| 宿主 | 尝试总数(`installed+skipped`) | `known-missing`(确定性) | 验收标准 |
|---|---|---|---|
| 番茄 73932 | `37 + K` | `[hongguo-banner-join-revert]` | **`lost=[]`** |
| 红果 73932 | `34 + K` | `[purify-search-ai-float-button, purify-search-ai-banner-entry, purify-search-ai-box-entry]` | **`lost=[]`** |

最好情况(全部命中):番茄 `installed=36+K skipped=1`,红果 `installed=31+K skipped=3`。K 从日志行 `purify ⑤b installed: classes=N methods=M (holders found=…)` 读。

**★ 关键:验收要钉 `lost=[]` 与 `known-missing` 集合,不要钉 `installed` 的具体数字。** 尤其 ③ 是一次「拒绝猜测」的全 dex 按名扫描 —— 若 `canThisPositionShow` 在真机上存在于多个类,它**会按设计落进 `lost`**(宁可不 hook 也不 hook 错),那是正确行为而不是回归。

**摘要行还可能出现两个后缀**(happy path 下都为空,文本与上游逐字一致):
- `failed=[…]` —— 某个 category 整段抛异常中止(`noteCategoryFailure`)
- `hook-failed=[…]` —— 某条 hook 的 `module.hook()` 本身抛异常(`hookFailures`)

后者是本次新加的:上游这种失败既不进 `installed` 也不进 `skipped`,于是 `installed + skipped == 尝试总数` 这条等式会**静默失衡**,而实机回归正是拿它对基线的。若摘要里出现 `hook-failed`,等式要按 `installed + skipped + hook-failed == 尝试总数` 来核。



采用 FanqieHook CHANGELOG `:7-21` 记录的那套:**debug → 实机验证 → 才合 main**。它的起因是**两次未验证就发布的回归**(v0.6.1, v0.7.0)。

squemaFQH 相反:**没有测试、没有 lint、没有 formatter**(AGENTS.md:28, :131),不要假设 `test`/`lint` task 存在。

---

## 8. 工作量估算

| 阶段 | 内容 | 估算 |
|---|---|---|
| 0 | 环境 + 两个未知数验证 | **不确定**,是最大变数。若 Kotlin × AGP 9 不通要走回退,多 0.5 天 |
| 1 | 工程骨架 + 构建统一 + 许可证文件 | ~35 行 Gradle + ~15 行 ProGuard + NOTICE |
| 2 | 引擎归并 + 单一入口 + 删死代码 | 搬 ~1600 行(FanqieHook 的 5 个文件)+ 新写 ~100 行入口 |
| 3 | 4 条 hook 重写到语义锚定 | **主要工作量**。squemaFQH 侧 ~510 行里只有 ~200 行逻辑需要保留,但**每条都要重写定位方式 + 补类型校验 + 补日志 + 补审计**,再加 §3.2 的 APK 核验 |
| 4 | UI + 状态面板 + 详情页 | squemaFQH 骨架可复用,但**详情页是从零写**;LIVE 方案 A 简单,方案 B 需实机试错 |
| 5 | 实机回归(3 个版本组合 × 13 项检查) | **不可压缩**,且是这类项目唯一有意义的验证 |

**代码总量:** 约 2.1k 行(FanqieHook 全量)+ ~200 行(squemaFQH 逻辑重写)+ ~300 行(入口 + UI 详情页)= **~2.6k 行**。

**真正的成本不在写代码,在实机验证。** 上游两个仓库加起来只有 FanqieHook 一方做了严肃的实机审计,squemaFQH 的 17 处文档失实(见 `RESEARCH.md` §10)有相当一部分根因就是"只验证了安装,没验证生效"。

---

## 9. 里程碑与验收标准

| M | 交付 | 验收 |
|---|---|---|
| **M0** | 环境可用 + 两个未知数有结论 | 最小 APK 能被 LSPosed 识别;DexKit 2.2.0 上 `ClassResolver` 的 7 处查询结果与 2.0.4 一致(**实机比对**) |
| **M1** | 骨架 + 构建基线 | `assembleDebug` / `assembleRelease` 均通过;R8 五项检查全过;APK ≤ 0.6 MB |
| **M2** | AdPack 原样工作 | **只装 AdPack 时**与上游基线逐字一致:番茄 73932 `installed=32 skipped=1`、红果 73932 `installed=30 skipped=3`,两者 `lost=[]`;广告肉眼确认消失。(加入 PurifyPack 后的完整基线见 §7.3.1) |
| **M3** | PurifyPack 4 条 hook | 每条都有命中计数 >0;⑤b 的拦截面已按 §5.3 收窄并记录实测命中数;① 已做冗余测试并记录去留决定 |
| **M4** | 状态面板 | chip 正确反映 SCOPE/LIVE;详情页 per-hook 数字与 logcat summary **完全一致**;LIVE 方案已确定并记录 |
| **M5** | 完整回归 | 3 个版本组合 × 13 项检查全过;热重载后 hook 自动重装;启动耗时开销可接受并有实测数字 |
| **M6** | 发布 | GPL-3.0 + NOTICE 三方归属齐全;README 用**诚实措辞**(不继承上游 17 处失实说法);明确披露激励入口失效这一预期代价 |

---

## 10. 已知遗留 / 明确不解决

| 项 | 状态 |
|---|---|
| 会员解锁、伪造社交数字 | **主动排除**(§0.1, §0.2) |
| 经典 Xposed API 兼容 | **技术不可能** |
| 金币/激励收益 | **不做**。发奖服务端校验,上游有实证 |
| 反检测 | **不做**。当前痕迹面:FanqieHook 在宿主 data 目录留 `cache/fanqiehook.log` + `cache/fanqiehook/libdexkit-<abi>.so`,加特征明显的 logcat tag;宿主检查自己的 cache 目录就能发现被注入。合并后应至少把日志路径挪出宿主可发现的位置,但**不承诺任何反检测能力** |
| `deoptimize` 语义 | **存疑未解**。API 文档以调用方为框,FanqieHook deopt 的是被 hook 方法本身。用命中审计实测两种做法 |
| `BrandTopViewDisplayStrategy#c` | **上游残留最脆弱点**(`AdHooks.kt:600`,硬编码混淆名且无字段级兜底,而 `ClassResolver.kt:108` 自己就点名它可能被改名)。本项目可尝试补一条字段级查找,但**不保证成功** |
| 全屏 hook 爆炸半径 | `AdHooks.kt:100-119` 把实现类上**每个**无参非静态 boolean 都置 false,超出注释声明的两个门。**用命中审计确认有没有误伤**,有则收窄 |
| `NsUtilsDependImpl` 解析方式 | `:63-65` 注释论证"由接口声明、全 APK 唯一实现",但 `:81-85` 代码是硬编码类名。**改成按接口查找**(一个白送的抗混淆机会) |
| 按位粒度的用户开关 | **v2**。两个上游对番茄/红果都零配置面 |
| 夸克/小X/Cherrygram | **不在范围** |

---

## 附:上游文档不可信的证据

**在写任何一行代码前读 `RESEARCH.md` §10。** squemaFQH 有 **17 处** README/AGENTS.md/CLAUDE.md/注释与代码不符,其中包括:招牌功能"用 DexKit 全版本通杀"是假的(② 是硬编码 `Class.forName`)、"屏蔽个人页推广广告位"实际什么都没屏蔽、装机验证说的 Toast 早已被删、`RuntimeDetector` 和 `SystemHookEntry` 是文档描述的**不存在的代码**、`writeActivationFlag` 的 javadoc 说它写文件而方法体只打日志、"5 个 hook 设备上验证生效"只验证了安装。

FanqieHook 相对干净,但也有 2 处:`minApiVersion=101` 与代码用了 102-only API 矛盾;`onHotReloaded` 的注释与 libxposed 契约相反。

它的 CLAUDE.md 还让你去读 `.claude/rules/` 和 `.codex/rules/` —— **两者都被 gitignore 了,不在仓库里**。AGENTS.md 规定要与 `E:\New\squemaFQH\1\squemaFQH\` 这份"权威备份"diff 后再同步 —— **那个盘符不存在,`1/` 也被 gitignore,拿不到**。

**结论:一切以代码为准。**
