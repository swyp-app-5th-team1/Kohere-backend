variable "project" {
  description = "프로젝트명(이름 접두사·태그)"
  type        = string
  default     = "kohere"
}

variable "environment" {
  description = "환경명(이름 접두사·태그)"
  type        = string
  default     = "dev"
}

variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "app_image" {
  description = "ECR 앱 이미지 URI(:tag 포함). 예: <account>.dkr.ecr.ap-northeast-2.amazonaws.com/kohere-backend:dev"
  type        = string
}

variable "instance_type" {
  description = "dev 호스트 EC2 타입(x86 — ECR 이미지 amd64). 기본 t3.small=2vCPU/2GB"
  type        = string
  default     = "t3.small"
}

variable "data_volume_size" {
  description = "데이터 EBS GB(mysql/mongo 영속, Redis 인메모리)"
  type        = number
  default     = 20
}

variable "ingress_cidrs" {
  description = "80/443 인바운드 허용 CIDR(dev 편의상 개방 가능)"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "db_name" {
  description = "MySQL·Mongo 논리 DB 이름"
  type        = string
  default     = "kohere"
}

# ----- DB 자격증명(SSM SecureString) + 외부 접속 (dev 한정) -----
variable "mysql_username" {
  description = "MySQL 앱 사용자명(앱이 이 계정으로 접속)"
  type        = string
  default     = "kohere"
}

variable "mysql_password" {
  description = "MySQL 앱 사용자(kohere) 비밀번호. 외부 노출 시 강한 값 권장"
  type        = string
  default     = "kohere"
  sensitive   = true
}

variable "mysql_root_password" {
  description = "MySQL root 비밀번호(외부 관리도구용)"
  type        = string
  default     = "kohere"
  sensitive   = true
}

variable "mongo_username" {
  description = "MongoDB root 사용자명(인증 활성화)"
  type        = string
  default     = "kohere"
}

variable "mongo_password" {
  description = "MongoDB root 비밀번호. URL-safe 값 권장(접속 URI에 포함)"
  type        = string
  default     = "kohere"
  sensitive   = true
}

variable "db_ingress_cidrs" {
  description = "DB 포트(3306·27017) 외부 접속 허용 CIDR. 빈 목록=미개방 — 본인 IP/32 권장"
  type        = list(string)
  default     = []
}

# ----- 노출 / HTTPS (필수 — HTTPS 강제) -----
variable "domain_name" {
  description = "dev 도메인(예: dev.kohere.app) — 필수(Route53 A + Caddy HTTPS)"
  type        = string
}

variable "route53_zone_id" {
  description = "Route53 호스팅 영역 ID — 필수"
  type        = string
}

variable "cdn_domain_name" {
  description = "콘텐츠 이미지 CDN 커스텀 도메인(예: cdn.dev.kohere.app) — 필수(us-east-1 ACM + CloudFront 별칭)"
  type        = string
}

# ----- 시크릿 (SSM Parameter Store SecureString — SM 미사용) -----
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

# Apple 인가코드 흐름(ADR-0031) — 탈퇴 시 /auth/revoke용 client_secret(ES256) 재료. 미설정 시 Apple 로그인만 미동작.
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
  description = "실 SMTP 호스트(dev — MailHog 없음)"
  type        = string
  default     = ""
}

variable "smtp_port" {
  description = "SMTP 포트"
  type        = number
  default     = 587
}

variable "smtp_username" {
  description = "SMTP 사용자명"
  type        = string
  default     = ""
  sensitive   = true
}

variable "smtp_password" {
  description = "SMTP 비밀번호"
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

# ----- 테스트 마스터 계정/로그인 (dev·local 전용, 운영 미사용) -----
variable "test_login_enabled" {
  description = "dev 테스트 마스터 로그인 기능(앱 app.auth.test-login.enabled) — 마스터 계정 시드 + 우회 로그인을 함께 켠다. 공유 EC2 백도어라 기본 false"
  type        = bool
  default     = false
}

# ----- 알람 / 이미지 -----
variable "discord_webhook_url" {
  description = "Discord 웹훅 URL — 채우면 CloudWatch 알람을 Discord로 통보(SNS→Lambda). 빈 값이면 미구성"
  type        = string
  default     = ""
  sensitive   = true
}

variable "images_bucket_name" {
  description = "콘텐츠 이미지 S3 버킷 이름 — 필수(S3는 전역 유일)"
  type        = string
}

# ----- CI/CD (GitHub Actions OIDC 배포 역할) -----
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
  description = "배포(CD)를 허용·트리거할 브랜치 — dev는 release 브랜치 머지 시 배포(deploy.yml on.push.branches 와 일치해야 함)"
  type        = string
  default     = "release"
}

variable "ecr_repository" {
  description = "푸시 대상 ECR 리포지토리 이름(prod와 공유)"
  type        = string
  default     = "kohere-backend"
}
