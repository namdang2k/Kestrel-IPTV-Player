package com.stbemu.host;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Channel logos from the portal: 3 background downloads, RAM cache, a view shows only the URL it last asked for. */
final class LogoLoader {
    private final Handler ui;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    LogoLoader(Handler ui) {
        this.ui = ui;
    }

    void load(String url, ImageView view) {
        view.setTag(url);
        Bitmap cached = url == null ? null : cache.get(url);
        view.setImageBitmap(cached);
        if (url == null || cached != null || failed.contains(url)) return;
        pool.execute(() -> {
            if (!url.equals(view.getTag())) return; // scrolled past before its turn
            Bitmap bitmap = fetch(url);
            ui.post(() -> {
                if (bitmap == null) failed.add(url);
                else cache.put(url, bitmap);
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    void release() {
        pool.shutdownNow();
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2; // 320 px portal logos → ~160 px, enough for a list row
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
