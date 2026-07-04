# Kohere dev 환경 — EC2 1대에 dev 전용 docker-compose(Caddy · app+mysql+mongo+redis).
# ALB·ECS·RDS·DocumentDB·ElastiCache·NAT 없음 — 매니지드는 dev엔 과투자라 비용 최소화([ADR-0021]).
#   접속: EIP → Route53 A 레코드 + Caddy/Let's Encrypt HTTPS(도메인 필수).
#   데이터: 전용 암호화 EBS. 시크릿: SSM Parameter Store SecureString(무료·SM 미사용).
#   앱 이미지: ECR(=prod와 동일). 매물 이미지: S3 + CloudFront(shared 모듈).
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

# ===== 매물 이미지 (S3 + CloudFront) — prod·dev 공용 =====
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

# ===== IAM (인스턴스 프로파일 — SSM·ECR·파라미터·S3 이미지) =====
module "iam" {
  source = "../../modules/dev/iam"

  name_prefix       = local.name_prefix
  tags              = local.common_tags
  aws_region        = var.aws_region
  account_id        = data.aws_caller_identity.current.account_id
  images_bucket_arn = module.s3_cloudfront.bucket_arn
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
  # 비밀 아닌 앱 설정(enabled·from) — SSM String으로 저장해 refresh-env가 .env로 회수(재배포만으로 반영, ADR-0024)
  solapi_enabled = var.solapi_enabled
  solapi_from    = var.solapi_from
  bizno_enabled  = var.bizno_enabled
  # dev 테스트 마스터 로그인 토글(비밀 아님) — 시드+우회 로그인을 함께 켠다. 시크릿(TEST_LOGIN_SECRET)은 secrets 모듈이 자동 생성한다.
  test_login_enabled = var.test_login_enabled
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

  # 시크릿·설정 파라미터가 먼저 존재해야 부팅 시 refresh-env가 .env로 주입할 수 있다.
  depends_on = [module.secrets]
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
}
