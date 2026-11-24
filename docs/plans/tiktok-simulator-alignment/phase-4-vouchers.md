# Phase 4. Voucher

[← Tổng quan](README.md)

- **Branch:** `fix/integration-tiktok-vouchers`
- **Phụ thuộc:** không; tạo branch từ `origin/develop` (phase 0 và R đã merge)
- **Migration:** `V27` (chỉ khi cần)
- **Bao phủ:** B13 (kéo thiếu mã), P1 #12 (nghiệp vụ voucher theo Phase 09c của simulator)

## Checklist trước khi làm

- [ ] Nếu phase này cần migration: kiểm tra số migration mới nhất trên `origin/develop` và trên branch phase 1. Dùng số kế tiếp sau cả hai (dự kiến `V27`).

## Việc cần làm

### 4.1 Không tạo voucher qua API
- `TiktokVoucherService.createVoucher` (L42-87) và `POST /api/v1/tiktok/vouchers` trả 501 `TIKTOK_VOUCHER_CREATE_UNSUPPORTED`. Thông báo hướng dẫn tạo lô mã trên Seller Center. Không còn sinh mã `SO-...`.
- `MarketplaceVoucherAdapterService` (L57-71): chế độ `api` trả `FAILED` với lý do "unsupported".
- Xóa `TiktokVoucherClient.createCoupon` (L62-80). Simulator không có route này và trả 404 cho mọi lệnh ghi lên `/promotion/...`.

### 4.2 Kéo mã
- `TiktokVoucherClient.searchCoupons(connectionId, titleKeyword, statuses)`:
  - body `{title_keyword, status:[...]}`;
  - `page_size=100`;
  - đi hết `next_page_token`.
- `syncCouponsFromSimulator` (L92-112) nhận thêm tiêu đề lô (tùy chọn).
- Chế độ `import` trong `MarketplaceVoucherAdapterService` (L84-102) chỉ chọn mã thỏa cả ba điều kiện:
  - thuộc lô có tiêu đề cấu hình trong campaign;
  - đang `ONGOING`;
  - chưa được phát.
- Cần xác định nơi lưu trạng thái "đã phát": `loyalty_reward_requests` hay `tiktok_vouchers`. Nếu phải thêm `issued_at` / `issued_request_id` vào `tiktok_vouchers`, tạo `V27` và cập nhật số migration thành 27 trong `DatabaseMigrationTest`.

### 4.3 Theo dõi dùng mã
- Thêm job `@Scheduled` trong integration, poll `GET /promotion/202406/coupons/{id}` cho các mã đã phát và chưa ở trạng thái cuối.
- Cập nhật `redeemed_count` và `status` theo bảng trong `smartomni-requirements/phase-09.md` của simulator.
- Ngừng poll khi `redeemed_count = 1`, hoặc mã đã `DEACTIVATED` / `EXPIRED`.

## Test

- Test mới `TiktokVoucherClientTest`: phân trang 60 mã qua 2 trang; body có `title_keyword`.
- `TiktokVoucherControllerAndServiceTest`: tạo voucher trả 501.
- `MarketplaceVoucherAdapterServiceTest`: chế độ `api` không gọi sàn; chế độ `import` không phát trùng mã.
- Test cho job theo dõi: dừng ở các trạng thái cuối.
- Lệnh: `mvn -pl smartomni-service-integration,smartomni-service-order -am test`, thêm `mvn -pl smartomni-migration-tests -am verify` nếu có `V27`.

## E2E với simulator

1. `POST /test-admin/scenarios/loyalty-vouchers` tạo 3 lô × 20 mã. `POST /tiktok/vouchers/sync` phải lấy đủ 60 mã.
2. Sync theo tiêu đề `Loyalty Diamond 30%` chỉ trả 20 mã.
3. `POST /tiktok/vouchers` trả 501.
4. Phát một mã, buyer dùng mã đó khi checkout. Job cập nhật `redeemed_count = 1` và ngừng poll mã này.

## Commit

- `fix(integration): refuse TikTok voucher creation through the API`
- `fix(integration): page TikTok coupon search by batch title`
- `feat(integration): track TikTok coupon redemption`
- `feat(db): track issued TikTok voucher codes` (chỉ khi cần `V27`)

## Xong khi

- Test pass.
- 4 bước E2E đều đạt.
- Đã mở PR.
