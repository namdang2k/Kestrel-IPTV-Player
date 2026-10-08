#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using System.Collections.Generic;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using StbEmu.Profiles;
using StbEmu.Services;
using StbEmu.UI;

namespace StbEmu.Android
{
    /// <summary>
    /// Native home in StbHost (Profile/Setting tabs, list, editor dialog). Java renders and keeps the draft text;
    /// this side owns the Profile objects and raises "ui.profile.*" events as <see cref="IProfileUi"/> events.
    /// </summary>
    public sealed class AndroidProfileUi : IProfileUi
    {
        public event Action Add;
        public event Action<Profile> Edit;
        public event Action<Profile> Delete;
        public event Action<Profile> Selected;
        public event Action<Profile> Saved;
        public event Action Cancelled;

        readonly AndroidStbHost host;
        readonly InAppPurchaseController iap; // null = no store (Pro sheet still opens, buying fails with a toast)
        readonly JObject app;
        IReadOnlyList<Profile> current = Array.Empty<Profile>();
        Profile editing; // null = no editor open; a stale save/cancel from a closed editor is dropped
        bool restoring; // Restore tapped: the result waits for the purchases fetch that follows it
        bool pendingPayment; // slow payment (cash, test card) in this session: the grant that completes it gets the success popup

        public AndroidProfileUi(AndroidStbHost host, string appName, string appVersion)
        {
            this.host = host;
            app = new JObject { ["name"] = appName, ["version"] = appVersion };
            host.NativeEvent += OnNativeEvent;
            iap = InAppPurchaseController.Instance;
            if (iap != null)
            {
                iap.OwnershipChanged += () => host.SetProState(ProState());
                iap.PlansChanged += () => host.SetProState(ProState());
                iap.Initialized += () => host.SetProState(ProState()); // prices arrived
                iap.PurchaseGranted += (id, fromThisSession) =>
                {
                    if (!pendingPayment || (id != InAppPurchaseController.RemoveAdsId && id != InAppPurchaseController.MonthlyId)) return;
                    pendingPayment = false;
                    host.HideProPanel();
                    Status(false, "Congratulations!", "Payment completed. You are now Pro. Enjoy ad-free streaming.");
                };
                iap.PurchasesFetched += ok =>
                {
                    if (!restoring) return;
                    restoring = false;
                    if (!ok) Status(false, "Restore failed", "Could not reach Google Play. Check your internet connection and try again.");
                    else if (iap.IsPro) Status(false, "Restore complete", "Your Pro purchase is active again.");
                    else Status(false, "Nothing to restore", "No Pro purchase was found for this Google account.");
                };
            }
            host.SetProState(ProState());
        }

        public void Show(IReadOnlyList<Profile> profiles)
        {
            current = profiles;
            editing = null;
            var list = new JArray();
            foreach (var p in profiles)
                list.Add(new JObject
                {
                    ["id"] = p.ProfileId, ["name"] = p.Name, ["host"] = Host(p.PortalUrl), ["mac"] = p.Identity?.Mac ?? "",
                });
            host.ShowProfileHome(new JObject { ["app"] = app, ["profiles"] = list }.ToString(Formatting.None));
        }

        public void ShowEdit(Profile profile)
        {
            editing = ProfileForm.Copy(profile);
            host.ShowProfileEditor(ProfileForm.ToForm(editing, profile == null ? "Add profile" : "Edit profile").ToString(Formatting.None));
        }

        public void ShowErrors(IEnumerable<string> messages) => host.SetProfileEditorErrors(new JArray(messages).ToString(Formatting.None));

        public void Hide()
        {
            editing = null;
            host.HideProfileHome();
        }

        // {pro, monthlyPrice, yearlyPrice, lifetimePrice}: the store's localized prices (Play caches them, so they show offline too);
        // Prices stay blank until Google Play provides a localized price.
        string ProState() => new JObject
        {
            ["pro"] = iap != null && iap.IsPro,
            ["forever"] = iap != null && iap.IsOwned(InAppPurchaseController.RemoveAdsId),
            ["subscription"] = iap != null && iap.IsOwned(InAppPurchaseController.MonthlyId),
            ["activePlan"] = iap?.ActiveBasePlan ?? "",
            ["monthlyPrice"] = iap?.GetPlanPrice(InAppPurchaseController.MonthlyPlanId) ?? "",
            ["yearlyPrice"] = iap?.GetPlanPrice(InAppPurchaseController.YearlyPlanId) ?? "",
            ["monthlyTrial"] = iap != null && iap.HasPlanTrial(InAppPurchaseController.MonthlyPlanId),
            ["yearlyTrial"] = iap != null && iap.HasPlanTrial(InAppPurchaseController.YearlyPlanId),
            ["lifetimePrice"] = iap?.GetPrice(InAppPurchaseController.RemoveAdsId) ?? "",
        }.ToString(Formatting.None);

        void Status(bool busy, string title, string message) =>
            host.ShowProStatus(new JObject { ["busy"] = busy, ["title"] = title, ["message"] = message }.ToString(Formatting.None));

        const string Offline = "Could not reach Google Play. Check your internet connection and try again.";

        void OnPro(string action, string data)
        {
            switch (action)
            {
                case "open":
                    if (iap != null && !iap.IsReady) iap.Initialize(); // launch connect failed (offline): retry now, not only on resume
                    iap?.RefreshPlans();
                    host.ShowProPanel(ProState());
                    break;
                case "buy":
                    if (iap == null || !iap.IsReady) { iap?.Initialize(); Status(false, "Store unavailable", Offline); return; }
                    Status(true, "Processing purchase", "Waiting for Google Play\u2026");
                    Action<PurchaseResult, string> onPurchase = (result, message) =>
                    {
                        switch (result)
                        {
                            case PurchaseResult.Purchased:
                                host.HideProPanel();
                                Status(false, "Purchase complete", string.IsNullOrEmpty(message) ? "You are now Pro. Enjoy ad-free streaming." : message);
                                break;
                            case PurchaseResult.Pending:
                                pendingPayment = true; // answered by PurchaseGranted
                                Status(false, "Payment pending", "Pro unlocks as soon as the payment completes.");
                                break;
                            case PurchaseResult.Cancelled: Status(false, "Purchase cancelled", "You have not been charged."); break;
                            default: Status(false, "Purchase failed", string.IsNullOrEmpty(message) ? Offline : message); break;
                        }
                    };
                    if (data == "lifetime") iap.BuyForever(onPurchase);
                    else iap.BuyPlan(data, onPurchase);
                    break;
                case "restore":
                    if (iap == null || !iap.IsReady) { iap?.Initialize(); Status(false, "Store unavailable", Offline); return; }
                    Status(true, "Restoring purchases", "Checking Google Play\u2026");
                    iap.Restore((ok, error) =>
                    {
                        if (ok) restoring = true; // answered by PurchasesFetched
                        else Status(false, "Restore failed", string.IsNullOrEmpty(error) ? Offline : error);
                    });
                    break;
            }
        }

        void OnNativeEvent(string type, string data)
        {
            if (type.StartsWith("ui.pro.", StringComparison.Ordinal)) { OnPro(type.Substring("ui.pro.".Length), data); return; }
            if (!type.StartsWith("ui.profile.", StringComparison.Ordinal)) return;
            switch (type.Substring("ui.profile.".Length))
            {
                case "add": Add?.Invoke(); break;
                case "edit": WithProfile(data, p => Edit?.Invoke(p)); break;
                case "delete": WithProfile(data, p => Delete?.Invoke(p)); break;
                case "connect": WithProfile(data, p => Selected?.Invoke(p)); break;
                case "cancel":
                    if (editing == null) return;
                    editing = null;
                    Cancelled?.Invoke();
                    break;
                case "save":
                    if (editing == null) return;
                    JObject form;
                    try { form = JObject.Parse(data); }
                    catch (JsonException e) { ShowErrors(new[] { "Could not read the form: " + e.Message }); return; }
                    ProfileForm.Apply(editing, form);
                    Saved?.Invoke(editing);
                    break;
            }
        }

        void WithProfile(string id, Action<Profile> action)
        {
            foreach (var p in current)
                if (p.ProfileId == id) { action(p); return; }
        }

        static string Host(string url) => Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.Host : url;
    }
}
#endif
