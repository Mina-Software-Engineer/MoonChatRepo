package com.mina.moonchat.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.Observer
import com.mina.moonchat.ui.activities.MainActivity
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentSignUpBinding
import com.mina.moonchat.viewmodels.SignUpViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel


class SignUpFragment : BaseFragment() {
    override val _viewModel: SignUpViewModel by inject()

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        binding.viewModel = _viewModel
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)




        val afterTextChangedListener = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                // ignore
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(s: Editable) {
                /*binding.btnSignUp.isEnabled = binding.editTextNameSignup.text.toString().trim().isNotEmpty()
                        && binding.editTextEmailSignup.text.toString().trim().isNotEmpty()
                        && binding.editTextPasswordSignup.text.toString().trim().isNotEmpty()*/
            }
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

        _viewModel.formState.observe(viewLifecycleOwner, Observer{ state ->
            if (state == null) {
                return@Observer
            } else if (state.usernameError != null){
                binding.editTextNameSignupContainer.error = resources.getString(state.usernameError)
                binding.editTextNameSignup.requestFocus()
            } else if (state.emailError != null){
                binding.editTextEmailSignupContainer.error = resources.getString(state.emailError)
                binding.editTextEmailSignup.requestFocus()
            }else if(state.passwordError != null){
                binding.passwordTextInput.error = resources.getString(state.passwordError)
            }else{
                //binding.btnSignUp.isEnabled = state.isDataValid
                val intentToMain = Intent(requireContext(), MainActivity::class.java)
                intentToMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intentToMain)
            }
        })

        binding.btnSignUp.setOnClickListener {

            _viewModel.signupDataChanged(
                binding.editTextNameSignup.text.toString().trim(),
                binding.editTextEmailSignup.text.toString().trim(),
                binding.editTextPasswordSignup.text.toString().trim()
            )
            /*_viewModel.createNewAccount(
                binding.editTextNameSignup.text.toString(),
                binding.editTextEmailSignup.text.toString(),
                binding.editTextPasswordSignup.text.toString())
                */



        }
    }



}