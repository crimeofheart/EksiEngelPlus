package org.duzgun.eksiengelplus.feature.settings

import android.os.Bundle
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.duzgun.eksiengelplus.datastore.ConfigRepository
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
            bind(R.id.switchPremiumIcons, { it.banPremiumIcons }, { c, v -> c.copy(banPremiumIcons = v) }),
            bind(R.id.switchAppPromo, { it.hideAppPromo }, { c, v -> c.copy(hideAppPromo = v) }),
            bind(R.id.switchBlockAds, { it.blockAds }, { c, v -> c.copy(blockAds = v) }),
            bind(R.id.switchDateFilter, { it.enableDateFilter }, { c, v -> c.copy(enableDateFilter = v) }),
            bind(R.id.switchSendData, { it.sendData }, { c, v -> c.copy(sendData = v) }),
            bind(R.id.switchSendLog, { it.sendLog }, { c, v -> c.copy(sendLog = v) }),
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                configRepository.config.collect { config ->
                    binding = true
                    switches.forEach { (view, b) -> view.isChecked = b.read(config) }
                    binding = false
                }
            }
        }
    }

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
