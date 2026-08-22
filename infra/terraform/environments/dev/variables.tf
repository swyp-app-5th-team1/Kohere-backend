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

# ----- Google Cloud Translation + AWS Workload Identity Federation -----
variable "google_cloud_project_id" {
  description = "Cloud Translation API와 WIF를 구성할 Google Cloud 프로젝트 ID"
  type        = string
}

variable "google_translation_service_account_email" {
  description = "dev EC2가 WIF로 가장할 기존 Google Translation 서비스 계정 이메일"
  type        = string

  validation {
    condition     = endswith(var.google_translation_service_account_email, ".iam.gserviceaccount.com")
    error_message = "google_translation_service_account_email에는 ...iam.gserviceaccount.com 형식의 서비스 계정 이메일을 입력해야 합니다."
  }
}

variable "chat_translation_enabled" {
  description = "dev 앱의 1:1 채팅 Google 자동 번역 활성화 여부"
  type        = bool
  default     = true
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

# ----- 로그 반출 (ADR-0038 롤아웃 ⑥) -----
variable "log_retention_days" {
  description = "앱 로그 CloudWatch 보존 기간(일). 무기한 금지 — 방치하면 비용이 선형으로 누적된다(ADR-0038)"
  type        = number
  default     = 30
}

variable "enable_cloudwatch_agent" {
  description = <<-EOT
    CloudWatch Agent로 /opt/kohere/logs/app.json을 반출할지. 기본 true —
    ADR-0038의 도입 게이트("여유 300MB 미만이면 보류")를 dev 호스트 실측으로 통과했다:
    available 656Mi, 스왑 2Gi 중 29Mi만 사용(= 메모리 압박 없음). Agent 상주는 ~50-100MB다.
    판단은 free가 아니라 available로 한다 — free가 낮은 것은 회수 가능한 페이지 캐시 때문이다.
    메모리·스왑은 자동 수집되지 않으므로(이 Agent는 logs 전용) 확인은 SSM 접속 후 free -m 으로 한다.
    끄면 Log Group·IAM 권한은 그대로 두고 반출만 멈춘다.
  EOT
  type        = bool
  default     = true
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

variable "naver_geocode_client_id" {
  description = "NCP Maps Geocoding API Client ID — SSM SecureString으로 저장·주입. 미설정 시 앱은 기동하고 주소 검색만 502"
  type        = string
  default     = ""
  sensitive   = true
}

variable "naver_geocode_client_secret" {
  description = "NCP Maps Geocoding API Client Secret — SSM SecureString으로 저장·주입"
  type        = string
  default     = ""
  sensitive   = true
}

variable "kakao_rest_api_key" {
  description = "카카오 로컬 API REST API 키 — SSM SecureString으로 저장·주입. 미설정 시 앱은 기동하고 인근 역 검색만 502, 등록의 인근 대학은 빈 배열"
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

# ----- 앱스토어 심사 계정 고정 인증번호 (dev 전용, 운영 미사용 — #180) -----
# 심사자가 실제 SMS·이메일을 수신할 수 없어, 등록된 심사 계정에 한해 인증번호를 고정값으로 발급하고 외부 발송을 생략한다.
# 역할별로 채널이 갈린다 — tenant 계정은 이메일 인증만, landlord 계정은 SMS 인증만 고정 인증번호를 받고
# 반대 채널·미등록 값은 실제 발송 없이 거절된다. 그룹은 통째로 비거나 통째로 채워야 하며(반쪽이면 앱 기동 실패),
# 같은 Google 계정을 양쪽에 넣으면 역할이 모호해 기동이 막힌다. 한쪽 트랙만 쓰는 운용은 허용.
variable "fixed_verification_enabled" {
  description = "심사 계정 고정 인증번호 활성화(앱 app.auth.fixed-verification.enabled). 심사 기간에만 켠다. 기본 false"
  type        = bool
  default     = false
}

variable "fixed_verification_code" {
  description = "심사 계정에 발급할 고정 인증번호(숫자, app.email/phone.code-length와 자릿수 일치). enabled=true인데 비면 앱 기동 실패"
  type        = string
  default     = ""
  sensitive   = true
}

variable "fixed_verification_tenant_google_emails" {
  description = "임차인 심사자의 Google 로그인 계정 이메일(콤마 구분) — 이메일 인증만 허용"
  type        = string
  default     = ""
  sensitive   = true
}

variable "fixed_verification_tenant_emails" {
  description = "임차인 심사에서 고정 인증번호가 적용되는 인증 대상 이메일(콤마 구분)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "fixed_verification_landlord_google_emails" {
  description = "임대인 심사자의 Google 로그인 계정 이메일(콤마 구분) — SMS 인증만 허용"
  type        = string
  default     = ""
  sensitive   = true
}

variable "fixed_verification_landlord_phones" {
  description = "임대인 심사에서 고정 인증번호가 적용되는 인증 대상 휴대폰 번호(콤마 구분)"
  type        = string
  default     = ""
  sensitive   = true
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

# ----- 임대인 웹(SPA) 정적 서빙 · 프론트 CI/CD (#232) -----
variable "web_artifacts_bucket_name" {
  description = "프론트 릴리스 아티팩트 S3 버킷 이름(전역 유일). 예: kohere-dev-web-artifacts"
  type        = string
}

variable "web_release_retention_days" {
  description = <<-EOT
    releases/ 보관 일수. 정하는 것은 "롤백 가능 여부"가 아니라 즉시·동일 롤백이 보장되는 창이다 —
    만료된 SHA로 롤백하면 워크플로가 그 커밋을 재빌드해 복원한다(느리고 바이트 동일 보장 없음).
    줄이면 초과 나이의 릴리스가 실제로 삭제되어 그만큼 즉시 롤백 창이 사라진다.
  EOT
  type        = number
  default     = 90
}

variable "github_web_repo" {
  description = <<-EOT
    프론트엔드 GitHub 리포지토리 이름. 배포 역할의 신뢰 조건(sub)에 리터럴로 박히므로 반드시 실제 값이어야 한다 —
    기본값을 두지 않는 이유는, 틀린 값으로도 apply 가 성공해 아무도 맡을 수 없는 역할이 조용히 생기기 때문이다.
    나중에 바꿔도 신뢰 정책만 in-place 갱신되고 역할 ARN은 그대로다.
  EOT
  type        = string
}

variable "github_org_id" {
  description = <<-EOT
    GitHub 조직의 숫자 ID(예: `gh api orgs/<org> --jq .id`). immutable subject claim 형식
    `repo:<org>@<orgId>/<repo>@<repoId>:...` 를 조립하는 데 쓴다 — 이름이 아니라 ID로 고정되므로
    레포·조직 이름을 바꿔도 신뢰가 다른 대상으로 옮겨가지 않는다.
  EOT
  type        = string
}

variable "github_web_repo_id" {
  description = "프론트 레포의 숫자 ID(`gh api repos/<org>/<repo> --jq .id`). 위와 같은 용도"
  type        = string
}

variable "github_web_deploy_branch" {
  description = "프론트 배포를 허용·트리거할 브랜치(프론트 deploy.yml 의 on.push.branches 와 일치해야 함)"
  type        = string
  default     = "release"
}
