package com.mina.moonchat.viewmodels

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.intro_activity.data.Result
import com.mina.moonchat.utils.CloudinaryImageUploader
import kotlinx.coroutines.launch

class ProfileViewModel(
    app: Application
)//, val dataSource: UserDataSource)
    : BaseViewModel(app, (app as MoonChat).repository, (app as MoonChat).repo) {

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

                currentUserDocRef.update(mapOf("profileImg" to uploadedImageUrl))
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

    /** 3) Retrieving photo from local storage*/
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
                    "Theres was error getting image from DB: ${result.exception}",
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

    fun deleteCurrentUserFromDB(){
        viewModelScope.launch {
            val result = localRepo.getCurrentUserInfo()
            when(result){
                is Result.Success<*> -> {
                    localRepo.deleteCurrentUserInfo()
                }
                is Result.Error -> {
                    Toast.makeText(
                        app.applicationContext,
                        "Theres was error deleting user from database: ${result.exception}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
