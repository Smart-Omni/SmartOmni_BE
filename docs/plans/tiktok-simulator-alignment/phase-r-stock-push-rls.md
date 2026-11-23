# Phase R. Rủi ro từ code `develop`: RLS khi đẩy tồn và timeout outbox

[← Tổng quan](README.md)

**Trạng thái: đã merge** qua [PR #38](https://github.com/Smart-Omni/SmartOmni_BE/pull/38) (`d286d5b`), CI `build` pass.

| | |
|---|---|
| Branch | `fix/inventory-stock-push-rls`, tạo từ `develop` (`c24065e`), đã merge |
| Commit | `634bc2a`, `d0cfb78`, merge `d3c1149` |
| Nguồn | mục 9 của `../../merge-develop-conflict-plan.md` |

## R1. RLS trong `MarketplaceStockPushService`

**Vấn đề.** Ở đầu `pushStock`, service chạy:

```sql
set_config('app.tenant_id', '<ghép chuỗi>', false)
set_config('app.user_role', 'admin', false)
```

rồi nuốt mọi lỗi. Tham số `false` đặt giá trị ở phạm vi session, nên giá trị còn bám trên connection sau khi trả về pool. Request sau dùng lại connection đó có thể chạy với tenant và role của request trước.

**Đã sửa.**
- Bỏ hai lệnh trên. Context RLS giờ lấy từ `TenantContext.openScope(...)`, vốn đã được mở ở `InternalIntegrationJobsController`. `RlsJpaDialect` áp context này trong phạm vi transaction.
- Nếu tenant scope không khớp `tenantId` của request, service ném `IllegalStateException` trước mọi truy vấn và lời gọi sàn.
- `MarketplaceStockPushServiceTest` có 4 test.

## R2. Timeout cho `RestTemplate` của outbox

**Vấn đề.** Trước đây `OutboxPublisherScheduler` dùng `new RestTemplate()` không có timeout. Một lần gọi bị treo sẽ chặn cả lượt publish đang chạy trong một transaction.

**Đã sửa.**
- `RestTemplate` tạo bằng `SimpleClientHttpRequestFactory`.
- Hai cấu hình mới: `smartomni.outbox.http.connect-timeout-ms` (mặc định 3000) và `smartomni.outbox.http.read-timeout-ms` (mặc định 10000). Giá trị ≤ 0 bị từ chối khi khởi động.
- Có ba constructor:
  - constructor `@Autowired`, nhận timeout từ cấu hình;
  - constructor 3 tham số, giữ cho test có sẵn của `develop`, dùng timeout mặc định;
  - constructor 4 tham số để truyền `RestTemplate` giả trong test.
- `OutboxPublisherSchedulerTest` có 7 test: 3 test của `develop` (thứ tự cùng SKU, nhánh TikTok) và 4 test timeout.

## Xung đột khi merge

PR #37 vào `develop` trong lúc PR #38 đang mở, và cả hai cùng sửa `OutboxPublisherScheduler`, cùng thêm `OutboxPublisherSchedulerTest`. Cách giải quyết:
- giữ khung của `develop`: nhánh TikTok qua `TiktokInventoryStockClient` và việc chặn event mới hơn cùng SKU;
- thêm phần timeout lên trên.

Sau khi merge, `mvn -B clean verify` pass. Từ trường hợp này rút ra quy tắc "merge `develop` trước khi mở PR" ghi trong [README](README.md).

## Phần còn lại

- Chưa chạy E2E đẩy tồn thật qua outbox lên simulator. Sẽ chạy cùng E2E của [phase 3](phase-3-catalog-errors.md), mục 3.4.
- Việc Shopee thiếu stock chuyển sang [phase S](phase-s-shopee-missing-stock.md).
