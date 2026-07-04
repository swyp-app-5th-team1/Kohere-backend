variable "name_prefix" {
  description = "리소스 이름 접두사. SSM 파라미터 접두사(/<name_prefix>) 기준"
  type        = string
}

variable "tags" {
  description = "공통 태그"
  type        = map(string)
  default     = {}
}

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

variable "apple_team_id" {
  description = "Apple Team ID (client_secret JWT의 iss, ADR-0031)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "apple_key_id" {
  description = "Apple Key ID (.p8 키 식별자, client_secret JWT 헤더 kid)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "apple_private_key" {
  description = "Apple .p8 개인키(PKCS#8 PEM) — ES256 client_secret 서명용"
  type        = string
  default     = ""
  sensitive   = true
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

variable "mysql_password" {
  description = "MySQL 앱 사용자 비밀번호"
  type        = string
  default     = "kohere"
  sensitive   = true
}

variable "mysql_root_password" {
  description = "MySQL root 비밀번호"
  type        = string
  default     = "kohere"
  sensitive   = true
}

variable "mongo_password" {
  description = "MongoDB root 비밀번호"
  type        = string
  default     = "kohere"
  sensitive   = true
}

variable "solapi_api_key" {
  description = "SOLAPI API Key"
  type        = string
  default     = ""
  sensitive   = true
}

variable "solapi_api_secret" {
  description = "SOLAPI API Secret"
  type        = string
  default     = ""
  sensitive   = true
}

variable "bizno_api_key" {
  description = "비즈노 API Key"
  type        = string
  default     = ""
  sensitive   = true
}

# ----- 앱 런타임 토글·설정(비밀 아님) — SSM String으로 저장해 refresh-env가 .env로 회수(재배포만으로 반영, ADR-0024) -----
variable "solapi_enabled" {
  description = "SOLAPI 실 발송 활성화(앱 app.solapi.enabled). false면 로깅 폴백"
  type        = bool
  default     = false
}

variable "solapi_from" {
  description = "SOLAPI 발신번호(사전 등록). 비밀 아님"
  type        = string
  default     = ""
}

variable "bizno_enabled" {
  description = "비즈노 실 검증 활성화(앱 app.bizno.enabled). false면 스텁 폴백"
  type        = bool
  default     = false
}

variable "test_login_enabled" {
  description = "dev 테스트 마스터 로그인 기능(앱 app.auth.test-login.enabled) — 마스터 계정 시드 + 우회 로그인을 함께 켠다. 기본 false(공유 EC2 백도어)"
  type        = bool
  default     = false
}
