# Kiểm tra DTO, body và chức năng sync product SmartOmni ↔ TikTok Simulator

Ngày kiểm tra: 22/11/2026. Phạm vi: client `TiktokCatalogClient`, DTO `TiktokCatalogDtos`, `TiktokProductMapper`, `TiktokProductImportService`, `TiktokPriceSyncService`, `TiktokCatalogService`, đối chiếu với `docs/openapi.json` và code backend của simulator, rồi gọi thử trên stack local (shop SmartOmni Official Flagship).

## 1. Kết luận ngắn

| Hạng mục | Kết quả |
|---|---|
| DTO đọc (search, detail, warehouses) so với OpenAPI simulator | **Khớp** các trường SmartOmni dùng |
| Import sản phẩm (phân trang, ánh xạ SKU, giới hạn gói) | **Chạy đúng** |
| Đồng bộ kho (warehouses sync) | **Chạy đúng** |
| Đẩy tồn kho (`inventory/update`) | **Body đúng**; lỗi xác thực trả sai mã HTTP (P4) |
| Đẩy giá (`prices/update`) | **Có lỗi**: body bị simulator từ chối khi giá có phần thập phân hoặc thiếu (P1, P2) |
| Đồng bộ giá về (`prices/sync`) | Chạy, chưa kiểm chứng trường hợp giá đổi (cần cấu hình price-sync) |

## 2. Hợp đồng từng lời gọi

SmartOmni ký mọi lời gọi bằng `app_key`, `timestamp`, `sign`, `shop_cipher` (query).

### 2.1 Search Products — `POST /product/202502/products/search`
- Query SmartOmni gửi: `page_size` (mặc định 50, `TIKTOK_PRODUCT_PAGE_SIZE`), `page_token` (từ trang 2). Body: `{}`.
- OpenAPI simulator: `page_size` bắt buộc ở query; body chỉ chứa bộ lọc (`status`, `seller_skus`, `sku_ids`, `create_time_*`, `update_time_*`). Khớp.
- Response SmartOmni đọc: `next_page_token`, `total_count`, `products[]` với `id`, `title`, `status`, `skus[].{id, seller_sku, price.{currency, sale_price}, inventory[].{warehouse_id, quantity}, status_info.status}`. Khớp OpenAPI.
- Vì body rỗng nên simulator trả mọi trạng thái; SmartOmni tự lọc (`ACTIVATE` và `SELLER_DEACTIVATED`/`PLATFORM_DEACTIVATED` được nhập, trạng thái khác bị `SKIPPED_STATUS`).

### 2.2 Get Product — `GET /product/202309/products/{productId}`
- Không có query ngoài chữ ký. `productId` được kiểm tra regex `[A-Za-z0-9_-]{1,64}` trước khi gọi.
- Response SmartOmni đọc thêm: `description`, `main_images[].urls`, `skus[].sales_attributes[].{name, value_name}`. Khớp OpenAPI.

### 2.3 Get Warehouses — `GET /logistics/202309/warehouses`
- Response đọc: `warehouses[].{id, name, type, sub_type, effect_status, is_default, address.{city, region_code}}`. Khớp OpenAPI.

### 2.4 Update Inventory — `POST /product/202309/products/{productId}/inventory/update`
Body SmartOmni sinh (ví dụ thật từ log simulator, đặt tồn = 0):

```json
{"skus":[{"id":"7510080002670926179","inventory":[
  {"warehouse_id":"7600098723288348596","quantity":0},
  {"warehouse_id":"7600140142204073953","quantity":0}]}]}
```

- Cách tính: lấy chi tiết sản phẩm và danh sách kho, tính tổng hiện tại, rồi chia lại theo kho (giảm dần từ kho mặc định; tăng thì cộng vào kho mặc định). Simulator nhận số tuyệt đối từng kho. Response `data.errors[]` được kiểm tra theo `detail.sku_id`.
- Giới hạn tự áp: không âm, tối đa 99.999 mỗi kho.
- Kết quả thử: đặt 55 → thành công; đặt 0 → thành công.

### 2.5 Update Price — `POST /product/202309/products/{productId}/prices/update`
Body SmartOmni sinh:

```json
{"skus":[{"id":"7510080002670926179","price":{"amount":"150000","currency":"VND"}}]}
```

Quy tắc của simulator (`products.controller.ts`): `price.currency` phải là `VND`; `price.amount` phải khớp `^[1-9][0-9]{0,11}$` (số nguyên dương, không phần thập phân); sản phẩm phải `ACTIVATE`; SKU phải thuộc sản phẩm.

## 3. Kết quả thử trên stack local

| Gọi `PUT /api/v1/tiktok/products/...` | Body gửi sang simulator | Simulator | SmartOmni trả |
|---|---|---|---|
| `price` `originalPrice: 159000.50` | `amount: "159000.50"` | 400 | **500** |
| `price` `originalPrice: 159000.00` | `amount: "159000.00"` | 400 | **500** |
| `price` không có giá | `amount: "0"` | 400 | **500** |
| `price` `skuId` không tồn tại | `amount: "159000"` | 400 | **500** |
| `price` `originalPrice: 150000, salePrice: 120000` | `amount: "150000"` | 200 | 200 (bỏ qua `salePrice`) |
| `price` `originalPrice: 159000` | `amount: "159000"` | 200 | 200 |
| `stock` `quantity: 0` | tồn 0 ở cả 2 kho | 200 | 200 |
| `stock` `quantity: -1` | không gửi | — | **500** |
| `stock` `quantity: 100000` | không gửi | — | **500** |
| `stock` `skuId` không tồn tại | không gửi | — | **500** |
| `stock` thiếu `quantity` | không gửi | — | **500** |
| Import khi có 80 sản phẩm (page size 50) | 2 trang search | 200 | 200, `pages: 2`, `productsSeen: 51`, `stopped_sku_limit` (gói Free giới hạn 100 SKU) |

Sau khi thử, tôi đã khôi phục SKU `7510080002670926179` về giá 159000 và tồn 37.

## 4. Lỗi và điểm cần chú ý

**P1. Giá có phần thập phân bị simulator từ chối.** `TiktokCatalogClient.updatePrice` dùng `targetPrice.toPlainString()`, nên `BigDecimal` có scale (`159000.00`, thường gặp khi đọc từ cột `NUMERIC`) thành `"159000.00"` và bị trả 400. Cần làm tròn về số nguyên VND, ví dụ `setScale(0, RoundingMode.HALF_UP).toPlainString()`, hoặc báo lỗi rõ khi giá không nguyên.

**P2. Thiếu giá gửi `"0"`, và `salePrice` bị bỏ.** Khi không có `originalPrice` mà có `salePrice`, code dùng `salePrice`; khi không có cả hai, gửi `"0"` (simulator từ chối). Ngoài ra khi có cả hai, chỉ `originalPrice` được gửi, trong khi phía import đọc ngược `price.sale_price` từ TikTok. Như vậy giá đẩy lên và giá đọc về là hai trường khác nhau, cần thống nhất nghĩa.

**P3. Tiền tệ cố định `VND`.** Không lấy theo `currency` của liên kết SKU.

**P4. Lỗi nghiệp vụ và lỗi sàn trả `500 INTERNAL_ERROR`.** Giá trị âm, vượt 99.999, SKU không thuộc sản phẩm, thiếu `quantity`, và `400` từ simulator đều ra 500 với thông báo chung. `IllegalArgumentException` và `TiktokApiException` nên được ánh xạ sang 400/422/502 có mã lỗi riêng.

**P5. Dữ liệu đọc về nhưng không lưu.** `main_images` được map vào DTO trung lập nhưng không có nơi nào lưu; `tax_exclusive_price` và `status_info` của SKU bị bỏ. Tồn kho chỉ khởi tạo khi chưa có (`initInventoryIfAbsent`, `ON CONFLICT DO NOTHING`), nên import lại không cập nhật tồn từ TikTok; đây có thể là chủ ý (SmartOmni là nguồn tồn) nhưng nên ghi rõ.

**P6. Chú thích sai.** Javadoc của `updateStock` ghi `/stocks/update`, code gọi `/inventory/update`.

**P7. Số lượng lời gọi.** Mỗi lần import gọi 1 lần search mỗi trang và 1 lần get detail cho mỗi sản phẩm hợp lệ (51 lần với 51 sản phẩm); mỗi lần đẩy tồn thêm 2 lần đọc (detail và warehouses). Có thể chấp nhận ở quy mô nhỏ.

**Quan sát chưa truy nguyên:** catalog của tenant test hiện liệt kê 400 SKU đã ánh xạ trong khi `skuLimit` là 100; import hiện dừng ở SKU mới đầu tiên. Tôi chưa xác định các SKU đó được tạo bằng đường nào.

## 5. Chưa kiểm chứng

- Đồng bộ giá khi giá trên simulator thay đổi (cần cấu hình price-sync của tenant; kết quả hiện là `considered: 0`).
- Sản phẩm `SELLER_DEACTIVATED`, `DELETED`, `FREEZE` trong luồng import thật.
- Đẩy tồn tự động từ Inventory Service qua outbox (`push-tiktok-stock`).

Dữ liệu tạo thêm trong simulator: 30 sản phẩm × 2 SKU do `POST /test-admin/catalog/bulk`, hiện nằm trong DB local của simulator.
