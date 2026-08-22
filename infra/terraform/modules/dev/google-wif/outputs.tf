output "credential_configuration" {
  description = "EC2 앱 컨테이너가 ADC로 읽을 비밀키 없는 Google external_account 설정 JSON"
  value       = local.credential_configuration
}

output "provider_name" {
  description = "생성된 AWS WIF Provider의 전체 Google 리소스 이름"
  value       = google_iam_workload_identity_pool_provider.aws.name
}

output "aws_role_principal_set" {
  description = "서비스 계정 가장 권한을 받은 AWS 역할 principalSet"
  value       = local.aws_role_principal_set
}
