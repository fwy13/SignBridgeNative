package com.fwy13.signbridge

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fwy13.signbridge.data.*
import com.fwy13.signbridge.models.ModelStorage
import com.fwy13.signbridge.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

enum class Page { Translate, Conversation, Library, Settings, Models, Capture, Qr, Room }
class BridgeViewModel(application: Application): AndroidViewModel(application) {
    val preferences=Preferences(application)
    val http=Http()
    val account=AccountRepository(http,SessionStore(application))
    val dictionary=DictionaryRepository(application,http,preferences)
    val videos=VideoRepository(application)
    val models=ModelStorage(application)
    val rooms=RoomRepository(account,http,viewModelScope)
    private val sentenceBuilder=SentenceBuilder(http)
    var page by mutableStateOf(Page.Translate)
    var tab by mutableStateOf(Page.Translate)
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var notice by mutableStateOf("")
    var initializing by mutableStateOf(true)
    var textInput by mutableStateOf("")
    var translationVideo by mutableStateOf<String?>(null)
    var chatInput by mutableStateOf("")
    var captureForRoom by mutableStateOf(false)
    var captureMode by mutableStateOf("word")
    var recognized by mutableStateOf<List<String>>(emptyList())
    var completedSentence by mutableStateOf("")
    var autoSentence by mutableStateOf(BuildConfig.SENTENCE_BUILDING_AVAILABLE)
    var building by mutableStateOf(false)
    var sentenceError by mutableStateOf("")
    var pronounOverride by mutableStateOf<Pronouns?>(null)
    var editingSigns by mutableStateOf(false)
    var signsDraft by mutableStateOf("")
    private var sentenceJob: Job?=null
    private var sentenceVersion=0L
    val texts: Texts get()=Texts(preferences.state.value.language)
    val pronouns: Pronouns get()=pronounOverride ?: preferences.state.value.pronouns
    init {
        viewModelScope.launch {
            val region=preferences.state.value.region
            if(region.isNotEmpty())dictionary.load(region)
            initializing=false
            if(region.isNotEmpty())launch { runCatching { dictionary.sync(region) } }
        }
        viewModelScope.launch {
            account.signedIn.collect { signedIn ->
                if(signedIn)launch { runCatching { account.refreshProfile() } }
                else { rooms.disconnect(); page=Page.Translate; tab=Page.Translate; resetCapture() }
            }
        }
    }
    fun action(block: suspend () -> Unit) {
        if(busy)return
        viewModelScope.launch {
            busy=true; error=""
            try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message ?: "Error" }
            finally { busy=false }
        }
    }
    fun region(value: String) {
        action {
            preferences.update(preferences.state.value.copy(region=value))
            dictionary.load(value)
            viewModelScope.launch { runCatching { dictionary.sync(value) } }
        }
    }
    fun navigate(value: Page) { page=value; if(value in listOf(Page.Translate,Page.Conversation,Page.Library,Page.Settings))tab=value }
    fun translateText() {
        if(textInput.isBlank())return
        translationVideo=signVideo(dictionary.find(textInput))
        if(translationVideo==null)error=texts("translation.noDataMsg","word" to textInput)
    }
    fun openCapture(room: Boolean=false) { resetCapture();captureForRoom=room;if(room)captureMode="word";page=Page.Capture }
    fun closeCapture() { resetCapture();page=if(captureForRoom)Page.Room else Page.Translate }
    fun resetCapture() {
        sentenceVersion++;sentenceJob?.cancel();recognized=emptyList();completedSentence="";building=false;sentenceError="";editingSigns=false;signsDraft=""
    }
    fun mode(value: String) { resetCapture();captureMode=value }
    fun result(label: String) {
        if(captureForRoom) {
            val words=chatInput.trim().split(Regex("\\s+"))
            if(!words.lastOrNull().equals(label,true)) chatInput=(chatInput.trim()+" "+label).trim()
            closeCapture();return
        }
        val base=if(editingSigns)parseSigns(signsDraft) else recognized
        recognized=base+label;editingSigns=false;signsDraft=recognized.joinToString(" · ")
        if(captureMode=="word")buildSentence()
    }
    fun parseSigns(value: String)=value.split(Regex("[·,\\n]")).map(String::trim).filter(String::isNotEmpty)
    fun confirmSigns() { recognized=parseSigns(signsDraft);signsDraft=recognized.joinToString(" · ");editingSigns=false;buildSentence() }
    fun manualSentence(value: String) { sentenceVersion++;sentenceJob?.cancel();building=false;sentenceError="";completedSentence=value }
    fun toggleSentence(value: Boolean) {
        if(!BuildConfig.SENTENCE_BUILDING_AVAILABLE)return
        autoSentence=value;sentenceVersion++;sentenceJob?.cancel();building=false;sentenceError=""
        if(value)buildSentence()
    }
    fun buildSentence() {
        sentenceVersion++;val version=sentenceVersion;sentenceJob?.cancel()
        if(recognized.isEmpty()) { completedSentence="";building=false;sentenceError="";return }
        if(!BuildConfig.SENTENCE_BUILDING_AVAILABLE || !autoSentence)return
        val words=recognized.toList(); val options=SentenceOptions(pronouns)
        sentenceJob=viewModelScope.launch {
            building=true;sentenceError=""
            try { val text=sentenceBuilder.build(words,options);if(version==sentenceVersion)completedSentence=text }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(version==sentenceVersion) { completedSentence="";sentenceError=texts("translation.sentenceBuildError") } }
            finally { if(version==sentenceVersion)building=false }
        }
    }
    fun join(code: String)=action { rooms.join(code);page=Page.Room;tab=Page.Conversation }
    fun createRoom()=action { rooms.join(rooms.create());page=Page.Room;tab=Page.Conversation }
    fun leaveRoom(end: Boolean=false)=action { try { rooms.leave(end) } finally { page=Page.Conversation;chatInput="" } }
    fun logout()=action {
        try { account.signOut() } finally { preferences.clear();dictionary.data.value=JSONObject();textInput="";chatInput="";translationVideo=null }
    }
    override fun onCleared() { rooms.disconnect();super.onCleared() }
}
