# Storefront — API catalog công khai

## Mục tiêu

Khách chưa đăng nhập mở `/store/{subdomain}` thấy sản phẩm đang bán của đúng cửa hàng, kèm giá và link mua trên Shopee/TikTok Shop. Không lộ dữ liệu nội bộ, không đọc chéo tenant.

Ngoài phạm vi: giỏ hàng/đặt hàng, flash sale, assistant, danh mục, màu sắc, đổi schema.

## Hiện trạng (khảo sát 2026-11-27, DB local)

- `GET /api/v1/products` không token trả 401. Chưa có API catalog công khai.
- FE API mode (`ApiStorefront.jsx`) chỉ gọi profile cửa hàng, thông báo "chưa có sản phẩm" viết cứng.
- Tenant `e2e772b1c`: 13 sản phẩm `active`, 100 SKU, 100 link TikTok `ACTIVATE` có giá VND và URL, 100 dòng tồn kho còn hàng. **Không có ảnh** (0 dòng `product_images`, 0 `image_url` trên link).
- `products`, `product_skus`, `product_marketplace_links`, `marketplace_connections` đã có policy `tenant_isolation` theo `app.tenant_id`.

## Quyết định

### API

| Endpoint | Trả về |
|---|---|
| `GET /api/v1/products/public/by-subdomain/{subdomain}?page&size&search&sort` | Trang sản phẩm |
| `GET /api/v1/products/public/by-subdomain/{subdomain}/{productId}` | Chi tiết một sản phẩm |

- Nằm dưới location `/api/v1/products/` sẵn có nên **không sửa nginx**. Cách đặt tên giống API profile của tenant.
- Subdomain lấy từ **path**, không lấy từ Host: FE mở qua `127.0.0.1:5174` không có Host của tenant.
- `size` mặc định 20, tối đa 50. `sort` chỉ nhận `newest` (mặc định) hoặc `name`. `search` lọc theo tên.
- Bọc trong `ApiResponse<T>`. Trang rỗng trả 200.
- 404 khi: subdomain sai định dạng/không tồn tại, tenant không `active`, sản phẩm không thuộc tenant hoặc không hiển thị.

### Bảo mật

Catalog không có `SecurityFilterChain` riêng, đang dùng `ServiceSecurityAutoConfiguration` của common. Thêm vào đó đúng một rule `permitAll` cho `HttpMethod.GET`, path `/api/v1/products/public/**`.

- Chọn cách này vì khai báo chain riêng sẽ thay toàn bộ chain chung (`@ConditionalOnMissingBean`) và phải chép lại mọi rule.
- Đổi lại, rule có hiệu lực ở mọi service dùng chain chung; hiện chỉ catalog có path này. Cần build/test lại các module phụ thuộc common.
- `JwtAuthenticationFilter` vẫn từ chối token hết hạn ngay cả trên path `permitAll`. FE phải gọi **không kèm token** (`authenticated: false`), như API profile.

### Tenant và RLS

```
Controller (KHÔNG transaction)
  1. tenantPlanResolver.findTenantBySubdomain(subdomain)
  2. status khác "active" hoặc không thấy → 404
  3. try (TenantContext.openScope(tenant.id(), null, null, subdomain)) {
         4. service @Transactional(readOnly = true)  → RLS lọc theo app.tenant_id
     }
```

- Resolver **không lọc status**, nên controller phải tự kiểm tra.
- Scope phải mở **trước khi transaction bắt đầu**: `RlsJpaDialect` chép `TenantContext` vào `app.tenant_id` lúc begin. Mở scope bên trong method `@Transactional` là quá muộn.
- Context có sẵn có thể sai: `SubdomainContextFilter` nạp theo Host, JWT nạp theo user đang đăng nhập. Tenant từ path phải ghi đè cả hai.
- Role để trống. **Không dùng `super_admin`** như `TenantServiceImpl`/`AuthServiceImpl`: chỉ cần thiếu một điều kiện `WHERE` là lộ mọi tenant.
- Truy vấn vẫn lọc `tenant_id` tường minh (lớp bảo vệ thứ hai). Không cần migration.
- Giới hạn đã biết: resolver nuốt lỗi DB và trả rỗng, nên lỗi DB lúc tra tenant hiện thành 404.

### Quy tắc hiển thị

Một **offer** chỉ tồn tại khi đủ cả:

- sản phẩm `status = active`;
- link có `sku_id` khác null và `platform_price` khác null;
- connection của link `status = connected`;
- `external_status` là một trong `ACTIVATE` (TikTok), `NORMAL` (Shopee), `ACTIVE` (bind tay). Null hoặc giá trị khác bị loại.

Platform `STOREFRONT` không tính. Nếu một SKU có nhiều link cùng sàn, lấy link `last_synced_at` mới nhất.

- `available = quantity_on_hand − reserved_quantity > 0` (không có dòng tồn kho → `false`). Chỉ trả boolean.
- Sản phẩm không có offer nào bị loại, và phải loại **ngay trong SQL phân trang** để `totalElements` đúng.
- Sản phẩm hết hàng vẫn hiện, đánh dấu hết hàng.

### Dữ liệu trả về

DTO mới theo kiểu whitelist, không dùng lại `UnifiedProductDto` (có số lượng, `sellerSku`, external id, gợi ý mapping).

```json
{ "id": 1, "name": "…", "description": "…", "imageUrl": null,
  "variants": [ { "id": 10, "variantName": "Đen / Cỡ S",
    "offers": [ { "platform": "tiktok_shop", "price": 159000, "currency": "VND",
                  "available": true, "url": "https://…" } ] } ] }
```

- Không trả: `tenant_id`, `base_price`, `sku_code`, số lượng, connection/external id, timestamp.
- Ảnh: `product_images` theo `sort_order`, rồi đến `image_url` của link, không có thì `null`.
- URL: tách phần chuẩn hóa URL Shopee/TikTok khỏi `UnifiedCatalogService` thành helper dùng chung. Chỉ trả URL `http/https`; không dựng được thì `null`.
- Nạp theo lô (sản phẩm → SKU → link/ảnh/tồn kho), không N+1.

## Phase 1 — Mở API công khai an toàn

1. Thêm DTO, controller, service với cách đặt scope và quy tắc hiển thị ở trên; làm API danh sách.
2. Thêm rule `permitAll` GET.
3. Test: request ẩn danh vào path public trả 200; `GET /api/v1/products` ẩn danh **vẫn 401**; POST vào path public trả 401.
4. Test tích hợp PostgreSQL theo mẫu `TiktokCatalogDatabaseTest`:
   - user tenant A xem cửa hàng B chỉ thấy sản phẩm B;
   - Host `e2e772b1c.…` nhưng path `demo-shop` trả dữ liệu demo-shop;
   - token super admin không mở rộng kết quả;
   - tenant bị khóa trả 404;
   - sản phẩm `hidden`, connection ngắt, listing ngừng bán không xuất hiện.
5. Chạy `mvn -pl smartomni-service-catalog,smartomni-migration-tests -am verify`. Đạt → commit `feat(catalog): add public storefront product list`.

## Phase 2 — Chi tiết sản phẩm

1. Thêm API chi tiết, cùng DTO và quy tắc.
2. Test `productId` của tenant khác, sản phẩm ẩn, sản phẩm không có offer đều 404.
3. Chạy `mvn -B clean verify` (phase 1 đã đụng module common). Cập nhật tài liệu API. Đạt → commit `feat(catalog): add public storefront product detail`.

## Phase 3 — Frontend (repo `SmartOmni_FE`, theo `AGENTS.md` của repo đó)

Nhánh `feature/` từ `develop`. Lúc khảo sát repo đang có thay đổi chưa commit ở `feature/tiktok-order-hub`; không đụng vào.

1. Thêm client gọi hai API, không kèm token.
2. `ApiStorefront` hiện không render `Outlet`: cần render và cấp context `{ base, products, store }`.
3. Chuyển dữ liệu:
   - `platform` → `channel` (`tiktok_shop` → `tiktok`); `url` → `listingUrl`;
   - FE dùng `variant.sku` làm khóa trên URL: gán bằng `variants[].id`; id sản phẩm đổi sang chuỗi;
   - `variantName` là nhãn biến thể (thay cho `colour`, sửa chữ "colours available"); **ẩn bộ lọc danh mục và màu** ở API mode (DB không có category, colour, badge, imageMode);
   - ảnh `null` dùng placeholder.
4. `CatalogPage` đang lọc/sắp xếp phía client. Ở API mode, tìm kiếm và phân trang theo server; ẩn sắp xếp và lọc giá, lọc sàn vì chỉ đúng trên một trang.
5. Giữ thông báo "chưa hỗ trợ" cho flash sale và assistant. Mô tả sản phẩm hiển thị dạng text thuần.
6. Cập nhật `tests/api-storefront.spec.js`, chạy `npm run lint` và `npm run test:api`. Đạt → commit `feat(storefront): show public catalog in API mode`.

## Hoàn tất khi

- `e2e772b1c` hiện 13 sản phẩm, offer TikTok giá 159.000–493.000 VND, **chỉ có ảnh placeholder**.
- `demo-shop` trả 200 danh sách rỗng, không phải 404. Tenant bị khóa trả 404.
- API quản trị vẫn yêu cầu đăng nhập; không có đường đọc chéo tenant.

## Việc tách riêng

Tìm nguyên nhân import TikTok không lưu ảnh sản phẩm.
