package org.lsposed.lspatch.util

import okhttp3.OkHttpClient
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.matrix.vector.ui.net.HttpClientFactory
import org.matrix.vector.ui.net.VectorDns

/**
 * The one HTTP client the manager uses, for the module store, its downloads and the GitHub feed
 * alike — the LSPatch counterpart of Vector's `ServiceLocator.net`.
 *
 * Built once and shared so its connection pool and disk cache are not thrown away, and so a single
 * [VectorDns] governs every request: the DoH setting and the status the appearance sheet shows
 * describe all of the manager's traffic, not a subset of it. [LSPSettings] supplies the DoH
 * preference the resolver reads per lookup.
 */
object LSPNetwork {

    private val net: HttpClientFactory.NetStack by lazy {
        HttpClientFactory.create(lspApp, LSPSettings)
    }

    /** The shared client. Route every remote fetch through this so DoH and the cache apply. */
    val client: OkHttpClient
        get() = net.client

    /** The resolver inside [client]; its status drives the Network section of the appearance sheet. */
    val dns: VectorDns
        get() = net.dns
}
