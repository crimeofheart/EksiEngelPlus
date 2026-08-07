package org.duzgun.eksiengelplus.feature.lists

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The busy flag guards an all-or-nothing write.
 *
 * A second tap landing on top of the first is not a race to survive but an action
 * to refuse -- two replaces racing would leave whichever transaction committed
 * last, which is not what either tap asked for. The flag stranding on true is the
 * worse failure of the two, since only reopening the screen would clear it.
 *
 * runBlocking rather than runTest: the work runs on the view model's own scope
 * with real dispatchers, so runTest's virtual clock expires a timeout before any
 * of it has happened.
 */
@RunWith(AndroidJUnit4::class)
class AuthorListBusyStateTest {

    private lateinit var db: EksiDatabase
    private lateinit var model: AuthorListViewModel

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, EksiDatabase::class.java).build()
        model = AuthorListViewModel(app, AuthorListRepository(db))
    }

    @After fun tearDown() = db.close()

    private fun stream(text: String): InputStream = ByteArrayInputStream(text.toByteArray())

    /**
     * Runs [action] and returns every value the flag took while it ran.
     *
     * The collector starts first, so a flag that went true and back is observed
     * rather than missed -- without that, a test asserting only the final value
     * passes even if the flag was never set at all.
     */
    private fun observingBusy(action: () -> Unit): List<Boolean> = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())
        val collector: Job = launch { model.busy.collect { seen += it } }
        // Let the collector attach and record the initial false.
        withTimeout(TIMEOUT) { while (seen.isEmpty()) delay(POLL) }

        action()

        withTimeout(TIMEOUT) {
            while (!(seen.contains(true) && !model.busy.value)) delay(POLL)
        }
        collector.cancel()
        seen
    }

    private fun nicks() = runBlocking { db.authorList().getAll().map { it.nick } }

    @Test
    fun busyRisesAndClearsAroundASuccessfulImport() {
        val seen = observingBusy { model.importFrom(replace = true) { stream("birisi\nbaskasi") } }

        assertThat(seen).contains(true)
        assertThat(model.busy.value).isFalse()
        assertThat(nicks()).containsExactly("birisi", "baskasi").inOrder()
    }

    @Test
    fun busyClearsAfterAFailedRead() {
        // The flag must clear even though the work threw, or the screen stays dead.
        val seen = observingBusy { model.importFrom(replace = true) { throw IOException("cannot open") } }

        assertThat(seen).contains(true)
        assertThat(model.busy.value).isFalse()
        assertThat(nicks()).isEmpty()
    }

    @Test
    fun aDismissedPickerIsNotAFailureAndLeavesTheListAlone() {
        observingBusy { model.importFrom(replace = true) { stream("birisi") } }

        observingBusy { model.importFrom(replace = true) { null } }

        assertThat(model.busy.value).isFalse()
        assertThat(nicks()).containsExactly("birisi")
    }

    @Test
    fun clearAlsoSettlesTheFlag() {
        observingBusy { model.importFrom(replace = true) { stream("birisi") } }

        observingBusy { model.clear() }

        assertThat(nicks()).isEmpty()
        assertThat(model.busy.value).isFalse()
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL = 10L
    }
}
