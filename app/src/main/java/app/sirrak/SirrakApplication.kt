package app.sirrak

import android.app.Application
import app.sirrak.core.runtime.AppContext

/** Owns the composition root for the process; the UI only ever sees the [AppContext]. */
class SirrakApplication : Application() {

    lateinit var container: AppContext
        private set

    override fun onCreate() {
        super.onCreate()
        // ADR-4: only the UI process composes the app graph. The isolated engine process
        // (<applicationId>:engine) is started by the service and must not duplicate repositories,
        // watchers or a second native engine instance while it runs inference requests.
        if (Application.getProcessName() != packageName) return
        container = AppComposition.build(this)
    }

    override fun onTerminate() {
        if (this::container.isInitialized) container.close()
        super.onTerminate()
    }
}
