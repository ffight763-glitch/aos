package org.anthezis.launcher

import android.content.Context
import android.widget.Toast

/**
 * AOS subscriptions: Basic (free), Plus, Ultra. Each feature of the launcher belongs to one plan; a higher plan has
 * everything of the lower ones. No payment yet: picking a plan in Settings > Home > Subscriptions switches it at once.
 */
object AosPlan {
    const val BASIC = 0; const val PLUS = 1; const val ULTRA = 2
    val ids = listOf("basic", "plus", "ultra")
    val names = listOf("Basic", "Plus", "Ultra")

    private fun prefs(c: Context) = c.getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
    fun level(c: Context) = ids.indexOf(prefs(c).getString("aos_plan", "basic")).coerceAtLeast(BASIC)
    fun set(c: Context, level: Int) {
        prefs(c).edit().putString("aos_plan", ids[level.coerceIn(BASIC, ULTRA)]).putBoolean("aos_dirty", true).apply()
        Fx.refresh(c)
    }

    /** Effects of Plus and Ultra (all the others are in Basic). */
    private val effectPlan = mapOf(
        "glass" to PLUS, "wp_blur" to PLUS, "parallax" to PLUS, "depth" to PLUS, "liquid_open" to PLUS, "icon_morph" to PLUS, "spring" to PLUS,
        "liquid_dock" to ULTRA, "dynamic_color" to ULTRA,
    )
    private val pagePlan = mapOf("stack" to PLUS, "cube" to PLUS, "pinwheel" to ULTRA)

    fun effectNeeds(id: String) = effectPlan[id] ?: BASIC
    fun pageNeeds(mode: String) = pagePlan[mode] ?: BASIC
    /** Premium (exclusive) themes come with Plus. */
    fun themeNeeds(style: String) = if (AosStyles.isExclusive(style)) PLUS else BASIC
    /** Own effects for every theme is a Plus feature; on Basic all themes share one set. */
    const val PER_THEME = PLUS

    fun has(c: Context, need: Int) = level(c) >= need

    /** True when allowed; otherwise says which plan is needed. */
    fun check(c: Context, need: Int): Boolean {
        if (has(c, need)) return true
        Toast.makeText(c, "Needs AOS ${names[need]}: Settings > Home > Subscriptions", Toast.LENGTH_SHORT).show()
        return false
    }
}
