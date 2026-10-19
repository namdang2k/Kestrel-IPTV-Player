using System;
using System.Collections.Generic;
using Firebase.Analytics;
using UnityEngine;

namespace StbEmu.Services
{
    /// <summary>
    /// Runtime side of <see cref="MonetizationPolicy"/>: bundled snapshot → raw remote config → effective timings for the
    /// user's segment, plus the persisted behavior that segments the user. Every ad timing read goes through <see cref="Get"/>.
    /// </summary>
    public sealed class MonetizationController
    {
        const string StateKey = "StbEmu.Monetization.Behavior";
        static MonetizationController instance;
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetInstance() => instance = null;

        /// <summary>First touch snapshots the asset, so it must happen before remote config overwrites it (RemoteConfigController.Awake).</summary>
        public static MonetizationController Instance => instance ?? (instance = new MonetizationController());

        readonly double[] bundled;
        double[] raw, effective;
        string abVersion = "Default";
        bool hold, rawChanged;
        MonetizationSegment segment;
        readonly UserBehaviorState behavior;
        float unsavedWatch;

        MonetizationController()
        {
            var settings = Resources.Load<AdMobSettings>(AdMobSettings.ResourcePath);
            bundled = settings != null ? AdMobRemoteConfig.From(settings).Timings() : new AdMobRemoteConfig().Timings();
            raw = bundled;
            behavior = Load();
            Publish();
        }

        static long Now => DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        static bool IsPro => InAppPurchaseController.Instance != null && InAppPurchaseController.Instance.IsPro;

        public double Get(int field) => effective[field];
        public MonetizationSegment Segment => segment;

        public void SetRawConfig(AdMobRemoteConfig config, string ab)
        {
            raw = config.Timings();
            abVersion = ab;
            rawChanged = true;
            RequestPublish();
        }

        /// <summary>While streaming, new timings wait for Home so a lower threshold never fires an ad or prompt mid-stream.</summary>
        public void SetHold(bool value)
        {
            if (hold == value) return;
            hold = value;
            if (!hold) Publish(); // also re-segments: days rolling off, ownership changes
        }

        void RequestPublish()
        {
            if (!hold) Publish(); // otherwise leaving the stream publishes
        }

        void Publish()
        {
            var old = segment;
            var next = IsPro ? MonetizationSegment.Pro : behavior.IsEngaged(Now) ? MonetizationSegment.Engaged : MonetizationSegment.Casual;
            if (effective != null && next != old) Log("segment_changed", new Parameter("from", Name(old)), new Parameter("to", Name(next)));
            segment = next;
            var reasons = new List<string>();
            var previous = effective;
            effective = MonetizationPolicy.Effective(raw, bundled, segment == MonetizationSegment.Engaged, behavior.totalWatch, reasons);
            string summary = string.Join(",", effective);
            if (previous != null && summary == string.Join(",", previous) && next == old && !rawChanged) return;
            rawChanged = false;
            Debug.Log($"[Monetization] policy v{MonetizationPolicy.Version} ab={abVersion} segment={Name(segment)} effective={summary}" +
                (reasons.Count > 0 ? " reasons=" + string.Join(" ", reasons) : ""));
            Log("monetization_policy_applied", new Parameter("policy_version", MonetizationPolicy.Version),
                new Parameter("ab_version", Clip(abVersion)), new Parameter("segment", Name(segment)),
                new Parameter("effective", Clip(summary)), new Parameter("reasons", Clip(string.Join(" ", reasons))));
        }

        /// <summary>Actual playback seconds (foreground, playing, no ad or prompt); independent of ad readiness.</summary>
        public void AddWatch(double seconds)
        {
            behavior.AddWatch(seconds, Now);
            if ((unsavedWatch += (float)seconds) >= 30) Save();
        }

        /// <summary>At Home: an engaged, non-Pro user past the caps. Records the nudge when it says yes.</summary>
        public bool TryNudge()
        {
            if (IsPro || !behavior.CanNudge(Now, Get(MonetizationPolicy.Upsell))) return false;
            behavior.RecordNudge(Now);
            Save();
            Log("upsell_shown");
            return true;
        }

        public void RecordPaywallView(string source) =>
            Log("paywall_view", new Parameter("source", source));

        public void RecordNudgeDismissed()
        {
            behavior.RecordNudgeDismissed(Now);
            Save();
            Log("upsell_dismissed");
        }

        public void RecordCheckoutStarted(string plan) =>
            Log("checkout_started", new Parameter("plan", Clip(plan)));

        public void RecordCheckoutResult(string plan, PurchaseResult result)
        {
            if (result == PurchaseResult.Cancelled) behavior.RecordCheckoutCancelled(Now);
            Save();
            Log("checkout_result", new Parameter("plan", Clip(plan)), new Parameter("result", result.ToString().ToLowerInvariant()));
            if (result == PurchaseResult.Purchased) RequestPublish();
        }

        public void Save()
        {
            unsavedWatch = 0;
            PlayerPrefs.SetString(StateKey, JsonUtility.ToJson(behavior));
            PlayerPrefs.Save();
        }

        static UserBehaviorState Load()
        {
            UserBehaviorState state = null;
            try { state = JsonUtility.FromJson<UserBehaviorState>(PlayerPrefs.GetString(StateKey, "")); }
            catch (ArgumentException) { } // corrupt: start over
            if (state == null || state.version != 1) state = new UserBehaviorState();
            state.Normalize(Now);
            return state;
        }

        static string Name(MonetizationSegment value) => value.ToString().ToLowerInvariant();
        static string Clip(string text) => string.IsNullOrEmpty(text) ? "" : text.Substring(0, Math.Min(100, text.Length));

        static void Log(string name, params Parameter[] parameters)
        {
            try { FirebaseController.Instance?.LogEvent(name, parameters); }
            catch (Exception error) { Debug.LogWarning("[Monetization] " + error.Message); }
        }
    }
}
