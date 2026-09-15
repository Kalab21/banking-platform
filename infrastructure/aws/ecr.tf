locals {
  service_names = [
    "eureka-server",
    "api-gateway",
    "user-service",
    "application-service",
    "account-service",
    "transaction-service",
    "payment-service",
    "statistics-service",
    "notification-service",
    "fraud-detection-service",
    "credit-card-service",
    "loan-service",
    "integration-service",
  ]
}

resource "aws_ecr_repository" "services" {
  for_each = toset(local.service_names)

  name                 = "banking-platform/${each.key}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = { Name = "banking-platform/${each.key}" }
}

# Lifecycle policy — keep last 10 images, expire untagged after 1 day
resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = aws_ecr_repository.services
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep last 10 tagged images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["v", "latest"]
          countType     = "imageCountMoreThan"
          countNumber   = 10
        }
        action = { type = "expire" }
      }
    ]
  })
}
