variable "name_prefix" {
  description = "리소스 이름 접두사. SSM 파라미터 접두사(/<name_prefix>) 기준"
  type        = string
}

variable "tags" {
  description = "공통 태그"
  type        = map(string)
  default     = {}
}

variable "aws_region" {
  description = "AWS 리전 (ECR·SSM)"
  type        = string
}

variable "account_id" {
  description = "AWS 계정 ID (ECR 레지스트리 호스트)"
  type        = string
}

# ----- 인프라 주입(다른 dev 모듈 출력) -----
variable "subnet_id" {
  description = "배치할 public subnet ID(network 모듈)"
  type        = string
}

variable "security_group_id" {
  description = "호스트 보안 그룹 ID(security 모듈)"
  type        = string
}

variable "instance_profile_name" {
  description = "인스턴스 프로파일 이름(iam 모듈)"
  type        = string
}

variable "volume_id" {
  description = "attach할 데이터 EBS 볼륨 ID(storage 모듈)"
  type        = string
}

# ----- 인스턴스 -----
variable "instance_type" {
  description = "EC2 인스턴스 타입. x86 — ECR 앱 이미지가 amd64. 기본 t3.small=2vCPU/2GB"
  type        = string
  default     = "t3.small"
}

# ----- 앱 / compose 렌더 -----
variable "app_image" {
  description = "ECR 앱 이미지 URI(:tag 포함). CI가 push한 dev 이미지"
  type        = string
}

variable "db_name" {
  description = "MySQL·Mongo 논리 DB 이름"
  type        = string
  default     = "kohere"
}

variable "domain_name" {
  description = "dev 도메인(Caddy 자동 HTTPS) — 필수"
  type        = string
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

variable "mail_from" {
  description = "발신 이메일 주소"
  type        = string
  default     = "noreply@kohere.app"
}

variable "mysql_username" {
  description = "MySQL 앱 사용자명(compose·reconcile)"
  type        = string
  default     = "kohere"
}

variable "mongo_username" {
  description = "MongoDB root 사용자명(compose·reconcile)"
  type        = string
  default     = "kohere"
}

# ----- 콘텐츠 이미지(S3 + CloudFront, shared 모듈 출력) -----
variable "images_bucket" {
  description = "콘텐츠 이미지 S3 버킷 이름(앱 env)"
  type        = string
  default     = ""
}

variable "images_cdn_domain" {
  description = "CloudFront 도메인(이미지 URL 베이스, 앱 env)"
  type        = string
  default     = ""
}

# ----- Google Cloud Translation WIF -----
variable "google_wif_credential_configuration" {
  description = "EC2 앱이 ADC로 읽을 비밀키 없는 Google external_account 설정 JSON"
  type        = string
}

variable "chat_translation_enabled" {
  description = "앱의 채팅 자동 번역 활성화 여부"
  type        = bool
}

variable "chat_translation_project_id" {
  description = "Cloud Translation API 호출 대상 Google Cloud 프로젝트 ID"
  type        = string
}

variable "chat_translation_location" {
  description = "Cloud Translation API location"
  type        = string
  default     = "global"
}

# ----- 로그 반출 (ADR-0038 롤아웃 ⑥) -----
variable "log_group_name" {
  description = "CloudWatch Agent가 /opt/kohere/logs/app.json을 실어 보낼 Log Group 이름(logs 모듈)"
  type        = string
  default     = ""
}

variable "enable_cloudwatch_agent" {
  description = <<-EOT
    CloudWatch Agent 설치·기동 여부. ADR-0038의 도입 게이트("여유 300MB 미만이면 보류")를
    dev 호스트 실측으로 통과해 기본 true다 — available 656Mi(스왑 2Gi 중 29Mi만 사용).
    판단 기준은 free가 아니라 available이다. free가 낮은 것은 커널이 남는 RAM을 페이지 캐시로
    쓰기 때문이고 그 캐시는 회수 가능하다. Agent 상주는 ~50-100MB다.
    끄면 Log Group·IAM 권한·로그 파일은 그대로 두고 반출만 멈춘다.
  EOT
  type        = bool
  default     = true
}

# ----- 임대인 웹 릴리스 (web 모듈 출력, #232) -----
variable "web_artifacts_bucket" {
  description = <<-EOT
    프론트 릴리스 아티팩트 S3 버킷 이름(web 모듈). deploy-web.sh 가 releases/<sha> 를 내려받고
    current.txt 포인터를 갱신하며, 부팅 시 그 포인터로 마지막 릴리스를 복원한다.
  EOT
  type        = string
}
