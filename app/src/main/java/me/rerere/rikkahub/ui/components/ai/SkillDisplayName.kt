package me.rerere.rikkahub.ui.components.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Skill

@Composable
fun Skill.displayName(): String = if (isBuiltIn) {
    stringResource(R.string.interactive_components_skill_name)
} else name

@Composable
fun Skill.displayDescription(): String = if (isBuiltIn) {
    stringResource(R.string.interactive_components_skill_description)
} else description
