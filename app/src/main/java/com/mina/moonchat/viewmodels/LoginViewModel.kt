package com.mina.moonchat.viewmodels

import android.app.Application
import android.content.Intent
import android.util.Patterns
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.mina.moonchat.ui.activities.MainActivity
import com.mina.moonchat.R
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.base.FormState

class LoginViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repository,
    (app as MoonChat).repo
) {

    /*private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //mAuth = FirebaseAuth.getInstance()
    }*/
    private val firestore : FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    fun login(email: String, password: String) {
        progressBar.value = true
        mAuth.signInWithEmailAndPassword(email, password).addOnCompleteListener{ task ->
            if (task.isSuccessful){
                ensureCurrentUserDocument { user ->
                    if (user == null) {
                        progressBar.value = false
                        Toast.makeText(
                            app.applicationContext,
                            "There was an error restoring your profile",
                            Toast.LENGTH_LONG
                        ).show()
                        return@ensureCurrentUserDocument
                    }

                    FirebaseMessaging.getInstance().token.addOnCompleteListener {
                        val token: String? = it.result
                        firestore.collection("Users").document(mAuth.currentUser!!.uid)
                            .update(mapOf("token" to token))
                        addUserToDB()
                    }

                    progressBar.value = false
                    val intentToMain = Intent(app.applicationContext, MainActivity::class.java)
                    intentToMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    app.startActivity(intentToMain)
                }
            }else{
                progressBar.value = false
                Toast.makeText(app.applicationContext, task.exception?.message, Toast.LENGTH_LONG).show()
            }
        }
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

    // A placeholder username validation check
    private fun isUserNameValid(username: String): Boolean {
        return if (username.contains("@")) {
            Patterns.EMAIL_ADDRESS.matcher(username).matches()
        } else {
            username.isNotBlank()
        }
    }

    // A placeholder password validation check
    private fun isPasswordValid(password: String): Boolean {
        return password.length > 5
    }


}
