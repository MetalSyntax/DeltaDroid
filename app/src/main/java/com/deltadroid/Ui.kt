package com.deltadroid

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** Tema oscuro M3 con el color del sistema como acento (Delta tiñe cada consola). */
@Composable
fun DeltaTheme(accent: Color, content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = accent, onPrimary = Color.White,
        secondaryContainer = accent.copy(alpha = 0.35f),
        background = Color(0xFF0E0E10), surface = Color(0xFF16161A),
        surfaceContainerLow = Color(0xFF1C1C21), surfaceContainerHigh = Color(0xFF26262C),
    ),
    content = content,
)

/** Preferencias que lee SkinView. */
object Prefs {
    fun of(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun haptics(ctx: Context) = of(ctx).getBoolean("haptics", true)
    fun opacity(ctx: Context) = of(ctx).getFloat("opacity", 0.7f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var haptics by remember { mutableStateOf(Prefs.haptics(ctx)) }
    var opacity by remember { mutableFloatStateOf(Prefs.opacity(ctx)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Ajustes", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            ListItem(
                headlineContent = { Text("Vibración al pulsar") },
                trailingContent = {
                    Switch(haptics, { haptics = it; Prefs.of(ctx).edit { putBoolean("haptics", it) } })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Text("Opacidad del control (horizontal): ${(opacity * 100).toInt()} %", Modifier.padding(start = 16.dp, top = 8.dp))
            Slider(opacity, { opacity = it }, valueRange = 0.2f..1f,
                onValueChangeFinished = { Prefs.of(ctx).edit { putFloat("opacity", opacity) } },
                modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "Skins: importa un archivo .deltaskin con «+» y reemplaza la de su consola.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

enum class PauseAction(val label: String, val icon: ImageVector) {
    SAVE("Guardar estado", Icons.Outlined.Save),
    LOAD("Cargar estado", Icons.Outlined.Restore),
    FAST_FORWARD("Avance rápido", Icons.Outlined.FastForward),
    RESET("Reiniciar", Icons.Outlined.RestartAlt),
    SCREENSHOT("Captura", Icons.Outlined.PhotoCamera),
    CHEATS("Trucos", Icons.Outlined.Code),
    SUSTAIN("Mantener botones", Icons.Outlined.PanTool),
    MUTE("Silenciar", Icons.AutoMirrored.Outlined.VolumeOff),
    EXIT("Salir", Icons.Outlined.Home),
}

/** Menú de pausa (equivalente al de Delta) como hoja inferior M3. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseSheet(title: String, subtitle: String, toggled: Set<PauseAction>, onAction: (PauseAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            // 2 filas de 4; las opciones activadas (avance rápido, mantener, silencio) se resaltan
            PauseAction.entries.filter { it != PauseAction.EXIT }.chunked(4).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { a ->
                    val on = a in toggled
                    Surface(
                        onClick = { onAction(a) },
                        shape = MaterialTheme.shapes.large,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        Column(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(a.icon, null)
                            Spacer(Modifier.height(8.dp))
                            Text(a.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ onAction(PauseAction.EXIT) }, Modifier.weight(1f)) {
                    Icon(PauseAction.EXIT.icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(PauseAction.EXIT.label)
                }
                Button(onDismiss, Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reanudar")
                }
            }
        }
    }
}
