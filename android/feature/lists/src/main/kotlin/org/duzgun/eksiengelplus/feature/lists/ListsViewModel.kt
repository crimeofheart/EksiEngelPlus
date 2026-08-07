package org.duzgun.eksiengelplus.feature.lists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.model.ListType
import org.duzgun.eksiengelplus.ops.engine.OperationState

/** What one list row shows. */
data class ListRowState(
    val listType: ListType,
    val count: Int,
    val isPartial: Boolean,
    val lastFullRefreshAt: Long?,
)

data class ListsUiState(
    val rows: List<ListRowState> = emptyList(),
    /**
     * True while a blocking operation is running. Refresh is gated on it: both
     * share the session and the HTTP stack, and reads are not paced, so a sync
     * would add request pressure to a run that is deliberately staying under the
     * server's ceiling.
     */
    val operationRunning: Boolean = false,
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    application: Application,
    private val db: EksiDatabase,
) : AndroidViewModel(application) {

    private val workManager get() = WorkManager.getInstance(getApplication())

    /** One-shot messages for the screen: export finished, export failed, and so on. */
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = messages.asSharedFlow()

    val state: StateFlow<ListsUiState> = combine(
        rowFlow(ListType.BLOCKED),
        rowFlow(ListType.MUTED),
        rowFlow(ListType.FOLLOWED),
        db.checkpoints().countWithState(OperationState.RUNNING.name).map { it > 0 },
    ) { blocked, muted, followed, running ->
        ListsUiState(listOf(blocked, muted, followed), running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

    private fun rowFlow(listType: ListType) = combine(
        db.relationUsers().countOf(listType),
        db.listSyncState().observe(listType),
    ) { count, sync ->
        ListRowState(
            listType = listType,
            count = count,
            isPartial = sync?.isPartial ?: false,
            lastFullRefreshAt = sync?.lastFullRefreshAt,
        )
    }

    fun refresh(listType: ListType) = ListSyncWorker.enqueue(workManager, listType)

    fun stop(listType: ListType) = ListSyncWorker.stop(workManager, listType)

    /**
     * Streams the list to [open]'s stream, joining registration dates in.
     *
     * The stream is opened by the caller because only an Activity holds the SAF
     * grant; everything after that is off the main thread.
     */
    fun export(listType: ListType, open: () -> OutputStream?) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val users = db.relationUsers().get(listType)
                    val rows = users.map { user ->
                        val cached = db.registrationDates().get(user.nick)
                        CsvCodec.Row(user.nick, user.registrationDate ?: cached?.registrationEpochDay)
                    }
                    val stream = open() ?: return@withContext null
                    stream.use { CsvCodec.writeExport(rows, it) }
                    rows.size
                }
            }
            result.fold(
                onSuccess = { written ->
                    // null means the user dismissed the picker, which is not an event.
                    if (written != null) messages.tryEmit("$written kayıt dışa aktarıldı.")
                },
                onFailure = { messages.tryEmit("dışa aktarma başarısız: ${it.message ?: "bilinmeyen hata"}") },
            )
        }
    }
}
