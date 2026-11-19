package kav.kavapp.examples.mylimits

import org.json.JSONArray
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import java.util.Calendar
import java.util.TimeZone

// My limits: two route rules. The filter hides a route with more changes or a longer walk than the rider set, with
// the reason Kav shows under the results; the annotator tags night rides, a tag the manifest names in both languages. Each is
// one call per search with every route, answered with one entry per route in the same order.
class MyLimits : KavExtension {
    override fun start(kav: Kav) {
        kav.provide("results.filter") { input ->
            val s = JSONObject(kav.settings())
            val most = s.optInt("changes", 2)
            val longest = s.optInt("walk", 15)
            val he = kav.locale() == "he"
            answer(input) { route ->
                val changes = rides(route) - 1
                val walk = longestWalk(route)
                when {
                    changes > most -> if (he) "$changes החלפות, יותר ממה שהגדרתם" else "$changes changes, more than you set"
                    walk > longest -> if (he) "הליכה של $walk דקות, ארוכה ממה שהגדרתם" else "a $walk minute walk, longer than you set"
                    else -> true
                }
            }
        }
        kav.provide("results.annotate") { input ->
            val s = JSONObject(kav.settings())
            answer(input) { route ->
                JSONArray().apply {
                    if (s.optBoolean("night", true) && atNight(route)) put("night")
                }
            }
        }
    }

    private fun answer(input: String, each: (JSONObject) -> Any): String {
        val routes = JSONObject(input).getJSONArray("routes")
        return JSONArray().apply { for (i in 0 until routes.length()) put(each(routes.getJSONObject(i))) }.toString()
    }

    private fun legs(route: JSONObject) = route.getJSONArray("legs").let { a -> (0 until a.length()).map(a::getJSONObject) }

    private fun rides(route: JSONObject) = legs(route).count { it.optString("kind") == "ride" }

    // The longest walk, in whole minutes rounded up.
    private fun longestWalk(route: JSONObject) = legs(route).filter { it.optString("kind") == "walk" }
        .maxOfOrNull { ((it.optLong("arr") - it.optLong("dep")) + 59) / 60 }?.toInt() ?: 0

    // A ride that leaves between 23:00 and 05:00, Israel time.
    private fun atNight(route: JSONObject) = legs(route).filter { it.optString("kind") == "ride" }.any { leg ->
        val hour = Calendar.getInstance(ISRAEL).apply { timeInMillis = leg.optLong("dep") * 1000 }.get(Calendar.HOUR_OF_DAY)
        hour >= 23 || hour < 5
    }

    private companion object {
        val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")
    }
}
