package com.dozy.pocketai

import android.app.Application
import com.dozy.pocketai.data.AppsStore
import com.dozy.pocketai.data.ChatStore
import com.dozy.pocketai.data.GitHub
import com.dozy.pocketai.data.Terminal
import com.dozy.pocketai.data.ModelRepository
import com.dozy.pocketai.data.SettingsStore
import com.dozy.pocketai.data.Updater
import com.dozy.pocketai.llm.LlamaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PocketApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var engine: LlamaEngine
    /** second model for Combo mode, loaded on demand */
    val engine2 by lazy { LlamaEngine() }
    lateinit var models: ModelRepository
    lateinit var settings: SettingsStore
    lateinit var chats: ChatStore
    lateinit var updater: Updater
    lateinit var github: GitHub
    lateinit var terminal: Terminal
    lateinit var apps: AppsStore

    override fun onCreate() {
        super.onCreate()
        engine = LlamaEngine()
        models = ModelRepository(this, appScope)
        settings = SettingsStore(this)
        chats = ChatStore(this)
        updater = Updater(this, appScope)
        updater.check(silent = true)
        github = GitHub(this)
        terminal = Terminal(this, appScope)
        apps = AppsStore(this)
    }
}
