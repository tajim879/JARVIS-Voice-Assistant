package com.jarvis.assistant.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Currency
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One cryptocurrency row from CoinGecko, priced in USD. */
data class CryptoCoin(
    val id: String,
    val symbol: String,
    val name: String,
    val priceUsd: Double,
    val change24hPercent: Double?,
    val marketCapUsd: Double?
)

/**
 * Live currency data.
 *  - Crypto: CoinGecko public API (no key), refreshed at most every 30 seconds.
 *  - Fiat: ExchangeRate-API open endpoint (no key, 160+ currencies, updated daily).
 * Everything is pivoted through USD so crypto<->fiat<->crypto conversion all work.
 */
object CurrencyRepository {

    private const val TAG = "CurrencyRepository"
    private const val CRYPTO_URL =
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc" +
            "&per_page=50&page=1&sparkline=false&price_change_percentage=24h"
    private const val FIAT_URL = "https://open.er-api.com/v6/latest/USD"

    private const val CRYPTO_TTL_MS = 30_000L
    private const val FIAT_TTL_MS = 30L * 60_000L

    /** Shown first in every picker. */
    val POPULAR_FIAT = listOf(
        "USD", "EUR", "GBP", "BDT", "INR", "PKR", "JPY", "CNY", "AED", "SAR",
        "CAD", "AUD", "CHF", "SGD", "MYR", "TRY", "RUB", "KRW", "BRL", "NGN"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var fiatRates: Map<String, Double> = emptyMap()
    @Volatile private var fiatFetchedAt: Long = 0L
    @Volatile private var coins: List<CryptoCoin> = emptyList()
    @Volatile private var coinsFetchedAt: Long = 0L

    /** Epoch millis of the newest successful crypto refresh (0 if never). */
    fun cryptoUpdatedAt(): Long = coinsFetchedAt

    fun fiatUpdatedAt(): Long = fiatFetchedAt

    fun cachedCoins(): List<CryptoCoin> = coins

    fun cachedFiatRates(): Map<String, Double> = fiatRates

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "JARVIS-Assistant")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP " + response.code)
            }
            return response.body?.string() ?: throw java.io.IOException("Empty response")
        }
    }

    /** Returns cached rates when fresh; otherwise refreshes. Falls back to stale cache on failure. */
    suspend fun getFiatRates(force: Boolean = false): Map<String, Double> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && fiatRates.isNotEmpty() && now - fiatFetchedAt < FIAT_TTL_MS) {
            return@withContext fiatRates
        }
        try {
            val root = JSONObject(httpGet(FIAT_URL))
            if (root.optString("result") == "success") {
                val ratesJson = root.getJSONObject("rates")
                val map = HashMap<String, Double>()
                val keys = ratesJson.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val value = ratesJson.optDouble(code, Double.NaN)
                    if (!value.isNaN() && value > 0.0) map[code.uppercase(Locale.ROOT)] = value
                }
                if (map.isNotEmpty()) {
                    fiatRates = map
                    fiatFetchedAt = now
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fiat refresh failed: " + e.message)
        }
        fiatRates
    }

    /** Returns cached coins when fresh; otherwise refreshes. Falls back to stale cache on failure/rate limit. */
    suspend fun getCoins(force: Boolean = false): List<CryptoCoin> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && coins.isNotEmpty() && now - coinsFetchedAt < CRYPTO_TTL_MS) {
            return@withContext coins
        }
        try {
            val arr = JSONArray(httpGet(CRYPTO_URL))
            val list = ArrayList<CryptoCoin>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val price = o.optDouble("current_price", Double.NaN)
                if (price.isNaN()) continue
                list.add(
                    CryptoCoin(
                        id = o.optString("id"),
                        symbol = o.optString("symbol").uppercase(Locale.ROOT),
                        name = o.optString("name"),
                        priceUsd = price,
                        change24hPercent = if (o.isNull("price_change_percentage_24h")) null
                        else o.optDouble("price_change_percentage_24h"),
                        marketCapUsd = if (o.isNull("market_cap")) null else o.optDouble("market_cap")
                    )
                )
            }
            if (list.isNotEmpty()) {
                coins = list
                coinsFetchedAt = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crypto refresh failed: " + e.message)
        }
        coins
    }

    /** Loads both sources. Never throws. */
    suspend fun refreshAll(force: Boolean = false) {
        getFiatRates(force)
        getCoins(force)
    }

    fun isFiat(code: String): Boolean = fiatRates.containsKey(code.uppercase(Locale.ROOT))

    private fun findCoin(codeOrName: String): CryptoCoin? {
        val q = codeOrName.trim()
        if (q.isEmpty()) return null
        return coins.firstOrNull { it.symbol.equals(q, ignoreCase = true) }
            ?: coins.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: coins.firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    /** Value of ONE unit of the asset in USD, or null if the asset is unknown. */
    fun usdValueOf(asset: String): Double? {
        val code = asset.trim().uppercase(Locale.ROOT)
        if (code == "USD") return 1.0
        // A crypto symbol wins over a fiat code only when no fiat code matches.
        fiatRates[code]?.let { return 1.0 / it }
        return findCoin(asset)?.priceUsd
    }

    /** Converts amount between any two known fiat codes or crypto symbols/names. */
    fun convert(amount: Double, from: String, to: String): Double? {
        val fromUsd = usdValueOf(from) ?: return null
        val toUsd = usdValueOf(to) ?: return null
        if (toUsd == 0.0) return null
        return amount * fromUsd / toUsd
    }

    fun symbolOf(asset: String): String {
        val code = asset.trim().uppercase(Locale.ROOT)
        if (fiatRates.containsKey(code)) return code
        return findCoin(asset)?.symbol ?: code
    }

    /** Localized display name (follows the device language) for a fiat code. */
    fun fiatDisplayName(code: String): String {
        return try {
            Currency.getInstance(code.uppercase(Locale.ROOT)).displayName
        } catch (e: Exception) {
            code
        }
    }

    /** Fiat codes (popular first) followed by top crypto symbols. */
    fun allAssets(): List<String> {
        val fiatAll = fiatRates.keys.sorted()
        val popular = POPULAR_FIAT.filter { fiatAll.contains(it) }
        val rest = fiatAll.filter { !popular.contains(it) }
        val crypto = coins.map { it.symbol }.distinct().filter { !fiatRates.containsKey(it) }
        return popular + crypto + rest
    }

    fun assetLabel(asset: String): String {
        val code = asset.trim().uppercase(Locale.ROOT)
        if (fiatRates.containsKey(code)) return code + "  -  " + fiatDisplayName(code)
        val coin = findCoin(asset)
        return if (coin != null) coin.symbol + "  -  " + coin.name else code
    }

    /** Pretty number: big values 2 decimals, small crypto values keep significant digits. */
    fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "-"
        val abs = Math.abs(value)
        val pattern = when {
            abs >= 1000.0 -> "%,.2f"
            abs >= 1.0 -> "%,.4f"
            abs >= 0.01 -> "%.6f"
            abs == 0.0 -> "%.2f"
            else -> "%.10f"
        }
        return String.format(Locale.getDefault(), pattern, value)
    }
}
