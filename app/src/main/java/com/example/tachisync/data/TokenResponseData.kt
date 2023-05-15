package com.example.tachisync.data

import kotlinx.serialization.json.JsonObject

data class TokenResponseData(val accessToken: String, val refreshToken: String, val expiresIn: Int) {
    constructor(jsonObject: JsonObject) : this(jsonObject["access_token"].toString(),
        jsonObject["refresh_token"].toString(),
        jsonObject["expires_in"].toString().toInt()) {
    }
}