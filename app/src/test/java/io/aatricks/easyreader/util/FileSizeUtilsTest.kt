package io.aatricks.easyreader.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileSizeUtilsTest {

    @Test
    fun `stale temp files are swept while an in-flight one survives`() {
        val dir = Files.createTempDirectory("file-size-utils-test").toFile()
        val twoHoursAgo = System.currentTimeMillis() - 2L * 60L * 60L * 1000L
        val stale = File(dir, "image.jpg.0d1f.tmp").apply {
            writeText("interrupted download")
            setLastModified(twoHoursAgo)
        }
        val inFlight = File(dir, "image.jpg.9ab2.tmp").apply { writeText("still writing") }
        val payload = File(dir, "image.jpg").apply { writeText("done") }

        FileSizeUtils.deleteStaleTempFiles(dir)

        assertFalse(stale.exists())
        assertTrue(inFlight.exists())
        assertTrue(payload.exists())
    }
}
