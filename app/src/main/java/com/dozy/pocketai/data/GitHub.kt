package com.dozy.pocketai.data

import android.content.Context
import com.dozy.pocketai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GhRepo(val fullName: String, val branch: String, val private: Boolean, val description: String, val updated: String)
data class GhEntry(val path: String, val isDir: Boolean, val size: Long) {
    val name get() = path.substringAfterLast('/')
    val parent get() = if ('/' in path) path.substringBeforeLast('/') else ""
}
data class DeviceCode(val userCode: String, val verifyUrl: String, val deviceCode: String, val interval: Int, val expiresIn: Int)

/** Repository the chat is attached to, with the files picked as context. */
data class Attachment(val repo: GhRepo, val files: Set<String>)

data class GhState(
    val login: String? = null,
    val token: String? = null,
    val attachment: Attachment? = null,
)

class GitHubException(message: String) : Exception(message)

const val REDIRECT = "pocketai://oauth"

/**
 * GitHub account + repositories for the chat ("folders" like in Claude):
 * sign-in with the OAuth device flow (or a personal token), list repos,
 * browse the tree and fetch file contents.
 */
class GitHub(context: Context) {
    private val prefs = context.getSharedPreferences("github", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(GhState(prefs.getString("login", null), prefs.getString("token", null)))
    val state: StateFlow<GhState> = _state.asStateFlow()
    private val treeCache = HashMap<String, List<GhEntry>>()
    private val fileCache = HashMap<String, String>()

    /** OAuth App client id (Device Flow enabled). Set in CI as GH_CLIENT_ID or in settings. */
    var clientId: String
        get() = prefs.getString("client_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GH_CLIENT_ID
        set(v) = prefs.edit().putString("client_id", v.trim()).apply()

    val loggedIn get() = _state.value.token != null

    /** Secret of the same OAuth App; needed by GitHub for the browser flow. */
    var clientSecret: String
        get() = prefs.getString("client_secret", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GH_CLIENT_SECRET
        set(v) = prefs.edit().putString("client_secret", v.trim()).apply()

    val canBrowserLogin get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    // ---------------- sign-in in the browser (Chrome opens, you tap Authorize, the app gets the token) ----------------

    /** Opens github.com in a Chrome custom tab; GitHub sends the user back to pocketai://oauth. */
    fun startBrowserLogin(context: Context) {
        if (!canBrowserLogin) throw GitHubException("Не задан Client ID / Secret OAuth-приложения")
        val rnd = java.security.SecureRandom()
        fun token(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
            .let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }
        val state = token(16)
        val verifier = token(48)
        val challenge = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
            .let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }
        prefs.edit().putString("oauth_state", state).putString("oauth_verifier", verifier).apply()
        val url = android.net.Uri.parse("https://github.com/login/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", "repo read:user")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("allow_signup", "true")
            .build()
        runCatching {
            androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
                .apply { intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                .launchUrl(context, url)
        }.onFailure {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()
    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn.asStateFlow()

    /** Called by MainActivity with pocketai://oauth?code=…&state=… */
    suspend fun handleRedirect(uri: android.net.Uri) {
        _loginError.value = null
        val expected = prefs.getString("oauth_state", null)
        val verifier = prefs.getString("oauth_verifier", null)
        uri.getQueryParameter("error")?.let { _loginError.value = "GitHub: " + (uri.getQueryParameter("error_description") ?: it); return }
        val code = uri.getQueryParameter("code") ?: return
        if (expected == null || uri.getQueryParameter("state") != expected) { _loginError.value = "Вход устарел — нажми «Войти» ещё раз"; return }
        _loggingIn.value = true
        try {
            val o = withContext(Dispatchers.IO) {
                form(
                    "https://github.com/login/oauth/access_token",
                    "client_id" to clientId, "client_secret" to clientSecret, "code" to code,
                    "redirect_uri" to REDIRECT, "code_verifier" to (verifier ?: ""),
                )
            }
            val t = o["access_token"]?.jsonPrimitive?.content
                ?: throw GitHubException(o["error_description"]?.jsonPrimitive?.content ?: "GitHub не выдал токен")
            loginWithToken(t)
            prefs.edit().remove("oauth_state").remove("oauth_verifier").apply()
        } catch (e: Exception) {
            _loginError.value = e.message
        } finally {
            _loggingIn.value = false
        }
    }

    // ---------------- sign-in ----------------

    suspend fun startDeviceFlow(): DeviceCode = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) throw GitHubException("Не задан Client ID OAuth-приложения (Настройки → GitHub)")
        val o = form("https://github.com/login/device/code", "client_id" to clientId, "scope" to "repo read:user")
        o["error"]?.let { throw GitHubException(o["error_description"]?.jsonPrimitive?.content ?: it.jsonPrimitive.content) }
        DeviceCode(
            o.str("user_code"), o.str("verification_uri"), o.str("device_code"),
            o["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
            o["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 900,
        )
    }

    /** Polls until the user confirms the code on github.com. */
    suspend fun finishDeviceFlow(code: DeviceCode) {
        var interval = code.interval
        val until = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(interval * 1000L)
            val o = withContext(Dispatchers.IO) {
                form(
                    "https://github.com/login/oauth/access_token",
                    "client_id" to clientId, "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                )
            }
            o["access_token"]?.let { return loginWithToken(it.jsonPrimitive.content) }
            when (o["error"]?.jsonPrimitive?.content) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                "expired_token" -> throw GitHubException("Код истёк — начни вход заново")
                "access_denied" -> throw GitHubException("Вход отменён")
                else -> throw GitHubException(o["error_description"]?.jsonPrimitive?.content ?: "Ошибка входа")
            }
        }
        throw GitHubException("Код истёк — начни вход заново")
    }

    suspend fun loginWithToken(token: String) {
        val t = token.trim()
        val user = withContext(Dispatchers.IO) { api("/user", t) }.jsonObject
        val login = user.str("login")
        prefs.edit().putString("token", t).putString("login", login).apply()
        _state.value = GhState(login, t)
    }

    fun logout() {
        prefs.edit().remove("token").remove("login").apply()
        refresh()
        _state.value = GhState()
    }

    // ---------------- repos & files ----------------

    suspend fun repos(): List<GhRepo> = withContext(Dispatchers.IO) {
        api("/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member").jsonArray.map {
            val o = it.jsonObject
            GhRepo(
                o.str("full_name"), o["default_branch"]?.jsonPrimitive?.content ?: "main",
                o["private"]?.jsonPrimitive?.boolean ?: false,
                o["description"]?.jsonPrimitive?.content?.takeIf { d -> d != "null" }.orEmpty(),
                o["pushed_at"]?.jsonPrimitive?.content.orEmpty().take(10),
            )
        }
    }

    suspend fun tree(repo: GhRepo): List<GhEntry> = withContext(Dispatchers.IO) {
        val key = repo.fullName + "@" + repo.branch
        treeCache[key]?.let { return@withContext it }
        val o = api("/repos/${repo.fullName}/git/trees/${enc(repo.branch)}?recursive=1").jsonObject
        val list = o["tree"]!!.jsonArray.map {
            val e = it.jsonObject
            GhEntry(e.str("path"), e.str("type") == "tree", e["size"]?.jsonPrimitive?.longOrNull ?: 0)
        }
        treeCache[key] = list
        list
    }

    suspend fun file(repo: GhRepo, path: String): String = withContext(Dispatchers.IO) {
        val key = "${repo.fullName}@${repo.branch}:$path"
        fileCache[key]?.let { return@withContext it }
        val url = "/repos/${repo.fullName}/contents/${path.split('/').joinToString("/") { enc(it) }}?ref=${enc(repo.branch)}"
        request(url, accept = "application/vnd.github.raw").also { fileCache[key] = it }
    }

    /** Codex-style: writes [content] to [path] as one commit on the repo's branch. Returns the commit URL. */
    suspend fun commitFile(repo: GhRepo, path: String, content: String, message: String): String = withContext(Dispatchers.IO) {
        val clean = path.trim().trimStart('/')
        val api = "/repos/${repo.fullName}/contents/${clean.split('/').joinToString("/") { enc(it) }}"
        val sha = runCatching {
            json.parseToJsonElement(request("$api?ref=${enc(repo.branch)}")).jsonObject["sha"]?.jsonPrimitive?.content
        }.getOrNull()
        val body = kotlinx.serialization.json.buildJsonObject {
            put("message", kotlinx.serialization.json.JsonPrimitive(message.ifBlank { "Update $clean from PocketAI" }))
            put("content", kotlinx.serialization.json.JsonPrimitive(
                android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)))
            put("branch", kotlinx.serialization.json.JsonPrimitive(repo.branch))
            if (sha != null) put("sha", kotlinx.serialization.json.JsonPrimitive(sha))
        }.toString()
        val res = json.parseToJsonElement(request(api, method = "PUT", body = body)).jsonObject
        fileCache.remove("${repo.fullName}@${repo.branch}:$clean")
        treeCache.remove("${repo.fullName}@${repo.branch}")
        res["commit"]?.jsonObject?.get("html_url")?.jsonPrimitive?.content ?: "https://github.com/${repo.fullName}"
    }

    /** Forget cached trees/files (e.g. after a push). */
    fun refresh() { treeCache.clear(); fileCache.clear() }

    fun attach(a: Attachment?) = _state.update { it.copy(attachment = a) }

    /** Contents of the attached files, packed for the prompt (stays under [budget] characters). */
    suspend fun buildContext(a: Attachment, budget: Int): String {
        val sb = StringBuilder("Файлы из репозитория ${a.repo.fullName} (ветка ${a.repo.branch}):\n")
        for (path in a.files.sorted()) {
            if (sb.length >= budget) { sb.append("\n(остальные файлы не поместились)"); break }
            val text = runCatching { file(a.repo, path) }.getOrElse { "(не удалось прочитать: ${it.message})" }
            val room = (budget - sb.length - 40).coerceAtLeast(0)
            val body = if (text.length > room) text.take(room) + "\n… (обрезано)" else text
            val lang = path.substringAfterLast('.', "")
            sb.append("\n### ").append(path).append("\n```").append(lang).append('\n').append(body).append("\n```\n")
        }
        return sb.toString()
    }

    // ---------------- http ----------------

    private fun api(path: String, token: String? = _state.value.token): JsonElement =
        json.parseToJsonElement(request(path, token = token))

    private fun request(
        path: String,
        accept: String = "application/vnd.github+json",
        token: String? = _state.value.token,
        method: String = "GET",
        body: String? = null,
    ): String {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "PocketAI")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        if (code == 409) throw GitHubException("GitHub: конфликт — файл изменился, попробуй ещё раз")
        if (code == 403 || code == 404) if (method != "GET") throw GitHubException("GitHub: нет прав на запись в репо (HTTP $code)")
        if (code == 401) throw GitHubException("GitHub: вход недействителен, войди заново")
        if (code !in 200..299) throw GitHubException("GitHub: HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun form(url: String, vararg fields: Pair<String, String>): JsonObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.outputStream.use { it.write(fields.joinToString("&") { (k, v) -> "$k=${enc(v)}" }.toByteArray()) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content ?: throw GitHubException("GitHub: нет поля $k")
}
