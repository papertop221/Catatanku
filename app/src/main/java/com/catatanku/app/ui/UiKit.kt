package com.catatanku.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.catatanku.app.security.SecurityPrefs
import java.security.MessageDigest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.reminders.toLocal
import com.catatanku.app.reminders.whenLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val ID: Locale = Locale.forLanguageTag("id-ID")
internal val Gutter = 20.dp
/** Jarak sebelum judul kelompok; lihat DESAIN.md → Jarak. */
internal val GroupGap = 28.dp

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH.mm", ID)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", ID)
private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", ID)

internal fun shortDate(t: Long, today: LocalDate = LocalDate.now()): String {
    val at = t.toLocal()
    return when {
        at.year != today.year -> FULL.format(at)
        at.toLocalDate() != today -> DAY.format(at)
        else -> TIME.format(at)
    }
}

internal val muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val timeColor @Composable get() = MaterialTheme.colorScheme.tertiary
/** Aksen biru arah "Fokus": aksi utama, item dok aktif, baris tanggal. */
internal val accent @Composable get() = MaterialTheme.colorScheme.secondary
internal val PillShape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
internal val GroupShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color = muted, style = style, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

/**
 * Gaya tampilan untuk teks catatan: butir ☑ redup dan dicoret, format ringan ([TextFormat]), tautan berwarna, dan sorotan
 * hasil cari. Teks tidak diubah (pemetaan offset identitas), sehingga aman dipakai sebagai VisualTransformation editor.
 */
internal fun formatAnnotated(
    text: String, mutedColor: Color, linkColor: Color = mutedColor, highlight: Color? = null, matches: List<IntRange> = emptyList(),
): androidx.compose.ui.text.AnnotatedString {
    val b = androidx.compose.ui.text.AnnotatedString.Builder(text)
    val limit = minOf(text.length, com.catatanku.app.domain.usecase.TextFormat.STYLE_LIMIT)
    if ('☑' in text) {
        var pos = 0
        while (pos < limit) {
            val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            var box = pos
            while (box < end && text[box] == ' ') box++
            if (box < end && text[box] == '☑') {
                b.addStyle(androidx.compose.ui.text.SpanStyle(color = mutedColor), box, box + 1)
                if (box + 1 < end) b.addStyle(androidx.compose.ui.text.SpanStyle(color = mutedColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), box + 1, end)
            }
            pos = end + 1
        }
    }
    com.catatanku.app.domain.usecase.TextFormat.spans(text).forEach { sp ->
        val style = when (sp.kind) {
            com.catatanku.app.domain.usecase.TextFormat.Kind.Heading1 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
            com.catatanku.app.domain.usecase.TextFormat.Kind.Heading2 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
            com.catatanku.app.domain.usecase.TextFormat.Kind.Bold -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
            com.catatanku.app.domain.usecase.TextFormat.Kind.Italic -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            com.catatanku.app.domain.usecase.TextFormat.Kind.Quote -> androidx.compose.ui.text.SpanStyle(color = mutedColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            com.catatanku.app.domain.usecase.TextFormat.Kind.Link -> androidx.compose.ui.text.SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
            com.catatanku.app.domain.usecase.TextFormat.Kind.Marker -> androidx.compose.ui.text.SpanStyle(color = mutedColor.copy(alpha = 0.55f), fontWeight = FontWeight.Normal, fontSize = 15.sp)
            com.catatanku.app.domain.usecase.TextFormat.Kind.Match -> null
        }
        if (style != null) b.addStyle(style, sp.start, sp.end)
    }
    if (highlight != null) matches.forEach { r -> if (r.last < text.length) b.addStyle(androidx.compose.ui.text.SpanStyle(background = highlight), r.first, r.last + 1) }
    return b.toAnnotatedString()
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(ID), modifier, color = muted, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Medium))

@Composable
internal fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun IconAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 20.dp, enabled: Boolean = true, onClick: () -> Unit) =
    IconButton(onClick, enabled = enabled) { Icon(icon, label, Modifier.size(size), if (enabled) tint else tint.copy(alpha = 0.38f)) }

@Composable
internal fun TextAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) =
    TextButton(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = SmallShape, colors = ButtonDefaults.textButtonColors(contentColor = color), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

@Composable
internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = SmallShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = SmallShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
internal fun SmallButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    if (primary) Button(onClick, Modifier.heightIn(min = 36.dp), enabled = enabled, shape = SmallShape, contentPadding = pad) { Text(text, style = MaterialTheme.typography.labelLarge) }
    else OutlinedButton(onClick, Modifier.heightIn(min = 36.dp), enabled = enabled, shape = SmallShape, contentPadding = pad, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun Choice(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) = Box(
    Modifier.heightIn(min = 36.dp).clip(SmallShape)
        .background(
            when {
                selected && enabled -> MaterialTheme.colorScheme.primary
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
    Alignment.Center,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            enabled -> MaterialTheme.colorScheme.onSurface
            else -> muted.copy(alpha = 0.5f)
        },
    )
}

@Composable
internal fun Badge(text: String, strong: Boolean = false) = Text(
    text,
    Modifier.clip(RoundedShape4).then(
        if (strong) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedShape4)
    ).padding(horizontal = 5.dp, vertical = 1.dp),
    style = MaterialTheme.typography.labelSmall,
    color = if (strong) MaterialTheme.colorScheme.onPrimary else muted,
)

private val RoundedShape4 = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)

@Composable
internal fun PlainField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge, singleLine: Boolean = true,
    ime: ImeAction = ImeAction.Default, onIme: () -> Unit = {},
) = BasicTextField(
    value, onChange, modifier,
    textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
    singleLine = singleLine,
    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ime),
    keyboardActions = KeyboardActions(onDone = { onIme() }, onSearch = { onIme() }, onSend = { onIme() }),
    decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = muted, maxLines = 1); inner() } },
)

@Composable
internal fun Confirm(title: String, text: String, ok: String, destructive: Boolean = false, onDismiss: () -> Unit, onOk: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, style = MaterialTheme.typography.titleLarge) }, text = { Text(text) },
    confirmButton = { TextAction(ok, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) { onDismiss(); onOk() } },
    dismissButton = { TextAction("Batal", onClick = onDismiss) },
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

@Composable
internal fun Menu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = DropdownMenu(
    expanded, onDismiss,
    shape = SmallShape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content = content,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 0.dp,
    dragHandle = {
        Box(Modifier.padding(top = 12.dp, bottom = 10.dp).size(width = 32.dp, height = 4.dp).clip(RoundedShape4).background(MaterialTheme.colorScheme.outlineVariant))
    },
) {
    Column(Modifier.padding(horizontal = Gutter).padding(top = 6.dp, bottom = 20.dp)) {
        Text(title, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
internal fun Notices(state: SnackbarHostState) = SnackbarHost(state) { data ->
    Snackbar(data, shape = SmallShape, actionColor = MaterialTheme.colorScheme.inversePrimary)
}

private val clipboardHandler by lazy { Handler(Looper.getMainLooper()) }
private var pendingClipClear: Runnable? = null

internal fun copyText(ctx: Context, text: String) {
    val appCtx = ctx.applicationContext
    val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Catatan", text)
    val autoClear = SecurityPrefs.isClipboardAutoClearEnabled(appCtx)
    val locked = SecurityPrefs.isLockEnabled(appCtx)
    if (Build.VERSION.SDK_INT >= 33 && (autoClear || locked)) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    pendingClipClear?.let { clipboardHandler.removeCallbacks(it) }
    if (autoClear) {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val task = Runnable {
            runCatching {
                val cur = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (cur != null) {
                    val curDigest = MessageDigest.getInstance("SHA-256").digest(cur.toByteArray(Charsets.UTF_8))
                    if (MessageDigest.isEqual(digest, curDigest)) {
                        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                        else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }
        }
        pendingClipClear = task
        clipboardHandler.postDelayed(task, 30_000L)
    }
}

internal fun reminderText(n: Note): String = listOfNotNull(
    (if (n.remindFired) "Terlewat · " else "") + whenLabel(n.remindAt),
    n.remindRepeat.takeIf { it != Repeat.None }?.label?.lowercase(),
).joinToString("  ·  ")

@Composable
internal fun SourceRow(n: Note, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clip(SmallShape).clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 8.dp)) {
        val first = n.body.take(240).trim().lineSequence().firstOrNull().orEmpty()
        Text(n.title.ifBlank { first }, style = NoteType.rowSnippet, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (n.title.isNotBlank() && first.isNotBlank()) Text(first.take(120), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
