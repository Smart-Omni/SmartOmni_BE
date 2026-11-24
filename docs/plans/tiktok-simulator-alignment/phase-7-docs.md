# Phase 7. Tài liệu và dọn dẹp

[← Tổng quan](README.md)

- **Branch:** có hai cách:
  - commit tài liệu ngay trong PR của từng phase;
  - hoặc gom thành một PR `docs/integration-tiktok-simulator-alignment` sau khi các phase 1–6 đã merge.
- **Phụ thuộc:** phase 1–6
- **Bao phủ:** dọn `ON_HOLD`, cập nhật tài liệu, đóng báo cáo.

## Việc cần làm

1. **`docs/tiktok-simulator-local.md`**:
   - thêm `TIKTOK_WEBHOOK_URL=http://integration-service:8086/api/v1/integrations/tiktok/webhooks`;
   - ghi chú khởi động lại gateway sau khi tạo lại container;
   - ghi chú simulator không còn `ON_HOLD`;
   - ghi chú cần reset DB local sau khi đổi tên migration `V24` thành `V25`.
2. **`docs/architecture.md`**: mô tả luồng webhook mới (idempotency, lấy chi tiết đơn, không hồi trạng thái), checkpoint polling, và quyết định về tồn kho TikTok ở phase 2.6.
3. **Báo cáo** `docs/reports/tiktok-simulator-integration-api-test.md`: đánh dấu từng mục đã sửa, kèm link PR và kết quả E2E.
4. **`ON_HOLD`**: giữ nguyên nhánh `ON_HOLD` trong `TiktokOrderStatusMapper`, `OrderService` và `ReturnRefundService` vì sàn thật vẫn có trạng thái này. Thêm comment ghi rõ simulator không còn gửi `ON_HOLD`. Không phụ thuộc vào `release_date`.
5. **`.env.example`**: kiểm tra đã có `TIKTOK_WEBHOOK_URL` (thêm ở phase 1).

## Commit

- `docs(integration): document TikTok webhook flow and simulator setup`
- `docs(integration): close resolved TikTok simulator findings`

## Xong khi

Tài liệu khớp code đã merge, và mọi mục P0/P1 trong báo cáo đều đã có trạng thái và link PR.
