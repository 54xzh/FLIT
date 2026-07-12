package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem

@Composable
fun ExtensionsPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.extensions_page_title),
                scrollBehavior = scrollBehavior,
                expandedTitleHorizontalPadding = 32.dp,
                navigationIcon = { BackButton() },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
        ) {
            item {
                SettingsGroup(title = stringResource(R.string.extensions_page_section_extensions)) {
                    SettingGroupItem(
                        title = stringResource(R.string.extensions_page_skills),
                        subtitle = stringResource(R.string.extensions_page_skills_desc),
                        icon = { Icon(Icons.Rounded.Extension, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingSkills) },
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.extensions_page_prompts),
                        subtitle = stringResource(R.string.extensions_page_prompts_desc),
                        icon = { Icon(Icons.Rounded.Code, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingModes()) },
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.extensions_page_workspace),
                        subtitle = stringResource(R.string.extensions_page_workspace_desc),
                        icon = { Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.Workspaces) },
                    )
                }
            }
        }
    }
}