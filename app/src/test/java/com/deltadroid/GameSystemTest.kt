package com.deltadroid

import org.junit.Assert.assertEquals
import org.junit.Test

class GameSystemTest {
    @Test fun detectsSystemByExtension() {
        assertEquals(GameSystem.GBA, GameSystem.byExt("Pokemon.GBA"))
        assertEquals(GameSystem.GBC, GameSystem.byExt("zelda.gb"))
        assertEquals(GameSystem.N64, GameSystem.byExt("mario.z64"))
        assertEquals(null, GameSystem.byExt("readme.txt"))
        assertEquals(GameSystem.GENESIS, GameSystem.byDeltaIdentifier("com.rileytestut.delta.game.genesis"))
    }

    @Test fun dpadAllowsDiagonalsAndDeadZone() {
        assertEquals(emptyList<String>(), dpadDirections(0.1f, -0.2f))
        assertEquals(listOf("right", "up"), dpadDirections(0.8f, -0.8f))
        assertEquals(listOf("left"), dpadDirections(-0.9f, 0.1f))
    }
}

class LibraryNamesTest {
    @Test fun cleansNoIntroTags() {
        assertEquals("Mario Kart - Super Circuit", cleanTitle("Mario Kart - Super Circuit (USA) [!]"))
        assertEquals("(Beta)", cleanTitle("(Beta)"))
    }

    @Test fun libretroThumbnailNames() {
        assertEquals("Tom _ Jerry", libretroName("Tom & Jerry"))
        assertEquals("Legend of Zelda, The - The Minish Cap (USA)", libretroName("Legend of Zelda, The - The Minish Cap (USA)"))
    }
}

class CheatCodeTest {
    @Test fun joinsMultilineCodesWithPlus() {
        assertEquals("AAAA-BBBB+CCCC-DDDD", normalizeCheatCode("  AAAA-BBBB\n\n CCCC-DDDD \n"))
        assertEquals("", normalizeCheatCode("   "))
    }
}

class ControlsAndBackupTest {
    @Test fun defaultMappingOnlyUsesButtonsTheConsoleHas() {
        val nes = Controls.defaults(GameSystem.NES).values.toSet()
        assertEquals(false, "x" in nes || "l" in nes)
        assertEquals("b", Controls.defaults(GameSystem.SNES)[android.view.KeyEvent.KEYCODE_BUTTON_A]) // abajo = B
        assertEquals("z", Controls.defaults(GameSystem.N64)[android.view.KeyEvent.KEYCODE_BUTTON_L2])
    }

    @Test fun backupRejectsPathsOutsideItsFolders() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        assertEquals(java.io.File(root, "saves/a.srm").canonicalPath, safeDest(root, "saves/a.srm")?.path)
        assertEquals(null, safeDest(root, "saves/../../etc/passwd"))
        assertEquals(null, safeDest(root, "shared_prefs/x.xml"))
        root.deleteRecursively()
    }

    @Test fun stateKindComesFromFileName() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        assertEquals(StateKind.AUTO, SaveState.read(java.io.File(dir, "auto.state").apply { writeText("x") }).kind)
        assertEquals(StateKind.MANUAL, SaveState.read(java.io.File(dir, "1700000000000.state").apply { writeText("x") }).kind)
        dir.deleteRecursively()
    }
}

class BackupRoundTripTest {
    @Test fun exportThenImportRestoresFilesAndSettings() {
        val src = kotlin.io.path.createTempDirectory().toFile()
        java.io.File(src, "saves").mkdirs(); java.io.File(src, "saves/zelda.gba.srm").writeText("SRAM")
        java.io.File(src, "states/zelda.gba").mkdirs(); java.io.File(src, "states/zelda.gba/quick.state").writeText("ST")
        java.io.File(src, "cache.tmp").writeText("no se respalda")
        val zip = java.io.ByteArrayOutputStream()
        assertEquals(2, Backup.export(src, mapOf("haptics" to false), zip))

        val dst = kotlin.io.path.createTempDirectory().toFile()
        var prefs = ""
        assertEquals(2, Backup.import(dst, zip.toByteArray().inputStream()) { prefs = it })
        assertEquals("SRAM", java.io.File(dst, "saves/zelda.gba.srm").readText())
        assertEquals("ST", java.io.File(dst, "states/zelda.gba/quick.state").readText())
        assertEquals(false, java.io.File(dst, "cache.tmp").exists())
        assertEquals(true, prefs.contains("haptics"))
        src.deleteRecursively(); dst.deleteRecursively()
    }
}
