# Phase 0. Ổn định branch và đưa tài liệu vào repo

[← Tổng quan](README.md)

- **Branch code:** `fix/inventory-import-initial-stock`, đã merge qua [PR #37](https://github.com/Smart-Omni/SmartOmni_BE/pull/37) (`630ce60`).
- **Branch tài liệu:** `docs/integration-tiktok-simulator-alignment` (mới), tạo từ `origin/develop`.
- **Bao phủ:**
  - lỗi `inventory-service` không khởi động;
  - xác minh sau khi merge `develop`;
  - đưa báo cáo test và plan vào repo.

## Đã xong

- `2d4a878 fix(inventory): mark TikTok stock client constructor for injection`.
- Merge `develop` (`1bd27a8`):
  - đổi `V24__tiktok_inventory_snapshots.sql` thành `V25`;
  - sửa test migration thành 25 migration và 54 bảng.
- PR #37 đã merge vào `develop`.
- Hai việc kiểm chứng đã được `mvn -B clean verify` trên `develop` mới (lượt chạy cho PR #38) bao phủ:
  - **Constructor của `TiktokInventoryStockClient`:** `PackagedServiceStartupIT` khởi động đủ 7 service, gồm `inventory-service` dùng constructor `@Autowired`. Vì vậy không cần thêm test riêng cho constructor public như kế hoạch cũ. `TiktokInventoryStockClientTest` trên `develop` đã có test cho `push` (header token, lỗi server).
  - **Test migration:** `DatabaseMigrationTest` và `PackagedServiceStartupIT` pass.

## Việc còn lại

1. **Reset DB local.**
   - Vì sao: DB local đã chạy `V24__tiktok_inventory_snapshots` từ trước khi đổi tên, nên Flyway sẽ báo lệch.
   - Chạy `docker compose -f docker-compose.yml -f docker-compose.tiktok-simulator.yml down -v`, rồi `up --build -d` từ `develop` mới.
   - Tạo lại tenant test bằng `onboarding` và kết nối lại shop.
   - Đây là bước chuẩn bị cho E2E của mọi phase sau.
2. **Commit tài liệu trên branch mới** `docs/integration-tiktok-simulator-alignment`, tạo từ `origin/develop`.
   - **Gồm:**
     - `docs/plans/tiktok-simulator-alignment/`;
     - `docs/reports/tiktok-simulator-integration-api-test.md`;
     - `docs/reports/tiktok-product-sync-check.md`;
     - `docs/tiktok-simulator-local.md`;
     - `docs/merge-develop-conflict-plan.md`, nếu bạn muốn giữ.
   - **Không gồm** các thay đổi docs khác của bạn trong working tree.
   - Kiểm tra trước: các file báo cáo khác trong `docs/reports/` (ví dụ `bao-cao-smartomni-dong-bo-ton-tiktok-2026-11-18.md`) có thể đã vào `develop` qua PR #37. Chỉ commit những file chưa có trên `develop`.
3. **Push và mở PR** vào `develop`.
4. **Dọn dẹp** (tùy bạn):
   - xóa branch local `fix/inventory-import-initial-stock` và `fix/inventory-stock-push-rls`;
   - xóa worktree `../SmartOmni_Platform-stock-push-rls` bằng `git worktree remove`.

## Commit

- `docs(integration): add TikTok simulator test reports and alignment plan`

## Xong khi

- DB local khởi động sạch từ `develop` mới.
- PR tài liệu đã mở.
