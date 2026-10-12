using System;
using System.Threading;
using Cysharp.Threading.Tasks;

namespace StbEmu.Portal
{
    public sealed class PortalLoadException : Exception
    {
        /// <summary>Raw native code, e.g. "NETWORK", "AUTHENTICATION_REJECTED", "CATALOG_TIMEOUT".</summary>
        public readonly string Reason;

        public PortalLoadException(string reason) : base($"Portal failed to load: {reason}")
        {
            Reason = reason;
        }
    }

    public interface IPortalView
    {
        /// <summary>Native errors after OpenAsync has completed (e.g. "NETWORK"). Raised on the Unity main thread.</summary>
        event Action<string> Error;

        /// <summary>
        /// Completes after profile validation and a valid essential catalog, including an empty catalog.
        /// Throws PortalLoadException on load error; cancel via <paramref name="ct"/> (e.g. timeout) closes the portal.
        /// </summary>
        UniTask OpenAsync(PortalSession session, CancellationToken ct);

        UniTask CloseAsync(CancellationToken ct);
    }
}
