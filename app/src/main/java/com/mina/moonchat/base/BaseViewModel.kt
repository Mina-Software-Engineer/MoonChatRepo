package com.mina.moonchat.base

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.mina.moonchat.data.UserLocalRepository1
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.data.server.UserLocalRepository
import com.mina.moonchat.models.User
import com.mina.moonchat.utils.SingleLiveEvent
import kotlinx.coroutines.launch

/**
 * Base class for View Models to declare the common LiveData objects in one place
 */

abstract class BaseViewModel(
    val app: Application,
    val localRepo: UserLocalRepository1,
    val repo: UserLocalRepository
) : AndroidViewModel(app) {

    private val storageInstance: FirebaseStorage by lazy {      //creating the main root of storage
        FirebaseStorage.getInstance()
    }

    //creating a reference object to Storage
    private val currentUserStorageRef: StorageReference
        //creating a new node that is the userID
        get() = storageInstance.reference.child(FirebaseAuth.getInstance().currentUser?.uid.toString())

    private val firestoreInstance: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    //creating a reference object to Firestore Database
    val currentUserDocRef: DocumentReference
        //creating a path to users/uid
        get() = firestoreInstance.document("Users/${FirebaseAuth.getInstance().currentUser?.uid.toString()}")

    val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //                      mAuth = FirebaseAuth.getInstance()
    }



    val _form = MutableLiveData<FormState>()
    val formState: LiveData<FormState> = _form

    private val _result = MutableLiveData<Result>()
    val result: LiveData<Result> = _result

    val navigationCommand: SingleLiveEvent<NavigationCommand> = SingleLiveEvent()
    val progressBar: SingleLiveEvent<Boolean> = SingleLiveEvent()



    //adding current user info to the database
    fun addUserToDB() {
        getCurrentUserInfo { user ->

            //val pfp = storageInstance.getReference(user.profileImg!!)

            /*Toast.makeText(
                app.applicationContext,
                "pfp is: $pfp",
                Toast.LENGTH_SHORT
            ).show()*/

            viewModelScope.launch {
                localRepo.addCurrentUserInfo(
                    AuthUserDTO(
                        user.userId,
                        user.displayName,
                        user.email,
                        user.password,
                        user.profileImg,
                        user.onlineState,
                        user.bio,
                        user.id
                    )
                )
            }

        }
    }

    //getting current user info from the server
    fun getCurrentUserInfo(onComplete: (User) -> Unit) {
        currentUserDocRef.get().addOnSuccessListener {
            onComplete(it.toObject(User::class.java)!!)
        }

    }

}