package com.mina.moonchat.base

/**
 * Data validation state of the Login/Sign up form.
 */

data class FormState(
    val usernameError: Int? = null,
    val emailError: Int? = null,
    val passwordError: Int? = null,
    val isDataValid: Boolean = false
)
