package com.mrmzone.idm.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.mrmzone.idm.App
import com.mrmzone.idm.data.Prefs
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.FileStore

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = DM.prefs
        val f = Form(this, "Options")
        f.header("CONNECTION")
        f.slider("Default connections per download", 1, 32, p.maxConnections) { p.maxConnections = it }
        f.slider("Max simultaneous downloads", 1, 10, p.maxSimultaneous) { p.maxSimultaneous = it; DM.schedule() }
        f.slider("Retries on network error", 0, 50, p.retries) { p.retries = it }
        f.switch("Download only on Wi-Fi", p.wifiOnly) { p.wifiOnly = it; DM.schedule() }

        f.header("SPEED LIMITER")
        val opts = listOf(0, 64, 128, 256, 512, 1024, 2048, 5120, 10240)
        f.slider("Global speed limit", 0, opts.size - 1, opts.indexOf(p.speedLimitKb).coerceAtLeast(0),
            { i -> if (opts[i] == 0) "Unlimited" else if (opts[i] >= 1024) "${opts[i] / 1024} MB/s" else "${opts[i]} KB/s" }) { DM.setGlobalLimit(opts[it]) }

        f.header("BEHAVIOUR")
        f.switch("Monitor clipboard for links", p.clipboardMonitor) { p.clipboardMonitor = it }
        f.switch("Show progress dialog when download starts", p.showStartDialog) { p.showStartDialog = it }
        f.switch("Show download complete dialog", p.showCompleteDialog) { p.showCompleteDialog = it }
        f.switch("Vibrate when download completes", p.vibrateOnComplete) { p.vibrateOnComplete = it }
        f.switch("Resume unfinished downloads when app restarts", p.autoResume) { p.autoResume = it }

        f.header("APPEARANCE")
        val rg = RadioGroup(this)
        listOf("Light", "Dark", "Follow system").forEachIndexed { i, t ->
            rg.addView(RadioButton(this).apply { text = t; id = 100 + i; isChecked = p.darkTheme == i })
        }
        rg.setOnCheckedChangeListener { _, id -> p.darkTheme = id - 100; App.applyTheme(id - 100) }
        f.add(rg)

        f.header("BROWSER")
        f.switch("Block ads & pop-up ads", p.adblock) { p.adblock = it }
        f.note("Uses EasyList + IndianList. You can turn it off for a single site from the browser's shield button.")
        val home = EditText(this).apply { hint = "Home page"; setText(p.homePage) }
        f.add(home)
        val ua = EditText(this).apply { hint = "User-Agent"; setText(p.userAgent); textSize = 12f }
        f.add(ua)
        val save = MaterialButton(this).apply {
            text = "Save browser settings"
            setOnClickListener {
                p.homePage = home.text.toString().ifBlank { "https://www.google.com" }
                p.userAgent = ua.text.toString().ifBlank { Prefs.DEFAULT_UA }
                android.widget.Toast.makeText(context, "Saved", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        f.add(save)

        f.header("VIDEO ENGINE (YouTube & 1000+ sites)")
        val ver = f.note("yt-dlp version: checking…")
        Thread { val v = com.mrmzone.idm.engine.Ytdlp.version(this); runOnUiThread { ver.text = "yt-dlp version: $v" } }.start()
        f.add(MaterialButton(this).apply {
            text = "Update video engine now"
            setOnClickListener {
                isEnabled = false; ver.text = "Updating yt-dlp…"
                Thread {
                    val r = runCatching { com.mrmzone.idm.engine.Ytdlp.update(this@SettingsActivity) }.getOrElse { "Update failed: ${it.message}" }
                    runOnUiThread { ver.text = r; isEnabled = true }
                }.start()
            }
        })
        f.note("The engine updates itself automatically once a day so YouTube downloads keep working.")

        f.header("SAVE LOCATION")
        f.note("Files are sorted automatically into:\nDownload/${FileStore.ROOT}/Video, Music, Documents, Compressed, Programs, Images, General")
        f.show()
    }
}
