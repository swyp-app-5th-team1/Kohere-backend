variable "name_prefix" {
  description = "리소스 이름 접두사"
  type        = string
}

variable "tags" {
  description = "공통 태그"
  type        = map(string)
  default     = {}
}

# 외부에서 받아야 하는 값(생성 불가) — 비워두면 빈 문자열로 저장되고, 운영 전 콘솔/tfvars로 채워야 한다.
variable "google_client_id" {
  description = "Google OIDC audience(우리 client ID)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "apple_client_id" {
  description = "Apple OIDC audience(우리 client ID)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "apple_team_id" {
  description = "Apple Team ID(client_secret JWT iss, ADR-0031)"
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

variable "smtp_username" {
  description = "운영 SMTP 사용자명(SES 등)"
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

variable "naver_search_client_id" {
  description = "네이버 지역 검색 API Client ID"
  type        = string
  default     = ""
  sensitive   = true
}

variable "naver_search_client_secret" {
  description = "네이버 지역 검색 API Client Secret"
  type        = string
  default     = ""
  sensitive   = true
}

variable "recovery_window_days" {
  description = "Secrets Manager 삭제 복구 대기일"
  type        = number
  default     = 7
}
