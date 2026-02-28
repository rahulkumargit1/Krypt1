package com.krypt.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

data class CallState(
    val isInCall: Boolean = false,
    val remoteUuid: String = "",
    val isIncoming: Boolean = false,
    val pendingOfferSdp: String = ""
)

data class UiState(
    val myUuid: String = "",
    val contacts: List<ContactEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val statuses: List<StatusEntity> = emptyList(),
    val conversationPreviews: Map<String, MessageEntity> = emptyMap(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val currentConversation: String = "",
    val isConnected: Boolean = false,
    val callState: CallState = CallState()
)

class KryptViewModel(private val context: Context) : ViewModel() {

    private val prefs: SharedPreferences = context.getSharedPreferences("krypt_prefs", Context.MODE_PRIVATE)
    private val db = KryptDatabase.getInstance(context)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var myUuid: String = ""
    private var myPublicKey: String = ""
    private var myPrivateKey: String = ""

    var webRTCManager: WebRTCManager? = null

    private val incomingChunks = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private val incomingChunkMeta = mutableMapOf<String, EncryptedFileChunk>()

    companion object {
        private const val NOTIF_CHANNEL_ID = "krypt_messages"
        private const val NOTIF_CHANNEL_NAME = "Krypt Messages"
    }

    init {
        createNotificationChannel()
        initializeIdentity()
        observeContacts()
        observeStatuses()
        observeConversationPreviews()
        observeIncomingMessages()
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Krypt encrypted message notifications"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun showMessageNotification(fromUuid: String, content: String, nickname: String) {
        // Don't show if we're already in that conversation
        if (_uiState.value.currentConversation == fromUuid) return

        val displayName = nickname.ifBlank { fromUuid.take(12) + "…" }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("🔒 $displayName")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(fromUuid.hashCode(), notification)
    }

    private fun showFileNotification(fromUuid: String, fileName: String, nickname: String) {
        if (_uiState.value.currentConversation == fromUuid) return
        val displayName = nickname.ifBlank { fromUuid.take(12) + "…" }
        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("📎 $displayName")
            .setContentText("Sent you a file: $fileName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(fromUuid.hashCode() + 1, notification)
    }

    // ─── Identity ─────────────────────────────────────────────────────────────

    private fun initializeIdentity() {
        myUuid = prefs.getString("uuid", null) ?: run {
            val newUuid = UUID.randomUUID().toString()
            prefs.edit().putString("uuid", newUuid).apply()
            newUuid
        }

        val storedPub = prefs.getString("public_key", null)
        val storedPriv = prefs.getString("private_key", null)

        if (storedPub != null && storedPriv != null) {
            myPublicKey = storedPub
            myPrivateKey = storedPriv
        } else {
            val (pub, priv) = CryptoEngine.generateRSAKeyPair()
            myPublicKey = pub
            myPrivateKey = priv
            prefs.edit().putString("public_key", pub).putString("private_key", priv).apply()
        }

        _uiState.update { it.copy(myUuid = myUuid) }
        NetworkClient.connect(myUuid, myPublicKey)
    }

    // ─── Contacts ─────────────────────────────────────────────────────────────

    private fun observeContacts() {
        viewModelScope.launch {
            db.contactDao().getAllContacts().collect { contacts ->
                _uiState.update { it.copy(contacts = contacts) }
                // observe unread counts for each contact
                contacts.forEach { contact ->
                    viewModelScope.launch {
                        db.messageDao().getUnreadCount(contact.uuid).collect { count ->
                            _uiState.update { state ->
                                state.copy(unreadCounts = state.unreadCounts + (contact.uuid to count))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeConversationPreviews() {
        viewModelScope.launch {
            db.messageDao().getConversationPreviews().collect { previews ->
                val map = previews.associateBy { it.conversationId }
                _uiState.update { it.copy(conversationPreviews = map) }
            }
        }
    }

    fun addContact(uuid: String, nickname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NetworkClient.requestPublicKey(uuid)
            val contact = ContactEntity(uuid = uuid, publicKey = "", nickname = nickname)
            db.contactDao().insertContact(contact)
        }
    }

    fun editContact(uuid: String, newNickname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.contactDao().updateNickname(uuid, newNickname)
        }
    }

    fun deleteContact(uuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.contactDao().deleteContact(uuid)
            db.messageDao().deleteConversation(uuid)
        }
    }

    // ─── Messages ─────────────────────────────────────────────────────────────

    fun openConversation(contactUuid: String) {
        _uiState.update { it.copy(currentConversation = contactUuid) }
        viewModelScope.launch {
            db.messageDao().getMessages(contactUuid).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        // Mark incoming messages as read and send read receipt
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().markIncomingRead(contactUuid)
            NetworkClient.sendReadReceipt(contactUuid)
        }
    }

    fun closeConversation() {
        _uiState.update { it.copy(currentConversation = "") }
    }

    fun sendTextMessage(to: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = db.contactDao().getContact(to) ?: return@launch
            if (contact.publicKey.isEmpty()) {
                // Request key again and retry
                NetworkClient.requestPublicKey(to)
                return@launch
            }
            try {
                val payload = CryptoEngine.encryptMessage(text, contact.publicKey)
                val sent = NetworkClient.sendEncryptedMessage(to, payload)
                val msg = MessageEntity(
                    conversationId = to,
                    fromUuid = myUuid,
                    content = text,
                    contentType = "text",
                    isSent = true,
                    isDelivered = false,
                    isRead = false
                )
                val msgId = db.messageDao().insertMessage(msg)
                // If successfully sent over network, we'll get delivery receipt back
                if (!sent) {
                    // Store as pending; will retry on reconnect
                }
            } catch (e: Exception) {
                // Encryption failed — maybe public key is corrupted, re-request
                NetworkClient.requestPublicKey(to)
            }
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().deleteMessage(messageId)
        }
    }

    fun deleteChat(contactUuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().deleteConversation(contactUuid)
        }
    }

    fun sendFile(to: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = db.contactDao().getContact(to) ?: return@launch
            if (contact.publicKey.isEmpty()) {
                NetworkClient.requestPublicKey(to)
                return@launch
            }
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = uri.lastPathSegment?.substringAfterLast("/")
                    ?: "file_${System.currentTimeMillis()}"
                val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@launch

                val chunks = CryptoEngine.encryptFileChunks(bytes, fileName, mimeType, contact.publicKey)
                chunks.forEach { chunk ->
                    NetworkClient.sendFileChunk(to, chunk)
                    Thread.sleep(120) // Increased delay to avoid WebSocket flooding
                }

                val contentType = if (mimeType.startsWith("image")) "image" else "file"
                val msg = MessageEntity(
                    conversationId = to,
                    fromUuid = myUuid,
                    content = "[sent: $fileName]",
                    contentType = contentType,
                    isSent = true
                )
                db.messageDao().insertMessage(msg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    private fun observeStatuses() {
        viewModelScope.launch {
            db.statusDao().getActiveStatuses().collect { statuses ->
                _uiState.update { it.copy(statuses = statuses) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                db.statusDao().deleteExpiredStatuses()
                Thread.sleep(60_000)
            }
        }
    }

    fun postStatus(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = CryptoEngine.encryptMessage(text, myPublicKey)
                NetworkClient.sendStatus(payload)
                val status = StatusEntity(fromUuid = myUuid, content = text)
                db.statusDao().insertStatus(status)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ─── Incoming Message Handler ──────────────────────────────────────────────

    private fun observeIncomingMessages() {
        viewModelScope.launch {
            NetworkClient.incomingMessages.collect { raw ->
                handleIncoming(raw)
            }
        }
    }

    private suspend fun handleIncoming(raw: String) = withContext(Dispatchers.IO) {
        val json = NetworkClient.parseMessage(raw) ?: return@withContext
        val type = json.get("type")?.asString ?: return@withContext

        when (type) {
            "message" -> {
                val from = json.get("from")?.asString ?: return@withContext

                // ── Receipt handling ──────────────────────────────────────────
                val receiptType = json.get("receipt_type")?.asString
                if (receiptType != null) {
                    when (receiptType) {
                        "delivered" -> {
                            val msgId = json.get("message_ref_id")?.asLong ?: return@withContext
                            db.messageDao().markDelivered(msgId)
                        }
                        "read_all" -> {
                            db.messageDao().markAllRead(from)
                        }
                    }
                    return@withContext
                }

                // ── Normal message ────────────────────────────────────────────
                val payloadObj = json.getAsJsonObject("payload") ?: return@withContext
                val payload = EncryptedPayload(
                    encryptedData = payloadObj.get("encryptedData").asString,
                    iv = payloadObj.get("iv").asString,
                    encryptedKey = payloadObj.get("encryptedKey").asString
                )
                try {
                    val text = CryptoEngine.decryptMessage(payload, myPrivateKey)
                    val msg = MessageEntity(
                        conversationId = from,
                        fromUuid = from,
                        content = text,
                        contentType = "text",
                        isSent = false,
                        isDelivered = true,
                        isRead = false
                    )
                    val msgId = db.messageDao().insertMessage(msg)

                    // Send delivery receipt back
                    NetworkClient.sendReceipt(from, msgId, "delivered")

                    // If chat is open, mark as read immediately
                    if (_uiState.value.currentConversation == from) {
                        db.messageDao().markIncomingRead(from)
                        NetworkClient.sendReadReceipt(from)
                    } else {
                        // Show notification
                        val contact = db.contactDao().getContact(from)
                        val nickname = contact?.nickname ?: ""
                        withContext(Dispatchers.Main) {
                            showMessageNotification(from, text, nickname)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            "file_chunk" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val payloadObj = json.getAsJsonObject("payload") ?: return@withContext
                try {
                    val chunk = gson.fromJson(payloadObj, EncryptedFileChunk::class.java)
                    val key = "${from}_${chunk.fileName}"
                    val chunkMap = incomingChunks.getOrPut(key) { mutableMapOf() }
                    val decryptedBytes = CryptoEngine.decryptFileChunk(chunk, myPrivateKey)
                    chunkMap[chunk.chunkIndex] = decryptedBytes
                    incomingChunkMeta[key] = chunk

                    if (chunkMap.size == chunk.totalChunks) {
                        val fullFile = (0 until chunk.totalChunks)
                            .flatMap { chunkMap[it]!!.toList() }
                            .toByteArray()
                        val dir = context.getExternalFilesDir(null)
                        val file = java.io.File(dir, chunk.fileName)
                        file.writeBytes(fullFile)

                        val contentType = if (chunk.mimeType.startsWith("image")) "image" else "file"
                        val msg = MessageEntity(
                            conversationId = from,
                            fromUuid = from,
                            content = "[received: ${chunk.fileName}]",
                            contentType = contentType,
                            filePath = file.absolutePath,
                            isSent = false
                        )
                        db.messageDao().insertMessage(msg)
                        incomingChunks.remove(key)
                        incomingChunkMeta.remove(key)

                        if (_uiState.value.currentConversation != from) {
                            val contact = db.contactDao().getContact(from)
                            val nickname = contact?.nickname ?: ""
                            withContext(Dispatchers.Main) {
                                showFileNotification(from, chunk.fileName, nickname)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Clean up partial chunks on error to prevent stale state
                    val key = "${from}_${try { gson.fromJson(payloadObj, EncryptedFileChunk::class.java).fileName } catch (ex: Exception) { return@withContext }}"
                    incomingChunks.remove(key)
                    incomingChunkMeta.remove(key)
                }
            }

            "public_key_response" -> {
                val targetUuid = json.get("target")?.asString ?: return@withContext
                val publicKey = json.get("public_key")?.asString ?: return@withContext
                val existing = db.contactDao().getContact(targetUuid)
                if (existing != null) {
                    db.contactDao().insertContact(existing.copy(publicKey = publicKey))
                } else {
                    db.contactDao().insertContact(ContactEntity(uuid = targetUuid, publicKey = publicKey))
                }
            }

            "webrtc_offer" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val sdp = json.get("sdp")?.asString ?: return@withContext
                _uiState.update {
                    it.copy(callState = CallState(
                        isInCall = true,
                        remoteUuid = from,
                        isIncoming = true,
                        pendingOfferSdp = sdp
                    ))
                }
            }

            "webrtc_answer" -> {
                val sdp = json.get("sdp")?.asString ?: return@withContext
                try {
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    webRTCManager?.setRemoteAnswer(answer)
                } catch (e: Exception) { e.printStackTrace() }
            }

            "webrtc_ice" -> {
                val candidate = json.get("candidate")?.asString ?: return@withContext
                val sdpMid = json.get("sdpMid")?.asString
                val sdpMLineIndex = json.get("sdpMLineIndex")?.asInt ?: 0
                try {
                    val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    webRTCManager?.addIceCandidate(ice)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    // ─── WebRTC ───────────────────────────────────────────────────────────────

    fun startCall(remoteUuid: String) {
        _uiState.update { it.copy(callState = CallState(isInCall = true, remoteUuid = remoteUuid)) }
        try {
            webRTCManager = WebRTCManager(
                context = context,
                localUuid = myUuid,
                remoteUuid = remoteUuid,
                onIceCandidate = { candidate ->
                    NetworkClient.sendICECandidate(remoteUuid, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                },
                onLocalSdp = { sdp ->
                    if (sdp.type == SessionDescription.Type.OFFER) {
                        NetworkClient.sendWebRTCOffer(remoteUuid, sdp.description)
                    } else {
                        NetworkClient.sendWebRTCAnswer(remoteUuid, sdp.description)
                    }
                },
                onTrack = {},
                onCallEnded = { endCall() }
            )
            webRTCManager?.createOffer()
        } catch (e: Exception) {
            e.printStackTrace()
            endCall()
        }
    }

    fun acceptCall() {
        val callState = _uiState.value.callState
        val remoteUuid = callState.remoteUuid
        val offerSdp = callState.pendingOfferSdp
        try {
            webRTCManager = WebRTCManager(
                context = context,
                localUuid = myUuid,
                remoteUuid = remoteUuid,
                onIceCandidate = { candidate ->
                    NetworkClient.sendICECandidate(remoteUuid, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                },
                onLocalSdp = { sdp ->
                    NetworkClient.sendWebRTCAnswer(remoteUuid, sdp.description)
                },
                onTrack = {},
                onCallEnded = { endCall() }
            )
            val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            webRTCManager?.createAnswer(offer)
            _uiState.update { it.copy(callState = callState.copy(isIncoming = false, pendingOfferSdp = "")) }
        } catch (e: Exception) {
            e.printStackTrace()
            endCall()
        }
    }

    fun endCall() {
        try { webRTCManager?.endCall() } catch (e: Exception) {}
        webRTCManager = null
        _uiState.update { it.copy(callState = CallState()) }
    }

    // ─── Factory ──────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return KryptViewModel(context.applicationContext) as T
        }
    }
}

val Gson = Gson()
