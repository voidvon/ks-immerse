package com.yytest.ksimmerse

import android.content.SharedPreferences
import android.content.res.Resources
import android.view.View
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
        const val KEY_DEBUG_DUMP = "debug_dump_tree"

        /** 需求：右侧栏与底部信息区改为 0.1 透明度，保留可点击 */
        const val DEFAULT_ALPHA = 0.1f
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

    /** 调试开关：把宿主的视图树 dump 到日志，用于适配新版本 */
    @Volatile
    var debugDumpTree: Boolean = true
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
    fun bind(prefs: SharedPreferences?) {
        enabled = prefs?.getBoolean(KEY_ENABLED, true) ?: true
        alpha = (prefs?.getFloat(KEY_ALPHA, DEFAULT_ALPHA) ?: DEFAULT_ALPHA).coerceIn(0f, 1f)
        fadeRightColumn = prefs?.getBoolean(KEY_RIGHT_COLUMN, true) ?: true
        fadeBottomCaption = prefs?.getBoolean(KEY_BOTTOM_CAPTION, true) ?: true
        fadeDisclaimer = prefs?.getBoolean(KEY_DISCLAIMER, true) ?: true
        fadeBottomHot = prefs?.getBoolean(KEY_BOTTOM_HOT, true) ?: true
        fadePendant = prefs?.getBoolean(KEY_PENDANT, true) ?: true
        pinSearchBubble = prefs?.getBoolean(KEY_PIN_SEARCH, true) ?: true
        containVideo = prefs?.getBoolean(KEY_CONTAIN_VIDEO, true) ?: true
        debugDumpTree = prefs?.getBoolean(KEY_DEBUG_DUMP, false) ?: false
        rebuild()
    }

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
    }

    private fun hex(v: Int) = "0x" + Integer.toHexString(v)
}
