package chimahon.novel.ui.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The reader's own text selection, in window pixels.
 *
 * [start] and [end] are the boxes of the first and last selected characters
 * (where the handles attach) and [bounds] encloses the whole selection.
 */
data class TextSelectionState(
    val text: String,
    val vertical: Boolean,
    val start: Rect?,
    val end: Rect?,
    val bounds: Rect?,
)

/** Which end of the selection a drag moves. */
enum class SelectionDragTarget(val js: String) {
    /** The long-pressing finger: the first word stays selected, the other end follows. */
    FOCUS("focus"),
    START("start"),
    END("end"),
}

/** Implemented by the reader WebView, which owns the selection in the page. */
internal interface TextSelectionHost {
    fun extendSelection(target: SelectionDragTarget, windowX: Float, windowY: Float)
    fun clearSelection()
    fun lookUpSelection()
}

/**
 * Shared state between the reader WebView (which starts a selection on long
 * press and keeps it in the page) and [ReaderTextSelectionOverlay] (which
 * draws handles and the action bar). Replaces Chromium's native selection,
 * whose magnifier is unusable with vertical text.
 */
class ReaderTextSelectionController {
    var state by mutableStateOf<TextSelectionState?>(null)
        internal set

    /** The end being dragged, or null when no drag is in progress. */
    var dragging by mutableStateOf<SelectionDragTarget?>(null)
        internal set

    /** Set when a selection starts: whether the long press was on the lower half, so the bar avoids the finger. */
    internal var barAtTopHint = false

    internal var host: TextSelectionHost? = null

    internal fun dragTo(target: SelectionDragTarget, window: Offset) {
        dragging = target
        host?.extendSelection(target, window.x, window.y)
    }

    internal fun endDrag() {
        dragging = null
    }

    fun clear() {
        dragging = null
        state = null
        host?.clearSelection()
    }

    internal fun lookUp() {
        dragging = null
        state = null
        host?.lookUpSelection()
    }
}

private const val CLAUDE_PACKAGE = "com.anthropic.claude"

private data class SelectionAppAction(
    val label: String,
    val icon: ImageBitmap?,
    val intent: Intent,
)

/** Apps offering "process text" actions (translators such as DeepL), plus a direct share to Claude. */
private fun queryAppActions(context: Context): Pair<SelectionAppAction?, List<SelectionAppAction>> {
    val pm = context.packageManager
    val iconPx = (32 * context.resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    val claude = pm.queryIntentActivities(
        Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(CLAUDE_PACKAGE),
        0,
    ).firstOrNull()?.let { info ->
        SelectionAppAction(
            label = pm.getApplicationLabel(info.activityInfo.applicationInfo).toString(),
            icon = runCatching { info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull(),
            intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .setComponent(ComponentName(info.activityInfo.packageName, info.activityInfo.name)),
        )
    }

    @Suppress("DEPRECATION")
    val processText = pm.queryIntentActivities(
        Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
        0,
    ).filter { it.activityInfo.packageName != context.packageName }
        .map { info ->
            SelectionAppAction(
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull(),
                intent = Intent(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .setComponent(ComponentName(info.activityInfo.packageName, info.activityInfo.name))
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
            )
        }
        .sortedBy { it.label.lowercase() }

    return claude to processText
}

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "App not available", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, "App not available", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Handles and action bar for the reader's text selection.
 *
 * The bar is docked to the top or bottom edge, on the side away from the
 * finger, and previews the selected text in large print while a drag is in
 * progress: unlike a magnifier it shows exactly which characters are
 * selected, reads the same for vertical and horizontal text, and only
 * redraws when the selection changes (which suits e-ink).
 */
@Composable
fun ReaderTextSelectionOverlay(
    controller: ReaderTextSelectionController,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val state = controller.state ?: return
    val context = LocalContext.current

    BackHandler { controller.clear() }

    val appActions by produceState<Pair<SelectionAppAction?, List<SelectionAppAction>>>(null to emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching { queryAppActions(context) }.getOrDefault(null to emptyList()) }
    }

    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var barBounds by remember { mutableStateOf<Rect?>(null) }
    // The bar starts on the half of the screen away from the long press, and
    // only moves when the finished selection would sit underneath it. (This
    // composable leaves composition when the selection clears, so each new
    // selection starts from the hint again.)
    var barAtTop by remember { mutableStateOf(controller.barAtTopHint) }
    LaunchedEffect(controller.dragging, state.bounds, barBounds) {
        val bar = barBounds ?: return@LaunchedEffect
        val selection = state.bounds ?: return@LaunchedEffect
        if (controller.dragging == null && bar.overlaps(selection)) {
            val c = coordinates ?: return@LaunchedEffect
            val center = c.windowToLocal(selection.center)
            barAtTop = center.y > c.size.height / 2f
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates = it },
    ) {
        val c = coordinates
        if (c != null && c.isAttached) {
            SelectionHandles(controller, state, c, contentColor)
        }

        SelectionBar(
            state = state,
            dragging = controller.dragging,
            backgroundColor = backgroundColor,
            contentColor = contentColor,
            claude = appActions.first,
            processTextApps = appActions.second,
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", state.text))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
                controller.clear()
            },
            onLookUp = { controller.lookUp() },
            onAppAction = { action ->
                val intent = Intent(action.intent)
                if (intent.action == Intent.ACTION_PROCESS_TEXT) {
                    intent.putExtra(Intent.EXTRA_PROCESS_TEXT, state.text)
                } else {
                    intent.putExtra(Intent.EXTRA_TEXT, state.text)
                }
                context.startSafely(intent)
                controller.clear()
            },
            onShare = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, state.text)
                context.startSafely(Intent.createChooser(send, null))
                controller.clear()
            },
            modifier = Modifier
                .align(if (barAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .windowInsetsPadding(if (barAtTop) WindowInsets.statusBars else WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .onGloballyPositioned { barBounds = it.boundsInWindow() },
        )
    }
}

private val KnobRadius = 9.dp
private val HandleTouchSize = 44.dp
private val StemWidth = 2.dp

/** Point (in overlay coordinates) where a handle's knob is centred. */
private fun knobCenter(rect: Rect, vertical: Boolean, isStart: Boolean, knobOffsetPx: Float): Offset = when {
    vertical && isStart -> Offset(rect.center.x, rect.top - knobOffsetPx)
    vertical -> Offset(rect.center.x, rect.bottom + knobOffsetPx)
    isStart -> Offset(rect.left, rect.bottom + knobOffsetPx)
    else -> Offset(rect.right, rect.bottom + knobOffsetPx)
}

@Composable
private fun SelectionHandles(
    controller: ReaderTextSelectionController,
    state: TextSelectionState,
    coordinates: LayoutCoordinates,
    color: Color,
) {
    val density = LocalDensity.current
    val knobRadiusPx = with(density) { KnobRadius.toPx() }
    val stemPx = with(density) { StemWidth.toPx() }
    val touchPx = with(density) { HandleTouchSize.toPx() }

    fun toLocal(rect: Rect?): Rect? = rect?.let {
        Rect(coordinates.windowToLocal(it.topLeft), coordinates.windowToLocal(it.bottomRight))
    }
    val startRect = toLocal(state.start)
    val endRect = toLocal(state.end)

    Canvas(modifier = Modifier.fillMaxSize()) {
        for ((rect, isStart) in listOf(startRect to true, endRect to false)) {
            if (rect == null) continue
            val knob = knobCenter(rect, state.vertical, isStart, knobRadiusPx + stemPx)
            // The stem runs along the character edge the selection ends at.
            val (from, to) = when {
                state.vertical && isStart -> Offset(rect.left, rect.top) to Offset(rect.right, rect.top)
                state.vertical -> Offset(rect.left, rect.bottom) to Offset(rect.right, rect.bottom)
                isStart -> Offset(rect.left, rect.top) to Offset(rect.left, rect.bottom)
                else -> Offset(rect.right, rect.top) to Offset(rect.right, rect.bottom)
            }
            drawLine(color, from, to, strokeWidth = stemPx)
            drawCircle(color, radius = knobRadiusPx, center = knob)
        }
    }

    for ((rect, target) in listOf(startRect to SelectionDragTarget.START, endRect to SelectionDragTarget.END)) {
        if (rect == null) continue
        HandleTouchTarget(
            controller = controller,
            target = target,
            charRect = rect,
            knob = knobCenter(rect, state.vertical, target == SelectionDragTarget.START, knobRadiusPx + stemPx),
            coordinates = coordinates,
            touchPx = touchPx,
        )
    }
}

/**
 * Invisible drag area over a handle's knob. It stays where the drag began
 * (so pointer positions are not distorted by the handle moving under the
 * finger) and drags a point that starts at the centre of the end character,
 * so the finger never covers the character being selected.
 */
@Composable
private fun HandleTouchTarget(
    controller: ReaderTextSelectionController,
    target: SelectionDragTarget,
    charRect: Rect,
    knob: Offset,
    coordinates: LayoutCoordinates,
    touchPx: Float,
) {
    var frozenKnob by remember { mutableStateOf<Offset?>(null) }
    val position = frozenKnob ?: knob
    val currentCharRect by rememberUpdatedState(charRect)
    val currentKnob by rememberUpdatedState(knob)
    val currentCoordinates by rememberUpdatedState(coordinates)
    val touchDp = with(LocalDensity.current) { touchPx.toDp() }

    Box(
        modifier = Modifier
            .offset { IntOffset((position.x - touchPx / 2).roundToInt(), (position.y - touchPx / 2).roundToInt()) }
            .size(touchDp)
            .pointerInput(target) {
                var tracked = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        frozenKnob = currentKnob
                        tracked = currentCharRect.center
                        controller.dragging = target
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        tracked += dragAmount
                        controller.dragTo(target, currentCoordinates.localToWindow(tracked))
                    },
                    onDragEnd = {
                        frozenKnob = null
                        controller.endDrag()
                    },
                    onDragCancel = {
                        frozenKnob = null
                        controller.endDrag()
                    },
                )
            },
    )
}

@Composable
private fun SelectionBar(
    state: TextSelectionState,
    dragging: SelectionDragTarget?,
    backgroundColor: Color,
    contentColor: Color,
    claude: SelectionAppAction?,
    processTextApps: List<SelectionAppAction>,
    onCopy: () -> Unit,
    onLookUp: () -> Unit,
    onAppAction: (SelectionAppAction) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 640.dp).fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, contentColor),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val fontSize = 20.sp
                val charsPerLine = with(LocalDensity.current) { (maxWidth.toPx() / fontSize.toPx()).toInt() }
                    .coerceAtLeast(8)
                val maxChars = charsPerLine * PREVIEW_LINES - 1
                val flat = state.text.replace(Regex("\\s*\\n\\s*"), " ")
                // While an end is dragged, show that end of the selection.
                val preview = when {
                    flat.length <= maxChars -> flat
                    dragging == SelectionDragTarget.END || dragging == SelectionDragTarget.FOCUS ->
                        "…" + flat.takeLast(maxChars - 1)
                    else -> flat
                }
                Text(
                    text = preview,
                    fontSize = fontSize,
                    lineHeight = 28.sp,
                    maxLines = PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SelectionActionButton("Copy", vector = Icons.Outlined.ContentCopy, onClick = onCopy)
                SelectionActionButton("Look up", vector = Icons.Outlined.Search, onClick = onLookUp)
                claude?.let { action ->
                    SelectionActionButton(action.label, bitmap = action.icon, onClick = { onAppAction(action) })
                }
                processTextApps.forEach { action ->
                    SelectionActionButton(action.label, bitmap = action.icon, onClick = { onAppAction(action) })
                }
                SelectionActionButton("Share", vector = Icons.Outlined.Share, onClick = onShare)
            }
        }
    }
}

private const val PREVIEW_LINES = 3

@Composable
private fun SelectionActionButton(
    label: String,
    vector: ImageVector? = null,
    bitmap: ImageBitmap? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .widthIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            bitmap != null -> Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
            vector != null -> Icon(imageVector = vector, contentDescription = null, modifier = Modifier.size(24.dp))
            else -> Box(Modifier.size(24.dp))
        }
        Text(text = label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
