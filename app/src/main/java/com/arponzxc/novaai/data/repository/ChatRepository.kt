package com.arponzxc.novaai.data.repository

import com.arponzxc.novaai.data.model.ChatTurn
import com.arponzxc.novaai.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" }
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
    }

    @Synchronized
    fun addPendingUserTurn(userText: String) {
        val cleanUser = userText.trim()
        if (cleanUser.isEmpty()) return

        val currentList = _turns.value.toMutableList()
        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == "...") {
                return
            }
        }
        currentList.add(ChatTurn(userTranscript = cleanUser, jarvisResponse = "..."))
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }
        _turns.value = currentList
        preferences.saveChatHistory(currentList)
    }

    @Synchronized
    fun completePendingTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()
        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()
        val pendingIdx = currentList.indexOfLast {
            it.userTranscript == cleanUser &&
                (it.jarvisResponse == "..." || it.jarvisResponse == "(Audio response)")
        }
        if (pendingIdx >= 0) {
            currentList[pendingIdx] = currentList[pendingIdx].copy(
                userTranscript = cleanUser.ifEmpty { currentList[pendingIdx].userTranscript },
                jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" }
            )
            _turns.value = currentList
            preferences.saveChatHistory(currentList)
            return
        }
        addTurn(cleanUser, cleanJarvis)
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
    }
}
