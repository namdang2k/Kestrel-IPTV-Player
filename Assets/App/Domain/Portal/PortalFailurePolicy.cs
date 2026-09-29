namespace StbEmu.Portal
{
    /// <summary>Only transient transport failures and one explicitly rejected session allow automatic reconnect.</summary>
    public static class PortalFailurePolicy
    {
        public static bool CanRetry(string code)
        {
            if (string.IsNullOrEmpty(code)) return false;
            if (code == "AUTH_FAILED") return true;
            if (code.Contains("ERR_CERT") || code.Contains("ERR_SSL")) return false;
            return code == "NETWORK" || code == "SERVER_UNAVAILABLE" || code.Contains("TIMEOUT")
                || code == "LOAD_FAILED:-2" || code == "LOAD_FAILED:-6" || code == "LOAD_FAILED:-8"
                || code.StartsWith("HTTP_5") || code.Contains("ERR_NAME_NOT_RESOLVED")
                || code.Contains("ERR_CONNECTION_") || code.Contains("ERR_INTERNET_DISCONNECTED")
                || code.Contains("ERR_NETWORK_CHANGED") || code.Contains("ERR_ADDRESS_UNREACHABLE");
        }
    }
}
