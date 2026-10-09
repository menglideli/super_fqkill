package dev.superfqkill.packs

import dev.superfqkill.core.ResolverLog
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 界面净化 pack —— 移植自 squemaFQH 的番茄/红果部分(`HookInit.java` 的 ③④⑤⑤b⑥),
 * 但**定位方式全部重写**到 [dev.superfqkill.core.ClassResolver] 的语义锚定 + 拒绝猜测纪律上。
 *
 * ## 这个 pack 做什么、不做什么
 *
 * **做**:改客户端本地渲染出来的显示内容 —— 昵称、自己主页上的社交数字、把个人页推广位
 * 的文案清空、清空推荐用户列表。
 *
 * **不做**:不触碰任何会员/权益数据,不伪造 `VipInfoModel`,不改任何服务端校验的值,
 * 不碰发奖链路。
 *
 * 这条边界是刻意划的,并且有上游背书:FanqieHook 在 `AdHooks.kt:329-330` 主动写下
 * "does NOT touch entitlement data, VipInfoModel, or any server-validated VIP flag",
 * 还把它唯一那条更激进的归因开屏绕过用编译期常量关掉了(`:733`,注明合规风险)。
 * squemaFQH 的 ② 会员解锁(`HookInit.java:225-255`,篡改 `VipInfoModel` 构造器实参
 * 伪造 `isVip="1"` / 到期时间到公元 5555 年)**不在本 pack 范围内,也没有被移植**。
 * 理由见 PLAN.md §0.1:除合规问题外,它在工程上也基本冗余 —— 番茄/红果本体是免费+广告
 * 模式,付费会员的核心卖点是免广告,而 AdPack 的零广告中央闸门已经把广告全拦了。
 *
 * ## 相对上游的改进(逐条对应 PLAN.md §5.2)
 *
 * | 上游做法 | 这里改成 |
 * |---|---|
 * | `findFirstMethodByUsingString` 取结果列表第一个,无唯一性断言 | [ClassResolver.findMethodUsingString] 的拒绝猜测:0 命中→WARN+null,>1 命中→WARN+列出候选+null |
 * | `findFirstNonAbstractMethodByName` 同上 | [ClassResolver.findMethodByNameAnywhere] 同上,并额外排除 synthetic/bridge |
 * | `catch (ReflectiveOperationException ignored)` 三处完全静默(`:312`/`:373`/`:405`) | [setFieldIfExists] / [setIntFieldFirstExisting] **一律记日志**。上游 ④ 的 `recvDiggNum` vs `recDiggNum` 疑点正是被这个静默吞掉的 |
 * | ④ 只写 `recvDiggNum` 一个字段名 | 按 [PurifyConfig.DIGG_FIELD_CANDIDATES] 依次尝试,日志里报出实际生效的那个 |
 * | ⑤b 对所有持有类的所有自类型参数方法无上限安装 | [PurifyConfig.MAX_COMMENT_HOLDER_CLASSES] / [MAX_HOOKS_PER_HOLDER_CLASS] 双上限 + 实测计数写日志 |
 * | ⑤ 对每个非 null 参数盲写 `userName` | [PurifyConfig.NICKNAME_STOP_AFTER_FIRST_HIT] 默认写成功一个就停 |
 * | 值全部硬编码在 `HookInit.java:74-85` | 全部集中到 [PurifyConfig],一个文件改完重建即可 |
 *
 * ## 已知限制
 *
 * ①(Lynx 横幅,上游 `willShowLynxBanner` → false)**没有移植**。理由:AdPack 的零广告
 * 中央闸门对宿主的 `checkAdAvailable(String,String)` 无条件返回 false,那是"这个位置现在
 * 能出广告吗"的总查询,很可能已经覆盖了 Lynx 横幅。两条 hook 在不同层,叠加不会冲突
 * (两个 false 还是 false),但在**没有实机验证冗余性**之前,加一条无法验证的重复 hook
 * 只会增加噪音。→ 实机实验:只装 AdPack 看 Lynx 横幅是否仍被拦;若仍被拦则保持现状,
 * 若未被拦再按 [ClassResolver.findMethodByNameAnywhere] 补一条(记得加返回类型校验,
 * 上游那条**没有**校验 `returnType == boolean`)。见 PLAN.md §5.1。
 */
class PurifyPack : FeaturePack {

    override val id: String = PACK_ID

    override fun install(ctx: PackContext) {
        val log = ctx.log
        val isHongguo = ctx.hostPackage == PKG_HONGGUO

        // ③④⑥ 是番茄专属:红果(短剧 App)没有个人页推广位、没有关注/粉丝/获赞这套社交
        // 数字、也没有"推荐用户"这个功能。上游用 installHooks 的 fullSet 布尔表达这件事
        // (番茄 true / 红果 false,`HookInit.java:110-116`)。
        //
        // 这里改成显式声明 known-missing。
        //
        // 说明这个声明**当前是保险而非必需**:下面三个 installer 各自都以 `if (isHongguo) return`
        // 开头,红果上根本不会去尝试这三条 hook,于是既不会有 skip 记录、也不可能出现在
        // `lost=[…]` 里(known-missing 列表是 `skipped ∩ 已声明集合`,没进 skipped 就不显示)。
        // 保留它的意义在于:哪天有人为了少一次宿主判断而删掉那些 early return,这三条会立刻
        // 变成"未解释的丢失",把真实回归的信号淹掉 —— 声明在这儿就把那个陷阱提前堵住了。
        // (HookManager 的 unexpectedSkips 机制正是为此,见 packs/FeaturePack.kt 的
        //  declareKnownMissing 说明:pack 级声明与 per-hook 的 knownMissingOnMiss 完全等价。)
        if (isHongguo) {
            ctx.declareKnownMissing(ID_PROMO, ID_SOCIAL, ID_RECOMMEND)
            log.info("purify: 红果宿主,跳过番茄专属的 ③④⑥(已声明为 known-missing)")
        }

        installPromotionPurify(ctx, isHongguo)
        installSocialStats(ctx, isHongguo)
        installNicknameHooks(ctx)
        installRecommendUsers(ctx, isHongguo)
    }

    // ── ③ 个人页推广位 ────────────────────────────────────────────────────────
    /**
     * 上游 README.md:113 把这条描述成"屏蔽个人页推广广告位" —— **它什么都没屏蔽**。
     * `canThisPositionShow` 的返回值原样透传,hook 只在
     * `pageName == "PromotionFromUserPage" && args[1] == true` 时改写**返回对象**的
     * `leftTime` / `text` / `extraInfo`。诚实的描述是:把个人页那个推广位渲染成一条
     * 剩余时间很长、文案为空的 banner。
     *
     * 它不伪造会员态、不触碰权益数据 —— 所以它属于界面净化,留在本 pack 内。
     * (PLAN.md §5.4 要求移植时确认它不依赖"当前是 VIP"这个前提;下面 [patchPromotionResult]
     *  的实机验证 TODO 就是这件事。)
     */
    private fun installPromotionPurify(ctx: PackContext, isHongguo: Boolean) {
        if (isHongguo) return
        val log = ctx.log
        val method = ctx.resolver.findMethodByNameAnywhere(METHOD_CAN_THIS_POSITION_SHOW)
        if (method == null) {
            // 必须记账:否则这个 id 既不在 installed 也不在 skipped/lost,从 summary 里彻底消失。
            ctx.hooks.noteMissing(ID_PROMO, "$METHOD_CAN_THIS_POSITION_SHOW 未找到", STRATEGY_PATCH_RESULT)
            return
        }
        ctx.hooks.install(
            id = ID_PROMO,
            method = method,
            strategy = STRATEGY_PATCH_RESULT
        ) { chain ->
            val result = chain.proceed()
            if (matchesPromotionPage(chain.args)) {
                patchPromotionResult(result, ctx.classLoader, log)
            }
            result
        }
    }

    private fun matchesPromotionPage(args: List<Any?>): Boolean {
        if (args.isEmpty()) return false
        if (PurifyConfig.PROMOTION_PAGE_NAME != args[0]) return false
        return args.size > 1 && java.lang.Boolean.TRUE == args[1]
    }

    private fun patchPromotionResult(result: Any?, classLoader: ClassLoader, log: ResolverLog) {
        if (result == null) return
        // extraInfo 需要一个无参构造器。若该类没有,这里会失败 —— 记日志,不静默。
        val extraInfo = newExtraInfo(classLoader, log)
        if (extraInfo != null) {
            setFieldIfExists(
                extraInfo, "leftTime", PurifyConfig.PROMOTION_LEFT_TIME_SEC, log, ID_PROMO
            )
            setFieldIfExists(result, "extraInfo", extraInfo, log, ID_PROMO)
        }
        setFieldIfExists(
            result, "leftTime", PurifyConfig.PROMOTION_LEFT_TIME_SEC, log, ID_PROMO
        )
        setFieldIfExists(result, "text", PurifyConfig.PROMOTION_TEXT, log, ID_PROMO)
        // TODO(实机): 截图确认改写后推广位渲染为空白条,且**不**依赖"当前是 VIP"这个前提。
        //  若它实际是在配合会员伪造,应按 PLAN.md §5.4 的判定把这条一并移除。
    }

    private fun newExtraInfo(classLoader: ClassLoader, log: ResolverLog): Any? = try {
        val clazz = Class.forName(CLASS_PROMOTION_EXTRA_INFO, false, classLoader)
        clazz.getDeclaredConstructor().newInstance()
    } catch (t: Throwable) {
        // 上游在 HookInit.java:379-381 用 catch(ReflectiveOperationException ignored) 静默返回 null。
        // 这里记日志:该类若无无参构造器,③ 的 extraInfo 部分就完全失效,不该是隐形的。
        log.warn(
            "purify ③: 无法实例化 $CLASS_PROMOTION_EXTRA_INFO " +
                "(${t.javaClass.simpleName}: ${t.message}) —— 只改 leftTime/text,不写 extraInfo"
        )
        null
    }

    // ── ④ 社交数字 ────────────────────────────────────────────────────────────
    /**
     * 伪造自己主页上显示的关注/粉丝/获赞数。**仅本地显示**:这三个值写在客户端渲染用的
     * model 对象上,不影响服务端,别人看你的主页看到的仍是真实数据。
     *
     * 定位锚点是宿主的一条日志格式串。⚠️ DexKit 的 `addUsingString` 是 **Contains** 子串匹配
     * (`StringMatchType.Contains`, 大小写敏感),不是精确匹配 —— 上游 README 的"引用 `<string>`"
     * 措辞会让人误以为是精确引用。Contains 更抗版本漂移,但更容易多命中,
     * 由 [ClassResolver.findMethodUsingString] 的拒绝猜测兜住。
     */
    private fun installSocialStats(ctx: PackContext, isHongguo: Boolean) {
        if (isHongguo) return
        val log = ctx.log

        val follow = PurifyConfig.FOLLOW_NUM
        val fans = PurifyConfig.FANS_NUM
        val digg = PurifyConfig.DIGG_NUM
        if (follow == null && fans == null && digg == null) {
            log.info("purify ④: FOLLOW_NUM/FANS_NUM/DIGG_NUM 全部为 null,按配置跳过")
            ctx.hooks.noteMissing(ID_SOCIAL, "按 PurifyConfig 配置关闭", STRATEGY_MUTATE_ARGS)
            return
        }

        val method = ctx.resolver.findMethodUsingString(USING_STRING_SOCIAL_STATS)
        if (method == null) {
            ctx.hooks.noteMissing(
                ID_SOCIAL,
                "未找到引用 \"$USING_STRING_SOCIAL_STATS\" 的方法",
                STRATEGY_MUTATE_ARGS
            )
            return
        }
        ctx.hooks.install(
            id = ID_SOCIAL,
            method = method,
            strategy = STRATEGY_MUTATE_ARGS
        ) { chain ->
            val args = chain.args
            val target = if (args.isNotEmpty()) args[0] else null
            if (target != null) {
                if (follow != null) {
                    setIntFieldIfExists(target, FIELD_FOLLOW_NUM, follow, log, ID_SOCIAL)
                }
                if (fans != null) {
                    setIntFieldIfExists(target, FIELD_FANS_NUM, fans, log, ID_SOCIAL)
                }
                if (digg != null) {
                    // 上游只写 recvDiggNum 一个名字,而它的定位串里写的是 recDiggNum
                    // (HookInit.java:391 vs :404),失败还被无日志吞掉 —— 所以"获赞"这一项
                    // 在上游很可能从来没生效过,而且没人知道。这里两个名字都试并报出结果。
                    setIntFieldFirstExisting(
                        target, PurifyConfig.DIGG_FIELD_CANDIDATES, digg, log, ID_SOCIAL
                    )
                }
            }
            chain.proceed(args.toNullableArray())
        }
    }

    // ── ⑤ / ⑤b 昵称 ───────────────────────────────────────────────────────────
    /**
     * ⑤ 个人信息同步入口 + ⑤b 评论用户名。两个挂载点,同一个改写字段(`userName`)。
     *
     * ⑤b 的爆炸半径必须控制:上游的 `findClassesWithFieldType` 返回**所有**持有
     * `CommentUserStrInfo` 字段的类,然后对每个类 hook 其"参数含自身类型"的所有非抽象方法
     * (一个 `copyFrom`/merge 模式),每个 hook 再对所有非 null 参数盲写 `userName`。
     * 那是热路径上的宽拦截,上游 README 完全没警告。这里加了双上限并把实测计数写进日志。
     */
    private fun installNicknameHooks(ctx: PackContext) {
        val log = ctx.log
        val nickname = PurifyConfig.NICKNAME
        if (nickname.isNullOrEmpty()) {
            log.info("purify ⑤/⑤b: NICKNAME 为空,按配置跳过昵称伪造")
            // 与 D5 同一失效模式,只是由配置触发:不记账的话这两个 id 既不在 installed
            // 也不在 skipped/lost,从 summary 里彻底消失,installed+skipped 的等式也跟着失衡。
            ctx.hooks.noteMissing(ID_NICKNAME_SYNC, "按 PurifyConfig 配置关闭(NICKNAME 为空)", STRATEGY_MUTATE_ARGS)
            ctx.hooks.noteMissing(ID_NICKNAME_COMMENT, "按 PurifyConfig 配置关闭(NICKNAME 为空)", STRATEGY_MUTATE_ARGS)
            return
        }

        // ⑤ 同步入口
        val syncMethod = ctx.resolver.findMethodUsingString(USING_STRING_SYNC_USER_INFO)
        if (syncMethod == null) {
            ctx.hooks.noteMissing(
                ID_NICKNAME_SYNC,
                "未找到引用 \"$USING_STRING_SYNC_USER_INFO\" 的方法",
                STRATEGY_MUTATE_ARGS
            )
        } else {
            ctx.hooks.install(
                id = ID_NICKNAME_SYNC,
                method = syncMethod,
                strategy = STRATEGY_MUTATE_ARGS
            ) { chain -> forgeNicknameOnArgs(chain, nickname, log, ID_NICKNAME_SYNC) }
        }

        // ⑤b 评论用户名
        installCommentNicknameHooks(ctx, nickname)
    }

    private fun installCommentNicknameHooks(ctx: PackContext, nickname: String) {
        val log = ctx.log
        val holderType = try {
            Class.forName(CLASS_COMMENT_USER_STR_INFO, false, ctx.classLoader)
        } catch (t: Throwable) {
            // 上游 catch(Throwable) 后只 WARN 一句就跳过整条 ⑤b。这里保留同样行为,
            // 但把异常类型带上 —— 类不存在和类加载失败是两件不同的事。
            log.warn(
                "purify ⑤b: $CLASS_COMMENT_USER_STR_INFO 不可加载 " +
                    "(${t.javaClass.simpleName}: ${t.message}),跳过评论昵称伪造"
            )
            ctx.hooks.noteMissing(ID_NICKNAME_COMMENT, "持有类不可加载: ${t.javaClass.simpleName}", STRATEGY_MUTATE_ARGS)
            return
        }

        val holders = ctx.resolver.findClassesHoldingFieldType(holderType)
        if (holders.isEmpty()) {
            ctx.hooks.noteMissing(
                ID_NICKNAME_COMMENT,
                "没有找到持有 $CLASS_COMMENT_USER_STR_INFO 字段的类",
                STRATEGY_MUTATE_ARGS
            )
            return
        }
        log.info("purify ⑤b: 找到 ${holders.size} 个持有类(上限 ${PurifyConfig.MAX_COMMENT_HOLDER_CLASSES})")

        var classesHooked = 0
        var methodsHooked = 0
        for (holder in holders) {
            if (classesHooked >= PurifyConfig.MAX_COMMENT_HOLDER_CLASSES) {
                log.warn(
                    "purify ⑤b: 已达持有类上限 ${PurifyConfig.MAX_COMMENT_HOLDER_CLASSES}," +
                        "剩余 ${holders.size - classesHooked} 个未处理 —— 若这是误伤,调高 " +
                        "PurifyConfig.MAX_COMMENT_HOLDER_CLASSES"
                )
                break
            }
            val candidates = holder.declaredMethods.filter { m ->
                !Modifier.isAbstract(m.modifiers) && !m.isSynthetic && !m.isBridge &&
                    m.parameterTypes.any { it == holder }
            }
            if (candidates.isEmpty()) continue

            if (candidates.size > PurifyConfig.MAX_HOOKS_PER_HOLDER_CLASS) {
                log.warn(
                    "purify ⑤b: ${holder.name} 有 ${candidates.size} 个自类型参数方法," +
                        "超过上限 ${PurifyConfig.MAX_HOOKS_PER_HOLDER_CLASS},只装前 " +
                        "${PurifyConfig.MAX_HOOKS_PER_HOLDER_CLASS} 个: " +
                        candidates.take(PurifyConfig.MAX_HOOKS_PER_HOLDER_CLASS).map { it.name }
                )
            }
            val picked = candidates.take(PurifyConfig.MAX_HOOKS_PER_HOLDER_CLASS)
            var installedHere = 0
            picked.forEachIndexed { index, m ->
                val hookId = "$ID_NICKNAME_COMMENT_PREFIX${holder.simpleName}#$index"
                val handle = ctx.hooks.install(
                    id = hookId,
                    method = m,
                    strategy = STRATEGY_MUTATE_ARGS
                ) { chain -> forgeNicknameOnArgs(chain, nickname, log, hookId) }
                if (handle != null) installedHere++
            }
            if (installedHere > 0) {
                classesHooked++
                methodsHooked += installedHere
            }
        }
        // 这条日志是 PLAN.md §5.3 要求的"先在实机上数清命中多少"的落点:
        // 装机后 grep "purify ⑤b installed" 就能看到真实的爆炸半径。
        log.info(
            "purify ⑤b installed: classes=$classesHooked methods=$methodsHooked " +
                "(holders found=${holders.size})"
        )
        // 找到了持有类、但没有任何"参数含自身类型"的方法 → 一条都没装上。
        // 这种情况同样必须记账,否则 ⑤b 从 summary 里静默消失(D5)。
        if (methodsHooked == 0) {
            ctx.hooks.noteMissing(
                ID_NICKNAME_COMMENT,
                "${holders.size} 个持有类上都没有自类型参数的方法",
                STRATEGY_MUTATE_ARGS
            )
        }
    }

    /**
     * 对参数列表里的对象写 `userName`。
     *
     * 上游对**每一个非 null 参数**都盲写一遍(`HookInit.java:305-311`),而匹配到的方法可能
     * 有多个参数、其中多数与用户名无关。[PurifyConfig.NICKNAME_STOP_AFTER_FIRST_HIT] 默认
     * 写成功一个就停,收窄这个不必要的宽拦截。
     */
    private fun forgeNicknameOnArgs(
        chain: Chain,
        nickname: String,
        log: ResolverLog,
        hookId: String
    ): Any? {
        val args = chain.args
        for (arg in args) {
            if (arg == null) continue
            if (setFieldIfExists(arg, FIELD_USER_NAME, nickname, log, hookId)) {
                if (PurifyConfig.NICKNAME_STOP_AFTER_FIRST_HIT) break
            }
        }
        return chain.proceed(args.toNullableArray())
    }

    // ── ⑥ 清空推荐用户 ────────────────────────────────────────────────────────
    /**
     * 把"获取推荐用户数据成功"回调的数据参数置 null,于是推荐用户列表渲染为空。
     *
     * 注意上游用 `\u83b7\u53d6...` 转义写这个中文字符串(`HookInit.java:416`)—— 那是因为
     * 它的源文件已经出现过 mojibake(`HookInit.java` 有 13 处 `????`),所以把 CJK 常量都
     * 转义了。本项目在 `gradle.properties` 里钉死了 UTF-8(daemon 的 jvmargs 里也重复了一遍),
     * 所以这里直接写字面中文。
     */
    private fun installRecommendUsers(ctx: PackContext, isHongguo: Boolean) {
        if (isHongguo) return
        val log = ctx.log
        val method = ctx.resolver.findMethodUsingString(USING_STRING_RECOMMEND_USERS)
        if (method == null) {
            ctx.hooks.noteMissing(
                ID_RECOMMEND,
                "未找到引用 \"$USING_STRING_RECOMMEND_USERS\" 的方法",
                STRATEGY_MUTATE_ARGS
            )
            return
        }
        ctx.hooks.install(
            id = ID_RECOMMEND,
            method = method,
            strategy = STRATEGY_MUTATE_ARGS
        ) { chain ->
            val args = chain.args
            // 只清第一个参数(上游 `HookInit.java:424-428` 的行为)。
            val newArgs = args.toNullableArray()
            if (newArgs.isNotEmpty()) newArgs[0] = null
            chain.proceed(newArgs)
        }
    }

    // ── 反射工具 ──────────────────────────────────────────────────────────────
    // 上游把这三个 helper 的失败全部 `catch (ReflectiveOperationException ignored)` 静默吞掉
    // (HookInit.java:312 / :373 / :405),于是"字段名写错"这件事完全隐形 —— ④ 的
    // recvDiggNum/recDiggNum 疑点就是这么被掩盖的。这里一律返回布尔并记日志。

    /** 沿父类链找字段(上游 `findField` 的行为,`HookInit.java:564-574`)。 */
    private fun findField(type: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    /** @return 是否写入成功。失败记 WARN,不静默。 */
    private fun setFieldIfExists(
        target: Any, fieldName: String, value: Any?, log: ResolverLog, hookId: String
    ): Boolean {
        val field = findField(target.javaClass, fieldName)
        if (field == null) {
            log.warn("$hookId: ${target.javaClass.name} 上没有字段 $fieldName,跳过该字段")
            return false
        }
        return try {
            field.isAccessible = true
            field.set(target, value)
            true
        } catch (t: Throwable) {
            log.warn(
                "$hookId: 写入 ${target.javaClass.name}#$fieldName 失败 " +
                    "(${t.javaClass.simpleName}: ${t.message})"
            )
            false
        }
    }

    private fun setIntFieldIfExists(
        target: Any, fieldName: String, value: Int, log: ResolverLog, hookId: String
    ): Boolean {
        val field = findField(target.javaClass, fieldName)
        if (field == null) {
            log.warn("$hookId: ${target.javaClass.name} 上没有字段 $fieldName,跳过该字段")
            return false
        }
        return try {
            field.isAccessible = true
            field.setInt(target, value)
            true
        } catch (t: Throwable) {
            // setInt 失败通常意味着字段不是 int 类型 —— 这正是上游"类型盲改写"会踩的坑
            // (HookInit.java:236-248 把 String 塞进可能是 boolean 的槽位)。
            log.warn(
                "$hookId: 写入 ${target.javaClass.name}#$fieldName=$value 失败,类型是 " +
                    "${field.type.name} (${t.javaClass.simpleName}: ${t.message})"
            )
            false
        }
    }

    /**
     * 按候选顺序试字段名,第一个存在的生效,并把**实际生效的名字**记进日志。
     * 用于 ④ 的获赞数 —— 上游的定位串写 `recDiggNum` 而写入用 `recvDiggNum`,两者必有一错。
     */
    private fun setIntFieldFirstExisting(
        target: Any, candidates: List<String>, value: Int, log: ResolverLog, hookId: String
    ): Boolean {
        for (name in candidates) {
            if (findField(target.javaClass, name) == null) continue
            if (setIntFieldIfExists(target, name, value, log, hookId)) {
                log.info("$hookId: 获赞字段实际生效的是 $name(候选 ${candidates})")
                return true
            }
        }
        log.warn(
            "$hookId: 候选字段名 $candidates 在 ${target.javaClass.name} 上都不存在或都写不进去"
        )
        return false
    }

    /**
     * `chain.args` 返回的是**不可变** List(libxposed 的 `Chain.getArgs()` javadoc 明说
     * "The returned list is immutable. If you want to change the arguments, you should call
     * proceed(Object...)"),所以要改参数必须另建一个可空元素的数组传给 `proceed(Object[])`。
     */
    private fun List<Any?>.toNullableArray(): Array<Any?> =
        Array<Any?>(size) { i -> this[i] }

    private companion object {
        const val PACK_ID = "purify"

        // hook id。ModuleEntry 在红果上把 ③④⑥ 声明为 known-missing 时引用的就是这几个常量。
        const val ID_PROMO = "purify-userpage-promo"
        const val ID_SOCIAL = "purify-social-stats"
        const val ID_RECOMMEND = "purify-recommend-users"
        const val ID_NICKNAME_SYNC = "purify-nickname-sync"
        const val ID_NICKNAME_COMMENT_PREFIX = "purify-nickname-comment-"

        /**
         * ⑤b 的**代表 id**。⑤b 的实际 hook id 是动态的(`purify-nickname-comment-<类名>#<序号>`),
         * 但在"连持有类都找不到"这种整体失败的情况下没有具体 id 可报,于是用这个代表 id 记账,
         * 保证 ⑤b 不会从 summary 里静默消失。
         */
        const val ID_NICKNAME_COMMENT = "purify-nickname-comment"

        // 定位锚点(全部来自上游 HookInit.java,已在 RESEARCH.md §3 逐条核对)
        const val METHOD_CAN_THIS_POSITION_SHOW = "canThisPositionShow"
        const val USING_STRING_SOCIAL_STATS =
            "followUserNum = %d, fansNum = %d, recDiggNum = %d, ugcReadBookCount = "
        const val USING_STRING_SYNC_USER_INFO = "doSyncInitUserInfo:%s"
        const val USING_STRING_RECOMMEND_USERS = "获取推荐用户数据成功"
        const val CLASS_COMMENT_USER_STR_INFO = "com.dragon.read.rpc.model.CommentUserStrInfo"
        const val CLASS_PROMOTION_EXTRA_INFO =
            "com.dragon.read.rpc.model.VipPromotionStrategyExtraInfo"

        // 字段名
        const val FIELD_USER_NAME = "userName"
        const val FIELD_FOLLOW_NUM = "followUserNum"
        const val FIELD_FANS_NUM = "fansNum"

        // HookManager 的 strategy 标签(用于 install summary 与 UI 明细)
        const val STRATEGY_MUTATE_ARGS = "mutate-args-then-proceed"
        const val STRATEGY_PATCH_RESULT = "proceed-then-patch-result"
    }
}
