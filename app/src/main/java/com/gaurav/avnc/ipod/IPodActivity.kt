/*
 * iPod Touch emulator integration (QEMU-iOS running in Termux).
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ipod

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.gaurav.avnc.R
import com.gaurav.avnc.databinding.ActivityIpodBinding
import com.gaurav.avnc.ui.home.HomeActivity
import com.gaurav.avnc.ui.vnc.startVncActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher: starts the emulator in Termux if needed, waits for its VNC
 * server, then opens the viewer.
 */
class IPodActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIpodBinding
    private var startJob: Job? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start()
        else showError(getString(R.string.ipod_msg_permission_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIpodBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.retryBtn.setOnClickListener { start() }
        binding.advancedBtn.setOnClickListener { startActivity(Intent(this, HomeActivity::class.java)) }
        start()
    }

    private fun start() {
        if (startJob?.isActive == true)
            return

        startJob = lifecycleScope.launch {
            showStatus(getString(R.string.ipod_msg_connecting))

            if (!checkRunning()) {
                if (!IPod.isTermuxInstalled(this@IPodActivity)) {
                    showError(getString(R.string.ipod_msg_no_termux))
                    return@launch
                }
                if (ContextCompat.checkSelfPermission(this@IPodActivity, IPod.TERMUX_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionRequest.launch(IPod.TERMUX_PERMISSION)
                    return@launch
                }

                showStatus(getString(R.string.ipod_msg_starting))
                try {
                    IPod.runCommand(this@IPodActivity, "start", "--no-viewer")
                } catch (e: Exception) {
                    Log.e("IPodActivity", "RUN_COMMAND failed", e)
                    showError(getString(R.string.ipod_msg_termux_failed, e.message ?: e.toString()))
                    return@launch
                }

                // A start backs up the state first, which can take a while on a big device
                var waited = 0
                while (!checkRunning()) {
                    if (waited >= START_TIMEOUT_MS) {
                        showError(getString(R.string.ipod_msg_start_timeout))
                        return@launch
                    }
                    delay(POLL_MS)
                    waited += POLL_MS.toInt()
                }
            }

            startVncActivity(this@IPodActivity, IPod.createProfile())
            finish()
        }
    }

    private suspend fun checkRunning() = withContext(Dispatchers.IO) { IPod.isRunning() }

    private fun showStatus(msg: String) {
        binding.progress.isVisible = true
        binding.message.text = msg
        binding.retryBtn.isVisible = false
        binding.advancedBtn.isVisible = false
    }

    private fun showError(msg: String) {
        binding.progress.isVisible = false
        binding.message.text = msg
        binding.retryBtn.isVisible = true
        binding.advancedBtn.isVisible = true
    }

    companion object {
        private const val POLL_MS = 500L
        private const val START_TIMEOUT_MS = 120_000
    }
}
