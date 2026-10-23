package app.openshelf

import android.app.Application
import app.openshelf.share.ShareHub
import app.openshelf.share.ShelfPrefs

class OpenShelfApp : Application() {
    lateinit var prefs: ShelfPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = ShelfPrefs(this)
        ShareHub.attach(this)
    }
}
