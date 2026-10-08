# Architecture invariants for the AWS model, checked without an AWS account.
#
#   cd infrastructure/aws && terraform init -backend=false && terraform test
#
# Needs Terraform 1.11 or later (override_during). CI runs it on 1.11.4.
#
# The providers are mocked, so nothing is created and no credentials are needed.
# `apply` against mocks gives every computed attribute a value, which lets these
# checks read the rendered ECS container definitions and resource arguments.
# They prove the configuration is coherent; they do not prove runtime behaviour
# in a real account.

# Mocked values are random strings unless given here. The AWS provider still
# validates ARN-shaped arguments, so resources whose ARNs feed other resources
# get well-formed placeholders.
mock_provider "aws" {
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/test" }
  }
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/test/1", dns_name = "banking-alb-test.us-east-1.elb.amazonaws.com", zone_id = "Z35SXDOTRQ7X7K" }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/test/1/2" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/test/1" }
  }
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:us-east-1:123456789012:key/test" }
  }
  mock_resource "aws_msk_configuration" {
    defaults = { arn = "arn:aws:kafka:us-east-1:123456789012:configuration/test/1" }
  }
  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:test" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::test-bucket" }
  }
  mock_resource "aws_service_discovery_service" {
    defaults = { arn = "arn:aws:servicediscovery:us-east-1:123456789012:service/srv-test" }
  }
  mock_resource "aws_ecs_task_definition" {
    defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:task-definition/test:1" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:us-east-1:123456789012:log-group:test" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
}

mock_provider "aws" {
  alias = "us_east_1"
  mock_resource "aws_acm_certificate" {
    defaults = { arn = "arn:aws:acm:us-east-1:123456789012:certificate/test" }
  }
  mock_resource "aws_acm_certificate_validation" {
    defaults = { certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/test" }
  }
  mock_resource "aws_wafv2_web_acl" {
    defaults = { arn = "arn:aws:wafv2:us-east-1:123456789012:global/webacl/test/1" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:us-east-1:123456789012:log-group:aws-waf-logs-test" }
  }
}

mock_provider "random" {}

# Distinct, recognisable values for the attributes the checks compare.
override_resource {
  target = aws_msk_cluster.main
  values = {
    bootstrap_brokers     = "b-1.plaintext.example:9092"
    bootstrap_brokers_tls = "b-1.tls.example:9094"
  }
}

override_resource {
  target = aws_elasticache_cluster.main
  values = {
    cache_nodes = [{ address = "redis.example.internal", port = 6379, id = "0001", availability_zone = "us-east-1a", outpost_arn = "" }]
  }
}

# The validation records drive a for_each, so they must be known at plan time.
override_resource {
  target          = aws_acm_certificate.main
  override_during = plan
  values = {
    arn                 = "arn:aws:acm:us-east-1:123456789012:certificate/test"
    id                  = "arn:aws:acm:us-east-1:123456789012:certificate/test"
    type                = "AMAZON_ISSUED"
    key_algorithm       = "RSA_2048"
    status              = "ISSUED"
    renewal_eligibility = "ELIGIBLE"
    not_before          = "2026-01-01T00:00:00Z"
    not_after           = "2027-01-01T00:00:00Z"
    pending_renewal     = false
    renewal_summary     = []
    validation_emails   = []
    domain_validation_options = [
      { domain_name = "api.yourbank.com", resource_record_name = "_a.api.yourbank.com.", resource_record_type = "CNAME", resource_record_value = "_a.acm-validations.aws." },
      { domain_name = "*.yourbank.com", resource_record_name = "_b.yourbank.com.", resource_record_type = "CNAME", resource_record_value = "_b.acm-validations.aws." },
    ]
  }
}

variables {
  ecr_registry = "123456789012.dkr.ecr.us-east-1.amazonaws.com"
  jwt_secret   = "test-only-secret-that-is-long-enough-000"
}

run "architecture_invariants" {
  command = apply

  # ── Database topology ──────────────────────────────────────────────────────

  assert {
    condition     = length(regexall("(?m)^CREATE DATABASE [a-z_]+;", file("scripts/init-databases.sql"))) == 11 && length(aws_ecs_task_definition.services) == 13
    error_message = "13 backend processes (Eureka, the gateway and 11 services) and exactly 11 service-owned logical databases."
  }

  assert {
    condition = toset(flatten(regexall("(?m)^CREATE DATABASE ([a-z_]+);", file("scripts/init-databases.sql")))) == toset(flatten([
      for td in aws_ecs_task_definition.services : [
        for e in jsondecode(td.container_definitions)[0].environment :
        regex("/([a-z_]+)$", e.value)[0] if e.name == "SPRING_DATASOURCE_URL"
      ]
    ]))
    error_message = "The databases the ECS tasks connect to must be exactly the ones init-databases.sql creates."
  }

  assert {
    condition     = aws_db_instance.main.multi_az == true
    error_message = "RDS must be Multi-AZ by default."
  }

  # ── Redis: user-service's sign-in throttle must reach ElastiCache ──────────

  assert {
    condition = contains([
      for e in jsondecode(aws_ecs_task_definition.services["user-service"].container_definitions)[0].environment :
      e.value if e.name == "SPRING_DATA_REDIS_HOST"
    ], "redis.example.internal")
    error_message = "user-service must receive the ElastiCache endpoint; its application.yml falls back to localhost, which is the task itself."
  }

  # ── Kafka: TLS-only brokers need TLS bootstrap brokers and SSL clients ──────

  assert {
    condition     = one(one(aws_msk_cluster.main.encryption_info).encryption_in_transit).client_broker == "TLS"
    error_message = "MSK must stay TLS-only between clients and brokers."
  }

  assert {
    condition = length([
      for td in aws_ecs_task_definition.services : td
      if contains([for e in jsondecode(td.container_definitions)[0].environment : e.name], "SPRING_KAFKA_BOOTSTRAP_SERVERS")
    ]) == 10
    error_message = "The 10 Kafka-enabled business services must all receive bootstrap servers."
  }

  assert {
    condition = alltrue([
      for td in aws_ecs_task_definition.services : alltrue([
        contains([for e in jsondecode(td.container_definitions)[0].environment : "${e.name}=${e.value}"], "SPRING_KAFKA_BOOTSTRAP_SERVERS=b-1.tls.example:9094"),
        contains([for e in jsondecode(td.container_definitions)[0].environment : "${e.name}=${e.value}"], "SPRING_KAFKA_SECURITY_PROTOCOL=SSL"),
      ]) if contains([for e in jsondecode(td.container_definitions)[0].environment : e.name], "SPRING_KAFKA_BOOTSTRAP_SERVERS")
    ])
    error_message = "Every Kafka client must use the TLS bootstrap brokers and spring.kafka.security.protocol=SSL."
  }

  assert {
    condition     = alltrue([for r in aws_security_group.msk.ingress : r.from_port != 9092])
    error_message = "The plaintext client port 9092 must not be opened for a TLS-only cluster."
  }

  # ── Edge: the ALB is reachable only through CloudFront ─────────────────────

  assert {
    condition = alltrue([
      for r in aws_security_group.alb.ingress :
      length(coalesce(r.cidr_blocks, [])) == 0 && length(coalesce(r.ipv6_cidr_blocks, [])) == 0 &&
      contains(r.prefix_list_ids, data.aws_ec2_managed_prefix_list.cloudfront_origin_facing.id)
    ])
    error_message = "ALB ingress must come only from the CloudFront origin-facing prefix list, never from open CIDRs."
  }

  assert {
    condition     = alltrue([for r in aws_security_group.alb.ingress : r.from_port == 443 && r.to_port == 443])
    error_message = "The ALB must admit HTTPS only."
  }

  assert {
    condition     = aws_lb_listener.https.default_action[0].type == "fixed-response" && aws_lb_listener.https.default_action[0].fixed_response[0].status_code == "403"
    error_message = "Requests without the origin header must get a fixed 403."
  }

  assert {
    condition = (
      one(aws_lb_listener_rule.from_cloudfront.condition).http_header[0].http_header_name == "X-Origin-Verify" &&
      one(aws_lb_listener_rule.from_cloudfront.condition).http_header[0].values == toset([random_password.cf_origin_secret.result]) &&
      aws_lb_listener_rule.from_cloudfront.action[0].target_group_arn == aws_lb_target_group.api_gateway.arn
    )
    error_message = "Only requests carrying CloudFront's X-Origin-Verify secret may reach the gateway target group."
  }

  assert {
    condition = anytrue([
      for h in one(aws_cloudfront_distribution.main.origin).custom_header :
      h.name == "X-Origin-Verify" && h.value == random_password.cf_origin_secret.result
    ])
    error_message = "CloudFront must send the X-Origin-Verify secret the ALB checks."
  }

  # ── CloudFront → ALB TLS: a hostname the certificate covers, no DNS loop ───

  assert {
    condition     = one(aws_cloudfront_distribution.main.origin).domain_name == aws_route53_record.origin.name
    error_message = "CloudFront's origin must be the dedicated origin hostname."
  }

  assert {
    condition     = one(aws_cloudfront_distribution.main.origin).domain_name != aws_route53_record.api.name
    error_message = "CloudFront's origin must not be the public API hostname, which resolves to CloudFront itself."
  }

  assert {
    condition = (
      length(split(".", aws_route53_record.origin.name)) == length(split(".", var.domain_name)) + 1 &&
      endswith(aws_route53_record.origin.name, ".${var.domain_name}") &&
      contains(aws_acm_certificate.main.subject_alternative_names, "*.${var.domain_name}")
    )
    error_message = "The origin hostname must be one label under the domain, so the *.<domain> certificate covers it."
  }

  assert {
    condition     = one(aws_route53_record.origin.alias).name == aws_lb.main.dns_name
    error_message = "The origin hostname must alias the ALB."
  }

  assert {
    condition     = one(aws_route53_record.api.alias).name == aws_cloudfront_distribution.main.domain_name
    error_message = "The public API hostname must alias CloudFront."
  }
}
