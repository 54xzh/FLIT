package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MermaidImageRenderer"
private const val RENDER_HEIGHT_PX = 4000

class MermaidImageRenderer(
    private val activity: Activity,
    private val density: Density,
    private val width: Dp = 540.dp,
    private val timeout: Duration = 5.seconds,
) {
    suspend fun render(
        code: String,
        theme: MermaidTheme,
        colorScheme: ColorScheme,
    ): Bitmap? = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(timeout) {
            renderOnMain(code = code, theme = theme, colorScheme = colorScheme)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun renderOnMain(
        code: String,
        theme: MermaidTheme,
        colorScheme: ColorScheme,
    ): Bitmap? = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val exportRequested = AtomicBoolean(false)
        val widthPx = (density.density * width.value).roundToInt().coerceAtLeast(1)
        val decorView = activity.window.decorView as? ViewGroup
        if (decorView == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val container = FrameLayout(activity).apply {
            alpha = 0f
            visibility = View.VISIBLE
            translationX = -widthPx.toFloat()
            translationY = -RENDER_HEIGHT_PX.toFloat()
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(widthPx, RENDER_HEIGHT_PX)
        }
        val webView = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(widthPx, RENDER_HEIGHT_PX)
            isClickable = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }

        fun cleanup() {
            runCatching {
                webView.stopLoading()
                webView.removeJavascriptInterface("AndroidInterface")
                container.removeView(webView)
                (container.parent as? ViewGroup)?.removeView(container)
                webView.destroy()
            }.onFailure {
                Log.w(TAG, "Failed to clean up Mermaid export WebView", it)
            }
        }

        fun finish(bitmap: Bitmap?) {
            if (!completed.compareAndSet(false, true)) return
            cleanup()
            continuation.resume(bitmap)
        }

        val jsInterface = object {
            @JavascriptInterface
            fun updateHeight(height: Int) {
                if (!exportRequested.compareAndSet(false, true)) return
                webView.post {
                    webView.evaluateJavascript("exportSvgToPng();", null)
                }
            }

            @JavascriptInterface
            fun exportImage(base64Image: String) {
                if (base64Image.isBlank()) {
                    webView.post { finish(null) }
                    return
                }
                val bitmap = runCatching {
                    val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }.onFailure {
                    Log.w(TAG, "Failed to decode Mermaid export image", it)
                }.getOrNull()
                webView.post { finish(bitmap) }
            }
        }

        continuation.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post {
                if (completed.compareAndSet(false, true)) {
                    cleanup()
                }
            }
        }

        webView.addJavascriptInterface(jsInterface, "AndroidInterface")
        container.addView(webView)
        decorView.addView(container)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(RENDER_HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        container.layout(0, 0, widthPx, RENDER_HEIGHT_PX)
        webView.loadDataWithBaseURL(
            null,
            buildMermaidHtml(code = code, theme = theme, colorScheme = colorScheme),
            "text/html",
            "UTF-8",
            null,
        )
    }
}
