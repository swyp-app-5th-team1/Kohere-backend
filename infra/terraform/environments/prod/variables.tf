# ===== 공통 =====
variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project" {
  description = "프로젝트명(이름 접두사·태그)"
  type        = string
  default     = "kohere"
}

variable "environment" {
  description = "환경명(이름 접두사·태그)"
  type        = string
  default     = "prod"
}

# ===== 네트워크 =====
variable "vpc_cidr" {
  description = "VPC CIDR"
  type        = string
  default     = "10.0.0.0/16"
}

variable "az_count" {
  description = "사용할 AZ 수"
  type        = number
  default     = 2
}

variable "single_nat_gateway" {
  description = "NAT 게이트웨이 1개 공유(비용 절감). false면 AZ당 1개(HA)"
  type        = bool
  default     = true
}

variable "enable_interface_endpoints" {
  description = "ECR/Secrets/Logs/SSM 인터페이스 VPC 엔드포인트 생성"
  type        = bool
  default     = true
}

variable "app_port" {
  description = "앱 컨테이너 포트"
  type        = number
  default     = 8080
}

variable "alb_ingress_cidrs" {
  description = "ALB 인바운드 허용 CIDR"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "health_check_path" {
  description = "ALB 헬스 체크 경로"
  type        = string
  default     = "/actuator/health"
}

# ===== ECR / 이미지 =====
variable "ecr_repo_name" {
  description = "ECR 리포지토리 이름(bootstrap 이 생성한 공유 리포를 data 로 조회)"
  type        = string
  default     = "kohere-backend"
}

variable "app_image_tag" {
  description = "배포할 이미지 태그(초기 apply 시 — 이후 CD가 새 태스크 정의로 교체)"
  type        = string
  default     = "latest"
}

# ===== ECS =====
variable "task_cpu" {
  description = "태스크 CPU 단위"
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "태스크 메모리(MB)"
  type        = number
  default     = 1024
}

variable "desired_count" {
  description = "초기 태스크 수"
  type        = number
  default     = 2
}

variable "autoscaling_min" {
  description = "오토스케일링 최소"
  type        = number
  default     = 2
}

variable "autoscaling_max" {
  description = "오토스케일링 최대"
  type        = number
  default     = 6
}

variable "cpu_target" {
  description = "오토스케일링 CPU 목표(%)"
  type        = number
  default     = 60
}

variable "log_retention_days" {
  description = "CloudWatch 로그 보존일"
  type        = number
  default     = 30
}

# ===== RDS (MySQL) =====
variable "rds_engine_version" {
  description = "MySQL 엔진 버전"
  type        = string
  default     = "8.0"
}

variable "rds_instance_class" {
  description = "RDS 인스턴스 클래스"
  type        = string
  default     = "db.t4g.micro"
}

variable "rds_allocated_storage" {
  description = "RDS 초기 스토리지(GB)"
  type        = number
  default     = 20
}

variable "rds_max_allocated_storage" {
  description = "RDS 스토리지 오토스케일링 상한(GB)"
  type        = number
  default     = 100
}

variable "rds_multi_az" {
  description = "RDS Multi-AZ"
  type        = bool
  default     = false
}

variable "rds_backup_retention" {
  description = "RDS 백업 보존일"
  type        = number
  default     = 7
}

variable "rds_deletion_protection" {
  description = "RDS 삭제 보호"
  type        = bool
  default     = true
}

variable "db_name" {
  description = "데이터베이스 이름(MySQL·DocumentDB 논리 DB)"
  type        = string
  default     = "kohere"
}

variable "db_username" {
  description = "RDS 마스터 사용자명"
  type        = string
  default     = "kohere"
}

# ===== DocumentDB =====
variable "docdb_engine_version" {
  description = "DocumentDB 엔진 버전(API는 5.0.0 형식 요구)"
  type        = string
  default     = "5.0.0"
}

variable "docdb_instance_class" {
  description = "DocumentDB 인스턴스 클래스"
  type        = string
  default     = "db.t3.medium"
}

variable "docdb_instance_count" {
  description = "DocumentDB 인스턴스 수"
  type        = number
  default     = 1
}

variable "docdb_backup_retention" {
  description = "DocumentDB 백업 보존일"
  type        = number
  default     = 7
}

variable "docdb_deletion_protection" {
  description = "DocumentDB 삭제 보호"
  type        = bool
  default     = true
}

variable "docdb_username" {
  description = "DocumentDB 마스터 사용자명"
  type        = string
  default     = "kohere"
}

variable "docdb_tls" {
  description = "DocumentDB TLS(enabled/disabled)"
  type        = string
  default     = "enabled"
}

# ===== ElastiCache (Redis) =====
variable "redis_node_type" {
  description = "Redis 노드 타입"
  type        = string
  default     = "cache.t4g.micro"
}

variable "redis_engine_version" {
  description = "Redis 엔진 버전"
  type        = string
  default     = "7.1"
}

variable "redis_replicas" {
  description = "Redis 복제본 수"
  type        = number
  default     = 1
}

variable "redis_multi_az" {
  description = "Redis Multi-AZ 자동 장애조치"
  type        = bool
  default     = true
}

variable "redis_transit_encryption" {
  description = "Redis 전송 구간 암호화(true면 Spring SSL·auth token 필요)"
  type        = bool
  default     = false
}

variable "redis_maxmemory_policy" {
  description = "Redis maxmemory-policy"
  type        = string
  default     = "volatile-ttl"
}

# ===== 도메인 / TLS (필수 — HTTPS 강제) =====
variable "domain_name" {
  description = "API 도메인(예: api.kohere.app) — 필수(ALB HTTPS)"
  type        = string
}

variable "route53_zone_id" {
  description = "Route53 호스팅 영역 ID — 필수(ACM 검증·alias)"
  type        = string
}

# ===== CI/CD (GitHub Actions OIDC) =====
variable "github_org" {
  description = "배포 워크플로 GitHub 조직/사용자"
  type        = string
  default     = "swyp-app-5th-team1"
}

variable "github_repo" {
  description = "배포 워크플로 GitHub 리포지토리"
  type        = string
  default     = "Kohere-backend"
}

variable "github_deploy_branch" {
  description = "배포를 허용할 브랜치"
  type        = string
  default     = "main"
}

# ===== S3 / CloudFront =====
variable "images_bucket_name" {
  description = "콘텐츠 이미지 버킷 이름 — 필수(S3는 전역 유일)"
  type        = string
}

variable "cloudfront_price_class" {
  description = "CloudFront 가격 등급"
  type        = string
  default     = "PriceClass_200"
}

variable "cdn_domain_name" {
  description = "콘텐츠 이미지 CDN 커스텀 도메인(예: cdn.kohere.app) — 필수(us-east-1 ACM + CloudFront 별칭)"
  type        = string
}

# ===== 모니터링 =====
variable "alarm_email" {
  description = "CloudWatch 알람 이메일 구독(빈 값이면 미구독). Discord와 병행 가능"
  type        = string
  default     = ""
}

variable "discord_webhook_url" {
  description = "Discord 웹훅 URL — 채우면 CloudWatch 알람을 Discord로 통보(SNS→Lambda, ADR-0027). 빈 값이면 미구성"
  type        = string
  default     = ""
  sensitive   = true
}

# ===== 외부 발급 시크릿/설정 (운영 전 채워야 함) =====
variable "google_client_id" {
  description = "Google OIDC audience"
  type        = string
  default     = ""
  sensitive   = true
}

variable "apple_client_id" {
  description = "Apple OIDC audience"
  type        = string
  default     = ""
  sensitive   = true
}

# Apple 인가코드 흐름(ADR-0031) — 탈퇴 시 /auth/revoke용 client_secret(ES256) 재료. 운영 전 채워야 동작.
variable "apple_team_id" {
  description = "Apple Team ID(client_secret JWT iss)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "apple_key_id" {
  description = "Apple Key ID(.p8 kid)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "apple_private_key" {
  description = "Apple .p8 개인키(PKCS#8 PEM) — ES256 client_secret 서명"
  type        = string
  default     = ""
  sensitive   = true
}

variable "smtp_host" {
  description = "운영 SMTP 호스트(예: email-smtp.ap-northeast-2.amazonaws.com)"
  type        = string
  default     = ""
}

variable "smtp_port" {
  description = "운영 SMTP 포트"
  type        = number
  default     = 587
}

variable "smtp_username" {
  description = "운영 SMTP 사용자명"
  type        = string
  default     = ""
  sensitive   = true
}

variable "smtp_password" {
  description = "운영 SMTP 비밀번호"
  type        = string
  default     = ""
  sensitive   = true
}

variable "mail_from" {
  description = "발신 이메일 주소"
  type        = string
  default     = "noreply@kohere.app"
}

# ----- 연락처 SMS (SOLAPI 국내 SMS API — 임대인 인증, ADR-0034) -----
variable "solapi_enabled" {
  description = "SOLAPI 실 발송 활성화(앱 app.solapi.enabled). false면 로깅 폴백"
  type        = bool
  default     = false
}

variable "solapi_from" {
  description = "SOLAPI 발신번호(사전 등록). 비밀 아님 — 평문 env"
  type        = string
  default     = ""
}

variable "solapi_api_key" {
  description = "SOLAPI API Key — SSM SecureString으로 저장·주입"
  type        = string
  default     = ""
  sensitive   = true
}

variable "solapi_api_secret" {
  description = "SOLAPI API Secret — SSM SecureString으로 저장·주입"
  type        = string
  default     = ""
  sensitive   = true
}

# ----- 사업자번호 검증 (비즈노 — 임대인 인증, ADR-0033) -----
variable "bizno_enabled" {
  description = "비즈노 실 검증 활성화(앱 app.bizno.enabled). false면 StubBusinessRegistryVerifier 폴백"
  type        = bool
  default     = false
}

variable "bizno_api_key" {
  description = "비즈노 API Key — SSM SecureString으로 저장·주입"
  type        = string
  default     = ""
  sensitive   = true
}

# ----- 네이버 지역 검색 API (지도 장소 검색 — #160/#162) -----
variable "naver_search_client_id" {
  description = "네이버 지역 검색 API Client ID — SSM SecureString으로 저장·주입. 미설정 시 앱은 기동하고 장소 검색만 502"
  type        = string
  default     = ""
  sensitive   = true
}

variable "naver_search_client_secret" {
  description = "네이버 지역 검색 API Client Secret — SSM SecureString으로 저장·주입"
  type        = string
  default     = ""
  sensitive   = true
}
