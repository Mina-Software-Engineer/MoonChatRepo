package com.mina.moonchat.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.BuildConfig
import com.mina.moonchat.R
import com.mina.moonchat.models.TextMessage
import java.io.File

class ImageViewerDialog(
    context: Context,
    private val message: TextMessage,
    private val currentUserId: String
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_image_viewer)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val rootLayout = findViewById<View>(R.id.dialog_root)
        val fullImageView = findViewById<ImageView>(R.id.image_view_full_screen)
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar_loading)
        val closeButton = findViewById<ImageButton>(R.id.button_close)

        closeButton.setOnClickListener { dismiss() }
        rootLayout.setOnClickListener { dismiss() }
        fullImageView.setOnClickListener { dismiss() }

        loadImage(fullImageView, progressBar)
    }

    private fun loadImage(imageView: ImageView, progressBar: ProgressBar) {
        val localFile = message.localImagePath?.let(::File)
        if (localFile?.isFile == true) {
            Glide.with(context)
                .load(localFile)
                .fitCenter()
                .listener(createRequestListener(progressBar))
                .into(imageView)
            return
        }

        val baseUrl = BuildConfig.CHAT_IMAGE_WORKER_URL.trimEnd('/')
        val user = FirebaseAuth.getInstance().currentUser
        if (baseUrl.isBlank() || user == null || message.media == null) {
            progressBar.visibility = View.GONE
            imageView.setImageResource(R.drawable.ic_image_message)
            return
        }

        val peerId = if (message.senderId == currentUserId) message.recipientId else message.senderId
        val imageUrl = "$baseUrl/v1/chat-images/${Uri.encode(message.id)}/content?peerId=${Uri.encode(peerId)}"

        user.getIdToken(false).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                progressBar.visibility = View.GONE
                imageView.setImageResource(R.drawable.ic_image_message)
                return@addOnCompleteListener
            }

            val token = task.result?.token
            if (token.isNullOrBlank()) {
                progressBar.visibility = View.GONE
                imageView.setImageResource(R.drawable.ic_image_message)
                return@addOnCompleteListener
            }

            val headers = LazyHeaders.Builder()
                .addHeader("Authorization", "Bearer $token")
                .build()

            Glide.with(context)
                .load(GlideUrl(imageUrl, headers))
                .fitCenter()
                .listener(createRequestListener(progressBar))
                .into(imageView)
        }
    }

    private fun createRequestListener(progressBar: ProgressBar): RequestListener<Drawable> {
        return object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>?,
                isFirstResource: Boolean
            ): Boolean {
                progressBar.visibility = View.GONE
                return false
            }

            override fun onResourceReady(
                resource: Drawable?,
                model: Any?,
                target: Target<Drawable>?,
                dataSource: DataSource?,
                isFirstResource: Boolean
            ): Boolean {
                progressBar.visibility = View.GONE
                return false
            }
        }
    }

    companion object {
        fun show(context: Context, message: TextMessage, currentUserId: String) {
            ImageViewerDialog(context, message, currentUserId).show()
        }
    }
}
