package com.mina.moonchat.base

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.databinding.library.baseAdapters.BR
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView

abstract class BaseRecyclerViewAdapter<T>(private val callback: ((item: T) -> Unit)? = null) :
    RecyclerView.Adapter<DataBindingViewHolder<T>>() {
    private var _items: MutableList<T> = mutableListOf()

    private val items: List<T>?
        get() = this._items

    override fun getItemCount() = _items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DataBindingViewHolder<T> {
        val layoutInflater = LayoutInflater.from(parent.context)

        val binding = DataBindingUtil
            .inflate<ViewDataBinding>(layoutInflater, getLayoutRes(viewType), parent, false)

        binding.lifecycleOwner = getLifecycleOwner()

        return DataBindingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DataBindingViewHolder<T>, position: Int) {
        val item = getItem(position)
        holder.bind(item)


        //Log.d("check", "${getItem(position) as ReminderDataSource}")

        holder.itemView.setOnClickListener {
            callback?.invoke(item)
        }
    }

    fun getItem(position: Int) = _items[position]

    /**
     * Adds data to the actual Dataset
     *
     * @param items to be merged
     */
    @SuppressLint("NotifyDataSetChanged")
    fun addData(items: List<T>) {
        _items.addAll(items)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clear() {
        _items.clear()
        notifyDataSetChanged()
    }

    @LayoutRes
    abstract fun getLayoutRes(viewType: Int): Int

    open fun getLifecycleOwner(): LifecycleOwner? {
        return null
    }

}


class DataBindingViewHolder<T>(private val binding: ViewDataBinding) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind(item: T) {
        binding.setVariable(BR.clickListener, item)
        binding.executePendingBindings()

        //Log.d("checkk", BR.item.toString())
    }
}