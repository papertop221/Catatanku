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
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    val vm: AppViewModel by viewModels()

    /** Android 8–9: konfirmasi PIN/pola lewat layar sistem. */
    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) vm.unlocked()
    }

    /** Teks yang dibagikan dari aplikasi lain menjadi catatan baru; notifikasi pengingat membuka catatannya. */
    private fun handleIntent(intent: Intent?) {
        val remindId = intent?.getLongExtra(Reminders.EXTRA_OPEN, 0L) ?: 0L
        if (remindId != 0L) {
            intent?.removeExtra(Reminders.EXTRA_OPEN)       // jangan dibuka ulang saat layar diputar
            vm.openFromReminder(remindId)
            return
        }
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        vm.saveDraft()
        vm.open(Note(title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty().take(120), body = text))
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
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = vm.unlocked()
                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    // Kunci layar HP sudah dihapus pengguna: tidak ada lagi yang bisa dipakai membuka, jadi jangan mengunci selamanya.
                    if (getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) { vm.updateLock(false); applySecureFlag() }
                }
            })
        } else {
            val km = getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION") val i = km?.createConfirmDeviceCredentialIntent("Buka Catatanku", null)
            if (i == null) { vm.updateLock(false); applySecureFlag() } else confirmCredential.launch(i)
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
