package com.jarvis.assistant.pro

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/**
 * Coding Agent workspace: a private project folder inside the app where Jarvis writes, reads,
 * edits and tests code. Paths are always relative to the workspace (no ".." escapes).
 */
object CodeWorkspace {

    private const val MAX_FILE_BYTES = 200_000
    private const val MAX_READ_CHARS = 20_000

    fun root(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "workspace")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Returns the file for a relative path, or null if the path tries to leave the workspace. */
    fun resolve(context: Context, relative: String): File? {
        val clean = relative.trim().replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean.contains("\u0000")) return null
        val base = root(context).canonicalFile
        val target = File(base, clean).canonicalFile
        return if (target.path == base.path || target.path.startsWith(base.path + File.separator)) target else null
    }

    fun list(context: Context): List<String> {
        val base = root(context)
        return base.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(base).path.replace(File.separatorChar, '/') }
            .sorted().take(300).toList()
    }

    fun exists(context: Context, relative: String): Boolean = resolve(context, relative)?.isFile == true

    fun read(context: Context, relative: String): String? {
        val f = resolve(context, relative) ?: return null
        if (!f.isFile) return null
        val text = f.readText()
        return if (text.length > MAX_READ_CHARS) text.take(MAX_READ_CHARS) + "\n...[truncated]" else text
    }

    /** Returns an error text or null on success. */
    fun write(context: Context, relative: String, content: String): String? {
        val f = resolve(context, relative) ?: return "That path is not allowed. Use a relative path inside the workspace."
        if (content.toByteArray().size > MAX_FILE_BYTES) return "File is too large (limit 200 KB)."
        f.parentFile?.mkdirs()
        f.writeText(content)
        return null
    }

    /** Replaces exactly one occurrence. Returns an error text or null on success. */
    fun edit(context: Context, relative: String, oldText: String, newText: String): String? {
        val f = resolve(context, relative) ?: return "That path is not allowed."
        if (!f.isFile) return "File not found: " + relative
        if (oldText.isEmpty()) return "old_text is empty."
        val text = f.readText()
        val first = text.indexOf(oldText)
        if (first < 0) return "old_text was not found in the file."
        if (text.indexOf(oldText, first + oldText.length) >= 0) return "old_text appears more than once; include more surrounding lines."
        f.writeText(text.substring(0, first) + newText + text.substring(first + oldText.length))
        return null
    }

    fun delete(context: Context, relative: String): Boolean {
        val f = resolve(context, relative) ?: return false
        return f.isFile && f.delete()
    }
}

/** Runs JavaScript in an isolated, network-blocked WebView and returns what console.log printed. */
object JsSandbox {

    private fun wrap(code: String): String {
        val quoted = JSONObject.quote(code)
        return "(function(){var out=[];" +
            "function fmt(a){try{return (typeof a==='object')?JSON.stringify(a):String(a);}catch(e){return String(a);}}" +
            "var log=function(){out.push(Array.prototype.slice.call(arguments).map(fmt).join(' '));};" +
            "var con={log:log,info:log,warn:log,error:log};" +
            "var assert=function(c,m){if(!c){throw new Error('Assertion failed'+(m?': '+m:''));}};" +
            "try{var r=(new Function('console','assert'," + quoted + "))(con,assert);" +
            "if(r!==undefined){log('=> '+fmt(r));}}catch(e){log('ERROR: '+e.message);}" +
            "return out.join('\\n');})()"
    }

    private fun decode(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else v.toString()
        } catch (e: Exception) {
            raw
        }
    }

    suspend fun run(context: Context, code: String): String = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<String> { cont ->
                val wv = WebView(context.applicationContext)
                var done = false
                fun finish(text: String) {
                    if (done) return
                    done = true
                    try { wv.destroy() } catch (e: Exception) { /* ignore */ }
                    if (cont.isActive) cont.resume(text)
                }
                wv.settings.javaScriptEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.settings.blockNetworkLoads = true
                wv.settings.domStorageEnabled = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(wrap(code)) { raw -> finish(decode(raw)) }
                    }
                }
                cont.invokeOnCancellation { wv.post { try { wv.destroy() } catch (e: Exception) { /* ignore */ } } }
                wv.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
            }
        }
        (result ?: "ERROR: the script did not finish within 10 seconds.").take(6000)
    }
}
