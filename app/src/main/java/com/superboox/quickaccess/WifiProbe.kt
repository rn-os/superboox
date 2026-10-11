package com.superboox.quickaccess

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.SystemClock
import java.security.MessageDigest

/**
 * Wi‑Fi section of the debug report, for telling a radio or signal problem apart from
 * a software one (a connection that degrades until Wi‑Fi is switched off and on).
 *
 * With Advanced access it runs, as ADB would: Android's Wi‑Fi status and its recent
 * signal / link-speed history, the driver's radio counters (/proc/net/wireless: link
 * quality, noise, missed beacons), Wi‑Fi power-save state, ping to the router and to the
 * internet, and a small download timed by curl if the tablet has it. SuperBoox itself
 * still has no internet permission; those commands run as the shell.
 *
 * Network names are hidden and access-point addresses replaced by a short tag (the same
 * tag in every report), so roaming between access points still shows.
 */
object WifiProbe {

    fun lines(context: Context): List<String> {
        val out = ArrayList<String>()
        basic(context, out)
        if (!AdvancedAccess.allowed()) {
            out += "more detail (router ping, signal history, radio counters) needs Advanced access"
            return out
        }
        fun run(label: String, cmd: String, max: Int = 40) {
            val started = SystemClock.uptimeMillis()
            val (code, text) = AdvancedAccess.shellNow(cmd)
            out += "$label (exit $code, ${SystemClock.uptimeMillis() - started} ms):"
            redact(text).lines().filter { it.isNotBlank() }.take(max).forEach { out += "  $it" }
        }
        run("status", "cmd wifi status 2>&1", 30)
        run("radio counters", "cat /proc/net/wireless 2>&1")
        run("power save and low-latency", "dumpsys wifi 2>/dev/null | grep -i -E 'power ?save|lowlatency|low_latency|mPowerSave|isLowLatency' | head -12")
        run("signal and link-speed history", "dumpsys wifi 2>/dev/null | grep -A 24 -m 1 'WifiScoreReport' ", 26)
        run("recent connection events", "dumpsys wifi 2>/dev/null | grep -i -E 'CMD_DISCONNECT|DISCONNECTED|roam|ASSOCIATION_REJECTION|AUTHENTICATION_FAILURE|NETWORK_DISCONNECTION' | tail -12")
        run("uptime", "cat /proc/uptime; uptime 2>&1")
        // Router first: slow or lossy here means the tablet's Wi‑Fi link itself; fine here but slow
        // to the internet means the router or the internet connection.
        val gateway = AdvancedAccess.shellNow("ip route get 1.1.1.1 2>/dev/null").second
            .let { Regex("""via (\d+\.\d+\.\d+\.\d+)""").find(it)?.groupValues?.get(1) }
        if (gateway != null) run("ping router", "ping -c 12 -i 0.3 -W 2 $gateway 2>&1 | tail -3")
        else out += "ping router: no router address found"
        run("ping internet (1.1.1.1)", "ping -c 12 -i 0.3 -W 2 1.1.1.1 2>&1 | tail -3")
        run("DNS lookup time", "s=\$(date +%s%N); ping -c 1 -W 3 one.one.one.one >/dev/null 2>&1; echo \$(( (\$(date +%s%N) - s) / 1000000 )) ms incl. one ping")
        run(
            "download (2 MB)",
            "if command -v curl >/dev/null 2>&1; then curl -s -o /dev/null -m 20 -w 'speed %{speed_download} B/s, connect %{time_connect}s, first byte %{time_starttransfer}s, total %{time_total}s, http %{http_code}' " +
                "'https://speed.cloudflare.com/__down?bytes=2000000'; else echo 'curl not on this tablet: use a speed test in the browser'; fi",
        )
        return out
    }

    /** What any app can see: band, signal, link speeds (names need location access, so they're left out). */
    private fun basic(context: Context, out: MutableList<String>) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            out += "connected: not on Wi‑Fi right now"
            return
        }
        val info = caps.transportInfo as? WifiInfo
        out += "connected: Wi‑Fi, internet validated: ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}, " +
            "bandwidth estimate down ${caps.linkDownstreamBandwidthKbps / 1000} / up ${caps.linkUpstreamBandwidthKbps / 1000} Mbps"
        if (info != null) {
            val band = when (info.frequency) {
                in 2400..2500 -> "2.4 GHz"
                in 4900..5900 -> "5 GHz"
                in 5925..7125 -> "6 GHz"
                else -> "?"
            }
            out += "band $band (${info.frequency} MHz), signal ${info.rssi} dBm (${quality(info.rssi)}), " +
                "link ${info.txLinkSpeedMbps} Mbps send / ${info.rxLinkSpeedMbps} Mbps receive, Wi‑Fi standard ${info.wifiStandard}"
        }
    }

    private fun quality(rssi: Int) = when {
        rssi >= -55 -> "strong"
        rssi >= -67 -> "good"
        rssi >= -75 -> "weak"
        else -> "very weak"
    }

    private val MAC = Regex("""\b([0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5})\b""")
    private val SSID = Regex("""(SSID[:=]\s*)("[^"]*"|\S+)""", RegexOption.IGNORE_CASE)

    /** Hides network names; access-point addresses become a short tag, the same in every report. */
    private fun redact(s: String): String =
        s.replace(SSID) { m -> m.groupValues[1] + "<hidden>" }
            .replace(MAC) { m -> "ap-" + hash(m.value.lowercase()) }

    private fun hash(v: String): String =
        MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).take(3).joinToString("") { "%02x".format(it) }
}
