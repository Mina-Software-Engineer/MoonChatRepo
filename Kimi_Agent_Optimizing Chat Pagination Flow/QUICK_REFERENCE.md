# Quick Reference Guide

## Your Desired Flow (Implemented)

### Sender Flow
```
User sends message
    ↓
Save to Room with PENDING status (Optimistic UI)
    ↓
Send to Firebase
    ↓
Firebase confirms with real ID
    ↓
Update Room: temp ID → real ID, PENDING → SYNCED
    ↓
UI updates automatically (Paging)
```

### Recipient Flow
```
Firebase listener detects new message
    ↓
Check if already exists in Room
    ↓
Save to Room with RECEIVED status
    ↓
UI updates automatically (Paging)
```

### Pagination Flow (Both Users)
```
User scrolls up to load older messages
    ↓
RemoteMediator fetches from Firebase (timestamp-based)
    ↓
Save to Room (ignores duplicates)
    ↓
UI updates via Paging
```

---

## Key Methods Reference

### Sending a Message
```kotlin
// In UserLocalRepository
suspend fun sendMessage(message: TextMessage) {
    // 1. Save with PENDING status (optimistic)
    // 2. Send to Firebase
    // 3. Update with real ID when confirmed
}
```

### Getting Messages
```kotlin
// In ChatViewModel
fun getMessageStream(recipientId: String): Flow<PagingData<TextMessage>> {
    // 1. Start real-time sync
    // 2. Return Paging flow from Room
}
```

### Handling Incoming Messages
```kotlin
// In UserLocalRepository
private fun handleIncomingMessage(message: TextMessage, currentUserId: String) {
    // 1. Check if exists
    // 2. If new → insert with RECEIVED/SYNCED status
    // 3. If pending → update to SYNCED
}
```

---

## Common Issues & Solutions

| Issue | Cause | Solution |
|-------|-------|----------|
| Duplicate messages | Listener inserts without checking | `messageExists()` check before insert |
| Wrong message order | Key-based pagination | Timestamp-based pagination |
| Messages not showing | Wrong channel ID | Use `getChannelId()` consistently |
| Failed messages lost | No status tracking | Use `MessageStatus.FAILED` |
| Memory leaks | Listeners not removed | Call `stopRealtimeSync()` in `onDestroy()` |

---

## Code Snippets

### Channel ID Generation
```kotlin
// Always use this method - it's consistent!
fun getChannelId(userId1: String, userId2: String): String {
    return if (userId1 < userId2) "${userId1}_${userId2}" else "${userId2}_${userId1}"
}
```

### Check Message Existence
```kotlin
// Before inserting from listener
if (!msgDao.messageExists(message.id)) {
    msgDao.insertMessage(message.toMessagesDTO())
}
```

### Retry Failed Message
```kotlin
// In your fragment/adapter
messageAdapter.setOnMessageClickListener(object : MessageAdapter.OnMessageClickListener {
    override fun onMessageClick(message: TextMessage) {
        if (message.isFailed()) {
            viewModel.retryMessage(message)
        }
    }
})
```

---

## Firebase Structure

```
Message/
  Sender: {userId}/
    ChannelID: {sorted_user1_user2}/
      Messages/
        {messageId}/
          id: "messageId"
          text: "Hello"
          senderId: "user1"
          recipientId: "user2"
          date: ServerValue.TIMESTAMP
          ...
```

---

## Lifecycle Management

```kotlin
class ChatFragment : Fragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Start sync
        loadMessages(recipientId)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Stop sync to prevent memory leaks
        viewModel.stopSync()
    }
}
```

---

## Dependencies (build.gradle)

```groovy
dependencies {
    // Paging
    implementation "androidx.paging:paging-runtime-ktx:3.2.1"
    
    // Room
    implementation "androidx.room:room-runtime:2.6.1"
    implementation "androidx.room:room-ktx:2.6.1"
    kapt "androidx.room:room-compiler:2.6.1"
    
    // Firebase
    implementation platform('com.google.firebase:firebase-bom:32.7.0')
    implementation 'com.google.firebase:firebase-database-ktx'
    implementation 'com.google.firebase:firebase-firestore-ktx'
    
    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3'
}
```
