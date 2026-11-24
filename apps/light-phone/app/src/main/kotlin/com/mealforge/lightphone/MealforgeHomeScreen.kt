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
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightTextField
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
private data class ServerSetup(val url: String = DEFAULT_SERVER, val accepted: Boolean = false,
    val error: String? = null, val saving: Boolean = false, val loaded: Boolean = false)

@InitialScreen
class MealforgeHomeScreen(activity: SealedLightActivity) : NativeScreen<Unit>(activity) {
    private val setup = MutableStateFlow(ServerSetup())
    private val settings = MutableStateFlow(false)
    private var workspace: Workspace? = null

    override fun willShow() {
        if (setup.value.loaded) {
            if (!settings.value) workspace?.show()
            return
        }
        scope.launch {
            try {
                val preferences = lightContext.dataStore.data.first()
                setup.value = ServerSetup(preferences[SERVER_URL] ?: DEFAULT_SERVER,
                    preferences[SERVER_ACCEPTED] ?: false, loaded = true)
                if (setup.value.accepted) openWorkspace()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                setup.value = setup.value.copy(loaded = true, error = error.message ?: "Could not read setup.")
            }
        }
    }

    override fun willHide() { workspace?.hide(); super.willHide() }
    override fun onScreenDestroy() { workspace?.close(); super.onScreenDestroy() }

    private val editText: EditNativeText = { title, initial, onSubmit ->
        navigateTo({ TextEditorScreen(it, title, initial) }, onSubmit)
    }

    private fun openWorkspace() {
        val url = setup.value.url
        if (workspace?.url != url) {
            workspace?.close()
            val client = MealforgeClient(url)
            val navigation = WebViewNavigation()
            workspace = Workspace(url, client, navigation,
                PlansPanel(client, scope,
                    openPlan = { id -> navigateTo({ PlanDetailScreen(it, url, id) }) { selectSection(it) } },
                    openRecipe = { id -> navigateTo({ RecipeDetailScreen(it, url, id) }) { selectSection(it) } },
                    editText = editText, onFilter = navigation::selectPlans),
                RecipesPanel(client, scope,
                    openRecipe = { id -> navigateTo({ RecipeDetailScreen(it, url, id) }) { selectSection(it) } },
                    editText = editText),
                GroceryPanel(url, scope, lightContext.dataStore, editText))
        }
        settings.value = false
        workspace?.show()
    }

    private fun selectSection(section: MainSection) {
        val current = workspace ?: return
        if (current.navigation.state.value.section == section) return
        current.hide()
        current.navigation.selectSection(section)
        current.show()
    }

    @Composable
    override fun Content() {
        val state by setup.collectAsState()
        val editing by settings.collectAsState()
        val current = workspace
        if (!state.loaded) {
            Frame("MealForge", backLabel = "") { BodyText("Loading...") }
        } else if (current != null && state.accepted && !editing) {
            val route by current.navigation.state.collectAsState()
            Frame(when (route.section) {
                MainSection.Plans -> "Meal plans"
                MainSection.Recipes -> "Recipes"
                MainSection.Grocery -> "Grocery"
            }, backLabel = "", main = route.section, onMain = ::selectSection,
                scrollKey = if (route.section == MainSection.Plans) route.plans else route.section,
                right = LightBarButton.Text("Server", onClick = { current.hide(); settings.value = true })) {
                current.panel().Content()
            }
        } else {
            Frame("Server setup", backLabel = if (current == null) "Close" else "Back", backAction = {
                if (current == null) goBack(null) else {
                    setup.value = ServerSetup(current.url, accepted = true, loaded = true)
                    settings.value = false
                    current.show()
                }
            }) {
                BodyText("Choose a MealForge server you trust.")
                LightTextField("Server origin", state.url, "https://your-server",
                    onClick = { editText("Server origin", state.url) { setup.value = setup.value.copy(url = it, accepted = false, error = null) } })
                ActionButton("Edit server URL", !state.saving) {
                    editText("Server origin", state.url) { setup.value = setup.value.copy(url = it, accepted = false, error = null) }
                }
                BodyText(if (state.url.startsWith("https://")) "HTTPS checks the server certificate."
                    else "HTTP is unencrypted. Only use a trusted server and network.", secondary = true)
                BodyText("Uses this server's existing public API. No additional login.", secondary = true)
                state.error?.let { BodyText(it) }
                ActionButton(if (state.saving) "Saving..." else "Trust this server and save", !state.saving) { saveServer() }
            }
        }
    }

    private fun saveServer() {
        if (setup.value.saving) return
        setup.value = setup.value.copy(saving = true, error = null)
        scope.launch {
            try {
                val url = normalizeServerUrl(setup.value.url)
                lightContext.dataStore.edit { it[SERVER_URL] = url; it[SERVER_ACCEPTED] = true }
                setup.value = ServerSetup(url, accepted = true, loaded = true)
                openWorkspace()
            } catch (cancelled: CancellationException) {
                setup.value = setup.value.copy(saving = false)
                throw cancelled
            } catch (error: Exception) {
                setup.value = setup.value.copy(saving = false, error = error.message ?: "Could not save setup.")
            }
        }
    }
}

private class Workspace(
    val url: String, private val client: MealforgeClient, val navigation: WebViewNavigation,
    private val plans: PlansPanel, private val recipes: RecipesPanel, private val groceries: GroceryPanel,
) {
    fun panel(): MealforgePanel = when (navigation.state.value.section) {
        MainSection.Plans -> plans; MainSection.Recipes -> recipes; MainSection.Grocery -> groceries
    }
    fun show(): Unit = panel().show()
    fun hide(): Unit = panel().hide()
    fun close() { plans.hide(); recipes.hide(); groceries.close(); client.close() }
}

internal class TextEditorScreen(
    activity: SealedLightActivity, private val title: String, private val initial: String,
) : SimpleLightScreen<String>(activity) {
    @Composable
    override fun Content() {
        val text = rememberTextFieldState(initial)
        val keyboard = rememberKeyboardOptions()
        LightTheme(colors = LightThemeColors.Dark) {
            LightTextInputEditor(title = title, state = text, keyboardOptionsFlow = keyboard,
                singleLine = true, submitLabel = "SAVE",
                onSubmit = { goBack(it.toString()) }, onBack = { goBack(null) },
                modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background))
        }
    }
}
