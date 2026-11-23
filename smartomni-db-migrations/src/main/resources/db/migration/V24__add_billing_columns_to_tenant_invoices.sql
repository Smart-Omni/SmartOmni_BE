-- V24: Add billing & payment metadata columns to tenant_invoices for SePay checkout integration

ALTER TABLE tenant_invoices
  ADD COLUMN IF NOT EXISTS payment_code varchar(50),
  ADD COLUMN IF NOT EXISTS gateway_transaction_id varchar(100),
  ADD COLUMN IF NOT EXISTS paid_at timestamptz;

COMMENT ON COLUMN tenant_invoices.payment_code IS 'Ma chuyen khoan thanh toan duy nhat (vd: DH10001)';
COMMENT ON COLUMN tenant_invoices.gateway_transaction_id IS 'Ma giao dich tra ve tu cong thanh toan SePay';
COMMENT ON COLUMN tenant_invoices.paid_at IS 'Thoi diem ghi nhan thanh toan thanh cong';
