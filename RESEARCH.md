# squemaFQH × FanqieHook 融合调研报告

> 调研对象(均已 clone 到 `research/`,单 commit 快照):
> - **squemaFQH** — https://github.com/Squemadylan/squemaFQH · `3f8e69a` · v1.9.0 (vc38) · GPL-3.0
> - **FanqieHook** — https://github.com/afwfv/FanqieHook · `11f9b98` · v0.8.6 (vc29) · MIT
>
> 本文所有结论均来自对源码的直接阅读,带 `文件:行号` 引用。**两个仓库的 README 都存在与代码不符的描述,已在 §10 逐条列出**,不要拿它们的文档当事实来源。
>
> 未做实机验证:本环境无 Android SDK 且只有 JDK 11(两项目均需 JDK 17),因此**没有编译过、没有跑过**。所有"效果"均为静态分析推断,已逐处标注。

---

## 1. 结论速览

| 问题 | 结论 |
|---|---|
| 两者在番茄/红果上功能重叠吗? | **几乎为零重叠,是严格互补关系**(§7 有硬证据) |
| 技术上能融合成一个模块吗? | **能,而且不难**。两者同为 Modern libxposed API 102、同 scope、同 `onPackageReady` 入口、同用 DexKit。冲突集中在**构建工具链**而非 hook 逻辑(§8) |
| 融合后效果怎么样? | **广告层 = FanqieHook 原样(已经是同类最强);非广告层 = 新增 UI 净化**。真正有价值的增量比想象中小,因为 FanqieHook 的"零广告中心闸门"已经覆盖了 squemaFQH 那 2 条广告 hook 的大部分场景(§9) |
| 值不值得做成项目? | **值得,但价值不在"1+1>2",而在"把 FanqieHook 的工程严谨性 + squemaFQH 的 UI/状态层"合成一个可维护的东西**。最大的可交付增量其实是修好 squemaFQH 那套 90% 是死代码的状态上报管线(§6.5) |
| 主要风险 | 账号风控、宿主版本漂移、**squemaFQH 完全没有 hook 命中审计**(装了≠生效)、合并后检测面变大 |

**一句话:** 融合可行性高、工作量中等(约 2.5k 行代码归并 + 构建统一),但**期望要放对位置**——这不是能力叠加,是"一个很强的引擎" + "一堆仪表盘和几个装饰件"的组合。

---

## 2. 两个项目是什么

| 维度 | squemaFQH ("Squema Hook") | FanqieHook ("番茄 红果 去广告") |
|---|---|---|
| 定位 | **多目标聚合模块**:一个模块管 5 个 App | **单目标专精模块**:只管番茄+红果的广告 |
| 包名 | `com.byterax.phoenix.read`(注:是红果包名加前缀,历史遗留自 ForkRax) | `dev.operit.fanqiehook` |
| 语言 | 纯 Java | 纯 Kotlin |
| 代码量 | ~5.3k 行 Java(其中番茄/红果相关 ~510 行) | ~2.1k 行 Kotlin(1182 行代码 + 859 行注释) |
| 5 个目标 | 番茄 / 红果 / 夸克 / 小X分身 / Cherrygram | 番茄 / 红果 |
| APK 体积 | 未标注(打包 DexKit 4 个 ABI) | **0.47 MB**(只打 arm64-v8a) |
| 有 UI 吗 | **有**,黑金玻璃卡片 5 张状态卡 | **完全没有**,`AndroidManifest.xml:3-8` 的 `<application>` 里零个 Activity,无桌面图标 |
| 用户可配置 | 夸克有设置页;番茄/红果**无任何开关** | **无任何开关**,两个开关都是编译期常量 |
| API 世代 | Modern libxposed **only**(`minApi=102 targetApi=102`) | Modern libxposed **only**(`minApi=101 targetApi=102`) |
| License | **GPL-3.0** | **MIT** |

**共同地基(这是融合可行的根本原因):**

两者都是纯 Modern API 模块 —— 全仓库 grep `de.robv|IXposedHookLoadPackage|XposedBridge` 在 FanqieHook 里**零命中**,在 squemaFQH 里只有一个死文件用到(`xposed/SystemUserService.java:5-7`)。都用 `META-INF/xposed/{java_init.list, module.prop, scope.list}` 注册,都声明 `staticScope=true`,都在 `onPackageReady` 里装 hook,都把 `ExceptionMode.PROTECTIVE` 挂在每条 hook 上,都依赖 DexKit 在混淆 dex 里找目标,都把 `io.github.libxposed:api` 设为 `compileOnly`、`org.luckypray:dexkit` 设为 `implementation`。

> ⚠️ 这也意味着**两者都不能兼容经典 Xposed API**。libxposed API 102 的契约明确写着 "Libxposed modules can not call legacy `de.robv.android.xposed` APIs"。想要"同时支持老框架"在 `targetApiVersion=102` 下**做不到**,不是工作量问题。

---

## 3. 番茄/红果 功能清单对比

### FanqieHook:33 条 hook,全部是广告拦截

13 个类别(`AdHooks.installAll()` `AdHooks.kt:40-54`),实测装载数(作者日志 `FanqieModule.kt:300-303`):番茄 73932 → `installed=32 skipped=1`;红果 73932 → `installed=30 skipped=3`。

| 类别 | 代表 hook id | 目标 | 行为 |
|---|---|---|---|
| 阅读器信息流/视频/贴片 | `read-flow-ad-line`, `reader-video-ad`, `reader-ad-for-sati` | `NsAdImpl#needReadFlowAdLine`, `#canReaderVideoAdShow`, `ReaderAdManager#canLoadAd` | → `false` |
| **零广告中心闸门** | `position-filter:*` | `NsAdImpl#checkAdAvailable(String,String)` + 所有 `NsAdConfigManagerApi` 实现类 | → **无条件 `false`** |
| TopView | `topview-main`, `topview-reader` | `NsAdImpl#checkCanShowTopView*` | → `false` |
| 开屏 | `splash-ad-activity-open`, `splash-ad-{brand,imc,natural}-view` | `NsAdImpl#openOpeningScreenAdActivity`, `OpeningScreenADActivity#show*View` | **void 空实现**(不调 `proceed()`) |
| 全屏插屏 | `fullscreen-ad-depend-gate`, `fullscreen-ad:<cls>#<m>` ×N | `NsUtilsDependImpl#canShowScreenAd` + 按接口枚举 | → `false` |
| 短剧暂停/banner | `series-pause-*`, `series-banner-*`, `hongguo-banner-join-revert` | `SeriesPauseAdImpl`, `SeriesBannerAdConfig`, `HongguoBannerServiceImpl` | → `false` |
| 听书 | `audio-info-flow-ad`, `audio-patch-ad` | `NsAdImpl#enableRequestAudio*Ad` | → `false` |
| 短剧多集信息流/横屏插入 | `short-series-ad-enable`, `short-series-landscape-insert-ad` | `ExperimentUtil` 里读 `enableMultiSeriesFlowAd` / `landscapeInsertAdEnable` 的那个方法 | → `false` |
| 搜索页 AI 入口净化 | `purify-search-ai-{float-button,banner-entry,box-entry}` | `FanqieSearchActivity` 里读对应字段的无参 boolean getter | → `false` |
| **VIP 入口隐藏(纯装饰)** | `hide-vip-entrance`, `hide-vip-entrance-in-ad` | `NsVipImpl#canShowVipEntrance*` | → `false` |
| 激励位收口 | `inspire-disable-ad-gift`, `inspire-disable-banner-dismiss-anim` | `NsAdImpl#disableAdGift`, `#disableBannerDismissAnimation` | → `true` |
| 归因开屏绕过 | `experimental-splash-attribution` | `AttributionManager#hasHitAttribution` | **编译期关闭**(`AdHooks.kt:733` 开关 = false,合规风险) |

### squemaFQH:6 个功能,只有 2 个是广告

| # | 功能 | 目标 App | 定位方式 | 替换行为 |
|---|---|---|---|---|
| ① | 关 Lynx 横幅 | 番茄 only | DexKit **按方法名** `willShowLynxBanner` | → `Boolean.FALSE`,**不调 `proceed()`** |
| ② | **解锁会员** | 两者 | **无 DexKit**,`Class.forName("com.dragon.read.user.model.VipInfoModel")` + 遍历全部构造器 (`HookInit.java:226-229`) | 篡改构造器实参后 `proceed(args)`:`args[0]=FAKE_EXPIRE_SEC`, `args[1]="1"`, `args[2]=FAKE_EXPIRE_SEC`;再把所有 `boolean` 参数置 `TRUE`、所有 `int` 参数置 `1000000` (`:236-248`) |
| ③ | 个人页推广位 | 番茄 only | DexKit **按方法名** `canThisPositionShow` | 先 `proceed()`,**仅当** `args[0]=="PromotionFromUserPage" && args[1]==TRUE` 时改写返回对象的 `leftTime=113143670061` / `text=""` / 新建 `VipPromotionStrategyExtraInfo` (`:353-381`) |
| ④ | 伪造关注/粉丝/获赞 | 番茄 only | DexKit **按方法体内字符串** `"followUserNum = %d, fansNum = %d, recDiggNum = %d, ugcReadBookCount = "` | 就地改 `args[0]` 字段后 `proceed(args)`:`followUserNum=5200000`, `fansNum=13140000`, `recvDiggNum=9990000` |
| ⑤ | 伪造昵称"云朵"(同步入口) | 两者 | DexKit 按字符串 `"doSyncInitUserInfo:%s"` | 对**每一个非 null 参数** `setField(arg,"userName","云朵")` 后 `proceed(args)` |
| ⑤b | 伪造昵称(评论用户) | 两者 | 两步:`Class.forName("...CommentUserStrInfo")` → DexKit `findClass` **按字段类型**找出所有持有该类型字段的类 → 反射 hook 其所有"参数含自身类型"的非抽象方法 (`:279-293`, `:508-527`) | 同 ⑤ |
| ⑥ | 清空推荐用户 | 番茄 only | DexKit 按字符串 `"获取推荐用户数据成功"`(`\uXXXX` 转义写在 `:416`) | `args[0] = null` 后 `proceed(args)` |

红果只拿到 ②⑤⑤b —— 由 `installHooks(pkg, loader, fullSet)` 的 `fullSet` 布尔控制(番茄 `true` / 红果 `false`,`HookInit.java:110-116`)。注意 **VIP hook 在 `fullSet` 分支之外无条件安装**(`:168`),所以 AGENTS.md:12 说红果是 "name-only 2-hook set" 是**错的**。

---

## 4. 原理:两种完全不同的目标定位哲学

这是本次调研最有价值的发现。两个模块面对的是同一个问题(番茄被 R8 混淆过、且每次升级混淆结果都变),给出了**两种截然不同的答案**,成熟度差距很大。

### squemaFQH:锚在"名字"和"字符串"上

三个 DexKit 辅助函数(`HookInit.java:479-527`),全部是**无 `searchClass()` 收窄的全 dex 扫描**:

| 辅助函数 | 查询 | 脆弱点 |
|---|---|---|
| `findFirstNonAbstractMethodByName` | `MethodMatcher().name(name)` | 方法名被混淆就死 |
| `findFirstMethodByUsingString` | `MethodMatcher().addUsingString(s)` | 依赖日志/格式串不被改 |
| `findClassesWithFieldType` | `ClassMatcher().fields(FieldsMatcher().addForType(t))` | 依赖字段类型不变 |

问题不在选了什么锚,而在**没有防御**:

- **取第一个,不校验唯一性**。两个 finder 都直接返回 list 的首元素,没有 `.single()` 断言。DexKit 按 descriptor 排序,所以"第一个"= 字典序最小 —— 确定但任意。若两个方法都引用 `doSyncInitUserInfo:%s`,你会静默 hook 错的那个。
- **② 这个招牌功能压根不用 DexKit**(`Class.forName` 硬编码类名)。README.md:119 宣称"使用 DexKit 按特征签名扫描方法(全版本通杀)"—— 对 VIP 这条是**假的**。`VipInfoModel` 一旦被混淆,招牌功能直接死,且 DexKit 救不了。
- **`addUsingString` 是 Contains 不是 Exact**(DexKit 该重载 `@JvmOverloads matchType=StringMatchType.Contains`),README 的"引用 `<string>`"措辞会让人误以为是精确匹配。
- **`DexKitBridge` 从不 close**。DexKit 官方文档明确警告会内存泄漏。番茄是多 dex 应用,解析后的 native 内存要等 GC 跑 `finalize()` 才释放。
- **全部同步跑在 `onPackageReady` 回调线程上**,且 `onPackageReady` **没有按进程名过滤**,多进程宿主每起一个进程就重扫一遍全 dex,发生在 App 启动路径上。仓库里**没有任何计时插桩**(grep `nanoTime|elapsedRealtime` 零命中),所以实际耗时无法给出,不猜。
- **`deoptimize()` 全仓库零调用**。任何被 ART 内联到调用点的番茄方法都不会触发 hook,而且没有任何机制发现这件事。

### FanqieHook:锚在"语义"上,并且明确拒绝猜

`ClassResolver.kt` 是一套**五级降级查找**,越往后越抗混淆:

| 级 | 方法 | 锚定信号 | 抗什么 |
|---|---|---|---|
| 1 | `findClass` (`:60-70`) | 精确 FQCN | 什么都不抗 |
| 2 | `findMethod`/`findMethodOn` (`:76-103`,`:151-169`) | 类名 + 方法名 + 精确参数类型 | 参数类型改名就死 |
| 3 | `findMethodIgnoringParams` (`:176-210`) | 类名 + 方法名 + **只看返回类型** | 抗参数类改名(实测 `so4.h`→`vq4.i`→`ti4.h` 漂移) |
| 4 | **`findNoArgBooleanGetterReadingField`** (`:234-310`) | DexKit:`declaredClass(X)` + `paramCount(0)` + `returnType("boolean")` + `addUsingField(FieldMatcher().name(F), UsingType.Read)` —— 即"**哪个无参 boolean 方法读了配置字段 F**" | **抗方法名改名**,锚在稳定的业务字段名上 |
| 5 | `findClassImplementingInterface` (`:423-448`) | DexKit:`interfaces { add(iface) }` + 可选方法名 | **抗实现类改名** |

第 4 级是核心创新。它**不匹配方法体内的字符串常量**(Xposed 圈的常见做法),而是匹配**字段读取边**——这是更强更稳的信号。`:218-232` 的注释记录了逼出这个设计的真实事故:73732 上 `landscapeInsertAdEnable` 由 `ExperimentUtil#q0()Z` 读取;73917 上 `q0` 变成了返回 `long` 的另一个配置,开关搬到了 `s0()Z`。按名字锚会静默 hook 错开关,按字段读取边锚则**零代码改动自动跟上**(`FanqieModule.kt:284-286` 记录了这次 73732→73967 的原地升级)。

两个必须学的实现细节:

1. **`addUsingField(String)` 会把参数当完整字段描述符解析并抛 `IllegalAccessError`**;代码刻意用只带 name 的 `FieldMatcher`,因为同一字段名可能挂在多个类上(`:254-260`)。
2. **拒绝猜测策略**(`:268-302`):恰好 1 个命中 → 用;0 个 → `null` + WARN;>1 个 → **只有当恰好一个是 `static` 时才接受**,否则 `null` + `"refusing to guess"`。理由写在 `:230-232`:hook 错方法会静默翻错开关,**比不 hook 更糟**。

配套的**未命中诊断**同样值得抄:`describeCandidates`(`:117-143`)列出同类下同参数数量的最多 12 个方法名,**按与目标名的公共前缀长度降序**排(`commonPrefixLength` `:145-149`)—— 于是 `disableAdGift`→`disableAdGiftV2` 会排在第一个。改名这件事从"重新审一遍 dex"降级成"读一行日志"。

再加一道保险:`HookManager` 的布尔替换会先校验 `method.returnType == Boolean.TYPE`,不匹配就 skip(`:132-137`)。这就是为什么上面那个 `q0`→`long` 的漂移是**失败安全**的(跳过)而不是**失败危险**(hook 错开关)。

### 结论

**融合时应当把 squemaFQH 那 5 条 DexKit 定位全部重写到 FanqieHook 的 `ClassResolver` 上。** 这不是风格偏好:第 4/5 级查找能力 squemaFQH 完全没有,而它现有的"取第一个 + 无返回类型校验 + 不 close bridge + 不按进程过滤"每一条都是已知的线上事故模式。

---

## 5. 关键机制深潜

### 5.1 FanqieHook 的"零广告中心闸门"(最重要的机制)

`AdHooks.kt:247-280`。`installBooleanFilter` 的 `shouldBlock` 闭包**无条件返回 `true`**(`:277`),于是 `HookManager.kt:178` 的 `if (shouldBlock(chain.args)) false else chain.proceed()` 永远短路成 `false`。这个"过滤器"是退化的 —— 它就是**一个硬 `false` 加一行日志**。

为什么能自动拦住未来新增的广告位:`checkAdAvailable(String position, String source)` 是宿主自己的**中央"这个位置现在能出广告吗"查询**。对**所有**参数值都回答"不能",意味着任何广告位——包括未来版本新增、本模块从没见过的——都拿不到填充。`:256-262` 的设计注释写得很明白:"不再区分被动位 / 用户主动的激励金币位 / 未知位,也不再需要维护拦截名单"。`FanqieModule.kt:318-320` 有实证:红果 73932 新增了 23 个广告类(含一个全新的 `IDrawRewardAdService`),零登记全自动拦住。

纵深防御:闸门同时装在 `NsAdImpl` **和**每一个 DexKit 找到的 `NsAdConfigManagerApi` 实现类上(`:229-245`,`:241` 有去重)。

**代价(README.md:16-18 已诚实披露):** 用户主动点的"看视频得金币 / 看广告免广告 / 看视频催更 / 听书激励 / 看视频解锁章节"全部失效,因为那里已无广告可播。README 同时正确指出**发奖由服务端校验,本地拦广告不会让金币白得**——"激励视频秒领"明确不支持(CHANGELOG 0.7.0/0.7.1 记录了这个功能上线后因实机金币不涨被回滚)。

> 副作用:`BLOCKED_POSITIONS`(22 条,`:634-676`)和 `PRESERVED_POSITIONS`(24 条,`:694-729`)自 v0.8.4 起**纯粹是日志分类学**,不再是过滤器。`PRESERVED_POSITIONS` 这个名字现在是误导——那些激励位也被拦了。

### 5.2 DexKit native 库加载:两条不同的路

DexKit 的 Java 层**自己不调 `System.loadLibrary`**,在被注入的宿主进程里所有 native 调用都会死于 `UnsatisfiedLinkError: No implementation found for ... nativeInitDexKit`。两个仓库各自解决了一遍:

| | squemaFQH (`HookInit.java:450-476`) | FanqieHook (`ClassResolver.kt:350-411`) |
|---|---|---|
| 桥构造 | `DexKitBridge.create(classLoader, true)` —— 第 2 参是 `useMemoryDexFile`(cookie 内存 dex),**不是缓存标志**(代码注释 `:443-444` 说错了) | `DexKitBridge.create(apkPath)` —— 走 **APK 路径** |
| 主路径 | `System.loadLibrary("dexkit")` | 同 |
| 兜底 | `System.load(getModuleApplicationInfo().nativeLibraryDir + "/libdexkit.so")` | **把模块自己的 APK 当 ZIP 打开**,按 `Build.SUPPORTED_ABIS` 挑第一个有 `lib/<abi>/libdexkit.so` 的条目,拷到宿主可写 cache 目录再 `System.load()` 绝对路径 |
| 兜底可用性 | **很可能是死路**:manifest 没设 `android:extractNativeLibs`,AGP 对 `minSdk 26` 的默认值是 `false`(so 压缩页对齐留在 APK 内),`nativeLibraryDir` 里根本没有物理文件 | 可用,且带 `outFile.length() != entry.size` 的重抽取判定(`:388`) |
| 热重载 | 无处理 | `UnsatisfiedLinkError` 消息含 "already" 视为成功(`:398-404`) |
| 并发保护 | `AtomicBoolean` 双检锁 | 懒构建 + `nativeLibLoaded` 标志 |
| 失败后果 | **重抛** `UnsatisfiedLinkError` | **降级**:返回 false,DexKit 查找全部关闭,反射类查找继续工作 |

**两个桥构造重载不同,这是融合时最需要验证的一点**(见 §8)。FanqieHook 的"从自己 APK 里解压 so"方案明显更可靠,融合后应统一采用。

### 5.3 squemaFQH 的会员解锁实际做了什么

`HookInit.java:225-255`,已在 §3 表格中列出。三个技术问题:

1. **类型盲改写**。`args[0..2]` 被赋 `String` 值,唯一的守卫是 `args.length >= 3`(`:236`),**没有检查这三个参数是不是 `String`**。随后的循环又把 boolean/int 覆写一遍(`:241-248`)。一个"第 2 参是 boolean"的 3 参重载会被塞进 `"1"` → 框架层失败 → 被 PROTECTIVE 吞掉 → **那个重载静默变成没 hook**。另外按它自己 javadoc(`:34-35`)声称的签名,index 5 是 `int unionSource`,被强写成 `1000000` —— 语义上是胡来。
2. **常量注释算错了**。`:74` 说 `113143670061000L` ms ≈ 5355 年;实际是 **5555-05-20T05:14:21Z**,约 3585 年,差 ~200 年。不影响功能。
3. **`FAKE_AVATAR_URL`(`:80`)是死变量**,声明后从未引用。

**关于实际收益(重要,且是推断):** 番茄小说本体是免费+广告模式,`VipInfoModel` 是客户端的会员态模型。构造器篡改只改**客户端内存里的对象**,而 §5.1 已说明**发奖/权益由服务端校验**。因此这条 hook 能拿到的,是那些**纯客户端判定**的 UI/行为开关(比如"你是 VIP 所以不显示某些推广位""VIP 才可用的本地功能入口");服务端 gated 的内容不会因此解锁。**这一点无法在无 APK/无实机的情况下证实,标记为未验证假设。**

同时注意一个**功能冗余**:番茄的付费会员主要卖点就是免广告,而 FanqieHook 的零广告闸门已经把广告拦干净了。也就是说**合并后,会员解锁在"免广告"这个最主要的诉求上几乎是纯冗余的**。

### 5.4 squemaFQH 的失败隔离(README 的说法基本成立)

三层,粒度不同:

- **(a) 调用期,per-hook**:`ExceptionMode.PROTECTIVE`,6 处 builder 站点(`:233, :303, :328, :342, :397, :422`)。hooker 抛异常由框架捕获记日志,调用按"无 hook"继续 → **退化成广告照常显示,永远不会崩宿主**。`module.prop:4` 也声明了 `exceptionMode=protective`(本来就是默认值),属于双保险。
- **(b) 安装期,per-feature**:`safeHook(name, ThrowingRunnable)`(`:538-548`),6 个安装步骤逐个包裹(`:168, :174-178, :184`)。捕获的是 `Throwable` 而非 `Exception`,所以 `UnsatisfiedLinkError`/`NoClassDefFoundError` 也能兜住。**这才是 README.md:119"单条失败不影响其余"成立的真实原因。**
- **(c) 回调内部,静默吞掉**:`setField`/`setIntField` 调用点被 `catch (ReflectiveOperationException ignored)` 包住(`:312, :373, :405`),**完全不打日志**。字段名写错的话是彻底隐形的。

FanqieHook 的对应机制更细:`installInternal`(`HookManager.kt:201-228`)整体 try/catch `Throwable`,可选的 `deoptimize` 失败单独 `runCatching` 只 WARN(`:210-211`)。**但它有个反向缺陷**:`AdHooks.installAll()`(`:40-54`)对 13 个类别函数是**裸调用,没有 per-category try/catch**,而 `:37-38` 的注释却声称"每个 installXxx 内部都有 try/catch"。今天的隔离性实际来自另外两层(ClassResolver 从不外抛异常 + installInternal 兜底),但**声称的不变量比实际强** —— 任何一个类别函数抛出 `Throwable` 会中止剩余类别。

### 5.5 状态上报管线:squemaFQH 的 90% 是死代码

这是 squemaFQH 最触目惊心的部分,也是**融合后最值得修的**。

**结论先行:番茄/红果的 LIVE 状态灯永远点不亮。五条通道里四条是死代码,第五条被 SELinux 挡住。** 仓库自己的实机日志印证了这点 —— `.workbuddy/memory/2026-09-09.md` 记录:"主 App 5 张卡片正确显示(番茄/红果 **SCOPE**,夸克 LIVE+设置,小X IDLE,Cherrygram **SCOPE**)"。

设计路径(README.md:91-95 / AGENTS.md:39-46 所述):
> hook 在目标进程触发 → `HookStatusReporter.reportTargetHooked(pkg)` → `ServiceManager.getService("com.byterax.phoenix.read.status")` → `IHookStatusService` → system_server 里的 `HookStatusService` → `SystemAmCompat.pushBinderToModuleApp` → `ServiceProvider.call` → `ServiceClient.linkService(binder)` → `ScopeStatus.isReported` → MainActivity chip

为什么永远走不通:

| # | 断点 | 证据 |
|---|---|---|
| 1 | **`SystemBootstrap.start(ClassLoader)` 零调用者**。它的 javadoc(`xposed/SystemBootstrap.java:9-10`)说"由 HookInit 通过 `onSystemServerStarting` 调用",但 **HookInit 从未 override `onSystemServerStarting`** | grep 全仓只有定义处 |
| 2 | **`scope.list` 里没有 `system` 条目**,只有 `android`。libxposed 文档明确要求 system_server 用虚拟包名 `system` 显式声明 | `scope.list:1` |
| 3 | 后果:`HookStatusReporter.reportTargetHooked`(`:13-26`)永远在 `:18-20` 看到 `binder == null` 静默返回;`ServiceClient.tryConnect()`(`:79-95`)永远失败 | MEMORY.md 自己承认:"AIDL 上报——实测**不可用**(system_server 通道未启动)" |
| 4 | **`HookStatusStore.markHooked` 零调用者**。SharedPreferences `pref_hook_status_v1` 从未被写入,`ScopeStatus.isReported` 的第一道检查永久为 false。`:74-92` 那整块"迁移旧文件"的代码迁移的也是一个从未被写过的文件 | grep |
| 5 | **`ScopeStatus.isRunningTarget` 因依赖版本而死**。它反射探测 `getRunningTargets()`(`:125`),把 `NoSuchMethodException` 当"service 101 没这方法"处理(`:131-132`)。而 `app/build.gradle:42` 就是 `service:101.0.0`,`getRunningTargets()` 是 API 102+ 才加的 | **改成 102.0.0 是这个文件里性价比最高的一处修复** |
| 6 | **`/data/local/tmp` marker 是代码里唯一端到端接通的通道**(`markTargetHooked` 确实在 `:189` 被调,`isReportedByMarker` 确实读),但 `/data/local/tmp` 是 `shell:shell` 0771、SELinux type `shell_data_file`,**enforcing 下 `untrusted_app` 域无读写权**。`writeFile`(`:112-119`)catch Throwable 只 `Log.w`;`:123-130` 那个反射 `chmod(0666)` 救不了——`FileOutputStream` 的 open 先失败 | 在 stock enforcing 设备上是静默 no-op |
| 7 | **`RuntimeDetector` 是 100% 死代码**,全仓零引用,只有 README.md:93 / AGENTS.md:44 / `.git-commit-msg.txt:6` 提到它。它自己的 javadoc(`:16-19`)都承认"None of these checks are authoritative" | grep |
| 8 | **即使全修好还有个顺序 bug**:`reportTargetHooked(pkg)` 在 `:112`/`:115` 被调 —— **在 `installHooks` 之前**,`:188` 又调一次。状态灯会在一条 hook 都还没装的时候宣称 LIVE | — |

**今天真正驱动卡片的是:** `HookApp.onCreate`(`:46-70`)注册 `XposedServiceHelper.OnServiceListener` 缓存 binder → `ScopeStatus.isInScope`(`:70-84`)调 `service.getScope()` 查 contains → `resolve`(`:50-58`)映射成 OFF/SCOPED/LIVE。因为 running 和 reported 都永久为 false,**番茄/红果卡片只可能是 OFF 或 SCOPED**。

**唯一真正工作的 LIVE 通道是夸克那条,而且没被复用:** `QuarkHookMain.markActiveViaProvider` 向 `content://com.byterax.phoenix.read.quark` 发 `method="mark"`(`QuarkHookMain.java:150`),`QuarkConfigProvider.call` 写一个 SharedPreferences 时间戳并返回(`:70-80`),`ScopeStatus.isReportedByMarker` 对夸克特判(`:163-166`)。**跨进程 `ContentProvider.call`(目标进程 → 模块 App)是这个仓库里唯一被验证可用的模式。**

> ⚠️ 但这条路对番茄/红果有个未解决的障碍:API 30+ 上**目标 App 需要对模块有 package visibility** 才能解析到那个 provider。夸克作为浏览器很可能自带 `QUERY_ALL_PACKAGES`,**番茄/红果未必有**。这条必须在实机上验证,不能假设。
>
> libxposed 原生替代方案是 `getRemotePreferences(group)` + `openRemoteFile`/`listRemoteFiles`(API 102,`PROP_CAP_REMOTE`),本仓未使用。**但注意方向不对**:remote preferences 在模块 App 侧可写、在被 hook 侧**只读**,而我们需要的是"目标进程写 → 模块 App 读"。

**组件取舍表(融合时直接照抄):**

| 组件 | 判定 | 删掉的后果 |
|---|---|---|
| `HookApp` + `XposedServiceHelper` | **必需** | 卡片彻底失去 scope 检测,全显示 OFF/IDLE。**唯一在工作的通道** |
| `ScopeStatus` | **必需** | 状态机没了,UI 无逻辑 |
| `MainActivity` | **必需** | UI 本身 |
| `HookStatusFiles` | 接通但无效 | 现代设备上无变化;在 `/data/local/tmp` 确实可写的 rooted/`su` 上下文 ROM 上,它是唯一可能的 LIVE 来源 |
| `HookStatusStore` | **死**(写侧从未被调) | 无 |
| `HookStatusReporter` | **死**(remote 永远 null) | 无 |
| `service/*` + `IHookStatusService.aidl` | **死** | 无功能损失,还能顺手去掉 `aidl true` 和那个**无权限保护的导出 provider** |
| `xposed/*`(4 个文件) | **死**,且是 `compileOnly de.robv.android.xposed:api:82` 的唯一消费者 | 无,还能**整条删掉经典 Xposed 依赖** —— 顺带消灭 README 陷阱 #3 |
| `LspScopeReader` | 仅兜底,且**危险** | `isInScope` 失去无 service 时的兜底。见下 |
| `RuntimeDetector` | **死** | 无 |

**`LspScopeReader` 的两个真实问题**(`:66-67`, `:107-108`, `:136-142`):它 `su -c cp /data/adb/lspd/config/modules_config.db` 到 cache 再只读打开,查 `SELECT app_pkg_name FROM scope WHERE module_pkg_name=? AND user_id=0` 和 `SELECT enabled FROM modules_state ...`。**没声明任何权限,靠的是交互式 Magisk Superuser 授权,而仓库里没有任何地方申请或说明这件事。** 问题:(i) 它在 `XposedService` 未绑定时从 `MainActivity.onResume` 可达 → **主线程上阻塞式 `su` exec + `waitFor()`,Magisk 弹授权框就 ANR**;(ii) `MainActivity.java:81-82` 在 `refreshCards()` 前立刻调 `invalidate()`,**故意让 3 秒 TTL 缓存在每次 onResume 都失效**。

---

## 6. 融合可行性:真实冲突清单

### 6.1 逻辑层:几乎不冲突

**硬证据 —— 零功能重叠:**

| 标识符 | FanqieHook | squemaFQH |
|---|---|---|
| `willShowLynxBanner` | 0 处 | 1 处 |
| `canThisPositionShow` | 0 处 | 1 处 |
| `CommentUserStrInfo` | 0 处 | 1 处 |
| `doSyncInitUserInfo` | 0 处 | 1 处 |
| `followUserNum` | 0 处 | 1 处 |
| `PromotionFromUserPage` | 0 处 | 1 处 |
| `checkAdAvailable` | **2 处** | **0 处** |
| `NsAdImpl` / `NsAdConfigManagerApi` / `IActivityScreenAdManager` | 多处 | **0 处** |
| `VipInfoModel` | **1 处,且只是注释** | 1 处(真 hook) |

那个 `VipInfoModel` 命中在 `AdHooks.kt:329-330`,原文是一句**免责声明**:

> `// Cosmetic only: hides VIP upsell entry points; does NOT touch entitlement data, VipInfoModel, or any server-validated VIP flag.`

**所以两者是严格互补:FanqieHook 明确拒绝碰权益数据,squemaFQH 恰好只碰权益数据(外加 2 条窄广告 hook 和 3 条 UI 装饰 hook)。** 不存在"同一条 hook 装两遍""两个模块抢同一个方法"的经典合并难题。

**语义层面的潜在相互作用(需实机验证,均为推断):**

1. squemaFQH ① `willShowLynxBanner`→false 和 FanqieHook 的 `checkAdAvailable`→false 是**不同层的两道闸**。合并后前者很可能变冗余 —— 但不会冲突,两个 `false` 叠一起还是 `false`。**建议合并后实测:单独关掉 ① 看 Lynx 横幅是否仍被拦。若是,直接删 ①。**
2. squemaFQH ③ 改写 `canThisPositionShow` 的**返回对象**(不是返回值),FanqieHook 不碰这个方法。不冲突。
3. squemaFQH ② 让客户端认为自己是 VIP,FanqieHook `hide-vip-entrance` 隐藏 VIP 购买入口。**方向一致**(既然"是 VIP"就不该看到购买入口),不打架。

### 6.2 构建工具链:这里才是真冲突

| 项 | squemaFQH | FanqieHook | 融合决策 | 风险 |
|---|---|---|---|---|
| AGP | **9.2.0** | **8.7.3** | 必须取 **9.x** —— squemaFQH README 陷阱 #4 记录 AGP 8.x 无法消费 `android-37.0` 新格式 SDK platform(`Failed to find Platform SDK platforms;android-37`) | **高** |
| Gradle | **9.5.1** | 8.11.1 | 9.5.1 | 中 |
| Kotlin | 不用 | **2.0.21** | **需验证 Kotlin 2.0.21 是否与 AGP 9.2.0 兼容**,大概率要升到 2.2+ | **高,最大的未知数** |
| JDK | 17(`gradle-daemon-jvm.properties` toolchainVendor=JETBRAINS + foojay resolver 1.0.0) | 17(`compileOptions`/`jvmTarget`) | 17,采用 squemaFQH 的 foojay daemon toolchain 方案(更省心) | 低 |
| compileSdk / targetSdk | **37 / 37** | 35 / 35 | **37 / 37**,并保留 `android.suppressUnsupportedCompileSdk=37` | 中 |
| buildToolsVersion | `37.0.0` 显式 | 未指定 | 显式 37.0.0 | 低 |
| minSdk | 26 | 26 | 26 | — |
| `abiFilters` | **未设 → 打 4 个 ABI** | **`arm64-v8a` only** | **arm64-v8a only**。FanqieHook `app/build.gradle.kts:19-25` 有实测依据:番茄 73532/73732 的 APK 分别 117/116 个 `.so` **全在 arm64-v8a 下**,红果同基线;v7a 永远用不上(宿主 arm64-only,32 位机装不上宿主),x86 只对模拟器有意义 | 低。**APK 体积大幅下降** |
| `minifyEnabled` | **false**,且 `proguard-rules.pro` 是 100% 注释掉的空模板 | **true** + `shrinkResources=true` + 一套完整 keep 规则 | **采用 FanqieHook 的**。squemaFQH 现在"安全"仅仅因为混淆是关的;**一旦开启而没补 `-keep class * extends io.github.libxposed.api.XposedModule` 和 `-adaptresourcefilecontents META-INF/xposed/java_init.list`,模块会在加载期直接坏掉** | 中 |
| 签名 | **完全没有 `signingConfigs`**,release 产出未签名 APK | 有 `ci` config,全走环境变量,未设 `KEYSTORE_PATH` 时回退 debug 签名 | 采用 FanqieHook 的 | 低 |
| `packaging` | 未设 | `resources.merges += "META-INF/xposed/*"` + excludes kotlin_module/AL2.0/LGPL2.1 | **必须保留 `merges`**,否则两个来源的 `META-INF/xposed/*` 会冲突 | 中 |
| `buildFeatures` | `aidl true`, `buildConfig true`(且 `buildConfigField SERVICE_VERSION` **生成了但全仓零引用**) | 未设 | **两个都可以删**(AIDL 是死代码,见 §5.5) | — |
| 仓库源 | Aliyun 镜像优先 + clojars(噪音)+ jitpack + google + mavenLocal + mavenCentral;注意 `allprojects` 里 mavenLocal/mavenCentral **排在 google() 之前**,与 `buildscript` 顺序不一致 | 常规 | 取一份干净的,去掉 clojars | 低 |

### 6.3 依赖冲突

| 依赖 | squemaFQH | FanqieHook | 决策 |
|---|---|---|---|
| `io.github.libxposed:api` | `compileOnly` 102.0.0 | `compileOnly` 102.0.0 | ✅ 一致,保持 `compileOnly`(改成 implementation 会与宿主框架冲突 —— 两边 README 都列为头号陷阱) |
| `org.luckypray:dexkit` | `implementation` **2.2.0** | `implementation` **2.0.4** | ⚠️ **版本冲突,必须统一**。建议 **2.2.0**(新)。但 **FanqieHook 的 `ClassResolver` 是对着 2.0.4 写的,升级后必须验证 API 兼容** —— 尤其 `DexKitBridge.create(String apkPath)`、`FieldMatcher`/`UsingType`、`findClass`/`findMethod` 的 Kotlin DSL。坐标注意是小写 `org.luckypray:dexkit`,**不是** `io.github.lsposed:dexkit`(那是旧 1.x 线) |
| **桥构造重载不同** | `create(classLoader, true)` | `create(apkPath)` | **统一到 apkPath 版本**(配合 FanqieHook 那套更可靠的 so 解压兜底)。注意 classLoader 版本在 loader 不是 `BaseDexClassLoader` 时会调 Kotlin `error()` 抛 `IllegalStateException` |
| `io.github.libxposed:service` | `implementation` **101.0.0** | 不用 | 保留但**升到 102.0.0**,这样 `ScopeStatus.isRunningTarget` 的 `getRunningTargets()` 才存在(见 §5.5 #5) |
| `de.robv.android.xposed:api:82` | `compileOnly` | 不用 | **删掉**。唯一消费者是死文件 `xposed/SystemUserService.java` |
| `androidx.annotation:annotation:1.7.1` | `implementation` | 不用 | 随 service 保留 |
| Kotlin stdlib | — | 随 Kotlin 插件 | 新增 |

### 6.4 入口与元数据

| 项 | squemaFQH | FanqieHook | 融合决策 |
|---|---|---|---|
| `java_init.list` | 1 行 `com.byterax.phoenix.read.HookInit` | 1 行 `dev.operit.fanqiehook.FanqieModule` | **合并成 1 个入口类**(不能有两行去分别装两套 hook 而互不知情 —— 会各自建 DexKit 桥、各自扫全 dex) |
| 入口类构造器 | **两个**:`HookInit(XposedInterface, ModuleLoadedParam)` + `HookInit()`,函数体都只是 `super();`。这是 LSPosed 1.9.2(v7024)~2.2.0 的兼容垫片 —— LSPosed 反射查找时优先命中旧签名,**缺任何一个都会抛 `NoSuchMethodException` 跳过整个模块** | **一个**隐式无参构造器(Kotlin 类不声明构造器) | **保留双构造器**,框架兼容面更宽。⚠️ 但注意:两个构造器都调**无参** `super()`,而 libxposed 文档说框架会自动调 `attachFramework()` 且模块"must not call it"。作者实机日志证明这套能跑,但**这是在依赖 LSPosed 的反射查找顺序,不是依赖文档化契约 —— 是个脆弱的东西,不要盲目抄** |
| `module.prop` | **只有 4 个键**:`minApiVersion=102`, `targetApiVersion=102`, `staticScope=true`, `exceptionMode=protective`。**缺 `id`/`name`/`version`/`versionCode`/`author`/`description`** | **完整 11 键**,含 `updateJson`、`actionIcon`、`autoHotReload=false` | **用 FanqieHook 的当模板**,补全 squemaFQH 缺的字段 |
| `minApiVersion` | 102 | **101,但代码用了 102-only 的 API** —— `HookBuilder.setId()`(`HookManager.kt:214`)是 `@SinceApi(API_102)`,`onHotReloading`/`onHotReloaded`/`HotReloadingParam` 也是(`FanqieModule.kt:157,164`)。在严格 101 框架上入口类会引用不存在的类型 | **改成 102**,消除这个不一致(未在 101 设备上验证,是从 API 注解推断) |
| `scope.list` | `android` + 5 个目标 | 2 个目标 | **`com.dragon.read` + `com.phoenix.read`**。`android` 那条是给死的 system_server 通道用的,**删掉**;漏写 scope 的后果是 LSPosed Manager 里根本不显示该目标 → 无法勾选 → hook 永不触发(MEMORY.md 陷阱 #4) |
| Manifest 权限 | **零个 `<uses-permission>`** | 零个 | 保持零。`<queries>` 建议列全两个目标(squemaFQH 只列了 5 个中的 3 个) |
| 导出组件 | **3 个全导出且无权限保护**:`MainActivity`(launcher)、`ServiceProvider`(authority `${applicationId}.ServiceProvider`,**无 `android:permission`**,仅运行时 `getCallingPackage()` 判断 `:50,:58`)、`QuarkConfigProvider`(**同样无保护**,`call()` 接受 `mark`/`get`/`get_all`/`set`/`set_string`,**看不到调用方包名检查 —— 设备上任何 App 都能翻夸克的 bypass 开关**) | `<application>` 零个 Activity | **删掉 AIDL/ServiceProvider 整条(死代码 + 攻击面)**。若要保留任何 provider,**必须加 `android:permission` 且校验调用方** |
| 进程门控 | **无** → `onPackageReady` 每个进程都触发,多进程宿主重复全 dex 扫描 | **有**:`packageName ∈ TARGET_PACKAGES` + `isFirstPackage` + **`processName == packageName`**(显式排除 `:push` / `:widgetProvider` / `:miniappX`,`FanqieModule.kt:62,69,73,77-79`) | **采用 FanqieHook 的门控**。这直接修掉 squemaFQH 的一个真实性能 bug |

### 6.5 许可证:这是硬约束

- squemaFQH = **GPL-3.0**(完整未修改文本,35823 字节)
- FanqieHook = **MIT**(`Copyright (c) 2026 afwfv`)
- DexKit = **LGPL-3.0**(两边 README 都标注了)
- `io.github.libxposed:service` = Apache-2.0

**结论:合并后的衍生作品必须整体以 GPL-3.0 发布。** MIT → GPL-3.0 是单向兼容的(MIT 代码可以并入 GPL 项目,反之不行),所以合法,但**你没有选择**:一旦引入 squemaFQH 的 GPL 代码,整个模块就是 GPL-3.0,必须附完整对应源码。想保持 MIT/闭源的话,**只能重写而非复制 squemaFQH 的代码**。

**另一处两个仓库都没处理的暴露面:** LGPL-3.0 要求接收者能够替换/重链接该库。DexKit 是 `implementation`,`libdexkit.so` 被静态打进 APK,而 squemaFQH 的 AGENTS.md:100 明写"**绝不能改成 `compileOnly`**"(否则 so 加载不了)—— 这恰好堵死了让 LGPL 姿态变干净的那唯一配置。在 GPL-3.0 APK 里塞一个不可替换的 LGPL-3.0 native 库是 Android 圈的常见灰区;LGPL-3.0 §3 确实允许按 GPL-3.0 条款分发,但**两个仓库都没有任何地方记录这个选择,也没有 NOTICE / 第三方归属文件,没有对应源码提供声明或重链接说明**。融合项目应当补上。

### 6.6 可搬运性(代码层面很干净)

**FanqieHook 侧:** Kotlin 代码异常好搬 —— **没有 `R` 类、没有 `BuildConfig`、没有资源依赖、没有 SharedPreferences、没有 AndroidX**(grep 全零命中)。

| 文件 | 行数 | 依赖 | 可搬运性 |
|---|---|---|---|
| `ApkVersion.kt` | 160 | **只依赖 `java.util.zip`** | ✅ 完全自包含,一个手写的二进制 AXML `versionCode` 解析器,任何注入上下文都能用 |
| `ModuleLog.kt` | 110 | `XposedModule`, `android.util.Log` | ✅ 改一处 TAG(`:100`) |
| `HookManager.kt` | 253 | libxposed 类型 + ModuleLog | ✅ **通用,不含任何番茄/字节知识**,任何 libxposed 模块可复用 |
| `ClassResolver.kt` | 500 | ModuleLog + DexKit + `Build` | ✅ **通用,不含宿主知识** |
| `AdHooks.kt` | 734 | 上面三个 | ✅ **这才是载荷**,41% 是注释 |
| `FanqieModule.kt` | 334 | 全部 + ApkVersion | ⚠️ 需重写 —— 334 行里有 160 行是 `SUPPORTED_VERSION_CODES` 的审计长文 |

耦合点:包名 `dev.operit.fanqiehook` 出现在 6 处 `package` + 3 处 import,**但从未作为字符串字面量被 Kotlin 引用**,所以改名是纯机械操作(查找替换 + `java_init.list` 1 行 + `proguard-rules.pro:6,10` + `build.gradle.kts:7,13`)。

**squemaFQH 侧:** `HookInit.java` 575 行里 **~510 行是番茄/红果专属或可复用的**,多目标分发只占 ~65 行(3 个 import、`onPackageLoaded` 14 行、`onPackageReady` 的 else 分支 25 行、3 个 `PKG_*` 别名)。**番茄/红果-only 的 fork 是一次删除,不是重写。**

改名会断的地方(按严重度):

| 耦合点 | 严重度 | 说明 |
|---|---|---|
| `java_init.list:1` | **高且静默** | 必须改成新 FQCN。忘了 → 模块永不加载,**无错误、无日志** |
| AIDL 包名 + 目录 | 高(若保留) | `Stub.DESCRIPTOR` 就是那个字符串,两端必须一致。**建议直接删(死代码)** |
| `Constants.MODULE_PACKAGE`(`:4`) | 中 | 手工维护的 `applicationId` 副本;`SERVICE_NAME`(`:15`)和 `PROVIDER_AUTHORITY`(`:18`)由它派生。`buildConfig true` 是开的、`BuildConfig.APPLICATION_ID` 存在,但**从未被使用** —— 没有任何机制保持同步 |
| Manifest `${applicationId}` 占位符(`:34,:39`) | 低 | 自动跟随。但 `QuarkHookMain.java:150` 硬编码了字面量 `content://com.byterax.phoenix.read.quark`,与占位符不一致 |
| `R` 类 | **对 hook 零影响** | `HookInit` 零处 `R` 引用。只有 `MainActivity` 和 `QuarkSettingsActivity` 用 |
| `HookStatusFiles`/`HookStatusReporter` 调用 | 低 | 5 处调用点(`:112, :115, :165, :188, :189`)。删掉后 hook 集就**完全与包名无关** |
| `settings.gradle:5` | 装饰 | `rootProject.name = "FanQieHook"` —— 这个 Gradle 项目至今仍用上游的名字,不叫 Squema |

---

## 7. 效果预估:合并后到底能得到什么

### 能拿到(高置信)

1. **广告拦截能力 = FanqieHook 原样**,33 条 hook,含那条"宿主以后新增广告位也自动拦"的中心闸门。这是两个仓库里唯一的硬能力,而且质量明显高于 squemaFQH。
2. **一套抗混淆的定位引擎**,并且把 squemaFQH 那 5 条脆弱的"按名字/按字符串取第一个"查找升级到语义锚定。
3. **UI 净化的 3 条**(清空推荐用户、伪造昵称、伪造社交数字)—— 纯本地显示层,与广告系统无耦合,低风险。
4. **一个真正能用的状态面板**:2 张卡片 + 修好的 LIVE 检测(§5.5 给了明确的修复路径:删死代码 → `service` 升 102 拿 `getRunningTargets()` → 夸克那套 `ContentProvider.call` 作为 best-effort)。FanqieHook 完全没有 UI,squemaFQH 的 UI 对番茄/红果永远只显示 SCOPE —— 这块是**实打实的增量**。
5. **工程健壮性的合并收益**:FanqieHook 的进程门控(修掉 squemaFQH 的多进程重复扫描)+ 返回类型校验(修掉 squemaFQH 的类型盲改写)+ 三路日志通道 + `install summary` 汇总行 + `auditHit` 命中审计(squemaFQH 完全没有)+ DexKit 桥共享与正确 close(修掉泄漏)。

### 拿不到 / 会失去(必须提前讲清楚)

1. **金币和激励视频会失效,而且是设计使然**。零广告闸门不区分被动位和用户主动的激励位。"看视频得金币""看广告免广告""看视频催更""听书激励""看视频解锁章节"全部无广告可播。FanqieHook README.md:16-18 已诚实披露,并且明确说明**发奖服务端校验,本地拦广告不会让金币白得**;"激励视频秒领"不在支持范围(0.7.0 上线、0.7.1 因实机金币不涨回滚)。
   → **如果项目的核心诉求包含金币,这个融合方向从根上就不成立**,因为两个仓库都不做、且都说明了为什么不做。
2. **squemaFQH 的另外 3 个目标(夸克/小X/Cherrygram)不在范围内**。它们的代码可以整目录删除(`quark/` `xiaox/` `cherrygram/`),但那意味着放弃 squemaFQH 约 2.6k 行代码的既有能力。要保留就是另一个项目了。
3. **经典 Xposed API 兼容 = 不可能**。两者都是纯 Modern API 102。
4. **零用户可配置性 → 需要新建**。两个仓库对番茄/红果都没有任何运行时开关(FanqieHook 连 Activity 都没有,squemaFQH 的设置页只服务夸克)。想做"按广告位粒度开关""保留激励位"这类功能,**是从零写**,不是合并。

### 会员解锁这条,建议不纳入(两个理由)

**工程理由(主要):功能冗余。** 番茄/红果本体是免费+广告模式,付费会员的主要卖点就是免广告。FanqieHook 的中心闸门已经把广告全拦了,再伪造客户端 `VipInfoModel` 在"免广告"这个最主要诉求上**几乎不增加任何东西**。而它能碰到的只是纯客户端判定的 UI 开关;§5.1 和 FanqieHook 自己的注释都指向同一个事实——**权益是服务端校验的**。同时这条 hook 的实现质量是 squemaFQH 里最差的:不用 DexKit(硬编码 `Class.forName`,README 关于"全版本通杀"的说法对它是假的)、类型盲改写会让某些重载静默失效、没有任何命中审计证明它在调用链上。

**合规理由:** 绕过付费会员校验属于规避服务的访问控制,这部分我不参与设计和实现。上游代码是公开的,`HookInit.java:225-255` 就是全部实现,本报告的 §3/§5.3 已如实记录其机制以供调研完整——但**融合项目的实现范围我建议明确排除它**。

> 顺带说明: FanqieHook 在 `AdHooks.kt:329-330` 主动写下"does NOT touch entitlement data, VipInfoModel, or any server-validated VIP flag",并把唯一那条更激进的 `AttributionManager#hasHitAttribution` 绕过用编译期常量关掉了(`:733`,注明合规风险)。这个自我约束的边界划得很清楚,值得作为新项目的基线。

---

## 8. 风险登记

| 风险 | 等级 | 说明 / 缓解 |
|---|---|---|
| **账号风控** | **高** | 两个 README 都做了免责声明。FanqieHook:"绕过广告可能违反番茄小说/红果《用户协议》…尤其是账号风险"。合并后 hook 数从 33 升到 ~38,且新增了权益/UI 层篡改,**检测面变大**。缓解:不碰服务端校验数据、不碰发奖链路、保留 FanqieHook 那条自我约束边界 |
| **注入痕迹** | 中 | FanqieHook **无任何反检测**,反而比必要的更可见:在宿主自己的 data 目录留 `cache/fanqiehook.log` 和 `cache/fanqiehook/libdexkit-<abi>.so`,加上一个特征明显的 logcat tag。宿主 App 检查自己的 cache 目录就能轻易发现被注入的证据。squemaFQH 同样没有反检测。缓解:日志路径改到模块私有域或降低落盘频率 |
| **宿主版本漂移** | 中 | FanqieHook 的设计已经处理得很好(§4):版本门**只影响日志级别,永不阻断安装**(`FanqieModule.kt:94-114`;`SUPPORTED_VERSION_CODES` 明写"是审计记录,不是闸门",`:242-247`),per-hook 解析 + per-hook skip = 优雅降级,`install summary` 行精确报出丢了什么。**残留最脆弱点:`AdHooks.kt:600` 的 `BrandTopViewDisplayStrategy#c(AbsActivity)Z` 是硬编码混淆名且无字段级兜底** —— `ClassResolver.kt:108` 自己就点名这个方法可能被改名 |
| **"装了 ≠ 生效"** | **高** | squemaFQH **完全没有 hook 命中插桩**(grep `deoptimize`/`setId`/`auditHit` 全零),所以它能做出的最强断言只是"hook 装上了",**没有任何东西证明 hook 在真实调用链上**。MEMORY.md 里"所有 5 个 hook 在设备上验证生效"这句话只验证了安装。FanqieHook 恰恰因为吃过这个亏才加了 `auditHit()`(`HookManager.kt:183-199` 注释:"静态看着对、实机不在链路上"是致命的)—— 但**在发布构建里是关的**(`AUDIT_HOOK_HITS = false`,`:249`)。缓解:合并项目应默认开启命中审计(至少首 N 次 + 每 M 次采样) |
| **Kotlin 2.0.21 × AGP 9.2.0 兼容性** | **高(未知)** | 见 §6.2。这是整个融合最大的技术未知数,必须**第一步就验证**,不要等到最后 |
| **DexKit 2.0.4 → 2.2.0 API 漂移** | 中 | `ClassResolver` 是对着 2.0.4 写的。必须验证 `create(String)`、`FieldMatcher`、`UsingType`、`findClass/findMethod` DSL 在 2.2.0 上的签名 |
| **`ContentProvider` 方案对番茄/红果可能不可用** | 中 | API 30+ package visibility,见 §5.5。夸克能用可能只是因为它自带 `QUERY_ALL_PACKAGES` |
| **`deoptimize` 语义存疑** | 低 | `HookManager.kt:209-211` 注释说"Force callers to not inline the callee",却对**被 hook 的方法本身**调 `module.deoptimize(method)`。libxposed javadoc 是以**调用方**为框:"when a short hooked method B is invoked by method A … To force A to call the hooked B, you can deoptimize A",并警告"you need to find all the callers of your hooked callee"。14 条 hook 传了 `deoptimize=true`。**这是从 API 契约读出的观察,不是已验证的缺陷** |
| **热重载后模块变哑** | 中 | `FanqieModule.kt:164-167` 的 `onHotReloaded` 只打日志,注释说"新一代会通过自己的 `onPackageReady` 重装"。但 API javadoc 说的正相反:"Package lifecycle callbacks are not automatically replayed after hot reload"。配合 `onHotReloading` 在 `unhookAll()` 后返回 `true`(`:159-161`),一次 service 触发的热重载会让模块**在进程重启前完全失效**。`autoHotReload=false` 只挡住了 App 更新路径,挡不住显式 service 路径 |
| **`installAll` 缺 per-category 兜底** | 低 | 见 §5.4 末 |
| **全屏 hook 的爆炸半径偏大** | 低-中 | `AdHooks.kt:100-119` 的形状枚举把实现类上**每一个**无参非静态 boolean 方法都置 false,不只是注释里说的那两个门。如果那个类还挂着无关的 boolean getter,会被一起翻掉 |
| **`NsUtilsDependImpl` 解析方式与注释不符** | 低 | `:63-65` 的注释论证说"由接口 `com.dragon.read.NsUtilsDepend` 声明,全 APK 只有一个实现",但 `:81-85` 的代码是**硬编码类名**解析 —— 一个白白浪费掉的抗混淆机会 |
| **④ 字段名可能对不上** | 低 | 定位串写 `recDiggNum`(`HookInit.java:391`),实际写的字段是 `recvDiggNum`(`:404`)。若真实字段是前者,`findField` 抛 `NoSuchFieldException` 并在 `:405` 被无日志吞掉 → **获赞数永远伪造不成功,且完全隐形**。需对真实 APK 核验 |
| **⑤b 拦截面过宽,未被文档化** | 低-中 | `findClassesWithFieldType` 返回**所有**持有 `CommentUserStrInfo` 字段的类,然后 hook 它们上面任何"参数含自身类型"的方法(一个 `copyFrom`/merge 模式),每个 hook 再对所有参数写 `userName`。这是一条**热路径上的宽拦截**,README 完全没警告其爆炸半径 |
| **文档不可信** | 中 | 见 §10。**两个仓库的 README 都有实质性错误**,squemaFQH 尤其严重(17 处)。任何基于文档的决策都必须先对代码复核 |

---

## 9. 两个仓库自身的问题清单(融合时应顺手修掉)

### FanqieHook

| 严重度 | 问题 | 位置 |
|---|---|---|
| 中 | `onHotReloaded` 依赖 `onPackageReady` 重放,而 API 契约明说不会重放 → service 触发热重载后模块失效 | `FanqieModule.kt:164-167` |
| 中 | 声明 `minApiVersion=101`,但 `setId()` 和两个热重载回调都是 `@SinceApi(API_102)` | `module.prop:9`, `HookManager.kt:214`, `FanqieModule.kt:157,164` |
| 中 | `BrandTopViewDisplayStrategy#c` 硬编码混淆名,无字段级兜底 —— 残留最脆弱目标 | `AdHooks.kt:600` |
| 低-中 | 全屏形状枚举把所有无参非静态 boolean 都置 false,超出注释声明的两个门 | `AdHooks.kt:100-119` |
| 低-中 | `NsUtilsDependImpl.canShowScreenAd` 按硬编码类名解析,与注释里的"唯一接口"论证不符 | `AdHooks.kt:63-65` vs `:81-85` |
| 低 | `installAll` 无 per-category try/catch,与 `:37-38` 声称的不变量不符 | `AdHooks.kt:40-54` |
| 低 | `deoptimize(method)` 作用于 callee,而 API 文档以 caller 为框 | `HookManager.kt:209-211` |
| 低 | `PackageManager.getPackageArchiveInfo` 被 `method.invoke(null, …)` 当静态方法调,而它不是静态的 | `FanqieModule.kt:205` |
| 低 | `module.prop:8` 声明 `actionIcon=launcher.png`,但仓库里没有任何图片资源;`AndroidManifest.xml` 也没有 `android:icon` | `module.prop`, `app/src/main/res/` |
| 文档 | `AdHooks` 头部审计表过期(还写着 73532/73732);且自相矛盾(`:218` 说 73532 的 impl 是 `h83.a`,`:222` 说是 `fe3.a`) | `AdHooks.kt:8-16` |
| 文档 | `PRESERVED_POSITIONS` 自 v0.8.4 起什么都不 preserve 了,名字误导 | `AdHooks.kt:694-729`, `:681-685` |

**为什么有 `ApkVersion.kt` 这个手写 AXML 解析器**(背景值得知道):`FanqieModule.kt:8-18` 记录了实测——OnePlus 9R / Android 14 / LSPosed 2.2.0 上,`PackageManager.getPackageArchiveInfo` 抛 `NullPointerException: null receiver`,`ActivityThread.currentApplication()` 返回 null(符合预期,`onPackageReady` 时 `Application` 还不存在)。两者都塌成 `-1`,而当时**失败即放行**的版本门就这么放过去了 —— 也就是说版本检查恰好在人们跑 LSPosed 的那些设备上静默失效。`readVersionCode`(`:182-236`)因此串了 4 级策略:manifest 解析 → `getPackageArchiveInfo` → `ActivityThread` → `-1`,每级 `runCatching` + WARN。

### squemaFQH

见 §5.5(状态管线 8 个断点)、§4(定位哲学 6 个缺陷)、§5.3(VIP hook 3 个问题)、§5.4(c)、§6.4(无保护导出组件),以及 §10 的 17 条文档失实。

补充几条:
- `writeActivationFlag` 的 javadoc(`HookInit.java:192-202`)说"写一个模块 App 会读的文件…硬编码 `/sdcard/Android/data/<module-pkg>/files/`",**而方法体(`:203-216`)什么都不写,只打日志**。同一个 14 行方法上叠着两段互相矛盾的文档(`:204-209` 的 NOTE 撤回了它上方的 javadoc)。
- 顺带一提:即使它写了,API 30+ 上 `/sdcard/Android/data/<pkg>/` 对其他 App 也是不可读的。
- `HookInit.java` 的注释里有 **13 处 mojibake `????`**(`:29, :31, :63-64, :82, :148-149, :167, :171, :181, :319, :333, :387, :413`)—— 证据表明 `gradle.properties` 里那套 UTF-8/locale 钉死是在这些注释**已经损坏之后**才加的,**仓库里无法恢复**。这也是为什么它把 CJK 字符串常量都写成 `\uXXXX` 转义。
- `CLAUDE.md:8-9` 让你去读 `.claude/rules/` 和 `.codex/rules/`,**但两者都被 gitignore 了**(`.gitignore`: `.claude/`, `.codex/`)。它指示你读的规则文件不在仓库里。
- AGENTS.md:21/:123/:134 和 README.md:105 都把 `E:\New\squemaFQH\1\squemaFQH\` 当作权威的 v1.9.0 源码备份,AGENTS.md:134 还要求同步前先与它 diff。**那是台机器上不存在的盘符上的绝对路径,且 `1/` 被 gitignore —— 拿不到。** 同样拿不到的还有 `ANDROID_HOME="C:/Users/Squema-Mini/…"`(AGENTS.md:26, :121;README.md:50)。**AGENTS.md:134 规定的验证流程无法执行。**

---

## 10. README 与代码不符的地方(逐条)

**做任何决策前先读这一节。** 两个仓库的文档都不能当事实来源。

### squemaFQH(17 处)

| # | 声称 | 实际 |
|---|---|---|
| 1 | README.md:95, AGENTS.md:47/:118 — `xposed/SystemHookEntry` 存在,"只在 `android` 包触发 `SystemBootstrap`" | **文件不存在**,已按 `.workbuddy/memory/2026-09-09.md` 删除。AGENTS.md:118 在描述不存在的代码的行为 |
| 2 | `SystemBootstrap.java:9-10` javadoc — "由 HookInit 通过 `onSystemServerStarting` 调用" | **HookInit 从未 override `onSystemServerStarting`**,`SystemBootstrap.start()` 零调用者;`scope.list` 也没有 `system`。**整个 `xposed/` 包不可达** |
| 3 | README.md:74 + MEMORY.md 装机验证 — 首次启动会弹 Toast `番茄红果 VIP Hook 成功` | **所有 Toast 已在仓库唯一的 commit `3f8e69a` 中删除**。唯一残留是 `HookInit.java:249-250` 的注释 |
| 4 | README.md:93, AGENTS.md:43, `.git-commit-msg.txt:6` — 状态 UI "由 RuntimeDetector 驱动" | **`RuntimeDetector` 零调用点**,纯死代码 |
| 5 | AGENTS.md:12 — 红果是 "name-only 2-hook set" | **VIP 对红果也装**(`HookInit.java:168`,在 `fullSet` 分支之外)。README.md:21 才是对的 |
| 6 | README.md:33 — "编译 / 目标 SDK \| Android 14(API 37)" | **API 37 ≠ Android 14**(那是 API 34)。自相矛盾;`app/build.gradle` 的 37/37 才是权威 |
| 7 | README.md:113 — ③ "屏蔽个人页推广广告位" | **什么都没屏蔽**。返回值原样透传,只在 `pageName=="PromotionFromUserPage" && args[1]==true` 时改写返回对象的 `leftTime`/`text`。诚实的描述是"把个人页 VIP 推广位渲染成一个已是 VIP、文案为空的 banner" |
| 8 | README.md:119 — "使用 DexKit 按特征签名扫描方法(全版本通杀)" | **② VIP(招牌功能)完全不用 DexKit**,是 `Class.forName` 硬编码。一点都不抗版本 |
| 9 | `HookInit.java:74` — "`113143670061000L` ms ≈ 5355 years" | 实际 = **5555-05-20T05:14:21Z**,约 3585 年 |
| 10 | `HookInit.java:192-202` javadoc — `writeActivationFlag` 会写文件 | **方法体(`:203-216`)什么都不写**,只打日志;同一方法内 `:204-209` 的 NOTE 撤回了上方 javadoc |
| 11 | `HookInit.java:443-444` — `create(ClassLoader, boolean)` 的注释 | 那个 boolean 是 `useMemoryDexFile`(cookie 内存 dex),不是缓存标志。且**未记录**:该重载在 loader 不是 `BaseDexClassLoader` 时会抛 `IllegalStateException` |
| 12 | AGENTS.md:110-113 — `activity_main` 的"关键 ID 名"清单 | **漏了 `card_cherrygram`**,而 `MainActivity.java:110` 绑定它、`activity_main.xml:101-102` 声明它 |
| 13 | `HookInit.java:29` javadoc — "Hook entry for ???? (com.phoenix.read)" | 把模块描述成红果-only,实际 5 个目标。另有 13 处 mojibake |
| 14 | CLAUDE.md:8-9 — 去读 `.claude/rules/` 和 `.codex/rules/` | **两者都被 gitignore**,不在仓库里 |
| 15 | AGENTS.md:21/:123/:134, README.md:105 — `E:\New\squemaFQH\1\squemaFQH\` 是权威源码备份,同步前要 diff | **拿不到**(盘符不存在 + `1/` 被 gitignore)。AGENTS.md:134 规定的验证流程无法执行 |
| 16 | README.md:83, AGENTS.md:34 — manifest 带 "xposed_description" | **一个 `<meta-data>` 都没有**;那是 `<application>` 上的 `android:description` 属性(`:14`)。对 Modern API 来说这是**正确**做法,但文档措辞误导 |
| 17 | MEMORY.md 装机验证 — "所有 5 个 hook 在设备上验证生效" | 只通过 logcat 验证了**安装**。**全仓无任何命中计数插桩**(`deoptimize`/`setId`/`auditHit` grep 全零),所以没有任何东西能证明任一 hook 真的在调用链上 |

### FanqieHook(2 处 + 1 处措辞)

| # | 声称 | 实际 |
|---|---|---|
| 1 | README.md:27 — "Modern API 101+(102 已适配)" | `module.prop:9` 写 `minApiVersion=101`,但 `setId()` 和两个热重载回调都是 `@SinceApi(API_102)`。**代码不是 101-clean 的**(未在 101 设备上验证,从 API 注解推断) |
| 2 | `FanqieModule.kt:164-167` 注释 — "新一代会通过自己的 `onPackageReady` 重装" | libxposed javadoc 说的正相反:热重载后包生命周期回调**不会**自动重放 |
| 3 | README.md:29 "体积 0.47 MB" 与 CHANGELOG 一致 | ✅ 可信。但 `module.prop:8` 的 `actionIcon=launcher.png` 指向一个不存在的资源 |

### 溯源

FanqieHook 是 **MIT**,`Copyright (c) 2026 afwfv`,**全仓没有任何地方致谢上游**(搜 `fork|upstream|derived|based on|credit|致谢|参考|来源|感谢|原项目|inspired` 只命中一句无关的中文)。本地 git 历史是单个 squash commit(`11f9b98`,2026-09-30),无父提交可查,无 fork 元数据。

两个软信号,**均为推断,不是衍生证据**:
1. **作者/命名空间不一致**:`module.prop:5` 和 `LICENSE:3` 是 `afwfv`,仓库是 `github.com/afwfv/FanqieHook`,但包命名空间是 `dev.operit.fanqiehook`,镜像是 `Xposed-Modules-Repo/dev.operit.fanqiehook`。`dev.operit` 看着像另一个开发者的 handle。公开镜像确认同一作者 `afwfv` 可回溯到 `[v0.1.0] - 2026-08-22 — 首个正式版,14 个 hook 全部验证通过`,所以这更像是命名空间选择而非 fork —— 但**本地 CHANGELOG 从 0.3.0 开始,缺 0.1.x/0.2.x 条目**(公开镜像有),早期溯源无法从这份 clone 检视。
2. **一处承认的外部目标表**:`AdHooks.kt:73` 说全屏广告的证据来自"**同类模块目标表交叉核对**"加本地 DEX 验证。这是最接近承认 hook 目标参考过另一个模块工作的表述,**但没点名任何项目**。

---

## 11. 值得直接抄走的陷阱清单

从两个仓库的 AGENTS.md:115-124 / README.md:200-211 / MEMORY.md 提炼,加上本次调研新发现的:

**依赖与构建**
1. libxposed `api` 必须 `compileOnly` —— 打进 APK 会与宿主框架冲突。
2. DexKit 必须 `implementation` —— `compileOnly` 意味着 APK 里没有 `libdexkit.so` → `UnsatisfiedLinkError: Could not load libdexkit.so`。
3. 坐标是小写 `org.luckypray:dexkit`,**不是** `io.github.lsposed:dexkit`(旧 1.x 线)。
4. AGP 8.x 无法消费 `android-37.0` 新格式 SDK platform → `Failed to find Platform SDK platforms;android-37`。要 SDK 37 就得 AGP 9.x。
5. Git Bash 下必须 `export ANDROID_HOME=…`,否则 `:app:compileDebugJavaWithJavac` 失败。
6. macOS 的 `._*` / `.DS_Store` 元数据文件会被当源码编译 → `error: not find class` / 资源解析失败。
7. `git add -A` 会把嵌套 git 仓库当 `mode 160000` gitlink 收进 index → 远端 large file 警告。
8. `packaging.resources.merges += "META-INF/xposed/*"` 必须有,否则元数据文件冲突。
9. 开 `minifyEnabled` 必须同时有 `-keep class * extends io.github.libxposed.api.XposedModule` 和 `-adaptresourcefilecontents META-INF/xposed/java_init.list`,否则**模块在加载期静默坏掉**。DexKit 要整包含成员保留(`libdexkit.so` 通过 JNI 按类名+方法签名回调 Java,绕过 R8 的 mapping 表);Gson 要为字段名反射保留。
10. 源码含字面 CJK 的项目,`gradle.properties` 里要钉死 `file.encoding=UTF-8` / `sun.jnu.encoding=UTF-8` / `user.language=en` / `user.country=US`(daemon 的 `org.gradle.jvmargs` 里也要重复一遍)。squemaFQH 是在已经出现 mojibake **之后**才加的。

**模块加载**
11. `java_init.list` 的 FQCN 写错 → 模块永不加载,**无错误、无日志**。
12. 永远不要在 `java_init.list` 里放经典 `IXposedHookLoadPackage` 实现 —— LSPosed v2.2.0 在 Modern 模式下找不到 `de.robv.android.xposed.IXposedHookLoadPackage` 会**静默失败整个模块**,不只是那一条。
13. 双构造器缺一不可 —— 少任何一个,LSPosed 抛 `NoSuchMethodException` 跳过整个模块。(但见 §6.4 的脆弱性警告)
14. `scope.list` 漏包 → LSPosed Manager 里根本不显示该目标 → 无法勾选 → hook 永不触发。
15. system_server 要用虚拟包名 `system` 显式声明,`android` 不等于 `system`。
16. **必须整机重启**,不能用 LSPosed 软重启(squemaFQH README.md:73:双构造器路径走的是 zygote init)。
17. `adb install -r` 覆盖安装后 LSPosed 偶尔会把 `enabled=0` 重置(README.md:77),要在 Manager 里重新勾选 + 强停目标 App。
18. 改 LSPosed 的 DB 需要 `chown 1000:1000`、`chmod 660`、删 `-shm`/`-wal`、整机重启(MEMORY.md)。

**Hook 正确性**
19. **必须有命中审计**。"静态看着对、实机不在链路上"是致命的(FanqieHook `HookManager.kt:183-191` 的原话,背后是"激励秒领"上线后回滚的真实事故)。
20. 装布尔替换前**先校验 `returnType == Boolean.TYPE`**,否则宿主一改签名你就会 hook 错开关。
21. **拒绝猜测**:DexKit 查询命中 >1 时不要取第一个。FanqieHook 的策略(`ClassResolver.kt:268-302`)是只有恰好一个 static 时才接受,否则 `null` + WARN。理由:hook 错方法会静默翻错开关,比不 hook 更糟。
22. `DexKitBridge` 是 `Closeable`,官方明确要求用完 `.close()` 否则内存泄漏。用 try-with-resources。
23. `addUsingField(String)` 会把参数当完整字段描述符解析并抛 `IllegalAccessError`;要按名字匹配就用只带 name 的 `FieldMatcher`(同一字段名可能挂多个类)。
24. `addUsingString(String)` 是 `StringMatchType.Contains` + 大小写敏感,**不是精确匹配**。
25. `DexKitBridge.create(loader, useMemoryDexFile)` 在 loader 不是 `BaseDexClassLoader` 时抛 `IllegalStateException`(Kotlin `error()`)。
26. **不要在 `onPackageReady` 里跑无界的全 dex 扫描而不过滤进程名** —— 多进程宿主会在 App 启动路径上重扫 N 遍。
27. ART 内联会让 hook 不触发。需要 `deoptimize`,而且按 API 文档应当 deopt **调用方**,不是被 hook 的方法本身。
28. Unicode 码点必须从 dex 字节里读,**绝不能从渲染出来的表格抄**(MEMORY.md 陷阱 #1:Cherrygram 的 `m.હ(long)` 是 U+0AB9 不是 U+0A99,肉眼几乎一样,差一个 UTF-8 字节,搞错就丢了 6 条 hook 里的 1 条)。
29. 反射写字段的 `catch (ReflectiveOperationException ignored)` **必须打日志** —— squemaFQH 的 `:312/:373/:405` 三处完全静默,字段名写错就是隐形的(④ 的 `recvDiggNum`/`recDiggNum` 疑点正是被这个掩盖的)。

**IPC / 状态**
30. **`/data/local/tmp` 不是可行的 IPC 通道** —— enforcing SELinux 下 `untrusted_app` 域无读写权(`shell:shell` 0771, type `shell_data_file`)。反射 `chmod` 救不了,`FileOutputStream` 的 open 先失败。
31. API 30+ 上 `/sdcard/Android/data/<pkg>/` 对其他 App 不可读。
32. `getRemotePreferences(group)` 在模块 App 侧可写、在被 hook 侧**只读** —— 方向与"目标进程上报状态"相反,不能直接用来做 LIVE 检测。
33. 目标进程 → 模块 App 的 `ContentProvider.call` 是 squemaFQH 里**唯一被验证可用**的模式(夸克),但 API 30+ 上目标 App 需要对模块有 package visibility 才能解析到 provider。
34. **绝不要在主线程上 `su` exec + `waitFor()`** —— Magisk 弹授权框就 ANR(squemaFQH `LspScopeReader` 的实际路径,且 `MainActivity.onResume` 每次先 `invalidate()` 故意打穿 TTL 缓存)。
35. 导出的 ContentProvider **必须**加 `android:permission` 并校验调用方包名。squemaFQH 的 `QuarkConfigProvider` 两者都没有 —— 设备上任何 App 都能翻它的 bypass 开关。

**工程纪律**
36. squemaFQH **没有测试、没有 lint、没有 formatter**(AGENTS.md:28, :131)。不要假设 `test`/`lint` task 存在,只能用 `./gradlew assembleDebug` 验证。
37. 加一个目标要**同步改 9 个文件**(MEMORY.md 的清单:`Constants`、`HookInit` 的 import + `onPackageLoaded` + `onPackageReady` 分支 + `PKG_*` 字段、`scope.list`、`HookStatusFiles`、`HookStatusStore.key()`、`MainActivity` 的 bindCard + 动画数组、`activity_main.xml` 的 include、`strings.xml`)。作者被这个咬过**两次**(MEMORY.md 陷阱 #2 和 `.workbuddy/memory/2026-09-09.md` 记录的是**同一个**回归:漏 `onPackageLoaded` + `PKG_QUARK`/`PKG_XIAOX` → `SquemaQuark` 日志 tag 完全空)。
38. UI 资源必须整套替换(layout + colors + strings + styles)—— 资源名错一个,`findViewById` 返回 null,主题静默退化(AGENTS.md:108)。
39. FanqieHook 的 CHANGELOG `:7-21` 记录了一套 debug → 实机验证 → main 的严格发布纪律,起因是**两次未验证就发布的回归**(v0.6.1, v0.7.0)。这套纪律值得直接采用。

---

## 12. 建议

### 值得做,但把预期放对

**这不是"1+1>2",而是"一个很强的引擎 + 一套仪表盘"。** FanqieHook 提供几乎全部实际能力(33 条 hook + 抗混淆定位引擎 + 中心闸门);squemaFQH 在番茄/红果上的增量是 3 条 UI 净化 hook、1 套(需要大修的)状态 UI、以及 1 条建议排除的会员解锁。

**真正的、被低估的增量其实是修好状态管线。** squemaFQH 那套 LIVE 检测有 8 个断点、90% 是死代码,而 FanqieHook 连 Activity 都没有。一个"能告诉你现在到底哪些 hook 活着、哪些在这次宿主升级后丢了"的面板,对这类**天然会随宿主升级而静默失效**的模块来说,价值高于再多拦两个广告位。FanqieHook 的 `install summary` 日志行已经算出了全部所需数据(`hooks installed=N skipped=M lost=[…] known-missing=[…]`,`HookManager.kt:63-72`),只是没人把它送到屏幕上。

### 推荐形态

**以 FanqieHook 为基座,把 squemaFQH 的番茄/红果部分作为"feature pack"移植进去**,而不是反过来。理由:

1. FanqieHook 的 `HookManager` + `ClassResolver` 是**宿主无关的通用基础设施**(不含任何番茄/字节知识),而 squemaFQH 没有等价物。
2. FanqieHook 的工程纪律更严:进程门控、返回类型校验、拒绝猜测、未命中诊断、三路日志、命中审计、`install summary`、R8 keep 规则完整、0.47 MB。squemaFQH 在这些维度上每一项都更弱,且带 90% 死代码。
3. 移植方向决定了代码质量的下限。反过来做(以 squemaFQH 为基座)等于把 33 条精心设计的 hook 塞进一个类型盲、无审计、无进程门控的框架。

**具体形态:**

```
新模块(建议 GPL-3.0,因为引入了 squemaFQH 的 GPL 代码)
├─ 引擎层(直接搬 FanqieHook,宿主无关)
│   HookManager · ClassResolver · ModuleLog · ApkVersion
├─ 单一入口类(libxposed XposedModule)
│   双构造器(兼容 LSPosed 1.9.2~2.2.0)
│   onPackageReady:包名门 + isFirstPackage + processName==packageName
│   → 共享一个 DexKit 桥(try-with-resources)→ 依次调用各 feature pack
├─ feature pack: ad(FanqieHook AdHooks 全量,33 条)
├─ feature pack: purify(squemaFQH ③⑤⑤b⑥ 移植到 ClassResolver 语义锚定)
├─ feature pack: status(修好的 LIVE 检测)
└─ UI 层(squemaFQH MainActivity 精简到 2~3 张卡 + 新增 per-feature 命中面板)
```

**构建基线:** AGP 9.2.0 / Gradle 9.5.1 / JDK 17(foojay daemon toolchain)/ Kotlin **需验证版本** / compileSdk+targetSdk 37 / minSdk 26 / `abiFilters arm64-v8a` / `minifyEnabled true` + FanqieHook 的 keep 规则 / `libxposed:api` compileOnly 102.0.0 / `libxposed:service` implementation **102.0.0** / `dexkit` implementation **2.2.0**(需验证 ClassResolver 兼容)/ 删掉 `de.robv.android.xposed:api:82` 和 `aidl`。

详细步骤见 `PLAN.md`。

### 前置条件(当前环境缺失)

- **无 Android SDK**(`ANDROID_HOME` 未设,常见路径均不存在)
- **只有 JDK 11**(Corretto 11.0.13_8),两个项目都要 **JDK 17**
- 因此本报告**未经编译验证**。`PLAN.md` 的阶段 0 就是搭这个环境,并且把两个最大的未知数(Kotlin × AGP 9 兼容性、DexKit 2.0.4→2.2.0 API 漂移)放在最前面验证。

### 一句实话

如果目标是**做一个更好用的番茄/红果去广告模块**,这个融合值得做,预期收益是"FanqieHook 的能力 + 一个真的能用的状态面板 + 几条 UI 净化",工作量约 2.5k 行代码归并 + 构建统一 + 实机回归。

如果目标包含**金币/激励收益**,这条路走不通 —— 两个仓库都不做,而且 FanqieHook 用一次上线-回滚的实证(CHANGELOG 0.7.0/0.7.1)说明了为什么:发奖是服务端校验的。
