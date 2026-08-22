terraform {
  required_version = ">= 1.10.0" # S3 native lockfile(use_lockfile) 사용
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
  }
}
