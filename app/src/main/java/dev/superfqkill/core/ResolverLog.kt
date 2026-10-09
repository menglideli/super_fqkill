package dev.superfqkill.core

/**
 * The logging surface the engine layer ([ClassResolver], [HookManager], and the packs) needs.
 *
 * 为什么要有这个接口 —— 它是**为了可测性**才抽出来的，不是风格偏好：
 *
 * [ModuleLog] 的实现走 `XposedModule.log(...)`，也就是说它的构造需要一个真实的
 * `XposedModule` 实例，而那只有在 LSPosed/框架把模块注入宿主进程后才存在。instrumented
 * 测试（CI-PLAN.md §4 Tier 3 的 `resolver-test` job）里没有 Xposed 框架，于是上游那种
 * `ClassResolver(classLoader, log: ModuleLog, ...)` 的写法**根本没法实例化** —— 引擎里
 * 最值得测的那套抗混淆查找逻辑，恰好被一个日志实现锁死在框架进程里。
 *
 * 抽出接口后：
 *   - 生产路径不变：[ModuleLog] 实现本接口，行为与上游逐条一致。
 *   - 测试路径：一个空实现（本接口的默认方法就是 no-op）或一个收集行的 fake 即可，
 *     不需要 `XposedModule`、不需要 libxposed 在 classpath 上被真正初始化。
 *
 * 方法集合就是引擎层在 [ModuleLog] 上**实际调用过**的那些（逐处核对调用点得到，不是把
 * `android.util.Log` 的六个级别照抄一遍）：`verbose` 目前引擎层没有调用者，保留它是为了
 * 让 [ModuleLog] 的公开面不因这次抽取而缩水。
 *
 * 注意这里**故意不提供** `info(message, tr)` / `debug(message, tr)` 之类 [ModuleLog] 没有的
 * 重载：接口带默认实现，一旦声明了而实现类没覆盖，调用点会静默丢掉那个 `Throwable`
 * —— 诊断信息无声消失，比编译不过更糟。要加就同时在 [ModuleLog] 里加真实现。
 */
interface ResolverLog {

    fun verbose(message: String) {}

    fun debug(message: String) {}

    fun info(message: String) {}

    fun warn(message: String) {}

    fun warn(message: String, tr: Throwable?) {}

    fun error(message: String) {}

    fun error(message: String, tr: Throwable?) {}
}
