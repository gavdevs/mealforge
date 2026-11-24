package com.mealforge.lightphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val STORE_SECTIONS = linkedMapOf(
    "produce" to "Produce", "meat-seafood" to "Meat & Seafood", "dairy-eggs" to "Dairy & Eggs",
    "bakery" to "Bakery", "pantry" to "Pantry", "frozen" to "Frozen", "spices" to "Spices",
    "beverages" to "Beverages", "other" to "Other",
)
private val GROCERY_CACHE = stringPreferencesKey("mealforge_last_groceries")
private const val MAX_CACHE_CHARS = 64_000
private data class GroceryDraft(
    val name: String = "", val section: String = "other", val planId: Int? = null,
    val interrupted: Boolean = false,
)

internal class GroceryPanel(
    private val serverUrl: String, private val scope: CoroutineScope,
    private val dataStore: DataStore<Preferences>, private val editText: EditNativeText,
) : MealforgePanel {
    private val grocery = MutableStateFlow(GroceryState(loading = true))
    private val draft = MutableStateFlow(GroceryDraft())
    private val sectionOpen = MutableStateFlow(false)
    private val cacheNotice = MutableStateFlow<String?>(null)
    private var savedAt = 0L
    private var module: AppModule? = null
    private var observer: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    override fun show() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            try {
                if (module == null) {
                    val encoded = dataStore.data.first()[GROCERY_CACHE]
                    val cached = if (encoded != null && encoded.length <= MAX_CACHE_CHARS) {
                        runCatching { json.decodeFromString<CachedGroceries>(encoded) }.getOrNull()
                            ?.takeIf { it.serverUrl == serverUrl }
                    } else null
                    module = AppModule(serverUrl, scope, cached)
                    savedAt = cached?.savedAt ?: 0
                }
                val store = module!!.groceries
                store.refresh()
                store.state.collect { current ->
                    grocery.value = current
                    if (!current.loading && !current.isStale && current.error == null &&
                        !current.adding && current.pendingItemIds.isEmpty()) {
                        val at = System.currentTimeMillis()
                        val encoded = json.encodeToString(CachedGroceries(serverUrl, at, current.plan, current.items))
                        try {
                            dataStore.edit {
                                if (encoded.length <= MAX_CACHE_CHARS) it[GROCERY_CACHE] = encoded
                                else it.remove(GROCERY_CACHE)
                            }
                            savedAt = at
                            cacheNotice.value = null
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { cacheNotice.value = "Last read could not be saved on this device." }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                grocery.value = grocery.value.copy(loading = false, isStale = true,
                    error = error.message ?: "Could not open groceries.")
            }
        }
    }

    override fun hide() {
        if (module?.groceries?.state?.value?.adding == true) draft.value = draft.value.copy(interrupted = true)
        module?.groceries?.stop()
        observer?.cancel()
    }
    fun close() { hide(); module?.close() }

    private fun add() {
        val store = module?.groceries ?: return
        val current = store.state.value
        val values = draft.value
        if (current.loading || current.isStale || values.interrupted || values.planId != current.plan?.planId) return
        val job = store.addManualItem(values.name, null, values.section) ?: return
        scope.launch {
            job.join()
            if (!job.isCancelled && store.state.value.error == null && !store.state.value.isStale) {
                draft.value = draft.value.copy(name = "", interrupted = false)
            }
        }
    }

    @Composable
    override fun Content() {
        val current by grocery.collectAsState()
        val values by draft.collectAsState()
        val selecting by sectionOpen.collectAsState()
        val cacheError by cacheNotice.collectAsState()
        val openSections = remember(current.items) {
            val open = current.items.filterNot { it.checked }.groupBy { it.section }
            (STORE_SECTIONS.keys + open.keys).distinct().mapNotNull { section -> open[section]?.let { section to it } }
        }
        val checked = remember(current.items) { current.items.filter { it.checked } }
        val writable = !current.loading && !current.isStale && current.plan != null
        if (current.loading) BodyText("Loading the list...", true)
        if (current.isStale) {
            BodyText("Last read only. Changes disabled until refresh succeeds.", true)
            if (savedAt > 0) BodyText("Last confirmed ${Instant.ofEpochMilli(savedAt)}", true)
        }
        current.error?.let { BodyText(it) }
        cacheError?.let { BodyText(it, true) }
        val plan = current.plan
        if (!current.loading && plan == null && current.error == null) {
            SectionHeading("No list yet")
            BodyText("The grocery list builds itself from the active meal plan. Set a plan active or push one from chat.", true)
        }
        if (plan != null) {
            BodyText("${plan.displayName} • ${checked.size}/${current.items.size} in the cart", true)
            openSections.forEach { (section, items) ->
                SectionHeading(STORE_SECTIONS[section] ?: section)
                items.forEach { item ->
                    GroceryRow(item, writable && item.id !in current.pendingItemIds,
                        item.id in current.pendingItemIds,
                        onToggle = { module?.groceries?.setChecked(item.id, true) },
                        onRemove = if (item.isManual) ({ module?.groceries?.removeManualItem(item.id); Unit }) else null)
                }
            }
            if (current.items.isNotEmpty() && checked.size == current.items.size) {
                BodyText("Everything's in the cart. Go home and cook.")
            }
            SectionHeading("Add an item")
            LightTextField("Add a grocery item", values.name, "Item name",
                onClick = {
                    if (!current.adding) editText("Item name", values.name) {
                        draft.value = draft.value.copy(name = it, planId = plan.planId)
                    }
                })
            ActionButton("Section: ${STORE_SECTIONS[values.section] ?: values.section}", !current.adding) {
                sectionOpen.value = !sectionOpen.value
            }
            if (selecting) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(0.3f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.spacedBy(0.3f.gridUnitsAsDp()),
            ) {
                STORE_SECTIONS.forEach { (key, label) ->
                    ActionButton(label, !current.adding, selected = values.section == key) {
                        draft.value = draft.value.copy(section = key)
                        sectionOpen.value = false
                    }
                }
            }
            val changedPlan = values.name.isNotBlank() && values.planId != plan.planId
            if (values.interrupted || changedPlan) {
                BodyText(if (values.interrupted) "Save interrupted. It may have reached the server. Check the refreshed list before adding again."
                    else "The active plan changed. Review the refreshed list before entering an item.")
                ActionButton("Clear entry", !current.adding) { draft.value = GroceryDraft(section = values.section, planId = plan.planId) }
            }
            ActionButton(if (current.adding) "Adding..." else "Add item",
                writable && !current.adding && !values.interrupted && !changedPlan && values.name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { add() }
            if (checked.isNotEmpty()) {
                SectionHeading("In the cart")
                checked.forEach { item ->
                    GroceryRow(item, writable && item.id !in current.pendingItemIds,
                        item.id in current.pendingItemIds,
                        onToggle = { module?.groceries?.setChecked(item.id, false) })
                }
            }
        }
        ActionButton("Refresh", !current.loading && current.pendingItemIds.isEmpty() && !current.adding) {
            if (module == null) show() else module?.groceries?.refresh()
        }
    }
}

@Composable
private fun GroceryRow(
    item: GroceryItem, enabled: Boolean, pending: Boolean, onToggle: () -> Unit, onRemove: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f)
            .semantics {
                contentDescription = "${if (item.checked) "Uncheck" else "Check"} ${item.name}"
                toggleableState = if (item.checked) ToggleableState.On else ToggleableState.Off
            }.lightClickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .heightIn(min = 44.dp).padding(vertical = 0.5f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically) {
            LightText(if (item.checked) "[x]" else "[ ]", LightTextVariant.Detail, monospace = true)
            Column(Modifier.weight(1f)) {
                LightText(item.name, LightTextVariant.ParagraphWide, lighten = item.checked, strikethrough = item.checked)
                if (pending) BodyText("Saving...", true)
                else if (item.isManual) BodyText("added", true)
            }
            if (item.quantityText.isNotEmpty()) BodyText(item.quantityText, true, Modifier.widthIn(max = 6f.gridUnitsAsDp()))
        }
        if (onRemove != null) ActionButton("Remove", enabled,
            modifier = Modifier.semantics { contentDescription = "Remove ${item.name}" }, action = onRemove)
    }
    Separator()
}
