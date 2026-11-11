package com.derstv.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ders TV — Udemy'yi Android TV'de kumandayla kullanmak için sade bir WebView kabuğu.
 *
 * İmleç modu : yön tuşları imleci gezdirir, OK tıklar, kenara gelince sayfa kayar.
 * Video modu : OK oynat/durdur, sol/sağ 10 sn geri/ileri, yukarı/aşağı imlece döner.
 * Uzun OK    : iki mod arasında geçiş.  Menü tuşu: yakınlaştırma seviyesi.
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

        root.post(new Runnable() {
            @Override public void run() {
                cx = root.getWidth() / 2f;
                cy = root.getHeight() / 2f;
                cursor.moveTo(cx, cy);
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
        showHint("Yön tuşları: imleç   •   OK: tıkla   •   Uzun OK: yazı boyutu / video modu   •   Geri: geri", 6000);
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
                applyViewport();
                updateModeForUrl(url);
                CookieManager.getInstance().flush();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // Udemy tek sayfa uygulaması; ders değişimleri burada yakalanır.
                applyViewport();
                updateModeForUrl(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    showHint("Sayfa açılamadı. İnternet bağlantısını kontrol edip uygulamayı yeniden açın.", 6000);
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
                if (down && e.getRepeatCount() == 0) cycleZoom();
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
        if (lecture || videoMode) {
            setVideoMode(!videoMode, true);
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
            showHint("Çıkmak için Geri'ye tekrar basın", 2000);
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
            // Udemy sonraki derse geçince oynatıcıyı yeniden kurar; tam ekranı ve kaliteyi tekrar uygula.
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (cssFull) applyCssFullscreen(true); }
            }, 1500);
            handler.removeCallbacks(highQuality);
            handler.postDelayed(highQuality, 3500);
        }
    }

    private void setVideoMode(boolean on, boolean announce) {
        videoMode = on;
        cursor.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        if (on) {
            // Ders açılınca videonun yüklenmesini bekleyip otomatik tam ekrana geç.
            handler.removeCallbacks(autoFullscreen);
            handler.postDelayed(autoFullscreen, 2500);
        } else {
            handler.removeCallbacks(autoFullscreen);
            if (isFullscreen()) exitFullscreen();
        }
        if (announce) {
            if (on) {
                showHint("Video modu  •  OK: oynat/durdur  •  ◀ ▶: 10 sn  •  ▼: tam ekran  •  ▲: imleç", 4000);
            } else {
                showHint("İmleç modu  •  Uzun OK: video modu", 2000);
            }
        }
    }

    // ------------------------------------------------------------------ fullscreen

    private boolean cssFull = false;

    private final Runnable autoFullscreen = new Runnable() {
        @Override public void run() {
            if (videoMode && !isFullscreen()) enterFullscreen();
            handler.postDelayed(highQuality, 1200);
        }
    };

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
                        if (!"0".equals(q)) showHint("Görüntü kalitesi: " + q + "p", 2000);
                    }
                });
            }
        }, 1600);
    }

    private boolean isFullscreen() {
        return customView != null || cssFull;
    }

    private void toggleFullscreen() {
        if (isFullscreen()) exitFullscreen(); else enterFullscreen();
    }

    /** Önce Udemy oynatıcısının kendi tam ekranını ("F" kısayolu) dener; olmazsa videoyu ekrana yayar. */
    private void enterFullscreen() {
        web.requestFocus();
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0));
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (customView != null || !videoMode) return;
                web.evaluateJavascript("!!(document.fullscreenElement||document.webkitFullscreenElement)",
                        new ValueCallback<String>() {
                            @Override public void onReceiveValue(String v) {
                                if (customView == null && !"true".equals(v)) applyCssFullscreen(true);
                            }
                        });
            }
        }, 800);
    }

    private void exitFullscreen() {
        if (customView != null) {
            web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (customView != null && customCallback != null) customCallback.onCustomViewHidden();
                }
            }, 300);
        }
        if (cssFull) applyCssFullscreen(false);
    }

    /** Oynatıcı kutusunu (video + altyazı + kontroller) sabit konumla tüm ekrana yayar. */
    private void applyCssFullscreen(final boolean on) {
        String js = "(function(on){var id='derstv-full',o=document.getElementById(id);if(o)o.remove();"
                + "var ms=document.querySelectorAll('[data-derstv-full],[data-derstv-anc]');"
                + "for(var k=0;k<ms.length;k++){ms[k].removeAttribute('data-derstv-full');ms[k].removeAttribute('data-derstv-anc');}"
                + "if(!on)return 'off';"
                + "var vs=document.querySelectorAll('video'),v=null,b=0;"
                + "for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),s=r.width*r.height;if(s>=b){b=s;v=vs[i];}}"
                + "if(!v||b<1)return 'none';"
                + "var vr=v.getBoundingClientRect(),c=v,p=v.parentElement;"
                + "while(p&&p!==document.body){var pr=p.getBoundingClientRect();"
                + "if(pr.width>vr.width*1.15+4||pr.height>vr.height*1.35+4)break;c=p;p=p.parentElement;}"
                + "c.setAttribute('data-derstv-full','1');"
                + "var a=c.parentElement;while(a){a.setAttribute('data-derstv-anc','1');a=a.parentElement;}"
                + "var st=document.createElement('style');st.id=id;"
                + "st.textContent='html,body{overflow:hidden!important;background:#000!important}'"
                + "+'body *{visibility:hidden!important}'"
                + "+'[data-derstv-full],[data-derstv-full] *{visibility:visible!important}'"
                + "+'[data-derstv-anc]{transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;overflow:visible!important}'"
                + "+'[data-derstv-full]{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;"
                + "width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;"
                + "margin:0!important;z-index:2147483647!important;background:#000!important;transform:none!important}'"
                + "+'[data-derstv-full] video{width:100%!important;height:100%!important;max-height:none!important;object-fit:contain!important}';"
                + "(document.head||document.documentElement).appendChild(st);return 'on';})(" + on + ")";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                String r = v == null ? "" : v.replace("\"", "");
                if (on) {
                    cssFull = "on".equals(r);
                    if (!cssFull) showHint("Video henüz yüklenmedi, biraz sonra ▼ ile tekrar deneyin", 2500);
                    else showHint("Tam ekran  •  ▼ veya Geri: çık", 1800);
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
                    showHint("Bu sayfada video bulunamadı", 2000);
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
        applyViewport();
        String[] names = {"Büyük", "Normal", "Küçük"};
        showHint("Yazı boyutu: " + names[widthIndex], 1500);
    }

    /** Sayfayı TV için sabit bir masaüstü genişliğinde (vars. 1280 px) gösterir. */
    private void applyViewport() {
        float density = getResources().getDisplayMetrics().density;
        int viewW = web.getWidth() > 0 ? web.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int cssW = WIDTHS[widthIndex];
        float scale = (viewW / density) / cssW;
        String content = String.format(Locale.US,
                "width=%d, initial-scale=%.4f, minimum-scale=%.4f, maximum-scale=%.4f, user-scalable=no",
                cssW, scale, scale, scale);
        String js = "(function(c){var m=document.querySelector('meta[name=viewport]');"
                + "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}"
                + "if(m.getAttribute('content')!==c)m.setAttribute('content',c);})('" + content + "')";
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------------ helpers

    private static String desktopUserAgent(String defaultUa) {
        String chrome = "130.0.0.0";
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(defaultUa == null ? "" : defaultUa);
        if (m.find()) chrome = m.group(1);
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chrome + " Safari/537.36";
    }

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
