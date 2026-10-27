-- Flyway migration V13: Add image_url to product_marketplace_links and order_items
ALTER TABLE product_marketplace_links
  ADD COLUMN image_url varchar(1024);

ALTER TABLE order_items
  ADD COLUMN image_url varchar(1024);
