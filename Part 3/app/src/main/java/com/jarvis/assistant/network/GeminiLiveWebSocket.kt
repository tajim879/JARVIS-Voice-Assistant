package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jarvis.assistant.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String)
        fun onToolCall(callId: String, name: String, args: JSONObject)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(GeminiConstants.KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            listener.onError("Gemini API Key is missing. Please configure it in Settings.")
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
                    Log.d(TAG, "WebSocket connected to Gemini Live")
                    // Send setup message FIRST before enabling audio streaming
                    sendSetupMessage(webSocket)
                    isConnected.set(true)
                    isSetupComplete.set(true)
                    listener.onConnectionStateChanged("LIVE")

                    // Start 9-minute session renewal
                    startSessionRenewal()
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
                    if (code == 1008) {
                        listener.onError("Gemini setup was rejected: $reason")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: $errorMsg", t)
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()
                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: $errorMsg. Retrying...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                    put("temperature", 0.35)
                })
                put("output_audio_transcription", JSONObject())
                put("input_audio_transcription", JSONObject())
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", JSONArray().apply {
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
                            put("description", "Searches YouTube and IMMEDIATELY OPENS AND PLAYS the requested video or song. Use whenever the user directly commands you to PLAY a video or song (e.g. \"play Tum Hi Ho\", \"play Believer on YouTube\", \"play XYZ song or youtube\", \"YouTube pe Arijit Singh chalao\", \"Jarvis video play karo\"). CRITICAL RULE: If the user says 'play', ALWAYS use search_and_play_youtube, NEVER use search_youtube. STRICT PROHIBITION: NEVER use this tool when the user is casually conversing or humming without an explicit play command.")
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
                            put("description", "Sends a WhatsApp message hands-free to a contact by name (e.g. \"message Rahul that I will be late\", \"WhatsApp Priya 'See you soon'\", \"send message to Dad: I reached home\"). If 2 WhatsApp apps exist and app_number is not specified, it asks the user 1 or 2.")
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
                            put("description", "Performs invisible, background web research using JARVIS's built-in Chrome engine. Use whenever the user asks a question, topic, real-time factual query, news, weather, or live information that you don't know off-hand.")
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
                            put("description", "Sends JARVIS to the background, minimizes the app to the home screen, and enters standby wake-word listening. Use immediately whenever user says 'go to background', 'go background', 'minimize', 'close', 'background me jao', 'go to home screen', 'go to sleep', or 'enter standby'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 15. set_flashlight
                        put(JSONObject().apply {
                            put("name", "set_flashlight")
                            put("description", "Turns the phone's torch or flashlight on or off. Use immediately whenever the user commands to turn on, enable, turn off, or disable the torch/flashlight (e.g. \"turn on torch\", \"turn off torch\", \"turn on flashlight\", \"turn off flashlight\", \"torch on\", \"torch off\", \"torch chalu karo\", \"torch band karo\").")
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
                    })
                }))
            })
        }
        val json = setup.toString()
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live with tools")
        ws.send(json)
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

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
                    return
                }

                val inputTrans = serverContent.optJSONObject("inputTranscription")
                if (inputTrans != null) {
                    val text = inputTrans.optString("text", "").trim()
                    if (text.isNotBlank()) {
                        listener.onUserTextReceived(text)
                    }
                }

                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    val text = outputTrans.optString("text", "").trim()
                    if (text.isNotBlank()) {
                        listener.onAssistantTextReceived(text)
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
                            val text = part.optString("text", "")
                            if (text.isNotBlank()) {
                                listener.onAssistantTextReceived(text)
                            }

                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.isNotBlank()) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    listener.onAudioDataReceived(pcmBytes)
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    Log.d(TAG, "Server signaled turnComplete")
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
            val realtimeInput = GeminiRealtimeInput(
                realtimeInput = RealtimeAudioInput(
                    audio = RealtimeAudioPayload(
                        mimeType = "audio/pcm;rate=16000",
                        data = base64Audio
                    )
                )
            )
            val json = gson.toJson(realtimeInput)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    /**
     * Hybrid VAD: the microphone stays open, but this tells Live API that the
     * current utterance ended.  It removes most of the wait after a question.
     */
    fun sendAudioStreamEnd() {
        if (!isConnected.get() || !isSetupComplete.get()) return
        try {
            val json = gson.toJson(
                GeminiRealtimeInput(
                    realtimeInput = RealtimeAudioInput(audioStreamEnd = true)
                )
            )
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio stream end: ${e.message}")
        }
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
                initiateConnection()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(3000)
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
