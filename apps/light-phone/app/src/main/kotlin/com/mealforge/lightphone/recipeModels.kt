package com.mealforge.lightphone

import kotlinx.serialization.Serializable

@Serializable
data class RecipeSummary(
    val id: Int,
    val title: String,
    val description: String,
    val servings: Int,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val tags: List<String>,
    val mealTypes: List<String>,
    val isFavorite: Boolean,
    val createdAt: String,
)

@Serializable
data class RecipeIngredient(
    val id: Int,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    val section: String,
    val sortOrder: Int,
)

@Serializable
data class RecipeUse(val planId: Int, val name: String?, val displayName: String)

@Serializable
data class RecipeDetail(
    val id: Int,
    val title: String,
    val description: String,
    val servings: Int,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val tags: List<String>,
    val mealTypes: List<String>,
    val isFavorite: Boolean,
    val createdAt: String,
    val stepsMarkdown: String,
    val ingredients: List<RecipeIngredient>,
    val usedInPlans: List<RecipeUse>,
)
