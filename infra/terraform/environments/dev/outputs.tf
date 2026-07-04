output "app_url" {
  description = "앱 접속 URL(HTTPS — 도메인 필수)"
  value       = module.host.app_url
}

output "public_ip" {
  description = "dev 호스트 EIP(Route53 A 레코드 대상)"
  value       = module.host.public_ip
}

output "instance_id" {
  description = "dev 호스트 EC2 인스턴스 ID(SSM 접속용)"
  value       = module.host.instance_id
}

output "images_bucket" {
  description = "매물 이미지 S3 버킷"
  value       = module.s3_cloudfront.bucket_name
}

output "images_cdn_domain" {
  description = "이미지 서빙 도메인(커스텀 별칭 — 강제)"
  value       = module.s3_cloudfront.cdn_domain
}

output "github_deploy_role_arn" {
  description = "GitHub Actions가 assume할 배포 역할 ARN(리포 Variables AWS_DEPLOY_ROLE_ARN 에 설정)"
  value       = aws_iam_role.github_deploy.arn
}

output "test_login_secret" {
  description = "dev 테스트 마스터 로그인 우회 시크릿(자동 생성) — 조회: terraform output -raw test_login_secret"
  value       = module.secrets.test_login_secret
  sensitive   = true
}
