package com.mina.moonchat.viewmodels

import ChatPagingSource
import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.models.ChatItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatListViewModel(app: Application): BaseViewModel(
    app,
    (app as MoonChat).repository,
    (app as MoonChat).repo
) {


    private val _chatFlow = MutableStateFlow<PagingData<ChatItem>>(PagingData.empty())
    val chatFlow: StateFlow<PagingData<ChatItem>> = _chatFlow.asStateFlow()

    init {
        fetchChats()
    }

    private fun fetchChats() {
        viewModelScope.launch {
            Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                pagingSourceFactory = { ChatPagingSource(mAuth.currentUser!!.uid) }
            ).flow.collectLatest {
                _chatFlow.value = it
            }
        }
    }


    /*fun listenForNewChats(): List<ChatItem> {
        return localRepo.getLastChats(mAuth.currentUser!!.uid)
    }*/


}