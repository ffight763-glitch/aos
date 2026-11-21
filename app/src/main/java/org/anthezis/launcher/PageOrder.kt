package org.anthezis.launcher

import android.content.Context

/** Order of the horizontal Home sections: "feed", "desk" (all desktops share one slot) and "folders". Default: feed, desk, folders. */
object PageOrder {
    private val all = listOf("feed", "desk", "folders")

    fun read(c: Context): List<String> {
        val saved = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).getString("page_order", "").orEmpty()
            .split(",").filter { it in all }.distinct()
        // Anything missing (older settings, a typo) is put back in its default place, so no section can vanish.
        return if (saved.size == all.size) saved else all
    }

    fun write(c: Context, order: List<String>) {
        c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE).edit().putString("page_order", order.joinToString(",")).apply()
    }
}
