package com.mina.moonchat.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.intro_activity.data.Result
import com.mina.moonchat.utils.CloudinaryImageUploader
import com.mina.moonchat.utils.FriendshipDataMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProfileViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repo
) {

    private val _pfpImage = MutableLiveData<Uri?>()
    val pfpImage: LiveData<Uri?> = _pfpImage

    private val _tvName = MutableLiveData<String>()
    val tvName: LiveData<String> = _tvName

    private val _tvEmail = MutableLiveData<String>()
    val tvEmail: LiveData<String> = _tvEmail

    private val _tvBio = MutableLiveData<String>()
    val tvBio: LiveData<String> = _tvBio

    private val _tvUserID = MutableLiveData<String>()
    val tvUserID: LiveData<String> = _tvUserID

    private val _tvFriendsCount = MutableLiveData<String>("0 Friends")
    val tvFriendsCount: LiveData<String> = _tvFriendsCount

    fun fetchFriendsCount() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val confirmedFriendIds = FriendshipDataMigration.fetchConfirmedFriendIds(
                FirebaseFirestore.getInstance(),
                currentUserId
            )
            val count = confirmedFriendIds.size
            launch(Dispatchers.Main) {
                _tvFriendsCount.value = if (count == 1) "1 Friend" else "$count Friends"
            }
        }
    }

    fun setPfpPhoto(uri: Uri) {
        _pfpImage.value = uri
        uploadPhoto(uri)
    }

    private fun uploadPhoto(uri: Uri) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserId.isNullOrBlank()) {
            Toast.makeText(
                app.applicationContext,
                "There was an error finding the current user",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        viewModelScope.launch {
            try {
                val uploadedImageUrl = CloudinaryImageUploader.uploadProfileImage(
                    context = app.applicationContext,
                    imageUri = uri,
                    userId = currentUserId
                )

                val imageUpdates = mapOf(
                    "profileImg" to uploadedImageUrl,
                    "pfp" to uploadedImageUrl,
                    "profilePic" to uploadedImageUrl
                )

                currentUserDocRef.update(imageUpdates)
                    .addOnSuccessListener {
                        _pfpImage.value = uploadedImageUrl.toUri()
                        addUserToDB()

                        Toast.makeText(
                            app.applicationContext,
                            "Image was uploaded successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(
                            app.applicationContext,
                            "There was an error saving the image URL",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (exception: Exception) {
                Toast.makeText(
                    app.applicationContext,
                    exception.message ?: "There was an error uploading the image",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Retrieving photo from local storage */
    fun getCurrentUserFromDB() {
        viewModelScope.launch {
            val result = localRepo.getCurrentUserInfo()

            if (result is Result.Success<*>) {
                val currentUser = result.data as AuthUserDTO

                _pfpImage.value = currentUser.pfp?.toUri()
                _tvName.value = currentUser.username
                _tvEmail.value = currentUser.email
                _tvBio.value = currentUser.bio
                _tvUserID.value = currentUser.id

            } else if (result is Result.Error) {
                Toast.makeText(
                    app.applicationContext,
                    "There was error getting user info from DB: ${result.exception}",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
        }
    }

    fun updateCurrentUserInfo(view: String, string: String) {
        val updatedInfo = mutableMapOf<String, Any>()

        when (view) {
            "name" -> {
                updatedInfo["displayName"] = string
                updatedInfo["username"] = string
                updatedInfo["name"] = string
                currentUserDocRef.update(updatedInfo)
                _tvName.value = string
                addUserToDB()
            }

            "email" -> {
                updatedInfo["email"] = string
                currentUserDocRef.update(updatedInfo)
                _tvEmail.value = string
                addUserToDB()
            }

            "bio" -> {
                updatedInfo["bio"] = string
                currentUserDocRef.update(updatedInfo)
                _tvBio.value = string
                addUserToDB()
            }

            else -> return
        }
    }

    fun deleteCurrentUserFromDB() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                (app as MoonChat).database.clearAllTables()
                Log.d("ProfileViewModel", "All Room database tables cleared on sign out.")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error clearing Room database on sign out: ${e.message}")
            }
        }
    }
}
