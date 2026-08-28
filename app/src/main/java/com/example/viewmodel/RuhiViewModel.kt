package com.example.viewmodel

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.data.api.GeminiApiClient
import com.example.data.model.*
import com.example.device.ActionResult
import com.example.device.ContactInfo
import com.example.device.DeviceActionBridge
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class AssistantState {
    object Idle : AssistantState()
    object Listening : AssistantState()
    object Processing : AssistantState()
    data class ExecutingAction(val actionName: String, val details: String) : AssistantState()
    data class Speaking(val text: String) : AssistantState()
    data class Error(val message: String) : AssistantState()
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actionBadge: ActionBadgeInfo? = null,
    val multipleContacts: List<ContactInfo>? = null
)

enum class MessageSender {
    USER,
    RUHI,
    SYSTEM
}

data class ActionBadgeInfo(
    val type: String, // "whatsapp", "call", "app", "url", "error", "success"
    val title: String,
    val description: String,
    val isSuccess: Boolean
)

class RuhiViewModel(application: Application) : AndroidViewModel(application) {

    private val actionBridge = DeviceActionBridge(application)
    val audioRecorder = AudioRecorder(application)
    val audioPlayer = AudioPlayer()

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _detectedLanguage = MutableStateFlow("Auto Detect (Multi-lingual)")
    val detectedLanguage = _detectedLanguage.asStateFlow()

    private val _multipleContactsResolution = MutableStateFlow<List<ContactInfo>?>(null)
    val multipleContactsResolution = _multipleContactsResolution.asStateFlow()

    // Amplitude stream combining mic input and playback output
    val visualizerAmplitude: StateFlow<Float> = combine(
        audioRecorder.amplitude,
        audioPlayer.playbackAmplitude,
        _state
    ) { recAmp, playAmp, curState ->
        when (curState) {
            is AssistantState.Listening -> recAmp
            is AssistantState.Speaking -> playAmp.coerceAtLeast(0.15f)
            is AssistantState.Processing -> 0.35f
            is AssistantState.ExecutingAction -> 0.5f
            else -> 0.05f
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.05f)

    // Conversation history kept for multi-turn context
    private val conversationHistory = mutableListOf<Content>()

    init {
        // Welcome message
        addMessage(
            ChatMessage(
                sender = MessageSender.RUHI,
                text = "Namaste! I am Ruhi (Arushi). I can talk with you in Hindi, English, Hinglish, Marathi, Tamil, Bengali, Telugu, and more. Try saying 'WhatsApp kholo', 'Call Mom', or 'Talk to me in Hindi'!"
            )
        )
    }

    private val systemInstruction = Content(
        role = "system",
        parts = listOf(
            Part(
                text = """
                    You are Ruhi (also known as Arushi), an intelligent, natural, warm, and highly capable multilingual AI voice assistant for Android.
                    
                    CRITICAL MULTILINGUAL CAPABILITIES:
                    - You understand and speak fluently in Hindi, English, Hinglish, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, and all other languages supported by Gemini.
                    - Automatically detect the language being spoken by the user.
                    - If user speaks in Hindi ("Hindi mein baat karo", "kya haal hai"), respond strictly in fluent Hindi.
                    - If user speaks in English ("Hello Arushi", "Talk to me in English"), respond in natural English.
                    - If user speaks in Hinglish ("Hinglish mein baat karo", "Mummy ko call lagao please"), respond naturally in warm conversational Hinglish.
                    - Automatically switch languages mid-conversation if the user switches languages.
                    - Keep your voice responses concise, conversational, and direct, suitable for real-time speech. Do not use asterisks, markdown, or bullet points in voice responses.
                    
                    APP CONTROL & FUNCTION CALLING:
                    - When user wants to perform an action (e.g. open WhatsApp, make a call, open YouTube, open Instagram, open Chrome, open settings, open a URL), you MUST trigger the corresponding tool function call:
                      1. openWhatsApp(): for opening WhatsApp ("WhatsApp kholo", "open WhatsApp", "WhatsApp open karo", "WhatsApp chalao").
                      2. callContact(contactName): for calling by name ("Mummy ko phone lagao", "Call Mom", "Call Rahul", "Rahul ko call karo").
                      3. makeCall(phoneNumber): for calling a specific number ("Call 9876543210").
                      4. openApp(appName): for opening installed apps like YouTube, Instagram, Chrome, Settings, Camera, Maps, Spotify ("Open YouTube", "YouTube kholo", "Open settings", "Instagram open karo").
                      5. openUrl(url): for opening web links.
                    - Never merely say "I am opening WhatsApp" without invoking the function.
                    - After executing a tool, acknowledge the result naturally in the conversation in the user's language.
                """.trimIndent()
            )
        )
    )

    private val tools = listOf(
        Tool(
            functionDeclarations = listOf(
                FunctionDeclaration(
                    name = "openWhatsApp",
                    description = "Opens the WhatsApp messenger application on the device.",
                    parameters = ParameterSchema(
                        type = "OBJECT",
                        properties = emptyMap()
                    )
                ),
                FunctionDeclaration(
                    name = "openApp",
                    description = "Opens an installed Android application such as YouTube, Instagram, Chrome, Settings, Camera, Maps, Spotify, etc.",
                    parameters = ParameterSchema(
                        type = "OBJECT",
                        properties = mapOf(
                            "appName" to SchemaProperty(
                                type = "STRING",
                                description = "The name of the application to open, e.g., 'YouTube', 'Instagram', 'Chrome', 'Settings', 'Camera', 'Spotify'."
                            )
                        ),
                        required = listOf("appName")
                    )
                ),
                FunctionDeclaration(
                    name = "openUrl",
                    description = "Opens a website URL in the device browser.",
                    parameters = ParameterSchema(
                        type = "OBJECT",
                        properties = mapOf(
                            "url" to SchemaProperty(
                                type = "STRING",
                                description = "The web address to open, e.g., 'https://google.com' or 'wikipedia.org'."
                            )
                        ),
                        required = listOf("url")
                    )
                ),
                FunctionDeclaration(
                    name = "makeCall",
                    description = "Initiates a phone call or opens the dialer with a specific phone number.",
                    parameters = ParameterSchema(
                        type = "OBJECT",
                        properties = mapOf(
                            "phoneNumber" to SchemaProperty(
                                type = "STRING",
                                description = "The numeric phone number to call, e.g. '9876543210' or '+919876543210'."
                            )
                        ),
                        required = listOf("phoneNumber")
                    )
                ),
                FunctionDeclaration(
                    name = "callContact",
                    description = "Searches the device contacts by name (e.g. 'Mom', 'Mummy', 'Rahul', 'Dad', 'Priya') and initiates a phone call.",
                    parameters = ParameterSchema(
                        type = "OBJECT",
                        properties = mapOf(
                            "contactName" to SchemaProperty(
                                type = "STRING",
                                description = "The name or relationship of the contact to call, e.g. 'Mom', 'Rahul', 'Mummy', 'Papa'."
                            )
                        ),
                        required = listOf("contactName")
                    )
                )
            )
        )
    )

    fun startListening() {
        // Stop any currently playing audio immediately to handle user interruptions
        audioPlayer.stop()
        _multipleContactsResolution.value = null

        val started = audioRecorder.startRecording()
        if (started) {
            _state.value = AssistantState.Listening
        } else {
            _state.value = AssistantState.Error("Microphone permission needed to record voice.")
        }
    }

    fun stopListeningAndProcess() {
        if (_state.value !is AssistantState.Listening) return

        val wavAudioBytes = audioRecorder.stopRecording()
        if (wavAudioBytes.size <= 44) {
            _state.value = AssistantState.Idle
            return
        }

        _state.value = AssistantState.Processing

        val base64Audio = Base64.encodeToString(wavAudioBytes, Base64.NO_WRAP)
        processAudioInput(base64Audio)
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        audioPlayer.stop()
        _multipleContactsResolution.value = null

        addMessage(ChatMessage(sender = MessageSender.USER, text = text))
        _state.value = AssistantState.Processing

        val userTurn = Content(
            role = "user",
            parts = listOf(Part(text = text))
        )
        conversationHistory.add(userTurn)

        sendGenerateRequest()
    }

    private fun processAudioInput(base64Audio: String) {
        addMessage(ChatMessage(sender = MessageSender.USER, text = "🎙️ [Voice message sent]"))

        val userTurn = Content(
            role = "user",
            parts = listOf(
                Part(
                    inlineData = InlineData(
                        mimeType = "audio/wav",
                        data = base64Audio
                    )
                )
            )
        )
        conversationHistory.add(userTurn)

        sendGenerateRequest()
    }

    private fun sendGenerateRequest() {
        viewModelScope.launch {
            try {
                val apiKey = GeminiApiClient.getApiKey()
                if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                    val errMsg = "Please configure your GEMINI_API_KEY in the Secrets panel."
                    _state.value = AssistantState.Error(errMsg)
                    addMessage(ChatMessage(sender = MessageSender.SYSTEM, text = errMsg))
                    return@launch
                }

                // Trim conversation history if it gets too large
                val historyToSend = if (conversationHistory.size > 12) {
                    conversationHistory.takeLast(10)
                } else {
                    conversationHistory
                }

                val request = GenerateContentRequest(
                    contents = historyToSend,
                    systemInstruction = systemInstruction,
                    tools = tools,
                    generationConfig = GenerationConfig(
                        temperature = 0.7f,
                        responseModalities = listOf("AUDIO", "TEXT"),
                        speechConfig = SpeechConfig(
                            voiceConfig = VoiceConfig(
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Aoede")
                            )
                        )
                    )
                )

                // Call Gemini 2.5 Flash Native Audio / Multimodal endpoint
                val response = GeminiApiClient.service.generateContent(
                    model = "gemini-2.5-flash",
                    apiKey = apiKey,
                    request = request
                )

                handleGeminiResponse(response)
            } catch (e: Exception) {
                val errorText = e.localizedMessage ?: "Connection error. Please try again."
                _state.value = AssistantState.Error(errorText)
                addMessage(ChatMessage(sender = MessageSender.SYSTEM, text = "Error: $errorText"))
            }
        }
    }

    private suspend fun handleGeminiResponse(response: GenerateContentResponse) {
        val candidate = response.candidates?.firstOrNull()
        val content = candidate?.content

        if (content == null) {
            _state.value = AssistantState.Idle
            return
        }

        // Add assistant's response to history
        conversationHistory.add(content)

        val parts = content.parts
        val functionCallPart = parts.firstOrNull { it.functionCall != null }

        if (functionCallPart?.functionCall != null) {
            // Gemini issued a function call!
            executeFunctionCall(functionCallPart.functionCall)
        } else {
            // Normal Voice / Text response
            var responseText = ""
            var audioData: InlineData? = null

            for (part in parts) {
                if (part.text != null) {
                    responseText += part.text + " "
                }
                if (part.inlineData != null) {
                    audioData = part.inlineData
                }
            }

            val finalCleanText = responseText.trim()
            if (finalCleanText.isNotEmpty()) {
                addMessage(ChatMessage(sender = MessageSender.RUHI, text = finalCleanText))
            }

            if (audioData != null) {
                _state.value = AssistantState.Speaking(finalCleanText.ifEmpty { "Speaking..." })
                audioPlayer.playAudioBase64(audioData.data, audioData.mimeType) {
                    _state.value = AssistantState.Idle
                }
            } else {
                _state.value = AssistantState.Idle
            }
        }
    }

    private fun executeFunctionCall(call: FunctionCall) {
        val functionName = call.name
        val args = call.args ?: emptyMap()

        _state.value = AssistantState.ExecutingAction(functionName, args.toString())

        var actionBadge: ActionBadgeInfo? = null
        var actionResultStatus = "success"
        var actionResultDetails = ""

        when (functionName) {
            "openWhatsApp" -> {
                val result = actionBridge.openWhatsApp()
                when (result) {
                    is ActionResult.Success -> {
                        actionBadge = ActionBadgeInfo("whatsapp", "WhatsApp", result.message, true)
                        actionResultStatus = "success"
                        actionResultDetails = result.message
                    }
                    is ActionResult.Failure -> {
                        actionBadge = ActionBadgeInfo("whatsapp", "WhatsApp", result.error, false)
                        actionResultStatus = "failed"
                        actionResultDetails = result.error
                    }
                    else -> {}
                }
            }

            "openApp" -> {
                val appName = args["appName"]?.toString() ?: "App"
                val result = actionBridge.openApp(appName)
                when (result) {
                    is ActionResult.Success -> {
                        actionBadge = ActionBadgeInfo("app", appName, result.message, true)
                        actionResultStatus = "success"
                        actionResultDetails = result.message
                    }
                    is ActionResult.Failure -> {
                        actionBadge = ActionBadgeInfo("app", appName, result.error, false)
                        actionResultStatus = "failed"
                        actionResultDetails = result.error
                    }
                    else -> {}
                }
            }

            "openUrl" -> {
                val url = args["url"]?.toString() ?: "https://google.com"
                val result = actionBridge.openUrl(url)
                when (result) {
                    is ActionResult.Success -> {
                        actionBadge = ActionBadgeInfo("url", "Browser", result.message, true)
                        actionResultStatus = "success"
                        actionResultDetails = result.message
                    }
                    is ActionResult.Failure -> {
                        actionBadge = ActionBadgeInfo("url", "Browser", result.error, false)
                        actionResultStatus = "failed"
                        actionResultDetails = result.error
                    }
                    else -> {}
                }
            }

            "makeCall" -> {
                val phoneNumber = args["phoneNumber"]?.toString() ?: ""
                val result = actionBridge.makeCall(phoneNumber)
                when (result) {
                    is ActionResult.Success -> {
                        actionBadge = ActionBadgeInfo("call", "Phone Call", result.message, true)
                        actionResultStatus = "success"
                        actionResultDetails = result.message
                    }
                    is ActionResult.Failure -> {
                        actionBadge = ActionBadgeInfo("call", "Phone Call", result.error, false)
                        actionResultStatus = "failed"
                        actionResultDetails = result.error
                    }
                    else -> {}
                }
            }

            "callContact" -> {
                val contactName = args["contactName"]?.toString() ?: ""
                val result = actionBridge.callContact(contactName)
                when (result) {
                    is ActionResult.Success -> {
                        actionBadge = ActionBadgeInfo("call", contactName, result.message, true)
                        actionResultStatus = "success"
                        actionResultDetails = result.message
                    }
                    is ActionResult.Failure -> {
                        actionBadge = ActionBadgeInfo("call", contactName, result.error, false)
                        actionResultStatus = "failed"
                        actionResultDetails = result.error
                    }
                    is ActionResult.MultipleMatches -> {
                        actionBadge = ActionBadgeInfo("call", contactName, result.message, true)
                        _multipleContactsResolution.value = result.contacts
                        actionResultStatus = "multiple_matches"
                        actionResultDetails = result.message
                    }
                }
            }

            else -> {
                actionResultStatus = "unknown_function"
                actionResultDetails = "Function $functionName is not recognized."
            }
        }

        // Add execution badge message to chat
        if (actionBadge != null) {
            addMessage(
                ChatMessage(
                    sender = MessageSender.SYSTEM,
                    text = actionBadge.description,
                    actionBadge = actionBadge,
                    multipleContacts = _multipleContactsResolution.value
                )
            )
        }

        // Send tool response back to Gemini to get natural speech response acknowledging the action
        sendToolResponse(functionName, actionResultStatus, actionResultDetails)
    }

    private fun sendToolResponse(functionName: String, status: String, details: String) {
        viewModelScope.launch {
            try {
                val apiKey = GeminiApiClient.getApiKey()

                val functionResponseTurn = Content(
                    role = "user",
                    parts = listOf(
                        Part(
                            functionResponse = FunctionResponse(
                                name = functionName,
                                response = mapOf(
                                    "status" to status,
                                    "details" to details
                                )
                            )
                        )
                    )
                )

                conversationHistory.add(functionResponseTurn)

                val request = GenerateContentRequest(
                    contents = conversationHistory,
                    systemInstruction = systemInstruction,
                    tools = tools,
                    generationConfig = GenerationConfig(
                        temperature = 0.7f,
                        responseModalities = listOf("AUDIO", "TEXT"),
                        speechConfig = SpeechConfig(
                            voiceConfig = VoiceConfig(
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Aoede")
                            )
                        )
                    )
                )

                val response = GeminiApiClient.service.generateContent(
                    model = "gemini-2.5-flash",
                    apiKey = apiKey,
                    request = request
                )

                handleGeminiResponse(response)
            } catch (e: Exception) {
                _state.value = AssistantState.Idle
            }
        }
    }

    fun selectDisambiguatedContact(contact: ContactInfo) {
        _multipleContactsResolution.value = null
        val result = actionBridge.makeCall(contact.phoneNumber)
        if (result is ActionResult.Success) {
            addMessage(
                ChatMessage(
                    sender = MessageSender.SYSTEM,
                    text = "Calling ${contact.name} (${contact.phoneNumber})",
                    actionBadge = ActionBadgeInfo("call", contact.name, result.message, true)
                )
            )
            // Follow up with voice confirmation
            sendTextMessage("I chose ${contact.name}")
        }
    }

    fun interruptSpeech() {
        audioPlayer.stop()
        if (_state.value is AssistantState.Speaking) {
            _state.value = AssistantState.Idle
        }
    }

    fun clearChat() {
        audioPlayer.stop()
        conversationHistory.clear()
        _messages.value = listOf(
            ChatMessage(
                sender = MessageSender.RUHI,
                text = "Namaste! Ready for your voice commands in Hindi, English, Hinglish, Marathi, and more."
            )
        )
        _state.value = AssistantState.Idle
    }

    private fun addMessage(msg: ChatMessage) {
        _messages.value = _messages.value + msg
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        audioRecorder.stopRecording()
    }
}
