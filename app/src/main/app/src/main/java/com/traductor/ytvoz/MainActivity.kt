package com.traductor.ytvoz

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var doblajeActivo = true
    private var ultimaFrase = ""

    @SuppressLint("SetJavaScriptEnabled", "UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        // Interfaz limpia generada por código (sin necesidad de archivos XML extra)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val barraSuperior = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val inputUrl = EditText(this).apply {
            hint = "Pega enlace de YouTube o busca..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnIr = Button(this).apply {
            text = "Ir"
        }

        val switchVoz = Switch(this).apply {
            text = "Voz ES "
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                doblajeActivo = isChecked
                if (!isChecked) tts.stop()
                ajustarVolumenVideo(if (isChecked) 0.15 else 1.0)
            }
        }

        barraSuperior.addView(inputUrl)
        barraSuperior.addView(btnIr)
        barraSuperior.addView(switchVoz)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(PuenteTraductor(), "AndroidTraductor")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    inyectarExtractorTiempoReal()
                }
            }
        }

        btnIr.setOnClickListener {
            val texto = inputUrl.text.toString().trim()
            if (texto.startsWith("http")) {
                webView.loadUrl(texto)
            } else if (texto.isNotEmpty()) {
                webView.loadUrl("https://m.youtube.com/results?search_query=${URLEncoder.encode(texto, "UTF-8")}")
            }
        }

        layout.addView(barraSuperior)
        layout.addView(webView)
        setContentView(layout)

        // Si abriste la app desde el botón "Compartir" de YouTube, carga ese video directo
        val urlCompartida = obtenerUrlCompartida(intent)
        if (urlCompartida != null) {
            webView.loadUrl(urlCompartida)
        } else {
            webView.loadUrl("https://m.youtube.com")
        }
    }

    private fun obtenerUrlCompartida(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val texto = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            val regex = "(https?://\\S+)".toRegex()
            return regex.find(texto)?.value
        }
        return null
    }

    private fun ajustarVolumenVideo(volumen: Double) {
        webView.evaluateJavascript(
            "var v = document.querySelector('video'); if(v) v.volume = $volumen;",
            null
        )
    }

    private fun inyectarExtractorTiempoReal() {
        // Script que baja el volumen original al 15%, activa subtítulos si están ocultos y captura el texto en vivo
        val js = """
            (function() {
                if (window.traductorInyectado) return;
                window.traductorInyectado = true;

                setInterval(function() {
                    var video = document.querySelector('video');
                    if (video && !video.paused) {
                        // Atenúa el audio original para escuchar la voz en español encima
                        if (video.volume > 0.2) video.volume = 0.15;
                    }

                    // Captura los segmentos de subtítulos generados en pantalla en tiempo real
                    var nodos = document.querySelectorAll('.ytp-caption-segment, .caption-window, .ytm-caption-segment');
                    if (nodos.length > 0) {
                        var textoActual = Array.from(nodos).map(n => n.innerText).join(' ').trim();
                        if (textoActual.length > 1) {
                            AndroidTraductor.procesarTexto(textoActual);
                        }
                    }
                }, 400);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class PuenteTraductor {
        @JavascriptInterface
        fun procesarTexto(texto: String) {
            if (!doblajeActivo) return
            val limpio = texto.replace("\n", " ").trim()
            if (limpio.isEmpty() || limpio == ultimaFrase || ultimaFrase.contains(limpio)) return
            ultimaFrase = limpio

            thread {
                try {
                    val q = URLEncoder.encode(limpio, "UTF-8")
                    val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=es&dt=t&q=$q")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                    val respuesta = conn.inputStream.bufferedReader().readText()
                    val json = JSONArray(respuesta).getJSONArray(0)
                    val traduccion = StringBuilder()
                    for (i in 0 until json.length()) {
                        traduccion.append(json.getJSONArray(i).getString(0))
                    }

                    val textoEspanol = traduccion.toString()
                    tts.speak(textoEspanol, TextToSpeech.QUEUE_ADD, null, System.currentTimeMillis().toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            tts.setSpeechRate(1.18f) // Ligeramente acelerado para seguir el ritmo natural del video
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
