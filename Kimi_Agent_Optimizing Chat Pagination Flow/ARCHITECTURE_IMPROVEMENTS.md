# MoonChat Architecture Improvements

## Summary of Changes

This document outlines the improvements made to your MoonChat app's message handling architecture with Firebase, Room Database, and Pagination.

---

## Key Issues Fixed

### 1. **Duplicate Messages**
**Problem**: Real-time listener was inserting messages without checking if they already existed.

**Solution**: 
- Added `messageExists()` check in `MessageDao`
- Implemented `handleIncomingMessage()` in `UserLocalRepository` that checks before inserting
- Uses `OnConflictStrategy.IGNORE` for pagination data

### 2. **Channel ID Inconsistency**
**Problem**: Different channel ID generation across components caused messages to be stored in wrong channels.

**Solution**:
- Centralized channel ID generation in `ServerSide.getChatChannelId()`
- Uses sorted user IDs: `min(user1, user2)_max(user1, user2)`
- Ensures both users see the same channel

### 3. **No Message Status Tracking**
**Problem**: Couldn't distinguish between pending, sent, or failed messages.

**Solution**:
- Added `MessageStatus` enum: `PENDING`, `SENT`, `RECEIVED`, `FAILED`, `SYNCED`
- Added `status` field to `MessagesDTO` and `TextMessage`
- UI shows different indicators for each state

### 4. **Pagination Key Problem**
**Problem**: Using Firebase push keys for pagination wasn't reliable for ordering.

**Solution**:
- Switched to **timestamp-based pagination**
- Added `firebaseTimestamp` field to `MessagesDTO`
- Uses `orderByChild("date")` in Firebase queries

### 5. **Sender's Message Echo**
**Problem**: When sender sends a message, it comes back through the listener as a duplicate.

**Solution**:
- Optimistic UI: Save with `PENDING` status first
- When Firebase confirms, update to `SYNCED` status
- Listener ignores messages that already exist with `SYNCED` status

---

## Improved Message Flow

### Sender Flow
```
1. User sends message
   ↓
2. Create message with temp ID + PENDING status
   ↓
3. Save to Room (optimistic UI - shows immediately)
   ↓
4. Send to Firebase
   ↓
5. Firebase returns message ID
   ↓
6. Update Room with real ID + SYNCED status
```

### Recipient Flow
```
1. Firebase listener detects new message
   ↓
2. Check if message exists in Room
   ↓
3. If new → Save to Room with RECEIVED status
   ↓
4. UI updates automatically via Paging
```

### Pagination Flow (Both Users)
```
1. User scrolls up (load older messages)
   ↓
2. RemoteMediator triggered
   ↓
3. Fetch from Firebase using oldest timestamp
   ↓
4. Save to Room (IGNORE if exists)
   ↓
5. UI updates via Paging
```

---

## Database Schema Changes

### MessagesDTO (Updated)
```kotlin
@Entity(
    tableName = "MessageEntity",
    indices = [
        Index(value = ["channelID", "message_time"]),
        Index(value = ["message_id"], unique = true)
    ]
)
data class MessagesDTO(
    @PrimaryKey(autoGenerate = false)
    val messageId: String,
    val messageText: String,
    val senderId: String,
    val recipientId: String,
    val messageTime: Long,
    val firebaseTimestamp: Long = 0,  // NEW: For consistent ordering
    val channelID: String,
    val messageType: String = "TEXT",
    var senderName: String,
    var recipientName: String,
    val status: MessageStatus = MessageStatus.SENT,  // NEW
    val isRead: Boolean = false  // NEW
)
```

---

## File Changes

| File | Changes |
|------|---------|
| `MessagesDTO.kt` | Added `firebaseTimestamp`, `status`, `isRead` fields; added indices |
| `TextMessage.kt` | Added `status` and `isRead` fields; added helper methods |
| `MessageDao.kt` | Added status queries, duplicate checks, improved pagination |
| `ServerSide.kt` | Timestamp-based pagination, consistent channel IDs, Flow support |
| `UserLocalRepository.kt` | Message status handling, duplicate prevention, listener management |
| `ChatRemoteMediator.kt` | Timestamp-based pagination, better error handling |
| `ChatViewModel.kt` | Lifecycle management, error handling, retry functionality |
| `ChatFragment.kt` | Proper lifecycle, loading states, error handling |
| `MessageAdapter.kt` | Status indicators, click listeners for retry |

---

## Migration Guide

### Step 1: Update Room Database Version
```kotlin
@Database(
    entities = [MessagesDTO::class, AuthUserDTO::class],
    version = 2,  // Increment version
    exportSchema = false
)
abstract class UserDatabase : RoomDatabase() {
    // ...
}
```

### Step 2: Create Migration (if needed)
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add new columns
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN firebase_timestamp INTEGER DEFAULT 0")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN status TEXT DEFAULT 'SENT'")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN is_read INTEGER DEFAULT 0")
        
        // Create new indices
        database.execSQL("CREATE INDEX index_channel_time ON MessageEntity(channelID, message_time)")
    }
}
```

### Step 3: Update Database Builder
```kotlin
Room.databaseBuilder(context, UserDatabase::class.java, "UserDB")
    .addMigrations(MIGRATION_1_2)
    .build()
```

---

## Best Practices Implemented

1. **Single Source of Truth**: Room database is the only source for UI
2. **Optimistic UI**: Messages appear immediately with PENDING status
3. **Duplicate Prevention**: Checks before inserting, uses IGNORE strategy
4. **Proper Lifecycle**: Listeners started/stopped with fragment lifecycle
5. **Error Handling**: Failed messages can be retried
6. **Consistent Ordering**: Timestamp-based pagination and sorting

---

## Testing Checklist

- [ ] Send message → appears immediately with pending indicator
- [ ] Message sends successfully → status changes to sent
- [ ] Pull to refresh → loads older messages
- [ ] Scroll up → paginates correctly
- [ ] Receive message → appears in real-time
- [ ] No duplicate messages when sending
- [ ] No duplicate messages when receiving
- [ ] Failed message → shows error indicator
- [ ] Click failed message → retry works
- [ ] Leave chat → listener stops
- [ ] Return to chat → listener restarts
