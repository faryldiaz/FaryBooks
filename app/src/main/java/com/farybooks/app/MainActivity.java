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
import android.provider.MediaStore;
import android.content.ContentValues;
import android.os.Environment;
import android.graphics.pdf.PdfDocument;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.widget.Toast;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int BACKUP_IMPORT_REQUEST = 1002;
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
        boolean demoMode = getPackageName().endsWith(".demo");
        if (demoMode) {
            webView.evaluateJavascript("window.FARYBOOKS_DEMO=true", null);
        }
        webView.loadUrl(demoMode ? "file:///android_asset/index.html?demo=1" : "file:///android_asset/index.html");

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
            askModel(request, "gemini-3.8-flash", true);
        }

        private void askModel(String request, String modelName, boolean allowFallback) {
            try {
                GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI()).generativeModel(modelName);
                GenerativeModelFutures model = GenerativeModelFutures.from(ai);
                Content prompt = new Content.Builder().addText("Eres FaryAI, asistente de escritura de FaryBooks. Responde en español, respeta la voz e intención de la autora y propón cambios sin afirmar que modificaste el manuscrito. Solicitud: " + request).build();
                ListenableFuture<GenerateContentResponse> response = model.generateContent(prompt);
                Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
                    @Override public void onSuccess(GenerateContentResponse result) {
                        String value = result.getText();
                        sendAIResult(value == null || value.trim().isEmpty() ? "FaryAI recibió una respuesta vacía. Inténtalo nuevamente." : value, value == null || value.trim().isEmpty());
                    }
                    @Override public void onFailure(Throwable t) {
                        if (allowFallback) {
                            askModel(request, "gemini-3.5-flash", false);
                            return;
                        }
                        String detail = t == null ? "" : t.getMessage();
                        if (detail == null || detail.trim().isEmpty()) detail = t == null ? "Error desconocido" : t.getClass().getSimpleName();
                        detail = detail.replaceAll("(?i)(api[_ -]?key|key)\\s*[:=]\\s*[^ ,;]+", "$1=[oculto]");
                        if (detail.length() > 220) detail = detail.substring(0, 220) + "…";
                        sendAIResult("No pude completar la respuesta. Detalle: " + detail, true);
                    }
                }, aiExecutor);
            } catch (Throwable t) {
                if (allowFallback) askModel(request, "gemini-3.5-flash", false);
                else sendAIResult("No pude iniciar FaryAI. Inténtalo nuevamente.", true);
            }
        }
        @JavascriptInterface public void correct(String request) {
            if (request == null || request.trim().isEmpty()) return;
            correctWithModel(request, "gemini-3.8-flash", true);
        }

        private void correctWithModel(String request, String modelName, boolean allowFallback) {
            try {
                GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI()).generativeModel(modelName);
                GenerativeModelFutures model = GenerativeModelFutures.from(ai);
                Content prompt = new Content.Builder().addText(request).build();
                ListenableFuture<GenerateContentResponse> response = model.generateContent(prompt);
                Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
                    @Override public void onSuccess(GenerateContentResponse result) {
                        String value = result.getText();
                        sendCorrectionResult(value == null ? "{\"corrections\":[]}" : value, false);
                    }
                    @Override public void onFailure(Throwable t) {
                        if (allowFallback) correctWithModel(request, "gemini-3.5-flash", false);
                        else sendCorrectionResult("{}", true);
                    }
                }, aiExecutor);
            } catch (Throwable t) {
                if (allowFallback) correctWithModel(request, "gemini-3.5-flash", false);
                else sendCorrectionResult("{}", true);
            }
        }

        @JavascriptInterface public void exportDocument(String title, String text, String format) {
            runOnUiThread(() -> {
                try { if ("pdf".equalsIgnoreCase(format)) savePdf(title, text); else saveDocx(title, text); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "No se pudo exportar el documento.", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void exportBackup(String json) {
            runOnUiThread(() -> {
                try {
                    String name = "FaryBooks-backup-" + System.currentTimeMillis() + ".json";
                    OutputStream out = createDownload(name, "application/json");
                    out.write(json.getBytes(StandardCharsets.UTF_8)); out.close();
                    Toast.makeText(MainActivity.this, "Copia de seguridad guardada ✓", Toast.LENGTH_SHORT).show();
                } catch (Exception e) { Toast.makeText(MainActivity.this, "No se pudo guardar la copia.", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void importBackup() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
                startActivityForResult(i, BACKUP_IMPORT_REQUEST);
            });
        }
    }

    private OutputStream createDownload(String name, String mime) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FaryBooks");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("No download URI");
        OutputStream out = getContentResolver().openOutputStream(uri);
        if (out == null) throw new Exception("No output stream");
        return out;
    }

    private String safeName(String s) {
        String n = s == null ? "FaryBooks" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "FaryBooks" : n;
    }

    private String xml(String s) {
        return (s == null ? "" : s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private void zipText(ZipOutputStream z, String path, String value) throws Exception {
        z.putNextEntry(new ZipEntry(path)); z.write(value.getBytes(StandardCharsets.UTF_8)); z.closeEntry();
    }

    private void saveDocx(String title, String text) throws Exception {
        OutputStream raw = createDownload(safeName(title) + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        ZipOutputStream z = new ZipOutputStream(raw);
        zipText(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
        zipText(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
        StringBuilder body = new StringBuilder();
        for (String line : (text == null ? "" : text).split("\\n", -1)) body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>");
        zipText(z, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>");
        z.close();
        Toast.makeText(this, "Word guardado en Descargas/FaryBooks ✓", Toast.LENGTH_SHORT).show();
    }

    private List<String> wrap(String text, Paint paint, float width) {
        List<String> lines = new ArrayList<>();
        for (String para : (text == null ? "" : text).split("\\n", -1)) {
            if (para.isEmpty()) { lines.add(""); continue; }
            String[] words = para.split("\\s+"); StringBuilder line = new StringBuilder();
            for (String w : words) {
                String test = line.length()==0 ? w : line+" "+w;
                if (paint.measureText(test) > width && line.length()>0) { lines.add(line.toString()); line = new StringBuilder(w); }
                else { if(line.length()>0) line.append(" "); line.append(w); }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private void savePdf(String title, String text) throws Exception {
        PdfDocument doc = new PdfDocument(); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setTextSize(12f);
        List<String> lines = wrap(text, p, 515f); int pageNo=1, idx=0;
        while (idx < lines.size() || (lines.isEmpty() && pageNo==1)) {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(595,842,pageNo++).create());
            Canvas c = page.getCanvas(); float y=55f;
            while(idx<lines.size() && y<800f){ c.drawText(lines.get(idx++),40f,y,p); y+=18f; }
            doc.finishPage(page); if(lines.isEmpty()) break;
        }
        OutputStream out=createDownload(safeName(title)+".pdf","application/pdf"); doc.writeTo(out); out.close(); doc.close();
        Toast.makeText(this, "PDF guardado en Descargas/FaryBooks ✓", Toast.LENGTH_SHORT).show();
    }

    private void sendCorrectionResult(String message, boolean error) {
        runOnUiThread(() -> webView.evaluateJavascript("window.receiveCorrection(" + org.json.JSONObject.quote(message) + "," + error + ")", null));
    }

    private void sendAIResult(String message, boolean error) {
        runOnUiThread(() -> webView.evaluateJavascript("window.receiveFaryAI(" + org.json.JSONObject.quote(message) + "," + error + ")", null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BACKUP_IMPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream in=getContentResolver().openInputStream(data.getData()); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
                while((n=in.read(buf))!=-1) out.write(buf,0,n); in.close();
                String json=out.toString("UTF-8"); webView.evaluateJavascript("window.receiveBackup("+org.json.JSONObject.quote(json)+")",null);
            } catch(Exception e){ Toast.makeText(this,"No se pudo leer la copia.",Toast.LENGTH_LONG).show(); }
            return;
        }
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
