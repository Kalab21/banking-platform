# Encryption key for Kafka data at rest. Rotation is enabled: an unrotated
# long-lived key is the kind of thing that only ever gets noticed in an audit.
resource "aws_kms_key" "msk" {
  description             = "Banking Platform MSK encryption at rest (${var.environment})"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  tags = { Name = "banking-msk-kms-${var.environment}" }
}

resource "aws_kms_alias" "msk" {
  name          = "alias/banking-msk-${var.environment}"
  target_key_id = aws_kms_key.msk.key_id
}

resource "aws_msk_configuration" "main" {
  name              = "banking-kafka-config-${var.environment}"
  kafka_versions    = [var.msk_kafka_version]
  server_properties = <<-PROPS
    auto.create.topics.enable=true
    default.replication.factor=2
    min.insync.replicas=1
    num.partitions=3
    log.retention.hours=168
    offsets.topic.replication.factor=2
    transaction.state.log.replication.factor=2
    transaction.state.log.min.isr=1
  PROPS
}

resource "aws_msk_cluster" "main" {
  cluster_name           = "banking-kafka-${var.environment}"
  kafka_version          = var.msk_kafka_version
  number_of_broker_nodes = var.msk_broker_count

  broker_node_group_info {
    instance_type   = var.msk_instance_type
    client_subnets  = slice(aws_subnet.private[*].id, 0, var.msk_broker_count)
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.msk_broker_volume_gb
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.main.arn
    revision = aws_msk_configuration.main.latest_revision
  }

  client_authentication {
    unauthenticated = true
  }

  encryption_info {
    # Customer-managed key rather than the AWS-managed default, so the key
    # policy, rotation and revocation are all visible in this repository
    # instead of being implicit.
    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn

    encryption_in_transit {
      # Was PLAINTEXT, which put every transaction, payment and KYC event on
      # the wire in the clear between the services and the brokers. TLS is the
      # only defensible setting for a system carrying this data, even inside a
      # private subnet: "the network is trusted" is exactly the assumption that
      # keeps failing.
      #
      # Clients must connect to the TLS bootstrap endpoint accordingly; see
      # the msk_bootstrap_brokers_tls output.
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = { Name = "banking-msk-${var.environment}" }
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/banking-platform/${var.environment}/msk"
  retention_in_days = 7
}
