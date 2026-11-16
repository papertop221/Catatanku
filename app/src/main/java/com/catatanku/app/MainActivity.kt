package com.catatanku.app

import android.app.KeyguardManager
import android.content.Intent
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
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.NoteView
import com.catatanku.app.domain.model.Screen
import com.catatanku.app.media.Attachments
import com.catatanku.app.reminders.Reminders
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

    /** Android 8–9: konfirmasi PIN/pola lewat layar sistem. */
    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) { vm.unlocked(); replayPendingIntent() }
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
            pendingIntent = intent
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
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty().take(120)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty().take(100_000)
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

    /** Dengan kunci aktif, isi aplikasi juga disembunyikan dari daftar aplikasi terbaru dan tangkapan layar. */
    private fun applySecureFlag() {
        if (vm.lockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Membuka kunci dengan sidik jari/wajah atau PIN layar HP. Aplikasi tidak menyimpan sandi sendiri. */
    private fun unlock() = try { showUnlockPrompt() } catch (e: Exception) { vm.say("Kunci layar HP tidak bisa dibuka: ${e.message}") }

    private fun showUnlockPrompt() {
        if (Build.VERSION.SDK_INT >= 29) {
            val b = BiometricPrompt.Builder(this).setTitle("Buka Catatanku")
            if (Build.VERSION.SDK_INT >= 30)
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { vm.unlocked(); replayPendingIntent() }
                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    // Kunci layar HP sudah dihapus pengguna: tidak ada lagi yang bisa dipakai membuka, jadi jangan mengunci selamanya.
                    if (getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
                        vm.updateLock(false); applySecureFlag(); replayPendingIntent()
                    }
                }
            })
        } else {
            val km = getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION") val i = km?.createConfirmDeviceCredentialIntent("Buka Catatanku", null)
            if (i == null) { vm.updateLock(false); applySecureFlag(); replayPendingIntent() } else confirmCredential.launch(i)
        }
    }

    override fun onStart() { super.onStart(); vm.onReturn() }
    override fun onStop() { super.onStop(); vm.onLeave() }
    /** Sistem kekurangan memori atau aplikasi ditinggalkan: model AI dilepas lebih dulu. */
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); vm.onTrimMemory(level) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Reminders.channels(this)
        applySecureFlag()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CatatankuTheme(vm.dynamicColor) {
                val snack = remember { SnackbarHostState() }
                // Pesan baru menggantikan yang sedang tampil, supaya "Urungkan" selalu milik aksi terakhir.
                LaunchedEffect(Unit) {
                    vm.notices.collectLatest { n ->
                        val result = snack.showSnackbar(n.text, n.action, duration = if (n.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) n.onAction?.invoke()
                    }
                }
                LaunchedEffect(vm.lockEnabled) { applySecureFlag() }
                if (vm.locked) { BackHandler { moveTaskToBack(true) }; LockScreen(::unlock) }
                else when (vm.screen) {
                    Screen.List -> {
                        // Kembali menutup dulu apa yang sedang terbuka di atas daftar, baru keluar dari aplikasi.
                        BackHandler(vm.askActive || vm.query.isNotEmpty() || vm.tagFilter != null || vm.view != NoteView.All) {
                            when {
                                vm.askActive -> vm.closeAsk()
                                vm.query.isNotEmpty() -> vm.updateQuery("")
                                vm.tagFilter != null -> vm.tagFilter = null
                                else -> vm.view = NoteView.All
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
