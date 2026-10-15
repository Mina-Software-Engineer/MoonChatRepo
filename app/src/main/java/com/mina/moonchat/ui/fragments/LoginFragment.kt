package com.mina.moonchat.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionInflater
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.R
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.base.NavigationCommand
import com.mina.moonchat.databinding.FragmentLoginBinding
import com.mina.moonchat.ui.activities.MainActivity
import com.mina.moonchat.viewmodels.EmailVerificationState
import com.mina.moonchat.viewmodels.LoginViewModel
import com.mina.moonchat.viewmodels.PasswordResetState
import org.koin.android.ext.android.inject

class LoginFragment : BaseFragment() {
    override val _viewModel: LoginViewModel by inject()

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        binding.viewModel = _viewModel

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
        val forgotPasswordBtn = binding.forgotPasswordBtn

        fun setLoadingState(isLoading: Boolean) {
            binding.loading.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.login.isEnabled = !isLoading && (_viewModel.formState.value?.isDataValid == true)
            binding.forgotPasswordBtn.isEnabled = !isLoading
            binding.signupBtn.isEnabled = !isLoading
        }

        val animFade = AnimationUtils.loadAnimation(context, androidx.appcompat.R.anim.abc_fade_in)
        binding.container.startAnimation(animFade)
        animFade.duration = 2500

        _viewModel.formState.observe(viewLifecycleOwner) { loginFormState ->
            if (loginFormState == null) return@observe
            loginButton.isEnabled = loginFormState.isDataValid
            loginFormState.usernameError?.let {
                binding.editTextEmailLogin.error = getString(it)
            }
            loginFormState.passwordError?.let {
                binding.passwordTextInput.error = getString(it)
            }
        }

        _viewModel.progressBar.observe(viewLifecycleOwner) { isLoading ->
            setLoadingState(isLoading)
        }

        _viewModel.loginErrorResId.observe(viewLifecycleOwner) { errorResId ->
            errorResId?.let {
                Toast.makeText(requireContext(), getString(it), Toast.LENGTH_LONG).show()
                _viewModel.clearLoginError()
            }
        }

        _viewModel.loginSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                val intentToMain = Intent(requireActivity(), MainActivity::class.java)
                startActivity(intentToMain)
                requireActivity().finish()
            }
        }

        _viewModel.passwordResetState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is PasswordResetState.Idle -> {
                    setLoadingState(false)
                }
                is PasswordResetState.Loading -> {
                    setLoadingState(true)
                }
                is PasswordResetState.Success -> {
                    setLoadingState(false)
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.reset_password_title)
                        .setMessage(getString(state.messageResId))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    _viewModel.clearPasswordResetState()
                }
                is PasswordResetState.Error -> {
                    setLoadingState(false)
                    Toast.makeText(requireContext(), getString(state.messageResId), Toast.LENGTH_LONG).show()
                    _viewModel.clearPasswordResetState()
                }
            }
        }

        _viewModel.emailVerificationState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is EmailVerificationState.Idle -> {
                    // Do nothing
                }
                is EmailVerificationState.Loading -> {
                    setLoadingState(true)
                }
                is EmailVerificationState.Unverified -> {
                    setLoadingState(false)
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.email_verification_sent_title)
                        .setMessage(getString(R.string.email_not_verified_error))
                        .setPositiveButton(R.string.resend_verification_email) { _, _ ->
                            _viewModel.resendVerificationEmail(state.user)
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            FirebaseAuth.getInstance().signOut()
                            _viewModel.clearEmailVerificationState()
                        }
                        .setOnCancelListener {
                            FirebaseAuth.getInstance().signOut()
                            _viewModel.clearEmailVerificationState()
                        }
                        .show()
                }
                is EmailVerificationState.ResentSuccess -> {
                    setLoadingState(false)
                    Toast.makeText(requireContext(), getString(state.messageResId), Toast.LENGTH_LONG).show()
                    _viewModel.clearEmailVerificationState()
                }
                is EmailVerificationState.Error -> {
                    setLoadingState(false)
                    Toast.makeText(requireContext(), getString(state.messageResId), Toast.LENGTH_LONG).show()
                    _viewModel.clearEmailVerificationState()
                }
            }
        }

        val afterTextChangedListener = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
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

        forgotPasswordBtn.setOnClickListener {
            if (_viewModel.passwordResetState.value is PasswordResetState.Loading) return@setOnClickListener
            showPasswordResetDialog(emailEditText.text.toString().trim())
        }

        binding.signupBtn.setOnClickListener {
            navigateToSignUp()
        }
    }

    private fun showPasswordResetDialog(currentEmail: String) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.email)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(currentEmail)
            setSelection(text.length)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_password_title)
            .setMessage(R.string.reset_password_instructions)
            .setView(container)
            .setPositiveButton(R.string.send_reset_link, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = input.text.toString()
                if (!LoginViewModel.isValidEmail(email)) {
                    input.error = getString(R.string.invalid_Email)
                    return@setOnClickListener
                }
                _viewModel.sendPasswordResetEmail(email)
                if (_viewModel.passwordResetState.value is PasswordResetState.Loading) {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun navigateToSignUp() {
        _viewModel.navigationCommand.value =
            NavigationCommand.To(LoginFragmentDirections.actionLoginFragmentToSignUpFragment())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
