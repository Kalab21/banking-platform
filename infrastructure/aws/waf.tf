# WAF WebACL must be in us-east-1 for CloudFront association
resource "aws_wafv2_web_acl" "banking" {
  provider    = aws.us_east_1
  name        = "banking-waf-${var.environment}"
  description = "Banking platform WAF — OWASP top 10 + rate limiting"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  # 1. AWS Common Rule Set (OWASP top 10 baseline)
  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "CommonRuleSet"
      sampled_requests_enabled   = true
    }
  }

  # 2. SQL Injection protection
  rule {
    name     = "AWSManagedRulesSQLiRuleSet"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesSQLiRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "SQLiRuleSet"
      sampled_requests_enabled   = true
    }
  }

  # 3. Known bad inputs (Log4Shell, SSRF, etc.)
  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 3

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "KnownBadInputs"
      sampled_requests_enabled   = true
    }
  }

  # 4. Rate limit: login/register endpoint — 100 requests per 5 minutes per IP
  #    Prevents brute-force and credential stuffing attacks
  rule {
    name     = "RateLimitAuthEndpoints"
    priority = 4

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 100
        aggregate_key_type = "IP"

        scope_down_statement {
          byte_match_statement {
            field_to_match {
              uri_path {}
            }
            positional_constraint = "STARTS_WITH"
            search_string         = "/api/auth"
            text_transformation {
              priority = 0
              type     = "LOWERCASE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AuthRateLimit"
      sampled_requests_enabled   = true
    }
  }

  # 5. Geo-block: OFAC-sanctioned countries
  rule {
    name     = "GeoBlockSanctionedCountries"
    priority = 5

    action {
      block {}
    }

    statement {
      geo_match_statement {
        # OFAC-sanctioned: Cuba, Iran, North Korea, Russia, Syria
        country_codes = ["CU", "IR", "KP", "RU", "SY"]
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "GeoBlock"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "BankingWAF"
    sampled_requests_enabled   = true
  }

  tags = { Name = "banking-waf-${var.environment}" }
}

# WAF logging to CloudWatch
resource "aws_cloudwatch_log_group" "waf" {
  provider          = aws.us_east_1
  name              = "aws-waf-logs-banking-${var.environment}"
  retention_in_days = 30
}

resource "aws_wafv2_web_acl_logging_configuration" "banking" {
  provider                = aws.us_east_1
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]
  resource_arn            = aws_wafv2_web_acl.banking.arn
}
