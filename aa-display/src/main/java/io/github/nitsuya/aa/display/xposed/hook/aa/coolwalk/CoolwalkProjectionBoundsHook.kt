package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.graphics.Rect
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Coolwalk compositor window geometry (`{blX=…, trX=…, width=…}`).
 *
 * Content surfaces are allocated from `trX − blX`. After FacetBar starve, AA still builds
 * windows at `blX=rail` (e.g. 107→1280 ⇒ width 1173) — that is the left black bar.
 * Zero [blX] when it looks like a single Coolwalk rail so the next Surface/DrawingSpec
 * is full HU.
 */
object CoolwalkProjectionBoundsHook {

    private val installed = AtomicBoolean(false)

    @Volatile
    private var boundsClassName: String? = null

    fun rememberClassName(name: String?) {
        if (!name.isNullOrEmpty()) boundsClassName = name
    }

    fun cachedClassName(): String? = boundsClassName

    fun install(classLoader: ClassLoader, className: String?) {
        if (className.isNullOrEmpty()) return
        if (!installed.compareAndSet(false, true)) return
        val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: run {
            installed.set(false)
            log(CoolwalkHookEnv.TAG, "AaUiHook: projection bounds class missing: $className")
            return
        }
        var hooked = 0
        for (ctor in clazz.declaredConstructors) {
            val p = ctor.parameterTypes
            // (blX, blY, trX, trY, z, Rect, …)
            if (p.size < 5) continue
            if (p[0] != Int::class.javaPrimitiveType ||
                p[1] != Int::class.javaPrimitiveType ||
                p[2] != Int::class.javaPrimitiveType ||
                p[3] != Int::class.javaPrimitiveType ||
                p[4] != Int::class.javaPrimitiveType
            ) {
                continue
            }
            ctor.isAccessible = true
            ctor.hookBefore { param ->
                val blX = param.args[0] as? Int ?: return@hookBefore
                val blY = param.args[1] as? Int ?: return@hookBefore
                val trX = param.args[2] as? Int ?: return@hookBefore
                val trY = param.args[3] as? Int ?: return@hookBefore
                if (trX <= blX + 2 || trY <= blY + 2) return@hookBefore
                if (isHuTruthPending()) return@hookBefore
                val fullHint = CoolwalkRailCoordinator.bestObservedFullHuWidthPx().coerceAtLeast(trX)
                val expandLeft = shouldExpandLeftInset(blX, blY, trX, trY)
                // Zeroing blX alone leaves trX at content-slot right (e.g. 1173 on 1280 HU)
                // → right black bar. Expand trX when the residual gap looks like one rail.
                // The same residual also appears with blX already 0 (`blX=0, trX=1200` on a
                // 1280 HU): the left rail was reclaimed upstream by content_bounds, but the
                // window is still one rail narrow — that is 高德's right-edge black bar.
                val expandRight = shouldExpandRightEdge(blX, trX, fullHint)
                if (!expandLeft && !expandRight) return@hookBefore
                if (expandLeft) {
                    param.args[0] = 0
                    // systemInsets Rect (if present) often mirrors the left rail.
                    (param.args.getOrNull(5) as? Rect)?.let { insets ->
                        if (insets.left in 8..240) {
                            insets.left = 0
                        }
                    }
                }
                if (expandRight) {
                    param.args[2] = fullHint
                }
                logDebug(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: expand projection bounds blX $blX→${param.args[0]} " +
                        "slot=${trX - blX}→${(param.args[2] as Int) - (param.args[0] as Int)} " +
                        "fullHint=$fullHint left=$expandLeft" +
                        if (expandRight) " trX→$fullHint" else "",
                )
            }
            hooked++
        }
        if (hooked > 0) {
            boundsClassName = className
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked projection bounds ctors=$hooked class=$className")
        } else {
            installed.set(false)
            log(CoolwalkHookEnv.TAG, "AaUiHook: projection bounds ctor not matched class=$className")
        }
    }

    /**
     * True when [blX] is a Coolwalk left-rail inset on a full-HU [trX] window
     * (width = trX − blX is the content slot that becomes the black bar).
     */
    internal fun shouldExpandLeftInset(blX: Int, blY: Int, trX: Int, trY: Int): Boolean {
        if (blX <= 1 || trX <= blX + 2) return false
        if (trY <= blY + 2) return false
        val snap = CoolwalkRailCoordinator.current()
        if (snap.phase == RailPhase.ReconnectSettling &&
            snap.fullHuWidthPx <= 0 &&
            snap.fullBleedStableCount == 0
        ) {
            return false
        }
        val slotW = trX - blX
        val fullHint = CoolwalkRailCoordinator.bestObservedFullHuWidthPx().coerceAtLeast(trX)
        val touch = snap.touchRailWidthPx
        if (touch > 1 && abs(blX - touch) <= 2) return true
        if (CoolwalkRailMath.isPlausibleRailGap(blX, fullHint, touch)) return true
        if (DisplayProfileSettle.isContentSlotVsFull(slotW, fullHint) &&
            CoolwalkRailMath.isPlausibleRailGap(fullHint - slotW, fullHint, touch)
        ) {
            return true
        }
        return false
    }

    /**
     * True when a content window ends one Coolwalk rail short of the head unit — that residual
     * *is* the right-edge black bar. Covers the case [shouldExpandLeftInset] cannot see:
     * `blX=0, trX=1200` on a 1280 HU, i.e. the left rail was already reclaimed by the
     * content_bounds path but the window is still a content slot wide, so every projected app
     * (高德 etc.) loses the right rail's worth of pixels while gearhead's own Coolwalk launcher —
     * drawn into the real full-width window — looks correct.
     *
     * Needs a real head-unit width from
     * [CoolwalkHuScreenParamsHook] / [CoolwalkRailCoordinator]: without one, `fullHint` collapses
     * to [trX] and this is a no-op. Narrow chrome (facet bar, dashboard) is excluded by requiring
     * the window to cover at least half the head unit, and the residual must still pass the
     * single-rail gap test, so a 80px-wide rail window can never be "expanded" to full width.
     */
    internal fun shouldExpandRightEdge(blX: Int, trX: Int, fullHint: Int): Boolean {
        if (fullHint <= trX + 1) return false
        val slotW = trX - blX.coerceAtLeast(0)
        if (slotW <= 0 || slotW * 2 < fullHint) return false
        return DisplayProfileSettle.isContentSlotVsFull(trX, fullHint)
    }

    /**
     * True while the head-unit width is still unknown mid-reconnect. Acting then widens
     * LayoutInfo before content_bounds, which crashed `:projection` and left a black bar.
     */
    private fun isHuTruthPending(): Boolean {
        val snap = CoolwalkRailCoordinator.current()
        return snap.phase == RailPhase.ReconnectSettling &&
            snap.fullHuWidthPx <= 0 &&
            snap.fullBleedStableCount == 0
    }
}
