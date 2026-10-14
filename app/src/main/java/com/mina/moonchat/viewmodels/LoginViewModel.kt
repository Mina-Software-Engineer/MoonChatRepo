package com.mina.moonchat.viewmodels

import android.app.Application
import android.util.Patterns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.mina.moonchat.R
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.base.FormState

sealed class PasswordResetState {
    object Idle : PasswordResetState()
    object Loading : PasswordResetState()
    data class Success(val messageResId: Int) : PasswordResetState()
    data class Error(val messageResId: Int) : PasswordResetState()
}

sealed class EmailVerificationState {
    object Idle : EmailVerificationState()
    object Loading : EmailVerificationState()
    data class Unverified(val email: String, val user: FirebaseUser?) : EmailVerificationState()
    data class ResentSuccess(val messageResId: Int) : EmailVerificationState()
    data class Error(val messageResId: Int) : EmailVerificationState()
}

class LoginViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repo
) {

    companion object {
        fun isValidEmail(email: String): Boolean {
            val trimmed = email.trim()
            if (trimmed.isEmpty()) return false
            return Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
        }

        fun mapPasswordResetError(exception: Exception?): Int {
            return when {
                exception is FirebaseNetworkException || exception?.message?.lowercase()?.contains("network") == true -> R.string.error_network_failure
                exception?.message?.lowercase()?.contains("too-many-requests") == true ||
                    exception?.message?.lowercase()?.contains("too many requests") == true -> R.string.error_too_many_requests
                else -> R.string.error_generic
            }
        }
    }

    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private val _passwordResetState = MutableLiveData<PasswordResetState>(PasswordResetState.Idle)
    val passwordResetState: LiveData<PasswordResetState> = _passwordResetState

    private val _emailVerificationState = MutableLiveData<EmailVerificationState>(EmailVerificationState.Idle)
    val emailVerificationState: LiveData<EmailVerificationState> = _emailVerificationState

    private val _loginErrorResId = MutableLiveData<Int?>(null)
    val loginErrorResId: LiveData<Int?> = _loginErrorResId

    private val _loginSuccess = MutableLiveData<Boolean>(false)
    val loginSuccess: LiveData<Boolean> = _loginSuccess

    fun login(email: String, password: String) {
        if (progressBar.value == true) return // Prevent double-tap
        progressBar.value = true
        _loginErrorResId.value = null

        mAuth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val firebaseUser = mAuth.currentUser
                if (firebaseUser == null) {
                    progressBar.value = false
                    _loginErrorResId.value = R.string.login_failed
                    return@addOnCompleteListener
                }

                // Force reload user state from Firebase server to check email verification
                firebaseUser.reload().addOnCompleteListener { reloadTask ->
                    if (!reloadTask.isSuccessful) {
                        progressBar.value = false
                        val exception = reloadTask.exception
                        if (exception is FirebaseNetworkException) {
                            _loginErrorResId.value = R.string.error_network_failure
                        } else {
                            _loginErrorResId.value = R.string.login_failed
                        }
                        return@addOnCompleteListener
                    }

                    if (!firebaseUser.isEmailVerified) {
                        progressBar.value = false
                        _emailVerificationState.value = EmailVerificationState.Unverified(
                            email = firebaseUser.email.orEmpty(),
                            user = firebaseUser
                        )
                        return@addOnCompleteListener
                    }

                    // Verified user -> grant access
                    ensureCurrentUserDocument(firebaseUser) { user ->
                        if (user == null) {
                            progressBar.value = false
                            _loginErrorResId.value = R.string.login_failed
                            return@ensureCurrentUserDocument
                        }

                        FirebaseMessaging.getInstance().token.addOnCompleteListener { tokenTask ->
                            val token = tokenTask.result
                            if (!token.isNullOrBlank()) {
                                val tokenUpdates = mapOf(
                                    "fcmToken" to token,
                                    "token" to token
                                )
                                firestore.collection("Users").document(firebaseUser.uid)
                                    .set(tokenUpdates, SetOptions.merge())
                            }
                            addUserToDB()
                        }

                        progressBar.value = false
                        _loginSuccess.value = true
                    }
                }
            } else {
                progressBar.value = false
                val exception = task.exception
                when {
                    exception is FirebaseNetworkException -> {
                        _loginErrorResId.value = R.string.error_network_failure
                    }
                    exception?.message?.lowercase()?.contains("too-many-requests") == true ||
                    exception?.message?.lowercase()?.contains("too many requests") == true -> {
                        _loginErrorResId.value = R.string.error_too_many_requests
                    }
                    else -> {
                        _loginErrorResId.value = R.string.login_failed
                    }
                }
            }
        }
    }

    fun sendPasswordResetEmail(emailInput: String) {
        val trimmedEmail = emailInput.trim()
        if (trimmedEmail.isEmpty() || !isValidEmail(trimmedEmail)) {
            _passwordResetState.value = PasswordResetState.Error(R.string.invalid_Email)
            return
        }

        if (_passwordResetState.value is PasswordResetState.Loading) return
        _passwordResetState.value = PasswordResetState.Loading

        mAuth.sendPasswordResetEmail(trimmedEmail).addOnCompleteListener { task ->
            val exception = task.exception
            when {
                task.isSuccessful -> {
                    _passwordResetState.value = PasswordResetState.Success(R.string.password_reset_neutral_success)
                }
                exception is FirebaseAuthInvalidUserException -> {
                    _passwordResetState.value = PasswordResetState.Success(R.string.password_reset_neutral_success)
                }
                exception is FirebaseNetworkException || exception?.message?.lowercase()?.contains("network") == true -> {
                    _passwordResetState.value = PasswordResetState.Error(R.string.error_network_failure)
                }
                exception?.message?.lowercase()?.contains("too-many-requests") == true ||
                    exception?.message?.lowercase()?.contains("too many requests") == true -> {
                    _passwordResetState.value = PasswordResetState.Error(R.string.error_too_many_requests)
                }
                else -> {
                    _passwordResetState.value = PasswordResetState.Error(mapPasswordResetError(exception))
                }
            }
        }
    }

    fun resendVerificationEmail(user: FirebaseUser?) {
        val targetUser = user ?: mAuth.currentUser
        if (targetUser == null) {
            _emailVerificationState.value = EmailVerificationState.Error(R.string.error_generic)
            return
        }

        _emailVerificationState.value = EmailVerificationState.Loading

        targetUser.sendEmailVerification().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                _emailVerificationState.value = EmailVerificationState.ResentSuccess(R.string.verification_email_resent)
            } else {
                val exception = task.exception
                when {
                    exception is FirebaseNetworkException || exception?.message?.lowercase()?.contains("network") == true -> {
                        _emailVerificationState.value = EmailVerificationState.Error(R.string.error_network_failure)
                    }
                    exception?.message?.lowercase()?.contains("too-many-requests") == true ||
                    exception?.message?.lowercase()?.contains("too many requests") == true -> {
                        _emailVerificationState.value = EmailVerificationState.Error(R.string.error_too_many_requests)
                    }
                    else -> {
                        _emailVerificationState.value = EmailVerificationState.Error(R.string.error_generic)
                    }
                }
            }
        }
    }

    fun clearPasswordResetState() {
        _passwordResetState.value = PasswordResetState.Idle
    }

    fun clearEmailVerificationState() {
        _emailVerificationState.value = EmailVerificationState.Idle
    }

    fun clearLoginError() {
        _loginErrorResId.value = null
    }

    fun loginDataChanged(username: String, password: String) {
        if (!isUserNameValid(username)) {
            _form.value = FormState(usernameError = R.string.invalid_email)
        } else if (!isPasswordValid(password)) {
            _form.value = FormState(passwordError = R.string.invalid_password)
        } else {
            _form.value = FormState(isDataValid = true)
        }
    }

    private fun isUserNameValid(username: String): Boolean {
        return if (username.contains("@")) {
            isValidEmail(username)
        } else {
            username.isNotBlank()
        }
    }

    private fun isPasswordValid(password: String): Boolean {
        return password.length > 5
    }
}
