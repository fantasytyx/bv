package dev.aaa1115910.biliapi.http.entity.login

import kotlinx.serialization.Serializable

@Serializable
data class LoginWebKeyData(
    val hash: String = "",
    val key: String = ""
)