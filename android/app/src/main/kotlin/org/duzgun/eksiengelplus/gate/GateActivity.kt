package org.duzgun.eksiengelplus.gate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.duzgun.eksiengelplus.R
import org.duzgun.eksiengelplus.ui.fitContentInsideSystemBars

/**
 * The update screen. While the build is blocked it is all the app shows: the
 * maintainer's message, güncelle, tekrar dene and kapat; leaving it closes the
 * app. Opened with [optional] it offers a newer build instead, and sonra goes
 * back to the app.
 *
 * Güncelle is the flavor's [Updater]: the APK build downloads the policy's APK,
 * checks it and opens the system installer; the Play build hands over to Play.
 */
@AndroidEntryPoint
class GateActivity : AppCompatActivity() {

    @Inject lateinit var gate: UpdateGate

    private lateinit var status: TextView
    private var updating: Job? = null

    private val optional: Boolean get() = intent.getBooleanExtra(EXTRA_OPTIONAL, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gate)
        fitContentInsideSystemBars()
        status = findViewById(R.id.gateStatus)
        findViewById<Button>(R.id.gateUpdate).setOnClickListener { update() }
        findViewById<Button>(R.id.gateCheck).apply {
            visibility = if (optional) View.GONE else View.VISIBLE
            setOnClickListener { checkAgain() }
        }
        findViewById<Button>(R.id.gateClose).apply {
            setText(if (optional) R.string.gate_later else R.string.gate_close)
            setOnClickListener { leave() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val decision = gate.decision()
        // Unblocked since it opened: a newer policy, or the update went in.
        if (!optional && !decision.blocks) {
            finish()
            return
        }
        val (title, fallback) = when (decision) {
            is GateDecision.Revoked -> R.string.gate_revoked_title to R.string.gate_revoked_text
            is GateDecision.UpdateRequired -> R.string.gate_required_title to R.string.gate_required_text
            else -> R.string.gate_available_title to R.string.gate_available_text
        }
        findViewById<TextView>(R.id.gateTitle).setText(title)
        findViewById<TextView>(R.id.gateMessage).text = gate.message() ?: getString(fallback)
    }

    private fun update() {
        if (updating?.isActive == true) return
        updating = lifecycleScope.launch { Updater.start(this@GateActivity, gate, ::showStatus) }
    }

    private fun checkAgain() {
        showStatus(getString(R.string.gate_checking))
        lifecycleScope.launch {
            gate.refresh(force = true)
            if (gate.decision().blocks) {
                showStatus(getString(R.string.gate_still_required))
                render()
            } else {
                finish()
            }
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    /** A blocked build has nothing behind this screen to go back to. */
    private fun leave() {
        if (optional) finish() else finishAffinity()
    }

    companion object {
        private const val EXTRA_OPTIONAL = "optional"

        fun intent(context: Context, optional: Boolean = false): Intent =
            Intent(context, GateActivity::class.java)
                .putExtra(EXTRA_OPTIONAL, optional)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
