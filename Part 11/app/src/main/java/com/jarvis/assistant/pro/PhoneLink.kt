package com.jarvis.assistant.pro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Phone Link: talks to the JARVIS PC Agent over the local Wi-Fi network.
 * The agent uses HTTPS with a self-signed certificate; this client trusts ONLY the certificate whose
 * SHA-256 fingerprint was pasted during pairing (so nobody else on the network can pretend to be your PC),
 * and every request carries the secret token.
 */
object PhoneLink {

    private const val PREFS = "jarvis_phonelink"

    class Reply(val ok: Boolean, val text: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun host(context: Context): String = prefs(context).getString("host", "") ?: ""
    fun token(context: Context): String = prefs(context).getString("token", "") ?: ""
    fun fingerprint(context: Context): String = prefs(context).getString("fp", "") ?: ""

    fun isConfigured(context: Context): Boolean =
        host(context).isNotBlank() && token(context).isNotBlank() && fingerprint(context).isNotBlank()

    fun save(context: Context, host: String, token: String, fingerprint: String) {
        prefs(context).edit()
            .putString("host", host.trim())
            .putString("token", token.trim())
            .putString("fp", fingerprint.trim().replace(":", "").lowercase())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Pairing code printed by the PC agent: host:port|token|sha256fingerprint. Returns an error text or null. */
    fun applyPairingCode(context: Context, code: String): String? {
        val parts = code.trim().split("|")
        if (parts.size != 3 || !parts[0].contains(":") || parts[1].length < 12 || parts[2].replace(":", "").length != 64) {
            return "That is not a valid pairing code. Copy the whole line that the PC agent prints."
        }
        save(context, parts[0], parts[1], parts[2])
        return null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pinnedClient(fingerprintHex: String): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val actual = if (chain.isNotEmpty()) sha256Hex(chain[0].encoded) else ""
                if (!actual.equals(fingerprintHex, ignoreCase = true)) {
                    throw CertificateException("This is not the paired PC (certificate fingerprint differs).")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun call(context: Context, path: String, body: JSONObject?, timeoutSec: Long = 40L): Reply =
        withContext(Dispatchers.IO) {
            if (!isConfigured(context)) {
                return@withContext Reply(
                    false,
                    "Phone Link is not set up. Run the JARVIS PC Agent on the PC, then paste its pairing code in Settings > Pro Capabilities > Phone Link."
                )
            }
            try {
                val client = pinnedClient(fingerprint(context)).newBuilder()
                    .callTimeout(timeoutSec, TimeUnit.SECONDS).build()
                val builder = Request.Builder()
                    .url("https://" + host(context).trim() + path)
                    .header("X-Jarvis-Token", token(context))
                val request = if (body != null) {
                    builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
                } else {
                    builder.get().build()
                }
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) Reply(true, text)
                    else Reply(false, "PC answered HTTP " + response.code + ": " + text.take(200))
                }
            } catch (e: Exception) {
                Reply(
                    false,
                    "Cannot reach the PC (" + e.javaClass.simpleName + "). Is the JARVIS PC Agent running, " +
                        "and are the phone and PC on the same Wi-Fi?"
                )
            }
        }
}
