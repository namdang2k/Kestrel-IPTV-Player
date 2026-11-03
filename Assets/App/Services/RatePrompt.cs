using System;
using UnityEngine;

namespace StbEmu.Services
{
    /// <summary>Persisted rate-prompt progress. Seconds are foreground time in the app, all launches added up.</summary>
    [Serializable]
    public sealed class RatePromptState
    {
        public double appSeconds;
        public double nextAt = RatePromptPolicy.FirstAtSeconds;
        /// <summary>Rate or Never was tapped: never prompt again.</summary>
        public bool stop;
    }

    public static class RatePromptPolicy
    {
        public const double FirstAtSeconds = 1200, RepeatSeconds = 1200, MinSessionSeconds = 300;

        public static bool IsDue(RatePromptState state, double sessionSeconds) =>
            !state.stop && state.appSeconds >= state.nextAt && sessionSeconds >= MinSessionSeconds;

        /// <summary>Next prompt a full <see cref="RepeatSeconds"/> of app time after this one.</summary>
        public static void NotNow(RatePromptState state) => state.nextAt = state.appSeconds + RepeatSeconds;
    }

    /// <summary>PlayerPrefs side of <see cref="RatePromptPolicy"/>; native shows the dialog and reports the choice.</summary>
    public sealed class RatePromptController
    {
        const string StateKey = "StbEmu.Rate.State";
        static RatePromptController instance;
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetInstance() => instance = null;
        public static RatePromptController Instance => instance ?? (instance = new RatePromptController());

        readonly RatePromptState state = Load();
        double sessionSeconds;
        float unsaved;

        public bool Stopped => state.stop;
        public bool IsDue => RatePromptPolicy.IsDue(state, sessionSeconds);

        /// <summary>Foreground seconds; call every frame the app is in the foreground.</summary>
        public void Tick(double seconds)
        {
            if (state.stop) return;
            state.appSeconds += seconds;
            sessionSeconds += seconds;
            if ((unsaved += (float)seconds) >= 30) Save();
        }

        /// <summary>Native choice: "rate" and "never" stop for good, "later" (Not now / Back) waits another 20 minutes.</summary>
        public void Record(string choice)
        {
            if (choice == "later") RatePromptPolicy.NotNow(state);
            else if (choice == "rate" || choice == "never") state.stop = true;
            else return;
            Save();
        }

        public void Save()
        {
            unsaved = 0;
            PlayerPrefs.SetString(StateKey, JsonUtility.ToJson(state));
            PlayerPrefs.Save();
        }

        static RatePromptState Load()
        {
            RatePromptState loaded = null;
            try { loaded = JsonUtility.FromJson<RatePromptState>(PlayerPrefs.GetString(StateKey, "")); }
            catch (ArgumentException) { } // corrupt: start over
            return loaded ?? new RatePromptState();
        }
    }
}
