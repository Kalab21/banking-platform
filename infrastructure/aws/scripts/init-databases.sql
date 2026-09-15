-- Run against RDS master (postgres db) after first terraform apply
-- PGPASSWORD=<password> psql -h <rds_endpoint> -U bankingadmin -d postgres -f init-databases.sql

CREATE DATABASE user_db;
CREATE DATABASE application_db;
CREATE DATABASE account_db;
CREATE DATABASE transaction_db;
CREATE DATABASE payment_db;
CREATE DATABASE statistics_db;
CREATE DATABASE notification_db;
CREATE DATABASE fraud_db;
CREATE DATABASE credit_card_db;
CREATE DATABASE loan_db;
CREATE DATABASE integration_db;

GRANT ALL PRIVILEGES ON DATABASE user_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE application_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE account_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE transaction_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE payment_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE statistics_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE notification_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE fraud_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE credit_card_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE loan_db TO bankingadmin;
GRANT ALL PRIVILEGES ON DATABASE integration_db TO bankingadmin;
