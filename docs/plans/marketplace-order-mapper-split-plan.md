# Kế hoạch tách luồng đơn hàng theo sàn (Shopee / TikTok)

> Ngày lập: 2026-11-24. Cập nhật cùng ngày sau khi chốt D1–D3.
> Thay thế phần mapping trạng thái trong [`shopee-order-pipeline-fix-plan.md`](shopee-order-pipeline-fix-plan.md). Phần lỗi payload Shopee (L1, L5, L6) của file đó vẫn giữ nguyên.
> Căn cứ:
> - SmartOmni `origin/develop` @ `f9495df`
> - `../shopee-simulator` @ `4ce7c84`
> - `../Smartomni-Tiktok-Simulator` @ `a830fce` ("refuse cancel once shipment is arranged")
>
> Trạng thái: **đã triển khai** trên nhánh `refactor/order-marketplace-policies`.
> Cập nhật 2026-11-25: **bỏ `ON_HOLD` khỏi luồng sàn.** Simulator TikTok đã bỏ trạng thái này (DECISIONS H1) và luồng chính của SmartOmni không có bước giữ đơn. Shopee `IN_CANCEL` giữ đơn ở `TO_SHIP`; TikTok `ON_HOLD` là trạng thái lạ. Chỉ WF-05 còn đặt `ON_HOLD` (kiểm hàng không đạt). Các bảng ở mục 3.2, 4 và 7 đã sửa theo.

---

## 0. Mục tiêu và nguyên tắc

**Mục tiêu:** mỗi sàn có mapper, bảng chuyển trạng thái và quy tắc tồn kho/trả hàng **của riêng mình**, khớp đúng simulator của sàn đó. `OrderService` không còn biết tên sàn: không có `TiktokOrderStatusMapper` dùng chung, không có `if (platform == TIKTOK_SHOP)`.

**Nguyên tắc:**
1. **Simulator của sàn nào là nguồn sự thật cho sàn đó.**
   - Shopee đối chiếu `shopee-simulator`.
   - TikTok đối chiếu `Smartomni-Tiktok-Simulator` và `docs/smartomni-requirements/`.
   - Không lấy logic sàn này áp cho sàn kia.
2. **Mapper chỉ nhận trạng thái thô của đúng sàn đó.** Giá trị lạ thì ném `UNKNOWN_MARKETPLACE_ORDER_STATUS`, không đoán.
3. **Integration gửi trạng thái thô và `platform`. Việc map chỉ diễn ra ở order-service**, mỗi sàn đúng một chỗ.
   - TikTok đã làm vậy (`TiktokOrderIngestionService` gửi `order.getOrderStatus()`).
   - Shopee phải đổi: bỏ `ShopeeOrderMapper.toCanonicalStatus` ở integration.
4. **Phần dùng chung giữ trong `OrderService`:**
   - idempotency (tenant + platform + platform_order_id);
   - kiểm tra dữ liệu cũ bằng `platform_update_time`;
   - quy tắc nhân viên chỉ được chuyển PENDING → TO_SHIP;
   - ghi lịch sử, ghi ví (`recordWalletLedger`, đã idempotent qua `existsByOrderId`);
   - RLS và tenant scope.
5. Mỗi bảng mapping và bảng chuyển trạng thái trong code phải **ghi nguồn bằng chú thích**: file simulator và commit.
6. **`RETURNED` chỉ có một nguồn**: kiểm hàng trả nhập kho đạt (WF-05, `ReturnRefundService` đặt `RETURNED` và `restockStatus = RESTOCKED`). Không sàn nào được đặt `RETURNED` qua việc map trạng thái đơn.

---

## 1. Logic hai simulator (đã đọc code)

### 1.1 Shopee: `shopee-simulator`

- Enum (`prisma/schema.prisma`): `UNPAID, READY_TO_SHIP, PROCESSED, SHIPPED, TO_CONFIRM_RECEIVE, COMPLETED, IN_CANCEL, CANCELLED, TO_RETURN`.
- Vòng đời: tạo đơn → `READY_TO_SHIP` (mặc định) → `ship_order` → `PROCESSED` → `SHIPPED` → `COMPLETED`.
- `PROCESSED` → `IN_CANCEL` khi buyer xin hủy. Seller ACCEPT thì thành `CANCELLED`. Seller REJECT thì về `READY_TO_SHIP` (`order.router.ts`) hoặc `PROCESSED` (`simulator.router.ts`).
- `UNPAID`/`READY_TO_SHIP` → `CANCELLED` khi seller hủy hoặc quá hạn SLA.
- `TO_CONFIRM_RECEIVE` có trong enum nhưng **không bao giờ được đặt**.
- Webhook code 3 có dạng `{ordersn, status (thô), update_time}`. Code 3 và code 4 được gửi song song nên có thể đến lệch thứ tự. Retry theo 5 phút, 30 phút, 3 giờ.
- Tồn kho: simulator **không tự trừ hay cộng** theo đơn. Chỉ `update_stock` mới làm đổi tồn kho, và khi đó gửi code 8 với giá trị tuyệt đối.

**Trả hàng (`TO_RETURN`)**, đọc từ `simulator.router.ts` ~3480, 3595 và `returns.router.ts` ~270, 410:
- Buyer mở yêu cầu trả (**kể cả trả một phần**): đơn `COMPLETED` → `TO_RETURN` ngay lập tức.
- Return bị hủy, đóng hoặc dispute thắng: đơn về `COMPLETED` nếu không còn return nào đang mở.
- Seller chấp nhận: đơn giữ `TO_RETURN` (hoàn tiền toàn phần, hoặc escrow về 0); trường hợp khác giữ nguyên trạng thái hiện có.
- **Mọi thay đổi này chỉ gửi push code 29** (`return_updates_push`), **không gửi push code 3**. SmartOmni chỉ thấy `TO_RETURN` khi tự gọi `get_order_detail` (polling đơn thiếu, hoặc một sync khác).

### 1.2 TikTok: `Smartomni-Tiktok-Simulator`

- Enum (`backend/src/orders/order-state.ts`): `UNPAID, AWAITING_SHIPMENT, PARTIALLY_SHIPPING, AWAITING_COLLECTION, IN_TRANSIT, DELIVERED, COMPLETED, CANCELLED`. **Không có `ON_HOLD`** (DECISIONS H1). `ON_HOLD` chỉ có trong danh sách trạng thái của OAS.
- `TRANSITIONS` sau commit `a830fce` (DECISIONS H14):
  ```
  UNPAID              → AWAITING_SHIPMENT | CANCELLED
  AWAITING_SHIPMENT   → PARTIALLY_SHIPPING | AWAITING_COLLECTION | CANCELLED
  PARTIALLY_SHIPPING  → AWAITING_COLLECTION
  AWAITING_COLLECTION → IN_TRANSIT
  IN_TRANSIT          → DELIVERED
  DELIVERED           → COMPLETED
  COMPLETED, CANCELLED: trạng thái cuối
  ```
  Đã xác nhận mọi đường hủy (`sellerCancel`, buyer cancel, duyệt cancellation, test console) đều đi qua `canTransition`. `sellerCancel` còn chặn riêng: chỉ cho hủy khi `UNPAID`/`AWAITING_SHIPMENT`.
- Mỗi lần chuyển trạng thái đều phát `ORDER_STATUS_CHANGE`, kể cả `DELIVERED` và `COMPLETED`. `DELIVERED → COMPLETED` **không tự động** theo giờ, chỉ xảy ra khi seller hoặc test console đẩy bước.
- Order detail trả về `update_time`, `paid_time`, `rts_time`, `collection_time` và `delivery_time` (`order.mapper.ts`).
- `DELIVERED` **không phải** `COMPLETED`. Điều kiện loyalty yêu cầu `COMPLETED` (phase-09: "DELIVERED is not enough"; thời điểm hoàn tất = `update_time` của lần chuyển sang COMPLETED).
- Trả hàng mở được từ `DELIVERED` hoặc `COMPLETED` và **không đổi trạng thái đơn** (phase-07, 07b.2). Hoàn kho qua phiếu nhập idempotent khi `RETURN_OR_REFUND_REQUEST_SUCCESS`/`_COMPLETE`. Phần này đã có trong `handleTiktokReturn`.
- Tồn kho: simulator tự giữ và nhả reservation. SmartOmni lấy tồn tuyệt đối qua `INVENTORY_CHANGED_BY_SHOP` (type 71) và **không trừ kho theo đơn**.

---

## 2. Lỗi hiện tại do dùng chung

| # | Sàn | Lỗi | Vị trí (develop) |
|---|---|---|---|
| M1 | Shopee | Đi qua `TiktokOrderStatusMapper`: `PENDING/TO_SHIP/SHIPPED/DELIVERING/RETURNED` và `READY_TO_SHIP/PROCESSED/...` đều bị coi là trạng thái lạ | `OrderService` dòng 92, 115, 235 |
| M2 | Cả hai | `isValidTransition` dùng chung và chỉ cho bước kề nhau. Polling 3 phút có thể nhảy cóc (TO_SHIP → COMPLETED) và bị từ chối | `OrderService` dòng 182 |
| M3 | TikTok | `DELIVERED → COMPLETED`: ghi ví và tính điểm loyalty **sớm**, trái phase-09 | `TiktokOrderStatusMapper` |
| ~~M4~~ | TikTok | ~~Hủy ở AWAITING_COLLECTION/PARTIALLY_SHIPPING bị chặn~~. **Hết lỗi**: simulator đã sửa ở `a830fce`, và mapping `SHIPPED` hiện tại khớp | — |
| M5 | TikTok | Còn map `IN_CANCEL`, giá trị không có trong simulator lẫn OAS | `TiktokOrderStatusMapper` |
| M6 | Shopee | `IN_CANCEL → CANCELLED` (ở integration). Khi seller REJECT, SmartOmni đã hủy và hoàn kho sai | `ShopeeOrderMapper.toCanonicalStatus` |
| M7 | Shopee | `TO_RETURN → RETURNED`: đặt RETURNED trước khi kiểm hàng, sai với trả một phần, và không về lại COMPLETED được | như trên |
| M8 | Shopee | Đường trả hàng chung chuyển COMPLETED → ON_HOLD, nhưng `isValidTransition` cấm chuyển này | `OrderService.handleOrderReturn` dòng 394, 427, 473 |
| M9 | Shopee | Đơn mới về với trạng thái `CANCELLED` (qua polling) vẫn bị trừ kho | `OrderService` dòng 140 |
| M10 | Cả hai | `updateOrderTracking` và `handleOrderReturn` mặc định coi request thiếu `platform` là TikTok | `OrderService` dòng 251, 333 |
| M11 | Cả hai | Chuyển trạng thái không hợp lệ từ webhook/polling thì ném lỗi, nên message bị retry rồi vào DLQ, trong khi chỉ cần bỏ qua | `OrderService.updateOrderStatus` |
| M12 | TikTok | **Đường thứ ba ghi đơn**: `POST /api/v1/integrations/tiktok/orders/sync` → `TiktokOrderSyncService.upsertOrder` ghi thẳng `orders` bằng JDBC với `mapStatus` riêng. Hàm này map `DELIVERED → completed` và giá trị lạ → `pending`, ghi đè trạng thái không kiểm tra chuyển trạng thái, không xét `update_time`, không ghi lịch sử | `integration/service/tiktok/TiktokOrderSyncService.java` dòng 157, 198, 311 |
| M13 | TikTok | Đọc `completed_time`, trường mà simulator và OAS không có, nên lần nào cũng gọi thêm order detail và đặt `completedAt = now()` | `TiktokOrderSyncService` dòng 138, 170 |

---

## 3. Thiết kế

### 3.1 Cấu trúc package (order-service)

```
order/marketplace/
  MarketplaceOrderPolicy.java          // interface
  MarketplaceOrderPolicyRegistry.java  // Map<Platform, Policy>, thiếu policy thì lỗi ngay khi khởi động
  shopee/
    ShopeeOrderStatusMapper.java
    ShopeeOrderPolicy.java
    ShopeeReturnHandler.java
  tiktok/
    TiktokOrderStatusMapper.java       // chuyển từ order/service/, sửa theo mục 4.2
    TiktokOrderPolicy.java
    TiktokReturnHandler.java           // tách handleTiktokReturn và các hàm phụ khỏi OrderService
```

```java
public interface MarketplaceOrderPolicy {
    Order.Platform platform();
    Order.OrderStatus mapStatus(String rawPlatformStatus);               // chỉ nhận trạng thái thô của sàn này
    boolean canTransition(Order.OrderStatus from, Order.OrderStatus to); // bảng riêng của sàn
    boolean deductStockOnCreate(Order.OrderStatus initialStatus);        // Shopee: true nếu không phải CANCELLED; TikTok: false
    boolean restockOnCancel(Order.OrderStatus previousStatus, Order order); // Shopee: trước khi bàn giao vận chuyển; TikTok: false
    Order handleReturn(Long tenantId, Order order, ProcessReturnRequest request);
}
```

`OrderService` chỉ gọi `registry.forPlatform(order.getPlatform())`. Mọi `TiktokOrderStatusMapper.*` và `if (platform == TIKTOK_SHOP)` trong `OrderService` đều bị bỏ.

### 3.2 Quy tắc chuyển trạng thái chung

- **Tiến thẳng, kể cả nhảy cóc**, theo thứ tự `PENDING < TO_SHIP < SHIPPED < DELIVERING < COMPLETED`. Lý do: polling 3 phút và webhook bị mất chỉ thấy trạng thái mới nhất. Nhánh phụ hủy đơn theo bảng riêng của từng sàn ở mục 4. Không sàn nào đưa đơn vào `ON_HOLD`.
- **Không lùi.** Nguồn là `MARKETPLACE_WEBHOOK`/`POLLING` mà chuyển không hợp lệ thì **ghi log và bỏ qua** (không ném lỗi, không vào DLQ), và đặt `reconciliationReason = STATUS_CONFLICT` để đối soát (sửa M11). Nguồn là `STAFF` thì vẫn ném lỗi như hiện tại.
- Trạng thái mới trùng trạng thái hiện tại thì không làm gì (ví dụ READY_TO_SHIP và PROCESSED cùng là TO_SHIP).

---

## 4. Mapping và chuyển trạng thái theo từng sàn

### 4.1 Shopee (`ShopeeOrderStatusMapper`, `ShopeeOrderPolicy`)

| Shopee thô | SmartOmni | Ghi chú |
|---|---|---|
| `UNPAID` | `PENDING` | |
| `READY_TO_SHIP` | `TO_SHIP` | |
| `PROCESSED` | `TO_SHIP` | Trùng với READY_TO_SHIP thì không đổi trạng thái, không ghi lịch sử |
| `SHIPPED` | `SHIPPED` | |
| `TO_CONFIRM_RECEIVE` | `DELIVERING` | Simulator không phát ra. Giữ cho Shopee thật |
| `COMPLETED` | `COMPLETED` | |
| `IN_CANCEL` | `TO_SHIP` | Buyer xin hủy, chưa phải trạng thái cuối: đơn giữ TO_SHIP, không hoàn kho, không ghi lịch sử. SmartOmni không có bước `ON_HOLD` |
| `CANCELLED` | `CANCELLED` | |
| `TO_RETURN` | **`COMPLETED`** | Simulator chỉ vào TO_RETURN từ COMPLETED. Đơn đã COMPLETED thì không đổi; đơn đang RETURNED thì bỏ qua (không lùi). Tiến trình trả hàng nằm ở `return_refund` (D1) |
| khác | lỗi `UNKNOWN_MARKETPLACE_ORDER_STATUS` | |

Bảng chuyển trạng thái Shopee (ngoài các bước tiến thẳng ở 3.2):

| Từ | Được sang | Căn cứ simulator |
|---|---|---|
| `PENDING`, `TO_SHIP` | `CANCELLED` | seller hủy, quá hạn SLA, hoặc seller ACCEPT yêu cầu hủy (IN_CANCEL → CANCELLED) |
| `TO_SHIP` | (giữ nguyên) | PROCESSED → IN_CANCEL, và REJECT → READY_TO_SHIP/PROCESSED |
| `SHIPPED`, `DELIVERING` | (không hủy) | simulator không có hủy sau khi giao vận chuyển |
| `COMPLETED` | `RETURNED` | **chỉ qua WF-05** (kiểm hàng đạt), không qua mapper |

Tồn kho Shopee:
- Đơn mới đã map SKU và không phải CANCELLED: trừ kho, outbox đẩy tồn tuyệt đối (`update_stock`).
- CANCELLED trước khi bàn giao vận chuyển: hoàn kho 1 lần.
- IN_CANCEL: không làm gì (đơn giữ TO_SHIP).
- Trả hàng: chỉ hoàn kho khi WF-05 kiểm đạt (logic sẵn có, idempotency key theo return).

`ShopeeReturnHandler` (push code 29 → `ShopeeReturnSyncService` → `handleOrderReturn`):
- Chỉ ghi và cập nhật `return_refund` cùng các item.
- **Không đổi trạng thái đơn** (bỏ các lệnh đặt ON_HOLD hay RETURNED ở đường trả hàng chung; sửa M8).
- Return `CANCELLED`/`CLOSED`: đóng bản ghi return, đơn giữ COMPLETED (khớp việc simulator đưa đơn về COMPLETED).

### 4.2 TikTok (`TiktokOrderStatusMapper`, `TiktokOrderPolicy`)

| TikTok thô | Hiện tại | **Mới** | Lý do |
|---|---|---|---|
| `UNPAID` / `100` | PENDING | `PENDING` | |
| `AWAITING_SHIPMENT` / `111` | TO_SHIP | `TO_SHIP` | |
| `PARTIALLY_SHIPPING` / `114` | SHIPPED | `SHIPPED` | Giữ nguyên. Không hủy được nữa (`a830fce`) |
| `AWAITING_COLLECTION` / `112` | SHIPPED | `SHIPPED` | Giữ nguyên. Không hủy được nữa (`a830fce`) |
| `IN_TRANSIT` / `121` | SHIPPED | `SHIPPED` | |
| `DELIVERED` / `122` | COMPLETED | **`DELIVERING`** | DELIVERED khác COMPLETED theo phase-09 (M3). Cùng nghĩa với Shopee `TO_CONFIRM_RECEIVE`: đã giao, chờ hoàn tất |
| `COMPLETED` / `130` | COMPLETED | `COMPLETED` | |
| `CANCELLED`, `CANCELED` / `140` | CANCELLED | `CANCELLED` | |
| `ON_HOLD` | ON_HOLD | **bỏ, coi là trạng thái lạ** | Simulator đã bỏ (DECISIONS H1); SmartOmni không có bước `ON_HOLD` (cập nhật 2026-11-25) |
| `IN_CANCEL` | ON_HOLD | **bỏ, coi là trạng thái lạ** | Không có trong simulator lẫn OAS (M5) |

Bảng chuyển trạng thái TikTok (ngoài các bước tiến thẳng ở 3.2), suy ra từ `TRANSITIONS` @ `a830fce`:

| Từ | Được sang |
|---|---|
| `PENDING`, `TO_SHIP` | `CANCELLED` |
| `SHIPPED`, `DELIVERING` | (không hủy) |
| `COMPLETED`, `CANCELLED` | trạng thái cuối. TikTok không bao giờ vào RETURNED, vì trả hàng không đổi trạng thái đơn |

Thời điểm hoàn tất của TikTok: khi sang COMPLETED, đặt `completedAt` bằng `platform_update_time` nếu có, thay vì `Instant.now()`. Đây là thời điểm phase-09 dùng để tính đủ 15 ngày cho loyalty.

Tồn kho TikTok:
- Không trừ kho khi có đơn, không hoàn kho khi hủy. Tồn tuyệt đối đến từ type 71.
- Trả hàng: `TiktokReturnHandler` giữ đúng logic hiện có của `handleTiktokReturn` (chỉ di chuyển, không đổi hành vi).

### 4.3 Mốc thời gian giao hàng TikTok (sửa phần "hiển thị chưa đủ")

Nguồn: `backend/src/orders/order.mapper.ts` (`orderItem`) và `orders.service.ts` (`transition`) @ `a830fce`.

| Trường OAS | Simulator đặt khi | Cột SmartOmni mới (`orders`) |
|---|---|---|
| `rts_time` | sang `AWAITING_COLLECTION` | `platform_rts_at` |
| `collection_time` | sang `IN_TRANSIT` | `platform_collected_at` |
| `delivery_time` | sang `DELIVERED` | `platform_delivered_at` |
| `cancel_time` | sang `CANCELLED` | `platform_cancelled_at` |

Đặc điểm của simulator cần tôn trọng:
- Các trường chỉ có trong **Get Order Detail/List** (`/order/202507/orders`). Webhook `ORDER_STATUS_CHANGE` chỉ có `order_id`, `order_status` và `update_time`. SmartOmni đã gọi detail sau mỗi webhook (`processOrderEvent`) nên **không cần gọi thêm API**.
- Trường nào chưa xảy ra thì **bị bỏ khỏi JSON** (`defined()`), không phải 0 hay null. Mỗi mốc chỉ đặt một lần.
- **Không có trường thời điểm hoàn tất.** Theo phase-09, thời điểm hoàn tất là `update_time` của lần chuyển sang COMPLETED.
- Khi chạy kịch bản loyalty, đồng hồ nghiệp vụ bị lùi, nên các mốc nằm trong quá khứ. Phải dùng giá trị của sàn, không dùng `now()`.

Quy tắc khi code (để không lệch simulator):
1. **Trạng thái chỉ lấy từ `status`.** Không suy trạng thái từ việc có mốc thời gian (ví dụ có `delivery_time` thì coi là DELIVERED).
2. Mốc thời gian chỉ để hiển thị và đối soát. **Không dùng `delivery_time` làm `completedAt`**, và không để mốc này kích hoạt ví hay loyalty.
3. Thiếu trường thì lưu null, không lưu 0. **Không ghi đè giá trị đã có bằng null.** Message cũ (`update_time` nhỏ hơn) bị bỏ qua cả phần mốc thời gian.
4. Đơn vị là epoch giây, dùng chung `platformInstant()` sẵn có.
5. Giữ `courier_scanned_at` như hiện tại (lấy từ `PACKAGE_UPDATE`). Không trộn với `platform_collected_at`, để không đổi logic hoàn kho.
6. Shopee để các cột này null ở giai đoạn này. Cột nullable nên không ảnh hưởng Shopee.
7. Bỏ `completed_time` khỏi `TiktokOrderDetailResponse` (M13).

Thay đổi cần làm:
- Migration `V28__order_platform_milestones.sql` thêm 4 cột `timestamptz NULL`. Kiểm số migration mới nhất trước khi đặt tên, và cập nhật số đếm trong `smartomni-migration-tests`.
- Thêm `rts_time`, `collection_time`, `delivery_time` và `cancel_time` vào `TiktokOrderDetailResponse`.
- Thêm `platform_rts_time`, `platform_collection_time`, `platform_delivery_time` và `platform_cancel_time` (tùy chọn) vào `common.dto.WebhookOrderPayload`.
- `TiktokOrderIngestionService` điền các trường này. `OrderService` lưu theo quy tắc 3.
- Entity `Order` thêm các field này, và `OrderDetailDto` trả chúng ra cho FE.

### 4.4 Gộp đường sync thủ công của TikTok vào order-service (M12, M13)

`TiktokOrderSyncService.upsertOrder` là một mapper TikTok thứ hai, nằm ngoài order-service. Nếu chỉ sửa `TiktokOrderStatusMapper`, đơn DELIVERED đồng bộ qua nút "Sync" **vẫn bị ghi thành COMPLETED**, ngược với D3.

Cách sửa:
- `syncOrders` không ghi DB nữa. Mỗi đơn được dựng thành `WebhookOrderPayload` bằng cùng hàm của `TiktokOrderIngestionService`, rồi publish `order.incoming` với `source = POLLING`.
- Xóa `upsertOrder`, `syncOrderItems` và `mapStatus` trong `TiktokOrderSyncService`. Hàm `syncOrders` trả về số đơn đã đẩy vào queue.
- Bỏ điều kiện `completedMissingTime` khi lấy thêm detail; chỉ lấy thêm khi thiếu line items.
- Test: sync một đơn DELIVERED cho ra DELIVERING. Sync lại cùng đơn không tạo thêm đơn và không ghi ví.

---

## 5. Thay đổi phía integration

**Shopee**
- `ShopeeOrderMapper.toPayload` dựng `common.dto.WebhookOrderPayload` (snake_case) với `order_status` là trạng thái thô của Shopee, `platform_update_time` lấy từ `update_time`, và `sku_id`.
- Bỏ `toCanonicalStatus` và `ShopeeOrderPayload`. Test `ShopeeOrderMapperTest.toCanonicalStatus_Matrix` chuyển sang `ShopeeOrderStatusMapperTest` bên order.
- `ShopeeWebhookDispatcher.enqueueOrderEvent` (đường fallback) dùng cùng DTO, với `update_time` lấy từ `data`.

**TikTok**
- Giữ nguyên `TiktokOrderIngestionService`, vì đã gửi đúng snake_case cùng trạng thái thô và `platform_update_time`.

**Cả hai**
- Mọi message tracking và return phải có `platform` (sửa M10). Order-service bỏ việc mặc định TIKTOK_SHOP và trả 400 khi thiếu.

---

## 6. Các bước thực hiện

Nhánh: `refactor/order-marketplace-policies` tạo từ `origin/develop`. Mỗi bước là một commit chạy test xanh.

1. **Test bảo vệ hành vi hiện có**: chạy `OrderLifecycleTest`, `TiktokOrderStatusMapperTest`, `ReturnRefundServiceTest`, `ShopeeOrderMapperTest` và ghi lại kết quả gốc.
2. **Tạo khung**: `MarketplaceOrderPolicy`, registry và hai policy tạm thời bọc đúng logic cũ. `OrderService` gọi qua registry. Hành vi không đổi, test cũ vẫn xanh.
3. **Tách `TiktokReturnHandler`** khỏi `OrderService`, chỉ di chuyển code.
4. **Quy tắc chung 3.2**: tiến thẳng có nhảy cóc, bỏ qua chuyển trạng thái không hợp lệ từ sàn (M2, M11).
5. **Shopee**: thêm `ShopeeOrderStatusMapper` và bảng chuyển trạng thái (4.1), `ShopeeReturnHandler`, sửa M8 và M9. Sửa integration Shopee (mục 5). Thêm test kịch bản Shopee (mục 7).
6. **TikTok**: sửa mapper theo 4.2 (M3: DELIVERED → DELIVERING; M5: bỏ IN_CANCEL), đặt `completedAt` theo `platform_update_time`. Cập nhật `TiktokOrderStatusMapperTest` và `OrderLifecycleTest` có ghi lý do. Đây là **thay đổi hành vi TikTok** (xem mục 8).
7. **TikTok sync thủ công** (4.4): đưa `TiktokOrderSyncService` về đường queue chung. **Phải làm cùng PR với bước 6**, nếu không D3 chỉ đúng một nửa.
8. **Mốc thời gian TikTok** (4.3): migration, DTO, entity, `OrderDetailDto`.
9. **Dọn dẹp**: bỏ default platform (M10), sửa chú thích sai về Shopee trong `WebhookOrderPayload`, cập nhật `docs/architecture.md`.
10. **Kiểm chứng**: `mvn -pl smartomni-service-order,smartomni-service-integration,smartomni-common,smartomni-db-migrations -am test`, `smartomni-migration-tests` nếu có Docker, rồi chạy E2E với cả hai simulator.

Migration: chỉ bước 8 cần (V28, 4 cột nullable). `ON_HOLD`, `DELIVERING` và `RETURNED` đều đã có trong enum SQL.

---

## 7. Test bắt buộc (mỗi sàn có bộ riêng)

**Shopee**, đối chiếu `test/phase4_order`, `phase5_webhook`, `phase10_lifecycle_deadline` và `phase13_returns_disputes` của simulator:
- Mapper đủ 9 giá trị enum, giá trị lạ thì lỗi.
- READY_TO_SHIP → PROCESSED → SHIPPED → COMPLETED. Ví ghi đúng 1 lần.
- PROCESSED → IN_CANCEL → REJECT → READY_TO_SHIP: không hoàn kho, đơn giữ TO_SHIP, không ghi lịch sử.
- IN_CANCEL → ACCEPT → CANCELLED: hoàn kho đúng 1 lần.
- Đơn COMPLETED nhận TO_RETURN: trạng thái giữ COMPLETED, không ghi lịch sử, không ghi ví lần hai.
- Đơn RETURNED (sau WF-05) nhận TO_RETURN/COMPLETED: bỏ qua, không lỗi, không vào DLQ.
- Push 29 với return một phần, rồi return bị hủy: `return_refund` cập nhật, trạng thái đơn không đổi.
- Đơn mới về đã CANCELLED: không trừ kho.
- Webhook trùng (retry) và webhook cũ (`update_time` nhỏ hơn): bỏ qua.
- Serialize payload qua `Jackson2JsonMessageConverter` rồi đọc ở order: đủ field.

**TikTok**, đối chiếu `backend/src/orders/order-state.ts` @ `a830fce` và `docs/smartomni-requirements/phase-07.md`, `phase-09.md`:
- Mapper đủ 8 trạng thái của simulator; `ON_HOLD` và `IN_CANCEL` thì lỗi.
- Duyệt mọi cặp trong `TRANSITIONS`: cặp hợp lệ thì được. `AWAITING_COLLECTION`/`IN_TRANSIT` → `CANCELLED` bị bỏ qua (ghi log, không lỗi).
- DELIVERED → DELIVERING: chưa ghi ví, chưa có `eligibleForPointsAt`. COMPLETED mới ghi, và `completedAt` bằng `update_time` của sàn.
- Nhảy cóc từ AWAITING_SHIPMENT sang COMPLETED (do mất webhook) vẫn được chấp nhận.
- Trả hàng mở khi đơn đang DELIVERING: không đổi trạng thái đơn; hoàn kho idempotent (giữ test hiện có).
- Mốc thời gian: thiếu trường thì giữ null. Message sau thiếu trường không xóa giá trị đã có. Message cũ không ghi đè. `delivery_time` không làm đổi `completedAt`.
- Sync thủ công một đơn DELIVERED cho ra DELIVERING, đi qua queue chung, sync lại không tạo trùng.

**Cách ly giữa hai sàn**: một test thay đổi bảng của Shopee không làm đổi kết quả của TikTok, và ngược lại (test registry trả đúng policy theo platform).

---

## 8. Quyết định đã chốt và ảnh hưởng

| # | Quyết định | Ảnh hưởng cần biết |
|---|---|---|
| D1 | Shopee `TO_RETURN` → `COMPLETED`. Trả hàng theo dõi qua `return_refund`; `RETURNED` chỉ do WF-05 đặt | Trong lúc có return đang mở, simulator hiển thị `TO_RETURN` còn SmartOmni hiển thị COMPLETED. FE cần badge "Đang trả hàng" lấy từ `return_refund` |
| D2 | TikTok giữ `AWAITING_COLLECTION`/`PARTIALLY_SHIPPING` → `SHIPPED` (simulator đã sửa ở `a830fce`) | Không đổi code mapping, không đổi FE. Nút hủy đơn TikTok trên FE chỉ nên hiện ở PENDING/TO_SHIP |
| D3 | TikTok `DELIVERED` → `DELIVERING`, giống Shopee `TO_CONFIRM_RECEIVE` | Xem mục 8.1 |
| D4 | Đơn thủ công mặc định `SHOPEE` (`createManualOrder` dòng 812) | **Đã xử lý 2026-11-25:** bắt buộc `platform` (400 `PLATFORM_REQUIRED`/`UNKNOWN_PLATFORM`); mã đơn luôn có tiền tố `MANUAL-` để không trùng mã đơn sàn; mã đã tồn tại thì 409 `DUPLICATE_PLATFORM_ORDER_ID` |
| D5 | Shopee push code 8 (tồn kho đổi trên sàn) | Ngoài phạm vi; vẫn bỏ qua |

### 8.1 Ảnh hưởng của D3

- **Ví và loyalty ghi muộn hơn, nhưng đúng**: chỉ ghi khi COMPLETED, không còn ghi lúc DELIVERED. Doanh thu trên dashboard (tính theo COMPLETED) sẽ trễ hơn trước với đơn TikTok.
- **Simulator không tự hoàn tất đơn**: đơn ở DELIVERING cho tới khi seller hoặc test console đẩy sang COMPLETED. Khi đó simulator gửi `ORDER_STATUS_CHANGE`, nên SmartOmni nhận được.
- **Dữ liệu cũ**: các đơn đã lỡ thành COMPLETED từ DELIVERED vẫn giữ COMPLETED (không lùi, không backfill).
- **Backend không có logic riêng cho `DELIVERING`** (đã grep `origin/develop`), nên rủi ro ở backend thấp.
- **Hiển thị**: SmartOmni biết được trạng thái "đã giao, chờ hoàn tất" và giờ chuyển trạng thái (lưu trong `order_status_history`). Giờ giao thực tế của sàn (`delivery_time`, `collection_time`, `rts_time`, `cancel_time`) được bổ sung theo **mục 4.3**, chỉ đọc từ order detail và không ảnh hưởng trạng thái, ví hay loyalty.
- **Đường sync thủ công** phải sửa cùng lúc (mục 4.4). Nếu không, đơn DELIVERED vẫn bị ghi COMPLETED qua nút "Sync".
- **FE**: repo FE không có ở đây nên chưa kiểm tra được nhãn. Nhãn của `DELIVERING` nên là "Đã giao – chờ hoàn tất" cho cả hai sàn, không phải "Đang giao".

---

## 9. Checklist khi code

- [ ] Đã mở file simulator tương ứng trước khi sửa mapping hoặc bảng chuyển trạng thái của sàn đó, và ghi nguồn (file và commit) trong chú thích.
- [ ] Không có import chéo: `marketplace/shopee` không dùng gì từ `marketplace/tiktok`, và ngược lại.
- [ ] `OrderService` không còn chữ `Tiktok`/`Shopee` và không còn `Platform.TIKTOK_SHOP`/`Platform.SHOPEE` (kiểm bằng grep).
- [ ] Integration gửi trạng thái thô; order là nơi duy nhất map.
- [ ] `RETURNED` chỉ được đặt bởi WF-05.
- [ ] Mỗi thay đổi hành vi của TikTok (bước 6) có test cập nhật kèm lý do trỏ tới tài liệu simulator.
- [ ] RLS: consumer mở tenant scope trước transaction, không `set_config` ở scope session, không bypass RLS.
- [ ] `mvn -B clean verify` xanh trước khi mở PR.
