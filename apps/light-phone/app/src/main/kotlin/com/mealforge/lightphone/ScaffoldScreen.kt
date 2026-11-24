package com.mealforge.lightphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Build-scaffold entry point only. The MealForge screens, navigation stack and
 * tRPC client are implemented in the follow-up session; this screen exists so
 * the Gradle build, SDK plugin validation and manifest generation can be proven
 * end to end before feature code lands.
 */
@InitialScreen
class ScaffoldScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        LightTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(text = "MealForge", variant = LightTextVariant.Title)
                LightText(
                    text = "Client shell only. Screens are not implemented yet.",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
