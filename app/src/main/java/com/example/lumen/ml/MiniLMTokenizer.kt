package com.example.lumen.ml

import android.content.Context
import org.json.JSONObject

class MiniLMTokenizer(context: Context) {

    private val vocab: Map<String, Int>
    private val maxLength = 128

    init {
        val json = context.assets.open("tokenizer.json")
            .bufferedReader().readText()
        val root = JSONObject(json)
        val vocabObj = root.getJSONObject("model").getJSONObject("vocab")
        val map = mutableMapOf<String, Int>()
        vocabObj.keys().forEach { key -> map[key] = vocabObj.getInt(key) }
        vocab = map
    }

    fun tokenize(text: String): TokenizerOutput {
        val tokens = mutableListOf<String>()
        tokens.add("[CLS]")

        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .forEach { word -> tokens.addAll(wordPiece(word)) }

        tokens.add("[SEP]")

        // truncate
        val truncated = if (tokens.size > maxLength)
            tokens.take(maxLength - 1) + listOf("[SEP]")
        else tokens

        val ids = truncated.map { vocab[it] ?: vocab["[UNK]"] ?: 100 }
        val mask = List(ids.size) { 1 }
        val typeIds = List(ids.size) { 0 }

        val padId = vocab["[PAD]"] ?: 0
        return TokenizerOutput(
            inputIds = (ids + List(maxLength - ids.size) { padId }).map { it.toLong() }.toLongArray(),
            attentionMask = (mask + List(maxLength - mask.size) { 0 }).map { it.toLong() }.toLongArray(),
            tokenTypeIds = (typeIds + List(maxLength - typeIds.size) { 0 }).map { it.toLong() }.toLongArray()
        )
    }

    private fun wordPiece(word: String): List<String> {
        if (vocab.containsKey(word)) return listOf(word)
        val tokens = mutableListOf<String>()
        var remaining = word
        var isFirst = true
        while (remaining.isNotEmpty()) {
            var found = false
            for (end in remaining.length downTo 1) {
                val sub = if (isFirst) remaining.substring(0, end)
                else "##" + remaining.substring(0, end)
                if (vocab.containsKey(sub)) {
                    tokens.add(sub)
                    remaining = remaining.substring(end)
                    isFirst = false
                    found = true
                    break
                }
            }
            if (!found) { tokens.add("[UNK]"); break }
        }
        return tokens
    }
}

data class TokenizerOutput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
)