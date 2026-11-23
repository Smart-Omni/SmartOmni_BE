# Phase 3. Giá, tồn và báo lỗi rõ ràng

[← Tổng quan](README.md)

- **Branch:** `fix/integration-tiktok-catalog-errors`
- **Phụ thuộc:** không; tạo branch từ `origin/develop` (làm song song với phase 1/2 được)
- **Migration:** không
- **Bao phủ:**
  - B8: body cập nhật giá
  - B9: lỗi 500
  - P1 #10: outbox tồn đúng tiêu chí
  - Javadoc
  - Xác minh `skuLimit`

## Việc cần làm

### 3.1 Body cập nhật giá (B8)
Sửa trong `TiktokCatalogClient.updatePrice` (L171-198):
- `amount = salePrice ?: originalPrice`, cho khớp với phía import vốn đọc `sale_price`.
- Từ chối 400 `TIKTOK_PRICE_INVALID` khi giá ≤ 0 hoặc có phần thập phân khác 0.
- Chuẩn hóa `159000.00` thành `"159000"` bằng `stripTrailingZeros` + `setScale(0)`.
- `currency` lấy từ liên kết (`CatalogMappingStore.findLinkByExternalSku`), mặc định `VND`.
- Gộp hai overload trùng lặp thành một hàm dựng body.

### 3.2 Lỗi đầu vào
- Đổi `IllegalArgumentException` sang `BusinessException`, trong `stockBody` / `inventoryPath` (L113-157) và trong `TiktokStockPushService`:

  | Mã lỗi | HTTP |
  |---|---|
  | `TIKTOK_STOCK_INVALID` | 400 |
  | `TIKTOK_SKU_NOT_ON_PRODUCT` | 400 |
  | `TIKTOK_PRODUCT_ID_INVALID` | 400 |
  | `TIKTOK_SKU_NO_WAREHOUSE` | 409 |

- `TiktokCatalogController` (L74-112) kiểm tra `quantity` bắt buộc.

### 3.3 Lỗi từ sàn (B9)
- Thêm `TiktokApiExceptionHandler` (`@RestControllerAdvice`, `@Order(HIGHEST_PRECEDENCE)`) trong integration. Ánh xạ:

  | Lỗi từ sàn | Trả về |
  |---|---|
  | rejection 4xx | 422 `TIKTOK_REQUEST_REJECTED` |
  | 401 / 403 | 409 `TIKTOK_RECONNECT_REQUIRED` |
  | 404 | 502 `TIKTOK_NOT_FOUND` |
  | transient | 503 `TIKTOK_UNAVAILABLE` |

- Với 422, kèm `summary()` nhưng không kèm payload.
- Tham khảo `TiktokFulfillmentClient.mapApiException` (L239-249).
- Phase 5 và 6 dùng lại handler này.

### 3.4 Outbox tồn (P1 #10)
Sau khi merge `develop`, có hai đường đẩy tồn TikTok. Cả hai phải cùng tiêu chí: chỉ coi là đã gửi khi HTTP 2xx, `code == 0` và SKU không có trong `data.errors`.
- **Đường 1:** `OutboxPublisherScheduler` (inventory) → `TiktokInventoryStockClient` → `POST /internal/jobs/push-tiktok-stock` → `TiktokStockPushService`.
- **Đường 2:** `MarketplaceStockPushService.pushStock` (L89-96) qua `POST /internal/jobs/push-stock` với `platform=tiktok_shop`, gọi thẳng `TiktokCatalogClient.updateStock`.

Đã xong ở [phase R](phase-r-stock-push-rls.md) (PR #38):
- `OutboxPublisherScheduler` có timeout.
- `MarketplaceStockPushService` không còn đặt RLS ở phạm vi session và từ chối chạy khi tenant scope không khớp.
- `OutboxPublisherSchedulerTest` đã có test: chỉ đánh dấu `SENT` khi push thành công; lỗi giữ `PENDING` và chặn event mới hơn cùng SKU; bị timeout thì giữ `PENDING`.

Việc còn lại:
- Xác minh `InternalTiktokStockController` (L45-49) và endpoint `push-stock` trả non-2xx khi `updateStock` ném lỗi.
  - Hiện `MarketplaceStockPushService` bọc lỗi thành `RuntimeException`, nên trả 500.
  - Outbox vẫn retry đúng với 500, nhưng mã lỗi không rõ ràng.
- Áp mã lỗi của 3.2 và 3.3 cho cả hai đường.
- Nhánh TikTok của `MarketplaceStockPushService` hiện bỏ qua im lặng khi `connection_id` null: không ném lỗi, nên outbox sẽ đánh dấu `SENT` dù chưa gửi gì. Đổi thành ném lỗi 409 `TIKTOK_LINK_WITHOUT_CONNECTION`.
- Khi không có liên kết nào (`links.isEmpty()`), service cũng `return` mà không báo lỗi. Cần chọn: coi là không có gì để gửi (giữ nguyên, ghi log) hay coi là lỗi. Ghi rõ lựa chọn trong PR.
- Giữ nguyên `verifyStockResult` (L159-166) và kiểm tra `code != 0` trong `TiktokEnvelope`, vì cả hai đã đúng.

### 3.5 Dọn dẹp
- Sửa Javadoc của `updateStock`: `/stocks/update` → `/inventory/update`.
- Tìm hiểu vì sao catalog có 400 SKU trong khi `skuLimit` là 100 (`store.countSkus()` so với `ProductService` L144). Nếu là lỗi thì sửa trong phase này, nếu không thì ghi vào báo cáo.

## Test

- `TiktokCatalogClientTest`: giá `159000.00`, `159000.50`, `0`; chỉ có `salePrice`; currency lấy từ liên kết.
- `TiktokCatalogControllerTest`: các mã 400 / 409 / 422 / 503.
- Test mới cho `TiktokApiExceptionHandler`.
- `TiktokStockPushServiceTest` và `InternalTiktokStockControllerTest`: lỗi lan ra thành non-2xx.
- Mở rộng `MarketplaceStockPushServiceTest` (đã có từ phase R) cho nhánh TikTok:
  - lỗi từ sàn lan ra non-2xx;
  - SKU nằm trong `errors` không được coi là đã gửi;
  - liên kết thiếu `connection_id` ném lỗi.
- `OutboxPublisherSchedulerTest` đã đủ từ phase R, chỉ cần chạy lại.
- Lệnh: `mvn -pl smartomni-service-integration,smartomni-service-inventory -am test`.

## E2E với simulator

1. `PUT /tiktok/products/price` với `159000.00` gửi lên simulator thành `"159000"` và nhận 200. Giá `159000.50` hoặc `0` trả 400 kèm mã rõ ràng.
2. `PUT /tiktok/products/stock` với giá trị âm, vượt 99.999, SKU sai hoặc thiếu `quantity`: trả 400 / 409, không còn 500.
3. Fault `APP_ERROR_200` trên `inventory/update`: outbox giữ `PENDING`, không chuyển `SENT`.
4. Simulator trả 401 thì SmartOmni trả `TIKTOK_RECONNECT_REQUIRED`.

## Commit

1. `fix(integration): send integer VND sale price to TikTok`
2. `fix(integration): return client errors for invalid TikTok stock input`
3. `fix(integration): map TikTok API failures to explicit HTTP errors`
4. `test(integration): cover TikTok stock push failure outcomes`
5. `docs(integration): fix TikTok inventory update path in Javadoc`

## Xong khi

- Test pass.
- 4 bước E2E đều đạt.
- Không còn lời gọi TikTok nào trong catalog/stock trả 500 cho lỗi đầu vào.
- Đã mở PR.
