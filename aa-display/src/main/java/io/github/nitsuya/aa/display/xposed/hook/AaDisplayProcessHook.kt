package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaDisplayPresentationResize

/**
 * Hooks inside AADisplay's own process: the Presentation surface that carries the
 * projected content must be widened together with the Coolwalk full-bleed layers.
 */
object AaDisplayProcessHook : BaseHook() {
    override val tagName: String = "AAD_AaDisplayProcessHook"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        var onCreateApplication: XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(Instrumentation::class.java) {
            name == "callApplicationOnCreate"
            && parameterCount == 1
            && parameterTypes[0] == Application::class.java
        }.hookBefore {
            onCreateApplication?.unhook()
            EzXHelperInit.initAppContext()
            AaDisplayPresentationResize.hookDisplayManager()
        }
    }
}
