package com.notcode.mobile.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserPreviewTest {
    @Test fun localHtmlRunsJavaScriptAndLoadsStyles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val project = File(context.filesDir, "projects/preview-instrumentation-${System.nanoTime()}")
        val root = File(project, "work").apply { mkdirs() }.canonicalFile
        File(root, "index.html").writeText("<html><head><link rel='stylesheet' href='style.css'></head><body><div id='result'>before</div><script src='app.js'></script></body></html>")
        File(root, "style.css").writeText("#result { color: rgb(12, 34, 56); }")
        File(root, "app.js").writeText("document.getElementById('result').textContent='JavaScript ran';")
        val controller = PreviewController(context)
        var view: WebView? = null
        try {
            controller.openProjectFile(root, com.notcode.mobile.filesystem.ProjectPath.parse("index.html"))
            assertNull(controller.state.value.error)
            instrumentation.runOnMainSync {
                view = createPreviewWebView(context, controller).also { it.loadUrl(requireNotNull(controller.state.value.url)) }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var rendered = false
            while (!rendered && System.nanoTime() < deadline) {
                val latch = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    view!!.evaluateJavascript("(function(){var e=document.getElementById('result');return !!e && e.textContent==='JavaScript ran' && getComputedStyle(e).color==='rgb(12, 34, 56)';})()") {
                        rendered = it == "true"
                        latch.countDown()
                    }
                }
                assertTrue(latch.await(2, TimeUnit.SECONDS))
            }
            assertTrue("HTML, external JS and CSS must render in the real WebView: ${controller.state.value.error}", rendered)
            instrumentation.runOnMainSync {
                assertFalse(view!!.settings.allowFileAccess)
                assertFalse(view!!.settings.allowContentAccess)
                assertFalse(view!!.settings.allowUniversalAccessFromFileURLs)
                assertFalse(android.webkit.CookieManager.getInstance().acceptThirdPartyCookies(view))
            }
        } finally {
            instrumentation.runOnMainSync { view?.destroy() }
            controller.close()
            project.deleteRecursively()
        }
    }
}
