# Kohere 백엔드 — prod 환경 통합 배선.
# system-overview §1-3-2(AWS 배포 토폴로지)에 따른 구성:
#   ALB(HTTPS) → ECS Fargate → RDS(MySQL)·DocumentDB·ElastiCache(Redis), 이미지=S3+CloudFront, 시크릿=Secrets Manager.

locals {
  name_prefix = "${var.project}-${var.environment}"

  common_tags = {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# ===== 네트워크 =====
module "network" {
  source = "../../modules/prod/network"

  name_prefix                = local.name_prefix
  tags                       = local.common_tags
  vpc_cidr                   = var.vpc_cidr
  az_count                   = var.az_count
  single_nat_gateway         = var.single_nat_gateway
  enable_interface_endpoints = var.enable_interface_endpoints
  app_port                   = var.app_port
}

# ===== 보안 그룹 =====
module "security" {
  source = "../../modules/prod/security"

  name_prefix       = local.name_prefix
  tags              = local.common_tags
  vpc_id            = module.network.vpc_id
  vpc_cidr          = module.network.vpc_cidr
  app_port          = var.app_port
  alb_ingress_cidrs = var.alb_ingress_cidrs
}

# ===== ECR — bootstrap 이 단일 생성(dev·prod 공유). 여기선 이름으로 조회만. =====
data "aws_ecr_repository" "app" {
  name = var.ecr_repo_name
}

# ===== ACM — ALB HTTPS 종단(리전) =====
module "acm" {
  source = "../../modules/shared/acm"

  name_prefix     = local.name_prefix
  tags            = local.common_tags
  domain_name     = var.domain_name
  route53_zone_id = var.route53_zone_id
}

# ===== ACM — CloudFront용(us-east-1 필수) =====
module "cdn_acm" {
  source = "../../modules/shared/acm"

  providers = { aws = aws.us_east_1 }

  name_prefix     = "${local.name_prefix}-cdn"
  tags            = local.common_tags
  domain_name     = var.cdn_domain_name
  route53_zone_id = var.route53_zone_id
}

# ===== RDS (MySQL) =====
module "rds" {
  source = "../../modules/prod/rds"

  name_prefix             = local.name_prefix
  tags                    = local.common_tags
  subnet_ids              = module.network.data_subnet_ids
  security_group_ids      = [module.security.rds_sg_id]
  engine_version          = var.rds_engine_version
  instance_class          = var.rds_instance_class
  allocated_storage       = var.rds_allocated_storage
  max_allocated_storage   = var.rds_max_allocated_storage
  multi_az                = var.rds_multi_az
  backup_retention_period = var.rds_backup_retention
  deletion_protection     = var.rds_deletion_protection
  db_name                 = var.db_name
  username                = var.db_username
}

# ===== DocumentDB =====
module "documentdb" {
  source = "../../modules/prod/documentdb"

  name_prefix         = local.name_prefix
  tags                = local.common_tags
  subnet_ids          = module.network.data_subnet_ids
  security_group_ids  = [module.security.docdb_sg_id]
  engine_version      = var.docdb_engine_version
  instance_class      = var.docdb_instance_class
  instance_count      = var.docdb_instance_count
  backup_retention    = var.docdb_backup_retention
  deletion_protection = var.docdb_deletion_protection
  username            = var.docdb_username
  database_name       = var.db_name
  tls                 = var.docdb_tls
}

# ===== ElastiCache (Redis) =====
module "elasticache" {
  source = "../../modules/prod/elasticache"

  name_prefix        = local.name_prefix
  tags               = local.common_tags
  subnet_ids         = module.network.data_subnet_ids
  security_group_ids = [module.security.redis_sg_id]
  node_type          = var.redis_node_type
  engine_version     = var.redis_engine_version
  replicas           = var.redis_replicas
  multi_az           = var.redis_multi_az
  transit_encryption = var.redis_transit_encryption
  maxmemory_policy   = var.redis_maxmemory_policy
}

# ===== 앱 시크릿 =====
module "secrets" {
  source = "../../modules/prod/secrets"

  name_prefix       = local.name_prefix
  tags              = local.common_tags
  google_client_id  = var.google_client_id
  apple_client_id   = var.apple_client_id
  apple_team_id     = var.apple_team_id
  apple_key_id      = var.apple_key_id
  apple_private_key = var.apple_private_key
  smtp_username     = var.smtp_username
  smtp_password     = var.smtp_password
  solapi_api_key    = var.solapi_api_key
  solapi_api_secret = var.solapi_api_secret
  bizno_api_key     = var.bizno_api_key
  # 네이버 지역 검색 API 인증정보(Client ID/Secret 모두 시크릿, #160/#162)
  naver_search_client_id     = var.naver_search_client_id
  naver_search_client_secret = var.naver_search_client_secret
}

# ===== IAM (ECS 역할 + GitHub OIDC) =====
module "iam" {
  source = "../../modules/prod/iam"

  name_prefix          = local.name_prefix
  tags                 = local.common_tags
  secret_arns          = concat(values(module.secrets.param_arns), [module.rds.password_param_arn, module.documentdb.uri_param_arn])
  ecr_repository_arn   = data.aws_ecr_repository.app.arn
  github_org           = var.github_org
  github_repo          = var.github_repo
  github_deploy_branch = var.github_deploy_branch
  region               = var.aws_region
  account_id           = data.aws_caller_identity.current.account_id
  images_bucket_arn    = module.s3_cloudfront.bucket_arn
}

# ===== ALB =====
module "alb" {
  source = "../../modules/prod/alb"

  name_prefix        = local.name_prefix
  tags               = local.common_tags
  vpc_id             = module.network.vpc_id
  public_subnet_ids  = module.network.public_subnet_ids
  security_group_ids = [module.security.alb_sg_id]
  app_port           = var.app_port
  health_check_path  = var.health_check_path
  certificate_arn    = module.acm.certificate_arn
}

# ===== ECS 컨테이너 환경/시크릿 매핑 (application-prod.yml 계약) =====
locals {
  container_image = "${data.aws_ecr_repository.app.repository_url}:${var.app_image_tag}"

  container_environment = [
    { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
    { name = "SPRING_DATASOURCE_URL", value = "jdbc:mysql://${module.rds.address}:${module.rds.port}/${module.rds.db_name}?serverTimezone=UTC" },
    { name = "SPRING_DATASOURCE_USERNAME", value = module.rds.username },
    { name = "SPRING_DATA_REDIS_HOST", value = module.elasticache.primary_endpoint },
    { name = "SPRING_DATA_REDIS_PORT", value = tostring(module.elasticache.port) },
    { name = "SPRING_MAIL_HOST", value = var.smtp_host },
    { name = "SPRING_MAIL_PORT", value = tostring(var.smtp_port) },
    { name = "MAIL_FROM", value = var.mail_from },
    # 콘텐츠 이미지: 앱이 S3에 업로드하고 CDN URL을 클라이언트에 반환(앱은 서빙 경로 아님).
    # APP_IMAGES_CDN_DOMAIN은 커스텀 별칭(cdn_domain_name)으로 고정 — 필수·강제(폴백 없음).
    { name = "APP_IMAGES_BUCKET", value = module.s3_cloudfront.bucket_name },
    { name = "APP_IMAGES_CDN_DOMAIN", value = module.s3_cloudfront.cdn_domain },
    # 연락처 SMS(SOLAPI, ADR-0034) — enabled/from은 비밀 아님(평문 env). api-key/secret은 container_secrets(SSM).
    { name = "SOLAPI_ENABLED", value = tostring(var.solapi_enabled) },
    { name = "SOLAPI_FROM", value = var.solapi_from },
    # 사업자번호 검증(비즈노, ADR-0033) — enabled는 비밀 아님(평문 env). api-key는 container_secrets(SSM).
    { name = "BIZNO_ENABLED", value = tostring(var.bizno_enabled) },
  ]

  # valueFrom = SSM 파라미터 ARN (Parameter Store SecureString — ADR-0023). 값 전체가 곧 시크릿.
  container_secrets = [
    { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = module.rds.password_param_arn },
    { name = "SPRING_DATA_MONGODB_URI", valueFrom = module.documentdb.uri_param_arn },
    { name = "JWT_SECRET", valueFrom = module.secrets.param_arns["JWT_SECRET"] },
    { name = "REFRESH_PEPPER", valueFrom = module.secrets.param_arns["REFRESH_PEPPER"] },
    { name = "EMAIL_PEPPER", valueFrom = module.secrets.param_arns["EMAIL_PEPPER"] },
    { name = "PHONE_PEPPER", valueFrom = module.secrets.param_arns["PHONE_PEPPER"] },
    { name = "GOOGLE_CLIENT_ID", valueFrom = module.secrets.param_arns["GOOGLE_CLIENT_ID"] },
    { name = "APPLE_CLIENT_ID", valueFrom = module.secrets.param_arns["APPLE_CLIENT_ID"] },
    { name = "APPLE_TEAM_ID", valueFrom = module.secrets.param_arns["APPLE_TEAM_ID"] },
    { name = "APPLE_KEY_ID", valueFrom = module.secrets.param_arns["APPLE_KEY_ID"] },
    { name = "APPLE_PRIVATE_KEY", valueFrom = module.secrets.param_arns["APPLE_PRIVATE_KEY"] },
    { name = "SPRING_MAIL_USERNAME", valueFrom = module.secrets.param_arns["SPRING_MAIL_USERNAME"] },
    { name = "SPRING_MAIL_PASSWORD", valueFrom = module.secrets.param_arns["SPRING_MAIL_PASSWORD"] },
    { name = "SOLAPI_API_KEY", valueFrom = module.secrets.param_arns["SOLAPI_API_KEY"] },
    { name = "SOLAPI_API_SECRET", valueFrom = module.secrets.param_arns["SOLAPI_API_SECRET"] },
    { name = "BIZNO_API_KEY", valueFrom = module.secrets.param_arns["BIZNO_API_KEY"] },
    # 네이버 지역 검색 API(지도 장소 검색, #160/#162) — Client ID/Secret 모두 SSM SecureString(valueFrom).
    { name = "NAVER_SEARCH_CLIENT_ID", valueFrom = module.secrets.param_arns["NAVER_SEARCH_CLIENT_ID"] },
    { name = "NAVER_SEARCH_CLIENT_SECRET", valueFrom = module.secrets.param_arns["NAVER_SEARCH_CLIENT_SECRET"] },
  ]
}

# ===== ECS Fargate =====
module "ecs" {
  source = "../../modules/prod/ecs"

  name_prefix             = local.name_prefix
  container_name          = "${local.name_prefix}-app"
  tags                    = local.common_tags
  aws_region              = var.aws_region
  app_subnet_ids          = module.network.app_subnet_ids
  security_group_ids      = [module.security.app_sg_id]
  target_group_arn        = module.alb.target_group_arn
  container_image         = local.container_image
  container_port          = var.app_port
  task_cpu                = var.task_cpu
  task_memory             = var.task_memory
  desired_count           = var.desired_count
  autoscaling_min         = var.autoscaling_min
  autoscaling_max         = var.autoscaling_max
  cpu_target              = var.cpu_target
  task_execution_role_arn = module.iam.task_execution_role_arn
  task_role_arn           = module.iam.task_role_arn
  container_environment   = local.container_environment
  container_secrets       = local.container_secrets
  log_retention_days      = var.log_retention_days
  health_check_path       = var.health_check_path

  # 리스너가 생성된 뒤 서비스가 타깃 그룹에 등록되도록.
  depends_on = [module.alb]
}

# ===== S3 + CloudFront (콘텐츠 이미지) =====
module "s3_cloudfront" {
  source = "../../modules/shared/s3-cloudfront"

  name_prefix = local.name_prefix
  tags        = local.common_tags
  bucket_name = var.images_bucket_name
  price_class = var.cloudfront_price_class

  # 커스텀 도메인(별칭 + us-east-1 ACM) — 필수. HTTPS·커스텀 도메인 강제.
  domain_aliases      = [var.cdn_domain_name]
  acm_certificate_arn = module.cdn_acm.certificate_arn
  route53_zone_id     = var.route53_zone_id
}

# ===== 모니터링 =====
module "monitoring" {
  source = "../../modules/prod/monitoring"

  name_prefix                = local.name_prefix
  tags                       = local.common_tags
  alarm_email                = var.alarm_email
  discord_webhook_url        = var.discord_webhook_url
  alb_arn_suffix             = module.alb.alb_arn_suffix
  target_group_arn_suffix    = module.alb.target_group_arn_suffix
  ecs_cluster_name           = module.ecs.cluster_name
  ecs_service_name           = module.ecs.service_name
  rds_instance_id            = module.rds.instance_id
  docdb_cluster_id           = module.documentdb.cluster_id
  redis_replication_group_id = module.elasticache.replication_group_id
}

# ===== Route53 alias (api 도메인 → ALB) =====
resource "aws_route53_record" "alb_alias" {
  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = module.alb.dns_name
    zone_id                = module.alb.zone_id
    evaluate_target_health = true
  }
}
