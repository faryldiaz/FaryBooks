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

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleAppBack
            );
        }
    }

    private void handleAppBack() {
        webView.evaluateJavascript(
            "(function(){if(typeof handleAndroidBack==='function'){return handleAndroidBack()?'handled':'exit'}return 'exit'})()",
            value -> {
                if ("\"exit\"".equals(value)) finish();
            }
        );
    }

    @Override
    public void onBackPressed() {
        if (android.os.Build.VERSION.SDK_INT < 33) handleAppBack();
    }
}
