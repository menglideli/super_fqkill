package dev.superfqkill.ui

import android.util.Log
import dev.superfqkill.core.MODULE_LOG_TAG
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService

/** 单个宿主的三态。语义与 squemaFQH 的 `ScopeStatus.State` 一致。 */
enum class ScopeState {
    /** 不在 LSPosed 作用域里（或服务没绑上，无从判断）—— 卡片不点亮。 */
    OFF,

    /** 在作用域里，但没有"进程正在运行"的证据。 */
    SCOPED,

    /** 在作用域里 **且** 宿主主进程正在被框架 hook。 */
    LIVE
}

/**
 * @param state 三态结果。
 * @param inScope `getScope()` 是否包含该包名。
 * @param running LIVE 时对应的那个 [HookedTarget]，其余情况为 null。它的 `state`
 *   （UP_TO_DATE / STALE / RELOADING / FAILED）与 `loadedVersionCode` 是**唯一**能从框架
 *   拿到的"注入到底成不成功"的信号，所以一并带出来给界面显示。
 */
data class HostStatus(
    val state: ScopeState,
    val inScope: Boolean,
    val running: HookedTarget?
) {
    /** 卡片是否点亮（金色）。与 squemaFQH 的 `isLit` 同义：SCOPED 与 LIVE 都算亮。 */
    val lit: Boolean get() = state != ScopeState.OFF
}

/**
 * 从 [XposedService] 推导每个宿主的状态。
 *
 * ## 与 squemaFQH 的两点关键差别
 *
 * **1. `getRunningTargets()` 直接调用，不用反射。** squemaFQH 依赖的是
 * `io.github.libxposed:service:101.0.0`，那个版本**没有**这个方法，所以它只能
 * `service.getClass().getMethod("getRunningTargets")` 反射探测，然后每次都吃
 * `NoSuchMethodException` → 永远返回 false → **LIVE 那盏灯永远点不亮**。本项目依赖
 * 102.0.0，方法是强类型的 `List<HookedTarget>`，直接调即可。
 *
 * 反射虽然不需要了，**防御性 try/catch 仍然保留**，因为要挡的不是"方法不存在"而是
 * 运行期两种真实异常：框架侧只实现了 API 101 时抛 `UnsupportedOperationException`
 * （`getRunningTargets` 标着 `@SinceApi(API_102)`，其实现第一件事就是 `requireApi`），
 * 服务已死时抛 `XposedService.ServiceException`（RuntimeException 的子类）。
 * 也就是说：**编译期确定有这个方法，运行期仍可能没有这个能力。**
 *
 * **2. 只按主进程名精确匹配。** [HookedTarget] 在 102.0.0 里**没有** `getPackageName()`
 * （已核验其全部公开成员：getUid / getPid / getProcessName / getState /
 * getLoadedVersionCode），所以 squemaFQH 那个"先试 getPackageName 再试 getProcessName"的
 * 反射匹配里，前者从来不可能成功。这里直接用 processName，并且**只认等于包名的那个**：
 * `ModuleEntry` 的门控 3 明确规定只在 `processName == packageName` 时装 hook，所以
 * `com.dragon.read:push` 正在运行**完全不能**说明我们的 hook 生效了。
 * squemaFQH 需要 `pkg + ":"` 前缀匹配是因为它给夸克装了多进程 hook，我们没有这个需求。
 *
 * ## 服务没绑上时怎么显示
 *
 * 返回 [ScopeState.OFF]。这是刻意的：**不知道**就显示"没生效"，不显示"可能生效"。
 * squemaFQH 在这里还会退回 `LspScopeReader`（阻塞式 `su` + Magisk 授权框 → ANR），
 * 那条路不移植；界面上由 `hint_no_service` 文案解释为什么全是 IDLE。
 */
object ScopeStatus {

    fun resolve(service: XposedService?, pkg: String): HostStatus {
        if (!isInScope(service, pkg)) {
            return HostStatus(ScopeState.OFF, inScope = false, running = null)
        }
        val running = findRunningTarget(service, pkg)
        return HostStatus(
            state = if (running != null) ScopeState.LIVE else ScopeState.SCOPED,
            inScope = true,
            running = running
        )
    }

    /** `getScope()` 里是否有这个包名。服务为 null 或调用失败一律 false。 */
    fun isInScope(service: XposedService?, pkg: String): Boolean {
        if (service == null) return false
        return runCatching { service.scope.contains(pkg) }
            .getOrElse {
                Log.w(MODULE_LOG_TAG, "getScope failed: ${it.javaClass.simpleName}: ${it.message}")
                false
            }
    }

    /** 宿主**主进程**是否正在被 hook；找不到或服务不可用时返回 null。 */
    fun findRunningTarget(service: XposedService?, pkg: String): HookedTarget? {
        if (service == null) return null
        return runCatching {
            service.runningTargets.firstOrNull { it.processName == pkg }
        }.getOrElse {
            // UnsupportedOperationException = 框架只到 API 101；ServiceException = 服务已死。
            // 两种都不是错误，降级成 SCOPED 就行。
            Log.w(
                MODULE_LOG_TAG,
                "getRunningTargets unavailable (${it.javaClass.simpleName}: ${it.message}); " +
                    "LIVE degrades to SCOPED"
            )
            null
        }
    }

    /** 给界面底部提示用的框架标识，服务没绑上时返回 null。 */
    fun frameworkLabel(service: XposedService?): String? {
        if (service == null) return null
        return runCatching {
            "${service.frameworkName} v${service.frameworkVersion} (api ${service.apiVersion})"
        }.getOrNull()
    }
}
