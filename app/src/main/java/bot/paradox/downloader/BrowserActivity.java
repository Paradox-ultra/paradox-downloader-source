package bot.paradox.downloader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;

import java.util.List;

/**
 * Built-in browser for pages that need a countdown, a captcha or a sign-in.
 * Any download the page starts is handed to the download engine with the
 * page's cookies; "Scan links" lists the mirrors on the current page.
 */
public final class BrowserActivity extends AppCompatActivity {
    private WebView web;
    private MaterialToolbar bar;
    private LinearProgressIndicator progress;

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
            else return false;
            return true;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                return scheme == null || !(scheme.equals("http") || scheme.equals("https"));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bar.setSubtitle(Uri.parse(url).getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setSubtitle(Uri.parse(url).getHost());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
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

        if (state == null) {
            String url = getIntent().getStringExtra("url");
            if (url != null) web.loadUrl(url);
        } else {
            web.restoreState(state);
        }
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
