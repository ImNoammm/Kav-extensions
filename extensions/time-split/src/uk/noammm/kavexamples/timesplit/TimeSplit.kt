package uk.noammm.kavexamples.timesplit

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import org.json.JSONObject
import uk.noammm.kav.api.Kav
import uk.noammm.kav.api.KavExtension
import kotlin.math.roundToInt

// Time split: a line under each search result that splits the trip's time into on board, waiting and walking. Kav asks
// its results card once for each route shown, with the route as route rules get it; a route it has nothing to say about
// gets no line.
class TimeSplit : KavExtension {
    private lateinit var kav: Kav

    override fun start(kav: Kav) {
        this.kav = kav
        kav.card("results") { context, about -> line(context, JSONObject(about).optJSONObject("trip")) }
    }

    private fun words(en: String, he: String) = if (kav.locale() == "he") he else en

    // The minutes of the route's legs of one kind ("ride", "wait" or "walk"), from their times in seconds.
    private fun minutes(route: JSONObject, kind: String): Int {
        val legs = route.optJSONArray("legs") ?: return 0
        var seconds = 0L
        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            if (leg.optString("kind") == kind) seconds += leg.optLong("arr") - leg.optLong("dep")
        }
        return (seconds / 60.0).roundToInt()
    }

    private fun line(c: Context, route: JSONObject?): View? {
        route ?: return null
        val s = JSONObject(kav.settings())
        val show = s.optString("show", "all")
        val quiet = s.optBoolean("quiet", true)
        val parts = listOf(
            Triple("all", words("on board", "בנסיעה"), minutes(route, "ride")),
            Triple("wait", words("waiting", "בהמתנה"), minutes(route, "wait")),
            Triple("walk", words("walking", "בהליכה"), minutes(route, "walk")),
        ).filter { (key, _, _) -> show == "all" || show == key }
            .filter { (_, _, m) -> !(quiet && m < 1) }
            .map { (_, label, m) -> words("$m min $label", "$m דק׳ $label") }
        if (parts.isEmpty()) return null
        val ui = kav.ui()
        return TextView(c).apply {
            text = parts.joinToString(" · ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textSize("caption"))
            setTextColor(ui.color("textSecondary"))
        }
    }
}
