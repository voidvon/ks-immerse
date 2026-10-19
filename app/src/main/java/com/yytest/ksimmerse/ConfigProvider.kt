package com.yytest.ksimmerse

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 把模块的设置以**只读 ContentProvider** 的形式暴露给宿主进程，让改设置能实时生效。
 *
 * ### 为什么不用 `getRemotePreferences`
 *
 * 那是 libxposed / LSPosed 提供的官方通道，但这个模块在 Vector 2.2 上实测拿到的是
 * **空对象**（`prefs group=ks_immerse null=false keys=[]`），于是宿主进程里所有开关
 * 都落在代码默认值上 —— 设置界面改了不生效。已经排除过的原因：
 *
 * * `AndroidManifest.xml` 里已经声明 `<meta-data android:name="xposedsharedprefs" .../>`；
 * * 重装 APK、`vector-cli modules disable/enable` 重新加载模块都没用；
 * * 模块的 `shared_prefs/ks_immerse.xml` 确实存在且内容正确；
 * * 想让 root 把该文件改成 world-readable 也不行 —— SELinux 不允许
 *   `chmod` 应用私有目录，而 DAC 上 `/data/data/<pkg>` 是 `drwx------`，
 *   宿主进程（另一个 uid）本来就读不到。
 *
 * ContentProvider 是标准 Android IPC，跨进程跨 uid 都能用，不依赖框架内部实现，
 * 所以这里自己走一条。
 *
 * ### 实时性
 *
 * 模块进程写完 `SharedPreferences` 后调用
 * [SettingsActivity] 里的 `notifyChange`（见 [CONTENT_URI]），
 * 宿主进程里注册的 `ContentObserver` 会立刻收到回调并重新载入配置，
 * 不需要重启宿主。
 *
 * ### 权限
 *
 * `android:exported="true"` 且不声明权限 —— 任何应用都能读到，但读到的只是
 * 「透明度 0.1」这类界面偏好，没有敏感信息。写入一律走模块自己的
 * `SharedPreferences`，本 Provider 的 `insert/update/delete` 全部是空实现。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cur = MatrixCursor(COLUMNS)
        val ctx: Context = context ?: return cur
        val prefs = ctx.getSharedPreferences(ImmerseConfig.PREFS_GROUP, Context.MODE_PRIVATE)
        for ((k, v) in prefs.all) {
            when (v) {
                is Boolean -> cur.addRow(arrayOf<Any?>(k, TYPE_BOOL, if (v) "1" else "0"))
                is Float -> cur.addRow(arrayOf<Any?>(k, TYPE_FLOAT, v.toString()))
                is Int -> cur.addRow(arrayOf<Any?>(k, TYPE_INT, v.toString()))
                is Long -> cur.addRow(arrayOf<Any?>(k, TYPE_LONG, v.toString()))
                is String -> cur.addRow(arrayOf<Any?>(k, TYPE_STRING, v))
                else -> Unit
            }
        }
        return cur
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.ksimmerse.config"
    // 只读：写入一律走模块自己的 SharedPreferences
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.yytest.ksimmerse.config"

        /** 宿主进程按这个 URI 查询 / 监听 */
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/prefs")

        const val TYPE_BOOL = "b"
        const val TYPE_FLOAT = "f"
        const val TYPE_INT = "i"
        const val TYPE_LONG = "l"
        const val TYPE_STRING = "s"

        private val COLUMNS = arrayOf("key", "type", "value")
    }
}
