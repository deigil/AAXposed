package io.github.nitsuya.aa.display.xposed.hook

import de.robv.android.xposed.XC_MethodHook

abstract class BaseHook {
    var isInit: Boolean = false
    abstract val tagName: String
    /** 由入口类（modern 的 ModernInit / legacy 的 XposedInit）翻译好目标后调用。 */
    abstract fun init(target: HookTarget)
}
inline fun XC_MethodHook.MethodHookParam.abortMethod() {
    this.result = null
}