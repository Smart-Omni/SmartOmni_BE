# Business Insights mock response contract

Status: planning DTO only. This contract is not connected to a route, adapter, database entity, or live calculation. Its fixture values are synthetic and must never be rendered as store data.

## Proposed response

`com.smartomni.ai.dto.insights.BusinessInsightsPreviewResponse` is a transport shape for the two FE Insights pages. It groups results per authorized marketplace connection, keeps channel-specific accounting meanings separate, and reports availability beside each metric. It is not a new source-of-truth ledger.

The DTO keeps monetary amounts nullable and attaches `DataState`, source endpoint/field, definitions, and missing inputs. Missing values are not zero. The response carries the half-open reporting interval, timezone, time basis, order-status policy, and per-connection sync coverage. Every query must be scoped to the authenticated tenant and its authorized connections.

## Capability check against current sources

| Requirement | Shopee | TikTok Shop | SmartOmni BE gap |
|---|---|---|---|
| Order value and count | Order list/detail plus item/model prices and quantities | Order search/detail provides payment values and line items (one unit per line in the local simulator) | Stored `Order.totalAmount` includes buyer total semantics; merchandise subtotal/discount components are not consistently persisted for analytics |
| Top products | Order item/model IDs, names, SKUs, quantities, and prices | Order lines include product ID/name, SKU ID/name, seller SKU, quantity, sale/original prices | Ingestion maps lines to internal `skuId`; `OrderItem` stores quantity and unit price, but unmapped lines must be reported separately and cross-channel rollup requires verified SKU mapping |
| Escrow and fee detail | Current adapter calls `GET /api/v2/payment/get_escrow_detail`, maps `escrow_amount`, buyer total, commission/service/transaction fees, actual shipping, reverse shipping, seller return refund, and shipping rebate | Current adapter calls order `price_detail`; it returns order/line price components. Current `/finance/settlements` is an estimate from COMPLETED order payment blocks and does not include final payout or fees | Shopee client currently converts absent numeric fields to zero; change to nullable/stateful amounts before using for reliable analytics |
| Authoritative TikTok finance | N/A | Official Finance Information API provides `GET /finance/202501/orders/{order_id}/statement_transactions` for order/SKU revenue, fees/tax, shipping, and settlement; `GET /finance/202507/orders/unsettled` returns estimated unsettled transaction amounts | Neither transaction endpoint is implemented in the current SmartOmni TikTok adapter. Add finance scope `seller.finance.info`, paginated sync, transaction status/source, idempotent storage and reconciliation before calling TikTok amounts actual |
| Return/refund detail | Return summary/detail includes refund amount, reverse shipping, seller compensation and return status | Return search returns refund amount/status/line items; the local SmartOmni mapper preserves `refund_shipping_fee` | Refund amount alone is not proof of seller debit. Reconcile final financial transactions to decide seller-paid shipping and retained fees |

### Specific semantics and cautions

- Shopee `reverse_shipping_fee` and `seller_return_refund` can support order-level explanation from the escrow response. Use the return state and order/return IDs to avoid double counting. `escrow_amount` is net settlement for that order, not merchandise revenue or profit.
- TikTok `price_detail` is not a payout statement. Order-payment sums currently returned by SmartOmni are marked estimates. Official transaction APIs make a fee/payout view feasible, but their Finance scope, region eligibility, transaction fields, and paging still need integration work.
- TikTok return `refund_shipping_fee` describes the shipping component refunded to the buyer; do not label it “seller paid return shipping” without matching seller-side statement transaction evidence.
- A returned order's seller impact is not automatically its entire order value. Show verified debits and credits separately. To call the outcome lost contribution/profit, SmartOmni also needs effective COGS, refund finality, and the returned item's disposition/restock value. Without those inputs show `PARTIAL` and list what is missing.
- For product rankings, aggregate normalized order lines by verified internal SKU, period, and the selected status/time policy. Expose an `UNMAPPED` or `AMBIGUOUS` bucket; never merge on product name alone. Exclude cancelled items and treat returned units under a declared refund/return policy.
- Define successful-sale units as eligible line quantities from declared successful order states, excluding cancelled quantities. Define product merchandise value as the sum of those line values after seller-funded discounts and before refund deductions; show confirmed refunds separately. Do not use a returned order's original price as its refund amount.
- Define buyer refunds as the sum of final, confirmed refund amounts for the selected period, each refund event counted once. A requested or estimated refund stays `PARTIAL` or `ESTIMATED` until matched to a final platform financial event.
- Do not add `refund_amount`, order-level refund total, return-line refund, and financial statement refund transactions together. Select one canonical ledger source and retain source transaction IDs for deduplication.

## Suggested implementation sequence

1. Confirm tenant-connection authorization and explicit period/status/time-basis rules for reporting.
2. Change Shopee escrow parsing to preserve null/unknown and record source timestamps; add an analytics-facing component contract.
3. Implement TikTok `statement_transactions` and unsettled transaction clients under the Finance Information permission; preserve all unknown additive fields and mark unsettled figures estimated.
4. Build a normalized finance transaction snapshot keyed by tenant, connection, platform, external order/transaction ID, and component ID. Store the platform payload version/source watermark for replay and reconciliation. Do not alter historical marketplace order prices to stand in for settlement.
5. Aggregate product lines only after internal SKU mapping; publish per-channel coverage and excluded/unmapped counts.
6. Return metrics, top products, seller debits/credits, return cost summaries, and data states from a new read-only insights API. Keep deterministic calculations in BE; provide computed outputs to the third-party LLM only for explanation.

## Field-to-source map

| Contract field | Candidate source |
|---|---|
| `merchandiseValue`, `orderCount`, `averageOrderValue` | Normalized order snapshots, distinct external order keys, explicit status/time policy |
| `topProducts[].units`, `topProducts[].merchandiseValue` | Normalized order lines after verified internal SKU mapping; order-line discounts allocated by declared policy |
| Shopee `platformFees` | `order_income.commission_fee + service_fee + seller_transaction_fee` (plus future fee fields as separate components) |
| Shopee `sellerShippingCost` | `actual_shipping_fee - buyer_paid_shipping_fee - shopee_shipping_rebate - shipping_fee_discount_from_3pl` only after validating signed semantics from actual marketplace payloads; preserve raw components |
| Shopee return costs | `order_income.reverse_shipping_fee`, `seller_return_refund`, and matching Return API status/ID |
| TikTok `sellerSettlement`, `platformFees`, `sellerShippingCost` | Official order statement transaction response fields and component breakdowns; unsettled response must keep `estimated=true` |
| `returnCosts.buyerRefunds` | Final refund transaction or one canonical verified return ledger mapping, deduped by transaction/return ID |
| `returnCosts.profitImpactComputable` | Requires final seller debit/credit plus COGS and returned stock disposition; otherwise `false` |

The Shopee shipping formula above is a proposed normalized metric, not an assertion that every seller's settlement components net identically. Keep each source component intact and validate country/shop-specific payout semantics before publishing a net amount.

## Source references inspected

- SmartOmni FE contract notes: `SmartOmni_FE/docs/TIKTOK_API_CONTRACT.md`.
- SmartOmni BE: `TiktokFinanceClient`, `TiktokFinanceEstimateService`, `ShopeeFinanceClient`, `ShopeeReturnsClient`, `Order`, `OrderItem`, and order ingestion mappers.
- Local TikTok simulator: `Tiktok Sim/Smartomni-Tiktok-Simulator/backend/prisma/schema.prisma`, `backend/src/orders/order.mapper.ts`, `backend/src/tiktok/orders.controller.ts`, and after-sale models/mappers.
- Local Shopee simulator: `shopee-docs/shopee-markdown/shopee-simulator/prisma/schema.prisma` and its payment/returns routers.
- TikTok Shop Open API: [Get Transactions by Order](https://partner.tiktokshop.com/docv2/page/get-transactions-by-order), [Get Unsettled Transactions](https://partner.tiktokshop.com/docv2/page/get-unsettled-transactions), [Get Order Price Details](https://partner.tiktokshop.com/docv2/page/get-order-price-details-202407), and [Search Returns](https://partner.tiktokshop.com/docv2/page/search-returns-202602).
