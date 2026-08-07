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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.runtime.OperationWorker

@HiltViewModel
class AuthorListViewModel @Inject constructor(
    application: Application,
    private val repository: AuthorListRepository,
) : AndroidViewModel(application) {

    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = messages.asSharedFlow()

    val count: StateFlow<Int> =
        repository.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val nicks: StateFlow<List<String>> =
        repository.nicks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(text: String) = apply(text, replace = true)

    fun append(text: String) = apply(text, replace = false)

    fun clear() {
        viewModelScope.launch {
            repository.clear()
            messages.tryEmit(getApplication<Application>().getString(R.string.author_list_cleared))
        }
    }

    /**
     * Reads a picked file.
     *
     * Parsed to completion before anything is written, which is what makes a
     * replace atomic: a malformed line at row 900 cannot leave the user with 899
     * authors and no way back.
     */
    fun importFrom(replace: Boolean, open: () -> InputStream?) {
        viewModelScope.launch {
            val parsed = runCatching {
                withContext(Dispatchers.IO) {
                    open()?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }
            parsed.fold(
                onSuccess = { text -> if (text != null) apply(text, replace) },
                onFailure = { messages.tryEmit(failure(it)) },
            )
        }
    }

    private fun apply(text: String, replace: Boolean) {
        viewModelScope.launch {
            val result = CsvCodec.parseImport(text)
            runCatching {
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
    fun run(mode: BanMode, targetType: TargetType) {
        viewModelScope.launch {
            val nicks = nicks.value.ifEmpty { repository.nicksNow() }
            if (nicks.isEmpty()) {
                messages.tryEmit(getApplication<Application>().getString(R.string.author_list_empty))
                return@launch
            }
            OperationWorker.enqueue(
                WorkManager.getInstance(getApplication()),
                operationId = UUID.randomUUID().toString(),
                request = OperationRequest(
                    source = BanSource.LIST,
                    mode = mode,
                    targetType = targetType,
                    nicks = nicks,
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
