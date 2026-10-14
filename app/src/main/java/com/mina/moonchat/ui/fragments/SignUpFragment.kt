package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import com.mina.moonchat.R
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentSignUpBinding
import com.mina.moonchat.viewmodels.SignUpState
import com.mina.moonchat.viewmodels.SignUpViewModel
import org.koin.android.ext.android.inject

class SignUpFragment : BaseFragment() {
    override val _viewModel: SignUpViewModel by inject()

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        binding.viewModel = _viewModel
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val afterTextChangedListener = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {}
        }

        binding.editTextNameSignup.addTextChangedListener(afterTextChangedListener)
        binding.editTextEmailSignup.addTextChangedListener(afterTextChangedListener)
        binding.editTextPasswordSignup.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                _viewModel.signupDataChanged(
                    binding.editTextNameSignup.text.toString().trim(),
                    binding.editTextEmailSignup.text.toString().trim(),
                    binding.editTextPasswordSignup.text.toString().trim()
                )
            }
            false
        }

        _viewModel.formState.observe(viewLifecycleOwner) { state ->
            if (state == null) return@observe
            binding.editTextNameSignupContainer.error = null
            binding.editTextEmailSignupContainer.error = null
            binding.passwordTextInput.error = null

            if (state.usernameError != null) {
                binding.editTextNameSignupContainer.error = getString(state.usernameError)
                binding.editTextNameSignup.requestFocus()
            } else if (state.emailError != null) {
                binding.editTextEmailSignupContainer.error = getString(state.emailError)
                binding.editTextEmailSignup.requestFocus()
            } else if (state.passwordError != null) {
                binding.passwordTextInput.error = getString(state.passwordError)
                binding.editTextPasswordSignup.requestFocus()
            }
        }

        _viewModel.signUpState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is SignUpState.Idle -> {
                    binding.btnSignUp.isEnabled = true
                }
                is SignUpState.Loading -> {
                    binding.btnSignUp.isEnabled = false
                }
                is SignUpState.VerificationPending -> {
                    binding.btnSignUp.isEnabled = true
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.email_verification_sent_title)
                        .setMessage(getString(R.string.email_verification_instructions))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            findNavController().popBackStack()
                        }
                        .setOnCancelListener {
                            findNavController().popBackStack()
                        }
                        .show()
                }
                is SignUpState.Error -> {
                    binding.btnSignUp.isEnabled = true
                    Toast.makeText(requireContext(), getString(state.messageResId), Toast.LENGTH_LONG).show()
                    _viewModel.clearSignUpState()
                }
            }
        }

        binding.btnSignUp.setOnClickListener {
            _viewModel.submitSignUp(
                binding.editTextNameSignup.text.toString().trim(),
                binding.editTextEmailSignup.text.toString().trim(),
                binding.editTextPasswordSignup.text.toString().trim()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
