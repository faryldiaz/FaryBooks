package com.farybooks.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");

        // Back is handled through Activity.onBackPressed for consistent WebView behavior.
    }

    private void handleAppBack() {
        webView.evaluateJavascript(
            "typeof handleAndroidBack==='function' ? handleAndroidBack() : false",
            value -> {
                // evaluateJavascript returns the JS boolean as the literal string true/false.
                // Only close Android when the web app explicitly reports that it has
                // nothing left to navigate back to.
                if ("false".equals(value) || "null".equals(value)) finish();
            }
        );
    }

    @Override
    public void onBackPressed() {
        handleAppBack();
    }
}
