package dev.aaa1115910.biliapi.http.entity.login

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PreCaptureData(
    @SerialName("gee_gt")
    val geeGt: String = "",
    @SerialName("gee_challenge")
    val geeChallenge: String = "",
    @SerialName("recaptcha_token")
    val recaptchaToken: String = ""
)