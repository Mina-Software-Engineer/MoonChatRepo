package com.mina.moonchat.ui.fragments

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.R
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentProfileBinding
import com.mina.moonchat.ui.activities.IntroActivity
import com.mina.moonchat.viewmodels.ProfileViewModel
import org.koin.android.ext.android.inject

class ProfileFragment : BaseFragment() {


    private lateinit var _binding: FragmentProfileBinding
    val binding get() = _binding
    override val _viewModel: ProfileViewModel by inject()


    private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //                      mAuth = FirebaseAuth.getInstance()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentProfileBinding.inflate(inflater, container, false)


        binding.viewModel = _viewModel
        binding.nameContainer.setOnClickListener {
            showEditTextDialog(binding.tvName)
        }

        binding.emailContainer.setOnClickListener {
            showEditTextDialog(binding.tvEmail)
        }

        binding.aboutContainer.setOnClickListener {
            showEditTextDialog(binding.tvBio)
        }

        binding.circleImageViewProfileImage.setOnClickListener {
            checkPermission()
        }

        _viewModel.pfpImage.observe(viewLifecycleOwner, Observer { uri ->
            if (!uri?.toString().isNullOrBlank()) {
                Glide.with(this)
                    .load(uri)
                    .placeholder(R.drawable.ic_account_circle)
                    .error(R.drawable.ic_account_circle)
                    .into(binding.circleImageViewProfileImage)
            } else {
                binding.circleImageViewProfileImage.setImageResource(R.drawable.ic_account_circle)
            }
        })

        _viewModel.tvName.observe(viewLifecycleOwner, Observer { name ->
            binding.tvName.text = name
        })

        _viewModel.tvEmail.observe(viewLifecycleOwner, Observer { email ->
            binding.tvEmail.text = email
        })

        _viewModel.tvBio.observe(viewLifecycleOwner, Observer { bio ->
            binding.tvBio.text = bio
        })

        _viewModel.tvUserID.observe(viewLifecycleOwner, Observer { userID ->
            binding.userId.text = userID
        })

        _viewModel.tvFriendsCount.observe(viewLifecycleOwner, Observer { friendsCountText ->
            binding.tvFriendsCount.text = friendsCountText
        })

        _viewModel.fetchFriendsCount()

        binding.signOut.setOnClickListener{
            mAuth.signOut()
            _viewModel.deleteCurrentUserFromDB()
            goToIntroScreen()
        }

        binding.shareBtn.setOnClickListener {
            val sendIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, binding.userId.text)
                type = "text/plain"
            }

            val shareIntent = Intent.createChooser(sendIntent, "This is my UserID, add me now in moon chat!")
            startActivity(shareIntent)

        }

        _viewModel.getCurrentUserFromDB()

        return binding.root
    }

    private fun goToIntroScreen() {
        val intentToIntro = Intent(requireActivity(), IntroActivity::class.java)
        startActivity(intentToIntro)
        requireActivity().finish()
        Toast.makeText(requireContext(), "User is is signed out successfully", Toast.LENGTH_LONG).show()
    }

    private fun checkPermission() {
        if (hasPermission()) {
            openGallery()
        } else {
            requestExternalStorageAccess()
        }
    }

    private fun hasPermission(): Boolean =
        ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    //a result of the picked image
    private val resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK && it.data != null && it.data!!.data != null) {
                //binding.circleImageViewProfileImage.setImageURI(it.data!!.data)
                if (hasPermission()) {
                    _viewModel.setPfpPhoto(it.data!!.data!!)
                }
                else {
                    requestExternalStorageAccess()
                }
            }
        }

    //a result for accessing external storage
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!it) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        requireActivity(),
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                ) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.permission_needed_title)
                        .setMessage(R.string.permission_denied_explanation)
                        .setPositiveButton(
                            "OK"
                        ) { _, _ ->

                        }.create().show()
                } else {
                    Snackbar.make(
                        binding.profileScreen,
                        R.string.permission_err,
                        Snackbar.LENGTH_LONG
                    )
                        .setAction("Settings") {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:com.mina.moonchat")
                            startActivity(intent)
                        }.show()
                }
            } else {
                //openGallery()
            }
        }


    private fun openGallery() {
        val gallery = Intent(Intent.ACTION_PICK)
        gallery.type = "image/*"
        resultLauncher.launch(gallery)
    }

    private fun requestExternalStorageAccess() {
        permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }


    private fun showEditTextDialog(textView: TextView) {

        val builder = AlertDialog.Builder(requireActivity())
        val inflater = requireActivity().layoutInflater
        val dialogLayout = inflater.inflate(R.layout.edit_text_layout, null)
        val editText = dialogLayout.findViewById<EditText>(R.id.et_editText)

        with(builder) {
            when (textView) {
                binding.tvName -> {
                    setTitle("Enter your Name!")
                    editText.setText(binding.tvName.text)
                }
                binding.tvBio -> {
                    setTitle("Enter your Bio!")
                    editText.setText(binding.tvBio.text)
                }
                binding.tvEmail -> {
                    setTitle("Enter your Email!")
                    editText.setText(binding.tvEmail.text)
                }
                else -> setTitle("")
            }

            when (textView) {
                binding.tvName -> {
                    setPositiveButton("Confirm") { dialog, which ->
                        _viewModel.updateCurrentUserInfo("name", editText.text.toString())
                    }
                }
                binding.tvBio -> {
                    setPositiveButton("Confirm") { dialog, which ->
                        _viewModel.updateCurrentUserInfo("bio", editText.text.toString())
                    }
                }
                binding.tvEmail -> {
                    setPositiveButton("Confirm") { dialog, which ->
                        _viewModel.updateCurrentUserInfo("email", editText.text.toString())

                    }
                }
                else -> setTitle("")
            }

            setNegativeButton("Cancel") { dialog, which ->

            }
            setView(dialogLayout)
            show()
        }
    }
}
