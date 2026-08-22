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

output "google_wif_provider_name" {
  description = "dev EC2가 Google 단기 토큰을 발급받을 때 사용하는 WIF Provider 전체 이름"
  value       = module.google_wif.provider_name
}

output "configure_chat_translation_document" {
  description = "기존 dev EC2에 WIF 파일·compose 설정을 1회 반영할 SSM Document 이름"
  value       = module.host.configure_chat_translation_document_name
}

output "app_log_group" {
  description = "앱 로그 CloudWatch Log Group(Logs Insights 쿼리 대상). 수집 여부는 enable_cloudwatch_agent"
  value       = module.logs.log_group_name
}

output "images_bucket" {
  description = "콘텐츠 이미지 S3 버킷"
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

output "web_artifacts_bucket" {
  description = "프론트 릴리스 아티팩트 버킷(프론트 리포 Variables 의 WEB_ARTIFACTS_BUCKET)"
  value       = module.web.bucket_name
}

output "github_web_deploy_role_arn" {
  description = "프론트 GitHub Actions가 assume할 배포 역할 ARN(프론트 리포 Variables 의 AWS_DEPLOY_ROLE_ARN)"
  value       = aws_iam_role.github_web_deploy.arn
}

output "ssm_deploy_web_document" {
  description = "프론트 릴리스 적용 SSM Document 이름(프론트 리포 Variables 의 SSM_DEPLOY_WEB_DOCUMENT)"
  value       = aws_ssm_document.deploy_web.name
}
