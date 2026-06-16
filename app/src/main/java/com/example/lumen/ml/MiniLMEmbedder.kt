package com.example.lumen.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.LongBuffer
import kotlin.math.sqrt

class MiniLMEmbedder(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer = MiniLMTokenizer(context)
    private val hasTokenTypeIds: Boolean

    init {
        val modelBytes = context.assets.open("minilm.onnx").readBytes()
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
        hasTokenTypeIds = session.inputNames.contains("token_type_ids")
        Log.d("Lumen", "ONNX model loaded. Inputs: ${session.inputNames}, hasTokenTypeIds=$hasTokenTypeIds")
    }

    @Synchronized
    fun embed(text: String): FloatArray? {
        return try {
            val tokens = tokenizer.tokenize(text)
            val seqLen = 128L

            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.inputIds),
                longArrayOf(1, seqLen)
            )
            val attMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.attentionMask),
                longArrayOf(1, seqLen)
            )

            val inputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attMaskTensor
            )

            val tokenTypeTensor = if (hasTokenTypeIds) {
                val t = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(tokens.tokenTypeIds),
                    longArrayOf(1, seqLen)
                )
                inputs["token_type_ids"] = t
                t
            } else null

            val output = session.run(inputs)

            // last_hidden_state: shape [1, 128, 384], mean pooling over sequence dimension
            val hiddenState = (output[0].value as Array<*>)[0] as Array<*>
            val attMask = tokens.attentionMask

            val embedding = FloatArray(384)
            var validTokens = 0f

            for (i in hiddenState.indices) {
                if (attMask[i] == 1L) {
                    val tokenVec = hiddenState[i] as FloatArray
                    for (j in 0 until 384) embedding[j] += tokenVec[j]
                    validTokens++
                }
            }

            if (validTokens > 0) {
                for (j in 0 until 384) embedding[j] /= validTokens
            }

            inputIdsTensor.close()
            attMaskTensor.close()
            tokenTypeTensor?.close()
            output.close()

            normalize(embedding)
        } catch (e: Exception) {
            Log.e("Lumen", "Embed failed for '$text': ${e.message}")
            null
        }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        // vectors are normalized so dot product = cosine similarity
        return a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
    }

    private fun normalize(v: FloatArray): FloatArray {
        val mag = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (mag == 0f) v else FloatArray(v.size) { v[it] / mag }
    }

    fun close() {
        session.close()
        env.close()
    }
}