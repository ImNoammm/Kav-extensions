package uk.noammm.kavexamples.stopreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import uk.noammm.kav.api.Kav
import uk.noammm.kav.api.KavExtension
import java.text.DateFormat
import java.util.Date

// Stop reminder: a card on each stop board with a button; a few minutes later (a setting) a notification says it's
// time to head to that stop. It uses Android itself for the notification, with Kav's own permission to post one, and
// it keeps time while Kav is running (in front or in the background).
class StopReminder : KavExtension {
    private lateinit var kav: Kav
    private val main = Handler(Looper.getMainLooper())
    // The reminder waiting now, if any: its stop's name, when it fires, and the job that fires it.
    private var waiting: Triple<String, Long, Runnable>? = null

    override fun start(kav: Kav) {
        this.kav = kav
        kav.card("stop") { context, about -> card(context, JSONObject(about).optString("name")) }
    }

    private fun words(en: String, he: String) = if (kav.locale() == "he") he else en

    private fun card(c: Context, stop: String): View {
        val ui = kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val minutes = JSONObject(kav.settings()).optString("minutes", "5").toInt()
        val w = waiting
        if (!c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            box.addView(ui.row(c, "warning", words("Kav can't send notifications", "Kav לא יכולה לשלוח התראות"),
                words("Allow them in Android's settings for Kav.", "אפשרו אותן בהגדרות של Android עבור Kav."), null))
        } else if (w != null && w.first == stop) {
            box.addView(ui.row(c, "clock", words("Reminder at ", "תזכורת ב-") + time(w.second), stop, null))
            box.addView(ui.button(c, words("Cancel it", "ביטול"), false) { cancel(); kav.log("cancelled") })
        } else {
            box.addView(ui.row(c, "clock", words("Remind me to go to this stop", "תזכירו לי ללכת לתחנה הזו"),
                words("In $minutes minutes", "בעוד $minutes דקות"), null))
            box.addView(ui.button(c, words("Remind me in $minutes min", "להזכיר בעוד $minutes דק׳"), true) {
                set(c.applicationContext, stop, minutes)
                ui.dialog(c, words("Reminder set", "התזכורת נקבעה"), words("At ", "ב-") + time(waiting!!.second), "OK") {}
            })
        }
        return ui.card(c, box)
    }

    private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    // One reminder at a time: a new one replaces the one waiting.
    private fun set(app: Context, stop: String, minutes: Int) {
        cancel()
        val at = System.currentTimeMillis() + minutes * 60_000L
        val job = Runnable { waiting = null; notify(app, stop) }
        waiting = Triple(stop, at, job)
        main.postDelayed(job, minutes * 60_000L)
        kav.log("set for $minutes min")
    }

    private fun cancel() {
        waiting?.let { main.removeCallbacks(it.third) }
        waiting = null
    }

    private fun notify(app: Context, stop: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, words("Stop reminders", "תזכורות לתחנה"), NotificationManager.IMPORTANCE_HIGH))
        val own = JSONObject(kav.settings()).optString("words")
        // A tap opens Kav, the app this runs in.
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.let { PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(own.ifBlank { words("Time to go", "הגיע הזמן לצאת") })
            .setContentText(words("Head to $stop", "לכו לתחנה $stop"))
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        nm.notify(1, n)
        kav.log("reminded")
    }

    private companion object {
        const val CHANNEL = "kavexamples.stop-reminder"
    }
}
