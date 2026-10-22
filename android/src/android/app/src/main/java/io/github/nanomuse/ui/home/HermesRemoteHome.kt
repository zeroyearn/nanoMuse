package io.github.nanomuse.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.openminis.app.R
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.theme.ChatColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.nanomuse.feed.FeedPost
import io.github.nanomuse.ideas.Idea
import io.github.nanomuse.remote.HermesConfig
import io.github.nanomuse.remote.HermesConnectionState
import io.github.nanomuse.remote.HermesRemoteMode
import io.github.nanomuse.remote.bot.MainBotRepository
import io.github.nanomuse.remote.chat.ActiveApproval
import io.github.nanomuse.remote.chat.MainSessionRepository
import io.github.nanomuse.remote.chat.MessageRole
import io.github.nanomuse.remote.chat.RemoteMessage
import io.github.nanomuse.remote.net.HermesGatewayClient
import io.github.nanomuse.remote.proactive.HermesMuseRepository
import io.github.nanomuse.remote.proactive.RemoteGoal
import io.github.nanomuse.ui.avatar.AgentAvatar
import io.github.nanomuse.ui.avatar.AgentMood
import io.github.nanomuse.ui.avatar.rememberHermesAgentMood
import io.github.nanomuse.ui.feed.FeedTab
import io.github.nanomuse.ui.ideas.IdeasTab
import kotlinx.coroutines.launch

@Composable
fun HermesRemoteHome(
    navController: NavHostController,
    onSwitchToLocal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val gatewayClient = remember { HermesGatewayClient(context) }
    val sessionRepo = remember { MainSessionRepository(context, gatewayClient) }
    val botRepo = remember { MainBotRepository(context) }
    val museRepo = remember { HermesMuseRepository(context) }

    var tab by rememberSaveable { mutableStateOf(HomeTab.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val connState by HermesRemoteMode.connectionState.collectAsState()
    val mainBotProfile by HermesRemoteMode.currentMainBot.collectAsState()
    val botInfo by botRepo.botInfo.collectAsState()

    val messages by sessionRepo.messages.collectAsState()
    val isStreaming by sessionRepo.isStreaming.collectAsState()
    val activeApproval by sessionRepo.activeApproval.collectAsState()

    val feedPosts by museRepo.feedPosts.collectAsState()
    val ideas by museRepo.ideas.collectAsState()
    val goals by museRepo.goals.collectAsState()

    val agentMood = rememberHermesAgentMood(
        isStreaming = isStreaming,
        isWaitingApproval = activeApproval != null,
        error = (connState as? HermesConnectionState.Error)?.message
    )

    // Initial connection & data sync
    LaunchedEffect(Unit) {
        gatewayClient.connect()
        botRepo.fetchMainBot()
        museRepo.fetchFeed()
        museRepo.fetchIdeas()
        museRepo.fetchGoals()
    }

    fun openDrawer() {
        focusManager.clearFocus(force = true)
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
    BackHandler(enabled = !drawerState.isOpen && tab != HomeTab.CHAT) { tab = HomeTab.CHAT }

    val hazeState = rememberHazeState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ChatColors.background,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            ) {
                HermesRemoteDrawer(
                    currentProfile = mainBotProfile,
                    connState = connState,
                    botInfo = botInfo,
                    onSwitchProfile = { target ->
                        scope.launch {
                            botRepo.switchMainBot(target, "Switched via Hermes Android App")
                        }
                    },
                    onReconnect = { gatewayClient.connect() },
                    onSwitchToLocal = onSwitchToLocal,
                    onClose = { closeDrawer() }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = ChatColors.background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                MuseBottomBar(
                    selected = tab,
                    hazeState = hazeState,
                    onSelect = { picked ->
                        focusManager.clearFocus(force = true)
                        tab = picked
                    }
                )
            }
        ) { padding ->
            val bottom = padding.calculateBottomPadding()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .consumeWindowInsets(PaddingValues(bottom = bottom))
            ) {
                val chatVisible = tab == HomeTab.CHAT
                val chatAlpha by animateFloatAsState(
                    targetValue = if (chatVisible) 1f else 0f,
                    animationSpec = tween(if (chatVisible) 120 else 220),
                    label = "chatAlpha"
                )

                // Main Chat surface
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .graphicsLayer { alpha = chatAlpha }
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Top Header
                        HermesRemoteHeader(
                            mainProfile = mainBotProfile,
                            mood = agentMood,
                            connState = connState,
                            onOpenDrawer = { openDrawer() }
                        )

                        // Message List & Composer
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            HermesChatBody(
                                messages = messages,
                                isStreaming = isStreaming,
                                activeApproval = activeApproval,
                                onSend = { text -> sessionRepo.sendMessage(text) },
                                onResolveApproval = { id, approved -> sessionRepo.resolveApproval(id, approved) },
                                bottomInset = bottom
                            )
                        }
                    }
                }

                // Non-chat Tabs
                AnimatedVisibility(
                    visible = !chatVisible,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(120)),
                    modifier = Modifier.fillMaxSize().zIndex(1f)
                ) {
                    Surface(
                        color = ChatColors.background,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                (fadeIn(tween(160)) togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false))
                            },
                            label = "remoteTab"
                        ) { page ->
                            val header: @Composable () -> Unit = {
                                HermesRemoteHeader(
                                    mainProfile = mainBotProfile,
                                    mood = agentMood,
                                    connState = connState,
                                    onOpenDrawer = { openDrawer() }
                                )
                            }
                            when (page) {
                                HomeTab.FEED -> {
                                    FeedTab(
                                        header = header,
                                        onDiscuss = { post ->
                                            tab = HomeTab.CHAT
                                            sessionRepo.sendMessage("Discussing feed: ${post.title}\n\n${post.body}")
                                        },
                                        onEditRoutine = {
                                            Toast.makeText(context, "Routine managed remotely via Hermes Workspace", Toast.LENGTH_SHORT).show()
                                        },
                                        bottomInset = bottom
                                    )
                                }
                                HomeTab.IDEAS -> {
                                    IdeasTab(
                                        header = header,
                                        onSendToChat = { ideaText ->
                                            tab = HomeTab.CHAT
                                            sessionRepo.sendMessage(ideaText)
                                        },
                                        onCreateRoutine = {
                                            tab = HomeTab.CHAT
                                            sessionRepo.sendMessage("Please create a routine for idea: ${it.title}")
                                        },
                                        onStartGoal = { category, seed ->
                                            tab = HomeTab.CHAT
                                            sessionRepo.sendMessage("Let's start a goal in category ${category.name}: ${seed ?: ""}")
                                        },
                                        bottomInset = bottom
                                    )
                                }
                                HomeTab.GOALS -> {
                                    HermesRemoteGoalsView(
                                        header = header,
                                        goals = goals,
                                        onOpenGoal = { goal ->
                                            tab = HomeTab.CHAT
                                            sessionRepo.sendMessage("Status review for goal: ${goal.title}")
                                        },
                                        bottomInset = bottom
                                    )
                                }
                                HomeTab.LIBRARY -> {
                                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                        Text("Workspace Intelligence & Docs\nManaged by Main Bot: $mainBotProfile", color = Color.Gray)
                                    }
                                }
                                HomeTab.CHAT -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HermesRemoteHeader(
    mainProfile: String,
    mood: AgentMood,
    connState: HermesConnectionState,
    onOpenDrawer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(8.dp))
        AgentAvatar(
            mood = mood,
            size = 36.dp,
            onClick = onOpenDrawer
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mainProfile,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text("👑", fontSize = 14.sp)
            }
            val statusText = when (connState) {
                is HermesConnectionState.Connected -> "Online · Hermes Gateway"
                is HermesConnectionState.Connecting -> "Connecting..."
                is HermesConnectionState.Error -> "Offline · ${connState.message}"
                is HermesConnectionState.Disconnected -> "Disconnected"
            }
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = when (connState) {
                    is HermesConnectionState.Connected -> Color(0xFF10B981)
                    is HermesConnectionState.Connecting -> Color(0xFFF59E0B)
                    else -> Color(0xFFEF4444)
                }
            )
        }
    }
}

@Composable
fun HermesChatBody(
    messages: List<RemoteMessage>,
    isStreaming: Boolean,
    activeApproval: ActiveApproval?,
    onSend: (String) -> Unit,
    onResolveApproval: (String, Boolean) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp
) {
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Connected to Hermes Main Bot. Send a message to start.", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                HermesMessageBubble(msg)
                Spacer(Modifier.height(10.dp))
            }

            activeApproval?.let { approval ->
                item(key = "approval_${approval.id}") {
                    HermesApprovalCard(
                        approval = approval,
                        onResolve = { approved -> onResolveApproval(approval.id, approved) }
                    )
                }
            }
        }

        // Composer
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomInset)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message Main Bot...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank()) {
                            val text = input.trim()
                            input = ""
                            onSend(text)
                        }
                    })
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            val text = input.trim()
                            input = ""
                            onSend(text)
                        }
                    },
                    enabled = input.isNotBlank() || isStreaming
                ) {
                    if (isStreaming) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HermesMessageBubble(msg: RemoteMessage) {
    val isUser = msg.role == MessageRole.USER
    val isTool = msg.role == MessageRole.TOOL

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        isUser -> MaterialTheme.colorScheme.primary
                        isTool -> Color(0xFFF1F5F9)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .width(androidx.compose.ui.unit.Dp.Unspecified)
        ) {
            if (isUser) {
                Text(msg.text, color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
            } else if (isTool) {
                Column {
                    Text("🔧 ${msg.toolName ?: "Tool"}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Text(msg.text, fontSize = 13.sp, color = Color.DarkGray)
                }
            } else {
                MarkdownText(
                    markdown = msg.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun HermesApprovalCard(
    approval: ActiveApproval,
    onResolve: (Boolean) -> Unit
) {
    Surface(
        color = Color(0xFFFEF3C7),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("⚠️ Action Approval Required", fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
            Spacer(Modifier.height(4.dp))
            Text(approval.action, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(approval.detail, fontSize = 13.sp, color = Color(0xFF78350F))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onResolve(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Deny")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onResolve(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Approve")
                }
            }
        }
    }
}

@Composable
fun HermesRemoteGoalsView(
    header: @Composable () -> Unit,
    goals: List<RemoteGoal>,
    onOpenGoal: (RemoteGoal) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MuseTones.canvas),
        contentPadding = PaddingValues(bottom = 28.dp + bottomInset)
    ) {
        item(key = "header") { header() }

        if (goals.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("No active goals from Hermes Muse.", color = Color.Gray)
                }
            }
        } else {
            items(goals, key = { it.id }) { goal ->
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onOpenGoal(goal) }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(goal.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text(goal.status, fontSize = 12.sp, color = Color(0xFF10B981))
                        }
                        goal.progress?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HermesRemoteDrawer(
    currentProfile: String,
    connState: HermesConnectionState,
    botInfo: io.github.nanomuse.remote.bot.MainBotInfo?,
    onSwitchProfile: (String) -> Unit,
    onReconnect: () -> Unit,
    onSwitchToLocal: () -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Hermes Remote Control", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Gateway Single Source of Truth", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Active Main Bot", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currentProfile, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text("👑", fontSize = 16.sp)
        }
        botInfo?.let {
            Text("Agent ID: ${it.agentId.take(16)}...", fontSize = 12.sp, color = Color.Gray)
            Text("CAS Gen: ${it.generation} | Muse: ${if (it.museInstalled) "Installed" else "No"}", fontSize = 12.sp, color = Color.Gray)
        }

        Spacer(Modifier.height(20.dp))
        Text("Quick Switch Main Bot", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        val candidateProfiles = listOf("yunying-ducu", "default", "yanshou", "toufang-qingbao")
        candidateProfiles.forEach { profile ->
            Button(
                onClick = { onSwitchProfile(profile); onClose() },
                enabled = profile != currentProfile,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (profile == currentProfile) Color.LightGray else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (profile == currentProfile) "$profile (Current 👑)" else "Switch to $profile")
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("Server & Gateway", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        val drawerContext = androidx.compose.ui.platform.LocalContext.current
        var currentCfg by remember { mutableStateOf(io.github.nanomuse.remote.HermesRemoteMode.getConfig(drawerContext)) }
        Text(currentCfg.gatewayUrl, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val newCfg = currentCfg.copy(gatewayUrl = io.github.nanomuse.remote.HermesConfig.DEFAULT_TAILSCALE_URL)
                    io.github.nanomuse.remote.HermesRemoteMode.saveConfig(drawerContext, newCfg)
                    currentCfg = newCfg
                    onReconnect()
                    Toast.makeText(drawerContext, "Switched to Tailscale URL", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text("Tailscale", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    val newCfg = currentCfg.copy(gatewayUrl = io.github.nanomuse.remote.HermesConfig.DEFAULT_PUBLIC_URL)
                    io.github.nanomuse.remote.HermesRemoteMode.saveConfig(drawerContext, newCfg)
                    currentCfg = newCfg
                    onReconnect()
                    Toast.makeText(drawerContext, "Switched to Public IPv4", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text("Public IPv4", fontSize = 11.sp)
            }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onReconnect) {
                Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
                Spacer(Modifier.width(4.dp))
                Text("Reconnect")
            }
            Button(
                onClick = onSwitchToLocal,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Local Mode")
            }
        }
    }
}
