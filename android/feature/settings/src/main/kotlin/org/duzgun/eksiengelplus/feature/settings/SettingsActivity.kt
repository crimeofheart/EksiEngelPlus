package org.duzgun.eksiengelplus.feature.settings

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import org.duzgun.eksiengelplus.ui.fitContentInsideSystemBars
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.duzgun.eksiengelplus.datastore.ConfigRepository
import org.duzgun.eksiengelplus.datastore.DateCriteria
import org.duzgun.eksiengelplus.datastore.DateFilterRule
import org.duzgun.eksiengelplus.datastore.EksiConfig

/**
 * The extension's options, on the same switches and in the same groups.
 *
 * Every value already existed in EksiConfig and was already read by the engine
 * and the bridge -- ported with android-foundations and then reachable only by
 * editing the store. This is the screen that was missing, not the settings.
 *
 * Writes go straight through on toggle rather than waiting for a save button:
 * there is nothing to validate across fields, and a settings screen that can be
 * abandoned half-applied is a worse contract than one that simply applies.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject lateinit var configRepository: ConfigRepository

    /**
     * True while the UI is being populated from stored config.
     *
     * setChecked fires the listener, so without this every render would write
     * back what it just read -- harmless but for the write amplification, and
     * genuinely wrong the moment a default differs from a stored value.
     */
    private var binding = false

    private lateinit var switches: List<Pair<MaterialSwitch, Binding>>

    /** How one switch reads and writes its field. */
    private class Binding(
        val read: (EksiConfig) -> Boolean,
        val write: (EksiConfig, Boolean) -> EksiConfig,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        fitContentInsideSystemBars()
        title = getString(R.string.settings_title)

        switches = listOf(
            bind(R.id.switchMute, { it.enableMute }, { c, v -> c.copy(enableMute = v) }),
            bind(R.id.switchTitleBan, { it.enableTitleBan }, { c, v -> c.copy(enableTitleBan = v) }),
            bind(R.id.switchNoobBan, { it.enableNoobBan }, { c, v -> c.copy(enableNoobBan = v) }),
            bind(
                R.id.switchProtectFollowed,
                { it.enableProtectFollowedUsers },
                { c, v -> c.copy(enableProtectFollowedUsers = v) },
            ),
            bind(
                R.id.switchOnlyRequired,
                { it.enableOnlyRequiredActions },
                { c, v -> c.copy(enableOnlyRequiredActions = v) },
            ),
            bind(
                R.id.switchAnalysis,
                { it.enableAnalysisBeforeOperation },
                { c, v -> c.copy(enableAnalysisBeforeOperation = v) },
            ),
            bind(R.id.switchPremiumIcons, { it.banPremiumIcons }, { c, v -> c.copy(banPremiumIcons = v) }),
            bind(R.id.switchAppPromo, { it.hideAppPromo }, { c, v -> c.copy(hideAppPromo = v) }),
            bind(R.id.switchBlockAds, { it.blockAds }, { c, v -> c.copy(blockAds = v) }),
            bind(R.id.switchDateFilter, { it.enableDateFilter }, { c, v -> c.copy(enableDateFilter = v) }),
            bind(R.id.switchSendData, { it.sendData }, { c, v -> c.copy(sendData = v) }),
            bind(R.id.switchSendLog, { it.sendLog }, { c, v -> c.copy(sendLog = v) }),
        )

        rulesSection = findViewById(R.id.rulesSection)
        rulesList = findViewById(R.id.rulesList)
        rulesEmpty = findViewById(R.id.rulesEmpty)
        findViewById<android.widget.Button>(R.id.rulesAdd).setOnClickListener { addRule() }

        cacheStats = findViewById(R.id.cacheStats)
        dbStats = findViewById(R.id.dbStats)
        findViewById<android.widget.Button>(R.id.clearCache).setOnClickListener { clearCache() }
        findViewById<android.widget.Button>(R.id.clearData).setOnClickListener { confirmClearData() }
        findViewById<android.widget.Button>(R.id.openReleaseNotes).setOnClickListener {
            startActivity(ReleaseNotesActivity.intent(this, appVersion()))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                configRepository.config.collect { config ->
                    binding = true
                    switches.forEach { (view, b) -> view.isChecked = b.read(config) }
                    renderRules(config)
                    binding = false
                }
            }
        }
    }

    // ------------------------------------------------------------- date rules

    private lateinit var rulesSection: android.view.ViewGroup
    private lateinit var rulesList: android.view.ViewGroup
    private lateinit var rulesEmpty: android.widget.TextView

    private val criteriaOrder = listOf(
        DateCriteria.NEWER_THAN,
        DateCriteria.OLDER_THAN,
        DateCriteria.BEFORE_DATE,
        DateCriteria.AFTER_DATE,
    )

    private fun criteriaLabels() = arrayOf(
        getString(R.string.settings_criteria_newer),
        getString(R.string.settings_criteria_older),
        getString(R.string.settings_criteria_before),
        getString(R.string.settings_criteria_after),
    )

    private fun addRule() {
        lifecycleScope.launch {
            configRepository.update { c ->
                c.copy(
                    dateFilterRules = c.dateFilterRules + DateFilterRule(
                        id = java.util.UUID.randomUUID().toString(),
                        criteria = DateCriteria.OLDER_THAN,
                        days = 30,
                    ),
                )
            }
        }
    }

    private fun mutateRule(id: String, change: (DateFilterRule) -> DateFilterRule) {
        lifecycleScope.launch {
            configRepository.update { c ->
                c.copy(dateFilterRules = c.dateFilterRules.map { if (it.id == id) change(it) else it })
            }
        }
    }

    private fun deleteRule(id: String) {
        lifecycleScope.launch {
            configRepository.update { c ->
                c.copy(dateFilterRules = c.dateFilterRules.filterNot { it.id == id })
            }
        }
    }

    /**
     * Rebuilds the rule rows from stored config.
     *
     * Rebuilt wholesale rather than diffed: the list is a handful of rows the
     * user edits by hand, and a diffing adapter here would be more machinery
     * than the thing it manages.
     */
    private fun renderRules(config: EksiConfig) {
        rulesSection.visibility = if (config.enableDateFilter) View.VISIBLE else View.GONE
        rulesEmpty.visibility = if (config.dateFilterRules.isEmpty()) View.VISIBLE else View.GONE
        rulesList.removeAllViews()

        for (rule in config.dateFilterRules) {
            val row = layoutInflater.inflate(R.layout.view_rule_row, rulesList, false)
            val enabled = row.findViewById<MaterialSwitch>(R.id.ruleEnabled)
            val criteria = row.findViewById<android.widget.Spinner>(R.id.ruleCriteria)
            val days = row.findViewById<android.widget.EditText>(R.id.ruleDays)
            val date = row.findViewById<android.widget.TextView>(R.id.ruleDate)

            enabled.isChecked = rule.enabled
            enabled.setOnCheckedChangeListener { _, checked ->
                if (!binding) mutateRule(rule.id) { it.copy(enabled = checked) }
            }

            criteria.adapter = android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                criteriaLabels(),
            )
            criteria.setSelection(criteriaOrder.indexOf(rule.criteria).coerceAtLeast(0))
            criteria.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val picked = criteriaOrder[position]
                        if (binding || picked == rule.criteria) return
                        // Switching between a day count and a date leaves the old
                        // value meaningless, so it is dropped rather than carried.
                        mutateRule(rule.id) {
                            it.copy(
                                criteria = picked,
                                days = if (picked.usesDays) it.days ?: 30 else null,
                                epochDay = if (picked.usesDays) null else it.epochDay,
                            )
                        }
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }

            days.visibility = if (rule.criteria.usesDays) View.VISIBLE else View.GONE
            date.visibility = if (rule.criteria.usesDays) View.GONE else View.VISIBLE

            days.setText(rule.days?.toString().orEmpty())
            days.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) return@setOnFocusChangeListener
                val v = days.text.toString().toIntOrNull()
                if (v != null && v != rule.days) mutateRule(rule.id) { it.copy(days = v) }
            }

            date.text = rule.epochDay
                ?.let { java.time.LocalDate.ofEpochDay(it).toString() }
                ?: getString(R.string.settings_rule_pick_date)
            date.setOnClickListener { pickDate(rule) }

            row.findViewById<android.widget.TextView>(R.id.ruleDelete)
                .setOnClickListener { deleteRule(rule.id) }

            rulesList.addView(row)
        }
    }

    private fun pickDate(rule: DateFilterRule) {
        val today = java.time.LocalDate.now()
        val start = rule.epochDay?.let { java.time.LocalDate.ofEpochDay(it) } ?: today
        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = java.time.LocalDate.of(year, month + 1, day)
                mutateRule(rule.id) { it.copy(epochDay = picked.toEpochDay()) }
            },
            start.year,
            start.monthValue - 1,
            start.dayOfMonth,
        ).show()
    }

    // ----------------------------------------------------------- maintenance

    @Inject lateinit var maintenance: Maintenance

    private lateinit var cacheStats: android.widget.TextView
    private lateinit var dbStats: android.widget.TextView

    /**
     * Re-read on every resume rather than observed.
     *
     * The numbers change from a worker in another process-lifetime, not from
     * this screen, and a user only ever compares them before and after pressing
     * a button. A Flow over three counts would be more machinery than a screen
     * that is open for ten seconds needs.
     */
    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val stats = maintenance.stats()
            cacheStats.text = getString(
                R.string.settings_cache_stats,
                stats.cacheTotal,
                stats.cacheExpired,
            )
            dbStats.text = getString(
                R.string.settings_db_stats,
                android.text.format.Formatter.formatShortFileSize(
                    this@SettingsActivity,
                    stats.databaseBytes,
                ),
            )
        }
    }

    private fun clearCache() {
        lifecycleScope.launch {
            maintenance.clearCache()
            refreshStats()
            toast(R.string.settings_cache_cleared)
        }
    }

    /**
     * Names what it deletes before it deletes it.
     *
     * The refusal is checked again after the confirmation, not only before it:
     * the dialog can sit open while the user starts a run from the notification.
     */
    private fun confirmClearData() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_data_title)
            .setMessage(R.string.settings_clear_data_body)
            .setPositiveButton(R.string.settings_clear_data_confirm) { _, _ -> clearData() }
            .setNegativeButton(R.string.settings_clear_data_cancel, null)
            .show()
    }

    private fun clearData() {
        lifecycleScope.launch {
            when (maintenance.clearStoredData()) {
                is Maintenance.ClearResult.Cleared -> {
                    refreshStats()
                    toast(R.string.settings_data_cleared)
                }
                is Maintenance.ClearResult.RefusedRunning ->
                    toast(R.string.settings_clear_refused)
            }
        }
    }

    private fun toast(res: Int) =
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun bind(
        id: Int,
        read: (EksiConfig) -> Boolean,
        write: (EksiConfig, Boolean) -> EksiConfig,
    ): Pair<MaterialSwitch, Binding> {
        val view = findViewById<MaterialSwitch>(id)
        val b = Binding(read, write)
        view.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (binding) return@setOnCheckedChangeListener
            lifecycleScope.launch { configRepository.update { write(it, checked) } }
        }
        return view to b
    }
}
