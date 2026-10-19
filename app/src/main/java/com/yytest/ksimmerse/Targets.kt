package com.yytest.ksimmerse

/**
 * 快手极速版（com.kuaishou.nebula）信息流叠层控件。
 *
 * ## 背景
 * 快手用 AndResGuard 混淆了资源**文件路径**（`res/layout/x.xml` -> `r/ar/a.xml`），
 * 但 `resources.arsc` 里的 **id 名称没有被混淆**；而且真正的 UI 资源放在第二张资源表
 * `assets/kwai_res/core_res`（11982 个 id、3947 个布局）里。
 * 因此这里优先在运行时按资源名解析，解析不到时退回实测确认的数值 id ——
 * 数值 id 已经写死在编译后的布局里，所以即使 getIdentifier 拿不到名字，
 * 用数值 id 做 findViewById 依然有效。
 *
 * ## 重要：首页信息流 ≠ 视频详情页
 * 实测（14.7.10.11757）发现两者用的是**完全不同的布局**：
 *
 * - **首页信息流**（HomeActivity 里的 Milano/slide_v2 滑页）：
 *   右侧栏根容器 `group_right_action_bar_root_layout`，类
 *   `com.yxcorp.gifshow.detail.slideplay.AdaptSizeChangeScaleLinearLayout`；
 *   底部信息区根容器 `group_information_root_layout`（内部是 `username_group` +
 *   `caption_scroll_container`）。这一套**没有** like_content / side_interactive_container。
 * - **视频详情页**：右侧栏是 `global_table_layout`（内含 `side_interactive_container`），
 *   底部说明是 `global_caption_layout`。
 *
 * 两套都列进来，靠 id 命中即可，不需要判断当前在哪个页面。
 */
object Targets {

    /** 作用域包名 */
    const val PACKAGE = "com.kuaishou.nebula"

    /** 数值 id 的验证版本 */
    const val VERIFIED_VERSION = "14.7.10.11757"

    // ------------------------------------------------------------------ 右侧整列

    /** 首页信息流：整条右侧操作栏（头像/关注/点赞/评论/收藏/转发） */
    const val NAME_RIGHT_BAR = "group_right_action_bar_root_layout"
    const val ID_RIGHT_BAR = 0x7f090bb4

    /** 视频详情页变体：整列 */
    const val NAME_RIGHT_TABLE = "global_table_layout"
    const val ID_RIGHT_TABLE = 0x7f0925cd

    /** 视频详情页变体：操作栏本体（global_table_layout 的后代，靠父链去重） */
    const val NAME_SIDE_INTERACTIVE = "side_interactive_container"
    const val ID_SIDE_INTERACTIVE = 0x7f092532

    // ------------------------------------------------------------------ 底部信息区

    /** 首页信息流：发布者名称 / 说明 / 标签 */
    const val NAME_BOTTOM_INFO = "group_information_root_layout"
    const val ID_BOTTOM_INFO = 0x7f090baa

    /** 视频详情页变体：说明区 */
    const val NAME_BOTTOM_CAPTION = "global_caption_layout"
    const val ID_BOTTOM_CAPTION = 0x7f0925cc

    // ------------------------------------------------------------------ 底部热点条

    /**
     * 首页信息流底部「🔥 热点 · xxx」条。
     *
     * 实测：屏幕上这条是 `group_bottom_root_layout`（类
     * `com.yxcorp.gifshow.detail.slideplay.TouchDelegateLinearLayout`，1080x120，
     * 位于页面底部），其 id 由代码设置在 r/ar/btl.xml 的根节点上；
     * 内部是 `general_entry_single_root_view` -> `left_layout` ->
     * `general_entry_title_container` ->（`general_entry_pre_title` + `general_entry_title`）。
     * 所以这里以 `group_bottom_root_layout` 为主、`general_entry_single_root_view` 为兜底。
     */
    const val NAME_BOTTOM_HOT = "group_bottom_root_layout"
    const val ID_BOTTOM_HOT = 0x7f090ba4

    const val NAME_BOTTOM_ENTRY = "general_entry_single_root_view"
    const val ID_BOTTOM_ENTRY = 0x7f092d41

    /**
     * 另一种底部推荐条（r/ar/bz1.xml：
     * FrameLayout > InterceptHorizontalScrollView > LinearLayout）。
     * 部分版本/实验组用它承载同一条热点信息。
     */
    const val NAME_BOTTOM_QUERY = "bottom_recommend_query_root_layout"
    const val ID_BOTTOM_QUERY = 0x7f092f8c

    // ------------------------------------------------------------------ 作者声明

    const val NAME_DISCLAIMER_CONTAINER = "slide_play_photo_disclaimer_container"
    const val ID_DISCLAIMER_CONTAINER = 0x7f092493

    const val NAME_DISCLAIMER_TEXT = "slide_play_photo_disclaimer_text"
    const val ID_DISCLAIMER_TEXT = 0x7f091ba8

    // ------------------------------------------------------------------ 搜画面按钮

    /**
     * 暂停时出现的「搜画面」气泡入口。
     *
     * 布局 r/ar/qz.xml：
     *   RelativeLayout(id=search_picture_entrance_v1)
     *     KwaiLottieAnimationView(id=breathe_lottie_bubbles_view)
     *     LinearLayout(id=slide_entrance_layout_v1)   <- 药丸本体
     *       KwaiCDNImageView(id=guiding_search_icon)
     *       TextView(id=guiding_bubble_text, "搜画面")
     *
     * 快手会**给每个视频随机一个位置**，所以要靠每帧强制覆盖来固定到右上角。
     */
    const val NAME_SEARCH_BUBBLE = "search_picture_entrance_v1"
    const val ID_SEARCH_BUBBLE = 0x7f0919fe

    /**
     * 「可见区域」的参照控件 —— 气泡要贴在它的右上角。
     * texture_view_frame 是真正的视频画面矩形：视频有黑边时它只覆盖画面本身，
     * 用它当参照，气泡才不会被父容器裁掉（之前"看不见了"就是这个原因）。
     */
    const val ID_TEXTURE_VIEW_FRAME = 0x7f091dff
    const val ID_SLIDE_PLAYERKIT_VIEW = 0x7f091bca
    const val ID_PLAYER = 0x7f0915db

    /**
     * 信息流里**单个视频页**的根容器。
     *
     * ViewPager 会同时挂 3 个这样的页面（当前页 + 前后各一页的回收页），
     * 回收页里的控件 `isShown()` 依然是 true，所以判断"这一页到底有没有生效"
     * 必须先把范围收敛到可见页，否则会被回收页上的目标控件骗过去。
     */
    const val ID_PAGE_ROOT = 0x7f091264
    const val CLASS_PAGE_ROOT = "com.kwai.library.slide.base.widget.SlidePlayVerticalRelativeLayout"

/**
 * 首页播放器的手势层。单击（暂停）/ 双击（点赞）都在这里分发：
 * `onSingleTapUp` 里自己实现了一套"是不是双击"的判定，
 * 分发方法分别是 `d(MotionEvent,boolean)`（单击）和 `b(MotionEvent,boolean,int)`（双击）。
 * 内部 trace 方法是 `e(String)`，打出来就是 onSingleTapUp / dispatchDoubleTap N。
 */
/** 视频区域真正收触摸的那一层 */
const val CLASS_TOUCH_LAYOUT = "com.kwai.library.slide.base.widget.SlidePlayVerticalTouchLayout"

const val CLASS_GESTURE_VIEW = "com.kwai.feed.player.ui.GestureView"

    val VIDEO_REF_IDS = intArrayOf(ID_TEXTURE_VIEW_FRAME, ID_SLIDE_PLAYERKIT_VIEW, ID_PLAYER)

    // ------------------------------------------------------------------ 浮层

    /**
     * 「评论区」浮层。
     *
     * 拉开评论区时，信息流那一页的右侧栏 / 底部信息区会被整个盖住或隐藏，
     * 于是"屏幕上没有生效的目标控件"这个判据会**误判成广告页**，把视频划走。
     * 所以判定之前先看这个容器在不在。
     *
     * ⚠️ 只认 `comment_panel_container`。实测（uiautomator dump）：
     *   - 评论区**关着**时 `comment_panel_container` 不在树里，
     *     但 `comment_container` / `comment_secondary_panel_container`
     *     一直都在（面板是预先 inflate 好的）；
     *   - 拿后者当判据会一直"以为评论区开着"，自动划走直接失效。
     *
     * id 来源：评论区打开时 `uiautomator dump` 里出现
     * `com.kuaishou.nebula:id/comment_panel_container`，
     * 再回 `_recon/core-res-dump.txt` 查数值。
     */
    const val NAME_COMMENT_PANEL = "comment_panel_container"
    const val ID_COMMENT_PANEL = 0x7f0906c1

    /** 判定"自动划走"之前必须确认这些浮层都没开 */
    val OVERLAY_IDS = intArrayOf(ID_COMMENT_PANEL)

    val OVERLAY = arrayOf(
        NAME_COMMENT_PANEL to ID_COMMENT_PANEL,
    )

    // ------------------------------------------------------------------ 广告标签

    /**
     * 视频广告的「广告」小标签容器。
     *
     * 实测依据（classes3.dex）：
     * `com.yxcorp.gifshow.ad.detail.presenter.ad.label.thanos.a`（即
     * `ThanosAdMutableLabelPresenter` 家族）的 `doBindView()` 里
     * `findViewById(0x7f091b68)` 拿到一个 **LinearLayout** 存进字段 `g`；
     * `onBind()` 里只有 `PhotoAdvertisement.AdData.ExtraDisplayInfo.mTagInfoList`
     * 非空时才 `setVisibility(VISIBLE)` 并往里塞标签控件（`f4d/a.a(ViewGroup)`）。
     *
     * 也就是说：**这个容器可见 == 当前视频是广告**；
     * 普通视频里这个 presenter 根本不会被创建，容器保持布局里的 GONE。
     * 它和「搜画面」气泡一样是 a11y 不可见的（uiautomator 里会被跳过），
     * 所以只能进程内按 id 找。
     */
    const val NAME_AD_LABEL = "slide_play_ad_label_container"
    const val ID_AD_LABEL = 0x7f091b68

    /** 左下角广告标签（部分广告样式用它） */
    const val NAME_AD_LABEL_LEFT = "icon_left_bottom_ad_label"
    const val ID_AD_LABEL_LEFT = 0x7f090cc5

    /** 「广告标签」分组：命中任意一个且可见即判定为广告 */
    val AD_LABEL = arrayOf(
        NAME_AD_LABEL to ID_AD_LABEL,
        NAME_AD_LABEL_LEFT to ID_AD_LABEL_LEFT,
    )

    // ------------------------------------------------------------------ 悬浮挂件

    /**
     * 右上角可拖动的「红包 / 金币」悬浮挂件。
     *
     * 这个挂件是 Tachikoma（快手自研跨端渲染）画出来的，**没有任何资源 id**
     * （实测 id 全是 0xffffffff），所以只能按类名匹配。
     * 视图层级：FrameLayout(270x270) > UnionView > TKViewContainer > TKYogaLayout > ...
     * 取 UnionView 这一层：既能覆盖整个挂件，类名又足够专属，
     * 不会误伤其他 Tachikoma 渲染的元素（那些用的是 TKViewContainer）。
     */
    const val CLASS_COIN_PENDANT = "com.kuaishou.growth.pendant.coin.core.kds.unionwidget.UnionView"

    /** 按类名匹配的目标控件 */
    val PENDANT_CLASSES = arrayOf(CLASS_COIN_PENDANT)

    // ------------------------------------------------------------------ 分组

    /** 「右侧整列」分组对应的 (资源名, 兜底 id) */
    val RIGHT_COLUMN = arrayOf(
        NAME_RIGHT_BAR to ID_RIGHT_BAR,
        NAME_RIGHT_TABLE to ID_RIGHT_TABLE,
        NAME_SIDE_INTERACTIVE to ID_SIDE_INTERACTIVE,
    )

    /** 「底部信息区」分组 */
    val BOTTOM_CAPTION = arrayOf(
        NAME_BOTTOM_INFO to ID_BOTTOM_INFO,
        NAME_BOTTOM_CAPTION to ID_BOTTOM_CAPTION,
    )

    /** 「作者声明」分组 */
    val DISCLAIMER = arrayOf(
        NAME_DISCLAIMER_CONTAINER to ID_DISCLAIMER_CONTAINER,
        NAME_DISCLAIMER_TEXT to ID_DISCLAIMER_TEXT,
    )

    /** 「搜画面」气泡（需要固定位置，不参与透明度处理） */
    val SEARCH_BUBBLE = arrayOf(
        NAME_SEARCH_BUBBLE to ID_SEARCH_BUBBLE,
    )

    /** 「底部热点条」分组 */
    val BOTTOM_HOT = arrayOf(
        NAME_BOTTOM_HOT to ID_BOTTOM_HOT,
        NAME_BOTTOM_ENTRY to ID_BOTTOM_ENTRY,
        NAME_BOTTOM_QUERY to ID_BOTTOM_QUERY,
    )
}
