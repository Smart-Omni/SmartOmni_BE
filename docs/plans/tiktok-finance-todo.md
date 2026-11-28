# TO-DO (Checkback Later): TikTok Shop Finance & Statement Integration

**Created:** 2026-11-28  
**Status:** TO-DO / Unverified against live TikTok Partner Center API  
**Context:** Implementing **"So sánh doanh thu"** (`ApiSalesInsights.jsx`) via Option 2 — adding mock TikTok Finance/Statement endpoints to `Smartomni-Tiktok-Simulator` so local development and end-to-end tests can exercise full side-by-side revenue and fee breakdowns (`Khoản trừ`) for both Shopee and TikTok Shop.

---

## 1. Why This TO-DO Exists

`Smartomni-Tiktok-Simulator` originally exposed **no** TikTok Finance APIs (`GET /finance/202309/statements`, `GET /finance/202501/orders/{order_id}/statement_transactions`, `GET /finance/202507/orders/unsettled`), so SmartOmni BE (`TiktokFinanceClient` / `TiktokFinanceEstimateService`) could only estimate gross totals from `COMPLETED` orders' `payment` blocks (`source = ESTIMATED_FROM_ORDERS`) and returned `null` for all fees, shipping settlements, and payouts.

To unblock local testing of the "So sánh doanh thu" dashboard with real simulated data, mock finance endpoints are being added to `Smartomni-Tiktok-Simulator`. **Because these simulator endpoints use synthetic fee rules and unverified field/scope details, every item below MUST be checked back against official TikTok Shop Partner Center documentation and a real TikTok Shop sandbox/production app before going live.**

---

## 2. Simulator Mock Assumptions to Check Back Later

| # | Area | Simulator Choice (Mock / Unverified) | What to Verify Against Real TikTok Shop Open API |
|---|---|---|---|
| 1 | **OAuth Scope** | Uses placeholder scope `sim.finance.read` (in `backend/src/oauth/scopes.ts` and `DEFAULT_APP_SCOPES`). | Verify the exact Partner Center permission/scope required for VN apps (documented as **Finance Information** / `seller.finance.info`, scope ID `430596` — confirm exact scope string in `granted_scopes` and whether VN domestic apps require special approval). |
| 2 | **Endpoint Versions & Paths** | Simulator exposes:<br>- `GET /finance/202309/statements`<br>- `GET /finance/202501/orders/{order_id}/statement_transactions`<br>- `GET /finance/202507/orders/unsettled` | Confirm which API versions are active and non-deprecated for VN region (`202309` vs `202501` vs `202507`), and whether `GET /finance/202501/statements/{statement_id}/statement_transactions` should be preferred over per-order polling. |
| 3 | **Sign Conventions (Positive vs Negative)** | Check whether fee/shipping/refund/adjustment fields in real TikTok responses are **negative** (deductions) and summed algebraically (`settlement = revenue + fee_and_tax + shipping_cost + adjustment`), vs positive magnitudes. | Inspect real sandbox payloads for `revenue_amount`, `fee_and_tax_amount`, `shipping_cost_amount`, `adjustment_amount`, `settlement_amount`, and nested `fee_tax_breakdown` / `shipping_cost_breakdown` / `sku_transactions[]`. Update SmartOmni BE normalization (`ChargeComponent.sellerImpact`) so sign flips never misreport credits vs debits. |
| 4 | **Fee Rates & Tax Rules (VN Domestic)** | Simulator computes synthetic VN domestic fees deterministically from order/return columns (e.g., commission fee, transaction fee, service/program fee, VAT/PIT withholding tax, seller-borne shipping cost, and return reverse-shipping fee on `RETURN_AND_REFUND`). | Real TikTok Shop VN fees vary by shop tier, category commission, Seller Shipping Program / Freeship Xtra, Voucher Xtra, payment method transaction fee, and VN e-commerce tax withholding (1%–1.5% VAT + PIT rules). Never hardcode simulator rates in SmartOmni BE; always ingest the statement numbers returned by TikTok. |
| 5 | **Settlement Timing & Status Lifecycle** | Simulator treats `COMPLETED` orders as `SETTLED` (daily statement keyed by UTC+7 `completedAt`/`updatedAt` date) and paid in-flight orders (`AWAITING_SHIPMENT`..`DELIVERED`) as `UNSETTLED`. | Real TikTok Shop settles orders only after the escrow/return window closes and statements are generated on payout cycles (`PAID` / `PROCESSING` / `FAILED`). Verify `statement_time` vs `order.create_time` vs `order.completed_at` for period attribution and reconciliation. |
| 6 | **Returns, Partial Refunds & Reverse Shipping** | Simulator derives `customer_refund_amount` and `return_shipping_fee_amount` from `AftersaleRequest` (`RETURN` in `SUCCESS`/`COMPLETE`). | Verify how real `statement_transactions` represent: (a) partial returns, (b) buyer-fault vs seller-fault reverse shipping fees, (c) refunded vs retained platform commission/transaction fees, and (d) adjustment transactions posted in a *later* statement cycle than the original order. |
| 7 | **Pagination, Rate Limits & Sync Watermarks** | Simulator supports `page_size` + `page_token` cursor paging without strict TikTok rate-limit quotas. | Implement incremental watermarked sync (`statement_time_ge` / `statement_time_lt`), idempotent upsert keyed by `(tenant_id, connection_id, order_id, transaction_id)`, and rate-limit backoff in `smartomni-service-integration` before calling live TikTok APIs in production. |

---

## 3. SmartOmni BE & FE Follow-Up Checklist (After Simulator Endpoints Are Ready)

1. **SmartOmni BE — Integration (`smartomni-service-integration`):**
   - [ ] Update `TiktokFinanceClient` to call `GET /finance/202501/orders/{order_id}/statement_transactions`, `GET /finance/202309/statements`, and `GET /finance/202507/orders/unsettled` instead of relying only on `order/price_detail` + `ESTIMATED_FROM_ORDERS`.
   - [ ] Update `ShopeeFinanceClient` to keep absent numeric fields `null` (not `0.0`) and expose `actualShippingFee`, `buyerPaidShippingFee`, `sellerShippingDiscount`, `escrowTax`, `finalShippingFee`.
   - [ ] Add `connectionId`, `createTime`, and order/line price breakdown fields to `WebhookOrderPayload`, `ShopeeOrderMapper`, and `TiktokOrderIngestionService`.
2. **SmartOmni BE — Persistence & Migrations (`smartomni-db-migrations`, `smartomni-service-order`):**
   - [ ] Add Flyway migration for `orders` price/time columns, `order_items` SKU/name/discount metadata (and make `OrderItem.skuId` nullable in JPA for unmapped SKUs), `returns_refunds` subtotal/shipping/reverse-shipping columns, and `order_finance_snapshots`.
   - [ ] Persist marketplace `createdAt`, `connectionId`, and finance transaction snapshots idempotently during order/return/statement sync (no fake hardcoded 5% fee in `SellerWalletLedger` for analytics).
3. **SmartOmni BE — Sales Insights API (`smartomni-service-ai` or `smartomni-service-order`):**
   - [ ] Implement read-only `GET /api/v1/ai/insights/sales?periodDays=7|30` (GMT+7 half-open periods, period-over-period delta, real VND daily/bucketed time series, Top 10 mapped SKUs + unmapped bucket, and unified 5-category `Khoản trừ` breakdown per channel).
   - [ ] Cross-check against `docs/business-insights-preview-contract.md` and `BusinessInsightsPreviewResponse.java`.
4. **SmartOmni FE (`SmartOmni_FE`):**
   - [ ] Wire `ApiSalesInsights.jsx` to the new BE endpoint (with loading/error/disconnected states and real VND line chart).
   - [ ] Re-verify with live TikTok Partner Center sandbox once credentials/scope `seller.finance.info` are approved.
