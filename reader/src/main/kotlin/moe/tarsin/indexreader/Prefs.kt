package moe.tarsin.indexreader

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("index_reader", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "").orEmpty()
        set(value) = sp.edit().putString("server", value.trim()).apply()

    var lastTerms: String
        get() = sp.getString("terms", "").orEmpty()
        set(value) = sp.edit().putString("terms", value).apply()

    var fontSize: Float
        get() = sp.getFloat("font_size", 18f)
        set(value) = sp.edit().putFloat("font_size", value).apply()

    var lineHeight: Float
        get() = sp.getFloat("line_height", 1.9f)
        set(value) = sp.edit().putFloat("line_height", value).apply()

    var theme: String
        get() = sp.getString("theme", "dark").orEmpty()
        set(value) = sp.edit().putString("theme", value).apply()

    var font: String
        get() = sp.getString("font", "serif").orEmpty()
        set(value) = sp.edit().putString("font", value).apply()

    fun position(path: String): Int = sp.getInt(key(path), 0)

    fun setPosition(path: String, line: Int) {
        sp.edit().putInt(key(path), line).apply()
    }

    private fun key(path: String) = "pos_" + path.hashCode()
}
