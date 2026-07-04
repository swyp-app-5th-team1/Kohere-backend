# 소셜 로그인 · 온보딩 API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

## 개요

소셜 로그인(Apple/Google) 검증 후 서버 자체 JWT(access+refresh)를 발급하고, 신규 회원의 **온보딩 중 본인 확인**(세입자 이메일 인증 · 임대인 연락처 SMS 인증 — 인증번호 발송·확인), 온보딩 필수정보 수집·약관 동의, 토큰 재발급/로그아웃, 회원 탈퇴, 내 프로필 조회·수정을 다룬다. 인증 헤더는 `Authorization: Bearer <accessToken>`, 토큰 갱신은 `POST /api/v1/auth/reissue`다.

상태 모델: 사용자는 `PENDING`(소셜 검증만 완료) → `TERMS_AGREED`(약관 동의 완료) → `ACTIVE`(온보딩 완료) → `WITHDRAWN`(탈퇴)로 전이한다. **약관 동의와 온보딩은 분리된 단계**로, 약관 동의(`POST /auth/terms`)가 온보딩 제출(`POST /auth/onboarding`)을 선행한다.

### 임대인 트랙

사용자는 **세입자(`TENANT`, 외국인)** 와 **임대인(`LANDLORD`)** 두 역할로 나뉜다. **소셜 로그인·약관 동의까지는 두 역할이 공통 흐름**이고, **이후 본인 확인·온보딩 단계에서 분기**한다 — 세입자는 이메일 인증(§3·§4) 후 `POST /auth/onboarding`(§5), 임대인은 연락처 SMS 인증(§4-1·§4-2) 후 `POST /auth/landlord/onboarding`(§5-2)으로 제출한다. **임대인 온보딩은 약관 동의 + 연락처(SMS) 인증만으로 완료**되며, 사업자등록번호는 온보딩 제출에 포함하지 않는다 — 온보딩을 마친(ACTIVE) 임대인이 나중에(매물 등록 시점) 별도 검증 API(§5-1)로 검증한다. **`userType`은 온보딩 제출 엔드포인트로 확정되고 이후 불변**이다(소셜 로그인·약관 단계에서는 미확정). **임대인은 이메일을 수집하지 않고 본인 확인을 연락처(휴대폰) SMS 인증으로 한다**([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)). 관련 유저 스토리: US-1-8(사업자번호 검증)·US-1-9(임대인 온보딩)·US-1-10(임대인 연락처 인증).

### 핵심 개념·enum

| 개념 | 값 | 설명 |
| --- | --- | --- |
| 사용자 상태 `status` | `PENDING`, `TERMS_AGREED`, `ACTIVE`, `WITHDRAWN` | 소셜 검증만 완료 → 약관 동의 완료 → 온보딩 완료 → 탈퇴 |
| provider | `APPLE`, `GOOGLE` | 소셜 로그인 제공자 |
| 성별 `gender` | `MALE`, `FEMALE` | 온보딩 필수 |
| 직업 `occupation` | `UNDERGRADUATE_STUDENT`(학부생), `GRADUATE_STUDENT`(대학원생), `EXCHANGE_STUDENT`(교환학생), `EDUCATION_ACADEMIC_RESEARCH`(교육/학술 연구), `IT_SOFTWARE_ENGINEERING`(IT/소프트웨어 엔지니어링), `DEVELOPER`(개발자), `DESIGNER`(디자이너) | 온보딩 필수 · 요구사항 확정값(#93) |
| 비자정보 `visaType` | `DIPLOMATIC_OFFICIAL_A-1_A-2`(외교·공무), `VISA_EXEMPTED_B`(사증면제), `JOURNALISM_RELIGIOUS_AFFAIRS_C-1_D-5_D-6`(취재·종교), `SHORT_TERM_VISIT_C-2_C-3`(단기방문), `STUDY_D-2`(유학), `TRAINEE_D-3_D-4`(연수), `INTRA_COMPANY_TRANSFER_D-7`(주재), `PROFESSIONAL_C-4_D-1_D-8_D-9_D-10_E-1_E-2_E-3_E-4_E-5_E-6_E-7`(전문인력), `NON_PROFESSIONAL_E-8_E-9_E-10`(비전문취업), `WORKING_HOLIDAY_H-1`(워킹홀리데이), `WORK_AND_VISIT_H-2`(방문취업), `FAMILY_VISITOR_DEPENDENT_F-1_F-2_F-3`(방문동거·거주·동반), `OVERSEAS_KOREAN_F-4`(재외동포), `PERMANENT_RESIDENCE_F-5`(영주), `MARRIAGE_MIGRANT_F-6`(결혼이민), `OTHERS_G-1`(기타) | 온보딩 필수 · 요구사항 확정값(#93). 값=상수명_체류자격코드(하이픈 포함) |
| 국적 `country` | ISO 3166-1 alpha-2 코드(예: `VN`) | 온보딩 필수 · 클라이언트는 국가만 전송, 표시명·국기는 서버가 `countries` 참조로 확보(응답에 `countryName`·`countryFlag` 포함, **`countryFlag`는 국기 이미지 URL**) |
| 이메일 `email` | 이메일 문자열 | **세입자** 온보딩 필수 · 인증번호로 사전 검증(§3·§4). 임대인은 미수집([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)) |
| 닉네임 `nickname` | `형용사 + 사물` 문자열 | 서버가 자동 배정(사용자 입력·수정 불가), 전역 유니크 |
| 사용자 역할 `userType` | `TENANT`(세입자·외국인), `LANDLORD`(임대인) | 온보딩 제출 엔드포인트(세입자 `/auth/onboarding` · 임대인 `/auth/landlord/onboarding`)로 확정·이후 불변. 소셜·약관 단계에서는 미확정 |
| 이름 `name` | 문자열 | **임대인 온보딩 필수** · 성·이름을 합친 단일 이름(세입자의 `firstName`/`lastName`과 구분). 빈 문자열 불가. API 필드명은 `name`을 유지하되 **저장은 세입자와 동일한 `FullName` VO에 보관**(`FullName.firstName`에 전체 이름, `lastName`은 미사용·null)해 **별도 `name` 컬럼 없이 `first_name`을 재사용**한다(서버가 `name`↔`FullName.firstName` 매핑) |
| 연락처 `phoneNumber` | 전화번호 문자열 | **임대인 온보딩 필수** · SMS 인증번호로 사전 검증(§4-1·§4-2) 필요. 응답·로그 마스킹(예 `010-****-5678`) |
| 사업자등록번호 `businessRegistrationNumber` | 숫자 10자리 문자열 | **임대인 전용** · **온보딩 제출에는 미포함**(온보딩은 약관·연락처 인증만으로 완료). 온보딩 후 매물 등록 시점에 별도 검증 API(§5-1)로 무상태 검증한다. 응답·로그 마스킹 |

- 날짜만 표기는 `YYYY-MM-DD`(예: `birthDate`), 시각은 ISO-8601 UTC(예: `2026-06-15T08:30:00Z`).
- enum은 모두 UPPER_SNAKE_CASE 문자열로 노출한다.
- **민감정보(토큰 원문·인증번호 원문·비자정보·이메일)는 로그·타 사용자 노출 시 마스킹**한다(error-response-guide §6). 본인 `GET /users/me`는 이메일을 평문으로 반환한다.
- **토큰 모델**: `accessToken`은 **JWT**(stateless — 매 요청 서명·만료를 검증, 저장 안 함). `refreshToken`은 **불투명(opaque) 랜덤 토큰**으로 발급하고 서버 저장소에 **해시로 보관**한다(회전·재사용 탐지·무효화 목적). 예시의 `rt_…`는 불투명 토큰을, `eyJ…`는 JWT를 나타낸다.

---

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/social-login` | 소셜 자격 검증 후 서버 JWT 발급(기존 로그인/신규 온보딩 분기) — Google은 `idToken`, **Apple은 `authorizationCode`**([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)) | 불필요 | 200 |
| POST | `/api/v1/auth/terms` | 약관 동의 제출(이용약관·개인정보처리방침·마케팅), 약관 동의 완료(TERMS_AGREED 전이) | 필수(온보딩 토큰) | 200 |
| POST | `/api/v1/auth/email/verification-code` | 온보딩 중 입력 이메일로 인증번호 발송(세입자) | 필수(온보딩 토큰) | 200 |
| POST | `/api/v1/auth/email/verify` | 인증번호 확인 → 이메일 검증 완료 처리(세입자) | 필수(온보딩 토큰) | 200 |
| POST | `/api/v1/auth/phone/verification-code` | 연락처로 SMS 인증번호 발송(임대인 전용) — 온보딩(US-1-10)·프로필 변경(US-1-5) 공용 | 필수(온보딩 토큰/정식 토큰) | 200 |
| POST | `/api/v1/auth/phone/verify` | 인증번호 확인 → 연락처 검증 완료 처리(임대인 전용) — 온보딩·프로필 변경 공용 | 필수(온보딩 토큰/정식 토큰) | 200 |
| POST | `/api/v1/auth/onboarding` | 세입자 온보딩 필수정보 제출(약관 동의·이메일 검증 선행), 가입 완료(ACTIVE 전이) | 필수(온보딩 토큰, TERMS_AGREED) | 200 |
| POST | `/api/v1/auth/business/verify` | 사업자등록번호 외부 검증(임대인 전용·온보딩 완료 후 무상태 검증), 결과 미저장·응답 body에만 반환 | 필수(정식 토큰(ACTIVE, ROLE_USER)) | 200 |
| POST | `/api/v1/auth/landlord/onboarding` | 임대인 온보딩 제출(약관·연락처 인증 선행), 가입 완료(ACTIVE 전이 + userType=LANDLORD 확정) | 필수(온보딩 토큰, TERMS_AGREED) | 200 |
| POST | `/api/v1/auth/reissue` | refresh 토큰으로 access 토큰 재발급 | 불필요(본문 refresh) | 200 |
| POST | `/api/v1/auth/logout` | 현재 세션 refresh 토큰 무효화 | 필수 | 204 |
| GET | `/api/v1/users/me` | 내 프로필 조회 | 필수 | 200 |
| PATCH | `/api/v1/users/me` | 내 프로필 부분 수정 | 필수 | 200 |
| DELETE | `/api/v1/users/me` | 회원 탈퇴(WITHDRAWN 전이, 토큰 일괄 무효화) | 필수 | 204 |

> `auth/onboarding`은 신규 리소스 생성이 아니라 약관 동의를 마친 `TERMS_AGREED` 사용자를 `ACTIVE`로 전이하는 상태 액션이므로 `200`을 쓴다(api-design-guide §1 — "생성 아닌 액션").
> 인증 "필수" 엔드포인트는 access 토큰 만료 시 `401 TOKEN_EXPIRED`로 재발급을 유도한다. **온보딩 토큰**(`ROLE_ONBOARDING` — `onboardingCompleted=false`, 상태 `PENDING`/`TERMS_AGREED` 공통)으로 `GET`/`PATCH /users/me`·`POST /auth/logout`(모두 `ROLE_USER` 필요) 보호 API에 접근하면 `403 AUTH_ONBOARDING_REQUIRED`를 반환한다(단, `DELETE /users/me`(탈퇴)·`POST /auth/terms`(약관 동의)·`POST /auth/email/verification-code`·`POST /auth/email/verify`(세입자 이메일 인증)·`POST /auth/phone/verification-code`·`POST /auth/phone/verify`(임대인 연락처 인증)·`POST /auth/onboarding`·`POST /auth/landlord/onboarding`(임대인 온보딩)은 온보딩 흐름이라 온보딩 토큰도 허용). 단 `/auth/phone/**`(연락처 인증)는 프로필 연락처 변경(US-1-5)을 위해 **정식 토큰(`ROLE_USER`)도 함께 허용**한다(온보딩 토큰·정식 토큰 양쪽 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) §6·§8). 반대로 `POST /auth/business/verify`(사업자번호 검증)는 온보딩 흐름이 아니라 **온보딩을 완료한(ACTIVE) 임대인이 정식 토큰(`ROLE_USER`)으로만 호출**하는 무상태 검증 API로, 온보딩 토큰으로 접근하면 `403 AUTH_ONBOARDING_REQUIRED`다(§5-1). 상태 전이 순서는 `POST /auth/terms`(PENDING→TERMS_AGREED) → `POST /auth/onboarding`(TERMS_AGREED→ACTIVE)이며, 약관 미동의 상태(`PENDING`)에서 온보딩을 제출하면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`다.

---

## 상세

### 1. POST `/api/v1/auth/social-login` — 소셜 로그인/온보딩 분기

앱이 provider에서 받은 자격을 서버가 검증한다 — **Google은 `idToken`** 을 서명·`aud`·`iss`·`exp`로 검증하고, **Apple은 `authorizationCode`** 를 `POST https://appleid.apple.com/auth/token`에서 교환해 받은 `id_token`을 같은 방식으로 검증한 뒤 신원(`sub`·`email`)을 얻는다. Apple은 교환으로 받은 `refresh_token`을 저장해 **탈퇴 시 토큰 폐기**(§10)에 사용한다([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)). 기존 `ACTIVE` 회원이면 로그인 처리하고 access+refresh 토큰을 발급한다(`status=ACTIVE`, `onboardingRequired=false`). 신규이거나 **가입을 끝내지 못한 회원(`PENDING`·`TERMS_AGREED`)** 이면 온보딩 전용 access 토큰(`onboardingCompleted=false` 클레임)과 `onboardingRequired=true`로 응답한다(refresh 토큰은 발급하지 않음). 신규면 `PENDING` 레코드를 새로 만든다.

응답의 **`status`로 클라이언트가 다음 화면을 분기**한다 — `PENDING`(소셜 로그인만 하고 약관 미동의)이면 **약관 동의 화면(§2)**, `TERMS_AGREED`(약관 동의했으나 온보딩 미완료)이면 **온보딩 화면(§5)**, `ACTIVE`이면 홈. 온보딩 토큰으로는 `GET /users/me`(ROLE_USER)가 `403`이라 상태를 따로 조회할 수 없으므로, 재개 지점은 이 응답의 `status`로 판단한다.

- **인증**: 불필요.
- Path/Query 파라미터: 없음.

#### Request Body

provider별로 **자격 필드 하나**를 채운다 — Google은 `idToken`, Apple은 `authorizationCode`(둘 다 단일 엔드포인트·동일 응답, [ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md) A안).

```json
// Google
{
  "provider": "GOOGLE",
  "idToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6..."
}
```

```json
// Apple
{
  "provider": "APPLE",
  "authorizationCode": "c1a2b3..."
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `provider` | string(enum) | 필수 | `APPLE` \| `GOOGLE` 중 하나(누락은 `INVALID_INPUT`, 허용 외 값은 역직렬화 실패로 `MALFORMED_REQUEST`) |
| `idToken` | string | provider별 | **Google 필수**. Google 발급 OIDC ID 토큰. Apple은 사용하지 않음 |
| `authorizationCode` | string | provider별 | **Apple 필수**. `ASAuthorizationAppleIDCredential.authorizationCode`(UTF-8 디코드한 문자열, 1회용·약 5분). Google은 사용하지 않음 |

> 필수 여부가 provider에 따라 달라(`idToken`↔`authorizationCode`) Bean Validation 대신 **application 계층에서 검증**한다 — 해당 provider의 자격 필드가 비어 있으면 `400 AUTH_MISSING_CREDENTIAL`. Apple `authorizationCode`는 1회용이므로 서버가 즉시 교환한다(재사용 시 `401 AUTH_INVALID_SOCIAL_TOKEN`).

#### 성공 Response — 기존 회원(ACTIVE) (200 OK)

```json
{
  "success": true,
  "data": {
    "onboardingRequired": false,
    "status": "ACTIVE",
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2",
    "expiresIn": 3600
  },
  "error": null
}
```

#### 성공 Response — 신규·미완료 회원(PENDING·TERMS_AGREED) (200 OK)

```json
{
  "success": true,
  "data": {
    "onboardingRequired": true,
    "status": "PENDING",
    "tokenType": "Bearer",
    "accessToken": "eyJ...onboarding-scope",
    "refreshToken": null,
    "expiresIn": 1800
  },
  "error": null
}
```

> 신규 가입과 약관 미동의 상태로 재로그인한 회원은 `status="PENDING"`(→ 약관 동의 화면 §2). 약관까지 동의하고 온보딩만 못 끝낸 채 재로그인한 회원은 같은 형태로 `status="TERMS_AGREED"`(→ 온보딩 화면 §5)를 받는다. 두 경우 모두 `onboardingRequired=true`·`refreshToken=null`이다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `onboardingRequired` | boolean | 가입 미완료 여부(`status != ACTIVE`). 편의 플래그 |
| `status` | string(enum) | 사용자 현재 상태 `PENDING` \| `TERMS_AGREED` \| `ACTIVE`. 클라이언트는 이 값으로 다음 화면을 분기(PENDING→약관 동의 §2, TERMS_AGREED→온보딩 §5, ACTIVE→홈) |
| `refreshToken` | string \| null | `ACTIVE` 로그인에서만 발급, 미완료(`PENDING`/`TERMS_AGREED`)는 `null` |

> `expiresIn`은 access 토큰 만료까지의 초(seconds). 미완료 회원에게 주는 access 토큰은 온보딩 흐름(약관 동의·본인 확인(세입자 이메일 인증·임대인 연락처 인증)·온보딩) API만 통과시킨다(클레임 `onboardingCompleted=false`, refresh 미발급). 온보딩 전용 임시 토큰 만료 1800초(30분), 정식 access 3600초(1시간) — [ADR-0011](../../adr/0011-token-lifetime-and-secret-policy.md)에서 확정.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `provider` 누락(null) (Bean Validation: `@NotNull`) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치. **`provider`가 허용 외 enum 문자열(`APPLE`/`GOOGLE` 외)이면 역직렬화 단계에서 거부되어 이 코드로 처리**된다 |
| 400 | `AUTH_MISSING_CREDENTIAL` | provider의 자격 필드 누락/빈값(Google `idToken` 또는 Apple `authorizationCode` 미전송) — application 계층 검증 |
| 401 | `AUTH_INVALID_SOCIAL_TOKEN` | Google `idToken`의 서명/`aud`/`iss`/`exp` 검증 실패, 또는 Apple 교환 실패(`invalid_grant`/`invalid_client` — 만료·재사용 코드, 잘못된 client_secret)와 교환으로 받은 `id_token` 검증 실패. **provider JWKS 조회 실패 등 OIDC 연동 오류도 현재 구현은 이 코드로 통합 처리**한다(아래 노트) |

> **연동 실패 처리(현행)**: `OidcTokenVerifierImpl`은 JWKS 조회 실패·provider 응답 오류를 포함한 모든 OIDC 검증 실패를 `401 AUTH_INVALID_SOCIAL_TOKEN`으로 변환한다. Apple `/auth/token` 교환 호출의 인증 실패(`invalid_grant`/`invalid_client`)도 `401`로 통합하고, Apple 측 일시 장애·타임아웃 등 I/O·5xx는 `502 UPSTREAM_ERROR`로 분리한다([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)). Google 경로는 종전대로 `502`/`503`을 내지 않는다(시퀀스 [US-1-1](../../architecture/sequence-diagrams/01-auth-onboarding/us-1-1-social-login.md)·REST Docs 스니펫과 정합). 외부 연동 견고화(타임아웃·재시도·서킷브레이커) 확대는 [error-response-guide](../error-response-guide.md) §3 참고.

---

### 2. POST `/api/v1/auth/terms` — 약관 동의

소셜 로그인 후 `PENDING` 사용자가 약관에 동의해 가입 흐름의 첫 단계를 마친다. 성공 시 `TERMS_AGREED`로 전이하고, 서버가 동의 시각(`agreedAt`)과 약관 버전(`termsVersion`)을 기록한다([ADR-0012](../../adr/0012-terms-version-management.md)). 이 단계 이후에야 온보딩 정보 제출(§5)이 가능하다.

- **인증**: 필수 — 소셜 로그인 단계에서 받은 온보딩 토큰(`onboardingCompleted=false`). 토큰은 갱신하지 않는다(상태만 전이).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "termsOfServiceAgreed": true,
  "privacyPolicyAgreed": true,
  "marketingAgreed": false
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `termsOfServiceAgreed` | boolean | 필수 | 이용약관 동의. `false`면 `AUTH_REQUIRED_AGREEMENT_MISSING`(422) |
| `privacyPolicyAgreed` | boolean | 필수 | 개인정보처리방침 동의. `false`면 `AUTH_REQUIRED_AGREEMENT_MISSING`(422) |
| `marketingAgreed` | boolean | 선택 | 마케팅 수신 동의(기본 `false`). 세분화된 마케팅 동의 항목은 고도화 예정(확인 필요) |

> `termsVersion`은 클라이언트가 보내지 않고 서버가 설정값(`app.terms.version`)을 기록한다. 약관 버전·문구 변경 시 재동의 정책은 [ADR-0012](../../adr/0012-terms-version-management.md).

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "status": "TERMS_AGREED",
    "termsOfServiceAgreed": true,
    "privacyPolicyAgreed": true,
    "marketingAgreed": false,
    "agreedAt": "2026-06-15T08:25:00Z"
  },
  "error": null
}
```

> `PENDING`의 **최초 동의**만 `TERMS_AGREED`로 전이한다. 이미 `TERMS_AGREED`인 사용자가 (네트워크 재시도 등으로) 다시 호출하면 상태·동의를 바꾸지 않고 멱등하게 현재 상태(`200`)를 반환한다 — 의도적 재동의가 아닌 중복 요청 방어다. 동의 후 **마케팅 수신 동의 변경은 `PATCH /users/me`(§9)** 로 처리하며, 약관 버전 변경에 따른 재동의 정책은 [ADR-0012](../../adr/0012-terms-version-management.md)(확인 필요).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `termsOfServiceAgreed`/`privacyPolicyAgreed` 누락(`@NotNull`) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩 토큰 누락/위조 / 만료 |
| 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 온보딩 완료(`ACTIVE`)된 사용자의 약관 동의 재요청 |
| 422 | `AUTH_REQUIRED_AGREEMENT_MISSING` | 필수 약관(이용약관/개인정보처리방침) 미동의 |

---

### 3. POST `/api/v1/auth/email/verification-code` — 이메일 인증번호 발송

온보딩 중인 사용자가 입력한 이메일 주소로 인증번호를 발송한다. **약관 동의(§2, `TERMS_AGREED`)가 선행**되어야 한다 — 약관 미동의(`PENDING`)면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`로 거절하고 약관 동의(§2)를 먼저 유도한다. 같은 사용자에 미검증 인증 시도가 남아 있으면 새 인증번호로 대체한다. 인증번호는 서버에 **해시로만 보관**하고 일정 시간(예: 5분 — 확인 필요) 후 만료한다. 재발송은 레이트리밋으로 보호한다.

메일은 아웃바운드 포트 `VerificationEmailSender`(인프라 어댑터: SMTP)로 **동기 발송**하며, **발송에 성공한 뒤에만** 인증번호 챌린지를 저장한다. provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(메일 템플릿·다국어, 동기/비동기 정책은 확인 필요).

- **인증**: 필수 — 소셜 로그인 단계에서 받은 온보딩 토큰(`onboardingCompleted=false`).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "email": "minh@example.com"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `email` | string | 필수 | 이메일 형식(`@Email`). 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "email": "mi***@example.com",
    "expiresIn": 300
  },
  "error": null
}
```

> `expiresIn`은 인증번호 만료까지의 초(seconds). `email`은 마스킹해 반환한다. 인증번호 원문은 응답·로그에 노출하지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `email` 누락/빈값/형식 위반(`@NotBlank`/`@Email`) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩 토큰 누락/위조 / 만료 |
| 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태의 요청(약관 동의 §2 선행 필요) |
| 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 온보딩 완료(ACTIVE)된 사용자의 요청(이메일 인증은 온보딩 단계 전용) |
| 429 | `TOO_MANY_REQUESTS` | 재발송 레이트리밋 초과(확인 필요: 임계값) |
| 502 | `UPSTREAM_ERROR` | 메일 발송 실패(provider 장애·타임아웃). 챌린지 미저장, 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 4. POST `/api/v1/auth/email/verify` — 이메일 인증번호 확인

발송된 인증번호를 검증한다. 성공하면 해당 사용자의 이메일을 **검증 완료(VERIFIED)** 로 표시하고, 이후 온보딩 제출 시 같은 이메일을 통과시킨다. 검증 시도는 횟수 상한으로 보호한다.

> **챌린지 부재(미발송·만료·이미 검증)**: 해당 사용자의 인증 챌린지(`email-verify:code:{userId}`)가 없으면 — 인증번호를 한 번도 요청하지 않았거나, TTL 만료, 이미 검증 완료로 소멸, 발송 실패(`502`)로 미저장 — 올릴 `attempts` 레코드 자체가 없으므로 **즉시 `422 AUTH_EMAIL_VERIFICATION_FAILED`** 로 거절하고 인증번호 (재)요청(§3)을 유도한다. `attempts`는 **챌린지가 존재하는데 코드가 불일치**할 때만 증가하며, 상한 초과 시 `429 TOO_MANY_REQUESTS`다.

- **인증**: 필수 — 온보딩 토큰(`onboardingCompleted=false`).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "email": "minh@example.com",
  "code": "482915"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `email` | string | 필수 | 인증번호를 발송한 이메일과 일치해야 함 |
| `code` | string | 필수 | 발송된 인증번호. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "email": "mi***@example.com",
    "verified": true
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `email`/`code` 누락/빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩 토큰 누락/위조 / 만료 |
| 422 | `AUTH_EMAIL_VERIFICATION_FAILED` | 코드 불일치, 또는 챌린지 부재(미발송·만료·이미 검증) — 부재 시 `attempts` 증가 없이 즉시 거절 |
| 429 | `TOO_MANY_REQUESTS` | 챌린지 존재 + 코드 불일치 누적으로 검증 시도 상한 초과(확인 필요: 임계값) |

---

### 4-1. POST `/api/v1/auth/phone/verification-code` — 연락처 인증번호 발송(임대인 전용)

**임대인 온보딩(US-1-10)** 또는 **정식 회원의 프로필 연락처 변경(US-1-5)** 시 입력한 연락처(휴대폰)로 SMS 인증번호를 발송한다(세입자 이메일 인증 §3과 대칭 — 임대인 트랙의 본인 확인, [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)). **약관 동의(§2, `TERMS_AGREED`) 이상**이면 진행한다 — 온보딩(`TERMS_AGREED`)·프로필 변경(`ACTIVE`) 두 컨텍스트 모두 허용하고, 약관 미동의(`PENDING`)면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`로 거절하고 약관 동의(§2)를 먼저 유도한다. 같은 사용자에 미검증 인증 시도가 남아 있으면 새 인증번호로 대체한다. **인증번호 정책은 이메일 인증(§3·§4)과 동일하다** — 인증번호 6자리, 서버에 **해시로만 보관**하고 코드 TTL 5분 후 만료, 검증 마커(VERIFIED) TTL 30분(온보딩 토큰 만료), 검증 시도 상한 5회, 재발송 간격 60초로 보호한다.

SMS는 아웃바운드 포트 `VerificationSmsSender`(인프라 어댑터: SMS API — 구체 provider는 [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md))로 **동기 발송**하며, **발송에 성공한 뒤에만** 인증번호 챌린지를 저장한다. provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(인증번호 생성·해시·검증은 서버가 보유해 이메일 인증과 대칭 — 어댑터는 발송만 담당. 동기/비동기 정책·문자 템플릿은 확인 필요).

- **인증**: 필수 — **(온보딩 단계, US-1-10)** 소셜 로그인에서 받은 온보딩 토큰(`onboardingCompleted=false`), **또는 (프로필 연락처 변경, US-1-5) 정식 토큰(`ACTIVE`, `ROLE_USER`)**. `/auth/phone/**`는 두 티어 모두 허용한다(보안 경로 확장 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) §6·§8).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 전화번호 형식. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "expiresIn": 300
  },
  "error": null
}
```

> `expiresIn`은 인증번호 만료까지의 초(seconds). `phoneNumber`는 마스킹해 반환한다. 인증번호 원문은 응답·로그에 노출하지 않는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber` 누락/빈값/형식 위반 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩/정식 토큰 누락/위조 / 만료 |
| 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태의 요청(약관 동의 §2 선행 필요) |
| 429 | `TOO_MANY_REQUESTS` | 재발송 레이트리밋 초과(이메일 인증과 동일 — 재발송 간격 60초) |
| 502 | `UPSTREAM_ERROR` | SMS 발송 실패(provider 장애·타임아웃). 챌린지 미저장, 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 4-2. POST `/api/v1/auth/phone/verify` — 연락처 인증번호 확인(임대인 전용)

발송된 인증번호를 검증한다. 성공하면 해당 사용자의 연락처를 **검증 완료(VERIFIED)** 로 표시하고, 이후 임대인 온보딩 제출(§5-2) 시 같은 번호를 통과시킨다. 검증 시도는 횟수 상한으로 보호한다(이메일 인증번호 확인 §4와 대칭).

> **챌린지 부재(미발송·만료·이미 검증)**: 해당 사용자의 인증 챌린지(`phone-verify:code:{userId}`)가 없으면 — 인증번호를 한 번도 요청하지 않았거나, TTL 만료, 이미 검증 완료로 소멸, 발송 실패(`502`)로 미저장 — 올릴 `attempts` 레코드 자체가 없으므로 **즉시 `422 AUTH_PHONE_VERIFICATION_FAILED`** 로 거절하고 인증번호 (재)요청(§4-1)을 유도한다. `attempts`는 **챌린지가 존재하는데 코드가 불일치**할 때만 증가하며, 상한 초과 시 `429 TOO_MANY_REQUESTS`다.

- **인증**: 필수 — **(온보딩 단계, US-1-10)** 온보딩 토큰(`onboardingCompleted=false`), **또는 (프로필 연락처 변경, US-1-5) 정식 토큰(`ACTIVE`, `ROLE_USER`)**. `/auth/phone/**`는 두 티어 모두 허용한다([ADR-0034](../../adr/0034-landlord-phone-sms-verification.md) §6·§8).
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "phoneNumber": "010-1234-5678",
  "code": "482915"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `phoneNumber` | string | 필수 | 인증번호를 발송한 연락처와 일치해야 함 |
| `code` | string | 필수 | 발송된 인증번호. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "phoneNumber": "010-****-5678",
    "verified": true
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `phoneNumber`/`code` 누락/빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 온보딩 토큰 누락/위조 / 만료 |
| 422 | `AUTH_PHONE_VERIFICATION_FAILED` | 코드 불일치, 또는 챌린지 부재(미발송·만료·이미 검증) — 부재 시 `attempts` 증가 없이 즉시 거절 |
| 429 | `TOO_MANY_REQUESTS` | 챌린지 존재 + 코드 불일치 누적으로 검증 시도 상한 초과(이메일 인증과 동일 — 검증 시도 5회) |

---

### 5. POST `/api/v1/auth/onboarding` — 온보딩 제출(가입 완료·세입자)

`TERMS_AGREED` 세입자가 필수 프로필을 제출해 가입을 완료한다. **약관 동의(§2)와 이메일 인증(§3·§4)이 선행**되어야 한다 — 약관 미동의(`PENDING`)면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`, 제출 `email`이 미검증·불일치면 `422 AUTH_EMAIL_NOT_VERIFIED`. 성공 시 `ACTIVE`로 전이하고, 닉네임을 자동 배정하며 정식 access/refresh 토큰을 발급한다. 사용자 단위로 멱등 처리해 동시 요청은 한 건만 성공한다.

> 약관 동의·`termsVersion`은 §2(약관 동의)에서 이미 기록되므로 이 요청 본문에는 약관 필드를 담지 않는다. `nickname`은 서버가 형용사 풀·사물 풀의 active 단어에서 골라 `형용사 + 사물`로 조합하고 전역 유니크를 보장(충돌 시 재조합 재시도, 상한 초과 시 fallback 예: 숫자 접미사)해 자동 배정하므로 요청 본문에 담지 않는다(사용자 입력·수정 불가). `email`은 §3·§4로 검증 완료된 값과 일치해야 한다. 응답의 `countryName`·`countryFlag`는 서버가 `country`(코드)로 `countries`에서 resolve한 값이다(저장은 `country` 코드만). `countryFlag`는 **국기 이미지 URL**(flagcdn.com SVG)이다.

- **인증**: 필수 — 소셜 로그인 단계에서 받은 온보딩 토큰(`onboardingCompleted=false`). 상태는 `TERMS_AGREED`여야 한다.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "firstName": "Minh",
  "lastName": "Nguyen",
  "gender": "MALE",
  "birthDate": "1998-04-12",
  "country": "VN",
  "occupation": "UNDERGRADUATE_STUDENT",
  "email": "minh@example.com",
  "visaType": "STUDY_D-2"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `firstName` | string | 필수 | 이름. 빈 문자열 불가 |
| `lastName` | string | 필수 | 성. 빈 문자열 불가 |
| `gender` | string(enum) | 필수 | `MALE` \| `FEMALE` |
| `birthDate` | string(date) | 필수 | `YYYY-MM-DD`, 과거 날짜만 허용(미래 불가) |
| `country` | string | 필수 | 국적 ISO 3166-1 alpha-2 코드(예: `VN`). `countries`에 존재해야 함(없으면 `INVALID_INPUT`) |
| `occupation` | string(enum) | 필수 | `UNDERGRADUATE_STUDENT` \| `GRADUATE_STUDENT` \| `EXCHANGE_STUDENT` \| `EDUCATION_ACADEMIC_RESEARCH` \| `IT_SOFTWARE_ENGINEERING` \| `DEVELOPER` \| `DESIGNER` |
| `email` | string | 필수 | 이메일 형식. **§3·§4로 사전 검증된 값과 일치**해야 함(미검증·불일치 `AUTH_EMAIL_NOT_VERIFIED` 422) |
| `visaType` | string(enum) | 필수 | `DIPLOMATIC_OFFICIAL_A-1_A-2` \| `VISA_EXEMPTED_B` \| `JOURNALISM_RELIGIOUS_AFFAIRS_C-1_D-5_D-6` \| `SHORT_TERM_VISIT_C-2_C-3` \| `STUDY_D-2` \| `TRAINEE_D-3_D-4` \| `INTRA_COMPANY_TRANSFER_D-7` \| `PROFESSIONAL_C-4_D-1_D-8_D-9_D-10_E-1_E-2_E-3_E-4_E-5_E-6_E-7` \| `NON_PROFESSIONAL_E-8_E-9_E-10` \| `WORKING_HOLIDAY_H-1` \| `WORK_AND_VISIT_H-2` \| `FAMILY_VISITOR_DEPENDENT_F-1_F-2_F-3` \| `OVERSEAS_KOREAN_F-4` \| `PERMANENT_RESIDENCE_F-5` \| `MARRIAGE_MIGRANT_F-6` \| `OTHERS_G-1` |

> 약관 동의(`termsOfServiceAgreed`·`privacyPolicyAgreed`·`marketingAgreed`)는 이 요청에 포함하지 않는다 — 앞선 `POST /auth/terms`(§2)에서 처리·기록된다.

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "user": {
      "id": 1024,
      "firstName": "Minh",
      "lastName": "Nguyen",
      "nickname": "BraveOtter",
      "gender": "MALE",
      "birthDate": "1998-04-12",
      "country": "VN",
      "countryName": "Vietnam",
      "countryFlag": "https://flagcdn.com/vn.svg",
      "occupation": "UNDERGRADUATE_STUDENT",
      "email": "minh@example.com",
      "visaType": "STUDY_D-2",
      "userType": "TENANT",
      "status": "ACTIVE",
      "marketingAgreed": false,
      "createdAt": "2026-06-15T08:30:00Z"
    },
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2",
    "expiresIn": 3600
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 필드 누락/형식·enum·날짜 위반(`gender`/`visaType`/`occupation` 불일치, `birthDate` 형식·미래, `firstName`/`lastName`/`country`/`email` 빈값·형식 등) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 `ACTIVE`인 사용자의 온보딩 재요청(동시 요청 포함) |
| 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태에서 온보딩 제출(약관 동의 §2 선행 필요) |
| 422 | `AUTH_EMAIL_NOT_VERIFIED` | 제출 `email`이 미검증이거나 검증한 이메일과 불일치 |

---

### 5-1. POST `/api/v1/auth/business/verify` — 사업자등록번호 검증(임대인 전용)

온보딩을 마친(`ACTIVE`) **임대인 전용**으로, 입력한 사업자등록번호를 외부 사업자등록정보 검증 API(국세청 사업자등록정보 기반, 구체 provider는 [ADR-0033](../../adr/0033-business-registry-verification.md))로 진위·영업 상태까지 확인하는 **무상태(stateless) 검증 API**다. 온보딩(§5-2)과 분리되어 있으며, 정식 access 토큰(`ROLE_USER`)을 가진 임대인이 나중에(매물 등록 시점) 호출한다. 형식(숫자 10자리) 위반은 외부 호출 전에 `400 INVALID_INPUT`으로 거른다. **정상(계속) 사업자면** `verified:true`를 응답 body로 돌려주고, 미등록·휴업·폐업 번호는 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`로 거절한다.

**무상태**: 검증 결과를 서버에 저장하지 않는다 — Redis 마커·`user.businessRegistrationNumberHash` 컬럼 어느 쪽에도 쓰지 않으며, 결과는 응답(HTTP body)에만 담긴다. 온보딩 제출에서 이 결과를 대조하는 게이트도 없다. 검증은 아웃바운드 포트 `BusinessRegistryVerifier`(인프라 어댑터: 사업자등록정보 검증 API — 국세청 사업자등록정보 진위·상태 기반)로 **동기 호출**한다. 검증 API 장애·타임아웃·5xx 등 연동 실패는 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(공통 코드 — [error-response-guide](../error-response-guide.md) §3). 사업자등록번호 원문은 응답·로그에 노출하지 않고 마스킹한다.

- **인증**: 필수 — **정식 access 토큰(`ACTIVE`, `ROLE_USER`)**. 온보딩 토큰(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`)으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌(`userType=TENANT`) ACTIVE 사용자면 `403 FORBIDDEN`이다.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "businessRegistrationNumber": "1234567890"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `businessRegistrationNumber` | string | 필수 | 숫자 10자리 또는 하이픈 형식(예 `123-45-67890`) — 어댑터가 하이픈을 제거해 조회·대조. 빈 문자열·형식 위반 불가(`INVALID_INPUT` — 외부 호출 전 거름) |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "businessRegistrationNumber": "****567890",
    "verified": true
  },
  "error": null
}
```

> `businessRegistrationNumber`는 마스킹해 반환한다(예: `****567890` — 마스킹 형식 확인 필요). **검증 결과는 서버에 저장하지 않는다**(무상태) — Redis 마커·`user.businessRegistrationNumberHash` 어느 쪽에도 쓰지 않으며 응답 body(`verified:true`)로만 회신한다. 검증 서비스가 회신한 상호·대표자 등 표시용 정보의 응답 노출 여부는 확인 필요.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `businessRegistrationNumber` 누락/빈값/형식(숫자 10자리) 위반 — 외부 호출 전 거름 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 정식 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 토큰(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`)으로 호출(정식 토큰 필요 — 온보딩 완료 후 호출하는 API) |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) ACTIVE 사용자의 요청(임대인 전용) |
| 422 | `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED` | 검증 서비스 조회 결과 미등록이거나 휴업·폐업 상태(진위·상태 검증 실패) |
| 429 | `TOO_MANY_REQUESTS` | 검증 시도 레이트리밋 초과(확인 필요: 시도 상한·간격 임계값) |
| 502 | `UPSTREAM_ERROR` | 사업자등록정보 검증 API 장애·타임아웃·5xx. 클라이언트 재시도 유도(공통 코드 — [error-response-guide](../error-response-guide.md) §3) |

---

### 5-2. POST `/api/v1/auth/landlord/onboarding` — 임대인 온보딩 제출(임대인 전용·가입 완료)

`TERMS_AGREED` 사용자가 임대인 필수 프로필을 제출해 가입을 완료한다(세입자 온보딩 §5와 분리된 **임대인 전용 엔드포인트**). **약관 동의(§2)·연락처 인증(§4-1·§4-2)이 선행**되어야 한다 — **임대인 온보딩은 약관 동의 + 연락처(SMS) 인증만으로 완료**되며, 사업자등록번호는 수집·검증하지 않는다(온보딩 후 매물 등록 시점에 별도 검증 API(§5-1)로 검증). 성공 시 `ACTIVE`로 전이하고 **`userType`을 `LANDLORD`로 확정**하며, 닉네임을 자동 배정하고 정식 access/refresh 토큰을 발급한다(상태 전이 액션이므로 `200`). 사용자 단위로 멱등 처리해 동시 요청은 한 건만 성공한다. 임대인은 성별·국적·직업·비자정보·생년월일과 **이메일을 수집하지 않으며**, 세입자의 성·이름(`firstName`/`lastName`) 대신 단일 `name`을 받는다.

> **검증 게이트 우선순위**: 약관 미동의(`PENDING`) → `422 AUTH_TERMS_AGREEMENT_REQUIRED`(이미 `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`) → 제출 `phoneNumber` 미검증·불일치 → `422 AUTH_PHONE_NOT_VERIFIED` 순으로 판정한다(약관 → 연락처, 사업자번호 게이트 없음). 약관 동의·`termsVersion`은 §2에서 이미 기록되므로 이 요청 본문에 약관 필드를 담지 않는다. `phoneNumber`는 §4-1·§4-2로 검증 완료된 값과 일치해야 한다. `nickname`은 서버가 자동 배정하므로 요청 본문에 담지 않는다(사용자 입력·수정 불가).

- **인증**: 필수 — 소셜 로그인 단계에서 받은 온보딩 토큰(`onboardingCompleted=false`). 상태는 `TERMS_AGREED`여야 한다.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "name": "Kim Minsu",
  "phoneNumber": "010-1234-5678"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `name` | string | 필수 | 성·이름을 합친 단일 이름. 빈 문자열 불가 |
| `phoneNumber` | string | 필수 | 전화번호 형식. **§4-1·§4-2로 사전 검증된 값과 일치**해야 함(미검증·불일치 `AUTH_PHONE_NOT_VERIFIED` 422) |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "user": {
      "id": 2048,
      "name": "Kim Minsu",
      "nickname": "BraveOtter",
      "phoneNumber": "010-****-5678",
      "userType": "LANDLORD",
      "status": "ACTIVE",
      "marketingAgreed": false,
      "createdAt": "2026-06-15T08:30:00Z"
    },
    "tokenType": "Bearer",
    "accessToken": "eyJ...access",
    "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2",
    "expiresIn": 3600
  },
  "error": null
}
```

> 임대인 응답은 세입자와 달리 `gender`·`country`·`occupation`·`visaType`·`birthDate`·`email`을 포함하지 않는다(임대인은 이메일 미수집 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)). `phoneNumber`는 마스킹해 반환한다(예: `010-****-5678` — 프로필 조회 §8은 본인이라 평문). `marketingAgreed`는 포함한다(약관 동의 시 확정). 사업자등록번호는 온보딩에서 수집하지 않으므로 응답에도 포함하지 않는다(온보딩 후 별도 검증 §5-1). 임대인 프로필 조회·수정은 `GET`(§8)·`PATCH`(§9) `/users/me`에서 `userType`에 따라 분기해 다룬다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `name`/`phoneNumber` 누락·빈값·형식(전화번호) 위반(`errors[]`로 위반 필드 반환) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 `ACTIVE`인 사용자의 온보딩 재요청(동시 요청 포함 — 한 요청만 성공) |
| 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태에서 온보딩 제출(약관 동의 §2 선행 — 우선 판정) |
| 422 | `AUTH_PHONE_NOT_VERIFIED` | 제출 `phoneNumber`가 미검증이거나 검증한 번호와 불일치(연락처 인증 §4-1·§4-2 선행) |

---

### 6. POST `/api/v1/auth/reissue` — 토큰 재발급

유효한 refresh 토큰으로 새 access 토큰을 재발급한다. 항상 회전한다 — 새 refresh 토큰도 함께 발급하고 제출한 refresh는 무효화(ROTATED)한다([ADR-0006](../../adr/0006-refresh-token-store-redis.md)). 폐기된 토큰을 다시 제출하는 재사용이 탐지되면 해당 사용자의 모든 refresh 토큰을 무효화한다.

- **인증**: 불필요(헤더 access 토큰 없이 본문 refresh 토큰으로 처리). 만료된 access 토큰 보유 클라이언트가 이 엔드포인트로 갱신한다.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `refreshToken` | string | 필수 | 서버가 발급·보관(해시) 중인 **불투명(opaque) refresh 토큰**. 빈 문자열 불가 |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "eyJ...new-access",
    "refreshToken": "rt_3b1e7c5a2f9d04e8b6c1a07f5d2e93b4c8a16f0d",
    "expiresIn": 3600
  },
  "error": null
}
```

> reissue는 항상 회전한다: 제출한 refresh는 무효화(ROTATED)하고 새 access·refresh를 함께 발급한다([ADR-0006](../../adr/0006-refresh-token-store-redis.md)).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `refreshToken` 누락/빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `AUTH_INVALID_REFRESH_TOKEN` | refresh 토큰 만료/위조/무효화/재사용 탐지 |

---

### 7. POST `/api/v1/auth/logout` — 로그아웃

전달된 refresh 토큰을 서버에서 무효화해 더는 재발급에 쓰지 못하게 한다. 이미 무효화된 토큰이면 멱등하게 `204`로 처리한다.

- **인증**: 필수.
- Path/Query 파라미터: 없음.

#### Request Body

```json
{
  "refreshToken": "rt_9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `refreshToken` | string | 필수 | 무효화할 refresh 토큰. 빈 문자열 불가 |

#### 성공 Response — 204 No Content

본문 없음. 이미 무효화된 토큰으로 재호출해도 멱등하게 `204`를 반환한다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `refreshToken` 누락/빈값 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | access 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근(logout은 `ROLE_USER` 필요) |

---

### 8. GET `/api/v1/users/me` — 내 프로필 조회

인증된 본인의 프로필을 조회한다. **응답 필드는 `userType`에 따라 갈린다** — 세입자(`TENANT`)는 성·이름·국적·직업·비자정보 등 외국인 프로필을, 임대인(`LANDLORD`)은 단일 `name`·연락처 중심 프로필을 받는다.

- **인증**: 필수(ACTIVE 사용자). PENDING 토큰 접근은 `403 AUTH_ONBOARDING_REQUIRED`.
- Path/Query 파라미터: 없음.

#### 성공 Response — 세입자(TENANT) (200 OK)

```json
{
  "success": true,
  "data": {
    "id": 1024,
    "userType": "TENANT",
    "firstName": "Minh",
    "lastName": "Nguyen",
    "nickname": "BraveOtter",
    "gender": "MALE",
    "birthDate": "1998-04-12",
    "country": "VN",
    "countryName": "Vietnam",
    "countryFlag": "https://flagcdn.com/vn.svg",
    "occupation": "UNDERGRADUATE_STUDENT",
    "email": "minh@example.com",
    "visaType": "STUDY_D-2",
    "status": "ACTIVE",
    "termsOfServiceAgreed": true,
    "privacyPolicyAgreed": true,
    "marketingAgreed": false,
    "createdAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

#### 성공 Response — 임대인(LANDLORD) (200 OK)

```json
{
  "success": true,
  "data": {
    "id": 2048,
    "userType": "LANDLORD",
    "name": "Kim Minsu",
    "nickname": "BraveOtter",
    "phoneNumber": "010-1234-5678",
    "status": "ACTIVE",
    "termsOfServiceAgreed": true,
    "privacyPolicyAgreed": true,
    "marketingAgreed": false,
    "createdAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

> 본인 프로필이므로 `phoneNumber`는 평문으로 반환한다(로그·타 사용자 노출 시에만 마스킹). 임대인 응답의 `name`은 저장된 `FullName.firstName`(전체 이름)을 매핑한 값이다.
> 임대인 응답은 세입자 전용 필드(`gender`·`country`·`countryName`·`countryFlag`·`occupation`·`visaType`·`birthDate`)와 `email`(임대인 미수집 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md))을 포함하지 않는다. **`businessRegistrationNumber`는 온보딩에서 수집하지 않으므로(온보딩 후 별도 검증 §5-1, 결과 미저장) 응답에 포함하지 않는다.**

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근 |
| 404 | `USER_NOT_FOUND` | 사용자가 `WITHDRAWN`이거나 삭제되어 없음 |

---

### 9. PATCH `/api/v1/users/me` — 내 프로필 부분 수정

본인 프로필을 부분 수정한다. 전송한 필드만 변경하고, 미전송 필드는 유지한다(미전송 ≠ 값 비움 — 현재 수정 대상 필드는 비움 불가). **수정 가능 필드는 `userType`에 따라 갈린다** — 세입자(`TENANT`)는 성·이름·국적·직업·비자정보·마케팅 동의를, 임대인(`LANDLORD`)은 `name`·`phoneNumber`·`marketingAgreed`만 수정한다.

- **인증**: 필수(ACTIVE 사용자). PENDING 토큰 접근은 `403 AUTH_ONBOARDING_REQUIRED`.
- Path/Query 파라미터: 없음.

#### Request Body — 세입자(TENANT) (모든 필드 선택)

```json
{
  "country": "KR",
  "occupation": "DEVELOPER",
  "visaType": "SHORT_TERM_VISIT_C-2_C-3",
  "marketingAgreed": true
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `firstName` | string | 선택 | 이름 |
| `lastName` | string | 선택 | 성 |
| `gender` | string(enum) | 선택 | `MALE` \| `FEMALE` |
| `birthDate` | string(date) | 선택 | `YYYY-MM-DD`, 과거 날짜만 |
| `country` | string | 선택 | 국적 ISO 코드(예: `KR`). `countries`에 존재해야 함 |
| `occupation` | string(enum) | 선택 | 직업 enum(위 목록과 동일) |
| `visaType` | string(enum) | 선택 | 비자정보 enum(위 목록과 동일) |
| `marketingAgreed` | boolean | 선택 | 마케팅 수신 동의 |

#### Request Body — 임대인(LANDLORD) (모든 필드 선택)

```json
{
  "name": "Kim Minsu",
  "phoneNumber": "010-1234-5678",
  "marketingAgreed": true
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `name` | string | 선택 | 성·이름을 합친 단일 이름. 빈 문자열 불가. 저장은 `FullName.firstName`에 매핑(§핵심 개념 표 참조) |
| `phoneNumber` | string | 선택 | 전화번호 형식. 빈 문자열 불가. **변경 시 SMS 재인증(§4-1·§4-2) 필요** — 새 번호가 VERIFIED일 때만 반영(미인증·불일치 `AUTH_PHONE_NOT_VERIFIED` 422) |
| `marketingAgreed` | boolean | 선택 | 마케팅 수신 동의 |

> 필수 약관 동의(`termsOfServiceAgreed`/`privacyPolicyAgreed`)는 이 엔드포인트로 철회할 수 없다(탈퇴 경로로만 처리). (확인 필요: 동의 철회 정책)
> `nickname`은 시스템 배정값이라 수정 대상이 아니다(세입자·임대인 공통 불변). **세입자** `email` 변경은 재인증(§3·§4)이 필요하므로 이 엔드포인트로는 수정하지 않는다(임대인은 `email` 미보유 — 별도 흐름, 확인 필요).
> **임대인 전용**: `userType`은 온보딩으로 확정된 뒤 불변이다. `businessRegistrationNumber`는 온보딩·프로필에서 수집·저장하지 않으므로 이 경로의 수정 대상이 아니다(필요 시 별도 검증 API §5-1로 무상태 검증). **`phoneNumber` 변경은 SMS 재인증(§4-1·§4-2)이 필요하다** — 새 번호를 재인증(VERIFIED)한 뒤에만 반영하며, 미인증·불일치는 `422 AUTH_PHONE_NOT_VERIFIED`다(온보딩 시 연락처 인증과 동일한 발송·확인을 정식 토큰 컨텍스트에서 재사용 — [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)). **클라이언트 계약**: 앱은 연락처 변경 시 **PATCH 이전에 새 번호 인증(§4-1·§4-2)을 먼저 수행**한다(정상 흐름). `422 AUTH_PHONE_NOT_VERIFIED`는 happy path가 아니라 **미인증·마커 TTL 만료·불일치 제출에 대한 서버 가드**다.

#### 성공 Response — 200 OK

수정된 프로필 전체를 `GET /users/me`와 동일 스키마의 공통 래퍼로 반환한다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `birthDate` 미래(`@Past` 위반), `country` 미존재(`countries`에 없음) 등 값 검증 위반 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치. **`gender`/`visaType`/`occupation` 허용 외 enum 문자열·`birthDate` 형식 불가**는 역직렬화 단계에서 거부되어 이 코드로 처리(요청 DTO가 enum/날짜 타입이라 매핑 실패 → onboarding(§5)은 String 수집·서버 파싱이라 `INVALID_INPUT`인 점과 다름) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 접근 |
| 404 | `USER_NOT_FOUND` | 사용자가 `WITHDRAWN`이거나 삭제되어 없음 |
| 422 | `AUTH_PHONE_NOT_VERIFIED` | (임대인) 새 `phoneNumber`로 변경 시 그 번호가 SMS 재인증(§4-1·§4-2)되지 않았거나 검증한 번호와 불일치 |

---

### 10. DELETE `/api/v1/users/me` — 회원 탈퇴

본인 계정을 탈퇴 처리한다. 사용자 상태를 `WITHDRAWN`으로 전이하고 모든 refresh 토큰을 무효화한다. PENDING(온보딩 미완료) 사용자도 탈퇴할 수 있다(온보딩 중단·정리 목적). **Apple 연동 계정은 저장된 `apple_refresh_token`으로 Apple `/auth/revoke`를 호출해 앱↔Apple ID 연동까지 폐기**한다(App Store 5.1.1(v), [ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)).

- **인증**: 필수.
- Path/Query 파라미터: 없음.
- Request Body: 없음.

#### 성공 Response — 204 No Content

본문 없음. 개인정보(세입자: 이름·생년월일·국적·직업·이메일·비자·닉네임 / 임대인: 이름·연락처·닉네임, 사업자번호 해시가 저장돼 있으면 함께)는 탈퇴 시 즉시 익명화, social_accounts 매핑 삭제([ADR-0014](../../adr/0014-withdrawal-pii-anonymization.md)). Apple 연동은 매핑 삭제 전에 `/auth/revoke`로 폐기하며, **best-effort**(이미 폐기·Apple 장애여도 탈퇴는 완료)다([ADR-0031](../../adr/0031-apple-sign-in-authorization-code-flow.md)).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 누락/위조 / 만료 |
| 409 | `USER_ALREADY_WITHDRAWN` | 이미 `WITHDRAWN`된 사용자의 탈퇴 재요청 |
| 404 | `USER_NOT_FOUND` | 사용자가 삭제되어 없음 |

---

## 도메인 에러 코드

> 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN`, `RESOURCE_NOT_FOUND` 등)는 [error-response-guide](../error-response-guide.md) §3·§4를 따르며 여기서 재정의하지 않는다. provider/idToken 등 입력 형식 위반은 별도 도메인 코드 없이 공통 코드로 처리한다 — Bean Validation 위반(누락·빈값)은 `INVALID_INPUT`, 역직렬화 실패(허용 외 enum 문자열 등)는 `MALFORMED_REQUEST`. 아래는 auth/user 도메인 고유 코드만 정의한다. prefix는 `AUTH` / `USER`.

| code | status | 의미 |
| --- | --- | --- |
| `AUTH_MISSING_CREDENTIAL` | 400 | provider의 자격 필드 누락(Google `idToken` 또는 Apple `authorizationCode` 미전송) |
| `AUTH_INVALID_SOCIAL_TOKEN` | 401 | Google `idToken` 검증 실패(서명/`aud`/`iss`/`exp`), 또는 Apple `authorizationCode` 교환 실패·교환 `id_token` 검증 실패(위조·만료·앱 불일치·재사용 코드) |
| `AUTH_EMAIL_VERIFICATION_FAILED` | 422 | 이메일 인증번호 불일치 또는 만료(미발송·만료·오입력) — 세입자 |
| `AUTH_EMAIL_NOT_VERIFIED` | 422 | 세입자 온보딩 제출 `email`이 미검증이거나 검증한 이메일과 불일치 |
| `AUTH_PHONE_VERIFICATION_FAILED` | 422 | 연락처(SMS) 인증번호 불일치 또는 만료(미발송·만료·오입력) — 임대인 |
| `AUTH_PHONE_NOT_VERIFIED` | 422 | 임대인 온보딩 제출 또는 프로필 연락처 변경 시 `phoneNumber`가 미검증이거나 검증한 번호와 불일치 |
| `AUTH_REQUIRED_AGREEMENT_MISSING` | 422 | 필수 약관(이용약관/개인정보처리방침) 미동의(약관 동의 `POST /auth/terms`) |
| `AUTH_TERMS_AGREEMENT_REQUIRED` | 422 | 약관 미동의(`PENDING`) 상태로 온보딩 제출 또는 연락처 인증(약관 동의 선행 필요) |
| `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED` | 422 | 사업자번호 검증(`POST /auth/business/verify`) 시 검증 서비스 조회 결과 미등록·휴업·폐업(진위·상태 검증 실패) |
| `AUTH_ONBOARDING_REQUIRED` | 403 | 온보딩 미완료(`PENDING`/`TERMS_AGREED`) 상태로 보호 API 접근 |
| `AUTH_ONBOARDING_ALREADY_COMPLETED` | 409 | 이미 온보딩 완료(ACTIVE)된 사용자가 온보딩 재요청 |
| `AUTH_INVALID_REFRESH_TOKEN` | 401 | refresh 토큰 만료/위조/무효화/재사용 탐지 |
| `USER_NOT_FOUND` | 404 | 대상 사용자가 없거나 탈퇴되어 조회 불가 |
| `USER_ALREADY_WITHDRAWN` | 409 | 이미 탈퇴(WITHDRAWN)된 사용자에 대한 탈퇴 재요청 |
