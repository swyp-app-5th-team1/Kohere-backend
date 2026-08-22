variable "google_project_id" {
  description = "Cloud Translation과 Workload Identity Federation을 구성할 Google Cloud 프로젝트 ID"
  type        = string
}

variable "translation_service_account_email" {
  description = "EC2가 WIF로 가장해 Cloud Translation을 호출할 기존 Google 서비스 계정 이메일"
  type        = string
}

variable "aws_account_id" {
  description = "WIF가 신뢰할 AWS 계정 ID"
  type        = string
}

variable "aws_role_name" {
  description = "WIF를 사용할 수 있는 유일한 AWS IAM 역할 이름(예: kohere-dev-host)"
  type        = string
}

variable "workload_identity_pool_id" {
  description = "Google Workload Identity Pool ID"
  type        = string
  default     = "kohere-dev-aws"
}

variable "workload_identity_provider_id" {
  description = "Workload Identity Pool 안의 AWS Provider ID"
  type        = string
  default     = "kohere-dev-ec2"
}
