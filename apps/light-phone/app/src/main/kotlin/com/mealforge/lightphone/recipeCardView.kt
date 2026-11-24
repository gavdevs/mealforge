package com.mealforge.lightphone

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@Composable
internal fun RecipeCardView(
    title: String,
    prepMinutes: Int?,
    cookMinutes: Int?,
    tags: List<String>,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    pending: Boolean = false,
    enabled: Boolean = true,
    favoriteEnabled: Boolean = enabled,
    cooked: Boolean? = null,
    onCooked: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().border(1.dp, LightThemeTokens.colors.contentSecondary)) {
        Row(Modifier.padding(0.75f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
            verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).lightClickable(onClick = onOpen)) {
                LightText(title, LightTextVariant.ParagraphWide, lighten = cooked == true)
                val time = formatRecipeTime(prepMinutes, cookMinutes)
                if (time != null) BodyText(time, secondary = true)
                if (tags.isNotEmpty()) BodyText(tags.take(3).joinToString(" • "), secondary = true)
            }
            FavoriteControl(title, isFavorite, favoriteEnabled && !pending, pending, onFavorite)
        }
        if (onCooked != null) {
            Separator()
            ActionButton(if (cooked == true) "Completed" else "Complete", enabled,
                selected = cooked == true,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = if (cooked == true) "Mark $title as not cooked" else "Complete $title"
                }, action = onCooked)
        }
    }
}

@Composable
internal fun FavoriteControl(title: String, favorite: Boolean, enabled: Boolean, pending: Boolean = false, action: () -> Unit) {
    ActionButton(if (pending) "..." else if (favorite) "★" else "☆", enabled,
        modifier = Modifier.semantics {
            contentDescription = if (favorite) "Unfavorite $title" else "Favorite $title"
        }, action = action)
}
