package com.mealforge.lightphone

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren

abstract class NativeScreen<R>(activity: SealedLightActivity) : SimpleLightScreen<R>(activity) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wheelScroll: ScrollState? = null

    override fun willHide() {
        wheelScroll = null
        scope.coroutineContext.cancelChildren()
    }

    override fun onAppPause() {
        willHide()
    }

    override fun onScreenDestroy() {
        willHide()
        scope.cancel()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // RotaryTurnUp/Down from the pinned light-keyboard v0.0.19 device keys.
        val delta = when (keyCode) {
            317 -> -100f
            318 -> 100f
            else -> return false
        }
        val scroll = wheelScroll ?: return false
        scroll.dispatchRawDelta(delta)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        wheelScroll != null && (keyCode == 317 || keyCode == 318)

    @Composable
    protected fun Page(
        title: String,
        modifier: Modifier = Modifier,
        backLabel: String = "BACK",
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val scroll = rememberScrollState()
        SideEffect { wheelScroll = scroll }
        LightTheme(colors = LightThemeColors.Dark) {
            Column(modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.Text(backLabel, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text(title),
                )
                LightScrollView(Modifier.weight(1f).padding(horizontal = 16.dp), scrollState = scroll) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun Choice(label: String, enabled: Boolean = true, action: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        lighten = !enabled,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action)
            .padding(vertical = 12.dp),
    )
}

@Composable
internal fun Detail(text: String) {
    LightText(text = text, variant = LightTextVariant.Detail, lighten = true,
        modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
internal fun Separator() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp)
        .background(LightThemeTokens.colors.contentSecondary))
}
