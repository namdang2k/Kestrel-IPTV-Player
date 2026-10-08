using System;
using System.Linq;
using UnityEngine.Purchasing;

namespace StbEmu.Services
{
    // Store-only plan changes. No backend or automatic subscription cancellation.
    public sealed partial class InAppPurchaseController
    {
        bool purchasesKnown, awaitingPlanVerification;
        string subscriptionReplacedToken = "";
        public string ActiveBasePlan => (string)planCatalog["activePlan"] ?? "";

        string PurchaseToken(string id) => store?.GetPurchases()
            .FirstOrDefault(order => order is ConfirmedOrder && order.CartOrdered.Items().Any(item => item.Product.definition.id == id))
            ?.Info?.Google?.PurchaseToken ?? "";

        bool HasDeferredPayment => store != null && store.GetPurchases().Any(order => order is DeferredOrder);

        bool IsReplacementOrder(Order order, string id) => id != MonthlyId || string.IsNullOrEmpty(subscriptionReplacedToken)
            || (order is FailedOrder && string.IsNullOrEmpty(order.Info?.Google?.PurchaseToken))
            || (!string.IsNullOrEmpty(order.Info?.Google?.PurchaseToken) && order.Info.Google.PurchaseToken != subscriptionReplacedToken);

        public void BuyPlan(string plan, Action<PurchaseResult, string> callback)
        {
            if (!purchasesKnown || requests.Count > 0 || HasDeferredPayment || IsOwned(RemoveAdsId))
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Wait for purchases to refresh, or finish the pending purchase first.")); return; }
            string oldToken = PurchaseToken(MonthlyId);
            if (IsOwned(MonthlyId) && oldToken.Length == 0)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Refresh purchases before changing plans.")); return; }
            if (oldToken.Length > 0 && ActiveBasePlan == plan)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "This plan is already active.")); return; }
            subscriptionReplacedToken = oldToken;
            BuyPlanCore(plan, oldToken, (result, message) =>
            {
                if (result != PurchaseResult.Pending) subscriptionReplacedToken = "";
                Invoke(() => callback?.Invoke(result, message));
            });
        }

        public void BuyForever(Action<PurchaseResult, string> callback)
        {
            if (!IsReady || !purchasesKnown || requests.Count > 0 || HasDeferredPayment)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Wait for purchases to refresh, then try again.")); return; }
            if (HasActiveSubscription)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Cancel your subscription and wait for it to expire before buying Pro Forever.")); return; }
            Buy(RemoveAdsId, callback);
        }
    }
}
