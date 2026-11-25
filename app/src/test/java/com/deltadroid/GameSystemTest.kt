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
