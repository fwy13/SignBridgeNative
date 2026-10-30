package com.fwy13.signbridge.network

import com.fwy13.signbridge.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
data class RoomState(
    val code: String = "", val participantId: String = "", val role: String = "normal", val displayName: String = "",
    val ownerId: String = "", val participants: Map<String, JSONObject> = emptyMap(),
    val messages: List<JSONObject> = emptyList(), val status: String = "disconnected", val ended: Boolean = false
)
class RoomRepository(private val account: AccountRepository, private val http: Http, private val scope: CoroutineScope) {
    val state = MutableStateFlow(RoomState())
    private var socket: WebSocket? = null
    private var retryJob: Job? = null
    private var retries = 0
    @Volatile private var generation = 0L
    suspend fun create(): String = JSONObject(account.api("/rooms/create?ttl_minutes=120", "POST")).getString("code")
    suspend fun join(input: String) {
        val code = if (input.startsWith("http")) input.toHttpUrl().pathSegments.lastOrNull().orEmpty() else input.trim()
        require(code.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid room code" }
        val participant = JSONObject(account.api("/rooms/$code/join?role=normal", "POST")).getJSONObject("participant")
        disconnect()
        val ownId = participant.get("id").toString()
        val own = JSONObject().put("participant_id", ownId).put("display_name", participant.optString("display_name"))
            .put("role", participant.optString("role", "normal"))
        state.value = RoomState(code, ownId, own.optString("role"), own.optString("display_name"), participants = mapOf(ownId to own), status = "connecting")
        val g = generation
        scope.launch {
            runCatching { JSONObject(account.api("/rooms/$code/room")) }.onSuccess { room ->
                if (g == generation) state.update { it.copy(ownerId = room.optString("owner_id")) }
            }
        }
        scope.launch {
            runCatching { JSONArray(account.api("/rooms/$code/participants")).objects() }.onSuccess { participants ->
                if (g == generation) state.update { it.copy(participants = participants.associateBy { p -> p.get("participant_id").toString() }) }
            }
        }
        connect(g)
    }
    private suspend fun connect(g: Long) {
        if (g != generation) return
        val token = try { account.token() } catch (e: Exception) { state.update { it.copy(status = "disconnected") }; return }
        val room = state.value
        val url = (BuildConfig.WS_BASE.replace("wss://", "https://").replace("ws://", "http://").trimEnd('/') + "/ws/rooms/${room.code}")
            .toHttpUrl().newBuilder().addQueryParameter("participant_id", room.participantId).addQueryParameter("role", room.role)
            .addQueryParameter("display_name", room.displayName).addQueryParameter("token", token).build()
        if (g != generation) return
        socket = http.client.newWebSocket(Request.Builder().url(url).header("Sec-WebSocket-Protocol", "jwt, $token").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (g != generation) { webSocket.close(1000, null); return }
                retries = 0; state.update { it.copy(status = "connected") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (g != generation) return
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "presence.join" -> state.update { it.copy(participants = it.participants + (msg.get("participant_id").toString() to msg)) }
                    "presence.leave" -> state.update { it.copy(participants = it.participants - msg.get("participant_id").toString()) }
                    "chat.message" -> state.update { it.copy(messages = it.messages + msg) }
                    "room.ended" -> { disconnect(); state.update { it.copy(messages = emptyList(), ended = true, status = "disconnected") } }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (g == generation) state.update { it.copy(status = "disconnected") } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (g != generation) return
                if (retries < 2) {
                    retries += 1; state.update { it.copy(status = "reconnecting") }
                    retryJob = scope.launch { delay(500L * retries); connect(g) }
                } else state.update { it.copy(status = "disconnected") }
            }
        })
    }
    fun send(value: String) {
        val text = value.trim()
        require(text.isNotEmpty())
        check(state.value.status == "connected" && socket?.send(JSONObject().put("type", "chat.message").put("text", text).toString()) == true) { "Room is disconnected" }
    }
    suspend fun leave(end: Boolean = false) {
        val code = state.value.code
        try { account.api("/rooms/$code/${if (end) "end" else "leave"}", "POST") }
        finally { disconnect(); state.value = RoomState() }
    }
    fun disconnect() { generation++; retryJob?.cancel(); socket?.close(1000, "Leaving screen"); socket = null; retries = 0 }
}
