package com.mina.moonchat.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.mina.moonchat.R

class IntroActivity : AppCompatActivity() {

    private var authCheckRequest = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MoonChat)
        super.onCreate(savedInstanceState)
        checkSavedSession()
    }

    private fun checkSavedSession() {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            showLogin()
            return
        }

        val requestId = ++authCheckRequest
        showCheckingSession()

        user.reload().addOnCompleteListener(this) { task ->
            if (isFinishing || isDestroyed || requestId != authCheckRequest) return@addOnCompleteListener

            if (task.isSuccessful) {
                val refreshedUser = auth.currentUser
                when {
                    refreshedUser == null -> showLogin()
                    refreshedUser.isEmailVerified -> openMain()
                    else -> {
                        auth.signOut()
                        showLogin()
                        Toast.makeText(
                            this,
                            R.string.email_not_verified_error,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else if (task.exception is FirebaseAuthInvalidUserException) {
                // The account no longer exists or is disabled; discard the invalid local session.
                auth.signOut()
                showLogin()
            } else {
                // Keep the Firebase session intact if the refresh failed temporarily.
                showRetryState()
            }
        }
    }

    private fun showCheckingSession() {
        setContentView(R.layout.activity_startup)
        findViewById<ProgressBar>(R.id.startupProgress).visibility = View.VISIBLE
        findViewById<TextView>(R.id.startupStatus).setText(R.string.auth_checking_session)
        findViewById<Button>(R.id.startupRetry).visibility = View.GONE
    }

    private fun showRetryState() {
        setContentView(R.layout.activity_startup)
        findViewById<ProgressBar>(R.id.startupProgress).visibility = View.GONE
        findViewById<TextView>(R.id.startupStatus).setText(R.string.auth_session_check_failed)
        findViewById<Button>(R.id.startupRetry).apply {
            visibility = View.VISIBLE
            setOnClickListener { checkSavedSession() }
        }
    }

    private fun showLogin() {
        if (!isFinishing && !isDestroyed) {
            setContentView(R.layout.activity_intro)
        }
    }

    private fun openMain() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            intent?.extras?.let { putExtras(it) }
        }
        startActivity(mainIntent)
        finish()
    }
}
