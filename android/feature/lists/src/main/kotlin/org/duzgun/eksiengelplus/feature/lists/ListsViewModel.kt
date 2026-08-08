package org.duzgun.eksiengelplus.feature.lists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.ListType
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.runtime.OperationWorker
import org.duzgun.eksiengelplus.ops.engine.OperationState

/**
 * Where a list's sync currently is.
 *
 * Queued and Running are distinct because the difference is the user's question:
 * a sync waiting on a network constraint has not stalled, and a spinner that
 * cannot tell them apart says nothing.
 */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Queued : SyncStatus
    /** [progress] is null until the first page lands. */
    data class Running(val progress: SyncProgress?) : SyncStatus

    val isActive: Boolean get() = this !is Idle
}

/** What one list row shows. */
data class ListRowState(
    val listType: ListType,
    val count: Int,
    val isPartial: Boolean,
    val lastFullRefreshAt: Long?,
    val sync: SyncStatus = SyncStatus.Idle,
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
    /** Which list is mid-export, if any. */
    val exporting: ListType? = null,
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    application: Application,
    private val db: EksiDatabase,
    private val configRepository: org.duzgun.eksiengelplus.datastore.ConfigRepository,
) : AndroidViewModel(application) {

    private val workManager get() = WorkManager.getInstance(getApplication())

    /** One-shot messages for the screen: export finished, export failed, and so on. */
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = messages.asSharedFlow()

    /**
     * The list whose export is in flight.
     *
     * Held here rather than in the Activity because an export outlives a rotation:
     * the coroutine is on the view model's scope, so the busy state has to live
     * where the work does.
     */
    private val exportingList = MutableStateFlow<ListType?>(null)

    val state: StateFlow<ListsUiState> = combine(
        rowFlow(ListType.BLOCKED),
        rowFlow(ListType.MUTED),
        rowFlow(ListType.FOLLOWED),
        rowFlow(ListType.TITLE_BANNED),
        db.checkpoints().countWithState(OperationState.RUNNING.name).map { it > 0 },
        exportingList,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ListsUiState(
            rows = values.take(4).map { it as ListRowState },
            operationRunning = values[4] as Boolean,
            exporting = values[5] as ListType?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

    private fun rowFlow(listType: ListType) = combine(
        db.relationUsers().countOf(listType),
        db.listSyncState().observe(listType),
        syncStatusFlow(listType),
    ) { count, sync, status ->
        ListRowState(
            listType = listType,
            count = count,
            isPartial = sync?.isPartial ?: false,
            lastFullRefreshAt = sync?.lastFullRefreshAt,
            sync = status,
        )
    }

    /**
     * Live state of the sync worker, straight from WorkManager.
     *
     * WorkManager is the authority on whether the work is running -- it survives
     * process death and the view model does not -- so asking it is the only answer
     * that stays true after the screen is reopened mid-sync.
     */
    private fun syncStatusFlow(listType: ListType): Flow<SyncStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(ListSyncWorker.uniqueWorkName(listType))
            .map { infos ->
                val live = infos.firstOrNull { !it.state.isFinished }
                when (live?.state) {
                    null -> SyncStatus.Idle
                    WorkInfo.State.RUNNING -> SyncStatus.Running(ListSyncWorker.progressOf(live.progress))
                    else -> SyncStatus.Queued
                }
            }
            .distinctUntilChanged()

    /** For the card summaries, so each says what it holds without being opened. */
    val authorListCount: Flow<Int> = db.authorList().count()

    val operationSummary: Flow<Pair<Int, Int>> = combine(
        db.checkpoints().countWithState(OperationState.RUNNING.name),
        db.queuedTasks().count(),
    ) { live, queued -> live to queued }

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
            exportingList.value = listType
            val result = runCancellable {
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
            // Cleared before the message, and in a finally-shaped position rather
            // than on the success path: a throw that left the row busy forever
            // would need the screen reopened to clear.
            exportingList.value = null
            result.fold(
                onSuccess = { written ->
                    // null means the user dismissed the picker, which is not an event.
                    if (written != null) {
                        messages.tryEmit(string(R.string.lists_exported, written))
                    }
                },
                onFailure = {
                    messages.tryEmit(
                        string(R.string.lists_export_failed, it.message ?: string(R.string.lists_unknown_error)),
                    )
                },
            )
        }
    }

    /**
     * Runs one of the migration sources against a synced list.
     *
     * The nicks are resolved here and carried in the request, exactly as a LIST
     * run does, so a resumed operation replays the set it started with rather
     * than whatever the list has since become.
     *
     * The ban_source is the real one rather than LIST, because those integers are
     * rows in the shared backend and reporting a migration as a list run would
     * make the two clients disagree about what happened.
     */
    /**
     * Starts a bulk operation on the account's own list.
     *
     * No nicks are passed and no local list is consulted: the task fetches the
     * list when it runs. Reading our synced copy meant refusing with "list is
     * empty" whenever the user had not pressed refresh first, which described
     * our cache rather than their account.
     */
    /**
     * The date-filtered run, refused when there is no filter to apply.
     *
     * Without an enabled rule the filter allows everything, so this would act on
     * the whole list -- which is the opposite of what someone reaching for a
     * date filter is asking for.
     */
    fun runDateBased(mode: BanMode, targetType: TargetType) {
        viewModelScope.launch {
            val config = configRepository.config.first()
            if (!config.enableDateFilter || config.dateFilterRules.none { it.enabled }) {
                messages.tryEmit(string(R.string.bulk_date_needs_filter))
                return@launch
            }
            runOnList(BanSource.DATE_BASED_BULK, mode, targetType)
        }
    }

    fun runOnList(source: BanSource, mode: BanMode, targetType: TargetType) {
        viewModelScope.launch {
            OperationWorker.enqueue(
                WorkManager.getInstance(getApplication()),
                db = db,
                operationId = java.util.UUID.randomUUID().toString(),
                request = OperationRequest(source = source, mode = mode, targetType = targetType),
            )
            messages.tryEmit(string(R.string.lists_run_started))
        }
    }

    private fun string(id: Int, vararg args: Any) =
        getApplication<Application>().getString(id, *args)
}
