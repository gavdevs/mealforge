package com.mealforge.lightphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import java.time.Instant
import kotlinx.coroutines.CancellationException
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

class GroceryScreen(activity: SealedLightActivity, private val serverUrl: String) : NativeScreen<Unit>(activity) {
    private val grocery = MutableStateFlow(GroceryState(loading = true))
    private val cacheNotice = MutableStateFlow<String?>(null)
    private val lastRead = MutableStateFlow(0L)
    private var module: AppModule? = null
    private var observer: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    override fun willShow() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            try {
                if (module == null) {
                    val encoded = lightContext.dataStore.data.first()[GROCERY_CACHE]
                    val cached = if (encoded != null && encoded.length <= MAX_CACHE_CHARS) {
                        // Malformed last-read data is discarded, never used as a write source.
                        runCatching { json.decodeFromString<CachedGroceries>(encoded) }.getOrNull()
                            ?.takeIf { it.serverUrl == serverUrl }
                    } else null
                    module = AppModule(serverUrl, scope, cached)
                    lastRead.value = cached?.savedAt ?: 0
                }
                val store = module!!.groceries
                store.refresh()
                store.state.collect { current ->
                    grocery.value = current
                    if (!current.loading && !current.isStale && current.error == null &&
                        !current.adding && current.pendingItemIds.isEmpty()) {
                        val at = System.currentTimeMillis()
                        lastRead.value = at
                        val encoded = json.encodeToString(CachedGroceries(serverUrl, at, current.plan, current.items))
                        try {
                            lightContext.dataStore.edit { preferences ->
                                if (encoded.length <= MAX_CACHE_CHARS) preferences[GROCERY_CACHE] = encoded
                                else preferences.remove(GROCERY_CACHE)
                            }
                            cacheNotice.value = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            cacheNotice.value = "Last read could not be saved on this device."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                grocery.value = grocery.value.copy(loading = false, error = error.message ?: "Could not open groceries.")
            }
        }
    }

    override fun willHide() {
        module?.groceries?.stop()
        super.willHide()
    }

    override fun onScreenDestroy() {
        module?.close()
        super.onScreenDestroy()
    }

    @Composable
    override fun Content() {
        val current by grocery.collectAsState()
        val savedAt by lastRead.collectAsState()
        val cacheError by cacheNotice.collectAsState()
        val openSections = remember(current.items) {
            val open = current.items.filterNot { it.checked }.groupBy { it.section }
            (STORE_SECTIONS.keys + open.keys).distinct().mapNotNull { section ->
                open[section]?.let { section to it }
            }
        }
        val checked = remember(current.items) { current.items.filter { it.checked } }
        val writable = !current.loading && !current.isStale && current.plan != null
        Page("Groceries") {
            current.plan?.let { plan ->
                LightText(plan.displayName, LightTextVariant.Subtitle)
                Detail("${checked.size} / ${current.items.size} in the cart")
            }
            if (current.loading) Detail("Loading the list…")
            if (current.isStale) Detail("STALE — last-read data; changes disabled until refresh succeeds.")
            if (savedAt > 0) Detail("Last confirmed: ${Instant.ofEpochMilli(savedAt)}")
            current.error?.let { Detail(it) }
            cacheError?.let { Detail(it) }
            if (!current.loading && current.plan == null && current.error == null) {
                Detail("No active meal plan. Activate a plan to get its grocery list.")
            }
            openSections.forEach { (section, items) ->
                LightText(STORE_SECTIONS[section] ?: section, LightTextVariant.Subheading)
                items.forEach { item ->
                    val pending = item.id in current.pendingItemIds
                    Choice("[ ] ${item.name}", writable && !pending) {
                        module?.groceries?.setChecked(item.id, true)
                    }
                    if (item.quantityText.isNotEmpty()) Detail(item.quantityText)
                    if (pending) Detail("Saving…")
                    if (item.isManual) {
                        Choice("Remove added item", writable && !pending) {
                            module?.groceries?.removeManualItem(item.id)
                        }
                    }
                }
            }
            if (checked.isNotEmpty()) {
                LightText("In the cart", LightTextVariant.Subheading)
                checked.forEach { item ->
                    Choice("[x] ${item.name}", writable && item.id !in current.pendingItemIds) {
                        module?.groceries?.setChecked(item.id, false)
                    }
                    if (item.quantityText.isNotEmpty()) Detail(item.quantityText)
                }
            }
            if (current.items.isNotEmpty() && checked.size == current.items.size) Detail("Everything's in the cart.")
            current.plan?.let { plan ->
                Choice("+ Add grocery item", writable && !current.adding) {
                    navigateTo({ ManualGroceryScreen(it, serverUrl, plan.planId) })
                }
            }
            Choice("Refresh", !current.loading && current.pendingItemIds.isEmpty() && !current.adding) {
                if (module == null) willShow() else module?.groceries?.refresh()
            }
        }
    }
}

private data class ManualDraft(val name: String = "", val quantity: String = "", val section: String? = null)

internal class ManualGroceryScreen(
    activity: SealedLightActivity,
    serverUrl: String,
    private val expectedPlanId: Int,
) : NativeScreen<Unit>(activity) {
    private val module = AppModule(serverUrl, scope)
    private val current = MutableStateFlow(GroceryState(loading = true))
    private val draft = MutableStateFlow(ManualDraft())
    private val notice = MutableStateFlow<String?>(null)
    private var observer: Job? = null
    private var interruptedSave = false

    override fun willShow() {
        if (observer?.isActive == true) return
        module.groceries.refresh()
        observer = scope.launch { module.groceries.state.collect { current.value = it } }
    }

    override fun willHide() {
        if (module.groceries.state.value.adding) {
            interruptedSave = true
            notice.value = "Save interrupted. It may have reached the server. Go back and check the list before adding again."
        }
        module.groceries.stop()
        super.willHide()
    }

    override fun onScreenDestroy() {
        module.close()
        super.onScreenDestroy()
    }

    @Composable
    override fun Content() {
        val state by current.collectAsState()
        val values by draft.collectAsState()
        val message by notice.collectAsState()
        val samePlan = state.plan?.planId == expectedPlanId
        val editable = !state.adding
        Page("Add grocery item") {
            if (state.loading) Detail("Checking the active plan…")
            if (!state.loading && !samePlan) Detail("The active plan changed. Go back and reopen the list.")
            state.error?.let { Detail(it) }
            message?.let { Detail(it) }
            Choice("Name: ${values.name.ifBlank { "enter a name" }}", editable) {
                navigateTo({ TextEditorScreen(it, "Item name", values.name) }) { text ->
                    draft.value = draft.value.copy(name = text)
                }
            }
            Choice("Quantity: ${values.quantity.ifBlank { "optional" }}", editable) {
                navigateTo({ TextEditorScreen(it, "Quantity (optional)", values.quantity) }) { text ->
                    draft.value = draft.value.copy(quantity = text)
                }
            }
            Choice("Section: ${STORE_SECTIONS[values.section] ?: "Other (default)"}", editable) {
                navigateTo({ SectionPickerScreen(it) }) { section -> draft.value = draft.value.copy(section = section) }
            }
            Choice(if (state.adding) "Saving…" else "Add item",
                editable && !state.loading && !state.isStale && samePlan && !interruptedSave && values.name.isNotBlank()) {
                val job = module.groceries.addManualItem(
                    values.name, values.quantity.trim().takeIf { it.isNotEmpty() }, values.section,
                ) ?: return@Choice
                scope.launch {
                    job.join()
                    if (!job.isCancelled && module.groceries.state.value.error == null) goBack(Unit)
                }
            }
        }
    }
}

private class SectionPickerScreen(activity: SealedLightActivity) : NativeScreen<String>(activity) {
    @Composable
    override fun Content() {
        Page("Store section") {
            STORE_SECTIONS.forEach { (key, label) -> Choice(label) { goBack(key) } }
        }
    }
}
