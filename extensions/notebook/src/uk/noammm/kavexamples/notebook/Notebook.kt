package uk.noammm.kavexamples.notebook

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.api.Kav
import uk.noammm.kav.api.KavExtension

// Notebook: a screen of its own, which the rider can pin as a tab (its page is marked "tab" in the manifest; Settings ›
// Extensions › Notebook › Show Notes as a tab). Built from Kav's own components, so it follows Kav's theme, dark mode
// and Hebrew. The notes are kept in a file of the extension's own, in Kav's private storage.
class Notebook : KavExtension {
    private lateinit var kav: Kav

    override fun start(kav: Kav) {
        this.kav = kav
        kav.screen("notes") { context, _ -> screen(context) }
    }

    private fun words(en: String, he: String) = if (kav.locale() == "he") he else en

    private fun screen(c: Context): View {
        val ui = kav.ui()
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        var typed = ""
        fun refill() {
            list.removeAllViews()
            val notes = load(c)
            if (notes.isEmpty()) list.addView(ui.row(c, "info", words("No notes yet", "עוד אין פתקים"), words("Write one above.", "כתבו אחד למעלה."), null))
            val newest = JSONObject(kav.settings()).optBoolean("newest", true)
            val order = if (newest) notes.indices.reversed() else notes.indices
            for (i in order) list.addView(ui.row(c, "edit", notes[i], null) {
                ui.dialog(c, words("Delete this note?", "למחוק את הפתק?"), notes[i], words("Delete", "מחיקה")) {
                    save(c, load(c).filterIndexed { j, _ -> j != i }); refill()
                }
            })
        }
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (kav.ui().dp("gap4") * c.resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad)
            addView(ui.header(c, words("New note", "פתק חדש")))
            // The field sits in a box of its own, so a fresh, empty one can take its place after each note.
            val field = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            fun freshField() {
                typed = ""
                field.removeAllViews()
                field.addView(ui.textField(c, "", words("Platform 2, line 480 at 7:10", "רציף 2, קו 480 ב-7:10")) { typed = it })
            }
            freshField()
            addView(field)
            addView(ui.button(c, words("Add", "הוספה"), true) {
                val t = typed.trim()
                if (t.isNotEmpty()) { save(c, load(c) + t); kav.log("added a note"); freshField(); refill() }
            })
            addView(ui.header(c, words("Your notes", "הפתקים שלכם")))
            addView(list)
        }
        refill()
        return ScrollView(c).apply { addView(page) }
    }

    private fun file(c: Context) = java.io.File(c.filesDir, "kavexamples-notebook.json")

    private fun load(c: Context): List<String> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map(a::getString)
    }.getOrDefault(emptyList())

    private fun save(c: Context, notes: List<String>) = file(c).writeText(JSONArray(notes).toString())
}
