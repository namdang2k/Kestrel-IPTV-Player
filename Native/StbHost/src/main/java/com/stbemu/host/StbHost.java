package com.stbemu.host;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Native layer on top of the Unity view: video SurfaceView (ExoPlayer) under a transparent WebView (portal).
 * All public methods may be called from any thread; work is posted to the Android UI thread,
 * which is also ExoPlayer's application looper.
 */
public final class StbHost {

    /** Implemented in C# with AndroidJavaProxy. Called on the Android UI thread, not Unity's main thread. */
    public interface Listener {
        void onEvent(String type, String data);
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** JS object name the facade uses to talk to native (WebViewCompat.addWebMessageListener). */
    private static final String BRIDGE_NAME = "stbNative";

    private SurfaceView videoView;
    private WebView webView;
    private ExoPlayer player;
    private String facadeJs;
    private ScriptHandler facadeScript;
    private JavaScriptReplyProxy portalReply;

    public StbHost(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        ui.post(this::attach);
    }

    private void attach() {
        ViewGroup root = activity.findViewById(android.R.id.content);

        videoView = new SurfaceView(activity);
        videoView.setZOrderMediaOverlay(true); // above Unity's SurfaceView, below window views (WebView)
        videoView.setVisibility(View.GONE);

        // Development builds only: inspect the portal's requests/responses via chrome://inspect.
        WebView.setWebContentsDebuggingEnabled((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView = new WebView(activity);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setVisibility(View.GONE);
        webView.setWebViewClient(new WebViewClient() { // keeps navigation inside the WebView instead of an external browser
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) emit("portal.error", "LOAD_FAILED:" + error.getErrorCode() + " " + error.getDescription() + " " + request.getUrl());
            }
        });
        facadeJs = readAsset("stb_facade.js");

        root.addView(videoView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        player = new ExoPlayer.Builder(activity).build();
        player.setVideoSurfaceView(videoView);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                emit("state", String.valueOf(state)); // Player.STATE_IDLE=1, BUFFERING=2, READY=3, ENDED=4
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                emit("isPlaying", String.valueOf(isPlaying));
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                emit("error", error.getErrorCodeName());
            }
        });
        emit("ready", "");
    }

    /**
     * Loads the portal with the STB facade installed at document start, before any portal script runs.
     * Facade and message listener are limited to the portal's origin; messages from sub-frames are dropped.
     *
     * @param snapshotJson JSON object serialized by C# (identity + sessionId); becomes window.__stbSnapshot for the facade.
     * @param userAgent    empty = WebView default.
     */
    public void openPortal(String url, String snapshotJson, String userAgent) {
        ui.post(() -> {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                emit("portal.error", "WEBVIEW_UNSUPPORTED"); // do not fall back to late injection: portal may already have failed
                return;
            }
            closePortalOnUi();

            // ponytail: one portal session at a time, so wipe web storage on every open; per-profile isolation needs WebView profiles (MULTI_PROFILE).
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();

            Uri uri = Uri.parse(url);
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
            Set<String> rules = Collections.singleton(origin);

            webView.getSettings().setUserAgentString(userAgent == null || userAgent.isEmpty() ? null : userAgent);
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, rules, (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                if (!isMainFrame || message.getData() == null) return;
                portalReply = replyProxy;
                emit("portal.message", message.getData());
            });
            facadeScript = WebViewCompat.addDocumentStartJavaScript(webView, "window.__stbSnapshot=" + snapshotJson + ";\n" + facadeJs, rules);

            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(url);
        });
    }

    /** Sends a JSON string to the facade (stbNative.onmessage). Dropped until the page has sent its first message. */
    public void publishToPortal(String json) {
        ui.post(() -> {
            if (portalReply != null) portalReply.postMessage(json);
        });
    }

    public void closePortal() {
        ui.post(this::closePortalOnUi);
    }

    private void closePortalOnUi() {
        if (facadeScript != null) {
            facadeScript.remove();
            facadeScript = null;
            WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME);
        }
        portalReply = null;
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE);
    }

    public void setPortalVisible(boolean visible) {
        ui.post(() -> webView.setVisibility(visible ? View.VISIBLE : View.GONE));
    }

    /** @param headersJson flat JSON object of request headers, e.g. {"User-Agent":"...","Referer":"..."}; may be null. */
    public void play(String url, String headersJson) {
        Map<String, String> headers;
        try {
            headers = parseHeaders(headersJson);
        } catch (JSONException e) {
            emit("error", "BAD_HEADERS");
            return;
        }
        ui.post(() -> {
            // ponytail: headers go to every host the stream touches; add a host-filtering OkHttp interceptor when a portal needs cookies.
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url)));
            player.prepare();
            player.play();
            videoView.setVisibility(View.VISIBLE);
        });
    }

    public void setPaused(boolean paused) {
        ui.post(() -> player.setPlayWhenReady(!paused));
    }

    public void setVolume(float volume) {
        ui.post(() -> player.setVolume(volume));
    }

    public void stop() {
        ui.post(() -> {
            player.stop();
            player.clearMediaItems();
            videoView.setVisibility(View.GONE);
        });
    }

    public void release() {
        ui.post(() -> {
            closePortalOnUi();
            player.release();
            webView.destroy();
            ViewGroup root = activity.findViewById(android.R.id.content);
            root.removeView(videoView);
            root.removeView(webView);
        });
    }

    private void emit(String type, String data) {
        if (listener != null) listener.onEvent(type, data);
    }

    private String readAsset(String name) {
        try (InputStream in = activity.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + name, e);
        }
    }

    private static Map<String, String> parseHeaders(String json) throws JSONException {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;
        JSONObject obj = new JSONObject(json);
        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
            String key = it.next();
            map.put(key, obj.getString(key));
        }
        return map;
    }
}
