package app.obsibible.obsidian

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Obsidian Web Clipper template language: {{variable|filter:"arg"|filter}}.
 * Only the filters that make sense for a YouTube clip are implemented. An unknown
 * filter passes the value through untouched, so a template never breaks a save.
 */
object Template {
    private sealed interface V {
        data class S(val s: String) : V
        data class L(val l: List<String>) : V
    }

    private fun V.text(sep: String = ","): String = when (this) {
        is V.S -> s
        is V.L -> l.joinToString(sep)
    }

    fun render(format: String, vars: Map<String, String>): String {
        val out = StringBuilder()
        var i = 0
        while (i < format.length) {
            val open = format.indexOf("{{", i)
            if (open < 0) {
                out.append(format, i, format.length)
                break
            }
            val close = findClose(format, open + 2)
            if (close < 0) {
                out.append(format, i, format.length)
                break
            }
            out.append(format, i, open)
            out.append(evaluate(format.substring(open + 2, close), vars))
            i = close + 2
        }
        return out.toString()
    }

    private fun findClose(s: String, from: Int): Int {
        var quote: Char? = null
        var i = from
        while (i < s.length - 1) {
            val c = s[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else if (c == '"' || c == '\'') quote = c
            else if (c == '}' && s[i + 1] == '}') return i
            i++
        }
        return -1
    }

    private fun evaluate(tag: String, vars: Map<String, String>): String {
        val parts = splitTopLevel(tag, '|')
        if (parts.isEmpty()) return ""
        val name = parts[0].trim()
        var value: V = V.S(vars[name] ?: "")
        for (f in parts.drop(1)) value = applyFilter(value, f.trim())
        return value.text()
    }

    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                cur.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    cur.append(s[i])
                } else if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
                cur.append(c)
            } else if (c == sep) {
                parts += cur.toString()
                cur.clear()
            } else cur.append(c)
            i++
        }
        parts += cur.toString()
        return parts
    }

    private fun parseArgs(raw: String): List<String> {
        val args = ArrayList<String>()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"' || c == '\'') {
                val sb = StringBuilder()
                i++
                while (i < raw.length && raw[i] != c) {
                    if (raw[i] == '\\' && i + 1 < raw.length) {
                        i++
                        sb.append(
                            when (raw[i]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> raw[i]
                            },
                        )
                    } else sb.append(raw[i])
                    i++
                }
                args += sb.toString()
                i++
            } else if (c == ':' || c == ',' || c == ' ') {
                i++
            } else {
                val sb = StringBuilder()
                while (i < raw.length && raw[i] != ':' && raw[i] != ',') sb.append(raw[i++])
                args += sb.toString().trim()
            }
        }
        return args
    }

    private fun applyFilter(v: V, filter: String): V {
        val colon = filter.indexOf(':')
        val name = (if (colon < 0) filter else filter.substring(0, colon)).trim()
        val args = if (colon < 0) emptyList() else parseArgs(filter.substring(colon + 1))
        fun mapEach(f: (String) -> String): V = when (v) {
            is V.S -> V.S(f(v.s))
            is V.L -> V.L(v.l.map(f))
        }
        return when (name) {
            "split" -> {
                val sep = args.firstOrNull() ?: ","
                val s = v.text()
                V.L(if (s.isEmpty()) emptyList() else s.split(sep).map { it.trim() }.filter { it.isNotEmpty() })
            }
            "join" -> V.S(v.text(args.firstOrNull() ?: ","))
            "wikilink" -> {
                val alias = args.firstOrNull()
                mapEach { if (it.isBlank()) it else if (alias != null) "[[$it|$alias]]" else "[[$it]]" }
            }
            "link" -> mapEach { if (it.isBlank()) it else "[${args.firstOrNull() ?: it}]($it)" }
            "lower" -> mapEach { it.lowercase() }
            "upper" -> mapEach { it.uppercase() }
            "capitalize" -> mapEach { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            "title" -> mapEach { s -> s.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.uppercase() } } }
            "trim" -> mapEach { it.trim() }
            "kebab" -> mapEach { it.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            "snake" -> mapEach { it.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }
            "camel" -> mapEach { s ->
                s.trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
                    .mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { c -> c.uppercase() } }
                    .joinToString("")
            }
            "safe_name" -> mapEach { safeName(it) }
            "blockquote" -> mapEach { s -> s.lines().joinToString("\n") { "> $it" } }
            "list" -> mapEach { s -> s.lines().filter { it.isNotBlank() }.joinToString("\n") { "- $it" } }
            "first" -> if (v is V.L) V.S(v.l.firstOrNull() ?: "") else v
            "last" -> if (v is V.L) V.S(v.l.lastOrNull() ?: "") else v
            "default" -> if (v.text().isBlank()) V.S(args.firstOrNull() ?: "") else v
            "replace" -> {
                val pairs = args.chunked(2).filter { it.size == 2 }
                mapEach { s -> pairs.fold(s) { acc, (a, b) -> acc.replace(a, b) } }
            }
            "date" -> mapEach { formatDate(it, args.firstOrNull() ?: "YYYY-MM-DD") ?: it }
            else -> v
        }
    }

    fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(120)

    // ---------- dates ----------

    fun parseDate(s: String): ZonedDateTime? {
        val t = s.trim()
        if (t.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(t).toZonedDateTime() }
        runCatching { return ZonedDateTime.parse(t) }
        runCatching { return LocalDateTime.parse(t).atZone(ZoneId.systemDefault()) }
        runCatching { return LocalDate.parse(t).atStartOfDay(ZoneId.systemDefault()) }
        return null
    }

    /** Moment-style tokens: YYYY MM DD HH mm ss, MMM, MMMM, ddd, dddd, A, [literal]. */
    fun formatDate(input: String, pattern: String): String? {
        val d = parseDate(input) ?: return null
        val sb = StringBuilder()
        var i = 0
        fun at(tok: String) = pattern.startsWith(tok, i)
        while (i < pattern.length) {
            when {
                pattern[i] == '[' -> {
                    val end = pattern.indexOf(']', i)
                    if (end < 0) { sb.append(pattern.substring(i)); i = pattern.length }
                    else { sb.append(pattern, i + 1, end); i = end + 1 }
                }
                at("YYYY") -> { sb.append("%04d".format(d.year)); i += 4 }
                at("YY") -> { sb.append("%02d".format(d.year % 100)); i += 2 }
                at("MMMM") -> { sb.append(d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)); i += 4 }
                at("MMM") -> { sb.append(d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)); i += 3 }
                at("MM") -> { sb.append("%02d".format(d.monthValue)); i += 2 }
                at("M") -> { sb.append(d.monthValue); i += 1 }
                at("DD") -> { sb.append("%02d".format(d.dayOfMonth)); i += 2 }
                at("D") -> { sb.append(d.dayOfMonth); i += 1 }
                at("dddd") -> { sb.append(d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)); i += 4 }
                at("ddd") -> { sb.append(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)); i += 3 }
                at("HH") -> { sb.append("%02d".format(d.hour)); i += 2 }
                at("H") -> { sb.append(d.hour); i += 1 }
                at("hh") -> { sb.append("%02d".format(if (d.hour % 12 == 0) 12 else d.hour % 12)); i += 2 }
                at("h") -> { sb.append(if (d.hour % 12 == 0) 12 else d.hour % 12); i += 1 }
                at("mm") -> { sb.append("%02d".format(d.minute)); i += 2 }
                at("m") -> { sb.append(d.minute); i += 1 }
                at("ss") -> { sb.append("%02d".format(d.second)); i += 2 }
                at("s") -> { sb.append(d.second); i += 1 }
                at("A") -> { sb.append(if (d.hour < 12) "AM" else "PM"); i += 1 }
                at("a") -> { sb.append(if (d.hour < 12) "am" else "pm"); i += 1 }
                at("ZZ") -> { sb.append(d.offset.id.replace(":", "").replace("Z", "+0000")); i += 2 }
                at("Z") -> { sb.append(d.offset.id.replace("Z", "+00:00")); i += 1 }
                else -> { sb.append(pattern[i]); i += 1 }
            }
        }
        return sb.toString()
    }
}
