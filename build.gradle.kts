// ── 构建选型(2026-10-09 更正)────────────────────────────────────────────────
//
// 原先这里用的是 AGP 8.7.3 + Kotlin 2.0.21 + compileSdk 35,理由是"squemaFQH 升 AGP 9
// 只是因为它选了 compileSdk 37,而 37 对我们并非必需"。**那个判断是错的**,CI 实测报错:
//
//   Execution failed for task ':app:checkDebugAarMetadata'
//   > Dependency 'io.github.libxposed:service:102.0.0' requires libraries and applications
//     that depend on it to compile against version 37 or later of the Android APIs.
//     :app is currently compiled against android-35.
//
// 根因:compileSdk 的下限不是由**我们的代码**决定的,而是由**依赖的 AAR metadata** 强制的。
// 本地核验(读 aar 内 META-INF/com/android/build/gradle/aar-metadata.properties):
//   · io.github.libxposed:api:102.0.0      → minCompileSdk=37
//   · io.github.libxposed:service:102.0.0  → minCompileSdk=37
//   · io.github.libxposed:service:101.0.0  → minCompileSdk=36(且**没有** getRunningTargets())
// 也就是说:**没有任何 libxposed service 版本能配 compileSdk 35**。
//
// 为什么上游 FanqieHook 能用 compileSdk 35:它的 libxposed:api 是 **compileOnly**,而
// `checkDebugAarMetadata` 只检查会进 APK 的依赖(implementation/runtime)。FanqieHook 唯一的
// implementation 依赖是 DexKit,没有 minCompileSdk 要求。我们的 UI 需要 service 库(它跑在
// 模块 App 进程里,必须 implementation —— squemaFQH 的注释也强调了这点),所以躲不开。
//
// 为什么选 service 102 而不是退回 101:只有 102 有 `getRunningTargets()`(已用 javap 核验
// 102.0.0 的 XposedService 确实声明了它,且带 HookedTarget$State)。这正是让状态面板的 LIVE
// 灯真的能亮起来的那个方法 —— squemaFQH 用 101.0.0,它的 isRunningTarget 反射探测永远
// NoSuchMethodException,所以番茄/红果卡片永远只显示 SCOPE。
//
// 连带要求(来自 Kotlin 官方 AGP 9 迁移矩阵):
//   · AGP 9.0 的 Max API Level 是 36.1 → 要 compileSdk 37 必须 **AGP 9.1+**,这里取 9.2.0
//     (squemaFQH 实证过 AGP 9.2.0 + Gradle 9.5.1 + compileSdk 37 + buildTools 37.0.0 可构建)
//   · Gradle 9.1+(AGP 9 硬要求)→ 用 9.5.1
//   · JDK 17+ ✓
//   · **AGP 9 内置 Kotlin 支持**,`com.android.application` 不再需要(也不应再)apply
//     `org.jetbrains.kotlin.android` —— 否则会与 AGP 自动注册的 `kotlin` extension 撞车
//     (`IllegalArgumentException: Cannot add extension with name 'kotlin'`)
//   · `kotlinOptions` 已废弃 → 迁移到 `compilerOptions`(见 app/build.gradle.kts)
plugins {
    id("com.android.application") version "9.2.0" apply false
}
