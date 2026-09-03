package io.aatricks.easyreader.ui.viewmodel

import io.aatricks.easyreader.data.model.LibraryItem
import io.aatricks.easyreader.data.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFiltersTest {

    @Test
    fun `TITLE sorts by the novel title even when chapter rows are titled by chapter only`() {
        val items = listOf(
            LibraryItem(id = "z", title = "Chapter 1", url = "https://s/zeta/1", baseTitle = "Zeta"),
            LibraryItem(id = "a", title = "Chapter 9", url = "https://s/alpha/9", baseTitle = "Alpha")
        )

        val sorted = LibraryFilters().apply(items, query = "", filter = null, sort = SortMode.TITLE)

        assertEquals(listOf("a", "z"), sorted.map { it.id })
    }
}
