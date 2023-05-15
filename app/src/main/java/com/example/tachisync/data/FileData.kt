package com.example.tachisync.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

data class FileData(val name: String, val id: String, val parent: String, val mimeType: String) {
    constructor(jsonObject: JsonObject) : this(
        jsonObject["name"].toString().replace("\"",""),
        jsonObject["id"].toString().replace("\"",""),
        if (jsonObject["parents"] != null && jsonObject["parents"]!!.jsonArray.isNotEmpty())
            jsonObject["parents"]!!.jsonArray[0].toString().replace("\"","")
        else
            "",
        jsonObject["mimeType"].toString()) {
    }
}
