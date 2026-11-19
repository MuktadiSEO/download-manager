package com.mrmzone.idm.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("mrm_idm", Context.MODE_PRIVATE)

    var maxConnections: Int get() = sp.getInt("max_conn", 8); set(v) = sp.edit().putInt("max_conn", v).apply()
    var maxSimultaneous: Int get() = sp.getInt("max_sim", 3); set(v) = sp.edit().putInt("max_sim", v).apply()
    var speedLimitKb: Int get() = sp.getInt("speed_limit", 0); set(v) = sp.edit().putInt("speed_limit", v).apply()
    var retries: Int get() = sp.getInt("retries", 10); set(v) = sp.edit().putInt("retries", v).apply()
    var wifiOnly: Boolean get() = sp.getBoolean("wifi_only", false); set(v) = sp.edit().putBoolean("wifi_only", v).apply()
    var clipboardMonitor: Boolean get() = sp.getBoolean("clip", true); set(v) = sp.edit().putBoolean("clip", v).apply()
    var showCompleteDialog: Boolean get() = sp.getBoolean("complete_dlg", true); set(v) = sp.edit().putBoolean("complete_dlg", v).apply()
    var showStartDialog: Boolean get() = sp.getBoolean("start_dlg", true); set(v) = sp.edit().putBoolean("start_dlg", v).apply()
    var vibrateOnComplete: Boolean get() = sp.getBoolean("vibrate", true); set(v) = sp.edit().putBoolean("vibrate", v).apply()
    var autoResume: Boolean get() = sp.getBoolean("auto_resume", true); set(v) = sp.edit().putBoolean("auto_resume", v).apply()
    var userAgent: String get() = sp.getString("ua", DEFAULT_UA)!!; set(v) = sp.edit().putString("ua", v).apply()
    /** default download folder chosen by the user (SAF tree uri); null = Download/MRM Zone/<category> */
    var saveTree: String? get() = sp.getString("save_tree", null); set(v) = sp.edit().putString("save_tree", v).apply()
    var lastClip: String? get() = sp.getString("last_clip", null); set(v) = sp.edit().putString("last_clip", v).apply()
    var homePage: String get() = sp.getString("home", "https://www.google.com")!!; set(v) = sp.edit().putString("home", v).apply()
    var darkTheme: Int get() = sp.getInt("theme", 2); set(v) = sp.edit().putInt("theme", v).apply() // 0 light 1 dark 2 system

    var ytUpdateCheck: Long get() = sp.getLong("yt_upd", 0); set(v) = sp.edit().putLong("yt_upd", v).apply()
    var ytBundleVc: Int get() = sp.getInt("yt_bundle_vc", 0); set(v) = sp.edit().putInt("yt_bundle_vc", v).apply()

    var adblock: Boolean get() = sp.getBoolean("adblock", true); set(v) = sp.edit().putBoolean("adblock", v).apply()
    var adblockAllow: Set<String> get() = sp.getStringSet("adblock_allow", emptySet())!!.toSet(); set(v) = sp.edit().putStringSet("adblock_allow", v).apply()
    var savedTabs: String? get() = sp.getString("tabs", null); set(v) = sp.edit().putString("tabs", v).apply()
    var savedTabCur: Int get() = sp.getInt("tab_cur", 0); set(v) = sp.edit().putInt("tab_cur", v).apply()

    // Scheduler
    var schedEnabled: Boolean get() = sp.getBoolean("sch_on", false); set(v) = sp.edit().putBoolean("sch_on", v).apply()
    var schedStartMin: Int get() = sp.getInt("sch_start", 2 * 60); set(v) = sp.edit().putInt("sch_start", v).apply()
    var schedStopEnabled: Boolean get() = sp.getBoolean("sch_stop_on", false); set(v) = sp.edit().putBoolean("sch_stop_on", v).apply()
    var schedStopMin: Int get() = sp.getInt("sch_stop", 7 * 60); set(v) = sp.edit().putInt("sch_stop", v).apply()
    var schedDays: Int get() = sp.getInt("sch_days", 0b1111111); set(v) = sp.edit().putInt("sch_days", v).apply() // bit0 = Sunday

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
