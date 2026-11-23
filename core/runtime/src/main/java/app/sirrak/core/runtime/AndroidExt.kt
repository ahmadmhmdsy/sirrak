package app.sirrak.core.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Walks a (possibly wrapped) context to its hosting Activity; null in previews/tests. */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
