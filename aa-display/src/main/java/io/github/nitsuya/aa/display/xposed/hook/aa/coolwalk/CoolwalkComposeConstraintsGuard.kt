package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * gearhead 17.x's head unit UI is Compose. Hiding the UI control stack
 * ([io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook]'s hideAaUiControls) starves
 * Coolwalk's GhFacetBar VD to 1xH, Dashboard VD to 1x1, and zeroes leftover window widths.
 *
 * Compose's `Constraints(minWidth, maxWidth, minHeight, maxHeight)` factory enforces:
 * `maxWidth >= minWidth && maxHeight >= minHeight && minWidth >= 0 && minHeight >= 0`,
 * otherwise throwing [IllegalArgumentException]
 * `maxWidth must be >= than minWidth,\nmaxHeight must be >= than minHeight,\nminWidth and minHeight must be >= 0`.
 *
 * Split-pane containers (`Lmrs;` MeasurePolicy, `Llwd;` layout with `IS_HORIZONTAL`) measure by
 * subtracting the first group's used space from main-axis available space as the second group's budget,
 * writing it back via `Constraints.copy`. When the container has 0/1 px left, the difference is negative,
 * failing the assertion and crashing `gearhead:projection` in `AndroidComposeView.dispatchDraw`
 * (manifesting as an Android Auto crash when connecting to car/DHU).
 *
 * Here we only hook the two validation entry points: **valid arguments pass through untouched**
 * (steady-state cost is four int comparisons); only tuples that would inevitably throw are clamped
 * to valid bounds (negative max clamped to 0, min clamped to <= max), equivalent to "no space on
 * this axis" so children measure as 0, matching Compose's handling of 0-sized containers.
 * Does not swallow exceptions, alter any valid constraints, or depend on which window was starved.
 */
object CoolwalkComposeConstraintsGuard {

    // AA 17.8 changed this assertion to include format specifiers:
    //   "maxWidth(%d) must be >= than minWidth(%d),\nmaxHeight(%d) must be >= than minHeight(%d)"
    // The old string "maxWidth must be >= than minWidth" is no longer a substring, giving 0 DexKit hits.
    // Use the shared infix "must be >= than minWidth", which matches both 17.2 and 17.8.
    private const val FAILURE_MARKER = "must be >= than minWidth"
    private const val LOG_LIMIT = 8

    /**
     * All `Constraints(minWidth, maxWidth, minHeight, maxHeight): long` validation entry points that
     * throw this assertion. 17.3 has two (one compares directly, one computes a boolean first); both
     * must be hooked, otherwise whichever path fires first will crash.
     */
    private var factories: List<Method> = emptyList()

    private var installed = false
    private var repaired = 0

    fun resolve(bridge: DexKitBridge, target: HookTarget) {
        if (factories.isNotEmpty()) return
        val candidates = runCatching {
            bridge.findMethod {
                searchPackages = listOf("")
                matcher {
                    usingStrings { add(FAILURE_MARKER, StringMatchType.Contains, false) }
                }
            }.mapNotNull { md ->
                runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
            }
        }.onFailure { e ->
            log(CoolwalkHookEnv.TAG, "AaUiHook: DexKit Constraints factory query failed", e)
        }.getOrDefault(emptyList())

        val matched = candidates.filter { m ->
            Modifier.isStatic(m.modifiers) &&
                m.returnType == Long::class.javaPrimitiveType &&
                m.parameterTypes.size == 4 &&
                m.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.onEach { it.isAccessible = true }
        if (matched.isEmpty()) {
            log(
                CoolwalkHookEnv.TAG,
                "AaUiHook: Compose Constraints factory not found, candidates=" +
                    candidates.map { "${it.declaringClass.name}#${it.name}" },
            )
            return
        }
        factories = matched
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: Compose Constraints factories=${matched.size} " +
                matched.map { "${it.declaringClass.name}#${it.name}" },
        )
    }

    fun install() {
        if (installed) return
        if (factories.isEmpty()) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: Compose Constraints guard unavailable")
            return
        }
        installed = true
        var hooked = 0
        for (method in factories) {
            runCatching {
                method.hookBefore { param -> clampConstraintArgs(param.args) }
                hooked++
            }.onFailure { e ->
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: hook Constraints ${method.declaringClass.name}#${method.name} failed",
                    e,
                )
            }
        }
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: Compose Constraints guard hooked=$hooked/${factories.size}",
        )
    }

    internal fun clampConstraintArgs(args: Array<Any?>?) {
        if (args == null || args.size != 4) return
        val minW = args[0] as? Int ?: return
        val maxW = args[1] as? Int ?: return
        val minH = args[2] as? Int ?: return
        val maxH = args[3] as? Int ?: return
        if (minW >= 0 && minH >= 0 && maxW >= minW && maxH >= minH) return

        val safeMaxW = maxW.coerceAtLeast(0)
        val safeMinW = minW.coerceIn(0, safeMaxW)
        val safeMaxH = maxH.coerceAtLeast(0)
        val safeMinH = minH.coerceIn(0, safeMaxH)
        args[0] = safeMinW
        args[1] = safeMaxW
        args[2] = safeMinH
        args[3] = safeMaxH
        note("w[$minW..$maxW]→[$safeMinW..$safeMaxW] h[$minH..$maxH]→[$safeMinH..$safeMaxH]")
    }

    private fun note(detail: String) {
        repaired++
        when {
            // Print stack trace on first occurrence to confirm the fix lands on the split-pane measure path.
            repaired == 1 -> log(
                CoolwalkHookEnv.TAG,
                "AaUiHook: repair Constraints $detail #1",
                Throwable("constraints-repair-trace"),
            )
            repaired <= LOG_LIMIT ->
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: repair Constraints $detail #$repaired")
            repaired % 500 == 0 ->
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: repaired Constraints x$repaired")
        }
    }
}
