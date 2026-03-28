package com.mina.moonchat.models

import android.os.Parcelable
import com.mina.moonchat.data.dto.AuthUserDTO
import kotlinx.android.parcel.Parcelize

/**
 * Data class that captures user information for logged in user
 */
@Parcelize
data class User(
    val userId: String,
    val displayName: String,
    val email: String,
    val password: String,
    val profileImg: String?,
    val onlineState: String,
    val bio: String,
    val id: String
): Parcelable{
    constructor(): this("", "", "", "", null, "", "", "")
}

//converting the Asteroid Domain Model list to Asteroid Entity list
fun List<User>.asAsteroidEntityModel(): List<AuthUserDTO> {
    return map {
        AuthUserDTO(
        userId = it.userId,
        username = it.displayName,
        email = it.email,
        password = it.password,
        pfp = it.profileImg,
        onlineStatus = it.onlineState,
        bio = it.bio,
        id = it.id
        )
    }
}

//converting the Asteroid Entity list to Asteroid Domain Model list
/*
fun List<AsteroidEntity>.asDomainModel(): List<Asteroid> {
    return map {
        Asteroid(
            id = it.id,
            codename = it.codename,
            closeApproachDate = it.date,
            absoluteMagnitude = it.absoluteMagnitude,
            estimatedDiameter = it.estimatedDiameter,
            relativeVelocity = it.relativeVelocity,
            distanceFromEarth = it.distance,
            isPotentiallyHazardous = it.isPotentiallyHazardous
        )
    }*/
