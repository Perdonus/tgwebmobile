package com.tgweb.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.ChatRepository
import com.tgweb.core.data.ChatSummary
import com.tgweb.core.data.MessageItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TGWebScreen()
            }
        }
    }
}

class MainViewModel(
    private val repository: ChatRepository,
) : ViewModel() {
    val dialogs: StateFlow<List<ChatSummary>> = repository.observeDialogList()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun messages(chatId: Long): StateFlow<List<MessageItem>> = repository.observeMessages(chatId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun sync() = viewModelScope.launch { repository.syncNow("manual") }

    fun send(chatId: Long, text: String) = viewModelScope.launch {
        repository.sendMessage(chatId, text)
    }
}

private class MainViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(AppRepositories.chatRepository) as T
    }
}

@Composable
private fun TGWebScreen(
    vm: MainViewModel = viewModel(factory = MainViewModelFactory()),
) {
    val dialogs by vm.dialogs.collectAsState()
    var selectedChatId by rememberSaveable { mutableLongStateOf(1001L) }
    var draft by remember { mutableStateOf("Hello from offline queue") }
    val messages by vm.messages(selectedChatId).collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("TGWeb Android", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Button(onClick = { scope.launch { vm.sync() } }) {
                    Text("Sync")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Dialogs", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(dialogs, key = { it.chatId }) { dialog ->
                    Card(onClick = { selectedChatId = dialog.chatId }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(dialog.title, fontWeight = FontWeight.SemiBold)
                            Text(dialog.lastMessagePreview)
                            if (dialog.unreadCount > 0) {
                                Text("Unread: ${dialog.unreadCount}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            Text("Messages: $selectedChatId", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(messages, key = { it.messageId }) { msg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(msg.text)
                            Text("${msg.status} • ${msg.createdAt}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Button(onClick = { scope.launch { vm.send(selectedChatId, draft) } }, modifier = Modifier.fillMaxWidth()) {
                Text("Send test message")
            }
        }
    }
}
