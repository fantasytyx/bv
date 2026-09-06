package dev.aaa1115910.biliapi.repositories

import org.koin.core.annotation.Single

@Single
class AuthRepository {
    var sessionData: String? = null
    var biliJct: String? = null
    var dedeUserIDCkMd5: String? = null
    var sid: String? = null
    var accessToken: String? = null
    var mid: Long? = null
    var buvid3: String? = null
    var buvid: String? = null
    /** 风控验证通过后的 gaia_vtoken（来自 grisk_id） */
    var gaiaVtoken: String? = null
}