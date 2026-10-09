package dev.superfqkill.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.superfqkill.core.MODULE_LOG_TAG
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 模块 App 侧的 [Application]：绑住 `XposedService` 并把它交给界面。
 *
 * ## 为什么只有这一条通道
 *
 * 状态面板要回答「模块在这个宿主里到底生效了没有」。可用的信息源只有一处：框架通过
 * [XposedService] 暴露的 `getScope()` 与 `getRunningTargets()`。squemaFQH 里另外那几条
 * 通道全部是死的或有害的，**一条都没有移植**（判定已成定论，不要重新加回来）：
 *
 *   - `HookStatusFiles`（`/data/local/tmp` marker）—— enforcing SELinux 下 `untrusted_app`
 *     域对 `/data/local/tmp`（`shell:shell` 0771，`shell_data_file` 类型）没有写权，
 *     反射 `chmod 0666` 也救不了：`FileOutputStream` 的 open 先失败。
 *   - `HookStatusStore` —— 写入侧（`markHooked`）零调用者，SharedPreferences 从来没被写过。
 *   - `HookStatusReporter` + `service/ServiceClient` + `ServiceProvider` + AIDL —— remote
 *     binder 永远是 null，因为 `SystemBootstrap.start()` 零调用者（`HookInit` 从未 override
 *     `onSystemServerStarting`）且 `scope.list` 里没有 `android` 条目；导出的 provider 还
 *     没有 `android:permission` 保护。
 *   - `RuntimeDetector` —— 100% 死代码，零引用。
 *   - `LspScopeReader` —— 主线程上阻塞式 `su` exec + `waitFor()`，可从 `onResume` 直达，
 *     且它的 3 秒 TTL 缓存被 `onResume` 故意 `invalidate()` 打穿；Magisk 一弹授权框就 ANR。
 *
 * ## 绑定是怎么发生的（不需要改清单）
 *
 * `io.github.libxposed:service` 这个 AAR 的清单里自带一个 exported 的
 * `io.github.libxposed.service.XposedProvider`（authority `${applicationId}.XposedService`），
 * AGP 会把它合并进我们的 APK —— 框架就是通过它把 binder 递进来的，然后
 * [XposedServiceHelper] 回调我们注册的 listener。所以本项目的 AndroidManifest.xml 里
 * 一个 `<provider>` 都没写是**正常的**（清单注释里那句"刻意没有任何 provider"指的是我们
 * 自己不再额外导出 squemaFQH 那两个无保护的 provider）。
 *
 * ContentProvider 的 `onCreate` 早于 [Application.onCreate]，因此 binder 可能在我们注册
 * listener 之前就到了；`XposedServiceHelper.registerListener` 会把已缓存的 service 立刻
 * 重放给新 listener（其实现里遍历 `mCache` 并逐个 `onServiceBind`），所以在 `onCreate`
 * 里注册不会漏掉早到的绑定。
 */
class ModuleApp : Application() {

    /** 界面刷新回调：service 绑定/断开时触发。 */
    interface StatusListener {
        fun onXposedStatusChanged()
    }

    /**
     * 当前绑定的服务，未绑定时为 null。
     *
     * `@Volatile`：[onServiceBind]/[onServiceDied] 在 binder 线程上回调，读的一侧是主线程。
     */
    @Volatile
    var xposedService: XposedService? = null
        private set

    private val listeners = CopyOnWriteArrayList<StatusListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // 整个进程只能注册一次：XposedServiceHelper 内部是单个 listener 槽位
        // （registerListener 直接覆盖 mListener，javadoc 也写着 "should only be called once"）。
        runCatching {
            XposedServiceHelper.registerListener(
                object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        xposedService = service
                        // 与模块日志共用 TAG：一条 `adb logcat -s SuperFqKill` 同时能看到
                        // 宿主进程里的 hook 安装摘要和模块 App 这边的绑定情况。
                        Log.i(
                            MODULE_LOG_TAG,
                            "XposedService bound: ${service.frameworkName} v${service.frameworkVersion}" +
                                " api=${service.apiVersion} scope=${safeScope(service)}"
                        )
                        notifyStatusChanged()
                    }

                    override fun onServiceDied(service: XposedService) {
                        xposedService = null
                        Log.w(MODULE_LOG_TAG, "XposedService died; status falls back to IDLE")
                        notifyStatusChanged()
                    }
                }
            )
        }.onFailure {
            // 库不在（例如被 R8 误裁）或框架侧 binder 异常。不能抛出去：Application.onCreate
            // 抛异常 = 模块 App 直接崩，用户连"为什么全是 IDLE"都看不到。
            Log.w(MODULE_LOG_TAG, "XposedServiceHelper.registerListener failed: $it")
        }
    }

    fun addStatusListener(listener: StatusListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeStatusListener(listener: StatusListener) {
        listeners.remove(listener)
    }

    /** 回调可能在 binder 线程上到达，界面刷新统一抛回主线程。 */
    private fun notifyStatusChanged() {
        mainHandler.post {
            for (listener in listeners) {
                // 一个界面回调炸了不该影响其余 listener，更不该崩掉 App。
                runCatching { listener.onXposedStatusChanged() }
                    .onFailure { Log.w(MODULE_LOG_TAG, "status listener failed: $it") }
            }
        }
    }

    private fun safeScope(service: XposedService): String =
        runCatching { service.scope.toString() }.getOrElse { "<error: ${it.javaClass.simpleName}>" }
}
