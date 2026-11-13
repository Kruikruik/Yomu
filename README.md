# Yomu — lecteur de mangas et webtoons (Android)

Appli Android inspirée de Tachiyomi, écrite en Kotlin + Jetpack Compose.

## Ce que fait l'appli

**v2** : onglets Nouveautés et Historique, téléchargements hors-ligne, tri/filtre/recherche
dans la bibliothèque, sélection multiple des chapitres (appui long), vérification automatique
des nouveaux chapitres avec notification, sauvegarde/restauration, et bouton
**Plus → Mettre à jour l'appli** qui installe la dernière version publiée sur GitHub.

- **Explorer** : séries populaires, récentes, recherche par titre (source : MangaDex, en anglais).
- **Fiche série** : couverture, auteur, statut, genres, résumé, liste des chapitres, bouton « Commencer / Continuer ».
- **Bibliothèque** : ajoute tes séries avec ♥, nombre de chapitres non lus, bouton ⟳ pour chercher les nouveaux chapitres.
- **Lecteur** (3 modes, changeables par série via ⚙) :
  - Webtoon : défilement vertical continu, zoom à deux doigts ou double-tap ;
  - Manga : pages de droite à gauche ;
  - Pages : de gauche à droite.
  - Toucher le centre = afficher le menu ; bords gauche/droit = page précédente/suivante (modes pages).
  - Barre pour sauter à une page, boutons chapitre précédent/suivant, préchargement des pages.
- Reprise automatique à la page où tu t'étais arrêté ; un chapitre est marqué lu quand tu arrives à la fin.
- Économiseur de données, cache d'images de 512 Mo (vidable dans Réglages).

Android 8.0 minimum.

## Obtenir l'APK (sans rien installer sur ton PC)

1. Crée un dépôt **vide** sur GitHub, par exemple `yomu`.
2. Envoie ce dossier dedans (depuis ton PC, dans le dossier `Yomu`) :
   ```bash
   git init
   git add .
   git commit -m "Yomu v1"
   git branch -M main
   git remote add origin https://github.com/TON_PSEUDO/yomu.git
   git push -u origin main
   ```
3. Va dans l'onglet **Actions** du dépôt : la compilation démarre toute seule (4 à 6 min).
4. Une fois terminée, ouvre sur ton téléphone :
   `https://github.com/TON_PSEUDO/yomu/releases/latest`
   et télécharge **Yomu.apk**. Autorise l'installation depuis ton navigateur si Android le demande.

Chaque `git push` recompile l'appli. Comme la clé de signature est fixe (`app/yomu.keystore`),
la nouvelle version s'installe par-dessus l'ancienne sans perdre ta bibliothèque.

> Le dépôt peut être privé : le lien Releases marchera tant que tu es connecté à GitHub sur ton téléphone.

## Compiler sur ton PC (optionnel)

Avec Android Studio : *Open* → dossier `Yomu` → *Build > Build APK(s)*.
Ou en ligne de commande (JDK 17 + SDK Android installés) : `./gradlew assembleDebug`
→ l'APK sort dans `app/build/outputs/apk/debug/`.

## Ajouter une source

1. Crée une classe dans `app/src/main/java/com/yomu/reader/source/` qui implémente l'interface `Source`
   (`popular`, `latest`, `search`, `details`, `chapters`, `pages`). `MangaDexSource.kt` sert d'exemple.
2. Ajoute-la dans la liste `SourceManager.all` (fichier `Source.kt`).

## Structure

```
app/src/main/java/com/yomu/reader/
├── MainActivity.kt / YomuApplication.kt   démarrage, cache d'images
├── model/      Manga, Chapter
├── net/        client HTTP
├── source/     interface Source + MangaDex
├── data/       bibliothèque, progression, réglages (fichier JSON local)
└── ui/         écrans : Bibliothèque, Explorer, Fiche, Lecteur, Réglages
```
