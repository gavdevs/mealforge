package com.mealforge.lightphone

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren

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
                    Modifier.weight(1f).fillMaxWidth().then(
                        if (main != null && backLabel.isEmpty()) {
                            Modifier.padding(horizontal = 0.25f.gridUnitsAsDp())
                        } else {
                            Modifier.padding(start = 1f.gridUnitsAsDp())
                        },
                    ),
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

@Composable
private fun ScrollPane(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.verticalScroll(scrollState), content = content)
}

@Composable
private fun MainNavigation(onSelect: (MainSection) -> Unit) {
    LightBottomBar(items = MainSection.entries.map { section ->
        LightBarButton.LightIcon(
            icon = when (section) {
                MainSection.Plans -> LightIcons.HOME
                MainSection.Recipes -> LightIcons.LARGE_LIST
                MainSection.Grocery -> LightIcons.ACCEPT
            },
            onClick = { onSelect(section) },
            contentDescription = section.name,
            sizeUnits = 1.5f,
        )
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
