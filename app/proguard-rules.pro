# R8 保留规则 —— LSPosed Modern API 模块
#
# 为什么这个文件必须认真写:squemaFQH 的 proguard-rules.pro 是 100% 注释掉的空模板,
# 它没出事仅仅因为 minifyEnabled false。开着 minify 而缺 keep 规则 = 模块在加载期
# **静默**坏掉(APK 构建成功、安装成功、LSPosed 里勾了作用域,但模块不加载,无任何报错)。
# scripts/verify-module-apk.sh 的第 4 项会在 CI 里对 release APK 校验入口类是否仍在 dex 中定义。

# ── 1. 模块入口 ───────────────────────────────────────────────────────────────
# META-INF/xposed/java_init.list 按【类名字符串】引用入口类。被 R8 改名或删除,
# 框架就找不到类 —— 这是全项目最致命的静默失败(squemaFQH 陷阱 #11)。
-keep class dev.superfqkill.ModuleEntry { *; }

# 双保险:即使入口类被改名,也让 R8 同步改写 java_init.list 里的类名。
# (FanqieHook 缺这一条,靠上面的 -keep 侥幸安全。)
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 模块自身其余代码整包保留:体积只有十几 KB,混淆省不出什么,
# 但任何按名字查找(日志 tag、异常栈、调试)都会变得难排查。
-keep class dev.superfqkill.** { *; }

# ── 2. libxposed API ──────────────────────────────────────────────────────────
# 该 API 由框架在运行时提供(compileOnly,不打包进 APK)。R8 看不到实现,需要抑制警告;
# 我们重写的生命周期方法由框架按签名回调,不能被改名。
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
    public boolean onHotReloading(...);
    public void onHotReloaded(...);
}

# ── 3. DexKit ─────────────────────────────────────────────────────────────────
# DexKit 的原生侧 (libdexkit.so) 通过 JNI 按【类名 + 方法签名】回调 Java 侧对象
# (桥接对象、查询回调、枚举等)。JNI 符号查找不走 R8 的映射表,因此这层必须整包保留 ——
# 否则表现为运行期 UnsatisfiedLinkError 或静默返回空结果。
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# DexKit 依赖的 JSON 序列化(Gson):按字段名反射映射,字段名不能被改。
-keep class com.google.gson.** { *; }
-keepclassmembers class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-dontwarn com.google.gson.**

# ── 4. Kotlin ─────────────────────────────────────────────────────────────────
# Kotlin 标准库是本模块最大的体积来源,可以由 R8 大幅裁剪;但元数据与
# when 表达式的合成类需要保留。
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ── 5. 通用 ───────────────────────────────────────────────────────────────────
# 保留行号,便于实机异常栈定位(对体积影响极小)。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
