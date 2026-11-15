package com.catatanku.app

import android.app.Application
import android.content.Context
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

enum class Screen { List, Editor, Settings, Ask }
enum class Sort(val label: String) { Updated("Terakhir diubah"), Created("Tanggal dibuat"), Title("Judul A–Z") }

/** Tampilan daftar: semua catatan aktif, satu folder, yang berpengingat, arsip, atau sampah. */
sealed interface NoteView {
    data object All : NoteView
    data class Folder(val name: String) : NoteView
    data object Reminders : NoteView
    data object Archive : NoteView
    data object Trash : NoteView
}

/** Hasil bantu-tulis AI yang menunggu keputusan pengguna (tidak pernah diterapkan otomatis). */
data class AiJob(
    val action: AiAction, val text: String = "", val running: Boolean = true, val error: String? = null,
    val progress: String? = null,       // mis. "Bagian 2 dari 4" untuk catatan panjang
)

/** Satu langkah rencana asisten: catatan sasaran yang ditemukan, pilihan lain, dan alasan bila belum bisa dijalankan. */
data class AgentItem(val step: AgentStep, val target: Note? = null, val options: List<Note> = emptyList(), val problem: String? = null)

/** Perintah yang sedang ditangani asisten: menyusun rencana → menunggu persetujuan → hasil. Tak pernah jalan tanpa persetujuan. */
data class AgentRun(
    val command: String, val items: List<AgentItem> = emptyList(), val planning: Boolean = false, val error: String? = null,
    val results: List<String>? = null,      // terisi setelah dijalankan
    val touched: Long = 0,                  // catatan terakhir yang dibuat/diubah, untuk tombol "Buka catatan"
)

/** Cadangan otomatis ke folder pilihan pengguna (lewat Storage Access Framework; tanpa izin penyimpanan). */
object AutoBackup {
    const val KEEP = 7

    /** Menulis cadangan hari ini (menimpa berkas hari yang sama) dan menyisakan [KEEP] berkas terbaru. */
    fun write(ctx: Context, tree: Uri, json: String): String {
        val cr = ctx.contentResolver
        val dirId = DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val existing = cr.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1).orEmpty()) } }.orEmpty()
            .filter { it.second.startsWith("catatanku-") && it.second.endsWith(".json") }
        val name = "catatanku-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
        val target = existing.firstOrNull { it.second == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
            ?: DocumentsContract.createDocument(cr, dir, "application/json", name) ?: error("berkas tidak bisa dibuat")
        cr.openOutputStream(target, "wt")!!.use { it.write(json.toByteArray()) }
        existing.filter { it.second != name }.sortedByDescending { it.second }.drop(KEEP - 1).forEach {
            runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }
        }
        return name
    }

    fun folderLabel(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Penyimpanan" } }.getOrDefault("Folder terpilih")
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NoteDb.get(app)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)      // tetap hidup saat layar ditutup (cadangan)
    val ai = LocalAi(app)

    var screen by mutableStateOf(Screen.List); private set
    var notes by mutableStateOf<List<Note>>(emptyList()); private set     // semua, termasuk sampah
    var index by mutableStateOf(SearchIndex(emptyList())); private set    // tanpa sampah

    // Daftar
    var view by mutableStateOf<NoteView>(NoteView.All)
    var sort by mutableStateOf(runCatching { Sort.valueOf(prefs.getString("sort", "")!!) }.getOrDefault(Sort.Updated)); private set
    var query by mutableStateOf("")
    var tagFilter by mutableStateOf<String?>(null)
    var aiTerms by mutableStateOf<List<String>>(emptyList()); private set
    var aiBusy by mutableStateOf(false); private set
    var batch by mutableStateOf<Pair<Int, Int>?>(null); private set       // (selesai, total) rapikan massal

    // Editor
    var draft by mutableStateOf(Note()); private set
    var suggestedTags by mutableStateOf<List<String>>(emptyList()); private set
    var suggestedByAi by mutableStateOf(false); private set
    var aiJob by mutableStateOf<AiJob?>(null); private set
    var undoBody by mutableStateOf<String?>(null); private set
    var aiSheet by mutableStateOf(false)                                  // lembar asisten AI di editor
    var versions by mutableStateOf<List<Version>?>(null); private set     // null = lembar riwayat tertutup
    private var opened: Note? = null                                      // keadaan catatan saat editor dibuka
    private var snapshotDone = false

    // Pengingat
    var reminderFor by mutableStateOf<Note?>(null); private set           // catatan yang lembar pengingatnya terbuka
    var reminderSeed by mutableStateOf<Long?>(null); private set          // waktu saran untuk lembar itu

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
    var askQuery by mutableStateOf("")

    // Asisten perintah
    var agent by mutableStateOf<AgentRun?>(null); private set
    private var agentUndo: Pair<List<Note>, List<Long>>? = null           // (keadaan sebelum diubah, id catatan yang dibuat)
    val agentCanUndo get() = agentUndo != null

    private var running: Job? = null
    private var batchJob: Job? = null
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    var hideAiBanner by mutableStateOf(prefs.getBoolean("hide_ai_banner", false)); private set
    fun dismissAiBanner() { hideAiBanner = true; prefs.edit().putBoolean("hide_ai_banner", true).apply() }

    init {
        viewModelScope.launch { ai.events.collect { messages.tryEmit(it) } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.purgeOldTrash(); Reminders.rescheduleAll(app) }
            reload()
        }
        // Alarm berbunyi atau tombol notifikasi ditekan: samakan layar dengan basis data.
        viewModelScope.launch {
            Reminders.changed.collect { id ->
                if (screen == Screen.Editor && draft.id == id) withContext(Dispatchers.IO) { db.get(id) }?.let { n ->
                    draft = draft.copy(remindAt = n.remindAt, remindRepeat = n.remindRepeat, remindAlarm = n.remindAlarm, remindFired = n.remindFired)
                    opened = opened?.copy(remindAt = n.remindAt, remindFired = n.remindFired)
                }
                reload()
            }
        }
    }

    private val ctx get() = getApplication<Application>()

    /** Semua penulisan catatan lewat sini agar alarm pengingat dan penanda cadangan selalu ikut diperbarui. */
    private fun persist(n: Note): Long {
        val id = db.upsert(n)
        Reminders.sync(ctx, n.copy(id = id))
        prefs.edit().putBoolean("dirty", true).apply()
        return id
    }

    private suspend fun reload() {
        val (list, idx) = withContext(Dispatchers.IO) {
            val l = db.all()
            l to SearchIndex(l.filter { !it.inTrash }.map { SearchIndex.Doc(it.id, it.title, it.body, it.tags) })
        }
        notes = list; index = idx
    }

    private val live get() = notes.filter { !it.inTrash }

    val allTags: List<String>
        get() = live.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }

    val folders: List<String>
        get() = live.map { it.folder }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

    val trashCount get() = notes.count { it.inTrash }
    val reminderCount get() = notes.count { it.hasReminder && !it.inTrash }

    fun visibleNotes(): List<Note> {
        val v = view
        val inView: (Note) -> Boolean = when (v) {
            NoteView.All -> { n -> !n.inTrash && !n.archived }
            is NoteView.Folder -> { n -> !n.inTrash && !n.archived && n.folder == v.name }
            NoteView.Reminders -> { n -> !n.inTrash && n.hasReminder }
            NoteView.Archive -> { n -> !n.inTrash && n.archived }
            NoteView.Trash -> { n -> n.inTrash }
        }
        val base = when {
            query.isBlank() && v == NoteView.Reminders -> notes.filter(inView).sortedBy { it.remindAt }     // yang terdekat (atau terlewat) di atas
            query.isBlank() -> notes.filter(inView).sortedWith(
                compareByDescending<Note> { it.pinned }.then(
                    when (sort) {
                        Sort.Updated -> compareByDescending { it.updated }
                        Sort.Created -> compareByDescending { it.created }
                        Sort.Title -> compareBy { it.title.lowercase() }
                    }
                )
            )
            v == NoteView.Trash -> notes.filter { it.inTrash && (it.title + " " + it.body).contains(query.trim(), ignoreCase = true) }
            else -> {
                // Saat mencari dari "Semua catatan", arsip ikut dicari agar tidak ada yang terlewat.
                val byId = notes.associateBy { it.id }
                index.search(query, aiTerms).mapNotNull { byId[it.id] }
                    .filter { if (v == NoteView.All) !it.inTrash else inView(it) }
            }
        }
        return tagFilter?.let { t -> base.filter { t in it.tags } } ?: base
    }

    fun updateSort(s: Sort) { sort = s; prefs.edit().putString("sort", s.name).apply() }
    fun updateQuery(q: String) { query = q; aiTerms = emptyList() }
    fun go(s: Screen) { screen = s }

    /** Pencarian cerdas: LLM memperluas kata kunci, lalu mesin TF-IDF yang mencari. */
    fun expandQueryWithAi() {
        val q = query.trim()
        if (q.isEmpty() || aiBusy) return
        viewModelScope.launch {
            aiBusy = true
            try {
                val terms = ai.expandQuery(q)
                if (query.trim() == q) aiTerms = terms
                if (terms.isEmpty()) messages.tryEmit("AI tidak memberi kata kunci tambahan.")
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { messages.tryEmit("AI gagal: ${e.message}")
            } finally { aiBusy = false }
        }
    }

    // ============================== Editor ==============================

    fun open(note: Note?) {
        stopAi()
        draft = note ?: Note(folder = (view as? NoteView.Folder)?.name ?: "")
        opened = note?.takeIf { it.id != 0L }; snapshotDone = false
        suggestedTags = emptyList(); aiJob = null; undoBody = null; aiSheet = false; versions = null; reminderFor = null
        screen = Screen.Editor
    }

    /** Dibuka dari notifikasi pengingat: pengingat sekali dianggap selesai, lalu catatannya ditampilkan. */
    fun openFromReminder(id: Long) {
        if (screen == Screen.Editor) saveDraft()
        Reminders.done(ctx, id)
        db.get(id)?.let { open(it) }
        viewModelScope.launch { reload() }
    }

    fun edit(transform: (Note) -> Note) { draft = transform(draft) }

    fun addTag(raw: String) {
        val t = TextTools.normalizeTag(raw)
        if (t.isNotEmpty() && t !in draft.tags) draft = draft.copy(tags = draft.tags + t)
        suggestedTags = suggestedTags - t
    }

    /** Simpan otomatis. Catatan baru yang kosong tidak disimpan. */
    fun saveDraft() {
        val d = draft
        if (d.isBlank && d.id == 0L) return
        val original = notes.firstOrNull { it.id == d.id }
        if (original != null && original.copy(updated = d.updated) == d) return   // tak berubah
        // Tulis sinkron: penulisan SQLite satu baris hanya beberapa milidetik, dan ini
        // menjamin tidak ada catatan ganda bila simpan dipicu dua kali berdekatan.
        val toSave = d.copy(updated = System.currentTimeMillis())
        // Riwayat versi: sekali per sesi sunting, simpan keadaan catatan saat dibuka sebelum isinya berubah.
        val before = opened
        if (before != null && !snapshotDone && before.id == d.id && before.body.isNotBlank() && (before.body != d.body || before.title != d.title)) {
            db.addVersion(before.id, before.title, before.body, before.updated)
            snapshotDone = true
        }
        draft = toSave.copy(id = persist(toSave))
        if (opened == null) { opened = draft; snapshotDone = true }       // catatan baru: belum ada versi lama
        viewModelScope.launch { reload() }
    }

    fun closeEditor() {
        stopAi(); saveDraft(); screen = Screen.List
        if (autoOrganize) organizeInBackground(draft.id)
    }
    fun openRelated(note: Note) { saveDraft(); open(note) }

    /** Hapus = pindah ke sampah (bisa dipulihkan 30 hari). */
    fun trashDraft() {
        stopAi()
        if (draft.id == 0L) { draft = Note(); screen = Screen.List; return }
        draft = draft.copy(deletedAt = System.currentTimeMillis(), pinned = false)
        saveDraft(); screen = Screen.List
        messages.tryEmit("Dipindah ke Sampah.")
    }

    fun restoreDraft() { draft = draft.copy(deletedAt = 0); saveDraft(); messages.tryEmit("Catatan dipulihkan.") }

    fun deleteDraftForever() {
        val id = draft.id
        draft = Note(); screen = Screen.List
        if (id != 0L) viewModelScope.launch {
            withContext(Dispatchers.IO) { db.delete(id); Reminders.clear(ctx, id) }
            prefs.edit().putBoolean("dirty", true).apply()
            reload()
        }
    }

    // ============================== Riwayat versi ==============================

    fun openVersions() {
        saveDraft()
        if (draft.id == 0L) { messages.tryEmit("Catatan ini belum punya riwayat."); return }
        versions = db.versions(draft.id)
    }

    fun closeVersions() { versions = null }

    /** Memulihkan versi lama. Isi saat ini lebih dulu disimpan ke riwayat, jadi pemulihan pun bisa dibatalkan. */
    fun restoreVersion(v: Version) {
        val d = draft
        if (d.body.isNotBlank() && d.body != v.body && versions.orEmpty().none { it.body == d.body && it.title == d.title })
            db.addVersion(d.id, d.title, d.body, System.currentTimeMillis())
        snapshotDone = true
        draft = d.copy(title = v.title, body = v.body)
        versions = null
        saveDraft()
        messages.tryEmit("Versi ${whenLabel(v.saved)} dipulihkan.")
    }

    // ============================== Pengingat ==============================

    /** Membuka lembar pengingat untuk [note] (default: catatan di editor). [seed] = waktu yang disarankan. */
    fun openReminder(note: Note? = null, seed: Long? = null) {
        val target = note ?: run { saveDraft(); draft }
        if (target.id == 0L) { messages.tryEmit("Tulis sesuatu dulu, baru setel pengingat."); return }
        aiSheet = false
        reminderSeed = seed; reminderFor = target
    }

    fun closeReminder() { reminderFor = null; reminderSeed = null }

    private fun changeReminder(id: Long, f: (Note) -> Note) {
        if (screen == Screen.Editor && draft.id == id) { draft = f(draft); saveDraft() }
        else notes.firstOrNull { it.id == id }?.let { update(f(it)) }
    }

    fun saveReminder(at: Long, repeat: Repeat, alarm: Boolean) {
        val id = reminderFor?.id ?: return
        closeReminder()
        changeReminder(id) { it.copy(remindAt = at, remindRepeat = repeat, remindAlarm = alarm, remindFired = false) }
        messages.tryEmit(
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
        messages.tryEmit("Pengingat dihapus.")
    }

    /** Waktu yang disebut di catatan, dikenali tanpa model. Dipakai untuk menawarkan pengingat. */
    fun reminderHint(): TimeParse.Found? =
        if (draft.hasReminder || draft.inTrash || draft.isBlank) null else TimeParse.best(draft.title + "\n" + draft.body)

    /** "Sarankan pengingat": pengenal waktu bawaan dulu; bila gagal dan ada model, LLM merumuskan ulang frasanya. */
    fun suggestReminder() {
        val d = draft
        if (d.isBlank || aiBusy) return
        TimeParse.best(d.title + "\n" + d.body)?.let { openReminder(seed = it.at.toMillis()); return }
        if (!ai.hasModel) { aiSheet = false; messages.tryEmit("Tidak menemukan tanggal atau jam di catatan ini."); return }
        aiSheet = false
        viewModelScope.launch {
            aiBusy = true
            try {
                val found = ai.reminderPhrase(d.title, d.body)?.let { TimeParse.best(it, LocalDateTime.now()) }
                if (found != null && screen == Screen.Editor && draft.id == d.id) openReminder(seed = found.at.toMillis())
                else if (found == null) messages.tryEmit("AI tidak menemukan jadwal atau tenggat di catatan ini.")
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { messages.tryEmit("AI gagal: ${e.message}")
            } finally { aiBusy = false }
        }
    }

    fun toggleArchive() {
        val nowArchived = !draft.archived
        draft = draft.copy(archived = nowArchived, pinned = if (nowArchived) false else draft.pinned)
        saveDraft()
        messages.tryEmit(if (nowArchived) "Diarsipkan." else "Dikeluarkan dari arsip.")
        if (nowArchived) screen = Screen.List
    }

    fun emptyTrash() = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.emptyTrash() }; reload()
        prefs.edit().putBoolean("dirty", true).apply()
        messages.tryEmit("Sampah dikosongkan.")
    }

    fun relatedToDraft(): List<Note> {
        val byId = notes.associateBy { it.id }
        return index.related(draft.title, draft.body, draft.tags, draft.id).mapNotNull { byId[it.id] }
    }

    /** Tag otomatis: LLM bila model tersedia, kalau tidak pakai kata kunci statistik. */
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
                if (suggestedTags.isEmpty()) messages.tryEmit("AI tidak menghasilkan tag baru.")
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                suggestedByAi = false
                suggestedTags = index.keywords(d.title, d.body).filter { it !in d.tags }
                messages.tryEmit("AI gagal (${e.message}); memakai kata kunci.")
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
                val clean = if (action.wholeNote && d.body.length > AiText.maxInput) runInParts(action, d) else {
                    val out = ai.generate(AiText.prompt(action, d.title, d.body, custom)) { partial ->
                        aiJob = aiJob?.copy(text = AiText.cleanBlock(partial))
                    }
                    if (action.forTitle) AiText.cleanLine(AiText.strip(out)) else AiText.cleanBlock(out)
                }
                aiJob = aiJob?.copy(text = clean, running = false, progress = null, error = if (clean.isBlank()) "Model tidak menghasilkan teks." else null)
            } catch (e: CancellationException) {
                aiJob = aiJob?.copy(running = false, progress = null)       // dihentikan pengguna: teks parsial tetap bisa dipakai
            } catch (e: Throwable) {
                aiJob = aiJob?.copy(running = false, progress = null, error = "AI gagal: ${e.message}")
            }
        }
    }

    /**
     * Catatan yang lebih panjang dari jendela model: tiap bagian diproses sendiri, lalu hasilnya digabung.
     * Ringkasan digabung lewat satu permintaan tambahan; daftar tugas dan perbaikan ejaan cukup disambung.
     */
    private suspend fun runInParts(action: AiAction, d: Note): String {
        val parts = AiText.chunks(d.body)
        val results = ArrayList<String>()
        parts.forEachIndexed { i, part ->
            val sofar = results.joinToString("\n")
            val label = "Bagian ${i + 1} dari ${parts.size}"
            aiJob = aiJob?.copy(progress = label)
            val out = ai.generate(AiText.partPrompt(action, d.title, part, i, parts.size)) { partial ->
                aiJob = aiJob?.copy(text = (if (sofar.isEmpty()) "" else sofar + "\n") + AiText.cleanBlock(partial), progress = label)
            }
            // Perbaikan ejaan menggantikan isi, jadi jarak antarbagian (baris kosong) dipertahankan seperti aslinya.
            results += AiText.cleanBlock(out) + if (action == AiAction.Fix) part.takeLastWhile { it.isWhitespace() } else ""
        }
        return when (action) {
            AiAction.Fix -> results.joinToString("").trim()
            AiAction.Tasks -> AiText.mergeTasks(results)
            else -> {
                val points = results.joinToString("\n")
                if (parts.size == 1) points else {
                    aiJob = aiJob?.copy(text = points, progress = "Menggabungkan ringkasan")
                    AiText.cleanBlock(ai.generate(AiText.mergeSummaryPrompt(d.title, points)) { partial ->
                        aiJob = aiJob?.copy(text = AiText.cleanBlock(partial))
                    })
                }
            }
        }
    }

    /** Hanya untuk uji tangkapan layar. */
    internal fun debugAiJob(j: AiJob) { aiJob = j }

    fun stopAi() { running?.cancel(); running = null }
    fun dismissAi() { stopAi(); aiJob = null; aiSheet = false }

    /** Menerapkan hasil AI atas pilihan pengguna. Mengganti isi bisa diurungkan satu langkah. */
    fun applyAi(replace: Boolean) {
        val j = aiJob ?: return
        val text = j.text.trim()
        if (text.isEmpty()) return
        draft = when {
            j.action.forTitle -> draft.copy(title = AiText.cleanLine(text).take(120))
            replace -> { undoBody = draft.body; draft.copy(body = text) }
            j.action == AiAction.Continue -> draft.copy(body = draft.body.trimEnd() + " " + text)
            else -> draft.copy(body = draft.body.trimEnd() + "\n\n" + text)
        }
        aiJob = null; aiSheet = false
    }

    fun undoAiReplace() { undoBody?.let { draft = draft.copy(body = it) }; undoBody = null }

    /** Rapikan satu catatan: isi judul, folder, dan tag yang masih kosong. Tidak menimpa isian pengguna. */
    fun organizeDraft() {
        val d = draft
        if (d.body.isBlank() || aiBusy) return
        viewModelScope.launch {
            aiBusy = true
            try {
                val o = ai.organize(d.title, d.body, folders, allTags)
                val merged = mergeOrganized(draft, o)
                messages.tryEmit(if (merged == draft) "Tidak ada yang perlu dirapikan." else describe(draft, merged))
                draft = merged
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) { messages.tryEmit("AI gagal: ${e.message}")
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

    /**
     * Rapikan otomatis (opsional): setelah editor ditutup, AI mengisi judul/tag/folder yang kosong di latar.
     * Aturannya sama dengan rapikan manual: tidak pernah menimpa isian pengguna maupun isi catatan.
     */
    private fun organizeInBackground(id: Long) {
        if (id == 0L || !ai.hasModel) return
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { db.get(id) } ?: return@launch
                if (!needsOrganizing(n) || n.body.length < 40) return@launch       // terlalu pendek untuk dirapikan
                val o = ai.organize(n.title, n.body, folders, allTags)
                val cur = withContext(Dispatchers.IO) { db.get(id) } ?: return@launch
                if (cur.inTrash || (screen == Screen.Editor && draft.id == id)) return@launch      // sedang disunting lagi: jangan ganggu
                val merged = mergeOrganized(cur, o)
                if (merged != cur) { withContext(Dispatchers.IO) { persist(merged) }; reload() }
            } catch (e: CancellationException) { throw e
            } catch (_: Throwable) {}      // pekerjaan latar: gagal diam-diam, catatan tidak berubah
        }
    }

    fun updateAutoOrganize(on: Boolean) { autoOrganize = on; prefs.edit().putBoolean("auto_organize", on).apply() }

    fun stopBatch() { batchJob?.cancel() }

    private fun needsOrganizing(n: Note) =
        !n.inTrash && n.body.isNotBlank() && (n.title.isBlank() || n.tags.isEmpty() || (n.folder.isBlank() && folders.isNotEmpty()))

    val organizableCount get() = notes.count(::needsOrganizing)

    /** Rapikan massal: hanya mengisi yang kosong, satu per satu, bisa dihentikan kapan saja. */
    fun organizeAll() {
        if (batch != null) return
        val targets = notes.filter(::needsOrganizing)
        if (targets.isEmpty()) { messages.tryEmit("Semua catatan sudah punya judul dan tag."); return }
        batch = 0 to targets.size
        batchJob = viewModelScope.launch {
            var changed = 0
            try {
                targets.forEachIndexed { i, n ->
                    val o = ai.organize(n.title, n.body, folders, allTags)
                    // Ambil versi terbaru: catatan bisa saja disunting selagi proses berjalan.
                    val cur = notes.firstOrNull { it.id == n.id }
                    val editing = screen == Screen.Editor && draft.id == n.id
                    val merged = if (cur == null || cur.inTrash || editing) cur else mergeOrganized(cur, o)
                    if (merged != null && merged != cur) { withContext(Dispatchers.IO) { persist(merged) }; changed++ }   // `updated` tidak diubah
                    batch = (i + 1) to targets.size
                }
                messages.tryEmit("$changed catatan dirapikan.")
            } catch (e: CancellationException) {
                messages.tryEmit("Dihentikan. $changed catatan sudah dirapikan.")
            } catch (e: Throwable) {
                messages.tryEmit("AI gagal: ${e.message}")
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
        val byId = notes.associateBy { it.id }
        askSources = index.search(q, limit = 4).mapNotNull { byId[it.id] }
        askAnswer = ""
        if (askSources.isEmpty()) { askAnswer = "Tidak ada catatan yang berkaitan dengan pertanyaan itu."; return }
        if (!ai.hasModel) { askAnswer = "Model AI belum dipasang. Berikut catatan yang paling berkaitan:"; return }
        askRunning = true
        running = viewModelScope.launch {
            try {
                val out = ai.generate(AiText.askPrompt(q, askSources.map { it.title to it.body })) { askAnswer = AiText.cleanBlock(it) }
                askAnswer = AiText.cleanBlock(out).ifBlank { "Model tidak menghasilkan jawaban." }
            } catch (e: CancellationException) {
            } catch (e: Throwable) { askAnswer = "AI gagal: ${e.message}"
            } finally { askRunning = false }
        }
    }

    /** Semua butir daftar centang yang belum selesai, dikelompokkan per catatan. Tanpa model. */
    fun askOpenTasks() {
        if (askRunning) return
        askQuery = "Tugas yang belum selesai"
        val withTasks = live.filter { !it.archived }.sortedByDescending { it.updated }
            .mapNotNull { n -> AiText.openTasks(n.body).takeIf { it.isNotEmpty() }?.let { n to it } }
        askSources = withTasks.map { it.first }
        askAnswer = if (withTasks.isEmpty()) "Tidak ada butir daftar centang yang belum selesai."
        else withTasks.joinToString("\n\n") { (n, tasks) -> n.heading.take(60) + "\n" + tasks.joinToString("\n") }
    }

    /** Rekap catatan 7 hari terakhir. Tanpa model, daftar catatannya tetap ditampilkan. */
    fun askRecap() {
        if (askRunning) return
        askQuery = "Rekap 7 hari terakhir"
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        askSources = live.filter { !it.archived && it.updated >= since && it.body.isNotBlank() }
            .sortedByDescending { it.updated }.take(if (AiText.maxInput > 4000) 8 else 5)
        askAnswer = ""
        if (askSources.isEmpty()) { askAnswer = "Tidak ada catatan yang ditulis atau diubah dalam 7 hari terakhir."; return }
        if (!ai.hasModel) { askAnswer = "Model AI belum dipasang. Berikut catatan 7 hari terakhir:"; return }
        askRunning = true
        running = viewModelScope.launch {
            try {
                val out = ai.generate(AiText.recapPrompt(askSources.map { it.title to it.body })) { askAnswer = AiText.cleanBlock(it) }
                askAnswer = AiText.cleanBlock(out).ifBlank { "Model tidak menghasilkan rekap." }
            } catch (e: CancellationException) {
            } catch (e: Throwable) { askAnswer = "AI gagal: ${e.message}"
            } finally { askRunning = false }
        }
    }

    fun closeAsk() { stopAi(); screen = Screen.List }

    /** Dibuka dari kolom cari: pertanyaan langsung dijalankan. */
    fun openAsk(q: String = "") {
        askQuery = q.trim(); askAnswer = ""; askSources = emptyList()
        screen = Screen.Ask
        if (askQuery.isNotEmpty()) ask(askQuery)
    }

    // ============================== Asisten perintah ==============================

    /** Perintah yang dikenali aturan (tanpa model); dipakai daftar untuk menawarkan tombol "Jalankan". */
    fun commandSteps(q: String): List<AgentStep>? = if (q.isBlank()) null else Agent.parse(q)

    /** Menyusun rencana dari [command]. Aturan dulu (instan, tanpa model); bila gagal dan ada model, LLM merumuskan ulang. */
    fun startAgent(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || agent?.planning == true) return
        agentUndo = null
        Agent.parse(cmd)?.let { agent = AgentRun(cmd, resolve(it)); return }
        if (!ai.hasModel) { agent = AgentRun(cmd, error = "Perintah itu belum dikenali."); return }
        agent = AgentRun(cmd, planning = true)
        running = viewModelScope.launch {
            try {
                val steps = Agent.parseModel(ai.generate(Agent.plannerPrompt(cmd)), cmd)
                agent = if (steps.isEmpty()) AgentRun(cmd, error = "Perintah itu belum dikenali.") else AgentRun(cmd, resolve(steps))
            } catch (e: CancellationException) { agent = null
            } catch (e: Throwable) { agent = AgentRun(cmd, error = "AI gagal: ${e.message}") }
        }
    }

    /** Mencari catatan sasaran tiap langkah dan memeriksa apakah langkah itu bisa dijalankan. */
    private fun resolve(steps: List<AgentStep>): List<AgentItem> {
        val byId = notes.associateBy { it.id }
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

    /** Pengguna memilih catatan sasaran lain untuk langkah ke-[i]. */
    fun agentPick(i: Int, note: Note) {
        val run = agent ?: return
        if (run.results != null) return
        agent = run.copy(items = run.items.mapIndexed { j, it -> if (j == i) it.copy(target = note, problem = problemFor(it.step, note)) else it })
    }

    val agentReady get() = agent?.let { !it.planning && it.results == null && it.error == null && it.items.isNotEmpty() && it.items.none { i -> i.problem != null } } == true
    val agentSetsReminder get() = agent?.items?.any { (it.step as? AgentStep.Create)?.at != null || it.step is AgentStep.Remind } == true

    /** Menjalankan rencana yang sudah disetujui. Semua perubahan bisa diurungkan dengan [undoAgent]. */
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
                        // Ambil keadaan terbaru: langkah sebelumnya bisa saja mengubah catatan yang sama.
                        val base = (if (s.target == Agent.PREV) prev else item.target)?.let { db.get(it.id) }
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
                        // Isi yang diubah asisten tetap bisa dilihat lagi lewat Riwayat versi.
                        if (changed.body != base.body && base.body.isNotBlank()) db.addVersion(base.id, base.title, base.body, base.updated)
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

    /** Mengembalikan semua yang diubah perintah terakhir: catatan baru dihapus, catatan lama dipulihkan. */
    fun undoAgent() {
        val (before, created) = agentUndo ?: return
        agentUndo = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                created.forEach { db.delete(it); Reminders.clear(ctx, it) }
                before.forEach { persist(it) }
            }
            prefs.edit().putBoolean("dirty", true).apply()
            reload()
            agent = null
            messages.tryEmit("Perintah diurungkan.")
        }
    }

    fun closeAgent() { if (agent?.planning == true) stopAi(); agent = null }

    fun openAgentNote() {
        val id = agent?.touched ?: 0
        agent = null
        notes.firstOrNull { it.id == id }?.let { open(it) }
    }

    /** Hanya untuk uji tangkapan layar. */
    internal fun debugAgent(r: AgentRun) { agent = r }

    // ============================== Aksi cepat dari daftar ==============================

    private fun update(n: Note) = viewModelScope.launch { withContext(Dispatchers.IO) { persist(n) }; reload() }
    fun togglePin(n: Note) = update(n.copy(pinned = !n.pinned))
    fun toggleArchive(n: Note) = update(n.copy(archived = !n.archived, pinned = false))
    fun trash(n: Note) { update(n.copy(deletedAt = System.currentTimeMillis(), pinned = false)); messages.tryEmit("Dipindah ke Sampah.") }
    fun restore(n: Note) = update(n.copy(deletedAt = 0))

    // ============================== Pengaturan ==============================

    fun importModel(uri: Uri) = viewModelScope.launch {
        try { ai.importModel(uri); messages.tryEmit("Model terpasang (${ai.modelSizeMb} MB).") }
        catch (e: Throwable) { messages.tryEmit("Impor gagal: ${e.message}") }
    }

    fun loadModel() = viewModelScope.launch {
        try { ai.load(); messages.tryEmit("Model siap.") } catch (_: Throwable) {}
    }

    fun unloadModel() = viewModelScope.launch { ai.unload() }
    fun deleteModel(id: String) = viewModelScope.launch { ai.delete(id) }
    fun activateModel(id: String) = viewModelScope.launch { ai.activate(id) }
    fun setGpu(on: Boolean) = viewModelScope.launch { ai.useGpu = on; ai.unload() }
    var lightAi by mutableStateOf(ai.lightMode); private set
    fun updateLightAi(on: Boolean) { lightAi = on; viewModelScope.launch { ai.setLight(on) } }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        try {
            val data = notes      // termasuk Sampah, agar cadangan benar-benar lengkap
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.toJson(data).toByteArray()) }
            }
            messages.tryEmit("${data.size} catatan diekspor.")
        } catch (e: Throwable) { messages.tryEmit("Ekspor gagal: ${e.message}") }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        try {
            val n = withContext(Dispatchers.IO) {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                val seen = db.all().map { it.title to it.body }.toSet()
                val now = System.currentTimeMillis()
                Backup.fromJson(text).filter { (it.title to it.body) !in seen }.map { n ->
                    // Pengingat lama di cadangan tidak boleh berbunyi serentak saat diimpor: yang berulang dimajukan
                    // ke jadwal berikutnya, yang sekali ditandai terlewat.
                    if (!n.hasReminder || n.remindAt > now) n
                    else n.remindRepeat.next(n.remindAt.toLocal(), LocalDateTime.now())?.let { n.copy(remindAt = it.toMillis(), remindFired = false) }
                        ?: n.copy(remindFired = true)
                }.onEach { persist(it) }.size
            }
            reload()
            messages.tryEmit("$n catatan diimpor (duplikat dilewati).")
        } catch (e: Throwable) { messages.tryEmit("Impor gagal: berkas tidak valid.") }
    }

    // ---------- Cadangan otomatis ----------

    val backupLabel: String? get() = backupTree?.let { AutoBackup.folderLabel(Uri.parse(it)) }

    fun setBackupFolder(uri: Uri?) {
        val cr = ctx.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        backupTree?.let { old -> runCatching { cr.releasePersistableUriPermission(Uri.parse(old), flags) } }
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, flags) }
        backupTree = uri?.toString(); backupError = null
        prefs.edit().putString("backup_tree", backupTree).remove("backup_error").putBoolean("dirty", true).apply()
        if (uri != null) backupNow(announce = true)
    }

    /** Dipanggil saat aplikasi ditinggalkan: menulis cadangan bila ada perubahan sejak cadangan terakhir. */
    fun backupNow(announce: Boolean = false) {
        val tree = backupTree ?: return
        if (!announce && !prefs.getBoolean("dirty", false)) return
        appScope.launch {
            try {
                AutoBackup.write(ctx, Uri.parse(tree), Backup.toJson(db.all()))
                val now = System.currentTimeMillis()
                prefs.edit().putBoolean("dirty", false).putLong("backup_at", now).remove("backup_error").apply()
                backupAt = now; backupError = null
                if (announce) messages.tryEmit("Cadangan tersimpan di ${backupLabel}.")
            } catch (e: Throwable) {
                val msg = e.message ?: "folder tidak bisa ditulisi"
                prefs.edit().putString("backup_error", msg).apply()
                backupError = msg
                if (announce) messages.tryEmit("Cadangan gagal: $msg")
            }
        }
    }

    // ---------- Kunci aplikasi ----------

    /** Kunci memakai sidik jari/PIN layar HP, jadi hanya bisa diaktifkan bila HP punya kunci layar. */
    fun updateLock(on: Boolean): Boolean {
        if (on && ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            messages.tryEmit("Setel dulu kunci layar HP (PIN, pola, atau sidik jari).")
            return false
        }
        lockEnabled = on; locked = false
        prefs.edit().putBoolean("lock", on).apply()
        return true
    }

    fun unlocked() { locked = false }
    /** Hanya untuk uji tangkapan layar. */
    internal fun debugLock() { locked = true }

    /** Aplikasi ditinggalkan / dibuka lagi. Jeda singkat (pemilih berkas, dialog izin) tidak mengunci ulang. */
    fun onLeave() { leftAt = System.currentTimeMillis(); backupNow() }
    fun onReturn() { if (lockEnabled && leftAt != 0L && System.currentTimeMillis() - leftAt > LOCK_GRACE_MS) locked = true }

    companion object { const val LOCK_GRACE_MS = 60_000L }

    // Basis data dipakai bersama penerima alarm, jadi tidak ditutup di sini.
}
