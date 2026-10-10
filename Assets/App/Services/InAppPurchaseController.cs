using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;
using UnityEngine.Purchasing;
using Newtonsoft.Json.Linq;

namespace StbEmu.Services
{
    public enum PurchaseResult { Purchased, Pending, Cancelled, Failed }

    /// <summary>
    /// Unity IAP 5 for the app's lifetime: store connection, catalog, purchases, subscriptions and restore.
    /// Content is granted in <see cref="PurchaseGranted"/> before the order is confirmed, so a purchase interrupted by a
    /// killed app (paid but never confirmed) comes back as a pending order when the user restores purchases and is granted then;
    /// consumables are granted once per transaction even if the order is delivered twice.
    /// All public members must be used from Unity's main thread.
    /// </summary>
    [DisallowMultipleComponent]
    public sealed partial class InAppPurchaseController : MonoBehaviour
    {
        [Serializable]
        public sealed class ProductEntry
        {
            public string id;
            public ProductType type;
        }

        public static InAppPurchaseController Instance { get; private set; }
        public const string RemoveAdsId = "remove_ads";
        public const string MonthlyId = "premium_monthly"; // subscription product, shared by both base plans
        public const string MonthlyPlanId = "monthly";
        public const string YearlyPlanId = "yearly";
        JObject planCatalog = new JObject();
        public event Action PlansChanged;
#if UNITY_ANDROID && !UNITY_EDITOR
        AndroidJavaObject subscriptionPlans;
        PlanListener planListener;
#endif
        public string GetPlanPrice(string plan) => (string)planCatalog[plan + "Price"] ?? "";
        public bool HasPlanTrial(string plan) => (bool?)planCatalog[plan + "Trial"] ?? false;

        public void RefreshPlans()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (subscriptionPlans == null)
            {
                using var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                using var activity = player.GetStatic<AndroidJavaObject>("currentActivity");
                planListener = new PlanListener(this);
                subscriptionPlans = new AndroidJavaObject("com.stbemu.host.SubscriptionPlans", activity, MonthlyId, planListener);
            }
            subscriptionPlans.Call("refresh");
#endif
        }

        void BuyPlanCore(string plan, string oldPurchaseToken, Action<PurchaseResult, string> callback)
        {
            if (plan != MonthlyPlanId && plan != YearlyPlanId)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Unknown base plan")); return; }
#if UNITY_ANDROID && !UNITY_EDITOR
            if (!IsReady || subscriptionPlans == null || requests.ContainsKey(MonthlyId))
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Store unavailable, subscription already owned, or purchase in progress")); return; }
            requests[MonthlyId] = callback;
            try { subscriptionPlans.Call("buy", plan, oldPurchaseToken); }
            catch (Exception error) { Complete(MonthlyId, PurchaseResult.Failed, error.Message); }
#else
            Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Base plans require Google Play"));
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        sealed class PlanListener : AndroidJavaProxy
        {
            readonly InAppPurchaseController owner;
            readonly System.Threading.SynchronizationContext context;
            public PlanListener(InAppPurchaseController owner) : base("com.stbemu.host.SubscriptionPlans$Listener")
            { this.owner = owner; context = System.Threading.SynchronizationContext.Current; }
            [UnityEngine.Scripting.Preserve]
            public void onCatalog(string json) => context.Post(_ =>
            {
                if (owner == null) return;
                owner.planCatalog = JObject.Parse(json);
                InAppPurchaseController.Invoke(() => owner.PlansChanged?.Invoke());
            }, null);
            [UnityEngine.Scripting.Preserve]
            public void onSubscription(bool active, string token, bool pending) => context.Post(_ =>
            {
                if (owner == null) return;
                owner.subscriptionToken = token;
                owner.subscriptionKnown = true;
                owner.subscriptionPaymentPending = pending;
                var now = new HashSet<string>(owner.owned);
                if (active) now.Add(MonthlyId);
                else now.Remove(MonthlyId);
                owner.SetOwned(now);
            }, null);
            [UnityEngine.Scripting.Preserve]
            public void onResult(string result, string message) => context.Post(_ =>
            {
                if (owner == null) return;
                // Unity IAP fetches, grants and confirms the order before the purchase callback succeeds.
                // A purchase callback may already have completed the request; don't leave the flag set for a later purchase.
                if (result == "purchased") { owner.awaitingPlanVerification = owner.requests.ContainsKey(MonthlyId); owner.store.FetchPurchases(); }
                else owner.Complete(MonthlyId, result == "pending" ? PurchaseResult.Pending
                    : result == "cancelled" ? PurchaseResult.Cancelled : PurchaseResult.Failed, message);
            }, null);
        }
#endif
        [SerializeField] bool initializeOnStart = true;
        [Tooltip("Product IDs exactly as created in Google Play Console / App Store Connect.")]
        [SerializeField] List<ProductEntry> products = new List<ProductEntry>
        {
            new ProductEntry { id = "remove_ads", type = ProductType.NonConsumable },
            new ProductEntry { id = "premium_monthly", type = ProductType.Subscription },
        };

        /// <summary>Store connected and catalog fetched: prices are known and Buy() works.</summary>
        public bool IsReady { get; private set; }
        public string LastError { get; private set; }
        public event Action Initialized;
        public event Action<string> Failed;
        /// <summary>
        /// Deliver the product now: (productId, fromThisSession). fromThisSession is false for purchases recovered on
        /// restore (app killed mid-purchase, deferred payment completed, bought on another device).
        /// </summary>
        public event Action<string, bool> PurchaseGranted;
        /// <summary>Payment not finished yet (e.g. cash / carrier / family approval); granted later via PurchaseGranted.</summary>
        public event Action<string> PurchaseDeferred;
        public event Action<string, PurchaseFailureReason, string> PurchaseFailed;
        /// <summary>The owned non-consumables / active subscriptions changed (bought, restored, expired, refunded).</summary>
        public event Action OwnershipChanged;
        /// <summary>A purchases fetch (purchase verification or restore) finished: true = ownership is now current, false = fetch failed.</summary>
        public event Action<bool> PurchasesFetched;

        const string OwnedKey = "StbEmu.IAP.Owned";
        const string GrantedKey = "StbEmu.IAP.GrantedTransactions";
        const int MaxRememberedTransactions = 100;

        StoreController store;
        bool connecting;
        readonly HashSet<string> owned = new HashSet<string>();
        readonly List<string> grantedTransactions = new List<string>();
        readonly Dictionary<string, Action<PurchaseResult, string>> requests = new Dictionary<string, Action<PurchaseResult, string>>();

        void Awake()
        {
            if (Instance != null && Instance != this) { Destroy(gameObject); return; }
            Instance = this;
            DontDestroyOnLoad(gameObject);
            // Cached so entitlements (e.g. no ads) apply at startup, before or without a store connection.
            owned.UnionWith(Split(PlayerPrefs.GetString(OwnedKey, "")));
            grantedTransactions.AddRange(Split(PlayerPrefs.GetString(GrantedKey, "")));
        }

        void Start() { if (initializeOnStart) Initialize(); }

        public async void Initialize()
        {
            if (IsReady || connecting) return;
            if (products.Count == 0) { Fail("No products configured."); return; }
            connecting = true;
            LastError = null;
            if (store == null)
            {
                store = UnityIAPServices.StoreController();
                store.OnStoreConnected += OnStoreConnected;
                store.OnStoreDisconnected += OnStoreDisconnected;
                store.OnProductsFetched += OnProductsFetched;
                store.OnProductsFetchFailed += OnProductsFetchFailed;
                store.OnPurchasesFetched += OnPurchasesFetched;
                store.OnPurchasesFetchFailed += OnPurchasesFetchFailed;
                store.OnPurchasePending += OnPurchasePending;
                store.OnPurchaseConfirmed += OnPurchaseConfirmed;
                store.OnPurchaseFailed += OnPurchaseFailed;
                store.OnPurchaseDeferred += OnPurchaseDeferred;
            }
            try
            {
                await store.Connect(); // OnStoreConnected → product catalog only
            }
            catch (Exception error)
            {
                connecting = false;
                Fail("Store connection failed: " + error.Message);
            }
        }

        // ---------- Queries ----------

        public bool IsOwned(string productId) => owned.Contains(productId);

        public bool HasActiveSubscription =>
            products.Any(p => p.type == ProductType.Subscription && owned.Contains(p.id));

        /// <summary>No ads: the lifetime unlock or any active subscription (cached, so valid before the store connects).</summary>
        public bool IsPro => IsOwned(RemoveAdsId) || HasActiveSubscription;

        public Product GetProduct(string productId) => store?.GetProductById(productId);

        /// <summary>Store-formatted price (e.g. "49.000 ₫"), or "" before the catalog is fetched.</summary>
        public string GetPrice(string productId) => GetProduct(productId)?.metadata?.localizedPriceString ?? "";
        /// <summary>Expiry/renewal info of an owned subscription, when the store provides it (null otherwise).</summary>
        public SubscriptionInfo GetSubscriptionInfo(string productId)
        {
            if (store == null) return null;
            foreach (var order in store.GetPurchases())
            {
                var info = order.Info?.PurchasedProductInfo?.FirstOrDefault(p => p.productId == productId);
                if (info?.subscriptionInfo != null) return info.subscriptionInfo;
            }
            return null;
        }

        // ---------- Actions ----------

        /// <summary>Starts a purchase. callback(result, message) fires once: Purchased, Pending (paid later), Cancelled or Failed.</summary>
        public void Buy(string productId, Action<PurchaseResult, string> callback = null)
        {
            var product = IsReady ? GetProduct(productId) : null;
            string error = !IsReady ? "Store not ready" + (LastError != null ? ": " + LastError : "")
                : product == null || !product.availableToPurchase ? "Product unavailable: " + productId
                : productId == RemoveAdsId && HasActiveSubscription ? "Cancel your subscription and wait for it to expire before buying Pro Forever."
                : requests.ContainsKey(productId) ? "A purchase of this product is already in progress"
                : Configured(productId)?.type != ProductType.Consumable && owned.Contains(productId) ? "Already owned"
                : null;
            if (error != null)
            {
                Debug.LogWarning("[IAP] Buy " + productId + " rejected: " + error);
                Invoke(() => callback?.Invoke(PurchaseResult.Failed, error));
                return;
            }
            requests[productId] = callback;
            Debug.Log("[IAP] Buy " + productId);
            store.PurchaseProduct(product);
        }

        /// <summary>
        /// Restores non-consumables and subscriptions when the user requests it.
        /// Granted items arrive through PurchaseGranted / OwnershipChanged.
        /// </summary>
        public void Restore(Action<bool, string> callback = null)
        {
            if (!IsReady) { Invoke(() => callback?.Invoke(false, "Store not ready")); return; }
            store.RestoreTransactions((success, error) =>
            {
                Debug.Log("[IAP] Restore " + (success ? "done" : "failed: " + error));
                if (success) store.FetchPurchases();
                Invoke(() => callback?.Invoke(success, error));
            });
        }

        // ---------- Store callbacks ----------

        void OnStoreConnected()
        {
            Debug.Log("[IAP] Store connected");
            store.FetchProducts(products.Where(p => !string.IsNullOrWhiteSpace(p.id))
                .Select(p => new ProductDefinition(p.id.Trim(), p.type)).ToList());
        }

        void OnStoreDisconnected(StoreConnectionFailureDescription failure)
        {
            connecting = false;
            IsReady = false;
            Fail("Store disconnected: " + failure?.message); // retried on the next resume
        }

        void OnProductsFetched(List<Product> fetched)
        {
            connecting = false;
            Debug.Log("[IAP] Products: " + string.Join(", ", fetched.Select(p => p.definition.id + " " + p.metadata?.localizedPriceString)));
            bool first = !IsReady;
            IsReady = true;
            LastError = null;
            if (first) Invoke(() => Initialized?.Invoke());
            RefreshPlans();
            // RefreshPlans checks SUBS only; Pro Forever ownership stays cached until purchase/restore.
        }

        void OnProductsFetchFailed(ProductFetchFailed failure)
        {
            connecting = false;
            Fail("Products fetch failed: " + failure.FailureReason);
        }

        void OnPurchasesFetched(Orders orders)
        {
            // Google Play returns only owned non-consumables and active subscriptions, so this is the source of truth.
            var now = new HashSet<string>();
            // IAP PendingOrder means paid, awaiting acknowledgement (not payment pending).
            // IAP delivers OnPurchasePending before this snapshot callback. Include those
            // orders so this refresh cannot revoke the entitlement that callback just granted.
            // DeferredOrders are unpaid and must never grant ownership.
            foreach (var order in orders.ConfirmedOrders.Cast<Order>().Concat(orders.PendingOrders))
            {
                foreach (var item in order.CartOrdered.Items())
                {
                    string id = item.Product.definition.id;
                    if (Configured(id)?.type == ProductType.Consumable) continue;
#if !UNITY_ANDROID || UNITY_EDITOR
                    var sub = order.Info?.PurchasedProductInfo?.FirstOrDefault(p => p.productId == id)?.subscriptionInfo;
                    if (sub != null && sub.IsExpired() == Result.True) continue;
#endif
                    // On Play, queryPurchases returns active subscriptions. IAP's SubscriptionInfo estimates
                    // expiry from the first base plan's metadata, which can be wrong for the yearly plan.
                    now.Add(id);
                }
            }
            foreach (var order in orders.DeferredOrders) NotifyDeferred(order);
            Debug.Log($"[IAP] Purchases: owned=[{string.Join(", ", now)}] pending={orders.PendingOrders.Count} deferred={orders.DeferredOrders.Count}");
            SetOwned(now);
            // Orders already confirmed by another store callback/client still complete a base-plan request.
            foreach (var order in orders.ConfirmedOrders)
                foreach (var item in order.CartOrdered.Items())
                    if (IsReplacementOrder(order, item.Product.definition.id)) Complete(item.Product.definition.id, PurchaseResult.Purchased, "");
            // Play reported the purchase but this fetch has no order to confirm: don't leave the spinner up forever.
            if (awaitingPlanVerification && !orders.PendingOrders.Any(o => o.CartOrdered.Items().Any(i => i.Product.definition.id == MonthlyId)))
                Complete(MonthlyId, PurchaseResult.Failed, "Could not verify purchase. Use Restore Purchase to retry.");
            RefreshPlans();
            Invoke(() => PurchasesFetched?.Invoke(true));
        }

        void OnPurchasesFetchFailed(PurchasesFetchFailureDescription failure)
        {
            if (awaitingPlanVerification) Complete(MonthlyId, PurchaseResult.Failed, "Could not verify purchase. Use Restore Purchase to retry.");
            Fail("Purchases fetch failed: " + failure.failureReason + " " + failure.message); // keep the cached ownership
            Invoke(() => PurchasesFetched?.Invoke(false));
        }

        void OnPurchasePending(PendingOrder order)
        {
            string transaction = order.Info?.TransactionID ?? "";
            bool alreadyGranted = transaction.Length > 0 && grantedTransactions.Contains(transaction);
            foreach (var item in order.CartOrdered.Items())
            {
                string id = item.Product.definition.id;
                bool fromThisSession = requests.ContainsKey(id) && IsReplacementOrder(order, id);
                if (Configured(id)?.type != ProductType.Consumable) owned.Add(id);
                if (alreadyGranted && Configured(id)?.type == ProductType.Consumable) continue; // delivered before the app died
                Debug.Log($"[IAP] Grant {id} tx={transaction} fromThisSession={fromThisSession}");
                Invoke(() => PurchaseGranted?.Invoke(id, fromThisSession));
            }
            RememberTransaction(transaction);
            SaveOwned();
            Invoke(() => OwnershipChanged?.Invoke());
            // Confirm after granting: dying before this line means the store re-delivers the order (no lost purchase),
            // and the remembered transaction stops a second consumable grant.
            store.ConfirmPurchase(order);
        }

        void OnPurchaseConfirmed(Order order)
        {
            if (order is FailedOrder failed) { OnPurchaseFailed(failed); return; }
            foreach (var item in order.CartOrdered.Items())
                if (IsReplacementOrder(order, item.Product.definition.id)) Complete(item.Product.definition.id, PurchaseResult.Purchased, "");
            // Refresh confirmed tokens and ownership after plan changes or deferred payments.
            store.FetchPurchases();
        }

        void OnPurchaseFailed(FailedOrder order)
        {
            var result = order.FailureReason == PurchaseFailureReason.UserCancelled ? PurchaseResult.Cancelled : PurchaseResult.Failed;
            foreach (var item in order.CartOrdered.Items())
            {
                string id = item.Product.definition.id;
                if (!IsReplacementOrder(order, id)) continue;
                Debug.LogWarning($"[IAP] Purchase {id} failed: {order.FailureReason} {order.Details}");
                Invoke(() => PurchaseFailed?.Invoke(id, order.FailureReason, order.Details));
                Complete(id, result, order.Details);
            }
        }

        void OnPurchaseDeferred(DeferredOrder order) => NotifyDeferred(order);

        void NotifyDeferred(Order order)
        {
            foreach (var item in order.CartOrdered.Items())
            {
                string id = item.Product.definition.id;
                Debug.Log("[IAP] Purchase deferred (payment pending): " + id);
                Invoke(() => PurchaseDeferred?.Invoke(id));
                Complete(id, PurchaseResult.Pending, "Payment pending");
            }
        }

        // Recheck subscription expiry on resume without querying Pro Forever purchases.
        void OnApplicationPause(bool paused)
        {
            if (paused || Instance != this) return;
            if (IsReady) RefreshPlans();
            else Initialize();
        }

        void OnDestroy()
        {
            if (Instance != this) return;
            Instance = null;
#if UNITY_ANDROID && !UNITY_EDITOR
            subscriptionPlans?.Call("close");
            subscriptionPlans?.Dispose();
            subscriptionPlans = null;
#endif
            if (store == null) return;
            store.OnStoreConnected -= OnStoreConnected;
            store.OnStoreDisconnected -= OnStoreDisconnected;
            store.OnProductsFetched -= OnProductsFetched;
            store.OnProductsFetchFailed -= OnProductsFetchFailed;
            store.OnPurchasesFetched -= OnPurchasesFetched;
            store.OnPurchasesFetchFailed -= OnPurchasesFetchFailed;
            store.OnPurchasePending -= OnPurchasePending;
            store.OnPurchaseConfirmed -= OnPurchaseConfirmed;
            store.OnPurchaseFailed -= OnPurchaseFailed;
            store.OnPurchaseDeferred -= OnPurchaseDeferred;
        }

        // ---------- Helpers ----------

        ProductEntry Configured(string productId) => products.FirstOrDefault(p => p.id == productId);

        void Complete(string productId, PurchaseResult result, string message)
        {
            if (!requests.TryGetValue(productId, out var callback)) return;
            requests.Remove(productId);
            if (productId == MonthlyId) awaitingPlanVerification = false;
            Invoke(() => callback?.Invoke(result, message));
        }

        void SetOwned(HashSet<string> now)
        {
            if (owned.SetEquals(now)) return;
            owned.Clear();
            owned.UnionWith(now);
            SaveOwned();
            Invoke(() => OwnershipChanged?.Invoke());
        }

        void SaveOwned()
        {
            PlayerPrefs.SetString(OwnedKey, string.Join("\n", owned));
            PlayerPrefs.Save();
        }

        void RememberTransaction(string transaction)
        {
            if (transaction.Length == 0 || grantedTransactions.Contains(transaction)) return;
            grantedTransactions.Add(transaction);
            if (grantedTransactions.Count > MaxRememberedTransactions) grantedTransactions.RemoveAt(0);
            PlayerPrefs.SetString(GrantedKey, string.Join("\n", grantedTransactions));
            PlayerPrefs.Save(); // before ConfirmPurchase: survives a kill right after granting
        }

        void Fail(string message)
        {
            LastError = message;
            Debug.LogWarning("[IAP] " + message, this);
            Invoke(() => Failed?.Invoke(message));
        }

        // A throwing app handler must not break the purchase flow (confirmation, bookkeeping).
        static void Invoke(Action action) => AdShowSession.Invoke(action);

        static IEnumerable<string> Split(string value) => value.Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
    }
}
