package dev.superfqkill.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import dev.superfqkill.R
import dev.superfqkill.core.MODULE_LOG_TAG
import dev.superfqkill.core.STATUS_FILE_NAME
import dev.superfqkill.packs.PKG_FANQIE
import dev.superfqkill.packs.PKG_HONGGUO

/**
 * 模块 App 的主界面：两张宿主状态卡片 + 「去哪看 hook 明细」+ 一条设计取舍告知。
 *
 * ## 几个刻意的设计决定
 *
 * **纯 `android.app.Activity`，不用 AppCompat/Material。** `app/build.gradle.kts` 只声明了
 * `androidx.annotation`（编译期注解），没有任何 UI 库，所以布局只能用框架自带控件 ——
 * squemaFQH 的 `MainActivity` 同样是 `extends Activity`，这条路是被验证过能跑的。
 *
 * **卡片由一张表驱动**（[bindCard] + [Card]），与 squemaFQH 的 `Card[]` 同构：新增一个宿主
 * 只要 (a) 在 `onCreate` 里加一行 `bindCard`，(b) 在 `activity_main.xml` 里加一个 `<include>`，
 * (c) 加一条 `target_xxx` 字符串。其余代码不用动。
 *
 * **不显示 per-hook 明细表。** `InstallSummary` 是在**宿主进程**里算出来的，而 host→module
 * 方向目前没有可用通道：`getRemotePreferences` 是反方向（模块 App 写、宿主读），
 * `/data/local/tmp` 被 SELinux 挡死，导出的 ContentProvider 又要宿主对模块有包可见性
 * （番茄/红果未必有），且每种方案都得在真机上验证权限与 SELinux 行为。**v0.1 不发明 IPC**，
 * 而是把日志入口直接摆在界面上：`adb logcat -s SuperFqKill` 与每个宿主自己的状态文件路径
 * （见 `res/layout/view_status_card.xml` 里那条可选中复制的文本）。
 * 界面里 `detail_col_id` / `detail_col_state` / `detail_col_hits` 三个字符串因此被用作
 * **日志字段图例**，而不是表格列头。
 *
 * **激励入口失效的告知放在页面下方、带金边底框**（`R.id.reward_notice`）。这是本模块唯一
 * 会削弱用户已有功能的取舍：零广告闸门连用户主动点的「看视频得金币」也一起关掉。
 * 两个上游都只在代码注释里披露，用户看不见；这里搬到界面上，文案见
 * `strings.xml` 的 `reward_notice_body`。
 *
 * ## 刷新时机
 *
 * `onResume`（回到前台重算一次）+ [ModuleApp.StatusListener]（服务绑定/断开时框架回调）。
 * **不**做 squemaFQH 那种 `onResume` 里阻塞读 scope 的事（那是它的 ANR 来源），
 * 这里所有查询都是 binder 上的同步小调用，且失败一律降级成 OFF。
 */
class MainActivity : Activity(), ModuleApp.StatusListener {

    private val cards = mutableListOf<Card>()

    private lateinit var hintText: TextView

    /**
     * 清单里 `<application android:name=".ui.ModuleApp">` 保证了它就是 [ModuleApp]。
     * 仍用安全转换：instrumented 测试可能用别的 Application 拉起本 Activity，
     * 那时当作"服务没绑上"（全部 IDLE）而不是崩掉。
     */
    private val app: ModuleApp? get() = application as? ModuleApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 setContentView 之前:清单是冻结文件、没有声明 android:theme,
        // 不换主题就会拿到系统默认的浅色带 ActionBar 主题。详见 values/styles.xml 的注释。
        setTheme(R.style.AppTheme)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.detail_logcat).text =
            getString(R.string.detail_logcat_cmd, MODULE_LOG_TAG)
        findViewById<TextView>(R.id.detail_legend).text = getString(
            R.string.detail_legend,
            getString(R.string.detail_col_id),
            getString(R.string.detail_col_state),
            getString(R.string.detail_col_hits)
        )
        hintText = findViewById(R.id.hint_text)

        cards += bindCard(R.id.card_fanqie, PKG_FANQIE, R.string.target_fanqie)
        cards += bindCard(R.id.card_hongguo, PKG_HONGGUO, R.string.target_hongguo)
        // 首次刷新交给 onResume(它必然在 onCreate 之后跑),不在这里重复刷一遍。
    }

    override fun onStart() {
        super.onStart()
        app?.addStatusListener(this)
    }

    override fun onStop() {
        app?.removeStatusListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onXposedStatusChanged() {
        refresh()
    }

    /**
     * 一张卡片的视图集合。
     *
     * 子视图必须用 `root.findViewById` 取：两张卡是同一个 `view_status_card.xml` 的两次
     * `<include>`，子 id 完全相同，用 Activity 级的 `findViewById` 只会永远拿到第一张卡的。
     * （卡片根 id 由 `<include android:id="@+id/card_xxx">` 覆盖掉布局里的
     * `status_card_root`，所以两张卡的根是可区分的。）
     */
    private class Card(val root: View, val pkg: String) {
        val icon: TextView = root.findViewById(R.id.status_card_icon)
        val title: TextView = root.findViewById(R.id.status_card_title)
        val subtitle: TextView = root.findViewById(R.id.status_card_subtitle)
        val pkgView: TextView = root.findViewById(R.id.status_card_package)
        val version: TextView = root.findViewById(R.id.status_card_version)
        val chip: TextView = root.findViewById(R.id.status_card_chip)
        val logcat: TextView = root.findViewById(R.id.status_card_logcat)
    }

    private fun bindCard(rootId: Int, pkg: String, titleRes: Int): Card {
        val card = Card(findViewById(rootId), pkg)
        card.title.setText(titleRes)
        card.pkgView.text = pkg
        card.logcat.text = getString(R.string.card_logcat_text, MODULE_LOG_TAG, pkg, STATUS_FILE_NAME)
        return card
    }

    private fun refresh() {
        val service = app?.xposedService
        for (card in cards) {
            applyCard(card, ScopeStatus.resolve(service, card.pkg))
        }
        val framework = ScopeStatus.frameworkLabel(service)
        hintText.text = if (service != null) {
            val base = getString(R.string.hint_service_bound)
            if (framework == null) base else "$base\n$framework"
        } else {
            getString(R.string.hint_no_service)
        }
    }

    private fun applyCard(card: Card, status: HostStatus) {
        val lit = status.lit
        val live = status.state == ScopeState.LIVE

        card.root.setBackgroundResource(
            if (lit) R.drawable.bg_glass_card_active else R.drawable.bg_glass_card_idle
        )
        card.icon.text = if (lit) ICON_LIT else ICON_IDLE
        card.icon.setTextColor(getColor(if (lit) R.color.gold_bright else R.color.text_muted))

        // LIVE 时把框架报的 HookedTarget.state 附在后面:STALE = 宿主升级过而模块还是旧代,
        // FAILED = 框架报告注入失败。这两个都是"看着勾上了其实没生效"的直接证据,
        // 比只写"运行中"有用得多。
        card.subtitle.text = when {
            live && status.running != null -> getString(
                R.string.state_with_detail,
                getString(R.string.state_running),
                status.running.state.name
            )
            live -> getString(R.string.state_running)
            lit -> getString(R.string.state_scoped)
            else -> getString(R.string.state_not_scoped)
        }
        card.subtitle.setTextColor(getColor(if (lit) R.color.text_gold else R.color.text_secondary))

        card.version.text = hostVersionText(card.pkg)

        card.chip.setText(
            when (status.state) {
                ScopeState.LIVE -> R.string.state_live
                ScopeState.SCOPED -> R.string.state_scope
                ScopeState.OFF -> R.string.state_off
            }
        )
        card.chip.setBackgroundResource(
            if (lit) R.drawable.bg_status_chip_active else R.drawable.bg_status_chip_idle
        )
        card.chip.setTextColor(getColor(if (lit) R.color.text_primary else R.color.text_secondary))
    }

    /**
     * 已安装宿主的 `versionName (versionCode)`，没装则显示"未安装"。
     *
     * 这里可以正常用 `PackageManager`：清单里的 `<queries>` 已经声明了两个目标包
     * （API 30+ 的包可见性要求），而且模块 App 侧的 Context 是完整的。
     * 与宿主进程里的 `ModuleEntry.readHostVersionCode` 不是一回事 —— 那边在
     * `onPackageReady` 阶段 Application 还不存在，`PackageManager` 路线实测会 NPE，
     * 所以它只能自己解析二进制 AXML（见 `core/ApkVersion.kt`）。
     *
     * 显示版本号的理由：install summary 该对上哪条审计基线，完全取决于宿主版本
     * （见 `packs/FeaturePack.kt` 的 `AUDITED_VERSION_CODES`）。
     */
    @Suppress("DEPRECATION")
    private fun hostVersionText(pkg: String): String {
        val info = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            ?: return getString(R.string.host_not_installed)
        // `longVersionCode` 是 API 28+;minSdk 26,所以低版本仍走 `versionCode`。
        // 新的 `PackageManager.PackageInfoFlags` 重载要 API 33+,同样够不到 minSdk,
        // 因此这里保留 int 重载并压掉 deprecation 警告。
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        return getString(R.string.host_version_installed, info.versionName ?: "?", code)
    }

    private companion object {
        /** 点亮 / 未点亮的图标字符（与 squemaFQH 一致：对勾 / 间隔号）。 */
        const val ICON_LIT = "\u2713"
        const val ICON_IDLE = "\u00B7"
    }
}
