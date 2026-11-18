package com.mrmzone.idm.engine

import android.content.Context
import com.mrmzone.idm.data.Prefs
import java.net.URL

/**
 * Lightweight ad / pop-up blocker for the built-in browser, using host lists extracted
 * from EasyList + IndianList (easylist.to, GPLv3 / CC BY-SA 3.0).
 */
object AdBlock {
    @Volatile private var adHosts: Set<String> = emptySet()
    @Volatile private var popupHosts: Set<String> = emptySet()
    @Volatile var loaded = false; private set

    fun load(ctx: Context) {
        if (loaded) return
        Thread {
            runCatching {
                val am = ctx.applicationContext.assets
                adHosts = am.open("adblock_hosts.txt").bufferedReader().useLines { l -> l.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet() }
                popupHosts = am.open("popup_hosts.txt").bufferedReader().useLines { l -> l.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet() }
                loaded = true
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun host(url: String?): String? = runCatching { URL(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    /** "a.b.example.co.uk" → checks a.b.example.co.uk, b.example.co.uk, example.co.uk, co.uk */
    private fun inSet(set: Set<String>, host: String?): Boolean {
        var h = host ?: return false
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun isAd(url: String?) = inSet(adHosts, host(url))
    fun isPopupAd(url: String?) = host(url).let { inSet(popupHosts, it) || inSet(adHosts, it) }

    /** Rough "same site" check: last two labels (example.com, example.co → enough for our heuristics). */
    fun site(host: String?): String? {
        val h = host ?: return null
        val parts = h.split('.')
        return if (parts.size <= 2) h else parts.takeLast(if (parts[parts.size - 2].length <= 3 && parts.last().length == 2) 3 else 2).joinToString(".")
    }

    fun sameSite(a: String?, b: String?) = site(host(a)) != null && site(host(a)) == site(host(b))

    fun enabledFor(prefs: Prefs, pageUrl: String?): Boolean =
        prefs.adblock && site(host(pageUrl)).let { it == null || it !in prefs.adblockAllow }
}
