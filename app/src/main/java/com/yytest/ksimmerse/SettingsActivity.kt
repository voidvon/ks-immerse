package com.yytest.ksimmerse

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 模块设置界面。写入的是模块自己的 SharedPreferences（group = ks_immerse）。
 *
 * 写完立刻通过 [ConfigProvider.CONTENT_URI] 发通知，被注入的宿主进程注册了
 * `ContentObserver`，收到后马上重新载入配置并刷新界面 —— 改设置实时生效，不用重启宿主。
 *
 * （`getRemotePreferences` 那条官方通道在 Vector 2.2 上拿不到数据，见 [ConfigProvider] 的注释。）
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var swEnabled: Switch
    private lateinit var seekAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var cbRight: CheckBox
    private lateinit var cbCaption: CheckBox
    private lateinit var cbDisclaimer: CheckBox
    private lateinit var cbBottomHot: CheckBox
    private lateinit var cbPendant: CheckBox
    private lateinit var cbPinSearch: CheckBox
    private lateinit var cbContainVideo: CheckBox
    private lateinit var cbAutoSkip: CheckBox
    private lateinit var cbKillDoubleTap: CheckBox
    private lateinit var seekAutoSkip: SeekBar
    private lateinit var tvAutoSkipValue: TextView

    /** 初始化控件时避免把默认值当成用户修改写回去 */
    private var restoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(ImmerseConfig.PREFS_GROUP, MODE_PRIVATE)

        // 清理历史上移除过的开关，别让旧值一直留在文件里（读配置时本来就会忽略未知键，
        // 这里只是让落盘内容保持干净）
        prefs.edit().remove("skip_ad").apply()

        swEnabled = findViewById(R.id.sw_enabled)
        seekAlpha = findViewById(R.id.seek_alpha)
        tvAlphaValue = findViewById(R.id.tv_alpha_value)
        cbRight = findViewById(R.id.cb_right_column)
        cbCaption = findViewById(R.id.cb_bottom_caption)
        cbDisclaimer = findViewById(R.id.cb_disclaimer)
        cbBottomHot = findViewById(R.id.cb_bottom_hot)
        cbPendant = findViewById(R.id.cb_pendant)
        cbPinSearch = findViewById(R.id.cb_pin_search)
        cbContainVideo = findViewById(R.id.cb_contain_video)
        cbAutoSkip = findViewById(R.id.cb_auto_skip)
        cbKillDoubleTap = findViewById(R.id.cb_kill_double_tap)
        seekAutoSkip = findViewById(R.id.seek_auto_skip)
        // max 由常量算出来，别写在布局里，免得改了 AUTO_SKIP_MIN/MAX 忘了同步
        seekAutoSkip.max = AUTO_SKIP_MAX_PROGRESS
        tvAutoSkipValue = findViewById(R.id.tv_auto_skip_value)

        restoring = true
        swEnabled.isChecked = prefs.getBoolean(ImmerseConfig.KEY_ENABLED, true)
        seekAlpha.progress = (prefs.getFloat(ImmerseConfig.KEY_ALPHA, ImmerseConfig.DEFAULT_ALPHA) * 100f)
            .toInt().coerceIn(0, 100)
        cbRight.isChecked = prefs.getBoolean(ImmerseConfig.KEY_RIGHT_COLUMN, true)
        cbCaption.isChecked = prefs.getBoolean(ImmerseConfig.KEY_BOTTOM_CAPTION, true)
        cbDisclaimer.isChecked = prefs.getBoolean(ImmerseConfig.KEY_DISCLAIMER, true)
        cbBottomHot.isChecked = prefs.getBoolean(ImmerseConfig.KEY_BOTTOM_HOT, true)
        cbPendant.isChecked = prefs.getBoolean(ImmerseConfig.KEY_PENDANT, true)
        cbPinSearch.isChecked = prefs.getBoolean(ImmerseConfig.KEY_PIN_SEARCH, true)
        cbContainVideo.isChecked = prefs.getBoolean(ImmerseConfig.KEY_CONTAIN_VIDEO, true)
        cbAutoSkip.isChecked = prefs.getBoolean(ImmerseConfig.KEY_AUTO_SKIP_UNMATCHED, true)
        cbKillDoubleTap.isChecked = prefs.getBoolean(ImmerseConfig.KEY_KILL_DOUBLE_TAP, true)
        seekAutoSkip.progress = ((prefs.getInt(
            ImmerseConfig.KEY_AUTO_SKIP_DELAY,
            ImmerseConfig.DEFAULT_AUTO_SKIP_DELAY,
        ) - ImmerseConfig.AUTO_SKIP_MIN) / ImmerseConfig.AUTO_SKIP_STEP).coerceIn(0, AUTO_SKIP_MAX_PROGRESS)
        restoring = false
        renderAlpha()
        renderAutoSkip()

        swEnabled.setOnCheckedChangeListener { _, _ -> persist() }
        cbRight.setOnCheckedChangeListener { _, _ -> persist() }
        cbCaption.setOnCheckedChangeListener { _, _ -> persist() }
        cbDisclaimer.setOnCheckedChangeListener { _, _ -> persist() }
        cbBottomHot.setOnCheckedChangeListener { _, _ -> persist() }
        cbPendant.setOnCheckedChangeListener { _, _ -> persist() }
        cbPinSearch.setOnCheckedChangeListener { _, _ -> persist() }
        cbContainVideo.setOnCheckedChangeListener { _, _ -> persist() }
        cbAutoSkip.setOnCheckedChangeListener { _, _ -> persist() }
        cbKillDoubleTap.setOnCheckedChangeListener { _, _ -> persist() }

        seekAutoSkip.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderAutoSkip()
                if (fromUser) persist()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderAlpha()
                if (fromUser) persist()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    /** SeekBar 的最大进度（0 → AUTO_SKIP_MIN，MAX → AUTO_SKIP_MAX） */
    private val AUTO_SKIP_MAX_PROGRESS =
        (ImmerseConfig.AUTO_SKIP_MAX - ImmerseConfig.AUTO_SKIP_MIN) / ImmerseConfig.AUTO_SKIP_STEP

    /** 当前滑条对应的毫秒数 */
    private fun autoSkipMs(): Int =
        ImmerseConfig.AUTO_SKIP_MIN + seekAutoSkip.progress * ImmerseConfig.AUTO_SKIP_STEP

    private fun renderAutoSkip() {
        tvAutoSkipValue.text = getString(R.string.auto_skip_delay_format, autoSkipMs() / 1000f)
    }

    private fun renderAlpha() {
        val value = seekAlpha.progress / 100f
        tvAlphaValue.text = getString(R.string.alpha_value_format, seekAlpha.progress, value)
    }

    private fun persist() {
        if (restoring) return
        prefs.edit()
            .putBoolean(ImmerseConfig.KEY_ENABLED, swEnabled.isChecked)
            .putFloat(ImmerseConfig.KEY_ALPHA, seekAlpha.progress / 100f)
            .putBoolean(ImmerseConfig.KEY_RIGHT_COLUMN, cbRight.isChecked)
            .putBoolean(ImmerseConfig.KEY_BOTTOM_CAPTION, cbCaption.isChecked)
            .putBoolean(ImmerseConfig.KEY_DISCLAIMER, cbDisclaimer.isChecked)
            .putBoolean(ImmerseConfig.KEY_BOTTOM_HOT, cbBottomHot.isChecked)
            .putBoolean(ImmerseConfig.KEY_PENDANT, cbPendant.isChecked)
            .putBoolean(ImmerseConfig.KEY_PIN_SEARCH, cbPinSearch.isChecked)
            .putBoolean(ImmerseConfig.KEY_CONTAIN_VIDEO, cbContainVideo.isChecked)
            .putBoolean(ImmerseConfig.KEY_AUTO_SKIP_UNMATCHED, cbAutoSkip.isChecked)
            .putBoolean(ImmerseConfig.KEY_KILL_DOUBLE_TAP, cbKillDoubleTap.isChecked)
            .putInt(ImmerseConfig.KEY_AUTO_SKIP_DELAY, autoSkipMs())
            .apply()

        // 通知已经注入到宿主进程的模块：配置变了，重新读一遍。
        // 宿主那边注册了 ContentObserver，收到后立刻重新载入并刷新界面，无需重启宿主。
        // 注意 commit 语义：apply() 是异步落盘，但同进程内读 SharedPreferences 立刻可见，
        // 而 Provider 的 query() 就跑在本进程，所以这里紧接着 notify 是安全的。
        try {
            contentResolver.notifyChange(ConfigProvider.CONTENT_URI, null)
        } catch (t: Throwable) {
            // 通知失败不影响写入本身，宿主下次查询仍能拿到新值
        }
    }
}
