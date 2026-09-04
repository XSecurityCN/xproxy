package org.jjgroup.xproxy.ui.http

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.request.detectBodyKind
import org.jjgroup.xproxy.fuzzer.request.formatBody
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.settings.core.CharsetPolicy
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings

internal data class RequestDisplayState(
    val rawText: String,
    val prettyText: String,
    val kind: BodyKind,
    val sizeHint: Int,
    val disableHighlight: Boolean,
    val headersOnlyHighlight: Boolean
)

internal fun buildRequestDisplayState(rawText: String): RequestDisplayState {
    val previewThreshold = ResponsePrettySettings.getLargeResponsePreviewMaxChars().coerceAtLeast(1024)
    val threshold = ResponsePrettySettings.getAutoPrettyMaxBytes().coerceAtLeast(0)
    val parsed = splitMessage(rawText)
    val headers = parseHeaders(parsed.headers)
    val bodySizeHint = parsed.body.length
    val rawPreviewThreshold = ResponsePrettySettings.getRawBodyPreviewMaxBytes().coerceAtLeast(0)
    val rawBodyStats = bodyDisplayStats(parsed.body)
    val large = rawText.length > previewThreshold || bodySizeHint > rawPreviewThreshold || rawBodyStats.shouldUseSmoothTextMode
    if (large) {
        val bodyPlaceholder = largeBodyPlaceholder("Large request", bodySizeHint, headers["content-type"]?.substringBefore(';')?.trim().orEmpty(), previewThreshold)
        return RequestDisplayState(
            rawText = joinMessageForViewer(parsed.headers, parsed.separator, bodyPlaceholder),
            prettyText = "Large request detected (${bodySizeHint} bytes). Pretty formatting is disabled; Raw shows headers only for smooth browsing.",
            kind = BodyKind.OTHER,
            sizeHint = bodySizeHint,
            disableHighlight = true,
            headersOnlyHighlight = true
        )
    }
    val decodedBody = CharsetPolicy.decodeBodyForDisplay(parsed.headers, parsed.body)
    val preview = buildViewerPreview(
        decodedBody,
        previewThreshold,
        "request body",
        knownFullLength = maxOf(bodySizeHint, decodedBody.length)
    )
    val bodyPreview = preview.text
    val displayStats = bodyDisplayStats(decodedBody)
    val kind = detectBodyKind(headers, bodyPreview)
    val rawRendered = joinMessageForViewer(parsed.headers, parsed.separator, bodyPreview)
    val prettyRendered = if (preview.truncated || bodySizeHint > threshold) {
        "Large request detected (${bodySizeHint} bytes). Pretty formatting is disabled; Raw shows a ${decodedBody.length.coerceAtMost(previewThreshold)}-char preview."
    } else {
        val prettyBody = if (kind == BodyKind.JSON || kind == BodyKind.HTML) {
            formatBody(decodedBody, kind, parsed.separator)
        } else {
            decodedBody
        }
        joinMessageForViewer(parsed.headers, parsed.separator, prettyBody)
    }
    return RequestDisplayState(
        rawText = rawRendered,
        prettyText = prettyRendered,
        kind = kind,
        sizeHint = bodySizeHint,
        disableHighlight = bodySizeHint > threshold || !displayStats.shouldHighlightBody || displayStats.shouldUseTextPerformanceMode,
        headersOnlyHighlight = bodySizeHint > threshold || !displayStats.shouldHighlightBody || displayStats.shouldUseTextPerformanceMode || isBinaryLikeRequestBody(decodedBody, kind)
    )
}

private fun isBinaryLikeRequestBody(bodyText: String, kind: BodyKind): Boolean {
    if (kind != BodyKind.OTHER) {
        return false
    }
    val sample = bodyText.take(4096)
    if (sample.isEmpty()) {
        return false
    }
    val control = sample.count { ch ->
        (ch.code in 0..8) || ch.code == 11 || ch.code == 12 || (ch.code in 14..31) || ch.code == 0xFFFD
    }
    return control > 0 || control * 100 / sample.length > 2
}
