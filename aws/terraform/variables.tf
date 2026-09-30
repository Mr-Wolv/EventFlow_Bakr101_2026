variable "region" {
  description = "AWS region used for the local validation stack"
  type        = string
  default     = "us-east-1"
}

variable "localstack_endpoint" {
  description = "LocalStack API endpoint (override with TF_VAR_localstack_endpoint)"
  type        = string
  default     = "http://localhost:4566"
}

variable "table_name" {
  description = "DynamoDB table storing processed OrderCreated events (idempotency + state)"
  type        = string
  default     = "eventflow-orders"
}

variable "archive_bucket" {
  description = "S3 bucket receiving the raw event archive copies"
  type        = string
  default     = "eventflow-order-archive"
}

variable "queue_name" {
  description = "SQS queue fed by the EventFlow order pipeline"
  type        = string
  default     = "eventflow-orders-queue"
}
