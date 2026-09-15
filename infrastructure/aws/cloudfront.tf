resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "Banking Platform API CDN — ${var.environment}"
  price_class         = "PriceClass_100"  # US, Canada, Europe edge locations
  aliases             = ["${var.api_subdomain}.${var.domain_name}"]
  web_acl_id          = aws_wafv2_web_acl.banking.arn

  # ── Origin: ALB ────────────────────────────────────────────────────────────

  origin {
    domain_name = aws_lb.main.dns_name
    origin_id   = "banking-alb"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.cf_origin_secret.result
    }
  }

  # ── Default Cache Behavior (API — no caching) ──────────────────────────────

  default_cache_behavior {
    allowed_methods  = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = "banking-alb"

    forwarded_values {
      query_string = true
      headers      = ["Authorization", "Content-Type", "X-Requested-With"]
      cookies {
        forward = "none"
      }
    }

    # No caching for API responses — always hit the origin
    min_ttl     = 0
    default_ttl = 0
    max_ttl     = 0

    viewer_protocol_policy = "redirect-to-https"
    compress               = true
  }

  # ── Cache Behavior: Swagger UI (cacheable static content) ─────────────────

  ordered_cache_behavior {
    path_pattern     = "/swagger-ui*"
    allowed_methods  = ["GET", "HEAD", "OPTIONS"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = "banking-alb"

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    min_ttl     = 0
    default_ttl = 3600
    max_ttl     = 86400

    viewer_protocol_policy = "redirect-to-https"
    compress               = true
  }

  ordered_cache_behavior {
    path_pattern     = "/v3/api-docs*"
    allowed_methods  = ["GET", "HEAD"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = "banking-alb"

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    min_ttl     = 0
    default_ttl = 300
    max_ttl     = 3600

    viewer_protocol_policy = "redirect-to-https"
    compress               = true
  }

  # ── Actuator health (no cache, small TTL) ──────────────────────────────────

  ordered_cache_behavior {
    path_pattern     = "/actuator/*"
    allowed_methods  = ["GET", "HEAD"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = "banking-alb"

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    min_ttl     = 0
    default_ttl = 10
    max_ttl     = 30

    viewer_protocol_policy = "redirect-to-https"
  }

  # ── TLS ────────────────────────────────────────────────────────────────────

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.main.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  tags = { Name = "banking-cf-${var.environment}" }
}

# Secret used to verify requests arriving at ALB came from CloudFront
resource "random_password" "cf_origin_secret" {
  length  = 32
  special = false
}
