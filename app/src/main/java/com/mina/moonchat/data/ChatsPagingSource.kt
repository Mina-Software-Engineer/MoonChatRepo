import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mina.moonchat.models.ChatItem
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatPagingSource(
    private val userId: String
) : PagingSource<Query, ChatItem>() {

    private val db = FirebaseFirestore.getInstance()
    private val chatsCollection = db.collection("Users").document(userId).collection("chat channel")
        .orderBy("date", Query.Direction.DESCENDING)

    override fun getRefreshKey(state: PagingState<Query, ChatItem>): Query? {
        return null // No refresh key needed for Firestore pagination
    }

    override suspend fun load(params: LoadParams<Query>): LoadResult<Query, ChatItem> {
        return try {
            val currentPage = params.key ?: chatsCollection.limit(20) // First page

            val snapshot = currentPage.get().await()

            val lastVisibleDoc = snapshot.documents.lastOrNull() // Get last document
            val nextQuery = lastVisibleDoc?.let {
                chatsCollection.startAfter(it).limit(20) // Next page query
            }

            val chats = snapshot.documents.mapNotNull { document ->
                val timestamp = document.getTimestamp("date")?.toDate()
                ChatItem(
                    chatId = document.id,
                    recipientId = document.getString("recipientId") ?: return@mapNotNull null,
                    username = document.getString("recipientName") ?: "Unknown",
                    lastMessage = document.getString("lastMessage") ?: "",
                    time = timestamp?.let(::formatChatTime).orEmpty(),
                    onlineState = document.getBoolean("isOnline") ?: false,
                    profileImg = document.getString("profileImg") ?: ""
                )
            }


            LoadResult.Page(
                data = chats,
                prevKey = null, // Only forward pagination
                nextKey = nextQuery
            )
        } catch (e: Exception) {
            Log.e("ChatPagingSource", "Failed to load chats", e)
            LoadResult.Error(e) // Handle error
        }
    }

    private fun formatChatTime(date: Date): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
    }
}
