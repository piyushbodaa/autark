package com.piyushbodaa.autark.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModelChecksumTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun acceptsMatchingSha256() {
        val file = temporaryFolder.newFile().apply { writeText("abc") }
        assertTrue(file.matchesSha256(ABC_SHA256))
    }

    @Test fun rejectsCorruptionEvenWhenSizeMatches() {
        val file = temporaryFolder.newFile().apply { writeText("abd") }
        assertTrue(file.length() == 3L)
        assertFalse(file.matchesSha256(ABC_SHA256))
    }

    @Test fun hashesAcrossReadBuffers() {
        val file = temporaryFolder.newFile().apply { writeBytes(ByteArray(100_000) { 'a'.code.toByte() }) }
        assertTrue(file.matchesSha256("6d1cf22d7cc09b085dfc25ee1a1f3ae0265804c607bc2074ad253bcc82fd81ee"))
    }

    @Test(expected = IOException::class) fun reportsUnreadableFile() {
        File(temporaryFolder.root, "missing.gguf").matchesSha256(ABC_SHA256)
    }

    companion object {
        private const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
