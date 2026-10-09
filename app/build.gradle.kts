plugins {
    // ★ AGP 9 内置 Kotlin 支持:`com.android.application` 自带 Kotlin 编译能力。
    // **不要再 apply `org.jetbrains.kotlin.android`** —— AGP 9 会自动注册名为 `kotlin` 的
    // extension,重复 apply 会撞车:
    //   IllegalArgumentException: Cannot add extension with name 'kotlin',
    //   as there is an extension already registered with that name
    // (这是 AGP 9 迁移最常见的坑;AGP 9 对 KGP 2.2.10 有运行时依赖,会自动拉进来。)
    id("com.android.application")
}

// 版本号必须与 src/main/resources/META-INF/xposed/module.prop 保持同步。
// scripts/verify-module-apk.sh 的第 6 项会在 CI 里强制校验这个一致性(以及 update.json)。
android {
    namespace = "dev.superfqkill"

    // ── compileSdk 37 + compileSdkMinor 0 ─────────────────────────────────────
    // 37 是 libxposed:service 102.0.0 的 aar-metadata 强制下限(理由见根 build.gradle.kts)。
    //
    // ★ **必须同时写 compileSdkMinor = 0**。Google 从未发布过 `platforms;android-37` 这个包,
    //   SDK 仓库里只有 `android-37.0` / `android-37.1` / `android-37.2`(次版本格式);
    //   纯整数的 API level 只到 36。AGP 通过独立的 `compileSdkMinor` 属性去解析次版本包,
    //   只写 `compileSdk = 37` 会报 `Failed to find Platform SDK platforms;android-37`
    //   —— 这正是 squemaFQH README 陷阱 #4 描述的现象,而它把原因归给了"AGP 8.x 太老",
    //   其实真正的原因是包名形态。已用 javap 核验 AGP 9.2.0 的 CommonExtension 确实声明了
    //   getCompileSdkMinor()/setCompileSdkMinor(Integer)。
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.superfqkill"
        minSdk = 26
        // AGP 9 起 targetSdk 未设置时默认等于 compileSdk(旧行为是默认等于 minSdk)。
        // 这里显式写出,不依赖默认值的变化。
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // release 只打 arm64-v8a。依据(来自 FanqieHook app/build.gradle.kts:19-25 的实测):
        // 番茄 73532/73732 的 117/116 个 .so 全在 arm64-v8a 下,红果同基线;x86/x86_64 只对
        // 模拟器有意义,armeabi-v7a 永远用不上(宿主 arm64-only,32 位机装不上宿主)。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // ★ 必须声明在 buildTypes **之前**。
    // Kotlin DSL 的 android{} 块是自上而下立即执行的:buildTypes.release 里那句
    // signingConfigs.getByName("ci") 在它自己那一步就会被求值,若此时 "ci" 还没被 create,
    // 会抛 UnknownDomainObjectException: SigningConfig with name 'ci' not found。
    // 上游 FanqieHook 就是这个顺序;之前把两块调换了,只有配了 KEYSTORE_PATH 才会触发,
    // 所以本地无 secret 时看不出来。
    signingConfigs {
        // CI 固定签名:由 workflow 从 secrets 注入。稳定签名是必需的 —— Xposed 模块靠
        // adb install -r 覆盖升级,签名不一致会直接失败。(squemaFQH 完全没有 signingConfigs,
        // release 产出未签名 APK。)未配 KEYSTORE_PATH 时本地开发回退 debug 签名。
        create("ci") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            // debug/androidTest 必须额外带 x86_64:CI 的 resolver-test job 跑在 x86_64 模拟器上
            // (GitHub runner 是 x86_64,arm64 模拟器慢到不可用),缺它则 libdexkit.so 加载失败。
            // 同时保留 arm64-v8a 以便真机手工验证。见 CI-PLAN.md §4 Tier 3。
            ndk {
                abiFilters += setOf("arm64-v8a", "x86_64")
            }
        }

        release {
            // R8 收缩:本模块 APK 的体积几乎全在 Kotlin 标准库与 DexKit 里(自身代码仅十几 KB)。
            // 开启后必须配合 proguard-rules.pro —— 尤其是 java_init.list 按类名字符串引用的入口类,
            // 以及 DexKit 会被 JNI 按签名回调的包。
            // 警告:squemaFQH 的 proguard-rules.pro 是 100% 注释掉的空模板,它"安全"仅仅因为
            // minifyEnabled false。开着 minify 而没有 keep 规则 = 模块在加载期静默坏掉。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // ── Kotlin 的 jvmTarget ───────────────────────────────────────────────────
    // 原来的 `kotlinOptions { jvmTarget = "17" }` 已删除:AGP 9 起 `kotlinOptions` 被废弃,
    // 内置 Kotlin 改用 `compilerOptions`(AGP 9 迁移矩阵明确列了这一条)。
    //
    // 这里**刻意不写**显式的 kotlin{} 块,而是让 AGP 的内置 Kotlin 跟随上面的 compileOptions。
    // 原因:`kotlin` extension 是 AGP 内部注册的,不在本脚本的 plugins{} 块里,
    // Kotlin DSL 的 type-safe accessor **可能不会为它生成** —— 那样脚本自身就编译不过,
    // 而且这个行为我无法在没有 Android SDK 的机器上验证。少写一处赌注。
    //
    // 兜底:gradle.properties 里设了 `kotlin.jvm.target.validation.mode=warning`,
    // 所以即便 Kotlin 与 Java 的 jvmTarget 不一致也只是警告而非构建失败
    // (在 Android 上 jvmTarget 只影响交给 D8 的字节码版本,D8 两者都能处理)。
    //
    // 若 CI 仍报 jvmTarget 相关**错误**,把下面这段取消注释(它需要 KGP 的类在
    // buildscript classpath 上,AGP 9 对 KGP 2.2.10 有运行时依赖,通常可满足):
    //
    // kotlin {
    //     compilerOptions {
    //         jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    //     }
    // }

    packaging {
        resources {
            // 必须保留:两个来源的 META-INF/xposed/* 会冲突,不 merges 就打包失败。
            merges += "META-INF/xposed/*"
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }

    // 不设 buildFeatures.aidl / buildConfig:
    //  · AIDL 状态通道在 squemaFQH 里是死代码(SystemBootstrap.start() 零调用者 +
    //    scope.list 缺 system 条目),且其导出的 ServiceProvider 无 android:permission 保护。
    //    我们用 XposedService.getScope()/getRunningTargets() 替代,见 PLAN.md §4.3/§4.4。
    //  · squemaFQH 的 buildConfigField SERVICE_VERSION 生成了但全仓零引用。

    testOptions {
        // instrumented 测试(ClassResolver 的 fixture dex 回归)见 CI-PLAN.md §4 Tier 3
        animationsDisabled = true
    }
}

dependencies {
    // libxposed API 由宿主框架在运行时提供 —— 必须 compileOnly,打进 APK 会与框架冲突
    // (两个上游的头号陷阱)。
    compileOnly("io.github.libxposed:api:102.0.0")

    // libxposed service:在【模块 App 进程】里运行(不是被 hook 的进程),所以必须 implementation。
    // 用 102.0.0 而不是 squemaFQH 的 101.0.0 —— getRunningTargets() 是 API 102+ 才有的,
    // 101 上 ScopeStatus.isRunningTarget 的反射探测永远 NoSuchMethodException → 永远 false,
    // 这正是 squemaFQH 的 LIVE 状态灯点不亮的原因之一(PLAN.md §4.3)。
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.annotation:annotation:1.7.1")

    // DexKit:必须 implementation,否则 APK 里没有 libdexkit.so →
    // UnsatisfiedLinkError: Could not load libdexkit.so。
    // 坐标是小写 org.luckypray:dexkit(不是 io.github.lsposed:dexkit,那是旧 1.x 线)。
    // 定 2.3.0 的理由见 PLAN.md §2 0.2 —— 这是修 bug 不是版本统一:
    //   · 2.0.4(FanqieHook 现用)缺 2.0.6 的 16k pagesize 支持
    //   · 2.0.4 带着 2.2.0 才修的 findMethod 缺陷:会把"没有实际 dex 定义的方法"
    //     (即调用目标)误当命中返回 —— 而 ClassResolver.findClassImplementingInterface
    //     用的正是 interfaces{} + methods{name} 这个模式
    //   · 2.3.0 的 accessFlags 匹配可替掉"查完再手工跳过 abstract"的做法
    implementation("org.luckypray:dexkit:2.3.0")

    // instrumented 测试(CI 的 resolver-test job)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
