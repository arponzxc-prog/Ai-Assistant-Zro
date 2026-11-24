# NOVA AI – Telegram Automation (report)

## Approach
Telegram automation reads the notification Telegram already shows on the device (user-granted
Notification Listener) and answers through the notification's own inline-reply action (RemoteInput).
No Telegram account/API access, userbot, or session access is used. Official Bot API mode is NOT implemented.

## New files
- automation/telegram/: TelegramAutomationManager, TelegramAssistant, GeminiTelegramReplyEngine,
  TelegramReplySender, TelegramAutomationPreferences, TelegramSafetyFilter, TelegramContactRuleEngine,
  TelegramVoiceAnnouncer, TelegramMessageProcessor, TelegramMessageClassifier
- automation/telegram/db/: TelegramEntities, TelegramDao, TelegramDatabase (own DB file, no destructive migration)
- automation/common/NotificationDeduplicator.kt
- ui/telegram/TelegramAutomationActivity.kt + layouts activity_telegram_automation, item_telegram_log, item_telegram_rule, drawable ic_telegram
- test: TelegramAutomationLogicTest.kt (28 unit tests)

## Modified files (additive only)
- NovaNotificationListenerService.kt: routes WhatsApp vs Telegram (org.telegram.messenger, org.telegram.messenger.web), requestRebind on disconnect
- JarvisApp.kt: creates telegramAutomationManager / telegramAssistant (reuses existing Gemini key + model)
- AndroidManifest.xml: activity + <queries> for Telegram packages
- SettingsFragment.kt + fragment_settings.xml: separate Telegram card (status badge, own ON/OFF state)

## Safety defaults
Master OFF, Auto reply OFF, groups ignored, previews not logged, OTP/password/card messages never answered or sent to AI,
cooldown 60s, 20 replies/hour global, 5/hour per chat.

## NOT verified (honest status)
This environment had no Kotlin compiler, Gradle or Android SDK, so NOTHING was compiled or run here.
Code was reviewed by hand (resource references and view-binding ids cross-checked: all resolve).
Please run: ./gradlew :app:testDebugUnitTest :app:assembleDebug
Needs a real device with Telegram to confirm: notification detection, inline reply availability, Bengali/English
Gemini replies, service reconnection. WhatsApp regression test not run.
Not implemented: Official Telegram Bot API optional mode.
