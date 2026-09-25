package com.stbemu.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.util.EventLogger;
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
    private static final String LOG_TAG = "StbPortal";

    private SurfaceView videoView;
    private Stage16x9 stage;
    private WebView webView;
    private ExoPlayer player;
    private String facadeJs;
    private ScriptHandler facadeScript;
    private JavaScriptReplyProxy portalReply;
    private LinearLayout overlay;
    private LinearLayout remote;
    private ChannelPanel channelPanel;
    private Button channelButton;
    private String channelSessionId = ""; // from channels.data; the facade drops messages without it
    private boolean controlsHidden;
    /** Stalker: stb.player.on is true while the player is full screen (not the menu or a list preview). */
    private static final String PORTAL_FULLSCREEN_JS = "!!(window.stb && stb.player && stb.player.on)";

    // Virtual remote → Android key → DOM keyCode seen by the portal (MAG values: arrows 37-40, OK 13, Back 8, Menu 122).
    // ponytail: CH+/CH- sent as PAGE_UP/PAGE_DOWN (33/34); verify against the real portal and remap here if it expects other codes.
    private static final String[] REMOTE_LABELS = {"◀", "▲", "▼", "▶", "OK", "Back", "Menu", "CH+", "CH−"};
    private static final int[] REMOTE_KEYS = {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN};

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
        // Honour the facade's <meta viewport width=…> so the 1280x720 TV layout is scaled to the phone screen.
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.setVisibility(View.GONE);
        webView.setWebViewClient(new WebViewClient() { // keeps navigation inside the WebView instead of an external browser
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) emit("portal.error", "LOAD_FAILED:" + error.getErrorCode() + " " + error.getDescription() + " " + request.getUrl());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                Log.w(LOG_TAG, "HTTP " + response.getStatusCode() + " " + request.getMethod() + " " + request.getUrl());
            }
        });
        // Portal JS console (incl. uncaught errors) → logcat; release WebView builds do not log it on their own.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.i(LOG_TAG, message.messageLevel() + " " + message.message() + " @" + message.sourceId() + ":" + message.lineNumber());
                return true;
            }
        });
        // System Back while the portal has focus goes to the portal as its Back key instead of closing the Unity activity.
        webView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (channelPanel.getVisibility() == View.VISIBLE) { // Back closes the channel list first
                if (event.getAction() == KeyEvent.ACTION_UP) channelPanel.setVisibility(View.GONE);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) sendKeyOnUi(KeyEvent.KEYCODE_DEL);
            return true;
        });
        webView.setOnTouchListener(touchAsRemote());
        facadeJs = readAsset("stb_facade.js");

        overlay = row(Gravity.TOP | Gravity.END, false);
        overlay.addView(button("⟳", v -> emit("ui.refresh", "")));
        overlay.addView(button("⌂", v -> emit("ui.home", "")));
        overlay.addView(button("🔍", v -> promptText()));
        overlay.addView(button("🎮", v -> remote.setVisibility(remote.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        remote = row(Gravity.BOTTOM, true);
        for (int i = 0; i < REMOTE_KEYS.length; i++) {
            int key = REMOTE_KEYS[i];
            remote.addView(button(REMOTE_LABELS[i], v -> sendKeyOnUi(key)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        channelButton = button("📺", v -> channelPanel.setVisibility(channelPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        channelButton.setVisibility(View.GONE); // until the portal's channel data arrives
        overlay.addView(channelButton, 0);
        channelPanel = new ChannelPanel(activity, id -> {
            channelPanel.setVisibility(View.GONE);
            if (portalReply != null) portalReply.postMessage("{\"version\":1,\"sessionId\":" + JSONObject.quote(channelSessionId)
                    + ",\"type\":\"channels.play\",\"payload\":{\"id\":" + JSONObject.quote(id) + "}}");
        });
        channelPanel.setVisibility(View.GONE);
        channelPanel.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stage = new Stage16x9(activity); // video + portal share one centred 16:9 box; controls below stay full-screen
        stage.addView(videoView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(overlay);
        root.addView(remote);
        root.addView(channelPanel);
        overlay.bringToFront(); // 📺 stays tappable above the open list

        player = new ExoPlayer.Builder(activity).build();
        // Playback diagnostics in logcat (tag EventLogger): dropped frames, formats/bitrate, buffering, errors.
        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) player.addAnalyticsListener(new EventLogger());
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
                String data = message.getData();
                if (data.contains("\"type\":\"channels.")) { onChannelMessage(data); return; } // never forwarded to C# (size)
                emit("portal.message", data);
            });
            facadeScript = WebViewCompat.addDocumentStartJavaScript(webView, "window.__stbSnapshot=" + snapshotJson + ";\n" + facadeJs, rules);

            webView.loadUrl(url); // stays hidden until C# sees bridge.hello and calls setPortalVisible(true)
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
        channelPanel.setVisibility(View.GONE); // next portal sends its own data
        channelButton.setVisibility(View.GONE);
    }

    private void onChannelMessage(String json) {
        try {
            JSONObject msg = new JSONObject(json);
            if ("channels.error".equals(msg.optString("type"))) {
                Toast.makeText(activity, "Không mở được kênh", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!"channels.data".equals(msg.optString("type"))) return;
            channelSessionId = msg.optString("sessionId");
            channelPanel.setData(msg.getJSONObject("payload"));
            channelButton.setVisibility(View.VISIBLE);
        } catch (JSONException e) {
            Log.w(LOG_TAG, "bad channels message: " + e);
        }
    }

    /** Native ⟳ ⌂ 🎮 bar (and the remote it toggles) above the WebView; uGUI is below the WebView and cannot be tapped there. */
    public void setOverlayVisible(boolean visible) {
        ui.post(() -> {
            controlsHidden = false;
            overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (!visible) {
                remote.setVisibility(View.GONE);
                channelPanel.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Stalker portals only understand remote keys, not touch. Drag = arrow keys (one step per 48dp, so a long drag
     * scrolls a list), tap = OK, long press = Menu. All touches are consumed; the page never sees them.
     */
    private View.OnTouchListener touchAsRemote() {
        float step = dp(48);
        GestureDetector detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            private float dx, dy;

            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                dx = dy = 0;
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (controlsHidden) { // first tap brings the controls back
                    setControlsHidden(false);
                    return true;
                }
                // Full-screen video in the portal: tap hides the controls; anywhere else it is OK.
                webView.evaluateJavascript(PORTAL_FULLSCREEN_JS, result -> {
                    if ("true".equals(result)) setControlsHidden(true);
                    else sendKeyOnUi(KeyEvent.KEYCODE_ENTER);
                });
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                sendKeyOnUi(KeyEvent.KEYCODE_F11); // MAG Menu
            }

            @Override
            public boolean onScroll(MotionEvent start, @NonNull MotionEvent e, float distanceX, float distanceY) {
                dx -= distanceX; // distance* is last minus current; flip to finger direction
                dy -= distanceY;
                while (Math.abs(dx) >= step || Math.abs(dy) >= step) {
                    if (Math.abs(dx) >= Math.abs(dy)) {
                        sendKeyOnUi(dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                        dx -= Math.signum(dx) * step;
                    } else {
                        sendKeyOnUi(dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
                        dy -= Math.signum(dy) * step;
                    }
                }
                return true;
            }
        });
        return (v, event) -> {
            detector.onTouchEvent(event);
            return true;
        };
    }

    /**
     * Phone keyboard for the portal's own text fields (search, login): the portal only reads key events, so the
     * entered text is typed into the focused field as real key presses.
     */
    private void promptText() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("Nhập để tìm");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(input)
                .setPositiveButton("OK", (d, which) -> typeText(input.getText().toString()))
                .setNegativeButton("Huỷ", null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    private void typeText(String text) {
        webView.requestFocus();
        // ponytail: only characters on a virtual keyboard (Latin letters, digits, punctuation); accented text is dropped.
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) {
            Log.w(LOG_TAG, "text has characters without a key mapping: " + text);
            return;
        }
        for (KeyEvent event : events) webView.dispatchKeyEvent(event);
    }

    private void setControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        overlay.setVisibility(hidden ? View.GONE : View.VISIBLE);
        if (hidden) remote.setVisibility(View.GONE);
    }

    private void sendKeyOnUi(int keyCode) {
        webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private LinearLayout row(int gravity, boolean fullWidth) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackgroundColor(0x99000000);
        layout.setVisibility(View.GONE);
        layout.setLayoutParams(new FrameLayout.LayoutParams(
                fullWidth ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity));
        return layout;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setMinWidth(dp(48));
        button.setFocusable(false); // keep focus (and Back) on the WebView
        button.setOnClickListener(onClick);
        return button;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

    public void setPortalVisible(boolean visible) {
        ui.post(() -> {
            webView.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) webView.requestFocus(); // so the Back key reaches the portal
        });
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
            String userAgent = headers.remove("User-Agent"); // the factory's own UA would override a plain header
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
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
            root.removeView(stage);
            root.removeView(overlay);
            root.removeView(remote);
            root.removeView(channelPanel);
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
