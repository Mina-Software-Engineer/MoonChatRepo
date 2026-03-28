package com.mina.moonchat.intro_activity.data

import com.mina.moonchat.models.User
import java.io.IOException
/*

*/
/**
 * Class that handles authentication w/ login credentials and retrieves user information.
 *//*


//To Perform a random user id
*/
/** UUID.randomUUID().toString() *//*

class LoginDataSource {

    fun login(username: String, password: String): Result<User> {
        */
/*try {
            // TODO: handle loggedInUser authentication
            val fakeUser = LoggedInUser(UUID.randomUUID().toString(), "Jane Doe")
            return Result.Success(fakeUser)
        } catch (e: Throwable) {
            return Result.Error(IOException("Error logging in", e))
        }*//*

        return Result.Error(IOException("Error"))
    }

    fun logout() {
        // TODO: revoke authentication
    }
}*/
