package app.androplay.admin

import android.content.Context
import org.json.JSONArray

/**
 * The library order the admin arranged by drag and drop. Stored per phone (not synced),
 * so every presenter can keep their own running order.
 */
object LibraryOrder {
    private const val PREFS = "library-order"
    private const val KEY = "ids"

    fun load(ctx: Context): List<String> = runCatching {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, ids: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(ids).toString())
            .apply()
    }

    /**
     * [entries] (newest first) arranged by [order]: ids not in [order] (new items) come first,
     * keeping their newest-first order, then the rest in the saved order.
     */
    fun arrange(entries: List<Entry>, order: List<String>): List<Entry> {
        val rank = HashMap<String, Int>(order.size * 2).apply { order.forEachIndexed { i, id -> putIfAbsent(id, i) } }
        val (known, fresh) = entries.partition { it.id in rank }
        return fresh + known.sortedBy { rank.getValue(it.id) }
    }
}
