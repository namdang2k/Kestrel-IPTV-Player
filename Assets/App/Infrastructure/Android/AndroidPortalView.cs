using System;
using System.Threading;
using Cysharp.Threading.Tasks;
using StbEmu.Portal;
using UnityEngine;

namespace StbEmu.Android
{
    public sealed class AndroidPortalView : IPortalView
    {
        public event Action<PortalMessage> MessageReceived;
        public event Action<string> Error;
        public event Action<string> Unavailable;
        public event Action<string> PhaseChanged;

        readonly AndroidStbHost host;
        string sessionId;
        UniTaskCompletionSource opening;

        public AndroidPortalView(AndroidStbHost host)
        {
            this.host = host;
            host.NativeEvent += OnNativeEvent;
        }

        public async UniTask OpenAsync(PortalSession session, CancellationToken ct)
        {
            await OpenCoreAsync(session, ct, true);
        }

        public async UniTask ReconnectAsync(PortalSession session, CancellationToken ct)
        {
            await OpenCoreAsync(session, ct, false);
        }

        async UniTask OpenCoreAsync(PortalSession session, CancellationToken ct, bool closeOnCancellation)
        {
            opening?.TrySetCanceled();
            sessionId = session.SessionId;
            var current = opening = new UniTaskCompletionSource();

            host.OpenPortal(session.PortalUrl, session.SnapshotJson(), session.UserAgent);
            try
            {
                await current.Task.AttachExternalCancellation(ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                if (closeOnCancellation && ReferenceEquals(opening, current) && sessionId == session.SessionId)
                    await CloseAsync(CancellationToken.None);
                else if (ReferenceEquals(opening, current) && sessionId == session.SessionId)
                {
                    sessionId = null;
                    host.AbortPortalReconnect("RECONNECT_TIMEOUT");
                }
                throw;
            }
        }

        public UniTask PublishAsync(PortalEvent message, CancellationToken ct)
        {
            if (sessionId != null) host.PublishToPortal(message.ToJson(sessionId));
            return UniTask.CompletedTask;
        }

        public UniTask CloseAsync(CancellationToken ct)
        {
            sessionId = null; // from here on, every message of the old page fails TryParse
            opening?.TrySetCanceled();
            host.ClosePortal();
            return UniTask.CompletedTask;
        }

        void OnNativeEvent(string type, string data)
        {
            switch (type)
            {
                case "portal.message":
                    if (!PortalMessage.TryParse(data, sessionId, out var message, out var error))
                    {
                        Debug.LogWarning($"Portal message rejected: {error}");
                        return;
                    }
                    if (message.Type != PortalMessage.Hello) MessageReceived?.Invoke(message);
                    break;

                case "portal.ready":
                    if (data == sessionId) opening?.TrySetResult();
                    break;
                case "portal.phase":
                    var prefix = sessionId + "|";
                    if (sessionId != null && data.StartsWith(prefix)) PhaseChanged?.Invoke(data.Substring(prefix.Length));
                    break;

                case "portal.error":
                    if (opening == null || !opening.TrySetException(new PortalLoadException(data))) Error?.Invoke(data);
                    break;
                case "portal.unavailable":
                    if (opening == null || !opening.TrySetException(new PortalLoadException(data))) Unavailable?.Invoke(data);
                    break;
                case "portal.catalog-error":
                    if (opening == null || !opening.TrySetException(new PortalLoadException("CATALOG_" + data))) Unavailable?.Invoke(data);
                    break;
            }
        }
    }
}
