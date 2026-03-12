@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.webview.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.webview.jsbridge.JsMessage
import io.github.kdroidfilter.webview.jsbridge.WebViewJsBridge
import io.github.kdroidfilter.webview.setting.WebSettings
import io.github.kdroidfilter.webview.util.KLogger
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLIFrameElement

/**
 * Platform-specific parameters for the WebView factory in WebAssembly/JavaScript.
 */
actual class WebViewFactoryParam {
    var container: Element = document.body!!
    var existingElement: HTMLIFrameElement? = null
}

/**
 * Default factory function for creating a WebView on the WebAssembly/JavaScript platform.
 */
actual fun defaultWebViewFactory(param: WebViewFactoryParam): NativeWebView {
    val iframe = param.existingElement ?: document.createElement("iframe") as HTMLIFrameElement

    iframe.style.apply {
        border = "none"
        width = "100%"
        height = "100%"
    }

    return NativeWebView(iframe)
}

/**
 * Factory function for creating a WebView with WebSettings applied.
 */
fun createWebViewWithSettings(
    param: WebViewFactoryParam,
    settings: WebSettings,
): NativeWebView {
    val iframe = param.existingElement ?: document.createElement("iframe") as HTMLIFrameElement
    val wasmSettings = settings.wasmJSWebSettings

    iframe.style.apply {
        width = "100%"
        height = "100%"

        border = if (wasmSettings.showBorder) {
            wasmSettings.borderStyle
        } else {
            "none"
        }

        val bgColor = (wasmSettings.backgroundColor ?: settings.backgroundColor)
        if (bgColor != androidx.compose.ui.graphics.Color.Transparent) {
            backgroundColor = "#${bgColor.value.toString(16).padStart(8, '0').substring(2)}"
        }

        wasmSettings.customContainerStyle?.let { customStyle ->
            cssText += "; $customStyle"
        }
    }

    if (wasmSettings.enableSandbox) {
        iframe.setAttribute(
            qualifiedName = "sandbox",
            value = wasmSettings.sandboxPermissions
        )
    }

    if (wasmSettings.allowFullscreen) {
        iframe.setAttribute(
            qualifiedName = "allowfullscreen",
            value = "true"
        )
    }

    return NativeWebView(iframe)
}

/**
 * Simple state adapter for WebView state synchronization
 */
@Composable
internal fun rememberWebViewStateAdapter(
    commonWebViewState: WebViewState
): WebViewStateAdapter = remember(commonWebViewState) {
    WebViewStateAdapter(commonWebViewState)
}

internal class WebViewStateAdapter(
    private val commonWebViewState: WebViewState,
    private val wasmWebViewState: WasmJsWebViewState = WasmJsWebViewState(),
) {
    fun syncFromCommon() {
        when (val content = commonWebViewState.content) {
            is WebContent.Url -> {
                wasmWebViewState.url = content.url
                wasmWebViewState.content = ""
            }

            is WebContent.Data -> {
                wasmWebViewState.content = content.data
                wasmWebViewState.url = ""
            }

            is WebContent.File -> {
                wasmWebViewState.content = ""
                wasmWebViewState.url = ""
            }

            is WebContent.NavigatorOnly -> {
                wasmWebViewState.url = ""
                wasmWebViewState.content = ""
            }
        }

        commonWebViewState.lastLoadedUrl?.let {
            wasmWebViewState.lastLoadedUrl = it
        }

        commonWebViewState.pageTitle?.let {
            wasmWebViewState.pageTitle = it
        }
    }

    fun syncToCommon() {
        wasmWebViewState.lastLoadedUrl?.let {
            commonWebViewState.lastLoadedUrl = it
        }

        wasmWebViewState.pageTitle?.let {
            commonWebViewState.pageTitle = it
        }

        if (wasmWebViewState.isLoading) {
            commonWebViewState.loadingState = LoadingState.Loading(0f)
        } else {
            commonWebViewState.loadingState = LoadingState.Finished
        }
    }

    fun getWasmWebViewState(): WasmJsWebViewState = wasmWebViewState
}

/**
 * Implementation of the WebView composable for the WebAssembly/JavaScript platform.
 */
@Composable
actual fun ActualWebView(
    state: WebViewState,
    modifier: Modifier,
    navigator: WebViewNavigator,
    webViewJsBridge: WebViewJsBridge?,
    onCreated: (NativeWebView) -> Unit,
    onDispose: (NativeWebView) -> Unit,
    factory: (WebViewFactoryParam) -> NativeWebView
) {
    val scope = rememberCoroutineScope()
    val stateAdapter = rememberWebViewStateAdapter(state)
    val htmlNavigator = rememberHtmlViewNavigator()
    val htmlViewState = remember { HtmlViewState() }

    LaunchedEffect(navigator, htmlNavigator) {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(100)
                navigator.canGoBack = htmlNavigator.canGoBack
                navigator.canGoForward = htmlNavigator.canGoForward
            }
        }
    }

    LaunchedEffect(state.content) {
        stateAdapter.syncFromCommon()

        when (state.content) {
            is WebContent.Url -> {
                htmlViewState.content = HtmlContent.Url(
                    (state.content as WebContent.Url).url,
                    (state.content as WebContent.Url).additionalHttpHeaders,
                )
            }

            is WebContent.Data -> {
                val data = (state.content as WebContent.Data).data
                val htmlWithBridge = if (webViewJsBridge != null) {
                    injectJsBridgeToHtml(data, webViewJsBridge.jsBridgeName)
                } else {
                    data
                }

                htmlViewState.content = HtmlContent.Data(
                    data = htmlWithBridge,
                    baseUrl = (state.content as WebContent.Data).baseUrl
                )
            }

            is WebContent.File -> {
                val fileName = (state.content as WebContent.File).fileName
                val fileReadType = (state.content as WebContent.File).readType

                val webView = state.webView
                if (webView != null) {
                    webView.loadHtmlFile(fileName, fileReadType)
                } else {
                    htmlViewState.loadingState = HtmlLoadingState.Loading
                }
            }

            is WebContent.NavigatorOnly -> {
                // No action needed
            }
        }
    }

    LaunchedEffect(htmlViewState.lastLoadedUrl, htmlViewState.pageTitle, htmlViewState.loadingState) {
        val wasmState = stateAdapter.getWasmWebViewState()

        htmlViewState.lastLoadedUrl?.let { wasmState.lastLoadedUrl = it }
        htmlViewState.pageTitle?.let { wasmState.pageTitle = it }

        wasmState.isLoading = when (htmlViewState.loadingState) {
            is HtmlLoadingState.Loading -> true
            is HtmlLoadingState.Finished -> false
            is HtmlLoadingState.Initializing -> false
        }

        stateAdapter.syncToCommon()
    }

    HtmlView(
        state = htmlViewState,
        modifier = modifier,
        navigator = htmlNavigator,
        onCreated = { element ->
            val nativeWebView = if (
                state.webSettings.wasmJSWebSettings.let {
                    it.backgroundColor != null ||
                            it.showBorder ||
                            it.enableSandbox ||
                            it.customContainerStyle != null
                }
            ) {
                createWebViewWithSettings(
                    WebViewFactoryParam().apply {
                        existingElement = element
                    },
                    state.webSettings
                )
            } else {
                factory(
                    WebViewFactoryParam().apply {
                        existingElement = element
                    }
                )
            }

            val webViewWrapper = WasmJsWebView(
                element = element,
                nativeWebView = nativeWebView,
                scope = scope,
                webViewJsBridge = webViewJsBridge
            )

            state.webView = webViewWrapper

            if (webViewJsBridge != null) {
                setupJsBridgeForWasm(element, webViewJsBridge, webViewWrapper)
            }

            if (state.content is WebContent.File) {
                val fileName = (state.content as WebContent.File).fileName
                val readType = (state.content as WebContent.File).readType
                scope.launch {
                    webViewWrapper.loadHtmlFile(fileName, readType)
                }
            }

            onCreated(nativeWebView)
        },
        onDispose = { element ->
            state.webView?.let {
                onDispose(NativeWebView(element))
                state.webView = null
            }
        }
    )
}

/**
 * Set up the JavaScript bridge for WasmJS platform
 */
private fun setupJsBridgeForWasm(
    element: HTMLIFrameElement,
    webViewJsBridge: WebViewJsBridge,
    webViewWrapper: WasmJsWebView
) {
    val messageHandler: (org.w3c.dom.events.Event) -> Unit = { event ->
        val messageEvent = event as org.w3c.dom.MessageEvent

        if (messageEvent.source == element.contentWindow && messageEvent.data != null) {
            try {
                val dataString = messageEvent.data.toString()

                if (dataString.contains(webViewJsBridge.jsBridgeName) && dataString.startsWith("{")) {
                    val actionPattern = """"action"\s*:\s*"([^"]*)"""".toRegex()
                    val paramsPattern = """"params"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
                    val callbackPattern = """"callbackId"\s*:\s*(\d+)""".toRegex()

                    val actionMatch = actionPattern.find(dataString)
                    val paramsMatch = paramsPattern.find(dataString)
                    val callbackMatch = callbackPattern.find(dataString)

                    if (actionMatch != null) {
                        val action = actionMatch.groupValues[1]
                        val rawParams = paramsMatch?.groupValues?.get(1) ?: "{}"
                        val params = rawParams.replace("\\\"", "\"").replace("\\\\", "\\")
                        val callbackId = callbackMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                        val jsMessage = JsMessage(
                            callbackId = callbackId,
                            methodName = action,
                            params = params,
                        )

                        webViewJsBridge.dispatch(jsMessage)
                    }
                }
            } catch (e: Exception) {
                KLogger.e(
                    t = e,
                    tag = "WasmJsWebView"
                ) {
                    "Error processing message: ${e.message}"
                }
            }
        }
    }

    kotlinx.browser.window.addEventListener("message", messageHandler)
    webViewJsBridge.webView = webViewWrapper
}
