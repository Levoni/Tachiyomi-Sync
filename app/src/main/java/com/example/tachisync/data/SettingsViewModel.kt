package com.example.tachisync.data

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tachisync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SettingsViewModel(): ViewModel() {
    private val _driveDirectory = MutableLiveData<String>()
    val driveDirectory : LiveData<String> = _driveDirectory
    var driveRefreshToken : String = ""
    var androidDirectory: String = ""

    private val _readableAndroidDirectory = MutableLiveData<String>()
    val readableAndroidDirectory: LiveData<String> = _readableAndroidDirectory

    init {
        _driveDirectory.value = ""
        _readableAndroidDirectory.value = ""
    }

    fun Initialize(context:Context) {
        viewModelScope.launch {
            _driveDirectory.value = context.settings.data.map { preferences ->
                preferences[DRIVE_DIRECTORY] ?: ""
            }.first()
            androidDirectory = context.settings.data.map { preferences ->
                preferences[ANDROID_DIRECTORY] ?: ""
            }.first()
            _readableAndroidDirectory.value = context.settings.data.map { preferences ->
                preferences[READABLE_ANDROID_DIRECTORY] ?: ""
            }.first()
            driveRefreshToken = context.settings.data.map { preferences ->
                preferences[REFRESH_TOKEN] ?: ""
            }.first()
        }
    }



    fun setReadableAndroidDirectory(directory: String) {
        _readableAndroidDirectory.value = directory
    }

    fun setDriveDirectory(directory: String) {
        _driveDirectory.value = directory

    }


    fun setPreferences_async(context: Context)  {
        viewModelScope.launch {
            context.settings.edit { preferences ->
                preferences[ANDROID_DIRECTORY] = androidDirectory
                preferences[READABLE_ANDROID_DIRECTORY] = _readableAndroidDirectory.value.toString()
                preferences[DRIVE_DIRECTORY] = _driveDirectory.value.toString()
                preferences[REFRESH_TOKEN] = driveRefreshToken
            }
        }
    }
}