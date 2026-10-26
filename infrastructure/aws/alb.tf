# ── ACM Certificate ──────────────────────────────────────────────────────────
#
# One certificate serves both TLS endpoints: CloudFront's viewer certificate
# (which must be in us-east-1) and the ALB's HTTPS listener (which must be in
# the ALB's region). That only works because this model is pinned to us-east-1;
# see the validation on var.aws_region. Moving the ALB to another region would
# need a second, regional certificate for the listener.

resource "aws_acm_certificate" "main" {
  provider          = aws.us_east_1
  domain_name       = "${var.api_subdomain}.${var.domain_name}"
  validation_method = "DNS"

  subject_alternative_names = [
    "*.${var.domain_name}",
  ]

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "banking-cert-${var.environment}" }
}

resource "aws_acm_certificate_validation" "main" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.main.arn
  validation_record_fqdns = [for record in aws_route53_record.cert_validation : record.fqdn]
}

# DNS validation records
resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.main.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = data.aws_route53_zone.main.zone_id
}

# ── Application Load Balancer (internet-facing) ───────────────────────────────

resource "aws_lb" "main" {
  name               = "banking-alb-${var.environment}"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  enable_deletion_protection = true
  enable_http2               = true

  access_logs {
    bucket  = aws_s3_bucket.alb_logs.id
    prefix  = "banking-alb"
    enabled = true
  }

  # ELB checks it can write to the bucket when logging is enabled, so the
  # policy granting that must exist first. The bucket reference alone does not
  # order the ALB after the policy.
  depends_on = [aws_s3_bucket_policy.alb_logs]

  tags = { Name = "banking-alb-${var.environment}" }
}

# ALB access log bucket
resource "aws_s3_bucket" "alb_logs" {
  bucket        = "banking-platform-alb-logs-${data.aws_caller_identity.current.account_id}-${var.environment}"
  force_destroy = false
  tags          = { Name = "banking-alb-logs" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# ALB access-log delivery writes as the Elastic Load Balancing log-delivery
# service principal, only under this ALB's prefix and account.
resource "aws_s3_bucket_policy" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "logdelivery.elasticloadbalancing.amazonaws.com" }
      Action    = "s3:PutObject"
      Resource  = "${aws_s3_bucket.alb_logs.arn}/banking-alb/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
    }]
  })

  # Applying the policy while the public-access block is being set can conflict.
  depends_on = [aws_s3_bucket_public_access_block.alb_logs]
}

# ── Target Group — API Gateway ────────────────────────────────────────────────

resource "aws_lb_target_group" "api_gateway" {
  name        = "banking-api-gw-tg-${var.environment}"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    enabled             = true
    path                = "/actuator/health"
    port                = "8080"
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  deregistration_delay = 30

  tags = { Name = "banking-api-gw-tg" }
}

# ── Listeners ─────────────────────────────────────────────────────────────────

# HTTPS only. There is no HTTP listener: CloudFront redirects viewers to HTTPS
# and reaches this origin over HTTPS, and the security group admits only
# CloudFront's origin-facing servers on 443.
#
# The certificate covers origin-api.<domain> through its *.<domain> SAN, which
# is the hostname CloudFront connects to (route53.tf).
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.main.certificate_arn

  # Anything that does not carry CloudFront's origin secret is refused.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

# Forward only requests that came through this CloudFront distribution: it adds
# X-Origin-Verify with a generated secret (cloudfront.tf). The value lives in
# Terraform state, never in source or outputs.
resource "aws_lb_listener_rule" "from_cloudfront" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 1

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.cf_origin_secret.result]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api_gateway.arn
  }
}
