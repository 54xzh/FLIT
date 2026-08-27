package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil3.compose.rememberAsyncImagePainter
import me.rerere.rikkahub.ui.components.ui.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import me.rerere.rikkahub.R
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.saveMessageImage

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
    prompt: String? = null, // Optional prompt to display
    onReferenceImage: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val state = rememberZoomablePagerState { images.size }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val isDarkMode = LocalDarkMode.current
    
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        // Full screen black background in dark mode
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkMode) Color.Black else Color.Black.copy(alpha = 0.9f))
        ) {
            // Image container - full bleed without rounded corners for proper clipping
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (prompt != null) 120.dp else 80.dp) // Space for prompt and button
            ) {
                ImagePager(
                    modifier = Modifier.fillMaxSize(),
                    pagerState = state,
                    imageLoader = { index ->
                        val painter = rememberAsyncImagePainter(images[index])
                        return@ImagePager Pair(painter, painter.intrinsicSize)
                    },
                )
            }

            // Bottom controls: Prompt text and save button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Prompt text (if provided)
                    if (!prompt.isNullOrBlank()) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Action buttons - aligned to the right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                    ) {
                        if (onReferenceImage != null) {
                            Surface(
                                onClick = {
                                    onReferenceImage(images[state.currentPage])
                                    onDismissRequest()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.AddLink,
                                        contentDescription = stringResource(R.string.reference_image),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        Surface(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        toaster.show("Saving...")
                                        val imgUrl = images[state.currentPage]
                                        context.saveMessageImage(imgUrl)
                                        toaster.show(message = "Image saved", type = ToastType.Success)
                                        onDismissRequest()
                                    }.onFailure {
                                        it.printStackTrace()
                                        toaster.show(
                                            message = it.toString(),
                                            type = ToastType.Error
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = stringResource(R.string.save),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
