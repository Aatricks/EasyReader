package io.aatricks.novelscraper.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL

object UrlSecurity {

    suspend fun isSafeUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val uri = URL(url)

            // 1. Validate Scheme
            if (uri.protocol != "http" && uri.protocol != "https") {
                return@runCatching false
            }

            val host = uri.host ?: return@runCatching false

            // 2. Check for private IPs / Localhost
            // resolving the host to check if it points to a local address
            val address = InetAddress.getByName(host)

            when {
                address.isLoopbackAddress -> false
                address.isSiteLocalAddress -> false
                address.isLinkLocalAddress -> false
                address.isAnyLocalAddress -> false
                // Check for 192.168.x.x, 10.x.x.x, 172.16.x.x - 172.31.x.x
                // isSiteLocalAddress covers 192.168.x.x, 10.x.x.x and 172.16.x.x range
                else -> true
            }
        }.getOrElse {
            // If the URL is malformed or host cannot be resolved, we treat it as unsafe
            // for the purpose of an external intent.
            false
        }
    }
}
