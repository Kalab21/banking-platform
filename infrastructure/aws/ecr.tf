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

  name = "banking-platform/${each.key}"

  # Trivy reports this as AVD-AWS-0031 and it is a fair finding: an immutable
  # tag is what stops a deployed digest changing under a tag that has already
  # been reviewed. It stays MUTABLE because the deployment path in this
  # repository re-pushes one tag rather than issuing a new one per build:
  # var.image_tag defaults to "latest" (variables.tf), scripts/push-images.sh
  # defaults its TAG argument to the same, and the lifecycle rule below keeps
  # the last ten images matching the "v" and "latest" prefixes.
  #
  # Flipping this without first moving those to a per-build tag would make the
  # second push of an image fail. That is a change to how images are built and
  # released, not to this resource, so the finding is left open and recorded
  # rather than cleared by breaking the deploy.
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
