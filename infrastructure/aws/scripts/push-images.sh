#!/usr/bin/env bash
# Build and push all service images to ECR
# Usage: bash push-images.sh <aws-region> <ecr-registry> <tag>
# Example: bash push-images.sh us-east-1 123456789012.dkr.ecr.us-east-1.amazonaws.com latest

set -euo pipefail

REGION="${1:-us-east-1}"
ECR_REGISTRY="${2:?ECR registry URL required}"
TAG="${3:-latest}"

SERVICES=(
  eureka-server
  api-gateway
  user-service
  application-service
  account-service
  transaction-service
  payment-service
  statistics-service
  notification-service
  fraud-detection-service
  credit-card-service
  loan-service
  integration-service
)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

echo "==> Authenticating with ECR..."
aws ecr get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}"

echo "==> Building and pushing ${#SERVICES[@]} service images..."

for SERVICE in "${SERVICES[@]}"; do
  REPO="${ECR_REGISTRY}/banking-platform/${SERVICE}"
  SERVICE_DIR="${ROOT_DIR}/${SERVICE}"

  echo ""
  echo "--- ${SERVICE} ---"

  if [[ ! -d "${SERVICE_DIR}" ]]; then
    echo "  [SKIP] Directory not found: ${SERVICE_DIR}"
    continue
  fi

  # Build using the root Dockerfile (COPY target/*.jar app.jar)
  docker build \
    -t "${REPO}:${TAG}" \
    -f "${ROOT_DIR}/Dockerfile" \
    "${SERVICE_DIR}"

  docker push "${REPO}:${TAG}"
  echo "  [OK] Pushed ${REPO}:${TAG}"
done

echo ""
echo "==> All images pushed. Update ECS services to deploy:"
echo "    aws ecs update-service --cluster banking-platform-prod --service <name> --force-new-deployment"
