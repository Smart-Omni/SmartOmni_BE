# Tích điểm khách hàng và voucher theo hạng

Đi kèm [storefront-public-catalog.md](storefront-public-catalog.md): storefront cho khách xem sản phẩm, xem chi tiết và bấm mua; spec này lo phần tích điểm sau khi mua.

## Mục tiêu

1. Khách đăng nhập storefront, dán **mã đơn trên sàn** (TikTok/Shopee) để nhận điểm. Không kiểm tra người mua; chỉ kiểm tra đơn hợp lệ và **chưa được claim**.
2. Điểm = `floor(tổng tiền đơn / 1000)`, chỉ claim được khi đơn đã hoàn thành **đủ 15 ngày**.
3. Trang tích điểm riêng: thanh ngang thể hiện số điểm so với các mốc hạng.
4. Shop cấu hình mốc điểm và % voucher **cho từng sàn**. Gợi ý sẵn: Gold 500 điểm giảm 10%, Platinum 1000 giảm 20%, Diamond 1500 giảm 30%.
5. Khách bấm **Nhận voucher** ở mốc đã đạt; mã được tạo trên sàn qua API voucher của simulator.

## Quyết định đã chốt (2026-11-27)

| Câu hỏi | Chọn |
|---|---|
| Khách nhập gì để claim | Mã đơn trên sàn |
| Ví điểm | Chung một ví cho mọi sàn; mốc và % riêng theo sàn |
| Phát voucher | Khách bấm nhận, mỗi mốc một lần, điểm không bị trừ |

## Hiện trạng (đã có sẵn, sẽ dùng lại)

- **Claim** (`POST /api/v1/loyalty/claims`, `LoyaltyCustomerService`): nhận **ID đơn nội bộ** + `totalAmount` phải khớp. Đã kiểm tra: đơn `completed`, `eligible_for_points_at <= now()`, tổng ≥ 1.000đ, không có hoàn trả `approved/completed`, chưa claim. Trigger DB kiểm tra lại và ghi ledger, cộng `loyalty_point_accounts.total_points`.
- **Ngày đủ điều kiện**: `OrderService` đặt `eligible_for_points_at = completed_at + 15 ngày`. DB local: 12 đơn TikTok `completed` đều có ngày này (ví dụ đơn `5760028171077135663`, đủ điều kiện từ 2026-12-09).
- **Mốc và voucher**: bảng `loyalty_reward_campaigns` đã gắn với **một connection (= một sàn)**, có `required_points` và `discount_percent`. `LoyaltyRewardService` cho mỗi khách nhận một campaign một lần, không trừ điểm.
- **Đính chính khi triển khai (2026-11-27)**: `MarketplaceVoucherAdapterService` **không** tạo voucher qua API cho cả hai sàn như bản đầu ghi:
  - **TikTok** (giống TikTok thật và simulator): không có API tạo coupon. Chỉ chạy chế độ `import`: shop tạo sẵn **lô mã** trên Seller Center, SmartOmni phát lần lượt từng mã của lô có tiêu đề chứa **tên campaign** (= tên hạng). % giảm do lô mã quyết định.
  - **Shopee**: trước đây luôn báo lỗi. Đã thêm chế độ `api` gọi `v2.voucher.add_voucher` của simulator: voucher dùng 1 lần, giảm theo %, mã 5 ký tự sinh từ `requestKey` (worker retry không tạo trùng), hiệu lực tối đa 90 ngày.
- **Hạng**: `LoyaltyTierCalculator` viết cứng Standard/Silver/Platinum/Diamond ở 500/1000/1500; cột sinh `loyalty_point_accounts.tier` cũng viết cứng `silver`.
- **FE**: đã có `ApiCustomerLoyalty.jsx` (profile, claim, campaign, voucher) và `ApiMerchantLoyalty.jsx` (tạo campaign) — dạng bảng, chưa có thanh tiến độ.

## Thiết kế

### 1. Claim bằng mã đơn trên sàn

`POST /api/v1/loyalty/claims` nhận thêm dạng mới, giữ dạng cũ để không vỡ FE hiện tại:

```json
{ "platformOrderId": "5760028171077135663", "platform": "tiktok_shop" }
```

- `platform` tùy chọn. Tìm `orders` theo `tenant_id` + `platform_order_id` (+ `platform` nếu có). Nếu khớp nhiều đơn ở các sàn khác nhau → 409 `PLATFORM_REQUIRED`.
- Bỏ yêu cầu `totalAmount` ở dạng mới; điểm tính theo `orders.total_amount` trong DB. Chấp nhận rủi ro: ai biết mã đơn đều claim được (đúng yêu cầu "không cần đúng người mua"); chống đoán mò bằng giới hạn tần suất claim sai theo khách (ví dụ 10 lần/giờ, Redis).
- Thứ tự lỗi trả cho khách: không tìm thấy (404) → chưa hoàn thành (400 `ORDER_NOT_COMPLETED`) → chưa đủ 15 ngày (400 `ORDER_NOT_ELIGIBLE_YET`, **kèm ngày đủ điều kiện**) → có hoàn trả (400) → đã claim (409 `ORDER_ALREADY_CLAIMED`).

### 2. Thiếu dữ kiện thì đọc từ TikTok

Khi đơn **không có trong DB** hoặc có nhưng **chưa `completed`** (đồng bộ chậm), và tenant có kết nối TikTok đang `connected`:

```
order-service ──► integration  POST /internal/integrations/orders/tiktok/refresh
                   { platformOrderId }
                   → TiktokOrderClient.getOrderDetail(connectionId, orderId) trên kết nối TikTok của tenant
                   → nếu tìm thấy: đưa qua TiktokOrderIngestionService (như luồng polling, qua RabbitMQ)
order-service: chờ tối đa ~3 giây (6 × 0,5 s) cho đơn xuất hiện, rồi kiểm tra như mục 1
```

- Ingestion là bất đồng bộ: TikTok báo `COMPLETED` nhưng hết thời gian chờ mà đơn chưa về → 409 `ORDER_SYNCING` ("thử lại sau ít phút"). TikTok báo trạng thái khác → 400 `ORDER_NOT_COMPLETED` kèm trạng thái.
- `TiktokOrderPolicy.completedAt` đã lấy `update_time` của sàn (không dùng `now()`), nên đơn cũ đọc bù không bị lùi ngày đủ điều kiện.

- Gọi nội bộ bằng cơ chế xác thực service đang dùng cho `/internal/integrations/vouchers`; không mở endpoint công khai.
- Shopee: chỉ dùng dữ liệu local (polling/webhook hiện có). Nếu cần, thêm sau bằng `ShopeeOrderClient.getOrderDetail`.
- Lỗi khi gọi TikTok → trả lỗi gốc (404/400) như mục 1, không claim.

### 3. Mốc hạng theo sàn = campaign

Mỗi mốc là **một `loyalty_reward_campaigns`** của connection đó: `name` = tên hạng, `required_points`, `discount_percent`. **Không cần bảng mới.**

- Màn cấu hình của shop: mỗi sàn đang kết nối có 3 dòng mốc, nút **Dùng gợi ý** điền Gold/Platinum/Diamond 500/1000/1500, 10/20/30%. Shop sửa tên, điểm, % rồi lưu.
- Các trường campaign khác điền mặc định khi tạo từ màn này: `quantity` 1000, `starts_at` = lúc lưu, `ends_at` = +1 năm, `status = active`; `provisioning_mode` = `import` cho TikTok, `api` cho Shopee. Màn quản lý campaign cũ vẫn dùng được để chỉnh chi tiết.
- **TikTok**: tên hạng phải trùng (nằm trong) tiêu đề lô mã đã tạo trên Seller Center, và % nhập ở đây chỉ để hiển thị — cần khớp với % của lô. Chưa kiểm tra tự động khi lưu.
- Kiểm tra khi lưu (`PUT /api/v1/loyalty/tiers`): 1–10 mốc, điểm và tên không trùng, % trong (0, 100], Shopee chỉ nhận % nguyên. Mốc bị bỏ khỏi danh sách được đóng (`closed`), không xóa.
- Hạng **tính từ mốc đã cấu hình**, không dùng `LoyaltyTierCalculator` viết cứng nữa (giữ làm mặc định khi shop chưa cấu hình). Cột sinh `tier` trong DB không còn dùng để hiển thị; không sửa migration cũ.

### 4. API cho trang tích điểm

`GET /api/v1/loyalty/me` trả thêm danh sách theo sàn (khách vẫn có một số điểm chung):

```json
{ "totalPoints": 620,
  "platforms": [ { "platform": "tiktok_shop", "connectionId": 3,
    "currentTier": "Gold", "nextTier": "Platinum", "pointsToNext": 380,
    "milestones": [
      { "campaignId": 11, "name": "Gold", "requiredPoints": 500, "discountPercent": 10,
        "reached": true, "reward": { "status": "issued", "code": "SOGOLD10A" } },
      { "campaignId": 12, "name": "Platinum", "requiredPoints": 1000, "discountPercent": 20,
        "reached": false, "reward": null } ] } ] }
```

- Chỉ trả campaign `active` của connection `connected`. `reward` lấy từ `loyalty_reward_requests` của khách (`pending/issued/failed`).
- Nhận voucher dùng API có sẵn `POST /api/v1/loyalty/me/reward-requests` với `campaignId`.

### 5. Giao diện (repo `SmartOmni_FE`)

**Storefront** (theo spec catalog): danh sách → chi tiết sản phẩm → nút **Mua trên TikTok Shop / Mua trên Shopee** mở `offer.url` ở tab mới (`rel="noopener noreferrer"`), tắt nút khi hết hàng hoặc không có URL. URL dùng đúng route của simulator: TikTok `/product/{productId}`, Shopee theo `UnifiedCatalogService`.

**Trang tích điểm** `/customer/loyalty` (đăng nhập CUSTOMER của đúng cửa hàng):

```
Điểm của bạn: 620            [ TikTok Shop | Shopee ]
0 ━━━━━━━━━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 1500
              Gold 500 ✓        Platinum 1000        Diamond 1500
              -10% [Nhận]       -20%                 -30%
Còn 380 điểm để lên Platinum
```

- Thanh ngang từ 0 đến mốc cao nhất của sàn đang chọn; phần tô = `min(điểm / mốc cao nhất, 1)`; mỗi mốc là một vạch có tên, điểm, %. Có `role="progressbar"` + `aria-valuenow/min/max`; trên màn hẹp các nhãn mốc xuống hàng dưới.
- Mốc đã đạt: nút **Nhận voucher** → trạng thái đang tạo → hiện mã + nút sao chép. Mốc lỗi phát hành: nút thử lại.
- Ô **Nhập mã đơn** + chọn sàn (tùy chọn), hiển thị lỗi theo mã ở mục 1, có ngày đủ điều kiện khi chưa đủ 15 ngày. Bên dưới là lịch sử claim.
- Shop: thêm phần **Mốc hạng theo sàn** vào `ApiMerchantLoyalty.jsx` như mục 3.

## Phase

1. **BE claim theo mã đơn** (order-service): dạng request mới, thứ tự lỗi, giới hạn tần suất. Test: đơn chưa đủ 15 ngày, đã claim, có hoàn trả, khác tenant, trùng mã giữa hai sàn. `mvn -pl smartomni-service-order,smartomni-migration-tests -am verify` → `feat(order): claim loyalty points by marketplace order id`.
2. **BE đọc bù từ TikTok** (integration + order): endpoint nội bộ refresh + gọi từ claim. Test bằng `FakeTiktokPlatform`: đơn chưa đồng bộ được tạo và claim được; TikTok lỗi không claim. → `feat(integration): refresh tiktok order on loyalty claim`.
3. **BE mốc theo sàn**: API lưu bộ mốc (tạo/sửa campaign theo lô) và profile mở rộng mục 4; thay hạng viết cứng. → `feat(order): configurable loyalty tiers per marketplace`.
4. **FE**: trang tích điểm, phần cấu hình mốc, nút mua trên storefront. `npm run lint` + `npm run test:api` (thêm test thanh tiến độ, nhận voucher, lỗi claim). → `feat(loyalty): customer tier progress page`.

Phase 1–3 độc lập với spec catalog; phase 4 cần catalog phase 1–2 cho phần nút mua.

## Hoàn tất khi

- Với tenant `e2e772b1c`: từ 2026-12-09, claim mã `5760028171077135663` được 184 điểm; claim lại báo đã claim. Trước ngày đó báo ngày đủ điều kiện.
- Đơn TikTok mới hoàn thành nhưng chưa đồng bộ vẫn claim được nhờ đọc bù.
- Shop bấm **Dùng gợi ý** tạo đủ 3 mốc cho mỗi sàn; khách thấy thanh tiến độ và nhận được mã voucher trên simulator.

## Rủi ro và câu hỏi mở

- Bỏ kiểm tra `totalAmount` làm việc claim chỉ dựa vào mã đơn; giới hạn tần suất giảm đoán mò nhưng không chặn người biết mã đơn của người khác.
- Đơn có hoàn trả **sau** khi đã claim: luồng `reverse` trong ledger đã có trong schema nhưng chưa thấy code gọi — cần xác nhận khi làm phase 1.
- Số voucher mỗi mốc mặc định 1000; hết số lượng thì khách đạt mốc không nhận được nữa.
