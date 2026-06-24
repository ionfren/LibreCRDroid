package re.abbot.librecr.app.libreview

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import re.abbot.librecr.protocol.pairing.Libre3ReceiverID
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class LibreViewAccountIdentity(
    val accountId: String,
    val accountNumber: Libre3ReceiverID,
)

class LibreViewAccountException(message: String) : Exception(message)

/** An authenticated LibreView session: everything needed to POST measurements. */
data class LibreViewSession(
    val baseUrl: String,
    val apiKey: String,
    val userToken: String,
    val accountId: String,
    val accountNumber: Libre3ReceiverID,
) {
    fun toIdentity(): LibreViewAccountIdentity = LibreViewAccountIdentity(accountId, accountNumber)
}

/** Outcome of a `/api/measurements` POST. status 0 = accepted. */
data class MeasurementUploadResult(val status: Int, val reason: String)

class LibreViewAccountClient {
    suspend fun fetchAccountIdentity(
        username: String,
        password: String,
        deviceId: String,
        locale: Locale = Locale.getDefault(),
    ): LibreViewAccountIdentity = login(username, password, deviceId, locale).toIdentity()

    /** Full login that also yields the UserToken for uploads. */
    suspend fun login(
        username: String,
        password: String,
        deviceId: String,
        locale: Locale = Locale.getDefault(),
    ): LibreViewSession = withContext(Dispatchers.IO) {
        if (username.isBlank()) throw LibreViewAccountException("LibreView email lipsă.")
        if (password.isBlank()) throw LibreViewAccountException("LibreView parolă lipsă.")

        val configUrl = getJson(LIBRE3_ASSETS_URL).getString("Configuration")
        val config = getJson(configUrl)
        val baseUrl = config.getString("newYuUrl").trimEnd('/')
        val apiKey = config.optString("newYuApiKey")
            .takeIf { it.isNotBlank() }
            ?: throw LibreViewAccountException("LibreView config nu conține API key.")

        authenticate(
            baseUrl = baseUrl,
            apiKey = apiKey,
            username = username.trim(),
            password = password,
            deviceId = deviceId,
            locale = locale,
            setDevice = false,
        )
    }

    /** Upload one measurement payload. Returns the LibreView status (0 = accepted). */
    suspend fun postMeasurements(
        session: LibreViewSession,
        body: String,
        locale: Locale = Locale.getDefault(),
    ): MeasurementUploadResult = withContext(Dispatchers.IO) {
        val language = libreLanguage(locale)
        val response = postJson(
            url = "${session.baseUrl}/api/measurements",
            body = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Platform" to "Android",
                "Version" to LIBRE3_VERSION,
                "Abbott-ADC-App-Platform" to "Android/${Build.VERSION.RELEASE}/FSL3/$LIBRE3_BUILD",
                "Accept-Language" to language,
                "x-api-key" to session.apiKey,
                "x-newyu-token" to session.userToken,
            ),
        )
        MeasurementUploadResult(response.optInt("status", -1), response.optString("reason"))
    }

    private fun authenticate(
        baseUrl: String,
        apiKey: String,
        username: String,
        password: String,
        deviceId: String,
        locale: Locale,
        setDevice: Boolean,
    ): LibreViewSession {
        val language = libreLanguage(locale)
        val body = JSONObject()
            .put("Culture", language)
            .put("DeviceId", deviceId)
            .put("Password", password)
            .put("SetDevice", setDevice)
            .put("UserName", username)
            .put("Domain", "Libreview")
            .put("GatewayType", "FSLibreLink3.Android")
            .toString()

        val response = postJson(
            url = "$baseUrl/api/nisperson/getauthentication",
            body = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Platform" to "Android",
                "Version" to LIBRE3_VERSION,
                "Abbott-ADC-App-Platform" to "Android/${Build.VERSION.RELEASE}/FSL3/$LIBRE3_BUILD",
                "Accept-Language" to language,
                "x-api-key" to apiKey,
                "x-newyu-token" to "",
            ),
        )

        val status = response.optInt("status", -1)
        if (status != 0) {
            val reason = response.optString("reason").ifBlank {
                response.optJSONObject("data")?.optString("message").orEmpty()
            }
            if (status == 20 && reason == "wrongDeviceForUser" && !setDevice) {
                return authenticate(baseUrl, apiKey, username, password, deviceId, locale, setDevice = true)
            }
            if (status == 429) {
                val lockout = response.optJSONObject("data")
                    ?.optJSONObject("data")
                    ?.optInt("lockout", 300)
                    ?: 300
                throw LibreViewAccountException("LibreView a blocat temporar login-ul ($lockout secunde).")
            }
            throw LibreViewAccountException("LibreView auth status=$status ${reason.ifBlank { "" }}".trim())
        }

        val result = response.getJSONObject("result")
        val accountId = result.getString("AccountId")
        val userToken = result.optString("UserToken")
        if (userToken.isBlank()) {
            throw LibreViewAccountException("LibreView nu a returnat UserToken.")
        }
        val accountNumber = Libre3ReceiverID(Libre3ReceiverID.jugglucoAccountIdNumber(accountId))
        if (accountNumber.value == 0) {
            throw LibreViewAccountException("LibreView AccountId a produs număr 0.")
        }
        return LibreViewSession(baseUrl, apiKey, userToken, accountId, accountNumber)
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return readJson(connection)
    }

    private fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): JSONObject {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            for ((name, value) in headers) setRequestProperty(name, value)
            setRequestProperty("Content-Length", bytes.size.toString())
        }
        BufferedOutputStream(connection.outputStream).use { out ->
            out.write(bytes)
            out.flush()
        }
        return readJson(connection)
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw LibreViewAccountException("LibreView HTTP $code ${text.take(160)}".trim())
        }
        return JSONObject(text)
    }

    private fun libreLanguage(locale: Locale): String =
        if (locale.country.isBlank()) locale.language else "${locale.language}-${locale.country}"

    private companion object {
        const val LIBRE3_VERSION = "3.3.0"
        const val LIBRE3_BUILD = "3.3.0.9092"
        const val TIMEOUT_MS = 20_000
        const val LIBRE3_ASSETS_URL =
            "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json"
    }
}
