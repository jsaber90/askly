package com.ai.askly

import android.os.Bundle
import android.app.Application
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.askly.ui.theme.DevAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val messages: List<ChatMessage> = listOf(
        ChatMessage("Hi! I’m Askly. What would you like to know?", false)
    ),
    val chats: List<ChatSession> = emptyList(),
    val activeChatId: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("askly_chats", 0)
    private var sessions = loadSessions().toMutableList()
    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private fun initialState(): ChatUiState {
        if (sessions.isEmpty()) {
            sessions += ChatSession(
                id = newId(),
                title = "New chat",
                messages = listOf(ChatMessage("Hi! I’m Askly. What would you like to know?", false))
            )
            persistSessions()
        }
        val active = sessions.first()
        return ChatUiState(messages = active.messages, chats = sessions, activeChatId = active.id)
    }

    fun sendMessage(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty() || _uiState.value.isLoading) return

        val updatedMessages = _uiState.value.messages + ChatMessage(cleanText, true)
        updateActiveMessages(updatedMessages)
        _uiState.value = _uiState.value.copy(messages = updatedMessages, isLoading = true, error = null)

        viewModelScope.launch {
            runCatching { OpenAiClient.ask(cleanText) }
                .onSuccess { answer ->
                    val finalMessages = updatedMessages + ChatMessage(answer, false)
                    updateActiveMessages(finalMessages)
                    _uiState.value = _uiState.value.copy(messages = finalMessages, isLoading = false)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        messages = updatedMessages,
                        isLoading = false,
                        error = error.message ?: "Something went wrong."
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

        val chat = ChatSession(newId(), "New chat", listOf(ChatMessage("Hi! I’m Askly. What would you like to know?", false)))
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
                "New chat",
                listOf(ChatMessage("Hi! I’m Askly. What would you like to know?", false))
            )
        }
        persistSessions()
        val active = sessions.first()
        _uiState.value = ChatUiState(messages = active.messages, chats = sessions, activeChatId = active.id)
    }

    private fun updateActiveMessages(messages: List<ChatMessage>) {
        val index = sessions.indexOfFirst { it.id == _uiState.value.activeChatId }
        if (index < 0) return
        val old = sessions[index]
        val title = if (old.title == "New chat" && messages.any { it.fromUser }) {
            messages.first { it.fromUser }.text.take(32)
        } else old.title
        sessions[index] = old.copy(title = title, messages = messages)
        persistSessions()
        _uiState.value = _uiState.value.copy(chats = sessions)
    }

    private fun newId() = System.currentTimeMillis().toString()

    private fun persistSessions() {
        val array = org.json.JSONArray()
        sessions.forEach { session ->
            val messages = org.json.JSONArray()
            session.messages.forEach { message ->
                messages.put(org.json.JSONObject().put("text", message.text).put("fromUser", message.fromUser))
            }
            array.put(org.json.JSONObject().put("id", session.id).put("title", session.title).put("messages", messages))
        }
        preferences.edit().putString("sessions", array.toString()).apply()
    }

    private fun loadSessions(): List<ChatSession> {
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

    suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        // The key is supplied locally through local.properties for this private prototype.
        val key = BuildConfig.OPENAI_API_KEY
        require(key.isNotBlank()) {
            "Add OPENAI_API_KEY=your_key_here to local.properties, then rebuild the app."
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
    var darkMode by rememberSaveable { mutableStateOf(false) }
    var arabic by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    DevAITheme(darkTheme = darkMode) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            if (showSettings) {
                SettingsScreen(
                    arabic = arabic,
                    darkMode = darkMode,
                    onBack = { showSettings = false },
                    onDarkModeChanged = { darkMode = it },
                    onArabicChanged = { arabic = it }
                )
            } else if (showHistory) {
                ChatHistoryScreen(
                    chats = chatViewModel.uiState.collectAsStateWithLifecycle().value.chats,
                    arabic = arabic,
                    onBack = { showHistory = false },
                    onNewChat = { chatViewModel.createNewChat(); showHistory = false },
                    onChatSelected = { chatViewModel.openChat(it); showHistory = false },
                    onDeleteChat = { chatViewModel.deleteChat(it) }
                )
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
                        Text(
                            if (arabic) "محادثة ذكية بسيطة" else "Simple AI chat",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = if (arabic) "المحادثات" else "Chat history"
                        )
                    }
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (arabic) "محادثة جديدة" else "New chat"
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = if (arabic) "الإعدادات" else "Settings"
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
                    placeholder = { Text(if (arabic) "اسأل أي شيء…" else "Ask anything…") },
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
                        contentDescription = if (arabic) "إرسال" else "Send",
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
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (arabic) "رجوع" else "Back"
                        )
                    }
                },
                title = { Text(if (arabic) "المحادثات" else "Chat history") },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (arabic) "محادثة جديدة" else "New chat"
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
                Text(if (arabic) "لا توجد محادثات بعد" else "No chats yet")
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
                                contentDescription = if (arabic) "حذف المحادثة" else "Delete chat",
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
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (arabic) "رجوع" else "Back"
                        )
                    }
                },
                title = { Text(if (arabic) "الإعدادات" else "Settings") }
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
                text = if (arabic) "المظهر" else "Appearance",
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
                    Text(if (arabic) "الوضع الداكن" else "Dark mode")
                }
                Switch(checked = darkMode, onCheckedChange = onDarkModeChanged)
            }

            Text(
                text = if (arabic) "اللغة" else "Language",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (arabic) "العربية" else "Arabic")
                Switch(checked = arabic, onCheckedChange = onArabicChanged)
            }

            Text(
                text = if (arabic) "يمكنك التبديل بين العربية والإنجليزية." else "Switch between Arabic and English.",
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

