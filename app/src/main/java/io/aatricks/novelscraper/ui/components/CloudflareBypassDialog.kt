package io.aatricks.novelscraper.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.aatricks.novelscraper.data.local.PreferencesManager
import io.aatricks.novelscraper.util.NetworkUtils

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareBypassDialog(
    url: String,
    onDismiss: () -> Unit,
    onBypassed: () -> Unit,
    preferencesManager: PreferencesManager
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cloudflare Bypass", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
                
                Text(
                    "Please wait for the challenge to complete. The app will automatically resume once bypassed.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )

                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = preferencesManager.userAgent
                            
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val cookies = CookieManager.getInstance().getCookie(url)
                                    if (cookies != null && !NetworkUtils.isCloudflareChallenge(view?.title ?: "")) {
                                        // Potential bypass
                                        preferencesManager.cookies = cookies
                                        // Update user agent in case it changed or for consistency
                                        preferencesManager.userAgent = settings.userAgentString
                                        
                                        // We check if we are still on the challenge page
                                        view?.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                            if (html != null && !NetworkUtils.isCloudflareChallenge(html)) {
                                                onBypassed()
                                            }
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    return false
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize().weight(1f)
                )
            }
        }
    }
}
