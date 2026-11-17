plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine") version "4.4.0"
}

android {
    val buildTime = System.currentTimeMillis()
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nitsuya.aa.display"
        minSdk = 31
        targetSdk = 36
        versionCode = 3017
        versionName = "0.24#17.8-e3"
        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }

    packaging {
        // Do not exclude META-INF/** wholesale: libxposed module descriptors (module.prop /
        // java_init.list / scope.list) must remain in the APK under META-INF/xposed/,
        // as the framework uses these zip entries to identify modern modules.
        // List the unwanted dependency metadata individually so META-INF/xposed/** is untouched.
        resources.excludes.addAll(
            arrayOf(
                "META-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/**/*.kotlin_module",
                "META-INF/*.properties",
                "META-INF/*.txt",
                "META-INF/*.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/androidx/**",
                "META-INF/com/**",
                "META-INF/proguard/**",
                "META-INF/services/**",
                "META-INF/versions/**",
                "kotlin/**"
            )
        )
    }
    signingConfigs {
        create("release") {
            storeFile = file("../key.jks")
            storePassword = System.getenv("KEY_ANDROID")
            keyAlias = "key0"
            keyPassword = System.getenv("KEY_ANDROID")
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEY_ANDROID") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            sourceSets.getByName("main").java.srcDir(File("build/generated/ksp/release/kotlin"))
        }
        getByName("debug") {
            // Keep debug artifacts unminified to avoid AGP warnings and speed up test builds.
            isMinifyEnabled = false
            isShrinkResources = false
            if (System.getenv("KEY_ANDROID") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

    }
    
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = variant.versionName.replace("#", "-")
            output.outputFileName = "aaxposed-${versionName}.apk"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "2.0"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources.additionalParameters += mutableListOf("--allow-reserved-package-id", "--package-id", "0x64")

    namespace = "io.github.nitsuya.aa.display"
    buildToolsVersion = "35.0.0"
}

configurations.all {
    exclude("androidx.appcompat", "appcompat")
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("dev.rikka.rikkax.appcompat:appcompat:1.6.1")
    implementation("dev.rikka.rikkax.material:material-preference:2.0.0")

    //kotlinx-coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    //ViewBindingUtil
    implementation("com.github.matsudamper:ViewBindingUtil:0.1")

    compileOnly(project(":lib-stub"))
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    compileOnly(files("./libs/de.robv.android.xposed_api_82.jar"))
    // libxposed (modern Xposed API). api must be compileOnly: the framework checks that the
    // entry class's XposedModule is loaded by the framework's own ClassLoader; bundling it
    // into the APK will cause loading to be rejected.
    // Pinned at 101: 102's AAR requires compileSdk=37 (AGP 8.11.2 only supports up to 36).
    // 101 and 102 have identical XposedModule constructors, four callback signatures, and
    // IXposedService DESCRIPTOR/transaction codes; attachFramework is invoked by the framework
    // on its own loaded api class, so compiling against 101 also works on 102 frameworks.
    // targetApiVersion in module.prop is also declared as 101 so de.robv.android.xposed.*
    // is not blocked by blockLegacyApi (EzXHelper depends on it).
    compileOnly("io.github.libxposed:api:101.0.0") {
        // libxposed's pom declares kotlin-stdlib 2.2.10, which bumps the project's stdlib
        // beyond what the compiler (Kotlin 2.0.0, max 2.1.0 metadata) can read. Its class
        // files have no kotlin/ references (pure Java), so excluding it is safe.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    // Module app side: writes remote config via XposedService, replacing world-readable XSharedPreferences.
    implementation("io.github.libxposed:service:101.0.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation("com.github.kyuubiran:EzXHelper:1.0.3")
    implementation("com.github.topjohnwu.libsu:core:5.2.0")
    implementation("org.luckypray:dexkit:2.0.0-rc3")
    implementation(files("./libs/aauto.aar"))

    //lifecycle
    val lifecycleVersion = "2.9.3"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-common-java8:$lifecycleVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

}
