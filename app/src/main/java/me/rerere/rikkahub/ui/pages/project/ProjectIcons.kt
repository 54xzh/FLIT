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
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
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
        ProjectIconDef("star", Icons.Rounded.Star),
        ProjectIconDef("tag", Icons.Rounded.Tag),

        // 写作与内容创作
        ProjectIconDef("edit", Icons.Rounded.Edit),
        ProjectIconDef("article", Icons.AutoMirrored.Rounded.Article),
        ProjectIconDef("auto_stories", Icons.Rounded.AutoStories),
        ProjectIconDef("mic", Icons.Rounded.Mic),
        ProjectIconDef("camera_alt", Icons.Rounded.CameraAlt),

        // 情感、人设与心理
        ProjectIconDef("favorite", Icons.Rounded.Favorite),
        ProjectIconDef("face", Icons.Rounded.Face),
        ProjectIconDef("mood", Icons.Rounded.Mood),
        ProjectIconDef("psychology", Icons.Rounded.Psychology),
        ProjectIconDef("volunteer_activism", Icons.Rounded.VolunteerActivism),

        // 技术与开发
        ProjectIconDef("terminal", Icons.Rounded.Terminal),
        ProjectIconDef("code", Icons.Rounded.Code),
        ProjectIconDef("bug_report", Icons.Rounded.BugReport),
        ProjectIconDef("smart_toy", Icons.Rounded.SmartToy),
        ProjectIconDef("storage", Icons.Rounded.Storage),
        ProjectIconDef("laptop", Icons.Rounded.Laptop),
        ProjectIconDef("smartphone", Icons.Rounded.Smartphone),
        ProjectIconDef("security", Icons.Rounded.Security),

        // 工作、商务与专业
        ProjectIconDef("work", Icons.Rounded.Work),
        ProjectIconDef("analytics", Icons.Rounded.Analytics),
        ProjectIconDef("savings", Icons.Rounded.Savings),
        ProjectIconDef("shopping_cart", Icons.Rounded.ShoppingCart),
        ProjectIconDef("gavel", Icons.Rounded.Gavel),
        ProjectIconDef("calculate", Icons.Rounded.Calculate),
        ProjectIconDef("trending_up", Icons.AutoMirrored.Rounded.TrendingUp),

        // 目标与时间管理
        ProjectIconDef("flag", Icons.Rounded.Flag),
        ProjectIconDef("schedule", Icons.Rounded.Schedule),
        ProjectIconDef("alarm", Icons.Rounded.Alarm),
        ProjectIconDef("check_circle", Icons.Rounded.CheckCircle),

        // 创意与设计
        ProjectIconDef("palette", Icons.Rounded.Palette),
        ProjectIconDef("brush", Icons.Rounded.Brush),
        ProjectIconDef("lightbulb", Icons.Rounded.Lightbulb),
        ProjectIconDef("auto_awesome", Icons.Rounded.AutoAwesome),

        // 学习与研究
        ProjectIconDef("school", Icons.Rounded.School),
        ProjectIconDef("menu_book", Icons.AutoMirrored.Rounded.MenuBook),
        ProjectIconDef("translate", Icons.Rounded.Translate),
        ProjectIconDef("science", Icons.Rounded.Science),

        // 生活、休闲与爱好
        ProjectIconDef("home", Icons.Rounded.Home),
        ProjectIconDef("coffee", Icons.Rounded.Coffee),
        ProjectIconDef("pets", Icons.Rounded.Pets),
        ProjectIconDef("restaurant", Icons.Rounded.Restaurant),
        ProjectIconDef("local_florist", Icons.Rounded.LocalFlorist),
        ProjectIconDef("spa", Icons.Rounded.Spa),
        ProjectIconDef("fitness_center", Icons.Rounded.FitnessCenter),

        // 出行与娱乐
        ProjectIconDef("flight", Icons.Rounded.Flight),
        ProjectIconDef("explore", Icons.Rounded.Explore),
        ProjectIconDef("directions_car", Icons.Rounded.DirectionsCar),
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
                    .heightIn(max = 420.dp),
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
