package de.stormarnamball.statistik;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Offline WebView shell for the unchanged v0.71 HTML application.
 * The asset is served from a secure HTTPS-style local origin for IndexedDB.
 * Imports/exports go through Android's Storage Access Framework.
 */
public class MainActivity extends Activity {
    private static final int PICK_JSON = 701;
    private static final int SAVE_JSON = 702;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private SaveTransaction collecting;
    private SaveTransaction prepared;

    private static class SaveTransaction {
        String id;
        String filename;
        String mime;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(17, 37, 40));
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(0);

        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // Prevent navigation to untrusted origins that would gain access to the JS bridge.
                return !("https".equals(uri.getScheme()) &&
                    "appassets.androidplatform.net".equals(uri.getHost()));
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (HOME.equals(url)) installDownloadHook();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, PICK_JSON);
                    return true;
                } catch (Exception ex) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new ExportBridge(), "AndroidExport");
        webView.loadUrl(HOME);
    }

    /**
     * HTML v0.71 exports blobs using a.download and a.click(). Android WebView
     * doesn't support blob downloads itself, so forward them to ACTION_CREATE_DOCUMENT.
     * Backup contents are transmitted in modest-sized base64 chunks.
     */
    private void installDownloadHook() {
        String js = "(function(){" +
            "if(window.__nativeSaveHook)return;window.__nativeSaveHook=true;" +
            "var original=HTMLAnchorElement.prototype.click;" +
            "HTMLAnchorElement.prototype.click=function(){" +
                "var a=this,href=a.href||'';" +
                "if(a.hasAttribute('download')&&href.indexOf('blob:')===0){" +
                    "var name=a.download||'backup.json';" +
                    "fetch(href).then(function(r){return r.blob()}).then(function(blob){" +
                        "var reader=new FileReader();reader.onload=function(){" +
                            "var data=String(reader.result||'').split(',')[1]||'';" +
                            "var id='save'+Date.now()+'_'+Math.random().toString(36).slice(2);" +
                            "AndroidExport.start(id,name,blob.type||'application/json');" +
                            "for(var i=0;i<data.length;i+=131072)AndroidExport.part(id,data.slice(i,i+131072));" +
                            "AndroidExport.finish(id);" +
                        "};reader.readAsDataURL(blob);" +
                    "}).catch(function(e){alert('Export fehlgeschlagen: '+e.message)});" +
                    "return;" +
                "}" +
                "return original.apply(this,arguments);" +
            "};" +
        "})();";
        webView.evaluateJavascript(js, null);
    }

    private class ExportBridge {
        @JavascriptInterface public synchronized void start(String id, String name, String mime) {
            collecting = new SaveTransaction();
            collecting.id = id;
            collecting.filename = name == null ? "backup.json" : name.replaceAll("[/\\\\]", "_");
            collecting.mime = mime != null && mime.toLowerCase(Locale.ROOT).contains("json")
                ? "application/json" : "application/octet-stream";
        }
        @JavascriptInterface public synchronized void part(String id, String base64Part) {
            if (collecting == null || !collecting.id.equals(id)) return;
            try {
                byte[] b = Base64.decode(base64Part, Base64.DEFAULT);
                collecting.bytes.write(b, 0, b.length);
            } catch (IllegalArgumentException ignored) {
                collecting = null;
            }
        }
        @JavascriptInterface public synchronized void finish(String id) {
            if (collecting == null || !collecting.id.equals(id)) return;
            SaveTransaction tx = collecting;
            collecting = null;
            runOnUiThread(() -> {
                if (prepared != null) {
                    Toast.makeText(MainActivity.this,
                        "Bitte zuerst den vorherigen Export abschließen", Toast.LENGTH_LONG).show();
                    return;
                }
                prepared = tx;
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(tx.mime);
                    intent.putExtra(Intent.EXTRA_TITLE, tx.filename);
                    startActivityForResult(intent, SAVE_JSON);
                } catch (Exception ex) {
                    prepared = null;
                    Toast.makeText(MainActivity.this, "Speichern nicht verfügbar", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_JSON) {
            if (fileCallback != null) {
                Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
                fileCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
                fileCallback = null;
            }
        } else if (requestCode == SAVE_JSON) {
            SaveTransaction tx = prepared;
            prepared = null;
            if (tx != null && resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                new Thread(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                        if (out == null) throw new IllegalStateException("Speicherziel nicht verfügbar");
                        out.write(tx.bytes.toByteArray());
                        out.flush();
                        runOnUiThread(() -> Toast.makeText(this,
                            "JSON-Backup gespeichert", Toast.LENGTH_LONG).show());
                    } catch (Exception ex) {
                        runOnUiThread(() -> Toast.makeText(this,
                            "Backup fehlgeschlagen: " + ex.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
