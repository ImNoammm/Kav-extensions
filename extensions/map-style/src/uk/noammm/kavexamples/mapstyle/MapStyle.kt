package uk.noammm.kavexamples.mapstyle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONObject
import uk.noammm.kav.api.Kav
import uk.noammm.kav.api.KavExtension
import uk.noammm.kav.api.KavMap

// Map style: Kav's stop marks on the Live tab drawn in a shape and colour the rider picks, and a pin of its own for
// a place the rider types. Everything is drawn again the moment a setting changes (kav.onSettings).
class MapStyle : KavExtension {
    private lateinit var kav: Kav
    private var placeName = ""

    override fun start(kav: Kav) {
        this.kav = kav
        kav.map().onTap { id, context ->
            if (id == PLACE) kav.ui().dialog(context, placeName, words("Your own pin, from Map style.", "הסיכה שלכם, מ-Map style."), "OK") {}
        }
        draw(JSONObject(kav.settings()))
        kav.onSettings { draw(JSONObject(it)) }
    }

    private fun draw(s: JSONObject) {
        val map = kav.map()
        val colour = COLOURS[s.optString("colour")] ?: COLOURS.getValue("teal")
        val dark = darker(colour)
        // Asked for each stop on the main thread, so the icon is drawn once here and shared.
        val icon = when (s.optString("shape")) {
            "pin" -> pin(px(22f), colour, dark)
            "square" -> square(px(14f), colour, dark)
            else -> null
        }
        map.stopLook { _, _, _, _ -> if (icon != null) KavMap.Look(0, 0, icon) else KavMap.Look(colour, dark, null) }

        placeName = s.optString("name").ifBlank { words("My place", "המקום שלי") }
        val at = latLon(s.optString("place"))
        if (at == null) map.remove(PLACE) else map.marker(PLACE, null, at.first, at.second, colour, pin(px(34f), colour, dark))
        kav.log("drawn: ${s.optString("shape")}, ${s.optString("colour")}, place ${if (at == null) "none" else "set"}")
    }

    // "32.0853, 34.7818" as two numbers, or null when it isn't a place.
    private fun latLon(text: String): Pair<Double, Double>? {
        val parts = text.split(',').map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) return null
        val (lat, lon) = parts.map { it!! }
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
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
        val shape = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.75f, cy + r * 0.6f); lineTo(cx, size.toFloat()); lineTo(cx + r * 0.75f, cy + r * 0.6f); close()
        }
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
        val COLOURS = mapOf(
            "teal" to 0xFF00897B.toInt(), "orange" to 0xFFF57C00.toInt(),
            "purple" to 0xFF7B1FA2.toInt(), "pink" to 0xFFD81B60.toInt(),
        )
    }
}
