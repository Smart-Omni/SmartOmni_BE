# Shopee Simulator to SmartOmni: Master Integration Roadmap & Frontend Handoff Guide

This document outlines the multi-phase engineering roadmap to achieve complete omnichannel integration between the **Shopee Open API v2 Simulator** (`https://shopee-simulator.onrender.com`) and the **SmartOmni Platform**.

---

## 1. Operating Rules & Frontend Team Boundary

> [!IMPORTANT]
> **Strict Frontend Scope Boundary Rule:**
> - **Backend / Agent Scope**: Full Spring Boot backend implementation, HTTP client adapters, PostgreSQL Flyway migrations, HMAC-SHA256 cryptography, webhook ingestion, RabbitMQ messaging, and unit tests covering all edge cases.
> - **Frontend Scope Boundary**: **DO NOT code new UIs on `SmartOmni_FE`** (this is strictly the FE team's scope). The agent is only permitted to add minimal connection trigger buttons or callback redirects when strictly necessary for end-to-end verification.
> - **Phase Inception UI Availability Gate**:
>   1. At the beginning of each phase, audit `SmartOmni_FE` to check if UI components/screens exist to support the planned backend APIs.
>   2. If a UI is missing or only exists as an offline mock/fixture (`staffFixtures.js`), document the exact gap.
>   3. Provide a complete **Backend API Contract for the Frontend Team** (HTTP method, endpoint URL, query/body JSON schema, standard response envelope, error codes, and recommended UX flow) so the user can directly hand off the specification to the FE team for design and implementation.

---

## 2. Master Architecture Flow

```mermaid
flowchart TD
    subgraph ShopeeSim["Shopee Simulator (Live on Render)"]
        AUTH_EP["OAuth 2.0 Kernel<br/>GET /auth<br/>POST /token/get"]
        CAT_EP["Product Catalog APIs<br/>get_item_list<br/>get_model_list<br/>update_stock/price"]
        WH_EP["Push Webhook Engine<br/>Codes 1, 2, 3, 4, 15, 29<br/>HMAC Signature"]
        ORD_EP["Order & Shipping APIs<br/>get_order_detail<br/>ship_order<br/>download_shipping_document"]
        FIN_EP["Escrow & Returns APIs<br/>get_escrow_detail<br/>confirm/dispute"]
    end

    subgraph SmartOmni["SmartOmni Platform"]
        FE["SmartOmni Frontend (Vite + React 19)<br/>Merchant Portal & Channels"]
        NGINX["Nginx Gateway (:8080)<br/>Reverse Proxy & Route Guard"]
        INT["Integration Service (:8086)<br/>Shopee Client, Signer, Adapters"]
        CAT["Catalog Service (:8083)<br/>Internal Products & SKU Links"]
        INV["Inventory Service (:8084)<br/>Stock Adjustments & Outbox"]
        ORD["Order Service (:8085)<br/>Idempotent Order Pipeline"]
        MQ[("RabbitMQ (:5672)<br/>Event Queues")]
        PG[("PostgreSQL (:5432)<br/>RLS Multi-Tenant Storage")]
    end

    FE -->|1. OAuth Consent| AUTH_EP
    FE -->|2. Forward Code/State| NGINX
    NGINX -->|/api/marketplace-connections/shopee/*| INT
    INT -->|3. Signed Token Exchange| AUTH_EP
    INT -->|4. Store Encrypted Tokens| PG

    INT -->|Phase 2: Inbound Catalog Sync| CAT_EP
    INT -->|Phase 2: Map SKUs & Prices| CAT
    CAT -->|Phase 2: Link Models| PG

    WH_EP -->|Phase 3: Real-Time Webhooks| NGINX
    NGINX -->|/webhook/{tenantId}/shopee| INT
    INT -->|Phase 3: Verify Sign & Publish| MQ
    MQ -->|Phase 4: Ingest Order| ORD
    ORD -->|Phase 4: Deduct Stock| INV
    INV -->|Phase 4: Outbox Event| MQ
    MQ -->|Phase 4: Push Stock Update| INT
    INT -->|Phase 4: update_stock| CAT_EP

    FE -->|Phase 5: Print AWB & Ship| INT
    INT -->|Phase 5: Ship & Thermal PDF| ORD_EP

    INT -->|Phase 6: Reconcile Escrow| FIN_EP
```

---

## 3. Phase Breakdown, UI Audits & Frontend API Handoff Specifications

---

### Phase 1: OAuth 2.0 Auth & Connection Kernel ✅ *(COMPLETED)*

#### Status & Implemented Backend
- **Branch**: `feature/INT-02-shopee-authorization` on `SmartOmmi_BE`
- **Commits**: `658fd9e`, `eed6bfb`, `e97c040`, `5baa96c`, `42a8e72`
- **Features**: HMAC-SHA256 signature generator (`ShopeeApiSigner`), AES-256-GCM encrypted tokens, single-use anti-CSRF state machine, DB migration `V12__shopee_connection.sql`, 4 unit test suites passing offline.

#### UI Availability Audit in `SmartOmni_FE`
- **Status**: **Available (Integrated)**
- **Audit Findings**:
  - Added "Connect via Shopee Simulator" action in `ShopWorkspace.jsx` (`/merchant/shops`).
  - Added [`ShopeeCallbackPage.jsx`](file:///Users/nguyetminh/Dev/SmartOmni/SmartOmni_FE/src/features/portal/ShopeeCallbackPage.jsx) at `/merchant/channels/shopee/callback` and `/auth/callback`.

#### Backend API Contract for Frontend Team
```http
POST /api/marketplace-connections/shopee/authorization
Authorization: Bearer <TenantAdminJWT>
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "message": "Success",
  "data": {
    "authorizationUrl": "https://shopee-simulator.onrender.com/api/v2/shop/auth_partner?partner_id=1001141&redirect_uri=http://localhost:5174/merchant/channels/shopee/callback&state=...",
    "expiresAt": "2026-10-27T10:10:00Z"
  }
}
```

```http
POST /api/marketplace-connections/shopee/callback
Authorization: Bearer <TenantAdminJWT>
Content-Type: application/json

{
  "code": "a1b2c3d4e5f67890",
  "shop_id": "727720655",
  "state": "random-state-token"
}
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "message": "Shopee connection updated",
  "data": {
    "id": 88,
    "status": "CONNECTED",
    "needsReconnect": false,
    "shopId": "727720655",
    "shopName": "SmartOmni Official Flagship",
    "shopRegion": "VN",
    "connectedAt": "2026-10-27T10:00:00Z",
    "accessTokenExpiresAt": "2026-10-27T14:00:00Z",
    "refreshTokenExpiresAt": "2026-11-26T10:00:00Z",
    "lastErrorCode": null,
    "lastErrorAt": null
  }
}
```

---

### Phase 2: Product Catalog & 2-Tier Variation Sync (Inbound & Outbound) ✅ *(COMPLETED)*

#### Status & Implemented Backend
- **Branch**: `feature/INT-02-shopee-authorization` on `SmartOmmi_BE`
- **Commits**: `e9e049e`, `dd66b0f`, `8deea21`, `167ae52`
- **Features**:
  - `ShopeeTokenService` & `ShopeeShopApiExecutor` for auto-refreshing expired access tokens and signing shop-level requests.
  - `ShopeeCatalogClient` supporting `get_item_list` (paginated), `get_item_base_info`, `get_model_list` (2-tier variant hierarchy), `get_warehouse_detail`, `update_stock`, and `update_price`.
  - `ShopeeProductMapper` for bidirectional translation between Shopee models and canonical `MarketplaceProductDto`.
  - `ShopeeProductImportService` with multi-page ingestion, duplicate run lock guard, and plan SKU limit enforcement.
  - `ShopeePriceSyncService` with price anomaly detection ($>50\%$ swing guard under FR-092).
  - `ShopeeCatalogController` exposing REST APIs under `/api/marketplace-connections/shopee/*`.
  - Full mock unit test suite in `ShopeeCatalogClientTest`, `ShopeeProductImportServiceTest`, and `ShopeePriceSyncServiceTest`.

#### UI Availability Audit in `SmartOmni_FE`
> [!WARNING]
> **FE UI Gap Identified:**
> - `StaffProducts.jsx` currently displays a mock form using hardcoded fixtures (`importCandidates` in `staffFixtures.js`).
> - Clicking "Queue demo import" creates a fake client-side job (`advanceProductImport`).
> - **Missing in FE**:
>   1. Real API integration calling the import trigger.
>   2. Active progress indicator showing real-time `status` (`running`, `completed`, `completed_with_errors`).
>   3. Import run history table consuming `GET /api/marketplace-connections/shopee/products/import-runs`.
>   4. Display of import metrics (`products_seen`, `products_created`, `skus_linked`, `skus_unmatched`, `items` error reasons).
>   5. Warehouse sync status table.
>   6. Price anomaly review screen for items flagged under FR-092 ($>50\%$ price shift).

#### Backend API Contract for Frontend Team

##### 1. Trigger Product Import
```http
POST /api/marketplace-connections/shopee/products/import
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "message": "Import finished",
  "data": {
    "id": 105,
    "status": "completed",
    "startedAt": "2026-10-27T10:05:00Z",
    "finishedAt": "2026-10-27T10:05:12Z",
    "pages": 2,
    "productsSeen": 42,
    "productsCreated": 10,
    "productsUpdated": 32,
    "productsSkipped": 0,
    "productsFailed": 0,
    "skusCreated": 65,
    "skusLinked": 65,
    "skusConflicted": 0,
    "skusUnmatched": 0,
    "skuLimitReached": false,
    "errorMessage": null,
    "items": []
  }
}
```
- **Error Responses**:
  - `409 Conflict` (`IMPORT_ALREADY_RUNNING`): An import job is already running for this connection.
  - `400 Bad Request` (`SHOPEE_NOT_CONNECTED`): Tenant does not have an active Shopee connection.

##### 2. List Recent Import Runs
```http
GET /api/marketplace-connections/shopee/products/import-runs?limit=10
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response `200 OK`**: Returns array of `ImportReport` objects ordered by `startedAt DESC`.

##### 3. Sync Warehouses
```http
POST /api/marketplace-connections/shopee/warehouses/sync
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "message": "Warehouses synced",
  "data": { "warehouses": 3 }
}
```

##### 4. Trigger Price Sync & Anomaly Check
```http
POST /api/marketplace-connections/shopee/prices/sync
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "message": "Prices synced",
  "data": {
    "compared": 65,
    "applied": 63,
    "anomalies": 2
  }
}
```

#### Detailed Backend Steps for Phase 2
1. **Shopee Catalog Client (`ShopeeCatalogClient.java`)**:
   - `getItemList`: Calls `GET /api/v2/product/get_item_list` (paginated, pageSize $\le 50$).
   - `getItemBaseInfo`: Calls `GET /api/v2/product/get_item_base_info` (batch up to 50 items).
   - `getModelList`: Calls `GET /api/v2/product/get_model_list` to fetch 2-tier variants (Color + Size matrix).
   - `updateStock`: Calls `POST /api/v2/product/update_stock`.
   - `updatePrice`: Calls `POST /api/v2/product/update_price`.
2. **Import Service (`ShopeeProductImportService.java`)**:
   - Handles multi-page traversal.
   - Maps Shopee items & models into `product_marketplace_links` and `product_skus`.
   - Prevents concurrent duplicate imports via DB index `uq_marketplace_import_runs_running`.
3. **Price Anomaly Policy (`ShopeePriceSyncService.java`)**:
   - Price drop $> 50\%$ recorded as `applied = false` in `price_sync_history`.
4. **Unit Tests & Edge Cases**:
   - Pagination handling $>50$ items.
   - Items with variants vs single-SKU items.
   - Unmatched SKUs (missing seller SKU code).
   - HTTP 429 rate limit backoff.

---

### Phase 3: Push Webhooks & Real-Time Event Ingestion ✅ *(COMPLETED)*

#### Status & Implemented Backend
- **Branch**: `feature/INT-02-shopee-authorization` on `SmartOmmi_BE`
- **Commits**: `b0e8fbb`, `f3259c4`
- **Features**:
  - `ShopeeWebhookEvent` DTO parsing incoming Push JSON with helper accessors for `ordersn`, `status`, `return_sn`, `tracking_no`.
  - `ShopeeWebhookValidator` verifying HMAC-SHA256 authorization signatures against `${url}|${rawBody}` and `${path}|${rawBody}`, plus $\le 300\text{s}$ timestamp replay defense.
  - `ShopeeWebhookDispatcher` managing push code routing:
    - Code 1: Re-authorizes active connection state.
    - Code 2 / 16: Automatically marks `MarketplaceConnection.status = REVOKED` and sets `lastErrorCode = "SHOP_DEAUTHORIZED"`.
    - Code 3: Dispatches normalized order event to RabbitMQ `smartomni.order.exchange` (`order.incoming`).
    - Code 4: Dispatches tracking event to RabbitMQ (`order.tracking`).
    - Code 15: Handles item promotion update logs.
    - Code 29: Dispatches return/refund event to RabbitMQ (`return.incoming`).
  - `WebhookController` updated to support `Authorization` & `X-Signature` headers, tenant isolation, and instant HTTP 200 OK standard Shopee response envelope.
  - Unit test suites in `ShopeeWebhookValidatorTest`, `ShopeeWebhookDispatcherTest`, and `WebhookControllerTest`.

#### UI Availability Audit in `SmartOmni_FE`
> [!NOTE]
> **FE UI Status**: **Infra / Background Feature**
> - Push webhooks are backend-to-backend calls from Shopee Simulator to SmartOmni Nginx.
> - **FE Impact**: No new screen required, but `ShopWorkspace.jsx` / `OwnerLogs.jsx` should eventually show connection status (`REVOKED` or `NEEDS_RECONNECT`) if Push Code 2 (deauthorization) is triggered.

#### Backend API Contract for External / Webhook Ingestion
```http
POST /webhook/{tenantId}/shopee
Authorization: <hmac_sha256_hex_digest>
Content-Type: application/json

{
  "code": 3,
  "shop_id": 727720655,
  "timestamp": 1714000500,
  "data": {
    "ordersn": "241027SP1042",
    "status": "READY_TO_SHIP",
    "update_time": 1714000500
  }
}
```
- **Validation**: Base string is `webhook_url + "|" + request_body`. Verified against partner key.
- **Push Codes Handled**:
  - `1`: `shop_authorization_push`
  - `2`: `shop_deauthorization_push` $\rightarrow$ marks connection `REVOKED`
  - `3`: `order_status_push` $\rightarrow$ publishes to RabbitMQ `order.events`
  - `4`: `order_tracking_no_push`
  - `15`: `item_promotion_push`
  - `29`: `return_push` $\rightarrow$ publishes to RabbitMQ `return.events`

---

### Phase 4: Omnichannel Order Lifecycle & Inventory Outbox Synchronization ✅ *(COMPLETED)*

#### Status & Implemented Backend
- **Branch**: `feature/INT-02-shopee-authorization` on `SmartOmmi_BE`
- **Commits**: `ff8fe23`, `5420b7a`
- **Features**:
  - `ShopeeOrderClient` supporting `get_order_list`, `get_order_detail` (items, quantities, recipient addresses, buyer details, shipping carrier), `cancel_order`, and `handle_buyer_cancellation`.
  - `ShopeeOrderPayload` canonical DTO format for RabbitMQ messaging and downstream processing.
  - `ShopeeOrderMapper` translating Shopee lifecycle states (`UNPAID` $\rightarrow$ `PENDING`, `READY_TO_SHIP` $\rightarrow$ `TO_SHIP`, `SHIPPED` $\rightarrow$ `SHIPPED`, `TO_CONFIRM_RECEIVE` $\rightarrow$ `DELIVERING`, `COMPLETED` $\rightarrow$ `COMPLETED`, `CANCELLED` $\rightarrow$ `CANCELLED`, `TO_RETURN` $\rightarrow$ `RETURNED`) and resolving line-item internal SKUs via `CatalogMappingStore` and SKU code fallback.
  - `ShopeeOrderSyncService` providing single order ingestion, batch sync, and cancellation operations.
  - `ShopeeOrderController` exposing REST endpoints (`GET /orders`, `POST /orders/{orderSn}/sync`, `POST /orders/batch-sync`, `POST /orders/{orderSn}/cancel`, `POST /orders/{orderSn}/handle-cancellation`).
  - Unit test suites in `ShopeeOrderClientTest`, `ShopeeOrderMapperTest`, `ShopeeOrderSyncServiceTest`, `ShopeeOrderControllerTest`, and `ShopeeWebhookDispatcherTest`.

#### UI Availability Audit in `SmartOmni_FE`
> [!WARNING]
> **FE UI Gap Identified:**
> - `StaffOrders.jsx` currently displays mock orders (`previewData.js`).
> - Filter tabs (`All`, `Pending`, `To ship`, `Shipped`, `Completed`, `Cancelled`, `Returned`) exist in HTML, but fetch from mock state.
> - **Missing in FE**:
>   1. Real API integration with `smartomni-service-order` (`GET /api/v1/orders`).
>   2. Channel badge showing Shopee icon and external Shopee order SN (`ordersn`).
>   3. Inventory warning badges when order is held due to out-of-stock.

#### Backend API Contract for Frontend Team

##### 1. List Orders
```http
GET /api/v1/orders?platform=SHOPEE&status=to_ship&page=0&size=20
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "ORD-501",
        "externalOrderId": "241027SP1042",
        "platform": "SHOPEE",
        "status": "to_ship",
        "totalAmount": 450000,
        "currency": "VND",
        "createdAt": "2026-10-27T08:30:00Z",
        "buyerUsername": "nguyen_buyer",
        "items": [
          {
            "skuCode": "WM-BLK",
            "productName": "Wireless Mouse Black",
            "quantity": 2,
            "unitPrice": 225000
          }
        ]
      }
    ],
    "totalElements": 1,
    "totalPages": 1
  }
}
```

#### Detailed Backend Steps for Phase 4
1. **Shopee Order Client (`ShopeeOrderClient.java`)**:
   - `getOrderList`: Calls `GET /api/v2/order/get_order_list`.
   - `getOrderDetail`: Calls `GET /api/v2/order/get_order_detail` (items, quantities, buyer details).
2. **Order Ingestion Pipeline (`smartomni-service-order`)**:
   - Consumes `order_status_push` from RabbitMQ.
   - Enforces unique index `(tenant_id, platform, external_order_id)`.
3. **Inventory Deduction (`smartomni-service-inventory`)**:
   - Atomically decrements `product_skus.stock_quantity`.
   - Writes event to `inventory_outbox`.
4. **Quartz Outbox Worker (`smartomni-jobs`)**:
   - Reads pending outbox entries, calls `POST /api/v2/product/update_stock` on Shopee.
5. **Unit Tests & Edge Cases**:
   - Out-of-order webhook delivery (`SHIPPED` before `READY_TO_SHIP`).
   - Buyer cancellation before shipping restores stock.
   - Stock depletion handling.

---

### Phase 5: Logistics, Airway Bill (AWB) Generation & SLA Tracking ✅ *(COMPLETED)*

#### Status & Implemented Backend
- **Branch**: `feature/INT-02-shopee-authorization` on `SmartOmmi_BE`
- **Commits**: `96db40d`, `d2ce194`
- **Features**:
  - `ShopeeShopApiExecutor` extended with `callRaw` for binary streaming and raw HTTP transport.
  - `ShopeeLogisticsClient` supporting `get_channel_list`, `get_shipping_parameter` (dropoff points, pickup addresses & time slots), `ship_order`, `get_tracking_number`, `create_shipping_document`, `get_shipping_document_result`, and `download_shipping_document`.
  - `ShopeeLogisticsService` orchestrating pickup/dropoff dispatching, tracking assignment, automated AWB generation triggering, and 48-hour SLA deadline calculation.
  - `ShopeeLogisticsController` exposing REST endpoints (`GET /logistics/channels`, `GET /orders/{orderSn}/shipping-parameter`, `POST /orders/{orderSn}/ship`, and `GET /orders/{orderSn}/shipping-document` for binary thermal PDF download).
  - Unit test suites in `ShopeeLogisticsClientTest`, `ShopeeLogisticsServiceTest`, and `ShopeeLogisticsControllerTest`.

#### UI Availability Audit in `SmartOmni_FE`
> [!WARNING]
> **FE UI Gap Identified:**
> - In `StaffOrders.jsx`, clicking an order currently opens a simple read-only detail dialog.
> - **Missing in FE**:
>   1. "Chuẩn bị hàng / Ship Order" modal allowing selection of:
>      - Dropoff (Gửi hàng tại bưu cục).
>      - Pickup (Bưu cục đến lấy) with pickup address & time slot selector.
>   2. "In phiếu gửi hàng / Print AWB" action button that downloads or opens the binary PDF document.
>   3. 48-hour SLA countdown timer badge ("Giao trước: 28/10 14:00 (Còn 22h)").

#### Backend API Contract for Frontend Team

##### 1. Get Shipping Parameters
```http
GET /api/v1/orders/{orderId}/shipping-parameter
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "data": {
    "orderId": "ORD-501",
    "externalOrderId": "241027SP1042",
    "dropoff": { "branchList": [{ "branchId": 101, "name": "SPX Buu Cuc Cau Giay" }] },
    "pickup": {
      "addressList": [{ "addressId": 901, "address": "123 Xuan Thuy, Cau Giay, Hanoi" }],
      "timeSlotList": [{ "pickupTimeId": "slot_morning", "date": "2026-10-28", "timeText": "09:00 - 12:00" }]
    }
  }
}
```

##### 2. Confirm Ship Order
```http
POST /api/v1/orders/{orderId}/ship
Authorization: Bearer <TenantStaffOrAdminJWT>
Content-Type: application/json

{
  "shippingMode": "pickup",
  "addressId": 901,
  "pickupTimeId": "slot_morning"
}
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "message": "Order shipped successfully",
  "data": { "status": "shipped", "trackingNumber": "SPXVN0293848201" }
}
```

##### 3. Download Airway Bill (AWB) Thermal PDF
```http
GET /api/v1/orders/{orderId}/shipping-document
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response**: Binary `application/pdf` with header `Content-Disposition: inline; filename="AWB-241027SP1042.pdf"`. Contains thermal 100x150mm layout with SPX Code128 barcode.

---

### Phase 6: Financial Escrow Reconciliation & Returns Management ✅ *(COMPLETED)*

#### Status & Implemented Backend
- **Branch**: `feature/INT-02-shopee-authorization` on `SmartOmmi_BE`
- **Commits**: `238b027`, `e33d1ee`
- **Features**:
  - `ShopeeFinanceClient` supporting itemized order escrow breakdown (`get_escrow_detail`) with mathematical clamping ($\ge 0$ VND safe display), platform commission, service fees, transaction fees, and shipping rebates.
  - `ShopeeReturnsClient` supporting `get_return_list`, `get_return_detail` (items, buyer proof images, reverse shipping fees, ruling notes), `confirm_return` (merchant refund approval), and `dispute_return` (seller evidence submission).
  - `ShopeeFinanceAndReturnsService` providing business orchestration for financial ledger inquiries and dispute submissions.
  - `ShopeeFinanceAndReturnsController` exposing REST endpoints (`GET /orders/{orderSn}/escrow`, `GET /returns`, `GET /returns/{returnSn}`, `POST /returns/{returnSn}/confirm`, and `POST /returns/{returnSn}/dispute`).
  - Unit test suites in `ShopeeFinanceClientTest`, `ShopeeReturnsClientTest`, `ShopeeFinanceAndReturnsServiceTest`, and `ShopeeFinanceAndReturnsControllerTest`.

#### UI Availability Audit in `SmartOmni_FE`
> [!WARNING]
> **FE UI Gap Identified:**
> - `StaffReturns.jsx` has a basic list of sample returns.
> - **Missing in FE**:
>   1. Escrow breakdown modal in orders/finance view showing Shopee financial reconciliation:
>      - Merchandise subtotal
>      - Shopee shipping rebate
>      - Commission fee
>      - Transaction fee
>      - Service fee
>      - Net escrow release amount
>   2. Return dispute form with proof image file upload selector (`file_type = 1` for seller dispute proof).
>   3. Clamped escrow display showing guaranteed $\ge 0$ VND.

#### Backend API Contract for Frontend Team

##### 1. Get Order Escrow Breakdown
```http
GET /api/v1/orders/{orderId}/escrow
Authorization: Bearer <TenantStaffOrAdminJWT>
```
- **Response `200 OK`**:
```json
{
  "success": true,
  "data": {
    "orderSn": "241027SP1042",
    "buyerTotal": 450000,
    "shopeeRebate": 30000,
    "commissionFee": 18000,
    "transactionFee": 9000,
    "serviceFee": 13500,
    "shippingFeeSeller": 0,
    "escrowAmount": 439500,
    "escrowStatus": "RELEASED",
    "releaseTime": "2026-10-27T12:00:00Z"
  }
}
```

##### 2. Dispute Return Request
```http
POST /api/v1/returns/{returnSn}/dispute
Authorization: Bearer <TenantStaffOrAdminJWT>
Content-Type: application/json

{
  "disputeReason": "NON_RECEIPT",
  "disputeTextReason": "Customer returned empty box, package weight mismatch.",
  "proofImages": [
    "https://cf.shopee.vn/file/proof_package_1.jpg",
    "https://cf.shopee.vn/file/proof_weight_2.jpg"
  ]
}
```
- **Response `200 OK`**: Returns updated return status `UNDER_SHOPEE_REVIEW`.

---

## 4. Master Tracking Checklist

| Phase | Milestone | Scope | UI Status in FE | Backend Status |
|---|---|---|:---:|:---:|
| **Phase 1** | **OAuth 2.0 Auth Kernel** | Authorization, Tokens, AES Encryption | ✅ Available | ✅ **Completed** |
| **Phase 2** | **Catalog & 2-Tier Variation Sync** | `get_item_list`, `get_model_list`, price sync | ⚠️ Needs Real FE Wiring | ✅ **Completed** |
| **Phase 3** | **Push Webhooks Engine** | HMAC Webhook verification, RabbitMQ | ℹ️ Infra / Logging | ✅ **Completed** |
| **Phase 4** | **Order Lifecycle & Inventory Outbox** | Order mapping, Outbox stock deduction | ⚠️ Needs Real API Wiring | ✅ **Completed** |
| **Phase 5** | **Logistics Fulfillment & AWB PDF** | Ship order, AWB thermal PDF, 48h SLA | ⚠️ Needs Shipping Modal & PDF | ✅ **Completed** |
| **Phase 6** | **Escrow Reconciliation & Returns** | Escrow breakdown, Return dispute proof | ⚠️ Needs Escrow Modal & Dispute Form | ✅ **Completed** |
