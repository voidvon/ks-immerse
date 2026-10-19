package com.yytest.ksimmerse

import android.content.SharedPreferences
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent

/**
 * 运行时配置：分组开关、目标透明度，以及每个分组实际生效的 view id 集合。
 *
 * 所有字段都从多个线程（hook 回调）读取，因此用 @Volatile 保证可见性。
 */
class ImmerseConfig {

    companion object {
        const val PREFS_GROUP = "ks_immerse"

        const val KEY_ENABLED = "enabled"
        const val KEY_ALPHA = "alpha"
        const val KEY_RIGHT_COLUMN = "fade_right_column"
        const val KEY_BOTTOM_CAPTION = "fade_bottom_caption"
        const val KEY_DISCLAIMER = "fade_disclaimer"
        const val KEY_BOTTOM_HOT = "fade_bottom_hot"
        const val KEY_PENDANT = "fade_pendant"
        const val KEY_PIN_SEARCH = "pin_search_bubble"
        const val KEY_CONTAIN_VIDEO = "contain_video"
        const val KEY_SKIP_AD = "skip_ad"
        const val KEY_AUTO_SKIP_UNMATCHED = "auto_skip_unmatched"
        const val KEY_DEBUG_DUMP = "debug_dump_tree"
        const val KEY_KILL_DOUBLE_TAP = "kill_double_tap"

        /** 需求：右侧栏与底部信息区改为 0.1 透明度，保留可点击 */
        const val DEFAULT_ALPHA = 0.1f

        /** 广告快进的目标：跳到「总时长 - 这个值」，也就是最后 0.5 秒 */
        const val AD_TAIL_MS = 500L
    }

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var alpha: Float = DEFAULT_ALPHA
        private set

    @Volatile
    private var fadeRightColumn: Boolean = true

    @Volatile
    private var fadeBottomCaption: Boolean = true

    @Volatile
    private var fadeDisclaimer: Boolean = true

    @Volatile
    private var fadeBottomHot: Boolean = true

    @Volatile
    private var fadePendant: Boolean = true

    /** 把暂停时的「搜画面」气泡固定到右上角 */
    @Volatile
    var pinSearchBubble: Boolean = true
        private set

    /** 视频按 contain 显示：完整画面，不裁切两侧 */
    @Volatile
    var containVideo: Boolean = true
        private set

    /** 检测到底部出现「广告」标签时，把进度快进到最后 0.5 秒 */
    @Volatile
    var skipAd: Boolean = false
        private set

    /**
     * 自动划走「沉浸没生效」的视频。
     *
     * 判据：当前可见页面上**没有任何目标控件处于 config.alpha 的透明度** ——
     * 说明这一页的右侧栏/底部信息区不是我们认得的那套控件（广告页就是这种），
     * 沉浸效果出不来，于是注入一个上滑手势翻到下一个。
     */
    @Volatile
    var autoSkipUnmatched: Boolean = true
        private set

    /** 调试开关：把宿主的视图树 dump 到日志，用于适配新版本 */
    @Volatile
    var debugDumpTree: Boolean = true
        private set

    /**
     * 接管视频区域的单击：去掉双击点赞，并让单击立刻生效。
     *
     * 宿主把单击（暂停）和双击（点赞）做在同一套自定义手势里，
     * 实测单击按下后要 309ms 才真的 pause —— 就是在等"会不会来第二下"。
     * 打开后由模块自己吃掉点击，单击立刻切播放/暂停，第二下直接丢弃。
     */
    @Volatile
    var killDoubleTap: Boolean = true
        private set

    // 各分组当前使用的 id（可被运行时按名解析替换）
    @Volatile
    private var rightIds: IntArray = idsOf(Targets.RIGHT_COLUMN)
    @Volatile
    private var captionIds: IntArray = idsOf(Targets.BOTTOM_CAPTION)
    @Volatile
    private var disclaimerIds: IntArray = idsOf(Targets.DISCLAIMER)
    @Volatile
    private var hotIds: IntArray = idsOf(Targets.BOTTOM_HOT)
    @Volatile
    private var adLabelIds: IntArray = idsOf(Targets.AD_LABEL)
    @Volatile
    private var overlayIds: IntArray = idsOf(Targets.OVERLAY)

    /** 判定"自动划走"之前要排除的浮层（评论区等） */
    fun overlayIds(): IntArray = overlayIds

    /** 启用分组的 id 并集，热路径只扫这个小数组 */
    @Volatile
    private var targets: IntArray = IntArray(0)

    @Volatile
    private var namesResolved: Boolean = false

    init {
        rebuild()
    }

    // ------------------------------------------------------------------ 配置读取

    /** 从（远程）SharedPreferences 载入配置；传 null 表示沿用默认值 */
    fun bind(prefs: SharedPreferences?) = bindWith { prefs?.all?.get(it) }

    /** 从 ContentProvider 送来的「键 → 值」载入配置 */
    fun bindValues(values: Map<String, Any?>) = bindWith { values[it] }

    private fun bindWith(get: (String) -> Any?) {
        enabled = bool(get(KEY_ENABLED), true)
        alpha = float(get(KEY_ALPHA), DEFAULT_ALPHA).coerceIn(0f, 1f)
        fadeRightColumn = bool(get(KEY_RIGHT_COLUMN), true)
        fadeBottomCaption = bool(get(KEY_BOTTOM_CAPTION), true)
        fadeDisclaimer = bool(get(KEY_DISCLAIMER), true)
        fadeBottomHot = bool(get(KEY_BOTTOM_HOT), true)
        fadePendant = bool(get(KEY_PENDANT), true)
        pinSearchBubble = bool(get(KEY_PIN_SEARCH), true)
        containVideo = bool(get(KEY_CONTAIN_VIDEO), true)
        skipAd = bool(get(KEY_SKIP_AD), false)
        autoSkipUnmatched = bool(get(KEY_AUTO_SKIP_UNMATCHED), true)
        debugDumpTree = bool(get(KEY_DEBUG_DUMP), false)
        killDoubleTap = bool(get(KEY_KILL_DOUBLE_TAP), true)
        rebuild()
    }

    private fun bool(v: Any?, d: Boolean): Boolean = (v as? Boolean) ?: d

    private fun float(v: Any?, d: Float): Float = (v as? Number)?.toFloat() ?: d

    // ------------------------------------------------------------------ 资源名解析

    /**
     * 用目标应用的 Resources 把资源名解析成真实 id。
     * 只跑一次；解析失败的项保留兜底数值 id。
     *
     * @return 本次调用是否真的执行了解析（用于只打一次日志）
     */
    fun resolveNames(res: Resources): Boolean {
        if (namesResolved) return false
        namesResolved = true
        rightIds = resolve(res, Targets.RIGHT_COLUMN, rightIds)
        captionIds = resolve(res, Targets.BOTTOM_CAPTION, captionIds)
        disclaimerIds = resolve(res, Targets.DISCLAIMER, disclaimerIds)
        hotIds = resolve(res, Targets.BOTTOM_HOT, hotIds)
        adLabelIds = resolve(res, Targets.AD_LABEL, adLabelIds)
        overlayIds = resolve(res, Targets.OVERLAY, overlayIds)
        rebuild()
        return true
    }

    private fun resolve(res: Resources, group: Array<Pair<String, Int>>, fallback: IntArray): IntArray {
        val out = fallback.copyOf()
        for (i in group.indices) {
            if (i >= out.size) break
            val id = try {
                res.getIdentifier(group[i].first, "id", Targets.PACKAGE)
            } catch (_: Throwable) {
                0
            }
            if (id != 0) out[i] = id
        }
        return out
    }

    // ------------------------------------------------------------------ 热路径

    /** 按 id 判断命中（热路径） */
    fun isTarget(id: Int): Boolean {
        if (!enabled || alpha >= 1f) return false
        val t = targets
        for (i in t.indices) {
            if (t[i] == id) return true
        }
        return false
    }

    /** 命中需要固定位置的控件？ */
    fun isPinTarget(id: Int): Boolean = enabled && pinSearchBubble && id == Targets.ID_SEARCH_BUBBLE

    /** 当前生效的「广告标签」id 列表（热路径只扫这个小数组） */
    fun adLabelIds(): IntArray = adLabelIds

    /** 所有需要降透明度的目标 id（用于判断"这一页到底有没有匹配上"） */
    fun targetIds(): IntArray = targets

    /**
     * 按控件判断命中：先看 id，再看类名。
     * 悬浮挂件（红包/金币）没有资源 id，只能靠类名识别。
     */
    fun isTarget(view: View): Boolean {
        if (isTarget(view.id)) return true
        if (!fadePendant || !enabled || alpha >= 1f) return false
        val name = view.javaClass.name
        val cs = Targets.PENDANT_CLASSES
        for (i in cs.indices) {
            if (cs[i] == name) return true
        }
        return false
    }

    /** 命中则把透明度压到目标值 */
    fun applyTo(view: View) {
        if (!isTarget(view)) return
        // 父链上已经有目标控件时直接跳过，否则父子都被设成 0.1 会叠乘成 0.01
        if (hasTargetAncestor(view)) return
        if (view.alpha != alpha) view.alpha = alpha
    }

    private fun hasTargetAncestor(view: View): Boolean {
        var p: ViewParent? = view.parent
        while (p is View) {
            if (isTarget(p)) return true
            p = p.parent
        }
        return false
    }

    /** 对刚 inflate 出来的子树做兜底扫描，返回本次真正设置了透明度的控件 id */
    fun applyToTree(root: View): IntArray {
        if (!enabled || alpha >= 1f) return IntArray(0)
        val t = targets
        val applied = ArrayList<Int>(4)
        for (i in t.indices) {
            val v = root.findViewById<View>(t[i]) ?: continue
            if (hasTargetAncestor(v)) continue
            if (v.alpha != alpha) {
                v.alpha = alpha
                applied.add(t[i])
            }
        }
        return applied.toIntArray()
    }

    /**
     * 设置被改动之后，把当前视图树上**所有**目标控件重新刷一遍。
     *
     * 为什么不能复用 [applyToTree]：那个方法用 `findViewById` 只找每个 id 的**第一个**，
     * 而 ViewPager 同时挂着 3 页，同一个 id 有 3 个实例；而且关掉总开关时
     * [isTarget] 会返回 false，[applyToTree] 直接不干活，界面会永远停在压暗状态。
     *
     * 这里显式遍历整棵树，并且按新配置决定是压暗还是恢复成 1.0。
     */
    fun reapplyAll(root: View) {
        val want = if (enabled && alpha < 1f) alpha else 1f
        val t = targets
        val found = ArrayList<View>(16)
        collectById(root, t, found)
        for (v in found) {
            if (hasTargetAncestor(v)) continue
            if (v.alpha != want) v.alpha = want
        }
    }

    private fun collectById(v: View, ids: IntArray, out: MutableList<View>) {
        for (i in ids.indices) {
            if (ids[i] == v.id) {
                out.add(v)
                break
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectById(v.getChildAt(i), ids, out)
        }
    }

    // ------------------------------------------------------------------ 杂项

    private fun rebuild() {
        val out = ArrayList<Int>(8)
        if (fadeRightColumn) rightIds.forEach { out.add(it) }
        if (fadeBottomCaption) captionIds.forEach { out.add(it) }
        if (fadeDisclaimer) disclaimerIds.forEach { out.add(it) }
        if (fadeBottomHot) hotIds.forEach { out.add(it) }
        targets = out.toIntArray()
    }

    private fun idsOf(group: Array<Pair<String, Int>>) = IntArray(group.size) { group[it].second }

    /** 输出当前生效配置，便于在日志里核对 */
    fun snapshot(): String = buildString {
        append("enabled=").append(enabled)
        append(" alpha=").append(alpha)
        append(" right=").append(rightIds.joinToString(",") { hex(it) })
        append(" caption=").append(captionIds.joinToString(",") { hex(it) })
        append(" disclaimer=").append(disclaimerIds.joinToString(",") { hex(it) })
        append(" hot=").append(hotIds.joinToString(",") { hex(it) })
        append(" pendant=").append(fadePendant)
        append(" pinSearch=").append(pinSearchBubble)
        append(" containVideo=").append(containVideo)
        append(" skipAd=").append(skipAd)
        append(" autoSkipUnmatched=").append(autoSkipUnmatched)
        append(" debug=").append(debugDumpTree)
        append(" killDoubleTap=").append(killDoubleTap)
        append(" adLabel=").append(adLabelIds.joinToString(",") { hex(it) })
    }

    private fun hex(v: Int) = "0x" + Integer.toHexString(v)
}
