package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.device.ContactInfo
import com.example.viewmodel.ActionBadgeInfo
import com.example.viewmodel.AssistantState
import com.example.viewmodel.ChatMessage
import com.example.viewmodel.MessageSender
import com.example.viewmodel.RuhiViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuhiScreen(
    viewModel: RuhiViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val visualizerAmp by viewModel.visualizerAmplitude.collectAsStateWithLifecycle()
    val multipleContacts by viewModel.multipleContactsResolution.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf("") }
    var showInfoDialog by remember { mutableStateOf(false) }

    // Permission states
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasAudioPermission = perms[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
        hasContactsPermission = perms[Manifest.permission.READ_CONTACTS] ?: hasContactsPermission
    }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Gradient background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0C20),
                        Color(0xFF181130),
                        Color(0xFF090514)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top App Bar
            RuhiTopBar(
                state = state,
                onInfoClick = { showInfoDialog = true },
                onClearClick = { viewModel.clearChat() }
            )

            // Permissions reminder banner if missing permissions
            if (!hasAudioPermission || !hasContactsPermission) {
                PermissionsBanner(
                    hasAudio = hasAudioPermission,
                    hasContacts = hasContactsPermission,
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.CALL_PHONE
                            )
                        )
                    }
                )
            }

            // Quick Prompt Chips
            QuickPromptsRow(
                onPromptSelected = { prompt ->
                    viewModel.sendTextMessage(prompt)
                }
            )

            // Chat Messages / Transcript Area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onContactSelected = { contact ->
                            viewModel.selectDisambiguatedContact(contact)
                        }
                    )
                }

                // Disambiguation prompt if active
                if (multipleContacts != null) {
                    item {
                        DisambiguationCard(
                            contacts = multipleContacts!!,
                            onSelect = { viewModel.selectDisambiguatedContact(it) }
                        )
                    }
                }
            }

            // Center Visualizer & Interaction Bar
            VoiceInteractionSection(
                state = state,
                amplitude = visualizerAmp,
                hasAudioPermission = hasAudioPermission,
                onStartListening = {
                    if (hasAudioPermission) {
                        viewModel.startListening()
                    } else {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                },
                onStopListening = { viewModel.stopListeningAndProcess() },
                onInterrupt = { viewModel.interruptSpeech() }
            )

            // Text Input fallback bar
            TextInputSection(
                text = textInput,
                onTextChanged = { textInput = it },
                onSend = {
                    if (textInput.isNotBlank()) {
                        viewModel.sendTextMessage(textInput)
                        textInput = ""
                    }
                }
            )
        }
    }

    if (showInfoDialog) {
        RuhiInfoDialog(onDismiss = { showInfoDialog = false })
    }
}

@Composable
fun RuhiTopBar(
    state: AssistantState,
    onInfoClick: () -> Unit,
    onClearClick: () -> Unit
) {
    val statusText = when (state) {
        is AssistantState.Listening -> "Listening..."
        is AssistantState.Processing -> "Gemini Live thinking..."
        is AssistantState.ExecutingAction -> "Executing ${state.actionName}..."
        is AssistantState.Speaking -> "Speaking (Gemini Live)"
        is AssistantState.Error -> "Attention needed"
        else -> "Ready"
    }

    val statusColor by animateColorAsState(
        targetValue = when (state) {
            is AssistantState.Listening -> Color(0xFFFF5252)
            is AssistantState.Processing -> Color(0xFFFFD54F)
            is AssistantState.ExecutingAction -> Color(0xFF64B5F6)
            is AssistantState.Speaking -> Color(0xFF69F0AE)
            is AssistantState.Error -> Color(0xFFFF8A80)
            else -> Color(0xFFB388FF)
        },
        label = "statusColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFE040FB), Color(0xFF7C4DFF), Color(0xFF00E5FF))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Ruhi Icon",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Ruhi AI",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Surface(
                        color = Color(0x33B388FF),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Live Multilingual",
                            color = Color(0xFFD1C4E9),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onClearClick,
                modifier = Modifier.testTag("clear_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = "Clear Chat",
                    tint = Color(0xFFB0BEC5)
                )
            }
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.testTag("info_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Help & Info",
                    tint = Color(0xFFB0BEC5)
                )
            }
        }
    }
}

@Composable
fun PermissionsBanner(
    hasAudio: Boolean,
    hasContacts: Boolean,
    onRequestPermissions: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF26193E)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Grant Device Permissions",
                    color = Color(0xFFFFD54F),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = if (!hasAudio && !hasContacts) "Mic & Contacts required for voice actions"
                    else if (!hasAudio) "Microphone needed for voice commands"
                    else "Contacts needed to call by name ('Call Mom')",
                    color = Color(0xFFCFD8DC),
                    fontSize = 11.sp
                )
            }
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Allow", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun QuickPromptsRow(onPromptSelected: (String) -> Unit) {
    val prompts = listOf(
        "WhatsApp kholo" to "💬",
        "Call Mom" to "📞",
        "Mummy ko call karo" to "👵",
        "Open WhatsApp" to "📲",
        "Hindi mein baat karo" to "🇮🇳",
        "Hinglish mein baat karo" to "🗣️",
        "Open YouTube" to "▶️",
        "Open Instagram" to "📷",
        "Call 9876543210" to "📱",
        "Open Settings" to "⚙️",
        "Talk to me in English" to "🌐"
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(prompts) { (prompt, emoji) ->
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onPromptSelected(prompt) }
                    .testTag("prompt_${prompt.replace(" ", "_")}"),
                color = Color(0xFF211538),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3D2766))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = emoji, fontSize = 12.sp)
                    Text(
                        text = prompt,
                        color = Color(0xFFECEFF1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onContactSelected: (ContactInfo) -> Unit
) {
    val isUser = message.sender == MessageSender.USER
    val isSystem = message.sender == MessageSender.SYSTEM

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (message.actionBadge != null) {
            ActionBadgeCard(badge = message.actionBadge)
            Spacer(modifier = Modifier.height(4.dp))
        }

        Surface(
            color = when {
                isUser -> Color(0xFF6200EE)
                isSystem -> Color(0xFF1E2638)
                else -> Color(0xFF281A46)
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            border = if (!isUser && !isSystem) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4A3178)) else null,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (isSystem) Icons.Default.Bolt else Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (isSystem) Color(0xFF64B5F6) else Color(0xFFE040FB),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (isSystem) "System Action" else "Ruhi",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSystem) Color(0xFF90CAF9) else Color(0xFFCE93D8)
                        )
                    }
                }

                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun ActionBadgeCard(badge: ActionBadgeInfo) {
    Surface(
        color = if (badge.isSuccess) Color(0x3300C853) else Color(0x33D50000),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (badge.isSuccess) Color(0xFF00E676) else Color(0xFFFF5252)
        ),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when (badge.type) {
                    "whatsapp" -> Icons.Default.Chat
                    "call" -> Icons.Default.Phone
                    "app" -> Icons.Default.Apps
                    "url" -> Icons.Default.Language
                    else -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                tint = if (badge.isSuccess) Color(0xFF69F0AE) else Color(0xFFFF8A80),
                modifier = Modifier.size(16.dp)
            )
            Column {
                Text(
                    text = "${badge.title}: ${if (badge.isSuccess) "Executed" else "Failed"}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (badge.isSuccess) Color(0xFFB9F6CA) else Color(0xFFFFCDD2)
                )
            }
        }
    }
}

@Composable
fun DisambiguationCard(
    contacts: List<ContactInfo>,
    onSelect: (ContactInfo) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF26193E)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7C4DFF))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Contacts, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                Text(
                    text = "Multiple Contacts Found. Tap to Call:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            contacts.forEach { contact ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(contact) }
                        .testTag("contact_${contact.id}"),
                    color = Color(0xFF332052),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(contact.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(contact.phoneNumber, color = Color(0xFFB0BEC5), fontSize = 12.sp)
                        }
                        Icon(Icons.Default.Call, contentDescription = "Call", tint = Color(0xFF69F0AE), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceInteractionSection(
    state: AssistantState,
    amplitude: Float,
    hasAudioPermission: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onInterrupt: () -> Unit
) {
    val isListening = state is AssistantState.Listening
    val isSpeaking = state is AssistantState.Speaking

    // Pulse animation for outer glow ring
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening || isSpeaking) 1.25f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveOffset"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Glowing Orb Visualizer
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(130.dp)
        ) {
            // Outer dynamic reacting glow
            val dynamicScale = (1f + amplitude * 0.4f) * (if (isListening || isSpeaking) pulseScale else 1f)
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(dynamicScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (isListening) {
                                listOf(Color(0x80FF1744), Color(0x33FF5252), Color.Transparent)
                            } else if (isSpeaking) {
                                listOf(Color(0x8000E5FF), Color(0x3300B0FF), Color.Transparent)
                            } else {
                                listOf(Color(0x55E040FB), Color(0x227C4DFF), Color.Transparent)
                            }
                        )
                    )
            )

            // Middle ring
            Box(
                modifier = Modifier
                    .size(85.dp)
                    .scale(1f + amplitude * 0.2f)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFE040FB),
                                Color(0xFF7C4DFF),
                                Color(0xFF00E5FF),
                                Color(0xFFE040FB)
                            )
                        )
                    )
            )

            // Main Interactive Mic Button
            Surface(
                onClick = {
                    if (isSpeaking) {
                        onInterrupt()
                    } else if (isListening) {
                        onStopListening()
                    } else {
                        onStartListening()
                    }
                },
                modifier = Modifier
                    .size(72.dp)
                    .testTag("main_voice_orb_button"),
                shape = CircleShape,
                color = if (isListening) Color(0xFFFF1744) else Color(0xFF1E1035),
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    if (isListening) Color.White else Color(0xFFB388FF)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when {
                            isSpeaking -> Icons.Default.Stop
                            isListening -> Icons.Default.Mic
                            else -> Icons.Default.MicNone
                        },
                        contentDescription = "Voice Action",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // State prompt instruction
        Text(
            text = when {
                isListening -> "Listening... Tap orb when done"
                isSpeaking -> "Speaking... Tap to interrupt"
                state is AssistantState.Processing -> "Thinking..."
                state is AssistantState.ExecutingAction -> "Executing action..."
                else -> "Tap orb to speak with Ruhi"
            },
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun TextInputSection(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = Color(0xFF1F1434),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3F2768))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = onTextChanged,
                placeholder = {
                    Text(
                        "Type in Hindi, Hinglish, English...",
                        color = Color(0xFF78909C),
                        fontSize = 13.sp
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("text_input_field"),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 2
            )

            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("send_message_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) Color(0xFFE040FB) else Color(0xFF546E7A)
                )
            }
        }
    }
}

@Composable
fun RuhiInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFE040FB))
                Text("Ruhi AI Voice Assistant", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Ruhi understands and speaks naturally in multiple Indian and international languages with real-time Gemini Live audio.",
                    color = Color(0xFFECEFF1),
                    fontSize = 13.sp
                )
                HorizontalDivider(color = Color(0xFF424242))
                Text("Supported Voice Commands:", fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F), fontSize = 13.sp)
                Text("• 'WhatsApp kholo' / 'Open WhatsApp'", color = Color(0xFFCFD8DC), fontSize = 12.sp)
                Text("• 'Call Mom' / 'Mummy ko call karo'", color = Color(0xFFCFD8DC), fontSize = 12.sp)
                Text("• 'Call 9876543210'", color = Color(0xFFCFD8DC), fontSize = 12.sp)
                Text("• 'Open YouTube' / 'Open Instagram' / 'Open Settings'", color = Color(0xFFCFD8DC), fontSize = 12.sp)
                Text("• 'Hindi mein baat karo' / 'Hinglish mein bolo'", color = Color(0xFFCFD8DC), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = Color(0xFFE040FB), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF211438),
        shape = RoundedCornerShape(20.dp)
    )
}
