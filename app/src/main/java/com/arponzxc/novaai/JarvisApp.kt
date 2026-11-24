package com.arponzxc.novaai

import android.app.Application
import com.arponzxc.novaai.security.SecurityManager
import com.arponzxc.novaai.audio.VoskModelManager
import com.arponzxc.novaai.automation.command.CommandExecutor
import com.arponzxc.novaai.automation.contacts.ContactResolver
import com.arponzxc.novaai.automation.phone.PhoneControlManager
import com.arponzxc.novaai.automation.whatsapp.WhatsAppAssistant
import com.arponzxc.novaai.automation.youtube.YouTubeAutomationManager
import com.arponzxc.novaai.data.memory.MemoryDatabase
import com.arponzxc.novaai.data.memory.MemoryManager
import com.arponzxc.novaai.data.memory.MemoryRepository
import com.arponzxc.novaai.data.preferences.AppPreferences
import com.arponzxc.novaai.data.preferences.WebSearchPreferences
import com.arponzxc.novaai.data.preferences.YouTubePreferences
import com.arponzxc.novaai.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JarvisApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var preferences: AppPreferences
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var memoryDatabase: MemoryDatabase
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var youTubePreferences: YouTubePreferences
        private set

    lateinit var webSearchPreferences: WebSearchPreferences
        private set

    lateinit var youTubeAutomationManager: YouTubeAutomationManager
        private set

    lateinit var contactResolver: ContactResolver
        private set

    lateinit var phoneControlManager: PhoneControlManager
        private set

    lateinit var whatsAppAssistant: WhatsAppAssistant
        private set

    lateinit var whatsAppAutomationManager: com.arponzxc.novaai.automation.whatsapp.WhatsAppAutomationManager
        private set

    lateinit var telegramAutomationManager: com.arponzxc.novaai.automation.telegram.TelegramAutomationManager
        private set

    lateinit var telegramAssistant: com.arponzxc.novaai.automation.telegram.TelegramAssistant
        private set

    lateinit var safFileManager: com.arponzxc.novaai.storage.SafFileManager
        private set

    lateinit var commandExecutor: CommandExecutor
        private set

    override fun onCreate() {
        super.onCreate()
        SecurityManager.verify(this)
        instance = this
        preferences = AppPreferences(this)
        chatRepository = ChatRepository(preferences)

        // Memory System
        memoryDatabase = MemoryDatabase.getInstance(this)
        memoryRepository = MemoryRepository(memoryDatabase.memoryDao())
        memoryManager = MemoryManager(memoryRepository, preferences, chatRepository, appScope)

        // Automation Systems
        youTubePreferences = YouTubePreferences(this)
        webSearchPreferences = WebSearchPreferences(this)
        youTubeAutomationManager = YouTubeAutomationManager(this, youTubePreferences)
        contactResolver = ContactResolver(this)
        phoneControlManager = PhoneControlManager(this, contactResolver)
        whatsAppAssistant = WhatsAppAssistant(this, contactResolver)
        whatsAppAutomationManager = com.arponzxc.novaai.automation.whatsapp.WhatsAppAutomationManager(
            context = this,
            apiKeyProvider = { preferences.apiKey },
            modelProvider = { preferences.aiModel }
        )
        // Telegram automation: independent manager/DB/prefs; re-uses the existing Gemini key + model
        telegramAutomationManager = com.arponzxc.novaai.automation.telegram.TelegramAutomationManager(
            context = this,
            apiKeyProvider = { preferences.apiKey },
            modelProvider = { preferences.aiModel }
        )
        telegramAssistant = com.arponzxc.novaai.automation.telegram.TelegramAssistant(this)
        safFileManager = com.arponzxc.novaai.storage.SafFileManager(this, preferences)
        com.arponzxc.novaai.storage.SafActionCoordinator.initialize(safFileManager, preferences)

        // Command Engine
        commandExecutor = CommandExecutor(
            phoneManager = phoneControlManager,
            youtubeManager = youTubeAutomationManager,
            whatsappAssistant = whatsAppAssistant,
            memoryManager = memoryManager,
            safFileManager = safFileManager
        )

        VoskModelManager.init(this, CoroutineScope(Dispatchers.Default))
        com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.initialize(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                com.arponzxc.novaai.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                if (startedActivities == 1) _isAppForeground.value = true
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                currentResumedActivity = activity
                com.arponzxc.novaai.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) _isAppForeground.value = false
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
        })
    }

    var currentResumedActivity: android.app.Activity? = null
        private set

    fun moveAppToBack(): Boolean {
        val act = currentResumedActivity ?: return false
        return try {
            act.moveTaskToBack(true)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val _isAppForeground = MutableStateFlow(false)
        val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

        lateinit var instance: JarvisApp
            private set
    }
}

