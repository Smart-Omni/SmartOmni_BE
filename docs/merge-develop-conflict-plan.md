# Kế hoạch merge `develop` vào `fix/inventory-import-initial-stock`

Ngày lập: 2026-11-23
Nhánh hiện tại: `fix/inventory-import-initial-stock` (21 commit, chưa push, commit cuối `e1cbbdc` – 18/11 09:46)
Nhánh đích: `develop` (`c24065e` – 19/11 09:53, trùng `origin/develop`)
Điểm tách chung: `f5faa22` (17/11 21:51)

## Nguyên tắc

1. **Code của `develop` được giữ nguyên.** Khi hai bên xung đột, lấy bản `develop` làm gốc.
2. Mọi điều chỉnh cần thiết để hai phần chạy chung đều **sửa ở phần code của nhánh này**, không sửa logic của `develop`.
3. Cách làm: `git merge develop` vào nhánh hiện tại (không rebase, để giữ nguyên lịch sử 21 commit).
4. Trước khi merge phải xử lý working tree đang có thay đổi chưa commit (xem mục 0).

---

## 0. Chuẩn bị

Working tree đang có thay đổi chưa commit:

| File | Trạng thái | Đề xuất |
|---|---|---|
| `smartomni-service-inventory/.../client/TiktokInventoryStockClient.java` | Thêm `@Autowired` vào constructor public (vì class có 2 constructor) | Commit riêng trước khi merge: `fix(inventory): mark TikTok stock client constructor for injection` |
| `.gitignore`, `docs/architecture.md`, `docs/erd.dbml`, `docs/code_diagram/...`, `docs/shopee-integration-fe-handoff.md` | Sửa docs | Commit riêng hoặc `git stash` (người dùng quyết định) |
| `specs/*.md` | Đã xoá | Như trên |
| `CLAUDE.md`, `docs/*.md` mới, `docs/reports/` | Chưa track | Không ảnh hưởng merge, để nguyên |

Merge cần working tree sạch với các file bị merge chạm tới. Ít nhất `TiktokInventoryStockClient.java` phải được commit hoặc stash trước.

---

## 1. Xung đột ẩn: trùng migration `V24` (quan trọng nhất)

Git **không** báo conflict vì khác tên file, nhưng Flyway sẽ lỗi `Found more than one migration with version 24` và **mọi service sẽ không khởi động được**.

| Nhánh | File |
|---|---|
| `develop` | `V24__add_billing_columns_to_tenant_invoices.sql` (thêm 3 cột vào `tenant_invoices`, không tạo bảng) |
| Nhánh này | `V24__tiktok_inventory_snapshots.sql` (tạo 3 bảng + RLS) |

**Sửa (phần của nhánh này):**
- `git mv smartomni-db-migrations/src/main/resources/db/migration/V24__tiktok_inventory_snapshots.sql smartomni-db-migrations/src/main/resources/db/migration/V25__tiktok_inventory_snapshots.sql`
- Nội dung SQL giữ nguyên. Cập nhật comment đầu file nếu có ghi "V24".
- Cập nhật `docs/reports/bao-cao-smartomni-dong-bo-ton-tiktok-2026-11-18.md` dòng 26 và 39: `V24` → `V25`, `V1–V24` → `V1–V25`.

**Rủi ro:** nếu máy local hoặc DB dùng chung đã chạy `V24__tiktok_inventory_snapshots`, `flyway_schema_history` sẽ lệch (checksum/version). Khi đó cần reset DB local (`make down` + xoá volume) trước khi chạy lại. Nhánh chưa push nên DB dùng chung không bị ảnh hưởng.

---

## 2. `ShopeeProductMapper.java` — lấy nguyên bản `develop`

Đường dẫn: `smartomni-service-integration/src/main/java/com/smartomni/integration/service/catalog/ShopeeProductMapper.java`

| | `develop` | Nhánh này |
|---|---|---|
| Item không có model | `int stockQty = item.availableStock()`, nếu model đầu có stock > 0 thì lấy stock model | `v.setStock(stock(item.availableStock()))` |
| Item nhiều model | Luôn tạo `StockDto` với `m.availableStock()` | `v.setStock(stock(m.availableStock()))` |
| Stock null | Ép kiểu `int` → **NPE** | Trả `null` để báo thiếu dữ liệu |

**Sửa:** chọn toàn bộ bản `develop` (`git checkout --theirs`). Xoá helper `stock(Integer)` của nhánh này.

**Nguồn gốc của `null` — phải revert ở `ShopeeCatalogClient.java` (phần nhánh này):**
- Bản gốc và `develop` đọc `stock_info_v2.summary_info.total_available_stock` bằng `.asInt(0)`, kiểu `int` → không bao giờ null (thiếu field thì = 0).
- Commit `8734802` của nhánh này đổi record `ShopeeItemDetail`/`ShopeeModelDetail` sang `Integer availableStock` và trả `null` khi field thiếu, không phải số nguyên, hoặc âm. File này không conflict nên merge sẽ tự lấy bản của nhánh này → mapper `develop` (`int stockQty = item.availableStock()`, `models.get(0).availableStock() > 0`) sẽ **NPE**.
- **Sửa:** đưa `ShopeeCatalogClient.java` về bản `develop`: `git checkout develop -- smartomni-service-integration/src/main/java/com/smartomni/integration/client/shopee/ShopeeCatalogClient.java` (2 record về `int`, 2 chỗ parse về `.asInt(0)`). Sau đó không còn NPE; thiếu stock được coi là 0 đúng như `develop`.

---

## 3. `ShopeeProductImportService.java` — lấy nguyên bản `develop`

Đường dẫn: `smartomni-service-integration/src/main/java/com/smartomni/integration/service/catalog/ShopeeProductImportService.java`

`develop` đã tự làm việc khởi tạo tồn kho mà nhánh này thêm (commit `8734802`):
- Gọi `store.initInventoryIfAbsent(skuId, extractStock(v))` ở cả 3 nhánh `UPDATE`, `LINK_EXISTING`, `CREATE`.
- Thêm `productUrl`, `saveProductImage`, `findProductIdByPlatformItemId`, luôn gọi `getModelList` có try/catch.

**Sửa:**
- Chọn toàn bộ bản `develop`.
- Xoá 3 lệnh gọi `initStock(...)` và method `initStock(long, MarketplaceProductDto, VariantDto, Run)` của nhánh này (nếu không sẽ gọi `initInventoryIfAbsent` hai lần).

**Hệ quả (mất tính năng):**
- Không còn báo `STOCK_MISSING` khi Shopee không trả stock. `extractStock` của `develop` trả `0`, nên SKU sẽ được khởi tạo số dư `0`.
- Commit `e42ec7e` (vẫn được giữ sau merge, không conflict) thêm cờ `inventoryInitialized = (có dòng trong bảng inventory)` để FE phân biệt "chưa có số dư" với "tồn = 0". Với Shopee, khi response thiếu `total_available_stock`, `develop` vẫn tạo dòng inventory = 0 → cờ thành `true`, nên trường hợp này hiển thị như "tồn = 0" thay vì "chưa khởi tạo". Chỉ ảnh hưởng trường hợp biên đó; response Shopee bình thường có stock nên không đổi. Luồng TikTok không bị ảnh hưởng.

---

## 4. `ShopeeProductImportServiceTest.java` — lấy bản `develop`, ghép test của nhánh này

Đường dẫn: `smartomni-service-integration/src/test/java/com/smartomni/integration/service/catalog/ShopeeProductImportServiceTest.java`

**Sửa:**
1. Lấy bản `develop` làm gốc (gồm `verify(store).initInventoryIfAbsent(eq(701L), eq(50))`, `insertLink` với `productUrl`, `saveProductImage`).
2. Bỏ dòng `verify(store).initInventoryIfAbsent(701L, 50);` của nhánh này (trùng với dòng của `develop`).
3. **Giữ** test `importBackfillsMappedSkuWithoutOverwritingExistingBalance`:
   - Item có `availableStock = 0` → mapper `develop` tạo stock 0 → `initInventoryIfAbsent(701L, 0)` vẫn đúng.
   - Có thể phải stub thêm `catalog.getModelList(88L, 1001L)` trả `List.of()` vì `develop` luôn gọi hàm này (nếu không stub, Mockito trả list rỗng mặc định nên vẫn chạy; kiểm tra khi chạy test).
   - `develop` gọi `store.findProductIdByPlatformItemId(...)` khi `productId == null`; mock mặc định trả `null` thay vì `Optional.empty()` → **có thể NPE**. Nếu xảy ra, stub `when(store.findProductIdByPlatformItemId(88L, "1001")).thenReturn(Optional.empty())` trong test này.
4. **Xoá** test `importReportsMissingStockWithoutCreatingZeroBalance` vì hành vi `STOCK_MISSING` không còn (và mapper `develop` sẽ NPE với `availableStock = null`).

---

## 5. `OutboxPublisherScheduler.java` — giữ khung `develop`, chèn nhánh TikTok của nhánh này

Đường dẫn: `smartomni-service-inventory/src/main/java/com/smartomni/inventory/scheduler/OutboxPublisherScheduler.java`

| | `develop` | Nhánh này |
|---|---|---|
| Lấy event | `findTop100ByStatusOrderByCreatedAtAsc(PENDING)` – mọi platform | `findTop100ByStatusAndPlatformOrderByCreatedAtAsc(PENDING, TIKTOK_SHOP)` – chỉ TikTok |
| Gửi đi | `RestTemplate` POST `/internal/jobs/push-stock` (header `X-Internal-Job-Token`) → `MarketplaceStockPushService` (Shopee + TikTok) | `TiktokInventoryStockClient.push` → `/internal/jobs/push-tiktok-stock` → `TiktokStockPushService` |
| Lỗi | Tăng `retryCount`, `FAILED` khi đạt `MAX_RETRY` | Như trên, thêm `failedSkus`: một event lỗi thì bỏ qua các event sau của cùng tenant+SKU trong lượt đó (giữ thứ tự) |

**Sửa — bản ghép đề xuất:**
- Giữ nguyên: field `restTemplate`, `integrationServiceUrl`, `jobsToken`, đoạn build request/header, log thành công, query `findTop100ByStatusOrderByCreatedAtAsc` của `develop`.
- Thêm (phần nhánh này):
  - Field `private final TiktokInventoryStockClient tiktokStockClient;` (Lombok `@RequiredArgsConstructor` sẽ đưa vào constructor; các field `@Value` không `final` nên không ảnh hưởng).
  - `Set<String> failedSkus` + `continue` khi key đã lỗi, `failedSkus.add(key)` trong `catch`.
  - Trong `try`: nếu `event.getPlatform() == TIKTOK_SHOP` → `tiktokStockClient.push(event)`; ngược lại chạy đoạn RestTemplate của `develop`.
  - Comment class: giữ dòng "TikTok events use absolute stock updates, so a retry after a timeout is safe."

Phác thảo:

```java
for (InventoryOutboxEvent event : pendingEvents) {
    String key = event.getTenantId() + ":" + event.getSkuId();
    if (failedSkus.contains(key)) continue;
    try {
        if (event.getPlatform() == InventoryOutboxEvent.Platform.TIKTOK_SHOP) {
            tiktokStockClient.push(event);
        } else {
            // ... nguyên khối RestTemplate của develop ...
        }
        event.setStatus(SENT);
        event.setProcessedAt(Instant.now());
        log.info(... của develop ...);
    } catch (Exception ex) {
        failedSkus.add(key);
        // ... xử lý retry của develop ...
    }
    outboxEventRepository.save(event);
}
```

**Lý do giữ đường TikTok riêng:** `MarketplaceStockPushService` của `develop` bỏ qua TikTok khi `connection_id` null và không cập nhật `last_synced_at` cho TikTok; `TiktokStockPushService` của nhánh này đẩy cho tất cả listing TikTok đã liên kết (commit `6c245c1`, `d86fcdc`, `9f5d359`).

**Các file liên quan cần chỉnh theo (phần nhánh này):**
- `InventoryOutboxEventRepository.java`: bỏ `findTop100ByStatusAndPlatformOrderByCreatedAtAsc` (không còn dùng), giữ method của `develop`. Kiểm tra merge tự động không để lại cả hai.
- `OutboxPublisherSchedulerTest.java` (của nhánh này):
  - Đổi stub sang `findTop100ByStatusOrderByCreatedAtAsc(PENDING)`.
  - Constructor `new OutboxPublisherScheduler(repository, transactions, client)` vẫn đúng chữ ký sau khi ghép.
  - Thêm test: event `SHOPEE` không gọi `tiktokStockClient` (đoạn RestTemplate gọi URL thật nên chỉ kiểm tra được nhánh lỗi → `retryCount` tăng; hoặc chỉ verify `never()` với client TikTok).

---

## 6. Test migration — sửa số lượng sau khi có V25

`DatabaseMigrationTest.java` tự merge được nhưng **số sai**. Sau khi đổi sang V25 (tổng 25 migration, 54 bảng — bảng của `develop` V24 không tạo bảng mới nên vẫn 54):

| Dòng (sau merge) | Hiện tại | Đúng |
|---|---|---|
| 174 `assertEquals(24, applied)` | 24 | **25** |
| 181, 183 số bảng / bảng có RLS | 54 | 54 (giữ) |
| 185 số enum | 24 | 24 (giữ – không phải số migration) |
| 189 `flyway_schema_history WHERE success` | 24 | **25** |
| 552 `migrate().migrationsExecuted` | 4 | **5** |
| 558 `flyway_schema_history WHERE success` | 24 | **25** |
| 584 bảng có RLS | 54 | 54 (giữ) |
| 640 `flyway_schema_history WHERE success` | 24 | **25** |
| 664 baseline V5 `migrationsExecuted` | 19 | **20** |

`PackagedServiceStartupIT.java` dòng 95: `assertEquals(24, ...)` → **25**.

> Lưu ý: tin nhắn trước có nói số bảng thành 57 — sai. Đúng là giữ 54, vì nhánh này đã cộng 3 bảng (51 → 54) và `develop` không thêm bảng.

---

## 7. Các file tự merge — cần kiểm tra lại

Không conflict nhưng nên rà khi review merge:
- `TiktokCatalogClient.java`: chữ ký `updateStock(Long connectionId, String productId, String skuId, Integer quantity)` hai bên giống nhau → `MarketplaceStockPushService` của `develop` vẫn compile.
- `CatalogMappingStore.java` / `JdbcCatalogMappingStore.java`: `initInventoryIfAbsent` có ở cả hai bên, giống nhau; `develop` thêm `saveProductImage`, `findProductIdByPlatformItemId`.

---

## 8. Thứ tự thực hiện

1. Commit/stash thay đổi chưa commit (mục 0).
2. `git merge develop` → 4 file conflict.
3. Mapper + ImportService: `git checkout --theirs` rồi xác nhận không còn `initStock`/`stock()` (mục 2, 3). Đưa `ShopeeCatalogClient.java` về bản `develop` (mục 2).
4. ImportServiceTest: ghép thủ công (mục 4).
5. OutboxPublisherScheduler: ghép thủ công (mục 5), sửa repository + test liên quan.
6. Đổi `V24__tiktok_inventory_snapshots.sql` → `V25` (mục 1), sửa số trong test migration (mục 6), sửa docs report.
7. Chạy test:
   - `mvn -pl smartomni-service-integration,smartomni-service-inventory -am test`
   - `mvn -pl smartomni-migration-tests -am verify` (cần Docker)
8. Commit merge: `Merge branch 'develop' into fix/inventory-import-initial-stock` (đổi tên migration và sửa số test có thể nằm trong merge commit hoặc commit riêng ngay sau).
9. Push nhánh `fix/inventory-import-initial-stock` lên `origin` (chỉ khi người dùng đồng ý). **Không** push hay merge vào `develop`.
10. (Ngoài phạm vi plan) Mở PR `fix/inventory-import-initial-stock` → `develop`, mô tả thay đổi + kết quả test, cần ít nhất 1 teammate approve trước khi merge (theo `AGENTS.md`).

## 9. Follow-up không làm trong merge này (thuộc code `develop`)

- `MarketplaceStockPushService` ghép chuỗi `tenantId` vào SQL `set_config` và dùng `set_config(..., false)` (phạm vi session, không phải transaction) — trái quy ước RLS trong `CLAUDE.md`.
- `OutboxPublisherScheduler` của `develop` tự `new RestTemplate()` không timeout, và không forward JWT (dùng internal token — đúng với job nội bộ).
- Luồng Shopee mất phân biệt "chưa khởi tạo số dư" và "tồn = 0".
