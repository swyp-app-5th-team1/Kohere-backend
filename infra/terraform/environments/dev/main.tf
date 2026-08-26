# Kohere dev 환경 — EC2 1대에 dev 전용 docker-compose(Caddy · app+mysql+mongo+redis).
# ALB·ECS·RDS·DocumentDB·ElastiCache·NAT 없음 — 매니지드는 dev엔 과투자라 비용 최소화([ADR-0021]).
#   접속: EIP → Route53 A 레코드 + Caddy/Let's Encrypt HTTPS(도메인 필수).
#   데이터: 전용 암호화 EBS. 시크릿: SSM Parameter Store SecureString(무료·SM 미사용).
#   앱 이미지: ECR(=prod와 동일). 콘텐츠 이미지: S3 + CloudFront(shared 모듈).
# prod처럼 서비스별 dev 모듈(network·security·iam·secrets·storage·host·dns·monitoring)로 조립한다.

locals {
  name_prefix = "${var.project}-${var.environment}"

  common_tags = {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

data "aws_caller_identity" "current" {}

# ===== ACM — CloudFront용(us-east-1 필수) =====
module "cdn_acm" {
  source = "../../modules/shared/acm"

  providers = { aws = aws.us_east_1 }

  name_prefix     = "${local.name_prefix}-cdn"
  tags            = local.common_tags
  domain_name     = var.cdn_domain_name
  route53_zone_id = var.route53_zone_id
}

# ===== 콘텐츠 이미지 (S3 + CloudFront) — prod·dev 공용 =====
module "s3_cloudfront" {
  source = "../../modules/shared/s3-cloudfront"

  name_prefix = local.name_prefix
  tags        = local.common_tags
  bucket_name = var.images_bucket_name

  # 커스텀 도메인(별칭 + us-east-1 ACM) — 필수. HTTPS·커스텀 도메인 강제.
  domain_aliases      = [var.cdn_domain_name]
  acm_certificate_arn = module.cdn_acm.certificate_arn
  route53_zone_id     = var.route53_zone_id
}

# ===== 임대인 웹 릴리스 아티팩트 (비공개 S3) =====
# 콘텐츠 이미지 버킷과 달리 CDN이 없다 — 읽는 주체가 dev 호스트 하나이고, 서빙은 그 호스트의 Caddy가 한다(#232).
module "web" {
  source = "../../modules/dev/web"

  tags                   = local.common_tags
  bucket_name            = var.web_artifacts_bucket_name
  release_retention_days = var.web_release_retention_days
}

# ===== 네트워크 (미니 VPC·IGW·public subnet) =====
module "network" {
  source = "../../modules/dev/network"

  name_prefix = local.name_prefix
  tags        = local.common_tags
}

# ===== 보안 그룹 (80/443 + 옵션 DB 포트) =====
module "security" {
  source = "../../modules/dev/security"

  name_prefix      = local.name_prefix
  tags             = local.common_tags
  vpc_id           = module.network.vpc_id
  ingress_cidrs    = var.ingress_cidrs
  db_ingress_cidrs = var.db_ingress_cidrs
}

# ===== 로그 (CloudWatch Log Group — 앱 로그 수집 대상) =====
# 빈 Log Group은 비용이 없으므로 Agent 활성 여부와 무관하게 항상 만든다. 수집만 host 모듈의
# enable_cloudwatch_agent로 토글해, 켜는 순간 권한·대상이 이미 준비된 상태가 되게 한다(ADR-0038 ⑥).
module "logs" {
  source = "../../modules/dev/logs"

  tags              = local.common_tags
  log_group_name    = "/${var.project}/${var.environment}/app"
  retention_in_days = var.log_retention_days
}

# ===== IAM (인스턴스 프로파일 — SSM·ECR·파라미터·S3 이미지·로그 반출) =====
module "iam" {
  source = "../../modules/dev/iam"

  name_prefix       = local.name_prefix
  tags              = local.common_tags
  aws_region        = var.aws_region
  account_id        = data.aws_caller_identity.current.account_id
  images_bucket_arn = module.s3_cloudfront.bucket_arn
  log_group_arn     = module.logs.log_group_arn
  # 프론트 릴리스 읽기 + current.txt 포인터 쓰기(#232)
  web_bucket_arn = module.web.bucket_arn
}

# ===== Google Cloud Translation WIF =====
# 기존 EC2 인스턴스 프로파일(kohere-dev-host)을 Google의 짧은 토큰으로 교환한다.
# AWS IAM 역할·정책은 그대로 두고, Google 쪽에서 이 역할만 번역 서비스 계정을 가장하도록 허용한다.
module "google_wif" {
  source = "../../modules/dev/google-wif"

  google_project_id                 = var.google_cloud_project_id
  translation_service_account_email = var.google_translation_service_account_email
  aws_account_id                    = data.aws_caller_identity.current.account_id
  aws_role_name                     = module.iam.role_name
}

# ===== 시크릿 (SSM Parameter Store SecureString) =====
module "secrets" {
  source = "../../modules/dev/secrets"

  name_prefix         = local.name_prefix
  tags                = local.common_tags
  google_client_id    = var.google_client_id
  apple_client_id     = var.apple_client_id
  apple_team_id       = var.apple_team_id
  apple_key_id        = var.apple_key_id
  apple_private_key   = var.apple_private_key
  smtp_username       = var.smtp_username
  smtp_password       = var.smtp_password
  mysql_password      = var.mysql_password
  mysql_root_password = var.mysql_root_password
  mongo_password      = var.mongo_password
  solapi_api_key      = var.solapi_api_key
  solapi_api_secret   = var.solapi_api_secret
  bizno_api_key       = var.bizno_api_key
  # 네이버 지역 검색 API 인증정보(Client ID/Secret 모두 시크릿, #160/#162)
  naver_search_client_id     = var.naver_search_client_id
  naver_search_client_secret = var.naver_search_client_secret
  # NCP Maps Geocoding 인증정보(도로명 주소 검색 — #223)
  naver_geocode_client_id     = var.naver_geocode_client_id
  naver_geocode_client_secret = var.naver_geocode_client_secret
  # 카카오 로컬 API 인증정보(인근 역 검색 · 등록 시 인근 대학 파생 — #224, ADR-0044)
  kakao_rest_api_key = var.kakao_rest_api_key
  # 비밀 아닌 앱 설정(enabled·from) — SSM String으로 저장해 refresh-env가 .env로 회수(재배포만으로 반영, ADR-0024)
  solapi_enabled = var.solapi_enabled
  solapi_from    = var.solapi_from
  bizno_enabled  = var.bizno_enabled
  # 임대인 웹 오리진(#272) — 재설정 링크 base URL. compose가 아니라 SSM 경유라 재배포만으로 반영된다.
  web_base_url = var.web_base_url
  # dev 테스트 마스터 로그인 토글(비밀 아님) — 시드+우회 로그인을 함께 켠다. 시크릿(TEST_LOGIN_SECRET)은 secrets 모듈이 자동 생성한다.
  test_login_enabled = var.test_login_enabled
  # 앱스토어 심사 계정 고정 인증번호(#180) — 심사 기간에만 켠다. 코드·계정 식별자는 SecureString.
  fixed_verification_enabled                = var.fixed_verification_enabled
  fixed_verification_code                   = var.fixed_verification_code
  fixed_verification_tenant_google_emails   = var.fixed_verification_tenant_google_emails
  fixed_verification_tenant_emails          = var.fixed_verification_tenant_emails
  fixed_verification_landlord_google_emails = var.fixed_verification_landlord_google_emails
  fixed_verification_landlord_phones        = var.fixed_verification_landlord_phones
}

# ===== 데이터 EBS (mysql/mongo 영속) =====
module "storage" {
  source = "../../modules/dev/storage"

  name_prefix       = local.name_prefix
  tags              = local.common_tags
  availability_zone = module.network.subnet_az
  size              = var.data_volume_size
}

# ===== 호스트 (EC2 + EIP + EBS attach + user_data 부트스트랩) =====
module "host" {
  source = "../../modules/dev/host"

  name_prefix           = local.name_prefix
  tags                  = local.common_tags
  aws_region            = var.aws_region
  account_id            = data.aws_caller_identity.current.account_id
  subnet_id             = module.network.subnet_id
  security_group_id     = module.security.security_group_id
  instance_profile_name = module.iam.instance_profile_name
  volume_id             = module.storage.volume_id
  instance_type         = var.instance_type
  app_image             = var.app_image
  db_name               = var.db_name
  domain_name           = var.domain_name
  smtp_host             = var.smtp_host
  smtp_port             = var.smtp_port
  mail_from             = var.mail_from
  mysql_username        = var.mysql_username
  mongo_username        = var.mongo_username
  images_bucket         = module.s3_cloudfront.bucket_name
  images_cdn_domain     = module.s3_cloudfront.cdn_domain

  # 서비스 계정 개인키가 아닌 WIF 설정 JSON을 호스트에 쓰고 앱 컨테이너에 읽기 전용으로 연결한다.
  google_wif_credential_configuration = module.google_wif.credential_configuration
  chat_translation_enabled            = var.chat_translation_enabled
  chat_translation_project_id         = var.google_cloud_project_id
  chat_translation_location           = "global"
  firebase_enabled                    = var.firebase_enabled
  firebase_project_id                 = var.google_cloud_project_id

  # 임대인 웹 릴리스(#232) — deploy-web.sh 렌더와 부팅 복원(current.txt)이 이 버킷을 쓴다.
  web_artifacts_bucket = module.web.bucket_name

  # 로그 반출(ADR-0038 ⑥) — Agent는 기본 비활성(ADR-0026 메모리 게이트). Log Group은 항상 준비돼 있다.
  log_group_name          = module.logs.log_group_name
  enable_cloudwatch_agent = var.enable_cloudwatch_agent

  # 시크릿·설정 파라미터가 먼저 존재해야 부팅 시 refresh-env가 .env로 주입할 수 있다.
  depends_on = [module.secrets, module.google_wif]
}

# ===== Route53 A 레코드(도메인 필수) → EIP =====
module "dns" {
  source = "../../modules/dev/dns"

  domain_name     = var.domain_name
  route53_zone_id = var.route53_zone_id
  public_ip       = module.host.public_ip
}

# ===== 모니터링 (CloudWatch 알람 + SNS) =====
module "monitoring" {
  source = "../../modules/dev/monitoring"

  name_prefix         = local.name_prefix
  tags                = local.common_tags
  discord_webhook_url = var.discord_webhook_url
  instance_id         = module.host.instance_id

  # 로그 일 수집량 상한 감시(ADR-0038). AWS가 Log Group당 하드 리밋을 주지 않아 조기 경보로 둔다.
  log_group_name = module.logs.log_group_name
}
