package com.farybooks.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.net.Uri;
import android.content.Intent;
import android.window.OnBackInvokedDispatcher;
import android.webkit.JavascriptInterface;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private final Executor aiExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new FaryAIBridge(), "FaryNative");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(Intent.createChooser(intent, "Seleccionar portada"), FILE_CHOOSER_REQUEST); }
                catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
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

    private class FaryAIBridge {
        @JavascriptInterface public void ask(String request) {
            if (request == null || request.trim().isEmpty()) return;
            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI()).generativeModel("gemini-3.8-flash");
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);
            Content prompt = new Content.Builder().addText("Eres FaryAI, asistente de escritura de FaryBooks. Responde en español, respeta la voz e intención de la autora y propón cambios sin afirmar que modificaste el manuscrito. Solicitud: " + request).build();
            ListenableFuture<GenerateContentResponse> response = model.generateContent(prompt);
            Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
                @Override public void onSuccess(GenerateContentResponse result) { sendAIResult(result.getText() == null ? "No recibí texto del modelo." : result.getText(), false); }
                @Override public void onFailure(Throwable t) { String detail = t.getMessage(); String lower = detail == null ? "" : detail.toLowerCase(); if (lower.contains("high demand") || lower.contains("unavailable") || lower.contains("resource exhausted") || lower.contains("429") || lower.contains("503")) sendAIResult("Estoy teniendo mucha demanda en este momento. Inténtalo de nuevo en unos instantes ✦", true); else sendAIResult("No pude responder en este momento. Revisa tu conexión e inténtalo nuevamente ✦", true); }
            }, aiExecutor);
        }
    }

    private void sendAIResult(String message, boolean error) {
        runOnUiThread(() -> webView.evaluateJavascript("window.receiveFaryAI(" + org.json.JSONObject.quote(message) + "," + error + ")", null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        handleAppBack();
    }
}
