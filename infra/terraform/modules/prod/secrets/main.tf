# 앱 레벨 시크릿(JWT 서명키·pepper·OIDC audience·SMTP 자격증명)을 SSM Parameter Store SecureString에 보관.
# Secrets Manager 미사용(무료, ADR-0023). JWT_SECRET/REFRESH_PEPPER/EMAIL_PEPPER/PHONE_PEPPER는 여기서 생성(ADR-0011: >=256bit).
# SSM은 JSON 키 추출이 안 되므로 키마다 별도 SecureString 파라미터로 둔다(ECS secrets valueFrom = 파라미터 ARN).
# 외부 발급값(GOOGLE/SMTP)은 빈 값이면 placeholder로 둔다(SSM은 빈 값 거부) — 운영 전 tfvars로 채울 것.

resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

resource "random_password" "refresh_pepper" {
  length  = 48
  special = false
}

resource "random_password" "email_pepper" {
  length  = 48
  special = false
}

resource "random_password" "phone_pepper" {
  length  = 48
  special = false
}

locals {
  app_params = {
    JWT_SECRET           = random_password.jwt_secret.result
    REFRESH_PEPPER       = random_password.refresh_pepper.result
    EMAIL_PEPPER         = random_password.email_pepper.result
    PHONE_PEPPER         = random_password.phone_pepper.result
    GOOGLE_CLIENT_ID     = var.google_client_id != "" ? var.google_client_id : "REPLACE_ME"
    APPLE_CLIENT_ID      = var.apple_client_id != "" ? var.apple_client_id : "REPLACE_ME"
    APPLE_TEAM_ID        = var.apple_team_id != "" ? var.apple_team_id : "REPLACE_ME"
    APPLE_KEY_ID         = var.apple_key_id != "" ? var.apple_key_id : "REPLACE_ME"
    APPLE_PRIVATE_KEY    = var.apple_private_key != "" ? var.apple_private_key : "REPLACE_ME"
    SPRING_MAIL_USERNAME = var.smtp_username != "" ? var.smtp_username : "REPLACE_ME"
    SPRING_MAIL_PASSWORD = var.smtp_password != "" ? var.smtp_password : "REPLACE_ME"
    SOLAPI_API_KEY       = var.solapi_api_key != "" ? var.solapi_api_key : "REPLACE_ME"
    SOLAPI_API_SECRET    = var.solapi_api_secret != "" ? var.solapi_api_secret : "REPLACE_ME"
    BIZNO_API_KEY        = var.bizno_api_key != "" ? var.bizno_api_key : "REPLACE_ME"
    # 네이버 지역 검색 API 인증정보 — 미설정 시 REPLACE_ME 저장(운영 전 채울 것, #160/#162)
    NAVER_SEARCH_CLIENT_ID     = var.naver_search_client_id != "" ? var.naver_search_client_id : "REPLACE_ME"
    NAVER_SEARCH_CLIENT_SECRET = var.naver_search_client_secret != "" ? var.naver_search_client_secret : "REPLACE_ME"
  }
}

resource "aws_ssm_parameter" "app" {
  for_each = local.app_params
  name     = "/${var.name_prefix}/${each.key}"
  type     = "SecureString"
  value    = each.value
  tags     = merge(var.tags, { Name = "${var.name_prefix}-${lower(each.key)}" })
}
