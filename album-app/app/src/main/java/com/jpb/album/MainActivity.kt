package com.jpb.album

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.jpb.album.data.Photo
import com.jpb.album.data.PhotoRepository
import com.jpb.album.ui.Timeline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlbumApp()
                }
            }
        }
    }
}

@Composable
private fun AlbumApp() {
    val context = LocalContext.current

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var granted by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var askedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> granted = isGranted }

    // Vérifie la permission au démarrage, sans la redemander en boucle.
    LaunchedEffect(Unit) {
        granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted && !askedOnce) {
            askedOnce = true
            launcher.launch(permission)
        }
    }

    LaunchedEffect(granted) {
        if (granted) photos = PhotoRepository.loadPhotos(context)
    }

    if (!granted) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pour afficher vos photos, l'app a besoin d'accéder à la galerie.")
                Button(
                    onClick = { launcher.launch(permission) },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Autoriser") }
            }
        }
    } else {
        Timeline(photos = photos)
    }
}
