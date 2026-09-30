output "table_name" {
  value       = aws_dynamodb_table.orders.name
  description = "DynamoDB table storing processed events"
}

output "archive_bucket" {
  value       = aws_s3_bucket.archive.id
  description = "S3 bucket receiving raw event archives"
}

output "queue_url" {
  value       = aws_sqs_queue.orders.url
  description = "SQS queue URL the validation script produces events into"
}

output "queue_arn" {
  value       = aws_sqs_queue.orders.arn
  description = "SQS queue ARN"
}

output "lambda_function_name" {
  value       = aws_lambda_function.order_fulfillment.function_name
  description = "Fulfillment Lambda function name"
}
