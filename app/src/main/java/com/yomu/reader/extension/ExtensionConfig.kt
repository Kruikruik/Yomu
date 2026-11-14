package com.yomu.reader.extension

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.select.QueryParser
import java.util.Locale

/*
 * Schéma des fichiers d'extension JSON (version 1). Voir docs/extensions.md.
 * L'appli ne contient aucune extension : l'utilisateur importe les siennes.
 */

const val SCHEMA_VERSION = 1

class ConfigException(message: String) : Exception(message)

/**
 * Comment extraire une valeur d'un élément HTML.
 *
 * Forme courte (texte) : "h1.title" (texte), "img@src" (attribut), "@href" (attribut de l'élément courant),
 * "img@data-src|src" (premier attribut non vide), "regex:motif" (regex sur le HTML de l'élément).
 * Forme longue (objet) : voir [parseField].
 */
data class Field(
    /** Sélecteur CSS appliqué à l'élément courant ; null = l'élément courant lui-même. */
    val css: String? = null,
    /** text | ownText | html | outerHtml | nom d'attribut. Plusieurs séparés par « | » = le premier non vide. */
    val attr: String = "text",
    /** Regex appliquée à la valeur ; on garde le groupe [group] (0 = tout). */
    val regex: Regex? = null,
    val group: Int = 1,
    /** Remplacements appliqués dans l'ordre (regex → texte). */
    val replace: List<Pair<Regex, String>> = emptyList(),
    /** Modèle final, ex. "{baseUrl}/img/{value}.jpg". */
    val template: String? = null,
)

/** Une requête HTTP. Variables : {baseUrl} {page} {page0} {query} {id} {url}. */
data class RequestSpec(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val contentType: String = "application/x-www-form-urlencoded",
    val headers: Map<String, String> = emptyMap(),
)

/** Liste de séries (populaires, récentes, recherche). */
data class ListSpec(
    val request: RequestSpec,
    val item: String,
    val title: Field,
    val link: Field,
    val cover: Field?,
    /** Si ce champ trouve quelque chose, il y a une page suivante. */
    val nextPage: Field?,
)

data class DetailsSpec(
    /** Facultatif : par défaut, la page de la série ({url}). */
    val request: RequestSpec?,
    val title: Field?,
    val cover: Field?,
    val author: Field?,
    val description: Field?,
    val status: Field?,
    val genres: Field?,
    /** Texte du site → statut affiché, ex. {"Ongoing": "En cours"}. */
    val statusMap: Map<String, String>,
)

data class ChaptersSpec(
    val request: RequestSpec?,
    val item: String,
    val link: Field,
    val title: Field?,
    val number: Field?,
    val date: Field?,
    val dateFormat: String?,
    val dateLocale: Locale,
    val scanlator: Field?,
    /** true si le site liste du plus ancien au plus récent. */
    val reverse: Boolean,
    /** Lien vers la page suivante de la liste de chapitres. */
    val nextPage: Field?,
)

data class PagesSpec(
    val request: RequestSpec?,
    /** Toutes les correspondances sont gardées, dans l'ordre. */
    val image: Field,
    /** Lien vers la page suivante, pour les lecteurs « une image par page ». */
    val nextPage: Field?,
)

data class ExtensionConfig(
    val id: String,
    val name: String,
    val lang: String,
    val version: Int,
    val baseUrl: String,
    val description: String?,
    val author: String?,
    /** null = user-agent de Yomu ; "webview" = celui du navigateur intégré ; sinon la valeur donnée. */
    val userAgent: String?,
    val headers: Map<String, String>,
    val imageHeaders: Map<String, String>,
    /** Cookies posés au premier chargement (ex. confirmation d'âge, langue). */
    val cookies: Map<String, String>,
    val rateLimitMs: Long,
    val popular: ListSpec?,
    val latest: ListSpec?,
    val search: ListSpec?,
    val details: DetailsSpec,
    val chapters: ChaptersSpec,
    val pages: PagesSpec,
) {
    companion object {
        private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{1,40}$")

        /** Lit et valide un fichier d'extension. Lève [ConfigException] avec un message clair. */
        fun parse(text: String): ExtensionConfig {
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ConfigException("JSON invalide : ${e.message}")
            }
            val schema = o.optInt("schema", 1)
            if (schema > SCHEMA_VERSION) throw ConfigException("Schéma $schema non géré (max $SCHEMA_VERSION) : mets Yomu à jour")

            val id = o.req("id").lowercase()
            if (!ID_RE.matches(id)) throw ConfigException("« id » : lettres minuscules, chiffres et tirets uniquement (2 à 41 caractères)")

            val baseUrl = o.req("baseUrl").trimEnd('/')
            val parsed = baseUrl.toHttpUrlOrNull()
            if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
                throw ConfigException("« baseUrl » doit être une adresse http(s) valide")
            }

            val popular = o.optJSONObject("popular")?.let { parseList(it, "popular") }
            val latest = o.optJSONObject("latest")?.let { parseList(it, "latest") }
            val search = o.optJSONObject("search")?.let { parseList(it, "search") }
            if (popular == null && latest == null && search == null) {
                throw ConfigException("Il faut au moins un des blocs « popular », « latest » ou « search »")
            }
            if (search != null && "{query}" !in search.request.url && search.request.body?.contains("{query}") != true) {
                throw ConfigException("« search » : l'URL ou le corps doit contenir {query}")
            }

            return ExtensionConfig(
                id = id,
                name = o.req("name"),
                lang = o.optString("lang", "en").ifBlank { "en" },
                version = o.optInt("version", 1),
                baseUrl = baseUrl,
                description = o.optStringOrNull("description"),
                author = o.optStringOrNull("author"),
                userAgent = o.optStringOrNull("userAgent"),
                headers = o.optJSONObject("headers").toStringMap(),
                imageHeaders = o.optJSONObject("imageHeaders").toStringMap(),
                cookies = o.optJSONObject("cookies").toStringMap(),
                rateLimitMs = o.optLong("rateLimitMs", 250L).coerceIn(0L, 10_000L),
                popular = popular,
                latest = latest,
                search = search,
                details = parseDetails(o.optJSONObject("details") ?: JSONObject()),
                chapters = parseChapters(o.optJSONObject("chapters") ?: throw ConfigException("Bloc « chapters » manquant")),
                pages = parsePages(o.optJSONObject("pages") ?: throw ConfigException("Bloc « pages » manquant")),
            )
        }

        private fun parseRequest(o: JSONObject, where: String, required: Boolean): RequestSpec? {
            val url = o.optStringOrNull("url") ?: if (required) throw ConfigException("« $where.url » manquant") else return null
            val method = o.optString("method", "GET").uppercase()
            if (method != "GET" && method != "POST") throw ConfigException("« $where.method » : GET ou POST")
            return RequestSpec(
                url = url,
                method = method,
                body = o.optStringOrNull("body"),
                contentType = o.optString("contentType", "application/x-www-form-urlencoded"),
                headers = o.optJSONObject("headers").toStringMap(),
            )
        }

        private fun parseList(o: JSONObject, where: String) = ListSpec(
            request = parseRequest(o, where, required = true)!!,
            item = o.css("item", where),
            title = o.field("title", where) ?: throw ConfigException("« $where.title » manquant"),
            link = o.field("link", where) ?: Field(css = "a", attr = "href"),
            cover = o.field("cover", where),
            nextPage = o.field("nextPage", where),
        )

        private fun parseDetails(o: JSONObject) = DetailsSpec(
            request = parseRequest(o, "details", required = false),
            title = o.field("title", "details"),
            cover = o.field("cover", "details"),
            author = o.field("author", "details"),
            description = o.field("description", "details"),
            status = o.field("status", "details"),
            genres = o.field("genres", "details"),
            statusMap = o.optJSONObject("statusMap").toStringMap(),
        )

        private fun parseChapters(o: JSONObject) = ChaptersSpec(
            request = parseRequest(o, "chapters", required = false),
            item = o.css("item", "chapters"),
            link = o.field("link", "chapters") ?: Field(css = "a", attr = "href"),
            title = o.field("title", "chapters"),
            number = o.field("number", "chapters"),
            date = o.field("date", "chapters"),
            dateFormat = o.optStringOrNull("dateFormat"),
            dateLocale = Locale.forLanguageTag(o.optString("dateLocale", "en")),
            scanlator = o.field("scanlator", "chapters"),
            reverse = o.optBoolean("reverse", false),
            nextPage = o.field("nextPage", "chapters"),
        )

        private fun parsePages(o: JSONObject) = PagesSpec(
            request = parseRequest(o, "pages", required = false),
            image = o.field("image", "pages") ?: throw ConfigException("« pages.image » manquant"),
            nextPage = o.field("nextPage", "pages"),
        )

        // ------------------------------------------------------------- champs

        private val SHORT_ATTR = Regex("^(.*)@([A-Za-z0-9_:|-]+)$")

        /** Champ : chaîne courte ou objet {css, attr, regex, group, replace, template}. */
        private fun JSONObject.field(key: String, where: String): Field? {
            if (!has(key) || isNull(key)) return null
            val path = "$where.$key"
            return when (val v = get(key)) {
                is String -> parseShort(v, path)
                is JSONObject -> Field(
                    css = v.optStringOrNull("css")?.also { checkCss(it, "$path.css") },
                    attr = v.optString("attr", "text").ifBlank { "text" },
                    regex = v.optStringOrNull("regex")?.let { compile(it, "$path.regex") },
                    group = v.optInt("group", 1),
                    replace = v.optJSONArray("replace").toReplacements("$path.replace"),
                    template = v.optStringOrNull("template"),
                )
                else -> throw ConfigException("« $path » : texte ou objet attendu")
            }
        }

        private fun parseShort(s: String, path: String): Field {
            val text = s.trim()
            if (text.startsWith("regex:")) return Field(attr = "outerHtml", regex = compile(text.removePrefix("regex:"), path))
            val m = SHORT_ATTR.find(text)
            val (css, attr) = if (m != null) m.groupValues[1].trim() to m.groupValues[2] else text to "text"
            val sel = css.ifBlank { null }?.also { checkCss(it, path) }
            return Field(css = sel, attr = attr)
        }

        private fun JSONObject.css(key: String, where: String): String {
            val v = optStringOrNull(key) ?: throw ConfigException("« $where.$key » manquant (sélecteur CSS)")
            checkCss(v, "$where.$key")
            return v
        }

        private fun checkCss(css: String, path: String) {
            try {
                QueryParser.parse(css)
            } catch (e: Exception) {
                throw ConfigException("« $path » : sélecteur CSS invalide « $css » (${e.message})")
            }
        }

        private fun compile(pattern: String, path: String): Regex = try {
            Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL))
        } catch (e: Exception) {
            throw ConfigException("« $path » : regex invalide (${e.message})")
        }

        private fun JSONArray?.toReplacements(path: String): List<Pair<Regex, String>> {
            if (this == null) return emptyList()
            return (0 until length()).map { i ->
                val pair = optJSONArray(i) ?: throw ConfigException("« $path » : liste de paires [\"regex\", \"remplacement\"]")
                compile(pair.optString(0), path) to pair.optString(1)
            }
        }

        private fun JSONObject.req(key: String): String =
            optStringOrNull(key) ?: throw ConfigException("Champ « $key » manquant")

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).trim().ifEmpty { null } else null

        private fun JSONObject?.toStringMap(): Map<String, String> {
            if (this == null) return emptyMap()
            return keys().asSequence().associateWith { optString(it) }
        }
    }
}
