# Test SmartOmni với TikTok simulator trên máy dev

Hai repo chạy bằng hai Compose project riêng. TikTok simulator mở UI ở `http://localhost:3090` và API ở `http://localhost:8090`. SmartOmni chạy qua gateway ở `http://localhost` (cổng 80). Các key dưới đây chỉ là dữ liệu giả cho simulator.

## 1. Chuẩn bị cấu hình

Trong repo `Smartomni-Tiktok-Simulator`, copy `.env.example` thành `.env`, thay các giá trị `CHANGE_ME` và đặt:

```dotenv
SEED_APP_KEY=smartomni-dev-app-key
SEED_APP_SECRET=smartomni-dev-app-secret-fake
SEED_APP_REDIRECT_URIS=http://localhost:5174/merchant/channels/tiktok-shop/callback,smartomni://oauth/tiktok,http://127.0.0.1:5174/merchant/channels/tiktok-shop/callback
```

URI đầu tiên là trang callback của FE web, phải trùng chính xác với `TIKTOK_REDIRECT_URI` của SmartOmni (mặc định trong `docker-compose.yml`). Hai URI còn lại phải nằm trong `TIKTOK_ALLOWED_REDIRECT_URIS`: `smartomni://oauth/tiktok` cho mobile, và bản `127.0.0.1` cho trường hợp FE được mở ở `http://127.0.0.1:5174`. Nếu SmartOmni không gửi `redirect_uri`, Simulator dùng URI đầu tiên trong danh sách. Sau khi đổi danh sách, tạo lại container `api` của Simulator để seed cập nhật app.

Trong repo `SmartOmni_Platform`, copy `.env.example` thành `.env`, thay các placeholder của database, RabbitMQ, JWT và AES key. Giữ các giá trị `TIKTOK_*` mẫu trong `.env.example`, hoặc đổi `TIKTOK_APP_KEY` / `TIKTOK_APP_SECRET` cùng với `SEED_APP_KEY` / `SEED_APP_SECRET` ở simulator. Không commit hai file `.env`.

`TIKTOK_AUTHORIZATION_URL=http://localhost:3090/seller/authorize` là trang người bán mở trong browser. Khi dùng Compose, file override `docker-compose.tiktok-simulator.yml` nối Integration Service vào network của simulator và đặt hai URL API thành `http://api:8090`. Không dùng `host.docker.internal:8090` trong trường hợp này: simulator chỉ bind `127.0.0.1` trên host và SmartOmni dùng DNS container riêng. Nếu chạy Integration Service trực tiếp trên host, đặt hai URL API thành `http://localhost:8090` trong môi trường chạy Java.

## 2. Khởi động

Mở hai terminal PowerShell:

```powershell
cd 'C:\Users\ASUS\Desktop\Semester 9\Capstone\Code\Smartomni-Tiktok-Simulator'
docker compose up --build -d
docker compose ps
```

```powershell
cd 'C:\Users\ASUS\Desktop\Semester 9\Capstone\Code\SmartOmni_Platform'
docker compose -f docker-compose.yml -f docker-compose.tiktok-simulator.yml up --build -d
docker compose -f docker-compose.yml -f docker-compose.tiktok-simulator.yml ps
```

Chờ `db`, `api`, `ui` của simulator và `integration-service`, `gateway` của SmartOmni sẵn sàng. Kiểm tra `http://localhost:8090/health/ready` và `http://localhost/health`. Nếu sửa `.env` sau khi khởi động, chạy `docker compose up -d --force-recreate api` ở simulator hoặc `docker compose up -d --force-recreate integration-service` ở SmartOmni.

**Lỗi build simulator đã quan sát ngày 17/11/2026:** image `ui` dừng ở `npm run build` vì `src/__tests__/testAdminProxy.test.ts` import `../../api/test-admin`, trong khi `frontend/Dockerfile` chỉ copy `src`, không copy `api`. Cần sửa Dockerfile của simulator để copy `api` vào build stage, hoặc tạm chạy `docker compose up -d --build api` và dùng API consent trực tiếp để test BE. Bài test bằng trình duyệt cần UI image build thành công.

## 3. Thử OAuth từ đầu đến cuối

**Luồng web:** chạy FE (`npm run dev`, cổng 5174), bấm kết nối TikTok Shop. Khi chạy local, FE gửi `redirect_uri` là trang callback trên chính origin đang mở (`http://localhost:5174/...` hoặc `http://127.0.0.1:5174/...`), vì phiên đăng nhập được lưu theo từng origin. Nếu FE không gửi `redirect_uri`, BE dùng `TIKTOK_REDIRECT_URI`. Sau khi chọn **Authorize**, Simulator chuyển về `<origin>/merchant/channels/tiktok-shop/callback?code=...&state=...`, và trang này tự gọi callback API. Mở FE ở `localhost`/`127.0.0.1` với cổng khác 5174 sẽ bị BE trả `INVALID_REDIRECT_URI`, trừ khi URI đó được thêm vào cả hai danh sách. Với subdomain `*.lvh.me`, FE không gửi `redirect_uri` và sẽ chặn với thông báo "Open SmartOmni at http://localhost:5174…"; hãy mở FE ở `localhost:5174` để kết nối TikTok.

**Luồng mobile/thủ công:** đăng nhập SmartOmni bằng Tenant Admin và lấy access token. Dùng cùng token cho hai request sau:

```http
POST http://localhost/api/v1/marketplace-connections/tiktok-shop/authorization
Authorization: Bearer <access-token>
Content-Type: application/json

{"redirect_uri":"smartomni://oauth/tiktok"}
```

Mở `data.authorizationUrl` trong response. Đăng nhập shop ở simulator UI (`http://localhost:3090`, ví dụ `phamgiahuy.ttsim@gmail.com` / `123`; danh sách tài khoản ở README của simulator), chọn **Authorize** (trang cấp quyền chỉ dùng shop đang đăng nhập; chưa đăng nhập thì nó chuyển sang `/login` rồi quay lại), rồi lấy `code` và `state` từ deep link `smartomni://oauth/tiktok?...` mà browser hoặc app nhận được. Gửi chúng về SmartOmni trước khi code hết hạn:

```http
POST http://localhost/api/v1/marketplace-connections/tiktok-shop/callback
Authorization: Bearer <cùng-access-token>
Content-Type: application/json

{"code":"<code>","state":"<state>"}
```

Xác nhận `GET http://localhost/api/v1/marketplace-connections/tiktok-shop` với cùng JWT trả kết nối `CONNECTED`. Khi từ chối quyền, body callback có thể dùng `{"error":"access_denied","error_description":"...","state":"<state>"}`; BE vẫn nhận `errorDescription` cho client cũ. Callback phải được gửi từ user đã bắt đầu OAuth; state chỉ dùng một lần.

Để kiểm tra thêm OpenAPI sản phẩm sau OAuth, gọi `POST http://localhost/api/v1/tiktok/products/products/import` với JWT của Tenant Admin vừa kết nối. Response `data.status=completed` và `data.counters` cho biết số sản phẩm/SKU đã xử lý. Bài thử live ngày 17/11/2026 với tenant test riêng đã nhận `CONNECTED`, sau đó import 6 sản phẩm (5 tạo, 1 bỏ qua vì `DELETED`) và 8 SKU, không có sản phẩm lỗi.

Nếu chưa có app bắt deep link, dùng browser devtools hoặc chức năng copy URL chuyển hướng của browser để lấy `code` và `state`, rồi gọi callback thủ công. Không đưa code/token vào log công khai. Xem log bằng `docker compose logs -f integration-service` và `docker compose logs -f api` trong từng repo.

## 4. Webhook, polling và lưu ý khi dựng lại

- **URL webhook.** Đặt trong `.env` của SmartOmni:

  ```dotenv
  TIKTOK_WEBHOOK_URL=http://integration-service:8086/api/v1/integrations/tiktok/webhooks
  ```

  Sau khi kết nối shop, SmartOmni đăng ký 6 topic với cùng địa chỉ này: `ORDER_STATUS_CHANGE`, `PACKAGE_UPDATE`, `RETURN_STATUS_CHANGE`, `CANCELLATION_STATUS_CHANGE`, `SELLER_DEAUTHORIZATION` và `INVENTORY_CHANGED_BY_SHOP`. Scheduler đăng ký lại mỗi 15 phút. `TIKTOK_INVENTORY_WEBHOOK_URL` vẫn được đọc khi `TIKTOK_WEBHOOK_URL` trống, nhưng đã cũ. Kiểm tra bằng `GET /event/202309/webhooks` ở simulator: phải thấy 6 topic.
- **Webhook không cần JWT.** Endpoint được xác thực bằng chữ ký HMAC. Mỗi `tts_notification_id` chỉ được xử lý một lần. Khi gặp lỗi tạm thời, SmartOmni trả 503 để simulator gửi lại.
- **Polling bù.** Đơn, trả hàng và hủy đơn được quét theo `update_time`, có checkpoint trong `tiktok_sync_checkpoints` và lùi 300 giây (`smartomni.order-polling.overlap-seconds`). Có thể gọi tay bằng `POST /internal/jobs/poll-orders` (cần token nội bộ).
- **Khởi động lại gateway** (`docker restart smartomni-gateway`) sau khi tạo lại container service. Integration Service mất khoảng 84 giây để sẵn sàng, và trong lúc đó Nginx giữ upstream cũ.
- **Simulator không còn `ON_HOLD`** (từ `7f721af`). SmartOmni cũng đã bỏ nhánh này: `ON_HOLD` từ TikTok bị coi là trạng thái lạ.
- **Reset DB local sau khi đổi tên migration.** DB đã chạy `V24__tiktok_inventory_snapshots` trước khi file được đổi thành `V25`, nên Flyway báo lệch. Chạy `docker compose -f docker-compose.yml -f docker-compose.tiktok-simulator.yml down -v`, rồi `up --build -d`, sau đó tạo lại tenant bằng `POST /api/v1/tenants/onboarding`. Lệnh `down -v` xóa toàn bộ dữ liệu local.

## 5. Lỗi thường gặp

| Hiện tượng | Kiểm tra |
| --- | --- |
| `TIKTOK_NOT_CONFIGURED` | Ba biến app key, app secret, authorization URL đã vào container Integration Service chưa? Chạy `docker compose config` và tạo lại container. |
| Simulator báo app/redirect không hợp lệ | Hai cặp key/secret phải trùng; URI callback phải khớp chính xác `SEED_APP_REDIRECT_URIS`. |
| Token exchange không kết nối được | Từ container SmartOmni, API simulator dùng `host.docker.internal:8090`; simulator phải đang chạy. |
| `TIKTOK_STATE_EXPIRED` hoặc `TIKTOK_STATE_REUSED` | Bắt đầu OAuth lại để lấy state và code mới. |

Simulator và các giá trị mẫu này chỉ phục vụ dev, không dùng với TikTok thật.
