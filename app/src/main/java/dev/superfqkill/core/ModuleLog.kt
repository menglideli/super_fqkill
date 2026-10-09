package dev.superfqkill.core

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * logcat / 模块日志的 TAG,以及宿主 cacheDir 下状态文件的名字。
 *
 * 为什么是**公开的顶层常量**:UI 要把 `adb logcat -s <TAG>` 与
 * `cat /data/data/<pkg>/cache/<状态文件>` 这两条命令原样展示给用户(见
 * [dev.superfqkill.ui.MainActivity]),而命令里的这两个值如果写死在 strings.xml,就会和
 * 代码里的实际取值漂移 —— 用户照着界面敲命令却什么都抓不到,那是比不给提示更糟的提示。
 * 所以三个地方(这里、[ModuleLog] 的 TAG、[dev.superfqkill.ModuleEntry] 建状态文件的路径)
 * 引用同一份常量。
 */
const val MODULE_LOG_TAG = "SuperFqKill"

/** 同上:状态文件名由入口在 `onPackageReady` 里拼进 `<dataDir>/cache/`。 */
const val STATUS_FILE_NAME = "superfqkill.log"

/**
 * Unified logging facade.
 *
 * Every message goes out through up to **three** channels, because no single one is reliable
 * across the LSPosed builds found in the wild:
 *
 *  1. `XposedModule.log(...)` — the framework's own module log. On stock LSPosed this lands in
 *     `/data/adb/lspd/log/modules_*.log` and is the canonical place to look.
 *  2. `android.util.Log` — ordinary logcat, for `adb logcat -s SuperFqKill`.
 *  3. A plain status file inside the **host app's** cache dir ([statusFile]).
 *
 * Why 2 and 3 exist (both measured on real devices, not hypothetical):
 *   - Some LSPosed forks never flush the module log file: it stayed at 21 bytes — just its
 *     header — while the module was demonstrably injected and running.
 *   - Some devices ship with system-wide logging disabled: `adb logcat -d` returned zero lines
 *     even when run as root, which kills channel 2 as well.
 *
 * With channel 3, `adb shell su -c 'cat /data/data/com.dragon.read/cache/superfqkill.log'` still
 * reports what the module did — which is the only way to verify hook installation on such a
 * device. (The exact file name is the entry point's choice: it assigns [statusFile] once the
 * host's data dir is known.)
 *
 * Levels follow `android.util.Log`: VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6.
 *
 * 实现 [ResolverLog]：引擎层（[ClassResolver] / [HookManager] / packs）只依赖那个接口，
 * 这样 instrumented 测试可以在没有 Xposed 框架的环境里跑（理由见 [ResolverLog]）。本类的
 * 公开方法集合与接口一一对应，抽取没有改动任何生产行为。
 */
class ModuleLog(
    private val module: XposedModule,
    /**
     * Set once the host's data dir is known (i.e. in `onPackageReady`); before that, messages only
     * go to channels 1 and 2. Mutable because the earliest lifecycle callback
     * (`onModuleLoaded`) runs before any host path is available.
     */
    @Volatile
    var statusFile: File? = null
) : ResolverLog {

    override fun verbose(message: String) = emit(Log.VERBOSE, "VERBOSE", message, null)

    override fun debug(message: String) = emit(Log.DEBUG, "DEBUG", message, null)

    override fun info(message: String) = emit(Log.INFO, "INFO", message, null)

    override fun warn(message: String) = emit(Log.WARN, "WARN", message, null)

    override fun warn(message: String, tr: Throwable?) = emit(Log.WARN, "WARN", message, tr)

    override fun error(message: String) = emit(Log.ERROR, "ERROR", message, null)

    override fun error(message: String, tr: Throwable?) = emit(Log.ERROR, "ERROR", message, tr)

    private fun emit(priority: Int, level: String, message: String, tr: Throwable?) {
        val line = "[$level] $message"
        runCatching { module.log(priority, TAG, line, tr) }
        runCatching { Log.println(priority, TAG, line) }
        writeStatus("$level $message")
    }

    /**
     * Append one line to the status file. Failures are ignored — diagnostics must never break the host.
     *
     * When the file crosses [MAX_STATUS_BYTES] we keep the most recent [KEEP_STATUS_BYTES] rather
     * than wiping the file. Wiping was a self-inflicted bug: the previous version truncated to a
     * single `... truncated ...` line, throwing away every diagnostic line written earlier in the
     * same process — including the `unverified host version` warning and the per-hook skip reasons
     * that the user would have needed to understand a post-upgrade regression.
     */
    private fun writeStatus(text: String) {
        val f = statusFile ?: return
        runCatching {
            if (f.length() > MAX_STATUS_BYTES.toLong()) trim(f)
            f.appendText("${TIME_FORMAT.format(Date())} $text\n")
        }
    }

    /**
     * Roll the status file: keep the newest [KEEP_STATUS_BYTES] of its content (cut at a line
     * boundary so we never preserve a half-written line) and prepend a marker.
     */
    private fun trim(f: File) {
        val all = runCatching { f.readText() }.getOrNull() ?: run {
            f.writeText("... status file could not be read for trimming ...\n")
            return
        }
        val tail = if (all.length > KEEP_STATUS_BYTES) all.takeLast(KEEP_STATUS_BYTES) else all
        val cut = tail.indexOf('\n')
        val kept = if (cut >= 0 && cut + 1 < tail.length) tail.substring(cut + 1) else tail
        f.writeText(
            "... older lines dropped (file exceeded ${MAX_STATUS_BYTES / 1024} KB) ...\n$kept"
        )
    }

    private companion object {
        // 上游是 "FanqieHook"。PLAN.md §3.1 要求移植时改 TAG —— logcat 过滤按 TAG 走
        // (`adb logcat -s SuperFqKill`)，留着上游的名字会让两个模块的日志混在一起分不清。
        // 取值来自顶层的 [MODULE_LOG_TAG]：UI 展示给用户的 logcat 命令用的是同一个常量。
        const val TAG = MODULE_LOG_TAG

        /** Status file is rolled once it exceeds this size, in bytes. */
        const val MAX_STATUS_BYTES = 256 * 1024

        /** After a roll we keep this much recent content (cut at a line boundary), in bytes. */
        const val KEEP_STATUS_BYTES = 192 * 1024

        val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
