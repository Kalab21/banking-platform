resource "aws_elasticache_subnet_group" "main" {
  name       = "banking-redis-subnet-group-${var.environment}"
  subnet_ids = aws_subnet.private[*].id
  tags       = { Name = "banking-redis-subnet-group" }
}

resource "aws_elasticache_parameter_group" "redis7" {
  name   = "banking-redis7-${var.environment}"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }
}

resource "aws_elasticache_cluster" "main" {
  cluster_id           = "banking-redis-${var.environment}"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.redis_node_type
  num_cache_nodes      = var.redis_num_cache_nodes
  parameter_group_name = aws_elasticache_parameter_group.redis7.name
  port                 = 6379
  subnet_group_name    = aws_elasticache_subnet_group.main.name
  security_group_ids   = [aws_security_group.redis.id]

  # Snapshot for point-in-time recovery
  snapshot_retention_limit = 3
  snapshot_window          = "02:00-03:00"

  tags = { Name = "banking-redis-${var.environment}" }
}
