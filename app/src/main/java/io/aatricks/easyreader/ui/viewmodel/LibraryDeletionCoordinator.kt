package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.repository.ContentRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns library deletion with a 5s undo window, extracted from LibraryViewModel. Items are marked
 * pending (and hidden by the ViewModel's state combine) until the window elapses, then committed
 * to the repository and cache. [onError] reports a user-facing message back to the ViewModel.
 */
class LibraryDeletionCoordinator(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val contentRepository: ContentRepository,
    private val onError: (String) -> Unit,
    private val onItemsRemoved: (List<String>) -> Unit,
) {
    private val _pendingDeletion = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeletion: StateFlow<Set<String>> = _pendingDeletion.asStateFlow()

    private var pendingDeleteJob: Job? = null
    private var pendingDeleteUrls: List<String> = emptyList()

    companion object {
        private const val UNDO_DELETE_WINDOW_MS = 5000L
    }

    fun schedule(ids: Set<String>) {
        if (ids.isEmpty()) return
        val items = repository.libraryItems.value
        val urls = ids.mapNotNull { id -> items.firstOrNull { it.id == id }?.url }
        pendingDeleteJob?.cancel()
        pendingDeleteUrls = (pendingDeleteUrls + urls).distinct()
        _pendingDeletion.update { it + ids }
        pendingDeleteJob = scope.launch {
            delay(UNDO_DELETE_WINDOW_MS)
            commit()
        }
    }

    fun undo() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        _pendingDeletion.value = emptySet()
        pendingDeleteUrls = emptyList()
    }

    private suspend fun commit() {
        val ids = _pendingDeletion.value
        if (ids.isEmpty()) return
        val urls = pendingDeleteUrls
        runCatching {
            contentRepository.clearCachesAndDownloadsForUrls(urls)
            repository.removeItems(ids)
        }.onSuccess {
            onItemsRemoved(urls)
        }.onFailure { e ->
            onError("Failed to remove items: ${e.message}")
        }
        _pendingDeletion.value = emptySet()
        pendingDeleteUrls = emptyList()
        pendingDeleteJob = null
    }

    fun flush() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        scope.launch { commit() }
    }

    fun removeImmediate(ids: Set<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            val urls = repository.libraryItems.value
                .asSequence()
                .filter { it.id in ids }
                .map { it.url }
                .toList()
            runCatching {
                contentRepository.clearCachesAndDownloadsForUrls(urls)
                repository.removeItems(ids)
            }.onSuccess {
                onItemsRemoved(urls)
            }.onFailure { e ->
                onError("Failed to remove items: ${e.message}")
            }
        }
    }
}
