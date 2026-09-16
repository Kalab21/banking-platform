output "api_url" {
  description = "Public API endpoint"
  value       = "https://${var.api_subdomain}.${var.domain_name}"
}

output "cloudfront_domain" {
  description = "CloudFront distribution domain (before DNS propagates)"
  value       = aws_cloudfront_distribution.main.domain_name
}

output "alb_dns" {
  description = "ALB DNS name (internal — traffic should go through CloudFront)"
  value       = aws_lb.main.dns_name
}

output "rds_endpoint" {
  description = "RDS PostgreSQL endpoint"
  value       = aws_db_instance.main.address
  sensitive   = true
}

output "rds_port" {
  value = aws_db_instance.main.port
}

output "redis_endpoint" {
  description = "ElastiCache Redis endpoint"
  value       = aws_elasticache_cluster.main.cache_nodes[0].address
}

output "msk_bootstrap_brokers_tls" {
  description = "MSK Kafka bootstrap broker string (TLS). This is the endpoint clients use; the cluster no longer serves PLAINTEXT."
  value       = aws_msk_cluster.main.bootstrap_brokers_tls
  sensitive   = true
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecr_repositories" {
  description = "ECR repository URLs for each service"
  value = {
    for k, v in aws_ecr_repository.services : k => v.repository_url
  }
}

output "db_secret_arn" {
  description = "Secrets Manager ARN for DB password"
  value       = aws_secretsmanager_secret.db_password.arn
}

output "vpc_id" {
  value = aws_vpc.main.id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "next_steps" {
  description = "Post-apply checklist"
  value       = <<-EOT
    1. Initialize databases:
       PGPASSWORD=$(aws secretsmanager get-secret-value --secret-id ${aws_secretsmanager_secret.db_password.name} --query SecretString --output text) \
         psql -h ${aws_db_instance.main.address} -U ${var.db_username} -d postgres \
         -f infrastructure/aws/scripts/init-databases.sql

    2. Build and push images:
       bash infrastructure/aws/scripts/push-images.sh ${var.aws_region} ${var.ecr_registry} ${var.image_tag}

    3. Verify all ECS services are RUNNING:
       aws ecs list-tasks --cluster ${aws_ecs_cluster.main.name}

    4. Test the API:
       curl https://${var.api_subdomain}.${var.domain_name}/actuator/health

    5. Run E2E tests against AWS:
       $GW = "https://${var.api_subdomain}.${var.domain_name}"
       # Update e2e-tests.ps1 GW variable and run
  EOT
}
