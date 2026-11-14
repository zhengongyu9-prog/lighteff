package com.qingxiao.lighteff.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** 云端返回结果的统一封装：error 为空表示成功 */
data class CloudResult<T>(val value: T? = null, val error: String? = null) {
    val ok: Boolean get() = error == null
}

/**
 * 极简 Supabase 客户端：邮箱注册/登录、token 刷新，以及一张备份表的读写。
 * 只依赖 HttpURLConnection，避免引入额外 SDK。
 */
class CloudSync(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("lighteff_cloud", Context.MODE_PRIVATE)

    var email: String? = prefs.getString(KEY_EMAIL, null)
        private set
    private var userId: String? = prefs.getString(KEY_USER_ID, null)
    private var accessToken: String? = prefs.getString(KEY_ACCESS, null)
    private var refreshToken: String? = prefs.getString(KEY_REFRESH, null)
    private var expiresAt: Long = prefs.getLong(KEY_EXPIRES, 0L)

    val lastSyncAt: Long?
        get() = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }

    val loggedIn: Boolean get() = !accessToken.isNullOrBlank() && !userId.isNullOrBlank()

    fun signUp(email: String, password: String): CloudResult<Unit> {
        val (code, text) = request(
            path = "/auth/v1/signup",
            method = "POST",
            body = JSONObject().put("email", email).put("password", password).toString()
        )
        if (code !in 200..299) return CloudResult(error = parseError(text, code))
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return CloudResult(error = "注册失败：返回内容无法解析")
        val token = json.optString("access_token")
        if (token.isBlank()) {
            // 邮箱已注册时 Supabase 不返回登录态，这里引导用户直接登录
            return CloudResult(error = "这个邮箱可能已经注册过，请切到「登录」再试一次")
        }
        saveSession(json)
        return CloudResult(value = Unit)
    }

    fun signIn(email: String, password: String): CloudResult<Unit> {
        val (code, text) = request(
            path = "/auth/v1/token?grant_type=password",
            method = "POST",
            body = JSONObject().put("email", email).put("password", password).toString()
        )
        if (code !in 200..299) return CloudResult(error = parseAuthError(text, code))
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return CloudResult(error = "登录失败：返回内容无法解析")
        if (json.optString("access_token").isBlank()) {
            return CloudResult(error = "登录失败：请先在邮箱完成验证")
        }
        saveSession(json)
        return CloudResult(value = Unit)
    }

    fun signOut() {
        val token = accessToken
        prefs.edit().clear().apply()
        email = null
        userId = null
        accessToken = null
        refreshToken = null
        expiresAt = 0L
        clearVersion()
        if (token != null) {
            runCatching { request("/auth/v1/logout", "POST", null, token) }
        }
    }

    /** 发送重置密码邮件，用户点邮件里的链接在网页上设置新密码 */
    fun sendPasswordReset(email: String): CloudResult<Unit> {
        val (code, text) = request(
            path = "/auth/v1/recover",
            method = "POST",
            body = JSONObject().put("email", email.trim()).toString()
        )
        if (code !in 200..299) return CloudResult(error = parseError(text, code))
        return CloudResult(value = Unit)
    }

    /** 云端是否已有备份（只取一条，受 RLS 限制只能看到自己的） */
    fun hasCloudBackup(): CloudResult<Boolean> {
        val uid = userId ?: return CloudResult(error = "未登录")
        val tokenResult = ensureToken()
        if (!tokenResult.ok) return CloudResult(error = tokenResult.error)
        val (code, text) = request(
            path = "/rest/v1/lighteff_backup?select=user_id&user_id=eq.$uid&limit=1",
            method = "GET",
            token = accessToken
        )
        if (code !in 200..299) return CloudResult(error = parseError(text, code))
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: return CloudResult(error = "读取云端状态失败")
        return CloudResult(value = array.length() > 0)
    }

    /** 云端备份最后更新的时间戳，用来发现「另一台设备改过」，避免直接覆盖 */
    val cloudVersion: String?
        get() = prefs.getString(KEY_CLOUD_VERSION, null)

    /** 只读云端当前的 updated_at；没有备份返回 null */
    fun remoteVersion(): CloudResult<String?> {
        val uid = userId ?: return CloudResult(error = "未登录")
        val tokenResult = ensureToken()
        if (!tokenResult.ok) return CloudResult(error = tokenResult.error)
        val (code, text) = request(
            path = "/rest/v1/lighteff_backup?select=updated_at&user_id=eq.$uid&limit=1",
            method = "GET",
            token = accessToken
        )
        if (code !in 200..299) return CloudResult(error = parseError(text, code))
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: return CloudResult(error = "读取云端状态失败")
        if (array.length() == 0) return CloudResult(value = null)
        val stamp = runCatching { array.getJSONObject(0).optString("updated_at") }.getOrNull()
        return CloudResult(value = stamp?.takeIf { it.isNotBlank() })
    }

    fun upload(json: String): CloudResult<Unit> {
        val uid = userId ?: return CloudResult(error = "未登录")
        val tokenResult = ensureToken()
        if (!tokenResult.ok) return CloudResult(error = tokenResult.error)
        val body = runCatching {
            JSONObject()
                .put("user_id", uid)
                .put("data", JSONObject(json))
                .put("updated_at", Instant.now().toString())
                .toString()
        }.getOrNull() ?: return CloudResult(error = "本机数据格式异常，无法上传")
        val (code, text) = request(
            path = "/rest/v1/lighteff_backup?on_conflict=user_id",
            method = "POST",
            body = body,
            token = accessToken,
            // 让服务端回传写入后的行，拿到它标准化过的 updated_at 作为版本号
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=representation")
        )
        if (code !in 200..299) return CloudResult(error = parseError(text, code))
        rememberVersion(text)
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
        return CloudResult(value = Unit)
    }

    fun download(): CloudResult<String> {
        val uid = userId ?: return CloudResult(error = "未登录")
        val tokenResult = ensureToken()
        if (!tokenResult.ok) return CloudResult(error = tokenResult.error)
        val (code, text) = request(
            path = "/rest/v1/lighteff_backup?select=data,updated_at&user_id=eq.$uid&limit=1",
            method = "GET",
            token = accessToken
        )
        if (code !in 200..299) return CloudResult(error = parseError(text, code))
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: return CloudResult(error = "云端数据无法解析")
        if (array.length() == 0) return CloudResult(error = "云端还没有备份")
        val row = runCatching { array.getJSONObject(0) }.getOrNull()
            ?: return CloudResult(error = "云端备份内容为空")
        val data = row.optJSONObject("data")
            ?: return CloudResult(error = "云端备份内容为空")
        row.optString("updated_at").takeIf { it.isNotBlank() }?.let { stamp ->
            prefs.edit().putString(KEY_CLOUD_VERSION, stamp).apply()
        }
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
        return CloudResult(value = data.toString())
    }

    /** 记下服务端返回的版本号（数组形式，取第一行） */
    private fun rememberVersion(responseText: String) {
        val stamp = runCatching {
            val array = JSONArray(responseText)
            if (array.length() == 0) null else array.getJSONObject(0).optString("updated_at")
        }.getOrNull()
        if (stamp.isNullOrBlank()) {
            // 拿不到服务端版本号就清掉旧值，避免之后一直误判成「另一台设备改过」
            prefs.edit().remove(KEY_CLOUD_VERSION).apply()
        } else {
            prefs.edit().putString(KEY_CLOUD_VERSION, stamp).apply()
        }
    }

    /** 退出登录时清掉版本号，重新登录会重新对齐 */
    private fun clearVersion() {
        prefs.edit().remove(KEY_CLOUD_VERSION).apply()
    }

    private fun saveSession(json: JSONObject) {
        val token = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expiresIn = json.optLong("expires_in", 3600L)
        val uid = json.optJSONObject("user")?.optString("id").orEmpty()
        val mail = json.optJSONObject("user")?.optString("email").orEmpty()
        prefs.edit()
            .putString(KEY_EMAIL, mail)
            .putString(KEY_USER_ID, uid)
            .putString(KEY_ACCESS, token)
            .putString(KEY_REFRESH, refresh)
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresIn * 1000L)
            .apply()
        email = mail
        userId = uid
        accessToken = token
        refreshToken = refresh
        expiresAt = System.currentTimeMillis() + expiresIn * 1000L
    }

    /** token 快过期时用 refresh_token 换新的 */
    private fun ensureToken(): CloudResult<Unit> {
        if (!loggedIn) return CloudResult(error = "未登录")
        if (System.currentTimeMillis() < expiresAt - 60_000L) return CloudResult(value = Unit)
        val refresh = refreshToken ?: return CloudResult(error = "登录已过期，请重新登录")
        val (code, text) = request(
            path = "/auth/v1/token?grant_type=refresh_token",
            method = "POST",
            body = JSONObject().put("refresh_token", refresh).toString()
        )
        if (code !in 200..299) {
            return CloudResult(error = "登录已过期，请重新登录")
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return CloudResult(error = "刷新登录状态失败")
        val token = json.optString("access_token")
        if (token.isBlank()) return CloudResult(error = "登录已过期，请重新登录")
        val newRefresh = json.optString("refresh_token", refresh)
        val expiresIn = json.optLong("expires_in", 3600L)
        accessToken = token
        refreshToken = newRefresh
        expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        prefs.edit()
            .putString(KEY_ACCESS, token)
            .putString(KEY_REFRESH, newRefresh)
            .putLong(KEY_EXPIRES, expiresAt)
            .apply()
        return CloudResult(value = Unit)
    }

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        token: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        // 断网时获取输出流会直接抛异常，必须整段包住，否则会冒泡成未捕获异常把 App 杀死
        return try {
            conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("apikey", API_KEY)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } catch (e: Exception) {
            -1 to (e.message ?: "网络异常")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun parseError(text: String, code: Int): String {
        if (code == -1) return "网络连接失败：$text"
        val json = runCatching { JSONObject(text) }.getOrNull()
        val errorCode = json?.optString("error_code").orEmpty()
        val message = json?.optString("message").orEmpty()
            .ifBlank { json?.optString("msg").orEmpty() }
        val hint = json?.optString("hint").orEmpty()
        return when {
            errorCode == "email_address_invalid" -> "这个邮箱地址不被接受，请换一个真实可用的邮箱"
            errorCode == "weak_password" -> "密码太简单，请换一个更复杂的密码"
            errorCode == "user_already_exists" -> "该邮箱已注册，请直接登录"
            errorCode == "email_not_confirmed" -> "邮箱还没验证，请先点邮件里的确认链接"
            message.contains("only request this after", ignoreCase = true) ->
                "操作太频繁了，请稍等一会儿再试"
            message.contains("rate limit", ignoreCase = true) ->
                "邮件发送太频繁，请稍后再试"
            message.contains("does not exist", ignoreCase = true) ||
                message.contains("relation", ignoreCase = true) ->
                "云端还没有建表，请先在 Supabase SQL Editor 执行建表语句"
            message.isNotBlank() -> message
            hint.isNotBlank() -> hint
            else -> "请求失败（HTTP $code）"
        }
    }

    private fun parseAuthError(text: String, code: Int): String {
        if (code == -1) return "网络连接失败：$text"
        val json = runCatching { JSONObject(text) }.getOrNull()
        val errorCode = json?.optString("error_code").orEmpty()
        val message = json?.optString("error_description").orEmpty()
            .ifBlank { json?.optString("msg").orEmpty() }
            .ifBlank { json?.optString("message").orEmpty() }
        return when {
            errorCode == "invalid_credentials" -> "邮箱或密码不正确"
            errorCode == "email_not_confirmed" -> "邮箱还没验证，请先点邮件里的确认链接"
            message.isNotBlank() -> message
            else -> "登录失败（HTTP $code）"
        }
    }

    private companion object {
        // 项目地址：https://fujkgsgapwdxcfnrubju.supabase.co
        const val BASE_URL = "https://fujkgsgapwdxcfnrubju.supabase.co"
        const val API_KEY = "sb_publishable_-62c7piHHjEpCKj3vvYjFQ_n-IFVKEC"

        const val KEY_EMAIL = "email"
        const val KEY_USER_ID = "user_id"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_CLOUD_VERSION = "cloud_version"
    }
}
