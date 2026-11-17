package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The Coolwalk empty media card is the **Dashboard** presentation (~304x460 on an 800x480 head unit),
 * displaying "Media unavailable".
 *
 * Starving the `name=Dashboard` VD is handled in [AaCoolwalkCompositorHook] / [CoolwalkCompositorPolicy]
 * (create / Builder / resize). This hook only does two things:
 *
 * 1. Keep [MEDIA_CAR_APP] **enabled** so AA Now Playing metadata can still be sent to the head unit;
 * 2. Hide leftover Dashboard Presentation windows and swallow Coolwalk's own Dashboard coverage
 *    assertions after `content_bounds` is widened.
 *
 * Item 2 is a required companion guard for hiding the UI control stack ([AaUiHook.hideAaUiControls]):
 * after the content window is widened to the full head unit width, the sibling Dashboard window
 * retains its original right-side geometry, so AA in `:car` asserts
 * `does not mostly cover or provide a hint for one side of` and throws [IllegalStateException],
 * killing `:car` and crashing `:projection` via dead binder (Android Auto crashes on DHU connect).
 *
 * Never hides [io.github.nitsuya.aa.display.ui.aa.AaDisplayActivity]'s projection window.
 */
object AaMediaPlaceholderHook : AaHook() {
    override val tagName: String = "AAD_AaMediaPlaceholderHook"

    private const val MEDIA_CAR_APP =
        "com.google.android.apps.auto.components.media.app.MediaCarAppService"

    private var insetAssertMethods: List<Method> = emptyList()

    /** Same toggle as [AaUiHook]: this guard is only needed when hiding UI controls is enabled. */
    private var hideAaUiControls: Boolean = true

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun isEnabled(config: SharedPreferences?, processName: String): Boolean {
        hideAaUiControls = AADisplayConfig.HideAaUiControls.get(config)
        return hideAaUiControls
    }

    override fun loadDexClass(bridge: DexKitBridge, target: HookTarget) {
        insetAssertMethods = listOf(
            "does not mostly cover or provide a hint for one side of",
            "does not fully cover",
        ).flatMap { needle ->
            runCatching {
                bridge.findMethod {
                    searchPackages = listOf("")
                    matcher { usingStrings(needle) }
                }.mapNotNull { md ->
                    runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
                }
            }.onFailure { e ->
                log(tagName, "DexKit inset assert query failed: $needle", e)
            }.getOrDefault(emptyList())
        }.distinctBy {
            "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}"
        }
        log(
            tagName,
            "inset assert methods=${insetAssertMethods.size} " +
                insetAssertMethods.map { "${it.declaringClass.name}#${it.name}" },
        )
    }

    override fun hook(config: SharedPreferences?, target: HookTarget) {
        ensureMediaCarAppEnabled()
        hideDashboardOnAddView()
        suppressDashboardCoverAssert()
    }

    /**
     * Head unit media host must stay enabled. Older versions disabled it, breaking Now Playing completely.
     */
    private fun ensureMediaCarAppEnabled() {
        runCatching {
            val ctx: Context = InitFields.appContext
            val cn = ComponentName(ctx.packageName, MEDIA_CAR_APP)
            val pm = ctx.packageManager
            val state = pm.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            ) {
                logDebug(tagName, "MediaCarApp already enabled/default state=$state")
                return
            }
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            logDebug(tagName, "re-enabled $MEDIA_CAR_APP (was state=$state)")
        }.onFailure { e ->
            log(tagName, "ensure $MEDIA_CAR_APP enabled failed", e)
        }
    }

    /** Original inset logic runs as usual; only swallow [IllegalStateException] for Dashboard cover assertion. */
    private fun suppressDashboardCoverAssert() {
        if (insetAssertMethods.isEmpty()) {
            log(tagName, "no Coolwalk inset-assert methods; cover crash not guarded")
            return
        }
        var hooked = 0
        for (method in insetAssertMethods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        return try {
                            XposedBridge.invokeOriginalMethod(
                                param.method,
                                param.thisObject,
                                param.args,
                            )
                        } catch (e: InvocationTargetException) {
                            val cause = e.cause ?: e
                            if (!isDashboardCoverAssert(cause)) throw cause
                            logDebug(
                                tagName,
                                "suppress ${method.declaringClass.simpleName}#${method.name} " +
                                    "Dashboard cover assert",
                            )
                            dummyInsetResult(method)
                        }
                    }
                })
                hooked++
            } catch (e: Throwable) {
                log(tagName, "hook inset ${method.declaringClass.name}#${method.name} failed", e)
            }
        }
        log(tagName, "hooked inset assert methods=$hooked/${insetAssertMethods.size}")
    }

    private fun isDashboardCoverAssert(t: Throwable): Boolean {
        if (t !is IllegalStateException) return false
        val msg = t.message ?: return false
        if (!msg.contains("Dashboard")) return false
        return msg.contains("does not mostly cover") || msg.contains("does not fully cover")
    }

    private fun dummyInsetResult(method: Method): Any? {
        val rt = method.returnType
        if (rt == Void.TYPE || rt == Void::class.java) return null
        if (rt == Rect::class.java) return Rect()
        return null
    }

    private fun hideDashboardOnAddView() {
        try {
            findMethod(Class.forName("android.view.WindowManagerGlobal")) {
                name == "addView" && parameterCount >= 2
            }.hookBefore { param ->
                val root = param.args[0] as? View ?: return@hookBefore
                val lp = param.args.getOrNull(1) as? WindowManager.LayoutParams
                if (!isDashboardWindow(lp, root)) return@hookBefore
                hideDashboard(root, lp)
            }
        } catch (e: Throwable) {
            log(tagName, "hook WindowManagerGlobal.addView failed", e)
        }
    }

    private fun isDashboardWindow(lp: WindowManager.LayoutParams?, root: View): Boolean {
        val title = lp?.title?.toString().orEmpty()
        if (title.contains("Dashboard", ignoreCase = true)) return true
        // Leftover lyric/media-browse windows — never match AaDisplayActivity projection.
        if (!title.contains("AaDisplayActivity", ignoreCase = true) &&
            !title.contains("AaMainFragment", ignoreCase = true)
        ) {
            if (title.contains("ClusterLyric", ignoreCase = true)) return true
            if (title.contains("MediaBrowse", ignoreCase = true) &&
                title.contains(BuildConfig.APPLICATION_ID, ignoreCase = true)
            ) {
                return true
            }
        }
        if (lp != null && lp.type == 2030 /* TYPE_PRIVATE_PRESENTATION */) {
            val w = lp.width
            val h = lp.height
            if (w in 200..400 && h in 360..520 && h > w) return true
        }
        return root.javaClass.name.contains("Dashboard", ignoreCase = true)
    }

    private fun hideDashboard(root: View, lp: WindowManager.LayoutParams?) {
        root.alpha = 0f
        root.visibility = View.GONE
        root.isClickable = false
        root.isFocusable = false
        if (root is ViewGroup) {
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        if (lp != null) {
            lp.width = 0
            lp.height = 0
            lp.alpha = 0f
        }
        logDebug(tagName, "hide Dashboard title=${lp?.title}")
    }
}
