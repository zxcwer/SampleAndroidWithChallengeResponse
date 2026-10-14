package com.example.sync.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.example.sync.authclient.DeviceAuthException
import com.example.sync.authclient.DeviceInfo
import com.example.sync.authclient.ErrorCodes
import com.example.sync.authclient.LockStatus
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var container: AppContainer
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var lockText: TextView
    private lateinit var messageText: TextView
    private lateinit var registerGroup: View
    private lateinit var dataGroup: View
    private lateinit var syncButton: Button
    private lateinit var registerButton: Button
    private lateinit var codeInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = AppContainer(this)

        statusText = findViewById(R.id.statusText)
        lockText = findViewById(R.id.lockText)
        messageText = findViewById(R.id.messageText)
        registerGroup = findViewById(R.id.registerGroup)
        dataGroup = findViewById(R.id.dataGroup)
        syncButton = findViewById(R.id.syncButton)
        registerButton = findViewById(R.id.registerButton)
        codeInput = findViewById(R.id.codeInput)

        registerButton.setOnClickListener { register() }
        syncButton.setOnClickListener { sync() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun register() {
        val code = codeInput.text.toString().trim()
        if (code.isEmpty()) {
            messageText.setText(R.string.msg_enter_code)
            return
        }
        setBusy(true)
        executor.execute {
            val result = runCatching {
                container.auth.register(code, DeviceInfo(Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME))
            }
            runOnUiThread {
                result.onSuccess {
                    container.revokedFlag.revoked = false
                    codeInput.text.clear()
                    messageText.setText(R.string.msg_registered)
                    sync()
                }.onFailure {
                    messageText.text = errorMessage(it)
                    setBusy(false)
                    render()
                }
            }
        }
    }

    private fun sync() {
        setBusy(true)
        messageText.setText(R.string.msg_syncing)
        executor.execute {
            val result = SyncRunner(container).run()
            runOnUiThread {
                messageText.text = when (result) {
                    SyncResult.Success -> getString(R.string.msg_sync_ok)
                    SyncResult.NetworkError -> getString(R.string.err_network)
                    is SyncResult.AuthError -> errorMessage(result.error)
                }
                setBusy(false)
                render()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        syncButton.isEnabled = !busy
        registerButton.isEnabled = !busy
    }

    /** 画面状態の更新。ロック中は業務データを表示しない。 */
    private fun render() {
        val deviceId = container.deviceIds.get()
        val registered = container.auth.isRegistered()
        val revoked = container.revokedFlag.revoked

        statusText.text = when {
            registered -> getString(R.string.status_registered, deviceId)
            deviceId != null -> getString(R.string.status_key_lost)
            else -> getString(R.string.status_not_registered)
        }
        registerGroup.visibility = if (!registered || revoked) View.VISIBLE else View.GONE
        syncButton.visibility = if (registered && !revoked) View.VISIBLE else View.GONE

        val lock = container.offlineGuard.status()
        container.logger.d("render: registered=$registered revoked=$revoked lock=$lock")
        val lockMessage = when {
            !registered -> null
            revoked -> getString(R.string.locked_revoked)
            lock is LockStatus.Locked && lock.reason == LockStatus.Reason.NEVER_SYNCED -> getString(R.string.locked_never_synced)
            lock is LockStatus.Locked -> getString(R.string.locked_offline)
            else -> null
        }
        lockText.text = lockMessage
        lockText.visibility = if (lockMessage != null) View.VISIBLE else View.GONE
        dataGroup.visibility = if (registered && lockMessage == null) View.VISIBLE else View.GONE

        if (registered && lock is LockStatus.Unlocked && !revoked) {
            val last = container.offlineGuard.lastSyncServerTimeSec()
            val lastText = last?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) }
                ?: getString(R.string.status_never_synced)
            statusText.append("\n" + getString(R.string.status_last_sync, lastText))
            statusText.append("\n" + getString(R.string.status_remaining, lock.remainingMs / 3_600_000))
        }
    }

    /** challenge_expired は DeviceAuthClient が自動再試行済み。終端エラーは再試行せずメッセージを表示する。 */
    private fun errorMessage(t: Throwable): String = when (t) {
        is IOException -> getString(R.string.err_network)
        is DeviceAuthException -> when (t.code) {
            ErrorCodes.DEVICE_REVOKED -> getString(R.string.err_device_revoked)
            ErrorCodes.DEVICE_NOT_REGISTERED -> getString(R.string.err_device_not_registered)
            ErrorCodes.APP_VERSION_UNSUPPORTED -> getString(R.string.err_app_version_unsupported)
            ErrorCodes.INVALID_REGISTRATION_CODE -> getString(R.string.err_invalid_registration_code)
            ErrorCodes.RATE_LIMITED -> getString(R.string.err_rate_limited)
            else -> getString(R.string.err_generic, t.code)
        }
        else -> getString(R.string.err_generic, t.javaClass.simpleName)
    }
}
