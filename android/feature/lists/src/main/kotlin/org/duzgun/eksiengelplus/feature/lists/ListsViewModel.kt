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
import org.duzgun.eksiengelplus.datastore.DateBulkPrefs
import org.duzgun.eksiengelplus.model.DateBulkSource
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
    private val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val message: SharedFlow<UiMessage> = messages.asSharedFlow()

    /**
     * Says something to the user.
     *
     * [showOperations] offers a way through to İşlem durumu, and belongs only on
     * messages about a run that has just been handed off -- the screen the user
     * is on shows no trace of it, so without this the next step is a hunt
     * through the menu.
     */
    private fun say(text: String, showOperations: Boolean = false) {
        messages.tryEmit(UiMessage(text, showOperations))
    }


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

    /**
     * Every list at once, for the swipe-down gesture.
     *
     * Gated on the same condition the per-row menu uses -- a running operation is
     * already holding itself under the server's rate ceiling, and four unpaced
     * syncs on top of it is the one thing that gate exists to prevent. Lists
     * already syncing are skipped rather than re-enqueued: the worker is unique
     * per list with a KEEP policy, so a second enqueue would be dropped silently
     * and the gesture would look like it did something it did not.
     *
     * Returns whether anything was started, so the screen can stop a spinner it
     * raised for work that is not going to happen.
     */
    fun refreshAll(): Boolean {
        val current = state.value
        if (current.operationRunning) {
            say(string(R.string.lists_operation_running))
            return false
        }
        val idle = current.rows.filterNot { it.sync.isActive }
        // Nothing idle means all four are already going: the gesture asked for a
        // refresh and a refresh is what is happening, so the spinner stays up and
        // render() takes it down when the last one lands.
        if (idle.isEmpty()) return current.rows.isNotEmpty()
        idle.forEach { ListSyncWorker.enqueue(workManager, it.listType) }
        return true
    }

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
                        say(string(R.string.lists_exported, written))
                    }
                },
                onFailure = {
                    say(
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
     * The date-filtered run, composed from the three choices the chooser offers.
     *
     * The criterion travels in the request rather than being read back out of
     * settings: those rules are standing protection for every operation, and a
     * one-off "unblock everyone I blocked before 2020" that edited them would
     * disarm that permanently. Remembering the composition is config, not a rule
     * — see EksiConfig.dateBulk.
     */
    /** What the chooser was last set to, so it opens where the user left it. */
    suspend fun dateBulkPrefs(): DateBulkPrefs = configRepository.config.first().dateBulk

    fun runDateBased(prefs: DateBulkPrefs) {
        viewModelScope.launch {
            val rule = prefs.toRule()
            if (rule == null) {
                // A criterion with no value allows everything, so the run would
                // act on the whole list -- the opposite of what someone reaching
                // for a date filter is asking for.
                say(string(R.string.bulk_date_needs_filter))
                return@launch
            }

            configRepository.update { it.copy(dateBulk = prefs) }

            val action = prefs.action
            val request = if (prefs.source == DateBulkSource.AUTHOR_LIST) {
                val nicks = db.authorList().getAll().map { it.nick }
                if (nicks.isEmpty()) {
                    say(string(R.string.bulk_date_needs_author_list))
                    return@launch
                }
                /*
                 * LIST, not DATE_BASED_BULK. ban_source integers are rows in the
                 * shared backend, and this is the author list being run -- which
                 * is what every other author-list run reports.
                 */
                OperationRequest(
                    source = BanSource.LIST,
                    mode = action.mode,
                    targetType = action.target,
                    thenApplyTo = action.then,
                    nicks = nicks,
                    dateRule = rule,
                )
            } else {
                OperationRequest(
                    source = BanSource.DATE_BASED_BULK,
                    mode = action.mode,
                    targetType = action.target,
                    thenApplyTo = action.then,
                    relationListOf = prefs.source.relationList,
                    dateRule = rule,
                )
            }

            OperationWorker.enqueue(
                workManager,
                db = db,
                operationId = java.util.UUID.randomUUID().toString(),
                request = request,
            )
            say(string(R.string.lists_run_started), showOperations = true)
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
            say(string(R.string.lists_run_started), showOperations = true)
        }
    }

    private fun string(id: Int, vararg args: Any) =
        getApplication<Application>().getString(id, *args)
}
