using System;
using System.Threading;
using Cysharp.Threading.Tasks;
using StbEmu.Portal;

namespace StbEmu.Android
{
    public sealed class AndroidPortalView : IPortalView
    {
        public event Action<string> Error;
        public event Action<string> Unavailable;
        public event Action<string> PhaseChanged;

        readonly AndroidStbHost host;
        string sessionId;
        UniTaskCompletionSource opening;
        CancellationTokenSource operation;

        public AndroidPortalView(AndroidStbHost host)
        {
            this.host = host;
            host.NativeEvent += OnNativeEvent;
        }

        public async UniTask OpenAsync(PortalSession session, CancellationToken ct)
        {
            await ConnectAsync(session, ct, true);
        }

        public async UniTask ReconnectAsync(PortalSession session, CancellationToken ct)
        {
            await ConnectAsync(session, ct, false);
        }

        async UniTask ConnectAsync(PortalSession snapshot, CancellationToken ct, bool closeOnCancellation)
        {
            operation?.Cancel();
            var owner = operation = CancellationTokenSource.CreateLinkedTokenSource(ct);
            var deadlineTimer = owner.CancelAfterSlim(TimeSpan.FromSeconds(180), DelayType.Realtime);
            var started = System.Diagnostics.Stopwatch.StartNew();
            try
            {
                for (int retries = 0; ; retries++)
                {
                    owner.Token.ThrowIfCancellationRequested();
                    try
                    {
                        await OpenCoreAsync(snapshot, owner.Token, closeOnCancellation);
                        owner.Token.ThrowIfCancellationRequested();
                        return;
                    }
                    catch (PortalLoadException e)
                    {
                        if (!PortalFailurePolicy.TryGetProfileRetryDelay(e.Reason, retries, out var delay)
                            || started.Elapsed + delay >= TimeSpan.FromSeconds(180)) throw;
                        PhaseChanged?.Invoke($"Retrying {retries + 1}/3");
                        await UniTask.Delay(delay, ignoreTimeScale: true, cancellationToken: owner.Token);
                        snapshot = snapshot.NextAttempt();
                    }
                }
            }
            catch
            {
                if (ReferenceEquals(operation, owner))
                {
                    sessionId = null;
                    if (closeOnCancellation) host.ClosePortal();
                    else host.AbortPortalReconnect("CONNECT_FAILED");
                }
                throw;
            }
            finally
            {
                if (ReferenceEquals(operation, owner)) operation = null;
                deadlineTimer.Dispose();
                owner.Dispose();
            }
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
            finally
            {
                if (ReferenceEquals(opening, current)) opening = null;
            }
        }

        public UniTask CloseAsync(CancellationToken ct)
        {
            operation?.Cancel();
            sessionId = null; // from here on, every event of the old session fails the sessionId prefix check
            opening?.TrySetCanceled();
            host.ClosePortal();
            return UniTask.CompletedTask;
        }

        void OnNativeEvent(string type, string data)
        {
            if (type == "portal.error" || type == "portal.unavailable" || type == "portal.catalog-error")
            {
                var eventPrefix = sessionId + "|";
                if (sessionId == null || !data.StartsWith(eventPrefix, StringComparison.Ordinal)) return;
                data = data.Substring(eventPrefix.Length);
            }
            switch (type)
            {
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
