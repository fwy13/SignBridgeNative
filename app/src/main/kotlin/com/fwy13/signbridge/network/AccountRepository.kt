package com.fwy13.signbridge.network

import com.fwy13.signbridge.BuildConfig
import com.fwy13.signbridge.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class AccountRepository(private val http: Http, private val store: SessionStore) {
    private val lock = Mutex()
    private val session = MutableStateFlow(store.read())
    val signedIn = MutableStateFlow(session.value?.optString("access_token")?.isNotBlank() == true)
    val profile = MutableStateFlow(session.value?.optJSONObject("user") ?: JSONObject())
    val backendProfile = MutableStateFlow(JSONObject())
    private val authHeaders = mapOf("apikey" to BackendConfig.supabaseAnonKey)
    private fun save(value: JSONObject) {
        if (!value.has("expires_at")) value.put("expires_at", System.currentTimeMillis() / 1000 + value.optLong("expires_in", 3600))
        store.write(value); session.value = value; signedIn.value = true
        profile.value = value.optJSONObject("user") ?: profile.value
    }
    suspend fun signIn(email: String, password: String) = withTimeout(15_000) {
        val response = JSONObject(http.text("${BackendConfig.supabaseUrl}/auth/v1/token?grant_type=password", "POST",
            JSONObject().put("email", email).put("password", password), authHeaders))
        require(response.optString("access_token").isNotBlank()) { "Missing access token" }
        save(response)
    }
    suspend fun signUp(name: String, email: String, password: String) {
        http.text("${BackendConfig.supabaseUrl}/auth/v1/signup", "POST",
            JSONObject().put("email", email).put("password", password).put("data", JSONObject().put("full_name", name)), authHeaders)
    }
    suspend fun token(): String = lock.withLock {
        val current = session.value ?: error("Please sign in")
        if (current.optLong("expires_at") <= System.currentTimeMillis() / 1000 + 30) {
            try {
                val response = JSONObject(http.text("${BackendConfig.supabaseUrl}/auth/v1/token?grant_type=refresh_token", "POST",
                    JSONObject().put("refresh_token", current.getString("refresh_token")), authHeaders))
                save(response)
            } catch (e: HttpFailure) { if (e.status in listOf(400, 401, 403)) clear(); throw e }
        }
        session.value?.getString("access_token") ?: error("Please sign in")
    }
    suspend fun api(path: String, method: String = "GET", body: JSONObject? = null): String {
        try { return http.text(BuildConfig.API_URL.trimEnd('/') + path, method, body, mapOf("Authorization" to "Bearer ${token()}")) }
        catch (e: HttpFailure) { if (e.status == 401) clear(); throw e }
    }
    suspend fun refreshProfile() {
        profile.value = JSONObject(http.text("${BackendConfig.supabaseUrl}/auth/v1/user", headers = authHeaders + ("Authorization" to "Bearer ${token()}")))
        backendProfile.value = JSONObject(api("/auth/me"))
    }
    suspend fun signOut() {
        try { http.text("${BackendConfig.supabaseUrl}/auth/v1/logout", "POST", headers = authHeaders + ("Authorization" to "Bearer ${token()}")) }
        finally { lock.withLock { clear() } }
    }
    private fun clear() { store.clear(); session.value = null; signedIn.value = false; profile.value = JSONObject(); backendProfile.value = JSONObject() }
}
