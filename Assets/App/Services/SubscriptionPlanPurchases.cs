using System;
using System.Linq;
using UnityEngine.Purchasing;

namespace StbEmu.Services
{
    // Store-only plan changes. No backend or automatic subscription cancellation.
    public sealed partial class InAppPurchaseController
    {
        bool awaitingPlanVerification;
        string subscriptionToken = "";
        bool subscriptionPaymentPending;
        bool subscriptionKnown;
        string subscriptionReplacedToken = "";
        public string ActiveBasePlan => (string)planCatalog["activePlan"] ?? "";

        string PurchaseToken(string id) => store?.GetPurchases()
            .FirstOrDefault(order => order is ConfirmedOrder && order.CartOrdered.Items().Any(item => item.Product.definition.id == id))
            ?.Info?.Google?.PurchaseToken ?? "";

        public bool HasDeferredPayment => subscriptionPaymentPending || (store != null && store.GetPurchases().Any(order => order is DeferredOrder));

        bool IsReplacementOrder(Order order, string id) => id != MonthlyId || string.IsNullOrEmpty(subscriptionReplacedToken)
            || (order is FailedOrder && string.IsNullOrEmpty(order.Info?.Google?.PurchaseToken))
            || (!string.IsNullOrEmpty(order.Info?.Google?.PurchaseToken) && order.Info.Google.PurchaseToken != subscriptionReplacedToken);

        public void BuyPlan(string plan, Action<PurchaseResult, string> callback)
        {
            if (!IsReady || requests.Count > 0 || HasDeferredPayment || IsOwned(RemoveAdsId))
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Store unavailable or a purchase is pending. Finish the pending purchase or use Restore Purchase.")); return; }
            string oldToken = subscriptionKnown ? subscriptionToken : PurchaseToken(MonthlyId);
            if (IsOwned(MonthlyId) && oldToken.Length == 0)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Use Restore Purchase before changing plans.")); return; }
            if (oldToken.Length > 0 && ActiveBasePlan == plan)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "This plan is already active.")); return; }
            subscriptionReplacedToken = oldToken;
            BuyPlanCore(plan, oldToken, (result, message) =>
            {
                subscriptionReplacedToken = ""; // the request is finished either way
                Invoke(() => callback?.Invoke(result, message));
            });
        }

        public void BuyForever(Action<PurchaseResult, string> callback)
        {
            if (!IsReady || requests.Count > 0 || HasDeferredPayment)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Store unavailable or a purchase is pending. Try again or use Restore Purchase.")); return; }
            if (HasActiveSubscription)
            { Invoke(() => callback?.Invoke(PurchaseResult.Failed, "Cancel your subscription and wait for it to expire before buying Pro Forever.")); return; }
            Buy(RemoveAdsId, callback);
        }
    }
}
