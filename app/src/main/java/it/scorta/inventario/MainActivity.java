package it.scorta.inventario;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Offline Android shell for Scorta.
 * All products, sales and inventory entries are stored in Android WebView's
 * private localStorage, not remotely. The bridge only provides printing and
 * user-approved file exports. There is no INTERNET permission.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/assets/";
    private static final int REQUEST_IMPORT = 201;
    private static final int REQUEST_EXPORT = 202;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private String pendingExport;
    private boolean printedExternally = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff101b31);
        getWindow().setNavigationBarColor(0xff101b31);

        webView = new WebView(this);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.addJavascriptInterface(new AndroidInterface(), "AndroidApp");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isLocalAsset(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!isLocalAsset(uri)) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                String resource = uri.getPath().substring("/assets/".length());
                if (resource.isEmpty() || resource.contains("..") || resource.contains("\\")) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                try {
                    InputStream stream = getAssets().open(resource);
                    String mime = mimeFor(resource);
                    return new WebResourceResponse(mime, mime.startsWith("image/") ? null : "UTF-8", stream);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                    .setPositiveButton("Conferma", (d, w) -> result.confirm())
                    .setNegativeButton("Annulla", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }
            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                    .setPositiveButton("OK", (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }
            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_IMPORT);
                    return true;
                } catch (Exception ex) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "Impossibile aprire il selettore file", Toast.LENGTH_LONG).show();
                    callback.onReceiveValue(null);
                    return true;
                }
            }
        });

        webView.loadUrl(BASE + "index.html");
    }

    private static boolean isLocalAsset(Uri uri) {
        return "https".equals(uri.getScheme())
                && HOST.equals(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().startsWith("/assets/");
    }

    private static String mimeFor(String path) {
        String s = path.toLowerCase(Locale.ROOT);
        if (s.endsWith(".html")) return "text/html";
        if (s.endsWith(".svg")) return "image/svg+xml";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".css")) return "text/css";
        if (s.endsWith(".js")) return "application/javascript";
        if (s.endsWith(".webmanifest")) return "application/manifest+json";
        return "application/octet-stream";
    }

    private final class AndroidInterface {
        @JavascriptInterface
        public void printDocument() {
            runOnUiThread(() -> {
                try {
                    printedExternally = true;
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    pm.print("Scorta - stampa", webView.createPrintDocumentAdapter("Scorta"),
                            new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .build());
                } catch (Exception ex) {
                    printedExternally = false;
                    Toast.makeText(MainActivity.this, "Stampa non disponibile", Toast.LENGTH_LONG).show();
                    webView.evaluateJavascript("document.body.classList.remove('print-ready')", null);
                }
            });
        }

        @JavascriptInterface
        public void saveTextFile(String requestedName, String mimeType, String content) {
            runOnUiThread(() -> {
                if (pendingExport != null) {
                    Toast.makeText(MainActivity.this, "Completa prima il salvataggio in corso", Toast.LENGTH_LONG).show();
                    return;
                }
                pendingExport = content;
                String fileName = requestedName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                if (fileName.isEmpty()) fileName = "scorta-backup.json";
                String cleanMime = mimeType.split(";", 2)[0].trim();
                if (cleanMime.isEmpty()) cleanMime = "application/octet-stream";
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(cleanMime);
                intent.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(intent, REQUEST_EXPORT);
                } catch (Exception ex) {
                    pendingExport = null;
                    Toast.makeText(MainActivity.this, "Impossibile salvare il file", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT) {
            if (fileChooserCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
        } else if (requestCode == REQUEST_EXPORT) {
            String content = pendingExport;
            pendingExport = null;
            if (resultCode == RESULT_OK && content != null && data != null && data.getData() != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "w")) {
                    if (stream == null) throw new IOException("Cannot open output file");
                    stream.write(content.getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                    Toast.makeText(this, "File salvato", Toast.LENGTH_LONG).show();
                } catch (IOException ex) {
                    Toast.makeText(this, "Errore durante il salvataggio: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android's print framework does not dispatch the browser's afterprint event.
        // Clear the print-only CSS after returning from the system print dialog.
        if (printedExternally && webView != null) {
            printedExternally = false;
            webView.postDelayed(() -> webView.evaluateJavascript(
                    "document.body.classList.remove('print-ready')", null), 350);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Chiudere Scorta?")
                .setMessage("I dati rimangono salvati su questo dispositivo. Ricorda di esportare periodicamente un backup JSON.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Chiudi", (dialog, which) -> finish())
                .show();
        }
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidApp");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
