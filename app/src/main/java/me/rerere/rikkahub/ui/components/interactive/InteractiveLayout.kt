package me.rerere.rikkahub.ui.components.interactive

import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Companion.WeightProperty
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

private val VerticalSpacing = 16.dp
private val HorizontalSpacing = 12.dp

/** 待到达的子组件不占一排加载框；每个引用独立订阅，完成后加入布局。 */
@Composable
private fun A2uiComponentScope.visibleChildren(children: List<A2uiComponentReference>): List<Pair<A2uiComponentReference, A2uiComponentState>> =
    buildList {
        children.forEach { reference ->
            key(reference.id, reference.baseDataPath) {
                val state = observeA2uiComponentState(reference)
                if (state !is A2uiComponentState.Loading) add(reference to state)
            }
        }
    }

@Composable
private fun InteractiveLayoutChild(state: A2uiComponentState, modifier: Modifier = Modifier) {
    when (state) {
        is A2uiComponentState.Success -> A2uiComponent(component = state.component, modifier = modifier)
        is A2uiComponentState.Error -> Text(stringResource(R.string.interactive_components_error),
            color = MaterialTheme.colorScheme.error, modifier = modifier)
        A2uiComponentState.Loading -> Unit
    }
}

private fun A2uiComponentState.weight(stretch: Boolean): Float? =
    (this as? A2uiComponentState.Success)?.component?.properties?.get(WeightProperty)?.toFloat()
        ?: if (stretch) 1f else null

internal object InteractiveColumn : A2uiBasicCatalogV1.Column {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, justify: A2uiBasicCatalogV1.Column.Justify,
        align: A2uiBasicCatalogV1.Column.Align, accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?, modifier: Modifier,
    ) {
        val visible = visibleChildren(children)
        val arrangement = when (justify) {
            A2uiBasicCatalogV1.Column.Justify.Start, A2uiBasicCatalogV1.Column.Justify.Stretch -> Arrangement.spacedBy(VerticalSpacing, Alignment.Top)
            A2uiBasicCatalogV1.Column.Justify.Center -> Arrangement.spacedBy(VerticalSpacing, Alignment.CenterVertically)
            A2uiBasicCatalogV1.Column.Justify.End -> Arrangement.spacedBy(VerticalSpacing, Alignment.Bottom)
            A2uiBasicCatalogV1.Column.Justify.SpaceBetween -> Arrangement.SpaceBetween
            A2uiBasicCatalogV1.Column.Justify.SpaceAround -> Arrangement.SpaceAround
            A2uiBasicCatalogV1.Column.Justify.SpaceEvenly -> Arrangement.SpaceEvenly
        }
        val alignment = when (align) {
            A2uiBasicCatalogV1.Column.Align.Start, A2uiBasicCatalogV1.Column.Align.Stretch -> Alignment.Start
            A2uiBasicCatalogV1.Column.Align.Center -> Alignment.CenterHorizontally
            A2uiBasicCatalogV1.Column.Align.End -> Alignment.End
        }
        val stretch = align == A2uiBasicCatalogV1.Column.Align.Stretch
        Column(modifier.interactiveAccessibility(accessibility).then(if (stretch) Modifier.fillMaxWidth() else Modifier),
            verticalArrangement = arrangement, horizontalAlignment = alignment) {
            visible.forEach { (reference, state) ->
                key(reference.id, reference.baseDataPath) {
                    val weight = state.weight(justify == A2uiBasicCatalogV1.Column.Justify.Stretch)
                    val childModifier = (if (stretch) Modifier.fillMaxWidth() else Modifier)
                        .then(if (weight != null) Modifier.weight(weight) else Modifier)
                    InteractiveLayoutChild(state, childModifier)
                }
            }
        }
    }
}

internal object InteractiveRow : A2uiBasicCatalogV1.Row {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, justify: A2uiBasicCatalogV1.Row.Justify,
        align: A2uiBasicCatalogV1.Row.Align, accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?, modifier: Modifier,
    ) {
        val visible = visibleChildren(children)
        val arrangement = when (justify) {
            A2uiBasicCatalogV1.Row.Justify.Start, A2uiBasicCatalogV1.Row.Justify.Stretch -> Arrangement.spacedBy(HorizontalSpacing, Alignment.Start)
            A2uiBasicCatalogV1.Row.Justify.Center -> Arrangement.spacedBy(HorizontalSpacing, Alignment.CenterHorizontally)
            A2uiBasicCatalogV1.Row.Justify.End -> Arrangement.spacedBy(HorizontalSpacing, Alignment.End)
            A2uiBasicCatalogV1.Row.Justify.SpaceBetween -> Arrangement.SpaceBetween
            A2uiBasicCatalogV1.Row.Justify.SpaceAround -> Arrangement.SpaceAround
            A2uiBasicCatalogV1.Row.Justify.SpaceEvenly -> Arrangement.SpaceEvenly
        }
        val alignment = when (align) {
            A2uiBasicCatalogV1.Row.Align.Start, A2uiBasicCatalogV1.Row.Align.Stretch -> Alignment.Top
            A2uiBasicCatalogV1.Row.Align.Center -> Alignment.CenterVertically
            A2uiBasicCatalogV1.Row.Align.End -> Alignment.Bottom
        }
        val stretch = align == A2uiBasicCatalogV1.Row.Align.Stretch
        Row(modifier.interactiveAccessibility(accessibility).then(if (stretch) Modifier.fillMaxHeight() else Modifier),
            horizontalArrangement = arrangement, verticalAlignment = alignment) {
            visible.forEach { (reference, state) ->
                key(reference.id, reference.baseDataPath) {
                    val weight = state.weight(justify == A2uiBasicCatalogV1.Row.Justify.Stretch)
                    val childModifier = (if (stretch) Modifier.fillMaxHeight() else Modifier)
                        .then(if (weight != null) Modifier.weight(weight) else Modifier)
                    InteractiveLayoutChild(state, childModifier)
                }
            }
        }
    }
}

internal object InteractiveList : A2uiBasicCatalogV1.List {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, direction: A2uiBasicCatalogV1.List.Direction,
        align: A2uiBasicCatalogV1.List.Align, accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?, modifier: Modifier,
    ) {
        val visible = visibleChildren(children)
        val vertical = direction == A2uiBasicCatalogV1.List.Direction.Vertical
        val childModifier = when {
            align != A2uiBasicCatalogV1.List.Align.Stretch -> Modifier
            vertical -> Modifier.fillMaxWidth()
            else -> Modifier.fillMaxHeight()
        }
        if (vertical) {
            val alignment = when (align) {
                A2uiBasicCatalogV1.List.Align.Start, A2uiBasicCatalogV1.List.Align.Stretch -> Alignment.Start
                A2uiBasicCatalogV1.List.Align.Center -> Alignment.CenterHorizontally
                A2uiBasicCatalogV1.List.Align.End -> Alignment.End
            }
            LazyColumn(modifier.interactiveAccessibility(accessibility), verticalArrangement = Arrangement.spacedBy(VerticalSpacing),
                horizontalAlignment = alignment) {
                items(visible, key = { it.first.id to it.first.baseDataPath }) { (_, state) -> InteractiveLayoutChild(state, childModifier) }
            }
        } else {
            val alignment = when (align) {
                A2uiBasicCatalogV1.List.Align.Start, A2uiBasicCatalogV1.List.Align.Stretch -> Alignment.Top
                A2uiBasicCatalogV1.List.Align.Center -> Alignment.CenterVertically
                A2uiBasicCatalogV1.List.Align.End -> Alignment.Bottom
            }
            LazyRow(modifier.interactiveAccessibility(accessibility), horizontalArrangement = Arrangement.spacedBy(HorizontalSpacing),
                verticalAlignment = alignment) {
                items(visible, key = { it.first.id to it.first.baseDataPath }) { (_, state) -> InteractiveLayoutChild(state, childModifier) }
            }
        }
    }
}
