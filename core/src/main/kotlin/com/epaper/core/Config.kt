package com.epaper.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that depends on the website's markup or on a file host's behaviour lives here,
 * loaded from `extraction_rules.json`, so a site change is a config edit rather than a code change.
 */
@Serializable
data class ExtractionConfig(
    val version: Int,
    val blogBaseUrl: String,
    val feedPath: String,
    val timeZone: String,
    val selectors: Selectors,
    val placeholderLinks: List<String>,
    val offPrefix: String,
    val maxRedirects: Int,
    val rules: List<HostRule>,
    val blockedHosts: List<String>,
) {
    fun ruleFor(url: String): HostRule? {
        val host = hostOf(url) ?: return null
        return rules.firstOrNull { rule -> rule.hosts.any { hostMatches(host, it) } }
    }

    fun isBlocked(host: String): Boolean = blockedHosts.any { hostMatches(host, it) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ExtractionConfig = json.decodeFromString(serializer(), text)

        /** The rules bundled with the library. */
        fun default(): ExtractionConfig {
            val text = ExtractionConfig::class.java.getResourceAsStream("/extraction_rules.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: error("extraction_rules.json missing from classpath")
            return parse(text)
        }
    }
}

@Serializable
data class Selectors(
    val item: String,
    val title: String,
    val subtitle: String,
    val code: String,
    val link: String,
    val categoryAttr: String,
    val postBody: String,
)

@Serializable
enum class Strategy { GOOGLE_DRIVE, DIRECT, BROWSER_ASSISTED }

@Serializable
data class HostRule(
    val name: String,
    val hosts: List<String>,
    val strategy: Strategy,
    /** Page text that means the host deleted the file. */
    val deadMarkers: List<String> = emptyList(),
    /** CSS selector whose `value` (or text) is the file name on the landing page. */
    val fileNameSelector: String? = null,
    /** Regex with one group that captures a human size such as "32.8 MB" from the landing page text. */
    val sizePattern: String? = null,
    /** Buttons the in-app browser presses for the user once they become enabled. */
    val autoClickSelectors: List<String> = emptyList(),
    /** Elements hidden in the in-app browser (ads, upsells). */
    val hideSelectors: List<String> = emptyList(),
    /** Hosts the in-app browser may navigate to without a user gesture. */
    val browserHosts: List<String> = emptyList(),
)

fun hostOf(url: String): String? =
    runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()

/** `sub.example.com` matches `example.com`; `badexample.com` does not. */
fun hostMatches(host: String, pattern: String): Boolean {
    val h = host.lowercase().trimEnd('.')
    val p = pattern.lowercase()
    return h == p || h.endsWith(".$p")
}
