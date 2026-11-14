-- Run once against a dedicated benchmark database, before training and measurements.
-- The application does not create or drop schema on startup.
CREATE SEQUENCE IF NOT EXISTS customer_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE IF NOT EXISTS customer (
    id bigint PRIMARY KEY,
    name varchar(255),
    email varchar(255)
);
