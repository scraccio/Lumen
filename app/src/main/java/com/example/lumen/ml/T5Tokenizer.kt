package com.example.lumen.ml

import android.content.Context
import org.json.JSONObject

class T5Tokenizer(context: Context) {

    private val idToPiece: Array<String>
    private val pieceToId: Map<String, Int>

    companion object {
        const val PAD_ID = 0L
        const val EOS_ID = 1L
        const val UNK_ID = 2L
        private const val SPACE_MARKER = '▁' // ▁
    }

    init {
        val json = context.assets.open("t5/tokenizer.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val vocabArray = root.getJSONObject("model").getJSONArray("vocab")
        val size = vocabArray.length()
        idToPiece = Array(size) { i ->
            vocabArray.getJSONArray(i).getString(0)
        }
        pieceToId = HashMap<String, Int>(size).also { map ->
            for (i in 0 until size) map[idToPiece[i]] = i
        }
    }

    fun encode(text: String, maxLength: Int = 512): Pair<LongArray, LongArray> {
        val words = text.trim().split(Regex("\\s+"))
        val ids = mutableListOf<Long>()

        for ((wi, word) in words.withIndex()) {
            val marked = if (wi == 0) word else "$SPACE_MARKER$word"
            var pos = 0
            while (pos < marked.length && ids.size < maxLength - 1) {
                var matched = false
                for (end in marked.length downTo pos + 1) {
                    val sub = marked.substring(pos, end)
                    val id = pieceToId[sub]
                    if (id != null) {
                        ids.add(id.toLong())
                        pos = end
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    ids.add(UNK_ID)
                    pos++
                }
            }
        }
        ids.add(EOS_ID) // T5 appends EOS

        val padded = LongArray(maxLength) { PAD_ID }
        val mask = LongArray(maxLength) { 0L }
        val len = minOf(ids.size, maxLength)
        for (i in 0 until len) {
            padded[i] = ids[i]
            mask[i] = 1L
        }
        return Pair(padded, mask)
    }

    fun decode(ids: LongArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == EOS_ID || id == PAD_ID) break
            if (id < 0 || id >= idToPiece.size) continue
            val piece = idToPiece[id.toInt()]
            // Skip T5 control tokens (e.g. the span-corruption sentinels "<extra_id_N>")
            // so they don't leak into the displayed summary.
            if (piece.startsWith("<extra_id_") || (piece.startsWith("<") && piece.endsWith(">"))) continue
            if (piece.startsWith(SPACE_MARKER)) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(piece.substring(1))
            } else {
                sb.append(piece)
            }
        }
        return sb.toString().trim()
    }
}
