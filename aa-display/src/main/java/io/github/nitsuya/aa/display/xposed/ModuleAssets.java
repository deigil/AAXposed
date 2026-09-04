/*
 * AADisplay - EzXHelper module-path bootstrap for the modern Xposed API.
 */

package io.github.nitsuya.aa.display.xposed;

import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.kyuubiran.ezxhelper.init.EzXHelperInit;
import com.github.kyuubiran.ezxhelper.init.InitFields;

/**
 * 补齐 EzXHelper 的 {@code modulePath} / {@code moduleRes}。
 *
 * <p>旧路径（{@link XposedInit}）靠 {@code EzXHelperInit.initZygote(startupParam)} 写入这两个字段，
 * 那是唯一的公开写入口。现代 API 下没有 {@code StartupParam}（它只有包私有构造器），
 * {@code XposedInterface} 也不提供 modulePath 访问器，所以 {@link ModernInit} 改为注入一个
 * 取路径的回调（{@code getModuleApplicationInfo().sourceDir}），由这里在**真正需要时**解析。
 *
 * <p>不补齐的后果不是“少个功能”：{@code InitFields.modulePath} 是 lateinit，
 * {@code CommonContextWrapper} 构造时调的 {@code addModuleAssetPath} 会读它并抛
 * {@code UninitializedPropertyAccessException}。该构造发生在 system_server 主 looper 上
 * （CoreManagerService 建投屏悬浮窗时），异常逃出去就是 {@code je#system_server}，整机软重启。
 *
 * <p>路径解析故意是懒的：{@code getModuleApplicationInfo()} 是一次同步 Binder 调用，而
 * {@code onModuleLoaded} 在 system_server 里早于 {@code SystemServer.startBootstrapServices}，
 * 那个时候没必要、也不该去打框架守护进程。回调取不到时还有
 * {@link #apkPathFromClassLoader()} 兜底 —— hook 侧只要能跑到这行，模块 APK 就一定在
 * 自己的 ClassLoader 里。
 *
 * <p>用 Java 写是因为这两个 setter 在 Kotlin 侧是 {@code internal}（跨编译单元不可见），
 * 但在字节码里是 public 的 {@code setModulePath$EzXHelper_release} /
 * {@code setModuleRes$EzXHelper_release}，javac 可以直接调用 —— 比反射好：R8 混淆时会连同
 * 调用点一起改名，不会悄悄失配。
 */
public final class ModuleAssets {

    private static final String TAG = "AAD_ModuleAssets";

    /** 模块 APK 路径的来源。现代路径由 {@link ModernInit} 注入。 */
    public interface PathSupplier {
        @Nullable
        String getModuleApkPath();
    }

    @Nullable
    private static volatile PathSupplier sPathSupplier;

    /** 解析失败后不再反复重试（每次失败都是一次白跑的 IPC + 日志噪音）。 */
    private static volatile boolean sResolveFailed;

    private ModuleAssets() {
    }

    /** 由入口类在分发任何 hook 之前调用。 */
    public static void installPathSupplier(@NonNull PathSupplier supplier) {
        sPathSupplier = supplier;
        sResolveFailed = false;
    }

    /**
     * 幂等地初始化 {@code modulePath}（以及尽力初始化 {@code moduleRes}）。
     * 旧框架路径上 {@code initZygote} 已经写过，这里直接短路。
     *
     * @return modulePath 是否可用
     */
    public static boolean ensureModulePath() {
        if (InitFields.INSTANCE.isModulePathInited()) {
            return true;
        }
        if (sResolveFailed) {
            return false;
        }
        return resolve();
    }

    private static synchronized boolean resolve() {
        InitFields fields = InitFields.INSTANCE;
        if (fields.isModulePathInited()) {
            return true;
        }
        PathSupplier supplier = sPathSupplier;
        String apkPath = null;
        if (supplier == null) {
            // 没有入口类注入 —— 比如某个 hook 进程只被 legacy 通道加载过，
            // 或者框架换了分发方式。这不致命，下面还有 ClassLoader 兜底。
            Log.w(TAG, "no module path supplier installed; falling back to class loader");
        } else {
            try {
                apkPath = supplier.getModuleApkPath();
            } catch (Throwable t) {
                Log.e(TAG, "module path supplier failed", t);
            }
        }
        if (apkPath == null || apkPath.isEmpty()) {
            apkPath = apkPathFromClassLoader();
        }
        if (apkPath == null || apkPath.isEmpty()) {
            Log.w(TAG, "module apk path unavailable; module resources stay unusable");
            sResolveFailed = true;
            return false;
        }
        try {
            fields.setModulePath$EzXHelper_release(apkPath);
        } catch (Throwable t) {
            Log.e(TAG, "failed to set modulePath=" + apkPath, t);
            sResolveFailed = true;
            return false;
        }
        // moduleRes 目前没有直接调用方，但 EzXHelper 的其他 API 会读它，一并补上。
        // XModuleResources 属于 legacy Xposed API，targetApiVersion=101 下未被 block；
        // 真失败了也只影响资源转发，不该拖累已经成功的 modulePath。
        try {
            if (!fields.isModuleResInited()) {
                fields.setModuleRes$EzXHelper_release(XModuleResources.createInstance(apkPath, null));
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed to create module resources for " + apkPath, t);
        }
        Log.i(TAG, "modulePath initialized: " + apkPath);
        return true;
    }

    /**
     * 兜底：从加载本类的 {@code PathClassLoader} 里抠出 APK 路径。
     *
     * <p>模块代码总是从模块 APK 加载的，所以 {@code BaseDexClassLoader.toString()}
     * 里必然带着它的 dex 路径。格式（AOSP，{@code BaseDexClassLoader#toString}）：
     * {@code dalvik.system.PathClassLoader[DexPathList[[zip file "/data/app/.../base.apk"], ...]]}。
     * 这是文本解析，不碰隐藏 API、不需要反射白名单，比 {@code DexPathList.dexElements}
     * 那条路稳。取第一个 {@code .apk} 就够 —— 模块只有一个 base.apk 带资源。
     */
    @Nullable
    private static String apkPathFromClassLoader() {
        ClassLoader loader = ModuleAssets.class.getClassLoader();
        if (loader == null) {
            return null;
        }
        String description = loader.toString();
        int end = description.indexOf(".apk\"");
        if (end < 0) {
            Log.w(TAG, "no apk path in class loader: " + description);
            return null;
        }
        int start = description.lastIndexOf('"', end);
        if (start < 0) {
            return null;
        }
        String path = description.substring(start + 1, end + 4);
        Log.i(TAG, "module apk path from class loader: " + path);
        return path;
    }

    /**
     * {@code EzXHelperInit.addModuleAssetPath} 的安全包装：解析不出路径或反射失败时返回
     * false，绝不向调用方抛异常。调用点可能位于 system_server 主线程，抛出等于让系统重启。
     */
    public static boolean addModuleAssetPath(@NonNull Resources resources) {
        if (!ensureModulePath()) {
            Log.w(TAG, "modulePath unavailable; module assets not added to " + resources);
            return false;
        }
        try {
            EzXHelperInit.INSTANCE.addModuleAssetPath(resources);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "addModuleAssetPath failed", t);
            return false;
        }
    }
}
