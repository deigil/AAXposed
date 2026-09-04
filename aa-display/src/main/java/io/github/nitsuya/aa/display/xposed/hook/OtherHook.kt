package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.res.Resources
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.log

object OtherHook : BaseHook() {
    override val tagName: String = "AAD_OtherHook"
    override fun init(target: HookTarget) {
        try {
            log(tagName, "${target.packageName}, ${target.appInfo?.uid}, ${target.isFirstApplication}, ${target.processName}")
            var onCreateApplication: XC_MethodHook.Unhook? = null
            onCreateApplication = findMethod(Instrumentation::class.java) {
                name == "callApplicationOnCreate"
                && parameterCount == 1
                && parameterTypes[0] == Application::class.java
            }.hookBefore {
                EzXHelperInit.initAppContext()
                onCreateApplication?.unhook()
                val id = InitFields.appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
                findAllMethods(Resources::class.java){
                    (
                        name == "getDimension"
                        || name == "getDimensionPixelSize"
                        || name == "getDimensionPixelOffset"
                    )
                    && parameterCount == 1
                    && parameterTypes[0] == Int::class.javaPrimitiveType
                }.hookBefore { param ->
                    if(param.args[0] != id){
                        return@hookBefore
                    }
                    param.result = if(param.method.name == "getDimension") 0F else 0
                }
            }
        } catch (e: Throwable) {
            log(tagName, "StatusBarHeight", e)
        }
    }
}