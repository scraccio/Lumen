package com.example.lumen.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.lumen.data.model.Article
import java.nio.LongBuffer

class T5Summarizer(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val encoderSession: OrtSession
    private val decoderSession: OrtSession
    private val tokenizer = T5Tokenizer(context)

    companion object {
        private const val MAX_INPUT_TOKENS = 512
        // The encoder always runs on a MAX_INPUT_TOKENS-padded tensor, so feeding more
        // real text costs nothing. ~4 chars/token for English roughly fills the budget;
        // the tokenizer truncates any overflow at MAX_INPUT_TOKENS.
        private const val MAX_INPUT_CHARS = 2000
        private const val MAX_SUMMARY_TOKENS = 220
        // Per-article condensation in the map stage. Kept short so all mini-summaries
        // for a cluster fit back under MAX_INPUT_TOKENS for the reduce stage.
        private const val MAX_MINI_SUMMARY_TOKENS = 90
        private const val MAX_TITLE_TOKENS = 16
        private const val NO_REPEAT_NGRAM = 3
        private const val TAG = "T5Summarizer"
    }

    /** Tokens that, if emitted next, would repeat an [n]-gram already present in [seq].
     *  Mirrors the standard no_repeat_ngram_size decoding constraint. */
    private fun bannedNgramTokens(seq: List<Long>, n: Int): Set<Long> {
        if (seq.size < n) return emptySet()
        val prefix = seq.subList(seq.size - (n - 1), seq.size)
        val banned = mutableSetOf<Long>()
        for (i in 0..seq.size - n) {
            if (seq.subList(i, i + n - 1) == prefix) banned.add(seq[i + n - 1])
        }
        return banned
    }

    init {
        val encBytes = context.assets.open("t5/encoder_model_quantized.onnx").readBytes()
        encoderSession = env.createSession(encBytes, OrtSession.SessionOptions())
        val decBytes = context.assets.open("t5/decoder_model_quantized.onnx").readBytes()
        decoderSession = env.createSession(decBytes, OrtSession.SessionOptions())
        // Log actual tensor names so on-device runtime mismatches are easy to diagnose
        Log.d(TAG, "T5 loaded. Encoder inputs: ${encoderSession.inputNames}, Decoder inputs: ${decoderSession.inputNames}")
    }

    @Synchronized
    fun summarizeWithBodies(articles: List<Article>, bodies: Map<String, String>): String {
        // No body text was scraped — summarizing a bare headline is meaningless and the
        // weak quantized model degenerates into multilingual gibberish on such input.
        // The headline IS the summary in that case.
        if (!hasUsableBody(articles, bodies)) return joinedTitles(articles)
        return runSummarization(buildReduceText(articles, bodies), MAX_SUMMARY_TOKENS, trimToSentence = true)
    }

    /** Generates the story summary and headline together so the expensive per-article
     *  map stage runs once, not twice. Both stages cover EVERY article in the cluster. */
    @Synchronized
    fun summarizeAndTitle(articles: List<Article>, bodies: Map<String, String>): Pair<String, String> {
        if (!hasUsableBody(articles, bodies)) {
            return joinedTitles(articles) to articles.first().title.replaceFirstChar { it.uppercase() }
        }
        val reduceText = buildReduceText(articles, bodies)
        val summary = runSummarization(reduceText, MAX_SUMMARY_TOKENS, trimToSentence = true)
        val title = runSummarization(reduceText, MAX_TITLE_TOKENS, trimToSentence = false)
            .replaceFirstChar { it.uppercase() }
        return summary to title
    }

    private fun hasUsableBody(articles: List<Article>, bodies: Map<String, String>): Boolean =
        articles.any { !bodies[it.url].isNullOrBlank() }

    private fun joinedTitles(articles: List<Article>): String =
        articles.joinToString(" ") { it.title }.trim()

    /** Map-reduce so every cluster article reaches the model despite the 512-token input
     *  cap. Map: condense each article's title+body alone. Reduce: feed the collected
     *  mini-summaries back in as one document. A single-article cluster skips the map
     *  stage — there's nothing to combine and double-summarizing only loses detail. */
    private fun buildReduceText(articles: List<Article>, bodies: Map<String, String>): String {
        if (articles.size <= 1) return buildInput(articles, bodies)
        val miniSummaries = articles.map { article ->
            val body = bodies[article.url].orEmpty().take(MAX_INPUT_CHARS)
            val input = "summarize: ${article.title}. $body".trim()
            runSummarization(input, MAX_MINI_SUMMARY_TOKENS, trimToSentence = true)
        }
        return "summarize: " + miniSummaries.joinToString(" ")
    }

    /** t5-small is near-extractive and frequently emits a task label at the very start
     *  of its output — and not always in English: "Consummarize:", "Abrégé:" (French),
     *  "Zusammenfassung:" (German). These also poison mini-summaries feeding the reduce
     *  stage. Strip a leading single-word "label:" tag (colon within the first 20 chars,
     *  containing no internal whitespace or sentence punctuation — also covers a space
     *  before the colon, e.g. "Folgende :"). Real prose that merely contains a colon
     *  ("Monday: markets fell") keeps its colon because the label has internal spaces. */
    private fun stripInstructionEcho(text: String): String {
        val colon = text.indexOf(':')
        if (colon in 1..20) {
            val label = text.substring(0, colon).trim()
            if (label.isNotEmpty() && label.none { it.isWhitespace() || it in ".!?," }) {
                return text.substring(colon + 1).trimStart()
            }
        }
        return text
    }

    /** Drop a trailing partial sentence left when generation stops at the token cap
     *  before emitting EOS. Trims back to the last sentence terminator so the summary
     *  never ends mid-word. No-op if there's no terminator (e.g. short headlines). */
    private fun trimToLastSentence(text: String): String {
        val end = text.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end >= 0) text.substring(0, end + 1) else text
    }

    private fun buildInput(articles: List<Article>, bodies: Map<String, String>): String {
        // T5 takes the "summarize:" instruction once at the start, not per segment.
        // Split the input budget fairly across articles so one long body can't crowd the
        // others out before the tokenizer's MAX_INPUT_TOKENS cut-off.
        val perArticleChars = (MAX_INPUT_CHARS / articles.size.coerceAtLeast(1)).coerceAtLeast(400)
        val joined = articles.joinToString("\n\n") { article ->
            val body = bodies[article.url]?.take(perArticleChars).orEmpty()
            "${article.title}. $body".trim()
        }
        return "summarize: $joined"
    }

    private fun runSummarization(inputText: String, maxOutputTokens: Int, trimToSentence: Boolean): String {
        return try {
            val (inputIds, attMask) = tokenizer.encode(inputText, MAX_INPUT_TOKENS)
            val seqLen = inputIds.size.toLong()

            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen))
            val attMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask), longArrayOf(1, seqLen))

            val encoderOutputs = synchronized(OnnxGate.lock) {
                encoderSession.run(
                    mapOf("input_ids" to inputIdsTensor, "attention_mask" to attMaskTensor)
                )
            }
            val encoderHidden = encoderOutputs[0].value // float[1, seqLen, hiddenDim] (512 for t5-small)

            inputIdsTensor.close()
            attMaskTensor.close()

            val encoderHiddenTensor = OnnxTensor.createTensor(env, encoderHidden as Array<*>)
            val encoderMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask), longArrayOf(1, seqLen))

            val generatedIds = mutableListOf(T5Tokenizer.PAD_ID)
            var hitTokenCap = true

            for (step in 0 until maxOutputTokens) {
                val decIds = generatedIds.toLongArray()
                val decLen = decIds.size.toLong()
                val decIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decIds), longArrayOf(1, decLen))

                val decOutputs = synchronized(OnnxGate.lock) {
                    decoderSession.run(
                        mapOf(
                            "input_ids" to decIdsTensor,
                            "encoder_hidden_states" to encoderHiddenTensor,
                            "encoder_attention_mask" to encoderMaskTensor
                        )
                    )
                }
                val logits = decOutputs[0].value as Array<*> // [1, decLen, vocabSize]
                val lastLogits = ((logits[0] as Array<*>)[decLen.toInt() - 1]) as FloatArray
                // Greedy decoding loops ("X. X. X."); block any token that would repeat an
                // already-emitted 3-gram so the summary keeps moving forward.
                for (banned in bannedNgramTokens(generatedIds, NO_REPEAT_NGRAM)) {
                    lastLogits[banned.toInt()] = Float.NEGATIVE_INFINITY
                }
                val nextId = lastLogits.indices.maxByOrNull { lastLogits[it] }?.toLong() ?: T5Tokenizer.EOS_ID

                decIdsTensor.close()
                decOutputs.close()

                if (nextId == T5Tokenizer.EOS_ID) { hitTokenCap = false; break }
                generatedIds.add(nextId)
            }

            encoderHiddenTensor.close()
            encoderMaskTensor.close()
            encoderOutputs.close()

            // generatedIds[0] is the decoder start token (PAD_ID); skip it so decode()
            // doesn't break immediately on the leading PAD and return an empty string.
            val decoded = stripInstructionEcho(tokenizer.decode(generatedIds.drop(1).toLongArray()))
            if (trimToSentence && hitTokenCap) trimToLastSentence(decoded) else decoded
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed: ${e.message}")
            "Summary unavailable."
        }
    }

    fun close() {
        encoderSession.close()
        decoderSession.close()
    }
}
