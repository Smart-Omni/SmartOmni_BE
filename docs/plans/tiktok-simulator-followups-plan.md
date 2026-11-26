# Plan 1/2: sửa code và tài liệu trước khi dùng TikTok Simulator đã deploy

Cập nhật: 26/11/2026.

| Thứ tự | Plan | Nội dung |
|---|---|---|
| **1** | **File này** | Sửa code và tài liệu, merge vào `develop` |
| 2 | [tiktok-simulator-deployed-plan.md](tiktok-simulator-deployed-plan.md) | Chạy SmartOmni local với simulator trên Render/Vercel |

**Chỉ bắt đầu plan 2 khi cả 4 phase ở đây đã merge vào `develop`.**

Ngoài phạm vi: PR phát hành `develop` → `main`.

## 0. Quy trình GitHub chung

Theo `docs/github-workflow.md` và `CONTRIBUTING.md`.

### Vì sao không bị conflict

- **Làm tuần tự.** Phase sau chỉ tạo nhánh khi PR của phase trước đã merge, và luôn tạo từ `develop` mới nhất.
- **File không chồng nhau.** Mỗi phase sửa một nhóm file riêng (bảng dưới).
- Hai file plan chỉ commit một lần ở phase 0. Sau đó mỗi phase chỉ sửa dòng **Trạng thái** trong mục của chính phase đó.
- Không sửa `.env`, `output/` hay file ngoài bảng trong bất kỳ phase nào.

| Phase | Nhánh | File được sửa |
|---|---|---|
| 0 | `docs/tiktok-simulator-plans` | `docs/plans/tiktok-simulator-followups-plan.md`, `docs/plans/tiktok-simulator-deployed-plan.md` |
| 1 | `fix/integration-webhook-connection-lookup` | `smartomni-service-integration/**` (repository, controller, store, executor, test); dòng Trạng thái phase 1 |
| 2 | `fix/catalog-tiktok-product-url` | `smartomni-service-catalog/**`, `docker-compose.yml`, `.env.example`; dòng Trạng thái phase 2 |
| 3 | `docs/tiktok-simulator-deployed` | `docs/tiktok-simulator-local.md`; dòng Trạng thái phase 3 |

### Các bước của mỗi phase

1. **Tạo nhánh từ `develop` mới nhất.** Working tree phải sạch, trừ `output/` (không commit thư mục này):

   ```powershell
   git status --short --branch
   git fetch origin
   git switch develop
   git pull --ff-only origin develop
   git switch -c <nhanh-cua-phase>
   ```

   Lần đầu chưa có `develop` cục bộ thì dùng `git switch --track origin/develop`.

2. Làm theo mục của phase.
3. Chạy test nhanh của module liên quan (ghi trong từng phase).
4. Sửa dòng **Trạng thái** của phase thành `Xong, chờ PR`.
5. **Commit khi xong phase.** Chỉ stage file trong bảng trên, kiểm tra rồi commit theo Conventional Commits:

   ```powershell
   git add -- <cac-file-cua-phase>
   git diff --cached
   git commit -m "<type>(<scope>): <mo-ta>"
   ```

6. **Kiểm tra toàn bộ** (giống CI), rồi push và mở PR vào `develop`:

   ```powershell
   mvn -B clean verify
   ```

   ```powershell
   git push -u origin <nhanh-cua-phase>
   ```

   ```powershell
   gh pr create --base develop --head <nhanh-cua-phase> --title "<giong-commit>" --body-file <file-mo-ta>
   ```

   Mô tả PR gồm: mục tiêu, thay đổi chính, cách kiểm thử và kết quả, ảnh hưởng tới API, migration hoặc cấu hình, và việc còn lại.

7. **Chờ CI xanh và ít nhất một approval.** Sửa theo review bằng commit mới trên cùng nhánh, không force-push.
8. **Sau khi merge:** về `develop`, kéo bản mới và xoá nhánh cục bộ:

   ```powershell
   git switch develop
   git pull --ff-only origin develop
   git branch -d <nhanh-cua-phase>
   ```

9. Chỉ khi xong bước 8 mới bắt đầu phase tiếp theo.

Nếu `develop` thay đổi trong lúc PR đang review: `git merge origin/develop` vào nhánh phase, chạy lại test, rồi push. Không rebase nhánh đã push.

## Phase 0: commit hai file plan

**Trạng thái:** Xong, chờ PR

- Nhánh: `docs/tiktok-simulator-plans`.
- Commit cả hai file plan ở trạng thái hiện tại, không đổi nội dung kỹ thuật.
- Commit: `docs(plans): add TikTok simulator follow-up and deployed plans`.
- Không cần chạy `mvn` (chỉ có tài liệu), nhưng CI vẫn chạy trên PR.

## Phase 1: chọn đúng connection theo định danh shop

**Trạng thái:** Xong, chưa push

### Hiện trạng

Ba chỗ tra connection theo định danh shop mà không lọc trạng thái hay nền tảng:

| Chỗ | Dùng để |
|---|---|
| `TiktokWebhookController.findConnectionByShop` | Nhận webhook |
| `TiktokConnectionStore.findByShopIdentifier` | `TiktokConnectionService.markRevoked(String)` |
| `TiktokShopApiExecutor.callByShopCipher` | Gọi Open API từ catalog, order, return, fulfillment client |

`TiktokConnectionStore.terminate()` xoá token nhưng giữ `shop_id`, `shop_cipher`, `seller_open_id`. Nếu tenant cũ và tenant mới cùng có dòng cho một shop, các hàm trên có thể lấy dòng `disconnected`:

- Webhook ghi vào sai tenant, hoặc lỗi vì không có token.
- `markRevoked(String)` chuyển dòng cũ sang `REVOKED`, còn dòng đang `CONNECTED` vẫn giữ nguyên.
- Open API báo lỗi kết nối dù shop đang kết nối.

### Cách sửa

1. Thêm query vào `MarketplaceConnectionRepository`. **Không đổi** `findFirstByShopId`, vì `ShopeeReturnSyncService` đang dùng nó cho Shopee.

   ```java
   Optional<MarketplaceConnection> findFirstByPlatformAndShopCipherAndStatusNotOrderByIdAsc(
           Platform platform, String shopCipher, ConnectionStatus status);
   Optional<MarketplaceConnection> findFirstByPlatformAndShopIdAndStatusNotOrderByIdAsc(
           Platform platform, String shopId, ConnectionStatus status);
   Optional<MarketplaceConnection> findFirstByPlatformAndSellerOpenIdAndStatusNotOrderByIdAsc(
           Platform platform, String sellerOpenId, ConnectionStatus status);
   ```

2. Thêm một hàm dùng chung (`default` method trong repository, hoặc trong `TiktokConnectionStore`), ví dụ `findTiktokByShopIdentifier(String id)`:
   - Duyệt theo thứ tự cipher → shop id → seller open id.
   - Với mỗi định danh, tìm dòng TikTok khác `DISCONNECTED` trước.
   - Không có dòng đang hoạt động thì giữ hành vi cũ: lấy dòng đầu tiên khớp định danh, kể cả đã disconnect. Như vậy webhook của shop đã ngắt vẫn trả lỗi như hiện nay.
   - Có thêm biến thể chỉ theo cipher cho `callByShopCipher`.

3. Cho cả ba chỗ ở bảng trên gọi hàm mới.
   - Trong controller, giữ nguyên `TenantContext.openScope(null, CONNECTION_LOOKUP_ROLE, null)` quanh lần tra cứu. Test `shopLookupRunsWithCrossTenantReadRole` đang kiểm tra điều này.

### Test

- Trước khi sửa, grep `src/test` để tìm mọi test đang mock ba finder cũ.
- `TiktokWebhookControllerTest`: đổi các mock `findFirstByShopCipher` (setUp, khoảng dòng 120, 297, 318–320) sang hàm mới.
- Thêm test:
  - Một dòng `disconnected` và một dòng `connected` cùng `shop_cipher`: webhook vào dòng `connected`.
  - Chỉ có dòng `disconnected`: hành vi giữ như cũ.
  - `markRevoked(String)` và `callByShopCipher` với dữ liệu trùng như trên.
- Test nhanh:

  ```powershell
  mvn -pl smartomni-service-integration -am test
  ```

### Commit

`fix(integration): prefer active TikTok connection when resolving a shop`

### Ngoài phạm vi

- Không thêm unique index hay migration.
- Không xoá định danh shop khi disconnect: giữ lại để audit, đúng như comment của `terminate()`.

## Phase 2: link sản phẩm TikTok theo cấu hình

**Trạng thái:** Chưa làm

### Hiện trạng

- `UnifiedCatalogService.java:34` ghi cứng `https://tiktok-simulator.onrender.com/product/`, không phải domain đang deploy.
- Hằng số này dùng để dựng link khi link chưa có URL (khoảng dòng 151–153 và 268), và được **ghi vào DB** trong `bindSkuMapping` (khoảng dòng 313).
- `TiktokAutoImportService.resolveProductUrl` đọc `smartomni.tiktok.product-url-template`, nhưng `docker-compose.yml` không truyền `TIKTOK_PRODUCT_URL_TEMPLATE` vào container. Vì vậy 200 link TikTok trong DB local đều chưa có URL.
- Đã kiểm tra: `platform_item_id` trong DB khớp `id` sản phẩm của `/demo/catalog` trên Vercel (5/5 mẫu). Trang `/product/{productId}` của simulator chỉ hiển thị sản phẩm khi **đã đăng nhập buyer**.

### Cách sửa

1. **Một biến cho cả hai service:** `TIKTOK_PRODUCT_URL_TEMPLATE`, có chỗ giữ chỗ `{productId}`.
   - `docker-compose.yml`: thêm `TIKTOK_PRODUCT_URL_TEMPLATE: ${TIKTOK_PRODUCT_URL_TEMPLATE:-}` cho `integration-service` và `catalog-service`.
   - `.env.example`: thêm dòng mẫu để trống, có chú thích.
   - `smartomni-service-catalog/src/main/resources/application.yml`: thêm `smartomni.tiktok.product-url-template: ${TIKTOK_PRODUCT_URL_TEMPLATE:}`.
2. **`UnifiedCatalogService`:**
   - Bỏ hằng số `TIKTOK_SIMULATOR_BASE_URL`, đọc template từ cấu hình (`@Value` hoặc một `@ConfigurationProperties` nhỏ).
   - Thêm hàm `tiktokProductUrl(String itemId)`: template trống hoặc `itemId` null thì trả `null`, không dựng link giả.
   - Thay các chỗ đang cộng chuỗi với hằng số bằng hàm này.
3. **Dữ liệu đã lưu:** `JdbcCatalogMappingStore` dùng `COALESCE` cho `platform_product_url`, nên import lại chỉ điền dòng đang trống. DB nào đã lưu domain cũ thì chạy một lần (DB local hiện có 0 dòng như vậy):

   ```sql
   UPDATE product_marketplace_links
   SET platform_product_url = replace(platform_product_url,
       'https://tiktok-simulator.onrender.com/product/',
       'https://smartomni-tiktok-simulator-ten.vercel.app/product/')
   WHERE platform = 'tiktok_shop'
     AND platform_product_url LIKE 'https://tiktok-simulator.onrender.com/product/%';
   ```

Giá trị thật của biến được đặt ở **plan 2, bước 2**, không đặt trong phase này.

### Test

- Test `UnifiedCatalogService`:
  - Template trống: URL là `null`.
  - Có template: `{productId}` được thay đúng.
  - URL đã lưu được giữ nguyên.
- Kiểm tra `docker compose config` có biến trong cả hai service.
- Test nhanh:

  ```powershell
  mvn -pl smartomni-service-catalog -am test
  ```

### Commit

`fix(catalog): build TikTok product links from TIKTOK_PRODUCT_URL_TEMPLATE`

### Ngoài phạm vi

- Gợi ý mapping giả `TT-PROD-4`, `TT-PROD-2` trong `computeSmartSuggestion`.
- Hằng số và link của Shopee.
- Link có `shopId` (`/product/{shopId}/{id}`): SmartOmni không lưu UUID shop của simulator.

## Phase 3: tài liệu `docs/tiktok-simulator-local.md`

**Trạng thái:** Chưa làm

### Sửa mâu thuẫn `host.docker.internal`

Đã kiểm tra:

- Các service SmartOmni dùng DNS riêng `172.28.0.53` (dnsmasq, chuyển tiếp tới `8.8.8.8`).
- Hỏi DNS này thì `host.docker.internal` trả **NXDOMAIN**; DNS mặc định của Docker thì phân giải được.
- Vậy `http://host.docker.internal:8090` không dùng được từ `integration-service`. Chưa thử trực tiếp trong container này vì lúc kiểm tra nó đang tắt.

Sửa:

- **Mục 1 (dòng 19):** giữ ý "không dùng `host.docker.internal`", sửa lý do: DNS riêng của SmartOmni không phân giải được tên này. Dùng override `docker-compose.tiktok-simulator.yml` với `http://api:8090`.
- **Bảng "Lỗi thường gặp" (dòng 92):** dòng "Token exchange không kết nối được" đổi thành:
  - đã chạy với override chưa;
  - simulator đã chạy trước chưa, để network `smartomni-tiktok-simulator_sim_edge` tồn tại;
  - `docker compose config` có hai base URL là `http://api:8090` không.
- Nhắc: `.env` nào đang để `host.docker.internal:8090` thì đổi về giá trị của `.env.example`.

Ngoài phạm vi: `ai-service` cũng dùng DNS này với `PYTHON_AI_BASE_URL=http://host.docker.internal:8000`, nên có thể gặp cùng lỗi. Cần kiểm tra riêng.

### Thêm mục "Dùng simulator đã deploy"

Mục ngắn; chi tiết thì dẫn link sang `docs/plans/tiktok-simulator-deployed-plan.md`:

- Bảng địa chỉ: Render API, Vercel UI.
- Bảng env: hai base URL, authorization URL, app secret lấy từ `SEED_APP_SECRET` trên Render, `TIKTOK_WEBHOOK_URL`, `TIKTOK_PRODUCT_URL_TEMPLATE`.
- Không dùng file override khi trỏ sang bản deploy.
- Webhook: chạy local thì cần tunnel (`cloudflared`); BE đã deploy thì dùng thẳng domain của BE.
- Đổi qua lại giữa local và deploy: chỉ cần **Reconnect**, không cần disconnect.
- Đánh thức Render trước mỗi phiên test.

### Commit

`docs(integration): document deployed TikTok simulator and fix host.docker.internal note`

## Nghiệm thu plan 1

| # | Kiểm tra | Đạt khi |
|---|---|---|
| 1 | 4 PR (phase 0–3) | Đã merge vào `develop`, CI xanh |
| 2 | `mvn -B clean verify` trên `develop` mới nhất | Pass, có test mới của phase 1 và 2 |
| 3 | `git grep "tiktok-simulator.onrender.com" -- smartomni-service-catalog/src/main` | Không còn kết quả |
| 4 | `docker compose config` | `TIKTOK_PRODUCT_URL_TEMPLATE` có trong `integration-service` và `catalog-service` |
| 5 | Đọc `docs/tiktok-simulator-local.md` | Không còn hướng dẫn dùng `host.docker.internal:8090`; có mục simulator đã deploy |

Kiểm tra trên môi trường chạy thật (webhook, link sản phẩm) nằm ở **mục 3 của plan 2**.

**Xong mục này → chuyển sang [plan 2](tiktok-simulator-deployed-plan.md).**
