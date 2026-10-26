# Assumes the hosted zone for var.domain_name already exists in Route 53
data "aws_route53_zone" "main" {
  name         = var.domain_name
  private_zone = false
}

# api.yourbank.com → CloudFront distribution
resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${var.api_subdomain}.${var.domain_name}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}

# IPv6
resource "aws_route53_record" "api_aaaa" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${var.api_subdomain}.${var.domain_name}"
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}

locals {
  # One label under the domain, so the certificate's *.<domain> SAN covers it.
  origin_hostname = "${var.origin_subdomain}.${var.domain_name}"
}

# origin-api.yourbank.com -> ALB. CloudFront's origin, not a public entry point:
# the ALB admits only CloudFront's origin-facing servers and requires the
# X-Origin-Verify header. The ALB is IPv4-only, so there is no AAAA record.
resource "aws_route53_record" "origin" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = local.origin_hostname
  type    = "A"

  # The public hostname aliases CloudFront and this one aliases the ALB; the
  # same name would collide and make CloudFront's origin resolve to itself.
  lifecycle {
    precondition {
      condition     = lower(var.origin_subdomain) != lower(var.api_subdomain)
      error_message = "origin_subdomain must differ from api_subdomain: the API hostname aliases CloudFront and the origin hostname aliases the ALB."
    }
  }

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = false
  }
}

# There is no Route 53 health check. No record uses failover routing and there
# is no second region to fail over to, and a checker hitting the ALB directly
# would now be refused by its security group and listener rule.
