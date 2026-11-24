package com.mealforge.lightphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SERVER_URL = stringPreferencesKey("mealforge_server_url")
private val SERVER_ACCEPTED = booleanPreferencesKey("mealforge_server_accepted")
private const val DEFAULT_SERVER = "https://meals.loosewire.dev"

private data class ServerSetup(
    val url: String = DEFAULT_SERVER,
    val accepted: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
)

@InitialScreen
class MealforgeHomeScreen(activity: SealedLightActivity) : NativeScreen<Unit>(activity) {
    private val setup = MutableStateFlow(ServerSetup())
    private var loaded = false

    override fun willShow() {
        if (loaded) return
        scope.launch {
            try {
                val preferences = lightContext.dataStore.data.first()
                setup.value = ServerSetup(
                    url = preferences[SERVER_URL] ?: DEFAULT_SERVER,
                    accepted = preferences[SERVER_ACCEPTED] ?: false,
                )
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setup.value = setup.value.copy(error = error.message ?: "Could not read setup.")
            }
        }
    }

    @Composable
    override fun Content() {
        val state by setup.collectAsState()
        Page("MealForge") {
            Detail(state.url)
            if (state.accepted) {
                Choice("Groceries") { navigateTo({ GroceryScreen(it, state.url) }) }
                PlanListKind.entries.forEach { kind ->
                    Choice(kind.title) { navigateTo({ PlanListScreen(it, state.url, kind) }) }
                }
                Choice("Recipes") { navigateTo({ RecipeListScreen(it, state.url) }) }
            }
            Detail(if (state.url.startsWith("https://")) {
                "HTTPS checks the server certificate on connection. Only use a MealForge server you trust."
            } else {
                "HTTP is unencrypted. Only use a server and network you trust."
            })
            Detail("This client uses the server's existing public API. It does not add a login or verify who operates the server.")
            state.error?.let { Detail(it) }
            Choice("Edit server URL", !state.saving) {
                navigateTo({ TextEditorScreen(it, "Server origin", state.url) }) { value ->
                    setup.value = setup.value.copy(url = value, accepted = false, error = null)
                }
            }
            if (!state.accepted) {
                Choice(if (state.saving) "Saving…" else "Trust this server and save", !state.saving) {
                    saveServer()
                }
            }
        }
    }

    private fun saveServer() {
        if (setup.value.saving) return
        setup.value = setup.value.copy(saving = true, error = null)
        scope.launch {
            try {
                val url = normalizeServerUrl(setup.value.url)
                lightContext.dataStore.edit {
                    it[SERVER_URL] = url
                    it[SERVER_ACCEPTED] = true
                }
                setup.value = ServerSetup(url = url, accepted = true)
            } catch (cancelled: CancellationException) {
                setup.value = setup.value.copy(saving = false)
                throw cancelled
            } catch (error: Exception) {
                setup.value = setup.value.copy(saving = false, error = error.message ?: "Could not save setup.")
            }
        }
    }
}

internal class TextEditorScreen(
    activity: SealedLightActivity,
    private val title: String,
    private val initial: String,
) : SimpleLightScreen<String>(activity) {
    @Composable
    override fun Content() {
        val text = rememberTextFieldState(initial)
        val keyboard = rememberKeyboardOptions()
        LightTheme(colors = LightThemeColors.Dark) {
            LightTextInputEditor(
                title = title,
                state = text,
                keyboardOptionsFlow = keyboard,
                singleLine = true,
                submitLabel = "SAVE",
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack(null) },
                modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
            )
        }
    }
}
