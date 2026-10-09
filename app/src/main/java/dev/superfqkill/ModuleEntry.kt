package dev.superfqkill

import android.os.Bundle
import dev.superfqkill.core.ApkVersion
import dev.superfqkill.core.ClassResolver
import dev.superfqkill.core.HookManager
import dev.superfqkill.core.ModuleLog
import dev.superfqkill.core.STATUS_FILE_NAME
import dev.superfqkill.packs.AUDITED_VERSION_CODES
import dev.superfqkill.packs.AdPack
import dev.superfqkill.packs.FeaturePack
import dev.superfqkill.packs.PKG_HONGGUO
import dev.superfqkill.packs.PackContext
import dev.superfqkill.packs.PurifyPack
import dev.superfqkill.packs.TARGET_PACKAGES
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File

/**
 * 模块唯一入口。
 *
 * **必须是 1 个入口类,不能是 2 个。** `META-INF/xposed/java_init.list` 放两行让两个入口各自装
 * hook,会导致各自建 DexKit 桥、各自扫全 dex。所有 feature pack 在这里共享同一个
 * [ClassResolver](即同一个桥)和同一个 [HookManager](即同一份 install summary)。
 *
 * 纯 Modern libxposed API 102。**不能**同时支持经典 `de.robv.android.xposed`:libxposed API 102
 * 的契约明确写着 "Libxposed modules can not call legacy de.robv.android.xposed APIs"。混进
 * `java_init.list` 的后果是 LSPosed v2.2.0 在 Modern 模式下**静默失败整个模块**(两个上游的头号陷阱)。
 */
class ModuleEntry : XposedModule {

    // ── 双构造器:LSPosed 1.9.2(v7024)~ 2.2.0 兼容垫片 ──────────────────────────
    //
    // LSPosed 反射查找入口类的构造器时优先命中旧式 2 参签名;API 102 严格模式则用无参的。
    // **缺任何一个,LSPosed 抛 NoSuchMethodException 并跳过整个模块**(squemaFQH 陷阱 #8)。
    //
    // ⚠️ 脆弱性警告(照抄上游时必须知道):两个构造器都调**无参** super(),而 libxposed 文档说
    // 框架会自动调 attachFramework() 且模块 "must not call it"。squemaFQH 的实机日志证明这套
    // 能跑,但这是在依赖 LSPosed 的反射查找顺序,**不是依赖文档化契约**。若只在 LSPosed 2.2.x
    // 上验证过,README 的支持下限就该写 2.2.x,不要照抄"1.9.2~2.2.0"这个未经验证的说法。
    @Suppress("UNUSED_PARAMETER")
    constructor(base: XposedInterface, param: ModuleLoadedParam) : super()

    constructor() : super()

    private val log = ModuleLog(this)

    /**
     * 进程名。libxposed 只在 [ModuleLoadedParam] 里给一次(`getProcessName()`),而门控 3 要在
     * `onPackageReady` 里用它,所以必须存下来(上游 FanqieHook 同样是 `@Volatile private var
     * processName`,FanqieModule.kt:45-46)。
     *
     * 初值刻意用一个不可能等于任何包名的哨兵:万一 `onModuleLoaded` 没被调用过,门控 3 会拦下
     * 全部安装(fail-closed),而不是放行到 `:push` / `:widgetProvider` 之类的进程里去。
     */
    @Volatile
    private var processName: String = UNKNOWN_PROCESS

    /**
     * 当前这一代的 hook 记账处。`XposedModule` 本身**没有** unhookAll —— 批量卸载的能力在
     * [HookManager] 上(只有它持有 HookHandle 列表),所以这里必须自己留住引用
     * (上游 FanqieHook 同理,FanqieModule.kt:48-49,159)。
     */
    @Volatile
    private var hookManager: HookManager? = null

    /**
     * 最近一次成功安装的目标,供 [onHotReloaded] 重装用。
     *
     * 为什么需要它:上游 FanqieHook 的 `onHotReloaded` 只打日志,注释说"新一代会通过自己的
     * `onPackageReady` 重装"—— 但 libxposed javadoc 说的正相反:"Package lifecycle callbacks
     * are not automatically replayed after hot reload"。配合 `onHotReloading` 在 `unhookAll()`
     * 后返回 true,一次 service 触发的热重载会让模块**在进程重启前完全失效**。
     * `autoHotReload=false` 只挡住 App 更新路径,挡不住显式 service 路径。PLAN.md §4.1 把这个
     * 列为必修项。
     *
     * ✅ **缺口已闭合(实现并经假框架两世代模拟验证)。**
     *
     * 问题的根源:libxposed 的热重载会**新建一个 ModuleEntry 实例**——`XposedModule` 的类注释
     * 写着 "Entry classes will be instantiated once for each loaded module generation in a
     * process",`onHotReloaded` 的 javadoc 写着 "This callback runs in **new** code"。所以新
     * 一代实例上的 [lastTarget] 必然是 null。换成 companion object 的静态字段也**没用**:
     * 新 generation 用的是新的模块 classloader,静态字段同样是全新的。
     *
     * 框架给的唯一跨代通道是 `HotReloadingParam.setSavedInstanceState()` →
     * `HotReloadedParam.getSavedInstanceState()`,而它的 javadoc 明确要求
     * "must not contain objects created under the old module classloader … Use
     * classloader-neutral values",即只能传 String/基元这类中性值(包名可以,ClassLoader 不行)。
     *
     * 采用的实现:
     *  1. `onHotReloading`(跑在**旧**代码里,所以 [lastTarget] 还可用)把包名 / apkPath /
     *     dataDir 三个 **String** 存进 Bundle。刻意**不**在这里 unhookAll —— 摘除旧 hook 是
     *     新代码的职责(`getOldHookHandles()`),而且框架默认实现的 `onHotReloaded` 干的就是这件事。
     *  2. `onHotReloaded`(新代码)**先**恢复目标、**再**摘旧 hook、**再**重装。顺序是刻意的:
     *     反过来会让两条 bail-out 路径把宿主留在零 hook 状态,比什么都不做更糟(实测过)。
     *     而且因为我们重写了 `onHotReloaded`,框架那个"只 unhook 旧 handle"的默认实现不会执行,
     *     **必须自己摘,否则每条 hook 装两遍**(实测:18 创建 / 9 存活,id 与 GEN1 完全一致)。
     *  3. ClassLoader 不能跨代转移,改用 `ActivityThread.currentApplication()` 重新取 ——
     *     热重载时宿主 Application 确实存在,这与 `onPackageReady` 阶段不同(那时上游实测
     *     currentApplication() 返回 null,正是 [ApkVersion] 存在的原因)。
     *  4. 重设 `log.statusFile`:[ModuleLog] 是每实例的,旧世的文件通道随旧实例失效。
     *
     * 仍然**待实机验证**的三点(假框架证明的是记账正确性,不是 ART 行为):
     *  · `ActivityThread.currentApplication()` 在热重载时是否真返回非 null(隐藏 API)
     *  · 你的 LSPosed 构建是否真的为 service 触发的重载调用这两个回调、是否接受我们的 Bundle
     *  · `ClassResolver` 的 `libdexkit.so` 跨世代加载 —— 已用「每世代唯一文件名 + per-classloader
     *    静态标记」处理(见 `ensureDexKitNativeLoaded`),但 ART 的
     *    "already opened by ClassLoader" 具体行为需要在真机上 grep `DexKit init failed` 确认。
     *    这是热重载路径上**最有价值的一次实机检查**:若 DexKit 没能在新世代绑定,
     *    AdPack 会丢掉 position-filter 与两条全屏闸门,而 PurifyPack 五条会全部失效。
     */
    @Volatile
    private var lastTarget: InstallTarget? = null

    /** 安装所需的全部输入,与生命周期 param 解耦,这样热重载路径也能复用。 */
    private class InstallTarget(
        val packageName: String,
        val classLoader: ClassLoader,
        val apkPath: String?,
        val dataDir: String?
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log.info("onModuleLoaded process=$processName")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 只记日志。全部工作在 onPackageReady 做 —— 那时宿主的类才真正可用。
        // (squemaFQH 在 onPackageLoaded 里给夸克装 hook,那是它自己的多目标分发需要;
        //  我们的两个目标都走 onPackageReady。)
        log.debug("onPackageLoaded: ${param.packageName} firstPackage=${param.isFirstPackage}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName

        // 门控 1:包名。scope.list 应该已经过滤过了,这里是第二道防线 ——
        // 两处不一致时的表现是「框架注入了但模块自己不动手」,只会安静地什么都不做。
        if (packageName !in TARGET_PACKAGES) return

        // 门控 2:只在主包加载时装一次。
        if (!param.isFirstPackage) {
            log.debug("not first package: $packageName, skip")
            return
        }

        // 门控 3:进程名必须等于包名。两个宿主的主进程都与包名同名,任何其他值意味着我们落在了
        // `:push` / `:widgetProvider` / `:miniappX` 里 —— 那些进程必须保持干净。
        // 这一条同时修掉 squemaFQH 的一个真实性能 bug:它**没有**进程过滤,而 onPackageReady 里的
        // DexKit 扫描是无界全 dex 扫描,于是多进程宿主每起一个进程就在 App 启动路径上重扫一遍。
        if (processName != packageName) {
            log.info("skip non-main process: $processName (package=$packageName)")
            return
        }

        // 第三日志通道:宿主 cacheDir 下的普通文件。需要它是因为部分 LSPosed fork 从不 flush
        // 模块日志(实测卡在 21 字节只剩 header,而模块确实注入了),部分设备系统级关掉日志
        // (root 下 logcat -d 也返回零行)。
        //
        // 刻意在版本检查**之前**设置,这样"未验证的宿主版本"警告和后面的 install summary
        // 一定落在那个真正可用的通道上。
        log.statusFile = runCatching {
            File(param.applicationInfo.dataDir, "cache/$STATUS_FILE_NAME")
        }.getOrNull()

        val target = InstallTarget(
            packageName = packageName,
            classLoader = param.classLoader,
            // DexKit 2.x 直接从磁盘读 DEX 而不是从 classloader 读,所以需要宿主的 APK sourceDir。
            apkPath = runCatching { param.applicationInfo.sourceDir }.getOrNull(),
            dataDir = runCatching { param.applicationInfo.dataDir }.getOrNull()
        )
        lastTarget = target

        installAll(target, via = "packageReady")
    }

    /**
     * 真正的安装流程。被 [onPackageReady] 和 [onHotReloaded] 共用。
     */
    private fun installAll(target: InstallTarget, via: String) {
        val packageName = target.packageName
        val versionCode = readHostVersionCode(target)

        log.info(
            "target ready via=$via package=$packageName process=$processName versionCode=$versionCode"
        )
        // 版本门是**咨询性的,永不阻断安装**。上游早期的设计是 fail-closed,于是未知的
        // versionCode 意味着**一条 hook 都不装**,每次宿主升级后模块看起来彻底坏了。
        // AUDITED_VERSION_CODES 是审计记录,不是闸门:往里加一项是"文档行为",从来不是前置条件。
        when {
            versionCode == null ->
                log.warn("host versionCode unavailable; installing anyway (gate is advisory only)")
            versionCode.toInt() in (AUDITED_VERSION_CODES[packageName] ?: emptySet()) ->
                log.info("host version $versionCode is in the audited set for $packageName")
            else ->
                log.warn(
                    "unverified host version $versionCode for $packageName; " +
                        "installing anyway — individual hooks that miss will be reported in the summary"
                )
        }

        // ClassResolver 是 Closeable:DexKitBridge 持有解析整个多 dex 宿主的 native 内存,
        // DexKit 官方明确要求用完 close 否则泄漏。squemaFQH **从不 close**(grep 零命中),
        // 上游 FanqieHook 也没有 close。这里用 use{} 兜住整个安装流程。
        ClassResolver(
            classLoader = target.classLoader,
            log = log,
            apkPath = target.apkPath,
            // 模块自己的 APK:libdexkit.so 的来源。DexKit 从不自己加载它,而在被注入的宿主进程里
            // System.loadLibrary 看不到模块的 lib 路径,所以 resolver 必须从这里解压再 System.load()。
            moduleApkPath = runCatching { getModuleApplicationInfo()?.sourceDir }.getOrNull(),
            hostDataDir = target.dataDir
        ).use { resolver ->
            val hooks = HookManager(this, log).also { hookManager = it }
            val ctx = PackContext(
                classLoader = target.classLoader,
                hostPackage = packageName,
                resolver = resolver,
                hooks = hooks,
                log = log
            )

            // 已知缺失声明:这些 hook id 在**某个**宿主上本来就不存在,不该算进「丢失」。
            // 番茄没有 HongguoBannerServiceImpl.enableShortSeriesAdJoinRevert(红果专属);
            // 红果没有 FanqieSearchActivity,所以三条搜索页 AI 净化 hook 在红果上缺失。
            // 不声明的话,番茄每次启动都会 skipped=1,真实回归的信号就被淹掉了。
            ctx.declareKnownMissing(*knownMissingFor(packageName))

            for (pack in PACKS) {
                // 每个 pack 单独兜底:一个 pack 抛 Throwable 不能阻止其余 pack 安装。
                // (pack 内部还有 AdPack.installAll 的 per-category 兜底,这是第二层。)
                try {
                    pack.install(ctx)
                } catch (t: Throwable) {
                    log.error("feature pack [${pack.id}] failed", t)
                    hooks.noteCategoryFailure("pack:${pack.id}", t)
                }
            }

            log.info(hooks.summary())
            // 结构化摘要目前只在进程内可用。送到模块 App 的 UI 需要 host→module 的 IPC,
            // 那是个未解问题(getRemotePreferences 方向相反;/data/local/tmp 被 SELinux 挡死;
            // ContentProvider.call 在 API 30+ 需要宿主对模块有 package visibility —— 夸克能用
            // 可能只因它自带 QUERY_ALL_PACKAGES,番茄/红果未必有)。必须实机验证,见 PLAN.md §6.3。
            log.debug("install summary detail: ${hooks.installSummary}")
        }
    }

    /** 各宿主上合法缺失的 hook id。与 AdPack 里的 per-hook knownMissingOnMiss 保持一致。 */
    private fun knownMissingFor(packageName: String): Array<String> = when (packageName) {
        PKG_HONGGUO -> arrayOf(
            "purify-search-ai-float-button",
            "purify-search-ai-banner-entry",
            "purify-search-ai-box-entry"
        )
        // 番茄上缺的是红果专属的 banner join-revert
        else -> arrayOf("hongguo-banner-join-revert")
    }

    /**
     * 读宿主 versionCode,多级降级。
     *
     * 为什么不用 PackageManager:上游实测(OnePlus 9R / Android 14 / LSPosed 2.2.0)
     * `getPackageArchiveInfo` 抛 NPE(null receiver),`ActivityThread.currentApplication()`
     * 返回 null(符合预期 —— onPackageReady 时 Application 还不存在)。两者都塌成 -1,
     * 而当时"失败即放行"的版本门就这么放过去了:版本检查恰好在人们跑 LSPosed 的那些设备上
     * 静默失效。所以主策略是自己解析 APK 里的二进制 AXML(见 ApkVersion)。
     */
    private fun readHostVersionCode(target: InstallTarget): Long? {
        // 策略 1:直接解析宿主 APK 的二进制 AndroidManifest.xml
        target.apkPath?.let { path ->
            ApkVersion.readVersionCode(path)?.let { return it }
            log.warn("AXML parse of $path did not yield a versionCode")
        } ?: log.warn("no apkPath available for versionCode read")

        // 策略 2:反射拿 Context 再问 PackageManager。
        // 注意 ActivityThread 是隐藏 API,只能反射;而 currentApplication() **是**静态方法,
        // 所以 invoke(null) 在这里是对的 —— 上游 FanqieModule.kt:205 把同一个 invoke(null,…)
        // 用在了 getPackageArchiveInfo 上,那**不是**静态方法,是它的一处真实 API 误用。
        runCatching {
            val path = target.apkPath ?: return@runCatching
            val atClass = Class.forName("android.app.ActivityThread")
            val ctx = atClass.getMethod("currentApplication").invoke(null) as? android.content.Context
            val pm = ctx?.packageManager ?: return@runCatching
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(path, 0)
            @Suppress("DEPRECATION")
            val code = info?.versionCode?.toLong()
            if (code != null && code > 0) {
                log.info("versionCode via getPackageArchiveInfo: $code")
                return code
            }
            log.warn("getPackageArchiveInfo returned no usable versionCode")
        }.onFailure { log.warn("getPackageArchiveInfo strategy failed: ${it.message}") }

        return null
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val hooks = hookManager
        val target = lastTarget
        log.info(
            "onHotReloading: 本代装有 ${hooks?.installedHandles?.size ?: 0} 条 hook," +
                "安装目标=${target?.packageName ?: "<无>"}"
        )

        if (target != null) {
            // 跨世代转移状态,**只能放类加载器中立的值**(String / 基本类型 / 框架 Bundle)。
            // ClassLoader、HookManager、ModuleLog、InstallTarget 都是旧模块 classloader 下的
            // 对象:放进去框架会抛 IllegalArgumentException(javadoc 明说这是诊断辅助),
            // 而且会把旧世代强引用住,阻止其 classloader 回收与 native 库卸载。
            param.setSavedInstanceState(Bundle().apply {
                putString(KEY_SAVED_PKG, target.packageName)
                target.apkPath?.let { putString(KEY_SAVED_APK, it) }
                target.dataDir?.let { putString(KEY_SAVED_DATA_DIR, it) }
            })
        }

        // 置空:这一代即将退休。留着旧引用没有意义,新一代是**新实例**,字段本来就是初值。
        hookManager = null

        // 刻意**不**在这里 unhookAll():
        //  · 摘除旧 hook 是**新代码**的职责 —— 新一代通过 HotReloadedParam.getOldHookHandles()
        //    拿到这批 handle 再决定摘除还是 replaceHook。框架的默认 onHotReloaded 实现就是
        //    `param.getOldHookHandles().forEach(HookHandle::unhook)`。
        //  · javadoc 说明返回 true 之前框架就会冻结旧代码,此后旧代码再注册 hook 会失败。
        //  · 若在旧代码里先摘掉,新一代拿到的就是一批已失效的 handle。
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // 本回调跑在**新一代**代码上:新实例 + 新模块 classloader。
        // 所以实例字段(lastTarget / hookManager / processName)全是初值,
        // companion object 的静态字段也一样是初值(静态是 per-classloader 的)。
        // 唯一的跨世代通道是 param.savedInstanceState。
        processName = param.processName

        // ── 1. 先恢复安装目标,**再**摘旧 hook ─────────────────────────────────
        // ★ 顺序很关键。上一版是"先无条件摘掉旧 hook,再判断能不能重装",于是两条 bail-out
        //   路径(没有 savedInstanceState / 取不到宿主 Context)都会把宿主留在**零 hook**状态 ——
        //   比框架默认的"什么都不做"更糟。实测过:GEN1 有 9 条,bail-out 后剩 0 条。
        //   现在改成先确认能重装,才动旧 hook。
        val state = param.savedInstanceState as? Bundle
        val pkg = state?.getString(KEY_SAVED_PKG)
        if (pkg == null) {
            log.warn(
                "onHotReloaded: 没有可恢复的安装状态(上一代可能没装过 hook);" +
                    "保留上一代的 ${param.oldHookHandles.size} 条 hook 不动"
            )
            return
        }

        // ── 2. 恢复宿主 ClassLoader ───────────────────────────────────────────
        // ClassLoader 不能跨世代转移(不是类加载器中立的值),只能重新取。
        // 热重载发生时宿主 App **确实在运行**,所以 ActivityThread.currentApplication() 可用 ——
        // 这与 onPackageReady 时不同:那时 Application 还没建,上游实测 currentApplication()
        // 返回 null、getPackageArchiveInfo 抛 NPE,这正是 ApkVersion 那个手写 AXML 解析器
        // 存在的原因(见 readHostVersionCode 的注释)。
        val app = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        }.getOrNull()
        if (app == null) {
            log.error(
                "onHotReloaded: 取不到宿主 Context → 拿不到 ClassLoader,放弃重装;" +
                    "保留上一代的 ${param.oldHookHandles.size} 条 hook 继续工作"
            )
            return
        }

        val target = InstallTarget(
            packageName = pkg,
            classLoader = app.classLoader,
            // 优先用保存下来的路径(与上一代完全一致),取不到再从 Context 现取
            apkPath = state?.getString(KEY_SAVED_APK)
                ?: runCatching { app.applicationInfo.sourceDir }.getOrNull(),
            dataDir = state?.getString(KEY_SAVED_DATA_DIR)
                ?: runCatching { app.applicationInfo.dataDir }.getOrNull()
        )
        lastTarget = target

        // ── 3. 重建文件日志通道 ───────────────────────────────────────────────
        // ModuleLog 是每实例的,旧世代那个 File 句柄随旧实例一起失效。不重设的话
        // 新一代的日志只剩 libxposed 模块日志 + logcat 两条通道 —— 而 ModuleLog 的注释
        // 记录了实测:部分 LSPosed fork 从不 flush 模块日志,部分设备系统级关掉 logcat。
        // 放在摘 hook 之前,好让下面每一步都有文件通道兜着。
        log.statusFile = target.dataDir?.let { File(it, "cache/$STATUS_FILE_NAME") }

        // ── 4. 现在才摘掉上一代的 hook ────────────────────────────────────────
        // ★ 不摘就会**新旧叠加,每条 hook 装两遍**。我们重写了 onHotReloaded,
        //   于是框架那个"只 unhook 旧 handle"的默认实现不会执行,必须自己做。
        //   (`HookHandle.unhook()` 的 javadoc 明说它是幂等的,所以这里重复摘是安全的。)
        val oldHandles = param.oldHookHandles
        log.info("onHotReloaded: 摘除上一代 ${oldHandles.size} 条 hook")
        oldHandles.forEach { handle ->
            runCatching { handle.unhook() }.onFailure {
                log.warn("onHotReloaded: 摘除旧 hook 失败(${it.javaClass.simpleName}: ${it.message})")
            }
        }

        log.info("onHotReloaded: 为 $pkg 重装 hook")
        try {
            installAll(target, via = "hotReload")
        } catch (t: Throwable) {
            log.error("onHotReloaded: 重装失败", t)
        }
    }

    private companion object {
        // 宿主包名常量统一放在 packs/FeaturePack.kt(PKG_FANQIE / PKG_HONGGUO):门控、
        // known-missing 分支、界面卡片引用同一份,不在这里再写一遍字面量。

        /** [processName] 的初值:一个不可能等于任何包名的哨兵,见该字段说明。 */
        const val UNKNOWN_PROCESS = "<unknown>"

        // 热重载跨世代转移状态的 Bundle 键。只能存类加载器中立的值(String),
        // 理由见 onHotReloading 里的注释。
        const val KEY_SAVED_PKG = "pkg"
        const val KEY_SAVED_APK = "apkPath"
        const val KEY_SAVED_DATA_DIR = "dataDir"

        /**
         * 安装顺序即执行顺序。AdPack 在前:它的中央闸门 `checkAdAvailable` 是最重要的一条,
         * 万一后面的 pack 出问题,广告拦截仍然成立。
         */
        val PACKS: List<FeaturePack> = listOf(AdPack(), PurifyPack())
    }
}
