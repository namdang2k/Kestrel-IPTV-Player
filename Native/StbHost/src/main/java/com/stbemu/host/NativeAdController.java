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

    private boolean homeNeeded;
    private NativeAd homeAd;
    private final NativeAdLoadState homeState = new NativeAdLoadState();

    private boolean epgNeeded;
    private NativeAd epgAd;
    private final NativeAdLoadState epgState = new NativeAdLoadState();

    private int tvNeededCount;
    private final List<NativeAd> tvAds = new ArrayList<>();
    private final NativeAdLoadState tvState = new NativeAdLoadState();

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
            resetStates();
            destroyAllAds();
            if (hadAds) onChanged.run();
            return;
        }
        boolean idsChanged = !nextIds.equals(adUnitIds);
        enabled = true;
        if (idsChanged) {
            adUnitIds = nextIds;
            resetStates();
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
        resetStates();
        destroyAllAds();
    }

    private void resetStates() {
        homeState.reset();
        epgState.reset();
        tvState.reset();
    }

    /** Home became visible again: drop failure history and pending retries, then load now if there is no ad. */
    void kickHome() {
        homeState.kick();
        loadHomeAd();
    }

    void kickEpg() {
        epgState.kick();
        loadEpgAd();
    }

    void kickTv() {
        tvState.kick();
        loadTvAd();
    }

    private void scheduleRetry(NativeAdLoadState state, long delayMs, Runnable load) {
        final int epoch = state.retryEpoch();
        ui.postDelayed(() -> {
            if (enabled && epoch == state.retryEpoch()) load.run();
        }, delayMs);
    }

    private void armWatchdog(NativeAdLoadState state, int id, Runnable onTimeout) {
        ui.postDelayed(() -> {
            if (state.isCurrent(id)) onTimeout.run();
        }, NativeAdLoadState.LOAD_TIMEOUT_MS);
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
        if (!enabled || !homeNeeded || adUnitIds.isEmpty() || homeAd != null || homeState.isLoading()) return;
        final int id = homeState.begin();
        final String unitId = adUnitIds.get(homeState.unitIndex());
        emit("load_request", unitId, "profiles", "");
        armWatchdog(homeState, id, () -> onHomeFailed(id, unitId, "timeout"));
        AdLoader loader = new AdLoader.Builder(context, unitId)
                .forNativeAd(ad -> ui.post(() -> {
                    if (!enabled || !homeState.isCurrent(id)) {
                        ad.destroy();
                        return;
                    }
                    homeState.onSuccess();
                    if (homeAd != null) homeAd.destroy();
                    homeAd = ad;
                    emit("loaded", unitId, "profiles", "");
                    onChanged.run();
                }))
                .withAdListener(new AdListener() {
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        ui.post(() -> onHomeFailed(id, unitId, error.getCode() + ":" + error.getMessage()));
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

    private void onHomeFailed(int id, String unitId, String detail) {
        if (!enabled || !homeState.isCurrent(id)) return;
        emit("load_failed", unitId, "profiles", detail);
        if (!homeNeeded) {
            homeState.cancel();
            return;
        }
        long delayMs = homeState.onFailure(adUnitIds.size());
        if (delayMs == 0L) loadHomeAd();
        else scheduleRetry(homeState, delayMs, this::loadHomeAd);
    }

    private void loadEpgAd() {
        if (!enabled || !epgNeeded || adUnitIds.isEmpty() || epgAd != null || epgState.isLoading()) return;
        final int id = epgState.begin();
        final String unitId = adUnitIds.get(epgState.unitIndex());
        emit("load_request", unitId, "epg", "");
        armWatchdog(epgState, id, () -> onEpgFailed(id, unitId, "timeout"));
        AdLoader loader = new AdLoader.Builder(context, unitId)
                .forNativeAd(ad -> ui.post(() -> {
                    if (!enabled || !epgState.isCurrent(id)) {
                        ad.destroy();
                        return;
                    }
                    epgState.onSuccess();
                    if (epgAd != null) epgAd.destroy();
                    epgAd = ad;
                    emit("loaded", unitId, "epg", "");
                    onChanged.run();
                }))
                .withAdListener(new AdListener() {
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        ui.post(() -> onEpgFailed(id, unitId, error.getCode() + ":" + error.getMessage()));
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

    private void onEpgFailed(int id, String unitId, String detail) {
        if (!enabled || !epgState.isCurrent(id)) return;
        emit("load_failed", unitId, "epg", detail);
        if (!epgNeeded) {
            epgState.cancel();
            return;
        }
        long delayMs = epgState.onFailure(adUnitIds.size());
        if (delayMs == 0L) loadEpgAd();
        else scheduleRetry(epgState, delayMs, this::loadEpgAd);
    }

    private void loadTvAd() {
        if (!enabled || adUnitIds.isEmpty() || tvAds.size() >= tvNeededCount || tvAds.size() >= MAX_TV_ADS || tvState.isLoading()) return;
        final int id = tvState.begin();
        final String unitId = adUnitIds.get(tvState.unitIndex());
        emit("load_request", unitId, "tv_categories", "");
        armWatchdog(tvState, id, () -> onTvFailed(id, unitId, "timeout"));
        AdLoader loader = new AdLoader.Builder(context, unitId)
                .forNativeAd(ad -> ui.post(() -> {
                    if (!enabled || !tvState.isCurrent(id)) {
                        ad.destroy();
                        return;
                    }
                    tvState.onSuccess();
                    tvAds.add(ad);
                    emit("loaded", unitId, "tv_categories", "");
                    onChanged.run();
                    if (tvAds.size() < tvNeededCount && tvAds.size() < MAX_TV_ADS) loadTvAd();
                }))
                .withAdListener(new AdListener() {
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        ui.post(() -> onTvFailed(id, unitId, error.getCode() + ":" + error.getMessage()));
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

    private void onTvFailed(int id, String unitId, String detail) {
        if (!enabled || !tvState.isCurrent(id)) return;
        emit("load_failed", unitId, "tv_categories", detail);
        if (tvAds.size() >= tvNeededCount) {
            tvState.cancel();
            return;
        }
        long delayMs = tvState.onFailure(adUnitIds.size());
        if (delayMs == 0L) loadTvAd();
        else scheduleRetry(tvState, delayMs, this::loadTvAd);
    }

    private void emit(String action, String unitId, String placement, String detail) {
        if (listener != null) {
            String safeDetail = detail == null ? "" : detail.replace('|', ' ');
            listener.onEvent("ads.native", action + "|" + unitId + "|" + placement + "|" + safeDetail);
        }
    }
}
