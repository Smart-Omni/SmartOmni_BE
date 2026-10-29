-- Flyway migration V16: Add phone and avatar to users
ALTER TABLE users
  ADD COLUMN phone  varchar(50),
  ADD COLUMN avatar varchar(500);
