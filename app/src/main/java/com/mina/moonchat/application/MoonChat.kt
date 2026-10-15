package com.mina.moonchat.application

import android.app.Application
import com.mina.moonchat.data.local.UserDatabase
import com.mina.moonchat.data.server.ServerSide
import com.mina.moonchat.data.server.UserLocalRepository
import com.mina.moonchat.viewmodels.*
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

class MoonChat : Application() {

    companion object {
        lateinit var instance: MoonChat
            private set
    }

    val database by lazy { UserDatabase.getDatabase(this) }
    private val server by lazy { ServerSide() }
    val repo by lazy { UserLocalRepository(database, server) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        com.mina.moonchat.utils.NotificationHelper.createNotificationChannel(this)

        val myModule = module {
            single { ProfileViewModel(get()) }
            single { SignUpViewModel(get()) }
            single { FriendsViewModel(get()) }
            viewModel { ChatViewModel(get()) }
            single { LoginViewModel(get()) }
            viewModel { ChatListViewModel(get()) }
        }

        startKoin {
            androidContext(this@MoonChat)
            modules(listOf(myModule))
        }
    }
}
