package org.duzgun.eksiengelplus.feature.lists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.runtime.OperationWorker

@HiltViewModel
class AuthorListViewModel @Inject constructor(
    application: Application,
    private val repository: AuthorListRepository,
    private val db: EksiDatabase,
) : AndroidViewModel(application) {

    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = messages.asSharedFlow()

    /**
     * True while a read or a write is in flight.
     *
     * Every mutation here is all-or-nothing, so a second one landing on top of the
     * first is not a race to survive but an action to refuse: two replaces racing
     * would leave whichever transaction committed last, which is not what either
     * tap asked for.
     */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val count: StateFlow<Int> =
        repository.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val nicks: StateFlow<List<String>> =
        repository.nicks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(text: String) = apply(text, replace = true)

    fun append(text: String) = apply(text, replace = false)

    fun clear() = launchBusy {
        repository.clear()
        messages.tryEmit(getApplication<Application>().getString(R.string.author_list_cleared))
    }

    /**
     * Reads a picked file.
     *
     * Parsed to completion before anything is written, which is what makes a
     * replace atomic: a malformed line at row 900 cannot leave the user with 899
     * authors and no way back.
     */
    fun importFrom(replace: Boolean, open: () -> InputStream?) {
        launchBusy {
            val text = runCancellable {
                withContext(Dispatchers.IO) {
                    open()?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }.getOrElse {
                messages.tryEmit(failure(it))
                return@launchBusy
            }
            // null is a dismissed picker, not a failure.
            if (text != null) write(text, replace)
        }
    }

    private fun apply(text: String, replace: Boolean) = launchBusy { write(text, replace) }

    private suspend fun write(text: String, replace: Boolean) {
        // Parsing is off the main thread with the write: a large pasted list is
        // enough work to drop frames, and it is the half that runs before anything
        // is committed.
        val result = withContext(Dispatchers.IO) { CsvCodec.parseImport(text) }
        runCancellable {
            if (replace) repository.replaceAll(result.rows) else repository.append(result.rows)
        }.fold(
            onSuccess = {
                messages.tryEmit(
                    getApplication<Application>().getString(
                        R.string.author_list_imported,
                        result.rows.size,
                        result.skippedLines,
                        result.datesRecognised,
                    ),
                )
            },
            onFailure = { messages.tryEmit(failure(it)) },
        )
    }

    /** Refuses to start while something else is running, and always clears after. */
    private fun launchBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Enqueues a run against the list.
     *
     * The nicks are resolved into the request here rather than read by the task,
     * so the set is fixed at enqueue time. That is not a convenience: the request
     * is serialised into the checkpoint, and TargetRunner checkpoints by index, so
     * a resumed run that re-read the table could pick up at the wrong position in a
     * list the user edited in between.
     */
    fun run(mode: BanMode, targetType: TargetType, thenApplyTo: TargetType? = null) {
        viewModelScope.launch {
            val nicks = nicks.value.ifEmpty { repository.nicksNow() }
            if (nicks.isEmpty()) {
                messages.tryEmit(getApplication<Application>().getString(R.string.author_list_empty))
                return@launch
            }
            OperationWorker.enqueue(
                WorkManager.getInstance(getApplication()),
                db = db,
                operationId = UUID.randomUUID().toString(),
                request = OperationRequest(
                    source = BanSource.LIST,
                    mode = mode,
                    targetType = targetType,
                    nicks = nicks,
                    thenApplyTo = thenApplyTo,
                ),
            )
            messages.tryEmit(
                getApplication<Application>().getString(R.string.author_list_enqueued, nicks.size),
            )
        }
    }

    private fun failure(cause: Throwable) = getApplication<Application>()
        .getString(R.string.author_list_failed, cause.message ?: "bilinmeyen hata")
}
