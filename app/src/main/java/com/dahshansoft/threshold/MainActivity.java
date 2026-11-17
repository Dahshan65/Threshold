package com.dahshansoft.threshold;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsOk = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#14233c"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b1628"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) return false;
                openExternal(url);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { sendEvent("start", id); }
                    @Override public void onDone(String id) { sendEvent("end", id); }
                    @Override public void onError(String id) { sendEvent("error", id); }
                });
                ttsOk = true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private void sendEvent(final String type, final String id) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window.__ttsEvt&&window.__ttsEvt('" + type + "','" + id + "')", null));
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back?window.__back():'exit'", value -> {
            if (value == null || value.contains("exit")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    /* ===== جسر بين صفحة HTML ومحرك الصوت في الأندرويد ===== */
    public class Bridge {

        @JavascriptInterface
        public boolean ttsReady() { return ttsOk; }

        @JavascriptInterface
        public String voices() {
            JSONArray arr = new JSONArray();
            try {
                if (ttsOk) {
                    Set<Voice> vs = tts.getVoices();
                    if (vs != null) for (Voice v : vs) {
                        Locale l = v.getLocale();
                        if (l == null || !"en".equals(l.getLanguage())) continue;
                        Set<String> f = v.getFeatures();
                        if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                        JSONObject o = new JSONObject();
                        o.put("name", v.getName());
                        o.put("lang", l.toLanguageTag());
                        o.put("local", !v.isNetworkConnectionRequired());
                        o.put("q", v.getQuality());
                        arr.put(o);
                    }
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public void speak(String id, String text, String voiceName, float rate, float pitch, boolean flush) {
            if (!ttsOk) { sendEvent("error", id); return; }
            try {
                boolean set = false;
                if (voiceName != null && voiceName.length() > 0 && tts.getVoices() != null) {
                    for (Voice v : tts.getVoices()) {
                        if (v.getName().equals(voiceName)) { tts.setVoice(v); set = true; break; }
                    }
                }
                if (!set) tts.setLanguage(Locale.US);
            } catch (Exception ignored) { }
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, new Bundle(), id);
        }

        @JavascriptInterface
        public void stop() { if (tts != null) tts.stop(); }

        @JavascriptInterface
        public boolean speaking() { return tts != null && tts.isSpeaking(); }

        @JavascriptInterface
        public void openUrl(String url) { openExternal(url); }
    }
}
