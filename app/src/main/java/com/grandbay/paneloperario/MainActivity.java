package com.grandbay.paneloperario;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Panel Operario RCMS.
 *
 * - El panel se muestra en pantalla completa en el navegador principal.
 * - Un vigilante dentro de la página avisa al instante si aparece
 *   "User account or authorization expired" o el formulario de login.
 * - El inicio de sesión se hace en un navegador OCULTO (detrás del panel),
 *   que comparte las cookies; al terminar se recarga el panel.
 */
public class MainActivity extends Activity {

    // ======== CONFIGURACIÓN (edite aquí si cambia algo) ========
    static final String TARGET_URL = "http://10.20.1.4:8181/rcms/web/cms/PanelOperario/";
    static final String TARGET_KEY = "PanelOperario";
    static final String LOGIN_URL  = "http://10.20.1.4:8181/";
    static final String USER = "admin";
    static final String PASS = "12Hik34++";
    // ===========================================================

    static final long RECONNECT_TIMEOUT_MS = 20_000;  // máximo por intento de login
    static final int  MAX_RECONNECT_TRIES  = 3;       // intentos seguidos antes de pedir login manual
    static final long RETRY_OFFLINE_MS     = 8_000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView web;        // panel visible
    private WebView bg;         // navegador oculto para iniciar sesión
    private View overlay;       // "Reconectando…"
    private View errorView;
    private TextView errorText;

    private String autoLoginFn;
    private boolean mainFrameError = false;

    // Estado de la reconexión
    private boolean reconnecting = false;
    private int reconnectTries = 0;
    private int bgSubmits = 0;
    private long bgLastSubmit = 0;
    private long bgPageAt = 0;
    private int noFormStreak = 0;
    private long lastReconnectOk = 0;
    private int quickRepeats = 0;
    private boolean manualMode = false;

    // ---------------------------------------------------------------- ciclo de vida

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            // Usar también la zona de la cámara/notch: sin franja negra arriba
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // El oculto va PRIMERO (queda detrás del panel, pero con tamaño real para que la página funcione)
        bg = new WebView(this);
        configure(bg);
        bg.setWebViewClient(new BgClient());
        root.addView(bg, full);

        web = new WebView(this);
        configure(web);
        web.addJavascriptInterface(new Bridge(), "PanelApp");
        web.setWebViewClient(new MainClient());
        root.addView(web, full);

        root.addView(buildOverlay(), full);
        root.addView(buildErrorView(), full);
        setContentView(root);
        hideSystemBars();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(web, true);
            cm.setAcceptThirdPartyCookies(bg, true);
        }

        web.loadUrl(TARGET_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView v) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        v.setBackgroundColor(Color.parseColor("#3D4049"));
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        bg.onResume();
        hideSystemBars();
        if (errorView.getVisibility() == View.VISIBLE) web.loadUrl(TARGET_URL);
    }

    @Override protected void onPause() {
        super.onPause();
        web.onPause();
        bg.onPause();
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().flush();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        bg.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ---------------------------------------------------------------- vigilante en el panel

    /** La página llama aquí apenas detecta sesión vencida o formulario de login. */
    private class Bridge {
        @JavascriptInterface public void sessionLost(final String why) {
            handler.post(() -> startReconnect());
        }
    }

    private static final String WATCHER_JS =
        "(function(){if(window.__panelWatch)return;window.__panelWatch=1;" +
        "var EXP=/authorization\\s*ex\\s*pired|account or authorization|session (has )?(expired|timed? ?out)|not logged|please log ?in|\\u8fc7\\u671f|\\u91cd\\u65b0\\u767b\\u5f55|sesi[o\\u00f3]n (expir|venc|caduc)/i;" +
        "var sent=0;" +
        "function chk(){try{var b=document.body;if(!b)return;" +
        " var pw=document.querySelector('input[type=password]');" +
        " var lost=(pw&&pw.offsetParent!==null)||EXP.test((b.innerText||'').replace(/\\s+/g,' '));" +
        " if(lost){var n=Date.now();if(n-sent>3000){sent=n;PanelApp.sessionLost('x');}}" +
        "}catch(e){}}" +
        "var t=null;" +
        "try{new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=null;chk();},250);})" +
        ".observe(document.documentElement,{childList:true,subtree:true,characterData:true});}catch(e){}" +
        "setInterval(chk,2000);chk();})()";

    private void injectWatcher() {
        web.evaluateJavascript(WATCHER_JS, null);
    }

    // ---------------------------------------------------------------- reconexión en segundo plano

    private void startReconnect() {
        if (reconnecting || manualMode) return;
        long now = System.currentTimeMillis();

        // Si la sesión "vence" justo después de reconectar varias veces, algo más falla: login manual
        if (now - lastReconnectOk < 15_000) quickRepeats++; else quickRepeats = 0;
        if (quickRepeats >= 3) { goManual("El RCMS rechaza la sesión. Inicie sesión manualmente."); return; }

        reconnecting = true;
        reconnectTries = 0;
        overlay.setVisibility(View.VISIBLE);
        beginBgLogin();
    }

    private void beginBgLogin() {
        reconnectTries++;
        bgSubmits = 0;
        bgLastSubmit = 0;
        bgPageAt = 0;
        noFormStreak = 0;
        handler.removeCallbacks(bgTimeout);
        handler.postDelayed(bgTimeout, RECONNECT_TIMEOUT_MS);
        handler.removeCallbacks(bgStep);
        bg.loadUrl(LOGIN_URL);
    }

    private final Runnable bgTimeout = () -> {
        if (!reconnecting) return;
        if (reconnectTries < MAX_RECONNECT_TRIES) beginBgLogin();
        else goManual("No se pudo iniciar sesión automáticamente.");
    };

    /** Revisa la página oculta cada 300 ms: llena y envía el login, y detecta cuándo entró. */
    private final Runnable bgStep = new Runnable() {
        @Override public void run() {
            if (!reconnecting) return;
            bg.evaluateJavascript(loginScript(bg, "'detect'"), r -> {
                if (!reconnecting) return;
                long now = System.currentTimeMillis();
                boolean form = r != null && r.contains("FORM") && !r.contains("NOFORM");
                boolean expired = r != null && r.contains("EXPIRED");

                if (form || expired) noFormStreak = 0;
                if (form) {
                    if (bgSubmits >= 3 && now - bgLastSubmit > 4000) {
                        bgTimeout.run();       // la clave no la acepta: siguiente intento / manual
                        return;
                    }
                    if (bgLastSubmit == 0 || now - bgLastSubmit > 2500) {
                        bgSubmits++;
                        bgLastSubmit = now;
                        bg.evaluateJavascript(loginScript(bg, String.valueOf(bgSubmits)), null);
                    }
                } else if (expired) {
                    bg.evaluateJavascript(loginScript(bg, "'ok'"), null);
                } else if (bgSubmits > 0 && now - bgLastSubmit > 400 && bg.getProgress() == 100
                        && ++noFormStreak >= 2) {
                    reconnectDone();       // el formulario desapareció tras enviar y la página cargó: sesión iniciada
                    return;
                } else if (bgSubmits == 0 && bgPageAt > 0 && now - bgPageAt > 3000) {
                    reconnectDone();       // no pidió login: la sesión ya era válida
                    return;
                }
                handler.postDelayed(bgStep, 300);
            });
        }
    };

    private void reconnectDone() {
        reconnecting = false;
        manualMode = false;
        lastReconnectOk = System.currentTimeMillis();
        handler.removeCallbacks(bgTimeout);
        handler.removeCallbacks(bgStep);
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().flush();
        bg.loadUrl("about:blank");
        web.loadUrl(TARGET_URL);       // el overlay se quita cuando el panel termina de cargar
    }

    private void goManual(String msg) {
        reconnecting = false;
        manualMode = true;
        handler.removeCallbacks(bgTimeout);
        handler.removeCallbacks(bgStep);
        bg.loadUrl("about:blank");
        overlay.setVisibility(View.GONE);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        web.loadUrl(LOGIN_URL);
    }

    // ---------------------------------------------------------------- clientes web

    private class MainClient extends WebViewClient {
        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
            mainFrameError = false;
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (mainFrameError) { showError(); return; }
            errorView.setVisibility(View.GONE);
            if (url != null && url.contains(TARGET_KEY)) {
                manualMode = false;
                injectWatcher();
                if (!reconnecting) handler.postDelayed(() -> {
                    if (!reconnecting) overlay.setVisibility(View.GONE);
                }, 600);
            } else if (manualMode) {
                // Login manual: cuando entre, volver al panel
                handler.postDelayed(manualCheck, 1500);
            } else if (!reconnecting) {
                // Cayó en otra página (p.ej. el login): reconectar por detrás
                injectWatcher();
                startReconnect();
            }
        }

        @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
            if (url != null && url.contains(TARGET_KEY)) injectWatcher();
        }

        @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
            h.proceed(USER, PASS);
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (Build.VERSION.SDK_INT >= 23 && req.isForMainFrame()) {
                mainFrameError = true;
                errorText.setText(offlineMessage(String.valueOf(err.getDescription())));
            }
        }

        @SuppressWarnings("deprecation")
        @Override public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
            if (Build.VERSION.SDK_INT < 23) {
                mainFrameError = true;
                errorText.setText(offlineMessage(desc));
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return false; }
    }

    private final Runnable manualCheck = new Runnable() {
        @Override public void run() {
            if (!manualMode) return;
            web.evaluateJavascript(loginScript(web, "'detect'"), r -> {
                String url = web.getUrl() == null ? "" : web.getUrl();
                boolean form = r != null && r.contains("FORM") && !r.contains("NOFORM");
                if (!form && !url.startsWith("about:")) {
                    manualMode = false;
                    quickRepeats = 0;
                    if (!url.contains(TARGET_KEY)) web.loadUrl(TARGET_URL);
                } else {
                    handler.postDelayed(this, 1500);
                }
            });
        }
    };

    private class BgClient extends WebViewClient {
        @Override public void onPageFinished(WebView v, String url) {
            if (!reconnecting || url == null || url.startsWith("about:")) return;
            if (bgPageAt == 0) bgPageAt = System.currentTimeMillis();
            handler.removeCallbacks(bgStep);
            handler.postDelayed(bgStep, 200);
        }

        @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
            h.proceed(USER, PASS);
        }

        @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return false; }
    }

    // ---------------------------------------------------------------- script de login

    private String autoLoginFn() {
        if (autoLoginFn == null) {
            try (InputStream in = getAssets().open("autologin.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                autoLoginFn = out.toString("UTF-8");
            } catch (IOException e) {
                autoLoginFn = "function(){return 'NOFORM';}";
            }
        }
        return autoLoginFn;
    }

    private String loginScript(WebView v, String mode) {
        return "(" + autoLoginFn() + ")(" + JSONObject.quote(USER) + "," + JSONObject.quote(PASS)
                + "," + mode + "," + (v == web) + ")";
    }

    // ---------------------------------------------------------------- vistas auxiliares

    private View buildOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#E61B1F24"));
        box.setClickable(true);                 // tapa el aviso de "expired"

        ProgressBar pb = new ProgressBar(this);
        box.addView(pb);

        TextView t = new TextView(this);
        t.setText("Reconectando…");
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(16), 0, 0);
        box.addView(t);

        box.setVisibility(View.GONE);
        overlay = box;
        return box;
    }

    private String offlineMessage(String detail) {
        return "No se puede conectar al RCMS\n\nVerifique que el equipo esté conectado a la red WiFi de la planta.\n\n"
                + "Reintentando automáticamente…\n(" + detail + ")";
    }

    private final Runnable retry = () -> web.loadUrl(TARGET_URL);

    private void showError() {
        overlay.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_OFFLINE_MS);
    }

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#1B1F24"));
        int pad = dp(32);
        box.setPadding(pad, pad, pad, pad);

        errorText = new TextView(this);
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(18);
        errorText.setGravity(Gravity.CENTER);
        box.addView(errorText);

        Button b = new Button(this);
        b.setText("Reintentar ahora");
        b.setOnClickListener(x -> { handler.removeCallbacks(retry); web.loadUrl(TARGET_URL); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        box.addView(b, lp);

        box.setVisibility(View.GONE);
        errorView = box;
        return box;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}
