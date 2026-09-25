package app.ee.core.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Shared HTTP client factory (docs/02-specification.md §4.4): one tuned
 * client for downloads, providers use it for their protocol traffic.
 * TLS uses the system trust store; user-imported CAs land with the network
 * features (M3).
 */
object HttpClientFactory {

    fun default(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
