package us.wangxy.voicebook.voice.routing

import us.wangxy.voicebook.voice.engine.VoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

enum class RoutingPolicy {
    LocalOnly,
    RemoteOnly,

    /** Local first, cloud when the local engine is unavailable or fails. */
    PreferLocal,

    /** Cloud first, local when offline or the cloud call fails. */
    PreferRemote,
}

interface NetworkMonitor {
    val isOnline: Boolean
}

object AssumeOnline : NetworkMonitor {
    override val isOnline: Boolean = true
}

/** Runtime-switchable routing policies, e.g. bound to a settings screen. */
class VoiceRoutingSettings(
    asrPolicy: RoutingPolicy = RoutingPolicy.PreferLocal,
    ttsPolicy: RoutingPolicy = RoutingPolicy.PreferLocal,
) {
    val asrPolicy = MutableStateFlow(asrPolicy)
    val ttsPolicy = MutableStateFlow(ttsPolicy)
}

/**
 * Orders the local and remote engine of one capability according to the current policy and
 * drops the ones that are not available right now.
 */
class EngineRouter<E : VoiceEngine>(
    private val local: E?,
    private val remote: E?,
    private val policy: () -> RoutingPolicy,
) {
    suspend fun candidates(): List<E> {
        val ordered = when (policy()) {
            RoutingPolicy.LocalOnly -> listOfNotNull(local)
            RoutingPolicy.RemoteOnly -> listOfNotNull(remote)
            RoutingPolicy.PreferLocal -> listOfNotNull(local, remote)
            RoutingPolicy.PreferRemote -> listOfNotNull(remote, local)
        }
        return ordered.filter { engine ->
            try {
                engine.isAvailable()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }
    }
}
