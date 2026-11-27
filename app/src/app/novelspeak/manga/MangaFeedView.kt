package app.novelspeak.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/**
 * Continuous vertical manga feed.
 *
 * Pages are stacked in one scroll surface, like a webtoon or a chapter viewed in a manga app.
 * Only pages near the viewport hold a decoded bitmap; the rest are placeholders that are filled
 * as the reader scrolls. When TTS advances to a bubble the feed scrolls that bubble into view and
 * highlights its OCR box on the page, so speech, image and text always agree.
 *
 * The feed deliberately owns vertical scrolling. [MangaPageView] only takes the gesture when the
 * page is zoomed in; at fit-width it lets the feed scroll normally.
 */
class MangaFeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    private val main = Handler(Looper.getMainLooper())
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private val pageViews = ArrayList<MangaPageView>()
    private val ratios = ArrayList<Float>()
    private val blocks = ArrayList<List<OcrBlock>>()
    private val imageSizes = ArrayList<Pair<Int, Int>?>()
    private val loading = HashSet<Int>()
    private val loaders = Executors.newFixedThreadPool(2)

    private var sources: List<MangaPageSource> = emptyList()
    private var loader: MangaPageLoader? = null
    private var generation = 0
    private var activePage = -1
    private var activeBlock = -1
    private var activeBlocks: List<Int> = emptyList()
    private var boxesVisible = true
    private var lastVisiblePage = -1
    private var userTouching = false
    private var followPausedUntil = 0L
    private var animator: android.animation.ValueAnimator? = null
    private var lastWidth = 0

    var onBlockTap: ((pageIndex: Int, blockIndex: Int) -> Unit)? = null
    var onVisiblePageChanged: ((pageIndex: Int) -> Unit)? = null

    init {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        setBackgroundColor(Color.BLACK)
        addView(column)
    }

    fun setPages(pages: List<MangaPageSource>, pageLoader: MangaPageLoader) {
        generation++
        animator?.cancel()
        loading.clear()
        sources = pages
        loader = pageLoader
        pageViews.clear()
        ratios.clear()
        blocks.clear()
        imageSizes.clear()
        column.removeAllViews()
        activePage = -1
        activeBlock = -1
        lastVisiblePage = -1
        for (i in pages.indices) {
            val view = MangaPageView(context).apply {
                setFeedMode(true)
                setPlaceholder("Page ${i + 1}")
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, pageHeightFor(DEFAULT_RATIO)
                ).apply {
                    topMargin = dp(if (i == 0) 0 else PAGE_GAP_DP)
                }
                onBlockTap = { blockIndex -> this@MangaFeedView.onBlockTap?.invoke(i, blockIndex) }
            }
            ratios.add(DEFAULT_RATIO)
            blocks.add(emptyList())
            imageSizes.add(null)
            pageViews.add(view)
            column.addView(view)
        }
        updateVisiblePages()
    }

    fun setBoxesVisible(visible: Boolean) {
        boxesVisible = visible
        for (view in pageViews) view.setBoxesVisible(visible)
    }

    /** A page result arrived: update boxes, ordering and the real aspect ratio. */
    fun setPageResult(pageIndex: Int, result: MangaSession.PageResult) {
        if (pageIndex !in pageViews.indices) return
        blocks[pageIndex] = result.blocks
        pageViews[pageIndex].setBlocks(
            result.blocks,
            if (pageIndex == activePage) activeBlock else -1
        )
        if (pageIndex == activePage) {
            pageViews[pageIndex].setActiveBlocks(activeBlocks, autoPan = false)
        }
        setPageSize(pageIndex, result.width, result.height)
    }

    /** The session already decoded this page; reuse it instead of downloading it twice. */
    fun setPageImage(pageIndex: Int, bitmap: Bitmap) {
        if (pageIndex !in pageViews.indices) return
        setPageSize(pageIndex, bitmap.width, bitmap.height)
        if (isNearViewport(pageIndex) || pageIndex == activePage) {
            pageViews[pageIndex].setPlaceholder("")
            pageViews[pageIndex].setPage(
                bitmap,
                blocks[pageIndex],
                if (pageIndex == activePage) activeBlock else -1,
                boxesVisible
            )
            if (pageIndex == activePage) {
                pageViews[pageIndex].setActiveBlocks(activeBlocks, autoPan = false)
            }
        }
    }

    /** Resume auto-follow after an exploratory scroll or an explicit jump. */
    fun resumeFollowing() {
        followPausedUntil = 0L
    }

    fun setActive(pageIndex: Int, blockIndex: Int, scroll: Boolean = true) {
        setActive(pageIndex, listOf(blockIndex), scroll)
    }

    /** Highlights every bubble covered by a merged speech chunk. */
    fun setActive(pageIndex: Int, blockIndices: List<Int>, scroll: Boolean = true) {
        if (pageIndex !in pageViews.indices) return
        val previous = activePage
        activePage = pageIndex
        activeBlocks = blockIndices
        activeBlock = blockIndices.firstOrNull() ?: -1
        if (previous in pageViews.indices && previous != pageIndex) {
            pageViews[previous].setActiveBlocks(emptyList(), autoPan = false)
        }
        pageViews[pageIndex].setActiveBlocks(blockIndices, autoPan = false)
        ensureLoaded(pageIndex, force = true)
        if (scroll && SystemClock.uptimeMillis() >= followPausedUntil) {
            scrollToBlock(pageIndex, activeBlock)
        }
        updateVisiblePages()
    }

    /** Scrolls to a page even when playback is not on it (manual page navigation). */
    fun scrollToPage(pageIndex: Int, smooth: Boolean = true) {
        if (pageIndex !in pageViews.indices) return
        ensureLoaded(pageIndex, force = true)
        updateVisiblePages()
        val target = pageViews[pageIndex].top.coerceAtLeast(0)
        if (smooth) animateScroll(target) else scrollTo(0, target)
    }

    fun currentPage(): Int {
        if (pageViews.isEmpty()) return -1
        val pivot = scrollY + height * 0.3f
        for (i in pageViews.indices) {
            val view = pageViews[i]
            if (view.bottom.toFloat() > pivot) return i
        }
        return pageViews.size - 1
    }

    fun pageCount(): Int = pageViews.size

    /** Stops background page loads when the reader is leaving. */
    fun release() {
        generation++
        animator?.cancel()
        animator = null
        loaders.shutdownNow()
        column.removeAllViews()
        pageViews.clear()
    }

    private fun setPageSize(pageIndex: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val newRatio = height.toFloat() / width.toFloat()
        val oldRatio = ratios[pageIndex]
        if (kotlin.math.abs(newRatio - oldRatio) < 0.01f) {
            imageSizes[pageIndex] = width to height
            return
        }
        val oldHeight = pageViews[pageIndex].height
        val visible = currentPage()
        ratios[pageIndex] = newRatio
        imageSizes[pageIndex] = width to height
        pageViews[pageIndex].layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            pageHeightFor(newRatio)
        ).apply {
            topMargin = dp(if (pageIndex == 0) 0 else PAGE_GAP_DP)
        }
        pageViews[pageIndex].requestLayout()
        // Keep the page the reader is looking at still while pages above it correct their height.
        if (pageIndex < visible) {
            main.post {
                val delta = pageViews[pageIndex].height - oldHeight
                if (delta != 0 && scrollY + delta >= 0) scrollBy(0, delta)
            }
        }
    }

    private fun pageHeightFor(ratio: Float): Int {
        val width = contentWidth()
        if (width <= 0) return 1
        return (width * ratio).toInt().coerceAtLeast(1)
    }

    private fun contentWidth(): Int {
        val w = width - paddingLeft - paddingRight
        return if (w > 0) w else resources.displayMetrics.widthPixels
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidth && pageViews.isNotEmpty()) {
            lastWidth = w
            for (i in pageViews.indices) {
                pageViews[i].layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    pageHeightFor(ratios[i])
                ).apply {
                    topMargin = dp(if (i == 0) 0 else PAGE_GAP_DP)
                }
            }
        }
        updateVisiblePages()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        updateVisiblePages()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                userTouching = true
                // A deliberate exploratory scroll should not be yanked back by the next
                // spoken bubble. Auto-follow resumes after a short pause, or immediately on
                // the next explicit page/text action.
                followPausedUntil = SystemClock.uptimeMillis() + FOLLOW_PAUSE_MS
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userTouching = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            userTouching = false
        }
        return super.onTouchEvent(ev)
    }

    private fun updateVisiblePages() {
        if (pageViews.isEmpty() || height <= 0) return
        val vh = height
        val top = scrollY
        val preloadTop = top - vh
        val preloadBottom = top + vh * 2
        val releaseTop = top - vh * 2
        val releaseBottom = top + vh * 3
        var visible = -1
        for (i in pageViews.indices) {
            val view = pageViews[i]
            if (visible < 0 && view.bottom > top + vh * 0.25f) visible = i
            if (view.bottom >= preloadTop && view.top <= preloadBottom) {
                ensureLoaded(i, force = i == activePage)
            } else if (i != activePage && (view.bottom < releaseTop || view.top > releaseBottom)) {
                detach(i)
            }
        }
        if (visible >= 0) {
            contentDescription = "Manga pages, page ${visible + 1} of ${pageViews.size}"
            if (visible != lastVisiblePage) {
                lastVisiblePage = visible
                // Only a finger scroll moves the badge; speech-driven scrolling keeps the
                // badge on the page that is actually being spoken.
                if (userTouching) onVisiblePageChanged?.invoke(visible)
            }
        }
    }

    private fun ensureLoaded(pageIndex: Int, force: Boolean) {
        if (pageIndex !in pageViews.indices) return
        if (pageViews[pageIndex].hasBitmap || pageIndex in loading) return
        if (!force && loading.size >= MAX_CONCURRENT_LOADS) return
        if (loading.size >= MAX_CONCURRENT_LOADS + 1) return
        val pageLoader = loader ?: return
        loading.add(pageIndex)
        val gen = generation
        val source = sources[pageIndex]
        loaders.execute {
            val bitmap = try {
                pageLoader.load(source)
            } catch (t: Throwable) {
                null
            }
            main.post {
                loading.remove(pageIndex)
                if (gen != generation || pageIndex !in pageViews.indices) return@post
                if (bitmap == null) {
                    pageViews[pageIndex].setPlaceholder("Page ${pageIndex + 1} unavailable")
                    return@post
                }
                setPageSize(pageIndex, bitmap.width, bitmap.height)
                if (isNearViewport(pageIndex) || pageIndex == activePage) {
                    pageViews[pageIndex].setPlaceholder("")
                    pageViews[pageIndex].setPage(
                        bitmap,
                        blocks[pageIndex],
                        if (pageIndex == activePage) activeBlock else -1,
                        boxesVisible
                    )
                    if (pageIndex == activePage) {
                        pageViews[pageIndex].setActiveBlocks(activeBlocks, autoPan = false)
                    }
                }
                // The load limit means another visible page may have been waiting.
                updateVisiblePages()
            }
        }
    }

    private fun detach(pageIndex: Int) {
        if (pageIndex in loading) return
        val view = pageViews[pageIndex]
        if (!view.hasBitmap) return
        view.setPage(
            null,
            blocks[pageIndex],
            if (pageIndex == activePage) activeBlock else -1,
            boxesVisible
        )
        view.setPlaceholder("Page ${pageIndex + 1}")
    }

    private fun isNearViewport(pageIndex: Int): Boolean {
        if (pageIndex !in pageViews.indices) return false
        val view = pageViews[pageIndex]
        return view.bottom >= scrollY - height && view.top <= scrollY + height * 2
    }

    private fun scrollToBlock(pageIndex: Int, blockIndex: Int) {
        val view = pageViews[pageIndex]
        val vh = height
        if (vh <= 0) return
        val centre = view.blockCenterY(blockIndex)
            ?: blockFractionY(pageIndex, blockIndex)?.let { (view.top + it * view.height).toFloat() }
            ?: (view.top + view.height / 2f)
        val absolute = view.top + centre
        val topEdge = scrollY + vh * 0.18f
        val bottomEdge = scrollY + vh * 0.82f
        if (absolute in topEdge..bottomEdge) return
        val target = (absolute - vh * 0.42f).toInt().coerceIn(0, maxScroll())
        animateScroll(target)
    }

    private fun blockFractionY(pageIndex: Int, blockIndex: Int): Float? {
        val box = blocks.getOrNull(pageIndex)?.getOrNull(blockIndex)?.boundingBox ?: return null
        if (box.isEmpty) return null
        val height = imageSizes.getOrNull(pageIndex)?.second ?: return null
        if (height <= 0) return null
        return (box.centerY() / height).coerceIn(0f, 1f)
    }

    private fun maxScroll(): Int = (column.height - height).coerceAtLeast(0)

    private fun animateScroll(target: Int) {
        animator?.cancel()
        val from = scrollY
        val to = target.coerceIn(0, maxScroll())
        if (from == to) return
        val distance = kotlin.math.abs(to - from)
        val duration = (240 + distance / 4).toLong().coerceIn(240L, 520L)
        val animation = android.animation.ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
        }
        animator = animation
        animation.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_RATIO = 1.5f
        const val PAGE_GAP_DP = 6
        const val MAX_CONCURRENT_LOADS = 3
        const val FOLLOW_PAUSE_MS = 6000L
    }
}
