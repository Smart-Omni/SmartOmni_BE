# Test SmartOmni với TikTok simulator trên máy dev

Hai repo chạy bằng hai Compose project riêng. TikTok simulator mở UI ở `http://localhost:3090` và API ở `http://localhost:8090`. SmartOmni chạy qua gateway ở `http://localhost` (cổng 80). Các key dưới đây chỉ là dữ liệu giả cho simulator.

## 1. Chuẩn bị cấu hình

Trong repo `Smartomni-Tiktok-Simulator`, copy `.env.example` thành `.env`, thay các giá trị `CHANGE_ME` và đặt:

```dotenv
SEED_APP_KEY=smartomni-dev-app-key
SEED_APP_SECRET=smartomni-dev-app-secret-fake
SEED_APP_REDIRECT_URIS=smartomni://oauth/tiktok
```

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

Đăng nhập SmartOmni bằng Tenant Admin và lấy access token. Dùng cùng token cho hai request sau:

```http
POST http://localhost/api/v1/marketplace-connections/tiktok-shop/authorization
Authorization: Bearer <access-token>
Content-Type: application/json

{"redirect_uri":"smartomni://oauth/tiktok"}
```

Mở `data.authorizationUrl` trong response. Đăng nhập shop ở simulator UI, chọn **Authorize**, rồi lấy `code` và `state` từ deep link `smartomni://oauth/tiktok?...` mà browser hoặc app nhận được. Gửi chúng về SmartOmni trước khi code hết hạn:

```http
POST http://localhost/api/v1/marketplace-connections/tiktok-shop/callback
Authorization: Bearer <cùng-access-token>
Content-Type: application/json

{"code":"<code>","state":"<state>"}
```

Xác nhận `GET http://localhost/api/v1/marketplace-connections/tiktok-shop` với cùng JWT trả kết nối `CONNECTED`. Khi từ chối quyền, body callback có thể dùng `{"error":"access_denied","error_description":"...","state":"<state>"}`; BE vẫn nhận `errorDescription` cho client cũ. Callback phải được gửi từ user đã bắt đầu OAuth; state chỉ dùng một lần.

Để kiểm tra thêm OpenAPI sản phẩm sau OAuth, gọi `POST http://localhost/api/v1/tiktok/products/products/import` với JWT của Tenant Admin vừa kết nối. Response `data.status=completed` và `data.counters` cho biết số sản phẩm/SKU đã xử lý. Bài thử live ngày 17/11/2026 với tenant test riêng đã nhận `CONNECTED`, sau đó import 6 sản phẩm (5 tạo, 1 bỏ qua vì `DELETED`) và 8 SKU, không có sản phẩm lỗi.

Nếu chưa có app bắt deep link, dùng browser devtools hoặc chức năng copy URL chuyển hướng của browser để lấy `code` và `state`, rồi gọi callback thủ công. Không đưa code/token vào log công khai. Xem log bằng `docker compose logs -f integration-service` và `docker compose logs -f api` trong từng repo.

## 4. Lỗi thường gặp

| Hiện tượng | Kiểm tra |
| --- | --- |
| `TIKTOK_NOT_CONFIGURED` | Ba biến app key, app secret, authorization URL đã vào container Integration Service chưa? Chạy `docker compose config` và tạo lại container. |
| Simulator báo app/redirect không hợp lệ | Hai cặp key/secret phải trùng; URI callback phải khớp chính xác `SEED_APP_REDIRECT_URIS`. |
| Token exchange không kết nối được | Từ container SmartOmni, API simulator dùng `host.docker.internal:8090`; simulator phải đang chạy. |
| `TIKTOK_STATE_EXPIRED` hoặc `TIKTOK_STATE_REUSED` | Bắt đầu OAuth lại để lấy state và code mới. |

Simulator và các giá trị mẫu này chỉ phục vụ dev, không dùng với TikTok thật.
