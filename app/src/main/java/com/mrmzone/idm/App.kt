package com.mrmzone.idm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.mrmzone.idm.data.Prefs
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.service.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(Prefs(this).darkTheme)
        Notifier.createChannels(this)
        DM.init(this)
        com.mrmzone.idm.engine.Ytdlp.warmUp(this)
        DM.completeListeners.add { d ->
            val p = DM.prefs
            if (p.vibrateOnComplete) runCatching {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(longArrayOf(0, 120, 80, 120), -1)
            }
            if (p.showCompleteDialog && inForeground) runCatching {
                startActivity(android.content.Intent(this, com.mrmzone.idm.ui.CompleteActivity::class.java)
                    .putExtra("id", d.id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) { started-- }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        @Volatile var started = 0
        val inForeground get() = started > 0
        fun applyTheme(mode: Int) = AppCompatDelegate.setDefaultNightMode(
            when (mode) { 0 -> AppCompatDelegate.MODE_NIGHT_NO; 1 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
    }
}
