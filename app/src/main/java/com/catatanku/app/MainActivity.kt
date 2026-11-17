package com.catatanku.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.NoteView
import com.catatanku.app.domain.model.Screen
import com.catatanku.app.media.Attachments
import com.catatanku.app.reminders.Reminders
import com.catatanku.app.security.InputSecurity
import com.catatanku.app.ui.AppViewModel
import com.catatanku.app.ui.CatatankuTheme
import com.catatanku.app.ui.EditorScreen
import com.catatanku.app.ui.ListScreen
import com.catatanku.app.ui.LockScreen
import com.catatanku.app.ui.SettingsScreen
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    val vm: AppViewModel by viewModels()

    private var pendingIntent: Intent? = null
    private var pendingConfirmVault = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) vm.onScreenOff()
        }
    }

    /** Android 8–9: konfirmasi PIN/pola lewat layar sistem. */
    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val forVault = pendingConfirmVault
        pendingConfirmVault = false
        if (it.resultCode == RESULT_OK) {
            if (forVault) vm.onVaultAuthenticated() else { vm.unlocked(); replayPendingIntent() }
        }
    }

    private fun replayPendingIntent() {
        val pending = pendingIntent ?: return
        pendingIntent = null
        handleIntent(pending)
    }

    /** Teks yang dibagikan dari aplikasi lain menjadi catatan baru; notifikasi pengingat membuka catatannya. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (vm.shouldDeferForLock()) {
            if (pendingIntent == null || intent.action != Intent.ACTION_MAIN) {
                pendingIntent = intent
            }
            return
        }
        val remindId = intent.getLongExtra(Reminders.EXTRA_OPEN, 0L)
        if (remindId != 0L) {
            val token = intent.getStringExtra(Reminders.EXTRA_TOKEN)
            intent.removeExtra(Reminders.EXTRA_OPEN)       // jangan dibuka ulang saat layar diputar
            intent.removeExtra(Reminders.EXTRA_TOKEN)
            if (Reminders.isValidOpenToken(this, token)) vm.openFromReminder(remindId)
            return
        }
        // Tile Setelan Cepat, pintasan ikon, asisten perangkat, dan aplikasi otomasi.
        when (intent.action) {
            Actions.NEW_NOTE -> { vm.saveDraft(); vm.open(null); return }
            Actions.SHOW_REMINDERS -> { vm.showReminders(); return }
            Actions.SHOW_TASKS -> { vm.showTasks(); return }
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> { vm.openAssist(); return }
        }
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        val subject = InputSecurity.sanitizeExternalText(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), 120)
        val text = InputSecurity.sanitizeExternalText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(), 100_000)
        @Suppress("DEPRECATION")
        val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        when {
            type.startsWith("image/") && stream != null -> {
                if (Attachments.isSafeExternalUri(this, stream)) {
                    vm.saveDraft(); vm.openSharedMedia(stream, isImage = true, title = subject, body = text)
                } else {
                    vm.say("Sumber berkas tidak didukung.")
                }
            }
            type.startsWith("audio/") && stream != null -> {
                if (Attachments.isSafeExternalUri(this, stream)) {
                    vm.saveDraft(); vm.openSharedMedia(stream, isImage = false, title = subject, body = text)
                } else {
                    vm.say("Sumber berkas tidak didukung.")
                }
            }
            type == "text/plain" && text.isNotEmpty() -> { vm.saveDraft(); vm.open(Note(title = subject, body = text)) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    /** Mencegah tangkapan layar, perekaman layar, dan pratinjau aplikasi terbaru ketika perisai/kunci/brankas aktif. */
    private fun applySecureFlag() {
        val secure = vm.screenShield || vm.lockEnabled || vm.vaultUnlocked
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!secure)
        }
    }

    /** Membuka kunci dengan biometrik kuat atau PIN layar HP. */
    private fun unlock(forVaultOnly: Boolean) = try {
        showUnlockPrompt(forVaultOnly)
    } catch (e: Exception) {
        vm.say("Kunci layar HP tidak bisa dibuka: ${e.message}")
    }

    private fun showUnlockPrompt(forVaultOnly: Boolean) {
        val km = getSystemService(KeyguardManager::class.java)
        val deviceSecure = km?.isDeviceSecure == true
        if (!deviceSecure) {
            if (vm.hasAppPin) {
                if (forVaultOnly) vm.say("Masukkan PIN Catatanku untuk membuka Brankas.")
                return
            }
            if (forVaultOnly) {
                vm.cancelVaultUnlockRequest()
                vm.say("Setel kunci layar HP atau PIN Catatanku lebih dulu untuk memakai Brankas.")
            } else if (vm.lockEnabled) {
                vm.updateLock(false)
                applySecureFlag()
                replayPendingIntent()
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val title = if (forVaultOnly) "Buka Brankas Catatanku" else "Buka Catatanku"
            val b = BiometricPrompt.Builder(this).setTitle(title)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            }
            b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    if (forVaultOnly) vm.onVaultAuthenticated() else { vm.unlocked(); replayPendingIntent() }
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    if (!vm.hasAppPin && getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
                        if (forVaultOnly) {
                            vm.cancelVaultUnlockRequest()
                        } else {
                            vm.updateLock(false); applySecureFlag(); replayPendingIntent()
                        }
                    }
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent(if (forVaultOnly) "Buka Brankas Catatanku" else "Buka Catatanku", null)
            if (i == null) {
                if (!vm.hasAppPin && !forVaultOnly) {
                    vm.updateLock(false); applySecureFlag(); replayPendingIntent()
                }
            } else {
                vm.suppressNextLeaveLock()
                pendingConfirmVault = forVaultOnly
                confirmCredential.launch(i)
            }
        }
    }

    override fun onStart() { super.onStart(); vm.onReturn() }
    override fun onStop() { super.onStop(); vm.onLeave() }

    /** Sistem kekurangan memori atau aplikasi ditinggalkan: model AI dilepas lebih dulu. */
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); vm.onTrimMemory(level) }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        Reminders.channels(this)
        applySecureFlag()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CatatankuTheme(vm.dynamicColor, incognitoKeyboard = vm.incognitoKeyboard) {
                val snack = remember { SnackbarHostState() }
                // Pesan baru menggantikan yang sedang tampil, supaya "Urungkan" selalu milik aksi terakhir.
                LaunchedEffect(Unit) {
                    vm.notices.collectLatest { n ->
                        val result = snack.showSnackbar(n.text, n.action, duration = if (n.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) n.onAction?.invoke()
                    }
                }
                LaunchedEffect(vm.screenShield, vm.lockEnabled, vm.vaultUnlocked) { applySecureFlag() }
                val canDevice = remember(vm.locked, vm.vaultUnlockRequested) {
                    getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
                }
                when {
                    vm.locked -> {
                        BackHandler { moveTaskToBack(true) }
                        LockScreen(
                            onUnlock = { unlock(false) },
                            title = "Catatanku terkunci",
                            hasAppPin = vm.hasAppPin,
                            canDeviceUnlock = canDevice,
                            failedAttempts = vm.failedUnlocks,
                            lockoutRemainingMs = vm.lockoutRemainingMs,
                            wipeAfterFailures = vm.wipeAfterFailures,
                            onPinSubmit = { vm.verifyPinAndUnlock(it, forVaultOnly = false) },
                        )
                    }
                    vm.vaultUnlockRequested -> {
                        BackHandler { vm.cancelVaultUnlockRequest() }
                        LockScreen(
                            onUnlock = { unlock(true) },
                            title = "Buka Brankas",
                            hasAppPin = vm.hasAppPin,
                            canDeviceUnlock = canDevice,
                            failedAttempts = vm.failedUnlocks,
                            lockoutRemainingMs = vm.lockoutRemainingMs,
                            wipeAfterFailures = vm.wipeAfterFailures,
                            onPinSubmit = { vm.verifyPinAndUnlock(it, forVaultOnly = true) },
                            onCancel = vm::cancelVaultUnlockRequest,
                        )
                    }
                    else -> when (vm.screen) {
                        Screen.List -> {
                            // Kembali menutup dulu apa yang sedang terbuka di atas daftar, baru keluar dari aplikasi.
                            BackHandler(vm.askActive || vm.query.isNotEmpty() || vm.tagFilter != null || vm.view != NoteView.All) {
                                when {
                                    vm.askActive -> vm.closeAsk()
                                    vm.query.isNotEmpty() -> vm.updateQuery("")
                                    vm.tagFilter != null -> vm.tagFilter = null
                                    else -> vm.switchView(NoteView.All)
                                }
                            }
                            ListScreen(vm, snack)
                        }
                        Screen.Editor -> { BackHandler { vm.closeEditor() }; EditorScreen(vm, snack) }
                        Screen.Settings -> { BackHandler { vm.go(Screen.List) }; SettingsScreen(vm, snack) }
                    }
                }
            }
        }
    }
}
