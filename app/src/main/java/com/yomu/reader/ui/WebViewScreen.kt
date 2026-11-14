package com.yomu.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yomu.reader.data.Bookmarks
import com.yomu.reader.data.hostOf
import com.yomu.reader.data.parseUserUrl
import com.yomu.reader.net.WebCookies

/**
 * Navigateur intégré. L'adresse est passée en paramètre (ou tapée par l'utilisateur) :
 * aucun site n'est codé en dur. Sans adresse, on reprend la dernière page visitée.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(initialUrl: String?, onBack: () -> Unit, onOpenBookmarks: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bookmarks by Bookmarks.items.collectAsState()

    var currentUrl by rememberSaveable {
        mutableStateOf(initialUrl?.let(::parseUserUrl) ?: Bookmarks.lastUrl.value)
    }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(currentUrl == null) }
    var address by remember { mutableStateOf(TextFieldValue(currentUrl.orEmpty())) }
    var menuOpen by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val focusRequester = remember { FocusRequester() }

    val isBookmarked = remember(bookmarks, currentUrl) { currentUrl?.let(Bookmarks::isBookmarked) == true }

    fun load(input: String) {
        val url = parseUserUrl(input)
        if (url == null) {
            Toast.makeText(context, "Adresse invalide", Toast.LENGTH_SHORT).show()
            return
        }
        editing = false
        currentUrl = url
        webView?.loadUrl(url)
    }

    fun startEditing() {
        val text = currentUrl.orEmpty()
        address = TextFieldValue(text, selection = TextRange(0, text.length))
        editing = true
    }

    // Retour : d'abord fermer la saisie, puis remonter l'historique du site, puis quitter.
    BackHandler(enabled = (editing && currentUrl != null) || canGoBack) {
        if (editing) {
            editing = false
        } else {
            webView?.goBack()
        }
    }

    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }

    // Pause / reprise de la WebView et écriture des cookies sur le disque.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    webView?.onPause()
                    WebCookies.flush()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            WebCookies.flush()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Fermer") }
                    },
                    title = {
                        if (editing) {
                            TextField(
                                value = address,
                                onValueChange = { address = it },
                                singleLine = true,
                                placeholder = { Text("Adresse du site") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { load(address.text) }),
                                trailingIcon = {
                                    if (address.text.isNotEmpty()) {
                                        IconButton(onClick = { address = TextFieldValue("") }) {
                                            Icon(Icons.Default.Close, contentDescription = "Effacer")
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 8.dp)
                                    .focusRequester(focusRequester),
                            )
                        } else {
                            Column(Modifier.fillMaxWidth().clickable { startEditing() }) {
                                val url = currentUrl.orEmpty()
                                Text(
                                    pageTitle.ifBlank { hostOf(url) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    hostOf(url),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (!editing && currentUrl != null) {
                            IconButton(onClick = {
                                val url = currentUrl ?: return@IconButton
                                val added = Bookmarks.toggle(pageTitle, url)
                                Toast.makeText(context, if (added) "Ajouté aux favoris" else "Retiré des favoris", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    if (isBookmarked) Icons.Default.Star else YomuIcons.StarBorder,
                                    contentDescription = "Favori",
                                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                if (progress in 1..99) webView?.stopLoading() else webView?.reload()
                            }) {
                                Icon(
                                    if (progress in 1..99) Icons.Default.Close else Icons.Default.Refresh,
                                    contentDescription = if (progress in 1..99) "Arrêter" else "Recharger",
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Page précédente") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                                    enabled = canGoBack,
                                    onClick = { menuOpen = false; webView?.goBack() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Page suivante") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                                    enabled = canGoForward,
                                    onClick = { menuOpen = false; webView?.goForward() },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Mes favoris") },
                                    onClick = { menuOpen = false; onOpenBookmarks() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Version ordinateur") },
                                    trailingIcon = { Checkbox(checked = desktopMode, onCheckedChange = null) },
                                    onClick = {
                                        menuOpen = false
                                        desktopMode = !desktopMode
                                        webView?.let { it.applyDesktopMode(desktopMode); it.reload() }
                                    },
                                )
                                val url = currentUrl
                                if (url != null) {
                                    DropdownMenuItem(
                                        text = { Text("Copier le lien") },
                                        onClick = {
                                            menuOpen = false
                                            clipboard.setText(AnnotatedString(url))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ouvrir dans le navigateur") },
                                        onClick = { menuOpen = false; context.openExternally(Uri.parse(url)) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Se déconnecter de ce site") },
                                        onClick = {
                                            menuOpen = false
                                            val n = WebCookies.clearFor(url)
                                            Toast.makeText(context, "$n cookie(s) supprimé(s)", Toast.LENGTH_SHORT).show()
                                            webView?.reload()
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        configure()
                        applyDesktopMode(desktopMode)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title.orEmpty()
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                handleSpecialScheme(view, request.url)

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                currentUrl = url
                                pageTitle = ""
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                                Bookmarks.onVisited(url)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                progress = 100
                                pageTitle = view.title.orEmpty()
                                WebCookies.flush()
                            }
                        }
                        // Fichiers à télécharger : on laisse le navigateur du téléphone s'en charger.
                        setDownloadListener { url, _, _, _, _ -> ctx.openExternally(Uri.parse(url)) }
                        currentUrl?.let { loadUrl(it) }
                        webView = this
                    }
                },
                onRelease = { wv ->
                    WebCookies.flush()
                    wv.stopLoading()
                    wv.destroy()
                    webView = null
                },
            )

            // Saisie d'adresse : suggestions tirées des favoris.
            val query = address.text.trim()
            val matches = remember(bookmarks, query) {
                bookmarks
                    .filter { query.isEmpty() || it.title.contains(query, true) || it.url.contains(query, true) }
                    .sortedByDescending { maxOf(it.lastVisited, it.addedAt) }
                    .take(12)
            }
            if (editing && (matches.isNotEmpty() || currentUrl == null)) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    items(matches, key = { it.id }) { b ->
                        ListItem(
                            headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { load(b.url) },
                        )
                    }
                    if (matches.isEmpty() && currentUrl == null) {
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("Tape l'adresse d'un site en haut.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Button(onClick = onOpenBookmarks) { Text("Gérer mes favoris") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- réglages WebView

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true // localStorage : beaucoup de sites y gardent la session
        loadWithOverviewMode = true
        useWideViewPort = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = true
        // Sécurité : pas d'accès aux fichiers du téléphone ni de pop-ups automatiques.
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false) // les liens « nouvel onglet » s'ouvrent ici
    }
    WebCookies.attach(this)
}

/** Version ordinateur : user-agent de bureau dérivé de celui du téléphone. */
private fun WebView.applyDesktopMode(enabled: Boolean) {
    settings.userAgentString = if (enabled) {
        WebSettings.getDefaultUserAgent(context)
            .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile", "")
    } else {
        null // null = user-agent par défaut
    }
}

/** http(s) reste dans la WebView ; le reste (mailto:, intent:, market:…) part vers une autre appli. */
private fun handleSpecialScheme(view: WebView, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") return false
    if (scheme == "intent") {
        val intent = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return true
        // On n'autorise que les applis « navigables », jamais un composant ciblé directement.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val opened = runCatching { view.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!opened) {
            intent.getStringExtra("browser_fallback_url")?.let(::parseUserUrl)?.let { view.loadUrl(it) }
        }
        return true
    }
    view.context.openExternally(uri)
    return true
}

private fun Context.openExternally(uri: Uri) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(this, "Aucune appli pour ouvrir ce lien", Toast.LENGTH_SHORT).show()
    }
}
