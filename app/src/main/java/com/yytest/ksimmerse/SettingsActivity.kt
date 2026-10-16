package com.yytest.ksimmerse

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 模块设置界面。写入的是模块自己的 SharedPreferences（group = ks_immerse），
 * 被注入的快手进程通过 Vector 的 remote preferences 读取同一个 group。
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

    /** 初始化控件时避免把默认值当成用户修改写回去 */
    private var restoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(ImmerseConfig.PREFS_GROUP, MODE_PRIVATE)

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
        restoring = false
        renderAlpha()

        swEnabled.setOnCheckedChangeListener { _, _ -> persist() }
        cbRight.setOnCheckedChangeListener { _, _ -> persist() }
        cbCaption.setOnCheckedChangeListener { _, _ -> persist() }
        cbDisclaimer.setOnCheckedChangeListener { _, _ -> persist() }
        cbBottomHot.setOnCheckedChangeListener { _, _ -> persist() }
        cbPendant.setOnCheckedChangeListener { _, _ -> persist() }
        cbPinSearch.setOnCheckedChangeListener { _, _ -> persist() }
        cbContainVideo.setOnCheckedChangeListener { _, _ -> persist() }

        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderAlpha()
                if (fromUser) persist()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
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
            .apply()
    }
}
