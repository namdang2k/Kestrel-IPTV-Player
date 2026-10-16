// Standalone tests for production policy/session classes; no native SDK or network.
using System;
using StbEmu.Services;
namespace GoogleMobileAds.Api { public sealed class AdValue { } }
namespace UnityEngine { public static class Debug { public static void LogException(Exception e) { } } }
namespace StbEmu.Services { public enum AdFormat { Banner, Interstitial, Rewarded, AppOpen } }

static class AdServicesPolicyTests
{
    static int checks;
    static void Equal<T>(T expected, T actual, string name)
    {
        checks++;
        if (!Equals(expected, actual)) throw new Exception(name + ": expected " + expected + ", got " + actual);
    }
    static string Block(AdDisplayTiming timing, AdFormat format, double now, double inter = 60, double open = 30, double delay = 60)
        => timing.GetBlockReason(format, now, inter, open, delay);
    static void Monetization()
    {
        double[] asset = { 60, 50, 600, 20, 400, 240, 180 };
        var reasons = new System.Collections.Generic.List<string>();
        var casual = MonetizationPolicy.Effective(asset, asset, false, reasons);
        Equal("60,50,600,20,400,240,180", string.Join(",", casual), "Casual keeps a sane base");
        Equal(0, reasons.Count, "No reasons for a sane base");
        Equal("90,75,600,30,600,360,135", string.Join(",", MonetizationPolicy.Effective(asset, asset, true)), "Engaged multipliers");
        double[] tier1 = { 600, 600, 1200, 120, 1200, 720, 900 };
        var engaged = MonetizationPolicy.Effective(tier1, asset, true);
        Equal(180.0, engaged[MonetizationPolicy.MinGap], "Tier 1 gap engaged");
        Equal(900.0, engaged[MonetizationPolicy.Interstitial], "Tier 1 interstitial engaged");
        Equal(675.0, engaged[MonetizationPolicy.Upsell], "Tier 1 upsell engaged");
        Equal(120.0, MonetizationPolicy.Effective(tier1, asset, false)[MonetizationPolicy.MinGap], "Back to casual recomputes from raw");
        reasons.Clear();
        double[] odd = { 1500, 10, 0, 0, 0, 0, 0 };
        var e = MonetizationPolicy.Effective(odd, asset, true, reasons);
        Equal(1800.0, e[MonetizationPolicy.Interstitial], "Max clamp");
        Equal(30.0, e[MonetizationPolicy.AppOpen], "Min clamp");
        Equal(300.0, e[MonetizationPolicy.FirstSessionDelay], "Zero delay raised to min");
        Equal(10.0, e[MonetizationPolicy.MinGap], "Zero gap raised to min");
        Equal(0.0, e[MonetizationPolicy.RewardedViewing], "Zero viewing keeps the prompt off");
        Equal(120.0, e[MonetizationPolicy.RewardedSkipped], "Skipped normalized while off");
        Equal(90.0, e[MonetizationPolicy.Upsell], "Zero upsell raised to min");
        Equal(true, reasons.Contains("interstitialIntervalSeconds:clamped"), "Clamp reason logged");
        Equal(true, reasons.Contains("minSecondsBetweenAds:zero_raised_to_min"), "Zero reason logged");
        double[] bad = { double.NaN, -1, 600, 20, double.PositiveInfinity, 240, 180 };
        e = MonetizationPolicy.Effective(bad, new double[] { 60, double.NaN, 600, 20, -5, 240, 180 }, false);
        Equal(60.0, e[MonetizationPolicy.Interstitial], "Invalid raw falls back to bundled");
        Equal(30.0, e[MonetizationPolicy.AppOpen], "Invalid bundled falls back to min");
        Equal(240.0, e[MonetizationPolicy.RewardedViewing], "Broken viewing is not read as off");
        Equal(300.0, MonetizationPolicy.Effective(new double[] { 60, 50, 600, 20, 300, 900, 180 }, asset, false)[MonetizationPolicy.RewardedSkipped],
            "Skipped capped by viewing");
        Equal("60,50,600,20,400,240,180", string.Join(",", asset), "Inputs are not mutated");

        var errors = new System.Collections.Generic.List<string>();
        Equal("600,600,1200,120,1200,720,900", string.Join(",", MonetizationPolicy.Tier(asset, 1, errors)), "Tier 1 preset");
        Equal("240,300,900,60,800,480,540", string.Join(",", MonetizationPolicy.Tier(asset, 2, errors)), "Tier 2 preset");
        Equal("60,50,600,20,400,240,180", string.Join(",", MonetizationPolicy.Tier(asset, 3, errors)), "Tier 3 is the base");
        Equal(null, MonetizationPolicy.Tier(new double[] { 60, -1, 600, 20, 400, 500, 0.5 }, 1, errors), "Invalid base rejected");
        Equal(3, errors.Count, "Negative, upsell<1 and skipped>viewing reported");

        const long day = 86400, t0 = 20000 * day;
        var s = new UserBehaviorState();
        s.Normalize(t0);
        s.AddWatch(1700, t0);
        Equal(false, s.IsEngaged(t0), "One day is not engaged");
        s.AddWatch(200, t0 + day);
        Equal(true, s.IsEngaged(t0 + day), "30 min over 2 days is engaged");
        Equal(false, s.IsEngaged(t0 + 7 * day), "Rolls off after 7 days");
        Equal(false, s.InIntentWindow(t0), "No intent yet");
        s.RecordManualPaywall(t0);
        Equal(false, s.InIntentWindow(t0), "One manual open is not intent");
        s.RecordManualPaywall(t0 + 3 * day);
        Equal(true, s.InIntentWindow(t0 + 3 * day + 100), "Second open within 7 days");
        Equal(false, s.InIntentWindow(t0 + 4 * day), "Intent expires after 24 h");
        s.RecordCheckoutStarted(t0 + 5 * day);
        Equal(true, s.InIntentWindow(t0 + 5 * day + 3600), "Checkout starts intent");
        s.RecordCheckoutStarted(t0 + 400 * day); // clock jumped forward, then back
        s.Normalize(t0 + 6 * day);
        Equal(false, s.InIntentWindow(t0 + 7 * day), "A future stamp cannot extend intent forever");

        var n = new UserBehaviorState();
        n.Normalize(t0);
        n.AddWatch(1000, t0); n.AddWatch(1000, t0 + day);
        long now = t0 + day + 10;
        Equal(true, n.CanNudge(now, 135), "Engaged with enough watch can be nudged");
        n.RecordNudge(now);
        n.AddWatch(1000, now);
        Equal(false, n.CanNudge(now + 60, 135), "One nudge per UTC day");
        Equal(true, n.CanNudge(t0 + 2 * day, 135), "Next day again");
        n.RecordNudgeDismissed(t0 + 2 * day);
        Equal(false, n.CanNudge(t0 + 3 * day, 135), "Dismiss pauses 48 h");
        Equal(true, n.CanNudge(t0 + 4 * day + 1, 0), "After 48 h");
        n.RecordCheckoutCancelled(t0 + 4 * day + 1);
        Equal(false, n.CanNudge(t0 + 6 * day, 0), "Cancel pauses 72 h");
        Equal(false, new UserBehaviorState().CanNudge(t0, 0), "Casual is never nudged");
    }

    static void Main()
    {
        var first = new AdDisplayTiming(true);
        Equal("first_session_delay", Block(first, AdFormat.Interstitial, 59.99), "Before first-session delay");
        Equal<string>(null, Block(first, AdFormat.Interstitial, 60), "At first-session delay");
        Equal<string>(null, Block(first, AdFormat.AppOpen, 0), "First-session delay does not affect app open");
        Equal<string>(null, Block(first, AdFormat.Rewarded, 0), "First-session delay does not affect reward");
        Equal<string>(null, Block(new AdDisplayTiming(false), AdFormat.Interstitial, 0), "Later session has no first delay");
        Equal<string>(null, Block(first, AdFormat.Interstitial, 61), "Rejected/not-ready requests consume no cooldown");
        first.RecordShown(AdFormat.Interstitial, 61);
        Equal("interstitial_cooldown", Block(first, AdFormat.Interstitial, 120.99), "Before inter boundary");
        Equal<string>(null, Block(first, AdFormat.Interstitial, 121), "At inter boundary");
        Equal("app_open_cooldown", Block(first, AdFormat.AppOpen, 62), "Interstitial blocks app open");
        first.RecordShown(AdFormat.AppOpen, 70);
        Equal("interstitial_cooldown", Block(first, AdFormat.Interstitial, 129.99), "App open blocks interstitial");
        var rewardTiming = new AdDisplayTiming(true);
        rewardTiming.RecordShown(AdFormat.Rewarded, 10);
        Equal("first_session_delay", Block(rewardTiming, AdFormat.Interstitial, 59), "Reward doesn't skip first-session delay");
        Equal("interstitial_cooldown", Block(rewardTiming, AdFormat.Interstitial, 69.99), "Reward blocks interstitial");
        Equal<string>(null, Block(rewardTiming, AdFormat.Interstitial, 70), "Reward cooldown boundary");
        rewardTiming.RecordShown(AdFormat.Banner, 70);
        Equal<string>(null, Block(rewardTiming, AdFormat.Interstitial, 70), "Banner consumes no cooldown");
        Equal("app_open_cooldown", Block(first, AdFormat.AppOpen, 99.99), "Before open boundary");
        Equal<string>(null, Block(first, AdFormat.AppOpen, 100), "At open boundary");
        Equal<string>(null, Block(first, AdFormat.AppOpen, 70, open: 0), "Zero disables interval");
        Equal<string>(null, Block(first, AdFormat.AppOpen, 70, open: -5), "Negative interval clamps to zero");
        Equal<string>(null, Block(new AdDisplayTiming(true), AdFormat.Interstitial, 0, delay: 0), "Zero disables first delay");

        var firstEntry = new AppOpenEntryPolicy(true, 0);
        Equal(false, firstEntry.CanShow(0), "No app open at session 1 startup");
        firstEntry.Resume(1, false);
        Equal(false, firstEntry.CanShow(1), "Initial unpause isn't a resume opportunity");
        firstEntry.Pause(false); firstEntry.Resume(20, false);
        Equal(true, firstEntry.CanShow(20), "Real resume can show, including first session");
        firstEntry.Consume();
        Equal(false, firstEntry.CanShow(21), "Resume opportunity consumed once");
        var secondEntry = new AppOpenEntryPolicy(false, 0);
        Equal(true, secondEntry.CanShow(0), "Session 2 startup eligible");
        Equal(false, secondEntry.CanShow(11), "Slow load can't interrupt later in session");
        secondEntry.Pause(false);
        Equal(false, secondEntry.CanShow(12), "No show in background");
        secondEntry.Resume(15, false);
        Equal(true, secondEntry.CanShow(15), "New pause/resume opens new window");
        secondEntry.Pause(true); secondEntry.Resume(16, false);
        Equal(false, secondEntry.CanShow(16), "No app open returning from native ad or consent");
        secondEntry.Pause(false); secondEntry.Resume(17, true);
        Equal(false, secondEntry.CanShow(17), "No app open while fullscreen still active");
        secondEntry.Pause(false); secondEntry.Resume(80, false);
        Equal(true, secondEntry.CanShow(80), "Subsequent real resume restores eligibility");
        var resumeTiming = new AdDisplayTiming(false);
        resumeTiming.RecordShown(AdFormat.AppOpen, 70);
        Equal("app_open_cooldown", Block(resumeTiming, AdFormat.AppOpen, 80), "Eligible resume still respects cooldown");

        int shown = 0, closed = 0, failed = 0, impressions = 0;
        var callbacks = new AdCallbacks {
            OnShown = () => shown++, OnClosed = () => closed++,
            OnFailed = _ => failed++, OnImpression = () => impressions++
        };
        var session = new AdShowSession(callbacks);
        session.Shown(); session.Shown(); session.Impression(); session.Close(); session.Close(); session.Fail("late"); session.Impression();
        Equal(1, shown, "Shown once"); Equal(1, closed, "Closed once");
        Equal(0, failed, "No failure after close"); Equal(1, impressions, "Ignore late impression");
        var rejection = new AdShowSession(callbacks);
        rejection.Fail("not_ready"); rejection.Fail("again"); rejection.Close(); rejection.Shown();
        Equal(1, failed, "Failure once"); Equal(1, closed, "No close after failure"); Equal(1, shown, "No shown after failure");
        int recovered = 0;
        var throwing = new AdShowSession(new AdCallbacks { OnShown = () => throw new Exception("caller"), OnClosed = () => recovered++ });
        throwing.Shown(); throwing.Close();
        Equal(1, recovered, "Caller exception does not block cleanup");
        new AdShowSession(null).Close();

        var viewing = new StreamingAdPolicy();
        Equal(false, viewing.ShouldShowChannelInterstitial("a"), "First pick of a launch has no interstitial");
        viewing.RecordPlaying("a");
        Equal(false, viewing.ShouldShowChannelInterstitial("a"), "Same channel has no interstitial");
        Equal(true, viewing.ShouldShowChannelInterstitial("b"), "Channel change shows interstitial");
        viewing.Disconnect();
        Equal(false, viewing.ShouldShowChannelInterstitial("b"), "First pick after reconnect has no interstitial");
        viewing.RecordPlaying("b");
        Equal(true, viewing.ShouldShowChannelInterstitial("c"), "Later change after reconnect shows interstitial");

        Monetization();
        Console.WriteLine("PASS: " + checks + " policy and callback checks");
    }
}
