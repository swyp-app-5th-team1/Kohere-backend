    

    

    # User Stories & Acceptance Criteria

> Kohere 핵심 기능(8종)의 **백엔드 유저 스토리**와 인수 조건(AC, Given/When/Then)이다.
> 작성 형식: [user-story-template](user-story-template.md). 각 기능의 API는 [api 스펙](../api/specs/)으로 연결된다.
> 에러 코드/형식은 [error-response-guide](../api/error-response-guide.md), 설계 규약은 [api-design-guide](../api/api-design-guide.md)를 따른다.

## 목차

- 1. 소셜 로그인 · 온보딩 — [API 스펙](../api/specs/01-auth-onboarding.md)
- 2. 맞춤 진단 & 매물 추천 — [API 스펙](../api/specs/02-diagnosis-recommendation.md)
- 3. 매물 탐색 · 찜 — [API 스펙](../api/specs/03-listings-favorites.md)
- 4. 매물 예약(신청) · (후속) 문의·인앱 채팅 — [API 스펙](../api/specs/04-booking-inquiry-chat.md)
- 5. 커뮤니티 (게시판 · 동네친구) — [API 스펙](../api/specs/05-community.md)
- 6. 게이미피케이션 (퀴즈) — [API 스펙](../api/specs/06-gamification.md)
- 7. 신고 처리 — [API 스펙](../api/specs/07-reports.md)
- 8. 생활 팁 (주제별 생활 정보) — [API 스펙](../api/specs/08-life-tips.md)

---

## 1. 소셜 로그인 · 온보딩

> 관련 API 스펙: [01-auth-onboarding](../api/specs/01-auth-onboarding.md)

외국인 사용자가 Apple/Google 소셜 계정으로 진입해 서버 자체 JWT를 발급받고, 신규 회원은 **약관 동의 → 본인 확인 → 온보딩** 순서로 가입 단계를 거친 뒤에야 보호 API를 사용할 수 있게 한다(본인 확인은 세입자 이메일 인증·임대인 연락처 SMS 인증으로 갈린다). 사용자 상태는 `PENDING`(소셜 검증) → `TERMS_AGREED`(약관 동의 완료) → `ACTIVE`(온보딩 완료)로 전이한다(약관 동의와 온보딩은 분리된 단계). 세입자 온보딩 필수 정보는 이름·성별·생년월일·국적(국가 코드 — 화면엔 국가만 받지만 국기까지 수집, `countries` 참조)·직업·이메일·비자정보이며, 닉네임은 서버가 `형용사 + 사물`로 자동 배정한다. 토큰 재발급·로그아웃·탈퇴·프로필 조회/수정까지 인증 생애주기 전체를 다룬다.

사용자는 **세입자(외국인)와 임대인** 두 역할(`userType`: `TENANT`/`LANDLORD`)로 나뉜다. 소셜 로그인(US-1-1)·약관 동의(US-1-7)까지는 **두 역할이 같은 공통 흐름**을 타고, **이후 본인 확인·온보딩 단계에서 역할이 갈린다** — 세입자는 이메일 인증(US-1-6)을 거쳐 위 정보를 `POST /auth/onboarding`(US-1-2)으로 제출하고, 임대인은 이름(성·이름을 합친 단일 `name`)·생년월일·연락처(전화)를 `POST /auth/landlord/onboarding`(US-1-9)으로 제출하되 그 선행으로 **연락처를 SMS 인증번호로 검증**(US-1-10)하는 단계만 거친다(약관 동의 + 연락처 인증만으로 온보딩이 완료된다). **사업자등록번호 검증**(US-1-8)은 온보딩 선행 단계가 아니라 **온보딩을 마친(ACTIVE) 임대인이 나중에(매물 등록 시점) 정식 access 토큰으로 호출하는 온보딩과 분리된 무상태(stateless) 검증 API**다(국세청 사업자등록정보 기반 외부 검증 API 사용). 임대인은 성별·직업·비자정보와 **이메일을 수집하지 않으며(생년월일은 세입자와 동일하게 수집), 사업자등록번호도 온보딩에서 수집하지 않는다** — 단 국적(`country`=`KR`)·표시 언어(`lang`=`ko`)는 클라이언트가 보내지 않고 **서버가 온보딩 시 고정 부여**한다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 `country` 미수집" 결정을 개정). `userType`은 온보딩 제출 엔드포인트로 확정되며, 그 이전(소셜 로그인·약관) 단계는 역할을 강제하지 않는다. 아래 US-1-1·US-1-3 ~ US-1-5·US-1-7은 별도 표기가 없으면 두 역할 공통이고, **US-1-2·US-1-6은 세입자(외국인) 전용**, **US-1-9·US-1-10은 임대인 온보딩 전용**, **US-1-8은 임대인 온보딩 후(ACTIVE) 매물 등록 시점의 임대인 전용 단계**다.

> 관련 NFR: [non-functional-requirements](non-functional-requirements.md) — 4. 보안(토큰/민감정보 보호), 1. 성능(소셜 검증 응답시간), 5. 관측성(인증 실패 로깅·마스킹). 구체 목표값은 NFR 문서 확정 전이라 (확인 필요).

---

### US-1-1 — 소셜 로그인으로 진입해 서버 토큰 발급받기

**As a** 외국인 사용자
**I want** 앱에서 받은 Apple/Google `idToken`을 서버에 넘겨 검증받고 서버 자체 JWT(access+refresh)를 발급받기를
**So that** 별도 비밀번호 없이 안전하게 로그인하고, 기존 회원이면 바로 서비스를, 신규면 약관 동의 화면으로 이동할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(소셜 토큰 서명·aud·iss·exp 검증, refresh 토큰 서버 보관/해시), 성능(provider 검증 외부호출 타임아웃·관측성)

**AC (Given / When / Then)**

- **정상 — 기존 회원 로그인**
  Given 해당 provider 계정으로 가입·온보딩을 완료한 `ACTIVE` 회원이 존재하고
  When 유효한 `idToken`으로 `POST /api/v1/auth/social-login`을 호출하면
  Then `200 OK` + 공통 래퍼 `data`에 `accessToken`/`refreshToken`/`onboardingRequired=false`/`status="ACTIVE"`/`tokenType="Bearer"`/`expiresIn`이 내려오고, refresh 토큰은 서버에 해시 저장되어 재발급에 사용된다. 앱은 홈으로 이동한다.
- **정상 — 신규 회원 약관 동의 유도**
  Given 해당 provider 계정으로 가입한 회원이 없고
  When 유효한 `idToken`으로 `POST /api/v1/auth/social-login`을 호출하면
  Then `200 OK` + `data.onboardingRequired=true`·`data.status="PENDING"`으로 응답하고, 서버는 provider/providerUserId/email만 보유한 **온보딩 미완료(PENDING)** 사용자 레코드를 생성한다. 이때 발급되는 access 토큰은 온보딩 흐름(약관 동의·본인 확인(세입자 이메일 인증·임대인 연락처/사업자번호 검증)·온보딩) API만 통과시키는 클레임(`onboardingCompleted=false`)을 가지며, refresh 토큰은 발급하지 않는다(`refreshToken=null`). 앱은 `status`로 분기해 다음으로 **약관 동의 화면**(US-1-7)으로 이동한다. (확인 필요: 온보딩 전용 임시 토큰 만료시간)
- **정상 — 가입 미완료 회원 재로그인(재개 지점 분기)**
  Given 소셜 로그인은 했으나 가입을 끝내지 못한 회원이 다시 로그인하고(신규 행은 만들지 않음)
  When 유효한 `idToken`으로 `POST /api/v1/auth/social-login`을 호출하면
  Then `200 OK` + `data.onboardingRequired=true`(refresh `null`)로 응답하되 **`data.status`로 재개 지점을 분기**한다 — **약관 미동의면 `status="PENDING"` → 약관 동의 화면(US-1-7)**, 약관까지 동의했으나 온보딩 미완료면 `status="TERMS_AGREED"` → 온보딩 화면(US-1-2). (온보딩 토큰으로는 `GET /users/me`가 `403`이라 상태를 따로 조회할 수 없으므로 이 응답의 `status`로 판단한다)
- **입력 검증 실패**
  Given `provider`가 누락(null)이거나 `idToken`이 빈 문자열이면
  When `POST /api/v1/auth/social-login`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`(field/reason)를 반환한다. (`provider`가 `APPLE`/`GOOGLE` 외 enum 문자열이면 역직렬화 단계에서 거부되어 `400` + `error.code=MALFORMED_REQUEST`로 처리한다 — 둘 다 별도 도메인 코드가 아닌 표준 입력 오류)
- **인증·권한 — 소셜 검증 실패**
  Given `idToken`의 서명이 위조되었거나 `aud`/`iss`가 우리 앱과 불일치하거나 `exp`가 지났으면
  When `POST /api/v1/auth/social-login`을 호출하면
  Then `401` + `error.code=AUTH_INVALID_SOCIAL_TOKEN`을 반환하고, 토큰 원문은 로그에 남기지 않는다(마스킹).
- **인증·권한 — provider 연동 실패도 검증 실패로 처리**
  Given Apple/Google 공개키(JWKS) 조회 또는 검증 요청이 타임아웃/네트워크 오류로 실패하면
  When `POST /api/v1/auth/social-login`을 호출하면
  Then 연동 실패를 포함한 모든 OIDC 검증 실패를 `401` + `error.code=AUTH_INVALID_SOCIAL_TOKEN`으로 처리하고, 회원 레코드를 생성하지 않는다.

---

### US-1-2 — 필수 온보딩 정보 제출하기 (세입자 전용)

**As a** 약관 동의·이메일 인증을 마친(TERMS_AGREED) 세입자(외국인) 사용자
**I want** 이름·성·성별·생년월일·국적·직업·이메일·비자정보를 한 번에 제출하기를(이메일은 사전 인증, 닉네임은 서버 자동 배정)
**So that** 회원 가입을 완료하고 정식 access/refresh 토큰으로 보호 기능을 이용할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(비자정보·이메일 등 민감정보 저장·로그 마스킹), 보안(이메일 소유 인증)
- 선행: 약관 동의(US-1-7, `TERMS_AGREED`)와 이메일 인증(US-1-6)이 완료되어야 한다.

**AC (Given / When / Then)**

- **정상 — 온보딩 완료**
  Given 약관 동의를 마친(`TERMS_AGREED`) 사용자의 유효한 온보딩 토큰을 보유하고 제출 `email`이 사전 인증(US-1-6)되어 있으며
  When 모든 필수 필드(`firstName`·`lastName`·`gender`·`birthDate`·`country`·`occupation`·`email`·`visaType`)를 담아(표시 언어 `lang`은 **선택** — 보내면 그 값으로, 안 보내면 미설정으로 두어 표시 시 `en` 폴백) `POST /api/v1/auth/onboarding`을 호출하면(약관 필드는 담지 않음 — 이미 US-1-7에서 동의·기록)
  Then `200 OK` + `data`에 완성된 프로필(서버가 자동 배정한 `nickname` 포함)과 정식 `accessToken`/`refreshToken`을 내려주고, 사용자 상태를 `TERMS_AGREED` → `ACTIVE`로 전이한다. (상태 전이 액션이므로 신규 리소스 생성이 아닌 `200`을 쓴다. `nickname`은 서버가 형용사 풀·사물 풀에서 골라 `형용사 + 사물`로 조합하고 전역 유니크 충돌 시 재조합해 배정하며 요청 본문에 담지 않는다)
- **입력 검증 실패**
  Given `firstName`/`lastName`/`country`가 비었거나(`country`가 `countries`에 없는 ISO 코드이거나), `gender`가 `MALE`/`FEMALE` 외 값이거나, `occupation`이 정의된 enum(`UNDERGRADUATE_STUDENT`·`GRADUATE_STUDENT`·`EXCHANGE_STUDENT`·`LANGUAGE_TEACHING`·`MANUFACTURING_PRODUCTION`·`BUSINESS_TRADE`·`ETC`) 외 값이거나, `birthDate`가 `YYYY-MM-DD` 형식 위반/미래 날짜이거나, `visaType`이 정의된 enum(상수명: `SHORT_TERM_VISIT`·`STUDENTS_TRAINEES`·`NON_PROFESSIONAL_WORKERS`·`WORKING_HOLIDAY_WORK_AND_VISIT`·`OVERSEAS_KOREANS`·`FAMILY_MARRIAGE_MIGRANTS`·`PERMANENT_RESIDENTS`·`PROFESSIONALS`·`DIPLOMATIC_OFFICIAL_AND_OTHERS`·`ETC`) 외 값이거나, `email` 형식이 어긋나면
  When `POST /api/v1/auth/onboarding`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`로 위반 필드를 반환한다.
- **비즈니스 규칙 — 약관 미동의 상태(우선 판정)**
  Given 약관 동의를 아직 마치지 않은(`PENDING`) 사용자가
  When `POST /api/v1/auth/onboarding`을 호출하면
  Then `422` + `error.code=AUTH_TERMS_AGREEMENT_REQUIRED`를 반환하고 상태를 전이하지 않는다(약관 동의 US-1-7 선행 필요 — 이메일 미인증보다 **약관 동의 안내가 먼저**).
- **비즈니스 규칙 — 이메일 미인증**
  Given 약관까지 동의한(`TERMS_AGREED`) 사용자의 제출 `email`이 인증번호로 검증(US-1-6)되지 않았거나 검증한 이메일과 다르면
  When `POST /api/v1/auth/onboarding`을 호출하면
  Then `422` + `error.code=AUTH_EMAIL_NOT_VERIFIED`를 반환하고 상태를 전이하지 않는다.
- **인증·권한 — 잘못된/누락 토큰**
  Given `Authorization` 헤더가 없거나 토큰이 위조/만료되었으면
  When `POST /api/v1/auth/onboarding`을 호출하면
  Then 누락·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`를 반환한다.
- **경계·동시성 — 중복 온보딩**
  Given 이미 온보딩을 완료(`ACTIVE`)한 사용자이거나, 동일 사용자가 온보딩 요청을 동시에 두 번 보내면
  When `POST /api/v1/auth/onboarding`을 호출하면
  Then 한 요청만 성공하고 나머지는 `409` + `error.code=AUTH_ONBOARDING_ALREADY_COMPLETED`를 반환한다(서버는 사용자 단위 멱등 처리).

---

### US-1-3 — 만료된 access 토큰을 refresh로 재발급받기

**As a** 로그인 상태가 유지되어야 하는 사용자
**I want** access 토큰이 만료됐을 때 refresh 토큰으로 새 access 토큰을 재발급받기를
**So that** 다시 소셜 로그인하지 않고도 끊김 없이 보호 API를 호출할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(refresh 토큰 회전·재사용 탐지), 성능(재발급 경로 무상태·저지연)

**AC (Given / When / Then)**

- **정상 — 재발급**
  Given 서버에 저장된 유효한(미만료·미무효화) refresh 토큰을 보유하고
  When `POST /api/v1/auth/reissue`에 `refreshToken`을 담아 호출하면
  Then `200 OK` + 새 `accessToken`(및 회전 정책 시 새 `refreshToken`)을 반환하고, 기존 refresh 토큰은 회전 시 무효화한다. (확인 필요: refresh 회전 적용 여부)
- **입력 검증 실패**
  Given 본문에 `refreshToken`이 없거나 빈 문자열이면
  When `POST /api/v1/auth/reissue`를 호출하면
  Then `400` + `error.code=INVALID_INPUT`을 반환한다.
- **인증·권한 — 만료/위조/무효화된 refresh**
  Given refresh 토큰이 만료·위조되었거나 로그아웃/탈퇴로 이미 무효화되었으면
  When `POST /api/v1/auth/reissue`를 호출하면
  Then `401` + `error.code=AUTH_INVALID_REFRESH_TOKEN`을 반환하고 새 토큰을 발급하지 않는다.
- **경계·동시성 — refresh 재사용 탐지**
  Given 회전으로 이미 한 번 사용·폐기된 refresh 토큰을 다시 제출하면
  When `POST /api/v1/auth/reissue`를 호출하면
  Then `401` + `error.code=AUTH_INVALID_REFRESH_TOKEN`을 반환하고, 해당 사용자의 모든 refresh 토큰을 무효화한다(탈취 의심 대응). (확인 필요: 일괄 무효화 정책)

---

### US-1-4 — 로그아웃·회원 탈퇴로 세션과 계정 정리하기

**As a** 로그인한 사용자
**I want** 로그아웃으로 현재 세션의 refresh 토큰을 무효화하거나, 탈퇴로 계정을 삭제하기를
**So that** 기기 분실·서비스 이탈 시 내 토큰과 개인정보가 더는 유효하지 않게 만들 수 있다.

- 우선순위: **Mid**
- 관련 NFR: 보안(토큰 즉시 무효화), 보안(탈퇴 시 개인정보 파기/익명화 — 확인 필요: 보존 기간)

**AC (Given / When / Then)**

- **정상 — 로그아웃**
  Given 유효한 access 토큰과 refresh 토큰을 보유한 사용자가
  When `POST /api/v1/auth/logout`에 `refreshToken`을 담아 호출하면
  Then `204 No Content`를 반환하고 해당 refresh 토큰을 서버에서 무효화한다(이후 reissue 불가).
- **정상 — 회원 탈퇴**
  Given 인증된 사용자(`ACTIVE` 또는 `PENDING`)가
  When `DELETE /api/v1/users/me`를 호출하면
  Then `204 No Content`를 반환하고 사용자 상태를 `WITHDRAWN`으로 전이하며 모든 refresh 토큰을 무효화한다(개인정보 파기/익명화는 정책 — 확인 필요).
- **정상 — Apple 연동 폐기(App Store 5.1.1(v))**
  Given Apple로 가입해 `apple_refresh_token`이 저장된 사용자가
  When `DELETE /api/v1/users/me`로 탈퇴하면
  Then `social_accounts` 매핑 삭제 전에 Apple `POST /auth/revoke`(`token_type_hint=refresh_token`)로 앱↔Apple ID 연동을 폐기한다([ADR-0031](../adr/0031-apple-sign-in-authorization-code-flow.md)). 이 폐기는 **best-effort** — Apple 장애·이미 폐기(`invalid_grant`)여도 탈퇴는 `204`로 완료한다(저장된 토큰이 없으면 스킵).
- **입력 검증 실패**
  Given 로그아웃 요청에 `refreshToken`이 누락되면
  When `POST /api/v1/auth/logout`을 호출하면
  Then `400` + `error.code=INVALID_INPUT`을 반환한다.
- **인증·권한**
  Given access 토큰이 없거나 만료/위조되었으면
  When `POST /api/v1/auth/logout` 또는 `DELETE /api/v1/users/me`를 호출하면
  Then `401` + `error.code=UNAUTHENTICATED`(만료는 `TOKEN_EXPIRED`)을 반환한다.
- **경계·동시성 — 이미 탈퇴/이미 무효화**
  Given 이미 `WITHDRAWN`된 사용자이거나, 동일 refresh 토큰으로 로그아웃을 두 번 호출하면
  When 동작을 재호출하면
  Then 탈퇴 재요청은 `409` + `error.code=USER_ALREADY_WITHDRAWN`, 이미 무효화된 토큰 로그아웃은 멱등하게 `204`로 처리한다.

---

### US-1-5 — 내 프로필 조회·수정하기

**As a** 온보딩을 완료한 사용자
**I want** 내 프로필을 조회하고 일부 필드를 수정하기를
**So that** 비자정보·국적·직업 갱신 등 내 정보를 최신 상태로 유지할 수 있다.

- 우선순위: **Mid**
- 관련 NFR: 보안(본인 리소스만 접근), 보안(민감정보 응답 마스킹 정책 — 확인 필요)
- 백엔드 관점: `GET`/`PATCH /api/v1/users/me`는 세입자·임대인 **공통 엔드포인트**이며, `userType`(`TENANT`/`LANDLORD`)에 따라 응답·수정 가능 필드가 갈린다.
  - **세입자(`TENANT`)**: 응답·수정에 세입자 전용 필드(`gender`·`country`(코드)+`countryName`·`countryFlag`·`occupation`·`visaType`)와 `birthDate`(임대인과 공통)·`lang`을 포함한다. `lang`(표시 언어, ISO 639-1 소문자, 지원 `en`·`ko`·`ja`)은 사용자가 앱 **지구본**에서 직접 고르는 **선택** 필드이며, 미설정이면(NULL) 표시 시 `en`으로 폴백한다. 다국어 화면(진단 문항·퀴즈·생활 팁) 번역이 이 값을 따른다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141), US-2-6·US-6-3·US-8-3). **임대인은 서버가 `'ko'` 고정이며 변경할 수 없다.**
  - **임대인(`LANDLORD`)**: 단일 `name`(내부 저장은 세입자와 동일한 `FullName` VO의 `firstName` 재사용 — `lastName`은 미사용/`null`, 별도 `name` 컬럼/필드를 두지 않음. API 요청·응답 필드명은 `name` 유지)·`birthDate`·`nickname`·`phoneNumber`·`status`·약관 동의 상태·`createdAt`·`country`+`countryName`·`countryFlag`·`lang`을 조회하고, 수정은 `name`·`marketingAgreed`를 자유 수정하며 `phoneNumber`는 SMS 재인증을 거쳐 변경한다. **임대인은 `email`을 수집하지 않아 응답에 포함하지 않는다**([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)). **임대인의 `lang`(`'ko'`)·`country`(`'KR'`)는 서버가 온보딩에서 고정으로 심는 값이라 응답에는 나오되 이 경로로 수정할 수 없다**(ADR-0034의 "임대인 country 미수집" 결정을 개정한다). 세입자 전용 필드(`gender`/`occupation`/`visaType`)는 **임대인 응답에 포함하지 않지만, `birthDate`는 임대인도 온보딩에서 수집하므로 응답에 포함한다**. `businessRegistrationNumber`는 원문 비저장이라 응답에 포함하지 않고 이 경로로 수정할 수 없다(변경 시 외부 사업자등록정보 재검증 필요). `phoneNumber`는 SMS 인증(US-1-10)된 값으로 본인 조회 시 평문 반환하되 타 사용자/로그 노출은 마스킹하며, **변경 시 SMS 재인증(US-1-10)이 필요하다 — 새 번호를 재인증해 VERIFIED된 뒤에만 반영하고 미인증·불일치는 `422 AUTH_PHONE_NOT_VERIFIED`다**.
  - 두 역할 공통으로 `userType`·`nickname`은 불변이고, 세입자 `email`은 재인증이 필요해 이 경로로 수정하지 않는다(임대인은 `email` 미보유).

**AC (Given / When / Then)**

- **정상 — 조회(세입자)**
  Given 유효한 access 토큰을 보유한 `ACTIVE` 세입자(`userType=TENANT`)가
  When `GET /api/v1/users/me`를 호출하면
  Then `200 OK` + `data`에 본인 프로필(이름·`nickname`·성별·생년월일·`country`(코드)+서버 resolve `countryName`·`countryFlag`·`occupation`·`email`·`visaType`·`lang`(표시 언어 — 미설정이면 응답에서 생략)·약관 동의 상태)을 반환한다.
- **정상 — 부분 수정(세입자)**
  Given 유효한 access 토큰을 보유한 세입자(`userType=TENANT`)가
  When `PATCH /api/v1/users/me`에 변경할 필드(예: `country`, `occupation`, `visaType`, `lang`, `marketingAgreed`)만 담아 호출하면
  Then `200 OK` + 수정된 프로필을 반환하고, 전송하지 않은 필드는 변경하지 않는다(미전송 ≠ 값 비움). `nickname`은 시스템 배정값이라 수정 대상이 아니며, `email` 변경은 재인증이 필요해 이 엔드포인트로는 처리하지 않는다(확인 필요).
- **정상 — 표시 언어 직접 선택(세입자)**
  Given 유효한 access 토큰을 보유한 세입자가
  When `PATCH /api/v1/users/me`에 `{ "lang": "en" }`만 담아 호출하면
  Then `200 OK` + `lang="en"`을 반환하고 `country`는 변경하지 않으며, 이후 진단 문항·퀴즈·생활 팁 표시 텍스트가 `en`으로 내려온다(등록 국가와 무관 — US-2-6·US-6-3·US-8-3).
- **정상 — 조회(임대인)**
  Given 유효한 access 토큰을 보유한 `ACTIVE` 임대인(`userType=LANDLORD`)이
  When `GET /api/v1/users/me`를 호출하면
  Then `200 OK` + `data`에 `userType="LANDLORD"`·`name`(=`FullName.firstName`)·`birthDate`·`nickname`·`phoneNumber`·`status`·약관 동의 상태(`termsOfServiceAgreed`/`privacyPolicyAgreed`/`marketingAgreed`)·`createdAt`과 함께 서버 고정값인 `country="KR"`+서버 resolve `countryName`·`countryFlag`·`lang="ko"`를 반환한다. 세입자 전용 필드(`gender`/`occupation`/`visaType`)와 `email`(임대인 미수집)·`businessRegistrationNumber`는 응답에 포함하지 않는다(`birthDate`는 임대인도 수집·반환).
- **정상 — 부분 수정(임대인)**
  Given 유효한 access 토큰을 보유한 임대인(`userType=LANDLORD`)이
  When `PATCH /api/v1/users/me`에 자유 수정 필드(`name`·`marketingAgreed`) 중 일부(예: `name`)만 담아 호출하면
  Then `200 OK` + 수정된 프로필을 반환하고, 전송하지 않은 필드는 변경하지 않는다(미전송 ≠ 값 비움). `name`은 내부적으로 `FullName.firstName`에 매핑해 저장한다. `birthDate`(온보딩에서 확정, 조회 전용)·`businessRegistrationNumber`(변경 시 외부 사업자등록정보 재검증 필요)·`userType`·`nickname`·`lang`·`country`(서버 고정 `'ko'`/`'KR'` — 표시 언어 변경은 세입자만 가능, [ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정(#141))는 이 경로로 수정할 수 없다(임대인은 `email` 미보유). `phoneNumber`는 변경 시 SMS 재인증(US-1-10)이 필요하다(아래 "연락처 변경 시 재인증" 참조).
- **비즈니스 규칙 — 임대인 연락처 변경 시 SMS 재인증(임대인)**
  Given 임대인이 새 `phoneNumber`로 변경하려 하나 그 번호를 SMS 재인증(US-1-10)하지 않았으면(VERIFIED 마커 없음·불일치)
  When `PATCH /api/v1/users/me`에 새 `phoneNumber`를 담아 호출하면
  Then `422` + `error.code=AUTH_PHONE_NOT_VERIFIED`를 반환하고 연락처를 변경하지 않는다. 새 번호를 SMS 인증번호로 재인증(`POST /auth/phone/verification-code`·`/auth/phone/verify`)해 VERIFIED된 뒤에 다시 호출하면 변경이 반영된다(온보딩 시 연락처 인증과 동일한 발송·확인을 정식 토큰 컨텍스트에서 재사용).
- **입력 검증 실패**
  Given 수정 본문의 `gender`/`visaType`/`occupation`이 정의된 enum 외 값이거나 `birthDate`가 형식/범위 위반이거나 `country`가 빈값이거나 `lang`이 지원 목록(`en`·`ko`·`ja`) 밖 코드(예: `"xx"`·`"KO"`(대문자)·`"ko-KR"`(지역 태그)·`"zh"`(콘텐츠 미시드))이면
  When `PATCH /api/v1/users/me`를 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`를 반환한다.
- **인증·권한 — 온보딩 미완료 접근**
  Given 온보딩 미완료(PENDING·TERMS_AGREED) 토큰으로 보호 프로필 API에 접근하면
  When `GET /api/v1/users/me` 또는 `PATCH /api/v1/users/me`를 호출하면
  Then `403` + `error.code=AUTH_ONBOARDING_REQUIRED`를 반환한다(인증은 됐으나 온보딩 미완료로 접근 불가).
- **경계 — 탈퇴/부재 사용자**
  Given 토큰은 유효하나 해당 사용자가 `WITHDRAWN`이거나 삭제되어 없으면
  When `GET /api/v1/users/me`를 호출하면
  Then `404` + `error.code=USER_NOT_FOUND`를 반환한다.

---

### US-1-6 — 온보딩 중 이메일 인증하기 (세입자 전용)

**As a** 약관 동의를 마쳤으나 온보딩 미완료(TERMS_AGREED) 세입자(외국인) 사용자
**I want** 온보딩에서 입력한 이메일로 인증번호를 받아 확인하기를
**So that** 본인 소유 이메일임을 증명하고(US-1-2 세입자 온보딩 제출의 선행 조건) 가입을 완료할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(이메일 소유 인증·인증번호 해시 보관·재발송/시도 레이트리밋), 보안(이메일·인증번호 로그 마스킹)
- 백엔드 관점: `auth`가 인증번호를 생성해 아웃바운드 포트 `VerificationEmailSender`(인프라 어댑터: SMTP)로 **동기 발송**하고, **발송에 성공한 뒤에만** 인증번호를 **해시로 보관**(Redis, TTL 자동 소멸)한다. provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답한다. 검증 성공 시 해당 사용자의 이메일을 `VERIFIED`로 마킹하고, 온보딩 제출(US-1-2)에서 제출 `email`과 대조한다.

**AC (Given / When / Then)**

- **정상 — 인증번호 발송**
  Given 약관 동의를 마친(`TERMS_AGREED`) 사용자가 이메일을 입력하고
  When `POST /api/v1/auth/email/verification-code`에 `{ email }`을 담아 호출하면
  Then `200 OK` + `data.expiresIn`(만료 초)을 반환하고 해당 이메일로 인증번호를 발송한다. 메일 발송에 성공한 뒤에만 인증번호 챌린지를 저장하며(인증번호 원문은 저장·로그하지 않고 해시로만 보관), `email`은 마스킹해 반환한다.
- **비즈니스 규칙 — 약관 미동의(선행 게이트)**
  Given 약관 동의를 아직 마치지 않은(`PENDING`) 사용자가
  When `POST /api/v1/auth/email/verification-code`를 호출하면
  Then `422` + `error.code=AUTH_TERMS_AGREEMENT_REQUIRED`를 반환하고 인증번호를 발송하지 않는다(약관 동의 US-1-7 선행 필요 — 이메일 인증은 약관 동의 이후 단계). (`/email/verify`는 약관 게이트를 두지 않으며, PENDING은 챌린지 부재로 `AUTH_EMAIL_VERIFICATION_FAILED`가 난다 — 아래 "인증번호 불일치/만료" 참조)
- **장애 — 메일 발송 실패**
  Given 메일 provider 장애·타임아웃 등으로 인증번호 발송이 실패하면
  When `POST /api/v1/auth/email/verification-code`를 호출하면
  Then `502` + `error.code=UPSTREAM_ERROR`를 반환하고, 인증번호 챌린지를 저장하지 않아 클라이언트가 재시도하도록 유도한다(동기 발송 정책).
- **정상 — 인증번호 확인**
  Given 발송된 인증번호가 유효(미만료·시도 미초과)하고
  When `POST /api/v1/auth/email/verify`에 `{ email, code }`를 담아 호출하면
  Then `200 OK` + `data.verified=true`를 반환하고 해당 이메일을 `VERIFIED`로 마킹해 온보딩 제출에 사용할 수 있게 한다.
- **입력 검증 실패**
  Given `email`이 누락·형식 위반이거나 확인 요청에 `code`가 빈 문자열이면
  When 이메일 인증 API를 호출하면
  Then `400` + `error.code=INVALID_INPUT`을 반환한다.
- **비즈니스 규칙 — 인증번호 불일치/만료**
  Given 잘못된 인증번호이거나 만료(미발송 포함)된 인증번호로
  When `POST /api/v1/auth/email/verify`를 호출하면
  Then `422` + `error.code=AUTH_EMAIL_VERIFICATION_FAILED`를 반환하고 이메일을 검증 완료로 표시하지 않는다.
- **경계·동시성 — 재발송/시도 레이트리밋**
  Given 짧은 시간에 인증번호 재발송을 반복하거나 검증 시도 상한을 초과하면
  When 이메일 인증 API를 호출하면
  Then `429` + `error.code=TOO_MANY_REQUESTS`를 반환한다(확인 필요: 재발송 간격·시도 상한 임계값).
- **인증·권한 — 잘못된/누락 토큰**
  Given `Authorization` 헤더가 없거나 토큰이 위조/만료되었으면
  When 이메일 인증 API를 호출하면
  Then 누락·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`를 반환한다.

---

### US-1-7 — 약관 동의 화면에서 약관 동의하기

**As a** 소셜 로그인은 마쳤으나 약관 미동의(PENDING) 사용자
**I want** 약관 동의 화면에서 이용약관·개인정보처리방침(+선택 마케팅)에 동의하기를
**So that** 가입 흐름의 첫 단계를 마쳐 `TERMS_AGREED`가 되고, 이어서 온보딩(US-1-2)을 진행할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(필수 약관 동의 시점·버전 기록), 보안(약관 버전 변경 시 재동의 정책 — 확인 필요)
- 백엔드 관점: `auth`가 `user`의 약관 동의 공개명령으로 `consent`(동의 3종 + `agreedAt` + `termsVersion`)를 기록하고 상태를 `PENDING` → `TERMS_AGREED`로 전이한다. `termsVersion`은 클라이언트가 보내지 않고 서버가 설정값(`app.terms.version`)을 기록한다([ADR-0012](../adr/0012-terms-version-management.md)). 토큰은 갱신하지 않는다(상태만 전이).

**AC (Given / When / Then)**

- **정상 — 약관 동의 완료**
  Given 소셜 로그인을 마친 `PENDING` 사용자의 유효한 온보딩 토큰을 보유하고
  When `termsOfServiceAgreed=true`·`privacyPolicyAgreed=true`(+선택 `marketingAgreed`)를 담아 `POST /api/v1/auth/terms`를 호출하면
  Then `200 OK` + `data`에 `status="TERMS_AGREED"`·동의 내용·`agreedAt`을 반환하고, 사용자 상태를 `PENDING` → `TERMS_AGREED`로 전이하며 `termsVersion`을 서버가 기록한다.
- **비즈니스 규칙 — 필수 동의 누락**
  Given 필수 약관(`termsOfServiceAgreed` 또는 `privacyPolicyAgreed`) 중 하나라도 `false`이면
  When `POST /api/v1/auth/terms`를 호출하면
  Then `422` + `error.code=AUTH_REQUIRED_AGREEMENT_MISSING`을 반환하고 상태를 전이하지 않는다(형식은 맞으나 비즈니스 규칙 위반 → 422).
- **입력 검증 실패**
  Given 필수 동의 boolean(`termsOfServiceAgreed`/`privacyPolicyAgreed`)이 누락(null)이면
  When `POST /api/v1/auth/terms`를 호출하면
  Then `400` + `error.code=INVALID_INPUT`을 반환한다.
- **인증·권한 — 잘못된/누락 토큰**
  Given `Authorization` 헤더가 없거나 토큰이 위조/만료되었으면
  When `POST /api/v1/auth/terms`를 호출하면
  Then 누락·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`를 반환한다.
- **경계·동시성 — 중복 호출/이미 온보딩 완료**
  Given 이미 `TERMS_AGREED`인 사용자가 (네트워크 재시도 등으로) 다시 호출하거나, 이미 온보딩을 완료(`ACTIVE`)한 사용자가
  When `POST /api/v1/auth/terms`를 호출하면
  Then `TERMS_AGREED` 중복 호출은 상태·동의를 바꾸지 않고 멱등하게 `200 OK`(현재 동의 상태)를 반환하고(의도적 재동의 아님 — 마케팅 동의 변경은 `PATCH /users/me`로 처리), 이미 `ACTIVE`이면 `409` + `error.code=AUTH_ONBOARDING_ALREADY_COMPLETED`를 반환한다.

---

### US-1-8 — 사업자등록번호 검증하기 (임대인 전용, 온보딩 후)

**As a** 온보딩을 완료한(ACTIVE) 임대인
**I want** 정식 access 토큰으로 사업자등록번호를 외부 사업자등록정보 검증 API(국세청 사업자등록정보 기반)로 진위·영업 상태까지 확인받기를
**So that** (매물 등록 등) 실제 영업 중인 사업자임을 증명해야 하는 시점에 온보딩과 분리된 무상태 검증으로 즉시 확인받을 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(사업자등록번호 등 민감정보 저장·로그 마스킹), 보안·신뢰성(외부 연동 타임아웃·관측성·장애 시 degrade), 보안(검증 시도 레이트리밋)
- 선행: 온보딩 완료(US-1-9, `ACTIVE`)와 정식 access 토큰(`ROLE_USER`)이 필요하다. 이 단계는 **임대인 온보딩 후(ACTIVE) 매물 등록 시점의 임대인 전용**으로, 온보딩(US-1-9)의 선행 게이트가 아니며 세입자 온보딩(US-1-2)에는 없다.
- 백엔드 관점: 온보딩과 **분리된 무상태(stateless) 검증 API**다 — 검증 결과를 서버에 저장하지 않고(Redis 마커 없음·`user.businessRegistrationNumberHash`에도 쓰지 않음) **응답(HTTP body)에만** 담아 회신한다. `auth`가 사업자등록번호를 아웃바운드 포트 `BusinessRegistryVerifier`(인프라 어댑터: 사업자등록정보 검증 API — 국세청 사업자등록정보 기반, 구체 provider는 [ADR-0033](../adr/0033-business-registry-verification.md))로 **동기 검증**해 정상(계속) 사업자면 `verified:true`를, 미등록·휴업·폐업·진위 실패면 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`를 응답한다. 검증 API 장애·타임아웃 등 연동 실패는 `502 UPSTREAM_ERROR`로 응답해 재시도를 유도한다(임대인 연락처 인증 US-1-10·세입자 이메일 인증 US-1-6의 동기 호출·실패 정책과 대칭). 인가는 정식 토큰(`ACTIVE`, `ROLE_USER`)이 필수이며, 온보딩 토큰(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`)으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌(`userType=TENANT`) `ACTIVE` 사용자면 `403 FORBIDDEN`이다.

**AC (Given / When / Then)**

- **정상 — 사업자등록번호 검증 성공**
  Given 온보딩을 완료한(`ACTIVE`) 임대인(`userType=LANDLORD`)이 정식 access 토큰(`ROLE_USER`)으로 정상 영업 중인 사업자등록번호를 입력하고
  When `POST /api/v1/auth/business/verify`에 `{ businessRegistrationNumber }`(숫자 10자리)를 담아 호출하면
  Then `200 OK` + `data`에 `businessRegistrationNumber`(마스킹)·`verified=true`를 반환한다. **검증 결과는 서버에 저장하지 않고**(Redis 마커 없음·`user.businessRegistrationNumberHash`에도 쓰지 않음) 응답 본문에만 담으며, 사업자등록번호는 응답·로그에서 마스킹한다.
- **인증·권한 — 온보딩 토큰으로 호출**
  Given 온보딩 미완료(`PENDING`·`TERMS_AGREED`) 온보딩 토큰(`ROLE_ONBOARDING`)으로
  When `POST /api/v1/auth/business/verify`를 호출하면
  Then `403` + `error.code=AUTH_ONBOARDING_REQUIRED`를 반환하고 외부 검증을 수행하지 않는다(온보딩 완료·정식 토큰 필요).
- **인증·권한 — 임대인이 아닌 사용자**
  Given 온보딩을 완료한(`ACTIVE`) 세입자(`userType=TENANT`)가 정식 access 토큰으로
  When `POST /api/v1/auth/business/verify`를 호출하면
  Then `403` + `error.code=FORBIDDEN`을 반환하고 외부 검증을 수행하지 않는다(임대인 전용 API).
- **입력 검증 실패**
  Given `businessRegistrationNumber`가 누락·빈값이거나 형식(숫자 10자리)에 어긋나면
  When `POST /api/v1/auth/business/verify`를 호출하면
  Then `400` + `error.code=INVALID_INPUT`을 반환한다(형식 위반은 외부 호출 전에 거른다).
- **비즈니스 규칙 — 진위·상태 검증 실패(미등록/휴업/폐업)**
  Given 검증 서비스 조회 결과 존재하지 않거나 휴업·폐업 상태인 사업자등록번호이면
  When `POST /api/v1/auth/business/verify`를 호출하면
  Then `422` + `error.code=AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`를 반환하고 번호를 검증 완료로 표시하지 않는다.
- **장애 — 외부 검증 서비스 실패**
  Given 사업자등록정보 검증 API가 장애·타임아웃·5xx로 응답하면
  When `POST /api/v1/auth/business/verify`를 호출하면
  Then `502` + `error.code=UPSTREAM_ERROR`를 반환하고 검증 결과를 저장하지 않아 클라이언트가 재시도하도록 유도한다(동기 검증 정책).
- **경계·동시성 — 검증 시도 레이트리밋**
  Given 짧은 시간에 검증을 반복 호출하면
  When `POST /api/v1/auth/business/verify`를 호출하면
  Then `429` + `error.code=TOO_MANY_REQUESTS`를 반환한다(확인 필요: 시도 상한·간격 임계값).
- **인증·권한 — 잘못된/누락 토큰**
  Given `Authorization` 헤더가 없거나 토큰이 위조/만료되었으면
  When `POST /api/v1/auth/business/verify`를 호출하면
  Then 누락·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`를 반환한다.

---

### US-1-9 — 임대인 온보딩 정보 제출하기 (임대인 전용)

**As a** 약관 동의·연락처 인증을 마친(TERMS_AGREED) 임대인 가입자
**I want** 이름(성·이름을 합친 단일 `name`)·생년월일·연락처(전화)를 한 번에 제출하기를(연락처는 사전 인증, 닉네임은 서버 자동 배정, 이메일·사업자번호는 미수집)
**So that** 임대인으로 가입을 완료하고 정식 access/refresh 토큰으로 임대인 기능(매물 연결·세입자 채팅 등)을 이용할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(연락처 등 민감정보 저장·로그 마스킹), 보안(휴대폰 소유 인증)
- 선행: 약관 동의(US-1-7, `TERMS_AGREED`)·연락처 인증(US-1-10)이 완료되어야 한다(약관 + 연락처만으로 온보딩 완료 — 사업자등록번호 검증(US-1-8)은 온보딩 선행이 아니라 온보딩 후 별도 단계다).
- 백엔드 관점: 세입자 온보딩(`POST /auth/onboarding`, US-1-2)과 분리된 **임대인 전용 엔드포인트** `POST /api/v1/auth/landlord/onboarding`로 처리한다. 성공 시 사용자 상태를 `TERMS_AGREED` → `ACTIVE`로 전이하고 **`userType`을 `LANDLORD`로 확정**하며, 닉네임 자동 배정과 정식 access/refresh 토큰 발급은 세입자 온보딩과 동일하다(상태 전이 액션이므로 `200`). 요청 본문은 `{ name, phoneNumber, birthDate }` 세 필드다. 임대인은 성별·국적·직업·비자정보와 **이메일을 수집하지 않으며(생년월일 `birthDate`은 세입자와 동일하게 필수 수집), 사업자등록번호도 온보딩에서 수집하지 않는다**([ADR-0034](../adr/0034-landlord-phone-sms-verification.md); `user.businessRegistrationNumberHash` 컬럼은 유지하되 온보딩 완료 시 `null`로 남고 추후 매물 등록 시점에 채운다). 세입자의 성·이름(`firstName`/`lastName`) 대신 단일 `name`을 받는다. 검증 게이트 우선순위는 약관 미동의 → 연락처 미인증 순이며 사업자번호 게이트는 없다.

**AC (Given / When / Then)**

- **정상 — 임대인 온보딩 완료**
  Given 약관 동의를 마친(`TERMS_AGREED`) 사용자의 유효한 온보딩 토큰을 보유하고 제출 `phoneNumber`가 사전 인증(US-1-10)되어 있으며
  When 모든 필수 필드(`name`·`phoneNumber`·`birthDate`)를 담아 `POST /api/v1/auth/landlord/onboarding`을 호출하면(약관 필드는 담지 않음)
  Then `200 OK` + `data`에 완성된 임대인 프로필(`userType="LANDLORD"`·서버가 자동 배정한 `nickname` 포함)과 정식 `accessToken`/`refreshToken`을 내려주고, 사용자 상태를 `TERMS_AGREED` → `ACTIVE`로 전이한다.
- **입력 검증 실패**
  Given `name`/`phoneNumber` 중 빈값이 있거나 형식(전화번호)이 어긋나거나 `birthDate`가 누락·형식 위반·미래 날짜이면
  When `POST /api/v1/auth/landlord/onboarding`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`로 위반 필드를 반환한다.
- **비즈니스 규칙 — 약관 미동의 상태(우선 판정)**
  Given 약관 동의를 아직 마치지 않은(`PENDING`) 사용자가
  When `POST /api/v1/auth/landlord/onboarding`을 호출하면
  Then `422` + `error.code=AUTH_TERMS_AGREEMENT_REQUIRED`를 반환하고 상태를 전이하지 않는다(약관 동의 US-1-7 선행 — 연락처 안내보다 **약관 동의가 먼저**).
- **비즈니스 규칙 — 연락처 미인증**
  Given 약관까지 동의한(`TERMS_AGREED`) 사용자의 제출 `phoneNumber`가 SMS 인증번호로 검증(US-1-10)되지 않았거나 검증한 번호와 다르면
  When `POST /api/v1/auth/landlord/onboarding`을 호출하면
  Then `422` + `error.code=AUTH_PHONE_NOT_VERIFIED`를 반환하고 상태를 전이하지 않는다.
- **인증·권한 — 잘못된/누락 토큰**
  Given `Authorization` 헤더가 없거나 토큰이 위조/만료되었으면
  When `POST /api/v1/auth/landlord/onboarding`을 호출하면
  Then 누락·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`를 반환한다.
- **경계·동시성 — 중복 온보딩**
  Given 이미 온보딩을 완료(`ACTIVE`)한 사용자이거나, 동일 사용자가 온보딩 요청을 동시에 두 번 보내면
  When `POST /api/v1/auth/landlord/onboarding`을 호출하면
  Then 한 요청만 성공하고 나머지는 `409` + `error.code=AUTH_ONBOARDING_ALREADY_COMPLETED`를 반환한다(서버는 사용자 단위 멱등 처리).

---

### US-1-10 — 온보딩 중 연락처(휴대폰) 인증하기 (임대인 전용)

**As a** 약관 동의를 마쳤으나 온보딩 미완료(TERMS_AGREED) 임대인 가입자
**I want** 온보딩에서 입력한 연락처(휴대폰)로 SMS 인증번호를 받아 확인하기를
**So that** 본인 소유 휴대폰임을 증명하고(US-1-9 임대인 온보딩 제출의 선행 조건) 임대인 가입을 완료할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(휴대폰 소유 인증·인증번호 해시 보관·재발송/시도 레이트리밋), 보안(연락처·인증번호 로그 마스킹)
- 선행: 약관 동의(US-1-7, `TERMS_AGREED`)가 완료되어야 한다. 이 단계는 **임대인 트랙 전용**으로, 세입자 이메일 인증(US-1-6)을 임대인 트랙에서 대체한다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)).
- 백엔드 관점: `auth`가 인증번호를 생성해 아웃바운드 포트 `VerificationSmsSender`(인프라 어댑터: SMS API — 구체 provider는 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md))로 **동기 발송**하고, **발송에 성공한 뒤에만** 인증번호를 **해시로 보관**(Redis, TTL 자동 소멸)한다. 인증번호 생성·해시·검증은 서버가 보유하고 어댑터는 발송만 담당한다(이메일 인증 US-1-6과 대칭). provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답한다. 검증 성공 시 해당 사용자의 연락처를 `VERIFIED`로 마킹하고, 임대인 온보딩 제출(US-1-9)에서 제출 `phoneNumber`와 대조한다.
- **인증번호 정책은 이메일 인증(US-1-6)과 동일하게 적용한다** — 인증번호 6자리·코드 TTL 5분·검증 마커(VERIFIED) TTL 30분(온보딩 토큰 만료)·검증 시도 상한 5회·재발송 간격 60초([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)).
- 이 발송·확인(`POST /auth/phone/verification-code`·`/auth/phone/verify`)은 **프로필에서 임대인 연락처를 변경할 때(US-1-5)도 재사용**해 새 번호를 재인증한다(정식 토큰(`ACTIVE`) 컨텍스트 허용). 변경은 새 번호가 VERIFIED된 뒤에만 반영하며 미인증·불일치는 `422 AUTH_PHONE_NOT_VERIFIED`다.

**AC (Given / When / Then)**

- **정상 — 인증번호 발송**
  Given 약관 동의를 마친(`TERMS_AGREED`) 사용자가 연락처(휴대폰)를 입력하고
  When `POST /api/v1/auth/phone/verification-code`에 `{ phoneNumber }`를 담아 호출하면
  Then `200 OK` + `data.expiresIn`(만료 초)을 반환하고 해당 번호로 SMS 인증번호를 발송한다. SMS 발송에 성공한 뒤에만 인증번호 챌린지를 저장하며(인증번호 원문은 저장·로그하지 않고 해시로만 보관), `phoneNumber`는 마스킹해 반환한다.
- **비즈니스 규칙 — 약관 미동의(선행 게이트)**
  Given 약관 동의를 아직 마치지 않은(`PENDING`) 사용자가
  When `POST /api/v1/auth/phone/verification-code`를 호출하면
  Then `422` + `error.code=AUTH_TERMS_AGREEMENT_REQUIRED`를 반환하고 인증번호를 발송하지 않는다(약관 동의 US-1-7 선행 필요). (`/phone/verify`는 약관 게이트를 두지 않으며, PENDING은 챌린지 부재로 `AUTH_PHONE_VERIFICATION_FAILED`가 난다 — 아래 "인증번호 불일치/만료" 참조)
- **장애 — SMS 발송 실패**
  Given SMS provider 장애·타임아웃 등으로 인증번호 발송이 실패하면
  When `POST /api/v1/auth/phone/verification-code`를 호출하면
  Then `502` + `error.code=UPSTREAM_ERROR`를 반환하고, 인증번호 챌린지를 저장하지 않아 클라이언트가 재시도하도록 유도한다(동기 발송 정책).
- **정상 — 인증번호 확인**
  Given 발송된 인증번호가 유효(미만료·시도 미초과)하고
  When `POST /api/v1/auth/phone/verify`에 `{ phoneNumber, code }`를 담아 호출하면
  Then `200 OK` + `data.verified=true`를 반환하고 해당 연락처를 `VERIFIED`로 마킹해 임대인 온보딩 제출(US-1-9)에 사용할 수 있게 한다.
- **입력 검증 실패**
  Given `phoneNumber`가 누락·형식 위반이거나 확인 요청에 `code`가 빈 문자열이면
  When 연락처 인증 API를 호출하면
  Then `400` + `error.code=INVALID_INPUT`을 반환한다.
- **비즈니스 규칙 — 인증번호 불일치/만료**
  Given 잘못된 인증번호이거나 만료(미발송 포함)된 인증번호로
  When `POST /api/v1/auth/phone/verify`를 호출하면
  Then `422` + `error.code=AUTH_PHONE_VERIFICATION_FAILED`를 반환하고 연락처를 검증 완료로 표시하지 않는다.
- **경계·동시성 — 재발송/시도 레이트리밋**
  Given 짧은 시간에 인증번호 재발송을 반복하거나 검증 시도 상한을 초과하면
  When 연락처 인증 API를 호출하면
  Then `429` + `error.code=TOO_MANY_REQUESTS`를 반환한다(이메일 인증 US-1-6과 동일 임계값 — 재발송 간격 60초·검증 시도 5회).
- **인증·권한 — 잘못된/누락 토큰**
  Given `Authorization` 헤더가 없거나 토큰이 위조/만료되었으면
  When 연락처 인증 API를 호출하면
  Then 누락·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`를 반환한다.

## 2. 맞춤 진단 & 매물 추천

> 관련 API 스펙: [02-diagnosis-recommendation](../api/specs/02-diagnosis-recommendation.md)

외국인 사용자가 6단계 진단(① 지역 / ② 입국 목적(유학 여부) / ③ 대학 그룹·지역 선택 / ④ 주거 환경 조건 / ⑤ 월세 범위(최소-최대) / ⑥ ARC 발급 여부)에 답하면, 서버는 조건에 맞는 매물 리스트와 지도용 좌표를 추천한다. 진단 문항과 선택지는 앱이 하드코딩하지 않고 백엔드가 제공하며, 사용자 표시 언어로 번역되어 내려간다(US-2-5·US-2-6). 진단은 제출 시 1건의 진단 레코드로 영속화되며, 사용자는 자신의 진단 이력·완료 여부를 조회하고 재진단(새 진단 생성)할 수 있다.

- 진단 입력은 서버에서 다시 검증한다(클라이언트 검증을 신뢰하지 않는다): `region` 1택, `purpose` 1택(필수, 단일 enum `Purpose`: `STUDY`|`NON_STUDY`), **입국 목적별 대학 그룹·지역 선택**(두 필드로 분리한다 — `university`(필드 키는 `university` 유지, 타입은 6 그룹 enum `UniversityGroup`: `HUFS_KHU_KOREA`·`SKKU_SUNGSHIN`·`SNU_CAU_SOONGSIL`·`HONGIK_YONSEI_EWHA`·`KONKUK_SEJONG_HYU`·`ETC`; 단일 선택. 각 그룹은 개별 대학 코드로 멤버십을 갖는다 — `HUFS_KHU_KOREA`→{`HUFS`,`KHU`,`KOREA`}, `SKKU_SUNGSHIN`→{`SKKU`,`SUNGSHIN`}, `SNU_CAU_SOONGSIL`→{`SNU`,`CAU`,`SOONGSIL`}, `HONGIK_YONSEI_EWHA`→{`HONGIK`,`YONSEI`,`EWHA`}, `KONKUK_SEJONG_HYU`→{`KONKUK`,`SEJONG`,`HYU`}, `ETC`→{}(빈 집합, 대학 필터 미적용·지역 기반 매칭으로 폴백). 멤버 개별 대학 코드는 매물의 `nearbyUniversityCodes` 저장값과 동일하다 — 매물 저장은 바뀌지 않는다.), `district`(enum `District`: `GURO_GU`·`YEONGDEUNGPO_GU`·`GEUMCHEON_GU`·`GWANAK_GU`·`DONGDAEMUN_GU`·`ETC`); 조건부 필수 — 입국 목적이 `STUDY`면 `university` 필수·`district` 없음, `NON_STUDY`면 `district` 필수·`university` 없음. 위반은 공통 `INVALID_INPUT`(400)+`errors[]`로 표현. 결정 근거는 [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)), `conditions`(enum `DiagnosisCondition`, listing `ConditionTag` 이름 통일: `MOVE_IN_NOW`·`FEMALE_ONLY`·`PRIVATE_BATH`·`ENGLISH_OK`·`ADDRESS_REGISTRATION`·`NO_MAINT_FEE`·`MEALS_INCLUDED`·`DOUBLE_ROOM`) 최대 3개(4개 이상이면 검증 실패), `monthlyRentMin`·`monthlyRentMax`(월세 범위, 각 0 이상 정수·필수, `monthlyRentMin` ≤ `monthlyRentMax`), `arcStatus`(enum `ArcStatus`: `ARC_ISSUED`|`NO_ARC`, 1택 필수). ⑥ `arcStatus`가 `NO_ARC`(ARC 미발급)이면 서버가 추천용 동명의 파생 조건 `NO_ARC`(`DiagnosisCondition`)를 `conditions`에 추가해 ARC 불요 매물만 매칭한다(사용자가 ④에서 직접 고르는 값 아님, 최대 3개 제한에서 제외).
- MVP 매물 데이터는 **서울 기준**이다. `BUSAN`/`GYEONGGI`는 선택지로 허용하되, 결과 매물이 0건일 수 있고 이때 조정 제안을 반환한다.
- 추천 결과의 매물 요약은 매물 탐색 도메인의 요약 DTO(`ListingSummaryResponse`)를 재사용한다(확인 필요 — 01 매물 탐색 스펙 확정 시 필드 동기화).
- 진단·결과는 본인만 접근 가능하다(소유권 검증). 모든 시각은 UTC ISO-8601, 금액은 KRW 정수, enum은 UPPER_SNAKE.
- 입력 검증 위반(필수값 누락·enum 불일치·조건 개수 초과·월세 범위 음수 또는 `monthlyRentMin` > `monthlyRentMax`·페이지 파라미터 범위)은 모두 공통 코드 `INVALID_INPUT`(400) + `errors[]`로 표현한다(error-response-guide §3·§4). 진단 도메인에서 별도 검증 코드를 만들지 않는다.

### US-2-1 — 진단 제출(진행 중 진단 확정 및 저장)

**As a** 한국 주거를 처음 찾는 외국인 사용자
**I want** 단계별로 서버에 저장해 둔 답으로 채워진 진행 중(`IN_PROGRESS`) 진단을 제출로 확정하고
**So that** 내 조건이 서버에 영속화되어 매번 다시 입력하지 않고 결과를 재조회·재진단할 수 있다

- **우선순위**: High
- **관련 NFR**: 입력 검증·보안(민감하지 않은 진단 입력이나 본인 소유로 격리), 진단 제출 p95 응답시간 목표(확인 필요 — NFR 문서 미확정)
- **백엔드 관점**: 진단 진행은 서버가 단계별로 저장한다 — 사용자당 진행 중 진단 1건(`status=IN_PROGRESS`, in-progress draft)을 두고 단계마다 받은 답을 채워 간다(US-2-5). 제출은 별도 단계다 — `POST /api/v1/diagnoses`는 6필드 누적 답을 다시 보내는 요청이 아니라, **서버에 이미 저장된 진행 중 진단을 확정하는 요청**이다. 서버는 저장된 답을 재검증(정규화·중복 제거·enum 검증·조건부 필수)한 뒤 진단 상태를 `IN_PROGRESS` → `COMPLETED`로 전이하고 생성 리소스 식별자(`diagnosisId`)·`submittedAt`을 반환한다. 진단 생성(`COMPLETED`)은 이 제출 시점이며, 이력·목록 조회는 `COMPLETED`만 노출한다(`IN_PROGRESS` 제외). 재진단은 새 진행 중 진단을 시작한다. 입국 목적에 따른 대학 그룹·지역 선택은 두 필드(`university`(필드 키 `university` 유지, 타입은 6 그룹 enum `UniversityGroup`, 단일 선택)·`district`(enum `District`))로 분리해 저장한다 — 입국 목적이 `STUDY`면 `university` 필수·`district` 없음, `NON_STUDY`면 `district` 필수·`university` 없음(조건부 필수, 위반은 공통 `INVALID_INPUT`(400)+`errors[]`).

**AC (Given / When / Then)**

- 시나리오: 정상 제출 — 진행 중 진단 확정

  - **Given** 로그인한 사용자가 유효한 access token을 보유하고, 단계별 응답(US-2-5)으로 진행 중 진단(`IN_PROGRESS`)에 `region=SEOUL`, `purpose=STUDY`, `university=SNU_CAU_SOONGSIL`(③ 대학 그룹 — 유학(`STUDY`) 시 `university` 필수·`district` 없음), `conditions=[FEMALE_ONLY,PRIVATE_BATH]`(3개 이하), `monthlyRentMin=300000`·`monthlyRentMax=600000`, `arcStatus=ARC_ISSUED`가 모두 저장되어 있다
  - **When** `POST /api/v1/diagnoses`(진행 중 진단 확정 요청)를 호출한다
  - **Then** 서버가 저장된 답을 재검증한 뒤 진단을 `IN_PROGRESS` → `COMPLETED`로 확정하고, `201 Created`와 함께 `data.diagnosisId`·`data.status=COMPLETED`·`data.submittedAt`(UTC ISO-8601)을 반환하고, `Location: /api/v1/diagnoses/{diagnosisId}` 헤더를 포함한다
- 시나리오: 검증 실패 — 저장된 답이 조건 4개 이상 / 필수 단계 미완료

  - **Given** 진행 중 진단에 저장된 답이 `conditions` 4개를 담았거나 `region`이 아직 채워지지 않았다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** `400 Bad Request`와 `error.code=INVALID_INPUT`을 반환하고(확정하지 않음), `error.errors[]`에 위반 필드(`conditions`/`region`)와 사유를 담는다 (개수 초과는 `conditions` reason으로 "최대 3개까지 선택할 수 있습니다.")
- 시나리오: 검증 실패 — 정의되지 않은 enum 값

  - **Given** 진행 중 진단에 저장된 답이 `region=JEJU`처럼 허용 목록(`SEOUL`/`BUSAN`/`GYEONGGI`)에 없는 값 또는 `conditions`에 미정의 코드를 담고 있다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** `400 Bad Request`와 `error.code=INVALID_INPUT`을 반환한다 (허용되지 않은 enum 값을 무시하지 않고 명시적으로 거부 — api-design-guide §5)
- 시나리오: 인증 실패

  - **Given** `Authorization` 헤더가 없거나 만료된 token을 보낸다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** 토큰 부재/위조는 `401`+`error.code=UNAUTHENTICATED`, 만료는 `401`+`error.code=TOKEN_EXPIRED`(재발급 유도)를 반환한다
- 시나리오: 경계 — 저장된 월세 범위(각 0 이상, `min` ≤ `max`)

  - **Given** 진행 중 진단에 저장된 `monthlyRentMin`/`monthlyRentMax`가 `0`/`0`(허용)이거나, `monthlyRentMin`이 `-1`(불허)이거나, `monthlyRentMin`이 `monthlyRentMax`보다 큰(예 `600000`/`300000`, 불허) 값이다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** `0`/`0`은 `201 Created`로 정상 확정, 음수는 `400`+`error.code=INVALID_INPUT`(`errors[]`의 `monthlyRentMin` reason "0 이상이어야 합니다."), `min` > `max`는 `400`+`error.code=INVALID_INPUT`(`errors[]`의 `monthlyRentMin` reason "monthlyRentMin은 monthlyRentMax 이하여야 합니다.")을 반환한다

### US-2-2 — 진단 결과(추천 매물 + 지도 좌표) 조회

**As a** 진단을 마친 외국인 사용자
**I want** 내 진단 조건에 맞는 매물 리스트와 지도용 좌표를 결과로 받고
**So that** 한국 부동산 용어를 몰라도 내게 맞는 매물을 지도와 목록으로 한눈에 비교할 수 있다

- **우선순위**: High
- **관련 NFR**: 성능(추천 쿼리·좌표 집계 응답시간 목표, 확인 필요 — NFR 문서 미확정), 보안(본인 진단만 조회)
- **백엔드 관점**: 저장된 진단 조건으로 매물을 매칭 → 요약 DTO(`ListingSummaryResponse`) 목록 + 지도 마커 좌표(`lat`/`lng`, WGS84) 반환. 목록은 **오프셋 기반 페이지네이션**(`page`/`size`, 기본 size 20, 최대 100), 정렬은 추천/가격/거리순. 0건이면 빈 `content` + 조정 제안(`suggestions`)을 함께 내려 클라이언트가 키워드/조건 완화를 안내한다. `suggestions`의 `reason`/`type`은 언어 무관 enum, 사람이 보는 `message`/`detail`은 **서버가 사용자 표시 언어로 번역**해 전송한다(enum 보유 라벨, `user` 공개 query로 언어 취득, 미지원=영어 폴백 — US-2-6 일관).

**AC (Given / When / Then)**

- 시나리오: 정상 조회 (결과 있음)

  - **Given** 본인이 소유한 `diagnosisId`가 있고 서울 기준 매칭 매물이 존재한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations?page=0&size=20&sort=recommended,desc`를 호출한다
  - **Then** `200 OK`와 함께 `data.content[]`(매물 요약), `data.markers[]`(lat/lng), `data.page`(오프셋 메타), `data.suggestions=null`을 반환한다
- 시나리오: 경계 — 0건 (부산/경기 또는 좁은 조건)

  - **Given** `region=BUSAN`처럼 MVP 데이터가 없거나 조건이 너무 좁아 매칭이 0건이다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `200 OK`(에러 아님)와 함께 `data.content=[]`, `data.markers=[]`, `data.suggestions`(완화 가능한 조건/예산/키워드 제안 목록)을 반환한다
- 시나리오: 인가 실패 — 타인의 진단 결과 접근

  - **Given** 다른 사용자가 소유한 `diagnosisId`로 요청한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `403 Forbidden`과 `error.code=FORBIDDEN`을 반환한다(소유권 위반으로 차단)
- 시나리오: 리소스 없음 — 존재하지 않는 진단

  - **Given** 어떤 사용자에게도 존재하지 않는 `diagnosisId`로 요청한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `404 Not Found`와 `error.code=DIAGNOSIS_NOT_FOUND`를 반환한다
- 시나리오: 입력 검증 실패 — 페이지 파라미터 범위 초과

  - **Given** `size=500`(최대 100 초과) 또는 정의되지 않은 `sort` 키를 보낸다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `400 Bad Request`와 `error.code=INVALID_INPUT`을 반환한다(허용되지 않은 `sort` 키를 무시하지 않고 거부 — api-design-guide §5)

### US-2-3 — 진단 이력 조회 및 최근 진단 다시 보기

**As a** 재방문한 외국인 사용자
**I want** 내 진단 이력 목록과 가장 최근 진단을 조회하고
**So that** 홈에서 "진단 시작 / 재진단" 문구가 완료 여부에 따라 분기되고, 지난 결과를 다시 볼 수 있다

- **우선순위**: Mid
- **관련 NFR**: 보안(본인 이력만), 성능(이력 목록 페이지네이션)
- **백엔드 관점**: 사용자별 진단 목록을 최신순으로 반환(오프셋 페이지네이션). 별도 "최근 진단" 단축 조회(`/diagnoses/latest`)로 홈의 완료 여부 분기에 쓰일 최신 1건과 그 입력 요약을 반환한다. 이력이 0건이면 빈 목록(에러 아님).

**AC (Given / When / Then)**

- 시나리오: 정상 — 이력 목록 조회

  - **Given** 본인이 과거 진단 3건을 제출했다
  - **When** `GET /api/v1/diagnoses?page=0&size=20&sort=submittedAt,desc`를 호출한다
  - **Then** `200 OK`와 `data.content[]`(각 `diagnosisId`·`region`·`submittedAt`·`status`), `data.page` 메타를 최신순으로 반환한다
- 시나리오: 경계 — 진단 이력 없음 (최초 사용자)

  - **Given** 한 번도 진단하지 않은 사용자다
  - **When** `GET /api/v1/diagnoses/latest`를 호출한다
  - **Then** `200 OK`와 `data.completed=false`를 반환해 홈이 "진단 시작" 문구로 분기하게 한다 (404 아님). 이 경우 진단 요약 필드는 응답에 포함되지 않는다
- 시나리오: 정상 — 최근 진단 단건 상세 다시 보기

  - **Given** 본인이 소유한 최근 진단 `diagnosisId`가 있다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}`를 호출한다
  - **Then** `200 OK`와 진단 입력 전체(`region`/`purpose`/대학 그룹·지역 선택(`university`(`UniversityGroup`)/`district`)/`conditions`/`monthlyRentMin`/`monthlyRentMax`/`arcStatus`/`submittedAt`)를 반환한다
- 시나리오: 인증 실패

  - **Given** 토큰 없이 요청한다
  - **When** `GET /api/v1/diagnoses`를 호출한다
  - **Then** `401`과 `error.code=UNAUTHENTICATED`를 반환한다
- 시나리오: 인가 실패 — 타인 진단 단건 조회

  - **Given** 다른 사용자의 `diagnosisId`로 요청한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}`를 호출한다
  - **Then** `403`과 `error.code=FORBIDDEN`을 반환한다
- 시나리오: 리소스 없음 — 존재하지 않는 진단 단건 조회

  - **Given** 어떤 사용자에게도 존재하지 않는 `diagnosisId`로 요청한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}`를 호출한다
  - **Then** `404`와 `error.code=DIAGNOSIS_NOT_FOUND`를 반환한다

### US-2-4 — 재진단(새 진단 생성)

**As a** 조건이 바뀐 외국인 사용자
**I want** 기존 진단을 덮어쓰지 않고 새 진단을 생성(재진단)하고
**So that** 진단 이력이 보존되면서 바뀐 조건으로 다시 결과를 받을 수 있다

- **우선순위**: Mid
- **관련 NFR**: 신뢰성(진단 이력 보존 — 재진단이 기존 진단을 덮어쓰지 않음)
- **백엔드 관점**: 재진단은 US-2-1과 동일한 `POST /api/v1/diagnoses`로, 항상 새 레코드를 생성하고 기존 진단을 덮어쓰지 않아 이력이 보존된다.

**AC (Given / When / Then)**

- 시나리오: 정상 — 재진단으로 새 레코드 생성

  - **Given** 본인이 이미 완료한 진단 1건이 있다
  - **When** 변경된 조건으로 `POST /api/v1/diagnoses`를 다시 호출한다
  - **Then** `201 Created`로 **새** `diagnosisId`가 발급되고 기존 진단은 그대로 이력에 남는다(덮어쓰지 않음)
- 시나리오: 입력 검증 실패 — 재진단 본문도 동일 규칙 적용

  - **Given** 재진단 본문에서 `purpose`를 누락(단일 enum 미선택)해 보낸다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** `400`과 `error.code=INVALID_INPUT`(`errors[]`의 `purpose` reason "필수 항목입니다.")을 반환한다
- 시나리오: 인증 실패

  - **Given** 만료된 token으로 재진단을 시도한다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** `401`과 `error.code=TOKEN_EXPIRED`를 반환한다

### US-2-5 — 진단 문항·선택지 백엔드 제공

**As a** 진단을 시작하는 외국인 사용자
**I want** 받을 단계 번호로 질문 1개를 조회하고 그 단계의 답 1개를 보내 서버가 저장하게 하는 흐름을 한 단계씩 반복하고
**So that** 앱을 새로 배포하지 않고도 질문·선택지(지역·대학·조건 등)와 분기 흐름을 서버에서 갱신·관리할 수 있다

- **우선순위**: High (진단 제출 플로우의 선행 단계)
- **관련 NFR**: 일관성(문항 카탈로그의 선택지 코드가 진단 제출 검증 enum과 1:1 일치), 단계별 분기를 서버가 결정(클라이언트 로컬 분기 아님)
- **백엔드 관점**: 진단 문항을 단계별로 내려주는 server-stateful 흐름을 두 엔드포인트로 제공한다(둘 다 인증 필수, 02 스펙 상세 §1에 반영) — 질문 조회 `GET /api/v1/diagnoses/questions/{step}`와 답 저장 `POST /api/v1/diagnoses/answers`. 진행 답은 **서버가 저장**한다 — 사용자당 진행 중 진단 1건(`status=IN_PROGRESS`)을 in-progress draft로 두고 채워 간다. 클라이언트는 받을 `step`(1~6)을 **path로 지정**해 `GET /api/v1/diagnoses/questions/{step}`로 그 단계 질문 1개와 선택지를 조회하고, 화면에서 받은 **현재 단계의 답 1개**(그 단계의 `field`+`code`; `conditions`처럼 다중 선택은 `codes` 배열; ⑤ 월세 범위는 코드가 아닌 두 숫자 필드 `field=monthlyRent`+`min`/`max`, 예 `{ "field": "monthlyRent", "min": 300000, "max": 600000 }` — 순서 없는 `codes[]` 배열을 재사용하지 않는다)만 `POST /api/v1/diagnoses/answers` 본문에 담아 보내며, 서버가 그 답을 진행 중 진단에 저장한다 — 6단계(① 지역 / ② 입국 목적 / ③ 대학 그룹·지역 / ④ 주거 조건 / ⑤ 월세 범위(min/max) / ⑥ ARC)를 한 번에 주지 않고 한 단계씩 내려간다. 다음 `step` 번호는 클라이언트가 정해 다시 `GET`을 호출한다. 요청 본문에 누적 답(`answers` 묶음)을 담지 않는다. 질문 조회 응답은 `{ "step", "field", "question"(번역된 표시 라벨), "select"(단일/다중·최대; ⑤ 월세 범위 단계는 고정 선택지 목록이 아닌 두 숫자 입력 `NUMBER_RANGE`·`options` 비움 — "모든 단계가 enum과 1:1인 고정 선택지 목록"이라는 가정에서 의도적으로 분리된 예외), "options": [ { "code", "label" } ] }`이며, 6단계 답이 모두 저장되면 클라이언트는 이후 `POST /api/v1/diagnoses`로 진행 중 진단을 확정 제출한다(US-2-1). ③ 단계 분기는 **서버가 저장된 `purpose`로 결정**하는 비즈니스 로직이다(`STUDY`면 6개 대학 그룹(`UniversityGroup`) `options`를 담은 `university` 질문, `NON_STUDY`면 `district` 질문 — 알맞은 한 질문만 내려주며, 유학 시 답은 단일 그룹 코드 1개(`field=university`, `code=<그룹코드>`)다). 분기 메타는 `diagnosisQuestions`에 두지 않으며(데이터만), 대학 질문·지역 질문은 각각 카탈로그 데이터로 존재하고 어느 것을 낼지는 서비스가 결정한다. 선택지 코드는 제출 시 검증하는 enum과 동일 출처여야 한다. 잘못된 답(미정의 enum, 목적-대학/지역 불일치 등)은 공통 `INVALID_INPUT`(400)+`errors[]`로 표현한다. MVP 데이터는 서울 기준.

**AC (Given / When / Then)**

- 시나리오: 정상 — step 지정으로 질문 조회

  - **Given** 진단을 시작한 사용자가 유효한 access token을 보유한다
  - **When** `GET /api/v1/diagnoses/questions/1`을 호출한다
  - **Then** `200 OK`와 함께 ① 지역(`field=region`) 질문 1개와 그 선택지(각 선택지의 코드·표시 라벨, 단일/다중·최대 선택 수 제약)를 `{ "step", "field", "question", "select", "options" }`로 반환한다
- 시나리오: 정상 — 단계 답 저장 후 다음 step 조회

  - **Given** ① 지역 질문을 받은 사용자가 그 단계의 답 1개(`field=region`, `code=SEOUL`)를 구성한다
  - **When** 그 답 1개를 `POST /api/v1/diagnoses/answers` 본문에 담아 보낸다
  - **Then** 서버가 그 답을 진행 중 진단에 저장하고 `200 OK`를 반환하며, 클라이언트는 이후 `GET /api/v1/diagnoses/questions/2`로 다음 단계(② 입국 목적) 질문을 조회한다(누적 답 재전송 없음)
- 시나리오: 일관성 — 선택지 코드 ↔ 제출 enum 일치

  - **Given** 응답으로 받은 선택지 코드(예: `conditions`의 `FEMALE_ONLY`)로 단계 답을 구성해 단계별로 저장한다
  - **When** 모든 단계 저장 후 `POST /api/v1/diagnoses`로 확정 제출한다
  - **Then** 문항 카탈로그와 제출 검증이 동일 enum을 쓰므로 `INVALID_INPUT` 없이 수용된다
- 시나리오: 입국 목적 분기 — 서버가 ③ 대학 그룹/지역 질문을 결정

  - **Given** ②까지 저장된 진행 중 진단의 `purpose`가 `STUDY`(또는 `NON_STUDY`)이다
  - **When** `GET /api/v1/diagnoses/questions/3`을 호출한다
  - **Then** 서버가 저장된 `purpose`로 ③ 질문을 결정해, 유학이면 `field=university`로 6개 대학 그룹(`UniversityGroup`) 옵션 — `HUFS_KHU_KOREA`("한국외대·경희대·고려대" / "HUFS · Kyung Hee · Korea Univ."), `SKKU_SUNGSHIN`("성균관대·성신여대" / "Sungkyunkwan · Sungshin Women's"), `SNU_CAU_SOONGSIL`("서울대·중앙대·숭실대" / "Seoul National · Chung-Ang · Soongsil"), `HONGIK_YONSEI_EWHA`("홍익대·연세대·이화여대" / "Hongik · Yonsei · Ewha Womans"), `KONKUK_SEJONG_HYU`("건국대·세종대·한양대" / "Konkuk · Sejong · Hanyang"), `ETC`("기타" / "Other") — 을 `options: [ { "code", "label" } ]`로(단일 선택), 비유학이면 `field=district`(구) 목록(`GURO_GU`·`YEONGDEUNGPO_GU`·`GEUMCHEON_GU`·`GWANAK_GU`·`DONGDAEMUN_GU`·`ETC`)을 — 알맞은 한 질문만 `options`에 담아 내려준다(클라이언트 로컬 분기 아님, 분기는 서버 비즈니스 로직)
- 시나리오: 완료 — 모든 단계 저장 후 제출로 이어짐

  - **Given** ① ~ ⑥ 단계 답이 모두 `POST /api/v1/diagnoses/answers`로 진행 중 진단에 저장되었다
  - **When** 마지막 단계(⑥ ARC) 답까지 저장을 마친다
  - **Then** 별도의 추가 질문 조회 없이 클라이언트는 이후 `POST /api/v1/diagnoses`로 진행 중 진단을 확정 제출한다
- 시나리오: 잘못된 답 — 검증 실패

  - **Given** 미정의 enum(예: `university`에 6 그룹(`UniversityGroup`)에 없는 코드) 또는 그 단계의 목적-대학 그룹/지역이 불일치하는 답 1개를 구성한다(필드명은 `university` 유지, 타입은 `UniversityGroup`)
  - **When** `POST /api/v1/diagnoses/answers`를 호출한다
  - **Then** 미정의 그룹 코드를 무시하지 않고 명시적으로 거부해 `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하며 `error.errors[]`에 위반 필드(`university`)를 담는다

### US-2-6 — 사용자 표시 언어 기반 진단 문항·선택지 번역 제공

**As a** 한국어가 익숙하지 않은 외국인 사용자
**I want** 진단 문항·선택지를 내 표시 언어에 맞게 번역된 텍스트로 받고
**So that** 모국어 또는 영어로 질문을 이해하고 정확히 답할 수 있다

- **우선순위**: High (외국인 대상 서비스의 핵심 접근성)
- **관련 NFR**: 국제화(i18n), 일관성(번역 누락 시 폴백), 보안(본인 표시 언어 기반 — 사용자 선택값)
- **백엔드 관점**: 번역 기준은 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**이다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)) — 사용자가 앱 지구본에서 언어를 직접 고르면 그 값이 기준이 되고 고르지 않았으면 `en`이 기준이 되며, 서버가 그 언어의 문항·선택지 **표시 라벨**을 채워 반환한다(`Accept-Language` 헤더에 의존하지 않음; 사용자 선택값이 기기 설정보다 안정적). 표시 언어는 `diagnosis`가 **`user` 모듈 공개 query(`getLanguage`)를 동기 호출**해 취득한다(도출 규칙은 `user`가 캡슐화하며 query 시그니처는 불변; 토큰 클레임 분기는 사용하지 않음; ADR-0002 Decision 5 — 모듈 의존 `diagnosis→user` 추가). 진단은 **세입자·임대인 모두 이용할 수 있다**(별도 역할 게이트 없음) — 임대인은 서버 고정 `lang='ko'`라 진단을 한국어로 본다. 번역은 별도 컬렉션·키 없이 **`diagnosisQuestions` 도큐먼트 안에 인라인 언어-키 맵으로 임베드**한다 — 질문은 `question: { "en": ..., "ja": ..., "ko": ... }`, 선택지는 `options: [ { "code": "SEOUL", "label": { "en": "Seoul", "ja": "ソウル" } }, ... ]`처럼 **언어 코드를 키로 하는 맵**으로 둔다. 서버는 사용자 언어 키(예 `ja`)로 message·label을 고르고, 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다. 선택지 **코드는 언어와 무관하게 동일·불변**(UPPER_SNAKE)하며 언어-키 맵의 값(표시 문자열)만 언어별이다(제출은 코드로 검증). 신규 6개 대학 그룹(`UniversityGroup`) 코드(`HUFS_KHU_KOREA`·`SKKU_SUNGSHIN`·`SNU_CAU_SOONGSIL`·`HONGIK_YONSEI_EWHA`·`KONKUK_SEJONG_HYU`·`ETC`)도 동일하게 UPPER_SNAKE 불변 코드이며 코드로 검증하고, 그룹의 표시 라벨(예 "서울대·중앙대·숭실대" / "Seoul National · Chung-Ang · Soongsil")은 다른 선택지와 똑같이 언어-키 맵으로 번역 대상이 된다. US-2-5와 동일 엔드포인트에서 처리한다.

**AC (Given / When / Then)**

- 시나리오: 정상 — 표시 언어에 맞는 번역 제공

  - **Given** 표시 언어가 일본어(`ja`)인 사용자(`lang="ja"`를 직접 고른 경우)가 진단 문항을 조회한다
  - **When** 진단 문항 조회 엔드포인트를 호출한다
  - **Then** 질문·선택지 표시 라벨이 해당 언어로 번역되어 반환되고, 선택지 코드는 언어와 무관하게 동일하다
- 시나리오: 폴백 — 미지원 언어

  - **Given** 번역이 준비되지 않은 국가/언어의 사용자다
  - **When** 진단 문항을 조회한다
  - **Then** 기본 언어(영어)로 폴백해 반환한다(에러 아님)
- 시나리오: 코드 불변 — 번역과 무관한 제출 검증

  - **Given** 번역된 라벨로 표시된 선택지를 골라 그 **코드**로 제출한다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** 언어와 무관하게 동일 코드로 정상 검증·저장된다

### US-2-7 — 지역 매물 부재 시 재질의·종료 및 서버 주도 진단 흐름 (v2)

**As a** 진단을 진행하는 외국인 사용자
**I want** 내가 시작을 결정한 진단을 서버가 다음 질문·분기·확정 시점을 알아서 판단해 이끌고, ① 지역에 매물이 없으면 다른 지역으로 다시 시도하거나 진단을 끝낼 수 있게 하고
**So that** 매물이 없는 지역인데도 끝까지 답하는 헛수고 없이, 앱을 나갔다 들어오지 않고 그 자리에서 재시도하거나 종료할 수 있다

- **우선순위**: High (진단 완주율·이탈 개선)
- **관련 NFR**: 사용성(불필요한 단계 진행 차단·클라 로컬 분기 제거), 유지보수성(진행 흐름을 서버가 소유), 신뢰성(진행 상태 서버 보관)
- **백엔드 관점**: 기존 v1(`/api/v1/diagnoses/*`, 클라이언트가 `step`·확정을 주도)은 **그대로 두고**, **서버 주도 대화형 흐름을 `/api/v2`에 신설**한다(하위 호환이 깨지는 변경이라 버전 상향 — [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md)). **서버는 질문과 분기만 주도하고, 진단을 시작할 시점과 매물을 받을 시점은 클라이언트가 결정한다** — 클라이언트가 **`POST /api/v2/diagnoses/start`**(본문 없음)로 진단을 시작하고 **`POST /api/v2/diagnoses/next`**로 현재 문항 답 1개씩을 이어 보내면, 서버는 `step`을 받지 않고 직전에 낸 문항에서 다음 질문을 결정하며 빌더가 다 채워지면 **자동 확정**한다. `/start`는 진행 중 세션이 있어도 **무조건 버리고** 새 세션(빈 draft·`pendingField=region`)을 만든다 — 진단하다 홈으로 갔다 다시 시작해도 서버가 기존 진단 정보를 보고 이어가지 않고 **언제나 처음부터**다. 진행 세션이 없는데 `/next`가 오면 서버가 임의로 흐름을 되살리지 않고 `400 DIAGNOSIS_SESSION_NOT_FOUND`로 막으며, 클라이언트는 `/start`로 복구한다. `/next`는 답(`field`)이 반드시 있어야 한다(없으면 `INVALID_INPUT`). 확정 응답에 추천 매물을 인라인으로 싣지 않고 **`diagnosisId`만** 담으며, **확정 시점에 매칭 유무조차 확인하지 않는다** — 매물은 클라이언트가 시점을 정해 **`GET /api/v2/diagnoses/{id}/recommendations`**(v1 §7과 같되 `suggestions` 없음)로 별도 조회하고, **매칭 0건은 그 응답의 `resultCode: NO_MATCH`로 드러난다**(흐름 응답엔 그 코드가 없다 — 미리 알려주려면 클라가 요청하지 않은 추천 쿼리를 서버가 돌려야 한다). ① 지역(`region`) 답 직후 매칭이 0건일 때만 서버가 예외적으로 미리 필터링해 "다른 지역 방을 찾아보시겠어요?" 문항을 끼워 넣는다 — 이 예외질문은 서버 코드에 하드코딩한 합성 문구가 아니라 **문항 카탈로그(`diagnosisQuestions`)의 일반 질문**(`step: 1`·`field: regionRetry`·`select: {type: SINGLE, max: 1}`·옵션 `YES`/`NO`)이라 별도 결과코드 없이 다른 문항과 똑같이 `NEXT_QUESTION`으로 내려가고 번역도 US-2-6과 동일 경로를 탄다(신규 환경은 order 0000 시드, 기배포 환경은 멱등 changeUnit order 0005로 적재). 그 예/아니오 응답에만 **클라이언트가 행할 행위**를 코드로 알린다 — 예=`RESTART`(클라가 `/start`로 처음부터 재시도) · 아니오=`TERMINATED`(진단 종료), 둘 다 세션을 삭제한다. 6단계까지 마친 뒤 매칭이 0건이면 `NO_MATCH`이며 **어떤 제안도 없다**(v1의 `suggestions` 기능·시드는 v1 전용으로 유지하되 v2는 참조하지 않는다). 매 응답은 정상 `200 OK`의 `data.resultCode`(태그드 유니온: `NEXT_QUESTION` / `RESTART` / `COMPLETED` / `TERMINATED`)로 표현한다(에러 아님). 진행 상태는 v1의 `diagnoses`(IN_PROGRESS 초안)를 공유하지 않고 v2 전용 세션(`diagnosisFlowSessions`)에 담고, 완료 시에만 정본 진단을 기존 `diagnoses`에 저장한다. **① 지역 0건으로 끝난 시도는 버리지 않는다** — 부분 답을 `diagnoses`에 `status=DISCARDED`로 남겨 "어느 지역을 원했는데 매물이 없었나"를 수요 분석에 쓴다(재시도·종료 양쪽. 사용자 노출 경로 없음 — 이력·최근은 `COMPLETED`만, v1 초안 조회는 `IN_PROGRESS`만 본다). 그 외 이탈은 돌아왔을 때에야 알 수 있어 집계가 편향되므로 기록하지 않는다. 문항 카탈로그·번역·입력 enum·③ 분기 규칙은 v1과 동일 출처(US-2-5·US-2-6·[ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)·[ADR-0029](../adr/0029-diagnosis-i18n-strategy.md))를 공유한다. 시퀀스: [US-2-7 다이어그램](../architecture/sequence-diagrams/02-diagnosis-recommendation/us-2-7-v2-server-driven-flow.md), API: [02 스펙 v2 절](../api/specs/02-diagnosis-recommendation.md).

**AC (Given / When / Then)**

- 시나리오: 클라 주도 시작 — `/start`로 ① 지역 질문 수령

  - **Given** 유효한 access token을 보유한 사용자가 진단 화면에 진입한다
  - **When** 본문 없이 `POST /api/v2/diagnoses/start`를 호출한다
  - **Then** `200 OK`와 `data.resultCode=NEXT_QUESTION`, `data.question`에 ① 지역(`field=region`) 문항 1개를 반환한다(클라이언트는 `step`을 지정하지 않는다)
- 시나리오: 클라 주도 시작 — 중단 후 다시 시작하면 처음부터

  - **Given** ①~③까지 답한 진행 세션이 남은 채 사용자가 홈으로 나갔다 진단을 다시 시작한다
  - **When** `POST /api/v2/diagnoses/start`를 호출한다
  - **Then** 서버가 진행 중이던 세션을 **무조건 폐기**하고 새 세션(빈 draft·`pendingField=region`)을 만들어 ① 지역 문항을 `NEXT_QUESTION`으로 반환한다(이전 답을 이어받지 않는다 — 서버가 기존 진단 정보를 보고 진행하지 않는다)
- 시나리오: 서버 주도 — 답 적용 후 다음 질문을 서버가 결정

  - **Given** ① 지역 문항을 받은 사용자가 매물이 있는 지역(예: `region=SEOUL`)을 골랐다
  - **When** 그 답 1개(`{ "field": "region", "code": "SEOUL" }`)를 `POST /api/v2/diagnoses/next`로 보낸다
  - **Then** 서버가 답을 저장하고 다음 단계(② 입국 목적) 문항을 `data.resultCode=NEXT_QUESTION`으로 반환한다(다음 `step`을 클라이언트가 지정하지 않는다)
- 시나리오: 지역 매물 0건 — 카탈로그 예외질문을 일반 질문으로 삽입

  - **Given** ① 지역 답이 매칭 매물이 없는 지역(예: MVP에서 `BUSAN`/`GYEONGGI`)이다
  - **When** 그 지역 답을 `POST /api/v2/diagnoses/next`로 보낸다
  - **Then** 서버가 지역-only 매칭이 0건임을 확인하고 `200 OK`와 `data.resultCode=NEXT_QUESTION`(별도 결과코드가 아님), `data.question`에 문항 카탈로그의 예외질문(`step=1`, `field=regionRetry`, 옵션 `YES`/`NO`, "현재 지역에는 매물이 없어요. 다른 지역 방을 찾아보시겠어요?")을 사용자 언어로 반환한다
- 시나리오: 예외질문에 "예" — 클라가 `/start`로 재시도(RESTART)

  - **Given** 직전 응답이 `field=regionRetry` 문항이다
  - **When** `{ "field": "regionRetry", "code": "YES" }`를 `POST /api/v2/diagnoses/next`로 보낸다
  - **Then** `200 OK`와 `data.resultCode=RESTART`(코드만, `question`·`diagnosisId` 없음)를 반환하고 서버는 세션을 삭제한다 — 클라이언트가 그 행위 코드를 받아 `POST /api/v2/diagnoses/start`로 처음부터 다시 시작한다(서버가 지역만 비워 되돌리지 않는다)
- 시나리오: 예외질문에 "아니오" — 진단 종료(TERMINATED)

  - **Given** 직전 응답이 `field=regionRetry` 문항이다
  - **When** `{ "field": "regionRetry", "code": "NO" }`를 `POST /api/v2/diagnoses/next`로 보낸다
  - **Then** 서버가 진행 세션을 폐기하고 `200 OK`와 `data.resultCode=TERMINATED`(진단종료코드)를 반환한다(에러 아님; `question`·`diagnosisId` 없음)
- 시나리오: 자동 확정 — 6단계 완성 시 서버가 확정하고 `diagnosisId`만 반환

  - **Given** ①~⑥ 단계 답이 모두 채워지도록 마지막(⑥ ARC) 답(`{ "field": "arcStatus", "code": "ARC_ISSUED" }`)을 보낸다(빌더 완성)
  - **When** 그 답을 `POST /api/v2/diagnoses/next`로 보낸다
  - **Then** 서버가 별도 확정 요청 없이 진단을 `IN_PROGRESS → COMPLETED`로 자동 확정해 저장하고, 매칭 매물이 존재하면 `data.resultCode=COMPLETED`와 `data.diagnosisId`만 반환한다(추천 매물을 함께 싣지 않는다 — 앱이 시점을 정해 `GET /api/v1/diagnoses/{diagnosisId}/recommendations`로 별도 요청한다)
- 시나리오: 최종 매칭 0건 — 확정은 COMPLETED, 0건은 추천 조회에서 드러남

  - **Given** 6단계까지 다 채웠으나 전체 조건에 맞는 매물이 0건이다
  - **When** 마지막 답으로 자동 확정이 일어난다
  - **Then** 서버는 매칭을 조회하지 않고 `200 OK`와 `data.resultCode=COMPLETED`·`data.diagnosisId`를 반환한다(매칭 유무와 무관 — no-match를 미리 알려주려면 클라이언트가 요청하지도 않은 추천 쿼리를 서버가 돌려야 하므로)
  - **And** 클라이언트가 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`를 호출하면 `content: []`·`markers: []`를 받으며 **이 빈 목록이 곧 no-match**다(조정 제안 `suggestions` 필드 자체가 없다 — v2는 제안 기능을 쓰지 않는다)
- 시나리오: 미완주 시도 보존 — 지역 0건에 "아니오"(포기)

  - **Given** ① 지역으로 매물이 없는 지역(예: `BUSAN`)을 골라 예외질문을 받았다
  - **When** `{ "field": "regionRetry", "code": "NO" }`를 보내 진단을 종료한다
  - **Then** 서버가 진행 세션을 지우기 전에 그때까지의 답을 `diagnoses`에 `status=DISCARDED`(`region=BUSAN`, 나머지 null, `submittedAt`=폐기 시각)로 남긴다 — "부산을 원했는데 매물이 없었다"는 수요 신호를 버리지 않기 위해서다
  - **And** 이 기록은 **사용자에게 노출되지 않는다** — 이력(`GET /api/v1/diagnoses`)·최근 진단은 `COMPLETED`만 보므로 목록에서 자동으로 빠지고, **id로 직접 조회하는 상세(`GET /api/v1/diagnoses/{id}`)·추천도 `404 DIAGNOSIS_NOT_FOUND`** 다(소유권만으론 못 막는다 — 본인 기록인 데다 진단 id가 순차 발급이라 추측 가능하다)
- 시나리오: 미완주 시도 보존 — "예"(재시도)도 남긴다

  - **Given** ① 지역 0건 예외질문을 받았다
  - **When** `{ "field": "regionRetry", "code": "YES" }`로 재시도를 택한다
  - **Then** 그 시도도 `DISCARDED`로 남는다 — 안 남기면 사용자가 다른 지역으로 완주했을 때 **원래 원했던 지역이 증발**한다
- 시나리오: 중도 이탈은 기록하지 않는다

  - **Given** ①~③까지 답하고 홈으로 나갔다가 나중에 진단을 다시 시작한다
  - **When** `POST /api/v2/diagnoses/start`가 이전 세션을 덮어쓴다
  - **Then** 이전 시도는 **기록하지 않고 버린다** — 이탈은 요청으로 오지 않아 돌아왔을 때에야 알 수 있어, 영영 안 돌아온 사용자는 집계에서 누락되고 시각도 실제 이탈 시각이 아니라 재시작 시각이라 신뢰할 수 없다(그 자리에서 정확히 아는 ① 지역 0건 경로만 남긴다)
- 시나리오: 매물 조회는 클라가 결정 — v2 추천 엔드포인트

  - **Given** 확정으로 `diagnosisId`를 받은 사용자가 진단 결과 화면에 진입한다
  - **When** `GET /api/v2/diagnoses/{diagnosisId}/recommendations?page=0&size=20`을 호출한다
  - **Then** `200 OK`와 추천 매물(`content`)·지도 좌표(`markers`)·페이지 메타(`page`)를 반환한다(v1 §7과 같은 계약이되 `suggestions`는 없다)
  - **And** 타인 소유 진단이면 `403 FORBIDDEN`, 없는 진단이면 `404 DIAGNOSIS_NOT_FOUND`다
- 시나리오: 세션 없음 — 진행 중 진단 없이 `/next`

  - **Given** 앱 재시작·터미널 응답 후 재전송·세션 만료 등으로 진행 중 세션이 없다
  - **When** `POST /api/v2/diagnoses/next`를 호출한다
  - **Then** `400`과 `error.code=DIAGNOSIS_SESSION_NOT_FOUND`("진행 중인 진단이 없습니다. 진단을 다시 시작해 주세요.")를 반환한다 — 서버가 임의로 흐름을 되살리거나 새 진단을 시작하지 않으며, 클라이언트가 `POST /api/v2/diagnoses/start`로 복구한다
- 시나리오: 답 누락 — `/next`에 답이 없음

  - **Given** 진행 중 세션이 있으나 요청 본문이 없거나 `field`가 비어 있다
  - **When** `POST /api/v2/diagnoses/next`를 호출한다
  - **Then** `400`과 `error.code=INVALID_INPUT`을 반환한다(현재 단계와 다른 `field`·미정의 enum·`regionRetry` code가 `YES`/`NO`가 아닌 경우도 동일)
- 시나리오: v1 무변경 — 기존 흐름 보존

  - **Given** 기존 클라이언트가 v1 흐름(`GET /api/v1/diagnoses/questions/{step}` · `POST /api/v1/diagnoses/answers` · `POST /api/v1/diagnoses`)을 사용한다
  - **When** v2 추가 이후에도 v1 엔드포인트를 그대로 호출한다
  - **Then** v1 동작·응답 계약이 바뀌지 않는다(예: `GET /api/v1/diagnoses/questions/1`은 `regionRetry` 문항이 같은 `step 1`에 있어도 ① 지역(`field=region`) 문항을 그대로 반환한다 — v2는 새 컨트롤러로만 추가되고 v1 로직을 건드리지 않는다)

## 3. 매물 탐색 · 찜

> 관련 API 스펙: [03-listings-favorites](../api/specs/03-listings-favorites.md)

외국인 사용자가 서울 지역 매물을 지도/리스트/검색으로 탐색하고, 상세를 확인하며, 관심 매물을 찜하고 최근 본 매물을 다시 찾는 흐름을 다룬다. 상세 조회·찜·최근 본 매물은 인증이 필수다. 응답은 모두 공통 래퍼 `{ success, data, error }`를 따르며, 에러 코드/HTTP status는 [error-response-guide](../api/error-response-guide.md)를 정본으로 한다. 검증 실패 시 필드 상세는 `error.errors[]`에 담긴다.

> **매물 다국어 표시([ADR-0037](../adr/0037-listing-localization-and-code-catalog.md))**: 매물 고유 문구(제목·주소·역명·방 이름·설명)는 `listings` 안 `{ko,en}`에서 사용자 언어 하나를 선택한다. UI에 표시하는 공통 코드(매물/임대/성별/교통/건물/시설/조건)는 `listingCatalog` 번역과 결합한 `{code,label}`로 응답한다. 프론트는 label을 표시하고 code를 기존 필터 요청·비즈니스 비교에 사용한다. 로그인 사용자는 `user::api getLanguage`로 계정에서 선택한 표시 언어(`users.lang`)를 얻고, 비로그인·미지원 언어는 MVP 기본 영어를 사용한다. ID·좌표·가격·재고·상태·통화와 요청 code는 번역하지 않는다.

### US-3-1 — 매물 리스트 탐색(필터·정렬·페이지네이션)

**As a** 한국 주거를 찾는 외국인 사용자
**I want** 예산·조건 칩으로 필터링하고 추천/가격/거리 순으로 정렬된 매물 목록을 오프셋 페이지 단위로 조회
**So that** 내 조건(예산·생활 조건)에 맞는 매물을 한눈에 비교하고 더 볼 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 목록 조회 응답시간 목표(NFR 미정), 무상태 조회로 수평 확장 가능
- 데이터 관점: 필터는 서버에서 MongoDB 질의 조건으로 평탄화(매물은 MongoDB 저장, ADR-0005), `sort=DISTANCE`는 `centerLat/centerLng`가 있어야 의미 있음, `page.totalElements`는 동일 필터 조건으로 산출

**AC (Given / When / Then)**

- 시나리오: 정상 목록 조회
  Given 활성 매물이 N건 존재하고
  When 비로그인 사용자가 `GET /api/v1/listings?minBudget=300000&maxBudget=700000&conditions=ENGLISH_OK&sort=PRICE_ASC&page=0&size=20`을 호출하면
  Then `200 OK`로 공통 래퍼의 `data.content[]`에 가격 오름차순으로 매물 요약이 담기고 `data.page`에 `number/size/totalElements/totalPages/hasNext`가 포함된다
- 시나리오: 입력 검증 실패(필터 값 오류)
  Given 클라이언트가
  When `minBudget=700000&maxBudget=300000`(최소>최대) 또는 정의되지 않은 `conditions=UNKNOWN_TAG`, `sort=CHEAPEST`처럼 허용되지 않은 enum/범위를 보내면
  Then 서버는 무시하지 않고 `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하며 `error.errors[]`에 위반 필드(`field`/`reason`)를 담는다
- 시나리오: 거리순 정렬에 기준 좌표 누락
  Given 사용자가
  When `sort=DISTANCE`를 지정했으나 `centerLat`/`centerLng`를 함께 보내지 않으면
  Then `400 Bad Request`, `error.code=LISTING_INVALID_SORT_PARAM`을 반환한다
- 시나리오: 경계(페이지 범위 초과·빈 결과)
  Given 필터 결과가 0건이거나 마지막 페이지를 넘는 `page`가 요청되면
  When 목록을 호출하면
  Then `200 OK`로 `data.content`는 빈 배열, `data.page.hasNext=false`를 반환한다(에러 아님)
- 시나리오: 인증 선택(비로그인 vs 로그인 맞춤 필드)
  Given 동일 매물에 대해
  When 비로그인 사용자가 호출하면 각 항목 `favorited=false`로, 로그인 사용자가 호출하면 본인의 찜 여부가 `favorited`에 반영되어 반환된다
  Then 두 경우 모두 `200 OK`이며 공개 데이터는 동일하다
- 시나리오: 기본 영어 표시와 code 보존
  Given 비로그인 사용자 또는 영어 국가로 등록한 사용자가
  When 매물 목록을 호출하면
  Then 제목·주소·역명·방 이름·설명은 영어 문자열이고, 공통 표시값은 예를 들어 `{ "code":"FEMALE_ONLY", "label":"Female Only" }`로 반환된다. 같은 필터를 다시 요청할 때는 label이 아니라 `code=FEMALE_ONLY`를 보낸다

### US-3-2 — 지도 bbox 마커 조회

**As a** 특정 지역·학교 주변에서 집을 찾는 외국인 사용자
**I want** 지도 화면에 보이는 영역(bounding box) 내 매물의 개별 좌표를 받기
**So that** 지도를 이동/확대하며 매물 분포를 빠르게 파악할 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 지도 패닝 시 잦은 호출을 견디는 응답시간/캐시 전략(NFR 미정)
- 데이터 관점: bbox는 4좌표가 모두 있어야 유효, 서버는 요청 bbox를 20% 확장해 조회, 응답은 프론트 지도 SDK가 클러스터링할 개별 매물 좌표(`listingId`, `lat`, `lng`) 중심, 결과 수는 서버 설정값(예: 최대 500건)으로 초과 시 에러 처리

**AC (Given / When / Then)**

- 시나리오: 정상 bbox 마커 조회
  Given 지도 영역이 유효 좌표로 주어지고
  When `GET /api/v1/listings/map?swLat=37.49&swLng=126.95&neLat=37.57&neLng=127.05`를 호출하면
  Then `200 OK`로 `data.markers[]`(각 항목 `listingId/lat/lng`)를 반환한다
- 시나리오: 입력 검증 실패(좌표 불완전/모순)
  Given 클라이언트가
  When bbox 4좌표 중 일부만 보내거나 `swLat > neLat`처럼 모순된 좌표를 보내면
  Then `400 Bad Request`, `error.code=LISTING_INVALID_BBOX`를 반환한다
- 시나리오: 인증 선택(비로그인 허용)
  Given 토큰 없는 사용자가
  When 지도 검색을 호출하면
  Then `200 OK`로 정상 조회된다(마커 응답에는 사용자 맞춤 필드 `favorited`가 포함되지 않는다)
- 시나리오: 경계(과도한 영역)
  Given 한 번에 너무 넓은 bbox가 주어지면
  When 검색을 호출하면
  Then 결과 수가 상한을 초과하는 경우 `400 Bad Request`, `error.code=LISTING_AREA_TOO_LARGE`로 범위 축소를 유도한다

### US-3-3 — 네이버 장소 검색 및 주변 매물 조회

**As a** 학교·동네·시설 이름만 아는 외국인 사용자
**I want** 키워드로 장소 후보를 검색하고 원하는 장소를 선택해 주변 매물을 조회하기
**So that** 정확한 주소나 좌표를 몰라도 익숙한 장소 이름을 기준으로 집을 찾을 수 있다

- 메타: 우선순위 **Mid**, 관련 NFR — 네이버 지역 검색 API 응답시간·가용성, 외부 API 인증정보 보호
- 데이터 관점: `GET /api/v1/listings/places`는 네이버 장소 후보만 최대 5개 반환하고 MongoDB 매물은 조회하지 않는다. 백엔드는 `mapx/mapy`를 WGS84 `lng/lat`으로 변환하며, 사용자가 후보를 선택한 뒤 앱이 계산한 bounds로 기존 `/api/v1/listings`와 `/api/v1/listings/map`을 호출한다.

**AC (Given / When / Then)**

- 시나리오: 정상 장소 후보 검색
  Given 유효한 access token을 가진 사용자가 "경희대"를 입력하고
  When `GET /api/v1/listings/places?keyword=경희대`를 Bearer access token과 함께 호출하면
  Then `200 OK`로 `data.items[]`에 `title`·`address`·`roadAddress`·`lat`·`lng`를 포함한 장소 후보를 최대 5개 반환한다
- 시나리오: 장소 선택 후 주변 매물 조회
  Given 사용자가 장소 후보 하나를 선택해 앱이 해당 `lat/lng`로 지도를 이동하고 현재 bounds를 계산하면
  When 같은 `swLat`·`swLng`·`neLat`·`neLng`를 `/api/v1/listings`와 `/api/v1/listings/map`에 전달하면
  Then 매물 목록(`data.content[]`)과 지도 마커(`data.markers[]`)를 각각 `200 OK`로 반환한다
- 시나리오: 입력 검증 실패(빈/과도 키워드)
  Given 클라이언트가
  When `keyword`를 누락하거나 공백만, 또는 50자를 초과해 보내면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환한다
- 시나리오: 장소 후보 없음(경계)
  Given 네이버 지역 검색 결과가 없는 키워드가 주어지고
  When 장소 검색 API를 호출하면
  Then 에러가 아닌 `200 OK`로 `data.items=[]`를 반환한다
- 시나리오: 인증 실패
  Given access token이 누락·위조 또는 만료되었고
  When 장소 검색 API를 호출하면
  Then 누락·위조는 `401 UNAUTHENTICATED`, 만료는 `401 TOKEN_EXPIRED`를 반환한다
- 시나리오: 네이버 연동 실패
  Given 네이버 API가 4xx/5xx를 반환하거나 타임아웃·인증정보 누락·응답 형식 이상이 발생하고
  When 장소 검색 API를 호출하면
  Then `502 Bad Gateway`, `error.code=UPSTREAM_ERROR`를 반환한다

> 기존 `GET /api/v1/listings/search`는 호환성을 위해 유지하지만, 본 사용자 스토리의 신규 지도 검색 흐름에서는 사용하지 않는다.

### US-3-4 — 매물 상세 조회 + 최근 본 매물 기록

**As a** 후보 매물을 자세히 보려는 외국인 사용자
**I want** 사진 갤러리·유형·가격·보증금·계약기간·위치·편의시설·임대인 정보가 담긴 상세를 보고, 로그인 상태면 본 매물이 최근 본 목록에 기록되기
**So that** 매물을 충분히 검토하고 다시 쉽게 찾아올 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 최근 본 매물 DB 보관 사용자별 최대 30개·조회 응답 최대 10개
- 데이터 관점: 임대인 연락처는 노출하지 않고 채팅으로만 연결, 최근 본 매물은 (userId, listingId) 유니크로 upsert하며 `viewedAt` 갱신, 상세 조회 성공 후 30개 초과 오래된 기록 삭제

**AC (Given / When / Then)**

- 시나리오: 정상 상세 조회(로그인) 및 기록
  Given 인증된 사용자가 존재하는 매물을 조회하면
  When `GET /api/v1/listings/{listingId}` (Authorization 포함)
  Then `200 OK`로 상세(사진 `imageUrls[]`, `type`, `monthlyRent`, `deposit`, `contractTermOptions[]`, `location`, `conditions[]`, `landlord`, `favorited`, `favoriteCount`)를 반환하고, 해당 매물이 최근 본 매물에 upsert된다
- 시나리오: 한국어 사용자 표시
  Given 계정의 표시 언어를 한국어(`ko`)로 선택한 사용자가
  When 같은 매물 상세를 조회하면
  Then 영문 사용자와 동일한 `code`를 받되 고유 문구와 `{code,label}`의 label은 한국어로 반환된다. 프론트의 필터 요청 code는 언어에 따라 달라지지 않는다
- 시나리오: 리소스 없음
  Given 존재하지 않거나 비공개/삭제된 매물 ID로
  When 상세를 조회하면
  Then `404 Not Found`, `error.code=LISTING_NOT_FOUND`를 반환한다
- 시나리오: 인증 실패
  Given 토큰 없는 사용자가
  When 상세를 조회하면
  Then `401 Unauthorized`, `error.code=UNAUTHENTICATED`를 반환한다
- 시나리오: 경계(최근 본 매물 30개 초과·동일 매물 재조회)
  Given 사용자가 이미 30개의 최근 본 매물을 가졌거나 같은 매물을 다시 보면
  When 상세를 조회하면
  Then 중복은 새 행을 만들지 않고 `viewedAt`만 갱신하며, 30개 초과분은 오래된 기록부터 삭제되고, `GET /api/v1/users/me/recent-listings` 조회 시 공개 매물만 최신순 최대 10건 반환된다

### US-3-5 — 찜 토글·찜 목록(인증 필수)

**As a** 관심 매물을 모아두려는 로그인 사용자
**I want** 매물 상세/목록에서 찜을 등록·해제하고 내 찜 목록을 조회
**So that** 마음에 든 매물을 다시 찾아 비교/연락할 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 동시 토글 시 `favoriteCount` 정합성(중복 찜 방지 유니크 제약)
- 데이터 관점: (userId, listingId) 유니크 제약으로 멱등 보장, `favoriteCount`는 카운터 컬럼 원자적 증감 또는 집계로 산출, 토글 응답에 최종 상태(`favorited`, `favoriteCount`) 반환. 찜·찜 목록·최근 본 매물은 모두 `me` 스코프이므로 타인 리소스 접근 경로가 없어 별도 `403`은 발생하지 않는다(인증 실패는 `401`)

**AC (Given / When / Then)**

- 시나리오: 정상 찜 신규 등록(생성)
  Given 인증된 사용자가 찜하지 않은 매물에 대해
  When `POST /api/v1/listings/{listingId}/favorite`를 호출하면
  Then `201 Created`로 `data={ "favorited": true, "favoriteCount": <증가값> }`를 반환한다
- 시나리오: 정상 찜 해제
  Given 인증된 사용자가 이미 찜한 매물에 대해
  When `DELETE /api/v1/listings/{listingId}/favorite`를 호출하면
  Then `200 OK`로 `data={ "favorited": false, "favoriteCount": <감소값> }`를 반환한다
- 시나리오: 인증 실패
  Given 토큰이 없거나 만료된 사용자가
  When 찜 등록/해제 또는 찜 목록을 호출하면
  Then `401 Unauthorized`, `error.code=UNAUTHENTICATED`(또는 만료 시 `TOKEN_EXPIRED`)를 반환한다
- 시나리오: 리소스 없음
  Given 존재하지 않거나 비공개/삭제된 매물 ID로
  When 찜 등록을 호출하면
  Then `404 Not Found`, `error.code=LISTING_NOT_FOUND`를 반환한다
- 시나리오: 경계·동시성(중복 찜·동시 요청 멱등)
  Given 사용자가 이미 찜한 매물에 대해
  When 같은 등록 요청이 (네트워크 재시도 등으로) 반복/동시에 들어오면
  Then 유니크 제약으로 중복 행이 생기지 않고, 추가 생성이 없으므로 `200 OK`로 현재 상태 `{ "favorited": true, "favoriteCount": <실제값> }`를 멱등하게 반환한다(별도 `LISTING_ALREADY_FAVORITED` 에러로 보지 않음). 마찬가지로 찜하지 않은 매물 해제는 멱등하게 `200 OK`, `{ "favorited": false, "favoriteCount": <실제값> }`를 반환한다
- 시나리오: 정상 찜 목록 조회
  Given 인증된 사용자가 찜한 매물이 있고
  When `GET /api/v1/users/me/favorites?page=0&size=20`을 호출하면
  Then `200 OK`로 `data.content[]`(모두 `favorited=true`)와 `data.page`를 반환하며, 찜이 없으면 빈 배열을 반환한다

## 4. 매물 예약(신청) · (후속) 문의·인앱 채팅

> 관련 API 스펙: [04-booking-inquiry-chat](../api/specs/04-booking-inquiry-chat.md)
>
> **스코프(1차 MVP)**: **매물 예약(= 신청, Booking)** 은 인앱 채팅과 분리된 **독립 기능**으로 구현한다. (**본 서비스에서 "신청"과 "예약"은 같은 `Booking`을 가리키는 동의어다.**) `ACTIVE` 세입자가 방 상품(`roomOffer`)에 타겟 입주일·계약기간으로 예약을 신청해 내역을 저장하고(US-4-1), 내 예약을 목록·단건 상세로 조회한다(US-4-2). 예약 상세는 **매물 정보·예약 일시·타겟 입주일·계약기간·예약자 성명·보증금·총 금액**을 내려준다. **임대인은 자기 소유 매물(`listing.landlordId`=본인)에 신청된 예약을 목록·단건 상세로 조회한다(US-4-6)** — 예약 **생성**은 세입자 전용이지만 **조회는 세입자(내 예약)·임대인(내 매물에 신청된 예약)** 두 관점으로 나뉜다. 후속으로 분리·이연하는 것은 **예약(신청) 자체가 아니라 문의(inquiry)·인앱 채팅(US-4-3~US-4-5), 그리고 예약 신청 시 채팅방에 예약 카드를 자동 기록하던 기존 F-03 chat 결합**이다 — 예약 생성 시 `BOOKING_CARD` 자동 전송·`BookingCreatedEvent` 발행은 하지 않는다. 예약은 매물·회원과 cross-store 조인이 금지되므로([ADR-0005](../adr/0005-polyglot-persistence.md)) 가격·성명은 조회 시점에 애플리케이션 레벨로 조합한다(`listing :: api`·`user :: api` 공개 쿼리, [ADR-0002](../adr/0002-inter-module-communication-via-events.md)).

외국인 세입자가 매물의 방 상품에 예약을 신청하면 예약 내역(대상 방 상품·타겟 입주일·계약기간·상태)이 저장되고, 세입자는 자신의 예약 목록과 상세(예상 비용 포함)를 다시 확인할 수 있다.

공통 규약: 모든 응답은 공통 래퍼 `{ success, data, error }`, 인증은 `Authorization: Bearer <accessToken>`, 에러 형식·코드는 [error-response-guide](../api/error-response-guide.md)를 따른다.

### US-4-1 — 매물 예약 생성(신청 저장)

**As a** 온보딩을 마친(`ACTIVE`) 외국인 세입자
**I want** 원하는 방 상품(`roomOffer`)에 타겟 입주일과 계약기간을 골라 예약을 신청하고 그 내역이 저장되기를
**So that** 나중에 내 예약 내역을 다시 확인하고, 같은 방에 중복 예약하는 실수를 막는다.

- 우선순위: High
- 관련 NFR: 보안(`ACTIVE`·`TENANT` 게이트), 입력 검증
- 백엔드 관점: `Booking`을 `REQUESTED` 상태로 저장한다(필드: `tenantId`·`listingId`·`roomOfferId`·`moveInDate`·`contractPeriod`(정수 개월수)·`status`·`createdAt`). `tenantId`는 SecurityContext에서 얻고, 요청자가 `ACTIVE`이며 `userType=TENANT`인지 다른 보호 엔드포인트와 **동일한 게이트**로 검사한다(온보딩 미완료·비세입자 차단; 상태-게이트 1:1 일치). **예약은 세입자 전용이라 임대인은 예약을 수행할 수 없고, 세입자가 자기 소유 매물을 예약하는 상황 자체가 성립하지 않으므로 본인 매물 차단(소유자 조회)은 두지 않는다.** 매물·방 상품 존재·공개 여부는 `listing :: api`로 검증한다(cross-store 조인 금지, ADR-0005). **MVP의 예약은 "신청" 성격이라 동일 방 상품 중복 신청을 제한하지 않는다**(활성 유니크 제약 없음 — 같은 방에도 여러 번 신청 가능). **예약 카드 전송·`BookingCreatedEvent` 발행은 후속(문의·인앱 채팅)** — 본 스토리에서는 하지 않는다.

**AC (Given/When/Then)**

- 정상
  - **Given** `ACTIVE` 세입자(`userType=TENANT`)가 공개 매물의 방 상품을 보고 있을 때
  - **When** `roomOfferId`·`moveInDate`(미래 날짜)·`contractPeriod`(양의 정수, 개월수)로 `POST /api/v1/listings/{listingId}/bookings`를 호출하면
  - **Then** `201 Created` + `Location: /api/v1/bookings/{bookingId}`로 응답하고, `data`에 `bookingId`·`status: "REQUESTED"`·`listingId`·`roomOfferId`·`moveInDate`·`contractPeriod`·`createdAt`가 포함된다.
- 입력 검증 실패
  - **When** `roomOfferId`나 `contractPeriod`를 누락하거나 `contractPeriod`가 양의 정수가 아니면(0·음수) → `400` + `INVALID_INPUT`(`errors[]`에 위반 필드). **When** `moveInDate`를 날짜 형식이 아닌 값/타입, 또는 `contractPeriod`를 숫자 아닌 타입으로 보내면 → `400` + `MALFORMED_REQUEST`.
  - **Then** 어느 경우에도 예약은 생성되지 않는다.
- 비즈니스 규칙(입주일)
  - **When** `moveInDate`가 (형식은 유효하나) 과거이거나 매물 입주 가능일 이전이면 → `422` + `BOOKING_INVALID_MOVE_IN_DATE`.
  - **Then** 이 경우 예약은 생성되지 않는다.
- 인증·권한·상태 게이트
  - **Given** `Authorization` 헤더가 없거나 만료된 토큰이면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`. **Given** 인증은 됐으나 온보딩 미완료(비`ACTIVE`) 사용자면 → 다른 보호 엔드포인트와 동일한 **온보딩 상태 게이트 에러**로 차단한다(코드 게이트와 1:1 일치). **Given** `userType`이 세입자가 아니면(임대인) → `403` + `FORBIDDEN`(예약은 세입자 전용).
  - **When** 존재하지 않거나 비공개/삭제된 매물·방 상품 ID로 호출하면 → `404` + `LISTING_NOT_FOUND`.
- 다건 신청 허용(중복 제한 없음)
  - **Given** 세입자가 같은 방 상품에 이미 신청한 이력이 있을 때
  - **When** 같은 방 상품에 다시 신청하면
  - **Then** 별개의 예약(신청)으로 정상 저장된다(`201`) — MVP의 예약은 "신청" 성격이라 활성 예약 중복 제한이 없다.

> 예약 수락/거절·취소 등 상태 전이는 본 스토리 범위 밖(확장 시 정의). 신청 직후 상태는 `REQUESTED` 고정.

### US-4-2 — 내 예약 조회(목록·단건 상세)

**As a** 매물을 예약(신청)한 외국인 세입자
**I want** 내 예약 목록과 각 예약의 상세(매물 정보·예약 일시·타겟 입주일·계약기간·예약자 성명·보증금·총 금액)를 조회하기를
**So that** 어떤 방을 어떤 조건으로 예약했는지, 예상 비용이 얼마인지 다시 확인한다.

- 우선순위: High
- 관련 NFR: 보안(본인 예약만 조회), 성능(목록 페이지네이션), 정합성(가격·성명 조회 시점 조인)
- 백엔드 관점: `GET /api/v1/bookings`는 요청자 본인 예약만 `createdAt` 내림차순 **오프셋 페이지네이션**(api-design-guide §4-1)으로 반환하고, `GET /api/v1/bookings/{bookingId}`는 단건 상세다. **가격·매물 정보·성명은 예약에 스냅샷 저장하지 않고 조회 시점에 실시간 조인**한다 — `listing :: api`로 `(listingId, roomOfferId)`의 매물 요약·`pricing`(보증금·월세)을, `user :: api`(`getUserName`)로 예약자 성명을 가져온다(둘 다 신규 공개 조회 메서드 필요). **총 금액 = 보증금 + 월세 × `contractPeriod`**(`contractPeriod`는 계약 개월수 정수, **관리비 제외**). 타인 예약은 조회되지 않는다.

**AC (Given/When/Then)**

- 정상(목록)
  - **Given** 인증된 세입자가 예약 2건을 보유할 때
  - **When** `GET /api/v1/bookings?page=0&size=20`을 호출하면
  - **Then** `200 OK` + `data.content`가 `createdAt` 내림차순으로 정렬되고, 각 항목에 `bookingId`·매물 요약(`listingId`·제목·썸네일)·`roomOfferId`·`moveInDate`·`contractPeriod`·`status`·`createdAt`가 포함되며 `data.page`에 오프셋 메타가 담긴다.
- 정상(단건 상세)
  - **Given** 인증된 세입자가 본인 예약 1건을 지목할 때
  - **When** `GET /api/v1/bookings/{bookingId}`를 호출하면
  - **Then** `200 OK` + `data`에 **매물 정보**(제목·썸네일·주소·방 상품명 등)·**예약 일시**(`createdAt`)·**타겟 입주일**(`moveInDate`)·**계약기간**(`contractPeriod`)·**예약자 성명**·**보증금**(`deposit`)·**총 금액**(`deposit + monthlyRent × 개월수`)이 포함된다.
- 정합성(실시간 가격)
  - **Given** 예약 이후 해당 방 상품의 가격(`pricing`)이 변경됐을 때
  - **When** 세입자가 상세를 다시 조회하면
  - **Then** 스냅샷이 아니라 **현재 가격 기준**으로 보증금·총 금액을 계산해 내려준다.
- 인증·권한
  - **Given** 토큰이 없거나 만료된 요청이면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`.
  - **When** 존재하지 않는 예약이거나 **타인의 예약** ID로 상세를 조회하면 → `404` + `BOOKING_NOT_FOUND`(존재 여부를 노출하지 않도록 본인 예약이 아니면 404로 통일; 신규 에러코드 필요).
- 경계(빈 목록/삭제된 매물)
  - **Given** 예약이 하나도 없을 때 → `GET /api/v1/bookings`는 `200` + `content: []`, `page.totalElements: 0`.
  - **Given** 예약한 방 상품이 이후 비공개/삭제됐을 때 → 상세 조회 시 예약 코어 내역(날짜·계약기간·상태)은 유지하되 매물 정보·가격 파트의 표기 정책은 **(확인 필요)** — 매물 필드 `null`/tombstone 반환 vs 별도 상태 코드.

> 신설 의존: 신규 에러코드 `BOOKING_NOT_FOUND`(404), `listing :: api`(매물 요약·roomOffer 가격 조회)·`user :: api`(`getUserName`) 공개 메서드, 그리고 `booking → {listing::api, user::api}` 의존 화이트리스트(`booking/package-info.java`) 추가가 선행돼야 한다.

### (후속·이연) US-4-3 — 매물 문의(채팅방 생성/조회) 및 매물 카드 고정

> **아래 US-4-3~US-4-5(문의·채팅방·메시지)는 인앱 채팅(기존 F-03 chat 결합)으로, 1차 MVP에서는 후속으로 분리·이연한다.** 매물 예약(신청, US-4-1·US-4-2)과 달리 문의·채팅 기능, 그리고 예약 신청 시 채팅방에 예약 카드를 자동 기록하던 결합은 재개 시 구현한다. 상세 설계·엔드포인트는 [spec-04](../api/specs/04-booking-inquiry-chat.md)·[시퀀스 다이어그램](../architecture/sequence-diagrams/04-booking-inquiry-chat/README.md)에 보존돼 있다(재개 시 번호·경로 재정합).

**As a** 매물이 궁금한 외국인 세입자
**I want** 임대인에게 문의를 시작하면 매물 정보 카드가 고정된 채팅방을 얻기를(없으면 생성, 있으면 기존 방 반환)
**So that** 문의 때마다 방이 중복 생성되지 않고, 어떤 매물에 대한 대화인지 맥락이 항상 유지된다.

- 우선순위: High
- 관련 NFR: 멱등성(같은 매물 반복 문의 시 동일 방 반환), 보안(본인 매물 문의 차단)
- 백엔드 관점: (매물, 세입자, 임대인) 키로 채팅방을 upsert한다. 신규 생성 시에만 `LISTING_CARD`를 `pinned: true`로 추가하고 `created: true` + `201`을, 기존 방이면 `created: false` + `200`을 반환한다.

**AC (Given/When/Then)**

- 정상(신규)
  - **Given** 인증된 세입자가 본인 소유가 아닌 매물에 대해 아직 채팅방이 없을 때
  - **When** `POST /api/v1/listings/{listingId}/inquiries`를 호출하면
  - **Then** `201 Created` + `Location` 헤더, `data.created: true`, `category: "LANDLORD"`, `pinned: true`인 `listingCard`와 `counterpart`(임대인) 정보가 반환된다.
- 정상(기존 방 반환 — 멱등)
  - **Given** 동일 매물에 대해 이미 채팅방이 존재할 때
  - **When** 같은 세입자가 다시 문의 API를 호출하면
  - **Then** `200 OK` + `data.created: false`로 기존 `chatRoomId`를 반환하고, 새 방이나 중복 카드 메시지는 생성되지 않는다.
- 인증·권한
  - **Given** 토큰이 없거나 만료된 요청일 때 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`. **Given** 본인이 소유한 매물일 때
  - **When** 문의 API를 호출하면
  - **Then** `422` + `CHAT_SELF_INQUIRY_NOT_ALLOWED`로 거절된다.
- 경계(없는 매물)
  - **Given** 인증된 세입자가
  - **When** 존재하지 않거나 비공개/삭제된 매물 ID로 문의하면
  - **Then** `404` + `LISTING_NOT_FOUND`로 응답하고 방을 만들지 않는다.

### (후속·이연) US-4-4 — 채팅방 리스트 조회

**As a** 여러 임대인과 대화 중인 세입자
**I want** 내가 참여한 채팅방 목록을 매물 썸네일·상대·마지막 메시지·시간·안읽음 수와 함께 최신순으로 보기를
**So that** 어떤 대화에 새 메시지가 왔는지 한눈에 파악하고 빠르게 이어갈 수 있다.

- 우선순위: High
- 관련 NFR: 성능(목록 응답시간), 보안(타인 방 미노출)
- 백엔드 관점: 요청자가 참여한 방만 `lastMessageAt` 내림차순으로 오프셋 페이지네이션(api-design-guide §4-1)으로 반환한다. `unreadCount`는 요청자의 마지막 읽은 위치 기준으로 계산한다. `category`로 `LANDLORD`/`NEIGHBOR` 필터링이 가능하다.

**AC (Given/When/Then)**

- 정상
  - **Given** 인증된 세입자가 채팅방 3개에 참여 중일 때
  - **When** `GET /api/v1/chat-rooms?page=0&size=20`을 호출하면
  - **Then** `200 OK` + `data.content`가 `lastMessageAt` 내림차순으로 정렬되고, 각 방에 `listing.thumbnailUrl`·`counterpart`·`lastMessage`·`unreadCount`가 포함되며 `data.page`에 오프셋 메타(`number`/`size`/`totalElements`/`totalPages`/`hasNext`)가 담긴다.
- 입력 검증 실패
  - **Given** 인증된 세입자가
  - **When** `category`에 정의되지 않은 값(예: `FRIEND`)이나 `size=500`(최대 100 초과)으로 호출하면
  - **Then** `400` + `INVALID_INPUT`으로 응답한다(허용되지 않은 필터 값은 무시하지 않고 알린다, api-design-guide §5).
- 인증·권한
  - **Given** 토큰이 없거나 만료된 요청일 때
  - **When** 리스트 API를 호출하면
  - **Then** `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`로 응답한다. 타인이 참여한 방은 응답 목록에 절대 포함되지 않는다.
- 경계
  - **Given** 인증된 세입자가 참여 중인 방이 하나도 없을 때
  - **When** 리스트 API를 호출하면
  - **Then** `200 OK` + 빈 `content: []`와 `page.totalElements: 0`, `page.hasNext: false`를 반환한다(에러 아님).

### (후속·이연) US-4-5 — 채팅 메시지 조회·전송·읽음 처리

**As a** 채팅방에 참여한 세입자 또는 임대인
**I want** 방의 메시지를 커서 페이지네이션으로 거슬러 보고, 텍스트 메시지를 보내고, 읽음 처리를 하기를
**So that** 과거 대화를 끊김 없이 이어보고, 안읽음 수를 정확히 유지하며, 상대에게 즉시 알림이 가게 한다.

- 우선순위: High
- 관련 NFR: 성능(메시지 페이지 조회), 관측성(푸시 이벤트), 레이트리밋(도배 방지), 보안(참여자만 접근)
- 백엔드 관점: 메시지는 최신순 커서 페이지네이션(api-design-guide §4-2). 전송은 `TEXT`만 허용(이미지·파일 불가, 서버가 `type` 고정)하고 전송 시 `lastMessageAt` 갱신 + 푸시 알림 도메인 이벤트를 발행한다. 읽음 처리는 마지막 읽은 메시지 위치를 전진만 시키는 멱등 연산이다. 모든 메시지 API는 요청자가 방 참여자인지 먼저 검사한다.

**AC (Given/When/Then)**

- 정상(조회·전송·읽음)
  - **Given** 방 참여자인 인증 사용자가
  - **When** `GET /api/v1/chat-rooms/{roomId}/messages?cursor=...&size=30`을 호출하면 → `200` + 최신순 `content`, `nextCursor`, `hasNext`. **When** `POST /api/v1/chat-rooms/{roomId}/messages`로 `content`를 보내면 → `201` + 저장된 `TEXT` 메시지(`mine: true`)와 상대에게 푸시 이벤트. **When** `POST /api/v1/chat-rooms/{roomId}/read`를 호출하면 → `200` + `unreadCount: 0`.
  - **Then** 위 결과가 각각 관찰되고 고정 카드(`pinned: true`)도 메시지 목록에 포함된다.
- 입력 검증 실패
  - **Given** 방 참여자가
  - **When** 빈/공백만 있는 `content`나 1000자 초과 본문을 전송하면 → `400` + `INVALID_INPUT`. **When** `size=500`(최대 초과)이나 잘못된 `cursor`로 조회하면 → `400` + `INVALID_INPUT`. **When** `read`에 `lastReadMessageId`를 숫자가 아닌 타입으로 보내면 → `400` + `MALFORMED_REQUEST`.
  - **Then** 메시지가 저장되지 않는다(이미지·파일 본문은 애초에 스키마에 없어 무시·거절).
- 인증·권한
  - **Given** 인증은 됐지만 해당 방의 참여자가 아닌 사용자가
  - **When** 메시지 조회/전송/읽음 중 어느 API든 호출하면
  - **Then** `403` + `FORBIDDEN`으로 거절된다. **When** 존재하지 않는 `roomId`면 → `404` + `CHAT_ROOM_NOT_FOUND`. **When** 토큰 없음/만료면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`.
  - **Then** 어느 경우에도 메시지 내용이 노출되거나 저장되지 않는다.
- 경계·동시성(레이트리밋·읽음 멱등)
  - **Given** 방 참여자가 짧은 시간에 메시지를 도배하거나, 읽음 처리를 동시에/반복 호출할 때
  - **When** 메시지 전송이 레이트리밋을 초과하면 → `429` + `TOO_MANY_REQUESTS`. **When** 같은 `lastReadMessageId`로 `read`를 반복 호출하거나 현재보다 과거 ID를 보내면
  - **Then** 읽음 위치는 전진만 하며 중복 호출에도 결과가 동일(멱등)하고, 다른 방의 메시지 ID를 보내면 `422` + `CHAT_MESSAGE_NOT_IN_ROOM`으로 거절된다.

### US-4-6 — 임대인 받은 신청 조회(내 매물, 목록·단건 상세)

> **[1차 MVP] 매물 예약(신청) 스토리다** — 후속·이연(US-4-3~US-4-5, 문의·채팅)이 아니라 US-4-1·US-4-2와 함께 booking 독립 기능에 속한다. 예약 **생성**은 세입자 전용이지만 **조회**는 세입자(내 예약, US-4-2)와 임대인(내 매물에 신청된 예약, 본 스토리)으로 나뉜다.

**As a** 매물을 등록·소유한 `ACTIVE` 임대인(`userType=LANDLORD`)  
**I want** 내 소유 매물에 신청된 예약을 목록으로 보고 각 신청의 상세(신청자 정보·매물 정보·타겟 입주일·계약기간·보증금·총 금액)를 조회하기를  
**So that** 어떤 세입자가 어떤 방에 어떤 조건으로 신청했는지 확인하고 응대(수락/거절은 후속)를 준비한다.

- 우선순위: High
- 관련 NFR: 보안(내 소유 매물 신청만 조회 — 소유권 스코프), 개인정보(신청자 PII(이름·성별·국적·이메일)를 임대인에게 **마스킹 없이 평문 노출** — 제품 결정), 성능(목록 페이지네이션), 정합성(가격·신청자 정보 조회 시점 조인)
- 백엔드 관점: **별도 임대인 전용 API를 두지 않고** 조회 엔드포인트(`GET /api/v1/bookings`·`GET /api/v1/bookings/{bookingId}`)에서 요청자 `userType`으로 **분기**한다 — `LANDLORD`면 내 소유 매물에 신청된 예약을(`TENANT`면 내 예약, US-4-2). `userType`은 토큰 클레임이 아니라 `user :: api`(`getUserType`)로 서비스 계층에서 판정하며, **두 역할 모두 유효한 요청이라 역할 `403`은 없다**. 소유권은 예약 **생성 시** 매물 소유자(`listing.landlordId`)를 `Booking.landlordId`로 **비정규화 저장**해 두므로(생성은 이미 `listing :: api`로 매물을 조회 중이라 소유자 캡처 비용이 거의 없다), 임대인 **목록**은 booking 저장소에서 **`landlord_id = 요청자`** 단일 조건을 `createdAt` 내림차순 **오프셋 페이지네이션**(api-design-guide §4-1)으로 조회한다(cross-store 조인 없음, [ADR-0005](../adr/0005-polyglot-persistence.md); `chat_rooms` 비정규화 선례와 일치). `landlordId`는 매물 상태와 무관해 `PAUSED` 매물의 신청도 포함된다. **상세**는 예약을 조회한 뒤 **`booking.landlordId == 요청자`인지 행 단위로 확인**한다(listing::api 왕복 없음). 응답 조립 시 매물 요약·가격은 `listing :: api`로, 신청자 프로필(성명·성별·국적·이메일)은 `user :: api`(신규 `getApplicantProfile`)로 조회 시점에 실시간 조인한다(스냅샷 없음, 마스킹 없이 평문 노출). **총 금액**(`totalAmount`)은 세입자 분기와 **동일한 필드·정의**(`보증금 + 월세 × 개월수`, 관리비 제외)다. 신규 에러코드는 없고 기존 `BOOKING_NOT_FOUND`(404) + 공통 `AUTH_ONBOARDING_REQUIRED`를 재사용한다.

**AC (Given/When/Then)**

- 정상(목록 — 임대인 분기)
  - **Given** 소유 매물에 신청 3건을 보유한 `ACTIVE` 임대인이
  - **When** `GET /api/v1/bookings?page=0&size=20`을 호출하면(요청자 `userType=LANDLORD`)
  - **Then** `200 OK` + `data.content`가 `createdAt` 내림차순으로 정렬되고, 각 항목에 `bookingId`·매물 요약(`listingId`·제목·썸네일)·`roomOfferId`·`roomOfferName`·신청자 성명(`applicant.name`)·`moveInDate`·`contractPeriod`·`status`·`createdAt`가 포함되며 `data.page`에 오프셋 메타가 담긴다. **타 임대인 매물의 신청은 절대 포함되지 않는다.**
- 정상(단건 상세 — 임대인 분기)
  - **Given** `ACTIVE` 임대인이 자기 소유 매물에 신청된 예약 1건을 지목할 때
  - **When** `GET /api/v1/bookings/{bookingId}`를 호출하면(요청자 `userType=LANDLORD`)
  - **Then** `200 OK` + `data`에 **신청자 정보**(`applicant`: 이름·성별·국적(`country`·`countryName`)·이메일, 마스킹 없음)·**매물 정보**(제목·썸네일·주소·방 상품명)·**타겟 입주일**(`moveInDate`)·**계약기간**(`contractPeriod`)·**상태**(`status`)·**보증금**(`deposit`)·**총 금액**(`totalAmount`, 세입자와 동일 필드)이 포함된다.
- 정합성(실시간 가격·현재값)
  - **Given** 신청 이후 해당 방 상품의 가격(`pricing`)이 변경됐을 때
  - **When** 임대인이 상세를 다시 조회하면
  - **Then** 스냅샷이 아니라 **현재 가격 기준**으로 보증금·총 금액을 계산해 내려준다.
- 인증·분기·소유권 스코프
  - **Given** 토큰이 없거나 만료된 요청이면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`.
  - **Given** 온보딩 미완료(비 `ACTIVE`) 요청이면 → `403` + `AUTH_ONBOARDING_REQUIRED`.
  - **When** `userType=TENANT`(세입자)가 같은 `GET /api/v1/bookings`를 호출하면 → **내 예약**으로 분기된다(US-4-2, 역할 `403` 없음).
  - **When** 임대인이 존재하지 않는 예약이거나 **내 소유 매물의 신청이 아닌** `bookingId`로 상세를 조회하면 → `404` + `BOOKING_NOT_FOUND`(존재 여부를 노출하지 않도록 내 매물 신청이 아니면 404로 통일 — 세입자 분기의 '타인 예약→404'와 동일).
- 경계(빈 목록/일시중지 매물/삭제된 매물)
  - **Given** 소유 매물이 없거나 소유 매물에 신청이 하나도 없을 때 → 임대인 분기 `GET /api/v1/bookings`는 `200` + `content: []`, `page.totalElements: 0`.
  - **Given** 소유 매물이 `PAUSED`(일시중지) 상태일 때 → 그 매물에 신청된 예약도 목록·상세에 포함된다(`landlordId`가 매물 상태와 무관하게 booking 행에 저장돼 있으므로 `PUBLISHED` 한정이 아니다).
  - **Given** 신청된 방 상품이 이후 비공개/삭제됐을 때 → 상세 조회 시 예약 코어 내역(날짜·계약기간·상태)은 유지하되 매물 정보·가격 파트의 표기 정책은 **(확인 필요)**(US-4-2와 동일).

> 신설 의존: 조회 서비스가 `user :: api`(`getUserType`)로 `userType`을 판정해 세입자/임대인 동작을 분기한다(별도 임대인 API·역할 `403` 없음). 선행 작업 — ① 예약 **생성** 시 소유자 캡처를 위해 `listing :: api`의 매물 조회 뷰(`RoomOfferBookingView`)에 `landlordId` 추가 노출 + `Booking`에 `landlordId` 저장, ② `user :: api` 신청자 프로필 조회(`getApplicantProfile` — 성명·성별·국적·이메일), ③ booking 저장소의 `landlord_id` 컬럼 + `findByLandlordId`(페이지·카운트) + `(landlord_id, created_at)` 인덱스(신규 마이그레이션, database-design §4-5). 임대인 조회에 listing::api 소유권 조회 메서드는 불필요(소유권은 booking 행에서 판정). `booking → {listing::api, user::api}` 의존 화이트리스트는 이미 선언돼 있다. 신규 에러코드 없이 기존 `BOOKING_NOT_FOUND`(404)를 재사용한다. **임대인에게 신청자 이메일·성별·국적은 마스킹 없이 평문으로 노출한다(제품 결정).**

## 5. 커뮤니티 (게시판 · 동네친구)

> 관련 API 스펙: [05-community](../api/specs/05-community.md)

자유게시판(FREE)·동네생활(NEIGHBORHOOD) 게시판에서 외국인 사용자가 텍스트 게시글을 작성/조회하고, 좋아요·댓글·공유로 상호작용하며, 동네생활 게시글의 작성자와 1:1 채팅을 시작하는 기능이다. 게시글은 텍스트(제목+본문)만 다루며 사진·동영상은 범위 외다. 채팅 메시지 송수신 자체는 04(채팅) 스펙을 `NEIGHBOR` 카테고리로 재사용한다.

공통 전제(모든 스토리에 적용):

- 모든 응답은 공통 래퍼 `{ success, data, error }`를 따른다. 에러는 [error-response-guide](../api/error-response-guide.md)가 정본이다.
- 인증은 `Authorization: Bearer <accessToken>`(서버 자체 JWT). 토큰 만료 시 `401 TOKEN_EXPIRED`, 미인증 시 `401 UNAUTHENTICATED`.
- 차단/탈퇴 사용자 처리: 탈퇴 사용자의 게시글·댓글은 본문을 유지하되 작성자 표기를 익명 처리(닉네임 `(탈퇴한 사용자)`, 국적 `null`)한다. 차단 관계인 상대가 작성한 게시글·댓글은 목록·상세에서 제외한다. (차단 모델 상세는 report/신고 모듈에 의존 — 해당 모듈 확정 필요)
- 시각은 UTC ISO-8601, enum은 UPPER_SNAKE, 목록은 오프셋 페이지네이션(`page`/`size`/`sort`)을 사용한다. 정렬은 가이드 형식 `sort=field,(asc|desc)`를 따른다(api-design-guide §6).

### US-5-1 — 게시글 작성 · 수정 · 삭제

**As a** 인증된 외국인 사용자
**I want** 자유게시판/동네생활에 제목과 본문(텍스트)으로 게시글을 작성하고, 내가 쓴 글을 수정·삭제하기를
**So that** 정보를 공유하고 잘못된 내용은 내가 직접 바로잡거나 내릴 수 있다 (서버는 작성자 소유권을 강제하고, 게시판 종류·입력 길이·소프트 삭제 상태를 일관되게 관리한다)

- 우선순위: High
- 관련 NFR: 보안(소유권 검증·입력 검증), 유지보수성(소프트 삭제로 댓글·좋아요 정합성 유지) (NFR 문서 템플릿 상태 — 확정 필요)

**AC (Given/When/Then)**

- 시나리오: 정상 작성

  - Given 인증된 사용자가 `boardType=FREE`, 유효한 `title`·`content`로 요청하고
  - When `POST /api/v1/community/posts`를 호출하면
  - Then `201 Created`, `data`에 생성된 `postId`·`boardType`·`createdAt`이 반환되고 `Location: /api/v1/community/posts/{postId}` 헤더가 포함되며, 해당 글은 작성자의 내 게시글 목록에 즉시 포함된다.
- 시나리오: 입력 검증 실패

  - Given 인증된 사용자가 `title`을 공백으로 보내거나(또는 `content`가 길이 한도 초과, `boardType`이 enum에 없는 값)
  - When `POST /api/v1/community/posts`를 호출하면
  - Then `400`, `error.code = INVALID_INPUT`, `errors[]`에 위반 필드(`title`/`content`/`boardType`)와 사유가 포함된다.
- 시나리오: 인증·권한 (남의 글 수정/삭제)

  - Given 사용자 A가 작성한 게시글을 사용자 B(인증됨)가
  - When `PATCH /api/v1/community/posts/{postId}` 또는 `DELETE /api/v1/community/posts/{postId}`로 변경하려 하면
  - Then `403`, `error.code = FORBIDDEN`이 반환되고 게시글은 변경되지 않는다. (미인증이면 `401 UNAUTHENTICATED`)
- 시나리오: 경계·동시성 (이미 삭제된 글 / 동시 삭제)

  - Given 작성자가 이미 삭제한(소프트 삭제) 게시글에 대해
  - When 같은 작성자가 다시 `DELETE /api/v1/community/posts/{postId}`를 호출하거나, 존재하지 않는 `postId`로 `PATCH`를 호출하면
  - Then 존재하지 않거나 이미 삭제된 글은 `404`, `error.code = POST_NOT_FOUND`로 응답한다(권한 검증보다 대상 부재를 우선 판정해 소유권 노출을 피한다). 동시 삭제 시에도 좋아요/댓글 수 집계가 음수가 되지 않는다.

### US-5-2 — 게시글 목록 · 상세 · 검색 · 내 게시글

**As a** 사용자(목록·검색은 비로그인 선택 가능)
**I want** 게시판별 게시글 목록을 최신순/인기순으로 보고, 키워드(제목·본문)·해시태그로 검색하고, 상세와 내 게시글을 조회하기를
**So that** 원하는 정보를 빠르게 찾고 내가 쓴 글을 한곳에서 관리할 수 있다 (서버는 정렬·페이지네이션·검색 조건을 표준화하고 목록 항목에 집계 수치를 함께 내려준다)

- 우선순위: High
- 관련 NFR: 성능(목록·검색 p95 응답시간, 정렬·검색 인덱스), 확장성(오프셋 페이지네이션) (NFR 문서 템플릿 상태 — 확정 필요)

**AC (Given/When/Then)**

- 시나리오: 정상 목록 조회

  - Given 게시글이 존재하는 상태에서
  - When `GET /api/v1/community/posts?boardType=FREE&sort=createdAt,desc&page=0&size=20`을 호출하면
  - Then `200`, `data`는 오프셋 페이지 객체(`content[]` + `page`)이고, 각 항목에 `postId`·`title`·`authorNickname`·`authorNationality`·`createdAt`·`likeCount`·`commentCount`가 포함된다. 인기순은 `sort=likeCount,desc`로 요청하며 좋아요수 기준(동점 시 `createdAt,desc`)으로 정렬된다.
- 시나리오: 입력 검증 실패 (잘못된 정렬/페이지)

  - Given 사용자가 허용되지 않은 정렬 키(`sort=unknown,desc`) 또는 `size=500`(최대 100 초과), `page=-1` 등 허용되지 않은 파라미터를 보내면
  - When `GET /api/v1/community/posts`를 호출하면
  - Then `400`, `error.code = INVALID_INPUT`으로 응답한다(정의되지 않은 필터·정렬 값은 무시하지 않는다).
- 시나리오: 인증·권한 (내 게시글)

  - Given 미인증 사용자가
  - When `GET /api/v1/community/posts/me`(내 게시글)를 호출하면
  - Then `401`, `error.code = UNAUTHENTICATED`로 응답한다. 인증된 사용자가 호출하면 `200`으로 본인 작성 글만 반환한다.
- 시나리오: 경계 (검색 결과 0건 / 없는 상세)

  - Given 일치하는 게시글이 없는 키워드로 검색하거나, 존재하지 않는 `postId`로 상세를 조회하면
  - When `GET /api/v1/community/posts?keyword=...` 또는 `GET /api/v1/community/posts/{postId}`를 호출하면
  - Then 검색은 `200` + 빈 `content[]`(`totalElements=0`)로, 없는 상세는 `404` + `error.code = POST_NOT_FOUND`로 응답한다. 해시태그 검색(`hashtag=...`)은 `#` 없이 태그명만 받아 해당 태그가 달린 글을 반환한다.

### US-5-3 — 좋아요 토글 · 공유 카운트

**As a** 인증된 사용자
**I want** 게시글에 좋아요를 켜고 끄고, 공유 시 공유 수가 증가하기를
**So that** 호응을 표현하고 인기 글이 인기순 정렬에 반영된다 (서버는 사용자당 좋아요 1회를 보장하고 동시 토글에도 집계를 정확히 유지한다)

- 우선순위: Mid
- 관련 NFR: 신뢰성(멱등·동시성 안전한 카운트), 보안(공유 도배 방지를 위한 사용자 단위 레이트리밋) (NFR 문서 템플릿 상태 — 확정 필요)

**AC (Given/When/Then)**

- 시나리오: 정상 좋아요 토글

  - Given 인증된 사용자가 좋아요하지 않은 게시글에 대해
  - When `POST /api/v1/community/posts/{postId}/like`를 호출하면
  - Then `200`, `data = { "liked": true, "likeCount": <증가된 값> }`로 현재 상태를 반환한다. 다시 호출하면 `{ "liked": false, ... }`로 토글된다.
- 시나리오: 입력 검증 / 대상 없음

  - Given 존재하지 않는 `postId`로
  - When `POST /api/v1/community/posts/{postId}/like` 또는 `POST .../share`를 호출하면
  - Then `404`, `error.code = POST_NOT_FOUND`로 응답한다.
- 시나리오: 인증·권한

  - Given 미인증 사용자가
  - When `POST /api/v1/community/posts/{postId}/like` 또는 `POST .../share`를 호출하면
  - Then `401`, `error.code = UNAUTHENTICATED`로 응답한다(좋아요·공유 모두 인증 필수 — 사용자 단위 멱등·레이트리밋을 보장하기 위함).
- 시나리오: 경계·동시성 (중복 좋아요 / 동시 토글)

  - Given 같은 사용자가 좋아요를 짧은 간격으로 두 번 연속 요청하거나, 여러 요청이 동시에 토글하면
  - When `POST /api/v1/community/posts/{postId}/like`가 동시에 처리되어도
  - Then 사용자당 좋아요는 최대 1로 유지되고(유니크 제약), `likeCount`는 실제 좋아요한 사용자 수와 일치하며 음수가 되지 않는다. 공유 카운트(`POST .../share`)는 비멱등으로 호출마다 1 증가하되, 사용자 단위 레이트리밋 초과 시 `429 TOO_MANY_REQUESTS`로 응답한다.

### US-5-4 — 댓글 작성 · 삭제

**As a** 인증된 사용자
**I want** 게시글에 댓글을 달고 내가 쓴 댓글을 삭제하기를
**So that** 게시글에 의견을 남기고 관리할 수 있다 (서버는 댓글 소유권을 강제하고 게시글의 댓글 수 집계를 정합하게 유지한다)

- 우선순위: Mid
- 관련 NFR: 보안(소유권), 유지보수성(소프트 삭제·집계 정합성) (NFR 문서 템플릿 상태 — 확정 필요)

**AC (Given/When/Then)**

- 시나리오: 정상 댓글 작성

  - Given 인증된 사용자가 유효한 `content`로
  - When `POST /api/v1/community/posts/{postId}/comments`를 호출하면
  - Then `201 Created`, `data`에 `commentId`·`content`·`authorNickname`·`createdAt`이 반환되고 `Location: /api/v1/community/posts/{postId}/comments/{commentId}` 헤더가 포함되며 게시글의 `commentCount`가 1 증가한다.
- 시나리오: 입력 검증 실패

  - Given `content`가 공백이거나 길이 한도를 초과하면
  - When `POST /api/v1/community/posts/{postId}/comments`를 호출하면
  - Then `400`, `error.code = INVALID_INPUT`, `errors[]`에 `content` 사유가 포함된다.
- 시나리오: 인증·권한 (남의 댓글 삭제)

  - Given 사용자 A의 댓글을 사용자 B가
  - When `DELETE /api/v1/community/posts/{postId}/comments/{commentId}`로 삭제하려 하면
  - Then `403`, `error.code = FORBIDDEN`으로 응답하고 댓글은 유지된다. (미인증이면 `401 UNAUTHENTICATED`)
- 시나리오: 경계 (없는 게시글/댓글, 동시 삭제)

  - Given 존재하지 않거나 삭제된 게시글/댓글에 대해
  - When 댓글을 작성·삭제하면
  - Then 게시글 부재는 `404 POST_NOT_FOUND`, 댓글 부재는 `404 COMMENT_NOT_FOUND`로 응답한다. 동시 삭제 시에도 `commentCount`가 음수가 되지 않는다.

### US-5-5 — 동네친구 1:1 채팅 시작

**As a** 인증된 외국인 사용자
**I want** 동네생활 게시글(또는 그 작성자)에서 작성자와 1:1 채팅방을 시작하기를
**So that** 같은 동네 사람과 바로 대화를 이어갈 수 있다 (서버는 본인-본인 채팅을 막고, 같은 상대와의 중복 방 생성을 방지하며 04 채팅 스펙의 `NEIGHBOR` 카테고리로 연결한다)

- 우선순위: Mid
- 관련 NFR: 보안(차단/탈퇴 사용자 처리), 신뢰성(중복 방 멱등 생성) (NFR 문서 템플릿 상태 — 확정 필요)

**AC (Given/When/Then)**

- 시나리오: 정상 채팅 시작

  - Given 인증된 사용자 A가 사용자 B의 동네생활 게시글에서
  - When `POST /api/v1/community/posts/{postId}/chat`을 호출하면
  - Then `201 Created`, `data`에 `NEIGHBOR` 카테고리 채팅방 식별자(`chatRoomId`, 04 스펙 재사용)가 반환되고 `Location: /api/v1/chat-rooms/{chatRoomId}` 헤더가 포함되어 메시지 송수신이 가능해진다.
- 시나리오: 입력 검증 / 대상 없음

  - Given 존재하지 않는 `postId` 또는 탈퇴한 작성자의 게시글에 대해
  - When `POST /api/v1/community/posts/{postId}/chat`을 호출하면
  - Then 게시글 부재는 `404 POST_NOT_FOUND`로, 작성자가 탈퇴해 채팅 불가 상태이면 `422 POST_CHAT_AUTHOR_UNAVAILABLE`로 응답한다.
- 시나리오: 인증·권한 (본인에게 채팅 시작 불가)

  - Given 인증된 사용자가 자신이 작성한 게시글에서
  - When `POST /api/v1/community/posts/{postId}/chat`을 호출하면
  - Then `422`, `error.code = POST_CHAT_SELF_NOT_ALLOWED`로 응답하고 방이 생성되지 않는다. 미인증이면 `401 UNAUTHENTICATED`.
- 시나리오: 경계·동시성 (중복 방 / 차단 관계)

  - Given A가 B와 이미 동네친구 채팅방이 있거나 짧은 간격으로 두 번 시작 요청하면
  - When `POST /api/v1/community/posts/{postId}/chat`이 다시 호출되어도
  - Then 새 방을 만들지 않고 기존 `chatRoomId`를 `200 OK`로 반환한다(멱등). A가 B를 차단했거나 B가 A를 차단한 관계면 `403 POST_CHAT_BLOCKED`로 거부한다(차단 모델은 report 모듈에 의존 — 확정 필요).

## 6. 게이미피케이션 (퀴즈)

> 관련 API 스펙: [06-gamification](../api/specs/06-gamification.md)

외국인 세입자(임차인)가 한국 주거 관련 지식을 **요청할 때마다 무작위로 제공되는 4지선다 퀴즈**로 반복 학습하는 기능이다. 사용자는 문항·보기를 조회하고, 고른 보기를 제출하면 서버가 저장된 정답과 대조해 **정답 여부**를 즉시 돌려준다 — 정답·오답 모두 **해설**을 함께 반환하고, 오답이면 **정답 보기**를 추가로 반환한다. 정답 판정은 전적으로 서버가 수행하며(클라이언트 응답값 신뢰 금지), 채점은 **무상태(stateless)** 다 — 제출 기록·포인트 적립·하루 1회 제한·`(userId, quizDate)` 유니크 제약이 없고, 사용자는 횟수 제한 없이 반복해 풀 수 있다.

> **범위 변경(이전 모델 대체)**: 이전 범위의 "오늘의 퀴즈(하루 1개)"·포인트 적립(`QUIZ_CORRECT`)·`/points` 합계·내역 조회 모델은 본 범위에서 **랜덤·무상태·다국어 학습 퀴즈로 대체**된다. 따라서 포인트 관련 스토리·엔드포인트는 제외되고, `QUIZ_NOT_TODAY`·`QUIZ_ALREADY_SUBMITTED` 도메인 에러는 발생하지 않는다. 이 대체 모델은 API 스펙([06-gamification](../api/specs/06-gamification.md))·시퀀스 다이어그램(`sequence-diagrams/06-gamification/`)·도메인 모델·DB 설계·[ADR-0035](../adr/0035-gamification-quiz-random-stateless-catalog.md)에 반영 완료됐다("한 도메인 = 네 곳" 정합, [CLAUDE.md](../../CLAUDE.md)). 남은 후속은 스캐폴드 코드(`src/main/java/com/kohere/gamification/**`) 재구현이다.
>
> **다국어 번역이 기반**이다. 퀴즈 문항·보기·해설의 **표시 텍스트**는 사용자의 **표시 언어**로 번역해 반환한다 — 표시 언어는 `gamification`이 `user` 모듈 공개 query(`getLanguage`)를 호출해 취득하며 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**으로 정한다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141); `Accept-Language`·토큰 클레임에 의존하지 않음). 해당 언어 번역이 없으면 **영어(`en`)로 폴백**한다(에러 아님). 보기 **키(A~D)는 언어와 무관하게 불변**이며 표시 텍스트만 언어별이다(채점은 키로 검증). 번역 저장은 `diagnosis`와 동일하게 문항 도큐먼트 안 **인라인 언어-키 맵**으로 임베드하는 방식을 따른다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md), US-2-6와 동일 패턴).
>
> 인증 표기 기준: 본 기능은 **외국인 세입자(`userType=TENANT`)** 대상이다 — 조회·채점 모두 **온보딩을 완료한(`ACTIVE`) 세입자** 전용이다(정식 access 토큰 = `ROLE_USER`). **한국 주거 지식을 학습하려는 외국인 세입자를 위한 기능이라 임대인은 대상이 아니다** — 임대인도 `country`·`lang`(`'KR'`/`'ko'` 고정)을 갖게 되었으므로([ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정(#141)) "임대인은 등록 국가가 없어서"가 아니라 **대상 액터 정의**가 게이트의 근거다. 상태를 저장하지 않으므로 타인 리소스 접근 개념이 없고 인증만 강제한다. `/api/v1/quizzes/**`는 `hasRole("USER")`(ACTIVE)로 게이팅하고 응용 계층에서 `userType=TENANT`를 검사한다 — 비-ACTIVE는 `403 AUTH_ONBOARDING_REQUIRED`, 세입자가 아니면 `403 FORBIDDEN`으로 거부한다.

### US-6-1 — 랜덤 퀴즈 조회

**As a** 로그인한 외국인 세입자(온보딩 완료, `ACTIVE`·`userType=TENANT`)
**I want** 요청할 때마다 서버가 무작위로 고른 4지선다 퀴즈 1개를 내 언어로 번역된 문항·보기와 함께 받기
**So that** 매번 새로운 문제로 횟수 제한 없이 반복 학습할 수 있다 (정답·해설은 조회 응답에 싣지 않고 채점 요청에서만 공개한다)

- 우선순위: High
- 관련 NFR: 국제화(문항·보기 표시 언어는 `users.lang`이 있으면 그 값, 없으면 영어 폴백), 성능(자주 호출되는 조회, p95 응답시간 목표 — 확인 필요), 보안(정답/해설은 조회 응답에 미포함)

**AC (Given/When/Then)**

- 시나리오: 정상 — 랜덤 퀴즈 조회

  - Given `ACTIVE` 세입자(`userType=TENANT`)가 있고, 퀴즈 콘텐츠 풀에 사용 가능한 퀴즈가 1개 이상 있다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `200 OK` 와 함께 `quizId`, `question`, `choices`(`key` A~D + `text`, 4개)를 사용자 언어로 번역해 받고, 응답에 `correctChoice`/`explanation`은 포함되지 않는다
- 시나리오: 반복 조회 — 매 호출 무작위 제공

  - Given 동일 세입자가 방금 한 문제를 조회·채점했다
  - When `GET /api/v1/quizzes/random`을 다시 호출한다
  - Then `200 OK` 와 함께 새 퀴즈를 받는다(제출 상태를 저장하지 않으므로 횟수 제한·`409` 차단이 없다)
- 시나리오: 인증 누락

  - Given Authorization 헤더가 없거나 토큰이 위조/만료되었다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `401 Unauthorized` 와 `error.code=UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)를 받는다
- 시나리오: 권한 — 온보딩 미완료(비-`ACTIVE`) 접근

  - Given 온보딩을 마치지 않은(`PENDING`/`TERMS_AGREED`) 토큰으로 접근한다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `403 Forbidden` 와 `error.code=AUTH_ONBOARDING_REQUIRED`를 받는다(정식 인증=`ROLE_USER` 필요)
- 시나리오: 경계 — 사용 가능한 퀴즈가 없음

  - Given 인증은 정상이나 퀴즈 콘텐츠 풀이 비어 있다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `404 Not Found` 와 `error.code=QUIZ_NOT_FOUND`를 받는다

### US-6-2 — 퀴즈 정답 제출 및 즉시 피드백

**As a** 로그인한 외국인 세입자(온보딩 완료, `ACTIVE`·`userType=TENANT`)
**I want** 내가 고른 보기(A~D)를 제출해 서버가 채점한 정답 여부와 해설을 즉시 받고, 오답이면 정답 보기도 함께 받기
**So that** 바로 학습 피드백을 얻는다 (정답 판정은 서버가 저장된 정답과 대조해 수행하며 클라가 보낸 정답 여부는 신뢰하지 않는다; 제출 기록·포인트 적립을 남기지 않는 무상태 채점이다)

- 우선순위: High
- 관련 NFR: 보안(정답 서버 판정, 정답 보기·해설은 채점 응답에서만 공개), 국제화(해설은 정답·오답 모두 사용자 언어로 번역, 영어 폴백), 신뢰성(무상태 채점 — 반복 호출에도 부작용 없음)

**AC (Given/When/Then)**

- 시나리오: 정상 채점 — 정답 (해설 반환)

  - Given `ACTIVE` 세입자(`userType=TENANT`)가 조회한 퀴즈에서 정답 보기를 골랐다
  - When `POST /api/v1/quizzes/{quizId}/answer`에 `{ "selectedChoice": "B" }`를 보낸다
  - Then `200 OK` 와 함께 `correct=true`와 `explanation`(해설, 사용자 언어로 번역)을 받고, `correctChoice`는 포함되지 않으며 적립·기록 등 부작용이 없다
- 시나리오: 정상 채점 — 오답 (정답 보기·해설 반환)

  - Given `ACTIVE` 세입자(`userType=TENANT`)가 오답 보기를 골랐다
  - When `POST /api/v1/quizzes/{quizId}/answer`에 `{ "selectedChoice": "A" }`를 보낸다
  - Then `200 OK` 와 함께 `correct=false`, `correctChoice`(정답 보기 키), `explanation`(해설, 사용자 언어로 번역)을 받고, 부작용이 없다
- 시나리오: 입력 검증 실패 — 허용되지 않은 보기 값

  - Given 세입자가 `selectedChoice`에 `E`(또는 빈 값/누락)를 보낸다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `400 Bad Request` 와 `error.code=INVALID_INPUT`, `errors[]`에 `selectedChoice` 필드 사유를 받는다
- 시나리오: 입력 검증 실패 — JSON 파싱 불가

  - Given 세입자가 본문을 깨진 JSON으로 보낸다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `400 Bad Request` 와 `error.code=MALFORMED_REQUEST`를 받는다
- 시나리오: 인증 누락

  - Given Authorization 헤더가 없거나 토큰이 만료/위조되었다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `401 Unauthorized` 와 `error.code=UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)를 받는다
- 시나리오: 경계 — 존재하지 않는 퀴즈 채점

  - Given 경로의 `{quizId}`가 존재하지 않는다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `404 Not Found` 와 `error.code=QUIZ_NOT_FOUND`를 받는다
- 시나리오: 반복 채점 — 부작용 없음(무상태)

  - Given 세입자가 같은 퀴즈를 여러 번 채점 요청한다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 반복 호출한다
  - Then 매번 채점 결과만 반환하고 제출 기록·포인트 적립 등 상태 변경이 없다(하루 1회 제한·`409 QUIZ_ALREADY_SUBMITTED` 없음)

### US-6-3 — 사용자 표시 언어 기반 퀴즈 문항·해설 번역 제공

**As a** 한국어가 익숙하지 않은 외국인 세입자(`ACTIVE`·`userType=TENANT`)
**I want** 퀴즈 문항·보기·해설을 내 표시 언어에 맞게 번역된 텍스트로 받기
**So that** 모국어 또는 영어로 문제를 이해하고 정확히 답할 수 있다 (번역 기준은 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**이며 `user` 공개 query `getLanguage`로 취득([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)), 미지원 언어는 영어로 폴백, 보기 키 A~D는 언어와 무관하게 불변)

- 우선순위: High
- 관련 NFR: 국제화(i18n), 일관성(번역 누락 시 영어 폴백), 보안(본인 표시 언어·국가 정보 기반 — 사용자 선택값 또는 온보딩 수집값)

**AC (Given/When/Then)**

- 시나리오: 정상 — 표시 언어에 맞는 번역 제공

  - Given 표시 언어가 일본어(`ja`)인 세입자(`lang="ja"`를 직접 고른 경우)가 퀴즈를 조회한다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then 문항·보기 표시 텍스트가 해당 언어로 번역되어 반환되고, 보기 키(A~D)는 언어와 무관하게 동일하다
- 시나리오: 폴백 — 미지원 언어

  - Given 번역이 준비되지 않은 국가/언어의 세입자다
  - When 퀴즈를 조회하거나 채점을 요청한다
  - Then 기본 언어(영어)로 폴백해 반환한다(에러 아님)
- 시나리오: 채점 해설도 번역 제공 (정답·오답 공통)

  - Given 표시 언어가 `ja`인 세입자가 정답 또는 오답을 제출한다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then 정답·오답 모두 `explanation`(해설)이 사용자 언어로 번역되어 반환되고(번역이 없으면 영어 폴백), 오답이면 `correctChoice`가 함께 반환된다
- 시나리오: 키 불변 — 번역과 무관한 채점

  - Given 번역된 라벨로 표시된 보기를 골라 그 키(A~D)로 제출한다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then 언어와 무관하게 동일 키로 정상 채점된다

## 7. 신고 처리

> 관련 API 스펙: [07-reports](../api/specs/07-reports.md)

외국인 사용자가 커뮤니티 게시글(POST)·댓글(COMMENT)·채팅 메시지(MESSAGE)에서 마주치는 스팸·욕설·성적 콘텐츠·외부 연락 유도·허위 정보 등을 신고로 접수·저장하는 기능. MVP 범위는 **신고 접수/저장**과 **신고 사유 메타 조회**까지이며, 운영자의 검토·제재 흐름(상태 전이, 조치)은 (확인 필요) 상태로 본 섹션에서 설계하지 않는다. 모든 응답은 공통 래퍼 `{ success, data, error }`를 따르고, 에러는 [error-response-guide](../api/error-response-guide.md)를 정본으로 한다.

신고 데이터 모델(백엔드 관점):

- `reporterId`(신고자, JWT subject), `targetType`(`POST`/`COMMENT`/`MESSAGE`), `targetId`(Long), `reason`(enum), `detail`(선택, 자유 텍스트), `status`(접수 시 `RECEIVED` 고정 — 후속 운영 흐름은 (확인 필요)), `createdAt`(UTC).
- 고유성 제약: `(reporterId, targetType, targetId)` 유니크 — 동일 사용자가 동일 대상을 중복 신고하지 못한다.
- 권한 모델: 게시글·댓글은 로그인 사용자라면 신고할 수 있으나, **채팅 메시지(`MESSAGE`)는 해당 채팅방 참여자만 신고**할 수 있다(타인 대화 열람 방지, [04-booking-inquiry-chat](../api/specs/04-booking-inquiry-chat.md)의 `403 FORBIDDEN` 규약과 일치). 본인이 작성한 콘텐츠 신고(자기 신고) 차단 여부는 (확인 필요).

### US-7-1 — 콘텐츠 신고 접수

**As a** 로그인한 외국인 사용자
**I want** 게시글·댓글·채팅 메시지를 사유와 함께 신고해 서버에 접수·저장하고
**So that** 부적절한 콘텐츠가 운영 검토 대상으로 기록되어 안전한 커뮤니티가 유지된다.
(백엔드/데이터 관점: 신고 1건을 `reports` 테이블에 영속화하고, 신고자·대상·사유를 검증하며, `status=RECEIVED`로 저장한다.)

- 메타: 우선순위 **High** / 관련 NFR: 보안(인증 필수·민감정보 비로깅), 신뢰성(중복 방지를 위한 DB 유니크 제약), 관측성(접수 건 추적)

**AC (Given/When/Then)**

```text
시나리오 1 (정상): 유효한 신고 접수
Given 인증된 사용자가 존재하는 게시글(targetType=POST, targetId=101)을 대상으로 하고
When  POST /api/v1/reports 로 { targetType:"POST", targetId:101, reason:"SPAM", detail:"광고 도배" } 를 보내면
Then  201 Created 와 함께 data 에 { reportId, targetType, targetId, reason, status:"RECEIVED", createdAt }(UTC ISO-8601)이 공통 래퍼로 반환된다.
      (단건 조회 엔드포인트는 MVP 미정의이므로 Location 헤더는 단건 조회 도입 시에만 부여한다 — (확인 필요))
```

```text
시나리오 2 (입력 검증 실패): 필수값 누락 / 잘못된 enum
Given 인증된 사용자가
When  POST /api/v1/reports 로 targetId 누락 또는 reason:"FOO"(미정의 enum) 또는 targetType 누락으로 보내면
Then  400 Bad Request, error.code="INVALID_INPUT" 이며 error.errors[] 에 field/reason 별 상세가 포함된다.
      (detail 은 선택이나 최대 길이 초과 시 동일하게 INVALID_INPUT. JSON 자체가 깨졌으면 400 MALFORMED_REQUEST)
```

```text
시나리오 3 (인증): 미인증 요청
Given Authorization 헤더가 없거나 토큰이 만료된 사용자가
When  POST /api/v1/reports 를 호출하면
Then  토큰 부재/위조는 401 error.code="UNAUTHENTICATED", 액세스 토큰 만료는 401 error.code="TOKEN_EXPIRED" 가 반환된다(재발급 유도).
```

```text
시나리오 4 (권한): 참여하지 않은 채팅방의 메시지 신고
Given 인증된 사용자가 자신이 참여하지 않은 채팅방의 메시지(targetType=MESSAGE, targetId=...)를 대상으로
When  POST /api/v1/reports 를 호출하면
Then  403 Forbidden, error.code="FORBIDDEN" 이 반환된다(타인 대화 열람·신고 차단). 참여자 본인이면 정상 접수된다.
```

```text
시나리오 5 (권한): 자기 콘텐츠 신고 (확인 필요)
Given 인증된 사용자가 본인이 작성한 게시글/댓글/메시지를 대상으로
When  POST /api/v1/reports 를 호출하면
Then  (정책: 자기 신고 차단 시) 422 Unprocessable Entity, error.code="REPORT_SELF_TARGET" 이 반환된다.
      차단하지 않는 정책이라면 정상 접수된다 — 차단 여부는 (확인 필요).
```

```text
시나리오 6 (경계·동시성): 중복 신고 차단
Given 동일 사용자가 동일 대상(targetType=POST, targetId=101)을 이미 한 번 신고한 상태에서
When  같은 대상으로 다시 POST /api/v1/reports 를 보내면(동시 2건이 거의 동시에 도착해도)
Then  409 Conflict, error.code="REPORT_ALREADY_EXISTS" 가 반환되고, DB 유니크 제약으로 두 번째 INSERT 는 거부되어 신고는 1건만 존재한다.
```

```text
시나리오 7 (경계): 존재하지 않는 대상 신고
Given 인증된 사용자가
When  존재하지 않는 targetId 로 POST /api/v1/reports 를 보내면
Then  404 Not Found, error.code="REPORT_TARGET_NOT_FOUND" 가 반환된다.
      (참고: 대상 존재 검증을 비동기/사후 검증으로 미루는 정책은 (확인 필요) — 본 스펙은 접수 시 동기 검증을 기본으로 한다.)
```

> 검증·권한 우선순위: 인증(401) → (필요 시) 레이트리밋(429, US-7-2) → 입력 검증(400) → 대상 존재(404) → 참여 권한·자기 신고(403/422) → 중복(409).

### US-7-2 — 신고 도배(레이트리밋) 방어

**As a** 서비스 운영 주체(시스템)
**I want** 한 사용자가 단시간에 과도하게 신고를 생성하지 못하도록 호출 한도를 적용하고
**So that** 신고 기능을 악용한 도배·괴롭힘으로부터 시스템과 다른 사용자를 보호한다.
(백엔드 관점: `reporterId` 기준 윈도우 카운팅으로 한도 초과 시 접수를 거부한다.)

- 메타: 우선순위 **Mid** / 관련 NFR: 보안·신뢰성(레이트리밋), 관측성(429 `WARN` 로깅 및 `Retry-After`)

**AC (Given/When/Then)**

```text
시나리오 1 (정상): 한도 내 신고
Given 직전 윈도우에서 한도 미만으로 신고한 사용자가
When  서로 다른 대상에 대해 정상 신고를 보내면
Then  각 요청은 201 Created 로 정상 접수된다.
```

```text
시나리오 2 (경계): 한도 초과
Given 동일 사용자가 정해진 시간 윈도우 안에 신고 한도(임계값은 (확인 필요): 예 분당 N건)를 모두 소진한 상태에서
When  추가로 POST /api/v1/reports 를 호출하면
Then  429 Too Many Requests, error.code="TOO_MANY_REQUESTS"(공통) 가 반환되고 Retry-After 헤더가 포함된다.
```

```text
시나리오 3 (우선순위: 레이트리밋 vs 입력 검증)
Given 한도를 초과한 사용자가
When  동시에 입력 검증도 실패하는(잘못된 enum) 요청을 보내면
Then  레이트리밋을 먼저 적용해 429 TOO_MANY_REQUESTS 를 반환한다(과도 호출 자체를 차단). 한도 내라면 400 INVALID_INPUT 으로 처리된다.
```

```text
시나리오 4 (인증 선행)
Given 미인증 사용자가
When  대량 신고를 시도하면
Then  레이트리밋 평가 이전에 401 UNAUTHENTICATED 로 거부된다(인증이 선행).
```

### US-7-3 — 신고 사유 목록(enum 메타) 조회

**As a** 클라이언트(모바일 앱)
**I want** 서버가 정의한 신고 사유 enum 목록을 메타로 조회하고
**So that** 신고 화면의 사유 선택지를 서버와 동일하게 유지하고, 신규 사유 추가 시 클라이언트 하드코딩 없이 반영한다.
(백엔드 관점: enum 카탈로그를 단일 출처로 노출해 클라이언트-서버 불일치를 방지한다.)

- 메타: 우선순위 **Mid** / 관련 NFR: 유지보수성(enum 단일 출처), 관측성(불필요)

**AC (Given/When/Then)**

```text
시나리오 1 (정상): 사유 목록 조회
Given 임의의 클라이언트가(인증 불필요)
When  GET /api/v1/reports/reasons 를 호출하면
Then  200 OK 와 함께 data.reasons[] 에 { code(UPPER_SNAKE), label } 목록(SPAM/ABUSE/SEXUAL_CONTENT/EXTERNAL_CONTACT/FALSE_INFO/ETC)이 공통 래퍼로 반환된다.
```

```text
시나리오 2 (경계): 페이지네이션 불필요한 소형 메타
Given 사유 개수가 고정·소규모임을 전제로
When  GET /api/v1/reports/reasons 를 호출하면
Then  페이지 객체 없이 전체 배열을 한 번에 반환한다(목록 규약 api-design-guide §4 미적용, 비페이지 메타임을 스펙에 명시).
```

```text
시나리오 3 (인증·권한): 인증 토큰 유무 무관
Given 인증 토큰이 있거나 없는 클라이언트 모두
When  GET /api/v1/reports/reasons 를 호출하면
Then  동일하게 200 OK 를 반환한다(인증 불필요).
```

```text
시나리오 4 (메서드): 미허용 메서드
Given 클라이언트가
When  /api/v1/reports/reasons 에 POST 등 미허용 메서드로 요청하면
Then  405 Method Not Allowed, error.code="METHOD_NOT_ALLOWED" 가 반환된다.
```

## 8. 생활 팁 (주제별 생활 정보)

> 관련 API 스펙: [08-life-tips](../api/specs/08-life-tips.md)

온보딩을 마친 세입자(외국인)가 한국 생활에 필요한 정보를 **주제(topic)** 별로 묶어 조회하는 읽기 전용 큐레이션 기능이다. 홈 화면 진입점([project-brief §4](../project/project-brief.md))에서 시작하며, 사용자는 먼저 주제 목록을 보고(US-8-1), 특정 주제를 고르면 그 주제에 속한 생활 팁(**제목 · 내용 · 사진**) 전체 리스트를 받는다(US-8-2). 한 주제에는 여러 개의 제목-내용-사진 항목이 들어갈 수 있다(주제 : 팁 = **1 : N**). 콘텐츠는 운영이 시드로 적재하는 큐레이션 콘텐츠이며 사용자 작성·수정·좋아요·신고가 없다(UGC인 커뮤니티(5절)와 구분된다).

**번역이 이 기능의 바탕이다** — 주제명·주제 설명(짧은·긴)·제목·내용 표시 텍스트는 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**으로 정한 언어로 번역해 내려주며(US-8-3), 진단 i18n과 **완전히 동일한 전략**을 재사용한다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141), US-2-6): 표시 문자열을 도큐먼트 안 **인라인 언어-키 맵**(`{ "en": …, "ja": …, "ko": … }`)으로 임베드하고, 서버가 `user` 모듈 공개 query `getLanguage(userId)`로 취득한 언어 키로 문자열을 골라 조립하며, 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). `Accept-Language` 헤더·토큰 클레임은 쓰지 않는다. 주제·팁의 식별자(`code`/`id`)와 이미지 URL(주제의 카드 이미지 `LifeTipTopic.imageUrl`·배경 이미지 `backgroundImageUrl`, 팁의 사진 `LifeTip.imageUrl`)은 언어 무관 불변이고, 표시 텍스트(주제명·주제 설명·제목·내용)만 언어별이다.

> **인증·상태 게이트 기준**: 대상 액터는 **ACTIVE 상태(온보딩 완료)의 세입자**이고, 모든 조회는 **정식 인증(ROLE_USER)** 을 요구한다. **한국 생활 정보가 필요한 외국인 세입자를 위한 기능이라 임대인은 대상이 아니며**, 온보딩 미완료 사용자는 표시 언어를 정할 프로필 자체가 확정되지 않아 대상이 아니다 — 임대인도 `country`·`lang`(`'KR'`/`'ko'` 고정)을 갖게 되었으므로([ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정(#141)) "임대인은 등록 국가가 없어서"가 아니라 **대상 액터 정의**가 게이트의 근거다. 온보딩 미완료(PENDING/TERMS_AGREED, ROLE_ONBOARDING) 토큰은 `403 AUTH_ONBOARDING_REQUIRED`, 인증 누락/만료는 `401 UNAUTHENTICATED`/`TOKEN_EXPIRED`다(진단 보호 엔드포인트와 동일 게이트). 구현 시 [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)의 정식 인증(ROLE_USER) 티어에 `/api/v1/life-tips/**`를 등록한다 — 기본 `anyRequest().authenticated()`는 온보딩 스코프 토큰도 통과시켜 ACTIVE 게이트가 아니기 때문이다.
>
> **註 — 세입자 게이트 미구현(기존 결함, #141에서 수정)**: 현재 코드에는 생활 팁의 `userType=TENANT` 검사가 **없어 임대인도 조회를 통과한다**(퀴즈(6절)에는 `assertTenant` 게이트가 이미 있다). 이 문서가 정본이며, 응용 계층에 TENANT 게이트를 **신설**해 코드가 문서를 따라가게 한다. 반면 **진단(2절)은 세입자·임대인 모두 이용 가능**해 게이트를 두지 않는다(임대인은 진단을 `ko`로 본다).

> **저장소**: 문서형·가변 스키마·언어-키 맵 임베드 특성상 **MongoDB**에 둔다([ADR-0005](../adr/0005-polyglot-persistence.md) 폴리글랏, [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md) 진단 카탈로그 저장 방식과 정합). 모듈 경계(`lifetip` 신설 여부)·저장소·MVP 편입 시점 확정은 이슈 #79에서 다룬다.

### US-8-1 — 생활 팁 주제 목록 조회

**As a** 온보딩을 마친(ACTIVE) 세입자(외국인) 사용자
**I want** 생활 팁이 어떤 주제로 나뉘어 있는지 주제 목록을 내 언어로 조회하되, 각 주제의 이름·짧은 설명·긴 설명과 카드 이미지·배경 이미지까지 한 응답에 함께 받고
**So that** 홈 화면에서 주제 카드(이미지 + 짧은 설명)를 훑어보고, 관심 있는 주제를 골라 상세 상단(배경 이미지 + 긴 설명)과 관련 생활 정보로 들어갈 수 있다

- **우선순위**: Mid (홈 진입 콘텐츠, 보호 핵심(진단·추천) 아님)
- **관련 NFR**: 국제화(i18n — `users.lang`이 있으면 그 값, 없으면 `en` 폴백), 성능(소규모 고정 카탈로그 조회), 유지보수성(주제 카탈로그 단일 출처)
- **백엔드 관점**: 주제(`LifeTipTopic`)는 운영이 적재한 큐레이션 카탈로그다. 각 주제는 언어 무관 식별 `code`(UPPER_SNAKE)와 노출 순서(`order`)를 가지며, 표시명(`name`)·짧은 설명(`shortDescription`)·긴 설명(`longDescription`)은 언어-키 맵으로 임베드되고, 카드 이미지(`LifeTipTopic.imageUrl`)·배경 이미지(`backgroundImageUrl`)는 언어 무관 절대 CDN URL이다. 서버는 `user`의 `getLanguage(userId)`로 표시 언어를 정하고 그 언어 키(없으면 `en`)로 `name`·`shortDescription`·`longDescription`을 채우며 두 이미지 URL은 그대로 실어, 노출 순서대로 주제당 6필드(`code`·`name`·`shortDescription`·`longDescription`·`imageUrl`·`backgroundImageUrl`)를 한 응답에 반환한다. 설명 2종·이미지 2종은 **모두 필수(값 없는 주제 없음)** 라 홈 카드·상세 상단이 항상 이미지·설명을 그린다(팁의 `LifeTip.imageUrl`은 '사진 없는 팁'이 있어 nullable이지만 주제의 이 4필드는 이와 구분된다). `order` 자체는 응답에 노출하지 않는다. 주제 수는 고정·소규모라 페이지네이션 없이 전체 배열을 한 번에 반환한다(비페이지 메타 — api-design-guide §4 목록 규약 미적용, US-7-3과 동일 성격). 앱은 목록에서 받은 주제 객체를 상세 화면까지 그대로 들고 가므로(주제는 소규모 고정, 과다 전송 부담 없음) 6필드가 이 한 응답에 함께 실린다. `code`는 US-8-2에서 특정 주제의 팁을 지정하는 path 키로 쓰인다.

**AC (Given / When / Then)**

- 시나리오: 정상 — 주제 목록을 내 언어로 조회

  - **Given** 표시 언어가 일본어(`ja`)인 ACTIVE 세입자가 유효한 access token(ROLE_USER)을 보유한다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** `200 OK`와 함께 `topics[]`를 공통 래퍼로 반환한다 — 각 주제에 `code`(UPPER_SNAKE), 일본어로 번역된 `name`·`shortDescription`·`longDescription`, 언어 무관 절대 CDN URL `imageUrl`·`backgroundImageUrl`가 실려 **6필드가 한 응답에 함께** 담기며, `order` 오름차순 노출 순서대로·페이지 객체 없이 전체 배열을 한 번에 준다
- 시나리오: 화면 구성 — 홈 카드·상세 상단

  - **Given** 등록 국가가 일본인 ACTIVE 세입자가 위 주제 목록을 받았다
  - **When** 홈 화면에서 주제 카드를 그리고, 한 주제를 골라 주제 상세 화면으로 들어간다
  - **Then** 홈 카드는 목록에서 받은 `imageUrl`(카드 이미지)과 `shortDescription`(짧은 설명)으로, 주제 상세 상단은 같은 주제 객체의 `backgroundImageUrl`(배경 이미지)과 `longDescription`(긴 설명)으로 구성된다(앱이 목록 응답의 주제 객체를 상세 화면까지 그대로 들고 가 추가 조회가 없다)
- 시나리오: 필수 — 값 없는 주제 없음

  - **Given** 주제 카탈로그의 모든 주제가 4개 신규 필드를 갖춰 적재되어 있다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** 반환된 모든 주제에서 `shortDescription`·`longDescription`·`imageUrl`·`backgroundImageUrl`이 값으로 채워져 온다(4필드 모두 필수 — '이미지·설명 없는 주제' 경계 케이스는 존재하지 않는다. 팁의 `LifeTip.imageUrl`이 nullable인 것과 구분된다)
- 시나리오: 폴백 — 미지원 언어

  - **Given** 번역이 준비되지 않은 국가/언어의 ACTIVE 세입자다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** 주제 표시명(`name`)과 짧은/긴 설명(`shortDescription`·`longDescription`)이 모두 영어(`en`)로 폴백되어 `200 OK`로 반환된다(에러 아님, US-8-3과 동일 전략), `code`와 이미지 2종(`imageUrl`·`backgroundImageUrl`)은 언어와 무관하게 동일하다
- 시나리오: 상태 게이트 — 온보딩 미완료

  - **Given** PENDING/TERMS_AGREED 상태(ROLE_ONBOARDING 토큰)의 사용자다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** `403 Forbidden`과 `error.code=AUTH_ONBOARDING_REQUIRED`를 받는다(정식 인증 ROLE_USER=ACTIVE만 허용)
- 시나리오: 인증 누락

  - **Given** Authorization 헤더가 없거나 토큰이 만료/위조되었다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** `401 Unauthorized`와 `error.code=UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)를 받는다

### US-8-2 — 특정 주제의 생활 팁(제목·내용·사진) 목록 조회

**As a** 특정 주제의 생활 정보를 보려는 ACTIVE 세입자(외국인) 사용자
**I want** 고른 주제에 속한 생활 팁(제목·내용·사진) 전체를 내 언어로 한 번에 받고
**So that** 그 주제의 정보를 앱을 새로 배포하지 않고도 최신 큐레이션으로 읽을 수 있다

- **우선순위**: Mid
- **관련 NFR**: 국제화(i18n — 제목·내용 번역·`en` 폴백), 일관성(주제-팁 참조 무결성), 성능(주제당 팁 수가 제한적이라 전체 반환)
- **백엔드 관점**: 생활 팁(`LifeTip`)은 하나의 주제(`topicCode`)에 속하며(주제 : 팁 = 1 : N), `title`·`content`는 언어-키 맵으로 임베드되고 `imageUrl`은 언어 무관(사진)이다. 클라이언트가 주제 `code`를 path로 지정해 `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출하면, 서버가 그 주제의 팁 전체를 노출 순서(`order`)대로 조립해 반환한다 — 각 팁의 `title`·`content`는 `getLanguage(userId)`로 정한 언어 키(없으면 `en`)로 채우고 `imageUrl`은 그대로 싣는다. 주제당 팁 수가 제한적이므로 페이지네이션 없이 전체 리스트를 한 번에 반환한다("해당 주제에 맞는 제목-내용-사진의 모든 리스트"). 존재하지 않는 주제 `code`는 `404 LIFE_TIP_TOPIC_NOT_FOUND`(신규 도메인 에러코드 — `ErrorCode` 등록 필요, `*_NOT_FOUND` 규약). 사진이 없는 팁은 `imageUrl`을 `null`(또는 생략)로 둔다.

**AC (Given / When / Then)**

- 시나리오: 정상 — 주제별 팁 전체 조회

  - **Given** 표시 언어가 일본어(`ja`)인 ACTIVE 세입자와, 팁 3건이 속한 주제(`code=MOVING_IN`)가 있다
  - **When** `GET /api/v1/life-tips/topics/MOVING_IN/tips`를 호출한다
  - **Then** `200 OK`와 함께 `tips[]`(각 `id`, 일본어로 번역된 `title`·`content`, `imageUrl`)가 노출 순서대로 3건 모두 반환되며, 페이지 객체 없이 전체 배열을 한 번에 준다
- 시나리오: 폴백 — 미지원 언어

  - **Given** 번역이 준비되지 않은 국가/언어의 ACTIVE 세입자가 위 주제를 조회한다
  - **When** `GET /api/v1/life-tips/topics/MOVING_IN/tips`를 호출한다
  - **Then** 각 팁의 `title`·`content`가 영어(`en`)로 폴백되어 `200 OK`로 반환된다(에러 아님), `imageUrl`은 언어와 무관하게 동일하다
- 시나리오: 경계 — 사진 없는 팁

  - **Given** 조회한 주제에 사진이 없는 팁이 포함되어 있다
  - **When** `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** 해당 팁의 `imageUrl`이 `null`(또는 생략)로 반환되고 나머지 필드(`title`·`content`)는 정상 노출된다
- 시나리오: 경계 — 존재하지 않는 주제

  - **Given** 경로의 `{topicCode}`가 카탈로그에 없다
  - **When** `GET /api/v1/life-tips/topics/UNKNOWN_TOPIC/tips`를 호출한다
  - **Then** `404 Not Found`와 `error.code=LIFE_TIP_TOPIC_NOT_FOUND`를 받는다
- 시나리오: 상태 게이트 — 온보딩 미완료

  - **Given** PENDING/TERMS_AGREED 상태(ROLE_ONBOARDING 토큰)의 사용자다
  - **When** `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** `403 Forbidden`과 `error.code=AUTH_ONBOARDING_REQUIRED`를 받는다
- 시나리오: 인증 누락

  - **Given** Authorization 헤더가 없거나 토큰이 만료/위조되었다
  - **When** `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** `401 Unauthorized`와 `error.code=UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)를 받는다

### US-8-3 — 사용자 표시 언어 기반 생활 팁 번역 제공

**As a** 한국어가 익숙하지 않은 ACTIVE 세입자(외국인) 사용자
**I want** 주제명·제목·내용을 내 표시 언어에 맞게 번역된 텍스트로 받고
**So that** 모국어 또는 영어로 생활 정보를 이해할 수 있다

- **우선순위**: High (외국인 대상 서비스의 핵심 접근성 — 이 기능의 바탕)
- **관련 NFR**: 국제화(i18n), 일관성(번역 누락 시 `en` 폴백), 보안(본인 표시 언어 기반 — 사용자 선택값)
- **백엔드 관점**: 번역 전략은 진단 i18n([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md), US-2-6)과 **동일**하며 별도 메커니즘을 만들지 않는다. 번역 기준은 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**이고([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)), 표시 언어는 `user` 모듈 공개 query `getLanguage(userId)`를 **동기 호출**해 취득한다(도출 규칙은 `user`가 캡슐화하며 query 시그니처는 불변; `Accept-Language`·토큰 클레임 미사용; [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5 — 모듈 의존 `lifetip → user` 추가). 번역 텍스트는 별도 메시지 컬렉션·키 없이 주제·팁 도큐먼트 안 **인라인 언어-키 맵**으로 임베드한다 — 주제는 `name: { "en": …, "ja": …, "ko": … }`, 팁은 `title`/`content` 각각 언어-키 맵. 서버는 사용자 언어 키로 문자열을 고르고 그 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). 주제·팁 식별자(`code`/`id`)와 `imageUrl`(사진)은 언어 무관 불변이고 표시 텍스트만 언어별이다. US-8-1·US-8-2와 동일 엔드포인트에서 처리하며 응답 스키마는 언어와 무관하게 동일하다(서버가 언어 문자열만 채운다).

**AC (Given / When / Then)**

- 시나리오: 정상 — 표시 언어에 맞는 번역 제공

  - **Given** 표시 언어가 일본어(`ja`)인 ACTIVE 세입자(`lang="ja"`를 직접 고른 경우)가 주제 목록 또는 주제별 팁을 조회한다
  - **When** 생활 팁 조회 엔드포인트를 호출한다
  - **Then** 주제명·제목·내용 표시 텍스트가 일본어로 번역되어 반환되고, `code`/`id`·`imageUrl`은 언어와 무관하게 동일하다
- 시나리오: 폴백 — 미지원 언어

  - **Given** 번역이 준비되지 않은 표시 언어의 사용자다(또는 `lang` 미설정)
  - **When** 생활 팁 조회 엔드포인트를 호출한다
  - **Then** 기본 언어(영어 `en`)로 폴백해 `200 OK`로 반환한다(에러 아님)
- 시나리오: 언어 결정 출처 — 헤더 무관

  - **Given** 사용자가 `Accept-Language`를 다른 값으로 보내도 그 사용자의 표시 언어는 일본어(`ja`)다
  - **When** 생활 팁 조회 엔드포인트를 호출한다
  - **Then** 응답 언어는 헤더와 무관하게 표시 언어(일본어)로 결정된다(번역 언어 출처는 `user`의 `getLanguage(userId)` — `users.lang`이 있으면 그 값, 없으면 `en`)
