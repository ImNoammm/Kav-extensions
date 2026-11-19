package kav.kavapp.examples.notebook

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import kav.kavapp.api.KavUi

// Notebook: a screen of its own, which the rider can pin as a tab (its page is marked "tab" in the manifest). Built from
// Kav's own components, so it follows Kav's theme, dark mode and Hebrew, and moves as Kav does: the list, a note to
// read, and its edit form are pages of Kav's (KavUi.pages), and a note added or deleted folds into or out of the list.
// The notes are kept in a file in Kav's private storage.
class Notebook : KavExtension {
    private lateinit var kav: Kav

    private class Note(val title: String, val body: String) {
        val heading get() = title.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
    }

    override fun start(kav: Kav) {
        this.kav = kav
        kav.screen("notes") { context, _ ->
            val pages = arrayOfNulls<KavUi.Pages>(1)
            val p = kav.ui().pages(context, listView(context, pages))
            pages[0] = p
            p.view()
        }
    }

    private fun words(en: String, he: String) = if (kav.locale() == "he") he else en

    private fun px(c: Context, name: String) = (kav.ui().dp(name) * c.resources.displayMetrics.density).toInt()

    private fun hairline(c: Context) = maxOf(1, (0.5f * c.resources.displayMetrics.density).toInt())

    // The list: a round plus at the top for a new note, then the notes as rows on one card, or one short line when there
    // are none. fold: a note that folds in (added) or out (deleted, still in the list given) once the list is shown.
    private fun listView(c: Context, pages: Array<KavUi.Pages?>, foldIn: Int = -1, foldOut: Note? = null, before: List<Note>? = null): View {
        val ui = kav.ui()
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(c, "gap8") * 3)
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }
        page.addView(LinearLayout(c).apply {
            gravity = Gravity.END
            addView(ui.iconButton(c, "plus", words("New note", "פתק חדש")) { pages[0]?.forward(editor(c, pages, -1)) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(c, "gap3") })
        val notes = before ?: load(c)
        val empty = TextView(c).apply {
            text = words("No notes yet. Tap + to write one.", "עוד אין פתקים. הקישו על + כדי לכתוב.")
            textSize = ui.textSize("subhead")
            setTextColor(ui.color("textSecondary"))
            gravity = Gravity.CENTER
            setPadding(0, px(c, "gap6"), 0, px(c, "gap6"))
        }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ui.color("surface"))
                cornerRadius = ui.dp("rCard") * c.resources.displayMetrics.density
                setStroke(hairline(c), ui.color("separator"))
            }
            clipToOutline = true
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }
        val newest = JSONObject(kav.settings()).optBoolean("newest", true)
        // Each row in a box of its own with the hairline above it, so a row and its line fold as one.
        fun item(i: Int, note: Note, first: Boolean) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            if (!first) addView(View(c).apply { setBackgroundColor(ui.color("separator")) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairline(c)).apply { marginStart = px(c, "gap4") })
            val sub = if (note.title.isNotBlank()) note.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() else null
            addView(ui.row(c, null, note.heading.ifBlank { words("Empty note", "פתק ריק") }, sub) { pages[0]?.forward(reader(c, pages, i)) })
        }
        val order = (if (newest) notes.indices.reversed() else notes.indices).toList()
        // A note being added folds in after the list shows; until then it isn't drawn.
        val drawn = order.filter { it != foldIn }
        for ((n, i) in drawn.withIndex()) card.addView(item(i, notes[i], n == 0))
        if (notes.isEmpty() || (drawn.isEmpty() && foldIn < 0)) page.addView(empty) else page.addView(card)
        if (foldIn >= 0 || foldOut != null) page.post {
            if (foldIn >= 0) {
                if (card.parent == null) { page.removeView(empty); page.addView(card) }
                val at = order.indexOf(foldIn)
                card.addView(item(foldIn, notes[foldIn], at == 0), at)
                // The row that was first gets its hairline.
                if (at == 0 && card.childCount > 1) (card.getChildAt(1) as LinearLayout).addView(View(c).apply { setBackgroundColor(ui.color("separator")) }, 0,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairline(c)).apply { marginStart = px(c, "gap4") })
            }
            if (foldOut != null) {
                val at = order.indexOfFirst { notes[it] === foldOut }
                if (at >= 0) {
                    card.removeViewAt(at)
                    if (card.childCount == 0) page.postDelayed({ page.removeView(card); page.addView(empty) }, 300)
                    else if (at == 0) (card.getChildAt(0) as LinearLayout).let { if (it.childCount > 1) it.removeViewAt(0) }
                }
            }
        }
        return ScrollView(c).apply { addView(page) }
    }

    // One note to read: Back, Edit and Delete at the top, its title as the page's title, its text under it.
    private fun reader(c: Context, pages: Array<KavUi.Pages?>, index: Int): View {
        val ui = kav.ui()
        val note = load(c).getOrNull(index) ?: Note("", "")
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconButton(c, "back", words("Back", "חזרה")) { pages[0]?.back(listView(c, pages)) })
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(ui.iconButton(c, "edit", words("Edit", "עריכה")) { pages[0]?.forward(editor(c, pages, index)) })
            addView(View(c), LinearLayout.LayoutParams(px(c, "gap2"), 1))
            addView(ui.iconButton(c, "delete", words("Delete", "מחיקה")) {
                ui.dialog(c, words("Delete this note?", "למחוק את הפתק?"), note.heading, words("Delete", "מחיקה")) {
                    val all = load(c)
                    save(c, all.filterIndexed { j, _ -> j != index })
                    kav.log("deleted a note")
                    pages[0]?.back(listView(c, pages, foldOut = all.getOrNull(index), before = all))
                }
            })
        }
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(c, "gap8") * 3)
            addView(bar)
            addView(TextView(c).apply {
                text = note.title.ifBlank { words("Note", "פתק") }
                textSize = ui.textSize("title")
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ui.color("text"))
                setPadding(0, px(c, "gap5"), 0, px(c, "gap2"))
            })
            addView(TextView(c).apply {
                text = note.body
                textSize = ui.textSize("body")
                setTextColor(ui.color("textSecondary"))
                setLineSpacing(0f, 1.15f)
                setTextIsSelectable(true)
            })
        }
        return ScrollView(c).apply { addView(page) }
    }

    // One note to write or edit: its title (one line, Next goes on to the text), its text (as many lines as it takes),
    // Save and Cancel. Save goes back to the note, or for a new one to the list, where it folds in.
    private fun editor(c: Context, pages: Array<KavUi.Pages?>, index: Int): View {
        val ui = kav.ui()
        val note = load(c).getOrNull(index) ?: Note("", "")
        var title = note.title
        var body = note.body
        fun spaced(top: String = "gap3") = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, top) }
        fun leave() { pages[0]?.back(if (index < 0) listView(c, pages) else reader(c, pages, index)) }
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(c, "gap2"), 0, px(c, "gap8") * 3)
            addView(TextView(c).apply {
                text = if (index < 0) words("New note", "פתק חדש") else words("Edit note", "עריכת פתק")
                textSize = ui.textSize("headline")
                setTextColor(ui.color("text"))
                setPadding(0, 0, 0, px(c, "gap3"))
            })
            addView(ui.textField(c, title, words("Title", "כותרת"), false, "next") { title = it })
            addView(ui.textField(c, body, words("Platform 2, line 480 at 7:10", "רציף 2, קו 480 ב-7:10"), true, null) { body = it }, spaced())
            addView(ui.button(c, words("Save", "שמירה"), true) {
                val t = title.trim()
                val b = body.trimEnd()
                if (t.isEmpty() && b.isBlank()) { leave(); return@button }
                val now = load(c).toMutableList()
                if (index in now.indices) {
                    now[index] = Note(t, b); save(c, now); kav.log("saved a note")
                    pages[0]?.back(reader(c, pages, index))
                } else {
                    now += Note(t, b); save(c, now); kav.log("added a note")
                    pages[0]?.back(listView(c, pages, foldIn = now.lastIndex))
                }
            }, spaced("gap5"))
            addView(ui.button(c, words("Cancel", "ביטול"), false) { leave() }, spaced())
        }
        return ScrollView(c).apply { addView(page) }
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
