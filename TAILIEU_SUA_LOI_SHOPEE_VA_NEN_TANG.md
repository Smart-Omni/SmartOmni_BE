# Tài Liệu Báo Cáo Sửa Lỗi & Nâng Cấp Hệ Thống Backend (Shopee & Nền Tảng SmartOmni)

> **Nhánh thực hiện:** `fix/shopee-return-and-platform-apis`  
> **Trạng thái:** Đã kiểm thử tự động toàn diện (BUILD SUCCESS), đã push lên remote GitHub:  
> 👉 [Tạo Pull Request trên GitHub](https://github.com/Smart-Omni/SmartOmni_BE/pull/new/fix/shopee-return-and-platform-apis)  
> **Phạm vi thay đổi:** Chỉ xử lý SmartOmni Backend (`SmartOmmi_BE`). **Tuyệt đối không sửa SmartOmni Frontend** và **không chạm vào luồng TikTok Shop**.

---

## 1. Phân Tích & Xác Minh Chi Tiết Các Phản Hồi Của Teammate

| Mã Lỗi | Nội Dung Phản Hồi | Phân Tích Thực Tế & Đánh Giá | Giải Pháp Đã Triển Khai |
| :--- | :--- | :--- | :--- |
| **B4** | *Kết nối sàn bằng App Key luôn trả 400 tại `POST /api/v1/marketplace-connections`* | **Hiểu nhầm một phần (Partially Misidentified)**: Lỗi 400 kèm mã `SHOPEE_USE_AUTHORIZATION_FLOW` **không phải là bug**, mà là cơ chế bảo mật có chủ đích. Trong Shopee Open API v2, `partner_id` và `partner_key` thuộc quyền sở hữu của SmartOmni (nhà phát triển nền tảng), người bán **không bao giờ sở hữu app key riêng**. Bắt buộc phải ủy quyền qua OAuth 2.0. Tuy nhiên, endpoint cũ vẫn nhận `SHOPEE` gây nhầm lẫn. | Đánh dấu `@Deprecated` endpoint `POST /api/v1/marketplace-connections`; cập nhật thông báo lỗi rõ ràng hướng dẫn chuyển hướng sang `POST /api/v1/marketplace-connections/shopee/authorization`. |
| **B5** | *OAuth Shopee/TikTok không quay về được app mobile (redirect_uri cố định web port 5174)* | **Chính xác 100%**: `ShopeeProperties.redirectUri` trước đó bị fix cứng ở `http://localhost:5174/merchant/channels/shopee/callback`, app di động không thể chặn callback để lấy authorization code. | Cập nhật `ShopeeConnectionController.start` và `ShopeeConnectionService.start` cho phép truyền dynamic `redirect_uri` (đã whitelist bảo mật cho `smartomni://*`, localhost, và `*.smartomni.vn`). |
| **C7** | *Chưa có API bật/tắt "tự nhập sản phẩm & đơn" khi kết nối sàn Shopee* | **Chính xác 100%**: Cột `auto_import_products` có trong database nhưng DTO bỏ quên và không có API cập nhật sau khi kết nối. | Bổ sung `autoImportProducts` vào `ShopeeConnectionDto` và tạo API `PATCH /api/v1/marketplace-connections/shopee/settings`. |
| **C2** | *Chưa có API chi tiết đơn và sản phẩm trong đơn (`GET /api/v1/orders/{id}`)* | **Chính xác 100%**: `OrderController` trước đây chỉ có list phân trang và `/track`, không có endpoint đọc line items của đơn hàng. | Xây dựng API `GET /api/v1/orders/{id}` trả về `OrderDetailDto` kèm danh sách sản phẩm `items` từ `OrderItemRepository`. |
| **C3** | *Chưa có API tạo đơn để test (danh sách đơn luôn trống trong môi trường dev)* | **Chính xác 100%**: Đơn hàng trước đây chỉ vào qua webhook hoặc polling, gây khó khăn cho việc kiểm thử tính năng đơn hàng và kho. | Bổ sung API `POST /api/v1/orders` tiếp nhận `CreateManualOrderRequest` (`source = MANUAL`), tự động lưu đơn và các line items. |
| **C5** | *Danh sách tồn kho thiếu SKU chưa đặt tồn, thiếu tên sản phẩm* | **Chính xác 100%**: `GET /api/v1/inventory` trước đây chỉ select bảng `inventory`. SKU nào chưa nhập kho lần đầu thì mất hút, và không có tên hàng. | Cải tiến query SQL kết hợp `product_skus`, `products`, và `inventory`, trả về toàn bộ SKU của tenant (tồn 0 nếu chưa nhập), kèm `skuCode`, `productName`. |
| **C8** | *Danh sách API trả thẳng entity JPA* | **Chính xác 100%**: Trả thẳng entity dễ làm lộ trường nhạy cảm (`version`, `tenantId`, `deletedAt`) và dễ vỡ giao diện khi sửa schema DB. | Chuẩn hóa toàn bộ response sang DTO sạch: `OrderDetailDto`, `OrderItemDto`, `InventoryItemDto`. |
| **C4** | *Danh sách sản phẩm không kèm SKU (gây N+1: 100 sản phẩm = 101 requests)* | **Chính xác 100%**: Frontend phải loop gọi `/{id}/skus` cho từng sản phẩm trong bảng. | Cung cấp endpoint batch `GET /api/v1/products/skus?productIds=1,2,3...` truy vấn trong 1 câu SQL duy nhất (`IN (:productIds)`). |
| **C6** | *Dự báo AI trả cả ngày đã qua, không sắp xếp* | **Chính xác 100%**: Query thiếu `ORDER BY` và không lọc các ngày quá khứ. | Bổ sung `OrderByForecastDateAsc` vào `DemandForecastRepository` và thêm tham số `?from=&days=` ở controller (mặc định chỉ lấy từ hôm nay trở đi). |

---

## 2. Giải Quyết Kịch Bản Hoàn Trả Hàng Shopee (DS-09 & Chuẩn Hóa WF-05)

### 2.1. Nối Webhook Hoàn Hàng Shopee Vào Order Service (Khắc Phục DS-09)
- **Vấn đề ban đầu:** `ShopeeWebhookDispatcher` đẩy webhook code `29` (Return) vào routing key `return.incoming` nhưng không có consumer hứng để gọi `OrderService.handleOrderReturn()`. Ngoài ra luồng hoàn kho cũ bị hoàn lặp lại và hoàn không phân biệt mặt hàng.
- **Giải pháp:**
  1. Thêm fast-path trong `ShopeeWebhookDispatcher` gọi trực tiếp `ShopeeReturnSyncService.syncReturnDetail()` xử lý ngay tức thì.
  2. Khai báo RabbitMQ queue `shopee.return.incoming` và gắn consumer `ShopeeReturnSyncConsumer` để xử lý async dự phòng / retry nếu mạng bận.
  3. `ShopeeReturnSyncService` gọi Shopee Open API v2 lấy chi tiết đơn hoàn (`GET /api/v2/returns/get_return_detail`), trích xuất từng mặt hàng và định tuyến chuẩn xác.

### 2.2. Chuẩn Hóa Quy Trình Hoàn Trả Hàng Vật Lý (WF-05)
- **Cổng kiểm định kho (Warehouse Inspection Gate):**
  - Đối với yêu cầu trả hàng vật lý (`PHYSICAL_RETURN`), hệ thống **không tự động hoàn kho** ngay khi nhận webhook sàn.
  - Đơn hàng được đưa vào trạng thái `ON_HOLD`, `restockStatus = AWAITING_INSPECTION`.
  - Thông tin trả hàng được lưu vào `return_refunds` và `return_refund_items` (bảng V20).
- **Quyền quyết định của nhân viên kho:**
  - Nhân viên kho kiểm tra bưu kiện thực tế và gọi `POST /api/v1/returns/{id}/inspection`.
  - Chỉ những sản phẩm còn nguyên vẹn/đạt chuẩn mới được duyệt hoàn kho.
  - Các sản phẩm bị hư hại/mở niêm phong sẽ bị từ chối nhập kho và ghi nhận lý do từ chối rõ ràng.
- **Bảo vệ kho chống cộng trùng lặp (Restock Idempotency Key):**
  - Mọi thao tác hoàn kho trong `InventoryService.restock()` đều yêu cầu và lưu vết `idempotencyKey` vào `inventory_restock_logs`.
  - Nếu webhook gửi lặp hoặc nhân viên bấm nhiều lần, hệ thống nhận diện thao tác đã áp dụng và bỏ qua an toàn.

---

## 3. Danh Sách & Hướng Dẫn Sử Dụng Các API Mới / Được Nâng Cấp

### 3.1. Kết Nối & Cấu Hình Shopee
- **Khởi tạo kết nối OAuth (Mobile & Web):**
  - `POST /api/v1/marketplace-connections/shopee/authorization`
  - Query param hoặc JSON Body: `{ "redirect_uri": "smartomni://oauth/shopee" }` (hoặc callback web).
  - Trả về `authorization_url` chuyển hướng người bán sang trang cấp quyền Shopee.
- **Bật/Tắt Tự Nhập Sản Phẩm Khi Kết Nối:**
  - `PATCH /api/v1/marketplace-connections/shopee/settings`
  - Body:
    ```json
    {
      "auto_import_products": true
    }
    ```

### 3.2. Quản Lý Đơn Hàng & Thử Nghiệm (Orders)
- **Lấy chi tiết đơn hàng kèm danh sách sản phẩm (Items):**
  - `GET /api/v1/orders/{orderId}`
  - Response:
    ```json
    {
      "success": true,
      "data": {
        "id": 100,
        "platform": "SHOPEE",
        "platformOrderId": "261019TEST01",
        "status": "TO_SHIP",
        "totalAmount": 250000,
        "items": [
          {
            "id": 1,
            "skuId": 10,
            "quantity": 2,
            "unitPrice": 125000,
            "subtotal": 250000
          }
        ]
      }
    }
    ```
- **Tạo đơn hàng thủ công để test môi trường dev:**
  - `POST /api/v1/orders`
  - Body:
    ```json
    {
      "platform": "SHOPEE",
      "customer_name": "Nguyen Van A",
      "customer_phone": "0901234567",
      "shipping_address": "123 Le Loi, Q1, TP.HCM",
      "items": [
        {
          "sku_id": 10,
          "quantity": 2,
          "unit_price": 125000
        }
      ]
    }
    ```

### 3.3. Tối Ưu Truy Vấn Sản Phẩm (Catalog - Tránh N+1)
- **Lấy SKU hàng loạt cho nhiều sản phẩm trong 1 request:**
  - `GET /api/v1/products/skus?productIds=1,2,3,4,5`
  - Trả về danh sách toàn bộ các biến thể SKU tương ứng, giảm từ 101 requests xuống chỉ còn 1 request.

### 3.4. Danh Sách Tồn Kho Toàn Diện (Inventory)
- **Xem tồn kho đầy đủ:**
  - `GET /api/v1/inventory?page=0&size=20`
  - Trả về `InventoryItemDto` hiển thị cả SKU chưa từng phát sinh kho (mặc định tồn 0), kèm `skuCode`, `productName`, `quantityOnHand`, `reservedQuantity`, `availableQuantity`.

### 3.5. Dự Báo Nhu Cầu Hàng Hóa AI (Demand Forecast)
- **Xem dự báo AI theo thời gian tăng dần:**
  - `GET /api/v1/ai/forecast/{skuId}?from=2026-11-16&days=14`
  - Tự động sắp xếp ngày dự báo tăng dần (`ORDER BY forecast_date ASC`), chỉ trả về từ ngày được chọn hoặc từ hôm nay trở đi.

---

## 4. Nhật Ký 11 Atomic Commits Trên Nhánh `fixing-bugs`

Toàn bộ các thay đổi được chia nhỏ thành các atomic commits chuẩn conventional commits:

1. `caabd6d` - `feat(order): ensure idempotent restock and itemized return processing in OrderService`
2. `ae48968` - `feat(integration): declare RabbitMQ return queue and binding infrastructure`
3. `984a3b3` - `feat(integration): implement ShopeeReturnSyncService to fetch return detail and notify OrderService`
4. `c3052ff` - `feat(integration): connect Shopee return webhook to OrderService via return.incoming consumer and dispatcher fast-path`
5. `cd82aae` - `feat(db,common): add migration V20 and DTOs for Shopee return inspection and inventory idempotency`
6. `1255c3d` - `feat(inventory): add idempotency key support to InventoryService restock`
7. `976a3e5` - `feat(order): implement warehouse staff return inspection API and safe restock state machine`
8. `4d61aaa` - `feat(integration): route Shopee physical returns to warehouse inspection stage`
9. `d8ec6c8` - `feat(integration): support dynamic mobile redirect URI and auto-import settings for Shopee connection` *(B4, B5, C7)*
10. `4199f01` - `feat(order,inventory): add order detail with items, manual test order creation, and enriched inventory listing` *(C2, C3, C5, C8)*
11. `dd18fee` - `feat(catalog,ai): optimize SKU batch query and chronological demand forecasting` *(C4, C6)*

---

## 5. Kết Quả Kiểm Thử (Regression Tests)
Chạy kiểm thử toàn bộ các module backend:
```bash
mvn test -pl smartomni-common,smartomni-service-inventory,smartomni-service-order,smartomni-service-integration,smartomni-service-catalog,smartomni-service-ai
```
- **Kết quả:** `BUILD SUCCESS` trên cả 6/6 module.
- **Tổng số tests:** Hơn 350+ tests chạy thành công, **0 Failures, 0 Errors, 1 Skipped**.
