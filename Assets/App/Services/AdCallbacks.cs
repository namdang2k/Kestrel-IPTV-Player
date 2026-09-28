using System;
using GoogleMobileAds.Api;
using UnityEngine;

namespace StbEmu.Services
{
    /// <summary>Callbacks for one show request, delivered on Unity's main thread.</summary>
    public sealed class AdCallbacks
    {
        public Action OnShown;
        public Action OnClosed;
        public Action<string> OnFailed;
        public Action OnImpression;
        public Action OnClicked;
        public Action<AdValue> OnPaid;
    }

    // Owns per-request completion state; errors and close are mutually exclusive.
    internal sealed class AdShowSession
    {
        readonly AdCallbacks callbacks;
        bool shown, completed;
        public AdShowSession(AdCallbacks callbacks) { this.callbacks = callbacks; }
        public void Shown()
        {
            if (completed || shown) return;
            shown = true;
            Invoke(callbacks?.OnShown);
        }
        public void Close()
        {
            if (completed) return;
            completed = true;
            Invoke(callbacks?.OnClosed);
        }
        public void Fail(string error)
        {
            if (completed) return;
            completed = true;
            Invoke(() => callbacks?.OnFailed?.Invoke(error));
        }
        public void Impression() { if (!completed) Invoke(callbacks?.OnImpression); }
        public void Clicked() { if (!completed) Invoke(callbacks?.OnClicked); }
        public void Paid(AdValue value) { if (!completed) Invoke(() => callbacks?.OnPaid?.Invoke(value)); }
        public static void Invoke(Action action)
        {
            if (action == null) return;
            // An app callback must not prevent native ad cleanup or resetting the show lock.
            foreach (Action handler in action.GetInvocationList())
            {
                try { handler(); }
                catch (Exception error) { Debug.LogException(error); }
            }
        }
    }
}
