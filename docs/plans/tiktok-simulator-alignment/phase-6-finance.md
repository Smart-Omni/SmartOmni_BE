# Phase 6. Finance dựng từ dữ liệu đơn

[← Tổng quan](README.md)

- **Branch:** `feat/integration-tiktok-finance-from-orders`
- **Phụ thuộc:** [phase 3](phase-3-catalog-errors.md) (dùng `TiktokApiExceptionHandler`)
- **Migration:** không
- **Bao phủ:** B5 (finance gọi route không tồn tại), theo lựa chọn "cả hai dựng từ đơn"

## Vì sao

Simulator không có API finance nào: không có `/finance/202309/statements`, không có `statement_transactions`, và cả support matrix lẫn manifest đều không liệt kê. Dữ liệu tiền chỉ có ở hai chỗ:
- `GET /order/202407/orders/{order_id}/price_detail`: giá niêm yết, giảm giá của seller/platform, phí ship, subtotal, `payment`, `total`, tính cho cả đơn và từng dòng; dùng scope `sim.order.read`;
- khối `payment` trong Get Order Detail.

## Việc cần làm

### 6.1 Escrow
- `TiktokFinanceClient.getEscrowDetail` (L62) chuyển sang `GET /order/202407/orders/{id}/price_detail`.
- Ánh xạ vào `TiktokEscrowDetail` các trường: `sku_list_price`, `subtotal_deduction_seller` / `subtotal_deduction_platform`, `shipping_*`, `payment`, `total`, `line_items`.
- Thêm trường `source = "ORDER_PRICE_DETAIL"`.

### 6.2 Settlements ước tính
- Thay `getSettlements` (L124) bằng service mới `TiktokFinanceEstimateService`:
  - gọi `searchOrders` với `order_status=COMPLETED`, `update_time` trong khoảng `[fromDate, toDate)`, đi hết các trang;
  - cộng các trường `payment.sub_total`, `seller_discount`, `platform_discount`, `shipping_fee`, `tax`, `total_amount`.
- Trả `TiktokSettlementListResult` kèm `estimated=true` và `source="ESTIMATED_FROM_ORDERS"`.
- Ghi rõ trong Swagger và docs rằng đây không phải sao kê của sàn.

## Test

- `TiktokFinanceControllerTest`: escrow và settlements trả đúng dạng; có `source` và `estimated`.
- Test mới `TiktokFinanceEstimateServiceTest`:
  - dùng fixture `price_detail` và 2 trang đơn;
  - tổng tính đúng;
  - đơn không phải `COMPLETED` không được cộng.
- Lệnh: `mvn -pl smartomni-service-integration -am test`.

## E2E với simulator

1. Đưa một đơn tới `COMPLETED`. Kết quả `GET /tiktok/finance/escrow/{orderId}` phải khớp `price_detail` của simulator.
2. Kết quả `GET /tiktok/finance/settlements?fromDate=&toDate=` phải bằng tổng các đơn `COMPLETED` trong khoảng đó, kèm `estimated=true`.
3. Đơn không tồn tại trả mã lỗi rõ ràng, không trả 500.

## Commit

- `feat(integration): build TikTok escrow detail from order price detail`
- `feat(integration): estimate TikTok settlements from completed orders`

## Xong khi

- Test pass.
- Đủ 3 bước E2E.
- Đã mở PR.
