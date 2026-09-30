# S3 archive for raw OrderCreated events. The Lambda writes one object per
# event under orders/<orderId>/<eventId>.json.
resource "aws_s3_bucket" "archive" {
  bucket = var.archive_bucket

  # Ephemeral validation stack: let `terraform destroy` remove the bucket with
  # its archived test objects instead of failing with BucketNotEmpty
  # (hit for real in the first CI run of this job).
  force_destroy = true
}

# DynamoDB is the durable idempotency store for the AWS path: the Lambda's
# conditional put (attribute_not_exists(eventId)) makes at-least-once SQS
# redelivery a no-op. This is the documented contrast with the Kafka path's
# process-local dedup set.
resource "aws_dynamodb_table" "orders" {
  name         = var.table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "eventId"

  attribute {
    name = "eventId"
    type = "S"
  }

  attribute {
    name = "orderId"
    type = "S"
  }

  # Query all stored events for one order (the archive/audit direction).
  global_secondary_index {
    name            = "orderId-index"
    hash_key        = "orderId"
    projection_type = "ALL"
  }
}

# SQS stands in for the event transport in this variant. Standard queue:
# at-least-once delivery, exactly like the Kafka topic; ordering semantics
# (and what changes) are discussed in docs/aws-architecture.md.
resource "aws_sqs_queue" "orders" {
  name                      = var.queue_name
  visibility_timeout_seconds = 60
}
