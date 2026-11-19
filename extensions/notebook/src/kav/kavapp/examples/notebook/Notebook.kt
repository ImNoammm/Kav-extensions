package kav.kavapp.examples.notebook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

// Notebook: a screen of its own, which the rider can pin as a tab (its page is marked "tab" in the manifest). Built from
// Kav's own components, so it follows Kav's theme, dark mode and Hebrew. A plus adds a note; tapping a note opens it to
// edit, with Delete inside. The notes are kept in a file in Kav's private storage.
class Notebook : KavExtension {
    private lateinit var kav: Kav

    private class Note(val title: String, val body: String)

    override fun start(kav: Kav) {
        this.kav = kav
        kav.screen("notes") { context, _ -> screen(context) }
    }

    private fun words(en: String, he: String) = if (kav.locale() == "he") he else en

    private fun px(c: Context, name: String) = (kav.ui().dp(name) * c.resources.displayMetrics.density).toInt()

    // The list, or one note open: the screen swaps between them in place.
    private fun screen(c: Context): View {
        val box = FrameLayout(c)
        listAgain(box, c)
        return box
    }

    private fun listAgain(box: FrameLayout, c: Context) {
        box.removeAllViews()
        box.addView(listView(c) { i -> box.removeAllViews(); box.addView(editor(c, i) { listAgain(box, c) }) })
    }

    // A round plus at the top for a new note, then the notes as rows on one card; one short line when there are none.
    // open(-1) is a new note.
    private fun listView(c: Context, open: (Int) -> Unit): View {
        val ui = kav.ui()
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(c, "gap8") * 3)
        }
        // The plus at the top, at the end, as the screen header's round button.
        val size = (48 * c.resources.displayMetrics.density).toInt()
        page.addView(LinearLayout(c).apply {
            gravity = Gravity.END
            addView(PlusButton(c, ui.color("fill"), ui.color("text")).apply {
                contentDescription = words("New note", "פתק חדש")
                setOnClickListener { open(-1) }
            }, LinearLayout.LayoutParams(size, size))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(c, "gap3") })
        val notes = load(c)
        if (notes.isEmpty()) {
            page.addView(TextView(c).apply {
                text = words("No notes yet. Tap + to write one.", "עוד אין פתקים. הקישו על + כדי לכתוב.")
                textSize = ui.textSize("subhead")
                setTextColor(ui.color("textSecondary"))
                gravity = Gravity.CENTER
                setPadding(0, px(c, "gap6"), 0, px(c, "gap6"))
            })
        } else {
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(ui.color("surface"))
                    cornerRadius = kav.ui().dp("rCard") * c.resources.displayMetrics.density
                    setStroke(maxOf(1, (0.5f * c.resources.displayMetrics.density).toInt()), ui.color("separator"))
                }
                clipToOutline = true
            }
            val newest = JSONObject(kav.settings()).optBoolean("newest", true)
            val order = if (newest) notes.indices.reversed() else notes.indices
            for ((n, i) in order.withIndex()) {
                if (n > 0) card.addView(View(c).apply { setBackgroundColor(ui.color("separator")) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (0.5f * c.resources.displayMetrics.density).toInt())).apply { marginStart = px(c, "gap4") })
                val note = notes[i]
                val first = note.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val title = note.title.ifBlank { first.ifBlank { words("Empty note", "פתק ריק") } }
                val sub = if (note.title.isNotBlank()) first.ifBlank { null } else null
                card.addView(ui.row(c, null, title, sub) { open(i) })
            }
            page.addView(card)
        }
        return ScrollView(c).apply { addView(page) }
    }

    // One note to write or edit: its title and its text, Save, Cancel, and Delete (with a confirm) for one already saved.
    private fun editor(c: Context, index: Int, back: () -> Unit): View {
        val ui = kav.ui()
        val notes = load(c)
        val note = notes.getOrNull(index) ?: Note("", "")
        var title = note.title
        var body = note.body
        val gap = px(c, "gap3")
        fun spaced(v: View) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap }
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(c, "gap2"), 0, px(c, "gap4"))
            addView(TextView(c).apply {
                text = if (index < 0) words("New note", "פתק חדש") else words("Edit note", "עריכת פתק")
                textSize = ui.textSize("headline")
                setTextColor(ui.color("text"))
                setPadding(0, 0, 0, px(c, "gap3"))
            })
            addView(ui.textField(c, title, words("Title", "כותרת")) { title = it })
            addView(ui.textField(c, body, words("Platform 2, line 480 at 7:10", "רציף 2, קו 480 ב-7:10")) { body = it }, spaced(this))
            addView(ui.button(c, words("Save", "שמירה"), true) {
                val t = title.trim()
                val b = body.trim()
                if (t.isEmpty() && b.isEmpty()) { back(); return@button }
                val now = load(c).toMutableList()
                if (index in now.indices) now[index] = Note(t, b) else now += Note(t, b)
                save(c, now)
                kav.log(if (index < 0) "added a note" else "saved a note")
                back()
            }, spaced(this).apply { topMargin = px(c, "gap5") })
            addView(ui.button(c, words("Cancel", "ביטול"), false) { back() }, spaced(this))
            if (index >= 0) addView(ui.button(c, words("Delete", "מחיקה"), false) {
                ui.dialog(c, words("Delete this note?", "למחוק את הפתק?"), note.title.ifBlank { note.body.lineSequence().firstOrNull().orEmpty() },
                    words("Delete", "מחיקה")) {
                    save(c, load(c).filterIndexed { j, _ -> j != index })
                    kav.log("deleted a note")
                    back()
                }
            }, spaced(this))
        }
        return ScrollView(c).apply { addView(page) }
    }

    // A round button with a plus, on Kav's fill, as the round buttons in Kav's own screen headers.
    @SuppressLint("ViewConstructor")
    private class PlusButton(c: Context, fill: Int, ink: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; strokeWidth = 2.2f * c.resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
        }

        init {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.2f
            canvas.drawLine(cx - r, cy, cx + r, cy, paint)
            canvas.drawLine(cx, cy - r, cx, cy + r, paint)
        }
    }

    private fun file(c: Context) = java.io.File(c.filesDir, "kavexamples-notebook.json")

    // Each note is {"title", "body"}; a note from before titles is a plain string, read as its body.
    private fun load(c: Context): List<Note> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { i ->
            when (val v = a.get(i)) {
                is JSONObject -> Note(v.optString("title"), v.optString("body"))
                else -> Note("", v.toString())
            }
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, notes: List<Note>) =
        file(c).writeText(JSONArray().apply { for (n in notes) put(JSONObject().put("title", n.title).put("body", n.body)) }.toString())
}
