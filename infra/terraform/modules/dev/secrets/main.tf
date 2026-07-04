# dev 앱 시크릿 — SSM Parameter Store SecureString (SM 미사용·무료, ADR-0023).
# 자동 생성(앱 부팅 필수): JWT_SECRET·REFRESH_PEPPER·EMAIL_PEPPER·PHONE_PEPPER. 나머지는 외부 발급 + DB 자격증명.
# 호스트가 인스턴스 프로파일로 부팅 시 .env에 주입. 변경 반영은 배포(refresh-env + reconcile-db, ADR-0024/0025).
resource "random_password" "jwt_secret" {
  length  = 48
  special = false
}
resource "random_password" "refresh_pepper" {
  length  = 32
  special = false
}
resource "random_password" "email_pepper" {
  length  = 32
  special = false
}
resource "random_password" "phone_pepper" {
  length  = 32
  special = false
}
# dev·local 테스트 마스터 로그인 우회 시크릿(app.auth.test-login.secret). 항상 생성해 두고, 활성화는 TEST_LOGIN_ENABLED 토글로 별도 제어한다.
resource "random_password" "test_login_secret" {
  length  = 48
  special = false
}

locals {
  ssm_prefix = "/${var.name_prefix}"

  # 자동 생성 시크릿 — 항상 존재(빈 값 불가). random 값은 plan 시점 미정이라 무조건 생성(필터 X).
  generated_params = {
    JWT_SECRET        = random_password.jwt_secret.result
    REFRESH_PEPPER    = random_password.refresh_pepper.result
    EMAIL_PEPPER      = random_password.email_pepper.result
    PHONE_PEPPER      = random_password.phone_pepper.result
    TEST_LOGIN_SECRET = random_password.test_login_secret.result
  }

  # 외부 발급(OIDC·SMTP) + DB 자격증명 — vars라 plan 시점 known. 빈 값은 SSM이 거부(길이 ≥ 1)하므로 생성 제외.
  provided_params = {
    GOOGLE_CLIENT_ID    = var.google_client_id
    APPLE_CLIENT_ID     = var.apple_client_id
    APPLE_TEAM_ID       = var.apple_team_id
    APPLE_KEY_ID        = var.apple_key_id
    APPLE_PRIVATE_KEY   = var.apple_private_key
    SMTP_USERNAME       = var.smtp_username
    SMTP_PASSWORD       = var.smtp_password
    MYSQL_PASSWORD      = var.mysql_password
    MYSQL_ROOT_PASSWORD = var.mysql_root_password
    MONGO_PASSWORD      = var.mongo_password
    SOLAPI_API_KEY      = var.solapi_api_key
    SOLAPI_API_SECRET   = var.solapi_api_secret
    BIZNO_API_KEY       = var.bizno_api_key
  }

  # nonsensitive: 빈 값 여부만 for_each 키 결정에 쓴다(값 자체는 노출 안 함). vars라 plan 시점 known.
  secure_params = merge(
    local.generated_params,
    { for k, v in local.provided_params : k => v if nonsensitive(v) != "" },
  )

  # 앱 런타임 토글·설정(비밀 아님). refresh-env가 .env로 회수해 컨테이너에 주입 — SOLAPI_ENABLED/FROM·BIZNO_ENABLED를
  # compose(부팅 1회 렌더)가 아닌 SSM 경유로 옮겨 재배포(refresh-env 재실행)만으로 반영되게 한다(ADR-0024). 빈 값은 SSM이 거부하므로 제외.
  config_params = {
    for k, v in {
      SOLAPI_ENABLED     = tostring(var.solapi_enabled)
      SOLAPI_FROM        = var.solapi_from
      BIZNO_ENABLED      = tostring(var.bizno_enabled)
      TEST_LOGIN_ENABLED = tostring(var.test_login_enabled)
    } : k => v if v != ""
  }
}

resource "aws_ssm_parameter" "secure" {
  for_each = local.secure_params
  name     = "${local.ssm_prefix}/${each.key}"
  type     = "SecureString"
  value    = each.value
  tags     = merge(var.tags, { Name = "${var.name_prefix}-${lower(each.key)}" })
}

# 비밀 아닌 앱 설정(enabled·from) — String(평문). refresh-env의 get_opt가 SecureString과 동일하게 읽는다(--with-decryption은 String에 무해).
resource "aws_ssm_parameter" "config" {
  for_each = local.config_params
  name     = "${local.ssm_prefix}/${each.key}"
  type     = "String"
  value    = each.value
  tags     = merge(var.tags, { Name = "${var.name_prefix}-${lower(each.key)}" })
}
