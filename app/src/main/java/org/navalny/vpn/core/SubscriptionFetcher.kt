package org.navalny.vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class FetchedSubscription(
    val title: String?,
    val servers: List<ServerProfile>,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
)

/**
 * Downloads a subscription with plain HttpURLConnection (no OkHttp — smaller app,
 * nothing running in the background). Updates only happen on demand / app start.
 */
object SubscriptionFetcher {

    // Panels (Marzban, 3x-ui, Remnawave...) choose the output format by User-Agent;
    // a v2rayNG-compatible UA makes them return a plain list of share links.
    private const val USER_AGENT = "v2rayNG/1.10.0 (NavalnyVPN)"

    suspend fun fetch(url: String, subscriptionId: String): FetchedSubscription = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val servers = LinkParser.parseMany(body, subscriptionId)
            if (servers.isEmpty()) error("В подписке нет серверов Hysteria2 / VLESS")

            val info = parseUserInfo(conn.getHeaderField("subscription-userinfo"))
            FetchedSubscription(
                title = parseTitle(conn.getHeaderField("profile-title"))
                    ?: parseContentDispositionName(conn.getHeaderField("content-disposition")),
                servers = servers,
                upload = info["upload"] ?: 0,
                download = info["download"] ?: 0,
                total = info["total"] ?: 0,
                expire = info["expire"] ?: 0,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parseUserInfo(header: String?): Map<String, Long> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split(';').mapNotNull { part ->
            val kv = part.trim().split('=')
            if (kv.size != 2) null else kv[0].trim().lowercase() to (kv[1].trim().toDoubleOrNull()?.toLong() ?: return@mapNotNull null)
        }.toMap()
    }

    private fun parseTitle(header: String?): String? {
        if (header.isNullOrBlank()) return null
        if (header.startsWith("base64:")) {
            return runCatching { String(Base64.getDecoder().decode(header.removePrefix("base64:")), Charsets.UTF_8) }.getOrNull()
        }
        return header
    }

    private fun parseContentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        if (utf != null) return LinkParser.percentDecode(utf)
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
    }
}
