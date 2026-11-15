package com.catatanku.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { List, Editor, Settings, Ask }
enum class Sort(val label: String) { Updated("Terakhir diubah"), Created("Tanggal dibuat"), Title("Judul A–Z") }

/** Tampilan daftar: semua catatan aktif, satu folder, arsip, atau sampah. */
sealed interface NoteView {
    data object All : NoteView
    data class Folder(val name: String) : NoteView
    data object Archive : NoteView
    data object Trash : NoteView
}

/** Hasil bantu-tulis AI yang menunggu keputusan pengguna (tidak pernah diterapkan otomatis). */
data class AiJob(val action: AiAction, val text: String = "", val running: Boolean = true, val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NoteDb(app)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
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

    // Tanya catatan
    var askSources by mutableStateOf<List<Note>>(emptyList()); private set
    var askAnswer by mutableStateOf(""); private set
    var askRunning by mutableStateOf(false); private set
    var askQuery by mutableStateOf("")

    private var running: Job? = null
    private var batchJob: Job? = null
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    var hideAiBanner by mutableStateOf(prefs.getBoolean("hide_ai_banner", false)); private set
    fun dismissAiBanner() { hideAiBanner = true; prefs.edit().putBoolean("hide_ai_banner", true).apply() }

    init {
        viewModelScope.launch { ai.events.collect { messages.tryEmit(it) } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.purgeOldTrash() }
            reload()
        }
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

    fun visibleNotes(): List<Note> {
        val v = view
        val inView: (Note) -> Boolean = when (v) {
            NoteView.All -> { n -> !n.inTrash && !n.archived }
            is NoteView.Folder -> { n -> !n.inTrash && !n.archived && n.folder == v.name }
            NoteView.Archive -> { n -> !n.inTrash && n.archived }
            NoteView.Trash -> { n -> n.inTrash }
        }
        val base = when {
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
        suggestedTags = emptyList(); aiJob = null; undoBody = null; aiSheet = false
        screen = Screen.Editor
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
        draft = toSave.copy(id = db.upsert(toSave))
        viewModelScope.launch { reload() }
    }

    fun closeEditor() { stopAi(); saveDraft(); screen = Screen.List }
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
        if (id != 0L) viewModelScope.launch { withContext(Dispatchers.IO) { db.delete(id) }; reload() }
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
                val out = ai.generate(AiText.prompt(action, d.title, d.body, custom)) { partial ->
                    aiJob = aiJob?.copy(text = AiText.cleanBlock(partial))
                }
                val clean = if (action.forTitle) AiText.cleanLine(AiText.strip(out)) else AiText.cleanBlock(out)
                aiJob = aiJob?.copy(text = clean, running = false, error = if (clean.isBlank()) "Model tidak menghasilkan teks." else null)
            } catch (e: CancellationException) {
                aiJob = aiJob?.copy(running = false)       // dihentikan pengguna: teks parsial tetap bisa dipakai
            } catch (e: Throwable) {
                aiJob = aiJob?.copy(running = false, error = "AI gagal: ${e.message}")
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
                    if (merged != null && merged != cur) { withContext(Dispatchers.IO) { db.upsert(merged) }; changed++ }   // `updated` tidak diubah
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

    fun closeAsk() { stopAi(); screen = Screen.List }

    /** Dibuka dari kolom cari: pertanyaan langsung dijalankan. */
    fun openAsk(q: String = "") {
        askQuery = q.trim(); askAnswer = ""; askSources = emptyList()
        screen = Screen.Ask
        if (askQuery.isNotEmpty()) ask(askQuery)
    }

    // ============================== Aksi cepat dari daftar ==============================

    private fun update(n: Note) = viewModelScope.launch { withContext(Dispatchers.IO) { db.upsert(n) }; reload() }
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

    fun exportTo(uri: Uri) = viewModelScope.launch {
        try {
            val data = live
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!
                    .use { it.write(Backup.toJson(data).toByteArray()) }
            }
            messages.tryEmit("${data.size} catatan diekspor.")
        } catch (e: Throwable) { messages.tryEmit("Ekspor gagal: ${e.message}") }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        try {
            val n = withContext(Dispatchers.IO) {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().decodeToString() }
                val seen = notes.map { it.title to it.body }.toSet()
                Backup.fromJson(text).filter { (it.title to it.body) !in seen }.onEach { db.upsert(it) }.size
            }
            reload()
            messages.tryEmit("$n catatan diimpor (duplikat dilewati).")
        } catch (e: Throwable) { messages.tryEmit("Impor gagal: berkas tidak valid.") }
    }

    override fun onCleared() { db.close() }
}
