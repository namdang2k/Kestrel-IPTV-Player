# Pro plans

Plan changes run directly through Google Play Billing. There is no billing backend, Cloud Function, or automatic cancellation API.

While a subscription is active, Monthly and Yearly remain available for plan changes. The known current plan is disabled. Pro Forever stays visible at 35% opacity, cannot be selected, and is also blocked in the purchase controller. The hint reads: `Cancel your subscription and wait for it to expire to buy Pro Forever.` A Google Play subscription-management link lets the user cancel renewal.

Cancellation does not end paid access immediately; Forever remains locked until Play no longer returns the active subscription. A canceled but unexpired subscription still grants Pro.

- Target Yearly: `CHARGE_FULL_PRICE`, preserving the user's requested immediate upgrade behavior.
- Target Monthly: `WITHOUT_PRORATION`, with the new price charged at the next renewal.
- Existing subscribers use paid base-plan offers, not a new free trial.

The native bridge remembers the selected base plan against the purchase token after a successful purchase. Google Billing's client Purchase object does not expose the base-plan ID for an uncached restored purchase. In that case, show both `Change to Monthly` and `Change to Yearly` without inventing a current plan; Google Play remains authoritative for the checkout. Current-token validation protects against stale plan changes.

The Pro sheet fills the available screen width and height, with three compact, vertically stacked cards and scrolling when the content exceeds the screen height.

| Card | Google Play product | Base plan |
| --- | --- | --- |
| Monthly | `premium_monthly` | `monthly` |
| Yearly | `premium_monthly` | `yearly` |
| Pro Forever | `remove_ads` (non-consumable) | none |

Both subscription cards use the note `auto renew. cancel anytime`.
Prices come from Google Play, with a placeholder until loaded. The subscription price is the final recurring pricing phase, not the zero-price trial phase.

`SubscriptionPlans.java` queries eligible offers and explicitly selects a base plan and its offer token. This is necessary because the installed Unity IAP 5.4.3 billing implementation selects the first subscription offer. The native bridge only queries and launches subscriptions; Unity IAP still fetches, grants, acknowledges and restores purchases. Pro Forever continues through the existing Unity IAP purchase flow. Both native Gradle and Unity's Gradle template use Billing 9.0.0, matching the installed IAP resolver.

A `3-Day Free Trial` label and `Start Free Trial` button appear only when Play returns an eligible offer whose initial phase is free for `P3D` with one cycle. Otherwise the regular base plan is selected and the button reads `Subscribe`. A one-month offer is not substituted for a three-day offer. Creating base plans alone does not create free-trial offers; configure the three-day offer under each base plan in Play Console.

On Android, active subscription ownership comes from Play's purchases query. Do not infer yearly expiration from Unity IAP's first-base-plan `SubscriptionInfo` metadata.

## Verification

- Android player C# compilation passed using Unity's Roslyn compiler and the project's Android response file.
- Native JVM suite: 57 tests passed, including paid-offer selection for both plan changes and replacement-mode checks.
- Release AAR rebuilt and copied to `Assets/Plugins/Android/StbHost.aar`.
- Real Play checkout and visual device inspection remain to be performed with a license-test account: check both base plans, trial eligibility, cancellation, pending payment, restore, expiry, and the lifetime product. No real purchase was made during implementation.
