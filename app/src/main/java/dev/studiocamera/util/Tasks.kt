package dev.studiocamera.util

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Attend le résultat d'une [Task] Google Play Services sans dépendre d'une bibliothèque coroutines tierce. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    addOnCanceledListener {
        if (cont.isActive) cont.resumeWithException(kotlinx.coroutines.CancellationException("Tâche annulée"))
    }
}
