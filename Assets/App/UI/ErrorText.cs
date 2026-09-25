namespace StbEmu.UI
{
    /// <summary>User-facing text for portal errors ("LOAD_FAILED:&lt;code&gt; net::ERR_… &lt;url&gt;", "WEBVIEW_UNSUPPORTED", <see cref="Timeout"/>).</summary>
    public static class ErrorText
    {
        public const string Timeout = "TIMEOUT";

        static readonly string[] NetworkErrors =
        {
            "ERR_NAME_NOT_RESOLVED", "ERR_CONNECTION_", "ERR_TIMED_OUT", "ERR_INTERNET_DISCONNECTED", "ERR_ADDRESS_UNREACHABLE", "ERR_NETWORK_CHANGED",
        };

        public static string For(string code)
        {
            if (string.IsNullOrEmpty(code)) return "Lỗi không xác định.";
            if (code == Timeout) return "Portal không phản hồi. Thử lại sau.";
            if (code.StartsWith("WEBVIEW_UNSUPPORTED")) return "WebView trên máy quá cũ. Hãy cập nhật Android System WebView.";
            if (code.Contains("ERR_CLEARTEXT_NOT_PERMITTED")) return "Portal dùng HTTP nhưng bị chặn. Hãy build lại app.";
            foreach (var network in NetworkErrors)
                if (code.Contains(network)) return "Không kết nối được tới portal. Kiểm tra mạng và Portal URL.";
            return "Lỗi: " + code;
        }
    }
}
