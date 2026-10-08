package com.stbemu.host;

import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.BillingFlowParams;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Constructor;
import java.util.List;
import static org.junit.Assert.*;

public class SubscriptionPlansTest {
    private JSONObject offer(String plan, String offerId, String trialPeriod) throws Exception {
        JSONArray phases = new JSONArray();
        if (trialPeriod != null) phases.put(new JSONObject()
                .put("priceAmountMicros", 0).put("formattedPrice", "$0.00")
                .put("priceCurrencyCode", "USD").put("billingPeriod", trialPeriod)
                .put("billingCycleCount", 1).put("recurrenceMode", 2));
        phases.put(new JSONObject().put("priceAmountMicros", 4990000).put("formattedPrice", "$4.99")
                .put("priceCurrencyCode", "USD").put("billingPeriod", "yearly".equals(plan) ? "P1Y" : "P1M")
                .put("billingCycleCount", 0).put("recurrenceMode", 1));
        JSONObject result = new JSONObject().put("basePlanId", plan).put("offerIdToken", plan + ":" + offerId)
                .put("pricingPhases", phases).put("offerTags", new JSONArray());
        if (offerId != null) result.put("offerId", offerId);
        return result;
    }

    private List<ProductDetails.SubscriptionOfferDetails> offers(JSONObject... offers) throws Exception {
        Constructor<ProductDetails.SubscriptionOfferDetails> ctor = ProductDetails.SubscriptionOfferDetails.class
                .getDeclaredConstructor(JSONObject.class);
        ctor.setAccessible(true);
        java.util.ArrayList<ProductDetails.SubscriptionOfferDetails> result = new java.util.ArrayList<>();
        for (JSONObject offer : offers) result.add(ctor.newInstance(offer));
        return result;
    }

    @Test public void yearlySelectsItsOwnTrialEvenWhenMonthlyComesFirst() throws Exception {
        ProductDetails.SubscriptionOfferDetails selected = SubscriptionPlans.select(offers(
                offer("monthly", "trial", "P3D"), offer("yearly", null, null), offer("yearly", "trial", "P3D")), "yearly");
        assertEquals("yearly:trial", selected.getOfferToken());
        assertTrue(SubscriptionPlans.hasTrial(selected));
    }

    @Test public void ineligibleTrialFallsBackToSameBasePlan() throws Exception {
        ProductDetails.SubscriptionOfferDetails selected = SubscriptionPlans.select(offers(
                offer("monthly", "trial", "P3D"), offer("yearly", null, null)), "yearly");
        assertEquals("yearly:null", selected.getOfferToken());
        assertFalse(SubscriptionPlans.hasTrial(selected));
    }

    @Test public void monthLongTrialIsNotAdvertisedAsThreeDays() throws Exception {
        ProductDetails.SubscriptionOfferDetails selected = SubscriptionPlans.select(offers(
                offer("yearly", "old-trial", "P1M"), offer("yearly", null, null)), "yearly");
        assertNull(selected.getOfferId());
        assertFalse(SubscriptionPlans.hasTrial(selected));
    }

    @Test public void missingPlanDoesNotPurchaseAnotherPlan() throws Exception {
        assertNull(SubscriptionPlans.select(offers(offer("monthly", "trial", "P3D")), "yearly"));
        assertNull(SubscriptionPlans.select(null, "yearly"));
    }

    @Test public void upgradeUsesPaidYearlyBasePlanInsteadOfTrial() throws Exception {
        ProductDetails.SubscriptionOfferDetails selected = SubscriptionPlans.select(offers(
                offer("yearly", "trial", "P3D"), offer("yearly", null, null)), "yearly", false);
        assertEquals("yearly:null", selected.getOfferToken());
        assertFalse(SubscriptionPlans.hasTrial(selected));
    }

    @Test public void upgradeFailsIfOnlyTrialOfferExists() throws Exception {
        assertNull(SubscriptionPlans.select(offers(offer("yearly", "trial", "P3D")), "yearly", false));
    }

    @Test public void monthlyChangeDoesNotApplyANewTrial() throws Exception {
        ProductDetails.SubscriptionOfferDetails selected = SubscriptionPlans.select(offers(
                offer("monthly", "trial", "P3D"), offer("monthly", null, null)), "monthly", false);
        assertEquals("monthly:null", selected.getOfferToken());
    }

    @Test public void yearlyChargesNowAndMonthlyChargesAtRenewal() {
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE,
                SubscriptionPlans.replacementMode("yearly"));
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION,
                SubscriptionPlans.replacementMode("monthly"));
    }
}
