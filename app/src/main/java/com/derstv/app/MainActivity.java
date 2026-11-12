package com.derstv.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EduCast TV — Udemy için resmi olmayan (unofficial) Android TV istemcisi.
 * Unofficial Android TV client for Udemy: a WebView shell driven by the TV remote.
 * Oturum bilgileri yalnızca cihazdaki WebView deposunda kalır; harici bir sunucuya gönderilmez.
 *
 * İmleç modu : yön tuşları imleci gezdirir, OK tıklar, kenara gelince sayfa kayar.
 * Video modu : OK oynat/durdur, sol/sağ 10 sn geri/ileri, yukarı/aşağı imlece döner.
 * Uzun OK    : imleç modunda video moduna geçiş, video modunda Türkçe altyazı aç/kapat.
 * Menü tuşu  : video modunda Türkçe altyazı aç/kapat, diğer sayfalarda yakınlaştırma.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://www.udemy.com/home/my-courses/learning/";
    private static final int[] WIDTHS = {1024, 1280, 1536};
    private static final long LONG_PRESS_MS = 650;
    private static final int SEEK_SEC = 10;
    private static final int FAST_SEEK_SEC = 30;

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;
    private TextView hint;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private float cx, cy;
    private boolean videoMode = false;
    private long okDownTime = 0;
    private boolean okLongHandled = false;
    private long lastBackTime = 0;
    private int widthIndex = 1;

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("derstv", MODE_PRIVATE);
        widthIndex = prefs.getInt("widthIndex", 1);
        if (widthIndex < 0 || widthIndex >= WIDTHS.length) widthIndex = 1;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        int p = dp(14);
        hint.setPadding(p * 2, p, p * 2, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(dp(12));
        hint.setBackground(bg);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(40);
        root.addView(hint, hlp);

        setContentView(root);
        setupWebView();

        // Ekran boyutu değişirse (ör. TV klavyesi açılıp kapanınca) sayfayı yeniden tam ekrana ölçekle.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (!nudging && ((r - l) != (or - ol) || (b - t) != (ob - ot))) {
                    handler.removeCallbacks(relayout);
                    handler.postDelayed(relayout, 250);
                }
            }
        });

        root.post(new Runnable() {
            @Override public void run() {
                cx = root.getWidth() / 2f;
                cy = root.getHeight() / 2f;
                cursor.moveTo(cx, cy);
            }
        });

        handler.postDelayed(viewportGuard, 3000);
        handler.postDelayed(errorGuard, 4000);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
        showHint(getString(R.string.hint_welcome), 6000);
        handler.postDelayed(firstRunNotice, 1500);
        handler.postDelayed(updateCheck, 8000);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        web.addJavascriptInterface(new SubBridge(), "DTVSub");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                // http(s) her zaman uygulama içinde açılsın; intent://, mailto: vb. yok sayılır.
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                viewportFixes = 0;
                applyViewport();
                updateModeForUrl(url);
                injectSubtitles(url);
                CookieManager.getInstance().flush();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // Udemy tek sayfa uygulaması; ders değişimleri burada yakalanır.
                viewportFixes = 0;
                applyViewport();
                updateModeForUrl(url);
                injectSubtitles(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    showHint(getString(R.string.err_page), 6000);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Udemy'nin şifreli (Widevine) videoları için korumalı medya izni.
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                            }
                        });
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                root.addView(view, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.INVISIBLE);
                cursor.bringToFront();
                hint.bringToFront();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCallback != null) customCallback.onCustomViewHidden();
                customCallback = null;
                web.setVisibility(View.VISIBLE);
                web.requestFocus();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        handler.removeCallbacks(updateCheck);
        handler.postDelayed(updateCheck, 8000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && ACTION_INSTALL_STATUS.equals(intent.getAction())) onInstallStatus(intent);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        int action = e.getAction();
        boolean down = action == KeyEvent.ACTION_DOWN;
        boolean up = action == KeyEvent.ACTION_UP;

        // Medya tuşları her modda videoyu kontrol eder.
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0) {
                    String a = code == KeyEvent.KEYCODE_MEDIA_PLAY ? "play"
                            : code == KeyEvent.KEYCODE_MEDIA_PAUSE ? "pause" : "toggle";
                    videoCommand(a);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) videoCommand(String.valueOf(FAST_SEEK_SEC));
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) videoCommand(String.valueOf(-FAST_SEEK_SEC));
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) {
                    if (videoMode) toggleSubtitles(); else cycleZoom();
                }
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
                if (down && e.getRepeatCount() == 0) toggleSubtitles();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) scrollAt(0, 0.8f);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) scrollAt(0, -0.8f);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (up) onBackKey();
                return true;
        }

        boolean isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (isOk) {
            if (down) {
                if (e.getRepeatCount() == 0) {
                    okDownTime = e.getEventTime();
                    okLongHandled = false;
                } else if (!okLongHandled && e.getEventTime() - okDownTime >= LONG_PRESS_MS) {
                    okLongHandled = true;
                    onLongOk();
                }
            } else if (up) {
                if (!okLongHandled) {
                    if (e.getEventTime() - okDownTime >= LONG_PRESS_MS) {
                        onLongOk(); // tekrar göndermeyen kumandalar için
                    } else if (videoMode) {
                        videoCommand("toggle");
                    } else {
                        click();
                    }
                }
                okLongHandled = false;
            }
            return true;
        }

        boolean isDpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (isDpad) {
            if (!down) return true;
            if (videoMode) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) videoCommand(String.valueOf(-SEEK_SEC));
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) videoCommand(String.valueOf(SEEK_SEC));
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (e.getRepeatCount() == 0) toggleFullscreen();
                } else if (isFullscreen()) {
                    if (e.getRepeatCount() == 0) cycleVideoZoom();
                } else setVideoMode(false, true);
                return true;
            }
            moveCursor(code, e.getRepeatCount());
            return true;
        }

        return super.dispatchKeyEvent(e);
    }

    /** Uzun OK: ders sayfasında video/imleç modu değiştirir, diğer sayfalarda yazı boyutunu değiştirir
     *  (Mi Box gibi Menü tuşu olmayan kumandalar için). */
    private void onLongOk() {
        String url = web.getUrl();
        boolean lecture = url != null && url.contains("/learn/lecture/");
        if (videoMode) {
            toggleSubtitles();
        } else if (lecture) {
            setVideoMode(true, true);
        } else {
            cycleZoom();
        }
    }

    private void onBackKey() {
        if (isFullscreen()) {
            exitFullscreen();
            return;
        }
        if (videoMode) {
            setVideoMode(false, true);
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBackTime < 2000) {
            finish();
        } else {
            lastBackTime = now;
            showHint(getString(R.string.exit_again), 2000);
        }
    }

    // ------------------------------------------------------------------ cursor

    private void moveCursor(int code, int repeat) {
        float step = dp(10) + Math.min(repeat, 25) * dp(2.5f);
        float w = root.getWidth(), h = root.getHeight();
        float edge = dp(24);
        float dx = 0, dy = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -step; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = step; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = step; break;
        }
        // Kenara dayalıysa sayfayı kaydır
        if (dy > 0 && cy >= h - edge) { scrollAt(0, 0.12f + Math.min(repeat, 10) * 0.01f); return; }
        if (dy < 0 && cy <= edge) { scrollAt(0, -(0.12f + Math.min(repeat, 10) * 0.01f)); return; }
        if (dx > 0 && cx >= w - edge) { scrollAt(0.15f, 0); return; }
        if (dx < 0 && cx <= edge) { scrollAt(-0.15f, 0); return; }

        cx = Math.max(dp(2), Math.min(w - dp(2), cx + dx));
        cy = Math.max(dp(2), Math.min(h - dp(2), cy + dy));
        cursor.moveTo(cx, cy);
        hover();
    }

    private View target() {
        return customView != null ? customView : web;
    }

    private void hover() {
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        target().dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private void click() {
        View v = target();
        long t = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(downEv);
        downEv.recycle();
        MotionEvent upEv = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0);
        upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(upEv);
        upEv.recycle();
        cursor.pulse();
    }

    /** İmlecin altındaki kaydırılabilir alanı (ör. müfredat paneli) ya da sayfayı kaydırır. */
    private void scrollAt(float fx, float fy) {
        float w = Math.max(1, root.getWidth()), h = Math.max(1, root.getHeight());
        String js = String.format(Locale.US,
                "(function(px,py,fx,fy){var x=px*innerWidth,y=py*innerHeight,dx=fx*innerWidth,dy=fy*innerHeight;"
                + "var el=document.elementFromPoint(x,y);"
                + "function ok(e){var s=getComputedStyle(e),o=dy?s.overflowY:s.overflowX;"
                + "return (o=='auto'||o=='scroll'||o=='overlay')&&(dy?e.scrollHeight>e.clientHeight+2:e.scrollWidth>e.clientWidth+2);}"
                + "while(el&&el!==document.body&&el!==document.documentElement){if(ok(el)){el.scrollBy(dx,dy);return;}el=el.parentElement;}"
                + "window.scrollBy(dx,dy);})(%f,%f,%f,%f)",
                cx / w, cy / h, fx, fy);
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------------ video

    private void updateModeForUrl(String url) {
        boolean lecture = url != null && url.contains("/learn/lecture/");
        if (lecture != videoMode) {
            setVideoMode(lecture, true);
        } else if (lecture) {
            // Udemy sonraki derse geçince oynatıcıyı yeniden kurar; kaliteyi tekrar seç
            // (tam ekranı bekçi kod kendisi geri getirir).
            handler.removeCallbacks(highQuality);
            handler.postDelayed(highQuality, 3000);
        }
    }

    private void setVideoMode(boolean on, boolean announce) {
        videoMode = on;
        cursor.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        if (on) {
            // Ders açılınca önce en yüksek kaliteyi seç, ardından tam ekrana geç.
            handler.removeCallbacks(highQuality);
            handler.removeCallbacks(autoFullscreen);
            handler.postDelayed(highQuality, 2000);
            handler.postDelayed(autoFullscreen, 4000);
        } else {
            handler.removeCallbacks(highQuality);
            handler.removeCallbacks(autoFullscreen);
            if (isFullscreen()) exitFullscreen();
        }
        if (announce) {
            if (on) {
                showHint(getString(R.string.hint_video_mode), 4000);
            } else {
                showHint(getString(R.string.hint_cursor_mode), 2000);
            }
        }
    }

    // ------------------------------------------------------------------ fullscreen

    private boolean cssFull = false;
    /** Tam ekran görüntü modları: object-fit + büyütme oranı */
    private static final String[] ZOOM_FIT = {"contain", "cover", "contain", "contain"};
    private static final float[] ZOOM_SCALE = {1f, 1f, 1.2f, 1.4f};
    /** Kullanıcı tam ekran istiyor mu? (▼ veya Geri ile kapatılana kadar true) */
    private boolean wantFull = false;
    private int fullBadCount = 0;

    private final Runnable autoFullscreen = new Runnable() {
        @Override public void run() {
            if (videoMode && !isFullscreen()) enterFullscreen();
        }
    };

    /** Udemy oynatıcıyı yeniden kurarsa (kalite değişimi, sonraki ders) tam ekranı geri getirir. */
    private final Runnable fullWatchdog = new Runnable() {
        @Override public void run() {
            if (!wantFull || !videoMode) return;
            if (customView == null) {
                String js = "(function(){var c=document.querySelector('[data-derstv-full]');"
                        + "if(!c||!document.getElementById('derstv-full'))return 'lost';"
                        + "var vs=document.querySelectorAll('video'),v=null,b=0;"
                        + "for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),s=r.width*r.height;if(s>=b){b=s;v=vs[i];}}"
                        + "if(!v||!c.contains(v)||!v.hasAttribute('data-derstv-vid'))return 'lost';"
                        + "var vr=v.getBoundingClientRect();"
                        + "if(vr.width<innerWidth*0.9||vr.height<innerHeight*0.9)return 'small';return 'ok';})()";
                web.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        if (!wantFull || !videoMode || customView != null || v == null) return;
                        if (v.contains("ok")) { fullBadCount = 0; return; }
                        fullBadCount++;
                        // Önce tam ekranı yeniden kur; hâlâ küçükse sayfa yüksekliğini yeniden hesaplat.
                        if (fullBadCount >= 2 && v.contains("small")) {
                            viewportFixes = 0;
                            nudgeWebView();
                        } else {
                            applyCssFullscreen(true, true);
                        }
                    }
                });
            }
            handler.postDelayed(this, 1500);
        }
    };

    // ------------------------------------------------------------------ playback error recovery

    /** Son bilinen oynatma konumu (sn) ve ait olduğu ders adresi */
    private double lastPos = 0;
    private String lastPosUrl = null;
    /** Yeniden yüklemeden sonra gidilecek konum */
    private double resumeAt = -1;
    private String resumeUrl = null;
    private int errorRecoveries = 0;
    private String errorUrl = null;
    private long lastRecovery = 0;

    private static String lectureKey(String url) {
        if (url == null) return "";
        int q = url.indexOf('?'); if (q >= 0) url = url.substring(0, q);
        int h = url.indexOf('#'); if (h >= 0) url = url.substring(0, h);
        return url;
    }

    /**
     * Udemy oynatıcısı "Video hatası / beklenmedik bir hata oluştu" penceresi gösterirse
     * dersi kendiliğinden yeniden yükler ve videoyu kaldığı saniyeden sürdürür.
     */
    private final Runnable errorGuard = new Runnable() {
        @Override public void run() {
            handler.postDelayed(this, 2000);
            if (!videoMode || web == null) return;
            final String key = lectureKey(web.getUrl());
            String seek = (resumeAt > 0 && key.equals(resumeUrl)) ? String.valueOf(resumeAt) : "-1";
            String js = "(function(){var R=" + seek + ";"
                    + "var err=false,re=/(video hatas|video error|oynatmay. birka|unexpected error|beklenmedik bir hata)/i;"
                    + "var ds=document.querySelectorAll('[role=dialog],[role=alertdialog],[class*=modal],[class*=error],[class*=alert]');"
                    + "for(var i=0;i<ds.length&&!err;i++){var d=ds[i];if(d.offsetParent===null&&getComputedStyle(d).position!=='fixed')continue;"
                    + "var t=(d.textContent||'');if(t.length<600&&re.test(t))err=true;}"
                    + "var vs=document.querySelectorAll('video'),v=null,b=0;"
                    + "for(var j=0;j<vs.length;j++){var r=vs[j].getBoundingClientRect(),s=r.width*r.height;if(s>=b){b=s;v=vs[j];}}"
                    + "if(err)return 'err';if(!v)return 'none';"
                    + "if(R>0){if(v.readyState>=1&&isFinite(v.duration)&&v.duration>0){"
                    + "if(Math.abs(v.currentTime-R)>4)v.currentTime=Math.min(R,v.duration-1);"
                    + "var p=v.play();if(p&&p.catch)p.catch(function(){});return 'resumed';}return 'wait';}"
                    + "return 't:'+v.currentTime;})()";
            web.evaluateJavascript(js, new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (v == null || !videoMode) return;
                    v = v.replace("\"", "");
                    if (v.startsWith("t:")) {
                        try {
                            double t = Double.parseDouble(v.substring(2));
                            if (t > 1) { lastPos = t; lastPosUrl = key; }
                        } catch (NumberFormatException ignored) { }
                    } else if (v.equals("resumed")) {
                        resumeAt = -1; resumeUrl = null;
                        showHint(getString(R.string.video_resumed), 2500);
                    } else if (v.equals("err")) {
                        recoverFromVideoError(key);
                    }
                }
            });
        }
    };

    private void recoverFromVideoError(String key) {
        long now = System.currentTimeMillis();
        if (now - lastRecovery < 15000) return;       // yeniden yükleme sürerken tekrar tetikleme
        if (!key.equals(errorUrl)) { errorUrl = key; errorRecoveries = 0; }
        if (errorRecoveries >= 3) {
            if (errorRecoveries == 3) {
                errorRecoveries++;
                showHint(getString(R.string.video_failed), 8000);
            }
            return;
        }
        errorRecoveries++;
        lastRecovery = now;
        resumeAt = key.equals(lastPosUrl) ? lastPos : -1;
        resumeUrl = key;
        showHint(getString(R.string.video_error_reload, errorRecoveries), 4000);
        web.reload();
    }

    // ------------------------------------------------------------------ Turkish subtitles

    private String subScript = null;
    private final ExecutorService subExec = Executors.newSingleThreadExecutor();

    /** Altyazı modu: "auto" (yalnızca yabancı dilli derslerde), "off" (hiç), "on" (her zaman). */
    private String subMode() {
        String m = prefs.getString("subMode", "auto");
        return "off".equals(m) || "on".equals(m) ? m : "auto";
    }

    /** Altyazı hedef dili = TV'nin dili (Türkçe değilse İngilizce). */
    private static String subLang() {
        return "tr".equals(Locale.getDefault().getLanguage()) ? "tr" : "en";
    }

    /** Ders sayfasına Türkçe altyazı katmanını (assets/subtitles.js) yükler. */
    private void injectSubtitles(String url) {
        if (url == null || !url.contains("/learn/lecture/")) return;
        if (subScript == null) {
            try {
                InputStream in = getAssets().open("subtitles.js");
                subScript = new String(readAll(in), "UTF-8");
            } catch (Exception ex) { return; }
        }
        web.evaluateJavascript(subScript + "\n;window.__dtvSub&&window.__dtvSub.setLang('" + subLang() + "');"
                + "window.__dtvSub&&window.__dtvSub.setMode('" + subMode() + "');", null);
    }

    /** Uzun OK / Menü: Otomatik → Kapalı → Her zaman açık → Otomatik */
    private void toggleSubtitles() {
        String cur = subMode();
        String next = "auto".equals(cur) ? "off" : "off".equals(cur) ? "on" : "auto";
        prefs.edit().putString("subMode", next).apply();
        injectSubtitles(web.getUrl());
        web.evaluateJavascript("window.__dtvSub&&window.__dtvSub.setMode('" + next + "')", null);
        int id = "off".equals(next) ? R.string.subs_off : "on".equals(next) ? R.string.subs_on : R.string.subs_auto;
        showHint(getString(id), 3500);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Google Çeviri (gtx) ile metni hedef dile çevirir. */
    private static String translateText(String q, String tl) throws Exception {
        String body = httpGet("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + tl + "&dt=t&q="
                + URLEncoder.encode(q, "UTF-8"));
        JSONArray segs = new JSONArray(body).getJSONArray(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length(); i++) {
            Object o = segs.getJSONArray(i).opt(0);
            if (o instanceof String) sb.append((String) o);
        }
        return sb.toString();
    }

    /** Yedek: her satırı ayrı q parametresiyle gönderir, cevap satır satır gelir. */
    private static List<String> translateDict(List<String> src, String tl) throws Exception {
        StringBuilder u = new StringBuilder("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=" + tl);
        for (String s : src) u.append("&q=").append(URLEncoder.encode(s.replace('\n', ' '), "UTF-8"));
        JSONArray a = new JSONArray(httpGet(u.toString()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object o = a.get(i);
            out.add(o instanceof JSONArray ? ((JSONArray) o).optString(0, "") : String.valueOf(o));
        }
        if (out.size() != src.size()) throw new java.io.IOException("line count mismatch");
        return out;
    }

    /** Satırları tek istekte çevirir; satır sayısı tutmazsa ikiye bölerek tekrar dener. */
    private static List<String> translateLines(List<String> src, String tl) throws Exception {
        List<String> out = new ArrayList<>();
        if (src.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < src.size(); i++) {
            if (i > 0) q.append('\n');
            q.append(src.get(i).replace('\n', ' '));
        }
        String res;
        try {
            res = translateText(q.toString(), tl);
        } catch (Exception ex) {
            return translateDict(src, tl);   // yedek çeviri adresi
        }
        String[] parts = res.split("\n", -1);
        List<String> clean = new ArrayList<>();
        for (String p : parts) clean.add(p.trim());
        while (clean.size() > src.size() && clean.get(clean.size() - 1).isEmpty()) clean.remove(clean.size() - 1);
        if (clean.size() == src.size()) return clean;
        if (src.size() == 1) {
            out.add(res.replace('\n', ' ').trim());
            return out;
        }
        int mid = src.size() / 2;
        out.addAll(translateLines(src.subList(0, mid), tl));
        out.addAll(translateLines(src.subList(mid, src.size()), tl));
        return out;
    }

    private void subCallback(final String id, final boolean ok, final String data) {
        handler.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.__dtvSubCb&&window.__dtvSubCb(" + JSONObject.quote(id) + ","
                        + ok + "," + JSONObject.quote(data == null ? "" : data) + ")", null);
            }
        });
    }

    /** subtitles.js ile uygulama arasındaki köprü (ağ istekleri CORS'a takılmasın diye burada yapılır). */
    private class SubBridge {
        @JavascriptInterface
        public void translate(final String id, final String json) {
            subExec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        JSONArray a = new JSONArray(json);
                        List<String> src = new ArrayList<>();
                        for (int i = 0; i < a.length(); i++) src.add(a.optString(i, ""));
                        JSONArray res = new JSONArray();
                        for (String s : translateLines(src, subLang())) res.put(s);
                        subCallback(id, true, res.toString());
                    } catch (Exception ex) {
                        subCallback(id, false, String.valueOf(ex.getMessage()));
                    }
                }
            });
        }

        @JavascriptInterface
        public void fetchText(final String id, final String url) {
            subExec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        Uri u = Uri.parse(url);
                        String host = u.getHost() == null ? "" : u.getHost();
                        if (!"https".equals(u.getScheme()) || !host.endsWith(".udemycdn.com")) {
                            throw new SecurityException("blocked host");
                        }
                        subCallback(id, true, httpGet(url));
                    } catch (Exception ex) {
                        subCallback(id, false, String.valueOf(ex.getMessage()));
                    }
                }
            });
        }

        @JavascriptInterface
        public void status(final String key) {
            handler.post(new Runnable() {
                @Override public void run() {
                    int id = "preparing".equals(key) ? R.string.sub_preparing
                            : "ready".equals(key) ? R.string.sub_ready
                            : "nocap".equals(key) ? R.string.sub_none : R.string.sub_failed;
                    if (videoMode) showHint(getString(id), 3000);
                }
            });
        }
    }

    // ------------------------------------------------------------------ first run notice

    private final Runnable firstRunNotice = new Runnable() {
        @Override public void run() {
            if (prefs.getBoolean("disclaimerShown", false) || isFinishing()) return;
            new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.disclaimer)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            prefs.edit().putBoolean("disclaimerShown", true).apply();
                        }
                    })
                    .show();
        }
    };

    // ------------------------------------------------------------------ auto update

    private static final String ACTION_INSTALL_STATUS = "io.github.tsemre.educasttv.INSTALL_STATUS";
    private static final long UPDATE_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private final ExecutorService updateExec = Executors.newSingleThreadExecutor();
    private boolean updateBusy = false;

    /** GitHub'daki son sürümü kontrol eder (en fazla 6 saatte bir). */
    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            final String repo = getString(R.string.update_repo).trim();
            if (repo.isEmpty() || updateBusy || isFinishing()) return;   // yerel/deneme derlemelerinde kapalı
            long now = System.currentTimeMillis();
            if (now - prefs.getLong("lastUpdateCheck", 0) < UPDATE_INTERVAL_MS) return;
            prefs.edit().putLong("lastUpdateCheck", now).apply();
            updateExec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject rel = new JSONObject(httpGet("https://api.github.com/repos/" + repo + "/releases/latest"));
                        int latest = parseBuildNumber(rel.optString("tag_name"));
                        if (latest <= currentVersionCode()) return;
                        String apkUrl = null;
                        JSONArray assets = rel.optJSONArray("assets");
                        for (int i = 0; assets != null && i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").toLowerCase(Locale.US).endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url");
                                break;
                            }
                        }
                        if (apkUrl == null || apkUrl.isEmpty()) return;
                        final String url = apkUrl;
                        final String name = rel.optString("name", rel.optString("tag_name"));
                        String body = rel.optString("body", "").trim();
                        if (body.length() > 600) body = body.substring(0, 600) + "…";
                        final String notes = body;
                        handler.post(new Runnable() {
                            @Override public void run() { showUpdateDialog(name, notes, url); }
                        });
                    } catch (Exception ignored) {
                        // Ağ yoksa bir sonraki açılışta tekrar denenir.
                        prefs.edit().putLong("lastUpdateCheck", 0).apply();
                    }
                }
            });
        }
    };

    private static int parseBuildNumber(String tag) {
        String d = tag == null ? "" : tag.replaceAll("[^0-9]", "");
        try { return d.isEmpty() ? 0 : Integer.parseInt(d); } catch (NumberFormatException e) { return 0; }
    }

    @SuppressWarnings("deprecation")
    private int currentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void showUpdateDialog(String name, String notes, final String url) {
        if (isFinishing()) return;
        String msg = getString(R.string.update_message, name) + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { downloadAndInstall(url); }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private void downloadAndInstall(final String url) {
        if (updateBusy) return;
        updateBusy = true;
        showHint(getString(R.string.update_downloading, 0), 3000);
        updateExec.execute(new Runnable() {
            @Override public void run() {
                try {
                    File apk = new File(getCacheDir(), "update.apk");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    try {
                        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                        long total = c.getContentLength();
                        InputStream in = c.getInputStream();
                        OutputStream out = new FileOutputStream(apk);
                        byte[] buf = new byte[65536];
                        long done = 0;
                        int n, lastPct = -1;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                final int pct = (int) (done * 100 / total);
                                if (pct / 10 != lastPct / 10) {
                                    lastPct = pct;
                                    handler.post(new Runnable() {
                                        @Override public void run() { showHint(getString(R.string.update_downloading, pct), 3000); }
                                    });
                                }
                            }
                        }
                        out.close();
                        in.close();
                    } finally {
                        c.disconnect();
                    }
                    installApk(apk);
                } catch (final Exception ex) {
                    updateBusy = false;
                    handler.post(new Runnable() {
                        @Override public void run() { showHint(getString(R.string.update_failed, String.valueOf(ex.getMessage())), 6000); }
                    });
                }
            }
        });
    }

    /** İndirilen APK'yı sistem yükleyicisine verir; Android onay penceresini gösterir. */
    private void installApk(File apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sessionId);
        try {
            OutputStream out = session.openWrite("update.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            session.fsync(out);
            out.close();
            Intent i = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getActivity(this, sessionId, i, flags);
            session.commit(p.getIntentSender());
        } finally {
            session.close();
        }
        handler.post(new Runnable() {
            @Override public void run() { showHint(getString(R.string.update_installing), 4000); }
        });
    }

    private void onInstallStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm);
                    return;
                } catch (Exception ignored) { }
            }
        }
        updateBusy = false;
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            showHint(getString(R.string.update_failed, m == null ? String.valueOf(status) : m), 8000);
        }
    }

    // ------------------------------------------------------------------ quality

    private final Runnable highQuality = new Runnable() {
        @Override public void run() { if (videoMode) forceHighestQuality(); }
    };

    /** Udemy oynatıcısının ayarlar menüsünden en yüksek çözünürlüğü (ör. 1080p) seçer. */
    private void forceHighestQuality() {
        String js = "(function(){"
                + "function items(){var a=document.querySelectorAll('[role=menuitemradio],[role=menuitem],[role=menuitemcheckbox],[role=option],li,button'),o=[];"
                + "for(var i=0;i<a.length;i++){var t=(a[i].textContent||'').trim();if(/^\\d{3,4}p\\b/i.test(t)&&t.length<12)o.push(a[i]);}return o;}"
                + "function pick(){var its=items();if(!its.length)return 0;var best=null,bv=0;"
                + "for(var i=0;i<its.length;i++){var v=parseInt(its[i].textContent,10);if(v>bv){bv=v;best=its[i];}}"
                + "var sel=best.getAttribute('aria-checked')==='true'||best.getAttribute('aria-selected')==='true';"
                + "if(!sel)best.click();window.__dtvQ=bv;return bv;}"
                + "function btn(){var b=document.querySelectorAll('button');for(var i=0;i<b.length;i++){"
                + "var s=(b[i].getAttribute('aria-label')||'')+' '+(b[i].getAttribute('data-purpose')||'')+' '+(b[i].getAttribute('title')||'');"
                + "if(/(settings|ayarlar|quality|kalite)/i.test(s))return b[i];}return null;}"
                + "window.__dtvQ=0;if(pick())return 'direct';"
                + "var s=btn();if(!s)return 'nobutton';s.click();"
                + "setTimeout(function(){if(!pick()){var a=document.querySelectorAll('[role=menuitem],button,li');"
                + "for(var i=0;i<a.length;i++){if(/(quality|kalite|çözünürlük)/i.test(a[i].textContent||'')&&(a[i].textContent||'').length<40){a[i].click();break;}}"
                + "setTimeout(function(){pick();s.click();},350);}else{setTimeout(function(){s.click();},250);}},450);"
                + "return 'menu';})()";
        web.evaluateJavascript(js, null);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("String(window.__dtvQ||0)", new ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        String q = v == null ? "0" : v.replace("\"", "");
                        if (!"0".equals(q)) showHint(getString(R.string.video_quality, q), 2000);
                    }
                });
            }
        }, 1600);
    }

    private boolean isFullscreen() {
        return customView != null || wantFull;
    }

    private int zoomIndex() {
        int z = prefs.getInt("videoZoom", 0);
        return (z < 0 || z >= ZOOM_FIT.length) ? 0 : z;
    }

    /** Tam ekranda ▲: Sığdır → Doldur → 1.2x → 1.4x */
    private void cycleVideoZoom() {
        int z = (zoomIndex() + 1) % ZOOM_FIT.length;
        prefs.edit().putInt("videoZoom", z).apply();
        applyVideoZoom();
        showHint(getString(R.string.video_zoom, getResources().getStringArray(R.array.zoom_names)[z]), 1800);
    }

    private void applyVideoZoom() {
        int z = zoomIndex();
        String js = String.format(Locale.US,
                "(function(){var d=document.documentElement.style;d.setProperty('--dtv-fit','%s');d.setProperty('--dtv-scale','%.2f');})()",
                ZOOM_FIT[z], ZOOM_SCALE[z]);
        web.evaluateJavascript(js, null);
    }

    private void toggleFullscreen() {
        if (isFullscreen()) exitFullscreen(); else enterFullscreen();
    }

    /** Videoyu (oynatıcı kutusuyla birlikte) tüm ekrana yayar ve bekçiyi başlatır. */
    private void enterFullscreen() {
        wantFull = true;
        fullBadCount = 0;
        applyCssFullscreen(true, false);
        handler.removeCallbacks(fullWatchdog);
        handler.postDelayed(fullWatchdog, 1500);
    }

    private void exitFullscreen() {
        wantFull = false;
        handler.removeCallbacks(fullWatchdog);
        if (customView != null) {
            web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (customView != null && customCallback != null) customCallback.onCustomViewHidden();
                }
            }, 300);
        }
        applyCssFullscreen(false, true);
    }

    /** Oynatıcı kutusunu (video + altyazı + kontroller) sabit konumla tüm ekrana yayar. */
    private void applyCssFullscreen(final boolean on, final boolean silent) {
        String js = "(function(on){var id='derstv-full',o=document.getElementById(id);if(o)o.remove();"
                + "var ms=document.querySelectorAll('[data-derstv-full],[data-derstv-anc],[data-derstv-vid],[data-derstv-chain]');"
                + "for(var k=0;k<ms.length;k++){ms[k].removeAttribute('data-derstv-full');ms[k].removeAttribute('data-derstv-anc');ms[k].removeAttribute('data-derstv-vid');ms[k].removeAttribute('data-derstv-chain');}"
                + "if(!on)return 'off';"
                + "var vs=document.querySelectorAll('video'),v=null,b=0;"
                + "for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),s=r.width*r.height;if(s>=b){b=s;v=vs[i];}}"
                + "if(!v||b<1)return 'none';"
                + "var vr=v.getBoundingClientRect(),c=v,p=v.parentElement;"
                + "while(p&&p!==document.body){var pr=p.getBoundingClientRect();"
                + "if(pr.width>vr.width*1.15+4||pr.height>vr.height*1.35+4)break;c=p;p=p.parentElement;}"
                + "c.setAttribute('data-derstv-full','1');v.setAttribute('data-derstv-vid','1');"
                + "var q=v.parentElement;while(q&&q!==c){q.setAttribute('data-derstv-chain','1');q=q.parentElement;}"
                + "var a=v.parentElement;while(a){a.setAttribute('data-derstv-anc','1');a=a.parentElement;}"
                + "var st=document.createElement('style');st.id=id;"
                + "st.textContent='html,body{overflow:hidden!important;background:#000!important}'"
                + "+'body *{visibility:hidden!important}'"
                + "+'[data-derstv-full],[data-derstv-full] *{visibility:visible!important}'"
                + "+'[data-derstv-anc]{transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;overflow:visible!important}'"
                + "+'[data-derstv-full]{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;"
                + "width:auto!important;height:auto!important;max-width:none!important;max-height:none!important;"
                + "margin:0!important;z-index:2147483647!important;background:#000!important;transform:none!important}'"
                + "+'[data-derstv-chain]{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;"
                + "min-height:0!important;padding-top:0!important;padding-bottom:0!important;aspect-ratio:auto!important;margin:0!important}'"
                + "+'[data-derstv-vid]{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;"
                + "width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;"
                + "margin:0!important;padding:0!important;background:#000!important;object-fit:var(--dtv-fit,contain)!important;"
                + "transform:scale(var(--dtv-scale,1))!important;transform-origin:50% 50%!important}';"
                + "(document.head||document.documentElement).appendChild(st);return 'on';})(" + on + ")";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                String r = v == null ? "" : v.replace("\"", "");
                if (on) {
                    cssFull = "on".equals(r);
                    if (cssFull) applyVideoZoom();
                    // Video henüz yoksa bekçi kod yüklenince tam ekranı uygular.
                    if (cssFull && !silent) showHint(getString(R.string.hint_fullscreen), 2500);
                } else {
                    cssFull = false;
                }
            }
        });
    }

    /** a = "toggle" | "play" | "pause" | saniye (ör. "10", "-10") */
    private void videoCommand(String a) {
        String js = "(function(a){var vs=document.querySelectorAll('video'),v=null,b=0;"
                + "for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),s=r.width*r.height;if(s>=b){b=s;v=vs[i];}}"
                + "if(!v)return 'none';"
                + "if(a=='toggle'){if(v.paused)v.play();else v.pause();}"
                + "else if(a=='play'){v.play();}else if(a=='pause'){v.pause();}"
                + "else{var d=isFinite(v.duration)?v.duration:1e9;v.currentTime=Math.max(0,Math.min(d-0.5,v.currentTime+parseFloat(a)));}"
                + "return (v.paused?'p':'r')+'|'+Math.floor(v.currentTime)+'|'+Math.floor(isFinite(v.duration)?v.duration:0);"
                + "})('" + a + "')";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (value == null) return;
                String v = value.replace("\"", "");
                if (v.equals("none") || v.equals("null")) {
                    showHint(getString(R.string.no_video), 2000);
                    return;
                }
                String[] parts = v.split("\\|");
                if (parts.length < 3) return;
                String icon = parts[0].equals("p") ? "⏸" : "▶";
                showHint(icon + "  " + fmt(parts[1]) + " / " + fmt(parts[2]), 1800);
            }
        });
    }

    private static String fmt(String secStr) {
        int s;
        try { s = Integer.parseInt(secStr); } catch (NumberFormatException ex) { return "--:--"; }
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ------------------------------------------------------------------ zoom / viewport

    private void cycleZoom() {
        widthIndex = (widthIndex + 1) % WIDTHS.length;
        prefs.edit().putInt("widthIndex", widthIndex).apply();
        viewportFixes = 0;
        applyViewport();
        showHint(getString(R.string.text_size, getResources().getStringArray(R.array.text_sizes)[widthIndex]), 1500);
    }

    /** Sayfayı TV için sabit bir masaüstü genişliğinde (vars. 1280 px) gösterir. */
    private void applyViewport() {
        if (web.getWidth() <= 0 || web.getHeight() <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int viewW = root.getWidth() > 0 ? root.getWidth() : web.getWidth();
        int viewH = root.getHeight() > 0 ? root.getHeight() : web.getHeight();
        int cssW = WIDTHS[widthIndex];
        float scale = (viewW / density) / cssW;
        int cssH = Math.round((viewH / density) / scale);
        String content = String.format(Locale.US,
                "width=%d, initial-scale=%.4f, minimum-scale=%.4f, maximum-scale=%.4f, user-scalable=no",
                cssW, scale, scale, scale);
        // Ayar Udemy tarafından değiştirildiyse ya da sayfa yüksekliği ekrana uymuyorsa düzelt.
        String js = String.format(Locale.US,
                "(function(c,w,h){var m=document.querySelector('meta[name=viewport]'),ch=false;"
                + "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}"
                + "if(m.getAttribute('content')!==c){m.setAttribute('content',c);ch=true;}"
                + "var d=document.documentElement;if(Math.abs(d.clientWidth-w)>8||Math.abs(d.clientHeight-h)>8)ch=true;"
                + "return ch?'fix':'ok';})('%s',%d,%d)", content, cssW, cssH);
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v != null && v.contains("fix")) {
                    if (viewportFixes < 3) { viewportFixes++; nudgeWebView(); }
                } else {
                    viewportFixes = 0;
                }
            }
        });
    }

    private int viewportFixes = 0;
    private boolean nudging = false;

    /** WebView'i 2 piksel küçültüp geri büyütür: tarayıcı sayfa yüksekliğini yeniden hesaplar. */
    private void nudgeWebView() {
        if (nudging || root.getHeight() <= 2) return;
        nudging = true;
        final ViewGroup.LayoutParams lp = web.getLayoutParams();
        lp.height = root.getHeight() - 2;
        web.setLayoutParams(lp);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                web.setLayoutParams(lp);
                nudging = false;
                if (wantFull && videoMode) {
                    handler.postDelayed(new Runnable() {
                        @Override public void run() { if (wantFull && videoMode) applyCssFullscreen(true, true); }
                    }, 300);
                }
            }
        }, 150);
    }

    /** Ekran ölçüsünü düzenli kontrol eder (Udemy ders değişiminde ayarı bozabiliyor). */
    private final Runnable viewportGuard = new Runnable() {
        @Override public void run() {
            if (!nudging) applyViewport();
            handler.postDelayed(this, 2000);
        }
    };

    // ------------------------------------------------------------------ helpers

    private static String desktopUserAgent(String defaultUa) {
        String chrome = "130.0.0.0";
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(defaultUa == null ? "" : defaultUa);
        if (m.find()) chrome = m.group(1);
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chrome + " Safari/537.36";
    }

    private final Runnable relayout = new Runnable() {
        @Override public void run() {
            viewportFixes = 0;
            applyViewport();
            if (wantFull && videoMode) applyCssFullscreen(true, true);
        }
    };

    private final Runnable hideHint = new Runnable() {
        @Override public void run() { hint.setVisibility(View.GONE); }
    };

    private void showHint(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.bringToFront();
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, ms);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ cursor view

    private static class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float x, y;
        private float pulse = 0f;
        private final float size;

        CursorView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            size = 26 * d;
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * d);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(0xFFA435F0);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * d);
            setClickable(false);
            setFocusable(false);
        }

        void moveTo(float nx, float ny) { x = nx; y = ny; invalidate(); }

        void pulse() {
            pulse = 1f;
            invalidate();
            postDelayed(new Runnable() {
                @Override public void run() { pulse = 0f; invalidate(); }
            }, 180);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (pulse > 0) c.drawCircle(x, y, size * 0.8f, ring);
            float s = size;
            arrow.reset();
            arrow.moveTo(x, y);
            arrow.lineTo(x, y + s);
            arrow.lineTo(x + s * 0.28f, y + s * 0.74f);
            arrow.lineTo(x + s * 0.48f, y + s * 1.12f);
            arrow.lineTo(x + s * 0.62f, y + s * 1.05f);
            arrow.lineTo(x + s * 0.43f, y + s * 0.68f);
            arrow.lineTo(x + s * 0.78f, y + s * 0.68f);
            arrow.close();
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
        }
    }
}
