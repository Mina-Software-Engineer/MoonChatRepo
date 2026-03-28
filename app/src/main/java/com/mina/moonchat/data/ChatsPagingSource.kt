import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mina.moonchat.models.ChatItem
import kotlinx.coroutines.tasks.await

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
                ChatItem(
                    chatId = document.id,
                    username = document.getString("recipientName") ?: "Unknown",
                    lastMessage = document.getString("text") ?: "",
                    time = document.getTimestamp("date")?.toDate()?.time.toString(),
                    onlineState = document.getBoolean("isOnline") ?: false,
                    profileImg = document.getString("pfp") ?: ""
                )
            }


            LoadResult.Page(
                data = chats,
                prevKey = null, // Only forward pagination
                nextKey = nextQuery
            )
        } catch (e: Exception) {
            LoadResult.Error(e) // Handle error
        }
    }
}
