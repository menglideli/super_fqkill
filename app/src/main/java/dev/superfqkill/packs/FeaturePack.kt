package dev.superfqkill.packs

import dev.superfqkill.core.ClassResolver
import dev.superfqkill.core.HookManager
import dev.superfqkill.core.ResolverLog

// ─────────────────────────────────────────────────────────────────────────────
// 作用域门控与版本审计记录 —— **两个独立对象，必须手工保持同步**
//
// 为什么刻意分开：上游 FanqieHook 写的是
//
//     val TARGET_PACKAGES = SUPPORTED_VERSION_CODES.keys        (FanqieModule.kt:332)
//
// 也就是把**作用域门控**（哪些包会被 hook）和**版本审计记录**（哪些 versionCode 做过
// 完整 DEX 审计）做成了同一个对象。后果是：往审计表里加一个包名，会**静默**让这个包
// 开始被 hook —— 一次纯文档性质的登记，变成了行为变更，而且不会有任何编译错误或日志
// 提示。反过来，想收窄作用域就得删审计记录，等于把审计历史一起丢掉。PLAN.md §4.5
// 把这条列为移植时必须修的项。
//
// 拆开之后：
//   · [TARGET_PACKAGES] 是**门控**。改它 = 改行为（哪些进程会被注入并装 hook）。
//     它必须与 `src/main/resources/META-INF/xposed/scope.list` 一致 —— scope.list 决定
//     框架把模块注入哪些进程，TARGET_PACKAGES 是进程内的第二道防线（上游在
//     onPackageLoaded/onPackageReady 里都查它，"Scope list should already filter, but
//     defensively short-circuit"）。两处不一致时的表现是「框架注入了但模块自己不动手」，
//     只会安静地什么都不做，所以改动要同时改两个地方。
//   · [AUDITED_VERSION_CODES] 是**记录**。改它 = 改日志措辞（某个 versionCode 是被打成
//     "in the audited set" 还是 "unverified host version"），不改任何 hook 行为 ——
//     版本门控是 advisory 的，未登记的版本照样 best-effort 安装。
//
// 代价是两者需要**手工保持同步**：新增一个受支持的宿主时，必须同时往 TARGET_PACKAGES
// 加包名、往 AUDITED_VERSION_CODES 加一条审计记录、往 scope.list 加一行。这个代价是
// 故意付的 —— 它把「登记一次审计」和「扩大 hook 作用域」变成两个各自显式的动作，
// 而不是一个动作的两个副作用。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 本模块会动手的宿主包名（作用域门控，**不是**审计记录 —— 见上方说明）。
 *
 * `com.dragon.read`  – 番茄小说
 * `com.phoenix.read` – 红果免费短剧
 *
 * Both are built from the same ByteDance "dragon" baseline (identical versionCode per
 * release) and still ship the ad classes under the `com.dragon.read.*` namespace, so a
 * single [AdPack] implementation covers both. Obfuscated delegate names DO differ between
 * them and between releases (e.g. the NsAdConfigManagerApi impl is `fe3.a` on Fanqie
 * 73532, `lf3.a` on Fanqie 73732, `yb3.a` on Hongguo 73732), which is exactly why those
 * are resolved through DexKit by interface rather than by hardcoded name.
 *
 * 两个包名各自有**具名常量**（下面），不直接在集合里写字面量：它们还被 `ModuleEntry`
 * （known-missing 分支）、`PurifyPack`（番茄专属 hook 的宿主分支）和 `ui.MainActivity`
 * （两张状态卡片）引用。四处各写一遍字面量迟早会对不上，而"对不上"的表现是模块安静地
 * 什么都不做 —— 正是本文件开头那条解耦说明要防的那类失效。
 */
const val PKG_FANQIE = "com.dragon.read"
const val PKG_HONGGUO = "com.phoenix.read"

val TARGET_PACKAGES: Set<String> = setOf(
    PKG_FANQIE,
    PKG_HONGGUO
)

/**
 * 做过完整 DEX 级 hook 目标审计的 versionCode（**审计记录，不是门控** —— 见上方说明）。
 *
 * This map is an **audit record, not a gate**: it lists the versions that
 * went through the full DEX-level hook-target audit. A versionCode outside these sets is
 * logged as "unverified host version" and then installed best-effort anyway. (上游原文写的是
 * `[SUPPORTED_VERSION_CODES] is an audit record, not a gate`；那个名字在本仓库叫
 * [AUDITED_VERSION_CODES]，而它的 `.keys` **不再**充当作用域门控 —— 门控是 [TARGET_PACKAGES]。)
 *
 * Adding an entry here is a documentation act ("these targets were verified"), never a
 * prerequisite for the module to work on a new host release.
 *
 * Audit history (DEX-level target + call-site verification performed for each entry):
 *   73532 (7.3.5.32) Fanqie + Hongguo – 26 targets, all class/method signatures match;
 *                    Fanqie side misses only the Hongguo-only HongguoBannerServiceImpl
 *   73732 (7.3.7.32) Fanqie – 25/26 (same Hongguo-only miss), every hook's invoke-site
 *                    count inside the target's type family identical to 73532
 *   73732 (7.3.7.32) Hongguo – 26/26
 *   73718 (7.3.7.18) Fanqie – 25/26 (same Hongguo-only miss); all 21 blocked position
 *                    strings present with identical occurrence counts to 73732;
 *                    DexKit-resolved impl identical (lf3.a). Hongguo 73718 not audited
 *                    (no APK available), hence not registered below.
 *   73917 (7.3.9.17) Fanqie – 29/30 on device (skipped=1 is the Hongguo-only class, not
 *                    installed here). Two obfuscated getters moved: the AdPack targets now
 *                    resolve by the stable config fields they read (`enableMultiSeriesFlowAd`
 *                    → p(), `landscapeInsertAdEnable` → s0(); the old hardcoded `q0` now
 *                    returns long and is a different config). DexKit-resolved ad impl moved
 *                    lf3.a → eg3.a; fullscreen impl mb3.f. Hongguo 73917 not audited.
 *   73967 (7.3.9.67) Fanqie – 29/30 on device (skipped=1 is the Hongguo-only class, not
 *                    installed here). Statically re-checked against the 73967 APK:
 *                      - both `ExperimentUtil` getters still resolve uniquely by the config
 *                        field they read (`enableMultiSeriesFlowAd` → p(),
 *                        `landscapeInsertAdEnable` → s0()); AB table lives in eg3.a#b
 *                        and holds 40 position names
 *                      - all 37 module position strings (22 blocked + 15 reward) are present
 *                        in the host string pool, zero missing
 *                      - ad impl still eg3.a, fullscreen impl still mb3.f
 *                    A real in-place upgrade 73732 → 73967 was also observed on a test
 *                    device, where the field-scoped lookup followed the renamed getter by
 *                    itself (q0 → s0) with no code change. Hongguo 73967 not audited.
 *   73932 (7.3.9.32) Fanqie – 相对 73967 全表静态复核，零漂移：
 *                      - 25 条番茄侧目标（类 + 方法 + 参数 + 返回类型）逐条命中，唯一
 *                        缺失仍是红果专属 HongguoBannerServiceImpl（番茄侧本来就不存在）
 *                      - 46 个位置名（22 blocked + 24 preserved）在字符串池里零缺失；
 *                        广告位 AB 表仍在 eg3.a，45 个位置常量与 73967 逐个一致
 *                        （含 intelligence_ad / screen_off_ad 两个未分类位）
 *                      - 字段反查落点未变：ExperimentUtil#p()（enableMultiSeriesFlowAd）、
 *                        ExperimentUtil#s0()（landscapeInsertAdEnable）、
 *                        FanqieSearchActivity#q1()/#Y1()/#Z1()（三个 AI 入口字段）
 *                      - DexKit 接口落点未变：广告配置实现 eg3.a、全屏广告实现 mb3.f
 *                        （零参 boolean 门控 a / onScreenAdDialogShow）
 *                      - 32 条 hook 的 invoke 调用点数与 73967 逐条相同（0 条死 hook），
 *                        ad 命名空间无新增类、无删除类（306084 → 306128 个类）
 *                    真机实测（本机 73932，v0.8.6）：install summary
 *                    `hooks installed=32 skipped=1 known-missing=[hongguo-banner-join-revert]`，
 *                    lost=[]；状态文件里实测拦到 reader_banner / video_reader_ad /
 *                    creator_ad / splash_ad 以及 canShowScreenAd 闸门，hook 确实在活链路上。
 *                    对照：未登记的 v0.8.5 在同一台 73932 上同样是 32/33 条，
 *                    只多一条 `unverified host version` 警告 —— 即本版本纯登记，行为不变。
 *                    结论：该版本不需要改任何 hook 代码，只是把版本登记进审计集。
 *   73932 (7.3.9.32) Hongguo – 相对 73732 全表静态复核：
 *                      - 26 条目标（含红果专属 HongguoBannerServiceImpl）逐条命中
 *                      - 46 个位置名零缺失；广告位 AB 表 45 个位置常量与 73732 逐个一致
 *                      - 字段反查落点未变：ExperimentUtil#p()/#s0()；搜索页三个 AI 入口
 *                        在红果上没有宿主类（FanqieSearchActivity 不存在），本来就跳过，
 *                        已标注 known-missing，不会算进「丢失」
 *                      - DexKit 落点随改名自动跟上：广告配置实现 yb3.a → tc3.a，
 *                        全屏广告实现 g73.f → a83.f（零参 boolean 门控 a /
 *                        onScreenAdDialogShow 不变）
 *                      - 32 条 hook 里唯一调用点数变化的是 ExperimentUtil#s0()（4 → 6 个
 *                        调用点，变多不是变没），其余逐条相同，0 条死 hook
 *                      - 该版本 ad 命名空间新增 23 个类（含新的 IDrawRewardAdService /
 *                        DrawRewardAdServiceImpl 激励广告服务）—— 零广告模式下 checkAdAvailable
 *                        一律返回「无广告」，新位无需登记也自动拦
 *                    真机实测（本机 73932，v0.8.6）：install summary
 *                    `hooks installed=30 skipped=3 known-missing=[purify-search-ai-float-button,
 *                    purify-search-ai-banner-entry, purify-search-ai-box-entry]`，lost=[]；
 *                    实测拦到 creator_ad（via tc3.a）与 canShowScreenAd 闸门。
 *                    结论：该版本不需要改任何 hook 代码，只是把版本登记进审计集。
 * Versions sharing one [AdPack] implementation because no target moved between them.
 *
 * 类型说明：上游是 `Map<String, Set<Long>>`，因为它直接跟 `readVersionCode()` 的 `Long`
 * 返回值比。这里按新项目的约定收成 `Set<Int>`（AXML 里的 versionCode 本来就是 32 位整型，
 * `longVersionCode` 只是给「主版本编码」留的余量）。**比较点在 ModuleEntry**：拿到 `Long`
 * 之后要 `versionCode.toInt()` 再查这张表，否则类型对不上编译不过。
 */
val AUDITED_VERSION_CODES: Map<String, Set<Int>> = mapOf(
    PKG_FANQIE to setOf(73532, 73718, 73732, 73917, 73967, 73932),
    PKG_HONGGUO to setOf(73532, 73732, 73932)
)

/**
 * 一次 pack 安装所需的全部上下文。
 *
 * 由入口（`dev.superfqkill.ModuleEntry`）在 `onPackageReady` 里构造**一份**，然后依次交给
 * 每个 [FeaturePack]。共享是刻意的：
 *   - [resolver] 只建一个 → 整个进程只有**一个 DexKit 桥**（PLAN.md §4.2）。两个 pack 各自
 *     建桥意味着各自扫一遍全 dex，番茄是 30 万类量级的多 dex 应用，这个代价不能付两次。
 *   - [hooks] 只建一个 → install summary 是**跨 pack 汇总**的一行，而不是每个 pack 一行；
 *     热重载时也只有一个地方需要 `unhookAll()`。
 *
 * 这里**不放** `XposedModule`：pack 不该直接碰框架，装 hook 一律走 [HookManager]
 * （它才有 per-hook try/catch、PROTECTIVE 模式、id 记账与命中审计）。
 */
class PackContext(
    /** 宿主的 classloader（`PackageReadyParam.classLoader`）—— 反射查找的根。 */
    val classLoader: ClassLoader,
    /** 宿主包名。同一个 [AdPack] 同时服务番茄与红果，需要按宿主区分时用得上。 */
    val hostPackage: String,
    /** 共享的语义定位器（含唯一的 DexKit 桥）。 */
    val resolver: ClassResolver,
    /** 共享的 hook 安装器与记账处。 */
    val hooks: HookManager,
    /** 日志。类型是 [ResolverLog] 而不是 [dev.superfqkill.core.ModuleLog]，理由见该接口。 */
    val log: ResolverLog
) {

    /**
     * Pack 级声明：这些 hook id 在**本宿主**上缺失是正常的，不要算进「丢失」。
     *
     * 与 [HookManager.replaceBooleanFalse] 的 `knownMissingOnMiss = true` 是同一个机制的两个
     * 入口，最终都落在 HookManager 的同一张 known-missing 表上，摘要里的表现完全一致
     * （INFO 级 `hook <id> n/a on this host: …`，计入 `known-missing=[…]`，不进 `lost=[…]`）。
     *
     * 什么时候用哪个：
     *   - per-hook 的 flag —— 缺失原因就写在那条 hook 旁边，读代码时不用跳。
     *     [AdPack] 用的就是这种（4 个 id：番茄侧缺红果专属的
     *     `hongguo-banner-join-revert`，红果侧缺 `purify-search-ai-*` 三条），
     *     因为每条的理由各不相同、且与那条 hook 的审计注释绑在一起。
     *   - 本方法 —— 一个 pack 想在一处集中声明「这一整组目标都是宿主专属的」，
     *     或者 id 是运行期算出来的（例如按 DexKit 反查到的实现类名拼 id）。
     *
     * 幂等；声明可以发生在安装之前（HookManager 在跳过时才查这张表）。
     */
    fun declareKnownMissing(vararg ids: String) {
        ids.forEach { hooks.declareKnownMissing(it) }
    }
}

/**
 * 一组功能 hook 的安装单元 —— 入口按同一个接口依次装每个 pack。
 *
 * 存在的理由：上游只有一个 `AdHooks(...).installAll()`，入口直接写死调用它。PLAN.md §3.1
 * 的目录里 packs/ 下还会有 `PurifyPack`（来自 squemaFQH 的界面净化 hook，按 §5 重写成
 * 语义锚定），阶段 4 可能还有更多。有了这个接口，入口就是
 *
 *     val ctx = PackContext(...)
 *     listOf(AdPack(), PurifyPack()).forEach { it.install(ctx) }
 *
 * 而不是每加一个 pack 就去改一次入口；同时 [id] 让日志与 [dev.superfqkill.core.InstallSummary]
 * 能把一条失败归到具体 pack 上。
 *
 * 约定（实现方需要遵守的，都是上游用真机故障换来的）：
 *   - `install` **不得抛异常**给入口。每条 hook 的安装已经被 [HookManager] 包在 try/catch 里，
 *     pack 自己要负责把「两条 hook 之间」的代码也包住（见 [AdPack.installAll] 的 per-category
 *     try/catch）—— 一个 category 抛 Throwable 不该让后面的 category 全部不执行。
 *   - 目标解析一律走 [PackContext.resolver]，解析不到就返回 null 让 HookManager 记一条 skip；
 *     **绝不猜**（多个候选时宁可少挂一条，挂错是静默改错行为）。
 *   - 不要在 pack 内部自己建 DexKit 桥或自己调 `module.hook`。
 */
interface FeaturePack {

    /** 稳定标识，用于日志与失败归属。不是 hook id 前缀，别拿去拼 hook id。 */
    val id: String

    /** 把本 pack 的 hook 装到 [ctx] 指向的宿主上。可重复调用（热重载后重装）。 */
    fun install(ctx: PackContext)
}
