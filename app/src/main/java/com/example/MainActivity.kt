package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme

/**
 * URL cible de l'application web React hébergée sur Vercel.
 */
private const val TARGET_URL = "https://cinema-now.vercel.app/"

/**
 * Vérifie si une connexion Internet active est actuellement disponible.
 */
fun isNetworkAvailable(context: Context): Boolean {
  val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    ?: return false
  val activeNetwork = connectivityManager.activeNetwork ?: return false
  val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
  return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Active le mode bord à bord (Edge-to-Edge) pour un rendu moderne et immersif
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme(darkTheme = true) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Color(0xFF0B0E14) // Fond cinéma sombre
        ) {
          CinemaWebViewScreen(url = TARGET_URL)
        }
      }
    }
  }
}

/**
 * Écran principal contenant la WebView responsive, plein écran et fluide,
 * avec gestion du retour arrière natif, support vidéo plein écran, barre de progression
 * et écran d'erreur hors-ligne.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CinemaWebViewScreen(
  url: String,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // Détection et écoute de la connectivité réseau pour le support hors-ligne
  var isOnline by remember { mutableStateOf(isNetworkAvailable(context)) }
  var isShowingCachedVersion by remember { mutableStateOf(!isNetworkAvailable(context)) }

  // Références et états de la WebView
  var reloadKey by remember { mutableIntStateOf(0) }
  var webViewInstance by remember { mutableStateOf<WebView?>(null) }
  var canGoBack by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(true) }
  var progress by remember { mutableFloatStateOf(0f) }
  var hasError by remember { mutableStateOf(false) }

  // Écoute dynamique des changements de réseau pour adapter le cacheMode à chaud
  DisposableEffect(context) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        isOnline = true
        isShowingCachedVersion = false
        webViewInstance?.post {
          webViewInstance?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
        }
      }

      override fun onLost(network: Network) {
        isOnline = false
        isShowingCachedVersion = true
        webViewInstance?.post {
          webViewInstance?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
      }
    }

    val request = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()

    try {
      cm?.registerNetworkCallback(request, callback)
    } catch (_: Exception) {}

    onDispose {
      try {
        cm?.unregisterNetworkCallback(callback)
      } catch (_: Exception) {}
    }
  }

  // États pour la lecture vidéo HTML5 en plein écran (bandes-annonces cinéma)
  var customView by remember { mutableStateOf<View?>(null) }
  var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

  // Gestion du bouton retour système (BackHandler)
  BackHandler(enabled = customView != null || canGoBack) {
    when {
      customView != null -> {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
      }
      canGoBack && webViewInstance != null -> {
        webViewInstance?.goBack()
      }
    }
  }

  // Synchronisation du cycle de vie Android avec la WebView (mise en pause des timers/médias)
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> webViewInstance?.onPause()
        Lifecycle.Event.ON_RESUME -> webViewInstance?.onResume()
        Lifecycle.Event.ON_DESTROY -> {
          webViewInstance?.destroy()
          webViewInstance = null
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    // 1. Conteneur WebView principal avec gestion de recréation propre en cas de crash
    key(reloadKey) {
      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Configuration avancée pour une navigation web ultra-fluide et support hors-ligne
            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true // Nécessaire pour localStorage, IndexedDB et l'état de l'application React
              loadWithOverviewMode = true
              useWideViewPort = true // Respecte la balise meta viewport du site responsive
              setSupportZoom(true)
              builtInZoomControls = true
              displayZoomControls = false // Masque les contrôles de zoom superposés obsolètes
              mediaPlaybackRequiresUserGesture = true // Évite le décodage vidéo sauvage dès le chargement

              // Gestion intelligente du cache :
              // Connecté : LOAD_DEFAULT (charge en réseau et met à jour le cache)
              // Hors-connexion : LOAD_CACHE_ELSE_NETWORK (charge la version locale en cache)
              cacheMode = if (isNetworkAvailable(ctx)) {
                WebSettings.LOAD_DEFAULT
              } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
              }

              mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
              allowFileAccess = false
              allowContentAccess = false
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
              }
            }

            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            // Client WebView pour gérer la navigation interne, les erreurs et la disparition du moteur de rendu
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
              ): Boolean {
                val target = request?.url ?: return false
                val host = target.host ?: ""

                // Maintient les URLs du site et de son domaine au sein de la WebView
                return if (host.contains("cinema-now.vercel.app") || host.contains("vercel.app")) {
                  false
                } else {
                  // Ouvre les liens externes (téléphone, courriel, réseaux sociaux) via l'application externe
                  try {
                    val intent = Intent(Intent.ACTION_VIEW, target)
                    context.startActivity(intent)
                    true
                  } catch (_: Exception) {
                    false
                  }
                }
              }

              override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                hasError = false
                canGoBack = view?.canGoBack() == true
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                canGoBack = view?.canGoBack() == true
                if (!isOnline) {
                  isShowingCachedVersion = true
                }
              }

              override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
              ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                  // Si le chargement réseau échoue (ex: pas de connexion), bascule sur la version mise en cache
                  if (view?.settings?.cacheMode != WebSettings.LOAD_CACHE_ELSE_NETWORK) {
                    view?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    isShowingCachedVersion = true
                    view?.loadUrl(url)
                  } else {
                    hasError = true
                    isLoading = false
                    isShowingCachedVersion = false
                  }
                }
              }

              override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
              ): Boolean {
                // Empêche le plantage de l'application hôte quand le moteur de rendu Chromium crash
                try {
                  (view?.parent as? ViewGroup)?.removeView(view)
                  view?.destroy()
                } catch (_: Exception) {}
                webViewInstance = null
                hasError = true
                isLoading = false
                return true
              }
            }

            // Client WebChrome pour la progression et les vidéos plein écran
            webChromeClient = object : WebChromeClient() {
              override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progress = newProgress / 100f
                if (newProgress == 100) {
                  isLoading = false
                }
                canGoBack = view?.canGoBack() == true
              }

              override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view
                customViewCallback = callback
              }

              override fun onHideCustomView() {
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
              }
            }

            loadUrl(url)
            webViewInstance = this
          }
        },
        onRelease = { view ->
          if (webViewInstance == view) {
            webViewInstance = null
          }
          view.destroy()
        },
        modifier = Modifier
          .fillMaxSize()
          .testTag("cinema_webview")
      )
    }

    // 2. Barre de progression discrète en haut d'écran pendant le chargement
    AnimatedVisibility(
      visible = isLoading && !hasError,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.TopCenter)
        .windowInsetsPadding(WindowInsets.statusBars)
    ) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp)
          .testTag("loading_progress_bar"),
        color = Color(0xFFF5C518), // Jaune or cinéma
        trackColor = Color(0x33F5C518)
      )
    }

    // 3. Écran d'erreur et de reconnexion élégant en cas de perte de connexion
    if (hasError) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF0B0E14))
          .padding(24.dp)
          .testTag("error_container"),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = "Pas de connexion internet",
            tint = Color(0xFFF5C518),
            modifier = Modifier.size(64.dp)
          )

          Spacer(modifier = Modifier.height(16.dp))

          Text(
            text = "Connexion impossible",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
          )

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = "Vérifiez votre connexion Internet pour accéder à Cinema Now.",
            fontSize = 14.sp,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center
          )

          Spacer(modifier = Modifier.height(24.dp))

          Button(
            onClick = {
              hasError = false
              isLoading = true
              isShowingCachedVersion = false
              reloadKey++
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFFF5C518),
              contentColor = Color(0xFF0B0E14)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag("retry_button")
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = "Réessayer", fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }

    // 4. Indicateur discret de version hors-ligne en cache
    AnimatedVisibility(
      visible = (!isOnline || isShowingCachedVersion) && !hasError && !isLoading,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(bottom = 16.dp)
    ) {
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xEE1E293B),
        shadowElevation = 6.dp,
        modifier = Modifier.testTag("offline_cached_banner")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = "Mode hors-ligne",
            tint = Color(0xFFF5C518),
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Mode hors-ligne (version en cache)",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }
    }

    // 5. Affichage plein écran d'une vidéo HTML5 si lancée (CustomView)
    if (customView != null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black)
          .testTag("fullscreen_video_container")
      ) {
        AndroidView(
          factory = { customView!! },
          modifier = Modifier.fillMaxSize()
        )
      }
    }
  }
}

/**
 * Fonction Greeting conservée pour la compatibilité avec les tests unitaires / de capture.
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}
