package dev.superfqkill.packs

/**
 * 界面净化的**用户自定义值** —— 想改就改这一个文件,然后重新构建。
 *
 * 为什么是编译期常量而不是运行时开关:
 *  · 上游两个仓库对番茄/红果都**零配置面**(FanqieHook 连 Activity 都没有;squemaFQH 的设置页
 *    只服务夸克,番茄/红果的值全是硬编码常量,连昵称"云朵"都写死在 `HookInit.java:79`)。
 *  · 运行时下发需要 host←module 方向的配置通道。libxposed 的 `getRemotePreferences(group)`
 *    正好是这个方向(模块 App 侧可写、被 hook 侧只读),但**写的一侧**要有 UI 才能落地,
 *    那是 v0.2 的事(PLAN.md §6.4)。
 *  · 现在有了 CI,改一行 + push 就能拿到新 APK,重建成本已经很低。
 *
 * 全部值都只影响**本地显示**,不触碰任何服务端数据、权益或发奖(见 [PurifyPack] 的类注释)。
 */
object PurifyConfig {

    // ── ⑤ 昵称 ────────────────────────────────────────────────────────────────
    /**
     * 伪造的昵称。上游写死为 "云朵"(`\u4e91\u6735`)。
     *
     * 生效范围:个人信息同步入口(⑤)与评论用户名(⑤b)。**仅本地显示** —— 别人看到的
     * 仍是服务端数据,这只改你自己客户端上渲染出来的名字。
     *
     * 置为 `null` 或空串则整条 ⑤/⑤b 不安装(见 [PurifyPack.installNicknameHooks])。
     */
    // 注意是 `val` 不是 `const val`：Kotlin 的 const 只允许基元与**非空** String，
    // 而这里"置为 null 即关闭该项"的契约需要可空类型。object 里的 val 同样是进程内唯一、
    // 初始化即定的字段，改法与常量没有区别。
    val NICKNAME: String? = "云朵"

    // ── ④ 社交数字 ────────────────────────────────────────────────────────────
    /**
     * 伪造的关注数 / 粉丝数 / 获赞数。上游分别是 5200000 / 13140000 / 9990000
     * (`HookInit.java:83-85`)。
     *
     * 同样**仅本地显示**:这三个数字写在客户端渲染用的 model 对象上,不会影响服务端,
     * 别人看你的主页看到的还是真实数据。
     *
     * 任意一项置为 `null` 则跳过该字段。三项全为 `null` 则整条 ④ 不安装。
     */
    // 同上：可空 → 不能是 const。
    val FOLLOW_NUM: Int? = 5200000
    val FANS_NUM: Int? = 13140000
    val DIGG_NUM: Int? = 9990000

    /**
     * 获赞字段名的候选列表,**按顺序尝试**,第一个存在的生效。
     *
     * 为什么需要这个:上游有一处未解决的疑似 bug —— 定位串写的是 `recDiggNum`
     * (`HookInit.java:391`),实际写入的字段却是 `recvDiggNum`(`:404`)。若真实字段名是前者,
     * `findField` 抛 `NoSuchFieldException`,而上游在 `:405` 用
     * `catch (ReflectiveOperationException ignored)` **无日志吞掉** —— 获赞数永远伪造不成功
     * 且完全隐形(RESEARCH.md §8 记录了这一点)。
     *
     * 这里两个名字都试,并且**把失败记进日志**而不是吞掉。真机上跑一次看日志就知道
     * 哪个才是对的,然后把另一个删掉。
     */
    val DIGG_FIELD_CANDIDATES: List<String> = listOf("recvDiggNum", "recDiggNum")

    // ── ③ 个人页推广位 ────────────────────────────────────────────────────────
    /**
     * ③ 改写后的 `leftTime`(秒)。上游用的是 `FAKE_EXPIRE_MS / 1000` = 113143670061
     * (`HookInit.java:74-76`)。
     *
     * ⚠️ 上游对这个常量的注释算错了:它说 `113143670061000L` ms ≈ 5355 年,实际是
     * **5555-05-20T05:14:21Z**(约 3585 年)。不影响功能,但别照着那句注释理解。
     *
     * 语义是"这个推广位的剩余时间还很长",配合 [PROMOTION_TEXT] 清空文案,效果是
     * 让个人页那个推广位渲染成一条空 banner。**它不伪造会员态、不触碰权益数据。**
     */
    const val PROMOTION_LEFT_TIME_SEC: Long = 113143670061L

    /** ③ 改写后的推广文案。上游是空串,即把文案清掉。 */
    const val PROMOTION_TEXT: String = ""

    /** ③ 只在 `pageName` 等于这个值时生效(上游 `HookInit.java:355` 的判定)。 */
    const val PROMOTION_PAGE_NAME: String = "PromotionFromUserPage"

    // ── ⑤b 爆炸半径控制 ───────────────────────────────────────────────────────
    /**
     * ⑤b 最多 hook 多少个"持有 CommentUserStrInfo 字段的类"。
     *
     * 为什么要有上限:上游的 `findClassesWithFieldType` 返回**所有**持有该字段的类,然后对每个类
     * hook 其"参数含自身类型"的所有非抽象方法(一个 `copyFrom`/merge 模式),每个 hook 再对
     * **所有非 null 参数**盲写 `userName`。那是热路径上的宽拦截,而上游 README 完全没警告其
     * 爆炸半径(PLAN.md §5.3)。
     *
     * 超过上限就停止安装并 WARN,把实测数字留在日志里 —— 先在真机上看清命中了多少,
     * 再决定这个上限该设多大。设为 [Int.MAX_VALUE] 即不限制(等同上游行为)。
     */
    const val MAX_COMMENT_HOLDER_CLASSES: Int = 8

    /** 每个持有类上最多 hook 多少个方法。理由同上。 */
    const val MAX_HOOKS_PER_HOLDER_CLASS: Int = 4

    /**
     * ⑤ 的 `setField` 是否只写第一个成功的参数。
     *
     * 上游对**每一个非 null 参数**都盲写一遍 `userName`(`HookInit.java:305-311`),
     * 而匹配到的方法可能有多个参数、其中多数与用户名无关 —— 那是没必要的宽。
     * 设为 true 则写成功一个就停。
     */
    const val NICKNAME_STOP_AFTER_FIRST_HIT: Boolean = true
}
