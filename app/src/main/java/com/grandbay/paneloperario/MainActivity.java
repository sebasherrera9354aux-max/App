package com.grandbay.paneloperario;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Environment;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.PopupMenu;
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
 * - Muestra el panel en pantalla completa y en modo escritorio.
 * - Un vigilante dentro de la página avisa al instante si aparece
 *   "User account or authorization expired" o el formulario de login.
 * - Entonces tapa la pantalla con "Conectando…", inicia sesión por detrás
 *   de esa cortina y vuelve al Panel Operario.
 */
public class MainActivity extends Activity {

    // ======== CONFIGURACIÓN (edite aquí si cambia algo) ========
    static final String TARGET_URL = "http://10.20.1.4:8181/rcms/web/cms/PanelOperario/";
    static final String TARGET_KEY = "PanelOperario";
    static final String LOGIN_URL  = "http://10.20.1.4:8181/";
    static final String USER = "admin";
    static final String PASS = "12Hik34++";
    static final int DESKTOP_WIDTH = 1366;      // ancho de "pantalla de computador" en píxeles CSS
    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/129.0.0.0 Safari/537.36";
    // ===========================================================

    static final long LOGIN_TIMEOUT_MS = 25_000;
    static final int  MAX_LOGIN_TRIES  = 3;
    static final long RETRY_OFFLINE_MS = 8_000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView web;
    private View overlay;
    private TextView overlayText;
    private View errorView;
    private TextView errorText;

    private String autoLoginFn;
    private boolean desktopMode = true;
    private boolean statsShown = false;
    private volatile String uaString = "";
    private final java.util.ArrayList<String> trail = new java.util.ArrayList<>();
    private boolean statsAfterManual = false;
    private boolean manualSawForm = false;     // pantalla de Estadísticas abierta
    static final String STATS_BASE = "http://10.20.1.4:8181/rcms/web/";
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_REQ = 7;
    private boolean mainFrameError = false;

    // WebView oculto con el panel real: desde ahí salen las consultas de Estadísticas
    private WebView helper;
    private boolean helperReady = false;
    private volatile String helperUrl = null;
    private volatile String lastVia = "-";
    private final java.util.ArrayList<String[]> helperQueue = new java.util.ArrayList<>();
    private final java.util.Map<String, String> hdrs = new java.util.concurrent.ConcurrentHashMap<>();

    // Estado del inicio de sesión
    private boolean loggingIn = false;
    private boolean manualMode = false;
    private int loginTries = 0;
    private int submits = 0;
    private long lastSubmit = 0;
    private long pageLoadedAt = 0;
    private int noFormStreak = 0;
    private long lastLoginOk = 0;
    private int quickRepeats = 0;

    // ---------------------------------------------------------------- ciclo de vida

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        prefs = getSharedPreferences("panel", MODE_PRIVATE);
        desktopMode = prefs.getBoolean("desktop", true);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        if (desktopMode) s.setUserAgentString(DESKTOP_UA);   // la página cree que es un computador
        uaString = s.getUserAgentString();
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);     // window.open / target=_blank se abren aquí mismo
        s.setAllowFileAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);        // ajusta el ancho de escritorio a la pantalla
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setBackgroundColor(Color.parseColor("#3D4049"));
        web.addJavascriptInterface(new Bridge(), "PanelApp");
        web.setWebViewClient(new MainClient());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, disp, mime, len) -> download(url, ua, disp, mime));
        root.addView(web, full);

        helper = new WebView(this);
        WebSettings hs = helper.getSettings();
        hs.setJavaScriptEnabled(true);
        hs.setDomStorageEnabled(true);
        hs.setUserAgentString(uaString);
        helper.addJavascriptInterface(new Bridge(), "PanelApp");
        helper.setWebViewClient(new HelperClient());
        helper.setAlpha(0f);
        root.addView(helper, new FrameLayout.LayoutParams(1, 1));   // invisible pero activo

        root.addView(buildMenuButton());

        root.addView(buildOverlay(), full);
        root.addView(buildErrorView(), full);
        setContentView(root);
        hideSystemBars();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        web.requestFocus();
        web.loadUrl(TARGET_URL);
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        hideSystemBars();
        if (errorView.getVisibility() == View.VISIBLE) web.loadUrl(TARGET_URL);
    }

    @Override protected void onPause() {
        super.onPause();
        web.onPause();
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().flush();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        helper.destroy();
        web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (statsShown) { showPanel(); return; }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        handler.postDelayed(() -> web.evaluateJavascript(desktopJs(), null), 300);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQ && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ---------------------------------------------------------------- modo escritorio + vigilante

    /** Ancho de computador (1366) ajustado para que la página completa quepa en la pantalla. */
    private String desktopJs() {
        if (!desktopMode) return "void 0";
        float density = getResources().getDisplayMetrics().density;
        float cssW = (web.getWidth() > 0 ? web.getWidth() : getResources().getDisplayMetrics().widthPixels) / density;
        float scale = Math.min(1f, cssW / DESKTOP_WIDTH);
        String c = String.format(java.util.Locale.US,
                "width=%d, initial-scale=%.4f, minimum-scale=%.4f, maximum-scale=4, user-scalable=yes",
                DESKTOP_WIDTH, scale, scale);
        return "(function(){try{var c='" + c + "';" +
            "var ms=document.querySelectorAll('meta[name=viewport]');for(var i=1;i<ms.length;i++)ms[i].remove();" +
            "var m=ms[0];if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
            "if(m.content!==c)m.content=c;}catch(e){}})()";
    }

    private static final String WATCHER_JS =
        "(function(){if(window.__panelWatch)return;window.__panelWatch=1;" +
        "var EXP=/account or authorization\\s*ex\\s*pired|authorization\\s*ex\\s*pired|session (has )?(expired|timed? ?out)|please log ?in again|\\u91cd\\u65b0\\u767b\\u5f55|sesi[o\\u00f3]n (expir|venc|caduc)/i;" +
        "var sent=0;" +
        "function chk(){try{var b=document.body;if(!b)return;" +
        " var lost=EXP.test((b.innerText||'').replace(/\\s+/g,' '));" +
        " if(lost){var n=Date.now();if(n-sent>3000){sent=n;PanelApp.sessionLost();}}" +
        "}catch(e){}}" +
        "var t=null;" +
        "try{new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=null;chk();},250);})" +
        ".observe(document.documentElement,{childList:true,subtree:true,characterData:true});}catch(e){}" +
        "setInterval(chk,3000);chk();})()";

    private void prepPage() {
        web.evaluateJavascript(desktopJs(), null);
        web.evaluateJavascript(WATCHER_JS, null);
        web.evaluateJavascript(HOOK_JS, null);
    }

    private class Bridge {
        @JavascriptInterface public void sessionLost() {
            handler.post(() -> startLogin());
        }

        /** Borra cookies y fuerza un inicio de sesión completo desde cero. */
        @JavascriptInterface public void freshLogin() {
            handler.post(() -> {
                manualMode = false;
                quickRepeats = 0;
                lastLoginOk = 0;
                if (Build.VERSION.SDK_INT >= 21) {
                    CookieManager.getInstance().removeAllCookies(ok -> {
                        CookieManager.getInstance().flush();
                        startLogin();
                    });
                } else {
                    startLogin();
                }
            });
        }

        /** Nombres de las cookies de sesión (sin valores), para diagnóstico. */
        @JavascriptInterface public String cookieNames() {
            String c = CookieManager.getInstance().getCookie(STATS_BASE + "statisticsMgr/x.action");
            if (c == null || c.trim().isEmpty()) return "(ninguna)";
            StringBuilder b = new StringBuilder();
            for (String part : c.split(";")) {
                String name = part.split("=", 2)[0].trim();
                if (b.length() > 0) b.append(", ");
                b.append(name);
            }
            return b.toString();
        }

        @JavascriptInterface public String loginTrail() {
            return trail.isEmpty() ? "(sin registro)" : android.text.TextUtils.join(" → ", trail);
        }

        /** Abre el login del RCS para entrar a mano; al entrar vuelve a Estadísticas. */
        @JavascriptInterface public void manualLogin() {
            handler.post(() -> {
                Runnable go = () -> {
                    statsAfterManual = true;
                    manualSawForm = false;
                    trail.clear();
                    goManual("Ingrese usuario y contraseña del RCS");
                };
                if (Build.VERSION.SDK_INT >= 21) {
                    CookieManager.getInstance().removeAllCookies(ok -> go.run());
                } else go.run();
            });
        }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }

        @JavascriptInterface public void openPanel() {
            handler.post(() -> showPanel());
        }

        /**
         * Consulta POST al RCMS hecha desde dentro de la página real del panel (WebView oculto),
         * con su jQuery/XHR: así salen las mismas cookies y cabeceras que usa el RCMS.
         */
        @JavascriptInterface public void post(final String id, final String path, final String body) {
            handler.post(() -> {
                if (path.contains("://") || path.contains("..")) { reply(id, -1, "ruta no permitida"); return; }
                String[] req = {id, STATS_BASE + path, body};
                if (helperReady) runHelper(req); else helperQueue.add(req);
            });
        }

        /** Respuesta del WebView oculto. */
        @JavascriptInterface public void helperDone(String id, int code, String text, String via) {
            lastVia = via;
            handler.post(() -> reply(id, code, text));
        }

        /** Cabeceras propias que el RCMS pone en sus consultas (p. ej. un token). */
        @JavascriptInterface public void hdr(String name, String value) {
            if (name != null && value != null && name.length() < 64) hdrs.put(name, value);
        }

        @JavascriptInterface public String helperInfo() {
            String u = helperUrl == null ? "(sin cargar)" : helperUrl.replace("http://10.20.1.4:8181", "");
            String h = hdrs.isEmpty() ? "(ninguna)" : android.text.TextUtils.join(", ", hdrs.keySet());
            return "página " + u + " · vía " + lastVia + " · cabeceras " + h;
        }
    }

    // ---------------------------------------------------------------- consultas desde la página real

    private static final String HOOK_JS =
        "(function(){if(window.__hdrHook)return;window.__hdrHook=1;" +
        "var S=/^(content-type|accept|x-requested-with|content-length)$/i;" +
        "var o=XMLHttpRequest.prototype.setRequestHeader;" +
        "XMLHttpRequest.prototype.setRequestHeader=function(k,v){" +
        "try{if(!S.test(k))PanelApp.hdr(String(k),String(v));}catch(e){}return o.apply(this,arguments);};})()";

    private static final String REQ_JS =
        "function(id,url,body,h){" +
        " function done(st,t,via){PanelApp.helperDone(id,st,String(t),via);}" +
        " try{" +
        "  var jq=window.jQuery;" +
        "  if(jq&&jq.ajax){jq.ajax({url:url,type:'POST',data:body,dataType:'text',headers:h,cache:false," +
        "   success:function(t,s,x){done(x.status,t,'jquery');}," +
        "   error:function(x){done(x.status||-1,x.responseText||('error '+x.status),'jquery');}});return;}" +
        "  var x=new XMLHttpRequest();x.open('POST',url,true);" +
        "  x.setRequestHeader('Content-Type','application/x-www-form-urlencoded; charset=UTF-8');" +
        "  x.setRequestHeader('X-Requested-With','XMLHttpRequest');" +
        "  for(var k in h){try{x.setRequestHeader(k,h[k]);}catch(e){}}" +
        "  x.onload=function(){done(x.status,x.responseText,'xhr');};" +
        "  x.onerror=function(){done(-1,'error de red','xhr');};" +
        "  x.send(body);" +
        " }catch(e){done(-1,String(e),'error');}}";

    private void runHelper(String[] req) {
        String js = "(" + REQ_JS + ")(" + JSONObject.quote(req[0]) + "," + JSONObject.quote(req[1]) + ","
                + JSONObject.quote(req[2]) + "," + new JSONObject(hdrs) + ")";
        helper.evaluateJavascript(js, null);
    }

    private void reply(String id, int code, String text) {
        String js = "window.__nativeResp && window.__nativeResp(" + JSONObject.quote(id) + ","
                + code + "," + JSONObject.quote(text) + ")";
        web.evaluateJavascript(js, null);
    }

    /** Carga (o recarga) el panel real en el WebView oculto, ya con la sesión actual. */
    private void loadHelper() {
        helperReady = false;
        helper.loadUrl(TARGET_URL);
    }

    private class HelperClient extends WebViewClient {
        @Override public void onPageFinished(WebView v, String url) {
            helperUrl = url;
            v.evaluateJavascript(HOOK_JS, null);
            if (url == null || url.startsWith("about:")) return;
            helperReady = true;
            for (String[] r : helperQueue) runHelper(r);
            helperQueue.clear();
        }

        @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
            h.proceed(USER, PASS);
        }
    }

    // ---------------------------------------------------------------- inicio de sesión tras la cortina

    private void startLogin() {
        if (loggingIn || manualMode) return;
        long now = System.currentTimeMillis();
        if (now - lastLoginOk < 15_000) quickRepeats++; else quickRepeats = 0;
        if (quickRepeats >= 3) { goManual("El RCMS rechaza la sesión. Inicie sesión manualmente."); return; }

        loggingIn = true;
        loginTries = 0;
        trail.clear();
        showOverlay("Conectando…");
        nextTry();
    }

    private void nextTry() {
        loginTries++;
        submits = 0;
        lastSubmit = 0;
        noFormStreak = 0;
        pageLoadedAt = 0;
        handler.removeCallbacks(step);
        handler.removeCallbacks(loginTimeout);
        handler.postDelayed(loginTimeout, LOGIN_TIMEOUT_MS);
        web.requestFocus();
        web.loadUrl(LOGIN_URL);
    }

    private final Runnable loginTimeout = () -> {
        if (!loggingIn) return;
        if (loginTries < MAX_LOGIN_TRIES) nextTry();
        else goManual("No se pudo iniciar sesión automáticamente.");
    };

    /** Cada 300 ms: si hay formulario lo llena y envía; cuando desaparece, la sesión quedó iniciada. */
    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (!loggingIn) return;
            web.evaluateJavascript(loginScript("'detect'"), r -> {
                if (!loggingIn) return;
                long now = System.currentTimeMillis();
                boolean form = r != null && r.contains("FORM") && !r.contains("NOFORM");
                boolean expired = r != null && r.contains("EXPIRED");

                if (form || expired) noFormStreak = 0;
                if (form) {
                    if (submits >= 3 && now - lastSubmit > 4000) { loginTimeout.run(); return; }
                    if (lastSubmit == 0 || now - lastSubmit > 3500) {
                        submits++;
                        lastSubmit = now;
                        trail.add("[envío " + submits + "]");
                        web.requestFocus();
                        web.evaluateJavascript(loginScript(String.valueOf(submits)), null);
                    }
                } else if (expired) {
                    web.evaluateJavascript(loginScript("'ok'"), null);
                } else if (web.getProgress() == 100 && ++noFormStreak >= 3) {
                    // Sin envío: esperar 8 s por si el formulario de login tarda en aparecer
                    if (submits > 0 || (pageLoadedAt > 0 && now - pageLoadedAt > 8000)) {
                        trail.add(submits > 0 ? "[sesión OK]" : "[sin formulario, se asume sesión]");
                        loginOk();
                        return;
                    }
                }
                handler.postDelayed(step, 300);
            });
        }
    };

    private void showStats() {
        statsShown = true;
        loadHelper();
        String html;
        try (InputStream in = getAssets().open("estadisticas.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            html = out.toString("UTF-8");
        } catch (IOException e) {
            html = "<p>Error cargando estadísticas</p>";
        }
        // Base = servidor RCMS: así las consultas salen con la misma sesión (cookies) del panel
        web.loadDataWithBaseURL(STATS_BASE, html, "text/html", "UTF-8", STATS_BASE + "app-estadisticas");
    }

    private void showPanel() {
        statsShown = false;
        web.clearHistory();
        web.loadUrl(TARGET_URL);
    }

    private void loginOk() {
        loggingIn = false;
        lastLoginOk = System.currentTimeMillis();
        handler.removeCallbacks(loginTimeout);
        handler.removeCallbacks(step);
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().flush();
        if (statsShown) { overlay.setVisibility(View.GONE); showStats(); return; }
        showOverlay("Abriendo panel…");
        web.loadUrl(TARGET_URL);           // la cortina se quita cuando el panel carga
    }

    private void goManual(String msg) {
        loggingIn = false;
        statsShown = false;
        manualMode = true;
        handler.removeCallbacks(loginTimeout);
        handler.removeCallbacks(step);
        overlay.setVisibility(View.GONE);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        web.loadUrl(LOGIN_URL);
    }

    private final Runnable manualCheck = new Runnable() {
        @Override public void run() {
            if (!manualMode) return;
            web.evaluateJavascript(loginScript("'detect'"), r -> {
                String url = web.getUrl() == null ? "" : web.getUrl();
                boolean form = r != null && r.contains("FORM") && !r.contains("NOFORM");
                if (form) manualSawForm = true;
                if (!form && web.getProgress() == 100 && (manualSawForm || !statsAfterManual)) {
                    manualMode = false;
                    quickRepeats = 0;
                    lastLoginOk = System.currentTimeMillis();
                    if (statsAfterManual) { statsAfterManual = false; showStats(); return; }
                    if (!url.contains(TARGET_KEY)) web.loadUrl(TARGET_URL);
                } else {
                    handler.postDelayed(this, 1500);
                }
            });
        }
    };

    // ---------------------------------------------------------------- cliente web

    private class MainClient extends WebViewClient {
        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
            mainFrameError = false;
            if ((loggingIn || manualMode) && url != null) {
                String u = url.replace("http://10.20.1.4:8181", "");
                if (trail.isEmpty() || !trail.get(trail.size() - 1).equals(u)) trail.add(u);
                while (trail.size() > 12) trail.remove(0);
            }
        }

        @Override public void onPageCommitVisible(WebView v, String url) {
            if (statsShown && !loggingIn) return;
            v.evaluateJavascript(desktopJs(), null);   // antes de que se vea, para que no "salte"
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (mainFrameError) { showError(); return; }
            errorView.setVisibility(View.GONE);
            if (statsShown && !loggingIn) {          // pantalla propia de Estadísticas
                overlay.setVisibility(View.GONE);
                return;
            }
            prepPage();

            if (loggingIn) {
                if (pageLoadedAt == 0) pageLoadedAt = System.currentTimeMillis();
                handler.removeCallbacks(step);
                handler.postDelayed(step, 400);
            } else if (manualMode) {
                handler.removeCallbacks(manualCheck);
                handler.postDelayed(manualCheck, 1500);
            } else if (url != null && url.contains(TARGET_KEY)) {
                handler.postDelayed(() -> {
                    if (!loggingIn) overlay.setVisibility(View.GONE);
                }, 700);
            } else if (url != null && !url.startsWith("about:")) {
                startLogin();      // cayó en el login u otra página
            }
        }

        @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
            if ((loggingIn || manualMode) && url != null) {   // cambios de página sin recarga (#...)
                String u = url.replace("http://10.20.1.4:8181", "");
                if (trail.isEmpty() || !trail.get(trail.size() - 1).equals(u)) trail.add(u);
                while (trail.size() > 12) trail.remove(0);
            }
            if (statsShown && !loggingIn) return;
            prepPage();
            if (!loggingIn && !manualMode && url != null && web.getProgress() == 100
                    && !url.contains(TARGET_KEY) && url.toLowerCase(java.util.Locale.US).contains("login")) {
                startLogin();
            }
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

    // ---------------------------------------------------------------- confirmaciones, avisos, archivos

    /** Sin esto, alert/confirm/prompt de la página se cancelan solos y los botones de acción "no hacen nada". */
    private class Chrome extends WebChromeClient {
        @Override public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> r.confirm())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }

        @Override public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("Aceptar", (d, w) -> r.confirm())
                    .setNegativeButton("Cancelar", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }

        @Override public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
            final EditText in = new EditText(MainActivity.this);
            in.setText(def);
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setView(in)
                    .setPositiveButton("Aceptar", (d, w) -> r.confirm(in.getText().toString()))
                    .setNegativeButton("Cancelar", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }

        @Override public boolean onJsBeforeUnload(WebView v, String url, String msg, JsResult r) {
            r.confirm();
            return true;
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                startActivityForResult(params.createIntent(), FILE_REQ);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    private void download(String url, String ua, String disposition, String mime) {
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            req.addRequestHeader("User-Agent", ua);
            req.setMimeType(mime);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "Descargando " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo descargar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- menú (botón ⋮ pequeño)

    private View buildMenuButton() {
        TextView b = new TextView(this);
        b.setText("\u22EE");
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
        bgd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bgd.setColor(Color.parseColor("#99000000"));
        b.setBackground(bgd);
        b.setAlpha(0.45f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(36), dp(36),
                Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(6), 0, 0, dp(6));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v);
            m.getMenu().add(0, 4, 0, statsShown ? "Volver al panel" : "Estadísticas de tareas");
            m.getMenu().add(0, 1, 0, "Recargar");
            m.getMenu().add(0, 2, 1, desktopMode ? "Ver en modo celular" : "Ver en modo escritorio");
            m.getMenu().add(0, 3, 2, "Iniciar sesión de nuevo");
            m.setOnMenuItemClickListener(it -> {
                switch (it.getItemId()) {
                    case 4: if (statsShown) showPanel(); else showStats(); break;
                    case 1: if (statsShown) showStats(); else web.reload(); break;
                    case 2:
                        desktopMode = !desktopMode;
                        prefs.edit().putBoolean("desktop", desktopMode).apply();
                        recreate();
                        break;
                    case 3: manualMode = false; quickRepeats = 0; startLogin(); break;
                }
                return true;
            });
            m.show();
        });
        return b;
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

    private String loginScript(String mode) {
        return "(" + autoLoginFn() + ")(" + JSONObject.quote(USER) + "," + JSONObject.quote(PASS)
                + "," + mode + ",true)";
    }

    // ---------------------------------------------------------------- vistas auxiliares

    private void showOverlay(String text) {
        overlayText.setText(text);
        overlay.setVisibility(View.VISIBLE);
    }

    private View buildOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#1B1F24"));   // opaca: no se ve el login
        box.setClickable(true);

        box.addView(new ProgressBar(this));

        overlayText = new TextView(this);
        overlayText.setTextColor(Color.WHITE);
        overlayText.setTextSize(18);
        overlayText.setGravity(Gravity.CENTER);
        overlayText.setPadding(0, dp(16), 0, 0);
        box.addView(overlayText);

        TextView brand = new TextView(this);
        brand.setText("Panel Operario · RCMS");
        brand.setTextColor(Color.parseColor("#8A93A0"));
        brand.setTextSize(13);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(8), 0, 0);
        box.addView(brand);

        overlayText.setText("Conectando…");
        box.setVisibility(View.VISIBLE);          // visible desde el arranque hasta que cargue el panel
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
