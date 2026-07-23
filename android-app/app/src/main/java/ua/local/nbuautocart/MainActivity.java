package ua.local.nbuautocart;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOME = "https://coins.bank.gov.ua/";
    private static final String RELEASE_API = "https://api.github.com/repos/newdedsasun-svg/nbu-auto-cart/releases/latest";
    private static final String MOBILE_CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36";
    // Частіше ніж раз на секунду магазин може сприйняти перевірки як атаку
    // та заблокувати IP або обліковий запис.
    private static final long CHECK_DELAY_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> urls = new ArrayList<>();
    private WebView webView;
    private WebView popupWebView;
    private LinearLayout root;
    private EditText linksInput;
    private TextView status;
    private Button startButton;
    private Button stopButton;
    private boolean running = false;
    private int currentIndex = 0;
    private long updateDownloadId = -1;
    private boolean updateReceiverRegistered = false;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != updateDownloadId) return;
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri apk = manager.getUriForDownloadedFile(id);
            if (apk == null) {
                Toast.makeText(MainActivity.this, "Не вдалося завантажити оновлення", Toast.LENGTH_LONG).show();
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apk, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(install);
            } catch (Exception error) {
                Toast.makeText(MainActivity.this, "Не вдалося відкрити інсталятор APK", Toast.LENGTH_LONG).show();
            }
        }
    };

    private final Runnable nextCheck = () -> {
        if (!running || urls.isEmpty()) return;
        currentIndex = (currentIndex + 1) % urls.size();
        String next = urls.get(currentIndex);
        setStatus("Перевіряю " + (currentIndex + 1) + " із " + urls.size());
        if (samePage(webView.getUrl(), next)) webView.reload();
        else webView.loadUrl(next);
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Автокошик НБУ");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.rgb(13, 77, 150));
        title.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        linksInput = new EditText(this);
        linksInput.setHint("Посилання на товари — кожне з нового рядка");
        linksInput.setMinLines(2);
        linksInput.setMaxLines(4);
        linksInput.setText(getPreferences(MODE_PRIVATE).getString("links", ""));
        root.addView(linksInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("Запустити");
        stopButton = new Button(this);
        stopButton.setText("Зупинити");
        Button homeButton = new Button(this);
        homeButton.setText("НБУ / Вхід");
        Button updateButton = new Button(this);
        updateButton.setText("Оновити");
        controls.addView(startButton, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(stopButton, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(homeButton, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(updateButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Спочатку увійдіть у магазин НБУ");
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUserAgentString(MOBILE_CHROME_USER_AGENT);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        settings.setSupportMultipleWindows(true);
        webView.setWebChromeClient(createChromeClient());
        webView.addJavascriptInterface(new CartBridge(), "NbuCartApp");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // Авторизація магазину може переходити на інший захищений домен.
                // Залишаємо всі HTTPS-сторінки в цьому WebView, щоб не втратити сесію входу.
                if ("https".equalsIgnoreCase(uri.getScheme())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (running && containsUrl(url)) {
                    setStatus("Шукаю активну кнопку покупки…");
                    injectBuyCheck();
                }
            }
        });

        startButton.setOnClickListener(v -> startMonitoring());
        stopButton.setOnClickListener(v -> stopMonitoring("Зупинено"));
        homeButton.setOnClickListener(v -> {
            stopMonitoring("Відкрито сторінку входу");
            webView.loadUrl(HOME);
        });
        updateButton.setOnClickListener(v -> checkForUpdate());
        IntentFilter updateFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, updateFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updateReceiver, updateFilter);
        updateReceiverRegistered = true;
        stopButton.setEnabled(false);
        webView.loadUrl(HOME);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebChromeClient createChromeClient() {
        return new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                closePopup();
                popupWebView = new WebView(MainActivity.this);
                WebSettings popupSettings = popupWebView.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setDomStorageEnabled(true);
                popupSettings.setDatabaseEnabled(true);
                popupSettings.setUserAgentString(MOBILE_CHROME_USER_AGENT);
                popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);
                popupSettings.setSupportMultipleWindows(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true);
                popupWebView.setWebChromeClient(createChromeClient());
                popupWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        if ("https".equalsIgnoreCase(uri.getScheme())) return false;
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    }
                });
                webView.setVisibility(View.GONE);
                root.addView(popupWebView, new LinearLayout.LayoutParams(-1, 0, 1));
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupWebView);
                resultMsg.sendToTarget();
                setStatus("Відкрито вікно авторизації НБУ");
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                closePopup();
            }
        };
    }

    private void closePopup() {
        if (popupWebView != null) {
            root.removeView(popupWebView);
            popupWebView.destroy();
            popupWebView = null;
        }
        if (webView != null) webView.setVisibility(View.VISIBLE);
    }

    private void checkForUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Дозвольте встановлення оновлень для цього застосунку, потім натисніть «Оновити» ще раз", Toast.LENGTH_LONG).show();
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        setStatus("Перевіряю оновлення…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "NbuAutoCart-Android");
                if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
                String json;
                try (Scanner scanner = new Scanner(connection.getInputStream(), "UTF-8").useDelimiter("\\A")) {
                    json = scanner.hasNext() ? scanner.next() : "";
                }
                Matcher versionMatch = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"v?([^\\\"]+)\\\"").matcher(json);
                Matcher urlMatch = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+\\.apk)\\\"").matcher(json);
                if (!versionMatch.find() || !urlMatch.find()) throw new Exception("APK не знайдено");
                String latest = versionMatch.group(1);
                String apkUrl = urlMatch.group(1).replace("\\/", "/");
                runOnUiThread(() -> {
                    if (BuildConfig.VERSION_NAME.equals(latest)) {
                        setStatus("Установлена найновіша версія " + latest);
                        Toast.makeText(this, "Оновлень немає", Toast.LENGTH_SHORT).show();
                    } else {
                        setStatus("Завантажую версію " + latest + "…");
                        downloadUpdate(apkUrl, latest);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setStatus("Не вдалося перевірити оновлення");
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void downloadUpdate(String apkUrl, String version) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("Автокошик НБУ " + version);
        request.setDescription("Завантаження оновлення");
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                "NBU-AutoCart-" + version + "-" + System.currentTimeMillis() + ".apk");
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        updateDownloadId = manager.enqueue(request);
    }

    private void startMonitoring() {
        LinkedHashSet<String> valid = new LinkedHashSet<>();
        for (String value : linksInput.getText().toString().split("[\\n,;]+")) {
            String url = validateUrl(value.trim());
            if (url != null) valid.add(url);
        }
        if (valid.isEmpty()) {
            Toast.makeText(this, "Вставте посилання з coins.bank.gov.ua", Toast.LENGTH_LONG).show();
            return;
        }
        urls.clear();
        urls.addAll(valid);
        getPreferences(MODE_PRIVATE).edit().putString("links", String.join("\n", urls)).apply();
        currentIndex = 0;
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus("Моніторинг запущено");
        webView.loadUrl(urls.get(0));
    }

    private void injectBuyCheck() {
        handler.removeCallbacks(nextCheck);
        String script = "(function(){" +
                "const ok=/^(додати\\s+(?:до|у|в)\\s+кошик|у\\s+кошик|в\\s+кошик|купити)$/i;" +
                "const bad=/(оформити|оплатити|перейти\\s+до\\s+кошика)/i;" +
                "const els=[...document.querySelectorAll('button,input[type=button],input[type=submit],a[role=button],a.btn,.btn')];" +
                "const b=els.find(e=>{const t=(e.value||e.textContent||e.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim();" +
                "const r=e.getBoundingClientRect(),s=getComputedStyle(e);" +
                "return ok.test(t)&&!bad.test(t)&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'&&r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';});" +
                "if(b){b.scrollIntoView({block:'center'});b.click();NbuCartApp.added();return 'clicked';}" +
                "return 'waiting';})()";
        webView.evaluateJavascript(script, result -> {
            if (running && !"\"clicked\"".equals(result)) handler.postDelayed(nextCheck, CHECK_DELAY_MS);
        });
    }

    private class CartBridge {
        @JavascriptInterface
        public void added() {
            runOnUiThread(() -> {
                if (!running) return;
                stopMonitoring("Товар додано — перевірте кошик");
                Toast.makeText(MainActivity.this, "Товар додано в кошик!", Toast.LENGTH_LONG).show();
                Vibrator vibrator = getSystemService(Vibrator.class);
                if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE));
            });
        }
    }

    private void stopMonitoring(String message) {
        running = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(nextCheck);
        if (startButton != null) startButton.setEnabled(true);
        if (stopButton != null) stopButton.setEnabled(false);
        setStatus(message);
    }

    private boolean containsUrl(String value) {
        String checked = validateUrl(value);
        return checked != null && urls.stream().anyMatch(url -> samePage(url, checked));
    }

    private boolean samePage(String first, String second) {
        String a = validateUrl(first);
        String b = validateUrl(second);
        return a != null && a.equals(b);
    }

    private String validateUrl(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"coins.bank.gov.ua".equalsIgnoreCase(uri.getHost())) return null;
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (popupWebView != null && popupWebView.canGoBack()) popupWebView.goBack();
        else if (popupWebView != null) closePopup();
        else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (updateReceiverRegistered) unregisterReceiver(updateReceiver);
        if (popupWebView != null) popupWebView.destroy();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
