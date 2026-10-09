package dev.superfqkill.core

import android.os.Build
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.query.matchers.FieldMatcher
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

/**
 * Reflective class / method lookup against the host app's class loader.
 *
 * The resolver accepts **class name strings** as primary inputs. Every miss is logged at WARN
 * (class) or WARN (method) so missing targets never silently turn into runtime crashes.
 *
 * Supported parameter type specifiers (case-sensitive):
 *   - Primitives: `boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, `double`
 *   - java.lang wrappers: `Boolean`, `Byte`, `Char`, `Short`, `Integer`, `Long`, `Float`, `Double`
 *   - `void`
 *   - Any `java.lang.*` or `android.*` type
 *   - Any application class (resolved through [classLoader])
 *   - Array notation: `<type>[]` (single-dimension only; multi-dim must be passed via [Class])
 *
 * For arrays, generic collections, or generic types, resolve the leaf class first and pass
 * the resulting `Class<*>` via overloads below.
 *
 * DexKit fallback:
 *   - [findClassImplementingInterface] uses DexKit 2.x at runtime to find targets whose class
 *     names are obfuscated and may change between Fanqie releases. The bridge is lazily created
 *     from [apkPath]（`DexKitBridge.create(String)` 这个重载，**不是** `create(ClassLoader,
 *     Boolean)`）and reused; 查询结果再通过 [classLoader] 落成 [Class] / [Method]。
 *     用完必须 [close]，见该方法的说明。
 *   - DexKit failure (e.g. on unsupported ART versions) is logged at WARN and degrades to an
 *     empty result; callers should treat empty results the same as a hard miss.
 *
 * 可测性（CI-PLAN.md §4 Tier 3 的前提）：日志走 [ResolverLog] 接口而不是 [ModuleLog]，
 * 模块 APK 路径是构造参数 [moduleApkPath] 而不是内部去取 `getModuleApplicationInfo()`，
 * 所以本类可以在**没有 Xposed 框架**的 instrumented 测试里直接实例化，把 [apkPath] 指向
 * fixture APK。
 */
class ClassResolver(
    private val classLoader: ClassLoader,
    private val log: ResolverLog,
    /**
     * APK source directory for the host app — needed by DexKit 2.x because it loads the DEX
     * straight off disk rather than from the class loader. Passed in by [dev.superfqkill.ModuleEntry]
     * from `PackageReadyParam.applicationInfo.sourceDir`. May be null in early lifecycle phases; in
     * that case DexKit-backed lookups degrade to reflection-only.
     */
    private val apkPath: String? = null,
    /**
     * The MODULE's own APK path — **注入进来的，不在内部去取**。DexKit 2.3.0 的 Java 侧同样
     * 不会自己调 `System.loadLibrary`（已核验 2.3.0 源码：`dexkit/src/main` 与 `dexkit-android`
     * 里没有任何 loadLibrary 调用，只有 DexKit 自己的 JVM 单测通过 test-only 的 `LibLoader`
     * 加载；官方 quick-start 明确要求使用方自己 `System.loadLibrary("dexkit")`），所以在
     * LSPosed 注入的宿主进程里没人加载 `libdexkit.so`，每个 native 调用都会死在
     * `UnsatisfiedLinkError: No implementation found for ... nativeInitDexKit`。
     *
     * 生产路径由 [dev.superfqkill.ModuleEntry] 传 `getModuleApplicationInfo()?.sourceDir`；
     * 之所以做成构造参数而不是在类内部去取，是因为取它需要一个真实的 `XposedModule` ——
     * 那样 instrumented 测试就没法把 DexKit 指向 fixture APK 了。默认 null 保持生产行为不变：
     * 拿不到就走 [ensureDexKitNativeLoaded] 里的 WARN + 关闭 DexKit 查找这条路。
     */
    private val moduleApkPath: String? = null,
    /**
     * Host app's data dir (writable — module code runs as the host UID). Used to extract
     * `libdexkit.so` when `System.loadLibrary` cannot resolve the module APK's lib dir.
     */
    private val hostDataDir: String? = null
) : Closeable {

    fun findClass(name: String): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (cnfe: ClassNotFoundException) {
            log.warn("class not found: $name")
            null
        } catch (t: Throwable) {
            log.warn("class lookup failed: $name (${t.javaClass.simpleName}: ${t.message})")
            null
        }
    }

    /**
     * Look up a method by name + parameter types given as class name strings.
     * Use [findMethod] overload that takes [Class] for arrays or generic types.
     */
    fun findMethod(
        className: String,
        methodName: String,
        vararg parameterTypeNames: String
    ): Method? {
        val owner = findClass(className) ?: return null
        val params = parameterTypeNames.map { name ->
            resolveType(name) ?: run {
                log.warn("method resolution aborted: param type '$name' not found")
                return null
            }
        }.toTypedArray()
        return findMethodOn(owner, methodName, params, className)
    }

    /**
     * Look up a method by name + parameter types given as resolved [Class] instances.
     * Use this overload when the parameter types are arrays, generics, or otherwise not expressible
     * as a simple class-name string. For ordinary class names prefer [findMethod] with `String` args.
     */
    fun findMethod(
        className: String,
        methodName: String,
        parameterTypes: Array<out Class<*>>
    ): Method? {
        val owner = findClass(className) ?: return null
        return findMethodOn(owner, methodName, parameterTypes, className)
    }

    /**
     * 目标方法找不到时，把该类上**形状相同**的候选方法名一起写进日志。
     *
     * 为什么值得单独做：少数目标（`ExperimentUtil#p()/#q0()`、`BrandTopViewDisplayStrategy#c()`）
     * 是混淆名，宿主每次混淆都有机会改名。只看「method not found」的话，日志里什么线索都没有，
     * 只能把整个目标集重新做一次 DEX 静态审计——这正是「每次更新都要重新适配」的成本来源。
     * 把同一形状（参数个数相同）的方法名列出来之后，绝大多数改名都能直接读日志对上。
     *
     * 只做提示，绝不自动替换：这些混淆名在同一类上往往有多个同形状的方法（例如
     * `ExperimentUtil` 上 `P/Q/p/q/q0` 都是 `public static ()Z`），自动挑一个等于猜，猜错就是
     * 静默改错开关。
     */
    private fun describeCandidates(
        owner: Class<*>,
        methodName: String,
        params: Array<out Class<*>>
    ): String {
        val candidates = runCatching {
            owner.declaredMethods
                .filter { it.parameterCount == params.size && !it.isSynthetic }
                .map { it.name }
                .distinct()
                .sortedWith(
                    // 按与目标名的公共前缀长度降序：真正常见的情况是「名字大体没改」
                    // （`disableAdGift` → `disableAdGiftV2`），这样最像的候选排在最前面。
                    // 大混淆类上同形状方法可能有两百多个，纯字母序会把有用线索挤出前 12 条。
                    compareByDescending<String> { commonPrefixLength(it, methodName) }
                        .thenBy { it }
                )
        }.getOrNull() ?: return ""
        if (candidates.isEmpty()) return " (class has no ${params.size}-arg methods)"

        val shown = if (candidates.size <= CANDIDATE_LIMIT) {
            candidates
        } else {
            candidates.take(CANDIDATE_LIMIT) + "…(+${candidates.size - CANDIDATE_LIMIT} more)"
        }
        return " — same-shape candidates on ${owner.simpleName}: ${shown.joinToString(", ")}"
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun findMethodOn(
        owner: Class<*>,
        methodName: String,
        params: Array<out Class<*>>,
        className: String
    ): Method? {
        return try {
            owner.getDeclaredMethod(methodName, *params).apply { isAccessible = true }
        } catch (nsm: NoSuchMethodException) {
            log.warn(
                "method not found: $className#$methodName(${params.joinToString { it.simpleName }})" +
                    describeCandidates(owner, methodName, params)
            )
            null
        } catch (t: Throwable) {
            log.warn("method lookup failed: $className#$methodName (${t.javaClass.simpleName})")
            null
        }
    }

    /**
     * Find a single method on a specific class by name + return type, ignoring parameter types.
     * Used when a hook target's parameters include obfuscated classes (e.g. `qh4.h`) that may
     * be renamed between Fanqie versions.
     */
    fun findMethodIgnoringParams(
        className: String,
        methodName: String,
        returnTypeName: String = "boolean"
    ): Method? {
        val owner = findClass(className) ?: return null
        return try {
            val hit = owner.declaredMethods.firstOrNull { m ->
                m.name == methodName && matchesReturnType(m.returnType, returnTypeName)
            }
            if (hit == null) {
                // Same diagnostic contract as [describeCandidates]: name the methods that are on
                // this class and return the expected type, so a renamed target is readable from
                // the log instead of requiring a DEX re-audit.
                val sameReturn = owner.declaredMethods
                    .filter { matchesReturnType(it.returnType, returnTypeName) && !it.isSynthetic }
                    .map { it.name }
                    .distinct()
                    .sorted()
                log.warn(
                    "method scan found no $className#$methodName returning $returnTypeName" +
                        if (sameReturn.isEmpty()) {
                            " (no method returning $returnTypeName)"
                        } else {
                            " — methods returning $returnTypeName: " +
                                sameReturn.take(CANDIDATE_LIMIT).joinToString(", ")
                        }
                )
            }
            hit?.apply { isAccessible = true }
        } catch (t: Throwable) {
            log.warn("method scan failed: $className#$methodName (${t.javaClass.simpleName})")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DexKit-backed lookups. Used for hooks whose target is obfuscated and may
    // move between Fanqie versions.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 按「这个方法读取了哪个配置字段」定位一个无参 boolean getter。
     *
     * 为什么需要——这是实测踩到的坑：`ExperimentUtil` 上的开关是**混淆名**，宿主每次发版都
     * 可能换字母。73732 上读 `ShortSeriesLandscapeInsertAdConfig#landscapeInsertAdEnable`
     * 的是 `q0()Z`；73917 上 `q0` 变成了返回 `long` 的另一个配置，同一个开关搬到了 `s0()Z`。
     * 继续硬编码名字的话，每发一版就要重新对一次字母（正是"每次更新都要重新适配"的来源之一）。
     *
     * 而它读取的**字段名是稳定的业务名**，所以按字段反查 getter 比按混淆名可靠得多。
     * 已核验（DEX 静态）：
     *   字段 `landscapeInsertAdEnable`      → 73732 `q0()Z`，73917 `s0()Z`
     *   字段 `enableMultiSeriesFlowAd`      → 73732 `p()Z` ，73917 `p()Z`
     * 判据（declaredClass + 无参 + 返回 boolean + 引用该字段）在两个版本上都只命中一个方法。
     *
     * **绝不猜**：命中多个时只接受其中唯一的一个 `static` 方法；仍然无法唯一确定就返回 null
     * 并打 WARN。宁可少拦一条，也不要挂错开关——挂错是静默改错行为，比不挂危险得多。
     */
    fun findNoArgBooleanGetterReadingField(
        className: String,
        fieldName: String
    ): Method? {
        log.info("field-scoped lookup called: $className#$fieldName")
        if (findClass(className) == null) {
            log.warn("field-scoped lookup aborted: $className not loadable")
            return null
        }
        val b = bridge()
        if (b == null) {
            log.warn("field-scoped lookup aborted: DexKit bridge unavailable")
            return null
        }
        return try {
            val hits = b.findMethod {
                matcher {
                    declaredClass(className)
                    paramCount(0)
                    returnType("boolean")
                    // 注意：`addUsingField(String)` 会把字符串当**完整字段描述符**解析
                    // （`Lcom/foo/Bar;->name:Z`），传裸字段名会抛
                    // IllegalAccessError: not field descriptor: xxx。
                    // 用只按名字匹配的 FieldMatcher 才对——同一字段名可能出现在多个类上
                    // （本 APK 里 `landscapeInsertAdEnable` 就有两个 owner 类），
                    // 按名字匹配可避开选错 owner。
                    addUsingField(FieldMatcher().apply { name(fieldName) }, UsingType.Read)
                }
            }
            log.info("field-scoped lookup raw hit count for $className#$fieldName: ${hits.size}")
            // 查询里已经写了 `returnType("boolean")`，这里再按反射结果过一遍是**故意的双保险**：
            // DexKit 报的类型来自 dex 元数据，而真正要挂上去的是 `Method`，两边对不上时
            // 宁可少命中也不要挂错。（与 accessFlags 无关，2.3.0 的新匹配器替代不了这一层。）
            val resolved = hits.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.filter { it.returnType == java.lang.Boolean.TYPE }

            when {
                resolved.size == 1 -> {
                    resolved.first().apply { isAccessible = true }.also {
                        log.info(
                            "field-scoped lookup: $className#$fieldName -> " +
                                "${it.declaringClass.name}#${it.name}"
                        )
                    }
                }
                resolved.isEmpty() -> {
                    log.warn(
                        "field-scoped lookup found no no-arg boolean getter reading " +
                            "$className#$fieldName"
                    )
                    null
                }
                else -> {
                    // TODO(DexKit 2.3.0): 这个 static 后过滤可以用新的 accessFlags 匹配表达
                    // （`accessFlags(DexAccessFlags.STATIC, MatchType.Contains)`，2.3.0 新增），
                    // 但那**不是**一次干净的替换：下面「拒绝猜测」的契约要求先看到**全部**候选
                    // 才能判断是否唯一，把 static 塞进查询会让非 static 的候选直接消失，
                    // 于是「多命中 → WARN + null」退化成「单命中 → 直接挂上去」，
                    // 正好丢掉这条注释上方写着的那个保护。所以保留后过滤。
                    val statics = resolved.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    if (statics.size == 1) {
                        statics.first().apply { isAccessible = true }.also {
                            log.info(
                                "field-scoped lookup: $className#$fieldName -> " +
                                    "${it.declaringClass.name}#${it.name} (unique static of " +
                                    "${resolved.size} candidates)"
                            )
                        }
                    } else {
                        log.warn(
                            "field-scoped lookup ambiguous for $className#$fieldName: " +
                                "${resolved.map { it.name }.sorted()} — refusing to guess"
                        )
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            log.warn(
                "field-scoped lookup failed for $className#$fieldName " +
                    "(${t.javaClass.simpleName}: ${t.message})"
            )
            null
        }
    }

    /**
     * 按【方法体内引用的字符串】定位方法 —— 移植自 squemaFQH 的 `findFirstMethodByUsingString`
     * (`HookInit.java:493-505`),但补上了它缺的三样东西。
     *
     * 用于 PurifyPack 的 ④⑤⑥:那些 hook 的锚点是宿主的日志/格式串
     * (`"doSyncInitUserInfo:%s"`、`"followUserNum = %d, fansNum = %d, …"`、`"获取推荐用户数据成功"`),
     * 这类字符串在混淆后依然保留,比混淆掉的方法名稳定得多。
     *
     * 相对上游的三点改进:
     *  1. **拒绝猜测**。上游直接返回结果列表的第一个元素,不做唯一性断言。DexKit 按 descriptor
     *     排序,所以"第一个"= 字典序最小 —— 确定但任意。若两个方法都引用同一字符串,上游会
     *     静默 hook 错的那个。这里 0 命中 → null+WARN,>1 命中 → null+WARN 并列出全部候选。
     *     理由与 [findNoArgBooleanGetterReadingField] 相同:hook 错方法会静默翻错开关,
     *     比不 hook 更糟。
     *  2. **可选 `className` 收窄**。上游的三个 DexKit 查询都没有 `searchClass()`,每次都是
     *     无界全 dex 扫描,且同步跑在 `onPackageReady` 回调线程上(即 App 启动路径)。
     *     能给出类名时务必给出。
     *  3. **明确 Contains 语义**。DexKit 的 `addUsingString(String)` 是 `@JvmOverloads`
     *     `matchType = StringMatchType.Contains, ignoreCase = false` —— 是**子串**匹配,
     *     不是精确匹配。上游 README 的"引用 `<string>`"措辞会让人误以为是精确引用。
     *     Contains 更抗版本漂移,但也更容易多命中,这正好由第 1 点兜住。
     */
    fun findMethodUsingString(usingString: String, className: String? = null): Method? {
        val label = if (className != null) "$className 引用 \"$usingString\"" else "引用 \"$usingString\""
        log.info("using-string lookup called: $label")
        val b = bridge()
        if (b == null) {
            log.warn("using-string lookup aborted: DexKit bridge unavailable")
            return null
        }
        return try {
            val hits = b.findMethod {
                matcher {
                    if (className != null) declaredClass(className)
                    addUsingString(usingString)
                }
            }
            log.info("using-string lookup raw hit count for $label: ${hits.size}")
            val resolved = hits.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.filter { !java.lang.reflect.Modifier.isAbstract(it.modifiers) }

            when {
                resolved.size == 1 ->
                    resolved.first().apply { isAccessible = true }.also {
                        log.info("using-string lookup: $label -> ${it.declaringClass.name}#${it.name}")
                    }
                resolved.isEmpty() -> {
                    log.warn("using-string lookup found nothing for $label")
                    null
                }
                else -> {
                    log.warn(
                        "using-string lookup ambiguous for $label: " +
                            resolved.map { "${it.declaringClass.name}#${it.name}" }.sorted() +
                            " — refusing to guess (pass className= to narrow)"
                    )
                    null
                }
            }
        } catch (t: Throwable) {
            log.warn(
                "using-string lookup failed for $label (${t.javaClass.simpleName}: ${t.message})"
            )
            null
        }
    }

    /**
     * 找出所有【持有某类型字段】的类 —— 移植自 squemaFQH 的 `findClassesWithFieldType`
     * (`HookInit.java:508-527`),用于 PurifyPack 的 ⑤b(评论用户名)。
     *
     * ⚠️ **这个查找天生返回一个集合,爆炸半径由调用方负责收窄。** 上游拿到集合后,对每个类
     * hook 其"参数含自身类型"的所有非抽象方法(一个 `copyFrom`/merge 模式),再对每个参数盲写
     * `userName` —— 那是热路径上的宽拦截,而它的 README 完全没警告这一点(PLAN.md §5.3)。
     * 调用方应当先用 [HookManager] 的命中审计在实机上数清命中多少个类、多少个方法、
     * 每秒多少次调用,再决定是否收窄。
     *
     * 上游还有个静默失败点:`cd.getInstance(cl)` 的异常被 `catch (Throwable ignored)` 吞掉,
     * 一个类都解析不出来时返回空列表且**无任何日志**。这里改成记 WARN。
     */
    fun findClassesHoldingFieldType(fieldType: Class<*>): List<Class<*>> {
        log.info("field-holder lookup called: 持有 ${fieldType.name} 字段的类")
        val b = bridge()
        if (b == null) {
            log.warn("field-holder lookup aborted: DexKit bridge unavailable")
            return emptyList()
        }
        return try {
            val hits = b.findClass {
                matcher {
                    fields {
                        addForType(fieldType)
                    }
                }
            }
            log.info("field-holder lookup raw hit count: ${hits.size}")
            val out = mutableListOf<Class<*>>()
            hits.forEach { data ->
                runCatching { out += data.getInstance(classLoader) }
                    .onFailure {
                        // `ClassData` 上没有 `className`（那是 `MethodData` 的属性）：
                        // ClassData 暴露的是 name / simpleName / descriptor（已在 DexKit 2.3.0
                        // 的发布产物上核验）。
                        log.warn("field-holder lookup: 无法实例化 ${data.name}: ${it.message}")
                    }
            }
            if (out.isEmpty()) {
                log.warn("field-holder lookup: ${hits.size} 个 dex 命中但一个都没能实例化")
            }
            out
        } catch (t: Throwable) {
            log.warn(
                "field-holder lookup failed for ${fieldType.name} " +
                    "(${t.javaClass.simpleName}: ${t.message})"
            )
            emptyList()
        }
    }

    /**
     * 全 dex 按【方法名】定位,不知道类名时用。
     *
     * 移植自 squemaFQH 的 `findFirstNonAbstractMethodByName`(`HookInit.java:479-490`)——
     * 用于 PurifyPack 的 ③(`canThisPositionShow`)。这类方法名是宿主未混淆的 API 面,
     * 能扛过 R8,但我们不知道它挂在哪个类上,所以只能全 dex 扫。
     *
     * 相对上游的两点改进:
     *  1. **拒绝猜测**:上游取结果列表的第一个(DexKit 按 descriptor 排序,所以"第一个"=
     *     字典序最小,确定但任意)。这里 >1 命中就 WARN + null 并列出候选。
     *  2. **跳过 abstract 之外,还要求非合成/非桥接方法**,减少无意义的候选噪音。
     *
     * ⚠️ 这是**无界全 dex 扫描**,同步跑在 `onPackageReady`(即 App 启动路径)上。
     * 能用 [findMethod] 给出类名时就不要用这个。
     */
    fun findMethodByNameAnywhere(methodName: String): Method? {
        log.info("by-name lookup called: #$methodName (full-dex scan)")
        val b = bridge()
        if (b == null) {
            log.warn("by-name lookup aborted: DexKit bridge unavailable")
            return null
        }
        return try {
            val hits = b.findMethod {
                matcher {
                    name = methodName
                }
            }
            log.info("by-name lookup raw hit count for #$methodName: ${hits.size}")
            val resolved = hits.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.filter {
                val m = it.modifiers
                !java.lang.reflect.Modifier.isAbstract(m) && !it.isSynthetic && !it.isBridge
            }

            when {
                resolved.size == 1 ->
                    resolved.first().apply { isAccessible = true }.also {
                        log.info("by-name lookup: #$methodName -> ${it.declaringClass.name}#${it.name}")
                    }
                resolved.isEmpty() -> {
                    log.warn("by-name lookup found no concrete #$methodName")
                    null
                }
                else -> {
                    log.warn(
                        "by-name lookup ambiguous for #$methodName: " +
                            resolved.map { "${it.declaringClass.name}#${it.name}" }.sorted() +
                            " — refusing to guess"
                    )
                    null
                }
            }
        } catch (t: Throwable) {
            log.warn(
                "by-name lookup failed for #$methodName (${t.javaClass.simpleName}: ${t.message})"
            )
            null
        }
    }

    private var dexKitBridge: DexKitBridge? = null
    private var nativeLibLoaded = false

    /** [close] 之后不再重建桥：见 [close] 的说明。 */
    private var closed = false

    private fun bridge(): DexKitBridge? {
        if (closed) {
            log.warn("DexKit unavailable: resolver already closed")
            return null
        }
        if (dexKitBridge == null) {
            val path = apkPath
            if (path == null) {
                log.warn("DexKit unavailable: no apkPath supplied to ClassResolver")
                return null
            }
            if (!ensureDexKitNativeLoaded()) {
                log.warn("DexKit unavailable: native lib could not be loaded; DexKit lookups disabled")
                return null
            }
            dexKitBridge = try {
                // String 重载：直接从磁盘解析宿主 APK 的 dex。
                // 不用 `create(ClassLoader, Boolean)` —— 那个重载走内存 dex，
                // 需要宿主的 loader 已经把 dex 映射进来，且拿不到未加载的 dex 文件。
                DexKitBridge.create(path)
            } catch (t: Throwable) {
                log.warn("DexKit init failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
        return dexKitBridge
    }

    /**
     * 释放 DexKit 桥持有的 native 资源。
     *
     * 上游**从来不 close**（FanqieHook 的 ClassResolver 懒建桥但没有关闭路径），而 DexKit 的
     * `DexKitBridge` 是 `Closeable`，官方 demo 一律写成 `DexKitBridge.create(apkPath).use { … }`：
     * 不 close 就得等 GC 跑 `finalize()` 才释放，番茄是多 dex 应用，native 内存要挂很久。
     * PLAN.md §4.2 要求 `onPackageReady` 用 `use {}` 包住整个安装流程，本类实现 [Closeable]
     * 就是为了能直接那么写。
     *
     * 顺带说明 2.3.0 新增的 `DexKitCacheBridge` 为什么**不用**：它标着
     * `@DexKitExperimentalApi`（已在 2.3.0 的发布产物上核验注解确实在），实验 API 的语义
     * 下一个版本就可能变；它解决的「跨进程/跨次复用查询缓存」也不是本模块的需求 ——
     * 一个进程只做一次安装，装完就 close。
     *
     * 语义：
     *   - 幂等（`DexKitBridge.close()` 自身在 token 为 0 时直接返回，这里再把引用置空）。
     *   - close 之后的查找**不会**重建桥，而是走 WARN + 降级（反射查找不受影响）。
     *     所以调用时机是「所有 pack 都装完」之后；已经装好的 hook 不在运行期做解析，
     *     不受影响。
     *   - [nativeLibLoaded] 不清：`.so` 一旦映射进本进程就一直在，重复 `System.load` 只会抛
     *     UnsatisfiedLinkError。
     */
    override fun close() {
        closed = true
        val b = dexKitBridge ?: return
        dexKitBridge = null
        runCatching { b.close() }
            .onFailure { log.warn("DexKit bridge close failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Load `libdexkit.so` before the first [DexKitBridge.create] call.
     *
     * DexKit 2.3.0's Java side never loads its own native library (no `System.loadLibrary`
     * call exists in `dexkit/src/main` nor in the `dexkit-android` AAR module — only DexKit's
     * own JVM unit tests load it, through a test-only `LibLoader`; the official quick-start
     * tells the integrator to call `System.loadLibrary("dexkit")` itself). In a normal app the
     * host's loader setup covers it; inside an LSPosed-injected host process it does not, so
     * every DexKit native method fails with
     * `UnsatisfiedLinkError: No implementation found for ... nativeInitDexKit`.
     *
     * Strategy:
     *  1. `System.loadLibrary("dexkit")` — works when the module classloader exposes the
     *     module APK's `lib/<abi>/` entries (stored uncompressed).
     *  2. Fallback: extract the matching-ABI `.so` from the module APK into the host's
     *     `cache/superfqkill/` and `System.load()` it by absolute path.
     *
     * 注：DexKit 文档里那个「minSdkVersion < 23 时 so 会被压缩存放、`System.loadLibrary` 找不到」
     * 的坑对本模块不适用（minSdk 26），但策略 2 仍然需要 —— 注入进程里的 classloader 能不能
     * 看到模块 APK 的 `lib/` 条目取决于框架实现，不能假设。
     */
    private fun ensureDexKitNativeLoaded(): Boolean {
        // 同世代内第二个 ClassResolver 实例不必再解压/加载一次 —— 静态标记是 per-classloader 的,
        // 所以它说的就是「本世代已经加载过了」。
        if (nativeLibLoaded || nativeLoadedInThisGeneration) {
            nativeLibLoaded = true
            return true
        }

        // Strategy 1: standard lookup through this class's classloader (the module's).
        try {
            System.loadLibrary("dexkit")
            nativeLibLoaded = true
            // 策略 1 也要设世代标记:下面 `contains("already")` 分支靠它区分
            // 「同世代重入(native 确实已绑定,可安全复用)」与
            // 「跨世代路径冲突(映射被复用但本世代 native 未绑定,必须降级)」。
            // 只由策略 2 设置的话,走策略 1 的世代在那个分支里会被误判成冲突。
            nativeLoadedInThisGeneration = true
            log.info("DexKit native lib loaded via System.loadLibrary")
            return true
        } catch (first: Throwable) {
            log.debug(
                "System.loadLibrary(dexkit) unavailable (${first.javaClass.simpleName}); " +
                    "falling back to extraction from module APK"
            )
        }

        // Strategy 2: extract from the module APK into a host-writable dir.
        val moduleApk = moduleApkPath ?: run {
            log.warn("DexKit native lib: moduleApkPath unavailable; DexKit lookups disabled")
            return false
        }
        val outDir = hostDataDir?.let { File(it, "cache/superfqkill") }?.apply { mkdirs() } ?: run {
            log.warn("DexKit native lib: hostDataDir unavailable; DexKit lookups disabled")
            return false
        }
        try {
            ZipFile(moduleApk).use { zf ->
                val abi = Build.SUPPORTED_ABIS.firstOrNull { zf.getEntry("lib/$it/libdexkit.so") != null }
                if (abi == null) {
                    log.warn(
                        "DexKit native lib: no libdexkit.so in module APK for ABIs " +
                            Build.SUPPORTED_ABIS.toList()
                    )
                    return false
                }
                val entry = zf.getEntry("lib/$abi/libdexkit.so")!!
                // ★ 文件名带**世代令牌**。一个 native 库只能被一个 classloader 加载;热重载后
                // 新一代用的是新的模块 classloader,若沿用固定路径 `libdexkit-<abi>.so`,
                // ART 会抛 "already opened by ClassLoader …",而**新世代的 JNI native 方法
                // 并没有被绑定** —— 于是 DexKitBridge.create 立刻死在
                // `UnsatisfiedLinkError: No implementation found for … nativeInitDexKit`,
                // 所有 DexKit 查找降级。后果是 AdPack 丢掉 position-filter 与两条全屏闸门,
                // 而 **PurifyPack 的 ③④⑤⑤b⑥ 全部失效**(它们没有反射兜底)。
                // 用每世代唯一的路径,让新一代映射自己的一份(395 KB,代价可忽略)。
                val outFile = File(outDir, "libdexkit-$abi-$GENERATION_TOKEN.so")
                // Re-extract when missing or stale (a module update may ship a different .so).
                if (outFile.length() != entry.size) {
                    zf.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                // 尽力清理往世代留下的 .so。Linux/Android 上 unlink 一个已 mmap 的文件是安全的
                // (映射继续有效直到卸载),所以这不会伤到仍在运行的旧世代。失败不致命。
                runCatching {
                    outDir.listFiles()
                        ?.filter { it.name.startsWith("libdexkit-$abi-") && it.name != outFile.name }
                        ?.forEach { stale ->
                            if (stale.delete()) log.debug("清理往世代 native 库: ${stale.name}")
                        }
                }.onFailure { log.debug("清理往世代 native 库失败(可忽略): ${it.message}") }

                System.load(outFile.absolutePath)
                nativeLibLoaded = true
                // 同时记下**本世代**已加载。companion object 的静态是 per-classloader 的,
                // 而每个热重载世代有自己独立的模块 classloader —— 所以这个标记天然就是
                // 「本世代是否已加载」,正好用来区分下面那个 "already" 到底是同代重入还是跨代冲突。
                nativeLoadedInThisGeneration = true
                log.info(
                    "DexKit native lib loaded from ${outFile.absolutePath} " +
                        "(abi=$abi, generation=$GENERATION_TOKEN)"
                )
                return true
            }
        } catch (ule: UnsatisfiedLinkError) {
            // 走到这里有两种情况,必须区分 —— 上游把两者都当成功,那是错的:
            //  · **同世代重入**(同一 classloader 对同一路径再 load 一次):native 确实已绑定,
            //    返回 true 是对的。
            //  · **跨世代路径冲突**(另一个 classloader 占了同一路径):映射被复用,但**本世代的
            //    native 方法没有绑定**,返回 true 会让调用方以为 DexKit 可用,然后在
            //    DexKitBridge.create 处炸掉。必须返回 false,让上层老实降级到反射查找。
            if (ule.message?.contains("already", ignoreCase = true) == true) {
                if (nativeLoadedInThisGeneration) {
                    nativeLibLoaded = true
                    log.info("DexKit native lib already loaded in this generation; reusing")
                    return true
                }
                log.warn(
                    "DexKit native lib 路径被另一个 classloader 占用(${ule.message})—— " +
                        "本世代 native 未绑定,DexKit 查找将关闭。世代令牌=$GENERATION_TOKEN"
                )
                return false
            }
            log.warn("DexKit native lib load failed: ${ule.message}")
            return false
        } catch (t: Throwable) {
            log.warn("DexKit native lib extraction failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
    }

    /**
     * Find every loaded class that implements the given fully-qualified interface name.
     *
     * Returns a list of resolved [Class] objects (resolved through [classLoader]); failed
     * resolutions are silently dropped. Returns an empty list if DexKit is unavailable or the
     * interface itself cannot be found.
     *
     * Optional [methodName] further restricts results to classes declaring a method with that
     * name (no signature filtering — useful when parameter types are obfuscated).
     *
     * DexKit 版本相关的行为差异（2.0.4 → 2.3.0，必须记在这里）：
     *   2.2.0 修掉了 `findMethod` 的一个缺陷 —— 此前**没有实际 dex 定义**的方法（也就是只作为
     *   调用目标出现的方法）也会被当成命中返回；官方 changelog 的例子正是
     *   `findMethod { matcher { declaredClass = xxxView; name = "setVisibility" } }` 这种
     *   「按 owner 类 + 方法名」的查询，而本方法用的 `interfaces { add(...) }` +
     *   `methods { add { name = ... } }` 就是同一个模式。
     *   在 2.0.4 上这条查询是**错的**：宿主里调用了接口方法的类可能被当成实现类返回，
     *   接着 `getInstance(classLoader)` 还能成功（那个类确实存在），于是我们会去 hook 一个
     *   根本不是实现类的东西 —— 静默挂错，比挂不上更糟。2.3.0 只返回真正定义在 dex 里的方法，
     *   这条查询才成立。这也是 app/build.gradle.kts 把 DexKit 钉在 2.3.0 的理由之一。
     *
     *   另：2.3.0 新增的 `accessFlags` 匹配（配 `DexAccessFlags` 常量）在这里**刻意不用** ——
     *   加一条「排除 abstract」之类的过滤会改变命中集合，而本模块的实机基线
     *   （番茄 73932 `installed=32 skipped=1`、红果 73932 `installed=30 skipped=3`）
     *   是在不加过滤的语义下审计出来的。要加必须连基线一起重新核验。
     */
    fun findClassImplementingInterface(
        interfaceName: String,
        methodName: String? = null
    ): List<Class<*>> {
        val b = bridge() ?: return emptyList()
        return try {
            val hits = b.findClass {
                matcher {
                    interfaces {
                        add(interfaceName)
                    }
                    if (methodName != null) {
                        methods {
                            add {
                                name = methodName
                            }
                        }
                    }
                }
            }
            hits.mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
        } catch (t: Throwable) {
            log.warn("DexKit findClass implementing $interfaceName failed: ${t.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun matchesReturnType(actual: Class<*>, requested: String): Boolean = when (requested) {
        "boolean" -> actual == java.lang.Boolean.TYPE
        "void" -> actual == java.lang.Void.TYPE
        "int" -> actual == java.lang.Integer.TYPE
        "long" -> actual == java.lang.Long.TYPE
        else -> actual.name == requested
    }

    private fun resolveType(name: String): Class<*>? {
        // Primitives
        when (name) {
            "void" -> return java.lang.Void.TYPE
            "boolean" -> return java.lang.Boolean.TYPE
            "byte" -> return java.lang.Byte.TYPE
            "char" -> return java.lang.Character.TYPE
            "short" -> return java.lang.Short.TYPE
            "int" -> return java.lang.Integer.TYPE
            "long" -> return java.lang.Long.TYPE
            "float" -> return java.lang.Float.TYPE
            "double" -> return java.lang.Double.TYPE
        }
        // java.lang wrappers
        when (name) {
            "Boolean" -> return java.lang.Boolean::class.java
            "Byte" -> return java.lang.Byte::class.java
            "Char" -> return java.lang.Character::class.java
            "Short" -> return java.lang.Short::class.java
            "Integer", "Int" -> return java.lang.Integer::class.java
            "Long" -> return java.lang.Long::class.java
            "Float" -> return java.lang.Float::class.java
            "Double" -> return java.lang.Double::class.java
            "String" -> return java.lang.String::class.java
            "CharSequence" -> return java.lang.CharSequence::class.java
            "Object" -> return java.lang.Object::class.java
            "Throwable" -> return java.lang.Throwable::class.java
        }
        // Array notation: "<type>[]"
        if (name.endsWith("[]")) {
            val componentName = name.removeSuffix("[]")
            val component = resolveType(componentName) ?: return null
            return java.lang.reflect.Array.newInstance(component, 0)::class.java
        }
        // Fallback: resolve via class loader
        return findClass(name)
    }

    private companion object {
        /** 日志里最多列几个同形状候选方法（`ExperimentUtil` 这类混淆类可能有十几个）。 */
        const val CANDIDATE_LIMIT = 12

        /**
         * **本世代**的唯一令牌,用于给解压出来的 `libdexkit.so` 命名。
         *
         * 为什么是 companion(静态)而不是实例字段:静态是 **per-classloader** 的,而 libxposed
         * 的每个热重载世代都有自己独立的模块 classloader(`XposedModule` javadoc:
         * "Entry classes will be instantiated once for each loaded module generation in a process")。
         * 所以这个值在一个世代内恒定、跨世代必然不同 —— 正是我们要的语义。
         *
         * 用 classloader 的 identityHashCode 单独做令牌是不够的:identityHashCode 会碰撞,
         * 两个世代撞上同一个值就会退回「固定路径」那个 bug。再拼一个 nanoTime 把它压到不可能。
         */
        // 注意 `java.lang.Long.toHexString` 必须写全:Kotlin 里裸写 `Long` 解析到的是
        // **kotlin.Long**,它的伴生对象没有 toHexString(编译不过);而 `Integer` 不是 Kotlin
        // 内建名(内建的是 Int),所以上面那个 `Integer.toHexString` 直接命中 java.lang.Integer。
        val GENERATION_TOKEN: String =
            Integer.toHexString(System.identityHashCode(ClassResolver::class.java.classLoader)) +
                "-" + java.lang.Long.toHexString(System.nanoTime())

        /**
         * native 库是否已在**本世代**成功加载。同样是 per-classloader 静态,所以它天然表达
         * 「本世代」而不是「本进程」—— 这正是区分「同世代重入(可安全复用)」与
         * 「跨世代路径冲突(native 未绑定,必须降级)」的依据。见 [ensureDexKitNativeLoaded]。
         */
        @Volatile
        var nativeLoadedInThisGeneration: Boolean = false
    }
}
