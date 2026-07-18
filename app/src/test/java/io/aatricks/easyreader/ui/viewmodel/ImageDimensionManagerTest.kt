package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.local.ImageDimensionDao
import io.aatricks.easyreader.data.model.ChapterContent
import io.aatricks.easyreader.data.model.ContentElement
import io.aatricks.easyreader.data.model.ImageDimensionEntity
import io.aatricks.easyreader.data.repository.ImageDimensionCacheRepository
import io.aatricks.easyreader.testutil.fakeImageDimensionCacheRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageDimensionManagerTest {

    @Test
    fun `persist stores dimensions and applies content rebuild once`() = runTest {
        val batches = mutableListOf<Map<String, Pair<Int, Int>>>()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) { batches += it }

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()

        assertEquals(800 to 1200, manager.dimensionState("http://img/1.jpg").value)
        assertEquals(listOf(mapOf("http://img/1.jpg" to (800 to 1200))), batches)
    }

    @Test
    fun `dimensionState returns the same observable instance and tracks persists`() = runTest {
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) {}

        val state = manager.dimensionState("http://img/1.jpg")
        assertSame(state, manager.dimensionState("http://img/1.jpg"))
        assertNull(state.value)

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()

        assertEquals(800 to 1200, state.value)
    }

    @Test
    fun `duplicate persist of identical dimensions is a no-op`() = runTest {
        val batches = mutableListOf<Map<String, Pair<Int, Int>>>()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) { batches += it }

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()
        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()

        assertEquals(1, batches.size)
    }

    @Test
    fun `changed dimensions for the same url go through`() = runTest {
        val batches = mutableListOf<Map<String, Pair<Int, Int>>>()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) { batches += it }

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()
        manager.persistImageDimensions("http://img/1.jpg", 900, 1600)
        advanceUntilIdle()

        assertEquals(900 to 1600, manager.dimensionState("http://img/1.jpg").value)
        assertEquals(2, batches.size)
        assertEquals(mapOf("http://img/1.jpg" to (900 to 1600)), batches[1])
    }

    @Test
    fun `pruneForChapter drops stale urls and keeps current ones`() = runTest {
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) {}

        manager.persistImageDimensions("http://old/1.jpg", 800, 1200)
        manager.persistImageDimensions("http://kept/2.jpg", 900, 1400)
        advanceUntilIdle()

        manager.pruneForChapter(setOf("http://kept/2.jpg", "http://new/3.jpg"))

        assertNull(manager.dimensionState("http://old/1.jpg").value)
        assertEquals(900 to 1400, manager.dimensionState("http://kept/2.jpg").value)
    }

    @Test
    fun `pruneForChapter reschedules content apply only for surviving urls`() = runTest {
        val batches = mutableListOf<Map<String, Pair<Int, Int>>>()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) { batches += it }

        manager.persistImageDimensions("http://old/1.jpg", 800, 1200)
        manager.persistImageDimensions("http://kept/2.jpg", 900, 1400)
        manager.pruneForChapter(setOf("http://kept/2.jpg"))
        advanceUntilIdle()

        assertEquals(listOf(mapOf("http://kept/2.jpg" to (900 to 1400))), batches)
    }

    @Test
    fun `pruneForChapter re-enqueues surviving dims for the content rebuild`() = runTest {
        val batches = mutableListOf<Map<String, Pair<Int, Int>>>()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) { batches += it }

        manager.persistImageDimensions("http://kept/2.jpg", 900, 1400)
        advanceUntilIdle()
        assertEquals(1, batches.size)

        manager.pruneForChapter(setOf("http://kept/2.jpg"))
        advanceUntilIdle()

        assertEquals(2, batches.size)
        assertEquals(mapOf("http://kept/2.jpg" to (900 to 1400)), batches[1])
    }

    @Test
    fun `failed db flush re-queues the batch and retries on the next flush`() = runTest {
        val dao = FlakyImageDimensionDao()
        val manager = ImageDimensionManager(this, ImageDimensionCacheRepository(dao)) {}

        manager.persistImageDimensions("http://img/1.jpg", 800, 1200)
        advanceUntilIdle()
        assertTrue(dao.upserted.isEmpty())

        dao.failWrites = false
        manager.persistImageDimensions("http://img/2.jpg", 700, 1000)
        advanceUntilIdle()

        assertEquals(setOf("http://img/1.jpg", "http://img/2.jpg"), dao.upserted.keys)
    }

    @Test
    fun `blank url and non-positive dimensions are rejected`() = runTest {
        val batches = mutableListOf<Map<String, Pair<Int, Int>>>()
        val manager = ImageDimensionManager(this, fakeImageDimensionCacheRepository()) { batches += it }

        manager.persistImageDimensions("", 800, 1200)
        manager.persistImageDimensions("http://img/1.jpg", 0, 1200)
        manager.persistImageDimensions("http://img/1.jpg", 800, -1)
        advanceUntilIdle()

        assertNull(manager.dimensionState("http://img/1.jpg").value)
        assertEquals(0, batches.size)
    }

    @Test
    fun `content transform updates images without rebuilding text elements`() {
        val text = ContentElement.Text("Paragraph")
        val image = ContentElement.Image("http://img/1.jpg")
        val content = ChapterContent(paragraphs = listOf(text, image), url = "http://chapter/1")

        val updated = applyResolvedImageDimensions(
            content,
            mapOf(image.url to (800 to 12_000))
        )

        assertSame(text, updated.paragraphs[0])
        assertEquals(
            ContentElement.Image("http://img/1.jpg", width = 800, height = 12_000),
            updated.paragraphs[1]
        )
    }

    private class FlakyImageDimensionDao : ImageDimensionDao {
        var failWrites = true
        val upserted = mutableMapOf<String, ImageDimensionEntity>()

        override suspend fun getMany(urls: List<String>, parserVersion: Int): List<ImageDimensionEntity> =
            emptyList()

        override suspend fun upsert(entity: ImageDimensionEntity) {
            upserted[entity.imageUrl] = entity
        }

        override suspend fun upsertAll(entities: List<ImageDimensionEntity>) {
            if (failWrites) throw RuntimeException("disk full")
            entities.forEach { upserted[it.imageUrl] = it }
        }

        override suspend fun prune(cutoffMs: Long, currentParserVersion: Int) = Unit
    }
}
