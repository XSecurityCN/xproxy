package org.jjgroup.xproxy.ui.http

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.request.applySyntax
import org.jjgroup.xproxy.fuzzer.request.decodeResponseBody
import org.jjgroup.xproxy.fuzzer.request.detectBodyKind
import org.jjgroup.xproxy.fuzzer.request.formatBody
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.settings.core.CharsetPolicy
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings
import org.jjgroup.xproxy.ui.highlight.HttpHighlighter
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import java.awt.CardLayout
import java.awt.Rectangle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.ImageIcon
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

// SSE 流式刷新时,判断"是否在底部"的阈值(像素):可见区底端距文档底小于该值则视为在底部,新数据到来时跟随滚到底。
private const val SSE_BOTTOM_FOLLOW_THRESHOLD = 48

internal class ResponseRenderer(
    private val responsePrettyArea: RSyntaxTextArea,
    private val responseRawArea: RSyntaxTextArea,
    private val responseRenderArea: RSyntaxTextArea,
    private val responseTabs: JTabbedPane,
    private val responseRenderHtmlPane: JEditorPane,
    private val responseRenderImageLabel: JLabel,
    private val responseRenderUnsupportedLabel: JLabel,
    private val responseRenderCardLayout: CardLayout,
    private val responseRenderCard: JPanel,
    private val renderHtmlCardId: String,
    private val renderImageCardId: String,
    private val renderUnsupportedCardId: String,
    private val responseSearchControllerRefresh: () -> Unit,
    private val responseRenderTabIndexProvider: () -> Int
) {
    val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-response-render").apply { isDaemon = true }
    }

    @Volatile
    var renderGeneration: Long = 0

    @Volatile
    var renderInProgress: Boolean = false

    var currentRawSource: String = ""
    var userChangedTabGeneration: Long = -1L
    var programmaticTabChange = false
    var deferredPrettyResponse: DeferredPrettyResponse? = null
    var deferredPrettyRawResponse: String? = null
    var deferredRenderState: ResponseDisplayState? = null

    // evidence 高亮标签(只清这些,不动语法高亮)。responseRawArea 的 highlighter 同时承载语法高亮,
    // 故不能用 removeAllHighlights,按 tag 精确移除。
    private val evidenceTags = java.util.ArrayList<Any>()
    private val evidencePainter = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(
        org.jjgroup.xproxy.settings.core.UiThemePalette.evidenceHighlight
    )
    // 待应用的 evidence 片段:showResponse 时设置,applyResponseDisplayState 文本就绪后高亮(render 异步)。
    @Volatile
    var pendingEvidence: List<String> = emptyList()

    /**
     * 在原始响应区高亮 evidence 片段(每个片段在响应里所有出现处)。已在展示响应后调用。
     * 片段为空列表则清除高亮。仅高亮 responseRawArea(pretty/render 不做,避免跨格式偏移错位)。
     */
    fun highlightEvidence(snippets: List<String>) {
        val area = responseRawArea
        synchronized(evidenceTags) {
            evidenceTags.toList().forEach { runCatching { area.highlighter.removeHighlight(it) } }
            evidenceTags.clear()
            if (snippets.isEmpty()) return
            val text = area.text
            for (snippet in snippets) {
                if (snippet.isBlank()) continue
                var from = 0
                while (from <= text.length) {
                    val idx = text.indexOf(snippet, from)
                    if (idx < 0) break
                    runCatching {
                        evidenceTags.add(area.highlighter.addHighlight(idx, idx + snippet.length, evidencePainter))
                    }
                    from = idx + snippet.length
                }
            }
        }
    }

    fun renderResponse(rawText: String, preserveUserTab: Boolean = false) {
        if (!renderInProgress && rawText == currentRawSource) {
            return
        }

        val generation = renderGeneration + 1
        renderGeneration = generation
        renderInProgress = true
        deferredPrettyResponse = null
        deferredPrettyRawResponse = null
        deferredRenderState = null

        HttpHighlighter.setPlain(responseRawArea)
        HttpHighlighter.setPlain(responsePrettyArea)
        HttpHighlighter.setPlain(responseRenderArea)

        if (rawText.isEmpty()) {
            responseRawArea.putClientProperty("xproxy.large-viewer-mode", false)
            responsePrettyArea.putClientProperty("xproxy.large-viewer-mode", false)
            responseRenderArea.putClientProperty("xproxy.large-viewer-mode", false)
            responseRawArea.text = ""
            responsePrettyArea.text = ""
            responseRenderArea.text = ""
            responseRenderHtmlPane.text = ""
            responseRenderImageLabel.icon = null
            responseRenderImageLabel.text = ""
            responseRenderUnsupportedLabel.text = "Render supports only HTML and Image resources."
            responseRenderCardLayout.show(responseRenderCard, renderUnsupportedCardId)
            updateRenderTabEnabled(false)
            responseRawArea.caretPosition = 0
            responsePrettyArea.caretPosition = 0
            responseRenderArea.caretPosition = 0
            applySyntax(responsePrettyArea, BodyKind.NONE)
            applySyntax(responseRawArea, BodyKind.NONE)
            programmaticTabChange = true
            responseTabs.selectedIndex = 0
            programmaticTabChange = false
            renderInProgress = false
            currentRawSource = rawText
            responseSearchControllerRefresh()
            return
        }

        setTextSafely(responseRawArea, "Rendering response...")
        setTextSafely(responsePrettyArea, "Rendering response...")
        setTextSafely(responseRenderArea, "Rendering response...")
        updateRenderTabEnabled(false)

        renderExecutor.execute {
            val state = runCatching { buildResponseDisplayState(rawText) }
                .getOrElse {
                    ResponseDisplayState(
                        rawText = rawText,
                        prettyText = rawText,
                        renderText = rawText,
                        renderMode = ResponseRenderMode.UNSUPPORTED,
                        renderHtmlText = null,
                        renderImageBytes = null,
                        renderMessage = "Render failed: ${it.message ?: "unknown error"}",
                        rawKind = BodyKind.OTHER,
                        prettyKind = BodyKind.OTHER,
                        selectedTabIndex = 1,
                        bodySize = rawText.length,
                        disableHighlight = true,
                        headersOnlyHighlight = true,
                        deferredPretty = null,
                        deferredPrettyRaw = null
                    )
                }

            SwingUtilities.invokeLater {
                if (generation != renderGeneration) {
                    return@invokeLater
                }
                applyResponseDisplayState(state, generation, rawText, preserveUserTab)
            }
        }
    }

    private fun setTextSafely(area: RSyntaxTextArea, text: String) {
        try {
            area.text = text
        } catch (_: RuntimeException) {
            SwingUtilities.invokeLater { area.text = text }
        }
    }

    fun buildResponseDisplayState(rawText: String): ResponseDisplayState {
        val parsed = splitMessage(rawText)
        val bodySizeRaw = parsed.body.length
        val threshold = ResponsePrettySettings.getAutoPrettyMaxBytes().coerceAtLeast(0)
        val previewThreshold = ResponsePrettySettings.getLargeResponsePreviewMaxChars().coerceAtLeast(1024)
        val headerMap = parseHeaders(parsed.headers)
        val contentType = headerMap["content-type"]?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val headerKind = inferBodyKindFromContentType(contentType)
        val renderMimeType = inferResponseMimeType(headerMap, headerKind)
        val rawPreviewThreshold = ResponsePrettySettings.getRawBodyPreviewMaxBytes().coerceAtLeast(0)
        if (rawText.length > previewThreshold || bodySizeRaw > rawPreviewThreshold) {
            val previewBudget = previewThreshold.coerceAtLeast(1024)
            val sizeValue = bodySizeRaw
            val bodyKindLarge = headerKind
            val binaryBodyLarge = isBinaryMime(renderMimeType)
            val rawBodyPreview = if (binaryBodyLarge) {
                binaryBodyPlaceholder("Binary", sizeValue, renderMimeType)
            } else {
                largeBodyPlaceholder("Large response", sizeValue, renderMimeType, previewBudget)
            }
            val rawRendered = joinMessageForViewer(parsed.headers, parsed.separator, rawBodyPreview)
            val renderModeLarge = resolveRenderMode(renderMimeType, contentType, bodyKindLarge)
            val effectiveRenderModeLarge = ResponseRenderMode.UNSUPPORTED
            return ResponseDisplayState(
                rawText = rawRendered,
                prettyText = "Large response detected (${sizeValue} bytes). Raw shows headers only; switch to Pretty to render content on demand.",
                renderText = rawRendered,
                renderMode = effectiveRenderModeLarge,
                renderHtmlText = null,
                renderImageBytes = null,
                renderMessage = if (effectiveRenderModeLarge == ResponseRenderMode.UNSUPPORTED) {
                    "Render disabled for large response (${sizeValue} bytes). Use Raw preview or Pretty-on-demand instead."
                } else {
                    renderUnavailableMessage(effectiveRenderModeLarge)
                },
                rawKind = bodyKindLarge,
                prettyKind = BodyKind.OTHER,
                selectedTabIndex = 1,
                bodySize = sizeValue,
                disableHighlight = true,
                headersOnlyHighlight = true,
                deferredPretty = null,
                deferredPrettyRaw = rawText
            )
        }

        if (isBinaryMime(renderMimeType)) {
            val bodySize = parsed.body.length
            val rawPreviewThreshold = ResponsePrettySettings.getRawBodyPreviewMaxBytes().coerceAtLeast(0)
            val rawBody = if (bodySize > rawPreviewThreshold || bodySize > ResponsePrettySettings.getSmoothTextViewMaxChars()) {
                largeBodyPlaceholder("Binary response", bodySize, renderMimeType, rawPreviewThreshold)
            } else {
                foldLongLinesForViewer(parsed.body)
            }
            val rawRendered = joinMessageForViewer(parsed.headers, parsed.separator, rawBody)
            val renderMode = resolveRenderMode(renderMimeType, contentType, BodyKind.OTHER)
            return ResponseDisplayState(
                rawText = rawRendered,
                prettyText = rawRendered,
                renderText = rawRendered,
                renderMode = renderMode,
                renderHtmlText = null,
                renderImageBytes = if (renderMode == ResponseRenderMode.IMAGE) parsed.body.toByteArray(Charsets.ISO_8859_1) else null,
                renderMessage = renderUnavailableMessage(renderMode),
                rawKind = BodyKind.OTHER,
                prettyKind = BodyKind.OTHER,
                selectedTabIndex = 1,
                bodySize = bodySize,
                disableHighlight = true,
                headersOnlyHighlight = true,
                deferredPretty = null,
                deferredPrettyRaw = null
            )
        }

        val decodedBody = if (parsed.body.isNotEmpty()) {
            decodeResponseBody(parsed.headers, parsed.body)
        } else {
            ""
        }
        val bodySize = decodedBody.length
        val decodedForDisplay = CharsetPolicy.decodeBodyForDisplay(parsed.headers, decodedBody)
        val displayStats = bodyDisplayStats(decodedForDisplay)
        val kind = detectBodyKind(headerMap, decodedForDisplay)
        val mimeType = inferResponseMimeType(headerMap, kind)
        val renderMode = resolveRenderMode(mimeType, contentType, kind)
        val disableHtmlRender = renderMode == ResponseRenderMode.HTML && shouldDisableHtmlRender(decodedForDisplay)
        val effectiveRenderMode = if (disableHtmlRender) ResponseRenderMode.UNSUPPORTED else renderMode
        val requiresPrettyFormatting = kind == BodyKind.JSON || kind == BodyKind.HTML
        val blockedByMimeWhitelist = !ResponsePrettySettings.isMimeAllowed(mimeType)
        val useDeferredPretty = requiresPrettyFormatting &&
            (decodedForDisplay.length > threshold || displayStats.shouldUseTextPerformanceMode)
        val disableHighlight = bodySize > threshold || decodedForDisplay.length > threshold || !displayStats.shouldHighlightBody || displayStats.shouldUseTextPerformanceMode

        val binaryBody = isBinaryLikeBody(decodedForDisplay, mimeType, kind)
        val rawBodyForDisplay = if (bodySize > rawPreviewThreshold || displayStats.shouldUseSmoothTextMode) {
            largeBodyPlaceholder(if (binaryBody) "Binary response" else "Large response", bodySize, mimeType, decodedForDisplay.length)
        } else {
            decodedForDisplay
        }
        val rawBodyForViewer = if (rawBodyForDisplay === decodedForDisplay) {
            foldLongLinesForViewer(rawBodyForDisplay)
        } else {
            rawBodyForDisplay
        }
        val rawRendered = if (rawBodyForViewer.isNotEmpty()) {
            parsed.headers + parsed.separator + parsed.separator + rawBodyForViewer
        } else {
            parsed.headers
        }

        if (useDeferredPretty) {
            return ResponseDisplayState(
                rawText = rawRendered,
                prettyText = "Large response detected (${bodySize} bytes). Auto-pretty is disabled. Switch to Pretty to render on demand.",
                renderText = rawRendered,
                renderMode = effectiveRenderMode,
                renderHtmlText = if (effectiveRenderMode == ResponseRenderMode.HTML) decodedForDisplay else null,
                renderImageBytes = if (effectiveRenderMode == ResponseRenderMode.IMAGE) decodedBody.toByteArray(Charsets.ISO_8859_1) else null,
                renderMessage = if (disableHtmlRender) {
                    "Render disabled for large HTML response (${decodedForDisplay.length} chars). Use Raw/Pretty instead."
                } else {
                    renderUnavailableMessage(effectiveRenderMode)
                },
                rawKind = kind,
                prettyKind = BodyKind.OTHER,
                selectedTabIndex = 1,
                bodySize = bodySize,
                disableHighlight = disableHighlight,
                headersOnlyHighlight = disableHighlight || binaryBody,
                deferredPretty = DeferredPrettyResponse(parsed.headers, decodedForDisplay, parsed.separator, kind),
                deferredPrettyRaw = null
            )
        }

        val prettyRendered = when {
            binaryBody && bodySize <= rawPreviewThreshold && !displayStats.shouldUseSmoothTextMode -> {
                joinMessageForViewer(parsed.headers, parsed.separator, foldLongLinesForViewer(decodedForDisplay))
            }
            binaryBody -> joinMessageForViewer(parsed.headers, parsed.separator, largeBodyPlaceholder("Binary response", bodySize, mimeType, decodedForDisplay.length))
            displayStats.shouldUseSmoothTextMode || decodedForDisplay.length > threshold -> {
                joinMessageForViewer(
                    parsed.headers,
                    parsed.separator,
                    largeBodyPlaceholder("Large response", decodedForDisplay.length, mimeType, decodedForDisplay.length)
                )
            }
            else -> {
                val prettyBody = if (requiresPrettyFormatting) {
                    formatBody(decodedForDisplay, kind, parsed.separator)
                } else {
                    foldLongLinesForViewer(decodedForDisplay)
                }
                joinMessageForViewer(parsed.headers, parsed.separator, prettyBody)
            }
        }

        return ResponseDisplayState(
            rawText = rawRendered,
            prettyText = prettyRendered,
            renderText = rawRendered,
            renderMode = effectiveRenderMode,
            renderHtmlText = if (effectiveRenderMode == ResponseRenderMode.HTML) decodedForDisplay else null,
            renderImageBytes = if (effectiveRenderMode == ResponseRenderMode.IMAGE) decodedBody.toByteArray(Charsets.ISO_8859_1) else null,
            renderMessage = if (disableHtmlRender) {
                "Render disabled for large HTML response (${decodedForDisplay.length} chars). Use Raw/Pretty instead."
            } else {
                renderUnavailableMessage(effectiveRenderMode)
            },
            rawKind = kind,
            prettyKind = kind,
            selectedTabIndex = if (blockedByMimeWhitelist || binaryBody || displayStats.shouldUseSmoothTextMode || decodedForDisplay.length > threshold) 1 else 0,
            bodySize = bodySize,
            disableHighlight = disableHighlight,
            headersOnlyHighlight = disableHighlight || binaryBody,
            deferredPretty = null,
            deferredPrettyRaw = null
        )
    }

    fun applyResponseDisplayState(state: ResponseDisplayState, generation: Long, sourceRaw: String, preserveUserTab: Boolean = false) {
        deferredPrettyResponse = state.deferredPretty
        deferredPrettyRawResponse = state.deferredPrettyRaw

        responseRawArea.putClientProperty("xproxy.highlight-size-hint", state.bodySize)
        responsePrettyArea.putClientProperty("xproxy.highlight-size-hint", state.bodySize)
        responseRenderArea.putClientProperty("xproxy.highlight-size-hint", state.bodySize)
        responseRawArea.putClientProperty("xproxy.large-viewer-mode", state.disableHighlight)
        responsePrettyArea.putClientProperty("xproxy.large-viewer-mode", state.disableHighlight)
        responseRenderArea.putClientProperty("xproxy.large-viewer-mode", state.disableHighlight)

        // 流式(SSE)实时刷新前,先记录可见文本区的滚动位置,替换文本后恢复(在底部则跟随新数据滚到底,
        // 否则保持原视图),避免每次刷新都跳回顶部。
        val sseScroll = if (preserveUserTab) captureSseScroll() else null

        responseRawArea.text = state.rawText
        responsePrettyArea.text = state.prettyText
        responseRenderArea.text = state.renderText
        deferredRenderState = state
        resetRenderViewUntilRequested(state)
        updateRenderTabEnabled(state.renderMode != ResponseRenderMode.UNSUPPORTED)
        if (sseScroll != null) {
            restoreSseScroll(sseScroll)
        } else {
            responseRawArea.caretPosition = 0
            responsePrettyArea.caretPosition = 0
            responseRenderArea.caretPosition = 0
        }

        if (state.headersOnlyHighlight) {
            HttpHighlighter.attachHeadersOnly(responseRawArea)
            HttpHighlighter.attachHeadersOnly(responsePrettyArea)
            HttpHighlighter.attachHeadersOnly(responseRenderArea)
        } else if (state.disableHighlight) {
            HttpHighlighter.attachHeadersOnly(responseRawArea)
            HttpHighlighter.attachHeadersOnly(responsePrettyArea)
            HttpHighlighter.attachHeadersOnly(responseRenderArea)
        } else {
            applySyntax(responseRawArea, state.rawKind)
            applySyntax(responsePrettyArea, state.prettyKind)
        }

        // preserveUserTab:流式(SSE)实时刷新时保留用户当前选中的 tab,不重置为 selectedTabIndex。
        val shouldRespectUserSelection = preserveUserTab || (userChangedTabGeneration == generation)
        if (!shouldRespectUserSelection) {
            programmaticTabChange = true
            responseTabs.selectedIndex = state.selectedTabIndex
            programmaticTabChange = false
        }
        renderInProgress = false
        currentRawSource = sourceRaw
        responseSearchControllerRefresh()
        // 语法高亮已就绪,现在叠加 evidence 高亮(在文本设置后,indexOf 才正确)。
        highlightEvidence(pendingEvidence)

        val renderTabIndex = responseRenderTabIndexProvider()
        if (renderTabIndex >= 0 && responseTabs.selectedIndex == renderTabIndex) {
            materializeDeferredRenderResponseIfNeeded()
        }
    }

    private data class SseAreaScroll(val area: RSyntaxTextArea, val showing: Boolean, val wasAtBottom: Boolean, val viewY: Int)

    /** 替换文本前捕获各文本区(可见的那个)的滚动状态:是否在底部 + 视图顶端 y。 */
    private fun captureSseScroll(): List<SseAreaScroll> =
        listOf(responseRawArea, responsePrettyArea, responseRenderArea).map { area ->
            val rect = area.visibleRect
            val showing = area.isShowing && rect.height > 0
            val wasAtBottom = showing && rect.y + rect.height >= area.height - SSE_BOTTOM_FOLLOW_THRESHOLD
            SseAreaScroll(area, showing, wasAtBottom, rect.y)
        }

    /** 替换文本后恢复滚动:在底部则跟随新数据滚到底(caret 置末),否则保持原视图位置。仅处理可见 tab。 */
    private fun restoreSseScroll(captured: List<SseAreaScroll>) {
        for (s in captured) {
            if (!s.showing) {
                continue // 非可见 tab:text= 已置于顶部,不影响显示
            }
            runCatching {
                if (s.wasAtBottom) {
                    s.area.caretPosition = s.area.document.length
                } else {
                    val rect = s.area.visibleRect
                    s.area.scrollRectToVisible(Rectangle(0, s.viewY, rect.width, rect.height.coerceAtLeast(1)))
                }
            }
        }
    }

    fun resetRenderViewUntilRequested(state: ResponseDisplayState) {
        responseRenderHtmlPane.text = ""
        responseRenderImageLabel.icon = null
        responseRenderImageLabel.text = ""
        responseRenderUnsupportedLabel.text = when (state.renderMode) {
            ResponseRenderMode.HTML, ResponseRenderMode.IMAGE -> "Switch to Render tab to load preview."
            ResponseRenderMode.UNSUPPORTED -> state.renderMessage
        }
        responseRenderCardLayout.show(responseRenderCard, renderUnsupportedCardId)
    }

    fun materializeDeferredRenderResponseIfNeeded() {
        val state = deferredRenderState ?: return
        val generation = renderGeneration
        responseRenderArea.text = "Rendering render view..."
        responseRenderArea.caretPosition = 0

        renderExecutor.execute {
            SwingUtilities.invokeLater {
                if (generation != renderGeneration) {
                    return@invokeLater
                }
                applyResponseRenderContent(state)
                deferredRenderState = null
                responseSearchControllerRefresh()
            }
        }
    }

    fun materializeDeferredPrettyResponseIfNeeded() {
        val deferred = deferredPrettyResponse
        val deferredRaw = deferredPrettyRawResponse
        if (deferred == null && deferredRaw == null) {
            return
        }

        val generation = renderGeneration
        responsePrettyArea.text = "Formatting pretty view..."
        responsePrettyArea.caretPosition = 0

        renderExecutor.execute {
            val prettyText = runCatching {
                if (deferred != null) {
                    val prettyBody = formatBody(deferred.decodedBody, deferred.kind, deferred.separator)
                    if (deferred.decodedBody.isNotEmpty()) {
                        deferred.headers + deferred.separator + deferred.separator + prettyBody
                    } else {
                        deferred.headers
                    }
                } else {
                    val raw = deferredRaw.orEmpty()
                    val parsed = splitMessage(raw)
                    val decodedBody = if (parsed.body.isNotEmpty()) {
                        decodeResponseBody(parsed.headers, parsed.body)
                    } else {
                        ""
                    }
                    val decodedForDisplay = CharsetPolicy.decodeBodyForDisplay(parsed.headers, decodedBody)
                    val headers = parseHeaders(parsed.headers)
                    val kind = detectBodyKind(headers, decodedForDisplay)
                    val prettyBody = if (kind == BodyKind.JSON || kind == BodyKind.HTML) {
                        formatBody(decodedForDisplay, kind, parsed.separator)
                    } else {
                        decodedForDisplay
                    }
                    if (decodedForDisplay.isNotEmpty()) {
                        parsed.headers + parsed.separator + parsed.separator + prettyBody
                    } else {
                        parsed.headers
                    }
                }
            }.getOrElse { "Failed to render Pretty view: ${it.message}" }

            SwingUtilities.invokeLater {
                if (generation != renderGeneration) {
                    return@invokeLater
                }
                val displayPrettyText = truncateForViewer(
                    prettyText,
                    ResponsePrettySettings.getLargeResponsePreviewMaxChars(),
                    "pretty response"
                ).first
                responsePrettyArea.text = displayPrettyText
                responsePrettyArea.caretPosition = 0
                val threshold = ResponsePrettySettings.getAutoPrettyMaxBytes().coerceAtLeast(0)
                val sizeHint = (responsePrettyArea.getClientProperty("xproxy.highlight-size-hint") as? Int) ?: responsePrettyArea.text.length
                if (sizeHint > threshold) {
                    HttpHighlighter.attachHeadersOnly(responsePrettyArea)
                } else {
                    applySyntax(responsePrettyArea, BodyKind.OTHER)
                }
                deferredPrettyResponse = null
                deferredPrettyRawResponse = null
                responseSearchControllerRefresh()
            }
        }
    }

    fun applyResponseRenderContent(state: ResponseDisplayState) {
        when (state.renderMode) {
            ResponseRenderMode.HTML -> {
                responseRenderHtmlPane.contentType = "text/html"
                responseRenderHtmlPane.text = sanitizeHtmlForSafeRender(state.renderHtmlText.orEmpty())
                responseRenderHtmlPane.caretPosition = 0
                responseRenderCardLayout.show(responseRenderCard, renderHtmlCardId)
            }

            ResponseRenderMode.IMAGE -> {
                val imageBytes = state.renderImageBytes
                if (imageBytes == null || imageBytes.isEmpty()) {
                    responseRenderImageLabel.icon = null
                    responseRenderImageLabel.text = "Render supports only HTML and Image resources."
                    responseRenderCardLayout.show(responseRenderCard, renderUnsupportedCardId)
                    return
                }
                val icon = ImageIcon(imageBytes)
                if (icon.iconWidth <= 0 || icon.iconHeight <= 0) {
                    responseRenderImageLabel.icon = null
                    responseRenderImageLabel.text = "Failed to render image content."
                    responseRenderCardLayout.show(responseRenderCard, renderUnsupportedCardId)
                    return
                }
                responseRenderImageLabel.text = ""
                responseRenderImageLabel.icon = icon
                responseRenderCardLayout.show(responseRenderCard, renderImageCardId)
            }

            ResponseRenderMode.UNSUPPORTED -> {
                responseRenderHtmlPane.text = ""
                responseRenderImageLabel.icon = null
                responseRenderImageLabel.text = ""
                responseRenderUnsupportedLabel.text = state.renderMessage
                responseRenderCardLayout.show(responseRenderCard, renderUnsupportedCardId)
            }
        }
    }

    fun updateRenderTabEnabled(enabled: Boolean) {
        val renderTabIndex = responseRenderTabIndexProvider()
        if (renderTabIndex < 0) {
            return
        }
        responseTabs.setEnabledAt(renderTabIndex, enabled)
        if (!enabled && responseTabs.selectedIndex == renderTabIndex) {
            programmaticTabChange = true
            responseTabs.selectedIndex = 1
            programmaticTabChange = false
        }
    }
}
