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

        readonly AndroidStbHost host;
        string sessionId;
        UniTaskCompletionSource opening;
        bool reconnecting; // the current opening is a reconnect: a no-stream failure must still surface as Error

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
            reconnecting = !closeOnCancellation;
            var current = opening = new UniTaskCompletionSource();

            host.OpenPortal(session.PortalUrl, session.SnapshotJson(), session.UserAgent);
            try
            {
                await current.Task.AttachExternalCancellation(ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                if (closeOnCancellation) await CloseAsync(CancellationToken.None);
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
                    if (message.Type == PortalMessage.Hello) opening?.TrySetResult(); // repeated on every page of the portal; only the first matters
                    else MessageReceived?.Invoke(message);
                    break;

                case "portal.error":
                    if (opening == null || !opening.TrySetException(new PortalLoadException(data)) || reconnecting) Error?.Invoke(data);
                    break;
                case "portal.unavailable":
                    if (opening == null || !opening.TrySetException(new PortalLoadException(data))) Unavailable?.Invoke(data);
                    break;
            }
        }
    }
}
