# Phase 5. Kết nối và scope

[← Tổng quan](README.md)

- **Branch:** `fix/integration-tiktok-connection`
- **Phụ thuộc:** [phase 3](phase-3-catalog-errors.md), vì cần `TiktokApiExceptionHandler`
- **Migration:** không
- **Bao phủ:** B10 và P1 #13 (quota kết nối), B7 và P1 #14 (scope)

## Việc cần làm

### 5.1 Quota (B10)
- Thêm `TiktokConnectionStore.countActiveConnectionsExcluding(tenantId, Platform.TIKTOK_SHOP)`.
- Cho `TiktokConnectionService.start` (L141), `complete` (L191) và `connect` (L102) dùng hàm này. Khi đó kết nối TikTok sẵn có của chính tenant, dù ở trạng thái `REFRESH_REJECTED` hay `MISSING_SCOPE`, không còn chặn việc cấp quyền lại.
- Kết nối của sàn khác (Shopee) vẫn tính vào giới hạn gói Free.

### 5.2 Scope (B7)
- `TiktokAuthClient` (L69-77): khi token không có `granted_scopes`, đặt `scopes = null` (không rõ) thay vì danh sách rỗng.
- `TiktokConnectionDto` trả `grantedScopes: null`.
- `missingScopes` (L296-305) bỏ qua kiểm tra khi scope không rõ.
- Lỗi thiếu scope từ sàn (403) đi qua handler của phase 3 thành `TIKTOK_SCOPE_MISSING`, không trả danh sách rỗng.

## Test

- `TiktokConnectionServiceTest`:
  - tenant Free có kết nối TikTok `REFRESH_REJECTED` vẫn bắt đầu OAuth được;
  - tenant Free có kết nối Shopee thì vẫn bị chặn.
- `TiktokAuthClientTest`: thiếu `granted_scopes` cho ra `null`.
- Test handler: 403 có thông báo về scope được ánh xạ sang `TIKTOK_SCOPE_MISSING`.
- Lệnh: `mvn -pl smartomni-service-integration -am test`.

## E2E với simulator

1. Ép refresh token thất bại (`/test-admin/tokens/:id/expire-refresh` hoặc `revoke`). SmartOmni báo `TIKTOK_RECONNECT_REQUIRED`.
2. `POST /marketplace-connections/tiktok-shop/authorization` trả 200 mà không cần ngắt kết nối trước. Kết nối lại xong thì trạng thái là `CONNECTED`.
3. `GET /marketplace-connections/tiktok-shop` trả `grantedScopes: null`.

## Commit

- `fix(integration): let a tenant re-authorize its own TikTok connection`
- `fix(integration): treat missing TikTok granted scopes as unknown`

## Xong khi

- Test pass.
- 3 bước E2E đều đạt.
- Đã mở PR.
