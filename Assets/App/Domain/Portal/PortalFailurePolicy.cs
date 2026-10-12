namespace StbEmu.Portal
{
    /// <summary>Only transient transport failures allow an automatic retry or reconnect; account and configuration errors never do.</summary>
    public static class PortalFailurePolicy
    {
        public const int MaxProfileRetries = 3;

        public static bool TryGetProfileRetryDelay(string code, int retries, out System.TimeSpan delay)
        {
            delay = System.TimeSpan.Zero;
            if (retries >= MaxProfileRetries || retries < 0 || code == null) return false;
            if (code.StartsWith("CATALOG_")) code = code.Substring(8);
            if (code != "NETWORK" && code != "TIMEOUT" && !code.StartsWith("HTTP_5")) return false;
            delay = System.TimeSpan.FromSeconds(1 << retries);
            return true;
        }

        public static bool CanRetry(string code)
        {
            if (string.IsNullOrEmpty(code)) return false;
            return code == "NETWORK" || code == "STREAM_LINK_REFUSED" || code.Contains("TIMEOUT") || code.StartsWith("HTTP_5");
        }
    }
}
