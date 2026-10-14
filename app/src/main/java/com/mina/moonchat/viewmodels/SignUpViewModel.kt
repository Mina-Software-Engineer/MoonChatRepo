package com.mina.moonchat.viewmodels

import android.app.Application
import android.util.Patterns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mina.moonchat.R
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.base.FormState
import com.mina.moonchat.models.User
import kotlin.random.Random

sealed class SignUpState {
    object Idle : SignUpState()
    object Loading : SignUpState()
    data class VerificationPending(val email: String) : SignUpState()
    data class Error(val messageResId: Int) : SignUpState()
}

class SignUpViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repo
) {

    companion object {
        fun isValidName(name: String): Boolean = name.trim().isNotEmpty()
        fun isValidEmail(email: String): Boolean = email.trim().isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
        fun isValidPassword(password: String): Boolean = password.length >= 6
    }

    private val firestoreInstance: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private val _signUpState = MutableLiveData<SignUpState>(SignUpState.Idle)
    val signUpState: LiveData<SignUpState> = _signUpState

    fun signupDataChanged(username: String, email: String, password: String) {
        if (!isUserNameValid(username)) {
            _form.value = FormState(usernameError = R.string.invalid_name)
        } else if (!isEmailValid(email)) {
            _form.value = FormState(emailError = R.string.invalid_Email)
        } else if (!isPasswordValid(password)) {
            _form.value = FormState(passwordError = R.string.invalid_password)
        } else {
            _form.value = FormState(isDataValid = true)
        }
    }

    fun submitSignUp(username: String, email: String, password: String) {
        if (!isUserNameValid(username)) {
            _form.value = FormState(usernameError = R.string.invalid_name)
            return
        }
        if (!isEmailValid(email)) {
            _form.value = FormState(emailError = R.string.invalid_Email)
            return
        }
        if (!isPasswordValid(password)) {
            _form.value = FormState(passwordError = R.string.invalid_password)
            return
        }
        if (_signUpState.value is SignUpState.Loading) return

        createNewAccount(username, email, password)
    }

    private fun isUserNameValid(username: String): Boolean = isValidName(username)

    private fun isEmailValid(email: String): Boolean = isValidEmail(email)

    private fun isPasswordValid(password: String): Boolean = isValidPassword(password)

    private fun createNewAccount(name: String, email: String, password: String) {
        _signUpState.value = SignUpState.Loading

        mAuth.createUserWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val firebaseUser = mAuth.currentUser
                if (firebaseUser == null) {
                    _signUpState.value = SignUpState.Error(R.string.error_generic)
                    return@addOnCompleteListener
                }

                val randomNum = Random(System.nanoTime()).nextInt(1000, 9999)
                val newUser = User(
                    userId = firebaseUser.uid,
                    displayName = name.trim(),
                    email = email.trim(),
                    password = "",
                    profileImg = null,
                    onlineState = "Offline",
                    bio = "",
                    id = "#$name$randomNum"
                )

                currentUserDocRef.set(newUser.toFirestoreMap(), SetOptions.merge())
                addUserToDB()

                firebaseUser.sendEmailVerification().addOnCompleteListener { sendTask ->
                    mAuth.signOut()
                    if (sendTask.isSuccessful) {
                        _signUpState.value = SignUpState.VerificationPending(email.trim())
                    } else {
                        val exception = sendTask.exception
                        if (exception is FirebaseNetworkException) {
                            _signUpState.value = SignUpState.Error(R.string.error_network_failure)
                        } else {
                            _signUpState.value = SignUpState.VerificationPending(email.trim())
                        }
                    }
                }
            } else {
                val exception = task.exception
                when {
                    exception is FirebaseNetworkException -> {
                        _signUpState.value = SignUpState.Error(R.string.error_network_failure)
                    }
                    exception?.message?.lowercase()?.contains("too-many-requests") == true ||
                        exception?.message?.lowercase()?.contains("too many requests") == true -> {
                        _signUpState.value = SignUpState.Error(R.string.error_too_many_requests)
                    }
                    else -> {
                        _signUpState.value = SignUpState.Error(R.string.error_generic)
                    }
                }
            }
        }
    }

    fun clearSignUpState() {
        _signUpState.value = SignUpState.Idle
    }
}
