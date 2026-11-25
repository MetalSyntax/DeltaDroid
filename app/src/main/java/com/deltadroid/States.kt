package com.deltadroid

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class StateKind(val label: String) { AUTO("Automático"), QUICK("Guardado rápido"), MANUAL("Guardados") }

data class SaveState(val file: File, val name: String, val kind: StateKind, val locked: Boolean) {
    val thumb get() = File(file.parentFile, file.nameWithoutExtension + ".png")
    private val meta get() = File(file.parentFile, file.nameWithoutExtension + ".json")
    val date get() = file.lastModified()

    fun writeMeta(name: String = this.name, locked: Boolean = this.locked) =
        meta.writeText(JSONObject().put("name", name).put("kind", kind.name).put("locked", locked).toString())

    fun delete() { file.delete(); thumb.delete(); meta.delete() }

    companion object {
        fun read(f: File): SaveState {
            val m = runCatching { JSONObject(File(f.parentFile, f.nameWithoutExtension + ".json").readText()) }.getOrNull()
            val kind = when (f.nameWithoutExtension) { "auto" -> StateKind.AUTO; "quick" -> StateKind.QUICK; else -> StateKind.MANUAL }
            val fallback = if (kind == StateKind.MANUAL) "Estado guardado" else kind.label
            return SaveState(f, m?.optString("name")?.ifBlank { null } ?: fallback, kind, m?.optBoolean("locked") ?: false)
        }
    }
}

/** Estados en files/states/<rom>/: auto.state (al salir), quick.state (botón de mando) y <fecha>.state. */
object StateStore {
    fun dir(ctx: Context, rom: File) = ctx.appDir("states/${rom.name}")

    fun list(ctx: Context, rom: File) = dir(ctx, rom).listFiles { f -> f.extension == "state" }.orEmpty()
        .map(SaveState::read).sortedWith(compareBy({ it.kind.ordinal }, { -it.date }))

    /** Escribe el estado (y su miniatura) en el archivo de ese tipo; `target` sobrescribe uno existente. */
    fun write(ctx: Context, rom: File, data: ByteArray, thumb: Bitmap?, kind: StateKind, target: SaveState? = null): SaveState {
        val name = when { target != null -> target.file.nameWithoutExtension; kind == StateKind.MANUAL -> "${System.currentTimeMillis()}"; else -> kind.name.lowercase() }
        val f = File(dir(ctx, rom), "$name.state")
        f.writeBytes(data)
        val s = SaveState.read(f)
        thumb?.let { b -> s.thumb.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } } ?: s.thumb.delete()
        if (target == null) s.writeMeta(if (kind == StateKind.MANUAL) "" else kind.label)
        return SaveState.read(f)
    }
}

enum class StatesMode(val title: String) { SAVE("Guardar estado"), LOAD("Cargar estado") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatesSheet(
    mode: StatesMode,
    states: List<SaveState>,
    onNew: () -> Unit,
    onPick: (SaveState) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<SaveState?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(mode.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (mode == StatesMode.SAVE) {
                FilledTonalButton(onNew, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Nuevo estado") }
                Spacer(Modifier.height(8.dp))
            }
            // en modo guardar solo se puede sobrescribir uno manual y sin bloqueo
            val shown = if (mode == StatesMode.SAVE) states.filter { it.kind == StateKind.MANUAL } else states
            if (shown.isEmpty()) Text("No hay estados guardados.", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { it.file.path }) { s ->
                    StateRow(s, enabled = mode == StatesMode.LOAD || !s.locked, onClick = { onPick(s) }, onLock = {
                        s.writeMeta(locked = !s.locked); onChanged()
                    }, onRename = { renaming = s }, onDelete = { s.delete(); onChanged() })
                }
            }
        }
    }
    renaming?.let { s ->
        var text by remember(s) { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renombrar estado") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ s.writeMeta(name = text.trim()); renaming = null; onChanged() }) { Text("Guardar") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun StateRow(s: SaveState, enabled: Boolean, onClick: () -> Unit, onLock: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (s.thumb.exists()) AsyncImage(s.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleSmall)
            Text("${s.kind.label} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.date))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.locked) Icon(Icons.Default.Lock, "Bloqueado", tint = MaterialTheme.colorScheme.primary)
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (s.locked) "Desbloquear" else "Bloquear") }, { menu = false; onLock() })
                DropdownMenuItem({ Text("Renombrar") }, { menu = false; onRename() })
                DropdownMenuItem({ Text("Borrar") }, { menu = false; onDelete() }, enabled = !s.locked)
            }
        }
    }
}
