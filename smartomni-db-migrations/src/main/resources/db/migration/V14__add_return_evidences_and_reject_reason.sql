-- Flyway migration V14: Add return reason, reject reason, evidence URLs, and restock status to orders
ALTER TABLE orders
  ADD COLUMN return_reason  varchar(255),
  ADD COLUMN reject_reason  varchar(500),
  ADD COLUMN evidence_urls  text,
  ADD COLUMN restock_status varchar(50);
