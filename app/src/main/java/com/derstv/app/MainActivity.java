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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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

        buildPlayerMenu();

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
        // Uygulama her açıldığında güncelleme kontrol edilir.
        prefs.edit().putLong("lastUpdateCheck", 0).apply();
        handler.postDelayed(updateCheck, 6000);
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
                menuBar.bringToFront();
                menuScroll.bringToFront();
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
                    if (videoMode) { if (menuVisible()) hideMenu(); else showMenu(); } else cycleZoom();
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
                        onMenuOk();
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
            if (videoMode && menuVisible()) {
                onMenuDpad(code);
                return true;
            }
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
        if (menuVisible()) { onMenuOk(); return; }
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
        if (menuScroll.getVisibility() == View.VISIBLE) {
            hidePopup();
            return;
        }
        if (menuVisible()) {
            hideMenu();
            return;
        }
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
        if (!on) hideMenu();
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
                    + "if(v&&" + speedStr() + "!==1&&Math.abs(v.playbackRate-" + speedStr() + ")>0.01)v.playbackRate=" + speedStr() + ";"
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

    /** Altyazı hedef dili: menüden seçilen dil; seçilmediyse TV'nin dili (Türkçe değilse İngilizce). */
    private String subLang() {
        String def = "tr".equals(Locale.getDefault().getLanguage()) ? "tr" : "en";
        return prefs.getString("subLang", def);
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
                + "window.__dtvSub&&window.__dtvSub.setSize('" + subSize() + "');"
                + "window.__dtvSub&&window.__dtvSub.setMode('" + subMode() + "');", null);
    }

    /** Uzun OK: Otomatik → Kapalı → Her zaman açık → Otomatik */
    private void toggleSubtitles() {
        String cur = subMode();
        setSubMode("auto".equals(cur) ? "off" : "off".equals(cur) ? "on" : "auto");
    }

    private void setSubMode(String next) {
        prefs.edit().putString("subMode", next).apply();
        injectSubtitles(web.getUrl());
        web.evaluateJavascript("window.__dtvSub&&window.__dtvSub.setMode('" + next + "')", null);
        int id = "off".equals(next) ? R.string.subs_off : "on".equals(next) ? R.string.subs_on : R.string.subs_auto;
        showHint(getString(id), 3000);
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

    // ------------------------------------------------------------------ player menu (VLC tarzı alt menü)

    private static final String[] MENU_IDS = {"prev", "back", "play", "fwd", "next", "speed", "subs", "zoom", "full"};
    private static final String[] SPEEDS = {"0.75", "1", "1.25", "1.5", "1.75", "2"};
    private static final String[] SUB_LANGS = {"tr", "en", "de", "fr", "es", "it", "pt", "ru", "ar", "nl"};
    private static final String[] SUB_LANG_NAMES = {"Türkçe", "English", "Deutsch", "Français", "Español",
            "Italiano", "Português", "Русский", "العربية", "Nederlands"};
    private static final String[] SUB_SIZES = {"0.8", "1", "1.3", "1.6"};
    private static final long MENU_HIDE_MS = 6000;

    private LinearLayout menuBar, menuRow, menuPopup;
    private ScrollView menuScroll;   // açılır liste uzun olursa kaydırılır
    private TextView menuTime;
    private ProgressBar menuProgress;
    private final List<TextView> menuItems = new ArrayList<>();
    private final List<TextView> popupItems = new ArrayList<>();
    private final List<String> popupValues = new ArrayList<>();
    private int menuIndex = 2, popupIndex = 0;
    private String popupKind = null;
    private boolean videoPaused = false;

    private float speed() {
        try { return Float.parseFloat(prefs.getString("speed", "1")); } catch (Exception e) { return 1f; }
    }

    /** Altyazı yazı boyutu çarpanı (varsayılan 1 = orta). */
    private String subSize() { return prefs.getString("subSize", "1"); }

    private String speedStr() { return String.format(Locale.US, "%.2f", speed()); }

    private static String speedLabel(String v) {
        return v.replace(".", ",") + "×";
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Alt menüyü (ilerleme çubuğu + düğmeler) ve açılır listeyi kurar; başlangıçta gizlidir. */
    private void buildPlayerMenu() {
        menuBar = new LinearLayout(this);
        menuBar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xEE000000, 0xB0000000, 0x00000000});
        menuBar.setBackground(fade);
        menuBar.setPadding(dp(32), dp(36), dp(32), dp(20));
        menuBar.setVisibility(View.GONE);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        menuTime = new TextView(this);
        menuTime.setTextColor(Color.WHITE);
        menuTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        menuTime.setText("0:00 / 0:00");
        menuTime.setPadding(0, 0, dp(16), 0);
        timeRow.addView(menuTime);
        menuProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        menuProgress.setMax(1000);
        menuProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF26B3A));
        menuProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        timeRow.addView(menuProgress, new LinearLayout.LayoutParams(0, dp(8), 1f));
        menuBar.addView(timeRow);

        menuRow = new LinearLayout(this);
        menuRow.setOrientation(LinearLayout.HORIZONTAL);
        menuRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        for (int i = 0; i < MENU_IDS.length; i++) {
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = dp(4);
            menuRow.addView(t, lp);
            menuItems.add(t);
        }
        menuBar.addView(menuRow, rowLp);
        root.addView(menuBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        menuScroll = new ScrollView(this);
        menuScroll.setBackground(roundBg(0xF0202024, 10));
        menuScroll.setVerticalScrollBarEnabled(true);
        menuScroll.setFocusable(false);
        menuScroll.setVisibility(View.GONE);
        menuPopup = new LinearLayout(this);
        menuPopup.setOrientation(LinearLayout.VERTICAL);
        menuPopup.setPadding(dp(6), dp(6), dp(6), dp(6));
        menuScroll.addView(menuPopup);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        slp.topMargin = dp(24);
        root.addView(menuScroll, slp);
        refreshMenuLabels();
    }

    private boolean menuVisible() {
        return menuBar != null && menuBar.getVisibility() == View.VISIBLE;
    }

    private void showMenu() {
        if (!videoMode) return;
        if (!menuVisible()) menuIndex = 2;   // açılınca odak oynat/durdur düğmesinde
        menuBar.setVisibility(View.VISIBLE);
        menuBar.bringToFront();
        menuScroll.bringToFront();
        hint.bringToFront();
        refreshMenuLabels();
        handler.removeCallbacks(menuTicker);
        handler.post(menuTicker);
        bumpMenuTimer();
    }

    private void hideMenu() {
        if (menuBar == null) return;
        hidePopup();
        menuBar.setVisibility(View.GONE);
        handler.removeCallbacks(menuTicker);
        handler.removeCallbacks(menuAutoHide);
    }

    private final Runnable menuAutoHide = new Runnable() {
        @Override public void run() { hideMenu(); }
    };

    private void bumpMenuTimer() {
        handler.removeCallbacks(menuAutoHide);
        handler.postDelayed(menuAutoHide, menuScroll.getVisibility() == View.VISIBLE ? MENU_HIDE_MS * 2 : MENU_HIDE_MS);
    }

    private String menuLabel(String id) {
        switch (id) {
            case "prev": return getString(R.string.menu_prev);
            case "back": return getString(R.string.menu_back);
            case "play": return videoPaused ? "▶" : "⏸";
            case "fwd": return getString(R.string.menu_fwd);
            case "next": return getString(R.string.menu_next);
            case "speed": return getString(R.string.menu_speed, speedLabel(trimSpeed(speed())));
            case "subs": return getString(R.string.menu_subs);
            case "zoom": return getString(R.string.menu_zoom);
            default: return getString(isFullscreen() ? R.string.menu_full_exit : R.string.menu_full);
        }
    }

    private static String trimSpeed(float f) {
        String s = String.format(Locale.US, "%.2f", f);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void refreshMenuLabels() {
        for (int i = 0; i < menuItems.size(); i++) {
            TextView t = menuItems.get(i);
            t.setText(menuLabel(MENU_IDS[i]));
            boolean sel = i == menuIndex && menuScroll.getVisibility() != View.VISIBLE;
            boolean open = i == menuIndex && menuScroll.getVisibility() == View.VISIBLE;
            t.setBackground(sel ? roundBg(0xFFF26B3A, 8) : open ? roundBg(0x66F26B3A, 8) : null);
            t.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** Menü açıkken her yarım saniyede süre, ilerleme ve oynat/durdur durumunu günceller. */
    private final Runnable menuTicker = new Runnable() {
        @Override public void run() {
            if (!menuVisible()) return;
            web.evaluateJavascript("(function(){var vs=document.querySelectorAll('video'),v=null,b=0;"
                    + "for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),s=r.width*r.height;if(s>=b){b=s;v=vs[i];}}"
                    + "if(!v)return 'none';return (v.paused?'p':'r')+'|'+v.currentTime+'|'+(isFinite(v.duration)?v.duration:0);})()",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            if (value == null || !menuVisible()) return;
                            String[] p = value.replace("\"", "").split("\\|");
                            if (p.length < 3) return;
                            try {
                                double cur = Double.parseDouble(p[1]), dur = Double.parseDouble(p[2]);
                                videoPaused = p[0].equals("p");
                                menuTime.setText(fmt(String.valueOf((long) cur)) + " / " + fmt(String.valueOf((long) dur)));
                                menuProgress.setProgress(dur > 0 ? (int) (cur * 1000 / dur) : 0);
                                menuItems.get(2).setText(menuLabel("play"));
                            } catch (NumberFormatException ignored) { }
                        }
                    });
            handler.postDelayed(this, 500);
        }
    };

    private void onMenuOk() {
        if (menuScroll.getVisibility() == View.VISIBLE) { choosePopup(); return; }
        if (!menuVisible()) { showMenu(); return; }
        bumpMenuTimer();
        String id = MENU_IDS[menuIndex];
        switch (id) {
            case "prev": hideMenu(); goLecture(-1); break;
            case "next": hideMenu(); goLecture(1); break;
            case "back": videoCommand(String.valueOf(-SEEK_SEC)); break;
            case "fwd": videoCommand(String.valueOf(SEEK_SEC)); break;
            case "play":
                videoCommand("toggle");
                videoPaused = !videoPaused;
                refreshMenuLabels();
                break;
            case "full": toggleFullscreen(); refreshMenuLabels(); break;
            default: openPopup(id); break;
        }
    }

    private void onMenuDpad(int code) {
        bumpMenuTimer();
        if (menuScroll.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) { popupIndex = Math.max(0, popupIndex - 1); refreshPopup(); }
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) { popupIndex = Math.min(popupItems.size() - 1, popupIndex + 1); refreshPopup(); }
            else hidePopup();
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) menuIndex = Math.max(0, menuIndex - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) menuIndex = Math.min(MENU_IDS.length - 1, menuIndex + 1);
        else if (code == KeyEvent.KEYCODE_DPAD_UP) { hideMenu(); return; }
        refreshMenuLabels();
    }

    private void addPopupItem(String label, String value, boolean checked) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setPadding(dp(16), dp(7), dp(28), dp(7));
        t.setText((checked ? "✓  " : "     ") + label);
        menuPopup.addView(t);
        popupItems.add(t);
        popupValues.add(value);
        if (checked) popupIndex = popupItems.size() - 1;
    }

    private void addPopupHeader(String label) {
        TextView t = new TextView(this);
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(16), dp(8), dp(16), dp(2));
        t.setText(label);
        menuPopup.addView(t);
    }

    private void openPopup(String kind) {
        menuPopup.removeAllViews();
        popupItems.clear();
        popupValues.clear();
        popupIndex = 0;
        popupKind = kind;
        if ("speed".equals(kind)) {
            float cur = speed();
            for (String v : SPEEDS) addPopupItem(speedLabel(v), v, Math.abs(Float.parseFloat(v) - cur) < 0.01f);
        } else if ("zoom".equals(kind)) {
            String[] names = getResources().getStringArray(R.array.zoom_names);
            int z = zoomIndex();
            for (int i = 0; i < names.length; i++) addPopupItem(names[i], String.valueOf(i), i == z);
        } else {
            String mode = subMode(), lang = subLang();
            addPopupHeader(getString(R.string.menu_subs_mode));
            addPopupItem(getString(R.string.subs_mode_auto), "mode:auto", "auto".equals(mode));
            addPopupItem(getString(R.string.subs_mode_off), "mode:off", "off".equals(mode));
            addPopupItem(getString(R.string.subs_mode_on), "mode:on", "on".equals(mode));
            int keep = popupIndex;
            addPopupHeader(getString(R.string.menu_subs_size));
            String[] sizeNames = getResources().getStringArray(R.array.sub_sizes);
            String size = subSize();
            for (int i = 0; i < SUB_SIZES.length; i++) addPopupItem(sizeNames[i], "size:" + SUB_SIZES[i], SUB_SIZES[i].equals(size));
            addPopupHeader(getString(R.string.menu_subs_lang));
            for (int i = 0; i < SUB_LANGS.length; i++) addPopupItem(SUB_LANG_NAMES[i], "lang:" + SUB_LANGS[i], SUB_LANGS[i].equals(lang));
            popupIndex = keep;   // odak mod seçiminde başlasın
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuScroll.getLayoutParams();
        TextView anchor = menuItems.get(menuIndex);
        lp.leftMargin = Math.max(dp(8), menuBar.getLeft() + menuRow.getLeft() + anchor.getLeft());
        lp.bottomMargin = menuBar.getHeight() - menuRow.getTop() + dp(6);
        menuScroll.setLayoutParams(lp);
        menuScroll.setVisibility(View.VISIBLE);
        menuScroll.scrollTo(0, 0);
        menuScroll.bringToFront();
        refreshPopup();
        refreshMenuLabels();
        bumpMenuTimer();
    }

    private void refreshPopup() {
        for (int i = 0; i < popupItems.size(); i++) {
            popupItems.get(i).setBackground(i == popupIndex ? roundBg(0xFFF26B3A, 6) : null);
        }
        if (popupIndex >= 0 && popupIndex < popupItems.size()) {
            final TextView sel = popupItems.get(popupIndex);
            menuScroll.post(new Runnable() {
                @Override public void run() {
                    int y = sel.getTop() - menuScroll.getHeight() / 2 + sel.getHeight() / 2;
                    menuScroll.smoothScrollTo(0, Math.max(0, y));
                }
            });
        }
    }

    private void hidePopup() {
        if (menuScroll == null || menuScroll.getVisibility() != View.VISIBLE) return;
        menuScroll.setVisibility(View.GONE);
        popupKind = null;
        refreshMenuLabels();
    }

    private void choosePopup() {
        if (popupIndex < 0 || popupIndex >= popupValues.size()) { hidePopup(); return; }
        String v = popupValues.get(popupIndex);
        if ("speed".equals(popupKind)) {
            prefs.edit().putString("speed", v).apply();
            web.evaluateJavascript("(function(){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++)vs[i].playbackRate=" + v + ";})()", null);
            showHint(getString(R.string.speed_set, speedLabel(v)), 1500);
        } else if ("zoom".equals(popupKind)) {
            prefs.edit().putInt("videoZoom", Integer.parseInt(v)).apply();
            if (!isFullscreen()) enterFullscreen(); else applyVideoZoom();
            showHint(getString(R.string.video_zoom, getResources().getStringArray(R.array.zoom_names)[Integer.parseInt(v)]), 1500);
        } else if (v.startsWith("mode:")) {
            setSubMode(v.substring(5));
        } else if (v.startsWith("size:")) {
            String k = v.substring(5);
            prefs.edit().putString("subSize", k).apply();
            injectSubtitles(web.getUrl());
            web.evaluateJavascript("window.__dtvSub&&window.__dtvSub.setSize('" + k + "')", null);
            int idx = java.util.Arrays.asList(SUB_SIZES).indexOf(k);
            showHint(getString(R.string.subs_size_set, getResources().getStringArray(R.array.sub_sizes)[Math.max(0, idx)]), 2000);
        } else if (v.startsWith("lang:")) {
            prefs.edit().putString("subLang", v.substring(5)).apply();
            if ("off".equals(subMode())) prefs.edit().putString("subMode", "auto").apply();
            injectSubtitles(web.getUrl());
            showHint(getString(R.string.subs_lang_set, popupItems.get(popupIndex).getText().toString().trim().replace("✓", "").trim()), 2000);
        }
        hidePopup();
        refreshMenuLabels();
        bumpMenuTimer();
    }

    /** Önceki / sonraki derse geçer: önce Udemy'nin kendi düğmesi, yoksa müfredat listesi. */
    private void goLecture(int dir) {
        String js = "(function(dir){var m=location.pathname.match(/\\/course\\/(.+?)\\/learn\\/lecture\\/(\\d+)/);if(!m)return 'no';"
                + "var b=document.querySelector(dir>0?'[data-purpose=\"go-to-next\"]':'[data-purpose=\"go-to-previous\"]');"
                + "if(b){b.click();return 'click';}"
                + "var slug=m[1],lid=m[2],d=slug.match(/^draft\\/(\\d+)$/);"
                + "function cid(){if(d)return Promise.resolve(d[1]);var e=document.querySelectorAll('[data-module-args]');"
                + "for(var i=0;i<e.length;i++){var x=(e[i].getAttribute('data-module-args')||'').match(/\"courseId\"\\s*:\\s*(\\d+)/);if(x)return Promise.resolve(x[1]);}"
                + "return fetch('/api-2.0/courses/'+slug+'/?fields[course]=id',{credentials:'include'}).then(function(r){return r.json();}).then(function(j){return j.id;});}"
                + "function all(u,acc){return fetch(u,{credentials:'include'}).then(function(r){return r.json();}).then(function(j){"
                + "acc=acc.concat(j.results||[]);return j.next?all(j.next,acc):acc;});}"
                + "cid().then(function(c){return all('/api-2.0/courses/'+c+'/subscriber-curriculum-items/?page_size=200&fields[lecture]=id&fields[quiz]=id&fields[practice]=id&fields[chapter]=id',[]);})"
                + ".then(function(items){var ls=items.filter(function(x){return x._class==='lecture';});"
                + "for(var i=0;i<ls.length;i++){if(String(ls[i].id)===lid){var t=ls[i+dir];"
                + "if(t)location.assign(location.pathname.replace(/lecture\\/\\d+/,'lecture/'+t.id));return;}}});"
                + "return 'api';})(" + dir + ")";
        web.evaluateJavascript(js, null);
        showHint(getString(dir > 0 ? R.string.lecture_next : R.string.lecture_prev), 2000);
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
    /** Uygulama açılışında her zaman; arka plandan dönüşte en fazla 10 dakikada bir kontrol. */
    private static final long UPDATE_INTERVAL_MS = 10L * 60 * 1000;
    /** "Sonra" denilen sürüm, uygulama kapanana kadar tekrar sorulmaz. */
    private int dismissedBuild = 0;
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
                        // GitHub'ın normal sürüm sayfası kullanılır (API'nin saatte 60 istek sınırı yok).
                        // Uses the plain releases page: no API rate limit, follows repo/user renames.
                        final String tag = latestReleaseTag(repo);
                        final int latest = parseBuildNumber(tag);
                        if (latest <= currentVersionCode() || latest == dismissedBuild) return;
                        final String url = "https://github.com/" + repo + "/releases/download/" + tag + "/EduCastTV.apk";
                        final String name = "EduCast TV 2.1." + latest;
                        String body = "";
                        try {   // sürüm notları isteğe bağlı / release notes are optional
                            JSONObject rel = new JSONObject(httpGet("https://api.github.com/repos/" + repo + "/releases/tags/" + tag));
                            body = rel.optString("body", "").trim();
                        } catch (Exception ignored) { }
                        if (body.length() > 600) body = body.substring(0, 600) + "…";
                        final String notes = body;
                        handler.post(new Runnable() {
                            @Override public void run() { showUpdateDialog(name, notes, url, latest); }
                        });
                    } catch (Exception ignored) {
                        // Ağ yoksa bir sonraki açılışta tekrar denenir.
                        prefs.edit().putLong("lastUpdateCheck", 0).apply();
                    }
                }
            });
        }
    };

    /** github.com/<repo>/releases/latest adresinin yönlendirmesinden son sürüm etiketini (ör. "v60") okur. */
    private static String latestReleaseTag(String repo) throws Exception {
        String url = "https://github.com/" + repo + "/releases/latest";
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "EduCastTV-updater");
            try {
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    int i = loc.indexOf("/releases/tag/");
                    if (i >= 0) {
                        String tag = loc.substring(i + "/releases/tag/".length());
                        int q = tag.indexOf('?');
                        return q >= 0 ? tag.substring(0, q) : tag;
                    }
                    url = loc.startsWith("http") ? loc : "https://github.com" + loc;   // kullanıcı/depo adı değişmiş
                    continue;
                }
                throw new java.io.IOException("HTTP " + code);
            } finally {
                c.disconnect();
            }
        }
        throw new java.io.IOException("too many redirects");
    }

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

    private void showUpdateDialog(String name, String notes, final String url, final int build) {
        if (isFinishing()) return;
        String msg = getString(R.string.update_message, name) + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { downloadAndInstall(url); }
                })
                .setNegativeButton(R.string.update_later, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { dismissedBuild = build; }
                })
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
