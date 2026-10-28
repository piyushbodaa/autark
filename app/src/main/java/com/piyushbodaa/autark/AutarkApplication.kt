package com.piyushbodaa.autark

import android.app.Application
import android.content.ComponentCallbacks2
import com.piyushbodaa.autark.data.ChatStore
import com.piyushbodaa.autark.data.SettingsStore
import com.piyushbodaa.autark.engine.LlamaEngine
import com.piyushbodaa.autark.models.Catalog
import com.piyushbodaa.autark.models.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AutarkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            // In the background the model is the first thing Android would kill us for.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                container.scope.launch { container.engine.release(stillWanted = { container.visible }) }
            // Just left: Android may stop the app at any moment now, so keep the chat on disk.
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                container.scope.launch { container.engine.saveSession() }
        }
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val engine = LlamaEngine(app.applicationInfo.nativeLibraryDir, File(app.cacheDir, "session.bin"))

    /** Whether the app is on screen; MainActivity keeps it up to date. */
    @Volatile
    var visible = false
    val chats = ChatStore(File(app.filesDir, "chats"))
    val models = ModelManager(app, scope, Catalog.model)
}
