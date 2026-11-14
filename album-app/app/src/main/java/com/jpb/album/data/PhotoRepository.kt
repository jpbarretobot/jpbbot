package com.jpb.album.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Une photo de la galerie, lue en lecture seule depuis le MediaStore. */
data class Photo(
    val id: Long,
    val uri: Uri,
    /** Date de prise de vue (ms depuis epoch), ou date d'ajout si absente. */
    val takenAtMillis: Long,
) {
    val day: LocalDate
        get() = Instant.ofEpochMilli(takenAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
}

/**
 * Lit les photos du téléphone sans les copier ni les modifier.
 * Les originaux restent où ils sont : l'app ne fait que les référencer.
 */
object PhotoRepository {

    suspend fun loadPhotos(context: Context): List<Photo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, " +
            "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val photos = mutableListOf<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(takenCol)
                // DATE_TAKEN peut valoir 0 (captures d'écran, images téléchargées) :
                // on retombe alors sur DATE_ADDED (en secondes, d'où le * 1000).
                val millis = if (taken > 0) taken else cursor.getLong(addedCol) * 1000L

                photos += Photo(
                    id = id,
                    uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString(),
                    ),
                    takenAtMillis = millis,
                )
            }
        }
        photos
    }
}
