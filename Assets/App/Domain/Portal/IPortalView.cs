using System;
using System.Threading;
using Cysharp.Threading.Tasks;

namespace StbEmu.Portal
{
    public sealed class PortalLoadException : Exception
    {
        public PortalLoadException(string reason) : base($"Portal failed to load: {reason}") { }
    }

    public interface IPortalView
    {
        /// <summary>Validated messages of the current session only. Raised on the Unity main thread.</summary>
        event Action<PortalMessage> MessageReceived;

        /// <summary>Load/WebView errors after OpenAsync has completed (e.g. "LOAD_FAILED:-2"). Raised on the Unity main thread.</summary>
        event Action<string> Error;

        /// <summary>
        /// Completes when the facade is running in the portal page (bridge.hello), not on page-finished.
        /// Throws PortalLoadException on load error; cancel via <paramref name="ct"/> (e.g. timeout) closes the portal.
        /// </summary>
        UniTask OpenAsync(PortalSession session, CancellationToken ct);

        UniTask PublishAsync(PortalEvent message, CancellationToken ct);

        UniTask CloseAsync(CancellationToken ct);
    }
}
