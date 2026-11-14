# Extensions JSON de Yomu

Yomu n'embarque aucune extension. Chaque utilisateur écrit ou importe ses propres fichiers
(Explorer → Extensions → Importer). Exemple complet : [exemple-extension.json](exemple-extension.json).

## Champs principaux

| Champ | Obligatoire | Rôle |
|---|---|---|
| `schema` | non (1) | Version du format |
| `id` | oui | Identifiant stable : minuscules, chiffres, tirets. **Ne pas le changer** : les séries de la bibliothèque y sont liées |
| `name`, `lang`, `version`, `author`, `description` | `name` oui | Affichage. Un fichier avec le même `id` et une `version` plus haute = mise à jour |
| `baseUrl` | oui | `https://site.tld`, sans `/` final |
| `userAgent` | non | Absent = user-agent de Yomu, `"webview"` = celui du navigateur intégré (conseillé si tu te connectes au site), ou une valeur libre |
| `headers`, `imageHeaders` | non | En-têtes ajoutés aux pages / aux images. `Referer` vaut `{baseUrl}/` par défaut |
| `cookies` | non | Cookies posés au premier chargement s'ils n'existent pas (langue, confirmation d'âge…) |
| `rateLimitMs` | non (250) | Délai minimum entre deux requêtes |
| `popular`, `latest`, `search` | au moins un | Listes de séries |
| `details` | non | Fiche de la série (sinon : balises `og:title`, `og:image`, `og:description`) |
| `chapters`, `pages` | oui | Liste des chapitres, images d'un chapitre |

## Requêtes

Chaque bloc peut avoir `url`, `method` (`GET`/`POST`), `body`, `contentType`, `headers`.
Variables : `{baseUrl}`, `{page}` (1, 2, 3…), `{page0}` (0, 1, 2…), `{query}` (encodée), `{id}`, `{url}`.

Pour `details`, `chapters` et `pages`, l'URL par défaut est l'adresse de l'élément (`{url}`).
Exemple de chapitres chargés par une autre adresse : `"url": "{baseUrl}/ajax/chapters/{id}"`.

## Sélecteurs (champs)

Forme courte :

| Écriture | Résultat |
|---|---|
| `"h3.title"` | texte de l'élément |
| `"a.link@href"` | attribut `href` |
| `"img@data-src\|src"` | premier attribut non vide |
| `"@href"` | attribut de l'élément courant (`item`) |
| `"regex:\"(https://[^\"]+\\.jpg)\""` | regex sur le HTML de l'élément (groupe 1) |

Forme longue :

```json
"image": {
  "css": "script",
  "attr": "html",
  "regex": "\"(https?://[^\"]+\\.(?:jpg|png|webp))\"",
  "group": 1,
  "replace": [["\\\\/", "/"]],
  "template": "{value}"
}
```

`attr` : `text`, `ownText`, `html`, `outerHtml` ou un nom d'attribut. Les liens et images relatifs
sont convertis en adresses complètes automatiquement.

## Listes de séries (`popular`, `latest`, `search`)

`item` (CSS, obligatoire) = un bloc par série ; `title` (obligatoire), `link` (défaut `a@href`), `cover`
sont cherchés **dans** ce bloc. `nextPage` : s'il trouve quelque chose, il y a une page suivante
(sans lui : page suivante tant que la liste n'est pas vide et que l'URL contient `{page}`).

## Chapitres

`item`, `link` (défaut `a@href`), `title` (défaut : texte du lien), `number` (défaut : deviné dans le titre),
`date` + `dateFormat` (format Java, ex. `MMM dd, yyyy`) + `dateLocale`, `scanlator`.
Les dates relatives (`3 days ago`, `il y a 2 h`, `yesterday`) sont reconnues.
`reverse: true` si le site liste du plus ancien au plus récent. `nextPage` = lien vers la page suivante de la liste.

## Pages

`image` : toutes les correspondances, dans l'ordre. `nextPage` : pour les lecteurs « une image par page ».

## Sessions et connexion

Les cookies sont partagés avec le navigateur intégré et gardés d'un lancement à l'autre.
Pour un site qui demande une connexion : Extensions → ⋮ → « Ouvrir le site », connecte-toi,
puis reviens. Mets `"userAgent": "webview"` pour que les requêtes ressemblent à celles du navigateur.

## Désactiver / supprimer

Désactivée : l'extension disparaît d'Explorer et de la recherche globale, mais les séries déjà
dans la bibliothèque restent lisibles. Supprimée : ces séries ne trouvent plus leur source.
