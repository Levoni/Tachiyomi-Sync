package com.example.tachisync.service

import android.content.ContentResolver
import android.content.ContentResolver.MimeTypeInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat.startActivity
import androidx.core.net.toFile
import androidx.documentfile.provider.DocumentFile
import com.example.tachisync.data.SettingsViewModel
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.EmptyContent.contentLength
import io.ktor.client.utils.EmptyContent.contentType
import io.ktor.http.*
import io.ktor.http.cio.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.sql.Time
import java.time.Instant

public class GoogleService {

    companion object {

        val client = HttpClient()
        private var accessToken: String = ""
        private var tokenExpiration = Time.from(Instant.now())

        fun AuthorizeWithSignOnPage(context: Context) {
            var url: String =
                "https://accounts.google.com/o/oauth2/auth?scope=https://www.googleapis.com/auth/drive" +
                        "&response_type=code" +
                        "&access_type=offline" +
                        "&redirect_uri=com.example.tachisync:/callback" +
                        "&client_id=213487956564-b79if896fihvd8v2vljlmt0qq7pj37am.apps.googleusercontent.com"
            var i: Intent = Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url))
            startActivity(context, i, null)
        }

        fun GetDriveRefreshTokenAndAccessToken(authorizationCode: String, context: Context, sharedViewModel: SettingsViewModel) {
            GlobalScope.launch {
                val response: HttpResponse = client.request(
                    "https://oauth2.googleapis.com/token" +
                            "?code=${authorizationCode}}" +
                            "&redirect_uri=com.example.tachisync:/callback" +
                            "&client_id=213487956564-b79if896fihvd8v2vljlmt0qq7pj37am.apps.googleusercontent.com" +
                            //"&client_secret=************" +
                            "&scope=" +
                            "&grant_type=authorization_code"
                ) {
                    method = HttpMethod.Post
                }
                if(response.status.value in 200..299) {
                    val stringBody: String = response.bodyAsText()
                    val jsonObject = Json.parseToJsonElement(stringBody).jsonObject
                    val accessToken = jsonObject["access_token"]
                    val refresh_token = jsonObject["refresh_token"]
                    val expireIn = jsonObject["expires_in"]
                    tokenExpiration = Time.from(Instant.now().plusSeconds(expireIn.toString().toLong()))
                    sharedViewModel.driveRefreshToken = refresh_token.toString()
                    sharedViewModel.setPreferences_async(context)
                    Toast.makeText(context,"Authorization Successful", Toast.LENGTH_LONG).show()
                }
            }
        }

        //TODO: test if this method returns a value
        suspend fun ResetAuthorizationWithRefreshToken(context: Context, sharedViewModel: SettingsViewModel) = GlobalScope.async(Dispatchers.Main) {
            if (accessToken == "" && tokenExpiration.before(Time.from(Instant.now()))) {
                if (sharedViewModel.driveRefreshToken != null) {
                    GlobalScope.launch {
                        val response: HttpResponse = client.request(
                            "https://oauth2.googleapis.com" +
                                    "?grant_type=refresh_token" +
                                    "&${sharedViewModel.driveRefreshToken}"
                        ) {
                            method = HttpMethod.Post
                        }
                        if (response.status.value in 200..299) {
                            val stringBody: String = response.bodyAsText()
                            val jsonObject = Json.parseToJsonElement(stringBody).jsonObject
                            val token = jsonObject["access_token"]
                            val expireIn = jsonObject["expires_in"]
                            tokenExpiration =
                                Time.from(Instant.now().plusSeconds(expireIn.toString().toLong()))
                            accessToken = token.toString()
                            true
                        }
                    }
                } else {
                    Toast.makeText(
                        context,
                        "Please Authorize Google Drive Access First.",
                        Toast.LENGTH_LONG
                    ).show()

                    false
                }
            }
        }

        suspend fun VerifyAndCreateDriveFolders(context: Context, sharedViewModel: SettingsViewModel): String = withContext(Dispatchers.Default) {
            var directorySegments = sharedViewModel.driveDirectory.toString().split("\"")
            val one = ResetAuthorizationWithRefreshToken(context,sharedViewModel).await()
            //TODO: see if this is actually returning a bool
            if(one as Boolean) {
                var parentId = ""
                val success = async { withContext(Dispatchers.Default) {
                    for (segment in directorySegments) {
                        val response: HttpResponse = client.request(
                            "https://www.googleapis.com/drive/v3/files" +
                                    "?q=name='${segment}'and mimeType='application/vnd.google-apps.folder'" +
                                    "&fields=files(id,name,parents,mimeType)"
                        ) {
                            method = HttpMethod.Post
                            headers {
                                bearerAuth(accessToken)
                                contentType(ContentType.Any)
                            }
                        }
                        if (response.status.value in 200..299) {
                            val stringBody: String = response.bodyAsText()
                            val jsonArray = Json.parseToJsonElement(stringBody).jsonArray
                            if(jsonArray.isNotEmpty()) {
                                val jsonObject = jsonArray[0].jsonObject
                                if(parentId != "" && parentId != jsonObject["Parent"].toString()) {
                                    parentId = jsonObject["Parent"].toString() // so new folder has proper parent id
                                    //TODO: Create Folder and set parent id
                                    Toast.makeText(context,"Folder Path doesn't exist", Toast.LENGTH_LONG).show()
                                    return@withContext false
                                }
                            } else {
                                //TODO: Create Folder and set parent id
                                Toast.makeText(context,"Folder Path doesn't exist", Toast.LENGTH_LONG).show()
                                return@withContext false
                            }
                        }
                    }
                    return@withContext true
                } } as Boolean
                if(success) {
                    return@withContext parentId
                }
                return@withContext ""
            }
            return@withContext ""
        }

        suspend fun DownloadDriveFile(parentId: String, directory: DocumentFile, context: Context, sharedViewModel: SettingsViewModel) {
            if(!parentId.isNullOrBlank()) {
                val ListResponse: HttpResponse = client.request(
                    "https://www.googleapis.com/drive/v3/files" +
                            "?q=parents='${parentId}'and mimeType='application/vnd.google-apps.folder'" +
                            "&fields=files(id,name,parents,mimeType)"
                ) {
                    method = HttpMethod.Post
                    headers {
                        bearerAuth(accessToken)
                        contentType(ContentType.Any)
                    }
                }
                if(ListResponse.status.value in 200..299) {
                    val jsonArray = Json.parseToJsonElement(ListResponse.bodyAsText()).jsonArray
                    if(jsonArray.isNotEmpty()) {
                        val jsonObject = jsonArray[0].jsonObject
                        if(!jsonObject["id"].toString().isNullOrBlank()) {
                            val id = jsonObject["id"].toString()
                            val fileResponse : HttpResponse = client.request(
                                "https://www.googleapis.com/drive/v3/files/${id}" +
                                        "?alt=media"
                            )  {
                                method = HttpMethod.Post
                                headers {
                                    bearerAuth(accessToken)
                                }
                            }
                            val fileBytes = fileResponse.readBytes()
                            val file = directory.createFile("", jsonObject["name"].toString())
                            file?.uri?.toFile()?.writeBytes(fileBytes)
                        }
                    }
                } else {
                    Toast.makeText(context,"File Does Not Exist", Toast.LENGTH_LONG).show()
                }
            }
        }

        suspend fun UploadFileToDrive(parentId: String, mimeType: String, fileBytes: ByteArray, context: Context, sharedViewModel: SettingsViewModel) {
            val metaData = {
                val name = "backupData"
                val mimeType = mimeType
                val parents = arrayOf(parentId)
            }

            val createResponse: HttpResponse = client.request(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
            ) {
                method = HttpMethod.Post
                headers {
                    bearerAuth(accessToken)
                    contentType(ContentType.MultiPart.Related.withParameter("boundary","Split"))
                }
                setBody() {
                    "--Split"
                    ContentType.Application.Json.withParameter("charset", "UTF-8")
                    Json.encodeToJsonElement(metaData)
                    "--Split"
                    "Content-Type: ${mimeType}"
                    fileBytes.decodeToString()
                    "--Split--"
                }
            }
            if(createResponse.status.value in 200..299) {
                Toast.makeText(context,"File Uploaded", Toast.LENGTH_LONG).show()
            }
        }
    }
}