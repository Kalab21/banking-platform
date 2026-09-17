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

  # The only thing this load balancer talks to is the API gateway task, on
  # the target group's port, and it health-checks the same port. That is the
  # whole of its outbound need, so unrestricted egress bought nothing.
  #
  # Written as the VPC CIDR rather than a reference to the ECS security group
  # because that group's ingress already references this one; pointing back at
  # it would be a Terraform dependency cycle. Both endpoints are inside the
  # VPC either way, and nothing else in the VPC listens on 8080 to a caller
  # that is not already allowed through the ECS group's own rules.
  egress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
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

  # Deliberately left unrestricted, and this is the honest reason rather than
  # an oversight.
  #
  # Everything the application itself needs is already inside the VPC — RDS,
  # ElastiCache, MSK, the other tasks — and the AWS APIs it uses at runtime
  # reach it through the interface endpoints in vpc.tf. What is *not* modelled
  # here is the Fargate agent's own path to the ECS control plane. This VPC has
  # no com.amazonaws.<region>.ecs, .ecs-agent or .ecs-telemetry endpoint, so
  # that traffic goes out through the NAT gateway. Narrowing this rule without
  # first adding those endpoints would stop tasks starting, and adding them is
  # an infrastructure change rather than a rule change.
  #
  # Trivy reports this as AVD-AWS-0104. It is a real finding and it is left
  # open on purpose: a configuration that scans clean and cannot launch a task
  # would be worse than one that is accurate about what it still allows.
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

  # No egress rules at all. A managed PostgreSQL instance answers connections;
  # it does not open them. Multi-AZ replication, automated backups and
  # Performance Insights are carried by the RDS service itself and do not
  # traverse this security group.
  #
  # Terraform removes the default allow-all egress when no egress block is
  # declared, which is the intent here rather than an omission.

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

  # No egress rules. This is a single-node cluster
  # (var.redis_num_cache_nodes defaults to 1), so there is no replica for the
  # node to talk to, and snapshots are written by the ElastiCache service
  # rather than by the node across this group.

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

  # Left unrestricted, deliberately.
  #
  # The cluster runs two brokers with a replication factor of two and
  # in_cluster encryption, so the brokers replicate to each other across this
  # group. The ports that traffic uses are internal to MSK and vary by Kafka
  # version, which is why AWS's own guidance is to allow traffic within the
  # cluster's security group rather than to enumerate them. Doing that properly
  # means a self-referencing rule on ingress as well — this group currently
  # admits only the ECS group — and that is a design change to the cluster's
  # networking, not a tightening of one rule.
  #
  # Trivy reports this as AVD-AWS-0104. Left open and reported rather than
  # replaced with a guess at the broker port range.
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

  # No egress rules. An interface endpoint's network interface is the target
  # of a connection, never the originator, so outbound rules on it protect
  # nothing and permit everything.

  tags = { Name = "banking-vpc-endpoints-sg" }
}
