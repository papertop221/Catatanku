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
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

enum class Screen { List, Editor, Settings }
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

/** Pesan singkat di dasar layar; [action] menambahkan satu tombol, mis. "Urungkan". */
class Notice(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** Ringkasan daftar catatan. Dihitung sekali tiap catatan berubah, bukan tiap kali layar digambar ulang. */
class Stats(notes: List<Note>) {
    val byId: Map<Long, Note> = notes.associateBy { it.id }
    val tags: List<String>          // yang paling sering dipakai lebih dulu
    val folders: List<String>
    val trashCount: Int
    val reminderCount: Int
    val organizable: Int

    init {
        val live = notes.filter { !it.inTrash }
        tags = live.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        folders = live.map { it.folder }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        trashCount = notes.size - live.size
        reminderCount = live.count { it.hasReminder }
        organizable = live.count { needsOrganizing(it, folders.isNotEmpty()) }
    }

    companion object {
        /** Catatan yang judul, tag, atau foldernya masih kosong dan bisa diisi AI. */
        fun needsOrganizing(n: Note, hasFolders: Boolean) =
            !n.inTrash && n.body.isNotBlank() && (n.title.isBlank() || n.tags.isEmpty() || (n.folder.isBlank() && hasFolders))
    }
}

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
    var stats by mutableStateOf(Stats(emptyList())); private set
    var loaded by mutableStateOf(false); private set                      // pembacaan pertama dari basis data sudah selesai
    var index by mutableStateOf(SearchIndex(emptyList())); private set    // tanpa sampah; diperbarui di latar
    private var writeGen = 0                                              // bertambah tiap daftar di memori diubah langsung
    private var indexJob: Job? = null
    private var searchJob: Job? = null
    /** Masih ada hitungan latar (indeks atau pencarian) yang belum selesai. Dipakai uji otomatis. */
    internal val settling get() = indexJob?.isActive == true || searchJob?.isActive == true

    // Daftar
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
    var batch by mutableStateOf<Pair<Int, Int>?>(null); private set       // (selesai, total) rapikan massal
    /** Catatan yang tampil di daftar untuk tampilan, urutan, pencarian, dan filter tag saat ini. */
    var shown by mutableStateOf<List<Note>>(emptyList()); private set

    // Hari ini: pengingat yang terlewat atau jatuh hari ini, dan jumlah butir daftar centang yang belum selesai
    var today by mutableStateOf<List<Note>>(emptyList()); private set
    var openTaskCount by mutableStateOf(0); private set
    var todayOpen by mutableStateOf(prefs.getBoolean("today_open", true)); private set
    fun toggleToday() { todayOpen = !todayOpen; prefs.edit().putBoolean("today_open", todayOpen).apply() }

    // Editor
    var draft by mutableStateOf(Note()); private set
    var suggestedTags by mutableStateOf<List<String>>(emptyList()); private set
    var suggestedByAi by mutableStateOf(false); private set
    var aiJob by mutableStateOf<AiJob?>(null); private set
    private var undoAi by mutableStateOf<Pair<String, String>?>(null)     // (judul, isi) sebelum hasil AI diterapkan
    val canUndoAi get() = undoAi != null
    var aiSheet by mutableStateOf(false)                                  // lembar bantu tulis di editor
    var versions by mutableStateOf<List<Version>?>(null); private set     // null = lembar riwayat tertutup
    private var opened: Note? = null                                      // keadaan catatan saat editor dibuka
    private var snapshotDone = false
    private var fresh = false                                             // catatan ini dibuat di sesi sunting sekarang
    private var clean by mutableStateOf<Note?>(null)                      // draf yang sama persis dengan yang tersimpan
    /** Draf sudah tersimpan seluruhnya (untuk penanda "Tersimpan" di editor). */
    val draftSaved get() = draft.id != 0L && draft === clean

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

    // Tanya catatan: jawabannya tampil di atas daftar, bukan di layar tersendiri. askQuery kosong = tidak ada jawaban.
    var askSources by mutableStateOf<List<Note>>(emptyList()); private set
    var askAnswer by mutableStateOf(""); private set
    var askRunning by mutableStateOf(false); private set
    var askByAi by mutableStateOf(false); private set                     // jawabannya ditulis model (bukan daftar apa adanya)
    var askQuery by mutableStateOf(""); private set
    val askActive get() = askQuery.isNotEmpty()

    // Asisten perintah
    var agent by mutableStateOf<AgentRun?>(null); private set
    private var agentUndo: Pair<List<Note>, List<Long>>? = null           // (keadaan sebelum diubah, id catatan yang dibuat)
    val agentCanUndo get() = agentUndo != null

    private var running: Job? = null
    private var batchJob: Job? = null
    val notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null) { notices.tryEmit(Notice(text, action, onAction)) }

    var hideAiBanner by mutableStateOf(prefs.getBoolean("hide_ai_banner", false)); private set
    fun dismissAiBanner() { hideAiBanner = true; prefs.edit().putBoolean("hide_ai_banner", true).apply() }

    /** Warna mengikuti wallpaper (Android 12+). Bawaannya palet aplikasi sendiri. */
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic_color", false)); private set
    fun updateDynamicColor(on: Boolean) { dynamicColor = on; prefs.edit().putBoolean("dynamic_color", on).apply() }

    @Volatile private var dirty = prefs.getBoolean("dirty", false)        // ada perubahan sejak cadangan terakhir
    private fun markDirty() { if (!dirty) { dirty = true; prefs.edit().putBoolean("dirty", true).apply() } }

    init {
        viewModelScope.launch { ai.events.collect { say(it) } }
        viewModelScope.launch {
            reload()        // daftar tampil dulu; beres-beres menyusul
            val purged = withContext(Dispatchers.IO) { db.purgeOldTrash().also { Reminders.rescheduleAll(app) } }
            if (purged) reload()
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

    /**
     * Semua penulisan catatan lewat sini agar alarm pengingat dan penanda cadangan selalu ikut diperbarui.
     * [before] = keadaan tersimpan sebelumnya, bila pemanggil tahu pasti. Alarm sistem hanya disentuh bila
     * pengingat atau status sampahnya berubah: simpan otomatis berjalan tiap jeda mengetik, dan menyetel alarm
     * berarti beberapa panggilan ke layanan sistem.
     */
    private fun persist(n: Note, before: Note? = null): Long {
        val id = db.upsert(n)
        val alarmSame = before != null && before.remindAt == n.remindAt && before.remindRepeat == n.remindRepeat &&
            before.remindAlarm == n.remindAlarm && before.remindFired == n.remindFired && before.inTrash == n.inTrash
        val newWithoutReminder = n.id == 0L && !n.hasReminder      // id baru belum mungkin punya alarm
        if (!alarmSame && !newWithoutReminder) Reminders.sync(ctx, n.copy(id = id))
        markDirty()
        return id
    }

    /** Daftar catatan berubah: perbarui ringkasan dan daftar yang tampil sekarang juga, indeks pencarian di latar. */
    private fun applyNotes(list: List<Note>) {
        notes = list
        stats = Stats(list)
        today = dueToday(list)
        refreshShown()
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            val prev = index
            val (idx, tasks) = withContext(Dispatchers.Default) {
                val live = list.filter { !it.inTrash }
                prev.update(live.map { SearchIndex.Doc(it.id, it.title, it.body, it.tags) }) to
                    live.sumOf { if (it.archived) 0 else AiText.openTaskCount(it.body) }
            }
            index = idx; openTaskCount = tasks
            if (query.isNotBlank()) refreshShown()
        }
    }

    /** Membaca semua catatan dari basis data. Diperlukan saat data berubah di luar layar (alarm, impor, proses latar). */
    private suspend fun reload() {
        while (true) {
            val gen = writeGen
            val read = withContext(Dispatchers.IO) { db.all() }
            if (gen != writeGen) continue       // ada simpan selagi membaca: baca lagi agar tidak menimpa yang lebih baru
            // Catatan yang tidak berubah memakai objek lama, sehingga barisnya tidak digambar ulang dan
            // fiturnya di indeks pencarian tidak dihitung ulang.
            val old = stats.byId
            applyNotes(read.map { n -> old[n.id]?.takeIf { it == n } ?: n })
            loaded = true
            return
        }
    }

    /** Menaruh hasil simpan langsung ke daftar di memori, tanpa membaca ulang seluruh basis data. */
    private fun putLocal(n: Note) {
        writeGen++
        val i = notes.indexOfFirst { it.id == n.id }
        applyNotes(if (i < 0) notes + n else notes.toMutableList().also { it[i] = n })
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

    /**
     * Menghitung [shown]. Tanpa kata cari, cukup saring dan urutkan. Dengan kata cari, mesin pencarian berjalan
     * di thread latar dan hanya hasil ketikan terakhir yang dipakai; sampai hasilnya datang, daftar lama tetap tampil.
     */
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
            q.isBlank() && v == NoteView.Reminders -> show(list.filter(inView).sortedBy { it.remindAt })     // yang terdekat (atau terlewat) di atas
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
                val idx = index; val terms = aiTerms; val byId = stats.byId
                searchJob = viewModelScope.launch {
                    val hits = withContext(Dispatchers.Default) { idx.search(q, terms) }
                    // Saat mencari dari "Semua catatan", arsip ikut dicari agar tidak ada yang terlewat.
                    show(hits.mapNotNull { byId[it.id] }.filter { if (v == NoteView.All) !it.inTrash else inView(it) })
                }
            }
        }
    }

    fun updateSort(s: Sort) { sort = s; prefs.edit().putString("sort", s.name).apply(); refreshShown() }
    fun updateQuery(q: String) {
        if (q == query) return
        if (askActive) closeAsk()       // jawaban yang tampil milik pertanyaan sebelumnya
        query = q; aiTerms = emptyList()
        refreshShown()
    }
    fun go(s: Screen) { screen = s }

    /** Pencarian cerdas: LLM memperluas kata kunci, lalu mesin TF-IDF yang mencari. */
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
        screen = Screen.Editor
    }

    /** Dibuka dari notifikasi pengingat: pengingat sekali dianggap selesai, lalu catatannya ditampilkan. */
    fun openFromReminder(id: Long) {
        if (screen == Screen.Editor) saveDraft()
        Reminders.done(ctx, id)
        db.get(id)?.let { open(it) }
        viewModelScope.launch { reload() }
    }

    fun edit(transform: (Note) -> Note) {
        val next = transform(draft)
        if (next != draft) draft = next      // kursor yang hanya berpindah tidak dihitung sebagai perubahan
    }

    fun addTag(raw: String) {
        val t = TextTools.normalizeTag(raw)
        if (t.isNotEmpty() && t !in draft.tags) draft = draft.copy(tags = draft.tags + t)
        suggestedTags = suggestedTags - t
    }

    /**
     * Simpan otomatis: dipanggil tiap jeda mengetik, saat editor ditutup, dan saat aplikasi ditinggalkan.
     * Catatan baru yang kosong tidak disimpan.
     */
    fun saveDraft() {
        val d = draft
        if (d.isBlank && d.id == 0L) return
        val original = stats.byId[d.id]
        if (original != null && original.copy(updated = d.updated) == d) { clean = d; return }   // tak berubah
        // Tulis sinkron agar tidak ada catatan ganda bila simpan dipicu dua kali berdekatan. Satu baris dalam
        // mode WAL tanpa paksa-tulis hanya sepersekian milidetik (lihat NoteDb.onConfigure).
        val toSave = d.copy(updated = System.currentTimeMillis())
        // Riwayat versi: sekali per sesi sunting, simpan keadaan catatan saat dibuka sebelum isinya berubah.
        val before = opened
        if (before != null && !snapshotDone && before.id == d.id && before.body.isNotBlank() && (before.body != d.body || before.title != d.title)) {
            db.addVersion(before.id, before.title, before.body, before.updated)
            snapshotDone = true
        }
        val saved = toSave.copy(id = persist(toSave, original))
        draft = saved; clean = saved
        if (opened == null) { opened = saved; snapshotDone = true }       // catatan baru: belum ada versi lama
        putLocal(saved)
    }

    fun closeEditor() {
        stopAi(); saveDraft(); screen = Screen.List
        val d = draft
        // Catatan baru yang sempat tersimpan otomatis lalu dikosongkan lagi tidak ditinggalkan sebagai baris kosong.
        if (fresh && d.id != 0L && d.isBlank && !d.hasReminder) {
            dropLocal(d.id)
            appScope.launch { db.delete(d.id) }
        } else if (autoOrganize) organizeInBackground(d.id)
    }
    fun openRelated(note: Note) { saveDraft(); open(note) }

    /** Hapus = pindah ke sampah (bisa dipulihkan 30 hari). */
    fun trashDraft() {
        stopAi()
        if (draft.id == 0L) { draft = Note(); screen = Screen.List; return }
        val pinned = draft.pinned
        draft = draft.copy(deletedAt = System.currentTimeMillis(), pinned = false)
        saveDraft(); screen = Screen.List
        val id = draft.id
        say("Dipindah ke Sampah.", "Urungkan") { stats.byId[id]?.let { update(it.copy(deletedAt = 0, pinned = pinned)) } }
    }

    fun restoreDraft() { draft = draft.copy(deletedAt = 0); saveDraft(); say("Catatan dipulihkan.") }

    fun deleteDraftForever() {
        val id = draft.id
        draft = Note(); screen = Screen.List
        if (id != 0L) {
            dropLocal(id)
            appScope.launch { db.delete(id); Reminders.clear(ctx, id) }
            markDirty()
        }
    }

    // ============================== Riwayat versi ==============================

    fun openVersions() {
        saveDraft()
        if (draft.id == 0L) { say("Catatan ini belum punya riwayat."); return }
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
        say("Versi ${whenLabel(v.saved)} dipulihkan.")
    }

    // ============================== Pengingat ==============================

    /** Membuka lembar pengingat untuk [note] (default: catatan di editor). [seed] = waktu yang disarankan. */
    fun openReminder(note: Note? = null, seed: Long? = null) {
        val target = note ?: run { saveDraft(); draft }
        if (target.id == 0L) { say("Tulis sesuatu dulu, baru setel pengingat."); return }
        aiSheet = false
        reminderSeed = seed; reminderFor = target
    }

    fun closeReminder() { reminderFor = null; reminderSeed = null }

    private fun changeReminder(id: Long, f: (Note) -> Note) {
        if (screen == Screen.Editor && draft.id == id) { draft = f(draft); saveDraft() }
        else stats.byId[id]?.let { update(f(it)) }
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

    /** "Selesai" dari blok Hari ini: pengingat sekali yang sudah berbunyi dihapus; yang berulang tetap berjalan. */
    fun finishReminder(n: Note) { Reminders.done(ctx, n.id) }

    /** Waktu yang disebut di catatan, dikenali tanpa model. Dipakai untuk menawarkan pengingat. Aman dipanggil dari thread latar. */
    fun reminderHint(d: Note = draft): TimeParse.Found? =
        if (d.hasReminder || d.inTrash || d.isBlank) null else TimeParse.best(d.title + "\n" + d.body)

    /** "Sarankan pengingat": pengenal waktu bawaan dulu; bila gagal dan ada model, LLM merumuskan ulang frasanya. */
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
        withContext(Dispatchers.IO) { db.emptyTrash() }; reload()
        markDirty()
        say("Sampah dikosongkan.")
    }

    /** Catatan yang mirip dengan [d]. Aman dipanggil dari thread latar. */
    fun relatedTo(d: Note): List<Note> {
        val byId = stats.byId
        return index.related(d.title, d.body, d.tags, d.id).mapNotNull { byId[it.id] }
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

    /** Menerapkan hasil AI atas pilihan pengguna. Apa pun yang diterapkan bisa diurungkan satu langkah. */
    fun applyAi(replace: Boolean) {
        val j = aiJob ?: return
        val text = j.text.trim()
        if (text.isEmpty()) return
        undoAi = draft.title to draft.body
        draft = when {
            j.action.forTitle -> draft.copy(title = AiText.cleanLine(text).take(120))
            replace -> draft.copy(body = text)
            j.action == AiAction.Continue -> draft.copy(body = draft.body.trimEnd() + " " + text)
            else -> draft.copy(body = draft.body.trimEnd() + "\n\n" + text)
        }
        aiJob = null; aiSheet = false
    }

    fun undoAiChange() { undoAi?.let { (title, body) -> draft = draft.copy(title = title, body = body) }; undoAi = null }

    /** Rapikan satu catatan: isi judul, folder, dan tag yang masih kosong. Tidak menimpa isian pengguna. */
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

    private fun needsOrganizing(n: Note) = Stats.needsOrganizing(n, folders.isNotEmpty())

    val organizableCount get() = stats.organizable

    /** Rapikan massal: hanya mengisi yang kosong, satu per satu, bisa dihentikan kapan saja. */
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
                    // Ambil versi terbaru: catatan bisa saja disunting selagi proses berjalan.
                    val cur = withContext(Dispatchers.IO) { db.get(n.id) }
                    val editing = screen == Screen.Editor && draft.id == n.id
                    val merged = if (cur == null || cur.inTrash || editing) cur else mergeOrganized(cur, o)
                    if (merged != null && merged != cur) { withContext(Dispatchers.IO) { persist(merged) }; changed++ }   // `updated` tidak diubah
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
        askQuery = "Tugas yang belum selesai"; askByAi = false
        val withTasks = notes.filter { !it.inTrash && !it.archived }.sortedByDescending { it.updated }
            .mapNotNull { n -> AiText.openTasks(n.body).takeIf { it.isNotEmpty() }?.let { n to it } }
        askSources = withTasks.map { it.first }
        askAnswer = if (withTasks.isEmpty()) "Tidak ada butir daftar centang yang belum selesai."
        else withTasks.joinToString("\n\n") { (n, tasks) -> n.heading.take(60) + "\n" + tasks.joinToString("\n") }
    }

    /** Rekap catatan 7 hari terakhir. Tanpa model, daftar catatannya tetap ditampilkan. */
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
                val out = ai.generate(AiText.recapPrompt(askSources.map { it.title to it.body })) { askAnswer = AiText.cleanBlock(it) }
                askAnswer = AiText.cleanBlock(out).ifBlank { "Model tidak menghasilkan rekap." }
            } catch (e: CancellationException) {
            } catch (e: Throwable) { askAnswer = "AI gagal: ${e.message}"
            } finally { askRunning = false }
        }
    }

    /** Menutup jawaban yang sedang tampil di atas daftar. */
    fun closeAsk() {
        if (askRunning) stopAi()
        askQuery = ""; askAnswer = ""; askSources = emptyList(); askRunning = false
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

    /** Hanya untuk uji tangkapan layar. */
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

    // ============================== Pengaturan ==============================

    fun importModel(uri: Uri) = viewModelScope.launch {
        try { ai.importModel(uri); say("Model terpasang (${ai.modelSizeMb} MB).") }
        catch (e: Throwable) { say("Impor gagal: ${e.message}") }
    }

    /** "Uji muat" di Pengaturan: sengaja melewati pemeriksaan RAM, karena pengguna memintanya langsung. */
    fun loadModel() = viewModelScope.launch {
        try { ai.load(force = true); say("Model siap.") } catch (_: Throwable) {}
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
            say("${data.size} catatan diekspor.")
        } catch (e: Throwable) { say("Ekspor gagal: ${e.message}") }
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
            say("$n catatan diimpor (duplikat dilewati).")
        } catch (e: Throwable) { say("Impor gagal: berkas tidak valid.") }
    }

    // ---------- Cadangan otomatis ----------

    val backupLabel: String? get() = backupTree?.let { AutoBackup.folderLabel(Uri.parse(it)) }

    fun setBackupFolder(uri: Uri?) {
        val cr = ctx.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        backupTree?.let { old -> runCatching { cr.releasePersistableUriPermission(Uri.parse(old), flags) } }
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, flags) }
        backupTree = uri?.toString(); backupError = null
        dirty = true
        prefs.edit().putString("backup_tree", backupTree).remove("backup_error").putBoolean("dirty", true).apply()
        if (uri != null) backupNow(announce = true)
    }

    /** Dipanggil saat aplikasi ditinggalkan: menulis cadangan bila ada perubahan sejak cadangan terakhir. */
    fun backupNow(announce: Boolean = false) {
        val tree = backupTree ?: return
        if (!announce && !dirty) return
        appScope.launch {
            try {
                dirty = false       // perubahan sesudah titik ini menandainya lagi
                AutoBackup.write(ctx, Uri.parse(tree), Backup.toJson(db.all()))
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

    /** Kunci memakai sidik jari/PIN layar HP, jadi hanya bisa diaktifkan bila HP punya kunci layar. */
    fun updateLock(on: Boolean): Boolean {
        if (on && ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            say("Setel dulu kunci layar HP (PIN, pola, atau sidik jari).")
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
    fun onLeave() {
        leftAt = System.currentTimeMillis()
        backupNow()
        appScope.launch { db.flush() }      // pastikan simpanan terakhir benar-benar tertulis ke penyimpanan
    }

    fun onReturn() {
        if (lockEnabled && leftAt != 0L && System.currentTimeMillis() - leftAt > LOCK_GRACE_MS) locked = true
        ai.onForeground()
        if (loaded) today = dueToday(notes)      // hari bisa saja sudah berganti selagi aplikasi di latar
    }

    /** Sinyal memori dari sistem diteruskan ke model AI, pemakai RAM terbesar. */
    fun onTrimMemory(level: Int) = ai.onTrimMemory(level)

    companion object { const val LOCK_GRACE_MS = 60_000L }

    // Basis data dipakai bersama penerima alarm, jadi tidak ditutup di sini.
}
