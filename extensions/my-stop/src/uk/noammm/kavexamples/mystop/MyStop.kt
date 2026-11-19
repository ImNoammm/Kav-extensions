package uk.noammm.kavexamples.mystop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.api.Kav
import uk.noammm.kav.api.KavExtension
import java.lang.ref.WeakReference

// My stop: a Home card with the next departures from one stop. The rider picks the stop with Kav's own stop picker (a
// "stop" setting) and how many departures with a stepper (a "number" setting). The card is made of Kav's own blocks:
// "stop" is Kav's row for a stop and "departures" its next timetabled departures, both from what is already on the
// phone, so it asks nothing of the internet. A change in its settings draws the card again at once.
class MyStop : KavExtension {
    private lateinit var kav: Kav
    private val main = Handler(Looper.getMainLooper())
    // The cards showing now, so a change in the settings redraws them.
    private val cards = ArrayList<WeakReference<FrameLayout>>()

    override fun start(kav: Kav) {
        this.kav = kav
        kav.card("home") { context, _ -> FrameLayout(context).also { cards += WeakReference(it); fill(it) } }
        kav.onSettings { main.post { cards.removeAll { it.get() == null }; cards.forEach { r -> r.get()?.let(::fill) } } }
    }

    private fun words(en: String, he: String) = if (kav.locale() == "he") he else en

    private fun fill(box: FrameLayout) {
        box.removeAllViews()
        box.addView(card(box.context))
    }

    private fun card(c: Context): View {
        val s = JSONObject(kav.settings())
        // {"code", "name", "lat", "lon"}, or null until the rider picks a stop.
        val code = s.optJSONObject("stop")?.optString("code").orEmpty()
        val blocks = JSONArray()
        if (code.isEmpty()) {
            blocks.put(JSONObject().put("type", "notice").put("text", words("Pick your stop in the settings to see its next departures here.",
                "בחרו את התחנה שלכם בהגדרות כדי לראות כאן את היציאות הבאות ממנה.")))
            blocks.put(JSONObject().put("type", "button").put("label", words("Open settings", "פתיחת ההגדרות")).put("icon", "settings")
                .put("action", JSONObject().put("settings", true)))
        } else {
            val ref = "il.mot/gtfs/stop/$code"
            if (s.optBoolean("name", true)) blocks.put(JSONObject().put("type", "stop").put("ref", ref))
            blocks.put(JSONObject().put("type", "departures").put("ref", ref).put("limit", s.optDouble("count", 3.0).toInt()))
        }
        val tree = JSONObject().put("sections", JSONArray().put(JSONObject().put("blocks", blocks)))
        return kav.ui().blocks(c, tree.toString())
    }
}
