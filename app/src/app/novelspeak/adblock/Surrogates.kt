package app.novelspeak.adblock

/**
 * Stub scripts served in place of blocked tracker scripts, so the page's own code keeps working.
 * DuckDuckGo calls these surrogates and ships them as a separate signed resource file; we keep the
 * same idea but only for the handful of globals that actually break reader sites.
 */
object Surrogates {

    private class Entry(val host: String, val js: String)

    private val entries = arrayOf(
        Entry(
            "google-analytics.com",
            "(function(){window.ga=window.ga||function(){};window.ga.create=function(){return{}};" +
                "window.ga.getByName=function(){return null};window.ga.getAll=function(){return[]};" +
                "window.ga.remove=function(){};window.dataLayer=window.dataLayer||[];" +
                "window.gtag=window.gtag||function(){};})();"
        ),
        Entry(
            "googletagmanager.com",
            "(function(){window.dataLayer=window.dataLayer||[];window.gtag=window.gtag||function(){};" +
                "window.ga=window.ga||function(){};})();"
        ),
        Entry(
            "googlesyndication.com",
            "(function(){window.googletag=window.googletag||{};window.googletag.cmd=window.googletag.cmd||[];" +
                "window.googletag.pubads=window.googletag.pubads||function(){return{refresh:function(){},setTargeting:function(){}}};" +
                "window.googletag.defineSlot=window.googletag.defineSlot||function(){return{addService:function(){return this},setTargeting:function(){return this}}};" +
                "window.googletag.enableServices=window.googletag.enableServices||function(){};" +
                "window.googletag.display=window.googletag.display||function(){};})();"
        ),
        Entry(
            "doubleclick.net",
            "(function(){window.googletag=window.googletag||{};window.googletag.cmd=window.googletag.cmd||[];" +
                "window.googletag.pubads=window.googletag.pubads||function(){return{refresh:function(){},setTargeting:function(){}}};})();"
        ),
        Entry(
            "connect.facebook.net",
            "(function(){window.fbq=window.fbq||function(){};window.fbq.callMethod=function(){};" +
                "window.fbq.queue=[];window.fbq.loaded=true;window.fbq.version='2.0';window._fbq=window.fbq;})();"
        ),
        Entry(
            "amazon-adsystem.com",
            "(function(){window.apstag=window.apstag||{};window.apstag.init=function(){};" +
                "window.apstag.fetchBids=function(o){if(o&&o.timeout)setTimeout(function(){o.timeout([])},0)};" +
                "window.apstag.setDisplayBids=function(){};window.apstag.renderImp=function(){};})();"
        ),
        Entry(
            "taboola.com",
            "(function(){window._taboola=window._taboola||[];window._taboola.push=function(){};" +
                "window._taboola.init=function(){};window._taboola.render=function(){};})();"
        ),
        Entry(
            "outbrain.com",
            "(function(){window.OBR=window.OBR||{};window.OBR.extern=window.OBR.extern||{};" +
                "window.OBR.extern.onRecs=window.OBR.extern.onRecs||function(){return{}};})();"
        ),
        Entry(
            "scorecardresearch.com",
            "(function(){window.COMSCORE=window.COMSCORE||{};window.COMSCORE.beacon=function(){};})();"
        ),
        Entry(
            "quantserve.com",
            "(function(){window.__qc=window.__qc||{};window.__qc.quantserve=function(){};})();"
        )
    )

    fun forHost(host: String): String? {
        if (host.isEmpty()) return null
        for (e in entries) {
            if (UrlTools.hostMatches(host, e.host)) return e.js
        }
        return null
    }

    /** Maps a uBO `$redirect=` resource name to a body we can serve. */
    fun forRedirect(name: String?): String? {
        if (name == null) return null
        val n = name.lowercase()
        return when {
            n.contains("noop") || n.contains("empty") -> ""
            n.endsWith(".js") -> ""
            n.contains("1x1") && n.endsWith(".gif") -> null // handled by the image path
            else -> null
        }
    }
}
