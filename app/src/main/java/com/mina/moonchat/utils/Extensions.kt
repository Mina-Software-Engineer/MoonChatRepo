package com.mina.moonchat.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.text.format.DateUtils.isToday
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mina.moonchat.R
import com.mina.moonchat.base.BaseRecyclerViewAdapter
import java.text.SimpleDateFormat
import java.util.*

//animate changing the view visibility
fun View.fadeIn() {
    this.visibility = View.VISIBLE
    this.alpha = 0f
    this.animate().alpha(1f).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            this@fadeIn.alpha = 1f
        }
    })
}

//animate changing the view visibility
fun View.fadeOut() {
    this.animate().alpha(0f).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            this@fadeOut.alpha = 1f
            this@fadeOut.visibility = View.GONE
        }
    })
}

/**
 * Extension function to setup the RecyclerView
 */
fun <T> RecyclerView.setup(
    adapter: BaseRecyclerViewAdapter<T>
) {

    this.apply {
        val linearLayout = LinearLayoutManager(this.context)
        linearLayout.stackFromEnd = true
        layoutManager = LinearLayoutManager(this.context)
        this.adapter = adapter
        setHasFixedSize(true)
    }
}

/*
fun Date.formatAsHeader(context: Context): String{
    return when{
        isToday() -> context.getString(R.string.today)
        isYesterday() -> formatAsYesterDay(context)
        isThisWeek() -> formatASWeekDay(context)
        isThisYear() -> {
            SimpleDateFormat("d LLLL", getCurrntLocale(context)).format(this)
        }
        else -> formateAsFull(context, abbreviated == false)
    }
}*/
