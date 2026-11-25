package com.deltadroid

import android.content.Context
import android.view.KeyEvent.*
import org.json.JSONObject

/** Mapeo de botones físicos (mando o teclado) -> input de la skin o acción especial, por sistema (como Delta). */
object Controls {
    val ACTIONS = linkedMapOf(
        "menu" to "Menú de pausa", "quickSave" to "Guardado rápido",
        "quickLoad" to "Carga rápida", "fastForward" to "Avance rápido",
    )
    private val NAMES = mapOf("up" to "Arriba", "down" to "Abajo", "left" to "Izquierda", "right" to "Derecha",
        "start" to "Start", "select" to "Select", "cUp" to "C ↑", "cDown" to "C ↓", "cLeft" to "C ←", "cRight" to "C →")

    fun inputs(s: GameSystem): List<String> =
        s.keys.keys.filter { it in usedBy(s) } + (if (s == GameSystem.N64) C_STICK.keys.toList() else emptyList()) + ACTIONS.keys

    fun label(name: String) = ACTIONS[name] ?: NAMES[name] ?: name.uppercase()

    /** Botones que existen en la consola (retroPad trae x/y/l/r también para NES/GB). */
    private fun usedBy(s: GameSystem) = when (s) {
        GameSystem.NES, GameSystem.GBC -> setOf("up", "down", "left", "right", "start", "select", "a", "b")
        GameSystem.GBA -> setOf("up", "down", "left", "right", "start", "select", "a", "b", "l", "r")
        else -> s.keys.keys
    }

    /** Por posición, estilo Nintendo: abajo = B, derecha = A, izquierda = Y, arriba = X. */
    fun defaults(s: GameSystem): Map<Int, String> {
        val m = mutableMapOf(
            KEYCODE_DPAD_UP to "up", KEYCODE_DPAD_DOWN to "down", KEYCODE_DPAD_LEFT to "left", KEYCODE_DPAD_RIGHT to "right",
            KEYCODE_BUTTON_START to "start", KEYCODE_BUTTON_SELECT to "select", KEYCODE_BUTTON_MODE to "menu",
            KEYCODE_BUTTON_L1 to "l", KEYCODE_BUTTON_R1 to "r",
            // teclado
            KEYCODE_ENTER to "start", KEYCODE_SHIFT_RIGHT to "select", KEYCODE_ESCAPE to "menu",
            KEYCODE_Z to "b", KEYCODE_X to "a", KEYCODE_A to "y", KEYCODE_S to "x", KEYCODE_Q to "l", KEYCODE_W to "r",
            KEYCODE_F1 to "quickSave", KEYCODE_F4 to "quickLoad", KEYCODE_TAB to "fastForward",
        )
        m += when (s) {
            GameSystem.N64 -> mapOf(KEYCODE_BUTTON_A to "a", KEYCODE_BUTTON_X to "b", KEYCODE_BUTTON_L2 to "z",
                KEYCODE_Z to "a", KEYCODE_X to "b", KEYCODE_A to "z")
            GameSystem.GENESIS -> mapOf(KEYCODE_BUTTON_X to "a", KEYCODE_BUTTON_A to "b", KEYCODE_BUTTON_B to "c",
                KEYCODE_BUTTON_L1 to "x", KEYCODE_BUTTON_Y to "y", KEYCODE_BUTTON_R1 to "z",
                KEYCODE_A to "a", KEYCODE_Z to "b", KEYCODE_X to "c")
            else -> mapOf(KEYCODE_BUTTON_A to "b", KEYCODE_BUTTON_B to "a", KEYCODE_BUTTON_X to "y", KEYCODE_BUTTON_Y to "x")
        }
        val valid = inputs(s).toSet()
        return m.filterValues { it in valid }
    }

    fun load(ctx: Context, s: GameSystem): Map<Int, String> {
        val raw = Prefs.of(ctx).getString("controls_${s.id}", null) ?: return defaults(s)
        val o = JSONObject(raw)
        return o.keys().asSequence().associate { it.toInt() to o.getString(it) }
    }

    fun save(ctx: Context, s: GameSystem, map: Map<Int, String>?) = Prefs.of(ctx).edit().apply {
        if (map == null) remove("controls_${s.id}")
        else putString("controls_${s.id}", JSONObject(map.mapKeys { it.key.toString() }).toString())
    }.apply()

    fun keyName(code: Int): String {
        val raw = keyCodeToString(code).removePrefix("KEYCODE_")
        return when {
            raw.startsWith("BUTTON_") -> "Mando " + raw.removePrefix("BUTTON_").replace('_', ' ')
            raw.startsWith("DPAD_") -> "Cruceta " + raw.removePrefix("DPAD_").replace('_', ' ')
            else -> "Tecla " + raw.replace('_', ' ')
        }
    }
}
