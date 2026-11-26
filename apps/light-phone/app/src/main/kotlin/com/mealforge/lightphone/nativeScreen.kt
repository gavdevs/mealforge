package com.mealforge.lightphone

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay

internal interface MealforgePanel {
    @Composable fun Content()
    fun show()
    fun hide()
}

internal typealias EditNativeText = (String, String, (String) -> Unit) -> Unit

abstract class NativeScreen<R>(activity: SealedLightActivity) : SimpleLightScreen<R>(activity) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scrolls = mutableMapOf<Any, ScrollState>()
    private var wheelScroll: ScrollState? = null

    override fun willHide() {
        wheelScroll = null
        scope.coroutineContext.cancelChildren()
    }

    override fun onAppPause() { willHide() }

    override fun onScreenDestroy() {
        willHide()
        scope.cancel()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = when (keyCode) { 317 -> -150f; 318 -> 150f; else -> return false }
        val scroll = wheelScroll ?: return false
        scroll.dispatchRawDelta(delta)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        wheelScroll != null && (keyCode == 317 || keyCode == 318)

    @Composable
    protected fun Frame(
        title: String,
        modifier: Modifier = Modifier,
        backLabel: String = "Back",
        main: MainSection? = null,
        onMain: ((MainSection) -> Unit)? = null,
        right: LightBarButton? = null,
        backAction: (() -> Unit)? = null,
        scrollKey: Any = main ?: title,
        bottom: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val scroll = scrolls.getOrPut(scrollKey) { ScrollState(0) }
        SideEffect { wheelScroll = scroll }
        LightTheme(colors = LightThemeColors.Dark) {
            Column(modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = if (backLabel.isEmpty()) null else LightBarButton.Text(
                        backLabel, onClick = backAction ?: { goBack(null) },
                    ),
                    center = LightTopBarCenter.Text(title),
                    rightButton = right,
                )
                ScrollPane(
                    Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                    scrollState = scroll,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 1f.gridUnitsAsDp()),
                        verticalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
                        content = content,
                    )
                }
                if (main != null && onMain != null) MainNavigation(onMain)
                else bottom?.invoke()
            }
        }
    }
}

// Thin transient overlay scrollbar. The SDK LightScrollView keeps a persistent
// 1dp rail and 5dp thumb on screen whenever content overflows; this overlay
// keeps the same content gutter so the right edge stays put, shows a 2dp thumb
// only while scrolling, and fades out when idle.
private val SCROLLBAR_THUMB_WIDTH = 2.dp
private const val SCROLLBAR_IDLE_TIMEOUT_MS = 600L

@Composable
private fun ScrollPane(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val showThumb = remember { mutableStateOf(false) }
    // Keyed on value changes rather than isScrollInProgress so wheel-driven
    // scrolls (dispatchRawDelta) also reveal the thumb.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collectLatest {
            showThumb.value = true
            delay(SCROLLBAR_IDLE_TIMEOUT_MS)
            showThumb.value = false
        }
    }
    val alpha by animateFloatAsState(
        if (showThumb.value && scrollState.maxValue > 0) 1f else 0f,
        animationSpec = tween(150),
        label = "scrollbarAlpha",
    )
    // Gutter matches the SDK scrollbar width so content keeps the same
    // right edge (content ends at 25/27 of the screen width).
    Box(modifier) {
        Column(
            Modifier.fillMaxSize()
                .padding(end = 2f.gridUnitsAsDp())
                .verticalScroll(scrollState),
            content = content,
        )
        if (scrollState.maxValue > 0) {
            ThinScrollBar(
                scrollState,
                alpha,
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun ThinScrollBar(
    scrollState: ScrollState,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val viewportPx = constraints.maxHeight.toFloat()
        val contentHeightPx = viewportPx + scrollState.maxValue
        val fraction = (viewportPx / contentHeightPx).coerceIn(0.1f, 0.85f)
        val thumbHeightPx = viewportPx * fraction
        val scrollFraction = if (scrollState.maxValue == 0) 0f
        else scrollState.value / scrollState.maxValue.toFloat()
        val thumbOffsetPx = scrollFraction * (viewportPx - thumbHeightPx)
        Box(
            Modifier
                .offset { IntOffset(x = 0, y = thumbOffsetPx.roundToInt()) }
                .width(SCROLLBAR_THUMB_WIDTH)
                .height(with(LocalDensity.current) { thumbHeightPx.toDp() })
                .alpha(alpha)
                .background(LightThemeTokens.colors.content),
        )
    }
}

@Composable
private fun MainNavigation(onSelect: (MainSection) -> Unit) {
    LightBottomBar(items = MainSection.entries.map { section ->
        LightBarButton.Text(section.name, onClick = { onSelect(section) })
    })
}

@Composable
internal fun ActionButton(
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier.semantics { this.selected = selected }
            .lightClickable(enabled = enabled, role = Role.Button, onClick = action)
            .heightIn(min = 44.dp)
            .border(1.dp, colors.contentSecondary)
            .background(if (selected) colors.content else colors.background)
            .padding(horizontal = 0.5f.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(label, LightTextVariant.Detail, align = TextAlign.Center,
            lighten = !enabled, color = if (selected) colors.background else null)
    }
}

@Composable
internal fun BodyText(text: String, secondary: Boolean = false, modifier: Modifier = Modifier) {
    LightText(text, if (secondary) LightTextVariant.Detail else LightTextVariant.ParagraphWide,
        lighten = secondary, modifier = modifier)
}

@Composable
internal fun SectionHeading(text: String) {
    LightText(text.uppercase(), LightTextVariant.Detail, lighten = true,
        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
}

@Composable
internal fun Separator() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LightThemeTokens.colors.contentSecondary))
}
