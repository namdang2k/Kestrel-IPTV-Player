namespace StbEmu.Services
{
    /// <summary>One short display opportunity per eligible startup/resume, never mid-session.</summary>
    public sealed class AppOpenEntryPolicy
    {
        const double EntryWindowSeconds = 10;
        bool paused, suppressResume, pending;
        double expiresAt;
        public bool IsForeground => !paused;
        public AppOpenEntryPolicy(bool firstSession, double now)
        {
            pending = !firstSession;
            expiresAt = now + EntryWindowSeconds;
        }
        public bool CanShow(double now) => !paused && pending && now <= expiresAt;
        public void Consume() { pending = false; }
        public void Pause(bool fullScreenOrConsent)
        {
            if (paused) return;
            paused = true;
            suppressResume = fullScreenOrConsent;
            pending = false;
        }
        public void Resume(double now, bool fullScreenOrConsent)
        {
            if (!paused) return; // Unity can send an initial false without an actual pause.
            paused = false;
            pending = !suppressResume && !fullScreenOrConsent;
            expiresAt = now + EntryWindowSeconds;
        }
    }
}
