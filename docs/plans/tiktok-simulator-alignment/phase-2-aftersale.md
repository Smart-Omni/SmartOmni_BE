# Phase 2. Trả hàng và hủy đơn

[← Tổng quan](README.md)

- **Branch:** `fix/integration-tiktok-aftersale`
- **Phụ thuộc:** [phase 1](phase-1-webhook-orders.md), cần route type 11/12 và bảng `tiktok_sync_checkpoints`
- **Migration:** không
- **Bao phủ:**
  - B2, B3: DTO và chi tiết trả hàng;
  - B4: seller hủy đơn;
  - xử lý webhook 11/12;
  - P1 #9: polling trả/hủy;
  - P1 #10, #11: hoàn tồn và tiền.

## Việc cần làm

### 2.1 DTO
- Tạo record `TiktokRefundAmount(currency, refundSubtotal, refundShippingFee, refundTax, refundTotal)`. Các giá trị là chuỗi, kèm helper parse sang `BigDecimal`.
- Dùng record này cho `refundAmount` ở `TiktokCancellationResponse` (L45), `TiktokReturnResponse` (L47) và `ReturnLineItem` (L89).
- Thêm vào DTO:
  - `cancel_line_items` (record `CancelLineItem`: `cancel_line_item_id`, `order_line_item_id`, `sku_id`, `seller_sku`, `refund_amount`);
  - `arbitration_status`;
  - `role`.
- Sửa các chỗ dùng `getRefundAmount()` ở order service để lấy `refund_total`:
  - `OrderService` L511, L532;
  - `ReturnRefundService` L185, L206.
- `TiktokReturnService.handleReturnStatusChange` (L183-188) đọc `refund_amount` dạng object.

### 2.2 Chi tiết trả hàng
- `TiktokReturnClient.getReturnDetail` (L60-64) chuyển sang `POST /return_refund/202309/returns/search` lọc theo id. Kiểm tra schema search của simulator có `return_ids` không. Nếu không có, lọc theo `order_id` rồi chọn đúng id.
- Thêm `getReturnRecords` cho `/records`, trả về list `{create_time, description, event, note, reason_text, role}`.
- Endpoint mới `GET /api/v1/tiktok/returns/{id}/records`.

### 2.3 Seller hủy đơn (B4)
- `TiktokCancellationClient.cancelOrder` (L75-82) chuyển sang `POST /return_refund/202309/cancellations`, body `{order_id, cancel_reason, order_line_item_ids?}`.
- Trả `cancel_id` cho controller.
- Chỉ cho phép khi đơn ở `UNPAID` hoặc `AWAITING_SHIPMENT`.

### 2.4 Webhook 11 và 12
- Tạo handler `TiktokAftersaleWebhookService`:
  1. Lấy bản ghi đầy đủ bằng search theo id.
  2. Bỏ qua event có `update_time` cũ hơn bản đã lưu.
  3. Nếu không cũ hơn: lưu `return_status` / `cancel_status` và `update_time`, rồi gọi `orderServiceClient.processReturn` sẵn có.
- Trả hàng một phần không được đổi trạng thái của cả đơn.

### 2.5 Polling bù
- Scheduler mới `TiktokAftersalePollingScheduler` dùng checkpoint của phase 1, resource `RETURNS` và `CANCELLATIONS`.
- Body thêm `update_time_ge` / `update_time_lt`. Hiện client chỉ gửi `create_time_*` (cancellation L35-70, return L104).
- `page_size`: returns 10-50, cancellations 1-50 (client đã kẹp sẵn).

### 2.6 Hoàn tồn (P1 #10, #11)
- Xác minh rằng `OrderService` không trừ kho cho đơn TikTok (L121-124) và tồn TikTok đi theo snapshot `INVENTORY_CHANGED_BY_SHOP` (commit `c43fa7a`, `7f3cc68`).
- Nếu đúng như vậy:
  - ghi quyết định vào docs: không hoàn tồn cho TikTok, chỉ đồng bộ snapshot;
  - thêm test bảo đảm return/cancel của TikTok không gọi restock.
- Nếu không đúng, dùng receipt idempotency của V20 với khóa `(order, order_line_item_id)`, và chỉ hoàn khi trạng thái là `RETURN_OR_REFUND_REQUEST_SUCCESS` hoặc `COMPLETE`.

## Test

- `TiktokApiClientsTest`:
  - parse fixture thật lấy từ log simulator (return và cancellation có `refund_amount` dạng object);
  - seller cancel gọi đúng path và body;
  - approve và reject.
- `TiktokReturnServiceTest`: event cũ bị bỏ qua.
- Test mới cho `TiktokAftersaleWebhookService` (type 11 và 12).
- Test mới cho `TiktokAftersalePollingScheduler`: dùng checkpoint, kẹp `page_size`.
- `ReturnRefundServiceTest`: tiền hoàn lấy từ `refund_total`.
- `TiktokReturnControllerTest`: endpoint `/records`.
- Lệnh: `mvn -pl smartomni-service-integration,smartomni-service-order -am test`.

## E2E với simulator

1. Tạo 2 đơn, đưa tới `DELIVERED`, buyer tạo trả hàng. Một đơn `AWAITING_SHIPMENT` có yêu cầu hủy.
2. `GET /tiktok/returns`, `/returns/{id}`, `/returns/{id}/records` và `GET /tiktok/cancellations` đều trả 200 với dữ liệu đầy đủ.
3. Seller hủy đơn `UNPAID` trả 200. Simulator ghi nhận cancellation.
4. Duyệt hoặc từ chối: webhook 11/12 về và SmartOmni cập nhật trạng thái nguồn.
5. Tắt webhook: polling bù vẫn lấy được trạng thái mới.

## Commit

1. `fix(integration): parse TikTok refund amounts as objects`
2. `fix(integration): read TikTok return detail from search and expose records`
3. `fix(integration): cancel TikTok orders through seller cancellation API`
4. `feat(integration): handle TikTok return and cancellation webhooks`
5. `feat(integration): poll TikTok returns and cancellations by update time`
6. `test(order): cover TikTok aftersale restock policy`

## Xong khi

- Test pass.
- 5 bước E2E đều đạt.
- Đã ghi quyết định hoàn tồn.
- Đã mở PR.
