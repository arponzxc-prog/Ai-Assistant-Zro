package com.arponzxc.novaai.automation.command

import com.arponzxc.novaai.automation.phone.DomainNormalizer
import java.util.Locale

data class ParsedCommandResult(
    val command: JarvisCommand?,
    val confidence: Float,
    val matchedPattern: String = ""
)

object CommandParser {

    private const val CONFIDENCE_THRESHOLD = 0.90f

    // Words/queries that are too vague to safely act on without checking with the user first.
    // If speech recognition only picks up one of these (or something this short), it usually
    // means the mic missed the real title, not that the user actually wants to play "it".
    private val AMBIGUOUS_PLAY_WORDS = setOf(
        "something", "anything", "whatever", "that", "this", "it", "one",
        "song", "video", "music", "gana", "gane", "track",
        "kuch", "kuch bhi", "pata nahi", "i don't know", "idk", "um", "uh", "hmm", "huh"
    )

    /**
     * Returns true when a parsed YouTube play query is too vague/short to trust blindly
     * (likely a mis-heard or dropped title), meaning Jarvis should confirm with the user
     * before actually launching a video instead of guessing.
     */
    fun isAmbiguousPlayQuery(query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return true
        if (q.length < 3) return true
        if (AMBIGUOUS_PLAY_WORDS.contains(q)) return true
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        if (words.size == 1 && words[0].length <= 3) return true
        if (words.all { AMBIGUOUS_PLAY_WORDS.contains(it) }) return true
        return false
    }

    fun parse(
        input: String,
        isAwaitingConfirmation: Boolean = false,
        isAwaitingDisambiguation: Boolean = false,
        isAwaitingStorageConfirmation: Boolean = false
    ): ParsedCommandResult {
        val rawWithoutWake = input.trim()
            .replace(Regex("(?i)^(hey\\s+nova|hi\\s+nova|hello\\s+nova|ok\\s+nova|okay\\s+nova|nova|hey\\s+jarvis|hi\\s+jarvis|hello\\s+jarvis|ok\\s+jarvis|jarvis|service|হেই\\s+নোভা|হ্যালো\\s+নোভা|ওকে\\s+নোভা|নোভা)[,\\s:-]*"), "")
            .replace(Regex("[.,!?:;।\"'\\s]+$"), "")
            .trim()

        val clean = rawWithoutWake.lowercase(Locale.ROOT)

        if (clean.isBlank()) {
            return ParsedCommandResult(null, 0.0f)
        }

        // 0. Explicit Voice Listening Controls ("NOVA, Start Listening", "NOVA, Stop Listening", Bengali equivalents)
        com.arponzxc.novaai.audio.VoiceControlCommandDetector.detect(input)?.let { voiceIntent ->
            return ParsedCommandResult(
                JarvisCommand.VoiceListeningControl(voiceIntent),
                0.99f,
                "VOICE_CONTROL_${voiceIntent.name}"
            )
        }

        // 0b. Storage Overwrite / Delete Confirmation (highest priority when SAF confirmation prompt is active)
        if (isAwaitingStorageConfirmation) {
            if (clean.matches(Regex("^(yes|yeah|yup|confirm|overwrite|delete|do it|proceed|ok|okay|sure|haan|haanji|হ্যাঁ|হ্যা|নিশ্চিত|করো|কর|মুছে\\s+ফেলো|ওভাররাইট\\s+করো|ডিলিট\\s+করো).*"))) {
                return ParsedCommandResult(JarvisCommand.ConfirmStorageAction(true), 1.0f, "STORAGE_CONFIRM")
            }
            if (clean.matches(Regex("^(no|nope|cancel|abort|don't|dont|stop|never mind|nahi|না|বাতিল|বাতিল\\s+করো|থামো|করো\\s+না).*"))) {
                return ParsedCommandResult(JarvisCommand.ConfirmStorageAction(false), 1.0f, "STORAGE_CANCEL")
            }
        }

        // 1. Confirmations & Cancellations (Priority when awaiting confirmation)
        if (isAwaitingConfirmation) {
            if (clean.matches(Regex("^(yes|yeah|yup|send|send it|confirm|do it|sure|okay|go ahead|please send|haan|haanji|kardo|theek hai|avunu|sare|chey|aamaa|sari|ho|thik ache|thik ahe|kar da|kar do|হ্যাঁ|হ্যা).*"))) {
                return ParsedCommandResult(JarvisCommand.ConfirmAction(true), 1.0f, "CONFIRM")
            }
            if (clean.matches(Regex("^(no|nope|cancel|don't|dont|never mind|stop|abort|nahi|mat karo|ruko|vaddu|oddu|vendaam|nako|thamba|naahi|mat kara|না|বাতিল).*"))) {
                return ParsedCommandResult(JarvisCommand.ConfirmAction(false), 1.0f, "CANCEL")
            }
        }

        // 2. Disambiguation choice when multiple contacts match
        if (isAwaitingDisambiguation) {
            return ParsedCommandResult(JarvisCommand.DisambiguationChoice(clean), 0.95f, "DISAMBIGUATION")
        }

        // 2a. NOVA Autonomous App & Web Development Studio Commands (Bengali & English)
        parseDevStudioCommand(rawWithoutWake, clean)?.let { return it }

        // 2a-bis. NOVA Phase 2 Commands (Vision, Research, File Manager, Floating Avatar, Multi-Provider API)
        parsePhase2Command(rawWithoutWake, clean)?.let { return it }

        // 2b. Android SAF File & Folder Commands (Bengali & English)
        parseStorageCommand(rawWithoutWake, clean)?.let { return it }

        // 2c. YouTube AI Assistant Intents (Search, Popular BD, Channel, Stats)
        com.arponzxc.novaai.automation.youtube.YouTubeIntentHandler.parse(rawWithoutWake)?.let {
            return ParsedCommandResult(JarvisCommand.YouTubeAiAssistantCommand(rawWithoutWake), 0.98f, "YOUTUBE_AI_INTENT")
        }

        val prefix = "^(?:please\\s+|can you\\s+|could you\\s+|just\\s+)?"

        // 3. Flashlight / Torch Commands (Strict Anchoring & Natural Variations across Indian languages)
        val flashWords = "(?:flash\\s*light|flash\\s*lite|flashlight|flash|torch|light|batti)"
        if (clean.matches(Regex("$prefix(?:turn\\s+on|open|enable|switch\\s+on)\\s+(?:the\\s+)?$flashWords$")) ||
            clean.matches(Regex("$prefix(?:turn|switch)\\s+(?:the\\s+)?$flashWords\\s+on$")) ||
            clean.matches(Regex("$prefix$flashWords\\s+(?:on|chalu|jalao|chalu\\s+karo|chalu\\s+kar\\s+do|on\\s+karo|on\\s+chey|on\\s+pannu|jwalao|chalu\\s+kara|jara)$")) ||
            clean.matches(Regex("$prefix(?:turn\\s+torch\\s+on|turn\\s+$flashWords\\s+on)$"))
        ) {
            return ParsedCommandResult(JarvisCommand.Flash(true), 0.98f, "FLASH_ON")
        }
        if (clean.matches(Regex("$prefix(?:turn\\s+off|close|disable|switch\\s+off)\\s+(?:the\\s+)?$flashWords$")) ||
            clean.matches(Regex("$prefix(?:turn|switch)\\s+(?:the\\s+)?$flashWords\\s+off$")) ||
            clean.matches(Regex("$prefix$flashWords\\s+(?:off|band|bujhao|band\\s+karo|band\\s+kar\\s+do|off\\s+karo|off\\s+chey|off\\s+pannu|nibhao|band\\s+kara|buta)$")) ||
            clean.matches(Regex("$prefix(?:turn\\s+torch\\s+off|turn\\s+$flashWords\\s+off)$"))
        ) {
            return ParsedCommandResult(JarvisCommand.Flash(false), 0.98f, "FLASH_OFF")
        }

        // 3b. YouTube Subscribe (Strict Anchoring)
        if (clean.matches(Regex("$prefix(?:subscribe|hit\\s+subscribe|click\\s+subscribe|press\\s+subscribe|subscribe\\s+to\\s+(?:this|the)\\s+channel|subscribe\\s+(?:karo|kardo|kar\\s+do))$"))) {
            return ParsedCommandResult(JarvisCommand.SubscribeChannel, 0.97f, "YOUTUBE_SUBSCRIBE")
        }

        // 3c. YouTube Fullscreen Toggle (Strict Anchoring)
        if (clean.matches(Regex("$prefix(?:make\\s+it\\s+|go\\s+)?full\\s*screen(?:\\s+mode)?$")) ||
            clean.matches(Regex("$prefix(?:make|go)\\s+full\\s*screen$")) ||
            clean.matches(Regex("$prefix(?:expand|maximi[sz]e)\\s+(?:the\\s+)?(?:video|screen|player)$")) ||
            clean.matches(Regex("^full\\s*screen\\s+(?:karo|kardo|kar\\s+do)$"))
        ) {
            return ParsedCommandResult(JarvisCommand.ToggleFullscreen, 0.97f, "YOUTUBE_FULLSCREEN")
        }

        // 4. Brightness Controls (Strict Anchoring)
        val levelWords = "(\\d{1,3}|zero|mute|min|minimum|lowest|silence|nil|off|ten|twenty|twenty five|quarter|thirty|forty|fifty|half|sixty|seventy|seventy five|eighty|ninety|hundred|full|max|maximum|highest)"
        val brightnessPattern = Regex("$prefix(?:set|make|turn|put|bring)?\\s*(?:the\\s+)?(?:screen\\s+)?brightness\\s*(?:to|at|is|=)?\\s*$levelWords\\s*%?$")
            .find(clean)
            ?: Regex("^(?:screen\\s+)?brightness\\s+(?:ko\\s+)?$levelWords\\s*(?:percent|%|karo)?$").find(clean)
            ?: Regex("$prefix(?:make|set)\\s+(?:the\\s+)?(?:screen\\s+)?brightness\\s+$levelWords$").find(clean)

        if (brightnessPattern != null) {
            val token = brightnessPattern.groupValues[1]
            val level = parseNumericLevel(token)
            if (level != null) {
                return ParsedCommandResult(JarvisCommand.Brightness(level), 0.96f, "BRIGHTNESS_SET")
            }
        }

        if (clean == "increase brightness" || clean == "brightness up" ||
            clean == "make it brighter" || clean == "make screen brighter" ||
            clean == "turn up brightness" || clean == "raise brightness" ||
            clean == "brighter" || clean == "more brightness" ||
            clean == "bright up" || clean == "screen brighter" ||
            clean == "brightness badhao" || clean == "light badhao" ||
            clean == "brightness badha do" || clean == "light badha do") {
            return ParsedCommandResult(JarvisCommand.Brightness(0, isRelative = true, delta = 20), 0.95f, "BRIGHTNESS_UP")
        }
        if (clean == "decrease brightness" || clean == "brightness down" ||
            clean == "dim brightness" || clean == "lower brightness" ||
            clean == "make it dimmer" || clean == "make screen dimmer" ||
            clean == "turn down brightness" || clean == "reduce brightness" ||
            clean == "dim the screen" || clean == "dimmer" ||
            clean == "less brightness" || clean == "screen dimmer" ||
            clean == "brightness kam" || clean == "light kam" ||
            clean == "brightness kam karo" || clean == "light kam karo") {
            return ParsedCommandResult(JarvisCommand.Brightness(0, isRelative = true, delta = -20), 0.95f, "BRIGHTNESS_DOWN")
        }
        if (clean == "maximum brightness" || clean == "full brightness" ||
            clean == "brightness maximum" || clean == "brightness full" ||
            clean == "max brightness" || clean == "brightness max" ||
            clean == "brightest") {
            return ParsedCommandResult(JarvisCommand.Brightness(100), 0.95f, "BRIGHTNESS_MAX")
        }
        if (clean == "minimum brightness" || clean == "lowest brightness" ||
            clean == "brightness minimum" || clean == "brightness lowest" ||
            clean == "min brightness" || clean == "brightness min" ||
            clean == "brightness zero" || clean == "dimmest") {
            return ParsedCommandResult(JarvisCommand.Brightness(0), 0.95f, "BRIGHTNESS_MIN")
        }

        // 5. Volume Controls (Strict Anchoring)
        val volumePattern = Regex("$prefix(?:set|make|turn|put|bring)?\\s*(?:the\\s+)?(?:media\\s+|sound\\s+)?volume\\s*(?:to|at|is|=)?\\s*$levelWords\\s*%?$")
            .find(clean)
            ?: Regex("^(?:media\\s+|sound\\s+)?volume\\s+(?:ko\\s+)?$levelWords\\s*(?:percent|%|karo)?$").find(clean)
            ?: Regex("$prefix(?:make|set)\\s+(?:the\\s+)?(?:media\\s+|sound\\s+)?volume\\s+$levelWords$").find(clean)

        if (volumePattern != null) {
            val token = volumePattern.groupValues[1]
            val level = parseNumericLevel(token)
            if (level != null) {
                return ParsedCommandResult(JarvisCommand.Volume(level), 0.96f, "VOLUME_SET")
            }
        }

        if (clean == "volume up" || clean == "increase volume" ||
            clean == "louder" || clean == "turn up volume" ||
            clean == "turn up the volume" || clean == "raise volume" ||
            clean == "raise the volume" || clean == "more volume" ||
            clean == "volume increase" || clean == "increase the volume" ||
            clean == "make it louder" || clean == "up the volume" ||
            clean == "sound up" || clean == "audio up" ||
            clean == "volume badhao" || clean == "awaz badhao" ||
            clean == "volume badha do" || clean == "awaz badha do" ||
            clean == "crank up" || clean == "pump up the volume") {
            return ParsedCommandResult(JarvisCommand.Volume(0, isRelative = true, delta = 1), 0.95f, "VOLUME_UP")
        }
        if (clean == "volume down" || clean == "decrease volume" ||
            clean == "lower volume" || clean == "softer" ||
            clean == "turn down volume" || clean == "turn down the volume" ||
            clean == "reduce volume" || clean == "reduce the volume" ||
            clean == "less volume" || clean == "volume decrease" ||
            clean == "decrease the volume" || clean == "lower the volume" ||
            clean == "make it softer" || clean == "quieter" ||
            clean == "sound down" || clean == "audio down" ||
            clean == "volume kam" || clean == "awaz kam" ||
            clean == "volume kam karo" || clean == "awaz kam karo" ||
            clean == "make it quieter" || clean == "down the volume") {
            return ParsedCommandResult(JarvisCommand.Volume(0, isRelative = true, delta = -1), 0.95f, "VOLUME_DOWN")
        }
        if (clean == "mute" || clean == "mute volume" || clean == "silence media" ||
            clean == "mute the volume" || clean == "mute it" ||
            clean == "go silent" || clean == "silent mode" ||
            clean == "sound band" || clean == "volume band" ||
            clean == "volume zero" || clean == "awaz band") {
            return ParsedCommandResult(JarvisCommand.Volume(0), 0.95f, "VOLUME_MUTE")
        }
        if (clean == "full volume" || clean == "max volume" ||
            clean == "maximum volume" || clean == "volume max" ||
            clean == "volume full" || clean == "awaz full" ||
            clean == "loudest") {
            return ParsedCommandResult(JarvisCommand.Volume(100), 0.95f, "VOLUME_MAX")
        }

        // 6. Media Playback Controls (Strict Anchoring)
        if (clean == "pause" || clean == "pause music" || clean == "pause video" || clean == "pause playback" ||
            clean == "pause the music" || clean == "pause the video" || clean == "stop playing") {
            return ParsedCommandResult(JarvisCommand.PauseMedia, 0.98f, "MEDIA_PAUSE")
        }
        if (clean == "resume" || clean == "resume music" || clean == "resume playback" || clean == "continue playback" ||
            clean == "resume the music" || clean == "continue playing" || clean == "resume playing") {
            return ParsedCommandResult(JarvisCommand.ResumeMedia, 0.98f, "MEDIA_RESUME")
        }
        if (clean == "next" || clean == "next video" || clean == "next song" || clean == "skip" ||
            clean == "skip this" || clean == "next track" || clean == "play next") {
            return ParsedCommandResult(JarvisCommand.NextMedia, 0.98f, "MEDIA_NEXT")
        }
        if (clean == "previous" || clean == "previous video" || clean == "previous song" ||
            clean == "go back" || clean == "previous track" || clean == "play previous") {
            return ParsedCommandResult(JarvisCommand.PreviousMedia, 0.98f, "MEDIA_PREVIOUS")
        }

        // 7. Memory Commands
        if (clean.startsWith("remember ") || clean.startsWith("remember that ")) {
            val fact = input.replace(Regex("(?i)^(hey nova|nova|hey jarvis|jarvis|please)?\\s*(remember that|remember)\\s*"), "").trim()
            return ParsedCommandResult(JarvisCommand.Remember(fact), 0.95f, "MEMORY_REMEMBER")
        }
        if (clean.startsWith("forget ") || clean.startsWith("forget that ") || clean.startsWith("forget my ")) {
            val target = input.replace(Regex("(?i)^(hey nova|nova|hey jarvis|jarvis|please)?\\s*(forget that|forget my|forget about|forget)\\s*"), "").trim()
            return ParsedCommandResult(JarvisCommand.Forget(target), 0.95f, "MEMORY_FORGET")
        }
        if (clean == "what do you remember about me" || clean == "what do you remember" || clean == "tell me what you remember") {
            return ParsedCommandResult(JarvisCommand.WhatDoYouRemember, 0.98f, "MEMORY_WHAT")
        }

        // Memory Recall: Birthday
        if (clean.matches(Regex("$prefix(?:when\\s+is|what\\s+is|when's|what's|tell\\s+me|do\\s+you\\s+know)\\s+(?:my\\s+)?birthday$")) ||
            clean.matches(Regex("$prefix(?:mera\\s+)?(?:birthday|janamdin)\\s+(?:kab\\s+hai|kab\\s+aata\\s+hai|kab\\s+h)$")) ||
            clean == "when is my birthday" || clean == "what is my birthday" || clean == "mera birthday kab hai") {
            return ParsedCommandResult(JarvisCommand.RecallBirthday, 0.98f, "MEMORY_RECALL_BIRTHDAY")
        }

        // Memory Recall: Favorite Song
        if (clean.matches(Regex("$prefix(?:what\\s+is|what's|tell\\s+me|do\\s+you\\s+know)\\s+(?:my\\s+)?favou?rite\\s+(?:song|music|gana|track)$")) ||
            clean.matches(Regex("$prefix(?:mera\\s+)?favou?rite\\s+(?:gana|song)\\s+(?:kaun\\s+sa\\s+hai|kya\\s+hai)$")) ||
            clean == "what is my favorite song" || clean == "what is my favourite song" || clean == "mera favorite gana kaun sa hai") {
            return ParsedCommandResult(JarvisCommand.RecallFavoriteSong, 0.98f, "MEMORY_RECALL_FAVORITE_SONG")
        }

        // Memory Recall: Routine
        if (clean.matches(Regex("$prefix(?:what\\s+is|what's|tell\\s+me|do\\s+you\\s+know)\\s+(?:my\\s+)?(?:daily\\s+)?routine$")) ||
            clean.matches(Regex("$prefix(?:mera\\s+)?(?:daily\\s+)?routine\\s+(?:kya\\s+hai|batao)$")) ||
            clean == "what is my routine" || clean == "what is my daily routine" || clean == "mera routine kya hai") {
            return ParsedCommandResult(JarvisCommand.RecallRoutine, 0.98f, "MEMORY_RECALL_ROUTINE")
        }

        // 8. Calling & Messaging (Strict Intent Filtering)

        // 0. WhatsApp Delete Message ("delete for everyone" must be checked before the
        //    generic "delete message" pattern so intent isn't lost)
        val deleteEveryoneRegex = Regex(
            "${prefix}delete\\s+(?:the\\s+)?(?:last\\s+|my\\s+|that\\s+)?(?:whatsapp\\s+)?message" +
                "(?:\\s+(?:on|from|in)\\s+whatsapp)?\\s*(?:from|for)\\s+everyone" +
                "(?:\\s+(?:to|for|from)\\s+([a-zA-Z0-9\\s]+))?$",
            RegexOption.IGNORE_CASE
        )
        val deleteEveryoneMatch = deleteEveryoneRegex.find(clean)
        if (deleteEveryoneMatch != null) {
            val contact = deleteEveryoneMatch.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            return ParsedCommandResult(JarvisCommand.WhatsAppDeleteMessage(contact, forEveryone = true), 0.96f, "WHATSAPP_DELETE_EVERYONE")
        }

        val deleteForMeRegex = Regex(
            "${prefix}delete\\s+(?:the\\s+)?(?:last\\s+|my\\s+|that\\s+)?(?:whatsapp\\s+)?message" +
                "(?:\\s+(?:on|from|in)\\s+whatsapp)?" +
                "(?:\\s+(?:to|for)\\s+([a-zA-Z0-9\\s]+))?$",
            RegexOption.IGNORE_CASE
        )
        val deleteForMeMatch = deleteForMeRegex.find(clean)
        if (deleteForMeMatch != null) {
            val contact = deleteForMeMatch.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            return ParsedCommandResult(JarvisCommand.WhatsAppDeleteMessage(contact, forEveryone = false), 0.94f, "WHATSAPP_DELETE_FOR_ME")
        }

        // A. WhatsApp Video Call
        val waVideoRegex = Regex("$prefix(?:whatsapp\\s+video\\s+call\\s+(?:to\\s+)?|video\\s+call\\s+(?:to\\s+)?([a-zA-Z0-9\\s]+?)\\s+on\\s+whatsapp|video\\s+call\\s+(?:to\\s+)?)([a-zA-Z0-9\\s]+?)$", RegexOption.IGNORE_CASE)
        val waVideoHinglish = Regex("^([a-zA-Z0-9]+)\\s+ko\\s+(?:whatsapp\\s+)?video\\s+call\\s*(?:karo|lagao|kardo)$", RegexOption.IGNORE_CASE)
        val waVideoMatch = waVideoRegex.find(clean) ?: waVideoHinglish.find(clean)
        if (waVideoMatch != null && (clean.contains("video") || clean.contains("whatsapp"))) {
            val contact = (waVideoMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: waVideoMatch.groupValues[1])
                .replace(Regex("(?i)\\s+(on\\s+whatsapp|using\\s+whatsapp|karo|lagao|kardo)$"), "")
                .trim()
            if (contact.isNotBlank() && !listOf("me", "him", "her", "them", "someone", "anyone").contains(contact)) {
                return ParsedCommandResult(JarvisCommand.WhatsAppCall(contact, video = true), 0.96f, "WHATSAPP_VIDEO_CALL")
            }
        }

        // B. WhatsApp Voice Call
        val waVoiceRegex = Regex("$prefix(?:whatsapp\\s+call(?:\\s+to)?|make\\s+a\\s+whatsapp\\s+call\\s+to|call\\s+([a-zA-Z0-9\\s]+?)\\s+on\\s+whatsapp)\\s*([a-zA-Z0-9\\s]*)$", RegexOption.IGNORE_CASE)
        val waVoiceHinglish = Regex("^([a-zA-Z0-9]+)\\s+ko\\s+whatsapp\\s+call\\s*(?:karo|lagao|kardo)$", RegexOption.IGNORE_CASE)
        val waVoiceMatch = waVoiceRegex.find(clean) ?: waVoiceHinglish.find(clean)
        if (waVoiceMatch != null && clean.contains("whatsapp") && !clean.contains("message") && !clean.contains("msg")) {
            val c1 = waVoiceMatch.groupValues.getOrNull(2)?.trim() ?: ""
            val c2 = waVoiceMatch.groupValues.getOrNull(1)?.trim() ?: ""
            val rawContact = if (c1.isNotBlank()) c1 else c2
            val contact = rawContact.replace(Regex("(?i)\\s+(on\\s+whatsapp|using\\s+whatsapp|karo|lagao)$"), "").trim()
            if (contact.isNotBlank() && !listOf("me", "him", "her", "them", "someone").contains(contact)) {
                return ParsedCommandResult(JarvisCommand.WhatsAppCall(contact, video = false), 0.96f, "WHATSAPP_VOICE_CALL")
            }
        }

        // C. WhatsApp Messages (Anchored with clear recipient and message)
        val waComprehensiveRegex = Regex("$prefix(?:send\\s+(?:a\\s+)?(?:whatsapp\\s+)?message\\s+(?:to\\s+)?|send\\s+(?:a\\s+)?whatsapp\\s+(?:to\\s+)?|message\\s+(?:to\\s+)?|whatsapp\\s+(?:to\\s+)?)\\s*([a-zA-Z0-9]+)\\s+(?:that|saying|:)?\\s*(.+)$", RegexOption.IGNORE_CASE)
        val waComprehensiveHinglish = Regex("^([a-zA-Z0-9]+)\\s+ko\\s+(?:whatsapp\\s+)?(?:message|msg)?\\s*(?:bhejo|karo|bhej do)\\s*(?:saying|that|:)?\\s*(.+)$", RegexOption.IGNORE_CASE)
        val waSendMatch = waComprehensiveRegex.find(input) ?: waComprehensiveHinglish.find(input)
        if (waSendMatch != null) {
            val contact = waSendMatch.groupValues[1]
                .replace(Regex("(?i)\\s+(on\\s+whatsapp|via\\s+whatsapp|using\\s+whatsapp)$"), "")
                .trim()
            val message = waSendMatch.groupValues[2].trim()
            val nonContacts = listOf("me", "someone", "anyone", "him", "her", "that", "to", "the", "for")
            if (contact.isNotBlank() && message.isNotBlank() && !nonContacts.contains(contact.lowercase(Locale.ROOT))) {
                return ParsedCommandResult(JarvisCommand.WhatsAppMessage(contact, message), 0.96f, "WHATSAPP_MESSAGE")
            }
        }

        // D. Cellular Phone Call (SIM / Dialer)
        // Must start with call verb (e.g. "call mom", "dial 9876543210", "make a phone call to dad")
        val phoneCallRegex = Regex("$prefix(?:make\\s+(?:a\\s+)?(?:phone\\s+)?call\\s+to|phone\\s+call\\s+to|dial|call(?:\\s+to)?)\\s+([a-zA-Z0-9+\\s]+)$", RegexOption.IGNORE_CASE)
        val phoneCallHinglish = Regex("^([a-zA-Z0-9+\\s]+?)\\s+ko\\s+(?:phone\\s+|call\\s+)?(?:call\\s+karo|call\\s+lagao|phone\\s+karo|phone\\s+lagao)$", RegexOption.IGNORE_CASE)
        val phoneCallMatch = phoneCallRegex.find(clean) ?: phoneCallHinglish.find(clean)
        if (phoneCallMatch != null && !clean.contains("whatsapp") && !clean.contains("video")) {
            val target = phoneCallMatch.groupValues[1].trim()
                .replace(Regex("(?i)^(him|her|them|someone|anyone|later|back)$"), "")
                .trim()
            val nonTargetWords = listOf("it", "that", "this", "day", "off", "back", "later", "yesterday", "tomorrow", "nova", "jarvis")
            if (target.isNotBlank() && target.length >= 2 && !nonTargetWords.contains(target.lowercase(Locale.ROOT))) {
                return ParsedCommandResult(JarvisCommand.PhoneCall(target), 0.96f, "PHONE_CALL")
            }
        }

        // 9. YouTube Video Play & Search (Strict Intent Anchoring)
        // Compound command: "open youtube and play [song]" / "open youtube and search [query]"
        val openAndPlayMatch = Regex("$prefix(?:open\\s+youtube\\s+(?:and|&)?\\s*(?:play|search)\\s+)(.+)$", RegexOption.IGNORE_CASE).find(clean)
        if (openAndPlayMatch != null) {
            val query = openAndPlayMatch.groupValues[1].trim()
            if (query.isNotBlank()) {
                return ParsedCommandResult(JarvisCommand.PlayYouTube(query), 0.96f, "YOUTUBE_OPEN_AND_PLAY")
            }
        }

        // Explicit "play on youtube [query]" or "play in youtube [query]"
        val playOnYtMatch = Regex("$prefix(?:play\\s+(?:on|in|from)\\s+youtube\\s+)(.+)$", RegexOption.IGNORE_CASE).find(clean)
        if (playOnYtMatch != null) {
            val q = playOnYtMatch.groupValues[1]
                .replace(Regex("(?i)\\s+(song|video|gana)$"), "")
                .trim()
            if (q.isNotBlank()) {
                return ParsedCommandResult(JarvisCommand.PlayYouTube(q), 0.97f, "YOUTUBE_PLAY_ON")
            }
        }

        // Hinglish Suffix Play: "<query> (song|gana|video)? (play karo|chalao|laga do|chala do|play kardo|bajao)"
        val ytSuffixRegex = Regex("^(.+?)\\s+(?:song|gana|video|track)?\\s*(?:play\\s+karo|play\\s+kardo|chalao|laga\\s+do|chala\\s+do|bajao|baja\\s+do)$", RegexOption.IGNORE_CASE)
        val ytSuffixMatch = ytSuffixRegex.find(clean)
        if (ytSuffixMatch != null && !clean.startsWith("open ") && !clean.startsWith("launch ")) {
            var raw = ytSuffixMatch.groupValues[1].trim()
            raw = raw.replace(Regex("(?i)^(hey nova|nova|hey jarvis|jarvis|please|in|on|at|pe|par)\\s+"), "")
                .replace(Regex("(?i)^(youtube\\s+(?:pe|par|in|on)?)\\s*"), "")
                .replace(Regex("(?i)\\s+(ke\\s+gane|ka\\s+gana|song|video|gana)$"), "")
                .trim()
            if (raw.isNotBlank() && raw.length > 1 && !listOf("cricket", "match", "game", "football").contains(raw.lowercase(Locale.ROOT))) {
                return ParsedCommandResult(JarvisCommand.PlayYouTube(raw), 0.96f, "YOUTUBE_PLAY_HINGLISH_SUFFIX")
            }
        }

        // Favorite Song Shortcut ("play my favorite song", "mera favorite gana chalao")
        if (clean.matches(Regex("$prefix(?:play\\s+(?:my\\s+)?(?:favou?rite\\s+song|favou?rite|song)|(?:mera\\s+)?favou?rite\\s+(?:gana|song)\\s+(?:play\\s+karo|chalao|bajao|laga\\s+do))$")) ||
            clean == "play something i like" || clean == "play my song") {
            return ParsedCommandResult(JarvisCommand.PlayYouTube("my favorite song"), 0.98f, "YOUTUBE_PLAY_FAVORITE")
        }

        // Standard Play: "Play Believer", "Play Shape of You on YouTube", "youtube pe Believer chalao"
        val ytPlayRegex = Regex("$prefix(?:play|chalao|laga\\s+do|bajao)\\s+(?:a\\s+|the\\s+)?(?:song\\s+|video\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        val ytPlayMatch = ytPlayRegex.find(clean)
        val isCasualSpeech = clean.contains("game") || clean.contains("match") || clean.contains("store") ||
                clean.contains("cricket") || clean.contains("football") || clean.contains("with") ||
                clean.contains("talking") || clean.contains("friend") || clean.contains("cousin") ||
                clean.startsWith("open ") || clean.startsWith("launch ")

        if (ytPlayMatch != null && !isCasualSpeech) {
            var rawQuery = ytPlayMatch.groupValues[1].trim()
                .replace(Regex("[.,!?:;\"'\\s]+$"), "")
            rawQuery = rawQuery
                .replace(Regex("(?i)^(in|on|at)\\s+youtube\\s+"), "")
                .replace(Regex("(?i)\\s+(on|in|pe|par|from|or|and)\\s+youtube$"), "")
                .replace(Regex("(?i)\\s+(song|video|gana|track|again)$"), "")
                .replace(Regex("[.,!?:;\"'\\s]+$"), "")
                .trim()
            if (rawQuery.isNotBlank() && rawQuery.length > 1 && rawQuery != "youtube" && rawQuery != "song" && rawQuery != "music") {
                return ParsedCommandResult(JarvisCommand.PlayYouTube(rawQuery), 0.95f, "YOUTUBE_PLAY")
            }
        }

        // YouTube Search: "search on youtube [query]" or "search [query] on youtube" or "search [query] video"
        val ytSearchPattern1 = Regex("$prefix(?:search\\s+on\\s+youtube|search\\s+in\\s+youtube|search\\s+youtube\\s+for|youtube\\s+pe\\s+search\\s+karo)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        val ytSearchPattern2 = Regex("$prefix(?:search\\s+for|search)\\s+(.+?)\\s+(?:on|in)\\s+youtube$", RegexOption.IGNORE_CASE).find(clean)
        val ytSearchPattern3 = Regex("^(.+?)\\s+(?:youtube\\s+pe\\s+search\\s+karo|youtube\\s+search\\s+karo)$", RegexOption.IGNORE_CASE).find(clean)
        val ytSearchMatch = ytSearchPattern1 ?: ytSearchPattern2 ?: ytSearchPattern3
        if (ytSearchMatch != null) {
            val query = ytSearchMatch.groupValues[1].trim()
                .replace(Regex("(?i)\\s+(on\\s+youtube|in\\s+youtube|pe|par|search\\s+karo)$"), "")
                .trim()
            if (query.isNotBlank()) {
                return ParsedCommandResult(JarvisCommand.SearchYouTube(query), 0.96f, "YOUTUBE_SEARCH")
            }
        }

        // 10. Multi-Website and Web URL Navigation
        val cleanForDomain = clean
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\bdot\\s+(com|org|net|in|io|co|ai|edu|gov|app|dev|me)\\b"), ".$1")
            .replace("twenty first", "21st")
            .replace("twenty-first", "21st")

        val hasExplicitOpenVerb = cleanForDomain.startsWith("open ") ||
                cleanForDomain.startsWith("launch ") ||
                cleanForDomain.startsWith("go to ") ||
                cleanForDomain.startsWith("browse ") ||
                cleanForDomain.contains("website") ||
                cleanForDomain.contains("tab") ||
                cleanForDomain.contains("in chrome") ||
                cleanForDomain.contains("in browser")

        // Check for opening multiple websites: "open vercel.com and github.com", "open vercel, github and twitter"
        val multiUrls = DomainNormalizer.extractMultipleUrls(cleanForDomain)
        if (hasExplicitOpenVerb && multiUrls.size > 1) {
            val hasWebIntent = multiUrls.any { url ->
                cleanForDomain.contains(".com") || cleanForDomain.contains(".dev") ||
                cleanForDomain.contains(".org") || cleanForDomain.contains(".net") ||
                cleanForDomain.contains(".in") || cleanForDomain.contains(".io") ||
                cleanForDomain.contains("wersel") || cleanForDomain.contains("vercel") ||
                cleanForDomain.contains("github") || cleanForDomain.contains("chatgpt") ||
                cleanForDomain.contains("21st")
            }
            if (hasWebIntent) {
                return ParsedCommandResult(JarvisCommand.OpenWebUrls(multiUrls), 0.98f, "OPEN_MULTIPLE_URLS")
            }
        }

        // Single Domain / Web URL check (e.g. "open vercel.com", "open wersel.com", "open 21st.dev", "twenty first dot dev")
        val domainRegex = Regex(".*\\b([a-zA-Z0-9-]+\\.(?:com|org|net|in|io|co|ai|edu|gov|app|dev|me)(?:/[^\\s]*)?)\\b.*", RegexOption.IGNORE_CASE)
        val domainMatch = domainRegex.find(cleanForDomain)
        if (domainMatch != null && hasExplicitOpenVerb && !clean.contains("message") && !clean.contains("whatsapp")) {
            val rawDomain = domainMatch.groupValues[1].trim()
            val normalizedUrl = DomainNormalizer.normalizeToUrl(rawDomain)
            return ParsedCommandResult(JarvisCommand.OpenWebUrls(listOf(normalizedUrl)), 0.98f, "WEB_URL")
        }

        // Phonetic Domain / Spoken tech websites: "open wersel", "open varsel", "open vercel", "open 21st dev", "open website vercel", "open flipkart website"
        if (cleanForDomain.startsWith("open ") || cleanForDomain.startsWith("launch ") || cleanForDomain.startsWith("go to ") || cleanForDomain.startsWith("browse ")) {
            val target = cleanForDomain.replace(Regex("(?i)^(open|launch|go to|browse)\\s+(the\\s+)?"), "")
                .replace(Regex("(?i)^(the\\s+)?(website|site|web)\\s+"), "")
                .replace(Regex("(?i)\\s+(website|site|web|in browser|in chrome)$"), "")
                .trim()
            val isExplicitWeb = cleanForDomain.contains("website") || cleanForDomain.contains("site") ||
                    cleanForDomain.contains("in browser") || cleanForDomain.contains("in chrome") ||
                    DomainNormalizer.isKnownWebAlias(target)
            if (isExplicitWeb && target.isNotBlank()) {
                val normalizedUrl = DomainNormalizer.normalizeToUrl(target)
                return ParsedCommandResult(JarvisCommand.OpenWebUrls(listOf(normalizedUrl)), 0.97f, "WEB_KNOWN_ALIAS")
            }
        }

        // Web Search (Google in browser) - Must explicitly have search or google intent
        val searchWebRegex = Regex("$prefix(?:search\\s+on\\s+google|search\\s+the\\s+web\\s+for|search\\s+google\\s+for|search\\s+for|search|google)\\s+(.+?)(?:\\s+(?:on\\s+google|on\\s+the\\s+web|online|in\\s+browser))?$", RegexOption.IGNORE_CASE)
        val searchHinglish = Regex("^(.+?)\\s+(?:search\\s+karo|search\\s+kardo|google\\s+karo|google\\s+pe\\s+search\\s+karo)$", RegexOption.IGNORE_CASE)
        val searchWebMatch = searchWebRegex.find(clean) ?: searchHinglish.find(clean)
        if (searchWebMatch != null && !clean.contains("youtube")) {
            val query = searchWebMatch.groupValues[1]
                .replace(Regex("(?i)\\s*(on google|on the web|on the internet|online|on browser|pe search karo|search karo|please)$"), "")
                .trim()
            // Ignore casual speech like "search me", "search here"
            if (query.isNotBlank() && query.length > 1 && !listOf("me", "here", "it", "this", "that").contains(query.lowercase(Locale.ROOT))) {
                return ParsedCommandResult(JarvisCommand.SearchWeb(query), 0.95f, "WEB_SEARCH")
            }
        }

        // 10b. Go To Background / Standby
        if (clean.matches(Regex("$prefix(?:go\\s+to\\s+background|go\\s+background|go\\s+in\\s+background|go\\s+in\\s+the\\s+background|minimize|go\\s+to\\s+home\\s+screen|go\\s+home|background\\s+me\\s+jao|background\\s+mein\\s+jao|background\\s+ki\\s+vellu|background\\s+po|background\\s+ch\\s+jao|background\\s+la\\s+ja|background\\s+e\\s+jao|send\\s+to\\s+background|go\\s+to\\s+sleep|sleep\\s+(?:nova|jarvis)|enter\\s+standby|standby\\s+mode|so\\s+jao|paduko|sleep)$", RegexOption.IGNORE_CASE))) {
            return ParsedCommandResult(JarvisCommand.GoToBackground, 1.0f, "GO_TO_BACKGROUND")
        }

        // 11. Open Apps (Strict: Must start with open/launch, not casual words)
        val openAppRegex = Regex("$prefix(?:open|launch)\\s+(?:the\\s+)?([a-zA-Z0-9\\s]+)$", RegexOption.IGNORE_CASE)
        val openMatch = openAppRegex.find(clean)
        if (openMatch != null) {
            var app = openMatch.groupValues[1].trim()
            app = app.replace(Regex("(?i)\\s+(app|application|please|for me|now)$"), "").trim()
            val nonAppWords = listOf("door", "window", "gate", "bottle", "box", "bag", "something", "anything", "background", "tab", "website", "site")
            val isWebDomain = app.contains(".com") || app.contains(".dev") || app.contains(".org") ||
                    app.contains(".net") || app.contains(".io") || DomainNormalizer.isKnownWebAlias(app)
            if (app.isNotBlank() && !nonAppWords.contains(app.lowercase(Locale.ROOT)) && !isWebDomain) {
                return ParsedCommandResult(JarvisCommand.OpenApp(app), 0.95f, "OPEN_APP")
            }
        }

        return ParsedCommandResult(null, 0.0f)
    }

    private fun parseNumericLevel(token: String): Int? {
        val clean = token.lowercase(Locale.ROOT).trim()
        clean.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        return when (clean) {
            "zero", "mute", "min", "minimum", "lowest", "silence", "nil", "off" -> 0
            "ten" -> 10
            "twenty" -> 20
            "twenty five", "quarter" -> 25
            "thirty" -> 30
            "forty" -> 40
            "fifty", "half" -> 50
            "sixty" -> 60
            "seventy" -> 70
            "seventy five" -> 75
            "eighty" -> 80
            "ninety" -> 90
            "hundred", "full", "max", "maximum", "highest" -> 100
            else -> null
        }
    }

    private fun parseStorageCommand(raw: String, clean: String): ParsedCommandResult? {
        val hasCreateVerb = listOf(
            "create", "make", "new ", "write file", "generate file",
            "তৈরি", "বানাও", "বানান", "ক্রিয়েট", "ক্রিয়েট", "লিখ", "লেখো"
        ).any { clean.contains(it) }
        val hasRenameVerb = listOf("rename", "রিনেম", "নাম পরিবর্তন", "নাম বদল").any { clean.contains(it) }
        val hasDeleteVerb = listOf("delete", "remove", "erase", "ডিলিট", "মুছে", "মুছো").any { clean.contains(it) }
        val hasListVerb = listOf("list file", "show file", "find file", "search file", "list folder", "কি ফাইল", "কী ফাইল", "ফাইল দেখাও", "ফাইল খুঁজে", "ফোল্ডারে কি আছে").any { clean.contains(it) }
        val hasCheckVerb = listOf("exist", "check if", "আছে কিনা", "আছে কি", "চেক করো").any { clean.contains(it) }

        val mentionsFolder = clean.contains("folder") || clean.contains("directory") || clean.contains("ফোল্ডার")
        val explicitExtMatch = Regex("([\\w\\-.]+\\.(?:txt|json|html|htm|css|js|xml|md|csv|yaml|yml))", RegexOption.IGNORE_CASE).find(raw)
        val detectedType = detectRequestedFileType(clean, explicitExtMatch?.groupValues?.get(1))
        val mentionsFile = clean.contains("file") || clean.contains("ফাইল") || explicitExtMatch != null || detectedType != null

        // Never hijack phone settings or WhatsApp message deletion
        if (clean.contains("whatsapp") || clean.contains("message")) return null
        if (!mentionsFolder && !mentionsFile && !hasRenameVerb && !hasDeleteVerb && !hasListVerb && !hasCheckVerb) {
            return null
        }

        // 1. Rename file or folder
        if (hasRenameVerb && (mentionsFolder || mentionsFile || explicitExtMatch != null)) {
            val enRename = Regex(
                "(?i)(?:rename|change\\s+name\\s+of)\\s+(?:the\\s+)?(?:file|folder)?\\s*[\"']?([^\"']+?)[\"']?\\s+(?:in\\s+([\\w\\s-]+?)\\s+)?to\\s+[\"']?([^\"']+)[\"']?$"
            ).find(raw)
            val bnRename = Regex(
                "[\"']?([\\w\\-.\\s]+?)[\"']?\\s*(?:এর\\s+নাম\\s+পরিবর্তন\\s+করে|রিনেম\\s+করে|নাম\\s+বদলে)\\s+[\"']?([\\w\\-.\\s]+?)[\"']?\\s*(?:রাখো|করো|দাও|করুন)?$"
            ).find(raw)
            if (enRename != null) {
                val oldName = enRename.groupValues[1].trim()
                val folderHint = normalizeKnownFolderHint(enRename.groupValues[2].trim())
                val newName = enRename.groupValues[3].trim()
                if (oldName.isNotBlank() && newName.isNotBlank()) {
                    return ParsedCommandResult(JarvisCommand.RenameStorageItem(oldName, newName, folderHint), 0.96f, "SAF_RENAME")
                }
            } else if (bnRename != null) {
                val oldName = bnRename.groupValues[1].trim()
                val newName = bnRename.groupValues[2].trim()
                if (oldName.isNotBlank() && newName.isNotBlank()) {
                    return ParsedCommandResult(JarvisCommand.RenameStorageItem(oldName, newName, extractFolderHint(raw)), 0.96f, "SAF_RENAME_BN")
                }
            }
        }

        // 2. Delete file or folder (requires explicit confirmation in executor/coordinator)
        if (hasDeleteVerb && (mentionsFolder || mentionsFile || explicitExtMatch != null)) {
            val folderHint = extractFolderHint(raw)
            val targetName = explicitExtMatch?.groupValues?.get(1)
                ?: extractNamedTarget(raw, isFolder = mentionsFolder && !mentionsFile)
            if (!targetName.isNullOrBlank()) {
                return ParsedCommandResult(JarvisCommand.DeleteStorageItem(targetName, folderHint), 0.95f, "SAF_DELETE")
            }
        }

        // 3. Check existence
        if (hasCheckVerb && (mentionsFolder || mentionsFile || explicitExtMatch != null)) {
            val folderHint = extractFolderHint(raw)
            val targetName = explicitExtMatch?.groupValues?.get(1)
                ?: extractNamedTarget(raw, isFolder = mentionsFolder && !mentionsFile)
            if (!targetName.isNullOrBlank()) {
                return ParsedCommandResult(JarvisCommand.CheckFileExists(targetName, folderHint), 0.95f, "SAF_CHECK_EXISTS")
            }
        }

        // 4. List / Find files
        if (hasListVerb) {
            val folderHint = extractFolderHint(raw)
            val query = explicitExtMatch?.groupValues?.get(1).orEmpty()
            return ParsedCommandResult(JarvisCommand.ListStorageFiles(query, folderHint), 0.94f, "SAF_LIST")
        }

        // 5. Create Folder (must NOT trigger if user is creating a file inside a folder like "Download Folder-এর মধ্যে HTML File তৈরি করো")
        val isCreatingFolder = hasCreateVerb && mentionsFolder && !mentionsFile
        if (isCreatingFolder) {
            val folderHint = extractFolderHint(raw)
            val folderName = extractNamedTarget(raw, isFolder = true)
            if (!folderName.isNullOrBlank()) {
                return ParsedCommandResult(JarvisCommand.CreateFolder(folderName, folderHint), 0.97f, "SAF_CREATE_FOLDER")
            }
        }

        // 6. Create / Write File
        if (hasCreateVerb && mentionsFile) {
            val folderHint = extractFolderHint(raw)
            val typeExt = detectedType ?: "txt"
            val explicitFileName = explicitExtMatch?.groupValues?.get(1)
                ?: extractNamedTarget(raw, isFolder = false)
            val finalFileName = when {
                !explicitFileName.isNullOrBlank() && explicitFileName.contains('.') -> explicitFileName
                !explicitFileName.isNullOrBlank() -> "$explicitFileName.$typeExt"
                else -> defaultFileNameForType(typeExt)
            }
            val content = extractFileContent(raw, clean, finalFileName, typeExt)
            return ParsedCommandResult(
                JarvisCommand.CreateFile(
                    fileName = finalFileName,
                    content = content,
                    fileType = typeExt,
                    folderHint = folderHint
                ),
                0.96f,
                "SAF_CREATE_FILE"
            )
        }

        return null
    }

    private fun detectRequestedFileType(clean: String, explicitFileName: String?): String? {
        if (explicitFileName != null && explicitFileName.contains('.')) {
            return explicitFileName.substringAfterLast('.').lowercase(Locale.ROOT)
        }
        return when {
            clean.contains("json") -> "json"
            clean.contains("html") || clean.contains("ওয়েব পেজ") -> "html"
            clean.contains("css") || clean.contains("stylesheet") -> "css"
            clean.contains("javascript") || Regex("\\bjs\\b").containsMatchIn(clean) -> "js"
            clean.contains("text file") || clean.contains("txt") || clean.contains("টেক্সট ফাইল") -> "txt"
            else -> null
        }
    }

    private fun defaultFileNameForType(typeExt: String): String = when (typeExt.lowercase(Locale.ROOT)) {
        "json" -> "data.json"
        "html" -> "index.html"
        "css" -> "style.css"
        "js" -> "script.js"
        else -> "Notes.txt"
    }

    private fun extractFolderHint(raw: String): String {
        val bnInside = Regex(
            "(?i)([A-Za-z0-9_\\-]+)\\s*(?:Folder|ফোল্ডার|directory)?[-\\s]*(?:এর\\s*মধ্যে|এর\\s*ভিতরে|এতে|তে|এ)"
        ).find(raw)
        if (bnInside != null) {
            val candidate = bnInside.groupValues[1].trim()
            if (!candidate.equals("ফোনে", ignoreCase = true) && !candidate.equals("আমার", ignoreCase = true)) {
                return normalizeKnownFolderHint(candidate)
            }
        }

        val enInside = Regex(
            "(?i)\\b(?:in|inside|into|under|from)\\s+(?:the\\s+)?([A-Za-z0-9_\\-]+)(?:\\s+(?:folder|directory))?"
        ).find(raw)
        if (enInside != null) {
            val candidate = enInside.groupValues[1].trim()
            if (candidate.lowercase(Locale.ROOT) !in setOf("my", "phone", "storage", "device")) {
                return normalizeKnownFolderHint(candidate)
            }
        }

        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.contains("download") -> "Downloads"
            lower.contains("document") -> "Documents"
            lower.contains("picture") -> "Pictures"
            lower.contains("music") -> "Music"
            else -> ""
        }
    }

    private fun normalizeKnownFolderHint(raw: String): String {
        val lower = raw.trim().lowercase(Locale.ROOT)
        return when {
            lower.startsWith("download") -> "Downloads"
            lower.startsWith("document") -> "Documents"
            lower.startsWith("picture") -> "Pictures"
            lower.startsWith("music") -> "Music"
            else -> raw.trim()
        }
    }

    private fun extractNamedTarget(raw: String, isFolder: Boolean): String? {
        // Bengali pattern: "<Name> নামে"
        val bnNamed = Regex("([A-Za-z0-9_\\-\\.]+)\\s+নামে").find(raw)
        if (bnNamed != null) {
            return bnNamed.groupValues[1].trim()
        }

        // English: "named <Name>" / "called <Name>"
        val enNamed = Regex("(?i)(?:named|called)\\s+[\"']?([A-Za-z0-9_\\-\\.]+)[\"']?").find(raw)
        if (enNamed != null) {
            return enNamed.groupValues[1].trim()
        }

        // English: "folder <Name>" / "file <Name>" (avoid generic words)
        val kindWord = if (isFolder) "(?:folder|directory)" else "(?:file)"
        val enDirect = Regex("(?i)$kindWord\\s+[\"']?([A-Za-z0-9_\\-\\.]+)[\"']?").find(raw)
        if (enDirect != null) {
            val token = enDirect.groupValues[1].trim()
            if (token.lowercase(Locale.ROOT) !in setOf(
                    "in", "inside", "with", "named", "called", "for", "and", "to", "from", "exists", "exist"
                )
            ) {
                return token
            }
        }

        // Bengali fallback before delete/check/exist: "<Name> ফোল্ডার" / "<Name> ফাইল"
        val bnBeforeKind = Regex("([A-Za-z0-9_\\-\\.]+)\\s+(?:Folder|ফোল্ডার|File|ফাইল)", RegexOption.IGNORE_CASE).find(raw)
        if (bnBeforeKind != null) {
            val candidate = bnBeforeKind.groupValues[1].trim()
            if (candidate.lowercase(Locale.ROOT) !in setOf(
                    "download", "downloads", "documents", "document", "text", "html", "json", "css", "js", "javascript", "new"
                )
            ) {
                return candidate
            }
        }
        return null
    }

    private fun extractFileContent(raw: String, clean: String, fileName: String, typeExt: String): String {
        val enWithContent = Regex(
            "(?i)(?:with\\s+(?:content|text|code)|containing|writing|write)\\s+[\"']?(.+?)[\"']?$"
        ).find(raw)
        if (enWithContent != null) {
            val captured = enWithContent.groupValues[1].trim()
            if (captured.isNotBlank()) return captured
        }

        val bnWithContent = Regex(
            "(?:তার\\s+(?:ভিতরে|মধ্যে)|ভিতরে|মধ্যে)\\s+(.+?)\\s*(?:লেখো|লিখে\\s+দাও|লিখো|রাখো)$"
        ).find(raw)
        if (bnWithContent != null) {
            val snippet = bnWithContent.groupValues[1].trim()
            if (typeExt == "json" && (snippet.contains("name", true) || snippet.contains("version", true))) {
                return "{\n  \"name\": \"${fileName.substringBeforeLast('.')}\",\n  \"version\": \"1.0.0\"\n}\n"
            }
            if (snippet.isNotBlank()) return snippet
        }

        return ""
    }

    internal fun parseDevStudioCommand(raw: String, clean: String): ParsedCommandResult? {
        // 1. Close active project
        val wantsCloseProject = (clean.contains("project") || clean.contains("প্রজেক্ট")) &&
            (clean.contains("close") || clean.contains("বন্ধ") || clean.contains("stop"))
        if (wantsCloseProject) {
            return ParsedCommandResult(JarvisCommand.CloseDevProject, 0.96f, "DEV_STUDIO_CLOSE_PROJECT")
        }

        // 2. Analyze build errors
        val wantsAnalyzeError = (clean.contains("error") || clean.contains("এরর") || clean.contains("সমস্যা")) &&
            (clean.contains("build") || clean.contains("বিল্ড") || clean.contains("gradle") || clean.contains("analy") || clean.contains("বিশ্লেষণ"))
        if (wantsAnalyzeError) {
            return ParsedCommandResult(JarvisCommand.AnalyzeBuildErrors, 0.96f, "DEV_STUDIO_ANALYZE_ERROR")
        }

        // 3. Check Termux / Java / Gradle toolchain in Termux
        val mentionsTermuxCheck = (clean.contains("termux") && (
            clean.contains("check") || clean.contains("status") || clean.contains("version") ||
                clean.contains("java") || clean.contains("দেখ") || clean.contains("চেক") ||
                clean.contains("ভার্সন") || clean.contains("tool")
            )) || clean.contains("check gradle") || clean.contains("gradle version")
        if (mentionsTermuxCheck) {
            return ParsedCommandResult(JarvisCommand.CheckTermuxEnvironment, 0.96f, "DEV_STUDIO_TERMUX_CHECK")
        }

        // 4. Open Dev Studio directly
        val mentionsStudio = clean.contains("dev studio") ||
            clean.contains("development studio") ||
            clean.contains("ডেভ স্টুডিও") ||
            clean.contains("অ্যাপ স্টুডিও")
        val wantsOpenStudio = mentionsStudio && (clean.contains("open") || clean.contains("launch") || clean.contains("খোল") || clean.contains("ওপেন") || clean.contains("যাও"))
        if (wantsOpenStudio) {
            return ParsedCommandResult(JarvisCommand.OpenDevStudio(), 0.97f, "DEV_STUDIO_OPEN")
        }

        // 5. Run / Preview HTML Website
        val wantsWebPreview = (clean.contains("preview") || clean.contains("live server") || clean.contains("run") ||
            clean.contains("প্রিভিউ") || clean.contains("চালাও") || clean.contains("রান")) &&
            (clean.contains("website") || clean.contains("web app") || clean.contains("html") ||
                clean.contains("ওয়েবসাইট") || clean.contains("ওয়েবসাইট"))
        if (wantsWebPreview) {
            return ParsedCommandResult(JarvisCommand.OpenWebLivePreview, 0.96f, "DEV_STUDIO_PREVIEW")
        }

        // 6. Build active app / Generate APK (not generating a brand new app project from scratch)
        val targetsBuildExistingApp = (
            (clean.contains("আমার") || clean.contains("my ") || clean.contains("active") || clean.contains("this ") || clean.contains("project")) &&
                (clean.contains("apk") || clean.contains("app") || clean.contains("অ্যাপ") || clean.contains("project")) &&
                (clean.contains("build") || clean.contains("compile") || clean.contains("তৈরি") || clean.contains("বিল্ড") || clean.contains("বানাও"))
            ) || clean.matches(Regex("^(?:build|compile|generate)\\s+(?:the\\s+)?(?:app|apk)$"))
        if (targetsBuildExistingApp && !clean.contains("একটি ") && !clean.contains("create an ") && !clean.contains("create a ")) {
            return ParsedCommandResult(JarvisCommand.BuildActiveDevProject, 0.96f, "DEV_STUDIO_BUILD_APK")
        }

        // 7. Create / Generate New Android App or HTML Website
        val hasCreateAction = clean.contains("create") || clean.contains("build") || clean.contains("make") ||
            clean.contains("generate") || clean.contains("তৈরি") || clean.contains("বানাও") || clean.contains("বানিয়ে")
        if (hasCreateAction) {
            val targetsAndroidApp = clean.contains("android app") ||
                clean.contains("kotlin app") ||
                clean.contains("অ্যান্ড্রয়েড অ্যাপ") ||
                clean.contains("অ্যান্ড্রয়েড অ্যাপ") ||
                (clean.contains("apk") && !clean.contains("install"))
            if (targetsAndroidApp) {
                return ParsedCommandResult(
                    JarvisCommand.GenerateDevProject(prompt = raw, projectType = "ANDROID_KOTLIN"),
                    0.96f,
                    "DEV_STUDIO_CREATE_ANDROID"
                )
            }
            val targetsWebsite = clean.contains("website") ||
                clean.contains("web app") ||
                clean.contains("html site") ||
                clean.contains("html website") ||
                clean.contains("landing page") ||
                clean.contains("portfolio") ||
                clean.contains("ওয়েবসাইট") ||
                clean.contains("ওয়েবসাইট")
            if (targetsWebsite) {
                return ParsedCommandResult(
                    JarvisCommand.GenerateDevProject(prompt = raw, projectType = "HTML_WEB"),
                    0.96f,
                    "DEV_STUDIO_CREATE_WEB"
                )
            }
        }
        return null
    }

    internal fun parsePhase2Command(raw: String, clean: String): ParsedCommandResult? {
        // 1. Stop speaking / mute output right away
        if (clean.matches(Regex("^(?:stop\\s+(?:speaking|talking)|be\\s+quiet|shut\\s+up|hush|চুপ\\s+করো|কথা\\s+বন্ধ\\s+করো|বলা\\s+বন্ধ\\s+করো)$"))) {
            return ParsedCommandResult(JarvisCommand.StopSpeakingCommand, 0.99f, "PHASE2_STOP_SPEAKING")
        }

        // 2. Analyze current screen / camera vision
        val asksScreenVision = clean.contains("what is on my screen") ||
            clean.contains("what's on my screen") ||
            clean.contains("analyze screen") ||
            clean.contains("analyze this screen") ||
            clean.contains("analyse screen") ||
            clean.contains("read my screen") ||
            clean.contains("describe screen") ||
            clean.contains("describe my screen") ||
            clean.contains("স্ক্রিন দেখো") ||
            clean.contains("স্ক্রিনে কি আছে") ||
            clean.contains("স্ক্রিন বিশ্লেষণ")
        if (asksScreenVision) {
            return ParsedCommandResult(
                JarvisCommand.AnalyzeScreenVision(raw),
                0.97f,
                "PHASE2_ANALYZE_SCREEN"
            )
        }

        // 3. Open Vision Studio
        val wantsVisionStudio = (clean.contains("vision studio") || clean.contains("ai vision") || clean == "vision") &&
            (clean.contains("open") || clean.contains("launch") || clean.contains("start") || clean.contains("খোল") || clean.contains("ওপেন") || clean == "vision" || clean == "vision studio")
        if (wantsVisionStudio) {
            return ParsedCommandResult(JarvisCommand.OpenVisionStudio, 0.97f, "PHASE2_OPEN_VISION")
        }

        // 4. AI Research & Knowledge
        if (clean == "open research" || clean == "open research center" || clean == "research center" || clean == "open knowledge") {
            return ParsedCommandResult(JarvisCommand.OpenResearchCenter, 0.97f, "PHASE2_OPEN_RESEARCH")
        }
        val deepResearchMatch = Regex("^(?:deep\\s+research|fact\\s*check|r&d|research\\s+on|research\\s+about|research)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (deepResearchMatch != null) {
            val query = deepResearchMatch.groupValues[1].trim()
            if (query.isNotBlank()) {
                val mode = when {
                    clean.startsWith("deep research") -> "DEEP"
                    clean.startsWith("fact") -> "FACT_CHECK"
                    clean.startsWith("r&d") -> "TECHNICAL"
                    else -> "NORMAL"
                }
                return ParsedCommandResult(JarvisCommand.RunResearch(query, mode), 0.97f, "PHASE2_RUN_RESEARCH")
            }
        }
        if (clean.contains("গবেষণা") || clean.contains("রিসার্চ")) {
            val q = raw.replace(Regex("(?:ডিপ\\s+রিসার্চ|রিসার্চ\\s+করো|গবেষণা\\s+করো|নিয়ে|নিয়ে)"), "").trim()
            if (q.length > 2) {
                val mode = if (clean.contains("ডিপ")) "DEEP" else "NORMAL"
                return ParsedCommandResult(JarvisCommand.RunResearch(q, mode), 0.96f, "PHASE2_RUN_RESEARCH_BN")
            }
            return ParsedCommandResult(JarvisCommand.OpenResearchCenter, 0.95f, "PHASE2_OPEN_RESEARCH_BN")
        }

        // 5. Intelligent File Manager
        if (clean.contains("file manager") || clean.contains("intelligent file") || clean.contains("ফাইল ম্যানেজার")) {
            return ParsedCommandResult(JarvisCommand.OpenFileManager, 0.97f, "PHASE2_OPEN_FILE_MANAGER")
        }

        // 6. Floating Avatar / Camera Pill Controls
        val mentionsFloatingPill = clean.contains("floating avatar") ||
            clean.contains("camera pill") ||
            clean.contains("floating pill") ||
            clean.contains("ফ্লোটিং অ্যাভাটার")
        if (mentionsFloatingPill) {
            return when {
                clean.contains("hide") || clean.contains("off") || clean.contains("disable") || clean.contains("বন্ধ") || clean.contains("লুকাও") ->
                    ParsedCommandResult(JarvisCommand.ToggleFloatingAvatar(false), 0.97f, "PHASE2_HIDE_FLOATING_AVATAR")
                clean.contains("show") || clean.contains("on") || clean.contains("enable") || clean.contains("চালু") || clean.contains("দেখাও") ->
                    ParsedCommandResult(JarvisCommand.ToggleFloatingAvatar(true), 0.97f, "PHASE2_SHOW_FLOATING_AVATAR")
                else -> ParsedCommandResult(JarvisCommand.ToggleFloatingAvatar(null), 0.94f, "PHASE2_SETTINGS_FLOATING_AVATAR")
            }
        }

        // 7. Multi-Provider API Manager & Switch Provider
        if (clean.contains("api manager") || clean.contains("multi provider") || clean.contains("ai provider settings")) {
            return ParsedCommandResult(JarvisCommand.OpenApiManager, 0.97f, "PHASE2_OPEN_API_MANAGER")
        }
        val switchProviderMatch = Regex("^(?:switch|change|set)\\s+(?:ai\\s+)?provider\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(clean)
            ?: Regex("^use\\s+(openai|gpt|anthropic|claude|gemini)\\s*(?:provider|model)?$", RegexOption.IGNORE_CASE).find(clean)
        if (switchProviderMatch != null) {
            val target = switchProviderMatch.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ParsedCommandResult(JarvisCommand.SwitchAiProvider(target), 0.97f, "PHASE2_SWITCH_PROVIDER")
            }
        }

        return null
    }
}
