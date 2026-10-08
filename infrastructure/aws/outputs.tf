output "api_url" {
  description = "Public API endpoint"
  value       = "https://${var.api_subdomain}.${var.domain_name}"
}

output "cloudfront_domain" {
  description = "CloudFront distribution domain (before DNS propagates)"
  value       = aws_cloudfront_distribution.main.domain_name
}

output "origin_hostname" {
  description = "CloudFront's origin hostname (aliases the ALB). Direct access is refused: the ALB admits only CloudFront's origin-facing servers carrying the origin header."
  value       = local.origin_hostname
}

output "alb_dns" {
  description = "ALB origin DNS name. The ALB is internet-facing for CloudFront only; direct public access is refused and normal traffic goes through CloudFront."
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

# A fresh environment needs application prerequisites before the ECS services
# can reach steady state. `terraform apply` creates the services, but their
# tasks pull images Terraform does not build and connect to databases Terraform
# does not create. Until both exist, first deployments fail, and the deployment
# circuit breaker stops them. Once the prerequisites are in place, a new
# deployment brings the services up.
output "next_steps" {
  description = "First-deployment order for a fresh environment"
  value       = <<-EOT
    `terraform apply` has created the infrastructure, ECR repositories and RDS
    deployment. The ECS services cannot reach steady state until steps 1 and 2
    are done; their first deployments are expected to fail until then.

    1. Build and push the 13 service images to ECR:
       bash infrastructure/aws/scripts/push-images.sh ${var.aws_region} ${var.ecr_registry} ${var.image_tag}

    2. Create the 11 service-owned logical databases on the RDS deployment
       (from a host with network access to the private RDS endpoint):
       PGPASSWORD=$(aws secretsmanager get-secret-value --secret-id ${aws_secretsmanager_secret.db_password.name} --query SecretString --output text)          psql -h ${aws_db_instance.main.address} -U ${var.db_username} -d postgres          -f infrastructure/aws/scripts/init-databases.sql

    3. Start a new deployment of every service (Eureka first, then the gateway
       and the business services) and wait for steady state:
       for s in $(aws ecs list-services --cluster ${aws_ecs_cluster.main.name} --query 'serviceArns[]' --output text); do
         aws ecs update-service --cluster ${aws_ecs_cluster.main.name} --service "$s" --force-new-deployment > /dev/null
       done
       aws ecs wait services-stable --cluster ${aws_ecs_cluster.main.name} --services <service names>

    4. Test the API through CloudFront:
       curl https://${var.api_subdomain}.${var.domain_name}/actuator/health
  EOT
}
