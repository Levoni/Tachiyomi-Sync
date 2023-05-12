package com.example.tachisync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.databinding.DataBindingUtil
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import com.example.tachisync.data.SettingsViewModel
import com.example.tachisync.databinding.ActivityMainBinding
import com.example.tachisync.service.GoogleService
import com.example.tachisync.service.GoogleService.Companion.DownloadDriveFile
import com.example.tachisync.service.GoogleService.Companion.GetDriveRefreshTokenAndAccessToken
import com.example.tachisync.service.GoogleService.Companion.UploadFileToDrive
import com.example.tachisync.service.GoogleService.Companion.VerifyAndCreateDriveFolders
import com.example.tachisync.service.GoogleService.Companion.client
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.sql.Time
import java.time.Instant

val Context.settings: DataStore<Preferences> by preferencesDataStore(
    name = "user"
)
val ANDROID_DIRECTORY = stringPreferencesKey("andriod_directory")
val READABLE_ANDROID_DIRECTORY = stringPreferencesKey("readable_android_directory")
val DRIVE_DIRECTORY = stringPreferencesKey("drive_directory")
val REFRESH_TOKEN = stringPreferencesKey("refresh_token")

class MainActivity : AppCompatActivity() {

    private val sharedViewModel = SettingsViewModel()
    private lateinit var dirRequest: ActivityResultLauncher<Uri?>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding: ActivityMainBinding = DataBindingUtil.setContentView(
            this, R.layout.activity_main)

        binding.settings = sharedViewModel
        binding.viewModel = this
        binding.lifecycleOwner = this
        binding.googlePathTextboxInput.setOnEditorActionListener  { v:TextView?, actionId: Int?, event: KeyEvent? ->
            if(actionId==EditorInfo.IME_ACTION_DONE || actionId == null) {
                sharedViewModel.setDriveDirectory(binding.googlePathTextboxInput.text.toString())
                sharedViewModel.setPreferences_async(this@MainActivity)
                binding.googlePathTextboxInput.clearFocus()
                Toast.makeText(this@MainActivity,"Drive Directory Saved", Toast.LENGTH_SHORT).show()
            }
            false
        }
        sharedViewModel.Initialize(this@MainActivity)

        //Callback from Goo OAuth2 sign in page
        var uri = intent.data;
        if(uri != null) {
            val authorizationCode = uri.getQueryParameter("code")
            if(authorizationCode != null) {
                GetDriveRefreshTokenAndAccessToken(authorizationCode, this@MainActivity, sharedViewModel)
            }
        }


        dirRequest = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                // call this to persist permission across decice reboots
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // do your stuff
                var directoryDocumentFile = DocumentFile.fromTreeUri(this@MainActivity,uri)
                var readablePath = directoryDocumentFile?.uri?.lastPathSegment!!.split(":")[1]
                sharedViewModel.androidDirectory =directoryDocumentFile.uri.toString()
                sharedViewModel.setReadableAndroidDirectory(readablePath)
                sharedViewModel.setPreferences_async(this@MainActivity)
                Toast.makeText(this@MainActivity,"Android Directory Saved", Toast.LENGTH_SHORT).show()
            }
        }



    }

    fun SelectDirectory() {
        dirRequest.launch(null)
    }

    fun upload() {
        GlobalScope.launch {
            uploadBackup()
        }
    }

    fun download() {
        GlobalScope.launch {
            DownloadBackup()
        }
    }

    suspend fun uploadBackup() {
        if(!sharedViewModel.androidDirectory.isNullOrBlank()) {
            var directoryDocumentFile =
                DocumentFile.fromTreeUri(this@MainActivity, sharedViewModel.androidDirectory.toUri())
            var files = directoryDocumentFile?.listFiles()
            if (!files.isNullOrEmpty()) {
                var sortedList = files.sortedByDescending { it.lastModified() }
                var inputStream = contentResolver.openInputStream(sortedList!![0].uri);
                var fileBytes = inputStream?.readBytes()

                val validFolder = VerifyAndCreateDriveFolders(this@MainActivity, sharedViewModel)
                if(!validFolder.isNullOrBlank()) {
                    UploadFileToDrive(validFolder, sortedList[0].type!!,fileBytes!!,this@MainActivity, sharedViewModel)
                } else {
                    //TODO: toastr about failure
                }
            }
        }
    }

    suspend fun DownloadBackup() {
        if(!sharedViewModel.androidDirectory.isNullOrBlank()) {
            var directoryDocumentFile =
                DocumentFile.fromTreeUri(this@MainActivity, sharedViewModel.androidDirectory.toUri())
            var files = directoryDocumentFile?.listFiles()
            for (file in files!!) {
                file.delete()
            }
            val validFolder =  VerifyAndCreateDriveFolders(this@MainActivity, sharedViewModel)
            if(!validFolder.isNullOrBlank()) {
                DownloadDriveFile(validFolder, directoryDocumentFile!!, this@MainActivity, sharedViewModel)
            } else {
                //TODO: toastr about failure
            }
        }
    }

    fun Authorize() {
        GoogleService.AuthorizeWithSignOnPage(this@MainActivity)
    }
}