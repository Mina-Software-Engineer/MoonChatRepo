package com.mina.moonchat.viewmodels

import android.app.Application
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel

class FriendsViewModel(app: Application ): BaseViewModel(
    app,
    (app as MoonChat).repository,
    (app as MoonChat).repo
){


    /*private val _itemRefresh = MutableLiveData<List<User>>()
    val itemRefresh: LiveData<List<User>> = _itemRefresh


    fun refreshList(user: List<User>){
        _itemRefresh.value = user
    }*/
}