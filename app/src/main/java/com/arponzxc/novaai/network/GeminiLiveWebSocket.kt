package com.arponzxc.novaai.network

import android.util.Base64
import android.util.Log
import com.arponzxc.novaai.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener,
    responseModalities: List<String> = listOf("AUDIO")
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String, isFatal: Boolean = false, errorCode: String? = null)
        fun onToolCall(callId: String, name: String, args: JSONObject)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        // Shared OkHttpClient across all sessions — reuses connection pool, DNS cache, and TLS sessions
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(60, TimeUnit.SECONDS)
            .build()

        fun sanitizeModalities(modalities: List<String>): List<String> {
            val cleaned = modalities.map { it.trim().uppercase() }
                .filter { it == "AUDIO" || it == "TEXT" }
                .distinct()
            return if (cleaned.isEmpty()) listOf("AUDIO") else cleaned
        }

        fun buildYouTubeToolDeclarations(): JSONArray = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "youtube_search_videos")
                put("description", "Searches YouTube Data API v3 for videos matching a query (in English, Bengali, or any language) and returns metadata including title, video ID, channel, duration, view count, and likes. Does NOT open the YouTube app.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Search query for YouTube videos.")
                        })
                        put("max_results", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional maximum number of videos to return (1-20, default 5).")
                        })
                        put("order", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional sort order: 'relevance', 'date', 'viewCount', or 'rating'.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            put(JSONObject().apply {
                put("name", "youtube_get_video_details")
                put("description", "Fetches detailed metadata and statistics for a specific YouTube video ID or watch/shorts/youtu.be URL (title, channel, description excerpt, duration, views, likes, comments). Note: returns metadata only, not video transcripts.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("video_id_or_url", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The YouTube video ID (e.g. 'dQw4w9WgXcQ') or full YouTube URL.")
                        })
                    })
                    put("required", JSONArray().put("video_id_or_url"))
                })
            })

            put(JSONObject().apply {
                put("name", "youtube_get_channel_details")
                put("description", "Looks up a YouTube channel by @handle, channel URL, channel ID, or channel name and returns channel info, description, subscriber count, total views, and video count.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("channel_query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Channel @handle, URL, channel ID (UC...), or channel name.")
                        })
                    })
                    put("required", JSONArray().put("channel_query"))
                })
            })

            put(JSONObject().apply {
                put("name", "youtube_get_channel_statistics")
                put("description", "Retrieves subscriber count, total channel views, and uploaded video count for a YouTube channel (@handle, URL, ID, or name).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("channel_query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Channel @handle, URL, channel ID, or channel name.")
                        })
                    })
                    put("required", JSONArray().put("channel_query"))
                })
            })

            put(JSONObject().apply {
                put("name", "youtube_get_popular_videos")
                put("description", "Fetches currently trending/popular YouTube videos for a region (default BD / Bangladesh, or US, IN, etc.) and optional category ID.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("region_code", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "ISO 3166-1 alpha-2 country code, e.g. 'BD', 'IN', 'US'. Defaults to 'BD'.")
                        })
                        put("category_id", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Optional YouTube video category ID, e.g. '10' for Music, '20' for Gaming, '28' for Tech.")
                        })
                        put("max_results", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional max videos (1-20, default 8).")
                        })
                    })
                })
            })

            put(JSONObject().apply {
                put("name", "youtube_search_channels")
                put("description", "Searches YouTube for creators/channels matching a query and returns matching channels with subscriber and view statistics.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Channel name or creator topic to search for.")
                        })
                        put("max_results", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional max channels (1-15, default 5).")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            put(JSONObject().apply {
                put("name", "youtube_get_category_videos")
                put("description", "Fetches popular/trending YouTube videos for a named category (English or Bengali like 'Music', 'Gaming', 'News', 'Tech', 'Sports', 'গান', 'খেলা', 'খবর', 'প্রযুক্তি') or numeric category ID.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("category", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Category name or ID, e.g. 'Music', 'Gaming', 'Science & Technology', 'News', 'Sports'.")
                        })
                        put("region_code", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "ISO country code, defaults to 'BD'.")
                        })
                        put("max_results", JSONObject().apply {
                            put("type", "INTEGER")
                            put("description", "Optional max videos (1-20, default 8).")
                        })
                    })
                    put("required", JSONArray().put("category"))
                })
            })
        }

        fun buildSetupPayload(
            model: String,
            voiceName: String,
            systemPrompt: String,
            responseModalities: List<String> = listOf("AUDIO")
        ): JSONObject {
            val sanitized = sanitizeModalities(responseModalities)
            return JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", model)
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                    put("generation_config", JSONObject().apply {
                        put("response_modalities", JSONArray().apply {
                            sanitized.forEach { put(it) }
                        })
                        if ("AUDIO" in sanitized) {
                            put("speech_config", JSONObject().apply {
                                put("voice_config", JSONObject().apply {
                                    put("prebuilt_voice_config", JSONObject().apply {
                                        put("voice_name", voiceName)
                                    })
                                })
                            })
                        }
                        put("temperature", 0.6)
                        if (model.contains("2.5")) {
                            put("thinking_config", JSONObject().apply {
                                put("thinking_budget", 0)
                            })
                        }
                    })
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("functionDeclarations", buildToolDeclarations())
                    }))
                })
            }
        }
        fun buildToolDeclarations(): JSONArray = JSONArray().apply {
            val ytTools = buildYouTubeToolDeclarations()
            for (i in 0 until ytTools.length()) {
                put(ytTools.getJSONObject(i))
            }
                        // 1. open_app
                        put(JSONObject().apply {
                            put("name", "open_app")
                            put("description", "Opens an app installed on the user's phone, e.g. YouTube, WhatsApp, Camera, Settings. If multiple instances exist, app_number specifies 1 or 2.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("app_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The name of the app to open (e.g. \"YouTube\", \"WhatsApp\", \"Instagram\").")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) when opening a dual/cloned app instance.")
                                    })
                                })
                                put("required", JSONArray().put("app_name"))
                            })
                        })

                        // 2. open_website
                        put(JSONObject().apply {
                            put("name", "open_website")
                            put("description", "Opens one or more websites/URLs in Google Chrome in separate tabs. Use whenever the user asks to open a website, visit a URL, or open multiple websites/URLs in different tabs (e.g. \"open github.com and names21st.dev in different tabs\", \"open website google.com\", \"open vercel.com\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("urls", JSONObject().apply {
                                        put("type", "ARRAY")
                                        put("description", "Array of website URLs or domain names to open, each in its own tab (e.g. [\"github.com\", \"names21st.dev\"]).")
                                        put("items", JSONObject().apply { put("type", "STRING") })
                                    })
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional string containing the website URL(s) or query.")
                                    })
                                })
                            })
                        })

                        // 3. search_in_chrome
                        put(JSONObject().apply {
                            put("name", "search_in_chrome")
                            put("description", "Opens Google Chrome and searches for a topic/question, or opens a website URL in a tab (e.g. \"search xyz in Chrome\", \"google xyz\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Query or topic to search in Google Chrome, or website URL.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 4. search_and_play_youtube
                        put(JSONObject().apply {
                            put("name", "search_and_play_youtube")
                            put("description", "Searches YouTube and IMMEDIATELY OPENS AND PLAYS the requested video or song. Use whenever the user directly commands you to PLAY a video or song (e.g. \"play Tum Hi Ho\", \"play Believer on YouTube\", \"play XYZ song or youtube\", \"YouTube pe Arijit Singh chalao\", \"Nova video play karo\"). CRITICAL RULE: If the user says 'play', ALWAYS use search_and_play_youtube, NEVER use search_youtube. STRICT PROHIBITION: NEVER use this tool when the user is casually conversing or humming without an explicit play command.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "What video/song to search for and immediately play, e.g. \"Tum Hi Ho Arijit Singh\".")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 5. search_youtube
                        put(JSONObject().apply {
                            put("name", "search_youtube")
                            put("description", "Opens YouTube and searches for a topic/query, displaying the SEARCH RESULTS SCREEN ONLY WITHOUT auto-playing or tapping any video. Use ONLY when the user explicitly asks to SEARCH on YouTube without asking to play (e.g. \"search this on YouTube\", \"YouTube pe search karo xyz\", \"search Python tutorial on YouTube\"). NEVER use this tool when the user says to play a video or song.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The query/topic to search on YouTube, e.g. \"Python tutorial\", \"latest news\".")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 6. youtube_accessibility_action
                        put(JSONObject().apply {
                            put("name", "youtube_accessibility_action")
                            put("description", "Performs an in-app YouTube action using accessibility: skipping an ad ('skip_ad'), liking the video ('like'), subscribing to the channel ('subscribe'), opening the channel ('open_channel'), or toggling fullscreen ('fullscreen').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("enum", JSONArray().apply {
                                            put("skip_ad"); put("like"); put("subscribe"); put("open_channel"); put("fullscreen"); put("click_first")
                                        })
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 7. media_playback_control
                        put(JSONObject().apply {
                            put("name", "media_playback_control")
                            put("description", "Controls whatever video/audio is currently playing (e.g. YouTube): play, pause, next, previous, stop.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("enum", JSONArray().apply {
                                            put("play"); put("pause"); put("next"); put("previous"); put("stop")
                                        })
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 8. send_whatsapp_message
                        put(JSONObject().apply {
                            put("name", "send_whatsapp_message")
                            put("description", "Opens WhatsApp and queues sending a text to a named contact (e.g. \"message Rahul that I will be late\"). The tool response distinguishes opening/queuing from a verified send; inspect the current chat before claiming the message was sent. If 2 WhatsApp apps exist and app_number is not specified, ask the user which one.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("recipient_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the contact as spoken by the user (e.g. \"Rahul\", \"Priya\", \"Dad\").")
                                    })
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The text message content to send to the recipient.")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                                    })
                                    put("confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if user already confirmed sending.")
                                    })
                                })
                                put("required", JSONArray().apply { put("recipient_name"); put("message") })
                            })
                        })

                        // 9. whatsapp_call
                        put(JSONObject().apply {
                            put("name", "whatsapp_call")
                            put("description", "Initiates a WhatsApp voice call or WhatsApp video call to a contact (e.g. \"WhatsApp call Mom\", \"WhatsApp video call Rahul\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("recipient_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the contact to call as spoken by the user.")
                                    })
                                    put("call_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "\"voice\" for voice call or \"video\" for video call.")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                                    })
                                    put("confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if user already confirmed calling.")
                                    })
                                })
                                put("required", JSONArray().apply { put("recipient_name"); put("call_type") })
                            })
                        })

                        // 10. call_contact
                        put(JSONObject().apply {
                            put("name", "call_contact")
                            put("description", "Places a standard phone call to a saved contact by spoken name, e.g. \"call mom\", \"call Rahul\", \"phone Dad\".")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("contact_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The contact's name as the user said it, e.g. \"mom\", \"Rahul\".")
                                    })
                                })
                                put("required", JSONArray().put("contact_name"))
                            })
                        })

                        // 11. set_volume
                        put(JSONObject().apply {
                            put("name", "set_volume")
                            put("description", "Adjusts media volume on device. Supports relative commands ('increase', 'decrease') or exact level ('set').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Action to perform: 'increase', 'decrease', or 'set'.")
                                    })
                                    put("percentage", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Target percentage level from 0 to 100 if specified.")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 12. set_brightness
                        put(JSONObject().apply {
                            put("name", "set_brightness")
                            put("description", "Adjusts screen brightness on device. Supports relative commands ('increase', 'decrease') or exact level ('set').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Action to perform: 'increase', 'decrease', or 'set'.")
                                    })
                                    put("percentage", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Target percentage level from 0 to 100 if specified.")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 13. builtin_chrome_search
                        put(JSONObject().apply {
                            put("name", "builtin_chrome_search")
                            put("description", "Performs invisible, background web research using NOVA AI's built-in Chrome engine. Use whenever the user asks a question, topic, real-time factual query, news, weather, or live information that you don't know off-hand.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Search query or topic to look up on the web.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 14. go_to_background
                        put(JSONObject().apply {
                            put("name", "go_to_background")
                            put("description", "Sends NOVA AI to the background, minimizes the app to the home screen, and enters standby wake-word listening. Use immediately whenever user says 'go to background', 'go background', 'minimize', 'close', 'background me jao', 'go to home screen', 'go to sleep', or 'enter standby'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 15. set_flashlight
                        put(JSONObject().apply {
                            put("name", "set_flashlight")
                            put("description", "Turns the phone's torch or flashlight on or off. ALWAYS call this tool unconditionally whenever the user commands to turn on, enable, turn off, or disable the torch/flashlight (e.g. \"turn on flash lite\", \"turn on flashlight\", \"turn off flashlight\", \"torch on\", \"torch off\", \"torch chalu karo\", \"torch band karo\"). NEVER claim it is already on or off without calling this tool.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("enabled", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to turn on the torch/flashlight, false to turn it off.")
                                    })
                                })
                                put("required", JSONArray().put("enabled"))
                            })
                        })

                        // 16. click_ui_element
                        put(JSONObject().apply {
                            put("name", "click_ui_element")
                            put("description", "Clicks or toggles a specific UI button, switch, link, icon, tab, or element. Prefer a unique current element_id or target_name. When Screen Share is active, normalized coordinates x/y (0-1000) may be used for a clearly visible unlabeled target; the tool checks the hit target and returns whether a screen change was verified. Never treat a dispatched gesture alone as proof of success.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("target_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Label, text, icon name, or description of the UI element to click (e.g., 'Generate', 'Arrow', 'Video', 'Search', 'Install').")
                                    })
                                    put("x", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized X coordinate (0-1000) of the visual center of the element on screen.")
                                    })
                                    put("y", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized Y coordinate (0-1000) of the visual center of the element on screen.")
                                    })
                                    put("element_id", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based element ID if known from current screen state summary.")
                                    })
                                    put("is_toggle", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if flipping a toggle switch or checkbox.")
                                    })
                                    put("description", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Additional visual context.")
                                    })
                                })
                                put("required", JSONArray().put("target_name"))
                            })
                        })

                        // 17. type_text
                        put(JSONObject().apply {
                            put("name", "type_text")
                            put("description", "Types text into a grounded input field. The result is successful only when the text is read back from the target; a clipboard paste or dispatched gesture alone is not proof. For Google Flow, use the named prompt field or, only with a confirmed live Screen Share frame, current normalized x/y coordinates of the visible prompt box. Never report text entered unless success=true and verified=true.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("text", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The exact text query or prompt to type.")
                                    })
                                    put("x", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized X coordinate (0-1000) of the text box center on screen.")
                                    })
                                    put("y", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized Y coordinate (0-1000) of the text box center on screen.")
                                    })
                                    put("target_input_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional name or hint of the input field (e.g., 'What do you want to create', 'Search').")
                                    })
                                    put("submit", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "Whether to submit or press enter after typing.")
                                    })
                                })
                                put("required", JSONArray().put("text"))
                            })
                        })

                        // 18. scroll_screen
                        put(JSONObject().apply {
                            put("name", "scroll_screen")
                            put("description", "Scrolls the active screen in a given direction to reveal elements that are offscreen. Supports gentle line-by-line scrolling for inspecting photos and lists.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("direction", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Direction to scroll: 'down', 'up', 'left', or 'right'.")
                                    })
                                    put("distance", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional scroll distance: 'small' (gentle line-by-line scroll, moves 1-2 rows at a time with zero fling, mandatory for browsing photos and documents), 'medium' (half screen), or 'large' (full page). Defaults to 'small'.")
                                    })
                                })
                                put("required", JSONArray().put("direction"))
                            })
                        })

                        // 19. press_key
                        put(JSONObject().apply {
                            put("name", "press_key")
                            put("description", "Executes system-level navigation gestures: 'back', 'home', or 'recents'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("key", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The system action: 'back', 'home', or 'recents'.")
                                    })
                                })
                                put("required", JSONArray().put("key"))
                            })
                        })

                        // 20. task_completed
                        put(JSONObject().apply {
                            put("name", "task_completed")
                            put("description", "Call this tool only after the user's requested task is visibly confirmed complete. Do not call it right after typing into a search bar; when searching for a chat or file, first open the matching result and verify the resulting screen.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("summary", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Brief summary of what was completed (e.g., 'Opened Bharat's chat on Telegram.').")
                                    })
                                })
                                put("required", JSONArray().put("summary"))
                            })
                        })

                        // 21. inspect_screen
                        put(JSONObject().apply {
                            put("name", "inspect_screen")
                            put("description", "Reads the foreground app, screen title, and accessible UI elements (text, buttons, tabs). This tool returns accessibility text, not a visual image. Only claim live visual access when Screen Share is confirmed active and a recent captured frame has been received. Call when you need current accessible labels or element IDs before interacting, or when the user asks about screen content. Do not use for normal conversation.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 22. create_folder (Android SAF)
                        put(JSONObject().apply {
                            put("name", "create_folder")
                            put("description", "Creates a new folder (or nested subfolder path) inside an authorized Storage Access Framework (SAF) folder such as Downloads, Documents, or a user-selected directory. Checks existence first. If folder access has not been granted yet, returns requires_permission=true and prompts the user via Android SAF picker.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("folder_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the folder to create, e.g. \"Projects\" or \"Projects/WebApp\".")
                                    })
                                    put("parent_folder", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional authorized parent folder name/hint such as \"Downloads\" or \"Documents\".")
                                    })
                                })
                                put("required", JSONArray().put("folder_name"))
                            })
                        })

                        // 23. create_file (Android SAF)
                        put(JSONObject().apply {
                            put("name", "create_file")
                            put("description", "Creates or writes a file (.txt, .json, .html, .css, .js, or custom extension) inside an authorized Android SAF folder. Checks if the file already exists first and asks for confirmation before overwriting unless overwrite_confirmed=true.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("file_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the file including extension, e.g. \"Notes.txt\", \"config.json\", \"index.html\", \"style.css\", \"app.js\".")
                                    })
                                    put("file_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional type: \"txt\", \"json\", \"html\", \"css\", \"js\", or \"custom\".")
                                    })
                                    put("content", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Text, JSON, HTML, CSS, or JavaScript code content to write into the file.")
                                    })
                                    put("folder_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional target authorized folder name/hint such as \"Downloads\", \"Documents\", or \"Projects\".")
                                    })
                                    put("overwrite_confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "Set to true ONLY after the user explicitly confirmed overwriting an existing file.")
                                    })
                                })
                                put("required", JSONArray().put("file_name"))
                            })
                        })

                        // 24. list_storage_files (Android SAF)
                        put(JSONObject().apply {
                            put("name", "list_storage_files")
                            put("description", "Lists or searches files and folders inside the user's SAF-authorized storage directories.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional filename or substring filter.")
                                    })
                                    put("folder_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional authorized folder name/hint to scope the listing.")
                                    })
                                })
                            })
                        })

                        // 25. check_file_exists (Android SAF)
                        put(JSONObject().apply {
                            put("name", "check_file_exists")
                            put("description", "Checks whether a file or folder exists inside an authorized SAF directory before creating or modifying it.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("item_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The file or folder name to check.")
                                    })
                                    put("folder_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional authorized folder name/hint.")
                                    })
                                })
                                put("required", JSONArray().put("item_name"))
                            })
                        })

                        // 26. rename_storage_item (Android SAF)
                        put(JSONObject().apply {
                            put("name", "rename_storage_item")
                            put("description", "Renames a file or folder inside an authorized SAF directory.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("current_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Existing name of the file or folder.")
                                    })
                                    put("new_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "New name to apply.")
                                    })
                                    put("folder_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional authorized folder name/hint.")
                                    })
                                })
                                put("required", JSONArray().put("current_name").put("new_name"))
                            })
                        })

                        // 27. delete_storage_item (Android SAF)
                        put(JSONObject().apply {
                            put("name", "delete_storage_item")
                            put("description", "Deletes a file or folder inside an authorized SAF directory ONLY after explicit user confirmation. Never set confirmed=true unless the user explicitly confirmed deletion.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("target_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Exact name of the file or folder to delete.")
                                    })
                                    put("folder_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional authorized folder name/hint.")
                                    })
                                    put("confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "Must be false until the user explicitly confirms deletion.")
                                    })
                                })
                                put("required", JSONArray().put("target_name"))
                            })
                        })

                        // 28. request_storage_permission (Android SAF)
                        put(JSONObject().apply {
                            put("name", "request_storage_permission")
                            put("description", "Prompts the user to authorize a folder through Android's native Storage Access Framework (SAF) picker when no suitable folder permission has been granted yet.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("reason", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Clear user-facing explanation of why folder access is needed.")
                                    })
                                    put("suggested_folder", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional suggested folder name like \"Downloads\" or \"Documents\".")
                                    })
                                })
                                put("required", JSONArray().put("reason"))
                            })
                        })

                        // 29. analyze_current_screen (Phase 2 Vision Engine)
                        put(JSONObject().apply {
                            put("name", "analyze_current_screen")
                            put("description", "Analyzes the current Android screen via live Screen Capture snapshot and/or Accessibility UI tree to answer what is on screen. If neither screen sharing nor accessibility is active, instructs the user to open Vision Studio.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("question", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "User question about what is currently visible on the screen.")
                                    })
                                })
                                put("required", JSONArray().put("question"))
                            })
                        })

                        // 30. deep_research_topic (Phase 2 Research & Knowledge Engine)
                        put(JSONObject().apply {
                            put("name", "deep_research_topic")
                            put("description", "Performs multi-source online research using real knowledge APIs (Wikipedia, DuckDuckGo, Wikidata) and, when the user has saved a free web-search API key in Settings (Tavily, Serper or Exa), live web search results too. Returns a synthesized answer with verifiable source citations.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Topic, question, or claim to research.")
                                    })
                                    put("mode", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional research depth: NORMAL, DEEP, QUICK_SUMMARY, FACT_CHECK, or TECHNICAL.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 31. open_nova_tool (Open Phase 2 Modules)
                        put(JSONObject().apply {
                            put("name", "open_nova_tool")
                            put("description", "Opens one of NOVA AI's built-in intelligence tools: 'vision' (Vision Studio), 'research' (Knowledge & Research), 'file_manager' (Intelligent File Manager), 'api_manager' (Multi-Provider API Manager), or 'floating_avatar' (Camera Pill settings).")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("tool_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target NOVA screen: 'vision', 'research', 'file_manager', 'api_manager', or 'floating_avatar'.")
                                    })
                                })
                                put("required", JSONArray().put("tool_name"))
                            })
                        })

                        // 32. switch_ai_provider (Multi-Provider API Manager)
                        put(JSONObject().apply {
                            put("name", "switch_ai_provider")
                            put("description", "Switches NOVA AI's active REST text and vision AI provider to a configured provider (e.g. 'gemini', 'openai', 'anthropic', or custom provider name).")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("provider_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Provider ID or name to switch to.")
                                    })
                                })
                                put("required", JSONArray().put("provider_name"))
                            })
                        })
        }
    }

    private val client = sharedClient

    private var activeModel: String = when (model) {
        GeminiConstants.MODEL_3_8_LIVE -> GeminiConstants.MODEL_3_8_LIVE
        GeminiConstants.MODEL_3_1_FLASH_AGENT -> GeminiConstants.MODEL_3_1_FLASH
        else -> if (model.isNotBlank() && !model.contains("2.5")) model else GeminiConstants.DEFAULT_MODEL
    }

    @Volatile
    private var requestedResponseModalities: List<String> = sanitizeModalities(responseModalities)
    @Volatile
    var didFallbackToAudioForText: Boolean = false
        private set

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null
    var promptProvider: (() -> String)? = null

    fun currentResponseModalities(): List<String> = requestedResponseModalities

    /**
     * Updates response_modalities safely. Reconnects WebSocket cleanly when active if modalities changed.
     */
    fun updateResponseModalities(modalities: List<String>, reconnectIfConnected: Boolean = true): Boolean {
        val next = sanitizeModalities(modalities)
        if (next == requestedResponseModalities) return false
        requestedResponseModalities = next
        didFallbackToAudioForText = false
        if (reconnectIfConnected && !isManuallyStopped.get() && coroutineScope != null) {
            Log.i(TAG, "Reconfiguring Gemini Live response_modalities -> $next")
            isSetupComplete.set(false)
            isConnected.set(false)
            stopTimers()
            try {
                webSocket?.close(1000, "Modality reconfiguration")
            } catch (_: Exception) {}
            webSocket = null
            scheduleReconnect()
        }
        return true
    }

    fun isSessionReady(): Boolean = isConnected.get() && isSetupComplete.get()

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Gemini API Key is empty. Aborting connection.")
            listener.onError("Gemini API Key is missing. Please configure it in Settings.", isFatal = true, errorCode = "API_KEY_MISSING")
            listener.onConnectionStateChanged("OFFLINE")
            return
        }

        listener.onConnectionStateChanged("CONNECTING...")

        try {
            val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to Gemini Live. Sending BidiSetup...")
                    isConnected.set(true)
                    isSetupComplete.set(false)
                    listener.onConnectionStateChanged("CONFIGURING...")
                    // Send setup message FIRST before enabling audio streaming
                    sendSetupMessage(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = reason.contains("API_KEY_INVALID", ignoreCase = true) ||
                                       reason.contains("API key not valid", ignoreCase = true) ||
                                       reason.contains("API key", ignoreCase = true) ||
                                       reason.contains("key invalid", ignoreCase = true)
                    if (isApiKeyError) {
                        Log.e(TAG, "Gemini rejected session due to invalid API key (code=$code, reason=$reason)")
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    val isQuotaError = reason.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                                       reason.contains("quota", ignoreCase = true)
                    if (isQuotaError) {
                        Log.w(TAG, "Gemini rate-limited or connection collision (code=$code, reason=$reason). Retrying in cooldown...")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    if (code == 1008) {
                        val isTextModalityUnsupported = "AUDIO" !in requestedResponseModalities &&
                            !didFallbackToAudioForText &&
                            (reason.contains("modality", ignoreCase = true) ||
                                reason.contains("modalities", ignoreCase = true) ||
                                reason.contains("TEXT", ignoreCase = false) ||
                                reason.contains("AUDIO", ignoreCase = false) ||
                                reason.contains("invalid argument", ignoreCase = true) ||
                                reason.contains("not supported", ignoreCase = true))
                        if (isTextModalityUnsupported) {
                            Log.w(TAG, "Server rejected TEXT-only modality ($reason); falling back safely to AUDIO modality with local audio suppression.")
                            didFallbackToAudioForText = true
                            requestedResponseModalities = listOf("AUDIO")
                            listener.onConnectionStateChanged("RECONNECTING...")
                            scheduleReconnect()
                            return
                        }
                        val isModelError = reason.contains("BidiGenerateContent", ignoreCase = true) ||
                                           reason.contains("not found", ignoreCase = true) ||
                                           reason.contains("not supported", ignoreCase = true)
                        if (isModelError) {
                            if (activeModel != GeminiConstants.MODEL_3_8_LIVE) {
                                Log.w(TAG, "Model $activeModel rejected by Gemini ($reason). Auto-falling back to ${GeminiConstants.MODEL_3_8_LIVE}")
                                activeModel = GeminiConstants.MODEL_3_8_LIVE
                                listener.onConnectionStateChanged("RECONNECTING...")
                                scheduleReconnect()
                                return
                            }
                        }
                        listener.onError("Gemini setup was rejected: $reason", isFatal = true, errorCode = "SETUP_REJECTED")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    val responseBody = try { response?.body?.string() } catch (_: Exception) { null }
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: code=$code, errorMsg=$errorMsg, body=$responseBody", t)
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = code == 400 || code == 401 || code == 403 ||
                            errorMsg.contains("400") || errorMsg.contains("403") || errorMsg.contains("401") ||
                            errorMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                            errorMsg.contains("API key not valid", ignoreCase = true) ||
                            (responseBody?.contains("API_KEY_INVALID", ignoreCase = true) == true) ||
                            (responseBody?.contains("API key not valid", ignoreCase = true) == true)

                    val isQuotaError = code == 429 ||
                            errorMsg.contains("429") ||
                            errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                            errorMsg.contains("quota", ignoreCase = true) ||
                            (responseBody?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true)

                    if (isApiKeyError) {
                        Log.e(TAG, "Fatal failure: Invalid API Key (HTTP $code: $errorMsg)")
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    if (isQuotaError) {
                        Log.w(TAG, "Quota / rate limit hit (HTTP $code: $errorMsg). Retrying in cooldown...")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: $errorMsg. Retrying...", isFatal = false, errorCode = "CONNECTION_ERROR")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}", isFatal = true, errorCode = "CONNECTION_FAILED")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val effectivePrompt = promptProvider?.invoke()?.takeIf { it.isNotBlank() } ?: systemPrompt
        val setup = buildSetupPayload(
            model = activeModel,
            voiceName = voiceName,
            systemPrompt = effectivePrompt,
            responseModalities = requestedResponseModalities
        )
        val json = setup.toString()
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live (modalities=$requestedResponseModalities)")
        ws.send(json)
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // Check if server returned an error payload
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message", "") ?: json.optString("error", "Unknown error")
                val errCode = errObj?.optInt("code", 0) ?: 0
                Log.e(TAG, "Server returned error: code=$errCode, message=$errMsg")
                val isApiKey = errMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                               errMsg.contains("API key not valid", ignoreCase = true) ||
                               errCode == 400 || errCode == 403
                val isQuota = errMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                              errMsg.contains("quota", ignoreCase = true) ||
                              errCode == 429
                if (isApiKey) {
                    listener.onError("Invalid API Key: $errMsg", isFatal = true, errorCode = "API_KEY_INVALID")
                    listener.onConnectionStateChanged("ERROR • INVALID KEY")
                } else if (isQuota) {
                    listener.onError("API Quota Exceeded: $errMsg", isFatal = true, errorCode = "QUOTA_EXCEEDED")
                    listener.onConnectionStateChanged("ERROR • QUOTA EXCEEDED")
                } else {
                    listener.onError("Gemini Error ($errCode): $errMsg", isFatal = false)
                }
                return
            }

            // 0. Server setupComplete handshake acknowledgment
            if (json.has("setupComplete")) {
                Log.i(TAG, "Server acknowledged setup: setupComplete received. Session is now LIVE!")
                if (!isSetupComplete.getAndSet(true)) {
                    listener.onConnectionStateChanged("LIVE")
                    startSessionRenewal()
                }
                return
            }

            // Ensure session is marked ready if server sends content before explicit setupComplete
            if (!isSetupComplete.get() && (json.has("serverContent") || json.has("toolCall"))) {
                isSetupComplete.set(true)
                listener.onConnectionStateChanged("LIVE")
                startSessionRenewal()
            }

            // 1. Top-level toolCall
            val topToolCall = json.optJSONObject("toolCall")
            if (topToolCall != null) {
                val functionCalls = topToolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val name = call.optString("name", "")
                        val id = call.optString("id", "")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        if (name.isNotEmpty()) {
                            Log.i(TAG, "Gemini invoked toolCall: $name (id=$id)")
                            listener.onToolCall(id, name, args)
                        }
                    }
                }
                return
            }

            // 2. serverContent
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                val scToolCall = serverContent.optJSONObject("toolCall")
                if (scToolCall != null) {
                    val functionCalls = scToolCall.optJSONArray("functionCalls")
                    if (functionCalls != null) {
                        for (i in 0 until functionCalls.length()) {
                            val call = functionCalls.getJSONObject(i)
                            val name = call.optString("name", "")
                            val id = call.optString("id", "")
                            val args = call.optJSONObject("args") ?: JSONObject()
                            if (name.isNotEmpty()) {
                                Log.i(TAG, "Gemini invoked toolCall (serverContent): $name (id=$id)")
                                listener.onToolCall(id, name, args)
                            }
                        }
                    }
                }

                val inputTrans = serverContent.optJSONObject("inputTranscription")
                if (inputTrans != null) {
                    var userText = inputTrans.optString("text", "").trim()
                    if (userText.isEmpty()) {
                        val parts = inputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            userText = sb.toString().trim()
                        }
                    }
                    if (userText.isNotBlank()) {
                        listener.onUserTextReceived(userText)
                    }
                }

                var hasOutputTranscriptionText = false
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    var outText = outputTrans.optString("text", "")
                    if (outText.isEmpty()) {
                        val parts = outputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            outText = sb.toString()
                        }
                    }
                    if (outText.isNotEmpty()) {
                        hasOutputTranscriptionText = true
                        listener.onAssistantTextReceived(outText)
                    }
                }

                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // Filter out internal thinking/reasoning parts (Gemini 2.5 thinking tokens)
                            val isThought = part.optBoolean("thought", false)
                            if (isThought) {
                                Log.d(TAG, "Suppressed model thought part: ${part.optString("text").take(40)}")
                                continue
                            }
                            val text = part.optString("text", "")
                            val isThinkingLeak = text.startsWith("**") && (
                                    text.contains("thinking", ignoreCase = true) ||
                                    text.contains("crafting", ignoreCase = true) ||
                                    text.contains("formulating", ignoreCase = true) ||
                                    text.contains("acknowledge", ignoreCase = true) ||
                                    text.contains("inquire", ignoreCase = true) ||
                                    text.contains("greeting", ignoreCase = true)
                            )
                            if (text.isNotEmpty() && !isThinkingLeak && !hasOutputTranscriptionText) {
                                listener.onAssistantTextReceived(text)
                            }

                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.length > 8) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    if (pcmBytes.size >= 16) {
                                        val validLen = pcmBytes.size and 1.inv()
                                        val aligned = if (validLen == pcmBytes.size) pcmBytes else pcmBytes.copyOf(validLen)
                                        listener.onAudioDataReceived(aligned)
                                    }
                                }
                            }
                        }
                    }
                }

                val isTurnDone = serverContent.optBoolean("turnComplete", false) ||
                                 serverContent.optBoolean("generationComplete", false) ||
                                 serverContent.optBoolean("endOfTurn", false)
                if (isTurnDone) {
                    Log.d(TAG, "Server signaled turnComplete/generationComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming server JSON: ${e.message}", e)
        }
    }

    fun sendToolResponse(callId: String, functionName: String, result: JSONObject) {
        try {
            val msg = JSONObject().apply {
                put("tool_response", JSONObject().apply {
                    put("function_responses", JSONArray().put(JSONObject().apply {
                        put("id", callId)
                        put("name", functionName)
                        put("response", result)
                    }))
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent tool_response for $functionName: $result")
        } catch (e: Exception) {
            Log.e(TAG, "sendToolResponse failed: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64Audio\"}}}"
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    private var mediaChunksSent = 0
    private val lastVideoFrameSentAt = AtomicLong(0L)

    /**
     * Streams real-time screen frames to Gemini Live (BidiGenerateContent).
     */
    fun sendMediaChunk(jpegBytes: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || jpegBytes.isEmpty()) return
        // Gemini Live accepts video as a continuous realtimeInput stream at up to 1 FPS.
        // Enforce the cap here so periodic and post-action frames share one rate limiter.
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastVideoFrameSentAt.get()
            if (now - previous < 1000L) return
            if (lastVideoFrameSentAt.compareAndSet(previous, now)) break
        }
        try {
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$base64Image\"}}}"
            val sent = webSocket?.send(json) ?: false
            mediaChunksSent++
            if (mediaChunksSent % 10 == 1) {
                Log.i(TAG, "Sent video frame #$mediaChunksSent to Gemini Live (sent=$sent, size=${jpegBytes.size / 1024} KB)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media chunk: ${e.message}")
        }
    }

    /**
     * In Gemini Live BidiGenerateContent, streaming audio via realtimeInput handles turn-taking
     * natively via server-side neural VAD. Sending empty client_content cancels active generation.
     */
    fun sendAudioStreamEnd() {
        // No-op: server-side VAD handles turn completion smoothly
    }

    /**
     * Sends a text prompt to Gemini Live using the client_content turn protocol.
     */
    fun sendClientContent(text: String) {
        if (!isConnected.get() || !isSetupComplete.get() || text.isBlank()) return
        try {
            val msg = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                    put("turn_complete", true)
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent client_content: $text")
        } catch (e: Exception) {
            Log.e(TAG, "sendClientContent failed: ${e.message}", e)
        }
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                Log.d(TAG, "9-minute session threshold reached. Seamlessly renewing session...")
                webSocket?.close(1000, "Session renewal")
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(500)
            if (!isManuallyStopped.get()) {
                Log.d(TAG, "Attempting auto-reconnect...")
                initiateConnection()
            }
        }
    }

    private fun stopTimers() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        stopTimers()
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected.set(false)

        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
