package com.example.lumen.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.LongBuffer

class BiasAnalyzer(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: BiasBertTokenizer

    private val labels = listOf("left", "center", "right")

    init {
        val fileDescriptor = context.assets.openFd("bias_bert.onnx")
        val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
        val modelBuffer = inputStream.channel.map(
            java.nio.channels.FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )

        // converti MappedByteBuffer in ByteArray
        val modelBytes = ByteArray(modelBuffer.remaining())
        modelBuffer.get(modelBytes)

        val sessionOptions = OrtSession.SessionOptions()
        session = env.createSession(modelBytes, sessionOptions)
        tokenizer = BiasBertTokenizer(context)
        Log.d("Lumen", "BiasAnalyzer loaded. Inputs: ${session.inputNames}")
    }

    @Synchronized
    fun analyze(text: String): BiasResult {
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
            val tokenTypeTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.tokenTypeIds),
                longArrayOf(1, seqLen)
            )

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attMaskTensor,
                "token_type_ids" to tokenTypeTensor
            )

            val output = synchronized(OnnxGate.lock) { session.run(inputs) }

            // logits shape [1, 3]
            val logits = ((output[0].value as Array<*>)[0]) as FloatArray

            inputIdsTensor.close()
            attMaskTensor.close()
            tokenTypeTensor.close()
            output.close()

            val softmax = softmax(logits)
            val maxIndex = softmax.indices.maxByOrNull { softmax[it] } ?: 1

            Log.d("Lumen", "Bias — left:${"%.2f".format(softmax[0])} center:${"%.2f".format(softmax[1])} right:${"%.2f".format(softmax[2])}")

            BiasResult(
                label = labels[maxIndex],
                score = softmax[maxIndex],
                leftScore = softmax[0],
                centerScore = softmax[1],
                rightScore = softmax[2]
            )

        } catch (e: Exception) {
            Log.e("Lumen", "BiasAnalyzer failed: ${e.message}")
            BiasResult("center", 0f, 0f, 1f, 0f)
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exp = logits.map { Math.exp((it - max).toDouble()).toFloat() }
        val sum = exp.sum()
        return exp.map { it / sum }.toFloatArray()
    }

    fun close() {
        session.close()
    }
}

data class BiasResult(
    val label: String,
    val score: Float,
    val leftScore: Float,
    val centerScore: Float,
    val rightScore: Float
)