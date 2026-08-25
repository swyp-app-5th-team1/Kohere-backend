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

variable "naver_geocode_client_id" {
  description = "NCP Maps Geocoding API Client ID(도로명 주소 검색)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "naver_geocode_client_secret" {
  description = "NCP Maps Geocoding API Client Secret(도로명 주소 검색)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "kakao_rest_api_key" {
  description = "카카오 로컬 API REST API 키(인근 역 검색 · 등록 시 인근 대학 파생)"
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

variable "web_base_url" {
  description = "임대인 웹(SPA) 오리진 — 비밀번호 재설정 링크 base URL(앱 app.web.base-url, #272). dev는 Caddy가 한 도메인에서 API와 SPA를 함께 내므로 domain_name과 같은 값. 앱이 계정 복구를 켠 채 이 값이 비거나 형식이 틀리면 기동을 거부한다"
  type        = string
  default     = ""
}

variable "test_login_enabled" {
  description = "dev 테스트 마스터 로그인 기능(앱 app.auth.test-login.enabled) — 마스터 계정 시드 + 우회 로그인을 함께 켠다. 기본 false(공유 EC2 백도어)"
  type        = bool
  default     = false
}

variable "fixed_verification_enabled" {
  description = "앱스토어 심사 계정 고정 인증번호(앱 app.auth.fixed-verification.enabled, #180). 심사 기간에만 켠다. 기본 false"
  type        = bool
  default     = false
}

# 고정 인증번호와 심사 계정 식별자는 SecureString으로 저장한다 — 코드는 우회 자격이고, 계정·연락처는 실제 인물의 PII다.
variable "fixed_verification_code" {
  description = "심사 계정에 발급할 고정 인증번호(숫자, app.email/phone.code-length와 자릿수 일치). enabled=true인데 비면 앱 기동 실패"
  type        = string
  default     = ""
  sensitive   = true
}

variable "fixed_verification_tenant_google_emails" {
  description = "임차인 심사자의 Google 로그인 계정 이메일(콤마 구분). 이 계정들은 이메일 인증만 고정 인증번호를 받는다"
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
  description = "임대인 심사자의 Google 로그인 계정 이메일(콤마 구분). 이 계정들은 SMS 인증만 고정 인증번호를 받는다"
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
