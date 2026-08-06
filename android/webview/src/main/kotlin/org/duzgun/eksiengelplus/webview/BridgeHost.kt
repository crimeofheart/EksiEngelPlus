package org.duzgun.eksiengelplus.webview

import android.content.Context
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest

private val BridgeJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Versioned so a stale page cannot be misread by a newer host, or the reverse. */
@Serializable
data class BridgeEnvelope(
    @SerialName("v") val version: Int = 1,
    val type: String,
    val reqId: String = "",
    val payload: EnqueuePayload? = null,
    val share: SharePayload? = null,
)

@Serializable
data class SharePayload(val url: String = "", val title: String = "")

/**
 * The exact fields EksiEngel_sendMessage posts (script.js:30-45), so the bridge
 * boundary and the operation request share one shape rather than drifting.
 */
@Serializable
data class EnqueuePayload(
    val banSource: Int? = null,
    val banMode: Int? = null,
    val targetType: Int? = null,
    val clickSource: Int? = null,
    val authorName: String? = null,
    val authorId: Long? = null,
    val entryUrl: String? = null,
    val entryId: Long? = null,
    val titleName: String? = null,
    val titleId: Long? = null,
    val timeSpecifier: Int? = null,
)

/**
 * Maps a page payload onto an engine request.
 *
 * Returns null rather than guessing on anything unrecognised: the page is
 * injected into a third-party site that can change under us, so a malformed
 * payload must fail visibly rather than start the wrong operation.
 */
object BridgeMapper {

    fun toRequest(payload: EnqueuePayload): OperationRequest? {
        val source = payload.banSource?.let { BanSource.fromPk(it) } ?: return null
        val mode = payload.banMode?.let { BanMode.fromPk(it) } ?: return null
        val targetType = payload.targetType?.let { TargetType.fromPk(it) } ?: TargetType.USER

        return OperationRequest(
            source = source,
            mode = mode,
            targetType = targetType,
            authorNick = payload.authorName,
            authorId = payload.authorId,
            entryId = payload.entryId ?: entryIdFromUrl(payload.entryUrl),
            titleSlug = payload.titleName,
            titleId = payload.titleId,
            // TimeSpecifier 1 is LAST_24_H, which maps to ?a=dailynice.
            lastDayOnly = payload.timeSpecifier == 1,
        )
    }

    /** The extension takes the trailing digit run (scrapingHandler.js:130). */
    private fun entryIdFromUrl(url: String?): Long? =
        url?.let { Regex("""(\d+)(?!.*\d)""").find(it)?.value?.toLongOrNull() }
}

/**
 * Installs bridge.js and the message channel.
 *
 * addWebMessageListener rather than addJavascriptInterface: the latter exposes
 * its object to EVERY page the WebView loads with no origin scoping, which is a
 * real hole when the WebView browses a user-content site full of arbitrary
 * outbound links.
 */
class BridgeHost(
    private val context: Context,
    private val allowedOrigins: Set<String>,
    private val onEnqueue: (OperationRequest) -> Unit,
    private val onShare: (url: String, title: String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        const val OBJECT_NAME = "EksiEngelPlus"
        private const val ASSET = "bridge.js"
    }

    private var replyProxy: JavaScriptReplyProxy? = null
    private var scriptHandler: ScriptHandler? = null
    private val script: String by lazy {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }

    /**
     * Config is baked into the preamble so the page reads it synchronously.
     *
     * The extension reads it asynchronously (script.js:7-28) and rewrites labels
     * once it arrives, which can render "engelle" before flipping to
     * "sessize al". Inlining removes the race rather than narrowing it.
     */
    private fun preamble(configJson: String, iconDataUri: String): String =
        """
        window.__EKSIENGEL_CONFIG__ = $configJson;
        window.__EKSIENGEL_ICON__ = "$iconDataUri";
        """.trimIndent()

    fun install(webView: WebView, configJson: String, iconDataUri: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                OBJECT_NAME,
                allowedOrigins,
            ) { _, message, _, _, proxy ->
                replyProxy = proxy
                handle(message.data)
            }
        }

        registerDocumentStart(webView, configJson, iconDataUri)
    }

    /**
     * Applies a config change to the page the user is looking at AND to every
     * page loaded afterwards.
     *
     * Two halves because they solve different problems: the live page is already
     * running and can only be told, while the next document reads the preamble
     * before any of our code runs, so the preamble itself has to be replaced. Doing
     * only the push would leave the next navigation rendering the old labels;
     * doing only the re-registration would leave the current page stale until the
     * user navigated.
     */
    fun updateConfig(webView: WebView, configJson: String, iconDataUri: String) {
        registerDocumentStart(webView, configJson, iconDataUri)
        push(webView, "configChanged", configJson)
    }

    /**
     * The previous handler is removed first: addDocumentStartJavaScript accumulates
     * rather than replaces, so leaving it registered would run two preambles, with
     * the stale one winning or losing depending on order.
     */
    private fun registerDocumentStart(webView: WebView, configJson: String, iconDataUri: String) {
        val full = preamble(configJson, iconDataUri) + "\n" + script

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler?.remove()
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, full, allowedOrigins)
        } else {
            // Fallback for older WebViews. bridge.js guards on
            // __eksiEngelBridgeLoaded, so double installation is harmless.
            pendingFallbackScript = full
        }
    }

    /** Set only when document-start injection is unavailable. */
    var pendingFallbackScript: String? = null
        private set

    fun push(webView: WebView, type: String, payloadJson: String) {
        val js = "window.__eksiEngelOnMessage && window.__eksiEngelOnMessage(" +
            "JSON.stringify({type:'$type',payload:$payloadJson}))"
        webView.evaluateJavascript(js, null)
    }

    /**
     * Parsed separately because the share payload has a different shape to the
     * enqueue one, and widening EnqueuePayload to cover both would let a
     * malformed share silently look like a valid operation.
     */
    private fun shareFrom(raw: String) {
        val obj = runCatching {
            BridgeJson.parseToJsonElement(raw).let { el ->
                el as? kotlinx.serialization.json.JsonObject
            }
        }.getOrNull() ?: return
        val payload = obj["payload"] as? kotlinx.serialization.json.JsonObject ?: return
        val url = (payload["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val title = (payload["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (url.isNotBlank()) onShare(url, title)
    }

    private fun handle(raw: String?) {
        val envelope = raw?.let {
            runCatching { BridgeJson.decodeFromString(BridgeEnvelope.serializer(), it) }.getOrNull()
        } ?: return

        val body = raw
        when (envelope.type) {
            "enqueueAction" -> envelope.payload
                ?.let(BridgeMapper::toRequest)
                ?.let(onEnqueue)
                ?: onLog("bridge: unmappable enqueue payload")

            "share" -> shareFrom(body)

            "log" -> onLog("page: ${envelope.payload}")
        }
    }
}
