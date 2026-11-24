package com.mealforge.lightphone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val NUMBERED_STEP = Regex("""^\s*\d+[.)]\s+(.*)$""")
private val PARAGRAPH_BREAK = Regex("\n{2,}")

fun splitCookingSteps(markdown: String): List<String> {
    val steps = ArrayList<String>()
    var current: StringBuilder? = null
    for (line in markdown.splitToSequence('\n')) {
        val match = NUMBERED_STEP.matchEntire(line)
        if (match != null) {
            current?.let { steps.add(it.toString().trim()) }
            current = StringBuilder(match.groupValues[1])
        } else if (current != null && line.isNotBlank()) {
            current.append('\n').append(line)
        }
    }
    current?.let { steps.add(it.toString().trim()) }
    if (steps.isNotEmpty()) return steps
    return PARAGRAPH_BREAK.split(markdown).map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf("") }
}

data class CookProgress(val steps: List<String>, val index: Int = 0, val finished: Boolean = false) {
    val current: String get() = steps[index]
}

class CookSession(markdown: String) {
    private val progress = MutableStateFlow(CookProgress(splitCookingSteps(markdown)))
    val state: StateFlow<CookProgress> = progress.asStateFlow()

    fun next() {
        progress.update {
            when {
                it.finished -> it
                it.index == it.steps.lastIndex -> it.copy(finished = true)
                else -> it.copy(index = it.index + 1)
            }
        }
    }

    fun previous() {
        progress.update { if (it.finished || it.index == 0) it else it.copy(index = it.index - 1) }
    }

    fun exit() {
        progress.update { if (it.finished) it else it.copy(finished = true) }
    }
}
