package app.novelspeak.adblock

/**
 * A deliberately small scriptlet library. uBO ships hundreds; these are the ones that matter for
 * the popup/anti-adblock patterns common on serialized-novel sites, rendered as self-contained JS
 * that the Android layer injects with `evaluateJavascript` (so page CSP cannot block them).
 */
object Scriptlets {

    fun render(name: String?, args: Array<String>?): String? {
        if (name == null) return null
        if (name.startsWith("trusted-")) return null
        val a = args ?: emptyArray()
        return when (name) {
            "set-constant" -> setConstant(a)
            "abort-on-property-read" -> abortOnProperty(a, read = true)
            "abort-on-property-write" -> abortOnProperty(a, read = false)
            "noeval" -> "(function(){try{window.eval=function(){throw new Error('noeval')}}catch(e){}})();"
            "nowebworker" -> "(function(){try{window.Worker=function(){return{postMessage:function(){},terminate:function(){},addEventListener:function(){},removeEventListener:function(){}}}}catch(e){}})();"
            "prevent-setTimeout" -> preventTimer(a, "setTimeout")
            "prevent-setInterval" -> preventTimer(a, "setInterval")
            else -> null
        }
    }

    private fun setConstant(args: Array<String>): String? {
        if (args.size < 2) return null
        val path = safePath(args[0]) ?: return null
        val value = jsValue(args[1]) ?: return null
        val pathJs = path.joinToString(",") { jsString(it) }
        return "(function(){try{var p=[$pathJs],o=window;for(var i=0;i<p.length-1;i++){o=o[p[i]]=o[p[i]]||{};}o[p[p.length-1]]=$value;}catch(e){}})();"
    }

    private fun abortOnProperty(args: Array<String>, read: Boolean): String? {
        if (args.isEmpty()) return null
        val path = safePath(args[0]) ?: return null
        val pathJs = path.joinToString(",") { jsString(it) }
        val body = if (read) {
            "get:function(){throw new ReferenceError('aborted')}"
        } else {
            "set:function(){throw new ReferenceError('aborted')}"
        }
        return "(function(){try{var p=[$pathJs],o=window;for(var i=0;i<p.length-1;i++){o=o[p[i]]=o[p[i]]||{};}Object.defineProperty(o,p[p.length-1],{$body,configurable:true});}catch(e){}})();"
    }

    private fun preventTimer(args: Array<String>, fn: String): String? {
        if (args.isEmpty()) return null
        val needle = args[0]
        if (needle.isEmpty()) return null
        return "(function(){try{var n=${jsString(needle)},f=window.$fn;window.$fn=function(h){try{if(String(h).indexOf(n)>=0)return 0}catch(e){}return f.apply(window,arguments)}}catch(e){}})();"
    }

    private fun safePath(raw: String): List<String>? {
        if (raw.isEmpty() || raw.length > 160) return null
        val parts = raw.split('.')
        if (parts.size > 12) return null
        for (p in parts) {
            if (p.isEmpty() || p.length > 64) return null
            for (c in p) {
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '$')) return null
            }
        }
        return parts
    }

    /** Returns a JS literal for the uBO set-constant value vocabulary. */
    private fun jsValue(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 160) return null
        return when (v) {
            "true", "false", "null", "undefined" -> v
            "noopFunc" -> "function(){}"
            "emptyArr" -> "[]"
            "emptyObj" -> "{}"
            "noopCallbackFunc" -> "function(){}"
            else -> {
                val numeric = v.toDoubleOrNull()
                if (numeric != null) v else jsString(v.removeSurrounding("\"").removeSurrounding("'"))
            }
        }
    }

    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
