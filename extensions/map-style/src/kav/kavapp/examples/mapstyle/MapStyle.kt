package kav.kavapp.examples.mapstyle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import kav.kavapp.api.KavMap

// Map style: Kav's stop marks on the Live tab drawn in a shape the rider picks (a declared setting) and a colour picked
// on a colour picker of its own (its settings view, Kav.settingsScreen), and a pin for a place the rider chooses with
// Kav's own place search (a "place" setting). Everything is drawn again the moment something changes.
class MapStyle : KavExtension {
    private lateinit var kav: Kav
    private var placeName = ""

    override fun start(kav: Kav) {
        this.kav = kav
        kav.map().onTap { id, context ->
            if (id == PLACE) kav.ui().dialog(context, placeName, words("Your own pin, from Map style.", "הסיכה שלכם, מ-Map style."), "OK") {}
        }
        kav.settingsScreen { context, _ -> colourPicker(context) }
        draw()
        kav.onSettings { draw() }
    }

    private fun prefs() = kav.context().getSharedPreferences("kavexamples.mapstyle", Context.MODE_PRIVATE)

    private fun colour() = prefs().getInt("colour", 0xFF00897B.toInt())

    private fun draw() {
        val s = JSONObject(kav.settings())
        val map = kav.map()
        val colour = colour()
        val dark = darker(colour)
        // Asked for each stop on the main thread, so the icon is drawn once here and shared.
        val icon = when (s.optString("shape")) {
            "pin" -> pin(px(22f), colour, dark)
            "square" -> square(px(14f), colour, dark)
            else -> null
        }
        map.stopLook { _, _, _, _ -> if (icon != null) KavMap.Look(0, 0, icon) else KavMap.Look(colour, dark, null) }

        // {"name", "lat", "lon"}, or null until the rider chooses a place.
        val place = s.optJSONObject("place")
        placeName = place?.optString("name")?.ifBlank { null } ?: words("My place", "המקום שלי")
        if (place == null) map.remove(PLACE)
        else map.marker(PLACE, null, place.optDouble("lat"), place.optDouble("lon"), colour, pin(px(34f), colour, dark))
        kav.log("drawn: ${s.optString("shape")}, #%06X, place %s".format(colour and 0xFFFFFF, if (place == null) "none" else "set"))
    }

    // Its own settings view: a preview of the pin on a card tinted with the colour, a hue strip to slide along, and a
    // row of presets. Not Kav's look on purpose: an extension's settings view is entirely its own.
    private fun colourPicker(c: Context): View {
        val d = c.resources.displayMetrics.density
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        // The pin on a small white disc, so it reads on a card of its own colour.
        val preview = ImageView(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            val pad = (6 * d).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(c).apply {
            text = words("Color of the marks", "צבע הסימונים")
            textSize = kav.ui().textSize("headline")
            setTextColor(Color.WHITE)
            setPadding((10 * d).toInt(), 0, 0, 0)
        }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            addView(title)
        }
        fun paint(colour: Int) {
            box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colour, darker(darker(colour)))).apply { cornerRadius = 24 * d }
            preview.setImageBitmap(pin((28 * d).toInt(), colour, darker(colour)))
        }
        fun choose(colour: Int) {
            prefs().edit().putInt("colour", colour).apply()
            paint(colour)
            draw()
        }
        val hue = HueStrip(c, colour()) { choose(it) }
        val presets = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            for (p in PRESETS) addView(View(c).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p); setStroke((2 * d).toInt(), Color.WHITE) }
                contentDescription = "#%06X".format(p and 0xFFFFFF)
                setOnClickListener { choose(p); hue.show(p) }
            }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0) })
            // Round, spread evenly across the card.
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(top)
        box.addView(hue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (36 * d).toInt()).apply { topMargin = (14 * d).toInt() })
        box.addView(presets, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
        paint(colour())
        return box
    }

    // A rainbow to slide a finger along, with a handle at the hue of the colour chosen, filled with that colour: the
    // handle follows the finger, and each move picks that hue.
    @SuppressLint("ViewConstructor")
    private class HueStrip(c: Context, private var colour: Int, val picked: (Int) -> Unit) : View(c) {
        private var hue = hueOf(colour)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3 * c.resources.displayMetrics.density }

        init { contentDescription = "Hue" }

        // A colour chosen elsewhere (a preset): the handle moves to its hue.
        fun show(c: Int) { colour = c; hue = hueOf(c); invalidate() }

        private fun hueOf(c: Int) = FloatArray(3).also { Color.colorToHSV(c, it) }[0]

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            track.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, .85f, .85f)) }, null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            // The handle, filled with the colour chosen, so the strip says which one it is.
            val x = (hue / 360f * width).coerceIn(r, width - r)
            fill.color = colour
            canvas.drawCircle(x, r, r - ring.strokeWidth, fill)
            canvas.drawCircle(x, r, r - ring.strokeWidth, ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            hue = (e.x.coerceIn(0f, width.toFloat()) / width * 360f).coerceAtMost(359.9f)
            colour = Color.HSVToColor(floatArrayOf(hue, .85f, .85f))
            invalidate()
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_MOVE) picked(colour)
            return true
        }
    }

    private fun words(en: String, he: String) = if (kav.locale() == "he") he else en

    private fun px(dp: Float) = (dp * kav.context().resources.displayMetrics.density).toInt()

    // A map pin: a round head with a point under it, its tip at the bottom centre.
    private fun pin(size: Int, fill: Int, ring: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size * 0.32f
        val cx = size / 2f
        val cy = r + size * 0.06f
        // One outline: the head and the point joined, so the ring doesn't cross the point.
        val shape = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        shape.op(Path().apply {
            moveTo(cx - r * 0.75f, cy + r * 0.6f); lineTo(cx, size.toFloat()); lineTo(cx + r * 0.75f, cy + r * 0.6f); close()
        }, Path.Op.UNION)
        p.color = fill; c.drawPath(shape, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = size / 14f; p.color = ring; c.drawPath(shape, p)
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r * 0.4f, p)
        return b
    }

    private fun square(size: Int, fill: Int, ring: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = size / 7f
        p.color = ring; c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), edge, edge, p)
        p.color = fill; c.drawRoundRect(edge, edge, size - edge, size - edge, edge / 2, edge / 2, p)
        return b
    }

    private fun darker(c: Int): Int {
        fun ch(shift: Int) = ((c shr shift and 0xFF) * 0.6).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        const val PLACE = "place"
        val PRESETS = intArrayOf(0xFF00897B.toInt(), 0xFFF57C00.toInt(), 0xFF7B1FA2.toInt(), 0xFFD81B60.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt())
    }
}
