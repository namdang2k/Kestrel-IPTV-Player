package com.stbemu.host;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads and caches AdMob {@link NativeAd} instances for the native Home (profile list), TV Category list and TV EPG screens.
 * Configured from Unity via {@code setNativeAdConfig(json)} so session gating ({@code sessionShowNative}),
 * Remote Config ad unit fallback lists, consent, and Pro ownership stay controlled by C#.
 */
final class NativeAdController {
    private static final String LOG_TAG = "StbNativeAds";
    private static final int MAX_TV_ADS = 3;

    private final Context context;
    private final Handler ui;
    private final StbHost.Listener listener;
    private final Runnable onChanged;

    private boolean enabled;
    private List<String> adUnitIds = Collections.emptyList();
    private int configGeneration;

    private boolean homeNeeded;
    private NativeAd homeAd;
    private boolean loadingHome;
    private int homeUnitIndex;
    private int homeRetryCount;

    private boolean epgNeeded;
    private NativeAd epgAd;
    private boolean loadingEpg;
    private int epgUnitIndex;
    private int epgRetryCount;

    private int tvNeededCount;
    private final List<NativeAd> tvAds = new ArrayList<>();
    private boolean loadingTv;
    private int tvUnitIndex;
    private int tvRetryCount;

    NativeAdController(Context context, Handler ui, StbHost.Listener listener, Runnable onChanged) {
        this.context = context;
        this.ui = ui;
        this.listener = listener;
        this.onChanged = onChanged;
    }

    /** Parses {@code {"enabled":true,"adUnitIds":["..."]}} on the Android UI thread. */
    void setConfig(String json) {
        boolean nextEnabled = false;
        List<String> nextIds = new ArrayList<>();
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject data = new JSONObject(json);
                nextEnabled = data.optBoolean("enabled", false);
                JSONArray arr = data.optJSONArray("adUnitIds");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String id = arr.optString(i, "").trim();
                        if (!id.isEmpty()) nextIds.add(id);
                    }
                }
            } catch (JSONException e) {
                Log.w(LOG_TAG, "Bad native ad config: " + e.getMessage());
            }
        }
        if (!nextEnabled || nextIds.isEmpty()) {
            boolean hadAds = enabled || homeAd != null || epgAd != null || !tvAds.isEmpty();
            enabled = false;
            adUnitIds = Collections.emptyList();
            configGeneration++;
            loadingHome = false;
            loadingTv = false;
            loadingEpg = false;
            destroyAllAds();
            if (hadAds) onChanged.run();
            return;
        }
        boolean idsChanged = !nextIds.equals(adUnitIds);
        enabled = true;
        if (idsChanged) {
            adUnitIds = nextIds;
            configGeneration++;
            loadingHome = false;
            loadingTv = false;
            homeUnitIndex = 0;
            tvUnitIndex = 0;
            epgUnitIndex = 0;
            homeRetryCount = 0;
            tvRetryCount = 0;
            epgRetryCount = 0;
            loadingEpg = false;
            destroyAllAds();
            onChanged.run();
        }
        ensureLoaded();
    }

    void setHomeNeeded(boolean needed) {
        if (homeNeeded == needed) return;
        homeNeeded = needed;
        if (needed) loadHomeAd();
    }

    void setTvNeededCount(int count) {
        int clamped = Math.max(0, Math.min(MAX_TV_ADS, count));
        if (tvNeededCount == clamped) return;
        tvNeededCount = clamped;
        if (tvAds.size() < tvNeededCount) loadTvAd();
    }

    void setEpgNeeded(boolean needed) {
        if (epgNeeded == needed) return;
        epgNeeded = needed;
        if (needed) loadEpgAd();
    }

    NativeAd getHomeAd() {
        return enabled ? homeAd : null;
    }

    NativeAd getEpgAd() {
        return enabled ? epgAd : null;
    }

    boolean hasTvAd() {
        return enabled && !tvAds.isEmpty();
    }

    int getTvAdCount() {
        return enabled ? tvAds.size() : 0;
    }

    NativeAd getTvAd(int slotIndex) {
        if (!enabled || tvAds.isEmpty()) return null;
        return tvAds.get(Math.abs(slotIndex) % tvAds.size());
    }

    void ensureLoaded() {
        if (!enabled || adUnitIds.isEmpty()) return;
        if (homeNeeded) loadHomeAd();
        if (tvNeededCount > 0) loadTvAd();
        if (epgNeeded) loadEpgAd();
    }

    void release() {
        enabled = false;
        homeNeeded = false;
        tvNeededCount = 0;
        epgNeeded = false;
        adUnitIds = Collections.emptyList();
        configGeneration++;
        loadingHome = false;
        loadingTv = false;
        loadingEpg = false;
        destroyAllAds();
    }

    private void destroyAllAds() {
        if (epgAd != null) {
            epgAd.destroy();
            epgAd = null;
        }
        if (homeAd != null) {
            homeAd.destroy();
            homeAd = null;
        }
        for (NativeAd ad : tvAds) {
            ad.destroy();
        }
        tvAds.clear();
    }

    private void loadHomeAd() {
        if (!enabled || !homeNeeded || adUnitIds.isEmpty() || homeAd != null || loadingHome) return;
        loadingHome = true;
        final int gen = configGeneration;
        final String unitId = adUnitIds.get(homeUnitIndex);
        emit("load_request", unitId, "profiles", "");
        AdLoader loader = new AdLoader.Builder(context, unitId)
                .forNativeAd(ad -> ui.post(() -> {
                    if (gen != configGeneration || !enabled) {
                        ad.destroy();
                        return;
                    }
                    loadingHome = false;
                    homeRetryCount = 0;
                    if (homeAd != null) homeAd.destroy();
                    homeAd = ad;
                    emit("loaded", unitId, "profiles", "");
                    onChanged.run();
                }))
                .withAdListener(new AdListener() {
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        ui.post(() -> {
                            if (gen != configGeneration || !enabled) return;
                            loadingHome = false;
                            emit("load_failed", unitId, "profiles", error.getCode() + ":" + error.getMessage());
                            if (!homeNeeded) return;
                            if (homeUnitIndex + 1 < adUnitIds.size()) {
                                homeUnitIndex++;
                                loadHomeAd();
                            } else {
                                homeUnitIndex = 0;
                                int retry = ++homeRetryCount;
                                long delayMs = Math.min(60_000L, (1L << Math.min(retry, 6)) * 1000L);
                                ui.postDelayed(() -> {
                                    if (gen == configGeneration && enabled && homeNeeded) loadHomeAd();
                                }, delayMs);
                            }
                        });
                    }

                    @Override public void onAdImpression() {
                        ui.post(() -> emit("impression", unitId, "profiles", ""));
                    }

                    @Override public void onAdClicked() {
                        ui.post(() -> emit("clicked", unitId, "profiles", ""));
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build())
                .build();
        loader.loadAd(new AdRequest.Builder().build());
    }

    private void loadEpgAd() {
        if (!enabled || !epgNeeded || adUnitIds.isEmpty() || epgAd != null || loadingEpg) return;
        loadingEpg = true;
        final int gen = configGeneration;
        final String unitId = adUnitIds.get(epgUnitIndex);
        emit("load_request", unitId, "epg", "");
        AdLoader loader = new AdLoader.Builder(context, unitId)
                .forNativeAd(ad -> ui.post(() -> {
                    if (gen != configGeneration || !enabled) {
                        ad.destroy();
                        return;
                    }
                    loadingEpg = false;
                    epgRetryCount = 0;
                    if (epgAd != null) epgAd.destroy();
                    epgAd = ad;
                    emit("loaded", unitId, "epg", "");
                    onChanged.run();
                }))
                .withAdListener(new AdListener() {
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        ui.post(() -> {
                            if (gen != configGeneration || !enabled) return;
                            loadingEpg = false;
                            emit("load_failed", unitId, "epg", error.getCode() + ":" + error.getMessage());
                            if (!epgNeeded) return;
                            if (epgUnitIndex + 1 < adUnitIds.size()) {
                                epgUnitIndex++;
                                loadEpgAd();
                            } else {
                                epgUnitIndex = 0;
                                int retry = ++epgRetryCount;
                                long delayMs = Math.min(60_000L, (1L << Math.min(retry, 6)) * 1000L);
                                ui.postDelayed(() -> {
                                    if (gen == configGeneration && enabled && epgNeeded) loadEpgAd();
                                }, delayMs);
                            }
                        });
                    }

                    @Override public void onAdImpression() {
                        ui.post(() -> emit("impression", unitId, "epg", ""));
                    }

                    @Override public void onAdClicked() {
                        ui.post(() -> emit("clicked", unitId, "epg", ""));
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build())
                .build();
        loader.loadAd(new AdRequest.Builder().build());
    }

    private void loadTvAd() {
        if (!enabled || adUnitIds.isEmpty() || tvAds.size() >= tvNeededCount || tvAds.size() >= MAX_TV_ADS || loadingTv) return;
        loadingTv = true;
        final int gen = configGeneration;
        final String unitId = adUnitIds.get(tvUnitIndex);
        emit("load_request", unitId, "tv_categories", "");
        AdLoader loader = new AdLoader.Builder(context, unitId)
                .forNativeAd(ad -> ui.post(() -> {
                    if (gen != configGeneration || !enabled) {
                        ad.destroy();
                        return;
                    }
                    loadingTv = false;
                    tvRetryCount = 0;
                    tvAds.add(ad);
                    emit("loaded", unitId, "tv_categories", "");
                    onChanged.run();
                    if (tvAds.size() < tvNeededCount && tvAds.size() < MAX_TV_ADS) loadTvAd();
                }))
                .withAdListener(new AdListener() {
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        ui.post(() -> {
                            if (gen != configGeneration || !enabled) return;
                            loadingTv = false;
                            emit("load_failed", unitId, "tv_categories", error.getCode() + ":" + error.getMessage());
                            if (tvAds.size() >= tvNeededCount) return;
                            if (tvUnitIndex + 1 < adUnitIds.size()) {
                                tvUnitIndex++;
                                loadTvAd();
                            } else {
                                tvUnitIndex = 0;
                                int retry = ++tvRetryCount;
                                long delayMs = Math.min(60_000L, (1L << Math.min(retry, 6)) * 1000L);
                                ui.postDelayed(() -> {
                                    if (gen == configGeneration && enabled && tvAds.size() < tvNeededCount) loadTvAd();
                                }, delayMs);
                            }
                        });
                    }

                    @Override public void onAdImpression() {
                        ui.post(() -> emit("impression", unitId, "tv_categories", ""));
                    }

                    @Override public void onAdClicked() {
                        ui.post(() -> emit("clicked", unitId, "tv_categories", ""));
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build())
                .build();
        loader.loadAd(new AdRequest.Builder().build());
    }

    private void emit(String action, String unitId, String placement, String detail) {
        if (listener != null) {
            String safeDetail = detail == null ? "" : detail.replace('|', ' ');
            listener.onEvent("ads.native", action + "|" + unitId + "|" + placement + "|" + safeDetail);
        }
    }
}
