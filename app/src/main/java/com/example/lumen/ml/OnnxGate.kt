package com.example.lumen.ml

/**
 * Process-wide lock serializing every ONNX `OrtSession.run` call.
 *
 * All ONNX wrappers (MiniLMEmbedder, BiasAnalyzer, T5Summarizer) share a single
 * `OrtEnvironment` singleton. Running multiple sessions concurrently against that
 * shared environment on this build of onnxruntime can abort the process natively
 * (SIGABRT inside libonnxruntime). Guarding every `run` with this monitor guarantees
 * inference happens one at a time, across all models and threads.
 */
object OnnxGate {
    val lock = Any()
}
