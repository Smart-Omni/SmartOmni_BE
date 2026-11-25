# Tài liệu API: tích hợp SmartOmni ↔ TikTok Simulator (kết quả test local)

Ngày test: 21/11/2026, bổ sung phần kiểm tra DTO/body sync product ngày 22/11/2026 (mục 2.2, 4 và 5; bản chi tiết ở `tiktok-product-sync-check.md`). Môi trường: Docker local, simulator ở `http://localhost:8090` (UI `:3090`), SmartOmni qua gateway `http://localhost`. Tenant test riêng (dữ liệu giả) kết nối shop **SmartOmni Official Flagship** (`shopId 7494000000000000003`). Kết quả đối chiếu giữa phản hồi của SmartOmni, log của Integration Service và `http-logs` của simulator (`/test-admin/http-logs`).

Chú thích: **PASS** = chạy đúng; **FAIL** = lỗi; **PARTIAL** = trả 200 nhưng dữ liệu sai/thiếu; **LOCAL** = không gọi sang simulator; **N/T** = chưa test.

Mọi API SmartOmni bên dưới (trừ auth) cần header `Authorization: Bearer <JWT Tenant Admin/Staff>` và được gateway route `/api/v1/...` tới Integration Service (`:8086`). SmartOmni gọi simulator bằng chữ ký `app_key/timestamp/sign/shop_cipher` (client `TiktokOpenApiClient`).

## 1. Tóm tắt

| Kết quả | Số API |
|---|---|
| PASS | 24 |
| PARTIAL | 3 |
| FAIL | 7 |
| LOCAL / N/T | 6 |

Lỗi cần xử lý, theo mức ưu tiên (chi tiết ở mục 4):

| # | Lỗi | Nằm ở | Hậu quả |
|---|---|---|---|
| B1 | Webhook receiver trả 401 với mọi request | **SmartOmni** | Simulator không thể đẩy event (đơn, trả hàng, tồn kho) về SmartOmni |
| B2 | `GET /tiktok/cancellations` trả 500 khi có dữ liệu | **SmartOmni** (DTO) | Không xem được yêu cầu hủy của buyer |
| B3 | `GET /tiktok/returns` trả 500 khi có dữ liệu; `GET /returns/{id}` trả toàn `null` | **SmartOmni** (DTO) | Không xem được yêu cầu trả hàng |
| B4 | `POST /tiktok/cancellations/cancel` → simulator 404 | **SmartOmni** (sai đường dẫn) | Seller không hủy được đơn |
| B5 | Finance (`settlements`, `escrow`) → simulator 404 | **Simulator** (chưa có route) hoặc SmartOmni gọi sai | Không có dữ liệu đối soát |
| B6 | `inventory-service` không khởi động được ở HEAD | **SmartOmni** | Đã sửa tạm (mục 5) |
| B7 | `grantedScopes` luôn rỗng | Hợp đồng token giữa hai bên | Không kiểm tra được scope |
| B8 | `PUT /tiktok/products/price` gửi `amount` có phần thập phân (`"159000.00"`) hoặc `"0"`, simulator trả 400 | **SmartOmni** (`TiktokCatalogClient.updatePrice`) | Không đẩy được giá lên sàn khi giá lấy từ cột `NUMERIC` hoặc không có giá |
| B9 | Lỗi xác thực và lỗi từ sàn của `PUT .../stock` và `.../price` đều trả `500 INTERNAL_ERROR` | **SmartOmni** (xử lý lỗi) | Client không phân biệt được lỗi nhập liệu với lỗi hệ thống |

## 2. Kết quả theo nhóm

### 2.1 Auth / kết nối shop

| API SmartOmni | Gọi sang simulator | KQ | Ghi chú |
|---|---|---|---|
| `POST /marketplace-connections/tiktok-shop/authorization` | không (tạo `state`, trả `authorizationUrl`) | PASS | 200; 401 khi thiếu JWT; 400 `INVALID_REDIRECT_URI` khi `redirect_uri` ngoài danh sách |
| `POST /marketplace-connections/tiktok-shop/callback` | `GET /api/v2/token/get`, `GET /authorization/202309/shops` | PASS | 200 `CONNECTED`; dùng lại `state` → 409 `TIKTOK_STATE_REUSED` |
| `GET /marketplace-connections/tiktok-shop` | không | PASS | Trả trạng thái kết nối |
| Làm mới token (tự động) | `GET /api/v2/token/refresh` | PASS | Ép hết hạn access token bằng `/test-admin/tokens/:id/expire-access`: simulator trả 401, SmartOmni tự refresh rồi gọi lại, 200 |
| `POST /marketplace-connections/tiktok-shop/disconnect` | không | PASS | Trạng thái → `DISCONNECTED`; các API sau trả 409 `TIKTOK_CONNECTION_DISCONNECTED` |
| `POST /tiktok/connect` (legacy) | — | N/T | 403 `FEATURE_NOT_AVAILABLE` do gói Free giới hạn 1 sàn |
| `grantedScopes` trong kết nối | — | PARTIAL | Luôn `[]`, xem B7 |

### 2.2 Sản phẩm, kho, giá

| API SmartOmni | Simulator | KQ | Ghi chú |
|---|---|---|---|
| `POST /tiktok/products/products/import` | `POST /product/202502/products/search`, `GET /product/202309/products/{id}` | PASS | 50 sản phẩm, 0 lỗi |
| `GET /tiktok/products/products/import-runs` | không | PASS | |
| `POST /tiktok/products/warehouses/sync` | `GET /logistics/202309/warehouses` | PASS | 2 kho |
| `POST /tiktok/products/prices/sync` | `GET /product/202309/products/{id}` | PASS | `considered: 0` (không có SKU lệch giá) |
| `PUT /tiktok/products/stock` | `POST /product/202309/products/{id}/inventory/update` | PASS | Giá trị hợp lệ: đặt 55 và 0 đều 200, tồn được chia lại theo kho, `errors: []`. Giá trị không hợp lệ trả 500 (B9) |
| `PUT /tiktok/products/price` | `POST /product/202309/products/{id}/prices/update` | **FAIL** | Số nguyên (`"150000"`) → 200. `"159000.00"`, `"159000.50"`, `"0"` → simulator 400, SmartOmni trả 500 (B8, B9). `salePrice` bị bỏ qua |
| `GET /tiktok/catalog` | không (đọc DB) | PASS | Có ánh xạ SKU local (`mapped: true`) |
| `POST /tiktok/catalog/auto-import` | như import | PASS | 50 sản phẩm / 400 SKU, tất cả đã liên kết trước đó |

#### Hợp đồng body và DTO của nhóm sản phẩm (đối chiếu OpenAPI và code simulator)

| Lời gọi | Query / body SmartOmni gửi | Quy tắc phía simulator | Kết quả đối chiếu |
|---|---|---|---|
| `POST /product/202502/products/search` | query `page_size` (mặc định 50), `page_token`; body `{}` | `page_size` bắt buộc ở query; body chỉ là bộ lọc (`status`, `seller_skus`, `sku_ids`, `create_time_*`, `update_time_*`) | Khớp. Body rỗng nên nhận mọi trạng thái; SmartOmni tự lọc (`ACTIVATE`, `SELLER_DEACTIVATED`, `PLATFORM_DEACTIVATED` được nhập) |
| `GET /product/202309/products/{id}` | không có query ngoài chữ ký | trả `description`, `main_images`, `skus[].sales_attributes` | Khớp. `id` kiểm tra regex `[A-Za-z0-9_-]{1,64}` |
| `GET /logistics/202309/warehouses` | không | trả `warehouses[]` với `is_default`, `address.region_code` | Khớp |
| `POST /product/202309/products/{id}/inventory/update` | `{"skus":[{"id":"...","inventory":[{"warehouse_id":"...","quantity":0}, ...]}]}` | số tuyệt đối theo kho; lỗi từng SKU ở `data.errors[]` | Khớp. SmartOmni giới hạn 0..99.999 mỗi kho |
| `POST /product/202309/products/{id}/prices/update` | `{"skus":[{"id":"...","price":{"amount":"150000","currency":"VND"}}]}` | `currency` phải `VND`; `amount` khớp `^[1-9][0-9]{0,11}$`; sản phẩm phải `ACTIVATE`; SKU phải thuộc sản phẩm | **Lệch** khi `amount` có phần thập phân hoặc bằng 0 (B8) |

Các trường SmartOmni đọc từ search/detail đều có trong OpenAPI: `id`, `title`, `status`, `description`, `main_images[].urls`, `skus[].{id, seller_sku, price.{currency, sale_price}, inventory[].{warehouse_id, quantity}, status_info.status, sales_attributes[].{name, value_name}}`.

Thử nghiệm import: 80 sản phẩm (page size 50) → `pages: 2`, `productsSeen: 51`, trạng thái `stopped_sku_limit` vì gói Free giới hạn 100 SKU. Phân trang và giới hạn gói chạy đúng.

### 2.3 Đơn hàng

| API SmartOmni | Simulator | KQ | Ghi chú |
|---|---|---|---|
| `GET /tiktok/orders` | `POST /order/202309/orders/search` | PASS | Trả đúng 4 đơn đã tạo |
| `GET /tiktok/orders?orderId=` và `GET /tiktok/orders/{id}` | `GET /order/202507/orders` | PASS | Chi tiết đơn, địa chỉ, `package_list` |
| `POST /tiktok/orders/sync` | search + get | PASS | `totalFetched 3, createdCount 3` |

Lưu ý: `line_items[].quantity` luôn `null` (OAS của simulator không có quantity, mỗi đơn vị là một dòng). Cần xem phía nhận có phụ thuộc quantity không.

### 2.4 Fulfillment

| API SmartOmni | Simulator | KQ | Ghi chú |
|---|---|---|---|
| `GET /tiktok/shipping-providers` | không | LOCAL | Danh sách nhà vận chuyển `SIM-CARRIER-1` được dựng sẵn; không gọi simulator khi không có `packageId` |
| `POST /tiktok/packages/{id}/ship` | `POST /fulfillment/202309/packages/{id}/ship` | PARTIAL | Đơn chuyển `AWAITING_COLLECTION`, nhưng body trả về toàn `null` (`package_id`, `tracking_number`...); gọi lần 2 vẫn 200 |
| `GET /tiktok/packages/{id}/shipping-label/download` | `GET /fulfillment/202309/packages/{id}/shipping_documents` | PASS | PDF hợp lệ (`%PDF-1.4`, 1151 byte) |
| `GET /tiktok/orders/{id}/shipping-label/download` | như trên | PASS | |

### 2.5 Hủy đơn, trả hàng, aftersale

| API SmartOmni | Simulator | KQ | Ghi chú |
|---|---|---|---|
| `GET /tiktok/cancellations` | `POST /return_refund/202309/cancellations/search` | **FAIL** | 200 khi rỗng; 500 khi có dữ liệu (B2) |
| `POST /tiktok/cancellations/cancel` | `POST /order/202309/orders/{id}/cancel` | **FAIL** | Simulator 404 (B4) |
| `POST /tiktok/cancellations/{id}/approve` | `POST /return_refund/202309/cancellations/{id}/approve` | PASS | Trạng thái → `CANCELLATION_REQUEST_SUCCESS` |
| `POST /tiktok/cancellations/{id}/reject` | `.../reject` | N/T | |
| `GET /tiktok/returns` | `POST /return_refund/202309/returns/search` | **FAIL** | 200 khi rỗng; 500 khi có dữ liệu (B3) |
| `GET /tiktok/returns/{id}` | `GET /return_refund/202309/returns/{id}/records` | PARTIAL | 200 nhưng mọi trường `null` (B3) |
| `POST /tiktok/returns/{id}/approve` | `POST /return_refund/202309/returns/{id}/approve` | PASS | `RETURN_AND_REFUND` → `AWAITING_BUYER_SHIP` |
| `POST /tiktok/returns/{id}/reject` | `.../reject` | PASS | Body `{"rejectReason":"ITEM_AS_DESCRIBED"}` → `REFUND_OR_RETURN_REQUEST_REJECT` |
| `GET /tiktok/aftersale/reasons` | không | LOCAL | Danh sách mã lý do dựng sẵn |
| `GET /tiktok/aftersale/reject-reasons` | `GET /return_refund/202309/reject_reasons` | PASS | |
| `GET /tiktok/aftersale/eligibility/{orderId}` | `GET /return_refund/202512/orders/{id}/aftersale_eligibility` | PASS | |

### 2.6 Voucher

| API SmartOmni | Simulator | KQ | Ghi chú |
|---|---|---|---|
| `POST /tiktok/vouchers/sync` | `POST /promotion/202406/coupons/search` | PASS | `[]` (seller chưa tạo coupon) |
| `GET /tiktok/vouchers` | không | PASS | |
| `POST /tiktok/vouchers` | không | LOCAL | 200, tạo mã `SO-VCH-...` ở DB SmartOmni; simulator không có route tạo coupon trên TikTok router (cố ý, theo README simulator). `discountType` phải là `PERCENT_OFF` hoặc `AMOUNT_OFF`, sai thì 400 `INVALID_DISCOUNT_TYPE` |

### 2.7 Finance

| API SmartOmni | Simulator | KQ | Ghi chú |
|---|---|---|---|
| `GET /tiktok/finance/settlements` | `GET /finance/202309/statements` | **FAIL** | Simulator 404 (B5) |
| `GET /tiktok/finance/escrow/{orderId}` | `GET /finance/202309/orders/{id}/statement_transactions` | **FAIL** | Simulator 404 (B5) |

### 2.8 Webhook (simulator → SmartOmni)

| API | KQ | Ghi chú |
|---|---|---|
| `POST /api/v1/integrations/tiktok/webhooks` | **FAIL** | Tôi ký HMAC đúng quy tắc của simulator (`HMAC-SHA256(secret, secret + path + timestamp + body)`) và gửi qua gateway: mọi request đều 401 `UNAUTHORIZED` ở lớp Spring Security, trước khi vào controller (B1) |
| `PUT/GET /event/202309/webhooks` (đăng ký topic tồn kho) | N/T | `TIKTOK_INVENTORY_WEBHOOK_URL` chưa cấu hình nên không được gọi |

## 3. Các lỗi tôi đã kiểm tra nhưng không phải lỗi

- Lượt test đầu chạy trên image `integration-service` build trước các commit căn chỉnh simulator (17/11 14:21), nên orders, voucher, aftersale và shipping-providers trả 500. Sau khi build lại từ source hiện tại, các API đó chạy được; kết quả ở mục 2 là của image mới.
- Return chỉ tạo được khi đơn đã `DELIVERED`. Cách đưa đơn tới đó trên simulator: thanh toán, `test-admin/orders/{id}/transition` sang `AWAITING_SHIPMENT`, SmartOmni ship, rồi `advance-shipment` hai lần (`IN_TRANSIT` → `DELIVERED`).

## 4. Chi tiết lỗi

### B1. Webhook receiver bị chặn bởi Spring Security (SmartOmni)
- Hiện tượng: mọi request tới `/api/v1/integrations/tiktok/webhooks` trả 401 (kể cả khi chữ ký đúng).
- Nguyên nhân: `ServiceSecurityAutoConfiguration.java:63` chỉ `permitAll` cho `/webhook/**` và `/internal/jobs/**`; `PublicEndpointPaths` chỉ có `/api/v1/auth/**` và `/api/v1/tenants/onboarding`. Đường dẫn của `TiktokWebhookController` (`/api/v1/integrations/tiktok`) không nằm trong danh sách, nên cần JWT.
- Gợi ý sửa: cho phép `POST /api/v1/integrations/tiktok/webhooks` không cần JWT (việc xác thực do HMAC trong controller đảm nhiệm).
- Chưa kiểm tra: xác thực HMAC, chống replay, xử lý trùng `tts_notification_id`, vì request chưa tới controller.

### B2. `GET /tiktok/cancellations` → 500
- Log: `TikTok response did not match CancellationSearchResponse` (`TiktokCancellationClient.java:67`).
- Nguyên nhân: `TiktokCancellationResponse.refundAmount` khai báo `BigDecimal`, còn simulator trả object `{currency, refund_subtotal, refund_shipping_fee, refund_tax, refund_total}`. Danh sách rỗng thì không lỗi, nên dễ lọt.

### B3. `GET /tiktok/returns` → 500 và `GET /tiktok/returns/{id}` toàn `null`
- Log: `TikTok response did not match the expected shape for ReturnSearchResponse`.
- Nguyên nhân khả dĩ: cùng kiểu với B2, `refund_amount` được khai báo `BigDecimal` (`TiktokReturnResponse.java:47` và `:89`) trong khi simulator trả object.
- `GET /returns/{id}` gọi `/records`, simulator trả `{records:[...]}` nên các trường cấp trên của DTO không có dữ liệu.
- Tôi chưa chụp thân phản hồi của simulator để chốt trường nào gây lỗi cho danh sách trả hàng.

### B4. Seller hủy đơn gọi sai đường dẫn
- SmartOmni gọi `POST /order/202309/orders/{id}/cancel`. Simulator chỉ có `POST /return_refund/202309/cancellations` ("Seller Cancel Order", body `order_id`, `cancel_reason`, `order_line_item_ids`, `skus`).

### B5. Finance
- Simulator không có route `/finance/...` (không có trong OpenAPI và support matrix). Cần quyết định: bổ sung vào simulator hoặc bỏ/tắt tính năng khi chạy với simulator.

### B6. `inventory-service` không khởi động
- Nguyên nhân: `TiktokInventoryStockClient` có hai constructor, không cái nào gắn `@Autowired`, nên Spring báo "No default constructor found".
- Image cũ chạy được vì build trước thay đổi này.

### B7. `grantedScopes` rỗng
- `TiktokAuthClient` chỉ đọc `data.granted_scopes`. Token response của simulator có `access_token`, `access_token_expire_in`, `refresh_token`, `refresh_token_expire_in`, `open_id` và không có `granted_scopes`. Scope của token chỉ xem được qua `GET /test-admin/tokens`.

### B8. Body cập nhật giá bị simulator từ chối
- `TiktokCatalogClient.updatePrice` (cả hai overload) gửi `targetPrice.toPlainString()`. `BigDecimal` có scale như `159000.00` hoặc `159000.50` thành chuỗi có phần thập phân, simulator trả 400 `price.amount must be a positive integer`.
- Khi không có `originalPrice` và `salePrice`, code gửi `"0"`, cũng bị từ chối.
- Khi có cả hai giá, chỉ `originalPrice` được gửi; phía import lại đọc `price.sale_price`, nên giá đẩy lên và giá đọc về là hai trường khác nhau.
- Tiền tệ cố định `VND`, không lấy theo liên kết SKU.
- Gợi ý: làm tròn về số nguyên VND (hoặc báo lỗi rõ), thống nhất trường giá, lấy currency từ liên kết.

### B9. Lỗi xác thực và lỗi sàn trả 500
- `PUT /tiktok/products/stock`: `quantity` âm, vượt 99.999, SKU không thuộc sản phẩm, thiếu `quantity` đều trả 500 `INTERNAL_ERROR` (nguồn là `IllegalArgumentException` hoặc lỗi bỏ qua kiểm tra).
- `PUT /tiktok/products/price`: mọi lỗi 400 từ simulator (`TiktokApiException`) cũng ra 500.
- Gợi ý: ánh xạ sang 400/422 cho lỗi nhập liệu và 502 cho lỗi từ sàn, kèm mã lỗi riêng.

### Quan sát khác về sync product (không phải lỗi chặn)
- `main_images` được map vào DTO nhưng không được lưu; `tax_exclusive_price` và `status_info` của SKU bị bỏ.
- Tồn kho chỉ khởi tạo khi chưa có (`initInventoryIfAbsent`), nên import lại không cập nhật tồn từ TikTok.
- Javadoc `updateStock` ghi `/stocks/update`, code gọi `/inventory/update`.
- Catalog của tenant test liệt kê 400 SKU đã ánh xạ trong khi `skuLimit` là 100; chưa truy nguyên đường tạo.
- Chưa kiểm chứng: đồng bộ giá khi giá simulator đổi (cần cấu hình price-sync, hiện `considered: 0`), sản phẩm `DELETED`/`FREEZE`/`SELLER_DEACTIVATED` trong import thật, đẩy tồn tự động qua outbox.

## 5. Thay đổi code tôi đã làm khi test

- `smartomni-service-inventory/.../TiktokInventoryStockClient.java`: thêm `@Autowired` vào constructor public (một dòng). **Chưa commit**; hãy xem lại trước khi dùng.
- Build lại image `integration-service` và `inventory-service` từ source hiện tại; khởi động lại `smartomni-gateway` vì nginx giữ IP cũ của container.
- Ngày 22/11 máy khởi động lại làm các service Java dừng (exit 255); đã chạy lại bằng `docker compose ... up -d` (không build lại).
- Dữ liệu test còn lại: tenant test đã kết nối lại shop; SKU `7510080002670926179` đã khôi phục giá 159000, tồn 37; simulator có thêm 30 sản phẩm × 2 SKU do `POST /test-admin/catalog/bulk` tạo.

## 6. Cách chạy lại

> Cập nhật 25/11/2026: bước 3 và 4 đã sửa theo simulator mới (từ commit `fbf921e`, cần database đã cập nhật): đăng nhập demo bằng email và mật khẩu, thanh toán xong đơn vào thẳng `AWAITING_SHIPMENT`. Kết quả test ở các mục khác giữ nguyên.

1. Khởi động: `docker compose -f docker-compose.yml -f docker-compose.tiktok-simulator.yml up --build -d` (trong repo SmartOmni) và `docker compose up --build -d` (trong repo simulator). Sau khi tạo lại container, chạy `docker restart smartomni-gateway`.
2. Tạo tenant: `POST /api/v1/tenants/onboarding` (`company_name`, `subdomain`, `admin_email`, `admin_full_name`, `admin_password`), đăng nhập `POST /api/v1/auth/login` kèm `subdomain`.
3. Kết nối shop: gọi `authorization`, mở `authorizationUrl` ở UI simulator, đăng nhập `phamgiahuy.ttsim@gmail.com` / `123` (seller "Phạm Gia Huy - SmartOmni Official Flagship"), rồi Authorize; lấy `code` và `state` từ body response của `POST /demo/authorization/consent`; gọi `callback`.
4. Tạo đơn: `POST /demo/sessions` với body `{"role":"BUYER","email":"nguyenminhkhoi.ttsim@gmail.com","password":"123"}` (buyer Nguyễn Minh Khôi) → `POST /demo/shops/{shopId}/checkout` → `.../orders/{id}/pay` (đơn vào thẳng `AWAITING_SHIPMENT`).
5. Aftersale: `POST /demo/shops/{shopId}/aftersale/returns` (đơn đã `DELIVERED`) và `.../aftersale/cancellations` (đơn đã thanh toán, chưa ship).

## 7. Cập nhật 23/11/2026: simulator thay đổi và kết quả chạy lại

### 7.1 Simulator đã đổi gì (commit tới `7f721af`, nhánh `develop`)
- **Bỏ trạng thái `ON_HOLD`** (DECISIONS H1): thanh toán chuyển đơn thẳng `UNPAID → AWAITING_SHIPMENT` và tạo package `TO_FULFILL` ngay. Order payload không còn `release_date`; `is_on_hold_order` luôn `false` (hoặc không có); lọc `order_status: "ON_HOLD"` vẫn hợp lệ nhưng luôn rỗng. Seller chỉ hủy được đơn ở `UNPAID` và `AWAITING_SHIPMENT`. Ship đơn `UNPAID` trả `order_unpaid`.
- **Voucher theo lô** (Phase 09c): mã trong lô là coupon dùng một lần; seller tạo lô ở Seller Center hoặc `POST /test-admin/scenarios/loyalty-vouchers`. Không có API tạo coupon (cố ý).
- **Tài liệu yêu cầu cho SmartOmni**: `docs/smartomni-requirements/phase-05..09.md` mô tả việc SmartOmni phải làm (webhook, idempotency, polling, tồn kho, hoàn trả, voucher, loyalty).
- Phần còn lại chủ yếu là UI. Dữ liệu simulator được làm mới (đơn cũ biến mất, token cũ bị từ chối).

### 7.2 Kết quả chạy lại trên simulator mới

| Luồng | Kết quả |
|---|---|
| Thanh toán đơn | PASS: `AWAITING_SHIPMENT` ngay, có package; SmartOmni thấy `package_list` và địa chỉ giao |
| Ship qua SmartOmni, tải nhãn PDF | PASS |
| Duyệt hủy, duyệt/từ chối trả hàng | PASS |
| `GET /tiktok/returns`, `GET /tiktok/cancellations` khi có dữ liệu | FAIL (B2, B3). Phản hồi thật: `refund_amount` là object `{currency, refund_subtotal, refund_shipping_fee, refund_tax, refund_total}`; trả hàng có thêm `return_orders` |
| `GET /tiktok/returns/{id}` | PARTIAL: vẫn toàn `null` vì `/records` trả `{records:[{create_time, description, event, note, reason_text, role}]}`, là lịch sử sự kiện, không phải đối tượng return (B3) |
| `POST /tiktok/cancellations/cancel`, finance | FAIL như cũ (B4, B5) |
| Kết nối sau khi simulator đổi | SmartOmni báo đúng `TIKTOK_RECONNECT_REQUIRED` khi refresh token bị từ chối. Cấp quyền lại bị chặn bởi quota gói Free (B10) |
| `PUT .../stock` với `quantity: 0` | simulator vẫn nhận 200, dù tài liệu simulator (D6.1) nói `[1, 99999]` |
| Kéo voucher (`POST /tiktok/vouchers/sync`) | PARTIAL: simulator có 60 mã (3 lô × 20), SmartOmni chỉ lấy 50 (B13) |

## 8. Việc SmartOmni cần làm để lấy đủ, đúng nghiệp vụ và giao tiếp bình thường

Thứ tự theo mức ưu tiên. Mỗi mục ghi nơi cần sửa. Mã B10 đến B13 dùng trong mục này là lỗi mới của lượt 23/11 (B10 quota kết nối, B11 topic webhook, B12 số `type` webhook, B13 voucher không phân trang).

### P0. Không có thì luồng chính không chạy

1. **B1. Cho phép webhook không cần JWT.** Thêm `POST /api/v1/integrations/tiktok/webhooks` vào danh sách public (`ServiceSecurityAutoConfiguration` hoặc `PublicEndpointPaths`); xác thực bằng HMAC trong controller. Hiện mọi webhook đều 401.
2. **B11. Đăng ký đủ topic webhook.** Simulator chỉ gửi topic đã đăng ký; SmartOmni mới đăng ký `INVENTORY_CHANGED_BY_SHOP` (`TiktokInventorySubscriptionService`) và chỉ khi `TIKTOK_INVENTORY_WEBHOOK_URL` có giá trị. Cần `PUT /event/202309/webhooks` cho: `ORDER_STATUS_CHANGE`, `PACKAGE_UPDATE`, `RETURN_STATUS_CHANGE`, `CANCELLATION_STATUS_CHANGE`, `SELLER_DEAUTHORIZATION` (và `NEW_MESSAGE`, `NEW_CONVERSATION` khi làm chat). Biến môi trường URL nên là URL webhook chung, không chỉ cho tồn kho.
3. **B12. Khớp số `type` của webhook.** Simulator: `ORDER_STATUS_CHANGE=1`, `RECIPIENT_ADDRESS_UPDATE=2`, `PACKAGE_UPDATE=3`, `REVERSE_STATUS_UPDATE=4`, `SELLER_DEAUTHORIZATION=6`, `CANCELLATION_STATUS_CHANGE=11`, `RETURN_STATUS_CHANGE=12`, `NEW_CONVERSATION=13`, `NEW_MESSAGE=14`, `DISPUTE_STATUS_CHANGE=15`, `INVENTORY_CHANGED_BY_SHOP=71`. `TiktokWebhookController` xử lý trả hàng ở type `2` hoặc `4` (không phải `12`), không xử lý hủy đơn (`11`), và nhận biết hủy ủy quyền bằng type `16` hoặc chuỗi trong body (simulator dùng `6`, body không có chuỗi tên). Cần sửa bảng ánh xạ. Số type vẫn là `unverified` ở cả hai phía.
4. **B2, B3. Sửa DTO trả hàng và hủy đơn.** `refund_amount` phải là object (hoặc `JsonNode`) ở `TiktokCancellationResponse` và `TiktokReturnResponse` (cả bản ghi lồng dòng hàng); thêm trường `return_orders`, `return_line_items`, `cancel_line_items`, `arbitration_status`. Chi tiết trả hàng nên lấy từ `returns/search` theo id; `/records` là lịch sử sự kiện, dùng riêng.
5. **B4. Hủy đơn của seller.** Gọi `POST /return_refund/202309/cancellations` (body `order_id`, `cancel_reason`, tùy chọn `order_line_item_ids`, `skus`), không dùng `/order/202309/orders/{id}/cancel`. Chỉ cho `UNPAID` và `AWAITING_SHIPMENT` (chưa ship).
6. **B8, B9. Sửa cập nhật giá và xử lý lỗi.** Làm tròn `amount` về số nguyên VND (không gửi `"159000.00"` hay `"0"`); thống nhất trường giá (`sale_price` so với giá gốc); lấy `currency` theo liên kết. Ánh xạ lỗi nhập liệu sang 400/422 và lỗi từ sàn sang 502 thay cho 500.

### P1. Cần để đúng nghiệp vụ theo yêu cầu của simulator

7. **Idempotency webhook (Phase 05).** Hiện `tts_notification_id` chỉ được ghi log (`webhook_events_log`), không chặn xử lý trùng. Cần ràng buộc duy nhất và bỏ qua bản trùng; upsert đơn theo (tenant, connection, order id); bỏ qua event có `update_time` cũ hơn bản đã lưu (không hồi trạng thái).
8. **Lấy chi tiết đơn khi nhận event.** Dùng `data.order_id` rồi `GET /order/202507/orders?ids=`; SKU chưa ánh xạ thì đưa đơn vào hàng chờ đối soát, không gán SKU đầu tiên.
9. **Polling bù (Phase 05, 07b).** Dùng `update_time_ge` (gồm) và `update_time_lt` (không gồm) kèm `page_token` cho orders, returns, cancellations; chỉ tiến checkpoint sau khi đã lưu bền. Giới hạn `page_size`: returns `10-50`, cancellations `1-50`. Hiện chỉ có poll đơn.
10. **Tồn kho (Phase 06).** Gửi số tuyệt đối (đã làm); coi SKU nằm trong `data.errors[]` là chưa gửi; chỉ đánh dấu outbox `SENT` khi HTTP 2xx, `code == 0` và SKU không có trong `errors`; mỗi `inventory[]` phải nêu đủ mọi kho của SKU; khóa idempotency theo (đơn, dòng hàng) để chống trừ trùng và hoàn tồn.
11. **Hoàn tồn khi trả hoặc hủy (Phase 07b).** Simulator không cộng lại tồn; SmartOmni hoàn theo `order_line_item_id` và chỉ khi trả hàng đạt `RETURN_OR_REFUND_REQUEST_SUCCESS` hoặc `COMPLETE`. Duyệt hủy chỉ trả lại phần giữ chỗ của simulator, phần trừ của SmartOmni phải hoàn riêng. Trả một phần không được biến cả đơn thành đã trả hoặc đã hủy.
12. **Voucher (Phase 09c).** (a) `POST /tiktok/vouchers` hiện tạo mã nội bộ `SO-VCH-...` và báo thành công; theo simulator phải trả "không hỗ trợ tạo voucher qua API" và không gửi mã như thể đã tạo (bỏ `createCoupon` gọi `POST /promotion/202406/coupons`, simulator không có route này). (b) Kéo mã: `POST /promotion/202406/coupons/search` với `title_keyword`, `status: ["ONGOING"]`, `page_size=100`, đi hết `next_page_token` (hiện cố định 50 và không phân trang nên chỉ lấy 50 trong 60 mã). (c) Theo dõi dùng bằng `GET /promotion/202406/coupons/{id}`, đọc `usage_stats.redeemed_count` và `status`; chỉ poll mã đã phát và chưa ở trạng thái cuối.
13. **Quota kết nối (B10).** `MarketplaceQuotaValidator.assertCanConnect` đếm cả kết nối hiện có của chính tenant (mọi trạng thái trừ `DISCONNECTED`), nên tenant Free không thể cấp quyền lại khi kết nối đang cần cấp lại. Khi bắt đầu OAuth, bỏ qua kết nối TikTok hiện có của tenant, hoặc chỉ tính kết nối của sàn khác.
14. **Scope.** Simulator không trả `granted_scopes` trong token (B7). Hoặc đọc scope qua cách khác, hoặc bỏ `grantedScopes` và `TIKTOK_REQUIRED_SCOPES`. Khi thiếu scope phải báo lỗi rõ, không trả danh sách rỗng.

### P2. Tính năng chưa có

15. **Theo dõi vận chuyển và nhãn.** Chưa có lời gọi `GET /fulfillment/202309/orders/{order_id}/tracking` (thời gian ở `update_time_millis`, đơn vị mili giây); nhãn cần `document_type=SHIPPING_LABEL`, `doc_url` chỉ trả sau khi kiểm tra tenant và không lưu lâu dài.
16. **Hội thoại (Phase 08).** Chưa có client nào cho `customer_service/202309/*`. Cần: chỉ đồng bộ từ lúc kết nối; chống trùng theo (connection, message id); tách `user_id` và `im_user_id`; không gửi lại khi kết quả gửi không xác định; tôn trọng `can_send_message=false`; bỏ qua tin `NOTIFICATION` ẩn và loại nội dung không hỗ trợ.
17. **Finance (B5).** Simulator không có route; quyết định bổ sung ở simulator hoặc tắt chức năng khi dùng simulator.
18. **Loyalty (Phase 09b).** Điểm = `floor(sub_total / 1000)` (không dùng `total_amount`); chỉ đơn `COMPLETED` đủ 15 ngày; claim một lần theo ràng buộc duy nhất. Simulator có `POST /test-admin/scenarios/loyalty-orders` để tạo dữ liệu biên. Chưa kiểm chứng ở lượt này.

### Dọn dẹp nhỏ
- Bỏ các nhánh `ON_HOLD` trong `TiktokOrderStatusMapper`, `TiktokOrderSyncService`, `OrderService`, `ReturnRefundService` khi chỉ dùng simulator mới (giữ nếu sàn thật vẫn dùng); đừng phụ thuộc `release_date`.
- Sửa Javadoc `updateStock` (`/stocks/update` thành `/inventory/update`); lưu `main_images` nếu cần hiển thị.
- Kiểm tra `skuLimit` 100 so với 400 SKU đã ánh xạ.

### Việc phía simulator (tài liệu)
- D6.1 ghi tồn `[1, 99999]` và từ chối `0`, nhưng simulator nhận `quantity: 0`; nên sửa một trong hai.

## 9. Môi trường sau lượt chạy lại 23/11
- Stack SmartOmni được dựng lại bằng `docker compose ... down` rồi `up -d` (giữ volume) vì mạng Docker bị hỏng sau khi máy khởi động lại. Integration Service mất khoảng 84 giây để sẵn sàng nên gateway trả 502 trong thời gian đó; cần khởi động lại `smartomni-gateway` sau khi service lên.
- Shop test được ngắt rồi nối lại; có 4 đơn mới và các yêu cầu trả hoặc hủy trong simulator; 3 lô voucher × 20 mã do `loyalty-vouchers` tạo; tồn SKU `7510080002670926179` đã đặt lại 37.

## 10. Trạng thái sửa (23/11/2026, theo `../plans/tiktok-simulator-alignment/`)

Code của mọi mục dưới đây đã sửa. `mvn -B clean verify` pass trên mọi branch (24/11), migration test V26 pass. E2E chạy ngày 24/11 trên branch gộp tạm `e2e/tiktok-alignment` (không push), với simulator `7f721af` và DB local dựng lại từ đầu sau khi backup. Các branch chưa push và chưa có PR.

| Mục | Branch | Thay đổi chính | Kết quả E2E |
|---|---|---|---|
| Shopee thiếu stock | `fix/integration-shopee-missing-stock` | Stock không rõ giữ `null`; import báo `STOCK_MISSING` và không tạo tồn 0 | Không chạy (không có Shopee sandbox), chỉ có unit test |
| B8, B9, P1 #10 | `fix/integration-tiktok-catalog-errors` | Giá gửi dạng số nguyên VND (`sale_price`, nếu không có thì `original_price`); có mã lỗi 400/409 cho giá và tồn; `TiktokApiExceptionHandler` ánh xạ lỗi sàn sang 503/409/502/422; cả hai đường đẩy tồn trả non-2xx khi lỗi; liên kết thiếu `connection_id` trả 409 | PASS: `159000.00` gửi lên thành `159000` (200), đổi `salePrice 161000.00` thì simulator lưu 161000; giá `159000.50`/`0` trả 400 `TIKTOK_PRICE_INVALID`; tồn `-1`/`100000`/thiếu `quantity` trả 400 `TIKTOK_STOCK_INVALID`, SKU sai trả `TIKTOK_SKU_NOT_ON_PRODUCT`, product id sai trả `TIKTOK_PRODUCT_ID_INVALID`; fault `APP_ERROR_200` thì outbox giữ `pending` (retry 1), lần sau gửi được; token bị thu hồi thì trả 409 `TIKTOK_RECONNECT_REQUIRED` |
| `skuLimit` 100 so với 400 SKU | `fix/integration-tiktok-catalog-errors` | Auto-import (chạy sau OAuth và qua `POST /tiktok/catalog/auto-import`) tạo SKU mà không kiểm tra `max_skus`; nay dừng ở giới hạn và báo `STOPPED_SKU_LIMIT` | PASS: auto-import sau OAuth dừng ở đúng 100 SKU (gói Free), `status=STOPPED_SKU_LIMIT` |
| B1, B11, B12, P1 #7–#9 | `fix/integration-tiktok-webhook` | Webhook không cần JWT; route theo enum `TiktokWebhookType`; idempotency theo `tts_notification_id`; type 1 lấy chi tiết đơn; bỏ qua event cũ (`orders.platform_updated_at`); đơn có SKU chưa ánh xạ đánh dấu `SKU_UNMAPPED`; polling theo `update_time` có checkpoint (`V26`); đăng ký 6 topic | PASS 5/5: simulator có 6 subscription; thanh toán xong thì webhook trả 200 và đơn `to_ship` có `platform_updated_at`; `DUPLICATE` (mỗi event giao 2 lần) chỉ tạo 1 đơn; `REORDER` (event `UNPAID` về muộn 18 giây) không làm hồi trạng thái; chữ ký sai trả 401, chữ ký đúng không cần JWT trả 200, trùng id trả 200, thiếu id trả 400; `DROP` thì đơn vẫn về qua `poll-orders` |
| (mới) RLS khi tra shop và khi polling | `fix/integration-tiktok-webhook` | Tra `shop_id` của webhook và danh sách kết nối khi polling chạy không có tenant. Với role cũ (`order_polling_worker` / không role), RLS trả 0 dòng, nên webhook sẽ 404 và polling không chạy. Nay dùng `price_sync_worker` (policy V6) | PASS (webhook tìm được shop, polling thấy kết nối) |
| B2, B3, B4, P1 #9 (trả/hủy), #11 | `fix/integration-tiktok-aftersale` (stack trên webhook) | `refund_amount` dạng object; lấy chi tiết trả hàng qua search và có endpoint `/records`; seller hủy đơn qua `POST /return_refund/202309/cancellations`; webhook 11/12 đọc lại bản ghi hiện tại; trả một phần không đổi trạng thái đơn; polling trả/hủy; `OrderServiceClient` gửi JWT nội bộ | PASS, trừ một điểm còn mở bên dưới: `/returns`, `/returns/{id}`, `/records`, `/cancellations` trả 200, `refund_amount` dạng object; seller hủy đơn `UNPAID` trả 200 kèm `cancel_id`, đơn đã giao trả 409 `TIKTOK_CANCEL_NOT_ALLOWED`; trả một phần bị từ chối thì đơn vẫn `completed` và bản ghi `rejected` 159000; duyệt hủy thì đơn `cancelled`; webhook 12 bị `DROP` thì polling lấy được sau 2 giây |
| B13, P1 #12 | `fix/integration-tiktok-vouchers` | Tạo voucher qua API trả 501; kéo mã theo `title_keyword`, đi hết các trang; phát một mã chưa dùng của lô; job theo dõi `redeemed_count`. Không cần migration: `tiktok_vouchers.idempotency_key` lưu `requestKey` đã nhận mã | PASS: sync lấy đủ 120 mã (60 của lượt này và 60 cũ), lọc `Loyalty Diamond 30%` được 40 (2 lô cùng tên); tạo voucher trả 501; phát mã cho cùng `requestKey` trả lại cùng mã, request khác nhận mã khác; buyer dùng mã thì job ghi `redeemed_count=1` và ngừng poll |
| B10, B7, P1 #13, #14 | `fix/integration-tiktok-connection` (stack trên catalog-errors) | Quota chỉ đếm kết nối của sàn khác; thiếu `granted_scopes` nghĩa là không rõ (`null`), bỏ qua kiểm tra; lỗi 403 do thiếu scope trả `TIKTOK_SCOPE_MISSING` | PASS: gói Free cấp quyền lại được mà không ngắt kết nối (về `CONNECTED`); `grantedScopes: null`. Chưa kiểm thực tế 403 thiếu scope (token simulator có đủ scope) |
| B5 | `feat/integration-tiktok-finance-from-orders` (stack trên catalog-errors) | Escrow lấy từ `price_detail`; settlements là ước tính từ đơn `COMPLETED` (`estimated=true`, `source=ESTIMATED_FROM_ORDERS`) | PASS: escrow khớp simulator (318000 + ship 25000 = 343000, 2 dòng, các trường phí `null`); settlements chỉ cộng đúng đơn `COMPLETED` trong khoảng; đơn không tồn tại trả 502 `TIKTOK_NOT_FOUND` |

**Lỗi tìm thấy khi chạy E2E (đã sửa):**
- `CatalogMappingService` tra `product_marketplace_links` mà không mở transaction, nên RLS không có tenant và mọi dòng đơn từ webhook hoặc polling đều bị coi là `SKU_UNMAPPED`. Sửa trên `fix/integration-tiktok-webhook` (`2270331`).
- `integration-service` trong Compose gọi order service ở `localhost:8085`, nên mọi `processReturn`/`updateTracking` đều bị từ chối kết nối. Code cũ nuốt lỗi này. Sửa trên `fix/integration-tiktok-aftersale` (`50a1da3`).

**Đã giải quyết (24/11): trả hàng toàn bộ một đơn đã `COMPLETED`.** Lần chạy E2E đầu cho thấy order service chuyển `COMPLETED → ON_HOLD`, bị state machine A5 chặn (409). Đã sửa theo đúng `Smartomni-Tiktok-Simulator/docs/smartomni-requirements/phase-07.md` mục 07b, trên `fix/integration-tiktok-aftersale` (`4245fe8`, `42f301d`, migration `V27`):
- 07b.2: trả hàng TikTok không bao giờ đổi trạng thái đơn. Mỗi yêu cầu lưu `marketplace_status` (nguyên văn `return_status`) và `marketplace_updated_at`; event cũ hơn bị bỏ qua.
- 07b.3: kiểm hàng của nhân viên với đơn TikTok chỉ được ghi lại, không đổi trạng thái đơn, không hoàn kho.
- 07b.4: khi yêu cầu đạt `RETURN_OR_REFUND_REQUEST_SUCCESS`/`COMPLETE`, hoàn kho từng dòng một lần, bằng receipt `tiktok-return:{order}:{order_line_item_id}`, cho mọi loại yêu cầu như tài liệu ghi. Simulator không tự cộng lại tồn.
- E2E (trả toàn bộ `RETURN_AND_REFUND`, đơn `COMPLETED`): 5 webhook `PENDING → AWAITING_BUYER_SHIP → BUYER_SHIPPED_ITEM → SUCCESS → COMPLETE` đều 200. Đơn giữ `completed` suốt quá trình. Tồn SmartOmni 20 → 21 ở `SUCCESS`, không cộng lại ở `COMPLETE`; có đúng 1 receipt; outbox đẩy 21 lên simulator.

**Quan sát khác:** đơn lấy về qua polling vẫn có `source=webhook`, vì consumer đặt nguồn theo hàng đợi chứ không theo đường vào. Đây là lỗi có từ trước.

**Quyết định đã ghi:**
- **Hoàn tồn TikTok (2.6), đã sửa lại theo 07b.4.** Trả hàng: SmartOmni hoàn kho từng dòng khi yêu cầu đạt `SUCCESS`/`COMPLETE`, vì simulator không cộng lại tồn và không phát snapshot. Hủy đơn: không hoàn kho riêng, vì simulator trả phần giữ chỗ và phát `INVENTORY_CHANGED_BY_SHOP`, mà SmartOmni chưa từng trừ kho cho đơn TikTok. Quyết định cũ ("không hoàn kho cho TikTok") đã bị thay.
- **Hủy đơn qua webhook 11.** Chỉ đọc lại và ghi log. Trạng thái đơn lấy từ `ORDER_STATUS_CHANGE`, vì simulator phát event này khi hủy xong. Nếu đẩy yêu cầu hủy qua luồng trả hàng, đơn sẽ bị đặt `ON_HOLD`.
- **Event cũ trả/hủy.** Integration luôn đọc lại bản ghi hiện tại từ sàn. Order service còn so `update_time` với `marketplace_updated_at` đã lưu và bỏ qua bản cũ hơn (07b.2).
- **Outbox tồn.** Mọi response non-2xx đều được retry tối đa `MAX_RETRY` lần, sau đó chuyển `FAILED`, nên một event lỗi vĩnh viễn không chặn SKU mãi. Khi SKU không có liên kết trên sàn đó, push là no-op có ghi log.
- **Dedupe webhook.** Event đã xử lý là bản trùng. Event có bản ghi nhưng chưa xử lý xong (lần trước trả 503) được xử lý lại. Bản ghi có chữ ký sai lưu `event_id = NULL`.
- **Voucher.** Tên campaign phải nằm trong tiêu đề lô mã trên Seller Center, vì order service gửi `campaignName` làm `title_keyword`.

**Nội dung cần đưa vào `docs/architecture.md`** (chưa sửa file đó vì đang có thay đổi chưa commit): luồng webhook TikTok (xác thực HMAC → idempotency theo `tts_notification_id` → route theo `TiktokWebhookType` → lấy chi tiết đơn → `order.incoming` → order service bỏ qua event cũ theo `platform_updated_at`); polling bù theo `update_time` có checkpoint `tiktok_sync_checkpoints` (ORDERS, RETURNS, CANCELLATIONS) và overlap; quyết định tồn kho TikTok ở trên; `OrderServiceClient` gửi JWT nội bộ khi chạy nền.
