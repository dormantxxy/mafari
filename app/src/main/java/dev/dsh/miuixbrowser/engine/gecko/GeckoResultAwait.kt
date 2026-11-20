package dev.dsh.miuixbrowser.engine.gecko

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Await a GeckoResult without blocking the calling thread. */
suspend fun <T> GeckoResult<T>.await(): T = suspendCancellableCoroutine { continuation ->
    accept(
        { value ->
            if (continuation.isActive) continuation.resume(value)
        },
        { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        },
    )
}

/** Find the Activity behind nested ContextWrappers, if one exists. */
fun Context.findActivity(): Activity? {
    var current: Context = this
    val visited = HashSet<Context>()
    while (current is ContextWrapper && visited.add(current)) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? Activity
}
