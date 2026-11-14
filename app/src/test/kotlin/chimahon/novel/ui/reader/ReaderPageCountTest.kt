package chimahon.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderPageCountTest {

    // Shaped like the split-spine test book: tiny front matter, two big text files, tiny back matter.
    private val chars = listOf(0, 20, 150, 10, 74_000, 62_000, 120, 300, 900)

    private fun pages(
        currentIndex: Int,
        currentPage: Int,
        currentTotal: Int,
        exact: Map<Int, Int> = emptyMap(),
        charsPerPage: Double? = 600.0,
        imageOnly: Set<Int> = setOf(0),
    ) = computeBookPages(
        sectionCount = chars.size,
        currentIndex = currentIndex,
        currentPage = currentPage,
        currentTotal = currentTotal,
        exactCounts = exact,
        sectionChars = { chars[it] },
        isImageOnly = { it in imageOnly },
        charsPerPage = charsPerPage,
    )

    @Test
    fun `estimates other sections and keeps the live section exact`() {
        // Front: cover 1 + ceil(20/600)=1 + 1 + 1 = 4 pages before section 4.
        val pos = pages(currentIndex = 4, currentPage = 50, currentTotal = 125)!!
        pos.page shouldBe 54
        // 4 + 125 + ceil(62000/600)=104 + 1 + 1 + ceil(900/600)=2
        pos.total shouldBe 237
        pos.exact shouldBe false
    }

    @Test
    fun `position does not reset when crossing into the next text file`() {
        // The ratio measured on the first file carries over when estimating it from the second.
        val ratio = measureCharsPerPage(chars = 74_000, pages = 125)
        val endOfFirst = pages(currentIndex = 4, currentPage = 125, currentTotal = 125, charsPerPage = ratio)!!
        val startOfSecond = pages(currentIndex = 5, currentPage = 1, currentTotal = 104, charsPerPage = ratio)!!
        startOfSecond.page shouldBe endOfFirst.page + 1
    }

    @Test
    fun `exact counts replace estimates and mark the result exact when complete`() {
        val exact = mapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1, 5 to 110, 6 to 1, 7 to 1, 8 to 2)
        val pos = pages(currentIndex = 4, currentPage = 10, currentTotal = 125, exact = exact)!!
        pos.page shouldBe 14
        pos.total shouldBe 4 + 125 + 110 + 1 + 1 + 2
        pos.exact shouldBe true
    }

    @Test
    fun `unknown until the current section reports or a ratio exists`() {
        pages(currentIndex = 4, currentPage = 0, currentTotal = 0) shouldBe null
        pages(currentIndex = 1, currentPage = 1, currentTotal = 1, charsPerPage = null) shouldBe null
    }

    @Test
    fun `ratio needs a section with enough pages and text`() {
        measureCharsPerPage(chars = 150, pages = 1) shouldBe null
        measureCharsPerPage(chars = 74_000, pages = 2) shouldBe null
        measureCharsPerPage(chars = 74_000, pages = 125) shouldBe 74_000 / 124.5
    }
}
