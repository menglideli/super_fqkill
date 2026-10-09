// 构建选型说明见 CI-PLAN.md §2.1:
// 采用 FanqieHook 的已知良好组合(AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.11.1 + compileSdk 35)。
// 不用 squemaFQH 的 AGP 9.2.0 + compileSdk 37 —— 那只是因为它选了 SDK 37 才被迫升 AGP
// (其陷阱 #4:AGP 8.x 消费不了 android-37.0 新格式 platform),而 SDK 37 对本项目的两个
// 目标 App 并非必需:hook 目标是运行时反射/DexKit 解析的宿主类,不是编译期依赖。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
