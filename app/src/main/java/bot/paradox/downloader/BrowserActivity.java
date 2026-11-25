package bot.paradox.downloader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Built-in browser for pages that need a countdown, a captcha or a sign-in.
 * Any download the page starts is handed to the download engine with the
 * page's cookies; "Scan links" lists the mirrors on the current page.
 *
 * <p>Ads and trackers are blocked with the same lists uBlock Origin uses (see {@link AdBlock}):
 * requests to ad hosts get an empty answer, ad elements are hidden, pop-ups opened by scripts
 * are dropped and redirects to ad domains are cancelled. The shield in the toolbar turns it off.
 */
public final class BrowserActivity extends AppCompatActivity {
    private WebView web;
    private MaterialToolbar bar;
    private LinearProgressIndicator progress;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger blocked = new AtomicInteger();
    private volatile String pageHost;
    private volatile boolean blockOn;
    private boolean subtitlePending, popupToasted;
    private ScriptHandler startScript;

    static void open(Context c, String url) {
        c.startActivity(new Intent(c, BrowserActivity.class).putExtra("url", url));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        EdgeToEdge.enable(this);
        super.onCreate(state);
        Prefs.applyAmoled(this);
        setContentView(R.layout.activity_browser);
        bar = findViewById(R.id.toolbar);
        progress = findViewById(R.id.progress);
        web = findViewById(R.id.web);

        bar.setNavigationOnClickListener(v -> finish());
        bar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_scan) scan();
            else if (id == R.id.action_reload) web.reload();
            else if (id == R.id.action_external) external();
            else if (id == R.id.action_adblock) toggleBlocker();
            else return false;
            return true;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(true); // so pop-ups can be vetted instead of replacing the page
        blockOn = AdBlock.enabled(this);
        shieldIcon();
        s.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return true;
                // A page that sends you to an ad domain without a tap is cancelled; a tap is always honoured.
                AdFilter f = AdBlock.filter();
                if (blockOn && f != null && !req.hasGesture() && f.blocksNavigation(u.toString(), pageHost, false)) {
                    blocked.incrementAndGet();
                    subtitle();
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (!blockOn) return null;
                Uri u = req.getUrl();
                if (req.isForMainFrame()) {
                    pageHost = u.getHost();
                    return null;
                }
                AdFilter f = AdBlock.filter();
                String url = u.toString();
                if (f == null || !url.startsWith("http")) return null;
                int types = AdFilter.guessTypes(url, req.getRequestHeaders().get("Accept"), false);
                if (!f.blocks(url, pageHost, types)) return null;
                blocked.incrementAndGet();
                subtitle();
                return empty();
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                blocked.set(0);
                pageHost = Uri.parse(url).getHost();
                subtitle();
                hideForSite(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                hideForSite(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                subtitle();
                hideForSite(view, url);
                if (blockOn && startScript == null && AdBlock.script() != null) view.evaluateJavascript(AdBlock.script(), null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message resultMsg) {
                // Load the pop-up in a throw-away view only to learn where it goes, then decide.
                final WebView tmp = new WebView(BrowserActivity.this);
                final AtomicBoolean seen = new AtomicBoolean();
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest r) {
                        String u = r.getUrl().toString();
                        if (r.isForMainFrame() && u.startsWith("http") && seen.compareAndSet(false, true)) {
                            main.post(() -> {
                                popup(u, userGesture);
                                discard(tmp);
                            });
                        }
                        return empty();
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                        return true;
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(tmp);
                resultMsg.sendToTarget();
                main.postDelayed(() -> discard(tmp), 8000);
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                progress.setProgressCompat(p, true);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                bar.setTitle(title == null || title.isEmpty() ? "Browser" : title);
            }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            Option o = new Option(url, URLUtil.guessFileName(url, disposition, mime));
            o.name = Http.filename(disposition);
            o.size = length;
            o.cookie = CookieManager.getInstance().getCookie(url);
            o.referer = web.getUrl();
            DownloadService.add(this, o, getIntent().getStringExtra("url"));
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            finish();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        // Wait for the filter lists (about half a second) so the first page is already filtered.
        final Bundle saved = state;
        if (blockOn) {
            AdBlock.whenReady(this, () -> begin(saved));
            AdBlock.maybeUpdate(this);
        } else {
            begin(saved);
        }
    }

    private boolean started;

    private void begin(Bundle state) {
        if (started || isDestroyed()) return;
        started = true;
        installStartScript();
        if (state == null) {
            String url = getIntent().getStringExtra("url");
            if (url != null) web.loadUrl(url);
        } else {
            web.restoreState(state);
        }
    }

    // ---- ad blocker ------------------------------------------------------------------------

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", 200, "OK", new HashMap<String, String>(),
                new ByteArrayInputStream(new byte[0]));
    }

    /** Hides ad elements as the page is built, in every frame, when the WebView supports it. */
    private void installStartScript() {
        if (startScript != null) {
            startScript.remove();
            startScript = null;
        }
        String js = AdBlock.script();
        if (!blockOn || js == null || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try {
            startScript = WebViewCompat.addDocumentStartJavaScript(web, js, Collections.singleton("*"));
        } catch (RuntimeException e) {
            startScript = null; // falls back to running it when the page has loaded
        }
    }

    private void hideForSite(WebView view, String url) {
        AdFilter f = AdBlock.filter();
        if (!blockOn || f == null || url == null) return;
        String js = AdBlock.siteScript(f, Uri.parse(url).getHost());
        if (js != null) view.evaluateJavascript(js, null);
    }

    private void popup(String url, boolean gesture) {
        AdFilter f = AdBlock.filter();
        boolean drop = false;
        if (blockOn && f != null) {
            String host = Uri.parse(url).getHost();
            boolean sameSite = host != null && pageHost != null
                    && AdFilter.sameSite(host.toLowerCase(java.util.Locale.ROOT), pageHost.toLowerCase(java.util.Locale.ROOT));
            // A tap can open anything that isn't on an ad list; with no tap only the site's own pages may open.
            drop = f.blocksNavigation(url, pageHost, true) || (!gesture && !sameSite);
        }
        if (drop) {
            blocked.incrementAndGet();
            subtitle();
            if (!popupToasted) {
                popupToasted = true;
                Toast.makeText(this, "Blocked a pop-up ad", Toast.LENGTH_SHORT).show();
            }
        } else {
            web.loadUrl(url);
        }
    }

    private void discard(WebView tmp) {
        if (tmp.getParent() != null) return;
        try {
            tmp.stopLoading();
            tmp.destroy();
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    private void subtitle() {
        if (subtitlePending) return;
        subtitlePending = true;
        main.postDelayed(() -> {
            subtitlePending = false;
            if (isDestroyed()) return;
            String host = pageHost == null ? "" : pageHost;
            int n = blocked.get();
            bar.setSubtitle(blockOn && n > 0 ? host + " · " + n + " blocked" : host);
        }, 250);
    }

    private void shieldIcon() {
        android.view.MenuItem item = bar.getMenu().findItem(R.id.action_adblock);
        if (item == null) return;
        item.setTitle(blockOn ? "Ad blocker: on" : "Ad blocker: off");
        if (item.getIcon() != null) item.getIcon().mutate().setAlpha(blockOn ? 255 : 90);
    }

    private void toggleBlocker() {
        blockOn = !blockOn;
        AdBlock.enabled(this, blockOn);
        shieldIcon();
        if (blockOn) {
            AdBlock.whenReady(this, () -> {
                installStartScript();
                web.reload();
            });
        } else {
            installStartScript();
            web.reload();
        }
        Toast.makeText(this, blockOn ? "Ad blocker on" : "Ad blocker off for now. Turn it on from the shield.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        web.stopLoading();
        web.destroy();
        if (isFinishing()) AdBlock.release();
        super.onDestroy();
    }

    private void scan() {
        String page = web.getUrl();
        if (page == null) return;
        web.evaluateJavascript("(function(){return document.documentElement.outerHTML.slice(0,800000)})()", value -> {
            String html;
            try {
                html = new JSONArray("[" + value + "]").getString(0);
            } catch (Exception e) {
                html = "";
            }
            List<Option> options = Resolver.scan(page, html, CookieManager.getInstance().getCookie(page));
            if (options.isEmpty()) {
                Toast.makeText(this, "No download links found yet. Tap the site's download button.", Toast.LENGTH_LONG).show();
                return;
            }
            String[] labels = new String[options.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = options.get(i).label + "\n" + options.get(i).detail();
            new MaterialAlertDialogBuilder(this)
                    .setTitle(options.size() + " links found")
                    .setItems(labels, (d, w) -> web.loadUrl(options.get(w).url))
                    .setNegativeButton("Close", null)
                    .show();
        });
    }

    private void external() {
        String url = web.getUrl();
        if (url == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception e) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }
}
