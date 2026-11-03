package org.futo.inputmethod.latin.fmaboard

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/** One recognized word, in image pixels. */
class OcrWord(val text: String, val box: Rect)

/** One recognized text line, in image pixels. [block] groups the lines of one paragraph. */
class OcrLine(val words: List<OcrWord>, val box: Rect, val block: Int) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/**
 * Turns recognized lines into text you can edit: paragraphs for normal text, and for code
 * the editor's line numbers are dropped and the indentation is rebuilt from where each line starts.
 */
object OcrText {
    private val CODE_HINT = Regex(
        "[=(){}\\[\\];<>]|^\\s*(def|class|for|while|if|elif|else|return|import|from|function|const|let|var|val|fun|public|private|#include)\\b"
    )
    private val NUMBER = Regex("\\d{1,4}")
    private val FENCE = Regex("^```[\\w+-]*\\n|\\n?```\\s*$")

    fun looksLikeCode(lines: List<OcrLine>): Boolean {
        if (lines.size < 2) return false
        val codeLike = lines.count { CODE_HINT.containsMatchIn(it.text) }
        return codeLike * 2 >= lines.size || numberedRows(rows(lines.flatMap { it.words }))
    }

    fun format(lines: List<OcrLine>, code: Boolean): String =
        if (code) asCode(lines) else asText(lines)

    /** Lines of a paragraph joined with spaces (a hyphen at the end of a line joins the word). */
    fun asText(lines: List<OcrLine>): String {
        val out = StringBuilder()
        var previous: OcrLine? = null
        for (line in lines) {
            val prev = previous
            if (prev != null) when {
                prev.block != line.block -> out.append('\n')
                out.endsWith("-") -> out.setLength(out.length - 1)
                else -> out.append(' ')
            }
            out.append(line.text)
            previous = line
        }
        return out.toString()
    }

    fun asCode(lines: List<OcrLine>): String {
        val rows = rows(lines.flatMap { it.words }).map { it.toMutableList() }
        if (rows.isEmpty()) return ""

        // The editor's line numbers: a column of numbers at the start of the rows. Then a row
        // that was only a number is an empty line, and gaps don't need guessing.
        val numbered = numberedRows(rows)
        if (numbered) rows.forEach { row ->
            if (row.isNotEmpty() && NUMBER.matches(row.first().text)) row.removeAt(0)
        }

        val words = rows.flatten()
        if (words.isEmpty()) return ""
        val charWidth = words.filter { it.text.length >= 2 }
            .map { it.box.width().toFloat() / it.text.length }
            .sorted().let { if (it.isEmpty()) 10f else it[it.size / 2] }
            .coerceAtLeast(1f)
        val minLeft = words.minOf { it.box.left }
        val offsets = rows.map { row -> row.firstOrNull()?.let { (it.box.left - minLeft) / charWidth } }
        val smallest = offsets.filterNotNull().filter { it > 1f }.minOrNull() ?: 4f
        val unit = if (smallest >= 3f) 4 else 2

        // Without line numbers, a big vertical gap is a blank line
        val centers = rows.map { row -> row.firstOrNull()?.box?.centerY() }
        val steps = centers.filterNotNull().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        val step = if (steps.isEmpty()) 0 else steps[steps.size / 2]

        val out = ArrayList<String>()
        var previousCenter: Int? = null
        rows.forEachIndexed { i, row ->
            val center = centers[i]
            if (!numbered && center != null && previousCenter != null && step > 0) {
                val missing = ((center - previousCenter!!).toFloat() / step).roundToInt() - 1
                repeat(missing.coerceIn(0, 3)) { out.add("") }
            }
            if (center != null) previousCenter = center
            val offset = offsets[i]
            if (row.isEmpty() || offset == null) {
                out.add("")
            } else {
                val spaces = (offset / unit).roundToInt() * unit
                out.add(" ".repeat(spaces) + row.joinToString(" ") { it.text })
            }
        }
        // Rows that were only a number were empty editor lines; drop those at the end
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        return out.joinToString("\n")
    }

    /** Words grouped into visual rows, top to bottom, each left to right. */
    private fun rows(words: List<OcrWord>): List<List<OcrWord>> {
        if (words.isEmpty()) return emptyList()
        val heights = words.map { it.box.height() }.sorted()
        val half = heights[heights.size / 2].coerceAtLeast(2) / 2
        val rows = ArrayList<MutableList<OcrWord>>()
        for (w in words.sortedBy { it.box.centerY() }) {
            val row = rows.lastOrNull()
            if (row != null && abs(w.box.centerY() - row.sumOf { it.box.centerY() } / row.size) < half) row.add(w)
            else rows.add(mutableListOf(w))
        }
        return rows.map { row -> row.sortedBy { it.box.left } }
    }

    private fun numberedRows(rows: List<List<OcrWord>>): Boolean {
        val numbered = rows.count { it.size > 0 && NUMBER.matches(it.first().text) }
        return numbered >= 3 && numbered * 10 >= rows.size * 6
    }

    const val AI_PROMPT =
        "Transcribes el texto de una imagen: una captura o una foto de una pantalla, un papel o un cartel. " +
        "Copia el texto tal cual aparece: no lo traduzcas, no lo resumas y no corrijas lo que dice. " +
        "Si es código, conserva la sangría exacta con espacios, no incluyas los números de línea del editor y no arregles errores del código. " +
        "Si es texto normal, separa los párrafos con un salto de línea y no partas las frases donde acaba cada línea de la imagen. " +
        "Si es una pantalla, copia el contenido principal y omite la interfaz (botones, menús, barras). " +
        "Te paso también una lectura automática que puede tener errores; úsala solo como ayuda. " +
        "Responde solo con el texto, sin comillas, sin ``` y sin explicaciones."

    fun aiRequest(draft: String, code: Boolean): String =
        (if (code) "Parece código.\n" else "") + "Lectura automática:\n<lectura>\n$draft\n</lectura>"

    /** Models sometimes wrap the answer in a code fence anyway. */
    fun cleanAiAnswer(text: String): String = FENCE.replace(text.trim(), "").trimEnd()
}
