package org.jjgroup.xproxy.target.model

data class SiteMapEntry(
    val key: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val path: String,
    var method: String,
    var statusCode: Int,
    var mimeType: String,
    var length: Int,
    var tls: Boolean,
    var requestRaw: String,
    var responseRaw: String,
    var title: String,
    var count: Int,
    var lastSeenMillis: Long
)
