package dev.superfqkill.core

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 一条 hook 的最终结局。与 [HookManager] 的日志级别一一对应：
 * [INSTALLED] / [SKIPPED_KNOWN_MISSING] 走 INFO，[SKIPPED] 走 WARN，[FAILED] 走 ERROR。
 */
enum class HookOutcome {
    /** `module.hook(...)` 成功返回了 handle。 */
    INSTALLED,

    /** 目标没解析到（或返回类型不符），且这**不是**本宿主预期内的缺失 —— 真·丢失。 */
    SKIPPED,

    /** 目标没解析到，但已声明为本宿主预期缺失（例如番茄侧没有红果专属类）。 */
    SKIPPED_KNOWN_MISSING,

    /** 解析到了目标，但安装过程本身抛了异常。 */
    FAILED
}

/**
 * 单条 hook 的机器可读记录。
 *
 * @param id 与日志里 `hook installed: <id>` 用的是同一个 id。
 * @param outcome 结局，见 [HookOutcome]。
 * @param strategy 这条 hook 用的**安装策略**标签。默认由 [HookManager] 按调用的是哪个
 *   install 方法填（`replace-boolean-false` / `replace-boolean-true` / `replace-body` /
 *   `boolean-filter`）；pack 也可以显式传一个更细的标签来记录**目标是怎么解析出来的**
 *   （例如 `dexkit:field-scoped`）—— [HookManager] 只拿到已经解析好的 [Method]，
 *   它自己无从得知这一层。
 * @param hits 到目前为止的命中次数（快照值，见 [HookManager.installSummary]）。
 */
data class HookRecord(
    val id: String,
    val outcome: HookOutcome,
    val strategy: String,
    val hits: Int
)

/**
 * 安装结果的机器可读摘要 —— [HookManager.summary] 那行日志的结构化版本。
 *
 * 存在的理由：`hooks installed=N skipped=M lost=[…]` 是给人读的字符串，任何自动化
 * （CI 基线比对、状态面板、回归脚本）都得靠正则去抠它。这里把同一批数字以不可变数据
 * 暴露出来，消费方不再解析日志。
 *
 * **传输到模块 App 的 UI 是一个尚未解决的问题，本次刻意不做。** host→module 的 IPC
 * （ContentProvider / broadcast / 文件）每一种都需要在真机上验证权限与 SELinux 行为，
 * 而这台机器上没有设备；上游两个仓库在这条路上都留下过死代码（PLAN.md §4.4 列的
 * `HookStatusService` / `HookStatusReporter` / `HookStatusStore` 全是零调用者或永远 null）。
 * 所以这里只保证**进程内**可读，不做任何持久化或跨进程通道。
 *
 * @param installed 成功装上的条数（= [HookManager.installedIds] 的长度）。
 * @param skipped 跳过的条数（含预期缺失）。
 * @param lost 真·丢失的 id —— 与 [unexpectedSkips] 按构造相等，单独留一个字段是因为
 *   日志里这一项就叫 `lost=[…]`，让消费方不必在两个同义名字之间猜。
 * @param knownMissing 跳过且**已声明为预期缺失**的 id（= `skipped ∩ 已声明集合`，
 *   不是「所有被声明过的 id」—— 声明了但实际装上的不算缺失）。
 * @param unexpectedSkips 见 [HookManager.unexpectedSkips]。
 * @param hooks 逐条记录，按**处理顺序**（不是安装顺序）。
 * @param categoryFailures 整段安装范围（例如 pack 里的一个 category）抛异常中止的名字，
 *   见 [HookManager.noteCategoryFailure]。
 * @param failedHooks `module.hook()` 抛异常、单条没能装上的 id，见 [HookManager.hookFailures]。
 *   与 [categoryFailures] 的区别：那是「整段没跑完」，这是「这一条跑了但失败了」。
 */
data class InstallSummary(
    val installed: Int,
    val skipped: Int,
    val lost: List<String>,
    val knownMissing: List<String>,
    val unexpectedSkips: List<String>,
    val hooks: List<HookRecord>,
    val categoryFailures: List<String>,
    val failedHooks: List<String> = emptyList()
)

/**
 * Centralised hook installation with consistent error handling, hot-reload-safe identity,
 * and per-hook try/catch isolation. One failed hook must NEVER cause another hook to be skipped.
 *
 * Convention:
 *   - Hook `id` matches the readable name used in `[SuperFqKill][INFO] hook installed: ...`.
 *   - Every hook uses `ExceptionMode.PROTECTIVE` so a thrown exception cannot crash the host app.
 *   - The set of installed hooks is exposed via [installed] for `onHotReloaded()` to remove or
 *     atomically replace.
 */
class HookManager(
    private val module: XposedModule,
    /**
     * 只用日志面，所以类型是 [ResolverLog] 而不是 [ModuleLog]（生产传 [ModuleLog] 即可）。
     *
     * [module] 则**必须**是真实的 `XposedModule`：`module.hook(...)` /
     * `module.deoptimize(...)` 是框架能力，没有它这个类什么都装不上 —— 因此 HookManager
     * 不像 [ClassResolver] 那样可以在无框架环境里实例化，Tier 3 的 instrumented 测试测的是
     * resolver 的查找逻辑，不是 hook 安装。
     */
    private val log: ResolverLog
) {

    private val installed = mutableListOf<HookHandle>()

    /** ids installed on this host version, in install order. */
    private val installedIds = mutableListOf<String>()

    /**
     * ids whose target could not be resolved on this host version (class/method moved or gone).
     *
     * This is the module's update-resilience contract: versionCode is only advisory, so the
     * verdict on whether a host update broke something comes from this list rather than from a
     * static re-audit of the whole target set.
     */
    private val skipped = mutableListOf<String>()

    /**
     * 其中**不是**宿主升级造成的、可以预期的缺失（例如红果专属类在番茄侧本来就不存在）。
     *
     * 单独分出来是为了让摘要保持"一眼可判定"：如果 known-missing 也算进丢失里，番茄上每次开屏
     * 都会看到 `skipped=1`，"有东西丢了"这个信号就被稀释成噪音，升级后真正掉了一条反而看不出来。
     *
     * 两种写法都会进这个列表：per-hook 的 `knownMissingOnMiss = true`（缺失原因就写在那条 hook
     * 旁边），以及 pack 级的 [declareKnownMissing]（一个 pack 想一次性声明一组 id 时）。
     */
    private val knownMissing = mutableListOf<String>()

    /** 每个 hook 的命中次数（审计用）。 */
    private val hitCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 每条 hook 的结局与策略，按处理顺序保留 —— [installSummary] 的逐条记录由它们生成。 */
    private val outcomes = LinkedHashMap<String, HookOutcome>()
    private val strategies = LinkedHashMap<String, String>()

    /**
     * 整段安装范围（pack 里的一个 category）抛异常中止时记在这里。
     *
     * 为什么必须有：per-hook 的 try/catch 只覆盖**单条** hook 的安装，覆盖不到「一个 category
     * 函数在两条 hook 之间抛了 Throwable」这种情况 —— 那时剩下的 hook 根本没机会被尝试，
     * 而摘要里只会看到 installed 少了几条，没有任何线索说明是整段没跑完。
     */
    private val categoryFailures = mutableListOf<String>()

    /**
     * `module.hook()` 本身抛异常、导致某条 hook 没能装上的 id。
     *
     * 为什么必须单独记：这种失败既不进 [installedIds] 也不进 [skipped]，于是
     * `installed + skipped == 尝试总数` 这条算术会**静默失衡** —— 而实机回归正是拿这条
     * 等式对基线的（PLAN.md §7.3）。上游同样有这个洞（`installInternal` 的 catch 只写
     * `outcomes[id] = FAILED`，而 `summary()` 是 `installedIds.size + skipped.size`）。
     * 结构化数据里它本来就在（`installSummary.hooks` 的 `outcome=FAILED`），缺的是摘要行。
     */
    private val hookFailures = mutableListOf<String>()

    val installedHandles: List<HookHandle> get() = installed.toList()

    /**
     * hook ids that were skipped and are NOT explained by host-specific differences — i.e. the ones
     * that actually indicate this host version moved a target the module still expects.
     */
    val unexpectedSkips: List<String> get() = skipped.filterNot { it in knownMissing }

    /**
     * 机器可读的安装摘要（[summary] 的结构化版本）。
     *
     * 每次读取都重新构造：`hits` 是随宿主运行不断增长的，快照语义比缓存一个陈旧对象更容易
     * 讲清楚。返回的对象本身不可变，可以安全地交给别的线程/调用方。
     */
    val installSummary: InstallSummary
        get() = InstallSummary(
            installed = installedIds.size,
            skipped = skipped.size,
            lost = unexpectedSkips,
            knownMissing = skipped.filter { it in knownMissing },
            unexpectedSkips = unexpectedSkips,
            hooks = outcomes.map { (id, outcome) ->
                HookRecord(
                    id = id,
                    outcome = outcome,
                    strategy = strategies[id] ?: STRATEGY_UNKNOWN,
                    hits = hitCounts[id] ?: 0
                )
            },
            categoryFailures = categoryFailures.toList(),
            failedHooks = hookFailures.toList()
        )

    /**
     * 安装结果摘要：一行说明「这次装上了几条、丢了哪几条」。
     *
     * 宿主升级后不需要重新适配，靠的就是这条摘要——模块会自行逐条退化，而丢掉的 hook id
     * 直接写在日志里，不用把全部目标重新静态审计一遍。
     */
    fun summary(): String {
        val lost = unexpectedSkips
        val expected = skipped.filter { it in knownMissing }
        val base = "hooks installed=${installedIds.size} skipped=${skipped.size}"
        return buildString {
            append(base)
            if (lost.isNotEmpty()) append(" lost=[${lost.joinToString(", ")}]")
            if (expected.isNotEmpty()) append(" known-missing=[${expected.joinToString(", ")}]")
            // 只在真的有整段失败时才追加，happy path 的摘要文本与上游逐字一致
            // （CI/实机回归是拿这行字符串跟基线对的）。
            if (categoryFailures.isNotEmpty()) {
                append(" failed=[${categoryFailures.joinToString(", ")}]")
            }
            // 单条 hook 安装失败也要出现在摘要里，否则 installed+skipped 对不上尝试总数，
            // 而实机回归正是拿这个等式对基线的。happy path 下这一项为空，摘要文本与上游逐字一致。
            if (hookFailures.isNotEmpty()) {
                append(" hook-failed=[${hookFailures.joinToString(", ")}]")
            }
        }
    }

    /**
     * Pack 级声明：这些 id 在**本宿主**上缺失是正常的，不要算进「丢失」。
     *
     * 与 per-hook 的 `knownMissingOnMiss = true` 等价（两者都进 [knownMissing]，[noteSkip]
     * 也都会认），区别只是声明的位置：per-hook 适合「缺失原因就写在这条 hook 旁边」的情况，
     * pack 级适合一个 pack 想在一处集中说明「这一组目标是宿主专属的」。
     *
     * 幂等：重复声明同一个 id 不会让列表长胖。
     */
    fun declareKnownMissing(id: String) {
        if (id !in knownMissing) knownMissing += id
    }

    /**
     * 记录一段安装范围（通常是 pack 里的一个 category 函数）整体抛异常中止。
     *
     * 调用方负责 catch —— 本方法只负责记账与打日志，它自己不吞异常也不重抛。
     * 记进 [categoryFailures] 之后会出现在 [summary] 的 `failed=[…]` 与
     * [InstallSummary.categoryFailures] 里。
     */
    fun noteCategoryFailure(category: String, t: Throwable) {
        categoryFailures += category
        log.error(
            "install category '$category' aborted: ${t.javaClass.simpleName}: ${t.message}",
            t
        )
    }

    /**
     * 公开记账入口:一条 hook **没能安装**,把原因写进 summary。
     *
     * 为什么需要它:[noteSkip] 是私有的,只有走 `install*` 系列才会被调用。于是一个 pack 若
     * 在"定位目标"阶段就失败、直接 `log.warn` + `return`(从不碰 [HookManager]),它的 id
     * **既不进 installed 也不进 skipped/lost** —— 从 summary 里彻底消失。
     *
     * 这破坏了本模块的核心契约:"宿主升级后有没有弄坏什么,看的就是这份清单"。
     * PurifyPack 的 ③④⑤⑤b⑥ 五条原本全是这个形状(实测:番茄 summary 是
     * `installed=9 skipped=21`,恰好等于 AdPack 的 30 次尝试,五个 purify id 一个都不在里面,
     * 尽管日志里有 10 条 purify WARN)。热重载后 DexKit 失效时,整个 pack 会**静默消失**。
     *
     * 若该 id 已通过 [declareKnownMissing] 或 per-hook flag 声明为宿主专属缺失,
     * [noteSkip] 会自动把它归到 `known-missing` 而不是 `lost`,日志级别也降为 INFO。
     */
    fun noteMissing(id: String, reason: String, strategy: String = STRATEGY_UNKNOWN): Unit {
        noteSkip(id, reason, knownMissing = false, strategy = strategy)
    }

    /**
     * Record a hook that could not be installed, and explain why in the log.
     *
     * @param knownMissing true when this target is legitimately absent on this host (e.g. a
     *   Hongguo-only class while running under 番茄小说). Such a skip is logged at INFO and shown
     *   separately in [summary] so it cannot mask a real regression.
     */
    private fun noteSkip(
        id: String,
        reason: String,
        knownMissing: Boolean = false,
        strategy: String = STRATEGY_UNKNOWN
    ): HookHandle? {
        skipped += id
        // pack 级声明过的 id 与 per-hook 传进来的 flag 同等对待：声明发生在安装之前，
        // 这里必须认它，否则同一种缺失会因为声明方式不同而打出不同级别/文案的日志。
        val expected = knownMissing || id in this.knownMissing
        if (expected) {
            if (id !in this.knownMissing) this.knownMissing += id
            log.info("hook $id n/a on this host: $reason")
        } else {
            log.warn("skip hook $id: $reason")
        }
        outcomes[id] = if (expected) HookOutcome.SKIPPED_KNOWN_MISSING else HookOutcome.SKIPPED
        strategies[id] = strategy
        return null
    }

    /**
     * Replace a method with one that always returns `false`.
     * For boolean methods only. Safe to call with null `method` — it logs and skips.
     *
     * @param knownMissingOnMiss set when the target is expected to be absent on one of the two
     *   supported hosts; keeps it out of the "lost" list in [summary].
     * @param strategy 见 [HookRecord.strategy]；不传则记为 [STRATEGY_REPLACE_BOOLEAN_FALSE]。
     */
    fun replaceBooleanFalse(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        knownMissingOnMiss: Boolean = false,
        strategy: String? = null
    ): HookHandle? = installBooleanReplacement(
        id,
        method,
        value = false,
        deoptimize = deoptimize,
        knownMissingOnMiss = knownMissingOnMiss,
        strategy = strategy ?: STRATEGY_REPLACE_BOOLEAN_FALSE
    )

    /**
     * Replace a method with one that always returns `true`.
     *
     * @param knownMissingOnMiss see [replaceBooleanFalse].
     * @param strategy 见 [HookRecord.strategy]；不传则记为 [STRATEGY_REPLACE_BOOLEAN_TRUE]。
     */
    fun replaceBooleanTrue(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        knownMissingOnMiss: Boolean = false,
        strategy: String? = null
    ): HookHandle? = installBooleanReplacement(
        id,
        method,
        value = true,
        deoptimize = deoptimize,
        knownMissingOnMiss = knownMissingOnMiss,
        strategy = strategy ?: STRATEGY_REPLACE_BOOLEAN_TRUE
    )

    private fun installBooleanReplacement(
        id: String,
        method: Method?,
        value: Boolean,
        deoptimize: Boolean,
        knownMissingOnMiss: Boolean = false,
        strategy: String
    ): HookHandle? {
        if (method == null) {
            return noteSkip(id, "method not found", knownMissingOnMiss, strategy)
        }
        if (method.returnType != java.lang.Boolean.TYPE) {
            return noteSkip(
                id,
                "not a boolean primitive method (returnType=${method.returnType.simpleName})",
                knownMissing = false,
                strategy = strategy
            )
        }
        return installInternal(id, method, deoptimize, strategy) {
            // Boolean primitive replacement: chain is unused.
            value
        }
    }

    /**
     * Install a hook that runs the supplied [hooker] against [method]. Returns null and logs WARN
     * if [method] is null.
     *
     * @param strategy 见 [HookRecord.strategy]；不传则记为 [STRATEGY_REPLACE_BODY]。
     */
    fun install(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        strategy: String? = null,
        body: (Chain) -> Any?
    ): HookHandle? {
        val s = strategy ?: STRATEGY_REPLACE_BODY
        if (method == null) {
            return noteSkip(id, "method not found", strategy = s)
        }
        return installInternal(id, method, deoptimize, s, body)
    }

    /**
     * Install a filter-style hook: original method is invoked unless [shouldBlock] matches.
     * [shouldBlock] receives the immutable argument list; return `true` to short-circuit with
     * `false`, `false` to call the original.
     *
     * @param strategy 见 [HookRecord.strategy]；不传则记为 [STRATEGY_BOOLEAN_FILTER]。
     */
    fun installBooleanFilter(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        strategy: String? = null,
        shouldBlock: (args: List<Any?>) -> Boolean
    ): HookHandle? {
        val s = strategy ?: STRATEGY_BOOLEAN_FILTER
        if (method == null) {
            return noteSkip(id, "method not found", strategy = s)
        }
        if (method.returnType != java.lang.Boolean.TYPE) {
            return noteSkip(id, "not a boolean primitive method", strategy = s)
        }
        return installInternal(id, method, deoptimize, s) { chain ->
            if (shouldBlock(chain.args)) false else chain.proceed()
        }
    }

    /**
     * 命中审计：每个 hook 被真实调用时记一行日志，用于**在真机上证明这条 hook 在链路上**。
     *
     * 为什么需要：`replaceBooleanFalse` 这类 hook 命中时是静默的（方法直接返回 false，
     * App 只是"没展示广告"），日志里什么都看不到——于是「目标方法存在 + 调用点数量没变」
     * 就成了唯一证据，而这并不能证明它真的被调用过。激励秒领那轮已经证明这种
     * 「静态看着对、实机不在链路上」的坑有多致命。
     *
     * 每个 id 只记前 [AUDIT_MAX_LOGS] 次，避免高频 hook（如 ExperimentUtil.p）刷屏；
     * 之后每 [AUDIT_EVERY] 次再记一行，用来确认它仍在被调用。
     *
     * 计数本身**不受** [AUDIT_HOOK_HITS] 控制：[InstallSummary] 里每条 hook 的 `hits`
     * 要在审计关掉时依然可用，否则那个字段永远是 0，而它的意义恰恰是「证明在活链路上」。
     * 开关只决定要不要把计数**打成日志**。
     */
    private fun auditHit(id: String) {
        val n = hitCounts.merge(id, 1, Int::plus) ?: 1
        if (!AUDIT_HOOK_HITS) return
        if (n <= AUDIT_MAX_LOGS || n % AUDIT_EVERY == 0) {
            log.info("hook hit[$n]: $id")
        }
    }

    private fun installInternal(
        id: String,
        method: Method,
        deoptimize: Boolean,
        strategy: String,
        body: (Chain) -> Any?
    ): HookHandle? {
        return try {
            if (deoptimize) {
                // Force callers to not inline the callee, so the hook can take effect.
                runCatching { module.deoptimize(method) }
                    .onFailure { log.warn("deoptimize failed for $id: ${it.javaClass.simpleName}") }
            }
            val handle = module.hook(method)
                .setId(id)
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    auditHit(id)
                    body(chain)
                })
            installed += handle
            installedIds += id
            outcomes[id] = HookOutcome.INSTALLED
            strategies[id] = strategy
            log.info("hook installed: $id -> ${method.declaringClass.name}#${method.name}")
            handle
        } catch (t: Throwable) {
            outcomes[id] = HookOutcome.FAILED
            strategies[id] = strategy
            hookFailures += id
            log.error("hook install failed: $id (${method.declaringClass.name}#${method.name})", t)
            null
        }
    }

    /**
     * Used by `onHotReloaded()` to retire every hook created by this manager.
     *
     * [knownMissing] 也在这里清空 —— 包括 pack 级声明的那些。所以热重载后**必须重新跑一遍
     * pack 的 install**（PLAN.md §4.1：`onHotReloaded` 要真正重装，不能只打日志），
     * 否则声明丢失、下一次摘要会把预期缺失误报成「丢失」。
     */
    fun unhookAll() {
        installed.forEach { runCatching { it.unhook() } }
        installed.clear()
        installedIds.clear()
        skipped.clear()
        knownMissing.clear()
        outcomes.clear()
        strategies.clear()
        categoryFailures.clear()
        // 必须一起清:否则退休之后 summary() 会打出 `installed=0 skipped=0 hook-failed=[…]`
        // —— 一个已经没有任何 hook 的 manager 还在报上一代的失败(实测到过)。
        hookFailures.clear()
    }

    private companion object {
        /**
         * 是否把 hook 命中**打成日志**（每个 id 前 3 次 + 之后每 200 次一条）。
         *
         * 真机核验 hook 是否真的在链路上时打开它——`replaceBooleanFalse` 这类 hook 命中时是
         * 静默的，不开审计就只能靠"目标方法存在 + 调用点数量没变"来推断，而那并不能证明
         * 它真的被调用过。
         *
         * **本仓库默认 true（上游发布版是 false）。** 理由：这类模块最致命的失效模式不是
         * "装不上"，而是"静态看着对、实机不在调用链上"——上游自己就因此上线又回滚过一个功能
         * （激励秒领：目标方法存在、调用点数量没变，实机金币却不涨，见本节 [auditHit] 的原话）。
         * 装不上有 WARN 兜着，不在链路上则**什么信号都没有**，只有命中日志能证明。
         * 采样（[AUDIT_MAX_LOGS] / [AUDIT_EVERY]）已经把刷屏压住了，所以这点日志量换
         * "每条 hook 是否真的活着"的证据是划算的。要安静日志就把它改回 false ——
         * 计数与 [InstallSummary] 的 `hits` 不受影响。
         */
        const val AUDIT_HOOK_HITS = true

        const val AUDIT_MAX_LOGS = 3
        const val AUDIT_EVERY = 200

        /** [HookRecord.strategy] 的默认标签：按调用的是哪个 install 方法填。 */
        const val STRATEGY_REPLACE_BOOLEAN_FALSE = "replace-boolean-false"
        const val STRATEGY_REPLACE_BOOLEAN_TRUE = "replace-boolean-true"
        const val STRATEGY_REPLACE_BODY = "replace-body"
        const val STRATEGY_BOOLEAN_FILTER = "boolean-filter"

        /** 兜底标签：只在记录与策略意外对不上时出现（正常路径不该看到它）。 */
        const val STRATEGY_UNKNOWN = "unknown"
    }
}
