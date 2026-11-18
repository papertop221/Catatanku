package com.catatanku.app.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.catatanku.app.ai.engine.AiEvent
import com.catatanku.app.ai.engine.LocalAi
import com.catatanku.app.ai.inference.Agent
import com.catatanku.app.ai.inference.AiText
import com.catatanku.app.ai.performance.AiBackendKind
import com.catatanku.app.ai.performance.AiTaskBudget
import com.catatanku.app.ai.performance.ContextProfile
import com.catatanku.app.data.database.NoteDb
import com.catatanku.app.data.repository.AutoBackup
import com.catatanku.app.data.repository.NoteRepository
import com.catatanku.app.domain.model.AgentItem
import com.catatanku.app.domain.model.AgentRun
import com.catatanku.app.domain.model.AgentStep
import com.catatanku.app.domain.model.AiAction
import com.catatanku.app.domain.model.AiJob
import com.catatanku.app.domain.model.BackupProgress
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.NoteView
import com.catatanku.app.domain.model.Notice
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.domain.model.Screen
import com.catatanku.app.domain.model.Sort
import com.catatanku.app.domain.model.Stats
import com.catatanku.app.domain.model.Version
import com.catatanku.app.domain.usecase.SearchIndex
import com.catatanku.app.domain.usecase.TimeParse
import com.catatanku.app.media.Attachments
import com.catatanku.app.publishShortcuts
import com.catatanku.app.reminders.Reminders
import com.catatanku.app.reminders.toLocal
import com.catatanku.app.reminders.toMillis
import com.catatanku.app.reminders.whenLabel
import com.catatanku.app.security.AppPinCrypto
import com.catatanku.app.security.DeviceVaultKey
import com.catatanku.app.security.LockTimeout
import com.catatanku.app.security.SecurityPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = NoteRepository(app)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ai = LocalAi.get(app)
    val appVersion: String by lazy {
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0)?.versionName.orEmpty() }
            .getOrDefault("").ifBlank { "?" }
    }

    var screen by mutableStateOf(Screen.List); private set
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var stats by mutableStateOf(Stats(emptyList())); private set
    var loaded by mutableStateOf(false); private set
    var index by mutableStateOf(SearchIndex(emptyList())); private set
    var existingAttachments by mutableStateOf<Set<String>>(emptySet()); private set
    private var notesById: Map<Long, Note> = emptyMap()
    private var listStale = false
    private var writeGen = 0
    private var indexJob: Job? = null
    private var searchJob: Job? = null
    private var backupJob: Job? = null
    internal val settling get() = indexJob?.isActive == true || searchJob?.isActive == true

    private var _view by mutableStateOf<NoteView>(NoteView.All)
    var view: NoteView
        get() = _view
        set(v) { _view = v; refreshShown() }
    var sort by mutableStateOf(runCatching { Sort.valueOf(prefs.getString("sort", "")!!) }.getOrDefault(Sort.Updated)); private set
    var query by mutableStateOf(""); private set
    private var _tagFilter by mutableStateOf<String?>(null)
    var tagFilter: String?
        get() = _tagFilter
        set(v) { _tagFilter = v; refreshShown() }
    var aiTerms by mutableStateOf<List<String>>(emptyList()); private set
    var aiBusy by mutableStateOf(false); private set
    var batch by mutableStateOf<Pair<Int, Int>?>(null); private set
    var shown by mutableStateOf<List<Note>>(emptyList()); private set

    // Cadangan & pemulihan
    var backupProgress by mutableStateOf<BackupProgress?>(null); private set
    var pendingEncryptedRestoreUri by mutableStateOf<Uri?>(null); private set

    // Hari ini
    var today by mutableStateOf<List<Note>>(emptyList()); private set
    var openTaskCount by mutableStateOf(0); private set
    var todayOpen by mutableStateOf(prefs.getBoolean("today_open", true)); private set
    fun toggleToday() { todayOpen = !todayOpen; prefs.edit().putBoolean("today_open", todayOpen).apply() }

    // Editor
    var draft by mutableStateOf(Note()); private set
    var suggestedTags by mutableStateOf<List<String>>(emptyList()); private set
    var suggestedByAi by mutableStateOf(false); private set
    var aiJob by mutableStateOf<AiJob?>(null); private set
    private data class UndoAiSnap(
        val title: String, val body: String,
        val remindAt: Long, val remindRepeat: Repeat, val remindAlarm: Boolean, val remindFired: Boolean,
    )
    private var undoAi by mutableStateOf<UndoAiSnap?>(null)
    val canUndoAi get() = undoAi != null
    var aiSheet by mutableStateOf(false)
    /** Rentang teks terpilih di editor saat lembar Asisten dibuka; null = seluruh catatan. */
    var aiSelection by mutableStateOf<IntRange?>(null)

    // Templat
    private val templateStore = com.catatanku.app.data.repository.TemplateStore(app)
    var userTemplates by mutableStateOf<List<com.catatanku.app.domain.usecase.NoteTemplate>>(emptyList()); private set
    var templateSheet by mutableStateOf(false)

    // Pilih banyak di daftar
    var selectedIds by mutableStateOf<Set<Long>>(emptySet()); private set
    val selecting get() = selectedIds.isNotEmpty()

    // Ringkasan lintas catatan
    var askDigest by mutableStateOf(false); private set
    var versions by mutableStateOf<List<Version>?>(null); private set
    private var opened: Note? = null
    private var snapshotDone = false
    private var fresh = false
    private var clean by mutableStateOf<Note?>(null)
    val draftSaved get() = draft.id != 0L && draft === clean

    // Pengingat
    var reminderFor by mutableStateOf<Note?>(null); private set
    var reminderSeed by mutableStateOf<Long?>(null); private set

    // Pengaturan keamanan & privasi
    var lockEnabled by mutableStateOf(SecurityPrefs.isLockEnabled(app)); private set
    var locked by mutableStateOf(SecurityPrefs.isLockEnabled(app)); private set
    var vaultUnlocked by mutableStateOf(false); private set
    var vaultUnlockRequested by mutableStateOf(false); private set
    var pendingVaultNoteId by mutableStateOf(0L); private set
    var vaultCount by mutableStateOf(0); private set
    var lockTimeout by mutableStateOf(SecurityPrefs.lockTimeout(app)); private set
    var screenShield by mutableStateOf(SecurityPrefs.isScreenShieldEnabled(app)); private set
    var incognitoKeyboard by mutableStateOf(SecurityPrefs.isIncognitoKeyboardEnabled(app)); private set
    var clipboardAutoClear by mutableStateOf(SecurityPrefs.isClipboardAutoClearEnabled(app)); private set
    var strictOffline by mutableStateOf(SecurityPrefs.isStrictOffline(app)); private set
    var hasAppPin by mutableStateOf(SecurityPrefs.hasAppPin(app)); private set
    var wipeAfterFailures by mutableStateOf(SecurityPrefs.isWipeAfterFailuresEnabled(app)); private set
    var failedUnlocks by mutableStateOf(SecurityPrefs.failedAttempts(app)); private set
    var lockoutRemainingMs by mutableStateOf(SecurityPrefs.remainingLockoutMs(app)); private set
    var autoBackupEncrypted by mutableStateOf(SecurityPrefs.hasAutoBackupEncryption(app)); private set
    private var leftAt = 0L
    private var pickerExemptUntil = 0L
    var autoOrganize by mutableStateOf(prefs.getBoolean("auto_organize", false)); private set
    var backupTree by mutableStateOf(prefs.getString("backup_tree", null)); private set
    var backupAt by mutableStateOf(prefs.getLong("backup_at", 0)); private set
    var backupError by mutableStateOf(prefs.getString("backup_error", null)); private set

    // Tanya catatan
    var askSources by mutableStateOf<List<Note>>(emptyList()); private set
    var askAnswer by mutableStateOf(""); private set
    var askRunning by mutableStateOf(false); private set
    var askByAi by mutableStateOf(false); private set
    var askQuery by mutableStateOf(""); private set
    var openTasksList by mutableStateOf<List<Pair<Note, List<String>>>>(emptyList()); private set
    val askActive get() = askQuery.isNotEmpty()
    var searchFocusTick by mutableStateOf(0); private set

    // Asisten perintah
    var agent by mutableStateOf<AgentRun?>(null); private set
    private var agentUndo: Pair<List<Note>, List<Long>>? = null
    val agentCanUndo get() = agentUndo != null

    private var running: Job? = null
    private var batchJob: Job? = null
    val notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null) { notices.tryEmit(Notice(text, action, onAction)) }

    var hideAiBanner by mutableStateOf(prefs.getBoolean("hide_ai_banner", false)); private set
    fun dismissAiBanner() { hideAiBanner = true; prefs.edit().putBoolean("hide_ai_banner", true).apply() }

    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic_color", false)); private set
    fun updateDynamicColor(on: Boolean) { dynamicColor = on; prefs.edit().putBoolean("dynamic_color", on).apply() }
    var themeMode by mutableStateOf(ThemeMode.parse(prefs.getString("theme_mode", null))); private set
    fun updateThemeMode(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme_mode", m.name).apply() }

    @Volatile private var dirty = prefs.getBoolean("dirty", false)
    private fun markDirty() { if (!dirty) { dirty = true; prefs.edit().putBoolean("dirty", true).apply() } }

    init {
        appScope.launch { runCatching { repo.purgeSemanticData() } }
        viewModelScope.launch {
            ai.events.collect { ev ->
                val text = when (ev) {
                    AiEvent.GpuFallbackToCpu -> "GPU tidak didukung di HP ini; beralih otomatis ke CPU."
                    AiEvent.VisionGpuFallbackToCpu -> "GPU gambar tidak didukung; membaca gambar memakai CPU."
                    is AiEvent.ModelInstalled -> "${ev.name} terpasang dan aktif."
                    is AiEvent.ModelVerifyFailed -> "Berkas ${ev.name} rusak atau tidak lengkap; sudah dihapus. Silakan unduh ulang."
                    is AiEvent.DownloadFailed -> "Unduhan ${ev.name} gagal (kode ${ev.reason}). Periksa koneksi lalu coba lagi."
                    is AiEvent.Message -> ev.text
                }
                say(text)
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Attachments.sweepStaleTempFiles(app)
                repo.purgeOldTrash()
                Reminders.rescheduleAll(app)
                publishShortcuts(app)
                Attachments.cleanup(app, repo.allNotes(includeVault = true))
            }
            if (!locked) {
                reload()
                withContext(Dispatchers.IO) { Attachments.migrateLegacyAttachments(app) }
                refreshAttachmentNames()
            }
        }
        viewModelScope.launch {
            Reminders.changed.collect { id ->
                if (id == Reminders.TEST_ID || locked) return@collect
                if (screen == Screen.Editor && draft.id == id) withContext(Dispatchers.IO) { repo.getNote(id) }?.let { n ->
                    if (!n.vault || vaultUnlocked) {
                        draft = draft.copy(remindAt = n.remindAt, remindRepeat = n.remindRepeat, remindAlarm = n.remindAlarm, remindFired = n.remindFired)
                        opened = opened?.copy(remindAt = n.remindAt, remindFired = n.remindFired)
                    }
                }
                reload()
            }
        }
    }

    private val ctx get() = getApplication<Application>()

    private fun refreshAttachmentNames() {
        appScope.launch {
            val names = Attachments.existingNames(ctx)
            withContext(Dispatchers.Main) { existingAttachments = names }
        }
    }

    private fun persist(n: Note, before: Note? = null): Long {
        val id = repo.persist(n, before)
        markDirty()
        return id
    }

    private fun applyNotes(list: List<Note>) {
        listStale = false
        val scoped = if (vaultUnlocked) list else list.filter { !it.vault }
        val publicList = scoped.filter { !it.vault }
        notes = scoped
        stats = Stats(publicList, AiText::openTaskCount)
        notesById = scoped.associateBy { it.id }
        today = dueToday(publicList)
        openTaskCount = stats.openTaskCount
        refreshShown()
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            val prev = index
            val idx = withContext(Dispatchers.Default) {
                val live = publicList.filter { !it.inTrash }
                prev.update(live.map { SearchIndex.Doc(it.id, it.title, it.body, it.tags) })
            }
            index = idx
            if (query.isNotBlank()) refreshShown()
        }
    }

    private suspend fun reload() {
        if (locked) return
        while (true) {
            val gen = writeGen
            val (read, vCount) = withContext(Dispatchers.IO) {
                repo.allNotes(includeVault = vaultUnlocked) to repo.vaultCount()
            }
            if (gen != writeGen) continue
            vaultCount = vCount
            if (locked) return
            val old = notesById
            applyNotes(read.map { n -> old[n.id]?.takeIf { it == n } ?: n })
            loaded = true
            return
        }
    }

    private fun putLocal(n: Note, deferList: Boolean = false) {
        writeGen++
        val i = notes.indexOfFirst { it.id == n.id }
        val updatedList = if (i < 0) notes + n else notes.toMutableList().also { it[i] = n }
        if (deferList) {
            notes = updatedList
            notesById = notesById + (n.id to n)
            listStale = true
        } else {
            applyNotes(updatedList)
        }
    }

    private fun flushListIfStale() {
        if (listStale) applyNotes(notes)
    }

    private fun dropLocal(id: Long) { writeGen++; applyNotes(notes.filter { it.id != id }) }

    private fun dueToday(list: List<Note>): List<Note> {
        val end = LocalDate.now().plusDays(1).atStartOfDay().toMillis()
        return list.filter { !it.vault && !it.inTrash && it.hasReminder && (it.remindFired || it.remindAt < end) }.sortedBy { it.remindAt }
    }

    val allTags: List<String> get() = stats.tags
    val folders: List<String> get() = stats.folders
    val trashCount get() = stats.trashCount
    val reminderCount get() = stats.reminderCount

    private fun refreshShown() {
        val v = view; val q = query; val tag = tagFilter; val list = notes
        if (v == NoteView.Vault) {
            if (!vaultUnlocked) {
                shown = emptyList()
                return
            }
            searchJob?.cancel(); searchJob = null
            val needle = q.trim()
            val base = list.filter { it.vault && !it.inTrash }.let { items ->
                if (needle.isBlank()) items
                else items.filter {
                    it.title.contains(needle, ignoreCase = true) ||
                        it.body.contains(needle, ignoreCase = true) ||
                        it.tags.any { t -> t.contains(needle, ignoreCase = true) }
                }
            }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
            shown = base
            return
        }
        val inView: (Note) -> Boolean = when (v) {
            NoteView.All -> { n -> !n.vault && !n.inTrash && !n.archived }
            is NoteView.Folder -> { n -> !n.vault && !n.inTrash && !n.archived && n.folder == v.name }
            NoteView.Reminders -> { n -> !n.vault && !n.inTrash && n.hasReminder }
            NoteView.Archive -> { n -> !n.vault && !n.inTrash && n.archived }
            NoteView.Trash -> { n -> !n.vault && n.inTrash }
            NoteView.Vault -> { _ -> false }
        }
        fun show(base: List<Note>) { shown = if (tag == null) base else base.filter { tag in it.tags } }
        searchJob?.cancel(); searchJob = null
        when {
            q.isBlank() && v == NoteView.Reminders -> show(list.filter(inView).sortedBy { it.remindAt })
            q.isBlank() -> show(list.filter(inView).sortedWith(
                compareByDescending<Note> { it.pinned }.then(
                    when (sort) {
                        Sort.Updated -> compareByDescending { it.updated }
                        Sort.Created -> compareByDescending { it.created }
                        Sort.Title -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    }
                )
            ))
            v == NoteView.Trash -> show(list.filter { !it.vault && it.inTrash && (it.title + " " + it.body).contains(q.trim(), ignoreCase = true) })
            else -> {
                val idx = index; val terms = aiTerms; val byId = stats.byId
                searchJob = viewModelScope.launch {
                    val hits = withContext(Dispatchers.Default) { idx.search(q, terms, limit = 100) }
                    show(hits.mapNotNull { byId[it.id] }.filter { !it.vault && if (v == NoteView.All) !it.inTrash else inView(it) })
                }
            }
        }
    }

    fun updateSort(s: Sort) { sort = s; prefs.edit().putString("sort", s.name).apply(); refreshShown() }
    fun updateQuery(q: String) {
        if (q == query) return
        if (askActive) closeAsk()
        query = q; aiTerms = emptyList(); clearSelection()
        refreshShown()
    }
    /** Kategori Pengaturan yang sedang dibuka; null = dashboard. */
    var settingsSection by mutableStateOf<String?>(null)
    fun go(s: Screen) {
        if (s == Screen.Settings && screen != Screen.Settings) settingsSection = null
        if (s != Screen.Editor) flushListIfStale()
        if (s == Screen.Settings && vaultUnlocked) lockVault()
        screen = s
    }

    fun switchView(v: NoteView) {
        clearSelection()
        if (v != NoteView.Vault && vaultUnlocked) lockVault()
        view = v
        tagFilter = null
    }

    fun expandQueryWithAi() {
        val q = query.trim()
        if (q.isEmpty() || aiBusy) return
        viewModelScope.launch {
            aiBusy = true
            try {
                val terms = ai.expandQuery(q)
                if (query.trim() == q) { aiTerms = terms; refreshShown() }
                if (terms.isEmpty()) say("AI tidak memberi kata kunci tambahan.")
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { say("AI gagal: ${e.message}")
            } finally { aiBusy = false }
        }
    }

    // ============================== Editor ==============================

    fun open(note: Note?) {
        if (note != null && note.vault && !vaultUnlocked) return
        stopAi()
        val inVault = view == NoteView.Vault && vaultUnlocked
        draft = note ?: Note(folder = (view as? NoteView.Folder)?.name ?: "", vault = inVault)
        opened = note?.takeIf { it.id != 0L }; snapshotDone = false
        fresh = draft.id == 0L
        clean = draft.takeIf { it.id != 0L }
        suggestedTags = emptyList(); aiJob = null; undoAi = null; aiSheet = false; versions = null; reminderFor = null
        refreshAttachmentNames()
        screen = Screen.Editor
    }

    fun openFromReminder(id: Long) {
        if (screen == Screen.Editor) saveDraft()
        val target = repo.getNote(id) ?: return
        if (target.vault && !vaultUnlocked) {
            pendingVaultNoteId = id
            vaultUnlockRequested = true
            return
        }
        Reminders.done(ctx, id)
        open(target)
        viewModelScope.launch { reload() }
    }

    fun edit(transform: (Note) -> Note) {
        val next = transform(draft)
        if (next != draft) draft = next
    }

    fun addTag(raw: String) {
        val t = com.catatanku.app.domain.usecase.TextTools.normalizeTag(raw)
        if (t.isNotEmpty() && t !in draft.tags) draft = draft.copy(tags = draft.tags + t)
        suggestedTags = suggestedTags - t
    }

    fun saveDraft() {
        val d = draft
        if (d.isBlank && d.id == 0L) return
        val original = notesById[d.id]
        if (original != null && original.copy(updated = d.updated) == d) { clean = d; return }
        val toSave = d.copy(updated = System.currentTimeMillis())
        val before = opened
        if (before != null && !snapshotDone && before.id == d.id && before.body.isNotBlank() && (before.body != d.body || before.title != d.title)) {
            repo.addVersion(before.id, before.title, before.body, before.updated)
            snapshotDone = true
        }
        val saved = toSave.copy(id = persist(toSave, original))
        draft = saved; clean = saved
        if (opened == null) { opened = saved; snapshotDone = true }
        putLocal(saved, deferList = screen == Screen.Editor)
    }

    fun closeEditor() {
        stopAi(); saveDraft(); screen = Screen.List
        val d = draft
        if (fresh && d.id != 0L && d.isBlank && !d.hasReminder) {
            dropLocal(d.id)
            appScope.launch { repo.deleteForever(d.id) }
        } else {
            flushListIfStale()
            if (autoOrganize && !d.vault) organizeInBackground(d.id)
        }
    }
    fun openRelated(note: Note) { saveDraft(); flushListIfStale(); open(note) }

    fun trashDraft() {
        stopAi()
        if (draft.id == 0L) { draft = Note(); screen = Screen.List; flushListIfStale(); return }
        val pinned = draft.pinned
        draft = draft.copy(deletedAt = System.currentTimeMillis(), pinned = false)
        saveDraft(); screen = Screen.List; flushListIfStale()
        val id = draft.id
        say("Dipindah ke Sampah.", "Urungkan") { notesById[id]?.let { update(it.copy(deletedAt = 0, pinned = pinned)) } }
    }

    fun restoreDraft() { draft = draft.copy(deletedAt = 0); saveDraft(); say("Catatan dipulihkan.") }

    fun deleteDraftForever() {
        val id = draft.id
        draft = Note(); screen = Screen.List
        if (id != 0L) {
            dropLocal(id)
            appScope.launch { repo.deleteForever(id) }
            markDirty()
        } else {
            flushListIfStale()
        }
    }

    fun deleteNoteForever(n: Note) {
        if (n.id == 0L) return
        dropLocal(n.id)
        appScope.launch { repo.deleteForever(n.id) }
        markDirty()
        say("Catatan dihapus permanen.")
    }

    // ============================== Lampiran gambar & suara ==============================

    fun attachImage(uri: Uri) = viewModelScope.launch {
        try {
            val name = withContext(Dispatchers.IO) { Attachments.importImage(ctx, uri) }
            existingAttachments = existingAttachments + name
            draft = draft.copy(attachments = draft.attachments + name)
            saveDraft()
            say("Gambar ditambahkan.")
        } catch (e: Throwable) { say("Gagal memuat gambar: ${e.message}") }
    }

    fun attachRecordedAudio(name: String) {
        existingAttachments = existingAttachments + name
        draft = draft.copy(attachments = draft.attachments + name)
        saveDraft()
        say("Catatan suara disimpan.")
    }

    fun removeAttachment(name: String) {
        if (name !in draft.attachments) return
        draft = draft.copy(attachments = draft.attachments - name)
        saveDraft()
        say("Lampiran dihapus.", "Urungkan") {
            if (name !in draft.attachments) {
                draft = draft.copy(attachments = draft.attachments + name)
                saveDraft()
            }
        }
    }

    fun openSharedMedia(uri: Uri, isImage: Boolean, title: String = "", body: String = "") = viewModelScope.launch {
        try {
            val name = withContext(Dispatchers.IO) {
                if (isImage) Attachments.importImage(ctx, uri) else Attachments.importAudio(ctx, uri)
            }
            existingAttachments = existingAttachments + name
            open(Note(title = title, body = body, attachments = listOf(name)))
            saveDraft()
        } catch (e: Throwable) {
            say("Gagal menerima lampiran: ${e.message}")
        }
    }

    fun runMediaAi(name: String, extractTasks: Boolean = false) {
        if (aiJob?.running == true) return
        val file = Attachments.file(ctx, name)
        if (!file.exists()) { say("Berkas lampiran tidak ditemukan."); return }
        val isImg = Attachments.isImage(name)
        val label = when {
            isImg && extractTasks -> "Ekstrak tugas"
            isImg -> "Ekstrak teks"
            extractTasks -> "Ekstrak tugas"
            else -> "Transkripsi"
        }
        aiJob = AiJob(
            if (extractTasks) AiAction.Tasks else AiAction.Summarize,
            customTitle = label,
            progress = if (isImg) (if (extractTasks) "Membaca tulisan di gambar…" else "Membaca gambar…") else "Mendengarkan rekaman…",
        )
        running = viewModelScope.launch {
            try {
                if (!extractTasks) {
                    val prompt = if (isImg) AiText.imageReadPrompt() else AiText.audioTranscribePrompt()
                    val task = if (isImg) AiTaskBudget.MEDIA_READ else AiTaskBudget.MEDIA_TRANSCRIBE
                    val out = ai.generate(
                        prompt,
                        task = task,
                        imageFile = if (isImg) file else null,
                        audioFile = if (!isImg) file else null,
                        ocrDerivative = isImg,
                    ) { partial ->
                        val cleaned = if (isImg) AiText.cleanBlock(partial) else AiText.cleanTranscript(partial)
                        aiJob = aiJob?.copy(text = cleaned)
                    }
                    val finalClean = if (isImg) AiText.cleanBlock(out) else AiText.cleanTranscript(out)
                    val detectedTime = if (!isImg) TimeParse.best(finalClean)?.at?.toMillis() else null
                    aiJob = aiJob?.copy(
                        text = finalClean, running = false, progress = null, suggestedRemindAt = detectedTime,
                        error = if (finalClean.isBlank()) "Model tidak menghasilkan teks." else null,
                    )
                    return@launch
                }

                val step1Prompt = if (isImg) AiText.imageOcrPrompt() else AiText.audioTranscribePrompt()
                val step1Task = if (isImg) AiTaskBudget.MEDIA_READ else AiTaskBudget.MEDIA_TRANSCRIBE
                val raw1 = ai.generate(
                    step1Prompt,
                    task = step1Task,
                    imageFile = if (isImg) file else null,
                    audioFile = if (!isImg) file else null,
                    ocrDerivative = isImg,
                )
                val step1Text = if (isImg) AiText.cleanBlock(raw1) else AiText.cleanTranscript(raw1)
                if (step1Text.isBlank() || (isImg && AiText.isNoTextOrSceneDescription(step1Text))) {
                    aiJob = aiJob?.copy(
                        text = "", running = false, progress = null,
                        error = if (isImg) "Gambar ini tidak memuat tulisan atau konteks tugas." else "Rekaman tidak memuat ucapan yang jelas.",
                    )
                    return@launch
                }

                aiJob = aiJob?.copy(progress = "Memilah tugas dan waktu…")
                val foundTime = TimeParse.best(step1Text)
                val direct = AiText.extractDirectTasksAndTime(step1Text)
                val extracted = direct ?: AiText.parseExtractedTasks(
                    ai.generate(AiText.tasksFromTextPrompt(step1Text), AiTaskBudget.TASKS),
                    foundTime,
                )
                if (extracted.isEmpty) {
                    aiJob = aiJob?.copy(
                        text = "", running = false, progress = null,
                        error = (if (isImg) "Tidak ada tugas yang ditemukan pada gambar ini." else "Tidak ada tugas yang ditemukan dalam rekaman ini.") +
                            (if (!isImg) "\n\nUcapan: “$step1Text”" else ""),
                    )
                } else {
                    aiJob = aiJob?.copy(
                        text = extracted.text,
                        running = false,
                        progress = null,
                        suggestedRemindAt = extracted.remindAt?.toMillis(),
                    )
                }
            } catch (e: CancellationException) {
                aiJob = aiJob?.copy(running = false, progress = null)
            } catch (e: Throwable) {
                aiJob = aiJob?.copy(running = false, progress = null, error = "AI gagal: ${e.message}")
            }
        }
    }

    // ============================== Riwayat versi ==============================

    fun openVersions() {
        saveDraft()
        if (draft.id == 0L) { say("Catatan ini belum punya riwayat."); return }
        versions = repo.versions(draft.id)
    }

    fun closeVersions() { versions = null }

    fun restoreVersion(v: Version) {
        val d = draft
        if (d.body.isNotBlank() && d.body != v.body && versions.orEmpty().none { it.body == d.body && it.title == d.title })
            repo.addVersion(d.id, d.title, d.body, System.currentTimeMillis())
        snapshotDone = true
        draft = d.copy(title = v.title, body = v.body)
        versions = null
        saveDraft()
        say("Versi ${whenLabel(v.saved)} dipulihkan.")
    }

    // ============================== Pengingat ==============================

    fun openReminder(note: Note? = null, seed: Long? = null) {
        val target = note ?: run { saveDraft(); draft }
        if (target.id == 0L) { say("Tulis sesuatu dulu, baru setel pengingat."); return }
        aiSheet = false
        reminderSeed = seed; reminderFor = target
    }

    fun closeReminder() { reminderFor = null; reminderSeed = null }

    private fun changeReminder(id: Long, f: (Note) -> Note) {
        if (screen == Screen.Editor && draft.id == id) { draft = f(draft); saveDraft() }
        else notesById[id]?.let { update(f(it)) }
    }

    fun saveReminder(at: Long, repeat: Repeat, alarm: Boolean) {
        val id = reminderFor?.id ?: return
        closeReminder()
        changeReminder(id) { it.copy(remindAt = at, remindRepeat = repeat, remindAlarm = alarm, remindFired = false) }
        say(
            "Pengingat: ${whenLabel(at)}" + when {
                !Reminders.canNotify(ctx) -> ". Notifikasi Catatanku dimatikan; aktifkan di pengaturan HP."
                !Reminders.canExact(ctx) -> ". Bisa terlambat beberapa menit karena izin alarm tepat waktu dimatikan."
                else -> ""
            }
        )
    }

    fun removeReminder(note: Note? = null) {
        val id = (note ?: reminderFor ?: draft).id
        closeReminder()
        changeReminder(id) { it.copy(remindAt = 0, remindRepeat = Repeat.None, remindAlarm = false, remindFired = false) }
        say("Pengingat dihapus.")
    }

    fun finishReminder(n: Note) { Reminders.done(ctx, n.id) }

    fun reminderHint(d: Note = draft): TimeParse.Found? =
        if (d.hasReminder || d.inTrash || d.isBlank) null else TimeParse.best(d.title + "\n" + d.body)

    fun suggestReminder() {
        val d = draft
        if (d.isBlank || aiBusy) return
        TimeParse.best(d.title + "\n" + d.body)?.let { openReminder(seed = it.at.toMillis()); return }
        if (!ai.hasModel) { aiSheet = false; say("Tidak menemukan tanggal atau jam di catatan ini."); return }
        aiSheet = false
        viewModelScope.launch {
            aiBusy = true
            try {
                val found = ai.reminderPhrase(d.title, d.body)?.let { TimeParse.best(it, LocalDateTime.now()) }
                if (found != null && screen == Screen.Editor && draft.id == d.id) openReminder(seed = found.at.toMillis())
                else if (found == null) say("AI tidak menemukan jadwal atau tenggat di catatan ini.")
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { say("AI gagal: ${e.message}")
            } finally { aiBusy = false }
        }
    }

    fun toggleArchive() {
        val nowArchived = !draft.archived
        val pinned = draft.pinned
        draft = draft.copy(archived = nowArchived, pinned = if (nowArchived) false else draft.pinned)
        saveDraft()
        if (!nowArchived) { say("Dikeluarkan dari arsip."); return }
        screen = Screen.List
        val id = draft.id
        say("Diarsipkan.", "Urungkan") { notesById[id]?.let { update(it.copy(archived = false, pinned = pinned)) } }
    }

    fun emptyTrash() = viewModelScope.launch {
        val vaultOnly = view == NoteView.Vault
        withContext(Dispatchers.IO) { repo.emptyTrash(vaultOnly = vaultOnly) }; reload()
        refreshAttachmentNames()
        markDirty()
        say("Sampah dikosongkan.")
    }

    fun moveDraftToVault(intoVault: Boolean) {
        if (!intoVault && !vaultUnlocked) return
        stopAi()
        if (draft.id == 0L && draft.isBlank) return
        val next = draft.copy(
            vault = intoVault,
            pinned = if (intoVault) false else draft.pinned,
            folder = if (intoVault) "" else draft.folder,
            archived = if (intoVault) false else draft.archived,
            updated = System.currentTimeMillis(),
        )
        draft = next
        saveDraft()
        if (intoVault && !vaultUnlocked) {
            draft = Note()
            clean = null
            opened = null
            dropLocal(next.id)
            vaultCount++
            screen = Screen.List
            say("Dipindahkan ke Brankas.")
        } else {
            viewModelScope.launch { reload() }
            say(if (intoVault) "Dipindahkan ke Brankas." else "Dikeluarkan dari Brankas.")
        }
    }

    fun moveNoteToVault(n: Note, intoVault: Boolean) {
        if (!intoVault && !vaultUnlocked) return
        val updated = n.copy(
            vault = intoVault,
            pinned = if (intoVault) false else n.pinned,
            folder = if (intoVault) "" else n.folder,
            archived = if (intoVault) false else n.archived,
            updated = System.currentTimeMillis(),
        )
        persist(updated, n)
        if (intoVault && !vaultUnlocked) {
            dropLocal(n.id)
            vaultCount++
        } else {
            putLocal(updated)
            viewModelScope.launch { reload() }
        }
        say(if (intoVault) "Dipindahkan ke Brankas." else "Dikeluarkan dari Brankas.")
    }

    fun relatedTo(d: Note): List<Note> {
        if (d.vault) return emptyList()
        val byId = notesById
        return index.related(d.title, d.body, d.tags, d.id).mapNotNull { byId[it.id]?.takeIf { !it.vault } }
    }

    fun suggestTags() {
        val d = draft
        if (d.isBlank || aiBusy) return
        if (!ai.hasModel) {
            suggestedByAi = false
            suggestedTags = index.keywords(d.title, d.body).filter { it !in d.tags }
            return
        }
        viewModelScope.launch {
            aiBusy = true
            try {
                suggestedTags = ai.suggestTags(d.title, d.body, allTags).filter { it !in draft.tags }
                suggestedByAi = true
                if (suggestedTags.isEmpty()) say("AI tidak menghasilkan tag baru.")
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                suggestedByAi = false
                suggestedTags = index.keywords(d.title, d.body).filter { it !in d.tags }
                say("AI gagal (${e.message}); memakai kata kunci.")
            } finally { aiBusy = false }
        }
    }

    // ============================== Bantu tulis ==============================

    fun runAi(action: AiAction, custom: String = "", selection: IntRange? = aiSelection) {
        val d = draft
        if (d.body.isBlank() || aiJob?.running == true) return
        val sel = selection?.takeIf { it.first >= 0 && it.last < d.body.length && !it.isEmpty() }
        if (sel != null) { runAiOnSelection(action, custom, sel); return }
        aiJob = AiJob(action)
        running = viewModelScope.launch {
            try {
                var suggestedTime: Long? = null
                val budget = AiTaskBudget.forAction(action)
                val clean = when {
                    action == AiAction.Plan || action == AiAction.Extract -> {
                        val out = ai.generate(AiText.prompt(action, d.title, d.body, custom), budget) { partial ->
                            aiJob = aiJob?.copy(text = AiText.cleanStructured(partial))
                        }
                        val structured = AiText.cleanStructured(out).let { if (action == AiAction.Plan) AiText.normalizeChecklist(it) else it }
                        // Waktu dihitung oleh kode dari frasa yang disalin model, bukan oleh model.
                        suggestedTime = TimeParse.best(structured)?.takeIf { it.hasTime }?.at?.toMillis()
                        structured
                    }
                    AiText.isStructured(action) && !(action.wholeNote && d.body.length > AiText.maxInput) -> {
                        val out = ai.generate(AiText.prompt(action, d.title, d.body, custom), budget) { partial ->
                            aiJob = aiJob?.copy(text = AiText.cleanStructured(partial))
                        }
                        AiText.cleanStructured(out)
                    }
                    action == AiAction.Tasks && d.body.length <= AiText.maxInput -> {
                        val foundTime = TimeParse.best(d.body)
                        val direct = AiText.extractDirectTasksAndTime(d.body)
                        val extracted = direct ?: AiText.parseExtractedTasks(
                            ai.generate(AiText.tasksFromTextPrompt(d.body), AiTaskBudget.TASKS), foundTime
                        )
                        suggestedTime = extracted.remindAt?.toMillis()
                        if (extracted.isEmpty) "Tidak ada tugas." else extracted.text
                    }
                    action.wholeNote && d.body.length > AiText.maxInput -> runInParts(action, d)
                    else -> {
                        val out = ai.generate(AiText.prompt(action, d.title, d.body, custom), budget) { partial ->
                            aiJob = aiJob?.copy(text = AiText.cleanBlock(partial))
                        }
                        if (action.forTitle) AiText.cleanLine(AiText.strip(out)) else AiText.cleanBlock(out)
                    }
                }
                aiJob = aiJob?.copy(
                    text = clean, running = false, progress = null, suggestedRemindAt = suggestedTime,
                    error = if (clean.isBlank()) "Model tidak menghasilkan teks." else null,
                )
            } catch (e: CancellationException) {
                aiJob = aiJob?.copy(running = false, progress = null)
            } catch (e: Throwable) {
                aiJob = aiJob?.copy(running = false, progress = null, error = "AI gagal: ${e.message}")
            }
        }
    }

    private fun runAiOnSelection(action: AiAction, custom: String, sel: IntRange) {
        val d = draft
        val picked = d.body.substring(sel.first, sel.last + 1)
        aiJob = AiJob(action, selStart = sel.first, selEnd = sel.last + 1, selText = picked)
        running = viewModelScope.launch {
            try {
                val out = ai.generate(AiText.selectionPrompt(action, picked, custom), AiTaskBudget.forAction(action)) { partial ->
                    aiJob = aiJob?.copy(text = if (AiText.isStructured(action)) AiText.cleanStructured(partial) else AiText.cleanBlock(partial))
                }
                var clean = if (action.forTitle) AiText.cleanLine(AiText.strip(out))
                else if (AiText.isStructured(action)) AiText.cleanStructured(out) else AiText.cleanBlock(out)
                if (action == AiAction.Plan) clean = AiText.normalizeChecklist(clean)
                aiJob = aiJob?.copy(text = clean, running = false, error = if (clean.isBlank()) "Model tidak menghasilkan teks." else null)
            } catch (e: CancellationException) {
                aiJob = aiJob?.copy(running = false)
            } catch (e: Throwable) {
                aiJob = aiJob?.copy(running = false, error = "AI gagal: ${e.message}")
            }
        }
    }

    private suspend fun runInParts(action: AiAction, d: Note): String {
        val parts = AiText.chunks(d.body)
        val results = ArrayList<String>()
        val budget = AiTaskBudget.forAction(action)
        parts.forEachIndexed { i, part ->
            val sofar = results.joinToString("\n")
            val label = "Bagian ${i + 1} dari ${parts.size}"
            aiJob = aiJob?.copy(progress = label)
            val out = ai.generate(AiText.partPrompt(action, d.title, part, i, parts.size), budget) { partial ->
                aiJob = aiJob?.copy(text = (if (sofar.isEmpty()) "" else sofar + "\n") + AiText.cleanBlock(partial), progress = label)
            }
            results += AiText.cleanBlock(out) + if (action == AiAction.Fix) part.takeLastWhile { it.isWhitespace() } else ""
        }
        return when (action) {
            AiAction.Fix -> results.joinToString("").trim()
            AiAction.Tasks -> AiText.mergeTasks(results)
            else -> {
                val points = results.joinToString("\n")
                if (parts.size == 1) points else {
                    aiJob = aiJob?.copy(text = points, progress = "Menggabungkan ringkasan")
                    AiText.cleanBlock(ai.generate(AiText.mergeSummaryPrompt(d.title, points), AiTaskBudget.SUMMARIZE) { partial ->
                        aiJob = aiJob?.copy(text = AiText.cleanBlock(partial))
                    })
                }
            }
        }
    }

    internal fun debugAiJob(j: AiJob) { aiJob = j }

    fun stopAi() { running?.cancel(); running = null }
    fun dismissAi() { stopAi(); aiJob = null; aiSheet = false; aiSelection = null }

    fun applyAi(replace: Boolean, remindAt: Long? = null) {
        val j = aiJob ?: return
        val text = j.text.trim()
        if (text.isEmpty()) return
        undoAi = UndoAiSnap(draft.title, draft.body, draft.remindAt, draft.remindRepeat, draft.remindAlarm, draft.remindFired)
        var next = when {
            j.isTemplate -> {
                val (t, b) = com.catatanku.app.domain.usecase.Templates.insertInto(
                    draft.title, draft.body, com.catatanku.app.domain.usecase.NoteTemplate("ai", j.templateName.orEmpty(), j.templateTitle, text),
                )
                draft.copy(title = t, body = b)
            }
            j.onSelection && !j.action.forTitle -> {
                val body = draft.body
                // Teks bisa sudah berubah sejak aksi dimulai: cari lagi potongan aslinya bila posisinya bergeser.
                val start = if (j.selEnd <= body.length && body.substring(j.selStart, j.selEnd) == j.selText) j.selStart else body.indexOf(j.selText)
                when {
                    start < 0 -> draft.copy(body = body.trimEnd() + "\n\n" + text)
                    replace -> draft.copy(body = body.substring(0, start) + text + body.substring(start + j.selText.length))
                    else -> {
                        val end = start + j.selText.length
                        draft.copy(body = body.substring(0, end) + "\n" + text + body.substring(end))
                    }
                }
            }
            j.action.forTitle -> draft.copy(title = AiText.cleanLine(text).take(120))
            replace || draft.body.isBlank() -> draft.copy(body = text)
            j.action == AiAction.Continue -> draft.copy(body = draft.body.trimEnd() + " " + text)
            else -> draft.copy(body = draft.body.trimEnd() + "\n\n" + text)
        }
        val validRemind = remindAt?.takeIf { it > System.currentTimeMillis() }
        if (validRemind != null) {
            next = next.copy(remindAt = validRemind, remindRepeat = Repeat.None, remindAlarm = false, remindFired = false)
        }
        draft = next
        if (validRemind != null) {
            saveDraft()
            say(
                "Pengingat: ${whenLabel(validRemind)}" + when {
                    !Reminders.canNotify(ctx) -> ". Notifikasi Catatanku dimatikan; aktifkan di pengaturan HP."
                    !Reminders.canExact(ctx) -> ". Bisa terlambat beberapa menit karena izin alarm tepat waktu dimatikan."
                    else -> ""
                }
            )
        }
        aiJob = null; aiSheet = false; aiSelection = null; templateSheet = false
    }

    // ============================== Templat ==============================

    fun loadTemplates() {
        appScope.launch {
            val list = runCatching { templateStore.load() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { userTemplates = list }
        }
    }

    fun openTemplates() { loadTemplates(); aiJob = null; templateSheet = true }
    fun closeTemplates() { if (aiJob?.isTemplate == true) stopAi(); if (aiJob?.isTemplate == true) aiJob = null; templateSheet = false }

    fun applyTemplate(t: com.catatanku.app.domain.usecase.NoteTemplate) {
        undoAi = UndoAiSnap(draft.title, draft.body, draft.remindAt, draft.remindRepeat, draft.remindAlarm, draft.remindFired)
        val (title, body) = com.catatanku.app.domain.usecase.Templates.insertInto(draft.title, draft.body, t)
        draft = draft.copy(title = title, body = body)
        templateSheet = false; aiJob = null
        say("Templat ${t.name} dipakai.", "Urungkan") { undoAiChange() }
    }

    /** Asisten membuat templat dari deskripsi singkat; catatan boleh masih kosong. */
    fun generateTemplate(description: String) {
        val desc = description.trim()
        if (desc.isEmpty() || aiJob?.running == true) return
        val name = desc.replace('\n', ' ').replaceFirstChar { it.titlecase() }.take(com.catatanku.app.domain.usecase.Templates.MAX_NAME)
        aiJob = AiJob(AiAction.Custom, customTitle = "Templat baru", templateName = name)
        running = viewModelScope.launch {
            try {
                val out = ai.generate(AiText.templatePrompt(desc), AiTaskBudget.TEMPLATE) { partial ->
                    aiJob = aiJob?.copy(text = AiText.cleanStructured(partial.substringAfter('\n', "")))
                }
                val parsed = AiText.parseTemplate(out)
                aiJob = if (parsed == null) aiJob?.copy(running = false, text = "", error = "Asisten tidak menghasilkan templat yang rapi. Coba deskripsi lain.")
                else aiJob?.copy(running = false, text = parsed.body, templateTitle = parsed.title)
            } catch (e: CancellationException) {
                aiJob = aiJob?.copy(running = false)
            } catch (e: Throwable) {
                aiJob = aiJob?.copy(running = false, error = "AI gagal: ${e.message}")
            }
        }
    }

    fun saveTemplate(name: String, title: String, body: String, announce: Boolean = true) {
        val clean = com.catatanku.app.domain.usecase.Templates.sanitize(name, title, body) ?: return
        val t = com.catatanku.app.domain.usecase.NoteTemplate("u" + System.currentTimeMillis(), clean.first, clean.second, clean.third)
        val next = (listOf(t) + userTemplates.filter { !it.name.equals(t.name, ignoreCase = true) }).take(com.catatanku.app.domain.usecase.Templates.MAX_USER_TEMPLATES)
        userTemplates = next
        appScope.launch { runCatching { templateStore.save(next) } }
        if (announce) say("Disimpan ke Templat saya.")
    }

    fun saveGeneratedTemplateAndApply() {
        val j = aiJob ?: return
        if (!j.isTemplate || j.text.isBlank()) return
        saveTemplate(j.templateName.orEmpty(), j.templateTitle, j.text, announce = false)
        applyAi(replace = false)
        say("Templat disimpan dan dipakai.", "Urungkan") { undoAiChange() }
    }

    fun saveDraftAsTemplate(name: String) {
        val d = draft
        if (d.body.isBlank()) { say("Catatan masih kosong."); return }
        saveTemplate(name.ifBlank { d.heading }, "", d.body)
    }

    fun deleteTemplate(t: com.catatanku.app.domain.usecase.NoteTemplate) {
        if (t.builtIn) return
        val before = userTemplates
        val next = before.filter { it.id != t.id }
        userTemplates = next
        appScope.launch { runCatching { templateStore.save(next) } }
        say("Templat ${t.name} dihapus.", "Urungkan") {
            userTemplates = before
            appScope.launch { runCatching { templateStore.save(before) } }
        }
    }

    // ============================== Utilitas catatan ==============================

    fun duplicateDraft() {
        saveDraft()
        val d = draft
        if (d.id == 0L || d.isBlank) return
        val now = System.currentTimeMillis()
        val copy = d.copy(
            id = 0L, title = (d.title.ifBlank { d.heading }.take(100) + " (salinan)"), created = now, updated = now,
            pinned = false, remindAt = 0L, remindRepeat = Repeat.None, remindAlarm = false, remindFired = false,
            attachments = emptyList(),
        )
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { persist(copy) }
            val saved = copy.copy(id = id)
            putLocal(saved)
            open(saved)
            say("Catatan digandakan" + if (d.attachments.isNotEmpty()) " (tanpa lampiran)." else ".")
        }
    }

    /** Ekspor satu catatan ke berkas pilihan pengguna. Catatan Brankas tidak diekspor sebagai teks polos. */
    fun exportDraft(uri: Uri, markdown: Boolean) {
        val d = draft
        if (d.vault) { say("Catatan Brankas tidak diekspor sebagai teks polos. Pakai ekspor terenkripsi di Pengaturan."); return }
        if (!com.catatanku.app.security.InputSecurity.isSafeExternalContentUri(uri.scheme, uri.authority, uri.userInfo, uri.host, ctx.packageName)) { say("Lokasi berkas tidak diizinkan."); return }
        val text = com.catatanku.app.domain.usecase.TextFormat.export(d.title, d.body, d.tags, markdown)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
            }
            say(if (ok) "Catatan diekspor." else "Ekspor gagal; coba lokasi lain.")
        }
    }

    fun exportFileName(markdown: Boolean) = com.catatanku.app.domain.usecase.TextFormat.fileName(draft.heading, if (markdown) "md" else "txt")

    // ============================== Tautan antarcatatan ==============================

    private fun linkPool(): List<Note> = notes.filter { !it.inTrash && (!it.vault || draft.vault) && it.id != draft.id }

    fun linkSuggestions(prefix: String): List<String> {
        val p = prefix.trim()
        return linkPool().asSequence().map { it.title.ifBlank { it.heading } }.filter { it.isNotBlank() && (p.isEmpty() || it.contains(p, ignoreCase = true)) }
            .distinctBy { it.lowercase() }.sortedBy { if (it.startsWith(p, ignoreCase = true)) 0 else 1 }.take(5).toList()
    }

    fun linkTarget(title: String): Note? =
        linkPool().firstOrNull { it.title.equals(title.trim(), ignoreCase = true) } ?: linkPool().firstOrNull { it.heading.equals(title.trim(), ignoreCase = true) }

    /** Membuka catatan yang ditautkan; bila belum ada, membuat catatan baru berjudul itu. */
    fun openLink(title: String) {
        val t = title.trim().take(120)
        if (t.isEmpty()) return
        val target = linkTarget(t)
        saveDraft(); flushListIfStale()
        if (target != null) { open(target); return }
        val fresh = Note(title = t, vault = draft.vault, folder = draft.folder)
        open(fresh)
        say("Catatan baru: $t")
    }

    fun backlinks(n: Note): List<Note> {
        val title = n.title.ifBlank { return emptyList() }
        return notes.filter { it.id != n.id && !it.inTrash && (!it.vault || n.vault) && com.catatanku.app.domain.usecase.TextFormat.linksTo(it.body, title) }.take(8)
    }

    // ============================== Pilih banyak ==============================

    fun toggleSelect(id: Long) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun selectAllShown() { selectedIds = shown.map { it.id }.toSet() }
    fun clearSelection() { selectedIds = emptySet() }
    val selectedNotes get() = selectedIds.mapNotNull { notesById[it] }

    /** Menerapkan perubahan ke banyak catatan dalam satu transaksi SQLite, lengkap dengan Urungkan. */
    private fun bulk(message: String, transform: (Note) -> Note) {
        val before = selectedNotes
        val after = before.map(transform).filterIndexed { i, n -> n != before[i] }
        clearSelection()
        if (after.isEmpty()) return
        val byId = after.associateBy { it.id }
        writeGen++
        applyNotes(notes.map { byId[it.id] ?: it })
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.persistAll(after, before.associateBy { it.id }) }; markDirty() }
        say(message, "Urungkan") {
            val restore = before.filter { b -> byId.containsKey(b.id) }
            val back = restore.associateBy { it.id }
            writeGen++
            applyNotes(notes.map { back[it.id] ?: it })
            viewModelScope.launch { withContext(Dispatchers.IO) { repo.persistAll(restore, after.associateBy { it.id }) }; markDirty() }
        }
    }

    fun bulkPin() {
        val pin = selectedNotes.any { !it.pinned }
        bulk(if (pin) "${selectedIds.size} catatan disematkan." else "Sematan dilepas.") { if (it.archived) it else it.copy(pinned = pin) }
    }
    fun bulkArchive() {
        val archive = selectedNotes.any { !it.archived }
        bulk(if (archive) "${selectedIds.size} catatan diarsipkan." else "${selectedIds.size} catatan dikeluarkan dari arsip.") { it.copy(archived = archive, pinned = false) }
    }
    fun bulkTrash() {
        val now = System.currentTimeMillis()
        bulk("${selectedIds.size} catatan dipindah ke Sampah.") { it.copy(deletedAt = now, pinned = false) }
    }
    fun bulkRestore() = bulk("${selectedIds.size} catatan dipulihkan.") { it.copy(deletedAt = 0) }
    fun bulkMove(folder: String) {
        val f = folder.trim().take(30)
        bulk(if (f.isEmpty()) "Dikeluarkan dari folder." else "Dipindah ke folder $f.") { it.copy(folder = f) }
    }
    fun bulkTag(raw: String) {
        val t = com.catatanku.app.domain.usecase.TextTools.normalizeTag(raw)
        if (t.isEmpty()) return
        bulk("Tag #$t ditambahkan.") { if (t in it.tags) it else it.copy(tags = it.tags + t) }
    }
    fun bulkDeleteForever() {
        val ids = selectedIds
        clearSelection()
        ids.forEach { id -> dropLocal(id) }
        viewModelScope.launch { withContext(Dispatchers.IO) { ids.forEach { repo.deleteForever(it) } }; markDirty() }
        say("${ids.size} catatan dihapus permanen.")
    }

    fun undoAiChange() {
        undoAi?.let { s ->
            val remChanged = draft.remindAt != s.remindAt || draft.remindRepeat != s.remindRepeat || draft.remindAlarm != s.remindAlarm || draft.remindFired != s.remindFired
            draft = draft.copy(
                title = s.title, body = s.body,
                remindAt = s.remindAt, remindRepeat = s.remindRepeat, remindAlarm = s.remindAlarm, remindFired = s.remindFired,
            )
            if (remChanged) saveDraft()
        }
        undoAi = null
    }

    fun organizeDraft() {
        val d = draft
        if (d.body.isBlank() || aiBusy) return
        viewModelScope.launch {
            aiBusy = true
            try {
                val o = ai.organize(d.title, d.body, folders, allTags)
                val merged = mergeOrganized(draft, o)
                say(if (merged == draft) "Tidak ada yang perlu dirapikan." else describe(draft, merged))
                draft = merged
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { say("AI gagal: ${e.message}")
            } finally { aiBusy = false }
        }
    }

    private fun mergeOrganized(n: Note, o: AiText.Organized) = n.copy(
        title = n.title.ifBlank { o.title },
        folder = n.folder.ifBlank { o.folder },
        tags = if (n.tags.isEmpty()) o.tags else n.tags,
    )

    private fun describe(old: Note, new: Note) = buildList {
        if (old.title != new.title) add("judul")
        if (old.folder != new.folder) add("folder “${new.folder}”")
        if (old.tags != new.tags) add("${new.tags.size} tag")
    }.joinToString(", ", prefix = "AI mengisi: ")

    private fun organizeInBackground(id: Long) {
        if (id == 0L || !ai.hasModel) return
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { repo.getNote(id) } ?: return@launch
                if (!needsOrganizing(n) || n.body.length < 40) return@launch
                val o = ai.organize(n.title, n.body, folders, allTags)
                val cur = withContext(Dispatchers.IO) { repo.getNote(id) } ?: return@launch
                if (cur.inTrash || (screen == Screen.Editor && draft.id == id)) return@launch
                val merged = mergeOrganized(cur, o)
                if (merged != cur) { withContext(Dispatchers.IO) { persist(merged) }; reload() }
            } catch (e: CancellationException) { throw e
            } catch (_: Throwable) {}
        }
    }

    fun updateAutoOrganize(on: Boolean) { autoOrganize = on; prefs.edit().putBoolean("auto_organize", on).apply() }

    fun stopBatch() { batchJob?.cancel() }

    private fun needsOrganizing(n: Note) = Stats.needsOrganizing(n, folders.isNotEmpty())

    val organizableCount get() = stats.organizable

    fun organizeAll() {
        if (batch != null) return
        val targets = notes.filter { !it.vault && needsOrganizing(it) }
        if (targets.isEmpty()) { say("Semua catatan sudah punya judul dan tag."); return }
        batch = 0 to targets.size
        batchJob = viewModelScope.launch {
            var changed = 0
            try {
                targets.forEachIndexed { i, n ->
                    val o = ai.organize(n.title, n.body, folders, allTags)
                    val cur = withContext(Dispatchers.IO) { repo.getNote(n.id) }
                    val editing = screen == Screen.Editor && draft.id == n.id
                    val merged = if (cur == null || cur.inTrash || editing) cur else mergeOrganized(cur, o)
                    if (merged != null && merged != cur) { withContext(Dispatchers.IO) { persist(merged) }; changed++ }
                    batch = (i + 1) to targets.size
                }
                say("$changed catatan dirapikan.")
            } catch (e: CancellationException) {
                say("Dihentikan. $changed catatan sudah dirapikan.")
            } catch (e: Throwable) {
                say("AI gagal: ${e.message}")
            } finally {
                batch = null
                viewModelScope.launch { reload() }
            }
        }
    }

    // ============================== Tanya catatan ==============================

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || askRunning) return
        val byId = stats.byId
        askQuery = q; askByAi = false
        askSources = index.search(q, limit = 4).mapNotNull { byId[it.id] }
        askAnswer = ""
        if (askSources.isEmpty()) { askAnswer = "Tidak ada catatan yang berkaitan dengan pertanyaan itu."; return }
        if (!ai.hasModel) { askAnswer = "Model AI belum dipasang. Berikut catatan yang paling berkaitan:"; return }
        askRunning = true; askByAi = true
        running = viewModelScope.launch {
            try {
                val out = ai.generate(AiText.askPrompt(q, askSources.map { it.title to it.body }), AiTaskBudget.ASK) {
                    askAnswer = AiText.cleanBlock(it)
                }
                askAnswer = AiText.cleanBlock(out).ifBlank { "Model tidak menghasilkan jawaban." }
            } catch (e: CancellationException) {
            } catch (e: Throwable) { askAnswer = "AI gagal: ${e.message}"
            } finally { askRunning = false }
        }
    }

    fun askOpenTasks() {
        if (askRunning) return
        askQuery = "Tugas terbuka"; askByAi = false
        val withTasks = notes.filter { !it.vault && !it.inTrash && !it.archived }.sortedByDescending { it.updated }
            .mapNotNull { n -> AiText.openTasks(n.body).takeIf { it.isNotEmpty() }?.let { n to it } }
        openTasksList = withTasks
        askSources = withTasks.map { it.first }
        askAnswer = if (withTasks.isEmpty()) "Tidak ada butir daftar centang yang belum selesai."
        else withTasks.joinToString("\n\n") { (n, tasks) -> n.heading.take(60) + "\n" + tasks.joinToString("\n") }
    }

    fun toggleOpenTask(note: Note, taskLine: String) {
        val cur = stats.byId[note.id] ?: return
        val nextBody = AiText.setTaskChecked(cur.body, taskLine, checked = true) ?: return
        update(cur.copy(body = nextBody, updated = System.currentTimeMillis()))
        if (askActive && !askByAi) askOpenTasks()
        say("Tugas dicentang.", "Urungkan") {
            stats.byId[note.id]?.let { latest ->
                AiText.setTaskChecked(latest.body, taskLine, checked = false)?.let { restored ->
                    update(latest.copy(body = restored, updated = System.currentTimeMillis()))
                    if (askActive && !askByAi) askOpenTasks()
                }
            }
        }
    }

    fun askRecap() {
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        digest("7 hari terakhir", notes.filter { !it.vault && !it.inTrash && !it.archived && it.updated >= since && it.body.isNotBlank() }
            .sortedByDescending { it.updated }, empty = "Tidak ada catatan yang ditulis atau diubah dalam 7 hari terakhir.")
    }

    /** Label cakupan tampilan saat ini, untuk "Ringkas tampilan ini". */
    fun digestScopeLabel(): String = when {
        query.isNotBlank() -> "hasil cari \"${query.trim().take(30)}\""
        tagFilter != null -> "tag #$tagFilter"
        else -> when (val v = view) {
            NoteView.All -> "semua catatan"
            is NoteView.Folder -> "folder ${v.name}"
            NoteView.Reminders -> "catatan berpengingat"
            NoteView.Archive -> "arsip"
            NoteView.Trash -> "sampah"
            NoteView.Vault -> "brankas"
        }
    }

    fun digestShown() = digest(digestScopeLabel(), shown.filter { !it.inTrash && it.body.isNotBlank() }, empty = "Tidak ada catatan berisi di tampilan ini.")

    /** Ringkasan lintas catatan oleh asisten, dengan nomor sumber; bisa disimpan sebagai catatan baru. */
    fun digest(scope: String, pool: List<Note>, empty: String) {
        if (askRunning) return
        clearSelection()
        val cap = if (AiText.maxInput > 4000) 10 else 6
        askQuery = "Ringkasan ${scope}"; askByAi = false; askDigest = false
        askSources = pool.take(cap)
        askAnswer = ""
        if (askSources.isEmpty()) { askAnswer = empty; return }
        if (!ai.hasModel) { askAnswer = "Asisten belum aktif. Berikut catatannya:"; return }
        askRunning = true; askByAi = true; askDigest = true
        val label = scope + if (pool.size > cap) " (${cap} terbaru dari ${pool.size})" else ""
        running = viewModelScope.launch {
            try {
                val out = ai.generate(AiText.digestPrompt(label, askSources.map { it.title to it.body }), AiTaskBudget.DIGEST) {
                    askAnswer = AiText.cleanStructured(it)
                }
                askAnswer = AiText.cleanStructured(out).ifBlank { "Model tidak menghasilkan ringkasan." }
            } catch (e: CancellationException) {
            } catch (e: Throwable) { askAnswer = "AI gagal: ${e.message}"; askDigest = false
            } finally { askRunning = false }
        }
    }

    /** Menyimpan jawaban ringkasan sebagai catatan baru beserta tautan ke sumbernya. */
    fun saveAnswerAsNote() {
        if (askRunning || askAnswer.isBlank()) return
        val sources = askSources.mapIndexed { i, n -> "[${i + 1}] [[${n.title.ifBlank { n.heading }.take(100)}]]" }.joinToString("\n")
        val body = askAnswer.trim() + if (sources.isNotEmpty()) "\n\n## Sumber\n$sources" else ""
        val title = askQuery.take(100)
        closeAsk()
        val now = System.currentTimeMillis()
        val n = Note(title = title, body = body, created = now, updated = now)
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { persist(n) }
            putLocal(n.copy(id = id))
            say("Ringkasan disimpan sebagai catatan.", "Buka") { stats.byId[id]?.let { open(it) } }
        }
    }

    fun closeAsk() {
        if (askRunning) stopAi()
        askQuery = ""; askAnswer = ""; askSources = emptyList(); openTasksList = emptyList(); askRunning = false; askDigest = false
    }

    // ============================== Asisten perintah ==============================

    fun commandSteps(q: String): List<AgentStep>? = if (q.isBlank()) null else Agent.parse(q)

    fun startAgent(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || agent?.planning == true) return
        agentUndo = null
        Agent.parse(cmd)?.let { agent = AgentRun(cmd, resolve(it)); return }
        if (!ai.hasModel) { agent = AgentRun(cmd, error = "Perintah itu belum dikenali."); return }
        agent = AgentRun(cmd, planning = true)
        running = viewModelScope.launch {
            try {
                val steps = Agent.parseModel(ai.generate(Agent.plannerPrompt(cmd), AiTaskBudget.PLANNER), cmd)
                agent = if (steps.isEmpty()) AgentRun(cmd, error = "Perintah itu belum dikenali.") else AgentRun(cmd, resolve(steps))
            } catch (e: CancellationException) { agent = null
            } catch (e: Throwable) { agent = AgentRun(cmd, error = "AI gagal: ${e.message}") }
        }
    }

    private fun resolve(steps: List<AgentStep>): List<AgentItem> {
        val byId = stats.byId
        var hasPrev = false
        return steps.map { s ->
            val t = s.target
            val item = when {
                t == null -> AgentItem(s, problem = problemFor(s, null))
                t == Agent.PREV -> AgentItem(s, problem = if (hasPrev) problemFor(s, null) else "Catatan mana yang dimaksud?")
                else -> {
                    val opts = index.search(t, limit = 3).mapNotNull { byId[it.id] }
                    AgentItem(s, opts.firstOrNull(), opts, if (opts.isEmpty()) "Tidak ada catatan yang cocok dengan “$t”." else problemFor(s, opts.first()))
                }
            }
            if (item.problem == null) hasPrev = true
            item
        }
    }

    private fun problemFor(s: AgentStep, n: Note?): String? = when (s) {
        is AgentStep.Create -> when {
            s.wantsReminder && s.at == null -> "Waktunya belum jelas. Sebutkan, mis. “besok jam 7 malam”."
            s.title.isBlank() && s.body.isBlank() -> "Isi catatannya kosong."
            else -> null
        }
        is AgentStep.Remind -> if (s.at == null) "Waktunya belum jelas. Sebutkan, mis. “besok jam 7 malam”." else null
        is AgentStep.Unremind -> if (n != null && !n.hasReminder) "Catatan itu tidak punya pengingat." else null
        is AgentStep.Check -> if (n != null && Agent.findTask(n.body, s.text) == null) "Tidak ada butir “${s.text}” yang belum dicentang di catatan itu." else null
        is AgentStep.Pin -> if (n != null && n.pinned == s.on) (if (s.on) "Catatan itu sudah disematkan." else "Catatan itu tidak disematkan.") else null
        is AgentStep.Archive -> if (n?.archived == true) "Catatan itu sudah diarsipkan." else null
        else -> null
    }

    fun agentPick(i: Int, note: Note) {
        val run = agent ?: return
        if (run.results != null) return
        agent = run.copy(items = run.items.mapIndexed { j, it -> if (j == i) it.copy(target = note, problem = problemFor(it.step, note)) else it })
    }

    val agentReady get() = agent?.let { !it.planning && it.results == null && it.error == null && it.items.isNotEmpty() && it.items.none { i -> i.problem != null } } == true
    val agentSetsReminder get() = agent?.items?.any { (it.step as? AgentStep.Create)?.at != null || it.step is AgentStep.Remind } == true

    fun runAgent() {
        val run = agent ?: return
        if (!agentReady) return
        val known = folders
        fun folderName(f: String) = known.firstOrNull { it.equals(f.trim(), ignoreCase = true) } ?: f.trim().replaceFirstChar { it.uppercase() }
        viewModelScope.launch {
            val before = ArrayList<Note>(); val created = ArrayList<Long>(); val results = ArrayList<String>()
            var prev: Note? = null
            try {
                withContext(Dispatchers.IO) {
                    for (item in run.items) {
                        val s = item.step
                        val now = System.currentTimeMillis()
                        if (s is AgentStep.Create) {
                            val n = Note(
                                title = s.title, body = s.body, tags = s.tags, folder = if (s.folder.isBlank()) "" else folderName(s.folder),
                                remindAt = s.at?.toMillis() ?: 0, remindRepeat = if (s.at != null) s.repeat else Repeat.None, remindAlarm = s.at != null && s.alarm,
                            )
                            val saved = n.copy(id = persist(n))
                            created += saved.id; prev = saved
                            results += "Catatan “${saved.heading.take(40)}” dibuat" + (s.at?.let { ", pengingat ${whenLabel(it.toMillis())}" } ?: "")
                            continue
                        }
                        val base = (if (s.target == Agent.PREV) prev else item.target)?.let { repo.getNote(it.id) }
                        if (base == null) { results += "Dilewati: catatannya tidak ditemukan"; continue }
                        val name = "“${base.heading.take(40)}”"
                        val (changed, text) = when (s) {
                            is AgentStep.Remind -> base.copy(remindAt = s.at!!.toMillis(), remindRepeat = s.repeat, remindAlarm = s.alarm, remindFired = false, archived = false) to
                                "Pengingat $name: ${whenLabel(s.at.toMillis())}"
                            is AgentStep.Unremind -> base.copy(remindAt = 0, remindRepeat = Repeat.None, remindAlarm = false, remindFired = false) to "Pengingat $name dihapus"
                            is AgentStep.Append -> base.copy(body = Agent.append(base.body, s.text, s.task), updated = now) to
                                (if (s.task) "Tugas" else "Teks") + " ditambahkan ke $name"
                            is AgentStep.Check -> base.copy(body = Agent.check(base.body, s.text) ?: base.body, updated = now) to "Butir dicentang di $name"
                            is AgentStep.Tag -> base.copy(tags = (base.tags + s.tags).distinct()) to "Tag ${s.tags.joinToString(" ") { "#$it" }} ke $name"
                            is AgentStep.Move -> base.copy(folder = folderName(s.folder)) to "$name dipindah ke folder ${folderName(s.folder)}"
                            is AgentStep.Pin -> base.copy(pinned = s.on) to (if (s.on) "$name disematkan" else "Sematan $name dilepas")
                            is AgentStep.Archive -> base.copy(archived = true, pinned = false) to "$name diarsipkan"
                            is AgentStep.Trash -> base.copy(deletedAt = now, pinned = false) to "$name dipindah ke Sampah"
                            is AgentStep.Create -> base to ""
                        }
                        if (changed == base) { results += "Tidak ada yang berubah di $name"; prev = base; continue }
                        if (before.none { it.id == base.id } && base.id !in created) before += base
                        if (changed.body != base.body && base.body.isNotBlank()) repo.addVersion(base.id, base.title, base.body, base.updated)
                        persist(changed); prev = changed
                        results += text
                    }
                }
                if (agentSetsReminder && !Reminders.canNotify(ctx)) results += "Notifikasi Catatanku dimatikan; aktifkan di pengaturan HP agar pengingat muncul."
            } catch (e: Throwable) { results += "Terhenti: ${e.message}" }
            reload()
            agentUndo = (before to created).takeIf { before.isNotEmpty() || created.isNotEmpty() }
            agent = run.copy(results = results, touched = prev?.id ?: 0)
            updateQuery("")
        }
    }

    fun undoAgent() {
        val (before, created) = agentUndo ?: return
        agentUndo = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                created.forEach { repo.deleteForever(it) }
                before.forEach { persist(it) }
            }
            markDirty()
            reload()
            agent = null
            say("Perintah diurungkan.")
        }
    }

    fun closeAgent() { if (agent?.planning == true) stopAi(); agent = null }

    fun openAgentNote() {
        val id = agent?.touched ?: 0
        agent = null
        stats.byId[id]?.let { open(it) }
    }

    internal fun debugAgent(r: AgentRun) { agent = r }

    // ============================== Aksi cepat dari daftar ==============================

    private fun update(n: Note) { persist(n); putLocal(n) }

    fun togglePin(n: Note) = update(n.copy(pinned = !n.pinned))

    fun toggleArchive(n: Note) {
        update(n.copy(archived = !n.archived, pinned = false))
        say(if (n.archived) "Dikeluarkan dari arsip." else "Diarsipkan.", "Urungkan") {
            notesById[n.id]?.let { update(it.copy(archived = n.archived, pinned = n.pinned)) }
        }
    }

    fun trash(n: Note) {
        update(n.copy(deletedAt = System.currentTimeMillis(), pinned = false))
        say("Dipindah ke Sampah.", "Urungkan") { notesById[n.id]?.let { update(it.copy(deletedAt = 0, pinned = n.pinned)) } }
    }

    fun restore(n: Note) { update(n.copy(deletedAt = 0)); say("Catatan dipulihkan.") }

    fun moveNoteToFolder(n: Note, folder: String) {
        val old = n.folder
        val target = folder.trim()
        if (old == target) return
        update(n.copy(folder = target))
        say(if (target.isEmpty()) "Dikeluarkan dari folder." else "Dipindah ke folder $target.", "Urungkan") {
            stats.byId[n.id]?.let { update(it.copy(folder = old)) }
        }
    }

    private fun moveFolderNotes(from: String, to: String, nextView: NoteView, undoView: NoteView, message: String) {
        val affectedIds = notes.filter { !it.inTrash && it.folder == from }.map { it.id }.toSet()
        if (affectedIds.isEmpty()) { view = nextView; return }
        writeGen++
        applyNotes(notes.map { if (it.id in affectedIds) it.copy(folder = to) else it })
        view = nextView
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.moveFolder(from, to) }
            markDirty()
        }
        say(message, "Urungkan") {
            val restore = notes.filter { it.id in affectedIds }
            if (restore.isNotEmpty()) {
                writeGen++
                applyNotes(notes.map { if (it.id in affectedIds) it.copy(folder = from) else it })
                view = undoView
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { restore.forEach { repo.db.upsert(it.copy(folder = from)) } }
                    markDirty()
                }
            }
        }
    }

    fun renameFolder(oldName: String, newName: String) {
        val target = newName.trim().take(30)
        if (target.isEmpty() || target == oldName) return
        if (notes.none { !it.inTrash && it.folder == oldName }) return
        moveFolderNotes(oldName, target, NoteView.Folder(target), NoteView.Folder(oldName), "Folder diubah menjadi $target.")
    }

    fun dissolveFolder(name: String) =
        moveFolderNotes(name, "", NoteView.All, NoteView.Folder(name), "Folder $name dibubarkan (catatan tetap ada).")

    // ============================== Pengaturan & AI ==============================

    fun importModel(uri: Uri) = viewModelScope.launch {
        try { ai.importModel(uri); say("Model terpasang (${ai.modelSizeMb} MB).") }
        catch (e: Throwable) { say("Impor gagal: ${e.message}") }
    }

    fun loadModel() = viewModelScope.launch {
        try { ai.load(force = true); say("Model siap.") } catch (_: Throwable) {}
    }

    fun unloadModel() = viewModelScope.launch { ai.unload() }
    fun deleteModel(id: String) = viewModelScope.launch { ai.delete(id) }
    fun activateModel(id: String) = viewModelScope.launch { ai.activate(id) }
    fun setGpu(on: Boolean) = viewModelScope.launch { ai.setBackend(if (on) AiBackendKind.GPU else AiBackendKind.CPU) }
    fun setBackendAuto() = viewModelScope.launch { ai.setBackend(null) }
    fun setContextProfile(profile: ContextProfile?) = viewModelScope.launch { ai.setContextProfile(profile) }
    fun runAiBenchmark() = viewModelScope.launch {
        try { say(ai.runDeviceBenchmark()) } catch (e: Throwable) { say("Benchmark gagal: ${e.message}") }
    }

    var lightAi by mutableStateOf(ai.lightMode); private set
    fun updateLightAi(on: Boolean) { lightAi = on; viewModelScope.launch { ai.setLight(on) } }

    // ============================== Cadangan ZIP & terenkripsi ==============================

    fun exportTo(uri: Uri, passphrase: CharArray? = null) {
        if (backupJob?.isActive == true) return
        val encrypted = passphrase != null && passphrase.isNotEmpty()
        backupJob = viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val activeCtx = coroutineContext
                    repo.exportZipTo(
                        uri = uri,
                        appVersion = appVersion,
                        passphrase = passphrase,
                        onCheckCancelled = { activeCtx.ensureActive() },
                        onProgress = { p -> backupProgress = p },
                    )
                }
                val suffix = if (!encrypted && vaultCount > 0) " (catatan Brankas dilewati karena tanpa sandi)" else ""
                say("$count catatan (beserta lampiran) diekspor$suffix.")
            } catch (_: CancellationException) {
                say("Ekspor cadangan dibatalkan.")
            } catch (e: Throwable) {
                say("Ekspor gagal: ${e.message}")
            } finally {
                passphrase?.fill('\u0000')
                backupProgress = null
            }
        }
    }

    fun importFrom(uri: Uri, passphrase: CharArray? = null) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val activeCtx = coroutineContext
                    repo.restoreFromUri(
                        uri = uri,
                        passphraseProvider = { passphrase },
                        onCheckCancelled = { activeCtx.ensureActive() },
                        onProgress = { p -> backupProgress = p },
                    )
                }
                pendingEncryptedRestoreUri = null
                reload()
                refreshAttachmentNames()
                val msg = buildString {
                    append("${report.importedNotes} catatan dipulihkan")
                    if (report.restoredAttachments > 0) append(", ${report.restoredAttachments} lampiran")
                    if (report.skippedDuplicates > 0) append(" (${report.skippedDuplicates} duplikat dilewati)")
                    if (report.missingAttachments.isNotEmpty()) append(". Peringatan: ${report.missingAttachments.size} lampiran tidak ada di arsip.")
                }
                say(msg)
            } catch (_: CancellationException) {
                say("Pemulihan cadangan dibatalkan; data lama tetap aman.")
            } catch (e: IllegalStateException) {
                if (e.message?.contains("terenkripsi", ignoreCase = true) == true && passphrase == null) {
                    pendingEncryptedRestoreUri = uri
                } else {
                    say("Impor gagal: ${e.message ?: "berkas tidak valid."}")
                }
            } catch (e: Throwable) {
                say("Impor gagal: ${e.message ?: "berkas tidak valid."}")
            } finally {
                passphrase?.fill('\u0000')
                backupProgress = null
            }
        }
    }

    fun cancelBackupJob() { backupJob?.cancel() }
    fun dismissEncryptedRestorePrompt() { pendingEncryptedRestoreUri = null }

    val backupLabel: String? get() = backupTree?.let { AutoBackup.folderLabel(Uri.parse(it)) }

    fun setBackupFolder(uri: Uri?) {
        val cr = ctx.contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        backupTree?.let { old -> runCatching { cr.releasePersistableUriPermission(Uri.parse(old), flags) } }
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, flags) }
        backupTree = uri?.toString(); backupError = null
        dirty = true
        prefs.edit().putString("backup_tree", backupTree).remove("backup_error").putBoolean("dirty", true).apply()
        if (uri != null) backupNow(announce = true)
    }

    fun backupNow(announce: Boolean = false) {
        val tree = backupTree ?: return
        if (!announce && !dirty) return
        appScope.launch {
            try {
                dirty = false
                AutoBackup.write(ctx, repo, Uri.parse(tree), appVersion)
                val now = System.currentTimeMillis()
                prefs.edit().putBoolean("dirty", dirty).putLong("backup_at", now).remove("backup_error").apply()
                backupAt = now; backupError = null
                if (announce) say("Cadangan tersimpan di ${backupLabel}.")
            } catch (e: Throwable) {
                dirty = true
                val msg = e.message ?: "folder tidak bisa ditulisi"
                prefs.edit().putString("backup_error", msg).apply()
                backupError = msg
                if (announce) say("Cadangan gagal: $msg")
            }
        }
    }

    // ---------- Keamanan, Kunci aplikasi & Privasi ketat ----------

    enum class PinResult { SUCCESS, LOCKED_OUT, WRONG, WIPED }

    fun suppressNextLeaveLock() {
        pickerExemptUntil = SystemClock.elapsedRealtime() + 180_000L
    }

    private fun isPickerExempt(): Boolean =
        pickerExemptUntil != 0L && SystemClock.elapsedRealtime() <= pickerExemptUntil

    fun updateLock(on: Boolean): Boolean {
        if (on && !hasAppPin && ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            say("Setel kunci layar HP atau buat PIN Catatanku lebih dulu.")
            return false
        }
        if (!on && hasAppPin) {
            SecurityPrefs.clearAppPin(ctx)
            hasAppPin = false
        }
        lockEnabled = on; locked = false; leftAt = 0L
        SecurityPrefs.setLockEnabled(ctx, on)
        return true
    }

    fun updateLockTimeout(t: LockTimeout) {
        lockTimeout = t
        SecurityPrefs.setLockTimeout(ctx, t)
    }

    fun updateScreenShield(on: Boolean) {
        screenShield = on
        SecurityPrefs.setScreenShieldEnabled(ctx, on)
    }

    fun updateIncognitoKeyboard(on: Boolean) {
        incognitoKeyboard = on
        SecurityPrefs.setIncognitoKeyboardEnabled(ctx, on)
    }

    fun updateClipboardAutoClear(on: Boolean) {
        clipboardAutoClear = on
        SecurityPrefs.setClipboardAutoClearEnabled(ctx, on)
    }

    fun updateStrictOffline(on: Boolean) {
        strictOffline = on
        SecurityPrefs.setStrictOffline(ctx, on)
        if (on) ai.cancelDownload()
    }

    fun updateWipeAfterFailures(on: Boolean) {
        wipeAfterFailures = on
        SecurityPrefs.setWipeAfterFailuresEnabled(ctx, on)
    }

    fun setAppPin(pin: CharArray) {
        try {
            SecurityPrefs.setAppPin(ctx, pin)
            hasAppPin = true
            lockEnabled = true
            locked = false
            failedUnlocks = 0
            lockoutRemainingMs = 0L
            say("PIN Catatanku disimpan dan Kunci Aplikasi diaktifkan.")
        } finally {
            pin.fill('\u0000')
        }
    }

    fun removeAppPin() {
        SecurityPrefs.clearAppPin(ctx)
        hasAppPin = false
        failedUnlocks = 0
        lockoutRemainingMs = 0L
        if (ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            lockEnabled = false
            SecurityPrefs.setLockEnabled(ctx, false)
        }
        say("PIN Catatanku dihapus.")
    }

    fun setAutoBackupPassphrase(passphrase: CharArray) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { SecurityPrefs.setAutoBackupPassphrase(ctx, passphrase) }
                autoBackupEncrypted = true
                dirty = true
                say("Enkripsi Cadangan Otomatis diaktifkan.")
                backupNow(announce = backupTree != null)
            } catch (e: Throwable) {
                say("Gagal menyetel sandi cadangan: ${e.message}")
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun clearAutoBackupEncryption() {
        SecurityPrefs.clearAutoBackupEncryption(ctx)
        autoBackupEncrypted = false
        say("Enkripsi Cadangan Otomatis dimatikan.")
    }

    fun verifyPinAndUnlock(pin: CharArray, forVaultOnly: Boolean = false): PinResult {
        return try {
            val rem = SecurityPrefs.remainingLockoutMs(ctx)
            if (rem > 0L) {
                lockoutRemainingMs = rem
                return PinResult.LOCKED_OUT
            }
            if (SecurityPrefs.verifyAppPin(ctx, pin)) {
                SecurityPrefs.resetFailures(ctx)
                failedUnlocks = 0
                lockoutRemainingMs = 0L
                if (forVaultOnly) onVaultAuthenticated() else unlocked()
                PinResult.SUCCESS
            } else {
                val fails = SecurityPrefs.recordUnlockFailure(ctx)
                failedUnlocks = fails
                lockoutRemainingMs = SecurityPrefs.remainingLockoutMs(ctx)
                if (AppPinCrypto.shouldTriggerWipe(fails, wipeAfterFailures)) {
                    panicWipe()
                    PinResult.WIPED
                } else {
                    PinResult.WRONG
                }
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    fun refreshLockoutState() {
        failedUnlocks = SecurityPrefs.failedAttempts(ctx)
        lockoutRemainingMs = SecurityPrefs.remainingLockoutMs(ctx)
    }

    fun requestOpenVault() {
        if (vaultUnlocked) {
            view = NoteView.Vault
            tagFilter = null
        } else {
            vaultUnlockRequested = true
        }
    }

    fun cancelVaultUnlockRequest() {
        vaultUnlockRequested = false
        pendingVaultNoteId = 0L
    }

    fun onVaultAuthenticated() {
        vaultUnlockRequested = false
        vaultUnlocked = true
        view = NoteView.Vault
        tagFilter = null
        val targetId = pendingVaultNoteId
        pendingVaultNoteId = 0L
        viewModelScope.launch {
            reload()
            if (targetId != 0L) {
                val note = withContext(Dispatchers.IO) { repo.getNote(targetId) }
                if (note != null) {
                    Reminders.done(ctx, targetId)
                    open(note)
                }
            }
        }
    }

    fun lockVault() {
        vaultUnlockRequested = false
        pendingVaultNoteId = 0L
        if (!vaultUnlocked) return
        if (draft.vault) {
            saveDraft()
            draft = Note(); clean = null; opened = null; undoAi = null; aiJob = null; aiSheet = false; versions = null; reminderFor = null
            if (screen == Screen.Editor) screen = Screen.List
        }
        vaultUnlocked = false
        if (view == NoteView.Vault) view = NoteView.All
        if (askActive) closeAsk()
        agent = null; agentUndo = null
        applyNotes(notes.filter { !it.vault })
    }

    private fun scrubSensitiveMemory() {
        userTemplates = emptyList(); selectedIds = emptySet(); templateSheet = false; aiSelection = null
        indexJob?.cancel(); indexJob = null
        searchJob?.cancel(); searchJob = null
        running?.cancel(); running = null
        batchJob?.cancel(); batchJob = null

        vaultUnlocked = false
        vaultUnlockRequested = false
        pendingVaultNoteId = 0L
        if (view == NoteView.Vault) view = NoteView.All
        screen = Screen.List
        notes = emptyList()
        shown = emptyList()
        today = emptyList()
        stats = Stats(emptyList())
        notesById = emptyMap()
        openTaskCount = 0
        index = SearchIndex(emptyList())
        draft = Note(); clean = null; opened = null; undoAi = null; aiJob = null; aiSheet = false; versions = null
        reminderFor = null; reminderSeed = null
        askQuery = ""; askAnswer = ""; askSources = emptyList(); openTasksList = emptyList(); askRunning = false
        agent = null; agentUndo = null; pendingEncryptedRestoreUri = null
        loaded = false
        DeviceVaultKey.clearMemoryCache()
        ai.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    fun lockNow() {
        if (!locked && screen == Screen.Editor) saveDraft()
        locked = true
        leftAt = 0L
        pickerExemptUntil = 0L
        scrubSensitiveMemory()
        appScope.launch {
            Attachments.sweepStaleTempFiles(ctx)
            repo.flush()
        }
    }

    fun unlocked() {
        locked = false
        leftAt = 0L
        pickerExemptUntil = 0L
        SecurityPrefs.resetFailures(ctx)
        failedUnlocks = 0
        lockoutRemainingMs = 0L
        viewModelScope.launch {
            reload()
            withContext(Dispatchers.IO) { Attachments.migrateLegacyAttachments(ctx) }
            refreshAttachmentNames()
        }
    }

    fun shouldDeferForLock(): Boolean { enforceLockIfExpired(); return locked }

    private fun enforceLockIfExpired() {
        if (!lockEnabled || locked) return
        if (leftAt != 0L && System.currentTimeMillis() - leftAt >= lockTimeout.ms) {
            locked = true
            scrubSensitiveMemory()
        }
    }

    fun showReminders() { if (screen == Screen.Editor) closeEditor() else flushListIfStale(); screen = Screen.List; switchView(NoteView.Reminders) }
    fun showTasks() { if (screen == Screen.Editor) closeEditor() else flushListIfStale(); screen = Screen.List; switchView(NoteView.All); askOpenTasks() }
    fun openAssist() { if (screen == Screen.Editor) closeEditor() else flushListIfStale(); screen = Screen.List; switchView(NoteView.All); searchFocusTick++ }

    internal fun debugLock() { locked = true }

    fun onLeave() {
        backupNow()
        if (isPickerExempt()) {
            leftAt = System.currentTimeMillis()
            appScope.launch { repo.flush() }
            return
        }
        if (vaultUnlocked) lockVault()
        if (lockEnabled) {
            if (lockTimeout == LockTimeout.IMMEDIATE) {
                lockNow()
            } else {
                leftAt = System.currentTimeMillis()
                appScope.launch { repo.flush() }
            }
        } else {
            appScope.launch { repo.flush() }
        }
    }

    fun onScreenOff() {
        pickerExemptUntil = 0L
        if (vaultUnlocked) lockVault()
        if (lockEnabled && !locked) lockNow()
    }

    fun onReturn() {
        val wasExempt = isPickerExempt()
        pickerExemptUntil = 0L
        if (!wasExempt) enforceLockIfExpired()
        leftAt = 0L
        if (locked) {
            refreshLockoutState()
            return
        }
        if (!loaded) {
            viewModelScope.launch { reload(); refreshAttachmentNames() }
        } else {
            flushListIfStale()
        }
        if (prefs.getBoolean("dirty", false)) dirty = true
        val (late, at) = Reminders.lastLate(ctx)
        if (late > 0 && at != prefs.getLong("late_seen", 0)) {
            prefs.edit().putLong("late_seen", at).apply()
            say("Pengingat terakhir terlambat ${late / 60_000} menit: HP ini menahan Catatanku.", "Perbaiki") { screen = Screen.Settings }
        }
        ai.onForeground()
        if (loaded) today = dueToday(notes)
    }

    /** Hapus darurat seluruh data catatan, lampiran, basis data, dan kunci perangkat keras secara permanen. */
    fun panicWipe() {
        scrubSensitiveMemory()
        backupJob?.cancel(); backupJob = null
        ai.cancelDownload()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ai.unload() }
                runCatching {
                    repo.allNotes(includeVault = true).forEach { Reminders.clear(ctx, it.id) }
                }
                SecurityPrefs.wipeStorageNow(ctx)
            }
            lockEnabled = false
            locked = false
            hasAppPin = false
            wipeAfterFailures = false
            failedUnlocks = 0
            lockoutRemainingMs = 0L
            autoBackupEncrypted = false
            lockTimeout = SecurityPrefs.lockTimeout(ctx)
            screenShield = SecurityPrefs.isScreenShieldEnabled(ctx)
            incognitoKeyboard = SecurityPrefs.isIncognitoKeyboardEnabled(ctx)
            clipboardAutoClear = SecurityPrefs.isClipboardAutoClearEnabled(ctx)
            strictOffline = SecurityPrefs.isStrictOffline(ctx)
            backupTree = null
            backupAt = 0L
            backupError = null
            vaultCount = 0
            existingAttachments = emptySet()
            loaded = true
            say("Seluruh data catatan dan kunci telah dihapus permanen.")
        }
    }

    fun onTrimMemory(level: Int) {
        ai.onTrimMemory(level)
        if (level >= com.catatanku.app.ai.performance.MemoryPolicy.TRIM_RUNNING_LOW) {
            }
    }
}
