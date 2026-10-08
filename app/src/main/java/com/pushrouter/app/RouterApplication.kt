package com.pushrouter.app

import android.app.Application
import com.pushrouter.app.data.RouterRepository
import com.pushrouter.app.delivery.HistoryCleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RouterApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var repository: RouterRepository? = null
        private set
    override fun onCreate() {
        super.onCreate()
        // Corrupt/unreadable encrypted state fails closed; never replace it with empty routing configuration.
        repository = runCatching { RouterRepository(this) }.getOrNull()
        scope.launch { runCatching { repository?.prune() } }
        if (repository != null) HistoryCleanupWorker.schedule(this)
    }
}
