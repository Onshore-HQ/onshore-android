package ng.onshore.sdk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import org.json.JSONArray
import org.json.JSONObject

/** What went wrong with a secure component call. It never holds a value. */
sealed class VaultException(code: String) : Exception(code) {
    /** The token is unknown, expired, already used, or minted for another app. Ask your server for a new one. */
    object InvalidClientToken : VaultException("invalid_client_token") { private fun readResolve(): Any = InvalidClientToken }
    /** These fields failed a format check. The token still works, so the person can correct them. */
    class ValidationFailed(val fields: List<String>) : VaultException("validation_failed")
    object Denied : VaultException("policy_denied") { private fun readResolve(): Any = Denied }
    object RateLimited : VaultException("rate_limited") { private fun readResolve(): Any = RateLimited }
    class Server(val status: Int, val serverCode: String) : VaultException("server")
    object Transport : VaultException("transport") { private fun readResolve(): Any = Transport }
}

data class FieldDescription(val name: String, val dataType: String)
data class ComponentDescription(val kind: String, val fields: List<FieldDescription>, val tokens: Int)
data class RevealedValue(val decision: String?, val value: String?, val errorCode: String?) {
    /** Kept out of logs, crash reports and debuggers' string views. */
    override fun toString() = "RevealedValue(decision=$decision, errorCode=$errorCode)"
}

/**
 * Talks to your own install's secure component endpoints. Calls block: run them off the main thread.
 *
 * This holds no credentials. Your server mints a single-use client token and hands it to the
 * app: never put a service account key in an app. [appOrigin] is `rvapp://` and your
 * application id, listed under Secure components for the vault.
 *
 * Pass [socketFactory] to pin your server's certificate.
 */
class SecureClient(
    baseUrl: String,
    private val appOrigin: String,
    private val socketFactory: SSLSocketFactory? = null,
    private val timeoutMs: Int = 15_000,
) {
    private val base = baseUrl.trimEnd('/')

    @Throws(VaultException::class)
    fun describe(clientToken: String): ComponentDescription {
        val j = post("describe", JSONObject().put("client_token", clientToken))
        return parseDescription(j)
    }

    /** Sends values and returns a token for each field, which is safe to give your app and server. */
    @Throws(VaultException::class)
    fun collect(clientToken: String, values: Map<String, String>): Map<String, String> {
        val v = JSONObject()
        values.forEach { (k, x) -> v.put(k, x) }
        val tokens = post("collect", JSONObject().put("client_token", clientToken).put("values", v)).optJSONObject("tokens")
            ?: throw VaultException.Transport
        return tokens.keys().asSequence().associateWith { tokens.getString(it) }
    }

    /** Asks for the values behind the tokens the client token names. Policy decides each one. */
    @Throws(VaultException::class)
    fun reveal(clientToken: String): List<RevealedValue> =
        parseResults(post("reveal", JSONObject().put("client_token", clientToken)).optJSONArray("results"))

    private fun post(path: String, body: JSONObject): JSONObject {
        body.put("parent_origin", appOrigin)
        val conn = try {
            (URL("$base/embed/v1/$path").openConnection() as HttpURLConnection)
        } catch (_: IOException) {
            throw VaultException.Transport
        }
        try {
            if (conn is HttpsURLConnection && socketFactory != null) conn.sslSocketFactory = socketFactory
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.instanceFollowRedirects = false // a redirect must not carry a value elsewhere
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return interpret(status, text)
        } catch (e: VaultException) {
            throw e
        } catch (_: Exception) {
            // The system's error can carry request state, so none of it is passed on.
            throw VaultException.Transport
        } finally {
            conn.disconnect()
        }
    }

    internal companion object {
        fun parseDescription(j: JSONObject): ComponentDescription {
            val fs = j.optJSONArray("fields") ?: JSONArray()
            val fields = (0 until fs.length()).map { i ->
                val f = fs.getJSONObject(i)
                FieldDescription(f.getString("name"), f.optString("data_type"))
            }
            return ComponentDescription(j.optString("kind"), fields, j.optInt("tokens"))
        }

        fun parseResults(a: JSONArray?): List<RevealedValue> {
            val rs = a ?: JSONArray()
            return (0 until rs.length()).map { i ->
                val r = rs.getJSONObject(i)
                RevealedValue(
                    r.opt("decision") as? String,
                    r.opt("value") as? String,
                    r.optJSONObject("error")?.opt("code") as? String,
                )
            }
        }

        fun interpret(status: Int, text: String): JSONObject {
            val j = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw VaultException.Transport
            }
            if (status in 200..299) return j
            val err = j.optJSONObject("error") ?: JSONObject()
            throw when (val code = err.optString("code")) {
                "invalid_client_token" -> VaultException.InvalidClientToken
                "validation_failed" -> VaultException.ValidationFailed(names(err.optJSONArray("fields")))
                "policy_denied" -> VaultException.Denied
                "rate_limited", "too_many_requests" -> VaultException.RateLimited
                else -> VaultException.Server(status, code)
            }
        }

        private fun names(a: JSONArray?): List<String> {
            val fs = a ?: return emptyList()
            return (0 until fs.length()).mapNotNull { i ->
                when (val f = fs.get(i)) {
                    is String -> f
                    is JSONObject -> f.opt("field") as? String
                    else -> null
                }
            }
        }
    }
}
