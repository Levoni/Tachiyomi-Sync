package com.example.tachisync.service

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
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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
            GlobalScope.launch(Dispatchers.Main) {
                val response: HttpResponse = client.request(
                    "https://oauth2.googleapis.com/token" +
                            "?code=${authorizationCode}}" +
                            "&redirect_uri=com.example.tachisync:/callback" +
                            "&client_id=213487956564-b79if896fihvd8v2vljlmt0qq7pj37am.apps.googleusercontent.com" +
                            "&scope=" +
                            "&grant_type=authorization_code"
                ) {
                    method = HttpMethod.Post
                }
                if(response.status.value in 200..299) {
                    val stringBody: String = response.bodyAsText()
                    val jsonObject = Json.parseToJsonElement(stringBody).jsonObject
                    accessToken = jsonObject["access_token"].toString()
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
        @OptIn(InternalAPI::class)
        suspend fun CheckForValidAuthentication(context: Context, sharedViewModel: SettingsViewModel): Boolean = withContext(Dispatchers.Main) {
            if (accessToken == "" || tokenExpiration.before(Time.from(Instant.now()))) {
                    Toast.makeText(
                        context,
                        "Please Authorize Google Drive Access First.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext false
                }
            return@withContext true
        }

        suspend fun VerifyAndCreateDriveFolders(context: Context, sharedViewModel: SettingsViewModel): String = withContext(Dispatchers.Main) {
            var directorySegments = sharedViewModel.driveDirectory.value.toString().split("/")
            val one = CheckForValidAuthentication(context,sharedViewModel)
            //TODO: see if this is actually returning a bool
            if(one) {
                var parentId = ""
                val success = withContext(Dispatchers.Main) {
                    for (segment in directorySegments) {
                        val response: HttpResponse = client.get(
                            "https://www.googleapis.com/drive/v3/files" +
                                    "?fields=files(id,name,parents,mimeType)" +
                                    "&q=name='Backup'+and+mimeType='application%2Fvnd.google-apps.folder'"
                        ) {
                            bearerAuth(accessToken)
                        }
                        var uri = response.call.request.url.toURI()
                        var string = response.call.request.url.toString()
                        var temp = uri.toString() + string
                        if (response.status.value in 200..299) {
                            val stringBody: String = response.bodyAsText()
                            val jsonArray = Json.parseToJsonElement(stringBody).jsonObject["files"]?.jsonArray!!
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
                } as Boolean
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