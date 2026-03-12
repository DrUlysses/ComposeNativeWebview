@file:OptIn(ExperimentalWasmJsInterop::class)
package io.github.kdroidfilter.webview.web

import org.w3c.dom.Element

/**
 * Evaluate JavaScript in the iframe context
 */
fun evaluateScriptJs(
    element: Element,
    script: String,
): String = js(
    //language=javascript
    """{
        try {
            return element.contentWindow && element.contentWindow.eval ? String(element.contentWindow.eval(script)) : '';
        } catch (err) {
            return 'Error: ' + err.message;
        }
    }"""
)

/**
 * Add a content identifier to an iframe for history tracking
 */
fun addContentIdentifierJs(iframe: Element) {
    js(
        //language=javascript
        """{
            try {
                if (iframe.contentWindow) {
                    const uniqueId = Math.random().toString(36).substring(2, 15);
                    iframe.contentWindow.history.replaceState(
                      { id: uniqueId },
                      '',
                      iframe.contentWindow.location.href
                    );
                }
            } catch (e) {
                console.error("Error adding content identifier:", e);
            }
        }"""
    )
}

/**
 * Request focus on an element
 */
fun requestFocus(element: Element) {
    js("element.focus()")
}
