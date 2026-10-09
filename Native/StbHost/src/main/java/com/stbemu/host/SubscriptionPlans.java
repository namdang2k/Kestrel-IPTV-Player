package com.stbemu.host;

import android.app.Activity;
import com.android.billingclient.api.*;
import org.json.JSONObject;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/** Selects Play base plans; Unity IAP remains responsible for ownership and acknowledgement. */
public final class SubscriptionPlans {
    public interface Listener {
        void onCatalog(String json);
        void onResult(String result, String message);
    }

    private final Activity activity;
    private final Listener listener;
    private final String productId;
    private final BillingClient client;
    private boolean buying, launched; // launched: Play checkout is open, only its listener may finish the purchase
    private String requestedPlan = "", activePlan = "";
    private final android.content.SharedPreferences planCache;
    private boolean connecting;
    private final List<Runnable> connectedActions = new ArrayList<>();

    public SubscriptionPlans(Activity activity, String productId, Listener listener) {
        this.activity = activity;
        this.productId = productId;
        this.listener = listener;
        planCache = activity.getSharedPreferences("stb_plan_cache", Activity.MODE_PRIVATE);
        client = BillingClient.newBuilder(activity)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .setListener((result, purchases) -> activity.runOnUiThread(() -> {
                    if (!buying) return;
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        finish(result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED ? "cancelled" : "failed", result.getDebugMessage());
                        return;
                    }
                    if (purchases != null) for (Purchase purchase : purchases) {
                        if (!purchase.getProducts().contains(productId)) continue;
                        // A pending payment keeps its token once paid, so cache the plan now too.
                        if (!requestedPlan.isEmpty()) planCache.edit().putString(purchase.getPurchaseToken(), requestedPlan).apply();
                        if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) finish("pending", "Payment pending");
                        else if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) finish("purchased", purchase.getPurchaseToken());
                        else finish("failed", "Unknown purchase state");
                        return;
                    }
                    finish("failed", "No matching subscription returned by Google Play");
                })).build();
    }

    public void refresh() {
        activity.runOnUiThread(() -> connect(() -> client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS).build(), (result, purchases) -> activity.runOnUiThread(() -> {
            activePlan = "";
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                for (Purchase purchase : purchases) if (purchase.getProducts().contains(productId)
                        && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED)
                    activePlan = planCache.getString(purchase.getPurchaseToken(), "");
            }
            query(null, "");
        }))));
    }

    public void buy(String plan, String oldPurchaseToken) {
        activity.runOnUiThread(() -> {
            if (buying) return;
            buying = true;
            requestedPlan = plan;
            if (!"monthly".equals(plan) && !"yearly".equals(plan)) { finish("failed", "Unknown base plan"); return; }
            connect(() -> client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS).build(), (result, purchases) -> activity.runOnUiThread(() -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) { finish("failed", result.getDebugMessage()); return; }
                String currentToken = "";
                for (Purchase purchase : purchases) if (purchase.getProducts().contains(productId)) {
                    if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) { finish("failed", "A subscription payment is pending"); return; }
                    if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) currentToken = purchase.getPurchaseToken();
                }
                if (!currentToken.equals(oldPurchaseToken)) {
                    finish("failed", "Subscription changed. Reopen the Pro panel and try again."); return;
                }
                if (!currentToken.isEmpty() && plan.equals(planCache.getString(currentToken, ""))) {
                    finish("failed", "This plan is already active."); return;
                }
                query(plan, oldPurchaseToken);
            })));
        });
    }

    public void close() { activity.runOnUiThread(client::endConnection); }

    private void connect(Runnable action) {
        if (client.isReady()) { action.run(); return; }
        connectedActions.add(action);
        if (connecting) return;
        connecting = true;
        client.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult result) {
                activity.runOnUiThread(() -> {
                    connecting = false;
                    List<Runnable> actions = new ArrayList<>(connectedActions);
                    connectedActions.clear();
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        for (Runnable pending : actions) pending.run();
                    } else fail(result.getDebugMessage());
                });
            }
            @Override public void onBillingServiceDisconnected() {
                activity.runOnUiThread(() -> {
                    connecting = false;
                    connectedActions.clear();
                    fail("Google Play disconnected. Please try again.");
                });
            }
        });
    }

    private void query(String plan, String oldPurchaseToken) {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId).setProductType(BillingClient.ProductType.SUBS).build();
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product)).build(), (result, response) -> activity.runOnUiThread(() -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) { fail(result.getDebugMessage()); return; }
            ProductDetails details = null;
            for (ProductDetails item : response.getProductDetailsList()) if (productId.equals(item.getProductId())) details = item;
            if (details == null) { fail("Subscription unavailable on Google Play"); return; }
            JSONObject catalog = new JSONObject();
            try {
                catalog.put("activePlan", activePlan);
                for (String id : new String[]{"monthly", "yearly"}) {
                    ProductDetails.SubscriptionOfferDetails offer = select(details.getSubscriptionOfferDetails(), id);
                    if (offer == null) continue;
                    List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
                    catalog.put(id + "Price", phases.get(phases.size() - 1).getFormattedPrice());
                    catalog.put(id + "Trial", hasTrial(offer));
                }
            } catch (org.json.JSONException error) { fail(error.getMessage()); return; }
            listener.onCatalog(catalog.toString());
            if (plan == null) return;
            ProductDetails.SubscriptionOfferDetails offer = select(details.getSubscriptionOfferDetails(), plan, oldPurchaseToken.isEmpty());
            if (offer == null) { finish("failed", "Base plan unavailable: " + plan); return; }
            BillingFlowParams.ProductDetailsParams params = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details).setOfferToken(offer.getOfferToken()).build();
            BillingFlowParams.Builder flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(params));
            if (!oldPurchaseToken.isEmpty()) flow.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(oldPurchaseToken)
                            .setSubscriptionReplacementMode(replacementMode(plan)).build());
            BillingResult launch = client.launchBillingFlow(activity, flow.build());
            if (launch.getResponseCode() != BillingClient.BillingResponseCode.OK) finish("failed", launch.getDebugMessage());
            else launched = true;
        }));
    }

    static int replacementMode(String targetPlan) {
        return "yearly".equals(targetPlan)
                ? BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE
                : BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION;
    }

    // Only advertise/select the configured 3-day trial; otherwise select the regular base plan.
    static ProductDetails.SubscriptionOfferDetails select(List<ProductDetails.SubscriptionOfferDetails> offers, String plan) {
        return select(offers, plan, true);
    }

    static ProductDetails.SubscriptionOfferDetails select(List<ProductDetails.SubscriptionOfferDetails> offers, String plan, boolean allowTrial) {
        ProductDetails.SubscriptionOfferDetails base = null;
        if (offers != null) for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (!plan.equals(offer.getBasePlanId()) || offer.getPricingPhases().getPricingPhaseList().isEmpty()) continue;
            if (allowTrial && hasTrial(offer)) return offer;
            if (offer.getOfferId() == null) base = offer;
        }
        return base;
    }

    static boolean hasTrial(ProductDetails.SubscriptionOfferDetails offer) {
        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
        return !phases.isEmpty() && phases.get(0).getPriceAmountMicros() == 0
                && "P3D".equals(phases.get(0).getBillingPeriod()) && phases.get(0).getBillingCycleCount() == 1;
    }

    private void fail(String message) {
        listener.onCatalog("{}");
        if (buying && !launched) finish("failed", message); // a refresh/disconnect error must not hide the checkout's result
    }

    private void finish(String result, String message) {
        buying = launched = false;
        listener.onResult(result, message);
    }
}
