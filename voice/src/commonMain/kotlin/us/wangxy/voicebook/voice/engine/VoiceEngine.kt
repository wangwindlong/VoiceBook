package us.wangxy.voicebook.voice.engine

enum class EngineKind { Local, Remote }

/** A concrete backend (sherpa-onnx, a cloud API, ...) that the router can choose from. */
interface VoiceEngine {
    val id: String
    val kind: EngineKind

    /** Cheap check: models loaded / endpoint configured / network reachable. */
    suspend fun isAvailable(): Boolean
}

open class VoiceEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NoEngineAvailableException(what: String) : VoiceEngineException("No $what engine is available for the current routing policy")
