package com.deltadroid

import android.view.KeyEvent.*

private val common = mapOf(
    "up" to KEYCODE_DPAD_UP, "down" to KEYCODE_DPAD_DOWN,
    "left" to KEYCODE_DPAD_LEFT, "right" to KEYCODE_DPAD_RIGHT,
    "start" to KEYCODE_BUTTON_START, "select" to KEYCODE_BUTTON_SELECT,
)
private val retroPad = common + mapOf(
    "a" to KEYCODE_BUTTON_A, "b" to KEYCODE_BUTTON_B, "x" to KEYCODE_BUTTON_X,
    "y" to KEYCODE_BUTTON_Y, "l" to KEYCODE_BUTTON_L1, "r" to KEYCODE_BUTTON_R1,
)

/**
 * Sistemas de Delta. `keys` traduce los nombres de input de las .deltaskin al RetroPad de libretro;
 * `color` = acento de su página en la biblioteca; `thumbs` = repo de libretro-thumbnails.
 */
enum class GameSystem(
    val label: String, val core: String, val exts: Set<String>, val keys: Map<String, Int>,
    val color: Long, private val thumbs: String,
) {
    NES("NES", "fceumm", setOf("nes", "fds"), retroPad, 0xFFE53935, "Nintendo_-_Nintendo_Entertainment_System"),
    SNES("SNES", "snes9x", setOf("sfc", "smc"), retroPad, 0xFF9575CD, "Nintendo_-_Super_Nintendo_Entertainment_System"),
    GBC("GBC", "gambatte", setOf("gb", "gbc"), retroPad, 0xFF8E24AA, "Nintendo_-_Game_Boy_Color"),
    GBA("GBA", "mgba", setOf("gba"), retroPad, 0xFF7C4DFF, "Nintendo_-_Game_Boy_Advance"),
    N64("N64", "mupen64plus_next_gles3", setOf("n64", "z64", "v64"), common + mapOf(
        "a" to KEYCODE_BUTTON_B, "b" to KEYCODE_BUTTON_Y, "z" to KEYCODE_BUTTON_L2,
        "l" to KEYCODE_BUTTON_L1, "r" to KEYCODE_BUTTON_R1,
    ), 0xFF43A047, "Nintendo_-_Nintendo_64"),
    DS("DS", "melonds", setOf("nds"), retroPad, 0xFF90A4AE, "Nintendo_-_Nintendo_DS"),
    GENESIS("Genesis", "genesis_plus_gx", setOf("md", "gen", "smd", "bin"), common + mapOf(
        "a" to KEYCODE_BUTTON_Y, "b" to KEYCODE_BUTTON_B, "c" to KEYCODE_BUTTON_A,
        "x" to KEYCODE_BUTTON_L1, "y" to KEYCODE_BUTTON_X, "z" to KEYCODE_BUTTON_R1,
    ), 0xFF1E88E5, "Sega_-_Mega_Drive_-_Genesis");

    val id get() = name.lowercase()

    /** GB y GBC comparten página (como en Delta) pero no repo de carátulas. */
    fun thumbRepo(fileName: String) =
        if (this == GBC && fileName.endsWith(".gb", true)) "Nintendo_-_Game_Boy" else thumbs

    companion object {
        fun byExt(fileName: String) = fileName.substringAfterLast('.', "").lowercase()
            .let { e -> entries.firstOrNull { e in it.exts } }

        /** "com.rileytestut.delta.game.gba" -> GBA */
        fun byDeltaIdentifier(id: String) = entries.firstOrNull { it.id == id.substringAfterLast('.') }
    }
}

/** C-buttons de N64: mupen64plus-next los lee del stick derecho. */
val C_STICK = mapOf("cUp" to (0f to -1f), "cDown" to (0f to 1f), "cLeft" to (-1f to 0f), "cRight" to (1f to 0f))

/** Direcciones de una cruceta a partir de la posición normalizada (-1..1) del dedo; permite diagonales. */
fun dpadDirections(dx: Float, dy: Float, dead: Float = 0.35f) = buildList {
    if (dx < -dead) add("left")
    if (dx > dead) add("right")
    if (dy < -dead) add("up")
    if (dy > dead) add("down")
}
