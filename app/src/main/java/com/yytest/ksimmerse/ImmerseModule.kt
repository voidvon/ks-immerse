package com.yytest.ksimmerse

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        try {
            logI("prefs group=${ImmerseConfig.PREFS_GROUP} null=${prefs == null} keys=${prefs?.all?.keys}")
        } catch (t: Throwable) {
            logW("prefs dump failed", t)
        }
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

    // ------------------------------------------------ 设置实时生效（ContentProvider 通道）

    /** 本进程的 Application，作为访问 ContentResolver 的 Context */
    @Volatile
    private var appContext: Application? = null

    /** 已经成功从 Provider 读到过配置 */
    @Volatile
    private var configLoaded = false

    /** 已经注册过监听 */
    @Volatile
    private var configObserverRegistered = false

    private var configObserver: ContentObserver? = null

    @Volatile
    private var lastConfigRetryAt = 0L

    /**
     * 建立配置通道：先从 [ConfigProvider] 拉一次配置，再注册监听。
     *
     * 之后用户在模块界面里改任何一项，[SettingsActivity] 都会
     * `notifyChange`，这里的 observer 立刻被回调 → 重新载入 → 刷新当前界面。
     * 不需要重启宿主进程。
     *
     * 只有主进程需要 —— 其它进程（push / messagesdk）没有界面，
     * 给它们也装一份只会让每次改设置都触发四五次重载。
     */
    private fun setupConfigChannel(app: Application) {
        appContext = app
        if (processName != Targets.PACKAGE) return
        loadConfig(app, "启动")
        registerConfigObserver(app)
    }

    private fun registerConfigObserver(app: Application) {
        if (configObserverRegistered) return
        configObserverRegistered = true
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    try {
                        loadConfig(app, "设置变化")
                    } catch (t: Throwable) {
                        logW("reload on change failed", t)
                    }
                }
            }
            app.contentResolver.registerContentObserver(ConfigProvider.CONTENT_URI, false, observer)
            configObserver = observer
            logI("config channel ready: ${ConfigProvider.CONTENT_URI}")
        } catch (t: Throwable) {
            configObserverRegistered = false
            logW("registerContentObserver failed", t)
        }
    }

    /**
     * 从 Provider 读一次配置。
     *
     * ⚠️ 装了新 APK 之后**第一次**启动宿主时这里多半读不到：
     * ColorOS 的关联启动管控会拦住「快手去启动模块」这一步，日志是
     * `OplusAppStartupManager: prevent start com.yytest.ksimmerse ... by contentprovider`，
     * 紧接着 `Failed to find provider info`，query 返回 null。
     * 只要用户手动打开过一次模块界面，这个应用就不再是 "stopped" 状态，之后一直正常。
     * 所以读不到不算失败，交给 [retryConfigIfNeeded] 定期重试。
     */
    private fun loadConfig(ctx: Context, why: String) {
        val map = try {
            readProvider(ctx)
        } catch (t: Throwable) {
            logW("查询设置 Provider 失败（$why）", t)
            return
        }
        if (map.isEmpty()) {
            // 读不到就保持代码里的默认值 —— 不要 bind 一个空 map，那会把当前值重置掉
            if (why != "重试") logI("设置 Provider 暂无数据（$why），沿用默认值，稍后重试")
            return
        }
        config.bindValues(map)
        configLoaded = true
        logI("config reloaded from provider（$why）: ${config.snapshot()}")

        // 关键：立刻把新配置刷到**当前已经在屏幕上**的控件上，
        // 否则透明度/开关要等控件下次挂载才生效，看起来就是"改了没反应"。
        try {
            val decor = lastDecor?.get()
            if (decor != null) config.reapplyAll(decor)
        } catch (t: Throwable) {
            logW("reapplyAll failed", t)
        }
    }

    /** 还没读到配置就每 3 秒重试一次，直到成功（见 [loadConfig] 的注释） */
    private fun retryConfigIfNeeded() {
        if (configLoaded) return
        val ctx = appContext ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastConfigRetryAt < 3000L) return
        lastConfigRetryAt = now
        loadConfig(ctx, "重试")
        if (configLoaded) appContext?.let { registerConfigObserver(it) }
    }

    /** 从模块进程的 Provider 读出全部设置项 */
    private fun readProvider(ctx: Context): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        val c = ctx.contentResolver.query(ConfigProvider.CONTENT_URI, null, null, null, null)
            ?: return out
        c.use { cur ->
            while (cur.moveToNext()) {
                val k = cur.getString(0) ?: continue
                val t = cur.getString(1)
                val v = cur.getString(2)
                out[k] = when (t) {
                    ConfigProvider.TYPE_BOOL -> v == "1"
                    ConfigProvider.TYPE_FLOAT -> v?.toFloatOrNull()
                    ConfigProvider.TYPE_INT -> v?.toIntOrNull()
                    ConfigProvider.TYPE_LONG -> v?.toLongOrNull()
                    else -> v
                }
            }
        }
        return out
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

        // 4.5) Application#onCreate —— 建立"设置实时生效"通道。
        // 选这个点是因为 Application 本身就是 Context，而且是整个进程最早的稳定入口，
        // 能保证第一帧画面出来之前配置就已经是用户设定的值。
        try {
            val m = Application::class.java.getDeclaredMethod("onCreate")
            hook(m).setId("ks_app_create").setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val r = chain.proceed()
                    try {
                        (chain.thisObject as? Application)?.let { setupConfigChannel(it) }
                    } catch (t: Throwable) {
                        logW("setupConfigChannel failed", t)
                    }
                    r
                }
            logI("hook ok: Application#onCreate")
        } catch (t: Throwable) {
            logW("hook Application#onCreate failed", t)
        }

        // 4.6) 接管视频区域的点击：去掉双击点赞，并让单击立刻生效。
        //      宿主那套 view 属于另一个 classloader，所以真正的 hook 交给
        //      retryTapTakeover()，等视图真的出现之后再装。
        retryTapTakeover()

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
        mainHandler.removeCallbacks(containTick)
        mainHandler.postDelayed(containTick, 1200)
    }

    private val containTick = object : Runnable {
        override fun run() {
            // 无条件跑：关掉 contain 时它负责把画面恢复成铺满容器，
            // 否则设置里关掉"视频完整显示"要等下一个视频才看得出变化。
            try {
                fixContain()
            } catch (_: Throwable) {
            }
            try {
            } catch (_: Throwable) {
            }
            try {
                retryConfigIfNeeded()
            } catch (_: Throwable) {
            }
            try {
                retryTapTakeover()
            } catch (_: Throwable) {
            }
            try {
                lastDecor?.get()?.takeIf { it.isAttachedToWindow }?.let { immersiveTick(it) }
            } catch (_: Throwable) {
            }
            mainHandler.postDelayed(this, 150)
        }
    }

    /**
     * 把视频从 cover 改成 contain（完整显示，不裁切）。
     *
     * 比例**必须**问播放器要（`getVideoWidth/getVideoHeight`），不能从 View 尺寸猜：
     * `PlayerKitContentFrame` 有时候正好等于容器尺寸（= 看起来"不需要裁切"），
     * `SurfaceView` 自己的尺寸又会被我们上次写进去的值污染。
     * 旧版本就是拿 `PlayerKitContentFrame` 当"原生比例"，所以会误判成不用处理 ——
     * 表现就是「有些视频不生效」。
     */
    private fun fixContain() {
        val decor = lastDecor?.get() ?: return
        if (!decor.isAttachedToWindow) return
        val container = findVisibleVideoFrame(decor) ?: return
        val cW = container.width
        val cH = container.height
        if (cW <= 0 || cH <= 0) return
        val surface = findSurface(container) ?: return
        val lp = surface.layoutParams ?: return

        val targetW: Int
        val targetH: Int
        // 被 cover 裁掉多少（只用来打日志，方便对照"到底裁了多少"）
        var cropX = 0
        var cropY = 0
        if (!config.containVideo) {
            // 关掉 contain —— 把尺寸还给播放器，恢复原生 cover
            if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                lp.height == ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                return
            }
            targetW = ViewGroup.LayoutParams.MATCH_PARENT
            targetH = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            val sw = surface.width
            val sh = surface.height
            if (sw <= 0 || sh <= 0) return
            // 播放器还没报出尺寸（未 prepare / 广告页）就先别动，
            // 拿错的比例硬压会把画面弄歪
            val vs = videoSize(container)
            if (vs != null) {
                val vAspect = vs[0].toDouble() / vs[1]
                val cAspect = cW.toDouble() / cH

                // 等比例缩到刚好放得下：cover 裁掉多少，就缩回来多少。
                //   vAspect > cAspect：视频比容器"宽" → cover 裁的是左右 → 高度缩到 cW/vAspect
                //   否则：视频比容器"高" → cover 裁的是上下 → 宽度缩到 cH*vAspect
                if (vAspect > cAspect) {
                    targetW = cW
                    targetH = Math.round(cW / vAspect).toInt()
                    cropX = Math.round(cH * vAspect).toInt() - cW
                } else {
                    targetW = Math.round(cH * vAspect).toInt()
                    targetH = cH
                    cropY = Math.round(cW / vAspect).toInt() - cH
                }
            } else {
                // 退路：拿不到播放器尺寸时，直接量「SurfaceView 比容器大出来多少」，
                // 再等比缩回去 —— 纯几何做法，对广告页这种 findPlayer 拿不到的情况也管用。
                // 装得下就什么都不做，所以不会和我们自己上一次写进去的尺寸打架。
                if (sw <= cW && sh <= cH) return
                val scale = minOf(cW.toDouble() / sw, cH.toDouble() / sh)
                targetW = Math.round(sw * scale).toInt()
                targetH = Math.round(sh * scale).toInt()
                cropX = sw - targetW
                cropY = sh - targetH
            }
            if (targetW <= 0 || targetH <= 0) return
            if (lp.width == targetW && lp.height == targetH) return
        }

        val before = "${surface.width}x${surface.height}"
        val flp = lp as? android.widget.FrameLayout.LayoutParams
            ?: android.widget.FrameLayout.LayoutParams(targetW, targetH)
        flp.width = targetW
        flp.height = targetH
        flp.gravity = android.view.Gravity.CENTER
        surface.layoutParams = flp

        // 日志压到「每个视频一条」：按视频尺寸去重（不能按容器去重 ——
        // ViewPager 会把同一个容器复用给下一个视频，那样只会打出第一条）
        val logKey = containVw * 100000 + containVh
        if (logKey != lastContainLogged || container !== lastContainContainer) {
            lastContainLogged = logKey
            lastContainContainer = container
            val vInfo = when {
                !config.containVideo -> "contain=off（已交回播放器）"
                containVw > 0 ->
                    "video=${containVw}x${containVh}(${"%.4f".format(containVw.toDouble() / containVh)})"
                else -> "video=?（退化成几何测量）"
            }
            logI(
                "CONTAIN $before -> ${targetW}x${targetH} container=${cW}x$cH $vInfo " +
                    "cAspect=${"%.4f".format(cW.toDouble() / cH)} " +
                    "cover裁掉 x=${cropX}px y=${cropY}px"
            )
        }
    }

    /** 上一次打过 CONTAIN 日志的视频尺寸 / 容器，用来把日志压到「每个视频一条」 */
    private var lastContainLogged = 0
    private var lastContainContainer: View? = null

    private var containPageId = 0
    private var containProbeAt = 0L
    private var containVw = 0
    private var containVh = 0

    /**
     * 当前视频的**真实**宽高，直接问播放器。
     *
     * 换页会清缓存；播放器没 prepare 时返回 0 → 返回 null（这帧先不处理）。
     * 同一页 3 秒内不重复探测，免得每 150ms 都去遍历一次对象图。
     */
    private fun videoSize(container: View): IntArray? {
        val fid = System.identityHashCode(container)
        val now = SystemClock.uptimeMillis()
        if (fid != containPageId) {
            containPageId = fid
            containVw = 0
            containVh = 0
        }
        if (containVw > 0 && now - containProbeAt < 3000L) {
            return intArrayOf(containVw, containVh)
        }
        if (now - containProbeAt < 500L) return null
        containProbeAt = now
        val p = try {
            findPlayer(container)
        } catch (_: Throwable) {
            null
        } ?: return null
        val w = callInt(p, "getVideoWidth") ?: return null
        val h = callInt(p, "getVideoHeight") ?: return null
        if (w <= 0 || h <= 0) return null
        containVw = w
        containVh = h
        return intArrayOf(w, h)
    }

    private fun callInt(o: Any, name: String): Int? = try {
        (o.javaClass.getMethod(name).invoke(o) as? Number)?.toInt()
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------------- 手势：去掉双击点赞

    /**
     * 接管视频区域上的点击（见 [handleVideoTouch]）。
     *
     * 只能 hook 框架的 `View#onTouchEvent` 再按运行时类名过滤 —— 宿主那套 view
     * 是另一个 classloader 加载的，`Class.forName` 拿不到（会抛 ClassNotFoundException），
     * 但拿到实例之后 `javaClass.name` 是准的。
     */
    private fun installTapTakeover(): Boolean {
        var ok = false
        // App 的类来自另一个 classloader，Class.forName 拿不到；
        // 但 View#onTouchEvent 是框架类，可以 hook，再按运行时类名过滤。
        try {
            val m = android.view.View::class.java.getDeclaredMethod(
                "onTouchEvent",
                android.view.MotionEvent::class.java,
            )
            hook(m).setId("ks_touch").setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val v = chain.thisObject as? android.view.View
                val ev = chain.getArg(0) as? android.view.MotionEvent
                // 关掉开关时**必须** chain.proceed() 把事件放回去 ——
                // 返回 true 等于替宿主把这次触摸吃掉了，宿主的单击/双击/滑动就全废了
                if (v == null || ev == null ||
                    v.javaClass.name != Targets.CLASS_TOUCH_LAYOUT ||
                    !config.enabled || !config.killDoubleTap
                ) {
                    return@intercept chain.proceed()
                }
                handleVideoTouch(v, ev)
            }
            logI("手势接管：View#onTouchEvent 已挂上")
            ok = true
        } catch (t: Throwable) {
            logW("hook View#onTouchEvent failed", t)
        }
        return ok
    }

    /** 我们自己记的播放状态（见 [togglePlayback]） */
    @Volatile
    private var tapPaused = false

    @Volatile
    private var tapPausedFrame = 0

    private var tapDownAt = 0L
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapMoved = false

    /**
     * 自己接管视频区域上的"点击"。
     *
     * 目标应用把单击（暂停）和双击（点赞）做在同一套自定义手势里，实测：
     *
     * ```
     * TOUCH DOWN t=97898586
     * PLAYER pause() t=97898895   // +309ms，就是在等"会不会来第二下"
     * ```
     *
     * 所以这里直接把点击**吃掉**：单击立刻切播放/暂停，第二下直接丢弃（不再点赞）。
     * 目标应用看不到这次手势 → 既没有等待，也不会点赞。
     *
     * 滑动/长按不受影响：上下滑翻页由外层 ViewPager 的 `onInterceptTouchEvent` 抢手势，
     * 子 View 吃掉 DOWN 并不妨碍它（和 ScrollView 里放 Button 是同一个机制）。
     */
    private fun handleVideoTouch(v: android.view.View, ev: android.view.MotionEvent): Any {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                tapDownAt = ev.eventTime
                tapDownX = ev.x
                tapDownY = ev.y
                tapMoved = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - tapDownX
                val dy = ev.y - tapDownY
                if (dx * dx + dy * dy > 60f * 60f) tapMoved = true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val quick = ev.eventTime - tapDownAt < 300L
                if (!tapMoved && quick) togglePlayback(v)
            }
        }
        return true
    }

    /** 立刻切换播放/暂停（不再等双击超时） */
    private fun togglePlayback(v: android.view.View) {
        val decor = lastDecor?.get() ?: (v.rootView as? android.view.View)
        if (decor == null) {
            logI("TAP 忽略：拿不到 decor")
            return
        }
        val frame = findVisibleVideoFrame(decor)
        if (frame == null) {
            logI("TAP 忽略：没找到可见的视频容器")
            return
        }
        val fid = System.identityHashCode(frame)
        if (fid != tapPausedFrame) {
            // 换了一页 —— 新视频默认是在播的
            tapPausedFrame = fid
            tapPaused = false
        }
        val p = findPlayer(frame)
        if (p == null) {
            logI("TAP 忽略：没找到播放器（多半是广告页）")
            return
        }
        // 不能拿 isPlaying() 当依据：自己 pause() 之后它不一定马上翻转，
        // 结果就是"越点越暂停"、永远恢复不了。所以自己记一份状态。
        val wantPause = !tapPaused
        val ok = if (wantPause) callVoid(p, "pause") else callVoid(p, "start")
        if (ok) tapPaused = wantPause
        logI("TAP -> ${if (wantPause) "pause" else "start"} ok=$ok")
    }

    private fun callVoid(o: Any, name: String): Boolean = try {
        o.javaClass.getMethod(name).invoke(o)
        true
    } catch (_: Throwable) {
        false
    }

    @Volatile
    private var tapTakeoverDone = false

    @Volatile
    private var tapTakeoverAt = 0L

    /** 首页的 view 是懒加载的，等它真的出现再装 hook */
    private fun retryTapTakeover() {
        if (tapTakeoverDone) return
        val now = SystemClock.uptimeMillis()
        if (now - tapTakeoverAt < 2000L) return
        tapTakeoverAt = now
        if (installTapTakeover()) tapTakeoverDone = true
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

    /**
     * 评论区之类的浮层有没有开着。
     *
     * 判据：容器在视图树里、且真的占了屏幕（`isShown` + 高度够）。
     * 这些容器平时也存在于树里，只是 GONE 或高度为 0，所以不能只看"找不找得到"。
     */
    private fun isOverlayOpen(decor: View): Boolean {
        val ids = config.overlayIds()
        if (ids.isEmpty()) return false
        val out = ArrayList<View>(2)
        collectAnyById(decor, ids, out)
        if (out.isEmpty()) return false
        val screenH = decor.resources.displayMetrics.heightPixels
        for (v in out) {
            if (v.isShown && v.height > screenH * 0.2f) return true
        }
        return false
    }

    private fun collectAnyById(v: View, ids: IntArray, out: MutableList<View>) {
        val id = v.id
        for (i in ids.indices) {
            if (ids[i] == id) {
                out.add(v)
                break
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectAnyById(v.getChildAt(i), ids, out)
        }
    }

    // ------------------------------------------------ 播放器实例发现（反射）

    /** 宿主播放器可能实现的接口（`getVideoWidth`/`getVideoHeight`/`getDuration` 这些方法名没有被混淆） */
    private val PLAYER_IFACES = arrayOf(
        "com.kwai.framework.player.core.b",
        "com.kwai.video.wayne.player.main.IWaynePlayer",
        "com.kwai.video.wayne.player.main.IMediaPlayerApi",
        "com.kwai.video.player.IMediaPlayer",
        "com.kwai.video.player.IKwaiMediaPlayer",
    )

    /** 对象图遍历时不再深入的包前缀 */
    private val SKIP_PREFIX = arrayOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.",
        "dalvik.", "libcore.", "sun.", "com.android.internal.",
    )

    /**
     * 找当前视频对应的播放器实例。
     *
     * 播放器藏在 PlayerKit 的私有字段里（`KwaiPlayerKitView.b/c` 是 Kotlin `Lazy`），
     * 而类名/字段名都被混淆过，所以这里做两件事：
     *  1) 走 `KwaiPlayerKitContext` 的服务定位器；
     *  2) 从 `slide_playerkit_view` 出发做**有界**对象图遍历，找实现了播放器接口的对象。
     *
     * 接口名和 `seekTo` / `getDuration` / `getCurrentPosition` 这几个方法名都是稳定的
     * （见 classes17.dex 的 `com.kwai.framework.player.core.b`）。
     */
    private fun findPlayer(container: View): Any? {
        val start = findById(container, Targets.ID_SLIDE_PLAYERKIT_VIEW) ?: container
        // 1) 服务定位器
        try {
            val ctx = start.javaClass.getMethod("getPlayerKitContext").invoke(start)
            if (ctx != null) {
                for (name in arrayOf("k", "f")) {
                    for (iface in PLAYER_IFACES) {
                        val cls = try {
                            Class.forName(iface)
                        } catch (_: Throwable) {
                            null
                        } ?: continue
                        val o = try {
                            ctx.javaClass.getMethod(name, Class::class.java).invoke(ctx, cls)
                        } catch (_: Throwable) {
                            null
                        }
                        if (o != null && isPlayer(o)) return o
                    }
                }
            }
        } catch (_: Throwable) {
        }
        // 2) 有界对象图遍历
        return bfsForPlayer(start)
    }

    private fun bfsForPlayer(start: Any): Any? {
        val found = ArrayList<Any>(4)
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        seen[start] = true
        var level = ArrayList<Any>(1)
        level.add(start)
        for (depth in 0 until 6) {
            val next = ArrayList<Any>(64)
            for (o in level) {
                if (isPlayer(o) && found.size < 4) found.add(o)
                if (found.size >= 4) break
                if (o is Collection<*>) {
                    for (e in o) {
                        val u = e ?: continue
                        if (seen.put(u, true) != null) continue
                        if (isPlayer(u)) {
                            if (found.size < 4) found.add(u)
                        } else if (!skippable(u)) {
                            next.add(u)
                        }
                    }
                    continue
                }
                if (o is Map<*, *>) {
                    for (e in o.values) {
                        val u = e ?: continue
                        if (seen.put(u, true) != null) continue
                        if (isPlayer(u)) {
                            if (found.size < 4) found.add(u)
                        } else if (!skippable(u)) {
                            next.add(u)
                        }
                    }
                    continue
                }
                for (f in fieldsOf(o.javaClass)) {
                    val raw = try {
                        f.isAccessible = true
                        f.get(o)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    val u = unwrapLazy(raw) ?: raw
                    if (u === o) continue
                    if (seen.put(u, true) != null) continue
                    if (isPlayer(u)) {
                        if (found.size < 4) found.add(u)
                    } else if (!skippable(u)) {
                        next.add(u)
                    }
                }
                if (found.size >= 4) break
            }
            if (found.isNotEmpty() || next.isEmpty() || next.size > 2000) break
            level = next
        }
        if (found.isEmpty()) return null
        // 多个候选时选「时长最大、且正在播放」的那个（预加载播放器时长可能为 0）
        var best: Any? = null
        var bestScore = Long.MIN_VALUE
        for (p in found) {
            val d = callLong(p, "getDuration") ?: 0L
            val playing = callBoolean(p, "isPlaying")
            val score = d + if (playing) 1_000_000_000_000L else 0L
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    private fun fieldsOf(c: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>(16)
        var cur: Class<*>? = c
        var guard = 0
        while (cur != null && cur != Any::class.java && guard++ < 8) {
            for (f in cur.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) continue
                out.add(f)
            }
            cur = cur.superclass
        }
        return out
    }

    /** Kotlin `Lazy`（快手里混淆成 `o3n/w`）—— 只在已初始化时取值，避免触发副作用 */
    private fun unwrapLazy(o: Any): Any? {
        val c = o.javaClass
        return try {
            val init = c.getMethod("isInitialized").invoke(o) as? Boolean ?: return null
            if (!init) return null
            c.getMethod("getValue").invoke(o)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isPlayer(o: Any): Boolean {
        if (o is View || o is String) return false
        return implementsAny(o.javaClass, PLAYER_IFACES, 0)
    }

    private fun implementsAny(c: Class<*>, names: Array<String>, depth: Int): Boolean {
        if (depth > 6) return false
        for (n in names) if (n == c.name) return true
        for (i in c.interfaces) if (implementsAny(i, names, depth + 1)) return true
        val s = c.superclass ?: return false
        return implementsAny(s, names, depth + 1)
    }

    /** 不进入这些对象的字段：系统/框架对象图太大，播放器不会藏在里面 */
    private fun skippable(o: Any): Boolean {
        if (o is View) return true
        if (o is android.content.Context) return true
        if (o is android.content.res.Resources) return true
        val n = o.javaClass.name
        for (p in SKIP_PREFIX) if (n.startsWith(p)) return true
        return false
    }

    private fun callLong(o: Any, name: String): Long? = try {
        (o.javaClass.getMethod(name).invoke(o) as? Number)?.toLong()
    } catch (_: Throwable) {
        null
    }

    private fun callBoolean(o: Any, name: String): Boolean = try {
        (o.javaClass.getMethod(name).invoke(o) as? Boolean) ?: false
    } catch (_: Throwable) {
        false
    }

    private fun findById(root: View, id: Int): View? = try {
        root.findViewById(id)
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------- 沉浸没生效 ⇒ 自动划走

    /** 当前这一页的标识（可见视频画面的实例），换页时会变 */
    @Volatile
    private var pageId = 0

    /** 这一页是否已经判定过 —— **每个视频只判定一次**，绝不反复划走 */
    @Volatile
    private var pageChecked = false

    /** 画面停稳的时刻（0 = 还在动） */
    @Volatile
    private var pageSettledAt = 0L

    /** 上一次看到的画面几何（纵向位置 + 高度），用来判断画面有没有停稳 */
    @Volatile
    private var lastFrameY = Int.MIN_VALUE

    @Volatile
    private var lastFrameH = -1

    /** 上一次注入手势的时间，兜底限流 */
    @Volatile
    private var lastSwipeAt = 0L

    @Volatile
    private var lastImmersiveReport = ""

    @Volatile
    private var lastImmersiveProbeAt = 0L

    @Volatile
    private var lastDumpedPage: java.lang.ref.WeakReference<View>? = null

    @Volatile
    private var lastMismatchNote = ""

    /** 是否已经见过至少一个"生效"的视频页 —— 用来确认我们确实在信息流里 */
    @Volatile
    private var seenMatchedOnce = false

    /** 画面停稳多久之后才判定，给页面渲染留时间 */
    private val settleMs = 1800L

    private val swipeCount = AtomicInteger(0)

    /** 一共判定过多少页 —— 正常情况下应该等于"看过的视频数"，每个视频只 +1 */
    private val judgeCount = AtomicInteger(0)

    /**
     * 当前**可见页**上、且真的落在屏幕里的目标控件。
     *
     * ViewPager 会缓存前后各一页，那些回收页的 `isShown()` 仍然是 true，
     * 所以必须再用屏幕坐标过滤一次，否则回收页上被压暗的右侧栏会让我们误判"这一页生效了"。
     */
    /**
     * 当前**可见页**的根容器：从可见的视频画面往上找到 SlidePlayVerticalRelativeLayout。
     * 这样就能把范围锁死在真正在播的那一页，不受回收页干扰。
     */
    private fun findPageRoot(decor: View): View? {
        val frame = findVisibleVideoFrame(decor) ?: return null
        var v: View? = frame
        var guard = 0
        while (v != null && guard++ < 24) {
            if (v.id == Targets.ID_PAGE_ROOT) return v
            if (v.javaClass.name == Targets.CLASS_PAGE_ROOT) return v
            val p = v.parent
            v = if (p is View) p else null
        }
        return null
    }

    /**
     * 屏幕上真正看得见的目标控件。
     *
     * 判据只有一条：控件至少有一半落在屏幕里。
     * ViewPager 会同时挂着前后各一页，那些回收页的 `isShown()` 依然是 true、
     * alpha 也还是我们压过的 0.1，只有屏幕坐标能把它们区分出来。
     */
    private fun visibleTargets(decor: View): ArrayList<View> {
        val screenH = decor.resources.displayMetrics.heightPixels
        val found = ArrayList<View>(16)
        collectAnyById(decor, config.targetIds(), found)
        val out = ArrayList<View>(8)
        val loc = IntArray(2)
        for (v in found) {
            if (!v.isShown || v.height <= 0) continue
            v.getLocationOnScreen(loc)
            val onScreen = minOf(loc[1] + v.height, screenH) - maxOf(loc[1], 0)
            if (onScreen < v.height * 0.5f) continue
            out.add(v)
        }
        return out
    }

    /** 诊断：把可见页里带 id 的控件打出来（每个视频页只打一次） */
    private fun dumpPageOnce(decor: View) {
        val page = findPageRoot(decor) ?: return
        if (lastDumpedPage?.get() === page) return
        lastDumpedPage = java.lang.ref.WeakReference(page)
        val out = ArrayList<String>(256)
        walkIds(page, out, 0)
        logI("PAGEDUMP root=${page.javaClass.name} lines=${out.size}")
        for (l in out) logI("PAGEDUMP $l")
    }

    private fun walkIds(v: View, out: MutableList<String>, depth: Int) {
        if (out.size >= 300 || depth > 40) return
        if (v.id != 0 || v is ViewGroup && v.childCount == 0) {
            out.add(
                "id=${hex(v.id)} a=${v.alpha} vis=${v.visibility} ${v.width}x${v.height} " +
                    "d=$depth cls=${v.javaClass.name}"
            )
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkIds(v.getChildAt(i), out, depth + 1)
        }
    }

    /**
     * 判断当前这一页有没有「匹配上」：只要可见页上**有任意一个目标控件**
     * 处于我们设定的透明度，就算生效。
     *
     * 广告页用的不是我们认得的那套右侧栏/底部信息区控件，所以这里会是 false。
     */
    private fun immersiveOk(decor: View): Boolean {
        for (v in visibleTargets(decor)) {
            if (v.alpha <= config.alpha + 0.05f) return true
        }
        return false
    }

    private fun immersiveTick(decor: View) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastImmersiveProbeAt < 300L) return
        lastImmersiveProbeAt = now

        // 诊断：把「可见页」上的目标控件状态记下来（变化时才打）
        if (config.debugDumpTree) try {
            val vis = visibleTargets(decor)
            val all = ArrayList<View>(16)
            collectAnyById(decor, config.targetIds(), all)
            val screenH = decor.resources.displayMetrics.heightPixels
            val frame = findVisibleVideoFrame(decor)
            val fl = IntArray(2)
            val frameInfo = if (frame == null) "frame=null screen=$screenH" else {
                frame.getLocationOnScreen(fl)
                "frame=y${fl[1]}..${fl[1] + frame.height} screen=$screenH"
            }
            val page = findPageRoot(decor)
            val pageInfo = if (page == null) "page=null" else "page=${hex(page.id)}/${page.javaClass.simpleName}"
            val loc = IntArray(2)
            val sb = StringBuilder()
            for (v in vis) {
                v.getLocationOnScreen(loc)
                sb.append('[').append(hex(v.id))
                    .append(" a=").append(v.alpha)
                    .append(" y=").append(loc[1])
                    .append(' ').append(v.width).append('x').append(v.height)
                    .append(" cls=").append(v.javaClass.simpleName)
                    .append("] ")
            }
            val s = if (sb.isEmpty()) "可见页上没有目标控件" else sb.toString()
            val full = "$frameInfo $pageInfo | $s （可见页 ${vis.size} / 全树 ${all.size}）"
            if (full != lastImmersiveReport) {
                lastImmersiveReport = full
                logI("MATCH: $full")
            }
        } catch (t: Throwable) {
            logI("MATCH 诊断异常: $t")
        }

        if (config.debugDumpTree) try {
            dumpPageOnce(decor)
        } catch (t: Throwable) {
            logI("PAGEDUMP 异常: $t")
        }

        if (!config.autoSkipUnmatched) return

        // 评论区（或其它浮层）开着的时候，信息流那一页会被盖住/隐藏，
        // "屏幕上没有生效的目标控件"这条判据会把评论区误判成广告页。
        // 所以先确认浮层没开 —— 开着就什么都不做，等关掉再说。
        if (isOverlayOpen(decor)) {
            pageSettledAt = 0L
            noteBlock("评论区等浮层开着")
            return
        }

        // 只对"确实是一个整页视频"的页面动手，避免在消息/我等非视频页面乱滑。
        // 注意：不能用播放器是否存在来判断 —— 广告页的播放器拿不到（findPlayer 返回 null），
        // 那样反而会把广告页当成"不是视频页"而永远跳过。
        val frame = try {
            findVisibleVideoFrame(decor)
        } catch (t: Throwable) {
            noteBlock("异常 ${t.javaClass.name}: ${t.message}")
            return
        }
        val screenH = decor.resources.displayMetrics.heightPixels
        if (frame == null || frame.height < screenH * 0.2f) {
            pageId = 0
            pageChecked = false
            pageSettledAt = 0L
            noteBlock("没有整页视频画面")
            return
        }

        val fid = System.identityHashCode(frame)
        val fl = IntArray(2)
        frame.getLocationOnScreen(fl)
        val y = fl[1]
        val h = frame.height

        // 换了一页（ViewPager 换了实例）→ 重新开始观察
        if (fid != pageId) {
            pageId = fid
            pageChecked = false
            pageSettledAt = 0L
            lastFrameY = y
            lastFrameH = h
            noteBlock("换了一页")
            return
        }
        // 画面还在动（翻页动画 / 打开评论区都会改变画面几何）→ 重新计时。
        // 这一点顺带挡住了评论区：拉开评论时画面会变，计时被重置，
        // 而这一页早就判定过了，所以不会再划走。
        if (y != lastFrameY || h != lastFrameH) {
            lastFrameY = y
            lastFrameH = h
            pageSettledAt = 0L
            noteBlock("画面还在动 (y=$y h=$h)")
            return
        }
        // 这一页已经判定过，之后无论用户怎么操作都不再重复判定
        if (pageChecked) {
            noteBlock("这一页已判定过")
            return
        }
        if (pageSettledAt == 0L) {
            pageSettledAt = now
            noteBlock("等画面停稳")
            return
        }
        if (now - pageSettledAt < settleMs) return

        // ---------------- 画面停稳，这一页只判定这一次 ----------------
        pageChecked = true
        val ok = try {
            immersiveOk(decor)
        } catch (t: Throwable) {
            noteBlock("异常 ${t.javaClass.name}: ${t.message}")
            return
        }
        val judgeNo = judgeCount.incrementAndGet()
        if (ok) {
            seenMatchedOnce = true
            logI("JUDGE #$judgeNo：这一页沉浸已生效，保持不动 (y=$y h=$h)")
            noteBlock("")
            return
        }
        noteBlock("")
        if (!seenMatchedOnce) {
            logI("JUDGE #$judgeNo：这一页没生效，但还没确认过信息流，先不划 (y=$y h=$h)")
            return
        }
        logI("JUDGE #$judgeNo：这一页沉浸没生效，划走 (y=$y h=$h)")
        if (now - lastSwipeAt < 800L) return
        lastSwipeAt = now
        val n = swipeCount.incrementAndGet()
        logI("UNMATCHED #$n：注入上滑手势翻页")
        injectSwipeUp(decor)
    }

    /** 记录"为什么没划走"，只在原因变化时打一条，避免刷屏 */
    private fun noteBlock(reason: String) {
        if (reason == lastMismatchNote) return
        lastMismatchNote = reason
        // 换页 / 浮层这两条是用户能直接感知的状态，始终打；其余细节只在调试开关下打
        val always = reason == "换了一页" || reason.startsWith("评论区")
        if (config.debugDumpTree || always) {
            logI("UNMATCHED 状态：${reason.ifEmpty { "已停稳并判定" }}")
        }
    }

    /**
     * 往视图树里注入一个上滑手势，等价于用户手动上滑。
     *
     * 事件时间必须真实递进，否则宿主的 ViewPager 算不出滑动速度、翻不了页。
     */
    private fun injectSwipeUp(target: View) {
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return
        val x = w / 2f
        val y0 = h * 0.78f
        val y1 = h * 0.22f
        val steps = 10
        val stepMs = 16L
        val t0 = android.os.SystemClock.uptimeMillis()
        val meta = 0
        val down = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, x, y0, meta)
        target.dispatchTouchEvent(down)
        down.recycle()
        for (i in 1..steps) {
            val dt = stepMs * i
            val y = y0 + (y1 - y0) * i / steps
            mainHandler.postDelayed({
                try {
                    val mv = android.view.MotionEvent.obtain(
                        t0, t0 + dt, android.view.MotionEvent.ACTION_MOVE, x, y, meta
                    )
                    target.dispatchTouchEvent(mv)
                    mv.recycle()
                    if (i == steps) {
                        val up = android.view.MotionEvent.obtain(
                            t0, t0 + dt + 8, android.view.MotionEvent.ACTION_UP, x, y1, meta
                        )
                        target.dispatchTouchEvent(up)
                        up.recycle()
                    }
                } catch (_: Throwable) {
                }
            }, dt)
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
