package org.anthezis.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The moving parts of an AOS theme (twinkling stars, lighthouse beam, owl blink, petals, fireflies, smoke, embers), drawn over
 * the 1080x2400 scene. Shared by the live wallpaper service and by [SceneFx], the in-launcher layer for phones whose system has
 * no live-wallpaper picker.
 */
internal class SceneAnimator {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = Array(70) { i -> floatArrayOf((i * 197 % 1080).toFloat(), (i * 331 % 1300).toFloat(), (i % 7).toFloat()) }
    private var beam: LinearGradient? = null

    private fun twinkle(c: Canvas, t: Float, maxY: Float, dark: Boolean) {
        if (!dark) return
        for (s in stars) {
            if (s[1] > maxY) continue
            paint.color = Color.argb((110 + 100 * sin(t * (1.2f + s[2] * 0.25f) + s[0])).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawCircle(s[0], s[1], 2.4f, paint)
        }
    }

    private fun glowDot(c: Canvas, x: Float, y: Float, r: Float, rgb: Int, a: Int) {
        paint.color = Color.argb(a / 4, Color.red(rgb), Color.green(rgb), Color.blue(rgb)); c.drawCircle(x, y, r * 3.2f, paint)
        paint.color = Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb)); c.drawCircle(x, y, r, paint)
    }

    private fun fireflies(c: Canvas, t: Float, n: Int, rgb: Int, top: Float, dark: Boolean) {
        if (!dark) return
        for (i in 0 until n) {
            val x = 80f + (i * 173 % 920) + 46 * sin(t * 0.5f + i)
            val y = top + (i * 97 % 640) + 30 * cos(t * 0.4f + i * 2f)
            glowDot(c, x, y, 5f, rgb, (150 + 100 * sin(t * 2f + i)).toInt().coerceIn(30, 255))
        }
    }

    /** A few birds gliding across the sky, wings flapping. */
    private fun birds(c: Canvas, t: Float, dark: Boolean) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.4f; paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (dark) Color.argb(150, 200, 210, 235) else Color.argb(190, 40, 50, 70)
        for (i in 0 until 4) {
            val x = ((t * (38f + i * 11f) + i * 330f) % 1500f) - 200f
            val y = 380f + i * 120f + 16f * sin(t * 1.3f + i)
            val flap = 14f * sin(t * 7f + i * 2f)
            val path = Path().apply { moveTo(x - 26f, y + flap * 0.4f); quadTo(x - 12f, y - 12f - flap, x, y); quadTo(x + 12f, y - 12f - flap, x + 26f, y + flap * 0.4f) }
            c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** Fish swimming at different depths. */
    private fun fish(c: Canvas, t: Float, dark: Boolean) {
        for (i in 0 until 6) {
            val dir = if (i % 2 == 0) 1f else -1f
            val sp = 40f + i * 14f
            val x = if (dir > 0) ((t * sp + i * 260f) % 1500f) - 200f else 1300f - ((t * sp + i * 260f) % 1500f)
            val y = 1000f + i * 190f + 18f * sin(t * 1.1f + i)
            paint.color = Color.argb(if (dark) 150 else 190, if (dark) 120 else 250, if (dark) 220 else 170, if (dark) 255 else 90)
            c.save(); c.scale(dir, 1f, x, y)
            c.drawOval(RectF(x - 34f, y - 14f, x + 34f, y + 14f), paint)
            c.drawPath(Path().apply { moveTo(x - 30f, y); lineTo(x - 54f, y - 16f); lineTo(x - 54f, y + 16f); close() }, paint)
            c.restore()
        }
    }

    /** Butterflies flutter over the meadow in lazy loops. */
    private fun butterflies(c: Canvas, t: Float) {
        for (i in 0 until 5) {
            val x = 160f + i * 190f + 70f * sin(t * 0.4f + i * 2f)
            val y = 1750f + (i % 3) * 120f + 50f * sin(t * 0.7f + i)
            val flap = 0.35f + 0.65f * Math.abs(sin(t * 9f + i))
            paint.color = Color.argb(230, 255, 140 + (i * 30) % 100, 190 - (i * 20) % 90)
            c.drawOval(RectF(x - 18f * flap, y - 10f, x, y + 10f), paint); c.drawOval(RectF(x, y - 10f, x + 18f * flap, y + 10f), paint)
        }
    }

    /** Wave lines sliding over the sea or sky of the wave themes. */
    private fun waveLines(c: Canvas, t: Float, dark: Boolean) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        for (k in 0 until 4) {
            paint.color = Color.argb(if (dark) 50 else 70, 255, 255, 255)
            val path = Path(); val base = 1250f + k * 220f
            var x = 0f; path.moveTo(0f, base)
            while (x <= 1080f) { val ang = (x / 1080f * 2.0 * Math.PI + t * (0.5f + k * 0.12f)).toFloat(); path.lineTo(x, base + 40f * sin(ang)); x += 20f }
            c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** Headlights and tail lights of cars driving through the neon street. */
    private fun cars(c: Canvas, t: Float) {
        for (i in 0 until 5) {
            val dir = if (i % 2 == 0) 1f else -1f
            val x = if (dir > 0) ((t * (160f + i * 30f) + i * 250f) % 1400f) - 160f else 1240f - ((t * (150f + i * 28f) + i * 250f) % 1400f)
            val y = 2180f + (i % 3) * 40f
            glowDot(c, x, y, 5f, if (dir > 0) 0xFF3D3D else 0xFFF2B0, 220)
            glowDot(c, x + dir * 26f, y, 5f, if (dir > 0) 0xFF3D3D else 0xFFF2B0, 220)
        }
    }
    /** Soft clouds drifting across the sky. */
    private fun clouds(c: Canvas, t: Float, alpha: Int) {
        paint.color = Color.argb(alpha, 255, 255, 255)
        for (i in 0 until 6) {
            val x = ((t * (9f + i * 4f) + i * 260f) % 1500f) - 300f
            val y = 280f + i * 130f
            c.drawOval(RectF(x, y, x + 320f, y + 70f), paint); c.drawOval(RectF(x + 60f, y - 30f, x + 220f, y + 50f), paint)
        }
    }

    /** Snowflakes drifting down and swaying. */
    private fun snowfall(c: Canvas, t: Float, n: Int, alpha: Int, speed: Float = 60f) {
        paint.color = Color.argb(alpha, 255, 255, 255)
        for (i in 0 until n) {
            val y = ((t * (speed + (i % 5) * 14f) + i * 173f) % 2600f) - 100f
            val x = (i * 151 % 1080) + 26f * sin(t * 0.8f + i)
            c.drawCircle(x, y, 3f + (i % 4) * 1.6f, paint)
        }
    }

    /** Sparks rising from a point, fading as they climb. */
    private fun embers(c: Canvas, t: Float, cx: Float, cy: Float, spread: Float, rgb: Int, n: Int) {
        for (i in 0 until n) {
            val life = ((t * (0.35f + (i % 5) * 0.06f) + i * 0.137f) % 1f)
            val x = cx + spread * sin(i * 2.3f) * (0.3f + life) + 18f * sin(t * 2f + i)
            val y = cy - life * 900f
            glowDot(c, x, y, 3.5f, rgb, (230 * (1f - life)).toInt().coerceIn(0, 255))
        }
    }

    /** Glints that flash on and off at fixed places (ice, gold dust). */
    private fun sparkles(c: Canvas, t: Float, n: Int, rgb: Int, top: Float, bottom: Float) {
        for (i in 0 until n) {
            val a = sin(t * (1.4f + (i % 4) * 0.3f) + i * 1.9f)
            if (a < 0.55f) continue
            val x = (i * 211 % 1040) + 20f; val y = top + (i * 137 % (bottom - top).toInt())
            val r = 10f * (a - 0.55f) / 0.45f + 2f
            paint.color = Color.argb((255 * (a - 0.4f)).toInt().coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            paint.strokeWidth = 2.4f; paint.style = Paint.Style.STROKE
            c.drawLine(x - r * 2, y, x + r * 2, y, paint); c.drawLine(x, y - r * 2, x, y + r * 2, paint)
            paint.style = Paint.Style.FILL; c.drawCircle(x, y, r * 0.5f, paint)
        }
    }

    /** A shooting star every [period] seconds, from the top right down to the left. */
    private fun shootingStar(c: Canvas, t: Float, period: Float, alpha: Int) {
        val ph = (t % period) / 1.1f
        if (ph >= 1f) return
        val x = 960f - ph * 760f; val y = 200f + ph * 520f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
        paint.shader = LinearGradient(x, y, x + 170f, y - 112f, Color.argb(alpha, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawLine(x, y, x + 170f, y - 112f, paint)
        paint.shader = null; paint.style = Paint.Style.FILL
    }

    /** Slow rays fanning out from the sun (dawn). */
    private fun sunRays(c: Canvas, t: Float, cx: Float, cy: Float, rgb: Int, alpha: Int) {
        for (i in 0 until 9) {
            val ang = (-Math.PI * 0.95 + i * Math.PI * 0.9 / 8 + 0.04 * sin(t * 0.3f + i)).toFloat()
            val len = 1300f + 120f * sin(t * 0.6f + i * 1.3f)
            val a = (alpha * (0.6f + 0.4f * sin(t * 0.9f + i))).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 46f; paint.strokeCap = Paint.Cap.ROUND
            paint.shader = LinearGradient(cx, cy, cx + len * cos(ang), cy + len * sin(ang),
                Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Shader.TileMode.CLAMP)
            c.drawLine(cx, cy, cx + len * cos(ang), cy + len * sin(ang), paint)
        }
        paint.shader = null; paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    /** Bokeh in the theme's own colours: soft discs that float up, sway and breathe. */
    private fun bokeh(c: Canvas, t: Float, colors: IntArray, dark: Boolean) {
        for (i in 0 until 18) {
            val rgb = colors[i % colors.size]
            val y = 2600f - ((t * (26f + i * 5f) + i * 211f) % 2800f)
            val x = (i * 131 % 1000) + 40f + 34f * sin(t * 0.5f + i)
            val r = 22f + (i % 5) * 13f + 5f * sin(t * 1.2f + i)
            val a = (if (dark) 70 else 90) + (30 * sin(t * 0.9f + i * 1.7f)).toInt()
            paint.color = Color.argb((a / 3).coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb)); c.drawCircle(x, y, r * 1.8f, paint)
            paint.color = Color.argb(a.coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb)); c.drawCircle(x, y, r, paint)
        }
    }

    /** Flat themes: a slow light sweeping across the colour and a few soft specks drifting up. */
    private fun flat(c: Canvas, t: Float, dark: Boolean) {
        val sx = ((t * 90f) % 2600f) - 700f
        paint.shader = LinearGradient(sx, 0f, sx + 700f, 2400f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(if (dark) 26 else 40, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1080f, 2400f, paint); paint.shader = null
        for (i in 0 until 14) {
            val y = 2500f - ((t * (18f + (i % 4) * 7f) + i * 190f) % 2700f)
            val x = (i * 167 % 1040) + 20f + 20f * sin(t * 0.6f + i)
            paint.color = Color.argb(if (dark) 46 else 60, 255, 255, 255)
            c.drawCircle(x, y, 6f + (i % 3) * 5f, paint)
        }
    }

    /** Draws one frame of [style] at time [t] seconds on a canvas already scaled to 1080x2400. */
    fun draw(c: Canvas, t: Float, style: String, dark: Boolean) {
        paint.shader = null; paint.style = Paint.Style.FILL
        if (style.startsWith("flat_")) { flat(c, t, dark); return }
        when (style) {
            "forestowl" -> {
                twinkle(c, t, 900f, dark)
                fireflies(c, t, 16, 0xFFE27A, 1500f, dark)
                // The owl blinks every few seconds: lids in the colour of its face discs over the eyes.
                if (t % 4.6f < 0.16f) {
                    paint.color = Color.parseColor(if (dark) "#5A4632" else "#B79A78")
                    for (dx in listOf(-44f, 44f)) c.drawCircle(410f + dx, 1030f, 38f, paint)
                }
            }
            "blue" -> {
                twinkle(c, t, 1200f, dark)
                val lx = 432f; val ly = 1058f
                if (dark) {
                    // The lighthouse beam sweeps slowly over the sea, the lamp breathes.
                    val g = beam ?: LinearGradient(0f, 0f, 820f, 0f, Color.argb(130, 255, 214, 120), Color.argb(0, 255, 214, 120), Shader.TileMode.CLAMP).also { beam = it }
                    c.save(); c.translate(lx, ly); c.rotate((sin(t * 0.55f) * 16f))
                    paint.shader = g
                    c.drawPath(Path().apply { moveTo(0f, -14f); lineTo(820f, -170f); lineTo(820f, 150f); lineTo(0f, 14f); close() }, paint)
                    paint.shader = null; c.restore()
                    glowDot(c, lx, ly, 34f + 6 * sin(t * 1.6f), 0xFFD66B, 120)
                }
                // Light shimmer on the water.
                paint.color = Color.argb(if (dark) 40 else 70, 255, 255, 255)
                for (i in 0 until 10) { val y = 1700f + i * 62f; val x = 80f + (i * 131 % 700) + 28 * sin(t * 0.8f + i); c.drawRoundRect(RectF(x, y, x + 150f, y + 5f), 3f, 3f, paint) }
            }
            "aurora" -> {
                twinkle(c, t, 1000f, dark)
                // A soft band of light drifts across the ribbons.
                val y = 560f + 120 * sin(t * 0.35f)
                paint.shader = LinearGradient(0f, y, 0f, y + 340f, Color.argb(0, 80, 230, 190), Color.argb(if (dark) 60 else 40, 80, 230, 190), Shader.TileMode.CLAMP)
                c.drawRect(0f, y, 1080f, y + 340f, paint); paint.shader = null
            }
            "ember" -> {
                // Embers rise from the dunes.
                for (i in 0 until 26) {
                    val y = 1700f - ((t * 46f + i * 83f) % 1400f)
                    val x = (i * 211 % 1080) + 34 * sin(t * 0.7f + i)
                    glowDot(c, x, y, 3.5f, 0xFFB066, (200 * (y / 1700f)).toInt().coerceIn(20, 220))
                }
                // The sun breathes a little.
                glowDot(c, 540f, 1248f, 230f + 8 * sin(t * 0.9f), 0xFFA050, if (dark) 22 else 16)
            }
            "forest" -> {
                twinkle(c, t, 900f, dark)
                fireflies(c, t, 14, 0xD8F27A, 1450f, dark)
                // Smoke from the cabin chimney.
                for (i in 0 until 6) {
                    val p = ((t * 0.18f + i / 6f) % 1f)
                    paint.color = Color.argb((90 * (1 - p)).toInt(), 235, 240, 238)
                    c.drawCircle(695f + 70 * p + 10 * sin(t + i), 1488f - 360 * p, 18f + 46 * p, paint)
                }
            }
            "rose" -> {
                twinkle(c, t, 800f, dark)
                // Petals drift down while swaying.
                for (i in 0 until 34) {
                    val x = (i * 131 % 1080) + 60 * sin(t * 0.6f + i)
                    val y = ((t * 58f + i * 91f) % 2600f) - 100f
                    c.save(); c.translate(x, y); c.rotate(40 * sin(t * 1.1f + i) + i * 11f)
                    paint.color = Color.argb(215, 255, 150 + (i % 3) * 25, 190 + (i % 4) * 12)
                    c.drawOval(RectF(-13f, -7f, 13f, 7f), paint); c.restore()
                }
            }
            "neon" -> {
                cars(c, t)
                // Neon signs flicker, rain streaks fall at a slant.
                for ((i, s) in listOf(Triple(220f, 1560f, 0xFF3DDC), Triple(620f, 1470f, 0x3DE0FF), Triple(880f, 1640f, 0xFFD23D)).withIndex()) {
                    val flick = if (sin(t * (7f + i * 3f) + i) > 0.92f) 0.35f else 1f
                    glowDot(c, s.first, s.second, 16f, s.third, (170 * flick).toInt())
                }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f
                paint.color = Color.argb(if (dark) 110 else 80, 220, 235, 255)
                for (i in 0 until 70) {
                    val x = (i * 157 % 1200) - 60f; val y = ((t * 950f + i * 131f) % 2600f) - 100f
                    c.drawLine(x, y, x - 18f, y + 64f, paint)
                }
                paint.style = Paint.Style.FILL
            }
            "snow" -> {
                twinkle(c, t, 700f, dark)
                paint.color = Color.argb(if (dark) 230 else 255, 255, 255, 255)
                for (i in 0 until 90) {
                    val r = 2.2f + (i % 4) * 1.5f
                    val y = ((t * (36f + (i % 5) * 20f) + i * 53f) % 2500f) - 50f
                    val x = (i * 97 % 1080) + 34f * sin(t * 0.6f + i)
                    if (!dark) paint.color = Color.argb(235, 255, 255, 255)
                    c.drawCircle(x, y, r, paint)
                }
            }
            "sunrise", "lagoon" -> waveLines(c, t, dark)
            "synth" -> {
                glowDot(c, 540f, 1180f, 300f + 10f * sin(t * 1.2f), 0xFF4F8B, if (dark) 22 else 14)
                twinkle(c, t, 800f, dark)
                // The grid scrolls toward the viewer: horizontal lines speed up as they get closer.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                paint.color = Color.argb(if (dark) 200 else 170, if (dark) 255 else 255, if (dark) 79 else 231, if (dark) 216 else 250)
                for (i in 0 until 12) {
                    val ph = ((t * 0.45f + i / 12f) % 1f)
                    val y = 1500f + 900f * ph * ph
                    c.drawLine(0f, y, 1080f, y, paint)
                }
                paint.style = Paint.Style.FILL
            }
            "ocean" -> {
                fish(c, t, dark)
                // Bubbles rise and sway; the light rays breathe.
                paint.color = Color.argb(if (dark) 120 else 170, 255, 255, 255)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f
                for (i in 0 until 38) {
                    val y = 2300f - ((t * (50f + (i % 6) * 14f) + i * 97f) % 2400f)
                    val x = (i * 211 % 1000) + 40f + 22f * sin(t * 0.9f + i)
                    c.drawCircle(x, y, 5f + (i % 5) * 3.2f, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(((if (dark) 18 else 26) * (0.5f + 0.5f * sin(t * 0.5f))).toInt(), 255, 255, 255)
                c.drawRect(0f, 0f, 1080f, 1700f, paint)
            }
            "matrix" -> {
                // Falling code: a bright head with a fading green tail, one column per slot.
                for (i in 0 until 30) {
                    val speed = 260f + (i * 37 % 200)
                    val head = ((t * speed + i * 211f) % 3000f) - 200f
                    val x = 10f + i * 36f
                    for (k in 0 until 14) {
                        val y = head - k * 58f
                        if (y < -40f || y > 2440f) continue
                        val a = (255 * (1f - k / 14f)).toInt()
                        paint.color = if (k == 0) Color.argb(255, 220, 255, 230) else if (dark) Color.argb(a, 40, 255, 120) else Color.argb(a, 20, 130, 60)
                        c.drawRect(x, y, x + 14f, y + 22f, paint)
                    }
                }
            }
            "meadow" -> {
                if (dark) {
                    twinkle(c, t, 800f, true)
                    fireflies(c, t, 18, 0xFFE27A, 1700f, true)
                } else {
                    butterflies(c, t)
                    // Pollen drifts across the field.
                    for (i in 0 until 26) {
                        val x = ((t * (14f + i % 5 * 6f) + i * 83f) % 1180f) - 50f
                        val y = 1500f + (i * 41 % 700) + 26f * sin(t * 0.8f + i)
                        glowDot(c, x, y, 3.2f, 0xFFE27A, 190)
                    }
                }
            }
            "inferno" -> {
                // Sparks fly up from the flames; the sun pulses.
                for (i in 0 until 34) {
                    val y = 2100f - ((t * (60f + (i % 5) * 18f) + i * 77f) % 1700f)
                    val x = (i * 197 % 1080) + 30f * sin(t * 0.9f + i)
                    glowDot(c, x, y, 3.6f, 0xFFC04D, (230 * (y / 2100f)).toInt().coerceIn(30, 230))
                }
                glowDot(c, 540f, 1000f, 190f + 6 * sin(t * 1.1f), 0xFF8A2A, if (dark) 26 else 18)
            }
            "hornet" -> {
                // A bright scan line sweeps down the honeycomb.
                val y = (t * 220f) % 2400f
                paint.shader = LinearGradient(0f, y - 120f, 0f, y + 120f, intArrayOf(Color.argb(0, 255, 200, 0), Color.argb(if (dark) 70 else 90, 255, 200, 0), Color.argb(0, 255, 200, 0)), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, y - 120f, 1080f, y + 120f, paint); paint.shader = null
            }
            "royal" -> {
                // Ripples expand from the middle and fade.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                for (i in 0 until 4) {
                    val ph = ((t * 0.18f + i / 4f) % 1f)
                    paint.color = Color.argb((110 * (1f - ph)).toInt(), 255, 255, 255)
                    c.drawCircle(540f, 1500f, 120f + ph * 900f, paint)
                }
                paint.style = Paint.Style.FILL
                twinkle(c, t, 1000f, dark)
            }
            "sakura" -> {
                twinkle(c, t, 700f, dark)
                // Petals drift down and spin.
                for (i in 0 until 40) {
                    val x = (i * 149 % 1080) + 70f * sin(t * 0.5f + i)
                    val y = ((t * 52f + i * 83f) % 2600f) - 100f
                    c.save(); c.translate(x, y); c.rotate(60 * sin(t * 0.9f + i) + i * 17f)
                    paint.color = Color.argb(225, 255, 170 + (i % 3) * 25, 200 + (i % 4) * 12)
                    c.drawOval(RectF(-12f, -7f, 12f, 7f), paint); c.restore()
                }
            }
            "desert" -> {
                twinkle(c, t, 900f, dark)
                // Sand streaks blow across the dunes.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                paint.color = Color.argb(if (dark) 70 else 100, 255, 230, 180)
                for (i in 0 until 26) {
                    val y = 1500f + (i * 37 % 800)
                    val x = ((t * (180f + i % 5 * 40f) + i * 131f) % 1400f) - 160f
                    c.drawLine(x, y, x + 90f + (i % 4) * 20f, y - 6f, paint)
                }
                paint.style = Paint.Style.FILL
            }
            "jungle" -> {
                // Fine rain at night, drifting mist and fireflies.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f
                paint.color = Color.argb(if (dark) 90 else 70, 220, 255, 235)
                for (i in 0 until 46) {
                    val x = (i * 173 % 1100) - 10f; val y = ((t * 800f + i * 117f) % 2500f) - 80f
                    c.drawLine(x, y, x - 6f, y + 40f, paint)
                }
                paint.style = Paint.Style.FILL
                fireflies(c, t, 16, 0xC8FF7A, 1400f, dark)
            }
            "circuit" -> {
                // Light pulses run along the traces.
                for (i in 0 until 16) {
                    val y = 200f + i * 150f
                    val x = ((t * (180f + (i % 4) * 60f) + i * 173f) % 1300f) - 100f
                    glowDot(c, x, y, 5f, if (dark) 0x38F2FF else 0x0A8FA8, 220)
                }
            }
            // Each mountain scene gets its own weather.
            "alps" -> {
                twinkle(c, t, 800f, dark)
                clouds(c, t, if (dark) 30 else 80)
                snowfall(c, t, 60, if (dark) 170 else 220)
            }
            "volcano" -> {
                embers(c, t, 540f, 1150f, 240f, 0xFF8A3D, 34)
                // The crater breathes: a warm glow that swells and fades.
                glowDot(c, 540f, 1180f, 150f + 20f * sin(t * 1.3f), 0xFF5A1F, if (dark) 40 else 26)
                clouds(c, t, if (dark) 22 else 50)
            }
            "glacier" -> {
                twinkle(c, t, 900f, dark)
                sparkles(c, t, 26, if (dark) 0xBFF4FF else 0xFFFFFF, 900f, 2300f)
                snowfall(c, t, 26, if (dark) 120 else 170, 34f)
            }
            "dawn" -> {
                sunRays(c, t, 540f, 1050f, 0xFFD9A0, if (dark) 40 else 60)
                clouds(c, t, if (dark) 36 else 80)
                birds(c, t, dark)
            }
            "twilight" -> {
                twinkle(c, t, 1500f, true)
                shootingStar(c, t, 5.5f, 230)
                fireflies(c, t, 10, 0xFFD27A, 1600f, true)
            }
            "forest_peaks" -> {
                twinkle(c, t, 800f, dark)
                if (dark) fireflies(c, t, 16, 0xCFFF8A, 1500f, true) else birds(c, t, false)
                for (i in 0 until 3) {   // low mist sliding through the valley
                    val x = ((t * (12f + i * 6f) + i * 420f) % 1900f) - 600f
                    paint.color = Color.argb(if (dark) 26 else 54, 255, 255, 255)
                    c.drawOval(RectF(x, 1700f + i * 170f, x + 1000f, 1700f + i * 170f + 220f), paint)
                }
            }
            // Bokeh themes float circles in their own colours.
            "bokeh_candy" -> bokeh(c, t, intArrayOf(0xFF70C8, 0xB06CFF, 0xFFB8DE), dark)
            "bokeh_gold" -> { bokeh(c, t, intArrayOf(0xFFC53D, 0xFF9F1C, 0xFFE9A8), dark); sparkles(c, t, 16, 0xFFE08A, 200f, 2200f) }
            "bokeh_ice" -> { bokeh(c, t, intArrayOf(0x4FD8FF, 0x3C9BFF, 0xCFF4FF), dark); sparkles(c, t, 12, 0xE6FAFF, 200f, 2200f) }
            "bokeh_mint" -> bokeh(c, t, intArrayOf(0x3DF0B8, 0x2CC9A0, 0xC9FFE9), dark)
            "bokeh_rose" -> bokeh(c, t, intArrayOf(0xFF5C84, 0xE0406A, 0xFFB8C8), dark)
            "storm" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f
                paint.color = Color.argb(if (dark) 120 else 90, 210, 225, 245)
                for (i in 0 until 80) {
                    val x = (i * 139 % 1200) - 60f; val y = ((t * 1400f + i * 97f) % 2600f) - 100f
                    c.drawLine(x, y, x - 26f, y + 70f, paint)
                }
                paint.style = Paint.Style.FILL
                // A flash of lightning every few seconds.
                val ph = t % 6.5f
                if (ph < 0.14f) { paint.color = Color.argb((150 * (1f - ph / 0.14f)).toInt(), 235, 240, 255); c.drawRect(0f, 0f, 1080f, 2400f, paint) }
            }
            "fog" -> {
                // Mist bands drift sideways.
                for (i in 0 until 5) {
                    val x = ((t * (10f + i * 5f) + i * 300f) % 1800f) - 500f
                    paint.color = Color.argb(if (dark) 34 else 60, 255, 255, 255)
                    c.drawOval(RectF(x, 1200f + i * 220f, x + 900f, 1200f + i * 220f + 260f), paint)
                }
            }
            "x_blocks" -> {
                // Blocky clouds glide across the sky.
                paint.color = Color.argb(if (dark) 70 else 230, 255, 255, 255)
                for (i in 0 until 4) {
                    val x = ((t * (14f + i * 6f) + i * 380f) % 1500f) - 300f
                    val y = 200f + i * 210f
                    c.drawRect(x, y, x + 240f, y + 60f, paint); c.drawRect(x + 60f, y - 60f, x + 180f, y, paint)
                }
            }
            "space" -> {
                twinkle(c, t, 2400f, dark)
                // A shooting star crosses the sky every few seconds.
                val ph = (t % 6.5f) / 1.1f
                if (ph < 1f) {
                    val x = 960f - ph * 760f; val y = 220f + ph * 520f
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                    paint.shader = LinearGradient(x, y, x + 170f, y - 112f, Color.argb(if (dark) 255 else 200, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
                    c.drawLine(x, y, x + 170f, y - 112f, paint)
                    paint.shader = null; paint.style = Paint.Style.FILL
                }
                glowDot(c, 760f, 870f, 118f + 4 * sin(t * 0.8f), 0xFFB27A, if (dark) 20 else 14)
            }
            "graphite" -> {
                twinkle(c, t, 800f, dark)
                // A few windows turn on and off slowly.
                for (i in 0 until 22) {
                    val a = (0.5f + 0.5f * sin(t * 0.5f + i * 1.7f))
                    paint.color = Color.argb(((if (dark) 200 else 80) * a).toInt().coerceIn(0, 255), 246, 213, 138)
                    val x = 40f + (i * 233 % 1000); val y = 1500f + (i * 61 % 360)
                    c.drawRect(x, y, x + 12f, y + 18f, paint)
                }
            }
        }
    }
}

/**
 * Animation layer inside the launcher: the same moving parts as the live wallpaper, drawn over the static wallpaper.
 * Works on every phone (Realme has no live-wallpaper picker). Runs only while it is on screen, about 30 frames a second.
 */
internal class SceneFx(ctx: Context, private val style: String, private val dark: Boolean) : View(ctx) {
    private val anim = SceneAnimator()
    private val t0 = SystemClock.uptimeMillis()
    private var running = false

    private val tick = object : Runnable {
        override fun run() { if (!running) return; invalidate(); postDelayed(this, 33) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    fun start() { if (running) return; running = true; post(tick) }
    fun stop() { running = false; removeCallbacks(tick) }

    override fun onDraw(c: Canvas) {
        c.save(); c.scale(width / 1080f, height / 2400f)
        anim.draw(c, (SystemClock.uptimeMillis() - t0) / 1000f, style, dark)
        c.restore()
    }
}
