# ── ALB — internet-facing ─────────────────────────────────────────────────────

resource "aws_security_group" "alb" {
  name        = "banking-alb-sg-${var.environment}"
  description = "ALB: allow HTTP/HTTPS from internet"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "banking-alb-sg" }
}

# ── ECS Tasks ─────────────────────────────────────────────────────────────────

resource "aws_security_group" "ecs" {
  name        = "banking-ecs-sg-${var.environment}"
  description = "ECS Fargate tasks: inbound from ALB on app ports, internal VPC traffic"
  vpc_id      = aws_vpc.main.id

  # API Gateway port — only from ALB
  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # All service ports — internal VPC (Feign + Eureka)
  ingress {
    from_port   = 8080
    to_port     = 8091
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  # Eureka
  ingress {
    from_port   = 8761
    to_port     = 8761
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "banking-ecs-sg" }
}

# ── RDS ───────────────────────────────────────────────────────────────────────

resource "aws_security_group" "rds" {
  name        = "banking-rds-sg-${var.environment}"
  description = "RDS PostgreSQL: inbound from ECS only"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "banking-rds-sg" }
}

# ── ElastiCache ───────────────────────────────────────────────────────────────

resource "aws_security_group" "redis" {
  name        = "banking-redis-sg-${var.environment}"
  description = "ElastiCache Redis: inbound from ECS only"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "banking-redis-sg" }
}

# ── MSK ───────────────────────────────────────────────────────────────────────

resource "aws_security_group" "msk" {
  name        = "banking-msk-sg-${var.environment}"
  description = "MSK Kafka: inbound from ECS only"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  ingress {
    from_port       = 9094
    to_port         = 9094
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "banking-msk-sg" }
}

# ── VPC Endpoints ─────────────────────────────────────────────────────────────

resource "aws_security_group" "vpc_endpoints" {
  name        = "banking-vpc-endpoints-sg-${var.environment}"
  description = "VPC interface endpoints: allow HTTPS from VPC"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "banking-vpc-endpoints-sg" }
}
