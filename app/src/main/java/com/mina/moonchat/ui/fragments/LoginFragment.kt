package com.mina.moonchat.ui.fragments

import android.content.Intent
import androidx.lifecycle.Observer
import androidx.annotation.StringRes
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionInflater
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.ui.activities.MainActivity
import com.mina.moonchat.R
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.base.NavigationCommand
import com.mina.moonchat.databinding.FragmentLoginBinding
import com.mina.moonchat.intro_activity.ui.login.LoggedInUserView
import com.mina.moonchat.viewmodels.LoginViewModel
import org.koin.android.ext.android.inject


class LoginFragment : BaseFragment() {
    override val _viewModel: LoginViewModel by inject()

    private var _binding: FragmentLoginBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!


    override fun onStart() {
        super.onStart()
        checkAuthentication()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        binding.viewModel = _viewModel

        //Logo received animation
        val animation =
            TransitionInflater.from(context).inflateTransition(android.R.transition.move)
        animation.duration = 600
        sharedElementEnterTransition = animation
        sharedElementReturnTransition = animation

        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        val emailEditText = binding.username
        val passwordEditText = binding.password
        val loginButton = binding.login
        val loadingProgressBar = binding.loading

        val animFade = AnimationUtils.loadAnimation(context, androidx.appcompat.R.anim.abc_fade_in)
        binding.container.startAnimation(animFade)
        animFade.duration = 2500

        _viewModel.formState.observe(viewLifecycleOwner, Observer { loginFormState ->

                if (loginFormState == null) {
                    return@Observer
                }
                loginButton.isEnabled = loginFormState.isDataValid
                loginFormState.usernameError?.let {
                    binding.editTextEmailLogin.error = getString(it)
                }
                loginFormState.passwordError?.let {
                    binding.passwordTextInput.error = getString(it)
                }
            })

        _viewModel.progressBar.observe(viewLifecycleOwner, Observer { state ->
            if (state) {
                binding.loading.visibility = View.VISIBLE
            } else {
                binding.loading.visibility = View.GONE
            }
        })

        _viewModel.result.observe(viewLifecycleOwner, Observer { loginResult ->

                loginResult ?: return@Observer
                loadingProgressBar.visibility = View.GONE
                loginResult.error?.let {
                    showLoginFailed(it)
                }
                loginResult.success?.let {
                    updateUiWithUser(it)
                }
            })

        val afterTextChangedListener = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                // ignore
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                // ignore
            }

            override fun afterTextChanged(s: Editable) {
                _viewModel.loginDataChanged(
                    emailEditText.text.toString(),
                    passwordEditText.text.toString()
                )
            }
        }
        emailEditText.addTextChangedListener(afterTextChangedListener)
        passwordEditText.addTextChangedListener(afterTextChangedListener)
        passwordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                _viewModel.login(
                    emailEditText.text.toString(),
                    passwordEditText.text.toString()
                )
            }
            false
        }

        loginButton.setOnClickListener {
            _viewModel.login(
                emailEditText.text.toString(),
                passwordEditText.text.toString()
            )
        }

        binding.signupBtn.setOnClickListener {
            navigateToSignUp()
        }
    }

    private fun navigateToSignUp() {
        _viewModel.navigationCommand.value =
            NavigationCommand.To(LoginFragmentDirections.actionLoginFragmentToSignUpFragment())
    }

    private fun updateUiWithUser(model: LoggedInUserView) {
        val welcome = getString(R.string.welcome) + model.displayName
        // TODO : initiate successful logged in experience
        val appContext = context?.applicationContext ?: return
        Toast.makeText(appContext, welcome, Toast.LENGTH_LONG).show()
    }

    private fun showLoginFailed(@StringRes errorString: Int) {
        val appContext = context?.applicationContext ?: return
        Toast.makeText(appContext, errorString, Toast.LENGTH_LONG).show()
    }

    private fun checkAuthentication() {
        if (FirebaseAuth.getInstance().currentUser?.uid != null) {
            val intentToMain = Intent(requireActivity(), MainActivity::class.java)
            startActivity(intentToMain)
            requireActivity().finish()
            Log.d("check", "User already signed in")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}