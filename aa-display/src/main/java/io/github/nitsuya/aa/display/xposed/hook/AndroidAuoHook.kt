package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.HookPreferences
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBasicsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBtnEventHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaDpiHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaMediaPlaceholderHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaPropsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaSignatureHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook
import io.github.nitsuya.aa.display.xposed.log
import org.luckypray.dexkit.DexKitBridge
import kotlin.system.measureTimeMillis


abstract class AaHook {
    companion object {
        const val processMain =       "com.google.android.projection.gearhead"
        const val processProjection = "com.google.android.projection.gearhead:projection"
        const val processCar =        "com.google.android.projection.gearhead:car"
    }
    abstract val tagName: String
    abstract fun isSupportProcess(processName: String) : Boolean

    /**
     * Config-level switch: even if [isSupportProcess] matches, the entire hook can be skipped by config.
     * Called once before [loadDexClass] / [hook] so the hook can cache its own toggle state.
     */
    open fun isEnabled(config: SharedPreferences?, processName: String): Boolean = true
    open fun loadDexClass(bridge: DexKitBridge, target: HookTarget) {}
    abstract fun hook(config: SharedPreferences?, target: HookTarget)
}

object AndroidAuoHook : BaseHook() {
    override val tagName: String = "AAD_AndroidAuoHook"
    override fun init(target: HookTarget) {
        val processName = target.processName
        val supported = listOf(AaBasicsHook, AaSignatureHook, AaDpiHook, AaBtnEventHook, AaUiHook, AaMediaPlaceholderHook, AaPropsHook)
            .filter { i -> i.isSupportProcess(processName) }
        if(supported.isEmpty()) return

        val config = HookPreferences.get()

        val hooks = supported.filter { h ->
            runCatching { h.isEnabled(config, processName) }.onFailure { e ->
                log(tagName, "${h.tagName} isEnabled failed; keeping hook enabled", e)
            }.getOrDefault(true)
        }
        if(hooks.isEmpty()) return

        val sourceDir = target.appInfo?.sourceDir
        if (sourceDir == null) {
            log(tagName, "no sourceDir for ${target.packageName}; skip")
            return
        }

        var onCreateApplication: XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(Instrumentation::class.java) {
            name == "callApplicationOnCreate"
            && parameterCount == 1
            && parameterTypes[0] == Application::class.java
        }.hookBefore {
            onCreateApplication?.unhook()
            EzXHelperInit.initAppContext()
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                if(bridge == null){
                    log(tagName,"DexKitBridge.create() failed")
                    return@hookBefore
                }
                val measureTimeMillis = measureTimeMillis {
                    hooks.forEach { h ->
                        runCatching {
                            h.loadDexClass(bridge, target)
                        }.onFailure { e ->
                            log(tagName, "${h.tagName} loadDexClass failed", e)
                        }
                    }
                }
                log(tagName,"${target.processName} load class measure ${measureTimeMillis}ms")
            }
            hooks.forEach { h ->
                runCatching {
                    h.hook(config, target)
                }.onFailure { e ->
                    log(tagName, "${h.tagName} hook failed", e)
                }
            }
        }
    }
}


