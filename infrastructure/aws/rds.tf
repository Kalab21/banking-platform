resource "random_password" "db" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_secretsmanager_secret" "db_password" {
  name                    = "banking-platform/${var.environment}/db-password"
  recovery_window_in_days = 7
  tags                    = { Name = "banking-db-password" }
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db.result
}

# ── Subnet Group ──────────────────────────────────────────────────────────────

resource "aws_db_subnet_group" "main" {
  name       = "banking-db-subnet-group-${var.environment}"
  subnet_ids = aws_subnet.private[*].id
  tags       = { Name = "banking-db-subnet-group" }
}

# ── Parameter Group ───────────────────────────────────────────────────────────

resource "aws_db_parameter_group" "postgres16" {
  name   = "banking-postgres16-${var.environment}"
  family = "postgres16"

  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements"
  }

  tags = { Name = "banking-pg16-params" }
}

# ── RDS Instance (Multi-AZ PostgreSQL 16) ────────────────────────────────────

resource "aws_db_instance" "main" {
  identifier = "banking-platform-${var.environment}"

  engine         = "postgres"
  engine_version = "16.3"
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "postgres"
  username = var.db_username
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.postgres16.name

  multi_az                  = var.db_multi_az
  publicly_accessible       = false
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "banking-platform-${var.environment}-final"

  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "Mon:04:00-Mon:05:00"

  performance_insights_enabled          = true
  performance_insights_retention_period = 7

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  tags = { Name = "banking-rds-${var.environment}" }
}

# ── Database Init ─────────────────────────────────────────────────────────────
# Run once after RDS is up — creates all 13 service databases.
# Execute from a bastion or CI/CD agent within the VPC:
#
#   PGPASSWORD=<password> psql -h <rds_endpoint> -U bankingadmin -d postgres \
#     -f infrastructure/aws/scripts/init-databases.sql

resource "null_resource" "db_init_reminder" {
  triggers = {
    rds_id = aws_db_instance.main.id
  }

  provisioner "local-exec" {
    command = "echo 'RDS ready at ${aws_db_instance.main.address}. Run scripts/init-databases.sql to create service databases.'"
  }
}
