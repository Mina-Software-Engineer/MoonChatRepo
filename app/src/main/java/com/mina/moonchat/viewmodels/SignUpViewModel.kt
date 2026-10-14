package com.mina.moonchat.viewmodels

import android.app.Application
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.mina.moonchat.R
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.base.FormState
import com.mina.moonchat.models.User
import kotlin.random.Random

class SignUpViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repo
) {

    private val firestoreInstance: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    fun signupDataChanged(username: String, email: String, password: String) {
        if (!isUserNameValid(username)) {
            _form.value = FormState(usernameError = R.string.invalid_email)
        } else if (!isEmailValid(email)) {
            _form.value = FormState(emailError = R.string.invalid_Email)
        } else if (!isPasswordValid(password)) {
            _form.value = FormState(passwordError = R.string.invalid_password)
        } else {
            _form.value = FormState(isDataValid = true)
            createNewAccount(username, email, password)
        }
    }

    private fun isUserNameValid(username: String): Boolean {
        return username.isNotEmpty()
    }

    private fun isEmailValid(email: String): Boolean {
        return email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun isPasswordValid(password: String): Boolean {
        return password.length > 5
    }

    private fun createNewAccount(name: String, email: String, password: String) {
        mAuth.createUserWithEmailAndPassword(email, password).addOnSuccessListener {
            val randomNum = Random(System.nanoTime()).nextInt(1000, 9999)
            val newUser = User(
                userId = mAuth.currentUser!!.uid,
                displayName = name,
                email = email,
                password = password,
                profileImg = null,
                onlineState = "Offline",
                bio = "",
                id = "#$name$randomNum"
            )
            currentUserDocRef.set(newUser.toFirestoreMap())
            addUserToDB()
            Log.d("SignUpViewModel", "User created: ${newUser.displayName}")
            Toast.makeText(app.applicationContext, "Account was created successfully!", Toast.LENGTH_LONG).show()
        }
    }
}
