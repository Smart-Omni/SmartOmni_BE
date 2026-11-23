# Phase S. Shopee thiếu stock: không khởi tạo tồn, báo `STOCK_MISSING`

[← Tổng quan](README.md)

- **Branch:** `fix/integration-shopee-missing-stock`, tạo từ `origin/develop`
- **Phụ thuộc:** không (làm song song với các phase TikTok được)
- **Migration:** không
- **Nguồn:** mục 3 và mục 9 của `../../merge-develop-conflict-plan.md`
- **Hướng xử lý đã chốt:** không khởi tạo tồn và báo `STOCK_MISSING`

## Vấn đề

Shopee có lúc không trả `stock_info_v2.summary_info.total_available_stock`. Khi đó:
1. `ShopeeCatalogClient` đọc trường này bằng `.asInt(0)`, nên được 0.
2. `ShopeeProductImportService.extractStock` trả 0 và tạo dòng inventory = 0.
3. Cờ `inventoryInitialized` thành `true`, nên FE hiện "tồn = 0" thay vì "chưa khởi tạo".

Luồng TikTok không gặp lỗi này: `TiktokProductImportService.initStock` đã báo `STOCK_MISSING`.

## Việc cần làm

1. **`ShopeeCatalogClient`**
   - Các record `ShopeeItemDetail` và `ShopeeModelDetail` dùng kiểu `Integer availableStock`.
   - Trả `null` khi trường thiếu, không phải số nguyên, hoặc âm.
   - Giữ nguyên trường hợp bình thường.
2. **`ShopeeProductMapper`**
   - Bỏ ép kiểu sang `int` (`int stockQty = item.availableStock()`, `models.get(0).availableStock() > 0`). Đây là chỗ từng gây NPE khi merge.
   - Khi giá trị là null thì để `stock = null`.
3. **`ShopeeProductImportService`**
   - Khi stock null: không gọi `initInventoryIfAbsent`, và thêm mục `STOCK_MISSING` vào import report.
   - Làm cho cả ba nhánh `UPDATE`, `LINK_EXISTING`, `CREATE`.
   - Không gọi `initInventoryIfAbsent` hai lần.
4. **Tìm các nơi khác gọi `availableStock()`**, ví dụ `CatalogSyncScheduler` và luồng giá Shopee, để không nơi nào ép null sang `int`.

## Test

- `ShopeeCatalogClientTest`: trường thiếu, chuỗi, số âm đều cho ra `null`; số hợp lệ đọc đúng.
- `ShopeeProductImportServiceTest`:
  - khôi phục `importReportsMissingStockWithoutCreatingZeroBalance` (đã xóa khi merge);
  - giữ `importBackfillsMappedSkuWithoutOverwritingExistingBalance`;
  - thêm test cho cả ba nhánh.
- Lệnh: `mvn -pl smartomni-service-integration -am test`. Chạy `mvn -B clean verify` trước khi mở PR.

## E2E

- Chạy được với Shopee sandbox nếu có. Simulator chỉ là TikTok nên không dùng được cho phase này.
- Nếu không có sandbox: đối chiếu bằng unit test, và ghi rõ trong PR là chưa chạy E2E.

## Commit

- `fix(integration): keep missing Shopee stock unknown instead of zero`
- `fix(integration): report STOCK_MISSING for Shopee imports`

## Xong khi

- Test pass.
- FE thấy "chưa khởi tạo" khi Shopee không trả stock.
- Đã mở PR.
