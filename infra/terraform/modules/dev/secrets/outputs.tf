output "ssm_prefix" {
  description = "SSM 파라미터 접두사(/<name_prefix>)"
  value       = local.ssm_prefix
}

output "parameter_arns" {
  description = "생성된 SecureString 파라미터 ARN 맵(호스트 의존성 명시용)"
  value       = { for k, p in aws_ssm_parameter.secure : k => p.arn }
}

output "test_login_secret" {
  description = "dev 테스트 마스터 로그인 우회 시크릿(자동 생성). 조회: terraform output -raw test_login_secret → idToken master:<role>:<secret>에 사용"
  value       = random_password.test_login_secret.result
  sensitive   = true
}
