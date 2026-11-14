package com.yomu.reader.extension

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/*
 * Extraction des valeurs selon les [Field] d'un fichier d'extension.
 */

/** Toutes les valeurs trouvées par [field] dans [scope], dans l'ordre. */
fun Field.extractAll(scope: Element, absolute: Boolean = false): List<String> {
    val elements = if (css == null) listOf(scope) else scope.select(css)
    val attrs = attr.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val out = ArrayList<String>()
    for (el in elements) {
        val raw = attrs.firstNotNullOfOrNull { read(el, it).takeIf(String::isNotBlank) } ?: continue
        val values = regex?.let { re ->
            re.findAll(raw).mapNotNull { m -> if (group in m.groupValues.indices) m.groups[group]?.value else m.value }.toList()
        } ?: listOf(raw)
        for (v0 in values) {
            var v = v0
            replace.forEach { (re, to) -> v = re.replace(v, to) }
            v = v.trim()
            if (template != null) v = template.replace("{value}", v)
            if (absolute) v = resolveUrl(scope.baseUri(), v) ?: continue
            if (v.isNotEmpty()) out += v
        }
    }
    return out
}

/** Première valeur trouvée, ou null. */
fun Field.extractOne(scope: Element, absolute: Boolean = false): String? = extractAll(scope, absolute).firstOrNull()

private fun read(el: Element, attr: String): String = when (attr) {
    "text" -> el.text()
    "ownText" -> el.ownText()
    "html" -> el.html()
    "outerHtml" -> el.outerHtml()
    else -> el.attr(attr)
}

/** Résout un lien relatif par rapport à la page. Renvoie null si ce n'est pas une adresse http(s). */
fun resolveUrl(pageUrl: String, link: String): String? {
    val clean = link.trim()
    if (clean.isEmpty() || clean.startsWith("javascript:") || clean.startsWith("data:")) return null
    val base = pageUrl.toHttpUrlOrNull()
    val url = base?.resolve(clean) ?: clean.toHttpUrlOrNull() ?: return null
    return url.toString()
}

/** Remplace {baseUrl} {page} {page0} {query} {id} {url} dans un modèle. */
fun fillTemplate(template: String, vars: Map<String, String>, encodeQuery: Boolean = true): String =
    Regex("\\{(\\w+)\\}").replace(template) { m ->
        val key = m.groupValues[1]
        val value = vars[key] ?: return@replace m.value
        if (key == "query" && encodeQuery) URLEncoder.encode(value, "UTF-8") else value
    }

// ---------------------------------------------------------------- numéros et dates

private val CHAPTER_NUMBER = Regex(
    "(?:ch(?:apter|apitre|\\.)?|[ée]p(?:isode)?\\.?|#)\\s*(\\d+(?:[.,]\\d+)?)",
    RegexOption.IGNORE_CASE,
)
private val ANY_NUMBER = Regex("(\\d+(?:[.,]\\d+)?)")

/** « Chapter 12.5 – Titre » → 12.5. */
fun guessChapterNumber(text: String): Float? {
    val m = CHAPTER_NUMBER.find(text) ?: ANY_NUMBER.find(text) ?: return null
    return m.groupValues[1].replace(',', '.').toFloatOrNull()
}

fun parseNumber(text: String): Float? = ANY_NUMBER.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()

private val RELATIVE = Regex(
    "(\\d+)\\s*(sec|second|seconde|min|minute|h|hr|hour|heure|d|day|jour|w|week|semaine|month|mois|y|yr|year|an|année)s?\\b",
    RegexOption.IGNORE_CASE,
)
private val DEFAULT_FORMATS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "MMM dd, yyyy", "MMMM dd, yyyy", "MMM d, yyyy", "dd/MM/yyyy", "dd.MM.yyyy",
)

/** Date en millisecondes : format donné, dates relatives (« 3 days ago », « il y a 2 h »), sinon formats courants. */
fun parseDate(text: String, format: String?, locale: Locale): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val lower = t.lowercase()
    val cal = Calendar.getInstance()
    when {
        lower.startsWith("today") || lower.startsWith("aujourd") || lower == "now" || lower.contains("just now") -> return cal.timeInMillis
        lower.startsWith("yesterday") || lower.startsWith("hier") -> return cal.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    }
    RELATIVE.find(lower)?.takeIf { "ago" in lower || "il y a" in lower || lower.matches(Regex("^\\d+\\s*\\w+$")) }?.let { m ->
        val n = m.groupValues[1].toInt()
        val field = when (m.groupValues[2].lowercase()) {
            "sec", "second", "seconde" -> Calendar.SECOND
            "min", "minute" -> Calendar.MINUTE
            "h", "hr", "hour", "heure" -> Calendar.HOUR_OF_DAY
            "d", "day", "jour" -> Calendar.DAY_OF_MONTH
            "w", "week", "semaine" -> Calendar.WEEK_OF_YEAR
            "month", "mois" -> Calendar.MONTH
            else -> Calendar.YEAR
        }
        cal.add(field, -n)
        return cal.timeInMillis
    }
    val formats = if (format != null) listOf(format) else DEFAULT_FORMATS
    for (f in formats) {
        val parsed = runCatching {
            SimpleDateFormat(f, locale).apply { isLenient = false }.parse(t)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}
