package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.util.Size
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds gearhead's own negotiated head-unit canvas dimensions into [CoolwalkRailCoordinator].
 *
 * **Why this step is required.** The `:car` process never sees the raw head-unit width:
 * - The HU-sized `LayoutDisplay` (1280x720 on DHU) only lives for 15ms in `:projection` before
 *   being destroyed; `:car` has no HU-sized VirtualDisplay, so
 *   [CoolwalkRailCoordinator.observeLiveVirtualDeviceHuSize] always comes up empty there;
 * - gearhead -> system_server binder calls are rejected by [io.github.nitsuya.aa.display.xposed.BridgeService]'s
 *   uid gate (`Someone else trying to get my binder?` in logs), and only system_server can write
 *   `CoolwalkRailStore`'s Settings.Global mirror, while system_server itself only knows the content-slot
 *   width -> the true width observed in `:projection` never reaches `:car`.
 *
 * Consequently `:car` only sees the "HU - sidebar" content slot (1200 on DHU),
 * `bestObservedFullHuWidthPx()` degenerates to 0, [CoolwalkProjectionBoundsHook] falls back to
 * `coerceAtLeast(trX)` -> `rightGap=0` -> no widening, and [CoolwalkDrawingSpecWiden] /
 * `DisplayProfileSettle` spin on 1200/1200, creating GhostActivityDisplay at content-slot width
 * (`layerStackRect: Rect(0, 0 - 1200, 720)`) -> AMap and other projected apps get a black bar on
 * the right while gearhead's own Coolwalk launcher and phone preview look normal.
 *
 * **Where the data comes from.** `:car` actually has the true value, just unread:
 * `CAR.WM.LOADING.MAN: Show for STARTUP, screen dimens DisplayParams(selectedIndex=0,
 * codecWidth=1280, ..., dispWidth=1280, dispHeight=720, dpi=160, scaledDimensions=1280x720, ...)`.
 * Obfuscation renames classes/fields but **never string constants**, so we parse `toString()`
 * (same `DisplayParams(selectedIndex=` marker as [AaDpiHook][io.github.nitsuya.aa.display.xposed.hook.aa.AaDpiHook])
 * without hardcoding any resolution — real head units vary (800x533@170, 715x633@170, DHU 1280x720@160).
 *
 * Timing is also sufficient: DisplayParams is logged at 42.164, [CoolwalkProjectionBoundsHook] first
 * evaluates at 42.175, and Ghost display is created at 42.183, so this feeds the width before all consumers.
 */
object CoolwalkHuScreenParamsHook {

    /** `DisplayParams.toString()` prefix, matching `AaDpiHook`. */
    private const val PARAMS_MARKER = "DisplayParams(selectedIndex="

    /** Fallback class marker: only DisplayParams has this field name. */
    private const val SCALED_MARKER = "scaledDimensions="

    /** Self-validation marker: any object treated as DisplayParams must contain this in `toString()`. */
    private const val DISP_MARKER = "dispWidth="

    /** Method logging the line above — guaranteed to receive the **selected** parameter set. */
    private const val SHOW_MARKER = "screen dimens"

    /** Sanity bounds only, not a resolution whitelist (715/800/1280 all fit). */
    private const val MIN_HU_PX = 200
    private const val MAX_HU_PX = 8192

    /** Cap on DexKit hits to avoid hooking a pile of similarly named classes. */
    private const val MAX_CANDIDATES = 4

    /**
     * Fallback when `toString()` is stripped by R8: read constructor args directly.
     * Indices come from the same constructor signature verified by `AaDpiHook`
     * (which modifies dpi via `args[8]`), matching `toString()` field order 1:1.
     */
    private const val ARG_DISP_W = 4
    private const val ARG_DISP_H = 5
    private const val ARG_SCALED = 12

    private val RE_DISP_W = Regex("dispWidth=(\\d{1,5})")
    private val RE_DISP_H = Regex("dispHeight=(\\d{1,5})")
    private val RE_SCALED = Regex("scaledDimensions=(\\d{1,5})x(\\d{1,5})")
    private val RE_DPI = Regex("dpi=(\\d{1,5})")

    private var installed = false

    private var paramsClassNames: List<String> = emptyList()
    private var showMethods: List<Method> = emptyList()

    @Volatile
    private var paramsClasses: List<Class<*>> = emptyList()

    /** Cache when consumer stores DisplayParams in a field (not passed via formal parameters). */
    @Volatile
    private var cachedHolderField: Field? = null

    /** Dimensions already fed to the coordinator, used to deduplicate repeated reports. */
    @Volatile
    private var acceptedWidth = 0

    @Volatile
    private var acceptedHeight = 0

    /**
     * All distinct sizes observed by constructors. >1 means the head unit advertises multiple
     * resolutions and construction order doesn't reflect the selected one; better to fall back
     * than let grow-only pick the largest blindly.
     */
    private val observedSizes = ConcurrentHashMap.newKeySet<String>()

    fun resolve(bridge: DexKitBridge, target: HookTarget) {
        if (paramsClassNames.isEmpty()) {
            paramsClassNames = runCatching {
                val byToString = findClassNames(bridge, PARAMS_MARKER)
                val bySizeField =
                    if (byToString.isEmpty()) findClassNames(bridge, SCALED_MARKER) else emptyList()
                (byToString + bySizeField).distinct().take(MAX_CANDIDATES)
            }.onFailure { e ->
                log(CoolwalkHookEnv.TAG, "AaUiHook: DexKit DisplayParams class query failed", e)
            }.getOrDefault(emptyList())
        }
        if (showMethods.isEmpty()) {
            showMethods = runCatching {
                bridge.findMethod {
                    searchPackages = listOf("")
                    matcher { usingStrings { add(SHOW_MARKER, StringMatchType.Contains, false) } }
                }.mapNotNull { md ->
                    runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
                }.distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.size}" }
                    .take(MAX_CANDIDATES)
                    .onEach { it.isAccessible = true }
            }.onFailure { e ->
                log(CoolwalkHookEnv.TAG, "AaUiHook: DexKit screen-dimens method query failed", e)
            }.getOrDefault(emptyList())
        }
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU params classes=$paramsClassNames show=" +
                showMethods.map { "${it.declaringClass.name}#${it.name}" },
        )
    }

    private fun findClassNames(bridge: DexKitBridge, marker: String): List<String> =
        bridge.findClass {
            searchPackages = listOf("")
            matcher { usingStrings { add(marker, StringMatchType.Contains, false) } }
        }.mapNotNull { runCatching { it.name }.getOrNull() }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        paramsClasses = paramsClassNames.mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }
        var shows = 0
        for (method in showMethods) {
            runCatching {
                method.hookBefore { param -> onShowInvoked(method, param.args, param.thisObject) }
                shows++
            }.onFailure { e ->
                log(CoolwalkHookEnv.TAG, "AaUiHook: hook screen-dimens ${method.name} failed", e)
            }
        }
        // Consumer method is guaranteed to receive the selected item and is the primary source;
        // constructor also feeds, but only when only one size has been seen so far (when a head unit
        // advertises multiple resolutions, ctor order is unreliable and rememberFullHuSize is grow-only).
        var ctors = 0
        for (clazz in paramsClasses) {
            for (ctor in clazz.declaredConstructors) {
                runCatching {
                    ctor.isAccessible = true
                    ctor.hookAfter { param -> onParamsConstructed(clazz, param.thisObject, param.args) }
                    ctors++
                }.onFailure { e ->
                    log(CoolwalkHookEnv.TAG, "AaUiHook: hook ${clazz.name} ctor failed", e)
                }
            }
        }
        if (shows == 0 && ctors == 0) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: HU screen params unavailable — falling back to content slot width")
            return
        }
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU screen params hooked show=$shows ctor=$ctors",
        )
    }

    private fun onShowInvoked(method: Method, args: Array<Any?>?, holder: Any?) {
        val text = args?.firstNotNullOfOrNull { textIfDisplayParams(it) }
            ?: textIfDisplayParams(holder)
            ?: holderFieldText(holder)
        if (text == null) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: ${method.name} did not carry DisplayParams")
            return
        }
        accept("show:${method.name}", parse(text))
    }

    private fun onParamsConstructed(clazz: Class<*>, instance: Any?, args: Array<Any?>?) {
        val text = runCatching { instance?.toString() }.getOrNull()
        val hu = text?.takeIf { it.contains(DISP_MARKER) }?.let { parse(it) } ?: parseArgs(args)
        if (hu == null) return
        val firstSeen = observedSizes.add("${hu.width}x${hu.height}")
        if (observedSizes.size > 1) {
            // Multiple candidate resolutions: construction order does not indicate selection; let consumer method decide.
            if (firstSeen) {
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: HU candidate ${hu.width}x${hu.height} ignored (seen $observedSizes)" +
                        " [ctor:${clazz.simpleName}]",
                )
            }
            return
        }
        accept("ctor:${clazz.simpleName}", hu)
    }

    private fun accept(source: String, hu: HuScreen?) {
        if (hu == null) return
        if (hu.width == acceptedWidth && hu.height == acceptedHeight) return
        acceptedWidth = hu.width
        acceptedHeight = hu.height
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU screen ${hu.width}x${hu.height} dpi=${hu.dpi} " +
                "disp=${hu.dispW}x${hu.dispH} [$source]",
        )
        if (hu.width <= hu.height) {
            // rememberFullHuSize only accepts landscape canvases and silently drops this; log it to save debugging time.
            log(CoolwalkHookEnv.TAG, "AaUiHook: HU non-landscape, dropped by rememberFullHuSize [$source]")
            return
        }
        CoolwalkRailCoordinator.rememberFullHuSize(hu.width, hu.height)
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU full=${CoolwalkRailCoordinator.observedFullHuWidthPx()} [$source]",
        )
    }

    /** Self-validation: class might be misidentified, but `dispWidth=` won't be. */
    private fun textIfDisplayParams(value: Any?): String? {
        if (value == null) return null
        val classes = paramsClasses
        if (classes.isNotEmpty() && classes.none { it.isInstance(value) }) return null
        val text = runCatching { value.toString() }.getOrNull() ?: return null
        return text.takeIf { it.contains(DISP_MARKER) }
    }

    /** Case where consumer method reads DisplayParams from a field rather than a parameter. */
    private fun holderFieldText(holder: Any?): String? {
        if (holder == null || paramsClasses.isEmpty()) return null
        cachedHolderField?.let { field ->
            return textIfDisplayParams(runCatching { field.get(holder) }.getOrNull())
        }
        val fields = runCatching { holder.javaClass.declaredFields }.getOrNull() ?: return null
        for (field in fields) {
            if (paramsClasses.none { it.isAssignableFrom(field.type) }) continue
            runCatching { field.isAccessible = true }
            val text = textIfDisplayParams(runCatching { field.get(holder) }.getOrNull()) ?: continue
            cachedHolderField = field
            return text
        }
        return null
    }

    private fun parse(text: String): HuScreen? {
        if (!text.contains(DISP_MARKER)) return null
        val dispW = RE_DISP_W.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val dispH = RE_DISP_H.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val scaled = RE_SCALED.find(text)?.groupValues
        val dpi = RE_DPI.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return build(
            scaledW = scaled?.getOrNull(1)?.toIntOrNull() ?: 0,
            scaledH = scaled?.getOrNull(2)?.toIntOrNull() ?: 0,
            dispW = dispW,
            dispH = dispH,
            dpi = dpi,
        )
    }

    /** Fallback when `toString()` is stripped: read constructor args, indices see [ARG_DISP_W]. */
    private fun parseArgs(args: Array<Any?>?): HuScreen? {
        if (args == null || args.size <= ARG_SCALED) return null
        val dispW = args[ARG_DISP_W] as? Int ?: return null
        val dispH = args[ARG_DISP_H] as? Int ?: return null
        val scaled = args[ARG_SCALED] as? Size
        return build(
            scaledW = scaled?.width ?: 0,
            scaledH = scaled?.height ?: 0,
            dispW = dispW,
            dispH = dispH,
            dpi = 0,
        )
    }

    /** scaledDimensions is what gearhead actually uses for layout (differs from disp* with non-square pixels). */
    private fun build(scaledW: Int, scaledH: Int, dispW: Int, dispH: Int, dpi: Int): HuScreen? {
        val useScaled = inHuRange(scaledW) && inHuRange(scaledH)
        val width = if (useScaled) scaledW else dispW
        val height = if (useScaled) scaledH else dispH
        if (!inHuRange(width) || !inHuRange(height)) return null
        return HuScreen(width, height, dpi, dispW, dispH)
    }

    private fun inHuRange(value: Int): Boolean = value in MIN_HU_PX..MAX_HU_PX

    private class HuScreen(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val dispW: Int,
        val dispH: Int,
    )
}
