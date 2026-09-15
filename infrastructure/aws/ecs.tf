# ── CloudWatch Log Groups ─────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "services" {
  for_each          = toset(local.service_names)
  name              = "/banking-platform/${var.environment}/${each.key}"
  retention_in_days = 14
}

# ── Cloud Map — internal service discovery for Eureka ─────────────────────────

resource "aws_service_discovery_private_dns_namespace" "banking" {
  name        = "banking.local"
  vpc         = aws_vpc.main.id
  description = "Internal DNS namespace for banking platform services"
}

resource "aws_service_discovery_service" "eureka" {
  name = "eureka"

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.banking.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

# ── ECS Cluster ───────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "main" {
  name = "banking-platform-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = { Name = "banking-cluster-${var.environment}" }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 1
  }
}

# ── Locals: service configuration map ────────────────────────────────────────

locals {
  # RDS endpoint without port suffix
  rds_host = aws_db_instance.main.address
  redis_host = aws_elasticache_cluster.main.cache_nodes[0].address
  msk_brokers = aws_msk_cluster.main.bootstrap_brokers
  eureka_url = "http://eureka.banking.local:8761/eureka/"

  # Common environment variables injected into every service
  common_env = [
    { name = "EUREKA_CLIENT_SERVICEURL_DEFAULTZONE", value = local.eureka_url },
    { name = "EUREKA_INSTANCE_PREFER_IP_ADDRESS",    value = "true" },
  ]

  # Per-service configuration
  service_configs = {
    eureka-server = {
      port        = 8761
      db          = false
      kafka       = false
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = true
      extra_env   = []
    }
    api-gateway = {
      port        = 8080
      db          = false
      kafka       = false
      redis       = true
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    user-service = {
      port        = 8081
      db          = true
      db_name     = "user_db"
      kafka       = false
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    application-service = {
      port        = 8082
      db          = true
      db_name     = "application_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    account-service = {
      port        = 8083
      db          = true
      db_name     = "account_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    transaction-service = {
      port        = 8084
      db          = true
      db_name     = "transaction_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    payment-service = {
      port        = 8085
      db          = true
      db_name     = "payment_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    statistics-service = {
      port        = 8086
      db          = true
      db_name     = "statistics_db"
      kafka       = true
      redis       = true
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    notification-service = {
      port        = 8087
      db          = true
      db_name     = "notification_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    fraud-detection-service = {
      port        = 8088
      db          = true
      db_name     = "fraud_db"
      kafka       = true
      redis       = true
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    credit-card-service = {
      port        = 8089
      db          = true
      db_name     = "credit_card_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    loan-service = {
      port        = 8090
      db          = true
      db_name     = "loan_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
    integration-service = {
      port        = 8091
      db          = true
      db_name     = "integration_db"
      kafka       = true
      redis       = false
      cpu         = 512
      memory      = 1024
      cloud_map   = false
      extra_env   = []
    }
  }
}

# ── Task Definitions ──────────────────────────────────────────────────────────

resource "aws_ecs_task_definition" "services" {
  for_each = local.service_configs

  family                   = "banking-${each.key}-${var.environment}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name  = each.key
      image = "${var.ecr_registry}/banking-platform/${each.key}:${var.image_tag}"

      portMappings = [{
        containerPort = each.value.port
        protocol      = "tcp"
      }]

      environment = concat(
        local.common_env,
        each.value.db ? [
          { name = "SPRING_DATASOURCE_URL",      value = "jdbc:postgresql://${local.rds_host}:5432/${each.value.db_name}" },
          { name = "SPRING_DATASOURCE_USERNAME",  value = var.db_username },
          { name = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", value = "5" },
          { name = "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE",      value = "2" },
        ] : [],
        each.value.kafka ? [
          { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = local.msk_brokers },
        ] : [],
        each.value.redis ? [
          { name = "SPRING_DATA_REDIS_HOST", value = local.redis_host },
          { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        ] : [],
        each.value.extra_env
      )

      secrets = concat(
        each.value.db ? [
          { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = aws_secretsmanager_secret.db_password.arn },
        ] : [],
        [
          { name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt_secret.arn },
        ]
      )

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = "/banking-platform/${var.environment}/${each.key}"
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }

      essential   = true
      healthCheck = {
        command     = ["CMD-SHELL", "wget -qO- http://localhost:${each.value.port}/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = { Name = "banking-${each.key}-td" }
}

# ── ECS Services ──────────────────────────────────────────────────────────────

resource "aws_ecs_service" "eureka" {
  name            = "banking-eureka-server-${var.environment}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.services["eureka-server"].arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.eureka.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  tags = { Name = "banking-eureka-server" }
}

resource "aws_ecs_service" "api_gateway" {
  name            = "banking-api-gateway-${var.environment}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.services["api-gateway"].arn
  desired_count   = var.ecs_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api_gateway.arn
    container_name   = "api-gateway"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_ecs_service.eureka, aws_lb_listener.https]

  tags = { Name = "banking-api-gateway" }
}

# All remaining microservices — same pattern, no ALB attachment
resource "aws_ecs_service" "microservices" {
  for_each = {
    for k, v in local.service_configs : k => v
    if k != "eureka-server" && k != "api-gateway"
  }

  name            = "banking-${each.key}-${var.environment}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.services[each.key].arn
  desired_count   = var.ecs_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_ecs_service.eureka]

  tags = { Name = "banking-${each.key}" }
}
