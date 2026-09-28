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
        Equal<string>(null, Block(first, AdFormat.AppOpen, 62), "Formats have independent cooldowns");
        first.RecordShown(AdFormat.AppOpen, 70);
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
        Console.WriteLine("PASS: " + checks + " policy and callback checks");
    }
}
