using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Firebase;
using Firebase.Analytics;
using Firebase.Crashlytics;
using UnityEngine;

namespace StbEmu.Services
{
    [DisallowMultipleComponent]
    public sealed class FirebaseController : MonoBehaviour
    {
        public static FirebaseController Instance { get; private set; }
        [SerializeField] bool initializeOnStart = true;
        [SerializeField] bool analyticsCollectionEnabled = true;
        [SerializeField] bool crashlyticsCollectionEnabled = true;
        public bool IsReady { get; private set; }
        public string LastError { get; private set; }
        public FirebaseApp App { get; private set; }
        public event Action Initialized;
        public event Action<string> Failed;
        Task<bool> initialization;
        const int MaxPendingEvents = 64;
        readonly Queue<(string Name, Parameter[] Parameters)> pendingEvents = new Queue<(string, Parameter[])>();

        void Awake()
        {
            if (Instance != null && Instance != this) { Destroy(gameObject); return; }
            Instance = this;
            DontDestroyOnLoad(gameObject);
        }

        async void Start()
        {
            if (initializeOnStart) await InitializeAsync();
        }

        // Call on Unity's main thread. Concurrent callers share the dependency check.
        public Task<bool> InitializeAsync()
        {
            if (IsReady) return Task.FromResult(true);
            if (initialization != null && !initialization.IsCompleted) return initialization;
            return initialization = InitializeCoreAsync();
        }

        async Task<bool> InitializeCoreAsync()
        {
            LastError = null;
            try
            {
                var dependencies = await FirebaseApp.CheckAndFixDependenciesAsync();
                if (this == null) return false;
                if (dependencies != DependencyStatus.Available)
                    throw new InvalidOperationException("Firebase dependencies: " + dependencies);
                App = FirebaseApp.DefaultInstance;
                FirebaseAnalytics.SetAnalyticsCollectionEnabled(analyticsCollectionEnabled);
                Crashlytics.IsCrashlyticsCollectionEnabled = crashlyticsCollectionEnabled;
                IsReady = true;
                Debug.Log($"[Firebase] Initialized: project={App.Options.ProjectId}, analytics={analyticsCollectionEnabled}, " +
                    $"crashlytics={crashlyticsCollectionEnabled}, queued events={pendingEvents.Count}");
                while (pendingEvents.Count > 0)
                {
                    var pending = pendingEvents.Dequeue();
                    try { FirebaseAnalytics.LogEvent(pending.Name, pending.Parameters); }
                    catch (Exception error) { Debug.LogWarning("[FirebaseController] Queued event failed: " + error.Message); }
                }
            }
            catch (Exception error)
            {
                if (this == null) return false;
                LastError = error.Message;
                Debug.LogError("[FirebaseController] " + LastError, this);
                Failed?.Invoke(LastError);
                return false;
            }
            Initialized?.Invoke();
            return true;
        }

        public bool LogEvent(string eventName, params Parameter[] parameters)
        {
            if (!analyticsCollectionEnabled || string.IsNullOrWhiteSpace(eventName)) return false;
            if (!IsReady)
            {
                if (pendingEvents.Count >= MaxPendingEvents) pendingEvents.Dequeue();
                pendingEvents.Enqueue((eventName, parameters == null ? Array.Empty<Parameter>() : (Parameter[])parameters.Clone()));
                return true;
            }
            FirebaseAnalytics.LogEvent(eventName, parameters ?? Array.Empty<Parameter>());
            return true;
        }

        public bool SetUserId(string userId)
        {
            if (!IsReady) return false;
            FirebaseAnalytics.SetUserId(userId);
            Crashlytics.SetUserId(userId ?? "");
            return true;
        }

        public bool LogException(Exception exception)
        {
            if (!IsReady || exception == null) return false;
            Crashlytics.LogException(exception);
            return true;
        }

        void OnDestroy()
        {
            if (Instance != this) return;
            Instance = null;
            IsReady = false;
            // DefaultInstance is shared with other Firebase SDKs; do not dispose it here.
        }
    }
}
