# The prebuilt shaded jar (mvn -f aws/lambda/pom.xml package) IS a valid Lambda
# deployment package — a jar is a zip with classes at the root, exactly what the
# runtime's classloader expects. Wrapping it in a second zip (archive_file) hides
# the classes and yields ClassNotFoundException at invoke time. The jar is built
# outside Terraform so the Java toolchain stays out of IaC.
# LocalStack auto-injects AWS_ENDPOINT_URL (pointing at itself via the Docker
# network) and test credentials into the function environment; the handler
# uses whatever it finds and falls back to the default AWS chain unchanged.

resource "aws_lambda_function" "order_fulfillment" {
  function_name    = "eventflow-order-fulfillment"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.eventflow.aws.OrderCreatedHandler::handleRequest"
  runtime          = "java21"
  filename         = "${path.module}/../lambda/target/eventflow-order-lambda.jar"
  source_code_hash = filebase64sha256("${path.module}/../lambda/target/eventflow-order-lambda.jar")
  timeout          = 60
  memory_size      = 512

  environment {
    variables = {
      TABLE_NAME     = aws_dynamodb_table.orders.name
      ARCHIVE_BUCKET = aws_s3_bucket.archive.id
    }
  }

  # The role policy must exist before the function registers, or the event
  # source mapping cannot assume the role.
  depends_on = [aws_iam_role_policy.lambda_permissions]
}

# SQS → Lambda: the event-bridge equivalent of the Kafka path's consumer group.
resource "aws_lambda_event_source_mapping" "orders" {
  event_source_arn  = aws_sqs_queue.orders.arn
  function_name     = aws_lambda_function.order_fulfillment.arn
  batch_size        = 5
  enabled           = true
}
