terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

# LocalStack endpoint configuration. Credentials are the LocalStack convention
# ("test"/"test"); with ENFORCE_IAM=1 the IAM policies in iam.tf are still
# evaluated for real, so the wiring is policy-correct, not just shape-correct.
provider "aws" {
  region                      = var.region
  access_key                  = "test"
  secret_key                  = "test"
  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    s3       = var.localstack_endpoint
    sqs      = var.localstack_endpoint
    lambda   = var.localstack_endpoint
    dynamodb = var.localstack_endpoint
    iam      = var.localstack_endpoint
    sts      = var.localstack_endpoint
  }
}
