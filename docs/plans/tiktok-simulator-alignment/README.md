# Plan: đưa SmartOmni về đúng hợp đồng TikTok Simulator

Plan chia thành nhiều phase, mỗi phase là một branch và một PR. File này chứa phần dùng chung: bối cảnh, trạng thái, quy tắc, thứ tự, bảng bao phủ, kiểm chứng và rủi ro.

*Cập nhật lần cuối: 23/11/2026, sau khi PR #37 và PR #38 đã merge vào `develop`.*

## Bối cảnh

Ba lượt test (21–23/11/2026) giữa SmartOmni và simulator (`Smartomni-Tiktok-Simulator`, nhánh `develop` tới `7f721af`, đã bỏ `ON_HOLD`) cho thấy nhiều lệch hợp đồng và lỗi nghiệp vụ. Chi tiết ở:
- `../../reports/tiktok-simulator-integration-api-test.md` (mục 4, 7, 8);
- `../../reports/tiktok-product-sync-check.md`.

Chuẩn nghiệp vụ lấy từ `Smartomni-Tiktok-Simulator/docs/smartomni-requirements/phase-05..09.md`.

**Mục tiêu:** SmartOmni nhận được webhook, xử lý đúng và không trùng, kéo dữ liệu đủ (phân trang, polling bù), gửi body đúng, và báo lỗi rõ ràng thay cho 500.

**Phạm vi:** P0, P1 và finance. P2 (tracking, hội thoại, loyalty) để sau.

**Quyết định đã chốt:**
- Mỗi phase một branch và một PR, tạo từ `develop`.
- Finance dựng từ dữ liệu đơn: escrow lấy từ `price_detail`; settlements là ước tính SmartOmni tự cộng từ đơn `COMPLETED`.
- Shopee thiếu stock: không khởi tạo tồn, báo `STOCK_MISSING`.

## Trạng thái các phase

| Phase | File | Branch | Phụ thuộc | Trạng thái |
|---|---|---|---|---|
| 0 | [phase-0-stabilize-branch.md](phase-0-stabilize-branch.md) | `fix/inventory-import-initial-stock` | — | **Code đã merge** ([PR #37](https://github.com/Smart-Omni/SmartOmni_BE/pull/37)). Còn commit tài liệu và reset DB local |
| R | [phase-r-stock-push-rls.md](phase-r-stock-push-rls.md) | `fix/inventory-stock-push-rls` | — | **Đã merge** ([PR #38](https://github.com/Smart-Omni/SmartOmni_BE/pull/38), `d286d5b`) |
| S | [phase-s-shopee-missing-stock.md](phase-s-shopee-missing-stock.md) | `fix/integration-shopee-missing-stock` | — | Chưa làm |
| 1 | [phase-1-webhook-orders.md](phase-1-webhook-orders.md) | `fix/integration-tiktok-webhook` | — | Chưa làm, có thể bắt đầu |
| 2 | [phase-2-aftersale.md](phase-2-aftersale.md) | `fix/integration-tiktok-aftersale` | 1 | Chưa làm |
| 3 | [phase-3-catalog-errors.md](phase-3-catalog-errors.md) | `fix/integration-tiktok-catalog-errors` | — | Chưa làm, có thể bắt đầu |
| 4 | [phase-4-vouchers.md](phase-4-vouchers.md) | `fix/integration-tiktok-vouchers` | — | Chưa làm, có thể bắt đầu |
| 5 | [phase-5-connection-scopes.md](phase-5-connection-scopes.md) | `fix/integration-tiktok-connection` | 3 | Chưa làm |
| 6 | [phase-6-finance.md](phase-6-finance.md) | `feat/integration-tiktok-finance-from-orders` | 3 | Chưa làm |
| 7 | [phase-7-docs.md](phase-7-docs.md) | commit cuối mỗi phase, hoặc PR docs riêng | 1–6 | Chưa làm |

**Thứ tự:**
- Phase 0 và R đã vào `develop`, nên không còn cần stacked PR. Mọi phase mới đều tạo từ `origin/develop` mới nhất.
- Phase 1, 3, 4 và S làm song song được: chúng không sửa cùng file.
- Phase 2 tạo từ phase 1 (cần bảng route type 11/12 và bảng checkpoint). Nếu PR phase 1 đã merge thì tạo từ `develop`.
- Phase 5 và 6 tạo sau phase 3, vì dùng lại `TiktokApiExceptionHandler` do phase 3 thêm.

## Bối cảnh git (23/11/2026)

`origin/develop` hiện ở `d286d5b`, gồm:
- **PR #37** (`630ce60`): đồng bộ tồn TikTok, khởi tạo tồn khi import, `@Autowired` cho `TiktokInventoryStockClient`, migration `V25__tiktok_inventory_snapshots.sql`.
- **PR #38** (`d286d5b`):
  - `MarketplaceStockPushService` không còn `set_config(..., false)` và từ chối chạy khi tenant scope không khớp;
  - `OutboxPublisherScheduler` có timeout 3s/10s, cấu hình được.
- Code có sẵn từ trước PR #38:
  - `MarketplaceStockPushService` với `POST /internal/jobs/push-stock`;
  - `OutboxPublisherScheduler` chạy cho mọi platform, event lỗi chặn các event mới hơn của cùng SKU;
  - `CatalogSyncScheduler` import TikTok mỗi 30 phút.

Cả hai branch `fix/inventory-import-initial-stock` và `fix/inventory-stock-push-rls` đã merge và có thể xóa. Worktree `../SmartOmni_Platform-stock-push-rls` cũng có thể xóa bằng `git worktree remove`.

Thư mục plan này và các báo cáo trong `docs/reports/` vẫn **chưa commit**. Chúng nằm trong working tree của checkout chính, cùng với các thay đổi docs khác của bạn (`docs/architecture.md`, `erd.dbml`, `specs/` bị xóa...). Xem việc còn lại ở [phase 0](phase-0-stabilize-branch.md).

**Quy tắc làm việc:**
- Branch tạo từ `origin/develop`. Mỗi phase một PR vào `develop`.
- Commit theo Conventional Commits, có dòng `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Báo trước mỗi lần push và tạo PR.
- Trước khi mở PR, merge `develop` mới nhất vào branch rồi chạy `mvn -B clean verify`. Lý do: PR #38 từng bị xung đột với PR #37 ở `OutboxPublisherScheduler` vì hai PR sửa cùng file song song.
- Không đụng tới các thay đổi docs chưa commit khác của bạn.

## Quy tắc migration

Migration mới nhất trên `develop` là `V25`; `V24` là billing của `develop`.
- Phase 1 dùng `V26`.
- Phase 4 dùng `V27` nếu cần.
- Không phase nào khác thêm migration.
- Trước khi tạo branch và trước khi merge, kiểm tra lại số migration trên `develop`. Nếu đã bị chiếm, đổi lên số kế tiếp.

Mỗi lần thêm migration, cập nhật `smartomni-migration-tests/src/test/java/com/smartomni/migrations/DatabaseMigrationTest.java`. Các dòng dưới đây đã kiểm tra trên `d286d5b`:
- Số migration: hiện là 25, ở L174, L189, L558, L640.
- Số bảng: hiện là 54, ở L181, L183, L584. Chỉ sửa khi thêm bảng.
- Số enum: hiện là 24, ở L185. Chỉ sửa khi thêm enum.
- Kiểm tra cả `PackagedServiceStartupIT`.

## Bảng bao phủ (mục trong báo cáo → phase)

| Mục trong báo cáo | Phase | Trạng thái |
|---|---|---|
| `inventory-service` không khởi động | 0 | Đã merge (PR #37) |
| RLS ở phạm vi session khi đẩy tồn | R | Đã merge (PR #38) |
| `RestTemplate` của outbox không có timeout | R | Đã merge (PR #38) |
| Shopee thiếu stock bị coi là 0 | S | Chưa làm |
| B1 webhook 401 | 1 (1.1) | Chưa làm |
| B12 số `type` webhook | 1 (1.2) | Chưa làm |
| P1 #7 idempotency, không hồi trạng thái | 1 (1.3, 1.5) | Chưa làm |
| P1 #8 lấy chi tiết đơn, SKU chưa ánh xạ | 1 (1.4, 1.6) | Chưa làm |
| P1 #9 polling | 1 (1.7, đơn), 2 (2.5, trả/hủy) | Chưa làm |
| B11 topic webhook | 1 (1.8) | Chưa làm |
| B2, B3 DTO và chi tiết trả hàng | 2 (2.1, 2.2) | Chưa làm |
| B4 seller hủy đơn | 2 (2.3) | Chưa làm |
| P1 #11 hoàn tồn, trả một phần | 2 (2.4, 2.6) | Chưa làm |
| B8 body giá | 3 (3.1) | Chưa làm |
| B9 lỗi 500 | 3 (3.2, 3.3) | Chưa làm |
| P1 #10 outbox tồn | 3 (3.4) | Một phần: timeout đã xong ở R; còn tiêu chí "đã gửi" và mã lỗi |
| Javadoc, `skuLimit` | 3 (3.5) | Chưa làm |
| B13 và P1 #12 voucher | 4 | Chưa làm |
| B10 và P1 #13 quota | 5 (5.1) | Chưa làm |
| B7 và P1 #14 scope | 5 (5.2) | Chưa làm |
| B5 finance | 6 | Chưa làm |
| Dọn `ON_HOLD`, tài liệu | 7 | Chưa làm |
| P2: tracking, hội thoại, loyalty | — | Ngoài phạm vi |

## Kiểm chứng chung

**Trước mỗi commit**
- `mvn -pl smartomni-service-integration -am test`.
- Thêm module khi phase sửa ở đó: `smartomni-common`, `smartomni-service-order`, `smartomni-service-inventory`.
- `mvn -pl smartomni-migration-tests -am verify` khi có migration (cần Docker).

**Trước mỗi PR**
- Merge `develop` mới nhất vào branch.
- `mvn -B clean verify` ở root.

**E2E với simulator, sau mỗi phase**

Chuẩn bị một lần (chưa làm): DB local đang lệch Flyway, vì đã chạy `V24__tiktok_inventory_snapshots` trước khi file này đổi tên thành `V25`. Các bước:
1. `docker compose -f docker-compose.yml -f docker-compose.tiktok-simulator.yml down -v`, rồi `up --build -d` từ `develop` mới.
2. Tạo lại tenant test bằng `POST /api/v1/tenants/onboarding` với dữ liệu giả.

Mỗi lần chạy E2E:
1. Chạy `docker compose -f docker-compose.yml -f docker-compose.tiktok-simulator.yml up --build -d <các service bị sửa>`.
2. Khi service đã lên (integration mất khoảng 84 giây), chạy `docker restart smartomni-gateway`.
3. Kết nối lại shop bằng consent qua API (`POST /demo/authorization/consent` với seller session), rồi gọi `callback`.
4. Chạy kịch bản E2E ghi trong file của phase đó.
5. Ghi kết quả thật vào báo cáo. Nếu không chạy được, ghi rõ lý do.

## Rủi ro và ghi chú

| Rủi ro | Cách xử lý | Nằm ở |
|---|---|---|
| Số `type` webhook và hợp đồng simulator đều `unverified` | Chỉ dùng một nguồn cho số type là enum `TiktokWebhookType`; test so khớp đủ bảng của simulator; không viết số trực tiếp trong code | [Phase 1](phase-1-webhook-orders.md), "Checklist trước khi làm" |
| `WebhookOrderPayload` dùng chung với Shopee | Trường mới là tùy chọn; null thì bỏ qua so sánh; thêm test cho đơn Shopee | [Phase 1](phase-1-webhook-orders.md) |
| Các PR sửa cùng file song song (như #37 và #38) | Merge `develop` và chạy verify trước khi mở PR. Nếu cần xung đột thì giữ khung của `develop`, thêm thay đổi của phase lên trên | Quy tắc làm việc ở trên |
| Trùng số migration với `develop` | Kiểm tra số migration trước khi tạo branch và trước khi merge | [Phase 1](phase-1-webhook-orders.md), [Phase 4](phase-4-vouchers.md) |
| RLS ở phạm vi session khi đẩy tồn | **Đã xử lý** (PR #38) | [Phase R](phase-r-stock-push-rls.md) |
| Outbox không có timeout | **Đã xử lý** (PR #38) | [Phase R](phase-r-stock-push-rls.md) |
| Shopee thiếu stock bị coi là 0 | Không khởi tạo tồn, báo `STOCK_MISSING` | [Phase S](phase-s-shopee-missing-stock.md) |
| DB local lệch Flyway sau khi đổi `V24` thành `V25` | Xóa volume và tạo lại dữ liệu test | "Kiểm chứng chung" ở trên |
