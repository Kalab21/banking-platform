variable "aws_region" {
  description = "Primary AWS region"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment (prod | staging)"
  type        = string
  default     = "prod"
}

variable "domain_name" {
  description = "Root domain — Route 53 hosted zone must already exist"
  type        = string
  default     = "yourbank.com"
}

variable "api_subdomain" {
  description = "Subdomain for the public API endpoint"
  type        = string
  default     = "api"
}

# ── Networking ────────────────────────────────────────────────────────────────

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDRs for public subnets (ALB lives here)"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "private_subnet_cidrs" {
  description = "CIDRs for private subnets (ECS, RDS, MSK, ElastiCache)"
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.11.0/24", "10.0.12.0/24"]
}

variable "availability_zones" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

# ── Database ──────────────────────────────────────────────────────────────────

variable "db_username" {
  description = "PostgreSQL master username"
  type        = string
  default     = "bankingadmin"
}

variable "db_instance_class" {
  type    = string
  default = "db.t3.medium"
}

variable "db_allocated_storage" {
  description = "Initial storage in GB"
  type        = number
  default     = 100
}

variable "db_max_allocated_storage" {
  description = "Max storage for autoscaling in GB"
  type        = number
  default     = 500
}

variable "db_multi_az" {
  description = "Enable Multi-AZ for RDS (standby in second AZ)"
  type        = bool
  default     = true
}

# ── ElastiCache ───────────────────────────────────────────────────────────────

variable "redis_node_type" {
  type    = string
  default = "cache.t3.small"
}

variable "redis_num_cache_nodes" {
  type    = number
  default = 1
}

# ── MSK (Kafka) ───────────────────────────────────────────────────────────────

variable "msk_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "msk_kafka_version" {
  type    = string
  default = "3.6.0"
}

variable "msk_broker_count" {
  description = "Must be a multiple of the number of AZs used"
  type        = number
  default     = 2
}

variable "msk_broker_volume_gb" {
  type    = number
  default = 100
}

# ── ECS ───────────────────────────────────────────────────────────────────────

variable "ecr_registry" {
  description = "ECR registry URL — e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com"
  type        = string
}

variable "image_tag" {
  description = "Docker image tag to deploy across all services"
  type        = string
  default     = "latest"
}

variable "ecs_task_cpu" {
  description = "Default Fargate task CPU units (1 vCPU = 1024)"
  type        = number
  default     = 512
}

variable "ecs_task_memory" {
  description = "Default Fargate task memory in MB"
  type        = number
  default     = 1024
}

variable "ecs_desired_count" {
  description = "Desired running task count per service"
  type        = number
  default     = 1
}

# ── Secrets (sensitive) ───────────────────────────────────────────────────────

variable "jwt_secret" {
  description = "JWT signing secret — override via TF_VAR_jwt_secret env var or tfvars"
  type        = string
  sensitive   = true
  default     = "banking-platform-secret-key-change-in-production"
}
