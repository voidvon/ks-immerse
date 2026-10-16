package com.yytest.ksimmerse

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.xmlpull.v1.XmlPullParser
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vector / libxposed API 102 模块入口。
 *
 * 目标：快手极速版信息流里，把「右侧整列（头像/昵称/关注/点赞/评论/收藏/转发）」与
 * 「底部信息区（发布者名称/说明/标签/作者声明）」的透明度压到 0.1 —— 视觉上让开视频，
 * 但控件仍然可点击。
 */
class ImmerseModule : XposedModule() {

    private val config = ImmerseConfig()

    /** 一个进程只装一次 hook */
    private val installed = AtomicBoolean(false)

    @Volatile
    private var processName: String = "?"

    /** 每个目标 id 只打一次「命中」日志 */
    private val hitOnce = ConcurrentHashMap<Int, Boolean>()

    /** 每个目标 id 的 setAlpha 拦截次数，用于判断快手是否在和我们抢 */
    private val blockCount = ConcurrentHashMap<Int, AtomicInteger>()

    /** 视图树 dump 只做一次 */
    private val dumpArmed = AtomicBoolean(true)

    /** 正在被「每帧拉回右上角」的控件（弱引用，避免持有已销毁页面的视图） */
    /** 主线程 Handler */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 当前 Activity 的 decorView，供 contain 循环使用 */
    @Volatile
    private var lastDecor: java.lang.ref.WeakReference<View>? = null

    /** contain 修正次数（诊断用） */
    private val containFixes = java.util.concurrent.atomic.AtomicInteger(0)

    /** 固定位置的诊断日志预算 */
    private val pinLogBudget = java.util.concurrent.atomic.AtomicInteger(40)

    private val pinTicking: MutableMap<View, Boolean> =
        Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        logI("onModuleLoaded process=${param.processName} api=$apiVersion framework=$frameworkName/$frameworkVersion")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 注意：绝对不能在这里对「非目标包」调用 detach()。
        // 快手主进程会先加载 com.google.android.webview，一旦 detach 就会把整个进程的
        // 模块回调全部摘掉，导致主进程永远装不上 hook。
        if (!installed.compareAndSet(false, true)) return

        try {
            loadPrefs()
            installHooks()
            logI("hooks installed, process=$processName triggerPackage=${param.packageName} ${config.snapshot()}")
        } catch (t: Throwable) {
            logW("installHooks failed", t)
        }
    }

    // ------------------------------------------------------------------ 配置

    private fun loadPrefs() {
        var prefs: SharedPreferences? = null
        try {
            prefs = getRemotePreferences(ImmerseConfig.PREFS_GROUP)
        } catch (t: Throwable) {
            logW("remote preferences unavailable, fallback to defaults", t)
        }
        config.bind(prefs)
        if (prefs != null) {
            try {
                prefs.registerOnSharedPreferenceChangeListener { p, _ ->
                    config.bind(p)
                    logI("config reloaded: ${config.snapshot()}")
                }
            } catch (t: Throwable) {
                logW("registerOnSharedPreferenceChangeListener failed", t)
            }
        }
    }

    // ------------------------------------------------------------------ Hook 安装

    private fun installHooks() {
        // 1) View#onAttachedToWindow —— 目标控件挂载到窗口时降透明度
        try {
            val m = View::class.java.getDeclaredMethod("onAttachedToWindow")
            hook(m).setId("ks_on_attached").setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val v = chain.thisObject as? View
                    if (v != null) {
                        // 整段包 try/catch：绝不能因为我们的逻辑抛异常而让
                        // 宿主的 onAttachedToWindow 不执行（那会让控件不再初始化、直接不显示）
                        try {
                            val id = v.id
                            if (config.isPinTarget(id)) registerPin(v)
                            if (config.isTarget(v)) onTargetAttached(v, id)
                            if (config.debugDumpTree) {
                                v.postDelayed({
                                    if (v.isAttachedToWindow && v.width > 0 && v.height > 0) {
                                        val loc = IntArray(2)
                                        v.getLocationOnScreen(loc)
                                        logI(
                                            "ATTACH id=${hex(v.id)} ${v.javaClass.name} " +
                                                "at=(${loc[0]},${loc[1]}) ${v.width}x${v.height} " +
                                                "a=${v.alpha} vis=${v.visibility}"
                                        )
                                    }
                                }, 900)
                            }
                        } catch (t: Throwable) {
                            logW("onAttachedToWindow handling failed", t)
                        }
                    }
                    chain.proceed()
                }
            logI("hook ok: View#onAttachedToWindow")
        } catch (t: Throwable) {
            logW("hook onAttachedToWindow failed", t)
        }

        // 2) View#setAlpha —— 记录/阻止快手把透明度改回去
        try {
            val m = View::class.java.getDeclaredMethod("setAlpha", Float::class.javaPrimitiveType!!)
            hook(m).setId("ks_set_alpha").setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val v = chain.thisObject as? View
                    if (v != null && config.isTarget(v)) {
                        val want = chain.getArg(0) as Float
                        if (want > config.alpha) {
                            val n = blockCount.computeIfAbsent(v.id) { AtomicInteger() }.incrementAndGet()
                            if (n <= 5) {
                                logI("block setAlpha#${n} id=${hex(v.id)} want=$want -> ${config.alpha} class=${v.javaClass.name}")
                            }
                            return@intercept chain.proceed(arrayOf<Any>(config.alpha))
                        }
                    }
                    chain.proceed()
                }
            logI("hook ok: View#setAlpha")
        } catch (t: Throwable) {
            logW("hook setAlpha failed", t)
        }

        // 3) LayoutInflater#inflate —— 兜底扫描新 inflate 的子树
        try {
            val m = LayoutInflater::class.java.getDeclaredMethod(
                "inflate",
                XmlPullParser::class.java,
                ViewGroup::class.java,
                Boolean::class.javaPrimitiveType!!,
            )
            hook(m).setId("ks_inflate").setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val root = chain.proceed()
                    if (root is View) {
                        val applied = config.applyToTree(root)
                        if (applied.isNotEmpty()) {
                            logI(
                                "inflate applied [${applied.joinToString(",") { hex(it) }}] " +
                                    "root=${root.javaClass.name}"
                            )
                        }
                    }
                    root
                }
            logI("hook ok: LayoutInflater#inflate")
        } catch (t: Throwable) {
            logW("hook inflate failed", t)
        }

        // 4) Activity#onCreate —— 借宿主 Resources 把资源名解析成真实 id
        try {
            val m = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            hook(m).setId("ks_activity_create").setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        try {
                            if (config.resolveNames(activity.resources)) {
                                logI("ids resolved from resources: ${config.snapshot()}")
                            }
                        } catch (t: Throwable) {
                            logW("resolveNames failed", t)
                        }
                    }
                    chain.proceed()
                }
            logI("hook ok: Activity#onCreate")
        } catch (t: Throwable) {
            logW("hook Activity#onCreate failed", t)
        }

        // 5) 诊断用：Activity 可见后把整棵视图树里带 id 的控件 dump 出来，
        //    定位屏幕上真正可见的那一套控件实例。
        try {
            val m = Activity::class.java.getDeclaredMethod("onResume")
            hook(m).setId("ks_activity_resume").setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val r = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        val decor = activity.window?.decorView
                        if (decor != null) {
                            lastDecor = java.lang.ref.WeakReference(decor)
                            startContainLoop()
                            if (config.debugDumpTree && dumpArmed.compareAndSet(true, false)) {
                                startDiag(activity, decor)
                            }
                        }
                    }
                    r
                }
            logI("hook ok: Activity#onResume")
        } catch (t: Throwable) {
            logW("hook Activity#onResume failed", t)
        }

        // 6) ViewRootImpl#setView —— 覆盖**所有窗口**，包括 WindowManager 悬浮窗
        //    （右上角可拖动的红包/金币挂件就在独立窗口里，Activity 的 decorView 看不到它）
        try {
            val cls = Class.forName("android.view.ViewRootImpl")
            val m = cls.getDeclaredMethod(
                "setView",
                View::class.java,
                WindowManager.LayoutParams::class.java,
                View::class.java,
            )
            hook(m).setId("ks_set_view").setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val root = chain.getArg(0) as? View
                    if (root != null) {
                        try {
                            val applied = config.applyToTree(root)
                            if (applied.isNotEmpty()) {
                                logI(
                                    "setView applied [${applied.joinToString(",") { hex(it) }}] " +
                                        "root=${root.javaClass.name}"
                                )
                            }
                            if (config.debugDumpTree) {
                                root.postDelayed({
                                    val out = ArrayList<String>()
                                    val loc = IntArray(2)
                                    walkAll(root, loc, out)
                                    logI("WINDOW-DUMP root=${root.javaClass.name} listed=${out.size}")
                                    for (l in out) logI("WINDOW-DUMP $l")
                                }, 2500)
                            }
                        } catch (t: Throwable) {
                            logW("setView handling failed", t)
                        }
                    }
                    chain.proceed()
                }
            logI("hook ok: ViewRootImpl#setView")
        } catch (t: Throwable) {
            logW("hook ViewRootImpl#setView failed", t)
        }
    }

    // ------------------------------------------------------------------ 视图树 dump

    /** 调试：把整棵视图树里带 id 的控件（含屏幕坐标/透明度）打到日志，用于适配新版本 */
    private fun startDiag(activity: Activity, decor: View) {
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        for (d in longArrayOf(4000, 9000, 14000, 20000, 26000)) {
            decor.postDelayed({ dumpPlayerTree(decor) }, d)
        }
        logI("DUMP screen=${screenW}x$screenH (整树 dump 已临时关闭)")

    }

    // ------------------------------------------------------------------ 视频 contain

    /**
     * 把视频从 cover 改成 contain。
     *
     * 快手真正的渲染层是 SurfaceView，它被按 cover 撑大（例如 1213x2157）塞进 1080 宽的容器里，
     * 两侧就被容器裁掉了。SurfaceView 的宽高比就是视频真实宽高比，所以这里把它约束成
     * "在容器内等比放下"的尺寸并居中 —— 画面完整，多余部分自然留出背景。
     */
    private fun startContainLoop() {
        if (!config.containVideo) return
        mainHandler.removeCallbacks(containTick)
        mainHandler.postDelayed(containTick, 1200)
    }

    private val containTick = object : Runnable {
        override fun run() {
            try {
                fixContain()
            } catch (_: Throwable) {
            }
            if (config.containVideo) mainHandler.postDelayed(this, 150)
        }
    }

    private fun fixContain() {
        val decor = lastDecor?.get() ?: return
        if (!decor.isAttachedToWindow) return
        val container = findVisibleVideoFrame(decor) ?: return
        val cW = container.width
        val cH = container.height
        if (cW <= 0 || cH <= 0) return
        val surface = findSurface(container) ?: return

        // 视频的"原生比例"取自 SurfaceView 的父层 PlayerKitContentFrame：
        // 播放器按视频真实比例给它定尺寸，而且不会因为我们改子 View 而变 —— 这是唯一可靠的来源。
        // （绝对不能用 SurfaceView 自己的尺寸：那是被 cover 撑大过的，而且会被我们上次改的值污染。）
        val sp = surface.parent as? View
        val native = sp != null && sp.width > 0 && sp.height > 0
        val vw = if (native) sp!!.width else surface.width
        val vh = if (native) sp!!.height else surface.height
        if (vw <= 0 || vh <= 0) return

        val vAspect = vw.toDouble() / vh
        val cAspect = cW.toDouble() / cH

        // 只有"视频比容器宽"（= 左右被裁切）才需要收窄；
        // 其余情况目标就是原生尺寸，等价于 MATCH_PARENT —— 顺便把之前留下的显式尺寸清掉。
        val targetW: Int
        val targetH: Int
        if (vAspect > cAspect) {
            targetW = cW
            targetH = Math.round(cW / vAspect).toInt()
        } else {
            targetW = vw
            targetH = vh
        }
        if (targetW <= 0 || targetH <= 0) return

        val lp = surface.layoutParams
        if (lp != null && lp.width == targetW && lp.height == targetH) return
        val flp = lp as? android.widget.FrameLayout.LayoutParams
            ?: android.widget.FrameLayout.LayoutParams(targetW, targetH)
        val before = "${surface.width}x${surface.height}"
        flp.width = targetW
        flp.height = targetH
        flp.gravity = android.view.Gravity.CENTER
        surface.layoutParams = flp

        val n = containFixes.incrementAndGet()
        if (n <= 5) {
            logI(
                "CONTAIN $before -> ${targetW}x${targetH} container=${cW}x$cH " +
                    "native=${vw}x$vh(${"%.4f".format(vAspect)}) cAspect=${"%.4f".format(cAspect)}"
            )
        }
    }

    /**
     * 找**当前屏幕上**的视频容器。
     * ViewPager 会缓存前后各一页，findViewById 拿到的很可能是回收页（y 为负）。
     */
    private fun findVisibleVideoFrame(decor: View): View? {
        val all = ArrayList<View>(4)
        collectById(decor, Targets.ID_TEXTURE_VIEW_FRAME, all)
        if (all.isEmpty()) return null
        val screenH = decor.resources.displayMetrics.heightPixels
        val loc = IntArray(2)
        var best: View? = null
        var bestArea = 0
        for (v in all) {
            if (v.width <= 0 || v.height <= 0) continue
            v.getLocationOnScreen(loc)
            val top = maxOf(loc[1], 0)
            val bottom = minOf(loc[1] + v.height, screenH)
            val visible = (bottom - top).coerceAtLeast(0) * v.width
            if (visible > bestArea) {
                bestArea = visible
                best = v
            }
        }
        return best
    }

    private fun collectById(v: View, id: Int, out: MutableList<View>) {
        if (v.id == id) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectById(v.getChildAt(i), id, out)
        }
    }

    /** 调试：完整 dump 播放器子树（不过滤 id），用来看清视频到底渲染在哪一层 */
    private fun dumpPlayerTree(decor: View) {
        val ids = intArrayOf(
            Targets.ID_TEXTURE_VIEW_FRAME,
            Targets.ID_SLIDE_PLAYERKIT_VIEW,
            Targets.ID_PLAYER,
        )
        for (id in ids) {
            val root = try {
                decor.findViewById<View>(id)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (root.width <= 0) continue
            val out = ArrayList<String>()
            walkSubtree(root, 0, out)
            logI("PLAYER-DUMP root=${root.javaClass.name} id=${hex(id)} lines=${out.size}")
            for (l in out) logI("PLAYER-DUMP $l")
            return
        }
    }

    private fun walkSubtree(v: View, depth: Int, out: MutableList<String>) {
        if (out.size > 120) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val pad = "  ".repeat(minOf(depth, 10))
        val lp = v.layoutParams
        val lpInfo = when (lp) {
            is android.widget.FrameLayout.LayoutParams ->
                "FLP(g=${Integer.toHexString(lp.gravity)},m=${lp.leftMargin},${lp.topMargin}," +
                    "${lp.rightMargin},${lp.bottomMargin},${lp.width}x${lp.height})"
            null -> "null"
            else -> "${lp.javaClass.simpleName}(${lp.width}x${lp.height})"
        }
        out.add(
            "$pad${v.javaClass.simpleName} id=${hex(v.id)} at=(${loc[0]},${loc[1]}) " +
                "${v.width}x${v.height} vis=${v.visibility} sc=(${v.scaleX},${v.scaleY}) " +
                "tx=(${v.translationX},${v.translationY}) lp=$lpInfo"
        )
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkSubtree(v.getChildAt(i), depth + 1, out)
        }
    }

    private fun findSurface(v: View): android.view.SurfaceView? {
        if (v is android.view.SurfaceView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findSurface(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun walkAll(v: View, loc: IntArray, out: MutableList<String>) {
        v.getLocationOnScreen(loc)
        val tv = v as? android.widget.TextView
        val hasText = tv != null && tv.text.isNotEmpty()
        if ((v.id != View.NO_ID || hasText) && out.size < 900) {
            val mark = if (config.isTarget(v)) " <<<TARGET" else ""
            val textInfo = if (hasText) " text=${tv!!.text}" else ""
            val lp = v.layoutParams
            val lpInfo = when (lp) {
                is android.widget.FrameLayout.LayoutParams ->
                    "FLP(g=${Integer.toHexString(lp.gravity)},m=${lp.leftMargin},${lp.topMargin},${lp.rightMargin},${lp.bottomMargin},${lp.width}x${lp.height})"
                is android.widget.RelativeLayout.LayoutParams ->
                    "RLP(w=${lp.width},h=${lp.height},rules=${lp.rules?.contentToString()})"
                else -> lp?.javaClass?.simpleName ?: "null"
            }
            out.add(
                "${v.javaClass.name} id=${hex(v.id)} at=(${loc[0]},${loc[1]}) " +
                    "${v.width}x${v.height} a=${v.alpha} vis=${v.visibility} " +
                    "left=${v.left} top=${v.top} tx=${v.translationX} ty=${v.translationY} " +
                    "parent=${v.parent?.javaClass?.name} lp=$lpInfo$textInfo$mark"
            )
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkAll(v.getChildAt(i), loc, out)
        }
    }

    // ------------------------------------------------------------------ 固定位置

    /**
     * 把「搜画面」气泡钉在右上角。
     *
     * 快手给每个视频随机一个位置，单次设置会被它自己改回去，所以这里挂一个
     * OnPreDrawListener，**每帧绘制前**都把位置拉回右上角 —— 相当于强制覆盖。
     * 用 translation 而不是改 LayoutParams：不受父容器类型限制，也不会触发重新布局。
     */
    private fun registerPin(v: View) {
        // 真正响应点击的很可能是外层 SearchPicBubbleElement / SearchPicBubbleItemView，
        // 只移动内层 RelativeLayout 的话视觉走了、点击区还在原地。
        val target = findBubbleRoot(v) ?: v
        logI("pin target=${target.javaClass.name} id=${hex(target.id)} inner=${v.javaClass.name}")
        if (pinTicking.put(target, true) != null) return
        var ref: View? = null
        val tick = object : Runnable {
            override fun run() {
                if (!target.isAttachedToWindow) {
                    pinTicking.remove(target)
                    return
                }
                try {
                    val root = target.rootView
                    if (root != null && (ref == null || !ref!!.isAttachedToWindow)) {
                        ref = findVideoRef(root)
                    }
                    pinToTopRight(target, ref)
                } catch (_: Throwable) {
                    // 每帧回调里绝不能抛异常
                }
                target.postOnAnimation(this)
            }
        }
        target.postOnAnimation(tick)
        logI("pin ticker started id=${hex(target.id)}")
    }

    /** 找「可见画面」的参照控件：优先真正的视频画面，其次播放器容器 */
    private fun findVideoRef(root: View): View? {
        for (id in Targets.VIDEO_REF_IDS) {
            val rv = try {
                root.findViewById<View>(id)
            } catch (_: Throwable) {
                null
            }
            if (rv != null && rv.isShown && rv.width > 0 && rv.height > 0) return rv
        }
        return null
    }

    /** 向上找最外层的 SearchPicBubble* 容器 */
    private fun findBubbleRoot(v: View): View? {
        var p: ViewParent? = v.parent
        var found: View? = null
        var guard = 0
        while (p is View && guard++ < 40) {
            val n = p.javaClass.name
            if (n.contains("searchpic.SearchPicBubbleElement") ||
                n.contains("searchpic.SearchPicBubbleItemView")
            ) {
                found = p
            }
            p = p.parent
        }
        return found
    }

    /**
     * 把控件钉在「可见画面」的右上角。
     *
     * 每帧重算并修正 translation —— 快手给每个视频随机位置，只有每帧覆盖才压得住。
     * translation 同时影响命中测试，所以点击热区会跟着一起走，不会留下"隐形按钮"。
     */
    private fun pinToTopRight(v: View, ref: View?) {
        if (v.width <= 0 || v.height <= 0) return
        val root = v.rootView ?: return
        val loc = IntArray(2)
        val rloc = IntArray(2)
        v.getLocationOnScreen(loc)
        val visLeft: Int
        val visTop: Int
        val visRight: Int
        if (ref != null && ref.isAttachedToWindow && ref.width > 0 && ref.height > 0) {
            ref.getLocationOnScreen(rloc)
            visLeft = rloc[0]
            visTop = rloc[1]
            visRight = rloc[0] + ref.width
        } else {
            root.getLocationOnScreen(rloc)
            visLeft = rloc[0]
            visTop = rloc[1]
            visRight = rloc[0] + root.width
        }
        if (visRight - visLeft < v.width) return
        val density = v.resources.displayMetrics.density
        val marginX = (PIN_MARGIN_X_DP * density).toInt()
        val desiredX = visRight - v.width - marginX
        // 纵向取两者较大值：
        //  - 全屏视频：避开顶部导航栏（同城/商城/生活/关注/发现）
        //  - 黑边视频：贴画面自身的顶部，才真的是"可见区域的右上角"
        val desiredY = maxOf(
            visTop + (PIN_MARGIN_EDGE_DP * density).toInt(),
            rloc[1] + (PIN_MARGIN_TOP_DP * density).toInt(),
        )
        val dx = desiredX - loc[0]
        val dy = desiredY - loc[1]
        if (dx != 0) v.translationX = v.translationX + dx
        if (dy != 0) v.translationY = v.translationY + dy
        if (pinLogBudget.getAndDecrement() > 0) {
            logI(
                "PIN ${v.javaClass.simpleName} vis=[$visLeft,$visTop,$visRight] " +
                    "was=(${loc[0]},${loc[1]}) -> (${loc[0] + dx},${loc[1] + dy}) " +
                    "size=${v.width}x${v.height} ref=${ref?.javaClass?.simpleName}"
            )
        }
    }

    // ------------------------------------------------------------------ 命中处理

    /**
     * 目标控件挂载时调用：设置透明度，并做 3 轮诊断
     *  1) 立刻回读 —— 确认 setAlpha 有没有写进去
     *  2) 2 秒后回读 —— 确认快手有没有改回去
     *  3) 之后每秒重放一次并回读 —— 确认到底是「被改回去」还是「压根没渲染出来」
     */
    private fun onTargetAttached(v: View, id: Int) {
        config.applyTo(v)

        if (hitOnce.putIfAbsent(id, true) != null) return

        logI(
            "HIT id=${hex(id)} class=${v.javaClass.name} " +
                "alphaNow=${v.alpha} vis=${v.visibility} " +
                "size=${v.width}x${v.height} attached=${v.isAttachedToWindow} " +
                "parent=${v.parent?.javaClass?.name}"
        )

        v.postDelayed({
            logI("VERIFY+2s id=${hex(id)} alpha=${v.alpha} attached=${v.isAttachedToWindow} size=${v.width}x${v.height}")
        }, 2000)
    }

    // ------------------------------------------------------------------ 日志

    private fun logI(msg: String) {
        Log.i(TAG, msg)
        runCatching { log(Log.INFO, TAG, msg) }
    }

    private fun logW(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        runCatching { log(Log.WARN, TAG, msg, t) }
    }

    private fun hex(v: Int) = "0x" + Integer.toHexString(v)

    private companion object {
        const val TAG = "KSImmerse"

        /** 固定到右上角时的边距 */
        const val PIN_MARGIN_X_DP = 12f

        /** 距屏幕顶部的下限：避开顶部导航栏 */
        const val PIN_MARGIN_TOP_DP = 96f

        /** 距「可见画面」顶部的边距 */
        const val PIN_MARGIN_EDGE_DP = 12f
    }
}
