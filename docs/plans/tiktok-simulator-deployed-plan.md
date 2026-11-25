# Plan 2/2: trỏ SmartOmni (chạy local) sang TikTok Simulator đã deploy

Cập nhật: 26/11/2026.

| Thứ tự | Plan | Nội dung |
|---|---|---|
| 1 | [tiktok-simulator-followups-plan.md](tiktok-simulator-followups-plan.md) | Sửa code và tài liệu, merge vào `develop` |
| **2** | **File này** | Chạy SmartOmni local với simulator trên Render/Vercel |

**Điều kiện bắt đầu:** cả 4 phase của plan 1 đã merge vào `develop` (xem "Nghiệm thu plan 1"). Plan này chỉ đổi `.env` và dữ liệu dev local, **không tạo commit hay PR**.

## 1. Bối cảnh

| Thành phần | Địa chỉ |
|---|---|
| Simulator API (Render) | `https://smartomni-tiktok-simulator-api.onrender.com` |
| Simulator UI (Vercel) | `https://smartomni-tiktok-simulator-ten.vercel.app` |
| SmartOmni BE (`SmartOmni_Platform`, docker compose) | `http://localhost` (nginx, cổng 80) |
| SmartOmni FE (`SmartOmni_FE`, vite) | `http://localhost:5174` |

Mục tiêu: OAuth chạy được, SmartOmni gọi được Open API, webhook từ Render về được SmartOmni ở local.

| Luồng | Đi từ → đến |
|---|---|
| Open API và token | integration-service (local) → Render |
| Trang consent OAuth | trình duyệt → Vercel `/seller/authorize` → `http://localhost:5174/merchant/channels/tiktok-shop/callback` |
| Webhook | Render worker → Cloudflare tunnel → nginx `localhost:80` → integration-service `/api/v1/integrations/tiktok/webhooks` |

### Đã kiểm tra

- Vercel `/seller/authorize`, `/console`, `/health/ready` và Render `/health/ready` trả 200.
- Vercel `/test-admin/status` trả 200, nên `TEST_ADMIN_TOKEN` hai bên đã khớp. Worker webhook đang chạy, đồng hồ simulator không lệch, đã seed 5 shop.
- Webhook không cần JWT: đã sửa ở commit `f725339`, có trên `develop`. Vì vậy plan này chạy từ `develop`, không chạy từ `main`.

### Chưa kiểm tra

- Giá trị env trên Render (`SEED_APP_SECRET`, `SEED_APP_REDIRECT_URIS`).
- Toàn bộ luồng OAuth, Open API và webhook với bản deploy.

Phạm vi: chỉ đổi cấu hình local. Code cần sửa đã làm ở plan 1. **Không cần sửa Render hay Vercel.**

Mọi lệnh bên dưới chạy trong thư mục `SmartOmni_Platform`.

## 2. Các bước

### Bước 0: Chuẩn bị

1. Chuyển sang `develop` mới nhất (đã có code của plan 1). Working tree phải sạch, trừ `output/`:

   ```powershell
   git status --short --branch
   git fetch origin
   git switch develop
   git pull --ff-only origin develop
   ```

   Kiểm tra có fix webhook. Lệnh sau phải in ra `0`:

   ```powershell
   git merge-base --is-ancestor f725339 HEAD; $LASTEXITCODE
   ```

   Kiểm tra có biến mới của plan 1 phase 2 (phải có kết quả):

   ```powershell
   git grep -n TIKTOK_PRODUCT_URL_TEMPLATE -- docker-compose.yml
   ```

2. Sao lưu `.env` để quay lại simulator local. `.local-backups/` đã được git bỏ qua.

   ```powershell
   Copy-Item .env .local-backups\env.local-sim.bak
   ```

3. Nếu simulator local đang chạy, tắt nó để không nhầm cổng 3090/8090 với bản deploy (chạy trong repo `Smartomni-Tiktok-Simulator`): `docker compose stop`.
4. Cài `cloudflared`, sau đó mở terminal mới:

   ```powershell
   winget install --id Cloudflare.cloudflared
   ```

### Bước 1: Mở tunnel

Chạy trong một terminal riêng và giữ nguyên:

```powershell
cloudflared tunnel --url http://localhost:80
```

Ghi lại URL `https://<random>.trycloudflare.com` (dưới đây gọi là `<tunnel>`).

- Tunnel đưa **toàn bộ gateway SmartOmni** ra internet (đăng nhập, onboarding, Swagger, `/webhook/**`), không chỉ endpoint TikTok. nginx chỉ chặn `/internal/`. Chỉ bật khi test và tắt ngay sau đó.
- Quick Tunnel đổi URL mỗi lần chạy lại. Xem mục 4.
- Trước khi stack chạy, gọi qua tunnel sẽ lỗi 502. Điều này bình thường.

### Bước 2: Sửa `.env` của SmartOmni

| Biến | Giá trị |
|---|---|
| `TIKTOK_OPEN_API_BASE_URL` | `https://smartomni-tiktok-simulator-api.onrender.com` |
| `TIKTOK_TOKEN_BASE_URL` | `https://smartomni-tiktok-simulator-api.onrender.com` |
| `TIKTOK_AUTHORIZATION_URL` | `https://smartomni-tiktok-simulator-ten.vercel.app/seller/authorize` |
| `TIKTOK_APP_KEY` | `smartomni-dev-app-key` |
| `TIKTOK_APP_SECRET` | giá trị `SEED_APP_SECRET` trên Render (service → Environment) |
| `TIKTOK_WEBHOOK_URL` | `<tunnel>/api/v1/integrations/tiktok/webhooks` (thêm dòng mới) |
| `TIKTOK_PRODUCT_URL_TEMPLATE` | `https://smartomni-tiktok-simulator-ten.vercel.app/product/{productId}` (thêm dòng mới, biến của plan 1 phase 2) |

- Không thêm `TIKTOK_INVENTORY_WEBHOOK_URL`: biến này đã cũ.
- Không cần thêm `TIKTOK_REDIRECT_URI` và `TIKTOK_ALLOWED_REDIRECT_URIS`: mặc định trong `docker-compose.yml` đã khớp.
- Không đưa app secret vào chat, log hay git.
- Trên Render, nếu bạn đã tự đặt `SEED_APP_REDIRECT_URIS` thì giá trị đó phải chứa `http://localhost:5174/merchant/channels/tiktok-shop/callback` và bản `127.0.0.1`. Nếu chưa đặt thì mặc định đã đúng. Giữ `WEBHOOK_REWRITE_LOCALHOST=false`.

### Bước 3: Khởi động SmartOmni

**Không dùng** `docker-compose.tiktok-simulator.yml`: file này ghi đè hai base URL về simulator local.

```powershell
docker compose up -d --build
```

Chờ khoảng 1–2 phút cho các service sẵn sàng, rồi khởi động lại gateway để nginx nhận đúng upstream:

```powershell
docker restart smartomni-gateway
```

Kiểm tra env đã vào container (lệnh này không in secret):

```powershell
docker exec smartomni-integration-service printenv TIKTOK_OPEN_API_BASE_URL TIKTOK_AUTHORIZATION_URL TIKTOK_WEBHOOK_URL TIKTOK_PRODUCT_URL_TEMPLATE
```

```powershell
docker exec smartomni-catalog-service printenv TIKTOK_PRODUCT_URL_TEMPLATE
```

### Bước 4: Kiểm tra endpoint webhook

Gọi qua local, sau đó đổi `http://localhost` thành `<tunnel>` và gọi lại:

```powershell
curl.exe -i -X POST http://localhost/api/v1/integrations/tiktok/webhooks -H "Content-Type: application/json" -H "Authorization: x" -d "{}"
```

| Kết quả | Ý nghĩa |
|---|---|
| 400 `Missing or invalid timestamp` | **Đúng**: request đã tới controller |
| 401 body JSON `UNAUTHORIZED` | Spring Security chặn: image cũ hoặc sai nhánh |
| 401 `Missing signature` | Thiếu header `Authorization` trong lệnh |
| 502 | integration-service chưa sẵn sàng, hoặc chưa restart gateway |

### Bước 5: Giữ nguyên kết nối, không disconnect

Token trong DB do simulator local cấp nên Render không nhận. Sau khi đổi `.env`, kết nối cũ có thể vẫn hiện `CONNECTED` một lúc, rồi chuyển sang `REFRESH_FAILED` khi SmartOmni thử refresh token. Điều này bình thường: chỉ cần **Reconnect** để cấp token mới. Dòng connection được dùng lại, sản phẩm và đơn đã import vẫn còn.

Kết nối hiện có trong DB local (kiểm tra ngày 25/11/2026), cả hai shop đều có trên Render:

| Tenant | Shop | Đăng nhập seller trên Vercel |
|---|---|---|
| 1 | SmartOmni Official Flagship (`7494000000000000003`) | Phạm Gia Huy |
| 3 | Saigon Modern Living (`7494000000000000005`) | Bùi Ngọc Anh |

Dòng của tenant 4 đã `disconnected` và không có `shop_id`, nên không gây trùng.

**Tuỳ chọn: xoá checkpoint polling.** Mốc cũ được giữ khi reconnect, nên polling bỏ qua các đơn trên Render có `update_time` cũ hơn mốc đó. Nếu cần lấy cả những đơn này, xoá mốc của hai kết nối (chỉ trên DB dev local):

```powershell
docker exec smartomni-postgres psql -U postgres -d smartomni -c "DELETE FROM tiktok_sync_checkpoints WHERE connection_id IN (1, 2);"
```

### Bước 6: Reconnect shop

1. Mở `https://smartomni-tiktok-simulator-api.onrender.com/health/ready` trước. Render free cần khoảng 1 phút để thức dậy, trong khi SmartOmni chỉ chờ 10 giây.
2. Chạy FE (`npm run dev`), mở đúng `http://localhost:5174` hoặc `http://127.0.0.1:5174`. Không dùng `lvh.me`.
3. Đăng nhập Tenant Owner → **Reconnect TikTok Shop** → đăng nhập đúng seller theo bảng ở bước 5 (tài khoản trong README của simulator) → **Authorize**. Chọn seller khác sẽ bị `TIKTOK_SHOP_MISMATCH`.
4. Làm lại cho tenant còn lại. Nếu trang Vercel còn giữ phiên seller trước, đăng xuất rồi đăng nhập seller kia.
5. Shop hiện `CONNECTED`. SmartOmni tự đăng ký 6 topic webhook và tự chạy auto-import: `ORDER_STATUS_CHANGE`, `PACKAGE_UPDATE`, `RETURN_STATUS_CHANGE`, `CANCELLATION_STATUS_CHANGE`, `SELLER_DEAUTHORIZATION`, `INVENTORY_CHANGED_BY_SHOP`.
6. Nếu đăng ký lỗi, scheduler thử lại mỗi 15 phút. Xem log:

   ```powershell
   docker compose logs --since 10m integration-service
   ```

## 3. Nghiệm thu

| # | Việc làm | Đạt khi |
|---|---|---|
| 1 | Reconnect ở bước 6 | Shop `CONNECTED`; log không có lỗi đổi token |
| 2 | Mở `/console` → HTTP log | Có request từ SmartOmni, trả thành công |
| 3 | Ngay sau khi kết nối shop, xem `/console` → HTTP log | Có `GET` và 6 lần `PUT /event/202309/webhooks` thành công; log integration-service không có `webhook subscription pending` hay `retry failed`. Trang Webhooks chỉ liệt kê job, không liệt kê subscription |
| 4 | Import sản phẩm, đồng bộ đơn | Dữ liệu khớp shop trên Vercel |
| 5 | Tạo và thanh toán đơn mới ở UI buyer | Job webhook `SUCCEEDED`; đơn xuất hiện trong SmartOmni |
| 6 | Kiểm tra event log (lệnh dưới) | `signature_valid = t`, `processed_at` có giá trị |
| 7 | Sau auto-import, mở catalog trong SmartOmni FE và bấm link TikTok của một SKU | Link dạng `https://smartomni-tiktok-simulator-ten.vercel.app/product/<id>`, mở được khi đã đăng nhập buyer trên Vercel (plan 1 phase 2) |
| 8 | Lệnh kiểm tra link (dưới) | Không còn dòng nào trỏ tới `tiktok-simulator.onrender.com` |

```powershell
docker exec smartomni-postgres psql -U postgres -d smartomni -c "SELECT tenant_id, event_type, signature_valid, processed_at, received_at FROM webhook_events_log WHERE platform = 'tiktok_shop' ORDER BY received_at DESC LIMIT 10;"
```

```powershell
docker exec smartomni-postgres psql -U postgres -d smartomni -c "SELECT count(*) FILTER (WHERE platform_product_url LIKE 'https://tiktok-simulator.onrender.com%') AS old_domain, count(*) FILTER (WHERE platform_product_url IS NULL) AS no_url, count(*) AS total FROM product_marketplace_links WHERE platform = 'tiktok_shop';"
```

Nếu `old_domain` > 0, chạy lệnh `UPDATE` ở plan 1 phase 2 (chỉ trên DB dev local).

### Smoke test (tuỳ chọn)

```powershell
.\scripts\e2e-tiktok-smoke.ps1 -SimulatorBaseUrl https://smartomni-tiktok-simulator-api.onrender.com -TestAdminToken <token-tren-Render>
```

- Script tạo tenant riêng và kết nối shop đầu tiên của seller demo đầu tiên. Trên Render hiện là Saigon Modern Living, shop tenant 3 đang giữ, nên script sẽ lỗi `TIKTOK_SHOP_ALREADY_LINKED`. Vì bạn không disconnect, **bỏ qua smoke test** và nghiệm thu theo bảng trên.
- Script cũng tạo đơn trên simulator Render và sửa dữ liệu trong DB local.

## 4. Mỗi phiên test sau

1. Đánh thức Render (mở `/health/ready`) và chờ trả 200. Làm trước tiên: nếu lần đăng ký đầu (khoảng 60 giây sau khi container chạy) thất bại, phải chờ 15 phút mới thử lại.
2. Mở tunnel (bước 1) và lấy URL mới.
3. Sửa `TIKTOK_WEBHOOK_URL` trong `.env`.
4. Nạp env mới và restart gateway sau khi service sẵn sàng:

   ```powershell
   docker compose up -d --force-recreate integration-service
   ```

   ```powershell
   docker restart smartomni-gateway
   ```

5. Scheduler tự ghi đè địa chỉ subscription, không cần kết nối lại shop.

Muốn URL cố định: dùng Cloudflare named tunnel (cần tài khoản và domain) hoặc ngrok static domain.

## 5. Lỗi thường gặp

| Hiện tượng | Kiểm tra |
|---|---|
| `smartomni-gateway` `Restarting`, log có `host not found in upstream "<service>"` | Service đó chưa chạy. Xem `docker compose ps -a` và log của nó, sửa xong rồi `docker restart smartomni-gateway` |
| Webhook trả 404 `Unknown shop_id` | Shop chưa kết nối |
| Webhook ghi vào sai tenant | Đang chạy code chưa có plan 1 phase 1. Pull `develop`, rồi `docker compose up -d --build integration-service` |
| Link sản phẩm TikTok trống | Chưa đặt `TIKTOK_PRODUCT_URL_TEMPLATE`, hoặc container chưa được tạo lại sau khi sửa `.env` |
| `TIKTOK_UNAVAILABLE` ở lần gọi đầu | Render đang ngủ. Mở `/health/ready` rồi thử lại |
| Simulator báo app hoặc chữ ký không hợp lệ | `TIKTOK_APP_SECRET` khác `SEED_APP_SECRET` |
| Job webhook `DEAD` | Tunnel hoặc service chưa sẵn sàng. Simulator chỉ thử 5 lần với backoff ngắn. Sửa xong rồi replay trong `/console` |
| Job cũ vẫn gửi tới URL tunnel trước | Job giữ địa chỉ lúc được tạo. Tạo sự kiện mới |
| Webhook 401 `Invalid signature…` | Sai app secret, hoặc đồng hồ máy lệch quá 5 phút |
| Log có 401 khi test dispute | Simulator gửi thêm một bản trực tiếp không chữ ký. Chỉ job trong hàng đợi có chữ ký |
| `TIKTOK_SHOP_MISMATCH` | Chọn sai seller khi Reconnect. Làm lại với đúng seller ở bước 5 |

## 6. Giới hạn của bản deploy

- Render free ngủ sau 15 phút không có request; worker webhook dừng trong lúc đó.
- Ảnh sinh ra và PDF vận đơn trên Render mất sau khi restart, deploy hoặc ngủ.
- Ai mở được trang Vercel cũng gọi được `/test-admin/*` (reset, clock, faults), và tài khoản demo dùng mật khẩu chung. Cân nhắc bật Vercel Deployment Protection.
- Mỗi bộ (shop, app, topic) chỉ giữ một địa chỉ webhook. Hai người cùng dùng một shop sẽ ghi đè URL của nhau, nên chia mỗi người một shop.

## 7. Quay lại simulator local

```powershell
Copy-Item .local-backups\env.local-sim.bak .env
```

File sao lưu đang để hai base URL là `http://host.docker.internal:8090`, giá trị này không dùng được (plan 1 phase 3). Sau khi khôi phục, đổi hai biến về `http://api:8090` như `.env.example`, và chạy kèm `docker-compose.tiktok-simulator.yml`.

Chạy lại theo `docs/tiktok-simulator-local.md`, bấm **Reconnect** cho từng shop (không cần disconnect), rồi tắt tunnel.

## 8. Việc đã chuyển sang plan 1

Các việc sửa code và tài liệu trước đây ghi ở đây đã chuyển sang [plan 1](tiktok-simulator-followups-plan.md):

| Việc | Plan 1 |
|---|---|
| Webhook có thể rơi vào dòng connection đã disconnect | Phase 1 |
| Link sản phẩm ghi cứng domain cũ, `TIKTOK_PRODUCT_URL_TEMPLATE` chưa vào container | Phase 2 |
| Mâu thuẫn `host.docker.internal` và mục "Dùng simulator đã deploy" trong `docs/tiktok-simulator-local.md` | Phase 3 |

Ngoài phạm vi cả hai plan: PR phát hành `develop` → `main`.
