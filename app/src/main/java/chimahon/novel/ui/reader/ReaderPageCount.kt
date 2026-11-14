package chimahon.novel.ui.reader

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max

/**
 * Everything that changes how many screen pages a section lays out to.
 * Colours, theme and tap zones are normalised away so they never invalidate counts.
 */
data class PageLayoutKey(
    val settings: ReaderSettings,
    val continuousMode: Boolean,
    val widthPx: Int,
    val heightPx: Int,
) {
    /** Stable string for the on-disk cache (bumped with [VERSION] when layout code changes). */
    fun signature(): String = "$VERSION|$continuousMode|${widthPx}x$heightPx|$settings"

    companion object {
        private const val VERSION = 1

        fun of(settings: ReaderSettings, continuousMode: Boolean, widthPx: Int, heightPx: Int): PageLayoutKey? {
            if (widthPx <= 0 || heightPx <= 0) return null
            return PageLayoutKey(
                settings = settings.copy(
                    theme = "",
                    backgroundColor = 0,
                    textColor = 0,
                    tapZonePercent = 0,
                    continuousMode = continuousMode,
                ),
                continuousMode = continuousMode,
                widthPx = widthPx,
                heightPx = heightPx,
            )
        }
    }
}

/** Exact per-section page counts for one layout, persisted next to the book. */
@Serializable
data class PageCountCache(
    val signature: String,
    val counts: Map<Int, Int>,
)

/** Whole-book page position. [exact] is false while any section is still estimated. */
data class BookPagePosition(
    val page: Int,
    val total: Int,
    val exact: Boolean,
)

/**
 * Whole-book pages from per-section counts. Each section uses, in order: the
 * live count of the section on screen, an exact background count, 1 for
 * image-only or text-less sections, else `chars / charsPerPage`.
 *
 * Returns null until the current section has reported its pages, or while an
 * estimate is needed but no chars-per-page ratio has been measured yet.
 */
internal fun computeBookPages(
    sectionCount: Int,
    currentIndex: Int,
    currentPage: Int,
    currentTotal: Int,
    exactCounts: Map<Int, Int>,
    sectionChars: (Int) -> Int,
    isImageOnly: (Int) -> Boolean,
    charsPerPage: Double?,
): BookPagePosition? {
    if (currentTotal <= 0 || currentIndex !in 0 until sectionCount) return null
    var before = 0
    var total = 0
    var exact = true
    for (i in 0 until sectionCount) {
        val pages = when {
            i == currentIndex -> currentTotal
            exactCounts[i] != null -> exactCounts.getValue(i)
            isImageOnly(i) -> 1
            else -> {
                val chars = sectionChars(i)
                if (chars > 0 && (charsPerPage == null || charsPerPage <= 0.0)) return null
                exact = false
                if (chars <= 0) 1 else max(1, ceil(chars / charsPerPage!!).toInt())
            }
        }
        if (i < currentIndex) before += pages
        total += pages
    }
    return BookPagePosition(
        page = (before + currentPage).coerceIn(1, total),
        total = total,
        exact = exact,
    )
}

/**
 * Characters per screen page measured from a laid-out section, or null when
 * the section is too small to give a meaningful ratio (title pages, notices).
 * The last page is usually partial, so it counts as half a page.
 */
internal fun measureCharsPerPage(chars: Int, pages: Int): Double? {
    if (pages < MIN_PAGES_FOR_RATIO || chars < MIN_CHARS_FOR_RATIO) return null
    return chars / (pages - 0.5)
}

private const val MIN_PAGES_FOR_RATIO = 3
private const val MIN_CHARS_FOR_RATIO = 300
