package com.mina.moonchat.application

import android.app.Application
import com.mina.moonchat.data.UserLocalRepository1
import com.mina.moonchat.data.local.UserDatabase
import com.mina.moonchat.data.server.ServerSide
import com.mina.moonchat.data.server.UserLocalRepository
import com.mina.moonchat.viewmodels.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class MoonChat : Application() {

    companion object {
        lateinit var instance: MoonChat
            private set
    }

    val database by lazy { UserDatabase.getDatabase(this) }
    private val server by lazy { ServerSide() }
    val repository by lazy { UserLocalRepository1(database.authDao(), database.userDao(), database.messageDao(), server) }
    val repo by lazy { UserLocalRepository(database, server) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        /**
         * use Koin Library as a service locator
         */
        val myModule = module {


            //single { LocalDB.createUserDao(this@MoonChat) }

            /*single {
                UserLocalRepository(
                    currentUserDao = get() as AuthDao,
                    userDao = get() as UserDao
                ) as UserDataSource
            }*/

            single {
                ProfileViewModel(
                    get()
                )
            }
            single {
                SignUpViewModel(
                    get()
                )
            }

            single {
                FriendsViewModel(
                    get()
                )
            }

            single {
                ChatViewModel(
                    get()
                )
            }

            //Declare a ViewModel - be later inject into Fragment with dedicated injector using by viewModel()
            single {
                LoginViewModel(
                    get()
                )
            }

            single {
                ChatListViewModel(
                    get()
                )
            }
        }

        startKoin {
            androidContext(this@MoonChat)
            modules(listOf(myModule))
        }
    }
}