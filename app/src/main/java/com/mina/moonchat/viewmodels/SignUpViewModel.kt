package com.mina.moonchat.viewmodels

import android.app.Application
import android.util.Patterns
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.mina.moonchat.R
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.base.FormState
import com.mina.moonchat.models.User
import kotlin.random.Random

class SignUpViewModel(app: Application): BaseViewModel(
    app,
    (app as MoonChat).repository,
    (app as MoonChat).repo
) {

    //a reference to firestore database
    private val firestoreInstance: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    /*private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //                      mAuth = FirebaseAuth.getInstance()
    }*/

    fun signupDataChanged(username: String, email: String, password: String) {
        if (!isUserNameValid(username)) {
            _form.value = FormState(usernameError = R.string.invalid_email)
        }else if(!isEmailValid(email)){
            _form.value = FormState(emailError = R.string.invalid_Email)
        }
        else if (!isPasswordValid(password)) {
            _form.value = FormState(passwordError = R.string.invalid_password)
        } else {
            _form.value = FormState(isDataValid = true)
            createNewAccount(username, email, password)
        }
    }

    // A placeholder username validation check
    private fun isUserNameValid(username: String): Boolean {
        return if (username.isEmpty()) {
            false
        } else {
            username.isNotEmpty()
        }
    }

    private fun isEmailValid(email: String): Boolean {
        return if (email.isNotEmpty()) {
            Patterns.EMAIL_ADDRESS.matcher(email).matches()
        } else if(email.isEmpty()){
            false
        } else {
            email.isNotEmpty()
        }
    }

    // A placeholder password validation check
    private fun isPasswordValid(password: String): Boolean {
        return password.length > 5
    }


    private fun createNewAccount(name: String, email: String, password: String){

        mAuth.createUserWithEmailAndPassword(email, password).addOnCompleteListener {
            if (it.isSuccessful){
                val randomNum = Random(System.nanoTime()).nextInt(9999 - 1)
                val newUser = User(
                    mAuth.currentUser!!.uid,
                    name,
                    email,
                    password,
                    null,
                    "",
                    "",
                    "#$name$randomNum")
                currentUserDocRef.set(newUser)
                addUserToDB()

                Toast.makeText(app.applicationContext, "Account was created successfully!", Toast.LENGTH_LONG).show()
                //Log.d("check", "Document id = ${newUser.userId}")
            }
        }
    }
}