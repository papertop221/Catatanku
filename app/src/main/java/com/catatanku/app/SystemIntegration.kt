package com.catatanku.app

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.quicksettings.TileService
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.catatanku.app.ai.engine.AiState
import com.catatanku.app.ai.engine.LocalAi
import com.catatanku.app.ai.inference.AiText
import com.catatanku.app.ai.performance.AiTaskBudget
import com.catatanku.app.domain.model.AiAction
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.usecase.TimeParse
import com.catatanku.app.reminders.saveFromOutside
import com.catatanku.app.reminders.toMillis
import com.catatanku.app.reminders.whenLabel
import com.catatanku.app.security.AppPinCrypto
import com.catatanku.app.security.InputSecurity
import com.catatanku.app.security.SecurityPrefs
import com.catatanku.app.ui.AppViewModel
import com.catatanku.app.ui.CatatankuTheme
import com.catatanku.app.ui.Hairline
import com.catatanku.app.ui.LockScreen
import com.catatanku.app.ui.Muted
import com.catatanku.app.ui.NoteType
import com.catatanku.app.ui.PrimaryButton
import com.catatanku.app.ui.SecondaryButton
import com.catatanku.app.ui.Sheet
import com.catatanku.app.ui.SmallShape
import com.catatanku.app.ui.TextAction
import com.catatanku.app.ui.copyText
import com.catatanku.app.ui.muted
import com.catatanku.app.ui.timeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Pintu masuk dari luar aplikasi: menu teks terpilih di aplikasi lain, tile Setelan Cepat, dan pintasan ikon.

/** Aksi yang dipahami [MainActivity] dari tile, pintasan ikon, dan aplikasi otomasi (mis. Tasker). */
object Actions {
    const val NEW_NOTE = "com.catatanku.app.NEW_NOTE"
    const val SHOW_REMINDERS = "com.catatanku.app.SHOW_REMINDERS"
    const val SHOW_TASKS = "com.catatanku.app.SHOW_TASKS"
}

/** Pintasan di ikon aplikasi (tekan lama ikon Catatanku). Didaftarkan dari kode agar berlaku juga untuk build uji yang id-nya berbeda. */
fun publishShortcuts(ctx: Context) = runCatching {
    fun item(id: String, label: String, action: String, icon: Int) = android.content.pm.ShortcutInfo.Builder(ctx, id)
        .setShortLabel(label).setIcon(android.graphics.drawable.Icon.createWithResource(ctx, icon))
        .setIntent(Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)).build()
    ctx.getSystemService(android.content.pm.ShortcutManager::class.java)?.dynamicShortcuts = listOf(
        item("baru", "Catatan baru", Actions.NEW_NOTE, R.drawable.ic_tile),
        item("pengingat", "Pengingat", Actions.SHOW_REMINDERS, R.drawable.ic_bell_dark),
        item("tugas", "Tugas belum selesai", Actions.SHOW_TASKS, R.drawable.ic_tasks),
    )
}

/** Tile Setelan Cepat "Catatan baru": menulis catatan dari mana saja tanpa mencari ikon aplikasi. */
class QuickNoteTile : TileService() {
    override fun onClick() {
        val open = Intent(this, MainActivity::class.java).setAction(Actions.NEW_NOTE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(open)
    }
}

/**
 * Muncul sebagai "Catatanku" di menu teks terpilih aplikasi mana pun (browser, obrolan, PDF). Teksnya bisa
 * disimpan sebagai catatan, atau diolah AI lokal lalu dikembalikan ke aplikasi asal. Teks tidak pernah keluar dari HP.
 *
 * Saat Kunci Aplikasi aktif, pintu ini ikut menuntut verifikasi sebelum menampilkan atau menyimpan teks.
 */
class ProcessTextActivity : ComponentActivity() {
    private var result by mutableStateOf<String?>(null)     // null = belum ada aksi AI yang dijalankan
    private var running by mutableStateOf(false)
    private var label by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var detectedRemindAt by mutableStateOf<Long?>(null)
    private var unlocked by mutableStateOf(true)
    private var failedAttempts by mutableStateOf(0)
    private var lockoutMs by mutableStateOf(0L)
    private var job: Job? = null
    private val ai by lazy { LocalAi.get(this) }

    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            SecurityPrefs.resetFailures(this)
            unlocked = true
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun requestUnlock() {
        val km = getSystemService(KeyguardManager::class.java)
        val deviceSecure = km?.isDeviceSecure == true
        if (!deviceSecure) {
            if (!SecurityPrefs.hasAppPin(this)) unlocked = true
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val b = BiometricPrompt.Builder(this).setTitle("Buka Catatanku")
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
                    SecurityPrefs.resetFailures(this@ProcessTextActivity)
                    unlocked = true
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Buka Catatanku", null)
            if (i != null) confirmCredential.launch(i)
            else if (!SecurityPrefs.hasAppPin(this)) unlocked = true
        }
    }

    private fun verifyPin(pin: CharArray): AppViewModel.PinResult {
        return try {
            val rem = SecurityPrefs.remainingLockoutMs(this)
            if (rem > 0L) {
                lockoutMs = rem
                return AppViewModel.PinResult.LOCKED_OUT
            }
            if (SecurityPrefs.verifyAppPin(this, pin)) {
                SecurityPrefs.resetFailures(this)
                failedAttempts = 0
                lockoutMs = 0L
                unlocked = true
                AppViewModel.PinResult.SUCCESS
            } else {
                val fails = SecurityPrefs.recordUnlockFailure(this)
                failedAttempts = fails
                lockoutMs = SecurityPrefs.remainingLockoutMs(this)
                if (AppPinCrypto.shouldTriggerWipe(fails, SecurityPrefs.isWipeAfterFailuresEnabled(this))) {
                    toast("Percobaan gagal melampaui batas; seluruh data dihapus.")
                    val appCtx = applicationContext
                    kotlin.concurrent.thread(name = "catatanku-wipe") { SecurityPrefs.wipeStorageNow(appCtx) }
                    finish()
                    AppViewModel.PinResult.WIPED
                } else {
                    AppViewModel.PinResult.WRONG
                }
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    private fun run(action: AiAction, text: String) {
        label = action.label; result = ""; error = null; detectedRemindAt = null; running = true
        job = lifecycleScope.launch {
            try {
                if (action == AiAction.Tasks) {
                    val foundTime = TimeParse.best(text)
                    val direct = AiText.extractDirectTasksAndTime(text)
                    val extracted = direct ?: AiText.parseExtractedTasks(
                        ai.generate(AiText.tasksFromTextPrompt(text), task = AiTaskBudget.TASKS),
                        foundTime,
                    )
                    if (extracted.isEmpty) {
                        result = ""
                        error = "Tidak ada tugas nyata yang ditemukan pada teks terpilih."
                    } else {
                        result = extracted.text
                        detectedRemindAt = extracted.remindAt?.toMillis()
                    }
                } else {
                    val out = ai.generate(AiText.prompt(action, "", text), task = AiTaskBudget.forAction(action)) {
                        result = AiText.cleanBlock(it)
                    }
                    result = AiText.cleanBlock(out)
                    if (result.isNullOrBlank()) error = "Model tidak menghasilkan teks. Coba lagi dengan teks yang lebih pendek."
                }
            } catch (e: CancellationException) {
            } catch (e: Throwable) { error = "AI gagal: ${e.message}" } finally { running = false }
        }
    }

    private fun save(text: String, remindAt: Long = 0) {
        saveFromOutside(this, Note(body = text.trim(), remindAt = remindAt))
        toast(if (remindAt != 0L) "Disimpan ke Catatanku, pengingat ${whenLabel(remindAt)}" else "Disimpan ke Catatanku")
        finish()
    }

    override fun onDestroy() {
        job?.cancel()
        // Model tidak perlu menahan RAM setelah lembar ini ditutup.
        ai.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val lockOn = SecurityPrefs.isLockEnabled(this)
        val shield = SecurityPrefs.isScreenShieldEnabled(this) || lockOn
        if (shield) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        }
        unlocked = !lockOn
        failedAttempts = SecurityPrefs.failedAttempts(this)
        lockoutMs = SecurityPrefs.remainingLockoutMs(this)

        val text = InputSecurity.sanitizeExternalText(
            intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
            100_000,
        )
        val readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) != false
        if (text.isEmpty()) { finish(); return }
        val time = TimeParse.best(text)?.at?.toMillis()
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val dynamic = prefs.getBoolean("dynamic_color", false)
        val incognito = SecurityPrefs.isIncognitoKeyboardEnabled(this)
        val hasPin = SecurityPrefs.hasAppPin(this)
        val canDevice = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        val wipeOnFail = SecurityPrefs.isWipeAfterFailuresEnabled(this)

        setContent {
            CatatankuTheme(dynamic, incognitoKeyboard = incognito) {
                if (!unlocked) {
                    LockScreen(
                        onUnlock = ::requestUnlock,
                        title = "Catatanku terkunci",
                        hasAppPin = hasPin,
                        canDeviceUnlock = canDevice,
                        failedAttempts = failedAttempts,
                        lockoutRemainingMs = lockoutMs,
                        wipeAfterFailures = wipeOnFail,
                        onPinSubmit = ::verifyPin,
                        onCancel = ::finish,
                    )
                    return@CatatankuTheme
                }
                val aiState by ai.state.collectAsStateWithLifecycle()
                val hasModel = aiState !is AiState.NoModel
                val out = result
                Sheet(if (out == null) "Catatanku" else label, onDismiss = ::finish) {
                    if (out == null) {
                        Text(text, Modifier.padding(top = 6.dp, bottom = 10.dp), style = NoteType.rowSnippet, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        @androidx.compose.runtime.Composable
                        fun Option(title: String, hint: String, enabled: Boolean = true, hintColor: androidx.compose.ui.graphics.Color = muted, onClick: () -> Unit) = Row(
                            Modifier.fillMaxWidth().clip(SmallShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = 0.5f))
                            Text(hint, Modifier.padding(start = 12.dp), color = hintColor, style = MaterialTheme.typography.bodySmall)
                        }
                        Option("Simpan sebagai catatan", "Tanpa AI") { save(text) }
                        // Waktu yang disebut di teks dikenali tanpa model.
                        if (time != null) Option("Simpan dan ingatkan", whenLabel(time), hintColor = timeColor) { save(text, time) }
                        Hairline(Modifier.padding(vertical = 4.dp))
                        Option("Ringkas", "Menjadi 3–5 poin", hasModel) { run(AiAction.Summarize, text) }
                        Option("Perbaiki ejaan", "Makna tetap", hasModel) { run(AiAction.Fix, text) }
                        Option("Ambil daftar tugas", "Menjadi daftar centang", hasModel) { run(AiAction.Tasks, text) }
                        if (!hasModel) Muted("Aksi AI butuh model. Buka Catatanku → Pengaturan → Model AI untuk mengunduhnya.", Modifier.padding(top = 6.dp))
                        else if (text.length > AiText.maxInput) Muted("Teks panjang: AI hanya membaca ±${AiText.maxInput} huruf pertama.", Modifier.padding(top = 6.dp))
                    } else {
                        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        Column(Modifier.heightIn(min = 80.dp, max = 340.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                            if (running && out.isEmpty()) Muted(if (aiState is AiState.Loading) "Memuat model ke memori…" else "Menulis…", style = MaterialTheme.typography.bodyMedium)
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                            if (out.isNotEmpty()) Text(out, style = NoteType.body, color = MaterialTheme.colorScheme.onSurface)
                        }
                        when {
                            running -> SecondaryButton("Hentikan", Modifier.fillMaxWidth()) { job?.cancel() }
                            out.isBlank() -> SecondaryButton("Kembali ke pilihan", Modifier.fillMaxWidth()) { result = null; error = null }
                            else -> {
                                val remind = detectedRemindAt?.takeIf { it > System.currentTimeMillis() }
                                // Aplikasi asal mengizinkan teksnya diganti (kolom yang bisa disunting): itu aksi utamanya.
                                if (!readOnly) PrimaryButton("Ganti teks terpilih", Modifier.fillMaxWidth()) {
                                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, out)); finish()
                                } else if (remind != null) PrimaryButton("Simpan & ingatkan ${whenLabel(remind)}", Modifier.fillMaxWidth()) {
                                    save(out, remind)
                                } else PrimaryButton("Simpan sebagai catatan", Modifier.fillMaxWidth()) { save(out) }
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextAction("Salin hasil") { copyText(this@ProcessTextActivity, out); toast("Hasil disalin"); finish() }
                                    if (!readOnly && remind != null) TextAction("Simpan + pengingat") { save(out, remind) }
                                    else if (!readOnly) TextAction("Simpan sebagai catatan") { save(out) }
                                    else if (remind != null) TextAction("Simpan tanpa pengingat") { save(out) }
                                    TextAction("Buang hasil") { result = null; detectedRemindAt = null }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
