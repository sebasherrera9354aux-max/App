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
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Panel Operario RCMS: abre la página, inicia sesión automáticamente
 * (formulario web o autenticación básica HTTP) y vuelve al Panel Operario.
 */
public class MainActivity extends Activity {

    // ======== CONFIGURACIÓN (edite aquí si cambia algo) ========
    static final String TARGET_URL = "http://10.20.1.4:8181/rcms/web/cms/PanelOperario/";
    static final String TARGET_KEY = "PanelOperario"; // texto que identifica la página destino
    static final String USER = "admin";
    static final String PASS = "12Hik34++";
    // ===========================================================

    static final int MAX_LOGIN_ATTEMPTS = 3;
    static final long RETRY_OFFLINE_MS = 10_000;   // reintento si no hay conexión
    static final long SESSION_CHECK_MS = 30_000;   // revisa si la sesión expiró
    static final long SUBMIT_COOLDOWN_MS = 4_000;

    private WebView web;
    private View errorView;
    private TextView errorText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int loginAttempts = 0;
    private int basicAuthAttempts = 0;
    private long lastSubmit = 0;
    private boolean pendingRedirect = false;
    private boolean mainFrameError = false;

    // ---------- JavaScript inyectado ----------

    private String autoLoginFn = null;

    private String autoLoginFn() {
        if (autoLoginFn == null) {
            try {
                java.io.InputStream in = getAssets().open("autologin.js");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                autoLoginFn = out.toString("UTF-8");
            } catch (java.io.IOException e) {
                autoLoginFn = "function(){return 'NOFORM';}";
            }
        }
        return autoLoginFn;
    }

    /** mode: "'detect'" o 1/2/3 (estrategia de envío según el intento). */
    private String loginScript(String mode, boolean onlyVisible) {
        return "(" + autoLoginFn() + ")(" + JSONObject.quote(USER) + "," + JSONObject.quote(PASS)
                + "," + mode + "," + onlyVisible + ")";
    }

    private boolean onTarget() {
        String url = web.getUrl();
        return url != null && url.contains(TARGET_KEY);
    }

    // ---------- Ciclo de vida ----------

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildErrorView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new Client());

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(TARGET_URL);

        handler.postDelayed(sessionWatch, SESSION_CHECK_MS);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

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
        web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ---------- Login automático ----------

    private void scheduleLoginChecks() {
        long[] delays = {700, 2000, 4000, 7000};
        for (long d : delays) handler.postDelayed(checkLoginTask, d);
    }

    private void checkLogin() {
        if (mainFrameError) return;
        web.evaluateJavascript(loginScript("'detect'", onTarget()), new ValueCallback<String>() { @Override public void onReceiveValue(String r) {
            boolean form = r != null && r.contains("FORM") && !r.contains("NOFORM");
            String url = web.getUrl() == null ? "" : web.getUrl();
            if (form) {
                tryAutoLogin();
            } else if (pendingRedirect && !url.contains(TARGET_KEY)) {
                // Sesión iniciada pero quedó en otra página: volver al Panel Operario
                pendingRedirect = false;
                web.loadUrl(TARGET_URL);
            } else if (url.contains(TARGET_KEY)) {
                pendingRedirect = false;
                loginAttempts = 0;
                basicAuthAttempts = 0;
            }
        }});
    }

    private final Runnable checkLoginTask = new Runnable() {
        @Override public void run() { checkLogin(); }
    };

    private void tryAutoLogin() {
        long now = System.currentTimeMillis();
        if (now - lastSubmit < SUBMIT_COOLDOWN_MS) return;
        if (loginAttempts >= MAX_LOGIN_ATTEMPTS) {
            if (loginAttempts == MAX_LOGIN_ATTEMPTS) {
                loginAttempts++;
                Toast.makeText(this, "No se pudo iniciar sesión automáticamente. Ingrese manualmente.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        lastSubmit = now;
        loginAttempts++;
        pendingRedirect = true;
        web.evaluateJavascript(loginScript(String.valueOf(loginAttempts), onTarget()), new ValueCallback<String>() {
            @Override public void onReceiveValue(String r) { handler.postDelayed(checkLoginTask, 4000); }
        });
    }

    private final Runnable sessionWatch = new Runnable() {
        @Override public void run() {
            checkLogin();
            handler.postDelayed(this, SESSION_CHECK_MS);
        }
    };

    // ---------- WebViewClient ----------

    private class Client extends WebViewClient {
        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
            mainFrameError = false;
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (mainFrameError) {
                showError();
            } else {
                errorView.setVisibility(View.GONE);
                scheduleLoginChecks();
            }
        }

        @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
            // Cambios de ruta dentro de la aplicación web (sin recargar la página)
            scheduleLoginChecks();
        }

        @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
            if (basicAuthAttempts++ < MAX_LOGIN_ATTEMPTS) {
                pendingRedirect = true;
                h.proceed(USER, PASS);
            } else {
                h.cancel();
            }
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

        @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return false; // todo se abre dentro de la app
        }
    }

    // ---------- Pantalla sin conexión ----------

    private String offlineMessage(String detail) {
        return "No se puede conectar al RCMS\n\nVerifique que el equipo esté conectado a la red WiFi de la planta.\n\n"
                + "Reintentando automáticamente…\n(" + detail + ")";
    }

    private final Runnable retry = new Runnable() {
        @Override public void run() { web.loadUrl(TARGET_URL); }
    };

    private void showError() {
        errorView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_OFFLINE_MS);
    }

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#1B1F24"));
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        errorText = new TextView(this);
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(18);
        errorText.setGravity(Gravity.CENTER);
        box.addView(errorText);

        Button b = new Button(this);
        b.setText("Reintentar ahora");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { handler.removeCallbacks(retry); web.loadUrl(TARGET_URL); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        box.addView(b, lp);

        box.setVisibility(View.GONE);
        errorView = box;
        return box;
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
