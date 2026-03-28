package com.mina.moonchat.base

import com.mina.moonchat.intro_activity.ui.login.LoggedInUserView

data class Result(
    val success: LoggedInUserView? = null,
    val error: Int? = null
)
