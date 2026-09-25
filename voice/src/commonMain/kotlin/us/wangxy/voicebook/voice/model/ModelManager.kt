package us.wangxy.voicebook.voice.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ModelSetupState {
    /** This platform has no local engines, so there is nothing to download. */
    data object Unsupported : ModelSetupState

    data class Missing(val downloadBytes: Long) : ModelSetupState

    data class Downloading(
        val model: String,
        val index: Int,
        val count: Int,
        val bytes: Long,
        val total: Long?,
    ) : ModelSetupState

    data class Extracting(val model: String, val index: Int, val count: Int, val fraction: Float) : ModelSetupState

    data object Ready : ModelSetupState

    data class Failed(val message: String) : ModelSetupState
}

/**
 * Installs the local models once, in an app-lifetime [scope] so leaving the screen doesn't
 * cancel a download. [onReady] runs after a download completes (not if everything was present).
 */
class ModelManager(
    private val repository: ModelRepository?,
    val artifacts: List<ModelArtifact>,
    private val onReady: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(diskState())
    val state: StateFlow<ModelSetupState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts downloading whatever is missing; no-op while running or when already installed. */
    fun ensureModels() {
        val repo = repository ?: return
        if (job?.isActive == true || _state.value == ModelSetupState.Ready) return
        job = scope.launch {
            try {
                val missing = artifacts.filterNot(repo::isReady)
                missing.forEachIndexed { i, artifact ->
                    repo.ensure(artifact) { progress ->
                        _state.value = when (progress) {
                            is ModelProgress.Downloading ->
                                ModelSetupState.Downloading(artifact.name, i + 1, missing.size, progress.bytes, progress.total)
                            is ModelProgress.Extracting ->
                                ModelSetupState.Extracting(artifact.name, i + 1, missing.size, progress.fraction)
                        }
                    }
                }
                _state.value = ModelSetupState.Ready
                if (missing.isNotEmpty()) onReady()
            } catch (e: CancellationException) {
                _state.value = diskState()
                throw e
            } catch (e: Exception) {
                _state.value = ModelSetupState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun diskState(): ModelSetupState {
        val repo = repository ?: return ModelSetupState.Unsupported
        val missing = artifacts.filterNot(repo::isReady)
        return if (missing.isEmpty()) ModelSetupState.Ready else ModelSetupState.Missing(missing.sumOf { it.downloadBytes })
    }
}
