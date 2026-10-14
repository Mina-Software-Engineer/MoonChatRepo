package com.mina.moonchat.base

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.mina.moonchat.data.dto.toAuthUserDTO
import com.mina.moonchat.data.server.UserLocalRepository
import com.mina.moonchat.models.User
import com.mina.moonchat.models.toNormalizedUser
import com.mina.moonchat.utils.SingleLiveEvent
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Base class for View Models to declare the common LiveData objects in one place
 */

abstract class BaseViewModel(
    val app: Application,
    val repo: UserLocalRepository
) : AndroidViewModel(app) {
    val localRepo: UserLocalRepository get() = repo

    private val storageInstance: FirebaseStorage by lazy {
        FirebaseStorage.getInstance()
    }

    private val currentUserStorageRef: StorageReference
        get() = storageInstance.reference.child(FirebaseAuth.getInstance().currentUser?.uid.toString())

    private val firestoreInstance: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    val currentUserDocRef: DocumentReference
        get() = firestoreInstance.document("Users/${FirebaseAuth.getInstance().currentUser?.uid.toString()}")

    val mAuth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    val _form = MutableLiveData<FormState>()
    val formState: LiveData<FormState> = _form

    private val _result = MutableLiveData<Result>()
    val result: LiveData<Result> = _result

    val navigationCommand: SingleLiveEvent<NavigationCommand> = SingleLiveEvent()
    val progressBar: SingleLiveEvent<Boolean> = SingleLiveEvent()

    // Adding current user info to the local Room database
    fun addUserToDB() {
        getCurrentUserInfo { user ->
            viewModelScope.launch {
                localRepo.addCurrentUserInfo(user.toAuthUserDTO())
            }
        }
    }

    fun ensureCurrentUserDocument(
        authUser: FirebaseUser? = mAuth.currentUser,
        onComplete: (User?) -> Unit = {}
    ) {
        val firebaseUser = authUser ?: run {
            onComplete(null)
            return
        }

        currentUserDocRef.get()
            .addOnSuccessListener { snapshot ->
                val existingUser = snapshot.toNormalizedUser()
                if (snapshot.exists() && existingUser != null) {
                    // Transparent in-place document migration on Firestore & local DB
                    currentUserDocRef.set(existingUser.toFirestoreMap(), SetOptions.merge())
                    viewModelScope.launch {
                        localRepo.addCurrentUserInfo(existingUser.toAuthUserDTO())
                    }
                    onComplete(existingUser)
                    return@addOnSuccessListener
                }

                val displayName = firebaseUser.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: firebaseUser.email?.substringBefore("@")
                    ?: "MoonChat User"
                val randomNum = Random(System.nanoTime()).nextInt(1000, 9999)
                val recoveredUser = User(
                    userId = firebaseUser.uid,
                    displayName = displayName,
                    email = firebaseUser.email.orEmpty(),
                    password = "",
                    profileImg = null,
                    onlineState = "Offline",
                    bio = "",
                    id = "#${displayName.replace(" ", "")}$randomNum"
                )

                currentUserDocRef.set(recoveredUser.toFirestoreMap(), SetOptions.merge())
                    .addOnSuccessListener {
                        viewModelScope.launch {
                            localRepo.addCurrentUserInfo(recoveredUser.toAuthUserDTO())
                        }
                        onComplete(recoveredUser)
                    }
                    .addOnFailureListener { onComplete(null) }
            }
            .addOnFailureListener {
                onComplete(null)
            }
    }

    // Getting current user info from the server
    fun getCurrentUserInfo(onComplete: (User) -> Unit) {
        ensureCurrentUserDocument { user ->
            user?.let(onComplete)
        }
    }
}
