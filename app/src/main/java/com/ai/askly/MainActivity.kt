package com.ai.askly

import android.os.Bundle
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.askly.data.ChatDatabase
import com.ai.askly.data.ChatEntity
import com.ai.askly.ui.theme.DevAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AsklyApp() }
    }
}

data class ChatMessage(val text: String, val fromUser: Boolean)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val chats: List<ChatSession> = emptyList(),
    val activeChatId: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@Composable
private fun asklyString(@StringRes id: Int, arabic: Boolean): String {
    val context = LocalContext.current
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(if (arabic) Locale("ar") else Locale.ENGLISH)
    return context.createConfigurationContext(configuration).getString(id)
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = ChatDatabase.get(application)
    private val preferences = application.getSharedPreferences("askly_chats", 0)
    private var sessions = mutableListOf<ChatSession>()
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val storedChats = database.chatDao().getAll()
            sessions = if (storedChats.isNotEmpty()) {
                storedChats.map(::toSession).toMutableList()
            } else {
                loadLegacySessions().toMutableList().ifEmpty {
                    mutableListOf(newEmptyChat())
                }.also {
                    database.chatDao().insertAll(it.mapIndexed(::toEntity))
                    preferences.edit().remove("sessions").apply()
                }
            }
            val active = sessions.first()
            _uiState.value = ChatUiState(messages = active.messages, chats = sessions.toList(), activeChatId = active.id)
        }
    }

    fun sendMessage(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty() || _uiState.value.isLoading) return

        val updatedMessages = _uiState.value.messages + ChatMessage(cleanText, true)
        updateActiveMessages(updatedMessages)
        _uiState.value = _uiState.value.copy(messages = updatedMessages, isLoading = true, error = null)

        viewModelScope.launch {
            runCatching { OpenAiClient.ask(cleanText, getApplication()) }
                .onSuccess { answer ->
                    val finalMessages = updatedMessages + ChatMessage(answer, false)
                    updateActiveMessages(finalMessages)
                    _uiState.value = _uiState.value.copy(messages = finalMessages, isLoading = false)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        messages = updatedMessages,
                        isLoading = false,
                        error = error.message ?: getApplication<Application>().getString(R.string.something_wrong)
                    )
                }
        }
    }

    fun createNewChat() {
        val emptyChat = sessions.firstOrNull { session ->
            session.messages.none { it.fromUser }
        }
        if (emptyChat != null) {
            _uiState.value = ChatUiState(
                messages = emptyChat.messages,
                chats = sessions,
                activeChatId = emptyChat.id
            )
            return
        }

        val chat = ChatSession(
            newId(),
            getApplication<Application>().getString(R.string.new_chat),
            listOf(ChatMessage(getApplication<Application>().getString(R.string.welcome_message), false))
        )
        sessions.add(0, chat)
        persistSessions()
        _uiState.value = ChatUiState(messages = chat.messages, chats = sessions, activeChatId = chat.id)
    }

    fun openChat(chatId: String) {
        val chat = sessions.firstOrNull { it.id == chatId } ?: return
        _uiState.value = ChatUiState(messages = chat.messages, chats = sessions, activeChatId = chat.id)
    }

    fun deleteChat(chatId: String) {
        sessions.removeAll { it.id == chatId }
        if (sessions.isEmpty()) {
            sessions += ChatSession(
                newId(),
                getApplication<Application>().getString(R.string.new_chat),
                listOf(ChatMessage(getApplication<Application>().getString(R.string.welcome_message), false))
            )
        }
        viewModelScope.launch(Dispatchers.IO) { database.chatDao().deleteById(chatId) }
        persistSessions()
        val active = sessions.first()
        _uiState.value = ChatUiState(messages = active.messages, chats = sessions, activeChatId = active.id)
    }

    private fun updateActiveMessages(messages: List<ChatMessage>) {
        val index = sessions.indexOfFirst { it.id == _uiState.value.activeChatId }
        if (index < 0) return
        val old = sessions[index]
        val title = if (old.title == getApplication<Application>().getString(R.string.new_chat) && messages.any { it.fromUser }) {
            messages.first { it.fromUser }.text.take(32)
        } else old.title
        sessions[index] = old.copy(title = title, messages = messages)
        persistSessions()
        _uiState.value = _uiState.value.copy(chats = sessions)
    }

    private fun newId() = System.currentTimeMillis().toString()

    private fun newEmptyChat() = ChatSession(
        newId(),
        getApplication<Application>().getString(R.string.new_chat),
        listOf(ChatMessage(getApplication<Application>().getString(R.string.welcome_message), false))
    )

    private fun persistSessions() {
        val array = org.json.JSONArray()
        sessions.forEach { session ->
            val messages = org.json.JSONArray()
            session.messages.forEach { message ->
                messages.put(org.json.JSONObject().put("text", message.text).put("fromUser", message.fromUser))
            }
            array.put(org.json.JSONObject().put("id", session.id).put("title", session.title).put("messages", messages))
        }
        viewModelScope.launch(Dispatchers.IO) {
            database.chatDao().insertAll(sessions.mapIndexed(::toEntity))
        }
    }

    private fun toEntity(position: Int, session: ChatSession) = ChatEntity(
        id = session.id,
        title = session.title,
        messagesJson = encodeMessages(session.messages),
        position = position
    )

    private fun toSession(entity: ChatEntity): ChatSession = ChatSession(
        id = entity.id,
        title = entity.title,
        messages = decodeMessages(entity.messagesJson)
    )

    private fun encodeMessages(messages: List<ChatMessage>): String {
        val array = org.json.JSONArray()
        messages.forEach { array.put(org.json.JSONObject().put("text", it.text).put("fromUser", it.fromUser)) }
        return array.toString()
    }

    private fun decodeMessages(raw: String): List<ChatMessage> {
        val array = org.json.JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val message = array.getJSONObject(i)
                add(ChatMessage(message.getString("text"), message.getBoolean("fromUser")))
            }
        }
    }

    private fun loadLegacySessions(): List<ChatSession> {
        val raw = preferences.getString("sessions", null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val session = array.getJSONObject(i)
                    val messagesJson = session.getJSONArray("messages")
                    val messages = buildList {
                        for (j in 0 until messagesJson.length()) {
                            val message = messagesJson.getJSONObject(j)
                            add(ChatMessage(message.getString("text"), message.getBoolean("fromUser")))
                        }
                    }
                    add(ChatSession(session.getString("id"), session.getString("title"), messages))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

private object OpenAiClient {
    private const val endpoint = "https://api.openai.com/v1/responses"

    suspend fun ask(prompt: String, context: Context): String = withContext(Dispatchers.IO) {
        // The key is supplied locally through local.properties for this private prototype.
        val key = BuildConfig.OPENAI_API_KEY
        require(key.isNotBlank()) {
            context.getString(R.string.api_key_missing)
        }

        // Responses API accepts a simple text input for a single-turn chat.
        val body = org.json.JSONObject()
            .put("model", "gpt-5")
            .put("input", prompt)
            .toString()

        val connection = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }

        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val responseStream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: throw IllegalStateException("OpenAI returned no error details.")
        }
        val responseText = responseStream.bufferedReader().use { it.readText() }

        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("OpenAI error ${connection.responseCode}: $responseText")
        }

        extractOutputText(org.json.JSONObject(responseText))
    }

    private fun extractOutputText(response: org.json.JSONObject): String {
        val output = response.optJSONArray("output")
            ?: throw IllegalStateException("The API returned no output.")

        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val text = content.optJSONObject(j)?.optString("text").orEmpty()
                if (text.isNotBlank()) return text
            }
        }
        throw IllegalStateException("The API returned an empty answer.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsklyApp(chatViewModel: ChatViewModel = viewModel()) {
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("askly_settings", 0) }
    var darkMode by rememberSaveable { mutableStateOf(settings.getBoolean("dark_mode", false)) }
    var arabic by rememberSaveable { mutableStateOf(settings.getBoolean("arabic", false)) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var historyRefreshKey by rememberSaveable { mutableStateOf(0) }

    DevAITheme(darkTheme = darkMode) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            if (showSettings) {
                SettingsScreen(
                    arabic = arabic,
                    darkMode = darkMode,
                    onBack = { showSettings = false },
                    onDarkModeChanged = {
                        darkMode = it
                        settings.edit().putBoolean("dark_mode", it).apply()
                    },
                    onArabicChanged = {
                        arabic = it
                        settings.edit().putBoolean("arabic", it).apply()
                    }
                )
            } else if (showHistory) {
                key(historyRefreshKey) {
                    ChatHistoryScreen(
                        chats = uiState.chats,
                        arabic = arabic,
                        onBack = { showHistory = false },
                        onNewChat = { chatViewModel.createNewChat(); showHistory = false },
                        onChatSelected = { chatViewModel.openChat(it); showHistory = false },
                        onDeleteChat = { chatViewModel.deleteChat(it); historyRefreshKey++ }
                    )
                }
            } else {
                ChatScreen(
                    chatViewModel = chatViewModel,
                    arabic = arabic,
                    onOpenSettings = { showSettings = true },
                    onOpenHistory = { showHistory = true },
                    onNewChat = { chatViewModel.createNewChat() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    chatViewModel: ChatViewModel,
    arabic: Boolean,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewChat: () -> Unit
) {
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Askly", fontWeight = FontWeight.Bold)
                        Text(asklyString(R.string.simple_ai_chat, arabic), style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = asklyString(R.string.chat_history, arabic)
                        )
                    }
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = asklyString(R.string.new_chat, arabic)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = asklyString(R.string.settings, arabic)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.messages) { message -> MessageBubble(message) }
                if (uiState.isLoading) {
                    item { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; chatViewModel.clearError() },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(asklyString(R.string.ask_anything, arabic)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { chatViewModel.sendMessage(input); input = "" },
                    enabled = input.isNotBlank() && !uiState.isLoading,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = asklyString(R.string.send, arabic),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHistoryScreen(
    chats: List<ChatSession>,
    arabic: Boolean,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    onChatSelected: (String) -> Unit,
    onDeleteChat: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = asklyString(R.string.back, arabic)
                        )
                    }
                },
                title = { Text(asklyString(R.string.chat_history, arabic)) },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = asklyString(R.string.new_chat, arabic)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (chats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(asklyString(R.string.no_chats, arabic))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chats, key = { it.id }) { chat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onChatSelected(chat.id) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(chat.title, maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDeleteChat(chat.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = asklyString(R.string.delete_chat, arabic),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    arabic: Boolean,
    darkMode: Boolean,
    onBack: () -> Unit,
    onDarkModeChanged: (Boolean) -> Unit,
    onArabicChanged: (Boolean) -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = asklyString(R.string.back, arabic)
                        )
                    }
                },
                title = { Text(asklyString(R.string.settings, arabic)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = asklyString(R.string.appearance, arabic),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DarkMode, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(asklyString(R.string.dark_mode, arabic))
                }
                Switch(checked = darkMode, onCheckedChange = onDarkModeChanged)
            }

            Text(
                text = asklyString(R.string.language, arabic),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(asklyString(R.string.arabic, arabic))
                Switch(checked = arabic, onCheckedChange = onArabicChanged)
            }

            Text(
                text = asklyString(R.string.language_hint, arabic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(
            text = message.text,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (message.fromUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
            color = if (message.fromUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

