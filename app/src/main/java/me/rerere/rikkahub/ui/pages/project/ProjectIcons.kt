package me.rerere.rikkahub.ui.pages.project

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes

data class ProjectIconDef(
    val key: String,
    val icon: ImageVector,
)

object ProjectIcons {
    val ALL: List<ProjectIconDef> = listOf(
        // 通用与文档
        ProjectIconDef("folder", Icons.Rounded.Folder),
        ProjectIconDef("folder_special", Icons.Rounded.FolderSpecial),
        ProjectIconDef("description", Icons.Rounded.Description),
        ProjectIconDef("task_alt", Icons.Rounded.TaskAlt),
        ProjectIconDef("bookmark", Icons.Rounded.Bookmark),

        // 技术与开发
        ProjectIconDef("terminal", Icons.Rounded.Terminal),
        ProjectIconDef("code", Icons.Rounded.Code),
        ProjectIconDef("bug_report", Icons.Rounded.BugReport),
        ProjectIconDef("smart_toy", Icons.Rounded.SmartToy),
        ProjectIconDef("storage", Icons.Rounded.Storage),

        // 工作与商务
        ProjectIconDef("work", Icons.Rounded.Work),
        ProjectIconDef("analytics", Icons.Rounded.Analytics),
        ProjectIconDef("savings", Icons.Rounded.Savings),
        ProjectIconDef("shopping_cart", Icons.Rounded.ShoppingCart),

        // 创意与设计
        ProjectIconDef("palette", Icons.Rounded.Palette),
        ProjectIconDef("brush", Icons.Rounded.Brush),
        ProjectIconDef("lightbulb", Icons.Rounded.Lightbulb),
        ProjectIconDef("auto_awesome", Icons.Rounded.AutoAwesome),

        // 学习与研究
        ProjectIconDef("school", Icons.Rounded.School),
        ProjectIconDef("menu_book", Icons.Rounded.MenuBook),
        ProjectIconDef("translate", Icons.Rounded.Translate),
        ProjectIconDef("science", Icons.Rounded.Science),

        // 生活与娱乐
        ProjectIconDef("home", Icons.Rounded.Home),
        ProjectIconDef("fitness_center", Icons.Rounded.FitnessCenter),
        ProjectIconDef("flight", Icons.Rounded.Flight),
        ProjectIconDef("sports_esports", Icons.Rounded.SportsEsports),
        ProjectIconDef("music_note", Icons.Rounded.MusicNote),
        ProjectIconDef("movie", Icons.Rounded.Movie),
        ProjectIconDef("rocket_launch", Icons.Rounded.RocketLaunch),
    )

    private val map: Map<String, ImageVector> = ALL.associate { it.key to it.icon }

    fun getIcon(key: String?): ImageVector {
        if (key.isNullOrBlank()) return Icons.Rounded.Folder
        return map[key.trim().lowercase()] ?: Icons.Rounded.Folder
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectIconPickerSheet(
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.CardLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.project_select_icon),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(ProjectIcons.ALL, key = { it.key }) { def ->
                    val isSelected = (selectedKey.isBlank() && def.key == "folder") || selectedKey.equals(def.key, ignoreCase = true)
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) 0.85f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
                        label = "icon_btn_scale"
                    )

                    Surface(
                        shape = AppShapes.CardMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = if (isSelected) {
                            BorderStroke(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else null,
                        modifier = Modifier
                            .size(56.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(AppShapes.CardMedium)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    onSelect(def.key)
                                    onDismiss()
                                }
                            )
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                imageVector = def.icon,
                                contentDescription = def.key,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

