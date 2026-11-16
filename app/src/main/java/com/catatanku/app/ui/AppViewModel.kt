package com.catatanku.app.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.catatanku.app.ai.engine.AiEvent
import com.catatanku.app.ai.engine.LocalAi
import com.catatanku.app.ai.engine.LocalEmbeddingProvider
import com.catatanku.app.ai.inference.Agent
import com.catatanku.app.ai.inference.AiText
import com.catatanku.app.ai.performance.AiBackendKind
import com.catatanku.app.ai.performance.AiTaskBudget
import com.catatanku.app.ai.performance.ContextProfile
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
import com.catatanku.app.domain.usecase.EmbeddingCodec
import com.catatanku.app.domain.usecase.HybridSearch
import com.catatanku.app.domain.usecase.SearchIndex
import com.catatanku.app.domain.usecase.SemanticIndex
import com.catatanku.app.domain.usecase.StoredEmbedding
import com.catatanku.app.domain.usecase.TimeParse
import com.catatanku.app.media.Attachments
import com.catatanku.app.publishShortcuts
import com.catatanku.app.reminders.Reminders
import com.catatanku.app.reminders.toLocal
import com.catatanku.app.reminders.toMillis
import com.catatanku.app.reminders.whenLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = NoteRepository(app)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ai = LocalAi.get(app)
    val embeddings = LocalEmbeddingProvider.get(app)
    val appVersion: String by lazy {
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0)?.versionName.orEmpty() }
            .getOrDefault("").ifBlank { "?" }
    }

    var screen by mutableStateOf(Screen.List); private set
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var stats by mutableStateOf(Stats(emptyList())); private set
    var loaded by mutableStateOf(false); private set
    var index by mutableStateOf(SearchIndex(emptyList())); private set
    var semanticIndex by mutableStateOf(SemanticIndex()); private set
    var existingAttachments by mutableStateOf<Set<String>>(emptySet()); private set
    private var notesById: Map<Long, Note> = emptyMap()
    private var listStale = false
    private var writeGen = 0
    private var indexJob: Job? = null
    private var embeddingJob: Job? = null
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
    var versions by mutableStateOf<List<Version>?>(null); private set
    private var opened: Note? = null
    private var snapshotDone = false
    private var fresh = false
    private var clean by mutableStateOf<Note?>(null)
    val draftSaved get() = draft.id != 0L && draft === clean

    // Pengingat
    var reminderFor by mutableStateOf<Note?>(null); private set
    var reminderSeed by mutableStateOf<Long?>(null); private set

    // Pengaturan
    var lockEnabled by mutableStateOf(prefs.getBoolean("lock", false)); private set
    var locked by mutableStateOf(prefs.getBoolean("lock", false)); private set
    private var leftAt = 0L
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

    @Volatile private var dirty = prefs.getBoolean("dirty", false)
    private fun markDirty() { if (!dirty) { dirty = true; prefs.edit().putBoolean("dirty", true).apply() } }

    init {
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
            reload()
            val purged = withContext(Dispatchers.IO) {
                repo.purgeOldTrash().also {
                    Reminders.rescheduleAll(app)
                    publishShortcuts(app)
                    Attachments.cleanup(app, repo.allNotes())
                }
            }
            refreshAttachmentNames()
            if (purged) reload()
        }
        viewModelScope.launch {
            Reminders.changed.collect { id ->
                if (id == Reminders.TEST_ID) return@collect
                if (screen == Screen.Editor && draft.id == id) withContext(Dispatchers.IO) { repo.getNote(id) }?.let { n ->
                    draft = draft.copy(remindAt = n.remindAt, remindRepeat = n.remindRepeat, remindAlarm = n.remindAlarm, remindFired = n.remindFired)
                    opened = opened?.copy(remindAt = n.remindAt, remindFired = n.remindFired)
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
        notes = list
        stats = Stats(list, AiText::openTaskCount)
        notesById = stats.byId
        today = dueToday(list)
        openTaskCount = stats.openTaskCount
        refreshShown()
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            val prev = index
            val idx = withContext(Dispatchers.Default) {
                val live = list.filter { !it.inTrash }
                prev.update(live.map { SearchIndex.Doc(it.id, it.title, it.body, it.tags) })
            }
            index = idx
            if (query.isNotBlank()) refreshShown()
            scheduleIncrementalEmbeddings(list)
        }
    }

    /** Memperbarui vektor semantik secara inkremental di latar tanpa pernah mengganggu jalur mengetik. */
    private fun scheduleIncrementalEmbeddings(list: List<Note>) {
        if (!embeddings.isAvailable) {
            if (!semanticIndex.isEmpty) semanticIndex = SemanticIndex()
            return
        }
        embeddingJob?.cancel()
        embeddingJob = viewModelScope.launch(Dispatchers.Default) {
            val modelId = embeddings.modelId
            if (modelId.isBlank()) return@launch
            val live = list.filter { !it.inTrash }
            val cached = withContext(Dispatchers.IO) {
                repo.pruneEmbeddings(live.map { it.id }.toSet())
                repo.embeddings(modelId)
            }
            val map = HashMap<Long, FloatArray>(live.size)
            for (n in live) {
                ensureActive()
                val hash = EmbeddingCodec.contentHash(n.title, n.body, n.tags)
                val hit = cached[n.id]?.takeIf { it.contentHash == hash && it.modelId == modelId }
                if (hit != null) {
                    map[n.id] = hit.vector
                } else {
                    val vec = embeddings.embedDocument(n.title, n.body, n.tags)
                    if (vec != null) {
                        map[n.id] = vec
                        withContext(Dispatchers.IO) { repo.upsertEmbedding(StoredEmbedding(n.id, hash, modelId, vec)) }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                semanticIndex = SemanticIndex(map, modelId)
                if (query.isNotBlank()) refreshShown()
            }
        }
    }

    private suspend fun reload() {
        while (true) {
            val gen = writeGen
            val read = withContext(Dispatchers.IO) { repo.allNotes() }
            if (gen != writeGen) continue
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
        return list.filter { !it.inTrash && it.hasReminder && (it.remindFired || it.remindAt < end) }.sortedBy { it.remindAt }
    }

    val allTags: List<String> get() = stats.tags
    val folders: List<String> get() = stats.folders
    val trashCount get() = stats.trashCount
    val reminderCount get() = stats.reminderCount

    private fun refreshShown() {
        val v = view; val q = query; val tag = tagFilter; val list = notes
        val inView: (Note) -> Boolean = when (v) {
            NoteView.All -> { n -> !n.inTrash && !n.archived }
            is NoteView.Folder -> { n -> !n.inTrash && !n.archived && n.folder == v.name }
            NoteView.Reminders -> { n -> !n.inTrash && n.hasReminder }
            NoteView.Archive -> { n -> !n.inTrash && n.archived }
            NoteView.Trash -> { n -> n.inTrash }
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
            v == NoteView.Trash -> show(list.filter { it.inTrash && (it.title + " " + it.body).contains(q.trim(), ignoreCase = true) })
            else -> {
                val idx = index; val sem = semanticIndex; val terms = aiTerms; val byId = stats.byId
                searchJob = viewModelScope.launch {
                    val hits = withContext(Dispatchers.Default) {
                        val qVec = if (embeddings.isAvailable && !sem.isEmpty) embeddings.embedQuery(q) else null
                        HybridSearch.search(idx, sem, q, qVec, terms)
                    }
                    show(hits.mapNotNull { byId[it.id] }.filter { if (v == NoteView.All) !it.inTrash else inView(it) })
                }
            }
        }
    }

    fun updateSort(s: Sort) { sort = s; prefs.edit().putString("sort", s.name).apply(); refreshShown() }
    fun updateQuery(q: String) {
        if (q == query) return
        if (askActive) closeAsk()
        query = q; aiTerms = emptyList()
        refreshShown()
    }
    fun go(s: Screen) { if (s != Screen.Editor) flushListIfStale(); screen = s }

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
        stopAi()
        draft = note ?: Note(folder = (view as? NoteView.Folder)?.name ?: "")
        opened = note?.takeIf { it.id != 0L }; snapshotDone = false
        fresh = draft.id == 0L
        clean = draft.takeIf { it.id != 0L }
        suggestedTags = emptyList(); aiJob = null; undoAi = null; aiSheet = false; versions = null; reminderFor = null
        refreshAttachmentNames()
        screen = Screen.Editor
    }

    fun openFromReminder(id: Long) {
        if (screen == Screen.Editor) saveDraft()
        Reminders.done(ctx, id)
        repo.getNote(id)?.let { open(it) }
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
            if (autoOrganize) organizeInBackground(d.id)
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
        say("Dipindah ke Sampah.", "Urungkan") { stats.byId[id]?.let { update(it.copy(deletedAt = 0, pinned = pinned)) } }
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
            isImg && extractTasks -> "Ambil tugas dari gambar"
            isImg -> "Baca gambar"
            extractTasks -> "Ambil tugas dari suara"
            else -> "Transkripsikan suara"
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
        say("Diarsipkan.", "Urungkan") { stats.byId[id]?.let { update(it.copy(archived = false, pinned = pinned)) } }
    }

    fun emptyTrash() = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.emptyTrash() }; reload()
        refreshAttachmentNames()
        markDirty()
        say("Sampah dikosongkan.")
    }

    fun relatedTo(d: Note): List<Note> {
        val byId = notesById
        return index.related(d.title, d.body, d.tags, d.id).mapNotNull { byId[it.id] }
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

    fun runAi(action: AiAction, custom: String = "") {
        val d = draft
        if (d.body.isBlank() || aiJob?.running == true) return
        aiJob = AiJob(action)
        running = viewModelScope.launch {
            try {
                var suggestedTime: Long? = null
                val budget = AiTaskBudget.forAction(action)
                val clean = when {
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
    fun dismissAi() { stopAi(); aiJob = null; aiSheet = false }

    fun applyAi(replace: Boolean, remindAt: Long? = null) {
        val j = aiJob ?: return
        val text = j.text.trim()
        if (text.isEmpty()) return
        undoAi = UndoAiSnap(draft.title, draft.body, draft.remindAt, draft.remindRepeat, draft.remindAlarm, draft.remindFired)
        var next = when {
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
        aiJob = null; aiSheet = false
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
        val targets = notes.filter(::needsOrganizing)
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
        askQuery = "Tugas yang belum selesai"; askByAi = false
        val withTasks = notes.filter { !it.inTrash && !it.archived }.sortedByDescending { it.updated }
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
        if (askRunning) return
        askQuery = "Rekap 7 hari terakhir"; askByAi = false
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        askSources = notes.filter { !it.inTrash && !it.archived && it.updated >= since && it.body.isNotBlank() }
            .sortedByDescending { it.updated }.take(if (AiText.maxInput > 4000) 8 else 5)
        askAnswer = ""
        if (askSources.isEmpty()) { askAnswer = "Tidak ada catatan yang ditulis atau diubah dalam 7 hari terakhir."; return }
        if (!ai.hasModel) { askAnswer = "Model AI belum dipasang. Berikut catatan 7 hari terakhir:"; return }
        askRunning = true; askByAi = true
        running = viewModelScope.launch {
            try {
                val out = ai.generate(AiText.recapPrompt(askSources.map { it.title to it.body }), AiTaskBudget.RECAP) {
                    askAnswer = AiText.cleanBlock(it)
                }
                askAnswer = AiText.cleanBlock(out).ifBlank { "Model tidak menghasilkan rekap." }
            } catch (e: CancellationException) {
            } catch (e: Throwable) { askAnswer = "AI gagal: ${e.message}"
            } finally { askRunning = false }
        }
    }

    fun closeAsk() {
        if (askRunning) stopAi()
        askQuery = ""; askAnswer = ""; askSources = emptyList(); openTasksList = emptyList(); askRunning = false
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
            stats.byId[n.id]?.let { update(it.copy(archived = n.archived, pinned = n.pinned)) }
        }
    }

    fun trash(n: Note) {
        update(n.copy(deletedAt = System.currentTimeMillis(), pinned = false))
        say("Dipindah ke Sampah.", "Urungkan") { stats.byId[n.id]?.let { update(it.copy(deletedAt = 0, pinned = n.pinned)) } }
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

    fun importEmbeddingModel(uri: Uri) = viewModelScope.launch {
        try {
            embeddings.importModel(uri)
            say("Model pencarian semantik terpasang; indeks disiapkan di latar.")
            scheduleIncrementalEmbeddings(notes)
        } catch (e: Throwable) {
            say("Impor model embedding gagal: ${e.message}")
        }
    }

    fun deleteEmbeddingModel() = viewModelScope.launch {
        embeddings.deleteModel()
        semanticIndex = SemanticIndex()
        say("Model pencarian semantik dihapus.")
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
                say("$count catatan (beserta lampiran) diekspor.")
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

    // ---------- Kunci aplikasi ----------

    fun updateLock(on: Boolean): Boolean {
        if (on && ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            say("Setel dulu kunci layar HP (PIN, pola, atau sidik jari).")
            return false
        }
        lockEnabled = on; locked = false; leftAt = 0L
        prefs.edit().putBoolean("lock", on).apply()
        return true
    }

    fun unlocked() { locked = false; leftAt = 0L }
    fun shouldDeferForLock(): Boolean { enforceLockIfExpired(); return locked }
    private fun enforceLockIfExpired() {
        if (lockEnabled && leftAt != 0L && System.currentTimeMillis() - leftAt > LOCK_GRACE_MS) locked = true
    }

    fun showReminders() { if (screen == Screen.Editor) closeEditor() else flushListIfStale(); screen = Screen.List; view = NoteView.Reminders }
    fun showTasks() { if (screen == Screen.Editor) closeEditor() else flushListIfStale(); screen = Screen.List; view = NoteView.All; askOpenTasks() }
    fun openAssist() { if (screen == Screen.Editor) closeEditor() else flushListIfStale(); screen = Screen.List; view = NoteView.All; searchFocusTick++ }

    internal fun debugLock() { locked = true }

    fun onLeave() {
        leftAt = System.currentTimeMillis()
        backupNow()
        appScope.launch { repo.flush() }
    }

    fun onReturn() {
        enforceLockIfExpired()
        leftAt = 0L
        flushListIfStale()
        if (prefs.getBoolean("dirty", false)) dirty = true
        val (late, at) = Reminders.lastLate(ctx)
        if (late > 0 && at != prefs.getLong("late_seen", 0)) {
            prefs.edit().putLong("late_seen", at).apply()
            say("Pengingat terakhir terlambat ${late / 60_000} menit: HP ini menahan Catatanku.", "Perbaiki") { screen = Screen.Settings }
        }
        ai.onForeground()
        if (loaded) today = dueToday(notes)
    }

    fun onTrimMemory(level: Int) {
        ai.onTrimMemory(level)
        if (level >= com.catatanku.app.ai.performance.MemoryPolicy.TRIM_RUNNING_LOW) {
            appScope.launch { embeddings.release() }
        }
    }

    companion object { const val LOCK_GRACE_MS = 60_000L }
}
