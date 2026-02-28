package com.krypt.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─── Contacts Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    viewModel: KryptViewModel,
    onOpenChat: (String) -> Unit,
    onOpenStatus: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<ContactEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf<ContactEntity?>(null) }
    var newUuid by remember { mutableStateOf("") }
    var newNick by remember { mutableStateOf("") }
    var editNick by remember { mutableStateOf("") }
    var showCopied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔐", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Krypt", color = KryptText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptBlack),
                actions = {
                    IconButton(onClick = onOpenStatus) {
                        Icon(Icons.Default.Circle, contentDescription = "Status", tint = KryptAccent)
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact", tint = KryptAccent)
                    }
                }
            )
        },
        containerColor = KryptBlack
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // My UUID card with copy button
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = KryptCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Your Krypt ID", color = KryptSubtext, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = uiState.myUuid,
                            color = KryptAccent,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Krypt ID", uiState.myUuid))
                        showCopied = true
                    }) {
                        Icon(
                            if (showCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy ID",
                            tint = if (showCopied) Color(0xFF00C853) else KryptAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            LaunchedEffect(showCopied) {
                if (showCopied) {
                    kotlinx.coroutines.delay(2000)
                    showCopied = false
                }
            }

            if (uiState.contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔒", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No contacts yet", color = KryptText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + to add someone", color = KryptSubtext, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn {
                    items(uiState.contacts) { contact ->
                        val unread = uiState.unreadCounts[contact.uuid] ?: 0
                        val preview = uiState.conversationPreviews[contact.uuid]
                        ContactRow(
                            contact = contact,
                            unreadCount = unread,
                            lastMessage = preview,
                            onClick = { onOpenChat(contact.uuid) },
                            onLongClick = { showEditDialog = contact }
                        )
                        HorizontalDivider(color = KryptCard.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    // Add contact dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newUuid = ""; newNick = "" },
            containerColor = KryptCard,
            title = { Text("Add Contact", color = KryptText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newUuid,
                        onValueChange = { newUuid = it },
                        label = { Text("Their Krypt ID (UUID)", color = KryptSubtext) },
                        colors = kryptTextFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newNick,
                        onValueChange = { newNick = it },
                        label = { Text("Nickname (optional)", color = KryptSubtext) },
                        colors = kryptTextFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newUuid.isNotBlank()) {
                            viewModel.addContact(newUuid.trim(), newNick.trim())
                            newUuid = ""; newNick = ""; showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KryptAccent)
                ) { Text("Add", color = KryptBlack, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newUuid = ""; newNick = "" }) {
                    Text("Cancel", color = KryptSubtext)
                }
            }
        )
    }

    // Edit/Delete contact dialog
    showEditDialog?.let { contact ->
        LaunchedEffect(contact) { editNick = contact.nickname }
        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            containerColor = KryptCard,
            title = { Text("Contact Options", color = KryptText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editNick,
                        onValueChange = { editNick = it },
                        label = { Text("Nickname", color = KryptSubtext) },
                        colors = kryptTextFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        contact.uuid,
                        color = KryptSubtext,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    // Delete chat button
                    OutlinedButton(
                        onClick = { viewModel.deleteChat(contact.uuid); showEditDialog = null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear Chat History")
                    }
                    // Delete contact button
                    OutlinedButton(
                        onClick = { showDeleteDialog = contact; showEditDialog = null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF1744))
                    ) {
                        Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete Contact")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.editContact(contact.uuid, editNick.trim())
                        showEditDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KryptAccent)
                ) { Text("Save", color = KryptBlack, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = null }) { Text("Cancel", color = KryptSubtext) }
            }
        )
    }

    // Confirm delete dialog
    showDeleteDialog?.let { contact ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = KryptCard,
            title = { Text("Delete Contact?", color = KryptText) },
            text = { Text("This will delete ${contact.nickname.ifBlank { "this contact" }} and all chat history. This cannot be undone.", color = KryptSubtext) },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteContact(contact.uuid); showDeleteDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                ) { Text("Delete", color = KryptText) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel", color = KryptSubtext) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    contact: ContactEntity,
    unreadCount: Int,
    lastMessage: MessageEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(KryptAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (contact.nickname.firstOrNull() ?: contact.uuid.first()).uppercaseChar().toString(),
                    color = KryptAccent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.nickname.ifBlank { contact.uuid.take(16) + "…" },
                    color = KryptText,
                    fontSize = 15.sp,
                    fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(Modifier.height(2.dp))
                if (lastMessage != null) {
                    val preview = when {
                        lastMessage.contentType == "image" -> "📷 Photo"
                        lastMessage.contentType == "file" -> "📎 File"
                        else -> lastMessage.content
                    }
                    Text(
                        text = if (lastMessage.isSent) "You: $preview" else preview,
                        color = if (unreadCount > 0) KryptAccent else KryptSubtext,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = if (contact.publicKey.isEmpty()) "⏳ Waiting for key…" else "Tap to chat",
                        color = KryptSubtext,
                        fontSize = 12.sp
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (lastMessage != null) {
                    Text(
                        text = formatTime(lastMessage.timestamp),
                        color = if (unreadCount > 0) KryptAccent else KryptSubtext,
                        fontSize = 11.sp
                    )
                }
                if (unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(KryptAccent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            color = KryptBlack,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ─── Chat Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: KryptViewModel,
    contactUuid: String,
    onStartCall: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var showMenuExpanded by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val contact = uiState.contacts.find { it.uuid == contactUuid }
    val displayName = contact?.nickname?.ifBlank { contactUuid.take(12) } ?: contactUuid.take(12)
    val keyReady = contact?.publicKey?.isNotEmpty() == true

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.sendFile(contactUuid, it) } }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showCamera = true }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    if (showCamera) {
        CameraScreen(
            onPhotoTaken = { uri -> showCamera = false; viewModel.sendFile(contactUuid, uri) },
            onDismiss = { showCamera = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                                .background(KryptAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.first().uppercaseChar().toString(),
                                color = KryptAccent,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(displayName, color = KryptText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("🔒 End-to-End Encrypted", color = KryptAccent, fontSize = 10.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeConversation(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = KryptText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptDark),
                actions = {
                    IconButton(onClick = onStartCall) {
                        Icon(Icons.Default.VideoCall, contentDescription = "Call", tint = KryptAccent)
                    }
                    Box {
                        IconButton(onClick = { showMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = KryptText)
                        }
                        DropdownMenu(
                            expanded = showMenuExpanded,
                            onDismissRequest = { showMenuExpanded = false },
                            modifier = Modifier.background(KryptCard) 
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear Chat", color = KryptText) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = Color(0xFFFF5252)) },
                                onClick = { showDeleteChatDialog = true; showMenuExpanded = false }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (!keyReady) {
                    Box(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A00)).padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⏳ Waiting for encryption key… Ask them to open the app.", color = Color(0xFFFFCC00), fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier
                        .background(KryptDark)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                            showCamera = true
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = KryptSubtext)
                    }
                    IconButton(onClick = { fileLauncher.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = KryptSubtext)
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message…", color = KryptSubtext) },
                        modifier = Modifier.weight(1f),
                        colors = kryptTextFieldColors(),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = keyReady,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank() && keyReady) {
                                viewModel.sendTextMessage(contactUuid, inputText.trim())
                                inputText = ""
                            }
                        })
                    )
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && keyReady) {
                                viewModel.sendTextMessage(contactUuid, inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = keyReady
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send",
                            tint = if (keyReady && inputText.isNotBlank()) KryptAccent else KryptSubtext)
                    }
                }
            }
        },
        containerColor = KryptBlack
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(uiState.messages, key = { it.id }) { msg ->
                MessageBubble(
                    message = msg,
                    myUuid = uiState.myUuid,
                    onLongClick = { selectedMessage = msg }
                )
            }
        }
    }

    // Long-press message options
    selectedMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            containerColor = KryptCard,
            title = { Text("Message Options", color = KryptText) },
            text = {
                Column {
                    if (msg.contentType == "text") {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Message", msg.content))
                            selectedMessage = null
                        }) {
                            Icon(Icons.Default.ContentCopy, null, tint = KryptAccent)
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Text", color = KryptText)
                        }
                    }
                    TextButton(onClick = {
                        viewModel.deleteMessage(msg.id)
                        selectedMessage = null
                    }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFFF5252))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete Message", color = Color(0xFFFF5252))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessage = null }) { Text("Close", color = KryptSubtext) }
            }
        )
    }

    // Delete chat confirmation
    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            containerColor = KryptCard,
            title = { Text("Clear Chat?", color = KryptText) },
            text = { Text("All messages with $displayName will be permanently deleted.", color = KryptSubtext) },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteChat(contactUuid); showDeleteChatDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) { Text("Clear", color = KryptText) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatDialog = false }) { Text("Cancel", color = KryptSubtext) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageEntity, myUuid: String, onLongClick: () -> Unit) {
    val isMine = message.fromUuid == myUuid
    val bubbleColor = if (isMine) KryptAccent.copy(alpha = 0.18f) else KryptCard
    val textColor = if (isMine) KryptAccent else KryptText
    val alignment = if (isMine) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isMine) 18.dp else 4.dp,
                    bottomEnd = if (isMine) 4.dp else 18.dp
                ))
                .background(bubbleColor)
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .widthIn(max = 280.dp)
        ) {
            Column {
                when (message.contentType) {
                    "image" -> {
                        if (message.filePath != null) {
                            AsyncImage(
                                model = File(message.filePath),
                                contentDescription = "Image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Image, null, tint = textColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(message.content, color = textColor, fontSize = 14.sp)
                            }
                        }
                    }
                    "file" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape)
                                    .background(KryptAccent.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.InsertDriveFile, null, tint = KryptAccent, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(message.content.removePrefix("[sent: ").removePrefix("[received: ").removeSuffix("]"),
                                color = textColor, fontSize = 13.sp)
                        }
                    }
                    else -> {
                        Text(text = message.content, color = textColor, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = KryptSubtext,
                        fontSize = 10.sp
                    )
                    if (isMine) {
                        Spacer(Modifier.width(4.dp))
                        MessageStatusIcon(message)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageStatusIcon(message: MessageEntity) {
    when {
        message.isRead -> {
            // Double tick cyan = read
            Text("✓✓", color = KryptAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        message.isDelivered -> {
            // Double tick grey = delivered
            Text("✓✓", color = KryptSubtext, fontSize = 11.sp)
        }
        else -> {
            // Single tick = sent
            Text("✓", color = KryptSubtext, fontSize = 11.sp)
        }
    }
}

// ─── CameraX Screen ───────────────────────────────────────────────────────────

@Composable
fun CameraScreen(onPhotoTaken: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) {
            onDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(KryptBlack)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = KryptText, modifier = Modifier.size(32.dp))
            }
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(KryptText),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = {
                        val outputFile = File(context.getExternalFilesDir(null), "krypt_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                        imageCapture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    onPhotoTaken(Uri.fromFile(outputFile))
                                }
                                override fun onError(exception: ImageCaptureException) { onDismiss() }
                            }
                        )
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxSize()
                ) {}
            }
        }
    }
}

// ─── Status Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: KryptViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var statusText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status", color = KryptText, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = KryptText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptDark)
            )
        },
        containerColor = KryptBlack
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = statusText,
                onValueChange = { statusText = it },
                label = { Text("Post a status (expires in 24h)", color = KryptSubtext) },
                modifier = Modifier.fillMaxWidth(),
                colors = kryptTextFieldColors(),
                maxLines = 4
            )
            Button(
                onClick = {
                    if (statusText.isNotBlank()) { viewModel.postStatus(statusText.trim()); statusText = "" }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = KryptAccent)
            ) { Text("Post Status", color = KryptBlack, fontWeight = FontWeight.Bold) }

            Text("Active Statuses", color = KryptSubtext, fontSize = 12.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.statuses) { status ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = KryptCard),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(status.fromUuid.take(16) + "…", color = KryptSubtext, fontSize = 10.sp)
                            Text(status.content, color = KryptText, fontSize = 14.sp)
                            Text("Expires: ${formatTime(status.expiresAt)}", color = KryptSubtext, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
fun kryptTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = KryptText,
    unfocusedTextColor = KryptText,
    focusedBorderColor = KryptAccent,
    unfocusedBorderColor = KryptCard,
    cursorColor = KryptAccent,
    focusedContainerColor = KryptDark,
    unfocusedContainerColor = KryptDark
)

fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
    }
}
