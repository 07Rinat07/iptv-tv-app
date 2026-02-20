package com.iptv.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.ScannerProxySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject

data class NetworkTargetResult(
    val label: String,
    val scope: NetworkProbeScope,
    val dnsOk: Boolean,
    val dnsDetail: String,
    val httpOk: Boolean,
    val httpCode: Int?,
    val httpDetail: String,
    val durationMs: Long
)

enum class NetworkProbeScope {
    API,
    WEB
}

data class NetworkTestUiState(
    val title: String = "Сетевой тест",
    val description: String = "Проверка DNS/API/Web перед запуском сканера",
    val isRunning: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxySummary: String = "proxy: off",
    val summary: String = "Нажмите \"Проверить сеть\", чтобы проверить доступность API и поисковиков.",
    val recommendation: String = "",
    val lastRunAt: String = "-",
    val results: List<NetworkTargetResult> = emptyList(),
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class NetworkTestViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(NetworkTestUiState())
    val uiState: StateFlow<NetworkTestUiState> = _uiState.asStateFlow()

    init {
        observeProxySettings()
    }

    fun runNetworkTest() {
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    summary = "Выполняется проверка DNS и HTTP...",
                    recommendation = "",
                    lastError = null,
                    lastInfo = null
                )
            }

            val startTs = System.currentTimeMillis()
            val proxy = runCatching { settingsRepository.observeScannerProxySettings().first() }.getOrNull()
            val proxySummary = applyProxyProperties(proxy)
            diagnosticsRepository.addLog(
                status = "network_test_start",
                message = "proxy=$proxySummary"
            )

            runCatching {
                val checks = networkTargets()
                val results = coroutineScope {
                    checks.map { target ->
                        async {
                            probeTarget(target)
                        }
                    }.awaitAll()
                }

                results.forEach { result ->
                    diagnosticsRepository.addLog(
                        status = "network_test_item",
                        message = buildString {
                            append("target=${result.label}, scope=${result.scope}, ")
                            append("dns=${if (result.dnsOk) "ok" else "fail"}(${result.dnsDetail}), ")
                            append("http=${if (result.httpOk) "ok" else "fail"}(")
                            append(result.httpCode?.toString() ?: "-")
                            append(", ${result.httpDetail}), ")
                            append("durationMs=${result.durationMs}")
                        }
                    )
                }

                val apiReachable = results
                    .filter { it.scope == NetworkProbeScope.API }
                    .any { it.httpOk || it.dnsOk }
                val webReachable = results
                    .filter { it.scope == NetworkProbeScope.WEB }
                    .any { it.httpOk || it.dnsOk }
                val recommendation = buildRecommendation(
                    apiReachable = apiReachable,
                    webReachable = webReachable
                )

                val elapsed = (System.currentTimeMillis() - startTs).coerceAtLeast(0L)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        proxySummary = "proxy: $proxySummary",
                        summary = "Проверка завершена за ${elapsed}мс | API=${if (apiReachable) "ok" else "fail"} | WEB=${if (webReachable) "ok" else "fail"}",
                        recommendation = recommendation,
                        lastRunAt = formatTimestamp(System.currentTimeMillis()),
                        results = results,
                        lastError = null,
                        lastInfo = "Сетевой тест выполнен"
                    )
                }
                diagnosticsRepository.addLog(
                    status = "network_test_finish",
                    message = "elapsedMs=$elapsed, apiReachable=$apiReachable, webReachable=$webReachable, proxy=$proxySummary"
                )
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        lastError = "Сетевой тест завершился ошибкой: ${throwable.message}",
                        lastInfo = null
                    )
                }
                diagnosticsRepository.addLog(
                    status = "network_test_error",
                    message = throwable.toLogSummary(maxDepth = 5)
                )
            }
        }
    }

    fun clearResults() {
        _uiState.update {
            it.copy(
                summary = "Результаты очищены. Нажмите \"Проверить сеть\" для повторного теста.",
                recommendation = "",
                results = emptyList(),
                lastError = null,
                lastInfo = "Результаты очищены"
            )
        }
    }

    private fun observeProxySettings() {
        viewModelScope.launch {
            settingsRepository.observeScannerProxySettings().collect { proxy ->
                _uiState.update {
                    it.copy(
                        proxyEnabled = proxy.enabled,
                        proxySummary = "proxy: ${proxySummary(proxy)}"
                    )
                }
            }
        }
    }

    private suspend fun probeTarget(target: NetworkTarget): NetworkTargetResult {
        val startedAt = System.currentTimeMillis()
        val dns = probeDns(target.host)
        val http = probeHttp(target.url)
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        return NetworkTargetResult(
            label = target.label,
            scope = target.scope,
            dnsOk = dns.ok,
            dnsDetail = dns.details,
            httpOk = http.ok,
            httpCode = http.code,
            httpDetail = http.details,
            durationMs = elapsed
        )
    }

    private suspend fun probeDns(host: String): ProbeResult {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val resolved = withTimeoutOrNull(DNS_TIMEOUT_MS) {
                runInterruptible {
                    InetAddress.getAllByName(host)
                        .mapNotNull { it.hostAddress }
                        .distinct()
                        .take(2)
                }
            }
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            when {
                resolved == null -> ProbeResult(ok = false, details = "timeout ${DNS_TIMEOUT_MS}ms ($elapsed ms)")
                resolved.isEmpty() -> ProbeResult(ok = false, details = "resolved empty ($elapsed ms)")
                else -> ProbeResult(ok = true, details = "${resolved.joinToString(",")} ($elapsed ms)")
            }
        }
    }

    private suspend fun probeHttp(url: String): ProbeHttpResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = System.currentTimeMillis()
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", NETWORK_TEST_USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,application/json,*/*")
                val code = connection.responseCode
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                connection.disconnect()
                val ok = code in 200..499
                ProbeHttpResult(
                    ok = ok,
                    code = code,
                    details = if (ok) "http $code ($elapsed ms)" else "http $code ($elapsed ms)"
                )
            }.getOrElse { throwable ->
                ProbeHttpResult(
                    ok = false,
                    code = null,
                    details = throwable.toLogSummary(maxDepth = 3).take(220)
                )
            }
        }
    }

    private fun buildRecommendation(apiReachable: Boolean, webReachable: Boolean): String {
        return when {
            apiReachable && webReachable ->
                "Сеть доступна. Рекомендуется режим сканера: Auto."
            apiReachable && !webReachable ->
                "Поисковики недоступны. Рекомендуется режим: Direct API."
            !apiReachable && webReachable ->
                "API недоступны, но поисковики работают. Рекомендуется режим: Search Engine."
            else ->
                "DNS/API/Web недоступны. Проверьте интернет на TV Box, DNS роутера, дату/время устройства и настройки прокси."
        }
    }

    private fun applyProxyProperties(settings: ScannerProxySettings?): String {
        val proxy = settings ?: return "off"
        if (!proxy.enabled) {
            clearProxyProperties()
            return "off"
        }

        val host = proxy.host.trim()
        val port = proxy.port ?: 0
        if (host.isBlank() || port !in 1..65535) {
            clearProxyProperties()
            return "invalid(off)"
        }

        System.setProperty(PROXY_HTTP_HOST, host)
        System.setProperty(PROXY_HTTPS_HOST, host)
        System.setProperty(PROXY_HTTP_PORT, port.toString())
        System.setProperty(PROXY_HTTPS_PORT, port.toString())

        val user = proxy.username.trim()
        if (user.isNotBlank()) {
            System.setProperty(PROXY_SCANNER_USER, user)
            System.setProperty(PROXY_SCANNER_PASS, proxy.password)
        } else {
            System.clearProperty(PROXY_SCANNER_USER)
            System.clearProperty(PROXY_SCANNER_PASS)
        }
        return proxySummary(proxy)
    }

    private fun proxySummary(proxy: ScannerProxySettings): String {
        if (!proxy.enabled) return "off"
        val host = proxy.host.trim()
        val port = proxy.port ?: 0
        if (host.isBlank() || port !in 1..65535) return "invalid(off)"
        val hasUser = proxy.username.trim().isNotBlank()
        return if (hasUser) "on($host:$port, user=${proxy.username.trim()})" else "on($host:$port)"
    }

    private fun clearProxyProperties() {
        System.clearProperty(PROXY_HTTP_HOST)
        System.clearProperty(PROXY_HTTPS_HOST)
        System.clearProperty(PROXY_HTTP_PORT)
        System.clearProperty(PROXY_HTTPS_PORT)
        System.clearProperty(PROXY_SCANNER_USER)
        System.clearProperty(PROXY_SCANNER_PASS)
    }

    private fun networkTargets(): List<NetworkTarget> {
        return listOf(
            NetworkTarget(
                label = "GitHub API",
                host = "api.github.com",
                url = "https://api.github.com/",
                scope = NetworkProbeScope.API
            ),
            NetworkTarget(
                label = "GitLab API",
                host = "gitlab.com",
                url = "https://gitlab.com/api/v4/version",
                scope = NetworkProbeScope.API
            ),
            NetworkTarget(
                label = "Bitbucket API",
                host = "api.bitbucket.org",
                url = "https://api.bitbucket.org/2.0/repositories",
                scope = NetworkProbeScope.API
            ),
            NetworkTarget(
                label = "DuckDuckGo",
                host = "duckduckgo.com",
                url = "https://duckduckgo.com/",
                scope = NetworkProbeScope.WEB
            ),
            NetworkTarget(
                label = "Bing",
                host = "www.bing.com",
                url = "https://www.bing.com/",
                scope = NetworkProbeScope.WEB
            )
        )
    }

    private fun formatTimestamp(ts: Long): String {
        val date = java.util.Date(ts)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return fmt.format(date)
    }

    private data class ProbeResult(
        val ok: Boolean,
        val details: String
    )

    private data class ProbeHttpResult(
        val ok: Boolean,
        val code: Int?,
        val details: String
    )

    private data class NetworkTarget(
        val label: String,
        val host: String,
        val url: String,
        val scope: NetworkProbeScope
    )

    private companion object {
        const val DNS_TIMEOUT_MS = 4_000L
        const val HTTP_TIMEOUT_MS = 7_000
        const val NETWORK_TEST_USER_AGENT = "myscanerIPTV/0.1 network-test"
        const val PROXY_HTTP_HOST = "http.proxyHost"
        const val PROXY_HTTP_PORT = "http.proxyPort"
        const val PROXY_HTTPS_HOST = "https.proxyHost"
        const val PROXY_HTTPS_PORT = "https.proxyPort"
        const val PROXY_SCANNER_USER = "myscaner.proxy.user"
        const val PROXY_SCANNER_PASS = "myscaner.proxy.pass"
    }
}
