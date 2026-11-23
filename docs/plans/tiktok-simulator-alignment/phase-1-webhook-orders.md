# Phase 1. Webhook và đơn hàng

[← Tổng quan](README.md)

- **Branch:** `fix/integration-tiktok-webhook`
- **Phụ thuộc:** không; tạo branch từ `origin/develop` (phase 0 và R đã merge)
- **Migration:** `V26`
- **Bao phủ:**
  - B1: webhook bị 401.
  - B11: thiếu topic.
  - B12: số `type` không khớp.
  - P1 #7: idempotency, không hồi trạng thái.
  - P1 #8: lấy chi tiết đơn, SKU chưa ánh xạ.
  - P1 #9: polling đơn.
  - Hủy ủy quyền.

## Checklist trước khi làm

- [ ] So số migration mới nhất trên `origin/develop` với `V26`. Nếu đã có người dùng `V26`, chọn số kế tiếp và cập nhật các con số trong `DatabaseMigrationTest`.
- [ ] Branch tạo từ `origin/develop` mới nhất (đã có PR #37 và #38). Trước khi mở PR, merge `develop` lại một lần nữa rồi chạy `mvn -B clean verify`.
- [ ] Số `type` webhook chỉ có một nguồn là enum `TiktokWebhookType`. Không so sánh với số trực tiếp ở nơi khác: tìm `getType() ==` và `case 1 ->`, phải không còn kết quả. Test `TiktokWebhookTypeTest` phải so khớp đủ bảng `WEBHOOK_TYPE` của simulator, ghi rõ nguồn ở `backend/src/webhooks/webhook-emitter.service.ts`.
- [ ] `WebhookOrderPayload.platformUpdateTime` là tùy chọn (`Long`, có thể null). `OrderService` bỏ qua so sánh khi giá trị là null. Thêm test chứng minh đơn Shopee (không có trường này) vẫn cập nhật trạng thái bình thường.

## Việc cần làm

### 1.1 Mở webhook (B1)
- Thêm `POST /api/v1/integrations/tiktok/webhooks` vào `permitAll` trong `smartomni-common/.../security/ServiceSecurityAutoConfiguration.java` (L63, cạnh `/webhook/**`), dùng `requestMatchers(HttpMethod.POST, ...)`.
- Không thêm vào `PublicEndpointPaths`. `TenantFilter` và `JwtAuthenticationFilter` không chặn request thiếu Bearer, nên để chúng chạy như bình thường.

### 1.2 Bảng ánh xạ type (B12)
- Tạo enum `TiktokWebhookType` trong `integration/dto/tiktok/`, khớp `WEBHOOK_TYPE` của simulator:

  | Số | Topic |
  |---|---|
  | 1 | `ORDER_STATUS_CHANGE` |
  | 2 | `RECIPIENT_ADDRESS_UPDATE` |
  | 3 | `PACKAGE_UPDATE` |
  | 4 | `REVERSE_STATUS_UPDATE` |
  | 6 | `SELLER_DEAUTHORIZATION` |
  | 11 | `CANCELLATION_STATUS_CHANGE` |
  | 12 | `RETURN_STATUS_CHANGE` |
  | 13 | `NEW_CONVERSATION` |
  | 14 | `NEW_MESSAGE` |
  | 15 | `DISPUTE_STATUS_CHANGE` |
  | 71 | `INVENTORY_CHANGED_BY_SHOP` |

- `TiktokWebhookController` route theo enum, thay cho `== 2 || == 4` (L190) và `resolveEventType` (L262).
- Hủy ủy quyền xử lý khi type 6. Giữ nhận diện theo chuỗi để tương thích ngược, bỏ `type == 16`.
- Type 11 và 12 được route tới handler nhưng phase này chỉ ghi log và trả 200. Xử lý thật làm ở [phase 2](phase-2-aftersale.md).

### 1.3 Idempotency (P1 #7)
- Dùng ràng buộc sẵn có `uq_webhook_events_log (tenant_id, platform, event_id)` (V2 L387).
- Sau khi chữ ký hợp lệ, insert bản ghi log trước, trong transaction riêng; `saveAuditLog` trả về boolean.
- `DataIntegrityViolationException` nghĩa là bản trùng: trả 200 `{"code":0}` và dừng. Hiện `saveAuditLog` (L227-243) nuốt mọi lỗi.
- Thiếu `tts_notification_id` thì trả 400, vì Postgres cho phép nhiều `NULL` trong ràng buộc unique.

### 1.4 `ORDER_STATUS_CHANGE` (P1 #8)
- Bỏ việc đẩy raw JSON vào `order.incoming` (L199-203). Message này không đúng dạng `WebhookOrderPayload` nên rơi vào DLQ.
- Thêm `TiktokOrderIngestionService.processOrderEvent(tenantId, connection, data)`:
  1. Gọi `TiktokOrderClient.getOrderDetails(connectionId, [data.order_id])`, tức `GET /order/202507/orders`.
  2. Gọi `ingestOrder` sẵn có (L66).
  3. Lỗi tạm thời trả 503 để simulator retry.

### 1.5 Không hồi trạng thái
- Thêm `platformUpdateTime` (epoch giây, tùy chọn) vào `WebhookOrderPayload` trong `smartomni-common`. Điền từ `update_time` của order detail trong `ingestOrder` (L169-181).
- `OrderService.processIncomingOrder` (order service, L63-80) bỏ qua cập nhật khi `payload.platformUpdateTime < order.platformUpdatedAt`. Khi giá trị là null thì bỏ qua phép so sánh, để Shopee không bị ảnh hưởng.
- `V26`: `ALTER TABLE orders ADD COLUMN platform_updated_at timestamptz`. Cập nhật entity `Order`.

### 1.6 SKU chưa ánh xạ
- `ingestOrder` (L100-103) vẫn tạo đơn nhưng đặt `reconciliationReason = "SKU_UNMAPPED"`.
- `V26`: thêm cột `orders.reconciliation_reason varchar(64)`.
- Không gán SKU đầu tiên và không trừ kho.

### 1.7 Polling đơn theo `update_time` có checkpoint (P1 #9)
- `V26` thêm bảng `tiktok_sync_checkpoints(tenant_id, connection_id, resource, last_update_time, updated_at)`:
  - unique `(connection_id, resource)`;
  - RLS bật và ép buộc như các bảng tenant khác (V5);
  - grant cho `smartomni_app` (V4).
- `scheduler/OrderPollingScheduler` (L105-114):
  - Lọc `updateTimeFrom = checkpoint` (gồm) và `updateTimeTo = now` (không gồm).
  - Giữ vòng lặp `page_token`.
  - Chỉ ghi checkpoint khi đã ingest xong cả cửa sổ.
  - Lần chạy đầu tiên dùng `now - lookback`.
- `TiktokOrderClient` (L107/110) đã gửi `update_time_ge` và `update_time_lt`, không cần sửa.

### 1.8 Đăng ký đủ topic (B11)
- Đổi `TiktokInventorySubscriptionService` thành `TiktokWebhookSubscriptionService.ensureSubscribed(connectionId)`. Service đăng ký 6 topic, mỗi topic một `PUT /event/202309/webhooks`, bỏ qua topic đã có:
  - `ORDER_STATUS_CHANGE`
  - `PACKAGE_UPDATE`
  - `RETURN_STATUS_CHANGE`
  - `CANCELLATION_STATUS_CHANGE`
  - `SELLER_DEAUTHORIZATION`
  - `INVENTORY_CHANGED_BY_SHOP`
- Thuộc tính mới `smartomni.tiktok.webhook-url` (`TIKTOK_WEBHOOK_URL`), fallback về `TIKTOK_INVENTORY_WEBHOOK_URL`.
- Thêm biến vào `docker-compose.yml` (L248) và `.env.example`.
- Cập nhật `TiktokInventorySubscriptionScheduler` và lời gọi ở `TiktokConnectionService:217`.

## Test

- `TiktokWebhookControllerTest`:
  - type 6, 11, 12 được route đúng;
  - cùng `tts_notification_id` chỉ xử lý một lần;
  - thiếu id trả 400;
  - type 1 gọi `getOrderDetails` và không còn publish raw JSON.
- Mới, trong `smartomni-common`: `ServiceSecurityAutoConfigurationWebhookTest` (MockMvc với controller giả). `POST` không JWT tới webhook không bị 401; đường dẫn khác vẫn 401.
- `TiktokOrderIngestionServiceTest`: đơn có SKU chưa ánh xạ được đặt `SKU_UNMAPPED` và có `platformUpdateTime`.
- `OrderLifecycleTest` (order service): event cũ không ghi đè trạng thái mới; payload không có `platformUpdateTime` vẫn được cập nhật.
- `OrderPollingSchedulerTest`: dùng cửa sổ `update_time` và checkpoint; checkpoint không tiến khi lỗi giữa chừng.
- `TiktokWebhookSubscriptionServiceTest` (đổi tên từ test cũ): đăng ký đủ 6 topic, chạy lại không đăng ký trùng.
- `DatabaseMigrationTest`:
  - 26 migration (L174, L189, L558, L640);
  - 55 bảng (L181, L183, L584);
  - bảng mới có RLS ép buộc.
- Lệnh: `mvn -pl smartomni-common,smartomni-service-integration,smartomni-service-order -am test` và `mvn -pl smartomni-migration-tests -am verify`.

## E2E với simulator

1. Đặt `TIKTOK_WEBHOOK_URL=http://integration-service:8086/api/v1/integrations/tiktok/webhooks`, rồi kết nối lại shop. `GET /event/202309/webhooks` phải thấy 6 topic.
2. Buyer thanh toán đơn. Simulator `deliveries` ghi HTTP 200, và đơn xuất hiện ở order service với đúng trạng thái.
3. Fault `DUPLICATE` chỉ tạo một bản ghi. Fault `REORDER` không làm hồi trạng thái.
4. Webhook ký sai trả 401. Webhook ký đúng không cần JWT.
5. Tắt webhook (fault `DROP`), đơn vẫn về qua polling bù.

## Commit

1. `fix(security): allow unauthenticated TikTok webhook POST`
2. `fix(integration): map TikTok webhook types to simulator values`
3. `fix(integration): dedupe TikTok webhooks by notification id`
4. `feat(db): add order platform update time, reconciliation reason and sync checkpoints`
5. `fix(integration): fetch order detail for TikTok order events`
6. `fix(order): ignore stale marketplace status updates`
7. `fix(integration): poll TikTok orders by update time with checkpoint`
8. `feat(integration): subscribe all TikTok webhook topics`

## Xong khi

- Test và migration test pass.
- 5 bước E2E đều đạt.
- Đã mở PR.
