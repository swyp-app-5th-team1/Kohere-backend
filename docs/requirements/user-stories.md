    

    

    # User Stories & Acceptance Criteria

> Kohere 핵심 기능(8종)의 **백엔드 유저 스토리**와 인수 조건(AC, Given/When/Then)이다.
> 작성 형식: [user-story-template](user-story-template.md). 각 기능의 API는 [api 스펙](../api/specs/)으로 연결된다.
> 에러 코드/형식은 [error-response-guide](../api/error-response-guide.md), 설계 규약은 [api-design-guide](../api/api-design-guide.md)를 따른다.

## 목차

- 1. 소셜 로그인 · 온보딩 — [API 스펙](../api/specs/01-auth-onboarding.md)
- 2. 맞춤 진단 & 매물 추천 — [API 스펙](../api/specs/02-diagnosis-recommendation.md)
- 3. 매물 등록 · 탐색 · 찜 — [API 스펙](../api/specs/03-listings-favorites.md)
- 4. 매물 예약(신청) · (후속) 문의·인앱 채팅 — [API 스펙](../api/specs/04-booking-inquiry-chat.md)
- 5. 커뮤니티 (게시판 · 동네친구) — [API 스펙](../api/specs/05-community.md)
- 6. 게이미피케이션 (퀴즈) — [API 스펙](../api/specs/06-gamification.md)
- 7. 신고 처리 — [API 스펙](../api/specs/07-reports.md)
- 8. 생활 팁 (주제별 생활 정보) — [API 스펙](../api/specs/08-life-tips.md)

---

## 1. 소셜 로그인 · 온보딩

> 관련 API 스펙: [01-auth-onboarding](../api/specs/01-auth-onboarding.md)

외국인 사용자가 Apple/Google 소셜 계정으로 진입해 서버 자체 JWT를 발급받고(소셜 로그인 시 네이티브 SDK가 준 이름·이메일도 함께 받아 확정·반환한다 — #192. **소셜 로그인은 앱 진입 경로이며, 임대인은 매물 등록 웹에서 이메일·비밀번호 로컬 자격증명으로도 진입한다** — US-1-11 ~ US-1-13), 신규 회원은 **약관 동의 → 온보딩** 순서로 가입 단계를 거친 뒤에야 보호 API를 사용할 수 있게 한다(임대인은 온보딩 선행으로 연락처 SMS 인증을 한 단계 더 거친다). 사용자 상태는 `PENDING`(소셜 검증) → `TERMS_AGREED`(약관 동의 완료) → `ACTIVE`(온보딩 완료)로 전이한다(약관 동의와 온보딩은 분리된 단계). 세입자 온보딩 필수 정보는 성별·생년월일·국적(국가 코드 — 화면엔 국가만 받지만 국기까지 수집, `countries` 참조)·비자정보이며(이름·이메일은 소셜 로그인 때 이미 확보해 온보딩에서 받지 않는다 — #192, 직업은 **선택** — 매물 추천·탐색에 쓰지 않아 미입력을 허용한다, #187), 닉네임은 서버가 `형용사 + 사물`로 자동 배정한다. 토큰 재발급·로그아웃·탈퇴·프로필 조회/수정까지 인증 생애주기 전체를 다룬다.

사용자는 **세입자(외국인)와 임대인** 두 역할(`userType`: `TENANT`/`LANDLORD`)로 나뉜다. 소셜 로그인(US-1-1)·약관 동의(US-1-7)까지는 **두 역할이 같은 공통 흐름**을 타고, **이후 본인 확인·온보딩 단계에서 역할이 갈린다** — 세입자는 위 정보를 `POST /auth/onboarding`(US-1-2)으로 제출하고, 임대인은 생년월일·연락처(전화)를 `POST /auth/landlord/onboarding`(US-1-9)으로 제출하되(이름은 세입자와 동일하게 소셜 로그인 때 확보해 온보딩에서 받지 않는다 — #192) 그 선행으로 **연락처를 SMS 인증번호로 검증**(US-1-10)하는 단계만 거친다(약관 동의 + 연락처 인증만으로 온보딩이 완료된다). **사업자등록번호 검증**(US-1-8)은 온보딩 선행 단계가 아니라 **온보딩을 마친(ACTIVE) 임대인이 나중에(매물 등록 시점) 정식 access 토큰으로 호출하는 온보딩과 분리된 무상태(stateless) 검증 API**다(국세청 사업자등록정보 기반 외부 검증 API 사용). 임대인은 성별·직업·비자정보를 **수집하지 않으며(이메일은 세입자와 동일하게 소셜 로그인 provider 값을 보유하고, 생년월일도 세입자와 동일하게 수집), 사업자등록번호도 온보딩에서 수집하지 않는다** — 단 국적(`country`=`KR`)·표시 언어(`lang`=`ko`)는 클라이언트가 보내지 않고 **서버가 온보딩 시 고정 부여**한다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 `country` 미수집" 결정을 개정). `userType`은 온보딩 제출 엔드포인트로 확정되며, 그 이전(소셜 로그인·약관) 단계는 역할을 강제하지 않는다. 아래 US-1-1·US-1-3 ~ US-1-5·US-1-7은 별도 표기가 없으면 두 역할 공통이고, **US-1-2·US-1-6은 세입자(외국인) 전용**, **US-1-9·US-1-10은 임대인 온보딩 전용**, **US-1-8은 임대인 온보딩 후(ACTIVE) 매물 등록 시점의 임대인 전용 단계**, **US-1-11 ~ US-1-13·US-1-15 ~ US-1-17은 임대인 웹(매물 등록 웹) 전용**이다(**US-1-14는 결번** — 어느 스토리에도 배정하지 않은 번호이며 빠진 스토리가 아니다). 그중 **US-1-16·US-1-17은 계정 복구 경로**로, 로그인 ID나 비밀번호를 잃은 사람이 타는 비로그인 진입점이다.

임대인에게는 앱 소셜 로그인과 별개로 **매물 등록 웹의 로컬 자격증명 경로**가 하나 더 있다 — 웹은 소셜 로그인을 지원하지 않고 **이메일(로그인 ID) + 비밀번호**로 가입·로그인하며(US-1-11·US-1-12), 가입 전 본인 확인은 비로그인 상태에서 **휴대폰 번호를 키로 쓰는 SMS 인증**(US-1-13)이 맡는다. 웹 가입 폼은 한 페이지에서 이름·생년월일·연락처·이메일·비밀번호·약관 동의를 모두 받으므로 서버가 `PENDING` → `TERMS_AGREED` → `ACTIVE`를 **한 트랜잭션 안에서 연속 전이**시켜 완주하며, 웹에는 부분 완료 상태도 온보딩 재개 화면도 없다(앱이 단계를 나눈 이유는 소셜이 이름·이메일만 주는 제약이었고 웹 폼에는 그 제약이 없다). 두 경로는 **같은 `users` 행을 공유한다** — 자격증명 테이블만 `social_accounts`(앱)와 `local_accounts`(웹)로 나뉘고, 같은 사람인지는 **SMS 인증을 통과한 휴대폰 번호 단독**으로 판정한다(이름은 매칭 조건이 아니다). 그래야 웹에서 등록한 매물의 `landlordId`와 앱 소셜 로그인의 `userId`가 같아져 예약(US-4-6)·후속 채팅이 앱에서 그대로 보인다. 판정 시점은 방향에 따라 갈린다 — 웹 가입은 SMS 인증을 이미 마친 뒤라 그 자리에서 **연결**되고(US-1-11), 앱은 소셜 로그인 시점에 번호를 몰라 임대인 온보딩(US-1-9)에서야 **병합**된다(US-1-15). 웹 refresh 토큰은 응답 본문이 아니라 **HttpOnly 쿠키**로 내리며, 재발급·로그아웃(US-1-3·US-1-4)은 쿠키 우선·요청 본문 fallback으로 읽어 앱 동작을 그대로 유지한다(v1 유지, v2 불필요). 로그인 ID(이메일)를 잊거나 비밀번호를 10회 틀려 계정이 잠긴 뒤의 **복구도 웹 안에서 끝난다** — 이메일 찾기(US-1-16)는 SMS로 번호 소유를 증명시킨 뒤 가입 시 입력한 이름을 대조해 마스킹한 이메일을 돌려주고, 비밀번호 재설정(US-1-17)은 메일로 보낸 1회용 링크로 새 비밀번호를 세우면서 **계정 잠금 해제를 겸한다**(잠금 해제 전용 API는 두지 않는다).

> 관련 NFR: [non-functional-requirements](non-functional-requirements.md) — 4. 보안(토큰/민감정보 보호), 1. 성능(소셜 검증 응답시간), 5. 관측성(인증 실패 로깅·마스킹). 구체 목표값은 NFR 문서 확정 전이라 (확인 필요).

---

### US-1-1 — 소셜 로그인으로 진입해 서버 토큰 발급받기

**As a** 외국인 사용자
**I want** 앱에서 받은 Apple/Google `idToken`과 네이티브 SDK가 준 이메일·이름을 서버에 넘겨 검증받고 서버 자체 JWT(access+refresh)를 발급받기를
**So that** 별도 비밀번호 없이 안전하게 로그인하고, 기존 회원이면 바로 서비스를, 신규면 약관 동의 화면으로 이동할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(소셜 토큰 서명·aud·iss·exp 검증, refresh 토큰 서버 보관/해시), 성능(provider 검증 외부호출 타임아웃·관측성)

**AC (Given / When / Then)**

- **정상 — 기존 회원 로그인**
  Given 해당 provider 계정으로 가입·온보딩을 완료한 `ACTIVE` 회원이 존재하고
  When 유효한 `idToken`으로 `POST /api/v1/auth/social-login`을 호출하면
  Then `200 OK` + 공통 래퍼 `data`에 `accessToken`/`refreshToken`/`onboardingRequired=false`/`status="ACTIVE"`/`tokenType="Bearer"`/`expiresIn`과 `email`/`name`(온보딩 프리필용 — 모든 분기에서 반환, 값은 User의 `name`/`email`)이 내려오고, refresh 토큰은 서버에 해시 저장되어 재발급에 사용된다. 앱은 홈으로 이동한다.
- **정상 — 신규 회원 약관 동의 유도**
  Given 해당 provider 계정으로 가입한 회원이 없고, 앱이 네이티브 SDK로부터 `email`·`name`을 받았고
  When 유효한 `idToken`과 함께 `email`·`name`을 담아(**신규 가입은 `email` 필수**, `name`은 캡처·없으면 `null`) `POST /api/v1/auth/social-login`을 호출하면
  Then `200 OK` + `data.onboardingRequired=true`·`data.status="PENDING"`(온보딩 프리필용 `email`/`name` 포함)으로 응답하고, 서버는 **소셜 로그인 값으로 `name`/`email`을 이미 채운 온보딩 미완료(PENDING)** 사용자 레코드(및 provider/providerUserId/email을 보유한 `SocialAccount`)를 생성한다(이름·이메일 수집을 온보딩까지 미루지 않는다 — #192; `SocialAccount`에 `name`은 저장하지 않는다). 이때 발급되는 access 토큰은 온보딩 흐름(약관 동의·본인 확인(임대인 연락처/사업자번호 검증)·온보딩) API만 통과시키는 클레임(`onboardingCompleted=false`)을 가지며, refresh 토큰은 발급하지 않는다(`refreshToken=null`). 앱은 `status`로 분기해 다음으로 **약관 동의 화면**(US-1-7)으로 이동한다. (확인 필요: 온보딩 전용 임시 토큰 만료시간)
- **정상 — 가입 미완료 회원 재로그인(재개 지점 분기)**
  Given 소셜 로그인은 했으나 가입을 끝내지 못한 회원이 다시 로그인하고(신규 행은 만들지 않음)
  When 유효한 `idToken`으로 `POST /api/v1/auth/social-login`을 호출하면
  Then `200 OK` + `data.onboardingRequired=true`(refresh `null`, 프리필용 `email`/`name` 포함 — 값은 저장된 User의 것)로 응답하되 **`data.status`로 재개 지점을 분기**한다 — **약관 미동의면 `status="PENDING"` → 약관 동의 화면(US-1-7)**, 약관까지 동의했으나 온보딩 미완료면 `status="TERMS_AGREED"` → 온보딩 화면(US-1-2). **재로그인이라 요청의 `email`/`name`은 무시하고 저장값을 덮어쓰지 않는다**(Apple은 최초 1회만 제공). (온보딩 토큰으로는 `GET /users/me`가 `403`이라 상태를 따로 조회할 수 없으므로 이 응답의 `status`로 판단한다)
- **입력 검증 실패**
  Given `provider`가 누락(null)이거나 `idToken`이 빈 문자열이면
  When `POST /api/v1/auth/social-login`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`(field/reason)를 반환한다. (`provider`가 `APPLE`/`GOOGLE` 외 값이면 서버가 파싱해 `400` + `error.code=INVALID_INPUT`으로 처리한다 — 둘 다 별도 도메인 코드가 아닌 표준 입력 오류)
- **인증·권한 — 소셜 검증 실패**
  Given `idToken`의 서명이 위조되었거나 `aud`/`iss`가 우리 앱과 불일치하거나 `exp`가 지났으면
  When `POST /api/v1/auth/social-login`을 호출하면
  Then `401` + `error.code=AUTH_INVALID_SOCIAL_TOKEN`을 반환하고, 토큰 원문은 로그에 남기지 않는다(마스킹).
- **인증·권한 — provider 연동 실패도 검증 실패로 처리**
  Given Apple/Google 공개키(JWKS) 조회 또는 검증 요청이 타임아웃/네트워크 오류로 실패하면
  When `POST /api/v1/auth/social-login`을 호출하면
  Then 연동 실패를 포함한 모든 OIDC 검증 실패를 `401` + `error.code=AUTH_INVALID_SOCIAL_TOKEN`으로 처리하고, 회원 레코드를 생성하지 않는다.
- **인증·권한 — 이메일 누락/불일치**
  Given **신규 가입(최초 로그인)인데** 소셜 토큰과 요청 어디에도 `email`이 없거나(토큰 `email` 클레임 부재 + 요청 `email` 미전송), 요청 `email`이 토큰의 `email` 클레임과 일치하지 않으면
  When `POST /api/v1/auth/social-login`을 호출하면
  Then 전자는 `422` + `error.code=AUTH_EMAIL_REQUIRED`, 후자는 `422` + `error.code=AUTH_EMAIL_MISMATCH`를 반환하고 회원 레코드를 생성하지 않는다(`email`은 provider 진본으로 확정하며, `name`은 검증 대상이 아니라 요청 값을 신뢰한다 — Apple은 이름을 최초 1회만 주므로 토큰에서 얻을 수 없다). **재로그인(기존 회원)은 요청에 `email`/`name`이 없어도 되고(저장값 사용) 이 검증을 하지 않는다** — 최초 로그인에서만 캡처·검증한다.

---

### US-1-2 — 필수 온보딩 정보 제출하기 (세입자 전용)

**As a** 약관 동의를 마친(TERMS_AGREED) 세입자(외국인) 사용자
**I want** 성별·생년월일·국적·비자정보(+선택 직업)를 한 번에 제출하기를(이름·이메일은 소셜 로그인 때 이미 확보, 닉네임은 서버 자동 배정)
**So that** 회원 가입을 완료하고 정식 access/refresh 토큰으로 보호 기능을 이용할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(비자정보 등 민감정보 저장·로그 마스킹)
- 선행: 약관 동의(US-1-7, `TERMS_AGREED`)가 완료되어야 한다(이메일 인증은 온보딩 선행이 아니다 — #192로 온보딩 선행 게이트에서 빠졌다).

**AC (Given / When / Then)**

- **정상 — 온보딩 완료**
  Given 약관 동의를 마친(`TERMS_AGREED`) 사용자의 유효한 온보딩 토큰을 보유하고
  When 모든 필수 필드(`gender`·`birthDate`·`country`·`visaType`)를 담아(표시 언어 `lang`과 직업 `occupation`은 **선택** — `lang`은 보내면 그 값으로, 안 보내면 미설정으로 두어 표시 시 `en` 폴백, `occupation`은 안 보내면 저장하지 않음(NULL) — #187) `POST /api/v1/auth/onboarding`을 호출하면(이름·이메일은 소셜 로그인 때 이미 User에 있어 담지 않고, 약관 필드도 담지 않음 — 이미 US-1-7에서 동의·기록)
  Then `200 OK` + `data`에 완성된 프로필(서버가 자동 배정한 `nickname` 포함)과 정식 `accessToken`/`refreshToken`을 내려주고, 사용자 상태를 `TERMS_AGREED` → `ACTIVE`로 전이한다. (상태 전이 액션이므로 신규 리소스 생성이 아닌 `200`을 쓴다. `nickname`은 서버가 형용사 풀·사물 풀에서 골라 `형용사 + 사물`로 조합하고 전역 유니크 충돌 시 재조합해 배정하며 요청 본문에 담지 않는다)
- **정상 — 직업 없이 온보딩 완료**
  Given 위 정상 조건을 갖춘(`TERMS_AGREED`) 사용자가
  When `occupation`을 담지 않고(미전송 또는 `null`) `POST /api/v1/auth/onboarding`을 호출하면
  Then `200 OK`로 온보딩이 완료되고(`ACTIVE` 전이·토큰 발급 동일) 직업은 저장하지 않으며(NULL), 프로필 응답에서 `occupation` 필드가 생략된다(`lang` 미설정과 동일 정책 — 매물 추천·탐색에 직업을 쓰지 않아 선택 입력, #187).
- **입력 검증 실패**
  Given `country`가 비었거나(`country`가 `countries`에 없는 ISO 코드이거나), `gender`가 `MALE`/`FEMALE` 외 값이거나, `occupation` 값을 보낸 경우 정의된 enum(`UNDERGRADUATE_STUDENT`·`GRADUATE_STUDENT`·`EXCHANGE_STUDENT`·`LANGUAGE_TEACHING`·`MANUFACTURING_PRODUCTION`·`BUSINESS_TRADE`·`ETC`) 외 값이거나(미전송·`null`은 선택이라 허용 — #187), `birthDate`가 `YYYY-MM-DD` 형식 위반/미래 날짜이거나, `visaType`이 정의된 enum(상수명: `SHORT_TERM_VISIT`·`STUDENTS_TRAINEES`·`NON_PROFESSIONAL_WORKERS`·`WORKING_HOLIDAY_WORK_AND_VISIT`·`OVERSEAS_KOREANS`·`FAMILY_MARRIAGE_MIGRANTS`·`PERMANENT_RESIDENTS`·`PROFESSIONALS`·`DIPLOMATIC_OFFICIAL_AND_OTHERS`·`ETC`) 외 값이면
  When `POST /api/v1/auth/onboarding`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`로 위반 필드를 반환한다.
- **비즈니스 규칙 — 약관 미동의 상태(우선 판정)**
  Given 약관 동의를 아직 마치지 않은(`PENDING`) 사용자가
  When `POST /api/v1/auth/onboarding`을 호출하면
  Then `422` + `error.code=AUTH_TERMS_AGREEMENT_REQUIRED`를 반환하고 상태를 전이하지 않는다(약관 동의 US-1-7 선행 필요).
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
  Then `204 No Content`를 반환하고 사용자 상태를 `WITHDRAWN`으로 전이하며 모든 refresh 토큰을 무효화한다(개인정보 파기/익명화는 정책 — 확인 필요). 자격증명은 **두 벌을 함께** 지운다 — `social_accounts`(앱)와 `local_accounts`(웹). `users` 행이 보존되고 `user_type`도 그대로라, 웹 자격증명이 남으면 탈퇴한 임대인이 이메일·비밀번호로 다시 로그인된다(ADR-0047).
  그리고 응답에 로그아웃과 **같은 `Max-Age=0` 삭제 쿠키**를 함께 내린다(ADR-0048). 서버에서는 이미 전부 무효화됐으므로 보안 구멍이 아니라 잔여물 정리다 — 지우지 않으면 죽은 쿠키가 최대 14일 브라우저에 남는다. 로그아웃과 달리 **조건이 없다**: 쿠키 `Path`가 `/api/v1/auth`라 브라우저가 `DELETE /users/me`에는 쿠키를 싣지 않아 보유 여부를 판정할 수 없다(쿠키를 가진 적 없는 앱에는 무해하다).
- **경계 — 삭제 쿠키는 성공 응답에만 실린다**
  Given 이미 탈퇴한 계정(`409`)이나 존재하지 않는 사용자(`404`)로 탈퇴를 호출하면
  Then 삭제 쿠키를 내리지 않는다. 실패 응답에 실으면 두 번째 탭·더블클릭으로 나는 `409`가 **아직 살아 있는 세션의 쿠키를 지운다**.
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
  - **임대인(`LANDLORD`)**: 단일 `name`(세입자·임대인 모두 단일 `name`으로 통일 — 내부도 세입자와 동일한 단일 `name` 컬럼에 저장하며 소셜 로그인 때 캡처돼 온보딩에서 재입력하지 않는다, #192. API 요청·응답 필드명은 `name`)·`email`·`birthDate`·`nickname`·`phoneNumber`·`status`·약관 동의 상태·`createdAt`·`country`+`countryName`·`countryFlag`·`lang`을 조회하고, 수정은 `name`·`marketingAgreed`를 자유 수정하며 `phoneNumber`는 SMS 재인증을 거쳐 변경한다. **임대인도 세입자와 동일하게 소셜 로그인 provider(Google/Apple) `email`을 보유해 응답에 포함한다**(email 수집 폼이 아니라 소셜 로그인이 역할 미정 상태로 캡처·저장한 값 — [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 "임대인 이메일 미수집" 결정을 개정(#192). email 수정은 세입자와 동일하게 후속 이슈). **임대인의 `lang`(`'ko'`)·`country`(`'KR'`)는 서버가 온보딩에서 고정으로 심는 값이라 응답에는 나오되 이 경로로 수정할 수 없다**(ADR-0034의 "임대인 country 미수집" 결정을 개정한다). 세입자 전용 필드(`gender`/`occupation`/`visaType`)는 **임대인 응답에 포함하지 않지만, `birthDate`는 임대인도 온보딩에서 수집하므로 응답에 포함한다**. `businessRegistrationNumber`는 `users`에는 해시 컬럼만 두고 원문을 매물 문서에만 저장하므로 프로필 응답·수정 대상이 아니다(변경 시 외부 사업자등록정보 재검증 필요 — [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). `phoneNumber`는 SMS 인증(US-1-10)된 값으로 본인 조회 시 평문 반환하되 타 사용자/로그 노출은 마스킹하며, **변경 시 SMS 재인증(US-1-10)이 필요하다 — 새 번호를 재인증해 VERIFIED된 뒤에만 반영하고 미인증·불일치는 `422 AUTH_PHONE_NOT_VERIFIED`다**.
  - 두 역할 공통으로 `userType`·`nickname`은 불변이고, 세입자 `email`은 소셜 로그인 값으로 고정되어 이 경로로 수정하지 않는다(이메일 변경은 후속 이슈 — #192; 임대인도 소셜 로그인 provider 값으로 `email`을 보유하되 수정은 세입자와 동일하게 후속 이슈다).

**AC (Given / When / Then)**

- **정상 — 조회(세입자)**
  Given 유효한 access 토큰을 보유한 `ACTIVE` 세입자(`userType=TENANT`)가
  When `GET /api/v1/users/me`를 호출하면
  Then `200 OK` + `data`에 본인 프로필(이름·`nickname`·성별·생년월일·`country`(코드)+서버 resolve `countryName`·`countryFlag`·`occupation`(온보딩 선택 — 미설정이면 응답에서 생략, #187)·`email`·`visaType`·`lang`(표시 언어 — 미설정이면 응답에서 생략)·약관 동의 상태)을 반환한다.
- **정상 — 부분 수정(세입자)**
  Given 유효한 access 토큰을 보유한 세입자(`userType=TENANT`)가
  When `PATCH /api/v1/users/me`에 변경할 필드(예: `name`, `country`, `occupation`, `visaType`, `lang`, `marketingAgreed`)만 담아 호출하면
  Then `200 OK` + 수정된 프로필을 반환하고, 전송하지 않은 필드는 변경하지 않는다(미전송 ≠ 값 비움). `nickname`은 시스템 배정값이라 수정 대상이 아니며, `name`은 단일 `name`으로 수정할 수 있다. `email` 변경은 이 엔드포인트로 처리하지 않는다(소셜 로그인 값으로 고정, 이메일 변경은 후속 이슈 — #192).
- **정상 — 표시 언어 직접 선택(세입자)**
  Given 유효한 access 토큰을 보유한 세입자가
  When `PATCH /api/v1/users/me`에 `{ "lang": "en" }`만 담아 호출하면
  Then `200 OK` + `lang="en"`을 반환하고 `country`는 변경하지 않으며, 이후 진단 문항·퀴즈·생활 팁 표시 텍스트가 `en`으로 내려온다(등록 국가와 무관 — US-2-6·US-6-3·US-8-3).
- **정상 — 조회(임대인)**
  Given 유효한 access 토큰을 보유한 `ACTIVE` 임대인(`userType=LANDLORD`)이
  When `GET /api/v1/users/me`를 호출하면
  Then `200 OK` + `data`에 `userType="LANDLORD"`·`name`·`email`·`birthDate`·`nickname`·`phoneNumber`·`status`·약관 동의 상태(`termsOfServiceAgreed`/`privacyPolicyAgreed`/`marketingAgreed`)·`createdAt`과 함께 서버 고정값인 `country="KR"`+서버 resolve `countryName`·`countryFlag`·`lang="ko"`를 반환한다. 세입자 전용 필드(`gender`/`occupation`/`visaType`)와 `businessRegistrationNumber`는 응답에 포함하지 않는다(`birthDate`는 임대인도 수집·반환하고, `email`은 임대인도 소셜 로그인 provider 값을 보유해 반환한다).
- **정상 — 부분 수정(임대인)**
  Given 유효한 access 토큰을 보유한 임대인(`userType=LANDLORD`)이
  When `PATCH /api/v1/users/me`에 자유 수정 필드(`name`·`marketingAgreed`) 중 일부(예: `name`)만 담아 호출하면
  Then `200 OK` + 수정된 프로필을 반환하고, 전송하지 않은 필드는 변경하지 않는다(미전송 ≠ 값 비움). `name`은 세입자와 동일하게 단일 `name` 컬럼에 저장한다(#192). `birthDate`(온보딩에서 확정, 조회 전용)·`businessRegistrationNumber`(원문은 매물 문서에만 저장돼 프로필 수정 대상이 아니며, 변경 시 외부 사업자등록정보 재검증 필요)·`userType`·`nickname`·`lang`·`country`(서버 고정 `'ko'`/`'KR'` — 표시 언어 변경은 세입자만 가능, [ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정(#141))는 이 경로로 수정할 수 없다(`email`은 임대인도 provider 값을 보유하되 세입자와 동일하게 이 경로로 수정하지 않는다 — 후속 이슈). `phoneNumber`는 변경 시 SMS 재인증(US-1-10)이 필요하다(아래 "연락처 변경 시 재인증" 참조).
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

### US-1-6 — 이메일 인증하기 (온보딩 완료 세입자 전용)

**As a** 온보딩을 완료한(ACTIVE) 세입자(외국인) 사용자
**I want** 정식 access 토큰으로 이메일에 인증번호를 받아 확인하기를
**So that** 본인 소유 이메일임을 증명할 수 있다(접근은 정식 토큰(ACTIVE) 전용이며, 검증 성공이 실제 `User.email`을 바꾸는 반영은 후속 이슈 — #192).

- 우선순위: **High**
- 관련 NFR: 보안(이메일 소유 인증·인증번호 해시 보관·재발송/시도 레이트리밋), 보안(이메일·인증번호 로그 마스킹)
- 백엔드 관점: `auth`가 인증번호를 생성해 아웃바운드 포트 `VerificationEmailSender`(인프라 어댑터: SMTP)로 **동기 발송**하고, **발송에 성공한 뒤에만** 인증번호를 **해시로 보관**(Redis, TTL 자동 소멸)한다. provider 장애·타임아웃 등 발송 실패 시 챌린지를 만들지 않고 `502 UPSTREAM_ERROR`로 응답한다. **접근은 온보딩 완료(ACTIVE) 정식 토큰(`ROLE_USER`) 전용**이다 — SecurityConfig에서 이 이메일 인증 API를 온보딩 스코프를 허용하던 tier2에서 `hasRole("USER")` tier3(ACTIVE 전용)로 반전하며(#192), 온보딩 스코프(`PENDING`/`TERMS_AGREED`) 토큰으로는 호출할 수 없다(`403 AUTH_ONBOARDING_REQUIRED`). 이메일 인증은 **더 이상 온보딩 선행 단계가 아니라 온보딩 후(ACTIVE) 단계**다. 검증 성공 시 이메일 소유 인증 사실을 마킹하되 **이번 범위에서는 `User.email`을 바꾸지 않는다**(실제 이메일 변경 반영은 후속 이슈). 임대인 온보딩의 전화(SMS) 인증(US-1-10)은 변경 없다.

**AC (Given / When / Then)**

- **정상 — 인증번호 발송**
  Given 온보딩을 완료한(`ACTIVE`) 세입자가 정식 access 토큰(`ROLE_USER`)으로 이메일을 입력하고
  When `POST /api/v1/auth/email/verification-code`에 `{ email }`을 담아 호출하면
  Then `200 OK` + `data.expiresIn`(만료 초)을 반환하고 해당 이메일로 인증번호를 발송한다. 메일 발송에 성공한 뒤에만 인증번호 챌린지를 저장하며(인증번호 원문은 저장·로그하지 않고 해시로만 보관), `email`은 마스킹해 반환한다.
- **인증·권한 — 온보딩 스코프 토큰으로 호출**
  Given 온보딩 미완료(`PENDING`·`TERMS_AGREED`) 온보딩 스코프 토큰으로
  When 이메일 인증 API(`/auth/email/verification-code`·`/auth/email/verify`)를 호출하면
  Then `403` + `error.code=AUTH_ONBOARDING_REQUIRED`를 반환하고 인증번호를 발송·검증하지 않는다(온보딩 완료·정식 토큰(`ROLE_USER`) 필요 — 이메일 인증은 온보딩 선행이 아니라 온보딩 후 단계, #192).
- **장애 — 메일 발송 실패**
  Given 메일 provider 장애·타임아웃 등으로 인증번호 발송이 실패하면
  When `POST /api/v1/auth/email/verification-code`를 호출하면
  Then `502` + `error.code=UPSTREAM_ERROR`를 반환하고, 인증번호 챌린지를 저장하지 않아 클라이언트가 재시도하도록 유도한다(동기 발송 정책).
- **정상 — 인증번호 확인**
  Given 발송된 인증번호가 유효(미만료·시도 미초과)하고
  When `POST /api/v1/auth/email/verify`에 `{ email, code }`를 담아 호출하면
  Then `200 OK` + `data.verified=true`를 반환하고 이메일 소유 인증 사실을 마킹한다. 다만 이번 범위에서는 `User.email`을 바꾸지 않는다(실제 이메일 변경 반영은 후속 이슈 — #192).
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
**So that** 실제 영업 중인 사업자임을 확인해야 할 때 온보딩과 분리된 무상태 검증으로 즉시 확인받을 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(사업자등록번호 등 민감정보 저장·로그 마스킹), 보안·신뢰성(외부 연동 타임아웃·관측성·장애 시 degrade), 보안(검증 시도 레이트리밋)
- 선행: 온보딩 완료(US-1-9, `ACTIVE`)와 정식 access 토큰(`ROLE_USER`)이 필요하다. 이 단계는 **임대인 온보딩 후(ACTIVE) 임대인이 필요할 때 직접 호출하는 전용 API**로, 온보딩(US-1-9)의 선행 게이트가 아니며 세입자 온보딩(US-1-2)에는 없다. **매물 등록(US-3-6)도 이 API를 호출하지 않는다** — 등록은 사업자등록번호를 형식만 검증해 매물 문서에 저장하고 진위는 관리자가 승인 심사에서 수동으로 확인한다([ADR-0033](../adr/0033-business-registry-verification.md) 개정).
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
**I want** 생년월일·연락처(전화)를 한 번에 제출하기를(이름은 세입자와 동일하게 소셜 로그인 때 캡처돼 온보딩에서 재입력하지 않는 단일 `name`, 연락처는 사전 인증, 닉네임은 서버 자동 배정, 이메일은 세입자와 동일하게 소셜 로그인 provider 값 보유·온보딩 미수집, 사업자번호는 미수집)
**So that** 임대인으로 가입을 완료하고 정식 access/refresh 토큰으로 임대인 기능(매물 연결·세입자 채팅 등)을 이용할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(연락처 등 민감정보 저장·로그 마스킹), 보안(휴대폰 소유 인증)
- 선행: 약관 동의(US-1-7, `TERMS_AGREED`)·연락처 인증(US-1-10)이 완료되어야 한다(약관 + 연락처만으로 온보딩 완료 — 사업자등록번호 검증(US-1-8)은 온보딩 선행이 아니고, **매물 등록(US-3-6)의 선행 단계도 아닌** 독립 무상태 API다).
- 백엔드 관점: 세입자 온보딩(`POST /auth/onboarding`, US-1-2)과 분리된 **임대인 전용 엔드포인트** `POST /api/v1/auth/landlord/onboarding`로 처리한다. 성공 시 사용자 상태를 `TERMS_AGREED` → `ACTIVE`로 전이하고 **`userType`을 `LANDLORD`로 확정**하며, 닉네임 자동 배정과 정식 access/refresh 토큰 발급은 세입자 온보딩과 동일하다(상태 전이 액션이므로 `200`). 요청 본문은 `{ phoneNumber, birthDate }` 두 필드다. 임대인은 성별·국적·직업·비자정보를 **수집하지 않으며(생년월일 `birthDate`은 세입자와 동일하게 필수 수집), 사업자등록번호도 온보딩에서 수집하지 않는다**([ADR-0034](../adr/0034-landlord-phone-sms-verification.md); `user.businessRegistrationNumberHash` 컬럼은 유지하되 온보딩 완료 시 `null`로 남고 **매물 등록 시점에도 채우지 않는다** — 사업자등록번호는 **매물 등록 요청(US-3-6)에서 받아 원문 그대로 매물 문서에 저장**한다, [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). 그때도 등록 API는 **형식(숫자 10자리)만 검증하고 진위는 자동 검증하지 않는다** — 무상태 검증 API(US-1-8)를 호출하지 않으며, 진위 확인은 관리자가 승인 심사에서 수동으로 한다. 따라서 임대인 계정에는 온보딩 이후에도 사업자등록번호가 남지 않는다. `name`은 세입자와 동일하게 소셜 로그인 때 `User.name`에 캡처·저장돼 온보딩에서 재입력하지 않으며(수정은 `PATCH /users/me`), `email`도 세입자와 동일하게 소셜 로그인 provider 값을 보유하므로 온보딩에서 수집하지 않는다(ADR-0034의 "임대인 이메일 미수집" 결정을 개정 — 수집 폼이 아니라 provider 값 보유, #192). 임대인 온보딩 본인 확인은 여전히 전화(SMS) 인증(US-1-10)이며 email은 인증 대상이 아니다. 검증 게이트 우선순위는 약관 미동의 → 연락처 미인증 순이며 사업자번호 게이트는 없다.

**AC (Given / When / Then)**

- **정상 — 임대인 온보딩 완료**
  Given 약관 동의를 마친(`TERMS_AGREED`) 사용자의 유효한 온보딩 토큰을 보유하고 제출 `phoneNumber`가 사전 인증(US-1-10)되어 있으며
  When 모든 필수 필드(`phoneNumber`·`birthDate`)를 담아 `POST /api/v1/auth/landlord/onboarding`을 호출하면(이름·약관 필드는 담지 않음 — 이름은 소셜 로그인 때 이미 확보)
  Then `200 OK` + `data`에 완성된 임대인 프로필(`userType="LANDLORD"`·서버가 자동 배정한 `nickname` 포함)과 정식 `accessToken`/`refreshToken`을 내려주고, 사용자 상태를 `TERMS_AGREED` → `ACTIVE`로 전이한다.
- **입력 검증 실패**
  Given `phoneNumber`가 빈값이거나 형식(전화번호)이 어긋나거나 `birthDate`가 누락·형식 위반·미래 날짜이면
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
- 선행: 약관 동의(US-1-7, `TERMS_AGREED`)가 완료되어야 한다. 이 단계는 **임대인 트랙 전용**의 온보딩 선행 본인 확인이다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)) — 세입자 온보딩에는 별도 본인 확인 단계가 없다(#192로 이메일 인증이 온보딩 선행에서 빠졌다).
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

---

### US-1-11 — 임대인 웹 회원가입하기 (임대인 웹 전용)

**As a** 매물을 등록하려는 임대인
**I want** 웹 회원가입 페이지 한 곳에서 이름·생년월일·연락처·이메일·비밀번호·약관 동의를 한 번에 제출해 가입을 끝내되, 앱에 이미 내 계정이 있으면 그 계정에 자동으로 연결되기를
**So that** 웹에서 등록한 매물의 예약·(후속) 채팅을 앱에서 소셜 로그인해도 그대로 볼 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(비밀번호 BCrypt 해시 보관·번호 소유 증명 없는 계정 탈취 차단), 보안(이름·연락처·비밀번호 로그 마스킹), 정합성(가입 원자성)
- 선행: 가입용 연락처 인증(US-1-13)을 통과해야 제출할 수 있다. 앱 소셜 로그인(US-1-1)·약관 동의(US-1-7)·임대인 온보딩(US-1-9)은 선행이 아니다 — 웹 가입은 앱과 독립된 진입점이며, 이미 앱 계정이 있는 사람도 같은 폼을 그대로 제출한다(연동 여부로 입력란이 갈리지 않는다).
- 백엔드 관점: `POST /api/v1/auth/signup`(`permitAll`)을 신설한다. 요청은 `{ name, birthDate, phoneNumber, email, password, termsOfServiceAgreed, privacyPolicyAgreed, marketingAgreed }`, 응답 `data`는 `{ linked, onboardingRequired, status, tokenType, accessToken, expiresIn, email, name }`이며 **refresh 토큰은 응답 본문에 담지 않고** `Set-Cookie: refreshToken`으로만 내린다(US-1-12의 쿠키 속성·TTL과 동일).
  - **가입 API 하나가 `ACTIVE`까지 원자적으로 완주한다** — `PENDING` → `TERMS_AGREED` → `ACTIVE`를 한 트랜잭션 안에서 연속 전이시키되 상태 체인과 도메인 전이(약관 동의·임대인 온보딩)는 앱과 동일한 것을 그대로 호출한다. **앱 계정과 데이터 모양이 같아야 연동이 성립**하므로 웹 전용 상태나 웹 전용 생성 경로를 만들지 않는다. 웹에 부분 완료 상태를 남기면 온보딩 재개 화면이 없어 로그인해도 갈 곳이 없는 죽은 계정이 된다.
  - 웹 자격증명은 `auth` 모듈이 새로 소유하는 `local_accounts`(`email` UNIQUE·`user_id` UNIQUE·`password_hash`·`failed_login_attempts`·`locked_at`·`name`·`birth_date`)에 저장한다. `users`에 비밀번호 컬럼을 붙이지 않는다 — 자격증명은 `auth`, 프로필은 `user` 소유라는 경계를 유지하고 `social_accounts`와 대칭을 이룬다. `user_id` UNIQUE가 "한 계정에 웹 자격증명 1개" 불변식을 DB 레벨로 보장한다.
  - **연동 판정 키는 SMS 인증을 통과한 `phoneNumber` 단독**이다. 번호는 비밀이 아니라 조회 키일 뿐이고 소유 증명은 전적으로 US-1-13이 담당하므로, 인증을 통과하지 않은 번호로는 어떤 연동도 일어나지 않는다. 이름을 매칭 조건에 넣으면 보안에 기여하는 바 없이 실패만 늘린다 — 앱 이름은 소셜 SDK 표기(`Kim Imdae`), 웹 이름은 직접 입력(`김임대`)이라 불일치가 자연스럽고, 불일치하면 계정이 조용히 갈라져 사용자는 "앱에서 내 매물의 예약이 안 보인다"만 겪는다.
  - 휴대폰 번호는 **입력 경로에서 숫자만 남겨 정규화**한 값으로 저장·조회하며, `users.phone_number`에 UNIQUE 제약을 새로 건다. 이 제약이 웹 가입과 앱 온보딩(US-1-15)이 동시에 들어올 때 계정이 갈라지는 것을 막는 유일한 수단이다. 세입자·탈퇴자는 번호가 NULL이라(MySQL UNIQUE는 NULL 중복을 허용) 영향받지 않는다. **기존 데이터는 백필하지 않는다** — 하이픈이 포함된 채 저장된 임대인 번호는 매칭에서 누락될 수 있다(아래 경계 AC).
  - 이메일 중복 검사는 **`local_accounts.email`에만** 건다. `users.email`은 보지 않으며 UNIQUE도 추가하지 않는다 — 소셜 로그인은 `(provider, providerUserId)`로 계정을 판정해 `users.email`이 로그인 ID가 아니고(nullable·비UNIQUE), 여기에 유일성을 걸면 소셜과 같은 이메일로 웹 가입하는 가장 흔한 정상 경로가 막힌다.
  - 약관은 앱 `POST /auth/terms`(US-1-7)와 **같은 3필드**를 가입 요청에 함께 싣는다 — `termsOfServiceAgreed`·`privacyPolicyAgreed`는 필수 `true`, `marketingAgreed`는 선택이다. 웹 화면에는 개인정보 수집·이용 동의 체크박스만 두고 서비스 이용약관은 **가입 버튼 문구로 갈음**하므로("가입하기를 누르면 서비스 이용약관에 동의하는 것으로 봅니다"), 서버가 기록하는 `termsOfServiceAgreed=true`가 화면 고지와 일치한다. 마케팅 동의는 화면에 없어도 무방하며 이후 `PATCH /users/me`로 켤 수 있다(US-1-5).
  - 서버 고정값은 앱과 동일하다 — `country="KR"`·`lang="ko"`·닉네임 자동 배정·`userType="LANDLORD"`. 사업자등록번호도 앱과 같이 가입에서 수집하지 않으며 검증(US-1-8)은 가입 후 별도 호출이다.
  - **표시 규칙: 응답의 `name`·`email`은 `users`의 값을 싣는다.** 폼이 준 `name`·`birthDate`는 `local_accounts`에 스냅샷으로만 남기고(provider가 준 표시 이름을 `User.name`과 별개로 보관하는 `social_accounts.name`과 같은 취급) 어떤 응답에도 싣지 않는다. 연동된 계정이면 응답에 소셜 진본 이메일이 나갈 수 있으며 의도된 동작이다. 프로필 수정(US-1-5)도 `users`만 바꾸고 `local_accounts` 사본은 갱신하지 않는다.

**AC (Given / When / Then)**

- **정상 — 기존 앱 계정과 연동되는 가입**
  Given 앱에서 소셜 로그인 후 임대인 온보딩까지 마쳐 `ACTIVE`·`LANDLORD`이고 연락처가 `01012345678`인 사용자가 존재하고, 웹 가입자가 **같은 번호로 가입용 연락처 인증(US-1-13)을 통과**했으며
  When 폼 전체(`name`·`birthDate`·`phoneNumber`·`email`·`password`·약관 3필드)를 담아 `POST /api/v1/auth/signup`을 호출하면
  Then `200 OK` + `data.linked=true`·`data.status="ACTIVE"`·`data.onboardingRequired=false`와 `accessToken`·`tokenType="Bearer"`·`expiresIn`을 반환하고(refresh는 `Set-Cookie`), **새 `users` 행을 만들지 않고** 기존 `user_id`에 `local_accounts` 행만 추가한다. 이후 이 계정으로 웹에서 등록한 매물의 `landlordId`는 앱 소셜 로그인 시의 `userId`와 같다.
- **정상 — 연동 시 `users`를 갱신하지 않는다**
  Given 위 연동이 성립하고 폼의 `name`·`birthDate`·`email`이 기존 계정의 값과 다르면
  When 가입을 제출하면
  Then 폼 값은 `local_accounts`(`name`·`birth_date`·`email`)에만 저장하고 **`users`의 `name`·`birth_date`·`email`은 갱신하지 않는다**(소셜 진본 유지). 기존 값은 온보딩을 마친 확정 값이고 폼 값은 방금 입력한 미검증 값이라, 덮어쓰면 "가입했더니 내 프로필이 바뀌었다"는 놀라운 동작이 된다. 응답의 `name`·`email`도 `users`의 값이며, 이름이 달라도 연동을 막지 않는다.
- **정상 — 연동 대상이 없는 신규 가입(한 번에 완주)**
  Given 그 번호를 가진 기존 사용자가 없고 가입용 연락처 인증을 통과했으며
  When `POST /api/v1/auth/signup`을 호출하면
  Then `200 OK` + `data.linked=false`·`data.status="ACTIVE"`·`data.onboardingRequired=false`와 정식 토큰(refresh는 쿠키)을 반환한다. 서버는 한 트랜잭션 안에서 `users` 생성(`PENDING`) → 약관 동의 전이(`TERMS_AGREED`) → 임대인 온보딩 전이(`ACTIVE`·`userType="LANDLORD"`·정규화한 `phoneNumber`·`birthDate` 기록) → `local_accounts` 생성까지 마친다. **신규 생성일 때만 폼의 `name`·`email`이 `users`에도 들어간다.** 웹에는 별도 약관·온보딩 단계가 없다.
- **정합성 — 가입 원자성**
  Given 위 연쇄 전이 중 어느 단계가 실패하면
  When 가입을 제출하면
  Then **전체를 롤백**한다. `users` 행만 생기고 `local_accounts`가 없는(로그인 불가) 상태나, 반대로 자격증명만 뜬 상태를 남기지 않는다.
- **보안 — 연락처 인증 없는 가입 시도(계정 탈취 차단)**
  Given 제출한 `phoneNumber`에 유효한 가입용 검증 마커(US-1-13)가 없으면
  When `POST /api/v1/auth/signup`을 호출하면
  Then `422` + `error.code=AUTH_PHONE_NOT_VERIFIED`를 반환하고 **계정 생성도 연동도 하지 않는다**(이름과 번호를 안다는 것만으로는 남의 계정에 자격증명이 붙지 않는다).
- **비즈니스 규칙 — 필수 약관 미동의**
  Given `termsOfServiceAgreed`·`privacyPolicyAgreed` 중 하나라도 `false`이면
  When 가입을 제출하면
  Then `422` + `error.code=AUTH_REQUIRED_AGREEMENT_MISSING`을 반환하고 계정을 만들지 않는다(US-1-7과 같은 규칙·같은 코드 — 형식은 맞으나 비즈니스 규칙 위반이라 422).
- **비즈니스 규칙 — 이메일 중복(로그인 ID 유일성)**
  Given 같은 이메일로 이미 `local_accounts` 행이 있으면
  When 가입을 제출하면
  Then `409` + `error.code=AUTH_EMAIL_ALREADY_REGISTERED`를 반환한다. 이메일은 연동 키가 아니라 **웹 로그인 ID**이므로 유일해야 로그인(US-1-12)이 계정을 특정할 수 있다(중복을 허용하면 로그인 기능 자체가 성립하지 않는다).
- **정상 — 앱 소셜 로그인 이메일과 같은 주소로 웹 가입(막지 않는다)**
  Given 앱에서 소셜 로그인으로 `users.email="kim@gmail.com"`을 보유한 사용자가 웹에서도 같은 주소로 가입하면
  When 가입을 제출하면
  Then **정상 처리한다** — 중복 검사는 `local_accounts.email`에만 걸고 `users.email`은 보지 않으며, `users.email`에 UNIQUE를 추가하지 않는다. 임대인 대다수가 타는 경로라 여기에 유일성을 걸면 본인이 본인 이메일로 가입하다 `409`를 맞는다.
- **경계 — 남이 쓰는 소셜 이메일로 웹 가입**
  Given A가 앱에서 `kim@gmail.com`으로 소셜 가입해 있고 **다른 사람** B가 웹에서 같은 주소로 가입하면(오타든 악의든)
  When 가입을 제출하면
  Then B의 번호가 A와 달라 매칭되지 않으므로 **별개 계정이 생성되고 `users.email`이 같은 사용자가 둘** 존재하게 된다. 매물·예약은 `user_id`로 갈려 섞이지 않으며, 이메일은 웹 로그인 ID일 뿐 계정 복구 수단이 아니라 계정 탈취로 이어지지 않는다.
- **비즈니스 규칙 — 이미 웹 계정이 연결된 사용자(번호로 판정)**
  Given 번호로 매칭된 `users`에 이미 `local_accounts` 행이 존재하면
  When 가입을 제출하면
  Then `409` + `error.code=AUTH_WEB_ACCOUNT_ALREADY_EXISTS`를 반환하고 **기존 자격증명을 덮어쓰지 않는다**(로그인 화면으로 안내). 이 상태는 연동이 이미 끝나 붙일 자리가 없는 상태이며, 제출된 이메일·비밀번호로 할 수 있는 일은 로그인 ID까지 조용히 바꾸는 자격증명 교체뿐이라 가입 엔드포인트가 할 일이 아니다. **응답에는 공통 에러 스키마(`code`·`message`)만 담고 그 계정의 이메일은 마스킹해서도 노출하지 않는다.** 이메일 중복(`AUTH_EMAIL_ALREADY_REGISTERED`)과는 다른 케이스다 — 저건 "이 이메일은 남이 쓰고 있다", 이건 "이 사람은 이미 웹 계정이 있다"이다.
- **경계 — 온보딩 미완료 앱 계정은 매칭되지 않는다(정상 동작)**
  Given 앱에서 소셜 로그인만 하고 임대인 온보딩을 마치지 않은 계정이 존재하면(`phone_number`가 NULL)
  When 웹 가입을 제출하면
  Then 매칭에 걸리지 않아 **새 계정으로 가입된다**(`linked=false`). 그 앱 계정이 나중에 임대인 온보딩을 마칠 때 **US-1-15가 병합**하므로 최종적으로 하나의 계정으로 수렴한다.
- **경계 — 세입자 계정은 매칭 대상이 아니다(세입자→임대인 전환 불가)**
  Given 앱에서 세입자(`TENANT`)로 가입·온보딩을 마친 계정이 존재하면(세입자는 `phone_number`가 NULL)
  When 같은 사람이 웹 가입을 제출하면
  Then 매칭에 걸리지 않아 **별개의 임대인 계정이 생성된다**. `userType`은 온보딩 제출로 확정된 뒤 불변이라 세입자→임대인 전환은 지원하지 않으며, **역할 검사나 거부 분기를 두지 않는다** — 번호 부재로 구조적으로 걸러진다. 서버는 두 계정이 동일인인지 알 수 없어 안내도 하지 않는다. 앱에서도 임대인으로 쓰려면 **다른 소셜 계정으로 앱에 가입해 US-1-15 병합**을 타야 한다.
- **경계 — 하이픈으로 저장된 기존 번호는 매칭에서 누락될 수 있다**
  Given 번호 정규화는 이번 변경의 **입력 경로에만** 적용하고 기존 데이터를 백필하지 않으므로, `users.phone_number`에 하이픈이 포함된 채 저장된 임대인이 있으면
  When 그 사람이 웹 가입을 제출하면
  Then 정규화한 번호와 문자열이 달라 매칭되지 않고 별개 계정이 생긴다. **알려진 제약으로 수용하며**, 필요하면 운영에서 해당 행을 개별 정리한다.
- **입력 검증 — 비밀번호 정책**
  Given 비밀번호가 영문자(`A-Za-z`) 1자 이상 · 숫자 1자 이상 · **ASCII 특수문자**(``!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~``) 1자 이상을 모두 포함하고 **길이 8~20자**를 만족하지 않거나, **공백**·한글 등 허용 집합 밖 문자를 포함하면
  When 가입을 제출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`(`field=password`)를 반환한다. 검증은 요청 DTO의 Bean Validation(`@Pattern`)으로 걸어 기존 `INVALID_INPUT` 처리 흐름에 태운다.
- **입력 검증 실패(그 외)**
  Given `name`·`phoneNumber`·`email` 누락, `email` 형식 위반, `phoneNumber` 형식 위반(하이픈은 허용하고 서버가 정규화한다), `birthDate` 형식 위반·미래 날짜, 약관 필수 boolean 누락(null)이면
  When `POST /api/v1/auth/signup`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`(field/reason)를 반환한다.
- **보안 — 비밀번호 보관**
  Given 어떤 경우에도
  When 가입을 제출하면
  Then 비밀번호 원문을 저장·로그하지 않고 **BCrypt 해시로만** 보관한다.
- **경계·동시성 — 같은 번호로 웹 가입과 앱 온보딩이 동시에 도착**
  Given 같은 번호에 대해 웹 가입 제출과 앱 임대인 온보딩 제출(US-1-15)이 거의 동시에 도착해 양쪽 모두 상대의 커밋 전에 번호를 조회하면
  When 두 트랜잭션이 각각 계정을 확정하려 하면
  Then `users.phone_number` UNIQUE 제약이 두 번째를 실패시켜 **같은 번호의 `ACTIVE` 계정이 둘 생기지 않는다**(조회 후 삽입 패턴은 애플리케이션 레벨로 막을 수 없다). 실패한 쪽은 재시도하면 상대가 만든 계정을 발견해 정상 연동·병합된다.
  **And** 실패한 요청의 응답은 **`409` + `error.code=RESOURCE_CONFLICT`** 다(전역 예외 핸들러가 **문서화된 UNIQUE 제약**의 중복 위반만 골라 번역한다 — 종전 `500 INTERNAL_ERROR`에서 바뀌었다). 트랜잭션은 통째로 롤백되므로 `users`·`local_accounts` 어디에도 반쪽 행이 남지 않는다.
- **알려진 제약 — 롤백돼도 refresh 해시는 Redis에 남는다**
  Given 위 경합처럼 커밋 시점에 UNIQUE 위반이 나면
  When 트랜잭션이 롤백돼 `409`가 나가면
  Then **MySQL 쓰기만** 되돌아간다 — 토큰 발급은 이미 끝난 뒤이고 refresh 해시는 Redis에 있어 롤백 대상이 아니므로, **쓰이지 않을 해시 하나가 14일 TTL로 남는다**. 원문은 응답으로 전달되지 않아 세션을 열 수 없고(악용 불가) 항목은 스스로 만료되지만, 그때까지 `refresh:user:{id}` 인덱스가 실제 세션 수보다 많아 보인다. 토큰 발급을 트랜잭션 밖으로 들어내는 재구성은 하지 않는다(수용).

---

### US-1-12 — 임대인 웹 로그인하기 (임대인 웹 전용)

**As a** 웹 계정을 만든 임대인
**I want** 이메일·비밀번호로 로그인하고 refresh 토큰이 브라우저 쿠키에 안전하게 보관되기를
**So that** 매 방문마다 다시 로그인하지 않고 웹에서 매물을 관리할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(무차별 대입 방어·자격증명 오류 정보 최소화·쿠키 속성), 보안(이메일·비밀번호 로그 마스킹), 성능(비밀번호 해시 검증 비용)
- 선행: 웹 회원가입(US-1-11) 완료. 앱 소셜 로그인(US-1-1)과는 별개 진입점이며 웹은 소셜 로그인을 지원하지 않는다.
- 백엔드 관점: `POST /api/v1/auth/login`(`permitAll`)을 신설한다. 요청은 `{ email, password }`, 응답 `data`는 `{ onboardingRequired, status, tokenType, accessToken, expiresIn, email, name }`이며 refresh는 `Set-Cookie`로만 내린다. 기존 `/auth/social-login`에 `provider=LOCAL`을 끼워 넣지 않는다 — 소셜 요청 DTO가 이미 provider별 조건부 자격 필드로 복잡해 `password`까지 섞으면 검증 분기가 3중이 된다.
  - **토큰 발급·회전·재사용 탐지는 기존 로직(US-1-3)을 그대로 재사용**한다. 규칙을 두 벌로 만들지 않으며, 웹 refresh TTL도 **앱과 동일한 14일**이라 웹 전용 설정키를 새로 두지 않는다.
  - refresh 쿠키는 `HttpOnly`·`Secure`·`SameSite=Lax`·`Path=/api/v1/auth`로 내린다. 재발급·로그아웃(US-1-3·US-1-4)은 refresh를 **쿠키 우선·요청 본문 fallback**으로 읽고, **요청이 쿠키로 왔으면 회전된 refresh를 다시 쿠키로, 본문으로 왔으면 기존대로 본문에** 담는다 — 앱 동작이 그대로라 하위 호환이 깨지지 않아 v2가 필요 없다.
  - 실패 횟수·잠금 시각은 Redis TTL이 아니라 **`local_accounts.failed_login_attempts`·`locked_at` 컬럼**에 둔다. TTL로 두면 만료와 함께 잠금이 **저절로** 풀리는데, 해제는 본인이 비밀번호 재설정(US-1-17)으로 **명시적으로** 하는 일이지 시간이 대신 해 주는 일이 아니다. 컬럼이라야 잠긴 시각이 행에 남아 언제 잠겼고 무엇으로 풀렸는지 사후에 확인할 수 있다(TTL 만료는 아무 흔적도 남기지 않는다). **마이그레이션 `V22` 주석에는 이 근거가 아직 "해제 기능 없음"으로 적혀 있으나 이미 적용된 스크립트라 체크섬 때문에 고칠 수 없다** — 근거의 정본은 이 문단이다.
  - **로그인 시도 자체에 레이트리밋을 건다** — 자격증명 조회·비밀번호 해시 대조보다 **먼저** IP 60회/시간·이메일 20회/시간을 세고(`app.auth.web.login.*`, Redis 고정 창) 초과하면 `429 TOO_MANY_REQUESTS`다. 순서가 계약이다: 뒤에서 보면 막힌 요청도 이미 BCrypt 비용을 치른 뒤라, `permitAll`이면서 **선행 조건이 하나도 없는** 이 경로의 CPU 증폭을 막지 못한다(가입은 SMS 인증 마커 게이트 뒤에 있다).
  - **웹과 API를 같은 오리진에 배치하는 것을 전제**로 한다 — CORS 설정이 필요 없고 `SameSite=Lax`가 크로스사이트 요청에 refresh 쿠키를 싣지 않아 `csrf.disable()`을 유지한다. 이 전제는 배포 형상에 달려 있다(아래 CSRF AC).
  - 신규 경로는 보안 설정의 `permitAll` matcher와 **공개 경로 목록 양쪽에** 등록한다. 목록에서 빠지면 만료된 토큰을 들고 있는 브라우저가 로그인 요청에서 `401 TOKEN_EXPIRED`를 맞는다.

**AC (Given / When / Then)**

- **정상 — 로그인**
  Given `local_accounts`에 등록된 이메일과 비밀번호가 일치하고 잠기지 않은 계정이 있고
  When `POST /api/v1/auth/login`에 `{ email, password }`를 담아 호출하면
  Then `200 OK` + `data`에 `accessToken`·`tokenType="Bearer"`·`expiresIn`·`status="ACTIVE"`·`onboardingRequired=false`와 `email`·`name`(둘 다 `users`의 값)을 반환하고, **refresh 토큰은 응답 본문이 아니라 `Set-Cookie`로** 내려간다. 실패 카운터는 0으로 리셋한다.
- **경계 — 웹 계정은 항상 `ACTIVE`다**
  Given 웹 가입이 한 트랜잭션으로 `ACTIVE`까지 완주하므로(US-1-11)
  When 웹 로그인을 호출하면
  Then `onboardingRequired`는 항상 `false`, `status`는 항상 `"ACTIVE"`다. **웹 로그인에는 온보딩 재개 분기가 없다** — 앱처럼 `PENDING`/`TERMS_AGREED` 상태로 로그인해 재개 지점을 분기하는 경로(US-1-1)가 웹에는 존재하지 않는다.
- **인증·권한 — 존재하지 않는 이메일 / 비밀번호 불일치**
  Given 둘 중 어느 경우든
  When `POST /api/v1/auth/login`을 호출하면
  Then **동일하게** `401` + `error.code=AUTH_INVALID_CREDENTIALS`를 반환한다. status·code·문구가 같다.
- **응답 — 비밀번호 불일치는 남은 시도를 알려준다**
  Given 계정은 실재하는데 비밀번호가 틀렸으면
  When `POST /api/v1/auth/login`을 호출하면
  Then `401` 응답의 `error.details`에 `failedAttempts`(누적 실패 횟수)와 `maxFailedAttempts`(잠금 상한)를 담는다. `failedAttempts`가 `maxFailedAttempts`에 도달한 응답이 곧 **잠금이 걸린 시점**이다.
- **경계 — `error.details`가 실리지 않는 실패**
  Given 등록되지 않은 이메일, `ACTIVE`가 아닌 계정, 잠긴 계정(`423`), 시도 한도 초과(`429`) 중 어느 경우든
  When 로그인을 호출하면
  Then 값이 `null`이 아니라 **`error.details` 필드 자체가 생략된다**.
- **알려진 제약 — 계정 열거를 수용한다**
  Given `error.details`는 등록된 계정의 비밀번호 불일치에만 실리므로
  When 임의의 비밀번호로 한 번 로그인해 보면
  Then **그 이메일의 가입 여부를 응답만으로 알 수 있다**. 잠긴 계정이 `423`으로 이미 존재를 드러내고 있고, **잠기기 전에** 남은 시도를 알려 주는 사전 안내가 이 기능의 목적이므로 **수용을 그대로 유지한다**. 자력 해제 경로(US-1-17)가 생겼다고 이 근거가 약해지지 않는다 — 잠긴 뒤에 복구할 수 있다는 것과 잠기기 전에 경고를 받는 것은 다른 일이고, 경고가 없으면 사용자는 예고 없이 `423`을 맞고 나서야 메일함으로 가야 한다. 시도 레이트리밋은 이메일 축이 이메일 단위라 열거를 막지 못하고, IP 축은 위조 가능하므로 완화책으로 셈하지 않는다.
- **보안 — 비밀번호 10회 실패 시 계정 잠금**
  Given 같은 계정에 대해 비밀번호 검증이 연속 10회 실패하면
  When 10회째 실패가 발생하면
  Then `failed_login_attempts=10`과 `locked_at`을 기록해 계정을 잠그고, 이후에는 **비밀번호가 맞더라도** `423` + `error.code=AUTH_ACCOUNT_LOCKED`를 반환한다(잠금 판정이 자격증명 검증보다 우선한다).
- **경계 — 잠금은 본인이 비밀번호 재설정으로 푼다**
  Given 계정이 잠긴 상태이면
  When 임대인이 다시 로그인하면
  Then 계속 `423` + `error.code=AUTH_ACCOUNT_LOCKED`이고, **해제는 본인이 비밀번호 재설정(US-1-17)을 마치는 것으로 이뤄진다** — 재설정 확정이 비밀번호 교체·실패 카운터 초기화·`locked_at` 비우기를 한 번에 하므로 별도 잠금 해제 API가 없다. **시간 경과 자동 해제는 여전히 없다**(기다리면 풀리는 잠금은 무차별 대입을 지연시킬 뿐 막지 못한다). 운영자가 DB에서 `locked_at`을 비우는 것은 재설정 메일조차 받을 수 없는 경우를 위한 **예외 수단**으로만 남는다 — 다만 재설정 경로는 이번 범위에서 `local`·`dev`에만 열려 있어, prod에서는 그 예외 수단이 당분간 유일한 해제 방법이며 잠긴 임대인이 연락할 대응 창구를 운영에서 정해 둬야 한다.
- **경계 — `locked_at`만 비우면 완전한 해제다**
  Given 운영자가 잠긴 계정의 `locked_at`만 비우고 `failed_login_attempts`(=10)는 그대로 두었으면
  When 그 임대인이 다시 로그인하다 비밀번호를 한 번 틀리면
  Then 카운터를 `11`로 올려 즉시 재잠금하지 않고 **`1`부터 다시 센다**(잠기지 않았는데 카운터가 이미 상한 이상인 상태 = 방금 해제된 계정으로 본다). 해제 절차가 운영자의 기억력에 기대지 않도록 코드가 절차를 완결시킨다. 이 보정이 필요한 쪽은 **예외 수단인 수동 해제뿐**이다 — 비밀번호 재설정(US-1-17)은 `locked_at`과 `failed_login_attempts`를 애초에 함께 비운다.
- **보안 — 로그인 시도 레이트리밋**
  Given 같은 IP에서 1시간에 60회 또는 같은 이메일로 1시간에 20회를 넘겨 로그인을 시도하면
  When 그 다음 요청이 오면
  Then **자격증명을 조회하기 전에** `429` + `error.code=TOO_MANY_REQUESTS`를 반환한다. 이메일 존재 여부와 무관하고, 어느 축에 걸렸는지 구분해 알리지 않는다(한도 역산 방지). 이메일 키는 소문자로 접어 세므로 대소문자를 바꿔가며 우회할 수 없다.
- **경계 — 의도적 계정 잠금(DoS)은 수용한다**
  Given 남의 이메일로 비밀번호를 10회 틀리면 그 사람의 계정을 잠글 수 있으면
  When 그런 시도가 반복되면
  Then 시도 레이트리밋(IP 60회/시간·이메일 20회/시간)이 **시간당 잠글 수 있는 계정 수를 묶되 완전히 막지는 못한다** — 이메일 한도가 잠금 임계값의 2배라 이메일 한도 안에서도 한 계정은 잠글 수 있다. 위조 가능한 IP 축과 달리 이메일 축은 우회할 수 없다는 점이 이 완화의 근거다. 잠금 정책을 채택하는 이상 피할 수 없는 고전적 부작용이며 리스크를 수용하고 진행한다.
- **보안 — refresh 쿠키 속성**
  Given 가입·로그인·재발급 응답으로 refresh 쿠키를 내릴 때
  When 쿠키를 만들면
  Then `HttpOnly`·`Secure`·`SameSite=Lax`·`Path=/api/v1/auth`·`Max-Age=1209600`(14일, 앱 refresh TTL과 동일)을 설정한다. 기본값은 안전한 쪽(`Secure`)이며 http로 도는 로컬 프로파일에서만 이를 내린다.
- **정상 — 쿠키로 재발급·로그아웃**
  Given 웹 세션의 access 토큰이 만료됐고 브라우저가 refresh 쿠키를 보유하면
  When 본문 없이 `POST /api/v1/auth/reissue`를 호출하면(쿠키 자동 첨부)
  Then 기존 회전·재사용 탐지 규칙(US-1-3)대로 새 토큰을 발급하고 **회전된 refresh를 다시 쿠키로** 내린다. 본문으로 온 요청은 기존대로 본문에 담아 돌려준다(앱 하위 호환). 쿠키를 **지우는** 자리는 둘이다 — 로그아웃(US-1-4)은 쿠키로 온 요청에, 탈퇴(US-1-4)는 성공 응답에 조건 없이 **`Max-Age=0` 삭제 쿠키**를 내린다.
- **입력 검증 실패 — refresh가 쿠키에도 본문에도 없음**
  Given `POST /api/v1/auth/reissue`·`POST /api/v1/auth/logout` 요청에 refresh 쿠키가 없고 본문 `refreshToken`도 없거나 공백이면
  When 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[].field="refreshToken"`을 반환한다(본문은 선택이 되므로 본문 부재 자체는 오류가 아니다). 본문 JSON 자체가 깨진 경우는 기존대로 `400` + `error.code=MALFORMED_REQUEST`다.
- **입력 검증 실패 — 로그인 요청**
  Given `email`이 누락·형식 위반이거나 `password`가 빈 문자열이면
  When `POST /api/v1/auth/login`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`를 반환한다(자격증명을 대조하기 전에 거른다).
- **보안 — CSRF**
  Given 웹과 API를 동일 오리진에 배치하고 refresh 쿠키가 `SameSite=Lax`이면
  When 크로스사이트 요청이 들어오면
  Then refresh 쿠키가 실리지 않는다. **별도 CSRF 토큰을 구현하지 않으며 `csrf.disable()`을 유지한다.** 다만 이 전제는 배포 형상에 달려 있다 — 웹을 다른 호스트로 배포하면 `csrf.disable()` + 쿠키 refresh가 CSRF 표면이 되므로, 배포 형상을 바꿀 때 이 결정을 함께 재검토해야 한다.

---

### US-1-13 — 가입 전 연락처(휴대폰) 인증하기 (임대인 웹 전용, 비로그인)

**As a** 아직 로그인하지 않은 웹 가입자
**I want** 내 휴대폰 번호로 SMS 인증번호를 받아 확인하기를
**So that** 본인 소유 번호임을 증명해 웹 회원가입(기존 앱 계정 연동 포함)을 진행할 수 있다.

- 우선순위: **High**
- 관련 NFR: 보안(휴대폰 소유 인증·인증번호 해시 보관·**비로그인 경로 SMS 남용 방지**), 보안(연락처·인증번호 로그 마스킹), 비용(SMS 발송비)
- 선행: 없다(비로그인 진입점). 이 인증을 통과해야 웹 회원가입(US-1-11)을 제출할 수 있다.
- 백엔드 관점: 온보딩용 연락처 인증(US-1-10)은 챌린지를 **`userId`를 키로 저장**하고 인증 필요 경로에 있어, 계정이 없는 가입 전 단계에서 재사용할 수 없다. 그래서 **정규화한 휴대폰 번호를 키로 쓰는 챌린지**를 쓰는 엔드포인트 `POST /api/v1/auth/phone/signup/verification-code`·`POST /api/v1/auth/phone/signup/verify`를 신설하고 `permitAll`로 연다. 기존 `/auth/phone/*`는 그대로 둔다(가입 먼저 → 인증 나중 대안은 이미 만든 `PENDING` 계정을 나중에 병합해야 해서 더 지저분하므로 채택하지 않는다).
  - 인증번호 정책(6자리·코드 TTL 5분·검증 마커 TTL 30분·검증 시도 상한 5회·재발송 간격 60초)과 `VerificationSmsSender` 포트·해시 보관·동기 발송 실패 정책은 US-1-10과 **동일하게 재사용**한다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)).
  - 번호는 저장·조회 전에 **숫자만 남겨 정규화**하므로 하이픈 유무와 무관하게 같은 챌린지를 가리킨다.
  - 검증 마커의 소비처는 **웹 회원가입 제출(US-1-11) 하나뿐**이라 용도 구분 필드를 두지 않으며, 가입이 성공하면 마커를 즉시 소비(삭제)한다.
  - 앱 심사용 고정 인증번호 우회는 `userId`와 Google 소셜 계정을 전제로 하므로 **이 비로그인 경로에는 적용하지 않는다**(앱 심사용 기능이라 웹 가입과 무관하다).

**AC (Given / When / Then)**

- **정상 — 인증번호 발송**
  Given 비로그인 상태에서 유효한 휴대폰 번호를 입력하고
  When `POST /api/v1/auth/phone/signup/verification-code`에 `{ phoneNumber }`를 담아 호출하면
  Then `200 OK` + `data.phoneNumber`(마스킹)·`data.expiresIn`(만료 초)을 반환하고 해당 번호로 SMS 인증번호를 발송한다. SMS 발송에 성공한 뒤에만 챌린지를 저장하며, 인증번호 원문은 저장·로그하지 않고 해시로만 보관한다.
- **정상 — 인증번호 확인**
  Given 발송된 인증번호가 유효(미만료·시도 미초과)하고
  When `POST /api/v1/auth/phone/signup/verify`에 `{ phoneNumber, code }`를 담아 호출하면
  Then `200 OK` + `data.phoneNumber`(마스킹)·`data.verified=true`를 반환하고 **정규화한 번호를 키로 검증 마커(TTL 30분)를 저장**해 웹 회원가입 제출(US-1-11)에서 대조할 수 있게 한다.
- **비즈니스 규칙 — 인증번호 불일치/만료/시도 초과**
  Given 잘못된 인증번호이거나 만료(미발송 포함)됐거나 검증 시도 상한(5회)을 넘겼으면
  When `POST /api/v1/auth/phone/signup/verify`를 호출하면
  Then `422` + `error.code=AUTH_PHONE_VERIFICATION_FAILED`를 반환하고 검증 마커를 저장하지 않는다.
- **보안 — SMS 남용 방지(비로그인 경로 필수)**
  Given 재발송 간격 60초를 지키지 않거나, **같은 번호로 1시간에 5회**를 넘겨 발송하거나, **같은 IP에서 1시간에 20회**를 넘겨 발송하면
  When `POST /api/v1/auth/phone/signup/verification-code`를 호출하면
  Then `429` + `error.code=TOO_MANY_REQUESTS`를 반환하고 SMS를 발송하지 않는다. 인증 없이 열린 경로라 문자 폭탄·발송비 남용 표면이 되므로 **번호 단위와 IP 단위 제한을 함께** 건다.
- **장애 — SMS 발송 실패**
  Given SMS provider 장애·타임아웃 등으로 인증번호 발송이 실패하면
  When `POST /api/v1/auth/phone/signup/verification-code`를 호출하면
  Then `502` + `error.code=UPSTREAM_ERROR`를 반환하고, 인증번호 챌린지를 저장하지 않아 클라이언트가 재시도하도록 유도한다(동기 발송 정책, US-1-10과 동일).
- **입력 검증 실패**
  Given `phoneNumber`가 누락·형식 위반(하이픈은 허용하고 서버가 정규화한다)이거나 확인 요청에 `code`가 빈 문자열이면
  When 가입용 연락처 인증 API를 호출하면
  Then `400` + `error.code=INVALID_INPUT`을 반환한다.
- **개인정보 — 계정 존재 여부 비노출**
  Given 이미 가입·연동 대상이 되는 번호든 가입 이력이 전혀 없는 번호든
  When 발송·확인 API를 호출하면
  Then **동일하게 발송·응답한다**. 응답만으로 그 번호의 가입 여부나 연동 대상 존재 여부를 알 수 없게 하며, 연동 판정은 가입 제출(US-1-11) 시점에만 이뤄진다.
- **인증·권한 — 토큰이 필요 없다**
  Given 이 두 엔드포인트는 `permitAll` 공개 경로이므로
  When `Authorization` 헤더 없이 호출하면
  Then 정상 처리한다. 만료된 토큰을 든 브라우저가 `401 TOKEN_EXPIRED`를 맞지 않도록 보안 설정의 `permitAll` matcher와 **공개 경로 목록 양쪽에** 등록한다.

---

### US-1-15 — 앱 임대인 온보딩에서 기존 웹 계정과 병합하기 (임대인 전용)

> **US-1-14는 결번이다** — 웹 임대인 트랙을 US-1-11·US-1-12·US-1-13·US-1-15로 확정하면서 배정하지 않은 번호이며, 누락되거나 이연된 스토리가 아니다. 이 번호는 다른 스토리에 재사용하지 않는다.

**As a** 웹에서 먼저 가입해 매물을 등록해 둔 임대인
**I want** 앱에서 소셜 로그인해 임대인 온보딩을 마치면 웹 계정과 자동으로 하나가 되기를
**So that** 웹에서 등록한 매물에 들어온 예약·(후속) 채팅을 앱에서 그대로 확인할 수 있다.

- 우선순위: **High**(이게 없으면 계정 연동이 웹→앱 한 방향으로만 성립해 절반이 비어 있다)
- 관련 NFR: 정합성(계정 병합 원자성), 보안(연락처 인증 없는 병합 차단)
- 선행: 앱 소셜 로그인(US-1-1) → 약관 동의(US-1-7) → 연락처 인증(US-1-10). 반대 방향(웹 가입 시 앱 계정을 찾아 연결)은 US-1-11이 담당한다.
- 백엔드 관점: **판정 지점은 로그인이 아니라 임대인 온보딩 제출(`POST /api/v1/auth/landlord/onboarding`, US-1-9)이다.** 소셜 로그인 시점에 서버가 아는 것은 provider·`providerUserId`·`email`·`name`뿐이고 휴대폰 번호는 모르는데, `users.phone_number`는 임대인 온보딩의 SMS 인증값으로만 채워지기 때문이다. 그 시점엔 이미 임시 `users` 행이 만들어진 뒤이므로 웹→앱 방향의 "연결"과 달리 앱→웹 방향은 **"병합"**이 된다.
  - 병합은 기존 게이트(약관 동의 → 연락처 인증)를 통과한 뒤 실행하는 분기다 — 인증된 번호로 `id <> 현재 사용자` · `status='ACTIVE'` · `user_type='LANDLORD'`인 `users`를 `SELECT ... FOR UPDATE`로 잠가 조회하고, 찾으면 `social_accounts.user_id`를 그 계정으로 이전한 뒤 임시 계정 행을 삭제하고 **이전 대상 계정 기준으로** 정식 토큰과 프로필을 반환한다. 못 찾으면 US-1-9 기존 동작 그대로다.
  - **병합 여부는 응답 필드 `linked`로 명시한다**(US-1-11 웹 가입 응답과 **같은 이름**이다 — 방향만 반대일 뿐 클라이언트에게는 둘 다 "계정이 하나로 합쳐졌다"는 한 가지 사실이라 어휘를 두 벌 익히게 하지 않는다). 이 필드가 없으면 앱은 *자기가 보낸 토큰에 박힌 `userId`* 를 꺼내 응답 `user.id`와 대조해야만 병합을 알 수 있는데, 그 비교를 빠뜨려도 화면은 정상으로 보이고 **다음 API 호출에서야** 사라진 계정의 토큰으로 깨진다. 세입자 온보딩(US-1-2)은 같은 응답 타입을 쓰지만 매칭 키인 번호를 수집하지 않아 병합 분기 자체가 없으므로 **항상 `false`** 다. 응답 필드 추가는 하위 호환이라 버전은 `/api/v1` 그대로다.
  - `status`·`user_type` 조건은 지금은 사실상 중복이다(번호가 채워진 계정은 `ACTIVE` 임대인뿐이고 UNIQUE까지 걸리면 최대 1건이다) — 그럼에도 **명시한다.** 나중에 다른 경로가 `PENDING` 계정에 번호를 채워도 병합이 오작동하지 않아야 하며, 암묵적 불변식에 기대지 않는다.
  - 임시 계정의 `users` 행은 **하드 삭제**한다. 병합이 안전한 이유는 그 계정이 방금 소셜 로그인으로 만들어져 **매물·예약·채팅이 하나도 없고 실제로 옮길 것이 `social_accounts` 행뿐**이기 때문이다. 두 계정이 같은 `user_id`로 수렴하므로 `listings`·`bookings`는 손댈 것이 없고, `chat`은 아직 영속 구현이 없어 병합 대상이 아니다. 매물 사진의 pending 업로드 키는 임대인 id를 품지만(`uploads/{landlordId}/…`) 온보딩 직후에는 진행 중 업로드가 없어 실무상 고아가 생기지 않는다.

**AC (Given / When / Then)**

- **정상 — 병합**
  Given 웹에서 가입해 연락처 `01012345678`을 보유한 `ACTIVE`·`LANDLORD` 계정(id=42)이 있고, 같은 사람이 앱에서 소셜 로그인해 임시 계정(id=99, `TERMS_AGREED`)이 만들어졌으며 그 번호로 연락처 인증(US-1-10)을 통과했고
  When `POST /api/v1/auth/landlord/onboarding`을 호출하면
  Then `200 OK` + **id=42 기준의 임대인 프로필과 정식 `accessToken`/`refreshToken`**을 반환하고, `social_accounts`의 `user_id`가 42로 이전되며 임시 계정 99의 `users` 행은 삭제된다. 이후 앱 소셜 로그인은 항상 42로 귀결되어 웹에서 등록한 매물의 예약(US-4-6)이 앱에서 조회된다.
- **정상 — 응답이 병합 사실을 알린다**
  Given 위 병합이 수행되면
  When 클라이언트가 응답을 받으면
  Then 응답 `data.linked=true`로 **병합됐음을 명시**한다 — 앱이 `data.user.id`를 자기가 보낸 토큰의 `userId`와 비교해 **추론하게 두지 않는다**. 그 비교를 빠뜨리면 앱은 낡은 토큰을 계속 쓰다 다음 호출에서 깨지고, 방금 입력한 값 대신 살아남은 계정의 이름·이메일이 표시되는 이유도 설명할 수 없다. 앱은 이 플래그를 보고 ① 저장 토큰을 응답 값으로 교체하고 ② "기존 웹 계정과 연결되었습니다"를 안내한다. 필드명은 웹 가입(US-1-11)의 `linked`와 **같다** — 같은 개념에 두 단어를 두지 않는다. 응답 필드 추가는 하위 호환이라 **`/api/v1`을 유지**한다.
- **정상 — 병합 대상이 없는 일반 온보딩**
  Given 인증한 번호를 가진 다른 계정이 없으면
  When 임대인 온보딩을 제출하면
  Then **기존 동작 그대로** 자기 계정을 `ACTIVE`로 전이시키고 응답 `data.linked=false`를 반환한다(US-1-9는 변경되지 않는다). 세입자 온보딩(US-1-2)은 같은 응답 타입을 쓰지만 병합 매칭 키인 휴대폰 번호를 수집하지 않아 분기 자체가 없으므로 **언제나 `false`** 다.
- **보안 — 연락처 인증 없는 병합 차단**
  Given 제출 `phoneNumber`에 유효한 검증 마커가 없거나 인증한 번호와 다르면
  When 온보딩을 제출하면
  Then 기존 게이트대로 `422` + `error.code=AUTH_PHONE_NOT_VERIFIED`로 거절하고 **병합도 수행하지 않는다**. 번호만 알면 남의 웹 계정을 흡수할 수 있는 경로를 만들지 않는다.
- **정합성 — 병합 원자성(DB 쓰기 한정)**
  Given 병합 도중 어느 단계가 실패하면
  When 온보딩을 제출하면
  Then **DB 쓰기 전체를 롤백**한다 — 매핑 이전과 임시 행 삭제는 한 트랜잭션이다. `social_accounts`가 어느 계정에도 붙지 않은 상태를 남기면 앱 로그인이 영구히 깨진다.
  **And** 그 보장은 **토큰 발급까지 덮지 않는다** — 아래 「알려진 제약」 참조.
- **경계 — 옮기는 `social_accounts` 행 수를 가정하지 않는다**
  Given 임시 계정은 방금 소셜 로그인으로 생성돼 `social_accounts`가 보통 정확히 1행이면
  When `social_accounts.user_id`를 대상 계정으로 이전하면
  Then 코드가 "1행"을 가정하지 않는다 — UPDATE는 N행이어도 안전하며 **영향 행 수를 단언하지 않는다**. **대상 계정 쪽은 여러 행이 될 수 있고 그게 정상이다** — 같은 사람이 Google로 병합한 뒤 Apple로도 앱 로그인해 다시 병합하면 한 계정에 `social_accounts` 2행이 붙으며, `(provider, providerUserId)` UNIQUE는 값이 달라 위반되지 않는다.
- **경계 — 임시 계정의 진단 기록은 삭제하지 않는다**
  Given 병합 대상 임시 계정이 진단 기록을 보유한 채로(진단 v2는 `permitAll`이라 온보딩 스코프 토큰으로도 생성될 수 있다)
  When 병합이 수행되면
  Then 병합은 **`users` 행만 삭제하고 진단 문서는 손대지 않는다**. 현재 탈퇴조차 진단을 지우지 않으므로 병합이 탈퇴보다 공격적으로 지우는 비대칭을 만들지 않는다. 그 결과 **사라진 계정을 가리키는 진단 문서가 남을 수 있으나** 조회 주체가 없어 실질 영향이 없는 알려진 제약으로 수용한다.
- **경계 — 양쪽 모두 완주한 계정은 자동 병합하지 않는다**
  Given 앱 계정이 이미 `ACTIVE`·`LANDLORD`이고 웹에도 같은 번호의 별도 계정이 존재하면(앱 온보딩 완료 후 웹에서 US-1-11 매칭이 실패한 이력 등)
  When 그 사용자가 앱을 계속 사용하면
  Then 온보딩 경로를 다시 타지 않아 **자동 병합 트리거가 없다**. 양쪽 모두 매물·예약을 보유했을 수 있어 데이터 이관 판단이 필요하므로 **운영 수동 처리 대상**으로 남기며, 이번 범위에서 병합 코드도 "계정 연결" 화면도 만들지 않는다.
- **경계·동시성 — 같은 번호의 동시 온보딩·가입**
  Given 같은 번호에 대해 두 건의 제출(앱 온보딩 두 기기, 또는 앱 온보딩과 웹 가입)이 거의 동시에 도착하면
  When 양쪽이 대상 계정을 조회하면
  Then 대상 `users` 행을 `SELECT ... FOR UPDATE`로 잠가 병합을 직렬화하고, 대상 계정이 아직 없는 경우는 `users.phone_number` UNIQUE 제약이 두 번째 확정을 실패시킨다. 실패한 쪽은 재시도하면 상대가 만든 계정을 발견해 정상 병합·연동된다.
  **And** 그 제약 위반은 `500`이 아니라 **`409` + `error.code=RESOURCE_CONFLICT`** 로 내려간다 — 재시도하면 성공하는 상황이라 클라이언트가 그 신호를 받아야 하고, `500`은 어느 스펙 에러 카탈로그에도 없는 status다. 번역은 전역 예외 핸들러가 맡는다: 임대인 온보딩의 번호 기록은 **기존 행 UPDATE**라 위반이 커밋 시점(트랜잭션 프록시 반환 이후)에야 드러나므로 서비스 안의 `try/catch`로는 잡히지 않는다. 코드가 `AUTH_*`가 아닌 이유는 그 자리가 모듈 밖이기 때문이다.
  **And** 번역 대상은 **문서화된 UNIQUE 제약**(`uq_users_phone_number` · `uq_local_accounts_email` · `uq_local_accounts_user_id`)의 중복 위반뿐이다 — 같은 예외 타입으로 오는 `NOT NULL` 위반·길이 초과는 재시도가 무의미한 서버 버그라 종전대로 **ERROR 로그 + `500`** 이다. 전부 409로 낮추면 클라이언트에 거짓 재시도 신호를 주고 로그 레벨이 WARN으로 떨어져 알림이 사라진다([error-response-guide §4](../api/error-response-guide.md)).
- **알려진 제약 — 롤백돼도 refresh 해시는 Redis에 남는다**
  Given 위 경합처럼 커밋 시점에 UNIQUE 위반이 나면
  When 트랜잭션이 롤백돼 `409`가 나가면
  Then **MySQL 쓰기만** 되돌아간다 — 토큰은 이미 발급된 뒤이고 refresh 해시는 Redis에 있어 롤백 대상이 아니므로, **쓰이지 않을 해시 하나가 14일 TTL로 남는다**. 원문은 응답으로 전달되지 않아 세션을 열 수 없고(악용 불가) 항목은 스스로 만료되지만, 그때까지 `refresh:user:{id}` 인덱스가 실제 세션 수보다 많아 보인다. 웹 회원가입(US-1-11)도 같은 모양이며, 토큰 발급을 트랜잭션 밖으로 들어내는 재구성은 문제 크기에 비해 변경이 커 하지 않는다(수용).

---

### US-1-16 — 가입한 이메일 찾기 (임대인 웹 전용, 비로그인)

**As a** 웹 로그인 ID로 쓰던 이메일을 잊은 임대인
**I want** 가입할 때 인증한 휴대폰 번호로 SMS 인증을 다시 통과한 뒤 가입 시 입력한 이름을 대조해 내 계정의 이메일을 확인하기를
**So that** 로그인(US-1-12)과 비밀번호 재설정(US-1-17)의 진입값인 이메일을 되찾아 웹으로 돌아올 수 있다.

- 우선순위: **High**(재설정 요청이 받는 값은 이메일 하나뿐이라, 이메일을 잊으면 US-1-17로도 복구가 시작되지 않는다)
- 관련 NFR: 보안(휴대폰 소유 인증 선행·인증번호 해시 보관·**비로그인 경로 SMS 남용 방지**·계정 열거 표면 최소화), 보안(연락처·이름·이메일 로그 마스킹), 비용(SMS 발송비)
- 선행: 없다(비로그인 진입점). 다만 조회 자체는 이 스토리 안의 SMS 인증(발송 → 확인)을 먼저 통과해야 한다. 계정 복구 트랙(US-1-16·US-1-17)은 이번 범위에서 `local`·`dev`에만 연다(prod 미배포).
- 백엔드 관점: `POST /api/v1/auth/phone/find-email/verification-code` · `POST /api/v1/auth/phone/find-email/verify` · `POST /api/v1/auth/email/find` 셋을 신설하고 전부 `permitAll`로 연다. 인증번호 정책(6자리·코드 TTL 5분·검증 마커 TTL 30분·검증 시도 상한 5회·재발송 간격 60초)과 `VerificationSmsSender` 포트·해시 보관·동기 발송 실패 정책은 US-1-10·US-1-13과 **동일하게 재사용**한다([ADR-0034](../adr/0034-landlord-phone-sms-verification.md)).
  - **가입용 챌린지(US-1-13)를 재사용하지 않고 경로와 Redis 버킷을 통째로 분리한다.** US-1-13은 "마커 소비처가 웹 가입 하나뿐이라 용도 구분 필드를 두지 않는다"는 결정 위에 서 있어, 같은 버킷을 공유하는 순간 **이메일 찾기용으로 받은 마커가 회원가입을 통과**시키거나 그 반대가 된다. 레이트리밋 버킷도 함께 나눈다 — 합치면 이메일을 몇 번 찾아본 사람이 정작 가입 SMS를 받지 못한다.
  - **이름 대조 대상은 `local_accounts.name` 단독이며 `users.name` 폴백을 두지 않는다.** 화면이 묻는 것은 "가입할 때 입력한 이름"이고 그 값이 정확히 `local_accounts.name`이다(연동 가입은 `users`를 갱신하지 않아 둘이 다를 수 있다 — US-1-11). 폴백을 두면 앱 소셜 표기(`Kim Imdae`)로도 통과해 화면 문구와 서버 판정이 어긋나고, 대조가 헐거워지는 만큼 남의 번호를 손에 넣은 사람의 통과 확률만 올려 준다.
  - **여기서는 계정 존재를 드러낸다(404).** US-1-13이 가입 여부를 끝까지 숨기는 것과 반대로 보이지만 위치가 다르다 — 이 조회는 SMS 검증 마커 **뒤**에 있어 호출자는 **소유를 증명한 자기 번호**로만 물을 수 있고, 자기 번호의 가입 여부는 열거할 수 있는 정보가 아니다. 열거 표면은 게이트가 닫고 있으므로 응답까지 흐리게 만들 이유가 없다.
  - **이름 불일치와 계정 미존재는 같은 404로 수렴시킨다.** 갈라 놓으면 "계정은 있는데 이름이 틀렸다"는 응답 자체가 이름 추측 오라클이 되어, 번호만 쥔 사람이 이름을 좁혀 갈 수 있다.
  - 조회에 성공하면 마커를 **소비(삭제)** 한다 — 마커의 목적은 조회 한 번을 여는 것이지 30분짜리 조회 패스를 주는 것이 아니다.

**AC (Given / When / Then)**

- **정상 — 인증번호 발송**
  Given 비로그인 상태에서 유효한 휴대폰 번호를 입력하고
  When `POST /api/v1/auth/phone/find-email/verification-code`에 `{ phoneNumber }`를 담아 호출하면
  Then `200 OK` + `data.phoneNumber`(마스킹 `010-****-5678`)·`data.expiresIn=300`을 반환하고 그 번호로 SMS 인증번호를 발송한다. 발송에 성공한 뒤에만 챌린지를 저장하며, 인증번호 원문은 저장·로그하지 않고 해시로만 보관한다.
- **개인정보 — 발송·확인 단계는 가입 여부를 노출하지 않는다**
  Given 웹 계정이 있는 번호든 가입 이력이 전혀 없는 번호든
  When 발송·확인 API를 호출하면
  Then **동일하게 발송하고 동일하게 응답한다**. 가입 여부 판정은 마지막 조회(`POST /api/v1/auth/email/find`) 한 곳에서만 일어난다 — 앞단에서 갈라 주면 인증번호를 받아 보지도 않고 번호만 넣어 가입 여부를 훑을 수 있다.
- **정상 — 인증번호 확인(검증 마커 생성)**
  Given 발송된 인증번호가 유효(미만료·시도 미초과)하고
  When `POST /api/v1/auth/phone/find-email/verify`에 `{ phoneNumber, code }`를 담아 호출하면
  Then `200 OK` + `data.phoneNumber`(마스킹)·`data.verified=true`를 반환하고 **정규화한 번호를 키로 이메일 찾기 전용 검증 마커(TTL 30분)** 를 저장한다. 가입용 마커(US-1-13)와 **다른 버킷**이라 서로의 게이트를 통과시키지 않는다.
- **비즈니스 규칙 — 인증번호 불일치/만료/시도 초과**
  Given 잘못된 인증번호이거나 만료(미발송 포함)됐거나 검증 시도 상한(5회)을 넘겼으면
  When `POST /api/v1/auth/phone/find-email/verify`를 호출하면
  Then 사유를 가르지 않고 **한 코드로** `422` + `error.code=AUTH_PHONE_VERIFICATION_FAILED`를 반환하고 마커를 저장하지 않는다(사유별로 나누면 챌린지 상태가 그대로 오라클이 된다 — US-1-13과 같은 규칙·같은 코드).
- **정상 — 이메일 찾기**
  Given 그 번호로 유효한 이메일 찾기 검증 마커가 있고, 제출한 `name`이 그 번호로 찾은 웹 계정의 `local_accounts.name`과 일치하면
  When `POST /api/v1/auth/email/find`에 `{ phoneNumber, name }`을 담아 호출하면
  Then `200 OK` + `data.email`을 **마스킹해**(`ki***@work.com`) 반환하고 검증 마커를 소비한다.
- **보안 — SMS 인증 없는 조회 차단(필수 게이트)**
  Given 제출한 `phoneNumber`에 유효한 이메일 찾기 검증 마커가 없거나 만료됐으면
  When `POST /api/v1/auth/email/find`를 호출하면
  Then `422` + `error.code=AUTH_PHONE_NOT_VERIFIED`를 반환하고 **계정을 조회하지 않는다**. 번호와 이름을 아는 것만으로는 남의 가입 이메일이 나오지 않으며, 번호 소유 증명이 이 경로의 유일한 방벽이다.
- **보안 — 이름 불일치와 계정 미존재는 같은 404다**
  Given 그 번호로 찾을 웹 계정이 없거나(미가입·앱 소셜 전용 계정), 계정은 있으나 제출한 `name`이 `local_accounts.name`과 다르면
  When `POST /api/v1/auth/email/find`를 호출하면
  Then 두 경우 **모두 같은** `404` + `error.code=AUTH_WEB_ACCOUNT_NOT_FOUND`를 반환한다. 구분해 알리면 "계정은 있다"가 이름 추측의 출발점이 된다. 대조는 `local_accounts.name` 단독이며 `users.name`으로 폴백하지 않는다.
- **보안 — 검증 마커는 1회용이다**
  Given 조회가 성공해 마커가 소비됐으면
  When 같은 번호로 다시 `POST /api/v1/auth/email/find`를 호출하면
  Then `422` + `error.code=AUTH_PHONE_NOT_VERIFIED`다. 다시 조회하려면 SMS 인증부터 다시 통과해야 하고, 그 지점에는 발송 레이트리밋이 걸려 있다.
- **알려진 제약 — 실패한 조회는 마커를 소비하지 않는다**
  Given 이름이 틀린 요청은 `404`만 내고 마커를 남겨 두므로
  When 같은 마커로 이름을 바꿔 다시 시도하면
  Then 마커 TTL(30분) 안에서는 **이름 재시도가 열려 있다**. 오타 하나에 SMS부터 다시 받게 하지 않으려는 의도적 선택이며, 시도할 수 있는 사람이 **번호 소유를 이미 증명한 사람**뿐이라 표면이 자기 번호 하나로 닫혀 있어 수용한다.
- **개인정보 — 응답 이메일은 마스킹한다**
  Given 번호 소유와 이름 대조를 모두 통과했더라도
  When 응답을 만들면
  Then 이메일은 `ki***@work.com`처럼 **마스킹해서만** 내보낸다. 이 화면의 목적은 "어느 주소로 가입했는지 떠올리게 하는 것"이고, 번호를 물려받은 사람(번호 재활용·중고폰)에게 전 주인의 주소를 통째로 넘기지 않는다. 로그인·재설정 입력은 어차피 본인이 전체 주소를 직접 친다.
- **보안 — SMS 남용 방지(비로그인 경로 필수)**
  Given 재발송 간격 60초를 지키지 않거나, **같은 번호로 1시간에 5회** 또는 **같은 IP에서 1시간에 20회**를 넘겨 발송하면
  When `POST /api/v1/auth/phone/find-email/verification-code`를 호출하면
  Then `429` + `error.code=TOO_MANY_REQUESTS`를 반환하고 SMS를 발송하지 않는다. 인증 없이 열린 경로라 문자 폭탄·발송비 남용 표면이 되므로 **번호 단위와 IP 단위 제한을 함께** 걸며, 카운터는 가입용(US-1-13) 버킷과 **분리**한다.
- **장애 — SMS 발송 실패**
  Given SMS provider 장애·타임아웃 등으로 인증번호 발송이 실패하면
  When `POST /api/v1/auth/phone/find-email/verification-code`를 호출하면
  Then `502` + `error.code=UPSTREAM_ERROR`를 반환하고 챌린지를 저장하지 않아 클라이언트가 재시도하도록 유도한다(동기 발송 정책, US-1-10·US-1-13과 동일).
- **입력 검증 실패**
  Given `phoneNumber` 누락·형식 위반(하이픈은 허용하고 서버가 정규화한다), `code`가 6자리 숫자가 아님, `name` 누락·공백·200자 초과 중 하나면
  When 해당 API를 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`(field/reason)를 반환한다. 본문 JSON 자체가 깨진 경우는 `400` + `error.code=MALFORMED_REQUEST`다.
- **인증·권한 — 토큰이 필요 없다**
  Given 세 엔드포인트 모두 `permitAll` 공개 경로이므로
  When `Authorization` 헤더 없이 호출하면
  Then 정상 처리한다. 보안 설정의 `permitAll` matcher와 **공개 경로 목록 양쪽에** 등록한다 — 한쪽만 넣으면 만료된 토큰이 남은 브라우저에서만 `401 TOKEN_EXPIRED`가 나고, 토큰 없이 부르는 로컬·테스트는 전부 초록이라 잡히지 않는다.

---

### US-1-17 — 비밀번호 재설정으로 로그인 복구하기 (임대인 웹 전용, 비로그인)

**As a** 비밀번호를 잊었거나 10회 실패로 계정이 잠긴 임대인
**I want** 가입한 이메일로 받은 링크에서 새 비밀번호를 정하기를
**So that** 운영자에게 문의하지 않고 스스로 로그인을 되찾는다(**계정 잠금 해제도 이 경로 하나로 끝난다**).

- 우선순위: **High**(US-1-12의 잠금에는 자력 해제 수단이 없어, 이게 없으면 10회 틀린 임대인은 운영자가 DB를 고쳐 줄 때까지 웹에 들어올 수 없다)
- 관련 NFR: 보안(재설정 토큰 해시 보관·1회용·짧은 TTL·기존 세션 무효화·링크 조립 시 호스트 헤더 불신), 보안(이메일·토큰·비밀번호 로그 마스킹), 가용성(동기 메일 발송 타임아웃)
- 선행: 없다(비로그인 진입점). 웹 계정(US-1-11)이 있는 이메일만 실제로 메일을 받는다. 이메일 자체를 잊었으면 US-1-16이 앞에 온다. 기능은 토글 `app.auth.web.password-reset.enabled`로 켜며 base 기본값이 `false`라 **prod에는 배포되지 않는다**(`local`·`dev`만 `true`).
- 백엔드 관점: `POST /api/v1/auth/password/reset-link` · `POST /api/v1/auth/password/reset-token/verify` · `POST /api/v1/auth/password/reset` 셋을 신설하고 전부 `permitAll`로 연다.
  - **"비밀번호 찾기"와 "계정 잠금 해제"는 같은 API다** — 화면만 둘이고 독립 잠금 해제 엔드포인트를 만들지 않는다. 잠긴 계정의 비밀번호는 이미 10회 추측당한 뒤라 신뢰할 수 없는 값이고, **잠금만 풀어 주면 같은 비밀번호로 같은 공격 창을 다시 여는 것**이다. 두 화면이 필요로 하는 조치가 하나로 수렴하는데 엔드포인트를 나누면 "잠금은 풀렸고 비밀번호는 그대로"라는, 아무도 원하지 않는 상태가 하나 더 생긴다.
  - **재설정 요청은 이름을 받지 않는다 — 이메일 하나다.** 소유 증명은 전적으로 **메일 수신함**이 하므로 이름을 더 받아도 보안이 늘지 않고, 앱 소셜 표기와 웹 입력 이름이 다른 US-1-11의 문제를 복구 경로에 다시 끌어들일 뿐이다(이름 대조가 필요한 곳은 수신함을 쓸 수 없는 US-1-16뿐이다).
  - 링크는 `{app.web.base-url}/reset-password?token=xyz` 형태이고 도착 지점은 프런트 SPA 페이지다. **base URL은 설정값이며 요청 `Host`·`X-Forwarded-Host`로 조립하지 않는다** — 헤더를 갈아 끼우면 공격자 도메인이 박힌 링크가 진짜 계정 주인에게 발송되고, 그가 클릭하는 순간 토큰이 넘어간다(호스트 헤더 포이즈닝은 곧 계정 탈취다).
  - 토큰은 `SecureRandom` 32바이트를 Base64URL로 인코딩한 **불투명 문자열**(`pr_` 접두 — refresh의 `rt_` 선례와 같은 모양)이며, 서버에는 `SHA-256(토큰 + pepper)`만 Redis에 TTL 30분으로 남긴다. 원문을 저장하지 않으므로 저장소가 통째로 유출돼도 링크를 재구성할 수 없다. pepper는 기존 `app.auth.email-pepper`를 재사용해 새 시크릿 배선을 늘리지 않는다.
  - 토큰 소비는 **원자 연산**이어야 한다(`GETDEL`). `조회 → 검증 → 삭제`로 쪼개면 같은 링크를 동시에 두 번 눌렀을 때 둘 다 통과하는 창이 남는다.
  - 확정 처리 순서는 **토큰 원자 소비 → 비밀번호 교체(MySQL) → refresh 전량 무효화(Redis) → 로그인 시도 카운터 삭제(Redis)** 로 고정한다. MySQL과 Redis에 걸친 원자성은 불가능하므로, 중간에 죽었을 때 남는 상태가 **"토큰만 소비됐다(메일을 다시 받으면 된다)"** 가 되도록 순서를 잡는다.
  - 메일은 US-1-6과 같은 SMTP 어댑터로 **동기 발송**하며, 이번에 `spring.mail`의 connection·read·write 타임아웃을 처음으로 설정한다 — 지금은 전부 미설정이라 **무한 대기**이고, 선행 게이트가 없는 `permitAll` 경로에서 스레드가 물리면 그대로 가용성 사고가 된다.

**AC (Given / When / Then)**

- **정상 — 재설정 링크 발송**
  Given 가입된 이메일을 입력하고
  When `POST /api/v1/auth/password/reset-link`에 `{ email }`을 담아 호출하면
  Then `200 OK` + `data.expiresIn=1800`을 반환하고 그 주소로 재설정 링크를 발송한다. 토큰 원문은 링크에만 실리고 서버에는 해시로만 남는다.
- **개인정보 — 미가입 이메일도 동일한 200이다**
  Given 가입 이력이 없는 이메일이면
  When `POST /api/v1/auth/password/reset-link`를 호출하면
  Then 가입된 이메일과 **완전히 같은** `200 OK` + `data.expiresIn=1800`을 반환하고 **메일만 보내지 않는다**. 이 경로에는 선행 게이트가 없어 임의의 이메일로 부를 수 있으므로, 응답을 가르는 순간 완전한 계정 열거 오라클이 된다(US-1-16이 `404`를 낼 수 있는 것은 SMS 마커 뒤라서이지 여기와 같은 자리가 아니다).
- **알려진 제약 — 응답 시간·status 분포로 존재가 드러난다**
  Given 발송이 동기라 가입 계정은 SMTP 왕복 시간이 들고 발송 실패 시 `502`가 날 수 있는 반면 미가입 이메일은 즉시 `200`이면
  When 같은 이메일로 여러 번 재 보면
  Then **응답 시간과 status 분포로 가입 여부가 드러난다**. 본문을 같게 맞추는 것만으로 타이밍 채널까지 닫히지는 않으며, 레이트리밋은 이 채널의 완화책이 아니라 발송비·메일 폭탄 방어다. 비동기 발송으로 옮기는 재구성은 이번 범위에서 하지 않고 **받아들인다**.
- **정상 — 링크 도착 후 사전 확인(토큰을 소비하지 않는다)**
  Given 메일의 링크로 재설정 페이지가 열렸고
  When `POST /api/v1/auth/password/reset-token/verify`에 `{ token }`을 담아 호출하면
  Then `200 OK` + `data.email`(마스킹)·`data.expiresIn`(고정값이 아니라 **남은 초**)을 반환하고 **토큰을 소비하지 않는다**. 메일 클라이언트·보안 스캐너가 링크를 미리 여는 일이 흔해, 여기서 소비하면 사용자가 클릭하기도 전에 링크가 죽는다.
- **정상 — 재설정 확정**
  Given 유효한 토큰과 정책을 만족하는 새 비밀번호가 있고
  When `POST /api/v1/auth/password/reset`에 `{ token, newPassword }`를 담아 호출하면
  Then **`204 No Content`**(본문 없음)를 반환하고, 한 요청 안에서 ① 토큰 소비 ② `password_hash` 교체(BCrypt) ③ `failed_login_attempts=0`·`locked_at` 비우기(**잠금 해제**) ④ 그 사용자의 refresh 토큰 전량 무효화 ⑤ 로그인 시도 레이트리밋 카운터 삭제까지 마친다.
- **정상 — 잠긴 계정도 이 경로로 복구된다**
  Given 비밀번호를 10회 틀려 `423` + `AUTH_ACCOUNT_LOCKED`를 받고 있는 계정이면
  When 그 이메일로 링크를 받아 재설정을 확정하면
  Then 잠금이 함께 풀려 **새 비밀번호로 곧바로 로그인된다**. 잠긴 상태여도 링크 발송·사전 확인·확정 어느 단계도 막지 않는다 — 잠금은 로그인 시도를 막는 장치이지 복구를 막는 장치가 아니다.
- **보안 — 로그인 시도 카운터까지 함께 지운다**
  Given 잠금까지 간 계정은 그 과정에서 이메일당 시도 한도(20회/시간, US-1-12)에 근접·도달해 있을 수 있으면
  When 재설정을 확정하면
  Then 그 이메일의 로그인 시도 카운터(`web-login:rate:email:{소문자 이메일}`)도 함께 삭제한다. 이걸 빠뜨리면 잠금은 풀렸는데 다음 로그인이 `429`로 막혀 **사용자 눈에는 아무것도 복구되지 않은 것과 같다**(에러 코드가 `423`에서 `429`로 바뀔 뿐이다). IP 축 카운터는 사용자 단위가 아니므로 지우지 않는다.
- **보안 — 기존 세션을 전부 끊는다**
  Given 비밀번호를 재설정하는 흔한 이유가 탈취 의심이면
  When 재설정을 확정하면
  Then 그 사용자의 refresh 토큰을 **전량 무효화**한다. 새 비밀번호를 세워도 공격자가 쥔 refresh가 14일 남아 있으면 세션이 그대로 살아 있어 복구가 아니다.
- **보안 — 새 세션을 발급하지 않는다**
  Given 재설정을 성공적으로 마쳐도
  When `204`를 응답하면
  Then **access 토큰도 refresh 쿠키도 내리지 않고** 로그인 화면으로 보낸다. 세션 발급 지점을 로그인 하나로 유지해 쿠키를 심는 경로를 늘리지 않으며, 링크를 열어 본 브라우저가 그대로 로그인 상태가 되는 일을 막는다. 사용자가 새 비밀번호로 실제 로그인되는지 그 자리에서 확인하게 되는 부수 효과도 있다.
- **보안 — 토큰은 1회용이고 30분이다**
  Given 이미 사용한 링크를 다시 누르거나 발급 후 30분이 지났으면
  When 사전 확인·확정 어느 쪽을 호출하든
  Then `422` + `error.code=AUTH_PASSWORD_RESET_TOKEN_INVALID`다. 확정이 성공한 순간 토큰은 원자적으로 소비되므로, 같은 링크가 두 번 통과하지 않는다.
- **비즈니스 규칙 — 토큰 부재·만료·사용됨은 같은 코드다**
  Given 토큰이 존재하지 않거나 위조됐거나 만료됐거나 이미 소비됐으면
  When 사전 확인·확정 어느 쪽을 호출하든
  Then 사유를 가르지 않고 `422` + `error.code=AUTH_PASSWORD_RESET_TOKEN_INVALID` 하나로 응답한다. 사유를 나누면 "이 토큰은 있었는데 만료됐다"가 곧 발급 이력 오라클이 된다.
- **입력 검증 — 새 비밀번호 정책은 가입과 같다**
  Given `newPassword`가 영문자 1자 이상 · 숫자 1자 이상 · **ASCII 특수문자** 1자 이상과 **길이 8~20자**를 만족하지 않거나 공백 등 허용 집합 밖 문자를 포함하면
  When `POST /api/v1/auth/password/reset`을 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[].field="newPassword"`를 반환한다. 가입(US-1-11)과 **같은 정책·같은 패턴 상수**를 쓴다 — 복구 경로만 규칙이 느슨하면 정책 전체가 느슨한 쪽으로 무너진다. 검증은 요청 DTO의 Bean Validation(`@Pattern`)이라 **토큰에 손대기 전에** 걸러지고, 오타 한 번으로 링크가 죽지 않는다.
- **보안 — 링크 발송 레이트리밋**
  Given 같은 이메일로 1시간에 5회 또는 같은 IP에서 1시간에 20회를 넘겨 링크를 요청하면
  When `POST /api/v1/auth/password/reset-link`를 호출하면
  Then `429` + `error.code=TOO_MANY_REQUESTS`를 반환하고 메일을 보내지 않는다. **가입 여부와 무관하게 세며**(미가입 이메일도 카운트한다 — 여기서 갈리면 위 열거 방어가 그대로 무너진다), 웹 로그인·가입 SMS 버킷과는 다른 버킷을 쓴다. 남의 수신함에 메일을 퍼붓는 남용과 발송비가 이 제한의 목적이다.
- **장애 — 메일 발송 실패**
  Given 메일 provider 장애·타임아웃 등으로 발송이 실패하면
  When `POST /api/v1/auth/password/reset-link`를 호출하면
  Then `502` + `error.code=UPSTREAM_ERROR`를 반환하고 토큰을 저장하지 않아 재시도를 유도한다(동기 발송 정책, US-1-6과 동일). SMTP 타임아웃을 명시하지 않으면 이 실패가 `502`가 아니라 **무한 대기**로 나타난다.
- **정합성 — 중간 실패 시 남는 상태**
  Given 확정 처리 중 어느 단계가 실패하면
  When 재설정을 확정하려 하면
  Then 고정된 순서(토큰 소비 → 비밀번호 교체 → refresh 무효화 → 시도 카운터 삭제) 덕분에 남는 상태는 **"토큰만 소비됐다"**(링크를 다시 받으면 된다)이거나 "비밀번호는 바뀌었으나 옛 세션·카운터가 남았다"가 된다. MySQL·Redis에 걸친 원자성은 없으므로 **순서 자체가 계약**이다 — 토큰 소비를 뒤로 미루면 실패한 요청이 살아 있는 토큰을 남겨 재사용 창이 생긴다.
- **입력 검증 실패(그 외)**
  Given `email` 누락·형식 위반·255자 초과이거나 `token`·`newPassword`가 누락·공백이면
  When 재설정 API를 호출하면
  Then `400` + `error.code=INVALID_INPUT` + `errors[]`를 반환한다. 본문 JSON 자체가 깨진 경우는 `400` + `error.code=MALFORMED_REQUEST`다.
- **인증·권한 — 토큰이 필요 없다**
  Given 세 엔드포인트 모두 `permitAll` 공개 경로이므로
  When `Authorization` 헤더 없이 호출하면
  Then 정상 처리한다. 보안 설정의 `permitAll` matcher와 **공개 경로 목록 양쪽에** 등록한다(한쪽만 넣으면 만료된 토큰이 남은 브라우저에서만 `401 TOKEN_EXPIRED`가 난다).
- **범위 — prod에는 배포하지 않는다**
  Given 토글 `app.auth.web.password-reset.enabled`의 base 기본값이 `false`이면
  When prod 프로파일로 기동하면
  Then 이 경로들은 열리지 않으며, 잠긴 계정의 해제는 그동안 운영자 수동 처리로 남는다(US-1-12). 토글이 켜진 프로파일(`local`·`dev`)에서만 `app.web.base-url` 형식을 **기동 시** 검증한다 — 값이 비어 있으면 링크가 조립되지 않아 기능이 조용히 무의미해지므로, 첫 사용자가 깨진 링크를 받기 전에 기동에서 실패시킨다.

## 2. 맞춤 진단 & 매물 추천

> 관련 API 스펙: [02-diagnosis-recommendation](../api/specs/02-diagnosis-recommendation.md)

외국인 사용자가 6단계 진단(① 지역 / ② 입국 목적(유학 여부) / ③ 대학 그룹·지역 선택 / ④ 주거 환경 조건 / ⑤ 월세 범위(최소-최대) / ⑥ ARC 발급 여부)에 답하면, 서버는 조건에 맞는 매물 리스트와 지도용 좌표를 추천한다. 진단 문항과 선택지는 앱이 하드코딩하지 않고 백엔드가 제공하며, 사용자 표시 언어로 번역되어 내려간다(US-2-5·US-2-6). 진단은 제출 시 1건의 진단 레코드로 영속화되며, 사용자는 자신의 진단 이력·완료 여부를 조회하고 재진단(새 진단 생성)할 수 있다.

- 진단 입력은 서버에서 다시 검증한다(클라이언트 검증을 신뢰하지 않는다): `region` 1택, `purpose` 1택(필수, 단일 enum `Purpose`: `STUDY`|`NON_STUDY`), **입국 목적별 대학 그룹·지역 선택**(두 필드로 분리한다 — `university`(필드 키는 `university` 유지, 타입은 6 그룹 enum `UniversityGroup`: `HUFS_KHU_KOREA`·`SKKU_SUNGSHIN`·`SNU_CAU_SOONGSIL`·`HONGIK_YONSEI_EWHA`·`KONKUK_SEJONG_HYU`·`ETC`; 단일 선택. 각 그룹은 개별 대학 코드로 멤버십을 갖는다 — `HUFS_KHU_KOREA`→{`HUFS`,`KHU`,`KOREA`}, `SKKU_SUNGSHIN`→{`SKKU`,`SUNGSHIN`}, `SNU_CAU_SOONGSIL`→{`SNU`,`CAU`,`SOONGSIL`}, `HONGIK_YONSEI_EWHA`→{`HONGIK`,`YONSEI`,`EWHA`}, `KONKUK_SEJONG_HYU`→{`KONKUK`,`SEJONG`,`HYU`}, `ETC`→{}(펼칠 멤버 없음, 대신 목록 14곳 전체를 제외 조건으로 넘겨 여집합(`$nin`) 매칭 — 목록에 든 대학 근처 매물은 빠진다). 멤버 개별 대학 코드는 매물의 `nearbyUniversityCodes` 저장값과 동일하다 — 매물 저장은 바뀌지 않는다.), `district`(enum `District`: `GURO_GU`·`YEONGDEUNGPO_GU`·`GEUMCHEON_GU`·`GWANAK_GU`·`DONGDAEMUN_GU`·`ETC`); 조건부 필수 — 입국 목적이 `STUDY`면 `university` 필수·`district` 없음, `NON_STUDY`면 `district` 필수·`university` 없음. 위반은 공통 `INVALID_INPUT`(400)+`errors[]`로 표현. 결정 근거는 [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)), `conditions`(enum `DiagnosisCondition`, listing `ConditionTag` 이름 통일: `MOVE_IN_NOW`·`FEMALE_ONLY`·`PRIVATE_BATH`·`ENGLISH_OK`·`ADDRESS_REGISTRATION`·`NO_MAINT_FEE`·`MEALS_INCLUDED`·`DOUBLE_ROOM`) 최대 3개(4개 이상이면 검증 실패), `monthlyRentMin`·`monthlyRentMax`(월세 범위, 각 0 이상 정수·필수, `monthlyRentMin` ≤ `monthlyRentMax`), `arcStatus`(enum `ArcStatus`: `ARC_ISSUED`|`NO_ARC`, 1택 필수). ⑥ `arcStatus`는 파생 조건을 만들지 않고 매물 루트 `arcRequired`(`ArcRequirement`)로 직접 필터한다 — `NO_ARC`(ARC 미발급)이면 `arcRequired=NOT_REQUIRED`인 매물만 매칭하고, `ARC_ISSUED`면 이 필터를 적용하지 않는다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md)). `DiagnosisCondition`에 `NO_ARC`는 없으며, 최대 3개 제한은 사용자가 ④에서 고른 `conditions`에만 적용된다.
- MVP 매물 데이터는 **서울 기준**이다. `BUSAN`/`GYEONGGI`는 매물 카탈로그 `CITY`에도 시드된 값이라 구조적으로 막혀 있지 않고 **해당 지역 매물이 아직 없을 뿐**이므로, 결과 매물이 0건일 수 있고 이때 조정 제안을 반환한다.
- 추천 결과의 매물 요약은 listing 모듈의 공개 DTO `RecommendedListingView`를 사용하며, 일반 탐색의 `ListingSummaryResponse`와는 필드 구성이 다르다.
- 진단·결과는 본인만 접근 가능하다(소유권 검증) — **회원은 `userId`가, 비회원(게스트)은 게스트 세션 키가 일치할 때만 통과하며, 신원 종류가 다르면(한쪽이 비어 있으면) 무조건 거절**한다(진단 id가 전역 순차 채번이라 소유권 검사가 유일한 방어선이다). **게스트 진단은 v2 경로에서만 만들어지고 조회되므로 게스트 쪽 판정도 v2 한정**이며, v1 진단 7개는 회원 전용이라 토큰 없는 요청이 애초에 닿지 못한다. 모든 시각은 UTC ISO-8601, 금액은 KRW 정수, enum은 UPPER_SNAKE.
- 입력 검증 위반(필수값 누락·enum 불일치·조건 개수 초과·월세 범위 음수 또는 `monthlyRentMin` > `monthlyRentMax`·페이지 파라미터 범위)은 모두 공통 코드 `INVALID_INPUT`(400) + `errors[]`로 표현한다(error-response-guide §3·§4). 진단 도메인에서 별도 검증 코드를 만들지 않는다.

> **비회원(게스트) 접근 기준(#181)**: 진단은 **v2 서버 주도 흐름에 한해 로그인하지 않아도 이용할 수 있다**(애플 심사 대응 — 개인화 활동에서 제외 가능한 기능은 로그인 없이 쓸 수 있어야 한다). [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)에 신규 등록하는 **`permitAll` 매처는 `/api/v2/diagnoses/**` 하나뿐**이며, **v1 진단(`/api/v1/diagnoses/**`) 7개는 회원 전용으로 유지**한다 — v1에는 매처를 추가하지 않고 현행대로 `anyRequest().authenticated()`에 남겨 **토큰을 필수로 둔다**. 두 버전 모두 현재 전용 매처가 없어 인증으로 떨어지므로 v2 줄은 **새로 넣어야** 하고, 빠뜨리면 게스트 진단이 계속 401이다. **인가를 여는 수단은 `permitAll`이며, 토큰 없는 요청에 `ROLE_GUEST` 인증을 주입해 `hasAnyRole("USER","GUEST")`로 여는 방식은 쓰지 않는다** — 이유는 둘이다. (a) `SecurityConfig`의 기본값이 `anyRequest().authenticated()`라, 게스트 인증이 주입되면 **명시적으로 열지 않은 엔드포인트까지**(채팅·커뮤니티 등) 함께 게스트에게 열린다. (b) 모든 요청이 "인증됨"이 되어 보호 자원 접근이 **401이 아니라 403**으로 바뀌는데, 그러면 클라이언트가 401을 신호로 거는 **토큰 재발급 플로우가 전역적으로 침묵한다.** `permitAll`은 열기로 한 경로만 정확히 연다. 따라서 **게스트 진단 흐름은 `POST /api/v2/diagnoses/start` → `POST /api/v2/diagnoses/next` → `GET /api/v2/diagnoses/{diagnosisId}/recommendations` 셋으로 닫히며**(US-2-7), 비로그인 상태로 v1 7개를 호출하면 그대로 `401`이다 — 클라이언트는 로그인 여부에 따라 진단 API 버전을 고른다(게스트면 v2). 게스트 신원은 임시 `userId`를 발급하지 않고 **`userId` 부재(`null`)** 로 표현하며, 대화·소유권 연속성은 **`X-Guest-Session-Id`**(값 형식 `anonymous<uuid>`) 헤더로 잇는다(US-2-7) — 서버가 키를 발급하는 지점은 **`POST /api/v2/diagnoses/start` 하나**이고 소비처는 `/next`와 v2 추천 조회이며, 세션 키를 요구하는 것은 **v2 진단뿐**이다(퀴즈·생활팁은 저장이 없어 요구하지 않는다). 게스트의 표시 언어는 **`en` 고정**이며 `user` 공개 query `getLanguage`를 **호출하지 않는다**(`users` 행이 없어 호출 자체가 `404 USER_NOT_FOUND`가 된다 — US-2-6). 진단은 원래 세입자·임대인 공통이라 역할 게이트는 없던 대로 없다. **토큰을 보냈는데 만료된 요청은 게스트로 강등하지 않고 `401 TOKEN_EXPIRED`를 유지**하고(재발급 유도), 토큰 미전송·위조 토큰만 게스트로 처리한다(`permitAll`인 v2 경로 이야기이며, v1은 원래 401이다). **진단은 인가 범위가 넓어지지 않는다** — v1·v2 모두 `hasRole("USER")` 매처가 없어 온보딩 미완료(PENDING/TERMS_AGREED) 토큰이 #181 이전에도 이미 통과했고, 그 토큰은 `users` 행이 있으므로 언어도 `users.lang`을 따른다(의도적 수용 — 매처를 실제로 넓히는 것은 퀴즈·생활 팁뿐이다). 게스트 진단 결과를 로그인 후 계정으로 **이관하지 않는다**(스키마만 열어 둔다). 게스트 진단 데이터의 **TTL은 도입 여부와 수치가 모두 (결정 필요)** 다 — 현재 코드에는 TTL 인덱스가 **하나도 없어**(회원 진단도 영구 보존) 게스트 때문에 새로 도입할지 자체가 미정이며, 도입하지 않고 회원과 동일하게 영구 보존하는 것도 선택지다.

### US-2-1 — 진단 제출(진행 중 진단 확정 및 저장)

**As a** 한국 주거를 처음 찾는 외국인 사용자 (로그인한 회원 — v1 진단은 회원 전용이다)
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
- 시나리오: 인증 실패 — v1 확정은 회원 전용

  - **Given** `Authorization` 헤더가 없거나 만료된 token을 보낸다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** 토큰 부재/위조는 `401`+`error.code=UNAUTHENTICATED`, 만료는 `401`+`error.code=TOKEN_EXPIRED`(재발급 유도)를 반환한다 — #181로 진단이 비회원에게 열려도 신규 `permitAll` 매처의 대상은 **`/api/v2/diagnoses/**` 뿐**이라 이 v1 확정 엔드포인트는 현행대로 토큰이 필수다
  - **And** 게스트는 이 엔드포인트를 아예 타지 않는다 — v2 흐름이 6단계를 다 채운 시점에 **서버가 자동 확정**하므로 별도 확정 요청이 없다(US-2-7)
- 시나리오: 경계 — 저장된 월세 범위(각 0 이상, `min` ≤ `max`)

  - **Given** 진행 중 진단에 저장된 `monthlyRentMin`/`monthlyRentMax`가 `0`/`0`(허용)이거나, `monthlyRentMin`이 `-1`(불허)이거나, `monthlyRentMin`이 `monthlyRentMax`보다 큰(예 `600000`/`300000`, 불허) 값이다
  - **When** `POST /api/v1/diagnoses`를 호출한다
  - **Then** `0`/`0`은 `201 Created`로 정상 확정, 음수는 `400`+`error.code=INVALID_INPUT`(`errors[]`의 `monthlyRentMin` reason "0 이상이어야 합니다."), `min` > `max`는 `400`+`error.code=INVALID_INPUT`(`errors[]`의 `monthlyRentMin` reason "monthlyRentMin은 monthlyRentMax 이하여야 합니다.")을 반환한다

### US-2-2 — 진단 결과(추천 매물 + 지도 좌표) 조회

**As a** 진단을 마친 외국인 사용자 (로그인한 회원 — 이 v1 추천 엔드포인트는 회원 전용이다)
**I want** 내 진단 조건에 맞는 매물 리스트와 지도용 좌표를 결과로 받고
**So that** 한국 부동산 용어를 몰라도 내게 맞는 매물을 지도와 목록으로 한눈에 비교할 수 있다

- **우선순위**: High
- **관련 NFR**: 성능(추천 쿼리·좌표 집계 응답시간 목표, 확인 필요 — NFR 문서 미확정), 보안(본인 진단만 조회)
- **백엔드 관점**: 저장된 진단 조건으로 매물을 매칭 → 추천 전용 DTO(`RecommendedListingView`) 목록 + 지도 마커 좌표(`lat`/`lng`, WGS84) 반환. 목록은 **오프셋 기반 페이지네이션**(`page`/`size`, 기본 size 20, 최대 100)이다. 요청은 추천/가격/거리 정렬 키와 방향을 검증하지만 현재 저장소는 `price*`만 월세 오름차순으로 처리하고 `recommended`·`distance`는 찜 수/수정일 기본 정렬을 사용하며 방향도 완전히 반영하지 않는다. 0건이면 빈 `content` + 조정 제안(`suggestions`)을 함께 내려 클라이언트가 키워드/조건 완화를 안내한다. `suggestions`의 `reason`/`type`은 언어 무관 enum, 사람이 보는 `message`/`detail`은 **서버가 사용자 표시 언어로 번역**해 전송한다(enum 보유 라벨, `user` 공개 query로 언어 취득, 미지원=영어 폴백 — US-2-6 일관). 이 엔드포인트는 **회원 전용**이며, 비회원(게스트)의 추천 조회는 v2 전용 엔드포인트 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`가 담당한다(US-2-7) — 검증·소유권·조건 매핑·`listing` 호출은 두 버전이 공유 컴포넌트(`DiagnosisRecommendationReader`) 한 곳을 쓰고, 응답 차이는 `suggestions` 유무뿐이다.

**AC (Given / When / Then)**

- 시나리오: 정상 조회 (결과 있음)

  - **Given** 본인이 소유한 `diagnosisId`가 있고 서울 기준 매칭 매물이 존재한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations?page=0&size=20&sort=recommended,desc`를 호출한다
  - **Then** `200 OK`와 함께 `data.content[]`(매물 요약), `data.markers[]`(lat/lng), `data.page`(오프셋 메타), `data.suggestions=null`을 반환한다
- 시나리오: 경계 — 0건 (부산/경기 또는 좁은 조건)

  - **Given** `region=BUSAN`처럼 MVP 데이터가 없거나 조건이 너무 좁아 매칭이 0건이다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `200 OK`(에러 아님)와 함께 `data.content=[]`, `data.markers=[]`, `data.suggestions`(완화 가능한 조건/예산/키워드 제안 목록)을 반환한다
- 시나리오: 인증 실패 — v1 추천 조회는 회원 전용

  - **Given** `Authorization` 헤더 없이 요청한다(게스트 세션 키를 실어도 마찬가지다)
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `401`과 `error.code=UNAUTHENTICATED`를 반환한다 — v1에는 `permitAll` 매처를 추가하지 않는다. 게스트는 대신 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`를 호출한다(US-2-7)
- 시나리오: 인가 실패 — 타인의 진단 결과 접근

  - **Given** 다른 사용자가 소유한 `diagnosisId`로 요청한다 — 다른 회원의 진단, 그리고 신원 종류가 엇갈리는 경우(회원 토큰으로 **게스트**가 v2에서 만든 진단을)
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** 두 경우 모두 `403 Forbidden`과 `error.code=FORBIDDEN`을 반환한다(소유권 위반으로 차단) — 소유권은 **신원 종류가 같고 값이 같을 때만** 통과하며 한쪽이 비어 있으면 무조건 거절하므로, 회원이 게스트 진단을 읽는 것도 막힌다
- 시나리오: 리소스 없음 — 존재하지 않는 진단

  - **Given** 어떤 사용자에게도 존재하지 않는 `diagnosisId`로 요청한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `404 Not Found`와 `error.code=DIAGNOSIS_NOT_FOUND`를 반환한다
- 시나리오: 입력 검증 실패 — 페이지 파라미터 범위 초과

  - **Given** `size=500`(최대 100 초과) 또는 정의되지 않은 `sort` 키를 보낸다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** `400 Bad Request`와 `error.code=INVALID_INPUT`을 반환한다(허용되지 않은 `sort` 키를 무시하지 않고 거부 — api-design-guide §5)

### US-2-3 — 진단 이력 조회 및 최근 진단 다시 보기

**As a** 재방문한 외국인 사용자 (로그인한 회원 — 이력·최근·단건 상세는 모두 v1이라 회원 전용이다)
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
- 시나리오: 인증 실패 — 이력·최근·상세는 회원 전용

  - **Given** 토큰 없이 요청한다
  - **When** `GET /api/v1/diagnoses`(또는 `/latest`·`/{diagnosisId}`)를 호출한다
  - **Then** `401`과 `error.code=UNAUTHENTICATED`를 반환한다 — v1 진단에는 `permitAll` 매처를 추가하지 않으므로 비회원은 이력·최근·상세에 접근하지 못한다. **게스트용 이력·최근 시맨틱을 새로 정의하지 않는다** — v2에는 대응물이 없고, 게스트 진단 결과는 로그인 후에도 계정으로 이관하지 않는다
- 시나리오: 인가 실패 — 타인 진단 단건 조회

  - **Given** 다른 사용자의 `diagnosisId`로 요청한다 — 다른 회원의 진단, 그리고 신원 종류가 엇갈리는 경우(회원 토큰으로 게스트가 v2에서 만든 진단을)
  - **When** `GET /api/v1/diagnoses/{diagnosisId}`를 호출한다
  - **Then** 둘 다 `403`과 `error.code=FORBIDDEN`을 반환한다 — 신원 종류가 같고 값이 같을 때만 통과하며 한쪽이 비어 있으면 무조건 거절한다
- 시나리오: 리소스 없음 — 존재하지 않는 진단 단건 조회

  - **Given** 어떤 사용자에게도 존재하지 않는 `diagnosisId`로 요청한다
  - **When** `GET /api/v1/diagnoses/{diagnosisId}`를 호출한다
  - **Then** `404`와 `error.code=DIAGNOSIS_NOT_FOUND`를 반환한다

### US-2-4 — 재진단(새 진단 생성)

**As a** 조건이 바뀐 외국인 사용자 (로그인한 회원 — 재진단도 v1 확정 엔드포인트라 회원 전용이다)
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
  - **Then** `401`과 `error.code=TOKEN_EXPIRED`를 반환한다(토큰 부재/위조는 `401`+`UNAUTHENTICATED`) — v1 진단은 회원 전용이라 인증 계약이 바뀌지 않는다
  - **And** 게스트의 "재진단"은 이 엔드포인트가 아니라 **`POST /api/v2/diagnoses/start`를 다시 호출**하는 것이며, 그때 서버가 새 게스트 세션 키를 발급한다(US-2-7)

### US-2-5 — 진단 문항·선택지 백엔드 제공

**As a** 진단을 시작하는 외국인 사용자 (로그인한 회원 — v1 단계별 흐름은 회원 전용이다)
**I want** 받을 단계 번호로 질문 1개를 조회하고 그 단계의 답 1개를 보내 서버가 저장하게 하는 흐름을 한 단계씩 반복하고
**So that** 앱을 새로 배포하지 않고도 질문·선택지(지역·대학·조건 등)와 분기 흐름을 서버에서 갱신·관리할 수 있다

- **우선순위**: High (진단 제출 플로우의 선행 단계)
- **관련 NFR**: 일관성(문항 카탈로그의 선택지 코드가 진단 제출 검증 enum과 1:1 일치), 단계별 분기를 서버가 결정(클라이언트 로컬 분기 아님)
- **백엔드 관점**: 진단 문항을 단계별로 내려주는 server-stateful 흐름을 두 엔드포인트로 제공한다(둘 다 **인증 필수** — v1 진단에는 `permitAll` 매처를 추가하지 않으므로 비회원은 호출할 수 없고, 게스트가 문항을 받는 경로는 v2 흐름뿐이다(US-2-7). 02 스펙 상세 §1에 반영) — 질문 조회 `GET /api/v1/diagnoses/questions/{step}`와 답 저장 `POST /api/v1/diagnoses/answers`. 진행 답은 **서버가 저장**한다 — 사용자당 진행 중 진단 1건(`status=IN_PROGRESS`)을 in-progress draft로 두고 채워 간다. 클라이언트는 받을 `step`(1~6)을 **path로 지정**해 `GET /api/v1/diagnoses/questions/{step}`로 그 단계 질문 1개와 선택지를 조회하고, 화면에서 받은 **현재 단계의 답 1개**(그 단계의 `field`+`code`; `conditions`처럼 다중 선택은 `codes` 배열; ⑤ 월세 범위는 코드가 아닌 두 숫자 필드 `field=monthlyRent`+`min`/`max`, 예 `{ "field": "monthlyRent", "min": 300000, "max": 600000 }` — 순서 없는 `codes[]` 배열을 재사용하지 않는다)만 `POST /api/v1/diagnoses/answers` 본문에 담아 보내며, 서버가 그 답을 진행 중 진단에 저장한다 — 6단계(① 지역 / ② 입국 목적 / ③ 대학 그룹·지역 / ④ 주거 조건 / ⑤ 월세 범위(min/max) / ⑥ ARC)를 한 번에 주지 않고 한 단계씩 내려간다. 다음 `step` 번호는 클라이언트가 정해 다시 `GET`을 호출한다. 요청 본문에 누적 답(`answers` 묶음)을 담지 않는다. 질문 조회 응답은 `{ "step", "field", "question"(번역된 표시 라벨), "select"(단일/다중·최대; ⑤ 월세 범위 단계는 고정 선택지 목록이 아닌 두 숫자 입력 `NUMBER_RANGE`·`options` 비움 — "모든 단계가 enum과 1:1인 고정 선택지 목록"이라는 가정에서 의도적으로 분리된 예외), "options": [ { "code", "label" } ] }`이며, 6단계 답이 모두 저장되면 클라이언트는 이후 `POST /api/v1/diagnoses`로 진행 중 진단을 확정 제출한다(US-2-1). ③ 단계 분기는 **서버가 저장된 `purpose`로 결정**하는 비즈니스 로직이다(`STUDY`면 6개 대학 그룹(`UniversityGroup`) `options`를 담은 `university` 질문, `NON_STUDY`면 `district` 질문 — 알맞은 한 질문만 내려주며, 유학 시 답은 단일 그룹 코드 1개(`field=university`, `code=<그룹코드>`)다). 분기 메타는 `diagnosisQuestions`에 두지 않으며(데이터만), 대학 질문·지역 질문은 각각 카탈로그 데이터로 존재하고 어느 것을 낼지는 서비스가 결정한다. 선택지 코드는 제출 시 검증하는 enum과 동일 출처여야 한다. 잘못된 답(미정의 enum, 목적-대학/지역 불일치 등)은 공통 `INVALID_INPUT`(400)+`errors[]`로 표현한다. MVP 데이터는 서울 기준.

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
- 시나리오: 인증 실패 — 비회원은 v1 문항·답 저장에 접근하지 못한다

  - **Given** 로그인하지 않은 사용자가 `Authorization` 헤더 없이 진단 화면에 진입한다
  - **When** `GET /api/v1/diagnoses/questions/1`(또는 `POST /api/v1/diagnoses/answers`)을 호출한다
  - **Then** `401`과 `error.code=UNAUTHENTICATED`를 반환한다 — v1 진단은 회원 전용이며 신규 `permitAll` 매처의 대상이 아니다. 게스트는 대신 `POST /api/v2/diagnoses/start` → `POST /api/v2/diagnoses/next`로 문항을 받는다(US-2-7)
- 시나리오: 온보딩 미완료 토큰 — #181 이전과 동일하게 통과

  - **Given** 온보딩을 마치지 않은(PENDING/TERMS_AGREED, `ROLE_ONBOARDING`) 토큰으로 접근한다
  - **When** `GET /api/v1/diagnoses/questions/1`을 호출한다
  - **Then** `403 AUTH_ONBOARDING_REQUIRED`가 아니라 `200 OK`를 받는다 — 진단에는 원래 `hasRole("USER")` 매처가 없어 `anyRequest().authenticated()`로 떨어졌고 `ROLE_ONBOARDING` 토큰은 **#181 이전에도 이미 통과했다**. #181이 말하는 "인가 범위 확대"는 매처를 `hasRole("USER")` → `permitAll`로 **수정**하는 퀴즈(US-6-1)·생활 팁(US-8-1)에만 해당하며, 진단은 v1이 그대로 인증 필수로 남는다
  - **And** 표시 언어는 **`users.lang`을 따른다** — 온보딩 중이어도 `users` 행은 존재하기 때문이다

### US-2-6 — 사용자 표시 언어 기반 진단 문항·선택지 번역 제공

**As a** 한국어가 익숙하지 않은 외국인 사용자 (로그인한 회원 — 이 v1 문항 엔드포인트는 회원 전용이다)
**I want** 진단 문항·선택지를 내 표시 언어에 맞게 번역된 텍스트로 받고
**So that** 모국어 또는 영어로 질문을 이해하고 정확히 답할 수 있다

- **우선순위**: High (외국인 대상 서비스의 핵심 접근성)
- **관련 NFR**: 국제화(i18n), 일관성(번역 누락 시 폴백), 보안(본인 표시 언어 기반 — 사용자 선택값)
- **백엔드 관점**: 번역 기준은 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**이다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)) — 사용자가 앱 지구본에서 언어를 직접 고르면 그 값이 기준이 되고 고르지 않았으면 `en`이 기준이 되며, 서버가 그 언어의 문항·선택지 **표시 라벨**을 채워 반환한다(`Accept-Language` 헤더에 의존하지 않음; 사용자 선택값이 기기 설정보다 안정적). 표시 언어는 `diagnosis`가 **`user` 모듈 공개 query(`getLanguage`)를 동기 호출**해 취득한다(도출 규칙은 `user`가 캡슐화하며 query 시그니처는 불변; 토큰 클레임 분기는 사용하지 않음; ADR-0002 Decision 5 — 모듈 의존 `diagnosis→user` 추가). 진단은 **세입자·임대인 모두 이용할 수 있다**(별도 역할 게이트 없음) — 임대인은 서버 고정 `lang='ko'`라 진단을 한국어로 본다. **비회원(게스트)은 이 v1 문항 엔드포인트를 호출할 수 없다**(회원 전용 — #181에서도 v1에는 `permitAll` 매처를 추가하지 않는다). 게스트가 문항을 받는 경로는 v2 흐름뿐이며(US-2-7), **그 경로의 표시 언어는 `en` 고정이고 `getLanguage`를 호출하지 않는다** — 게스트는 `users` 행이 없어 호출하면 `404 USER_NOT_FOUND`가 되므로 분기의 요점은 기본값이 아니라 호출 회피다. 번역 전략(인라인 언어-키 맵·`en` 폴백·코드 불변)은 v1·v2가 동일 출처를 공유한다. 게스트에 대한 `Accept-Language` 헤더 해석은 이번 범위 밖이다(지원 언어가 en/ko/ja로 한정돼 임의 로케일 매핑 정책이 별도로 필요하다). 번역은 별도 컬렉션·키 없이 **`diagnosisQuestions` 도큐먼트 안에 인라인 언어-키 맵으로 임베드**한다 — 질문은 `question: { "en": ..., "ja": ..., "ko": ... }`, 선택지는 `options: [ { "code": "SEOUL", "label": { "en": "Seoul", "ja": "ソウル" } }, ... ]`처럼 **언어 코드를 키로 하는 맵**으로 둔다. 서버는 사용자 언어 키(예 `ja`)로 message·label을 고르고, 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다. 선택지 **코드는 언어와 무관하게 동일·불변**(UPPER_SNAKE)하며 언어-키 맵의 값(표시 문자열)만 언어별이다(제출은 코드로 검증). 신규 6개 대학 그룹(`UniversityGroup`) 코드(`HUFS_KHU_KOREA`·`SKKU_SUNGSHIN`·`SNU_CAU_SOONGSIL`·`HONGIK_YONSEI_EWHA`·`KONKUK_SEJONG_HYU`·`ETC`)도 동일하게 UPPER_SNAKE 불변 코드이며 코드로 검증하고, 그룹의 표시 라벨(예 "서울대·중앙대·숭실대" / "Seoul National · Chung-Ang · Soongsil")은 다른 선택지와 똑같이 언어-키 맵으로 번역 대상이 된다. US-2-5와 동일 엔드포인트에서 처리한다.

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
- 시나리오: 게스트 번역은 v2 경로에서 다룬다

  - **Given** `Authorization` 헤더 없이 진단을 시작하는 비로그인 사용자다(`Accept-Language`를 무엇으로 보내든 무관)
  - **When** v1 문항 엔드포인트(`GET /api/v1/diagnoses/questions/{step}`)를 호출한다
  - **Then** 번역 이전에 인증에서 막혀 `401`+`UNAUTHENTICATED`다 — 게스트의 문항 번역 계약(**영어(`en`) 고정**·`getLanguage` **미호출**)은 v2 흐름(`POST /api/v2/diagnoses/start`·`/next`)에서 규정한다(US-2-7). 선택지 코드가 언어·신원과 무관하게 불변인 것은 두 경로가 같다

### US-2-7 — 지역 매물 부재 시 재질의·종료 및 서버 주도 진단 흐름 (v2)

**As a** 진단을 진행하는 외국인 사용자 (로그인한 회원 또는 **비로그인 사용자**)
**I want** 내가 시작을 결정한 진단을 서버가 다음 질문·분기·확정 시점을 알아서 판단해 이끌고, ① 지역에 매물이 없으면 다른 지역으로 다시 시도하거나 진단을 끝낼 수 있게 하고
**So that** 매물이 없는 지역인데도 끝까지 답하는 헛수고 없이, 앱을 나갔다 들어오지 않고 그 자리에서 재시도하거나 종료할 수 있다

- **우선순위**: High (진단 완주율·이탈 개선)
- **관련 NFR**: 사용성(불필요한 단계 진행 차단·클라 로컬 분기 제거), 유지보수성(진행 흐름을 서버가 소유), 신뢰성(진행 상태 서버 보관)
- **백엔드 관점**: 기존 v1(`/api/v1/diagnoses/*`, 클라이언트가 `step`·확정을 주도)은 **그대로 두고**, **서버 주도 대화형 흐름을 `/api/v2`에 신설**한다(하위 호환이 깨지는 변경이라 버전 상향 — [ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md)). **서버는 질문과 분기만 주도하고, 진단을 시작할 시점과 매물을 받을 시점은 클라이언트가 결정한다** — 클라이언트가 **`POST /api/v2/diagnoses/start`**(본문 없음)로 진단을 시작하고 **`POST /api/v2/diagnoses/next`**로 현재 문항 답 1개씩을 이어 보내면, 서버는 `step`을 받지 않고 직전에 낸 문항에서 다음 질문을 결정하며 빌더가 다 채워지면 **자동 확정**한다. `/start`는 진행 중 세션이 있어도 **무조건 버리고** 새 세션(빈 draft·`pendingField=region`)을 만든다 — 진단하다 홈으로 갔다 다시 시작해도 서버가 기존 진단 정보를 보고 이어가지 않고 **언제나 처음부터**다. 진행 세션이 없는데 `/next`가 오면 서버가 임의로 흐름을 되살리지 않고 `400 DIAGNOSIS_SESSION_NOT_FOUND`로 막으며, 클라이언트는 `/start`로 복구한다. `/next`는 답(`field`)이 반드시 있어야 한다(없으면 `INVALID_INPUT`). 확정 응답에 추천 매물을 인라인으로 싣지 않고 **`diagnosisId`만** 담으며, **확정 시점에 매칭 유무조차 확인하지 않는다** — 매물은 클라이언트가 시점을 정해 **`GET /api/v2/diagnoses/{id}/recommendations`**(v1 §7과 같되 `suggestions` 없음)로 별도 조회하고, **매칭 0건은 그 응답의 `resultCode: NO_MATCH`로 드러난다**(흐름 응답엔 그 코드가 없다 — 미리 알려주려면 클라가 요청하지 않은 추천 쿼리를 서버가 돌려야 한다). ① 지역(`region`) 답 직후 매칭이 0건일 때만 서버가 예외적으로 미리 필터링해 "다른 지역 방을 찾아보시겠어요?" 문항을 끼워 넣는다 — 이 예외질문은 서버 코드에 하드코딩한 합성 문구가 아니라 **문항 카탈로그(`diagnosisQuestions`)의 일반 질문**(`step: 1`·`field: regionRetry`·`select: {type: SINGLE, max: 1}`·옵션 `YES`/`NO`)이라 별도 결과코드 없이 다른 문항과 똑같이 `NEXT_QUESTION`으로 내려가고 번역도 US-2-6과 동일 경로를 탄다(신규 환경은 order 0000 시드, 기배포 환경은 멱등 정본 시드 `diagnosis-questions.json`에 포함). 그 예/아니오 응답에만 **클라이언트가 행할 행위**를 코드로 알린다 — 예=`RESTART`(클라가 `/start`로 처음부터 재시도) · 아니오=`TERMINATED`(진단 종료), 둘 다 세션을 삭제한다. 6단계까지 마친 뒤 매칭이 0건이면 `NO_MATCH`이며 **어떤 제안도 없다**(v1의 `suggestions` 기능·시드는 v1 전용으로 유지하되 v2는 참조하지 않는다). 매 응답은 정상 `200 OK`의 `data.resultCode`(태그드 유니온: `NEXT_QUESTION` / `RESTART` / `COMPLETED` / `TERMINATED`)로 표현한다(에러 아님). 진행 상태는 v1의 `diagnoses`(IN_PROGRESS 초안)를 공유하지 않고 v2 전용 세션(`diagnosisFlowSessions`)에 담고, 완료 시에만 정본 진단을 기존 `diagnoses`에 저장한다. **비회원(게스트)도 이 흐름을 그대로 타며, 여기가 게스트 진단의 정본 경로다**(#181 — 신규 `permitAll` 매처는 `/api/v2/diagnoses/**` 하나뿐이고 v1 진단 7개는 회원 전용으로 남는다) — 회원 세션이 `userId`를 키로 upsert되는 자리에서, 게스트는 `POST /api/v2/diagnoses/start`가 **서버가 발급해 응답에 실어 주는 게스트 세션 키**(값 형식 `anonymous<uuid>`)를 받아 이후 `/next`·추천 요청에 **`X-Guest-Session-Id` 헤더로 에코**한다. 문서에는 `userId`(회원)와 `guestSessionId`(게스트) 두 신원 필드를 두고 **정확히 하나만** 채우며, `diagnosisFlowSessions`의 `userId` UNIQUE 인덱스는 partial로 좁히고 `guestSessionId` partial UNIQUE를 별도로 신설한다(한 인덱스에 두 신원을 섞으면 게스트 문서의 빈 `userId`끼리 충돌한다). 게스트 세션 키는 **요청자마다 달라야 한다** — 진단 id가 전역 순차 채번이라 공용·상수 키를 쓰면 게스트 A가 id를 증가시키며 게스트 B의 진단을 읽을 수 있다. 클라이언트에는 키를 보관·에코할 의무가 새로 생기며, 키를 잃으면 진단을 처음부터 다시 해야 한다. **① 지역 0건으로 끝난 시도는 버리지 않는다** — 부분 답을 `diagnoses`에 `status=DISCARDED`로 남겨 "어느 지역을 원했는데 매물이 없었나"를 수요 분석에 쓴다(재시도·종료 양쪽. 사용자 노출 경로 없음 — 이력·최근은 `COMPLETED`만, v1 초안 조회는 `IN_PROGRESS`만 본다). 그 외 이탈은 돌아왔을 때에야 알 수 있어 집계가 편향되므로 기록하지 않는다. 문항 카탈로그·번역·입력 enum·③ 분기 규칙은 v1과 동일 출처(US-2-5·US-2-6·[ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md)·[ADR-0029](../adr/0029-diagnosis-i18n-strategy.md))를 공유한다. 시퀀스: [US-2-7 다이어그램](../architecture/sequence-diagrams/02-diagnosis-recommendation/us-2-7-v2-server-driven-flow.md), API: [02 스펙 v2 절](../api/specs/02-diagnosis-recommendation.md).

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
- 시나리오: 비회원(게스트) 시작 — 토큰 없이 `/start`, 서버가 세션 키 발급

  - **Given** 로그인하지 않은 사용자가 진단 화면에 진입한다
  - **When** `Authorization` 헤더 없이 본문 없이 `POST /api/v2/diagnoses/start`를 호출한다
  - **Then** `401`이 아니라 `200 OK`와 `data.resultCode=NEXT_QUESTION`·`data.question`(① 지역 문항, 표시 라벨은 **영어(`en`)**)을 받고, **서버가 발급한 게스트 세션 키**(응답 필드 `data.guestSessionId`, 값 형식 `anonymous<uuid>` — 회원 응답에서는 생략된다)를 함께 받는다([02 스펙 v2 절](../api/specs/02-diagnosis-recommendation.md))
  - **And** 이 키는 요청자마다 다르다(공용·상수 키가 아니다 — 진단 id가 순차 채번이라 공용 키를 쓰면 게스트끼리 서로의 진단을 열람할 수 있다)
  - **And** 서버는 이 요청에서 `user` 공개 query `getLanguage`를 **한 번도 호출하지 않는다** — 게스트는 `users` 행이 없어 호출하면 첫 요청부터 `404 USER_NOT_FOUND`가 되므로, 요점은 기본값이 아니라 **호출 회피**다. 게스트는 이 흐름 전 구간(`/start`·`/next`·추천 조회)에서 `user` 모듈을 부르지 않고 `en`을 쓴다
- 시나리오: 비회원(게스트) 연속성 — 세션 키를 에코하면 흐름이 이어진다

  - **Given** 위에서 게스트 세션 키를 발급받았다
  - **When** 그 키를 `X-Guest-Session-Id` 헤더에 실어 `{ "field": "region", "code": "SEOUL" }`을 `POST /api/v2/diagnoses/next`로 보낸다
  - **Then** 서버가 그 키로 세션을 찾아 답을 저장하고 다음 단계(② 입국 목적) 문항을 `NEXT_QUESTION`으로 반환한다 — 회원이 `userId`로 세션을 잇는 자리를 게스트는 이 키로 잇는다
  - **And** ①~⑥을 모두 채우면 회원과 동일하게 자동 확정되어 `data.resultCode=COMPLETED`·`data.diagnosisId`를 받고, 저장된 진단 문서는 `userId`가 비고 게스트 세션 키가 채워진다(두 신원 필드 중 정확히 하나만 채워진다)
- 시나리오: 비회원(게스트) 세션 키 누락·불일치

  - **Given** 게스트가 `/start`로 세션을 시작해 두었다
  - **When** `X-Guest-Session-Id` 없이, 또는 다른 게스트의 키로 `POST /api/v2/diagnoses/next`를 호출한다
  - **Then** 두 경우 모두 자기 세션에 닿지 못하고 `400`+`error.code=DIAGNOSIS_SESSION_NOT_FOUND`를 받으며, 클라이언트는 `POST /api/v2/diagnoses/start`로 복구한다 — 서버가 요청마다 새 키를 만들어 흐름을 되살리지 않는다(그러면 `/start`만 200이고 `/next`가 구조적으로 죽는다)
- 시나리오: 비회원(게스트) 추천 조회 — 소유권 3방향

  - **Given** 게스트 A가 확정한 진단 `diagnosisId`가 있다
  - **When** (a) 게스트 A가 자기 키로, (b) 게스트 B가 자기 키로, (c) 회원이 토큰으로 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`를 호출한다
  - **Then** (a)만 `200 OK`(`content`·`markers`·`page`)이고 (b)·(c)는 `403 FORBIDDEN`이다 — 소유권은 신원 종류가 같고 값이 같을 때만 통과하며, 반대로 **게스트 키로 회원 진단을 조회하는 것도 `403`** 이다
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
  - **And** #181(비회원 접근)에서도 v1의 인가 계약은 그대로다 — `permitAll` 매처는 `/api/v2/diagnoses/**`에만 추가되고 v1은 계속 인증 필수이므로, v1 응답 DTO(`AnswerSavedResponse` 등)와 그 RestDocs 테스트도 바뀌지 않는다 — 게스트 세션 키를 발급하는 지점도 `POST /api/v2/diagnoses/start` 하나뿐이라 v1 응답에는 등장하지 않는다

## 3. 매물 등록 · 탐색 · 찜

> 관련 API 스펙: [03-listings-favorites](../api/specs/03-listings-favorites.md)

외국인 사용자가 서울 지역 매물을 지도/리스트/검색으로 탐색하고, 상세를 확인하며, 관심 매물을 찜하고 최근 본 매물을 다시 찾는 흐름과, **임대인이 직접 매물을 등록해 관리자 승인 대기 상태로 올리는 흐름**(US-3-6), **관리자가 그 매물을 심사해 승인·반려하는 흐름**(US-3-7), **임대인이 자기 매물을 상태와 무관하게 다시 보는 흐름**(US-3-8), **임대인이 그 매물을 고쳐 다시 심사를 받는 흐름**(US-3-9)을 함께 다룬다. 목록·지도·장소 후보·기존 키워드 검색·상세 조회는 가입 전에도 사용할 수 있는 공개 API이고, 찜 등록/해제·찜 목록·최근 본 목록은 인증이 필수이며, 매물 등록·수정과 임대인 전용 매물 조회는 온보딩을 마친(`ACTIVE`) **임대인 전용**이고, 심사(모든 상태 조회·승인·반려)는 **관리자(`userType=ADMIN`) 전용**이다. 세입자에게 노출되는 조회(목록·지도·상세·찜 목록·최근 본 목록)는 모두 **`PUBLISHED` 매물만** 대상으로 하므로, 등록 직후의 `PENDING` 매물은 어느 조회에도 나타나지 않고, 공개 중이던 매물도 임대인이 수정을 제출해 `UPDATE_PENDING`이 되면 심사가 끝날 때까지 같은 조회에서 빠진다(US-3-9). 응답은 모두 공통 래퍼 `{ success, data, error }`를 따르며, 에러 코드/HTTP status는 [error-response-guide](../api/error-response-guide.md)를 정본으로 한다. 요청 바인딩·필드 검증 실패는 가능한 경우 `error.errors[]`에 필드 상세를 담지만, 최소값>최대값 같은 서비스 계층의 교차 필드 검증은 `errors=[]`일 수 있다.

> **매물 API 버전 경계(조회 v1 종료 · v2 신설)**: 조회 5종(목록 `GET /listings` · 지도 `/listings/map` · 상세 `/listings/{listingId}` · 찜 토글 `POST`·`DELETE /listings/{listingId}/favorite` · 내 스코프 `/users/me/favorites`·`/users/me/recent-listings`)의 경로는 **`/api/v2`가 정본**이며, 아래 AC의 경로 표기도 v2를 따른다. v4 스키마 개편 이후 `/api/v1`의 같은 경로는 **개정 전(v3) 응답 구조를 그대로 복원한 `deprecated` 스텁**으로 **DB에 접근하지 않고 데이터 0건**만 반환한다 — 목록·찜 목록은 빈 페이지(`content: []`, `page.totalElements: 0`), 최근 본 목록은 빈 목록(`content: []` — 이 응답에는 `page` 객체가 없다), 지도는 빈 마커(`markers: []`, `total: 0`), 상세와 찜 토글은 `404 LISTING_NOT_FOUND`다. 출시된 구버전 앱은 "매물 없음" 화면을 보고 업데이트로 유도되며, 새 데이터로 옛 응답을 조립하지 않으므로 하위 호환용 값을 날조하지 않는다(v1 제거 시점은 미정). **예외로 `GET /api/v1/listings/places`(네이버 장소 검색, US-3-3)는 매물 데이터를 쓰지 않아 v1 그대로 동작한다.** 인가는 같은 네임스페이스 안에서 갈린다 — `/api/v2/listings/**`의 **GET은 공개(`permitAll`)**, 등록 `POST /api/v2/listings`·수정 `PUT /api/v2/listings/{listingId}`(US-3-9)·찜 토글·`me` 스코프 조회(`/api/v2/users/me/**` — 임대인 전용 매물 조회 US-3-8 포함)는 **인증 필수(`ROLE_USER`)** 다.
>
> **매물 다국어 표시([ADR-0037](../adr/0037-listing-localization-and-code-catalog.md))**: 매물 고유 문구(제목·주소·역명·방 이름·설명)는 `listings` 안 `{ko,en}`에서 사용자 언어 하나를 선택한다. UI에 표시하는 공통 코드(매물 유형·임대 유형·ARC 요구·성별 정책·조건 태그·건물 형태·난방·교통·도시·자치구·주방·세탁·공용공간·생활 편의·보안·제공 물품·주변 시설·지원 언어·대학 19개 카테고리 — [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md))는 `listingCatalog` 번역과 결합한 `{code,label}`로 응답한다. 프론트는 label을 표시하고 code를 기존 필터 요청·비즈니스 비교에 사용한다. 로그인 사용자는 `user::api getLanguage`로 계정에서 선택한 표시 언어(`users.lang`)를 얻고, 비로그인·미지원 언어는 MVP 기본 영어를 사용한다. ID·좌표·가격·상태·통화와 요청 code는 번역하지 않는다.

### US-3-1 — 매물 리스트 탐색(필터·정렬·페이지네이션)

**As a** 한국 주거를 찾는 외국인 사용자
**I want** 예산·조건 칩으로 필터링하고 추천/가격/거리 순으로 정렬된 매물 목록을 오프셋 페이지 단위로 조회
**So that** 내 조건(예산·생활 조건)에 맞는 매물을 한눈에 비교하고 더 볼 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 목록 조회 응답시간 목표(NFR 미정), 무상태 조회로 수평 확장 가능
- 데이터 관점: 필터는 서버에서 MongoDB 질의 조건으로 평탄화(매물은 MongoDB 저장, ADR-0005), `sort=DISTANCE`는 bbox 네 좌표가 모두 있어야 하며 기준점은 요청 bbox의 중심, `page.totalElements`는 동일 필터 조건으로 산출

**AC (Given / When / Then)**

- 시나리오: 정상 목록 조회
  Given 관리자 승인을 받은 `PUBLISHED` 매물이 N건 존재하고(승인 대기 `PENDING`·반려 `REJECTED`·수정 심사 대기 `UPDATE_PENDING` 매물은 조회 대상이 아니다)
  When 비로그인 사용자가 `GET /api/v2/listings?minBudget=300000&maxBudget=700000&conditions=ENGLISH_OK&sort=PRICE_ASC&page=0&size=20`을 호출하면
  Then `200 OK`로 공통 래퍼의 `data.content[]`에 가격 오름차순으로 **`PUBLISHED` 매물만** 담기고(임대인이 방금 등록해 `PENDING`인 매물은 `data.page.totalElements`에도 잡히지 않는다) `data.page`에 `number/size/totalElements/totalPages/hasNext`가 포함된다
- 시나리오: 입력 검증 실패(필터 값 오류)
  Given 클라이언트가
  When `minBudget=700000&maxBudget=300000`(최소>최대) 또는 정의되지 않은 `conditions=UNKNOWN_TAG`, `sort=CHEAPEST`처럼 허용되지 않은 enum/범위를 보내면
  Then 서버는 무시하지 않고 `400 Bad Request`, `error.code=INVALID_INPUT`을 반환한다. enum 바인딩처럼 필드를 특정할 수 있는 오류는 `error.errors[]`에 `field`/`reason`을 담고, 최소값>최대값 같은 서비스 계층 교차 필드 검증은 `errors=[]`일 수 있다
- 시나리오: 거리순 정렬에 기준 좌표 누락
  Given 사용자가
  When `sort=DISTANCE`를 지정했으나 `swLat`·`swLng`·`neLat`·`neLng`를 모두 보내지 않으면
  Then `400 Bad Request`, `error.code=LISTING_INVALID_SORT_PARAM`을 반환한다
- 시나리오: 경계(페이지 범위 초과·빈 결과)
  Given 필터 결과가 0건이거나 마지막 페이지를 넘는 `page`가 요청되면
  When 목록을 호출하면
  Then `200 OK`로 `data.content`는 빈 배열, `data.page.hasNext=false`를 반환한다(에러 아님)
- 시나리오: 인증 선택(현재 목록 개인화 범위)
  Given 동일 매물에 대해
  When 비로그인 또는 로그인 사용자가 목록을 호출하면
  Then 두 경우 모두 `200 OK`이며, 로그인 사용자는 계정 표시 언어만 적용되고 목록 항목의 `favorited`는 현재 구현상 모두 `false`다. 실제 찜 상태는 상세·찜 목록·최근 본 목록에서 반영된다
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
  When `GET /api/v2/listings/map?swLat=37.49&swLng=126.95&neLat=37.57&neLng=127.05`를 호출하면
  Then `200 OK`로 `data.markers[]`(각 항목 `listingId/lat/lng`)를 반환한다
- 시나리오: 입력 검증 실패(좌표 불완전/모순)
  Given 클라이언트가
  When bbox 4좌표 중 일부만 보내거나 `swLat >= neLat`처럼 모순된 좌표를 보내면
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
- 데이터 관점: `GET /api/v1/listings/places`는 네이버 장소 후보만 최대 5개 반환하고 MongoDB 매물은 조회하지 않는다 — 매물 데이터를 쓰지 않으므로 조회 계열의 v2 이관 대상이 아니라 **v1 경로 그대로 유지**한다. 백엔드는 `mapx/mapy`를 WGS84 `lng/lat`으로 변환하며, 사용자가 후보를 선택한 뒤 앱이 계산한 bounds로 정본인 `/api/v2/listings`와 `/api/v2/listings/map`을 호출한다(한 흐름에 장소 검색 v1과 매물 조회 v2가 섞인다).

**AC (Given / When / Then)**

- 시나리오: 정상 장소 후보 검색
  Given 비로그인 사용자가 "경희대"를 입력하고
  When `GET /api/v1/listings/places?keyword=경희대`를 호출하면
  Then `200 OK`로 `data.items[]`에 `title`·`address`·`roadAddress`·`lat`·`lng`를 포함한 장소 후보를 최대 5개 반환한다
- 시나리오: 장소 선택 후 주변 매물 조회
  Given 사용자가 장소 후보 하나를 선택해 앱이 해당 `lat/lng`로 지도를 이동하고 현재 bounds를 계산하면
  When 같은 `swLat`·`swLng`·`neLat`·`neLng`를 `/api/v2/listings`와 `/api/v2/listings/map`에 전달하면
  Then 매물 목록(`data.content[]`)과 지도 마커(`data.markers[]`)를 각각 `200 OK`로 반환한다
- 시나리오: 입력 검증 실패(빈/과도 키워드)
  Given 클라이언트가
  When `keyword`를 누락하거나 공백만, 또는 50자를 초과해 보내면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환한다
- 시나리오: 장소 후보 없음(경계)
  Given 네이버 지역 검색 결과가 없는 키워드가 주어지고
  When 장소 검색 API를 호출하면
  Then 에러가 아닌 `200 OK`로 `data.items=[]`를 반환한다
- 시나리오: 공개 조회의 토큰 처리
  Given access token이 없거나 위조되었거나 만료되었고
  When 장소 검색 API를 호출하면
  Then 토큰 없음·위조는 익명 요청으로 처리되어 정상 조회되고, 만료 토큰만 `401 TOKEN_EXPIRED`를 반환한다
- 시나리오: 네이버 연동 실패
  Given 네이버 API가 4xx/5xx를 반환하거나 타임아웃·인증정보 누락·응답 형식 이상이 발생하고
  When 장소 검색 API를 호출하면
  Then `502 Bad Gateway`, `error.code=UPSTREAM_ERROR`를 반환한다

> 시드 POI 사전을 쓰던 옛 키워드 검색(`GET /listings/search`)은 **v1·v2 양쪽에서 제거됐다** — 이 스토리의 장소 검색 흐름이 그 자리를 대체했고 클라이언트가 호출하지 않았기 때문이다([ADR-0043](../adr/0043-remove-seeded-poi-keyword-search.md)).

### US-3-4 — 매물 상세 조회 + 최근 본 매물 기록

**As a** 후보 매물을 자세히 보려는 외국인 사용자
**I want** 사진 갤러리·유형·방 상품별 가격/보증금/계약기간·위치·편의시설이 담긴 상세를 보고, 로그인 상태면 본 매물이 최근 본 목록에 기록되기
**So that** 매물을 충분히 검토하고 다시 쉽게 찾아올 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 최근 본 매물 DB 보관 사용자별 최대 30개·조회 응답 최대 10개
- 데이터 관점: 매물 담당 연락처(`contact`: 담당자명·지점 대표 전화)는 상세 응답에 공개하고, 임대인 개인 연락처(`users.phone_number`)는 노출하지 않고 채팅으로만 연결한다(둘은 별개 값이며, 개인 번호를 매물 문서에 복사하지 않는다 — [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md) Amended). 응답에서 제외하는 값은 `businessRegistrationNumber`와 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)이다. 최근 본 매물은 (userId, listingId) 유니크로 upsert하며 `viewedAt` 갱신, 상세 조회 성공 후 30개 초과 오래된 기록 삭제

**AC (Given / When / Then)**

- 시나리오: 정상 상세 조회(로그인) 및 기록
  Given 인증된 사용자가 존재하는 매물을 조회하면
  When `GET /api/v2/listings/{listingId}` (Authorization 포함)
  Then `200 OK`로 상세(사진 `imageUrls[]`·`roomOffers[].roomImageUrls`, `type`, `rentalType`, `arcRequired`, 방 상품별 `roomOffers[].pricing`(`deposit`·`monthlyRent`)·`roomOffers[].contract`(방 상품별 계약기간), `location`, `conditions[]`, `languagesSupported`, `nearbyFacilities`, `contact`, `ageMin`/`ageMax`, `blogUrl`, `refundPolicy`(문장 하나), `description`·`extraNotes`, `favorited`, `favoriteCount`)를 반환하고, 해당 매물이 최근 본 매물에 upsert된다. 매물 담당 연락처(`contact`)는 포함하되 임대인 개인 정보·연락처와 `businessRegistrationNumber`·설문 3종은 상세 응답에 포함하지 않는다
- 시나리오: 한국어 사용자 표시
  Given 계정의 표시 언어를 한국어(`ko`)로 선택한 사용자가
  When 같은 매물 상세를 조회하면
  Then 영문 사용자와 동일한 `code`를 받되 고유 문구와 `{code,label}`의 label은 한국어로 반환된다. 프론트의 필터 요청 code는 언어에 따라 달라지지 않는다
- 시나리오: 리소스 없음
  Given 존재하지 않거나 `PUBLISHED`가 아닌(승인 대기 `PENDING`·반려 `REJECTED`·수정 심사 대기 `UPDATE_PENDING`) 매물이거나 ACTIVE 방 상품이 없는 매물 ID로
  When 상세를 조회하면
  Then `404 Not Found`, `error.code=LISTING_NOT_FOUND`를 반환한다. 임대인이 방금 등록한 자기 매물(`PENDING`)도 이 상세 API로는 조회되지 않는다 — 승인 전 매물을 임대인에게 보여 주는 것은 임대인 전용 조회(US-3-8)가 맡는다
- 시나리오: 공개 상세 조회
  Given 비로그인·온보딩 미완료 사용자 또는 위조 토큰을 보낸 사용자가
  When 상세를 조회하면
  Then `200 OK`로 영어 기본 문구와 `favorited=false`를 반환하고 최근 본 기록은 남기지 않는다. 단, 만료 토큰은 `401 TOKEN_EXPIRED`다
- 시나리오: 경계(최근 본 매물 30개 초과·동일 매물 재조회)
  Given 사용자가 이미 30개의 최근 본 매물을 가졌거나 같은 매물을 다시 보면
  When 상세를 조회하면
  Then 중복은 새 행을 만들지 않고 `viewedAt`만 갱신하며, 30개 초과분은 오래된 기록부터 삭제되고, `GET /api/v2/users/me/recent-listings` 조회 시 `PUBLISHED`이면서 ACTIVE 방 상품이 있는 매물만 최신순 최대 10건 반환된다

### US-3-5 — 찜 토글·찜 목록(인증 필수)

**As a** 관심 매물을 모아두려는 로그인 사용자
**I want** 매물 상세/목록에서 찜을 등록·해제하고 내 찜 목록을 조회
**So that** 마음에 든 매물을 다시 찾아 비교/연락할 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 동시 토글 시 `favoriteCount` 정합성(중복 찜 방지 유니크 제약). 현재 찜 문서와 `favoriteCount`는 같은 MongoDB 안에서 순차적으로 별도 갱신되며 트랜잭션·재계산 배치는 구현되어 있지 않다
- 데이터 관점: (userId, listingId) 유니크 제약으로 멱등 보장, `favoriteCount`는 카운터 컬럼 원자적 증감 또는 집계로 산출, 토글 응답에 최종 상태(`favorited`, `favoriteCount`) 반환. 찜·찜 목록·최근 본 매물은 모두 `me` 스코프이므로 타인 리소스 접근 경로가 없어 별도 `403`은 발생하지 않는다(인증 실패는 `401`)

**AC (Given / When / Then)**

- 시나리오: 정상 찜 신규 등록(생성)
  Given 인증된 사용자가 찜하지 않은 매물에 대해
  When `POST /api/v2/listings/{listingId}/favorite`를 호출하면
  Then `201 Created`로 `data={ "favorited": true, "favoriteCount": <증가값> }`를 반환한다
- 시나리오: 정상 찜 해제
  Given 인증된 사용자가 이미 찜한 매물에 대해
  When `DELETE /api/v2/listings/{listingId}/favorite`를 호출하면
  Then `200 OK`로 `data={ "favorited": false, "favoriteCount": <감소값> }`를 반환한다
- 시나리오: 인증 실패
  Given 토큰이 없거나 만료된 사용자가
  When 찜 등록/해제 또는 찜 목록을 호출하면
  Then `401 Unauthorized`, `error.code=UNAUTHENTICATED`(또는 만료 시 `TOKEN_EXPIRED`)를 반환한다
- 시나리오: 리소스 없음
  Given 존재하지 않거나 `PUBLISHED`가 아닌(승인 대기 `PENDING`·반려 `REJECTED`·수정 심사 대기 `UPDATE_PENDING`) 매물이거나 ACTIVE 방 상품이 없는 매물 ID로
  When 찜 등록을 호출하면
  Then `404 Not Found`, `error.code=LISTING_NOT_FOUND`를 반환한다
- 시나리오: 경계·동시성(중복 찜·동시 요청 멱등)
  Given 사용자가 이미 찜한 매물에 대해
  When 같은 등록 요청이 (네트워크 재시도 등으로) 반복/동시에 들어오면
  Then 유니크 제약으로 중복 행이 생기지 않고, 추가 생성이 없으므로 `200 OK`로 현재 상태 `{ "favorited": true, "favoriteCount": <실제값> }`를 멱등하게 반환한다(별도 `LISTING_ALREADY_FAVORITED` 에러로 보지 않음). 마찬가지로 찜하지 않은 매물 해제는 멱등하게 `200 OK`, `{ "favorited": false, "favoriteCount": <실제값> }`를 반환한다
- 시나리오: 정상 찜 목록 조회
  Given 인증된 사용자가 찜한 매물이 있고
  When `GET /api/v2/users/me/favorites?page=0&size=20`을 호출하면
  Then `200 OK`로 `data.content[]`(모두 `favorited=true`)와 `data.page`를 최근 찜한 순으로 반환하며, 찜이 없으면 빈 배열을 반환한다. 현재 찜 목록 조회는 `PUBLISHED`만 검사하므로 ACTIVE 방 상품이 없는 공개 매물이 빈 `roomOffers[]`로 포함될 수 있다

### US-3-6 — 임대인 매물 등록

**As a** 온보딩을 마친(`ACTIVE`) 임대인(`userType=LANDLORD`)
**I want** 사진을 올리면서 진행 상황을 확인하고, 등록 폼이 받는 지점·건물·공용시설·주변시설·객실(방 상품) 정보와 함께 제출해 매물을 만들기
**So that** 관리자 승인을 거쳐 내 매물이 세입자 탐색·검색에 노출되고 문의를 받을 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 보안(임대인 전용 인가·매물 문서에 저장되는 PII), 입력 검증(카탈로그 대조·교차 필드 검증)
- 데이터 관점: 매물 v2의 **첫 엔드포인트** `POST /api/v2/listings`로 처리한다(조회 계열도 뒤이어 v2로 이관됐다 — 위 **매물 API 버전 경계** 참고). 저장 스키마는 v4([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md))를 그대로 쓰고, 요청 본문은 등록 폼이 실제로 받는 값만 담는다. **서버가 채우는 값은 요청 본문에 없다** — `_id`·`roomOffers[].roomOfferId`(ObjectId 발급)·`schemaVersion`(4)·`status`(`PENDING`)·`favoriteCount`(0)·`createdAt`/`updatedAt`·`rentalType`(`MONTHLY_RENT` 고정)·`pricing.currency`(`KRW` 고정)·`roomOffers[].status`(`ACTIVE`)·`consents.version`(서버 설정값)·`consents.agreedAt`. `landlordId`도 본문이 아니라 **토큰(SecurityContext)** 에서 얻는다.
- 주소 관점: 주소 칸은 자유 입력이 아니라 **검색으로 채운다**([ADR-0042](../adr/0042-road-address-search-with-ncp-geocoding.md)). `GET /api/v1/listings/addresses`(임대인 전용)가 NCP Geocoding으로 표준 도로명 주소·좌표를 돌려주고, 임대인이 고른 후보의 `roadAddress`·`lat`·`lng`를 등록 요청의 `address.fullAddress`·`address.lat`·`address.lng`에 그대로 담는다. **후보를 거르지 않는다** — 등록도 전국을 받고, 카탈로그(`CITY`·`DISTRICT`)가 모르는 지역이면 행정구역을 `ETC`로 저장한다([ADR-0046](../adr/0046-administrative-region-as-catalog-data.md)). 서버는 등록 시점에 지오코딩을 다시 하지 않는다 — 등록마다 외부 왕복과 502 경로가 늘기 때문이며, 좌표 위조는 `PENDING` + 관리자 승인 심사가 흡수한다.
- 사진 관점: **두 단계**다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)). 먼저 `POST /api/v2/listings/images`로 사진을 **한 장씩** 올려 `{ key, url }`을 받고, 등록 요청(`application/json`)에 그 키를 `imageKeys`(1~5개)·`roomOffers[].roomImageKeys`(방마다 2~5개)로 담는다. 요청을 파일마다 가르는 이유는 브라우저가 **요청 단위로만 진행률을 주기 때문**이다 — 한 요청에 몰아 실으면 파일별 진행률·속도를 만들 수 없고 실패한 파일만 다시 올릴 수도 없다. 사진과 방의 짝은 **JSON 구조**가 표현한다(배열 순서가 곧 표시 순서). 임시 사진은 `uploads/{landlordId}/{uuid}.{ext}`에 놓이고, 등록이 확정될 때 `listings/{listingId}/cover/…`·`listings/{listingId}/rooms/{roomOfferId}/…`로 복사된다 — 키가 식별자를 포함하므로 ObjectId를 저장 전에 발급한다. 응답의 URL은 **확정 위치 기준**이라 업로드 때 받은 미리보기 URL과 다르다.
- 정합성 관점: 사진이 매물보다 **먼저** 저장되므로 폼을 버리면 임시 사진이 남는다 — `uploads/` prefix에만 **7일 만료**를 걸어 자동 정리한다. prefix가 갈리므로 만료 규칙이 살아 있는 매물 사진(`listings/`)을 건드릴 수 없다. 등록은 **키 검사 → 복사 → 문서 저장 → 임시본 삭제** 순이고, 복사나 저장이 실패하면 **복사본만** 걷어낸다 — 임시본은 남겨서 사용자가 그대로 다시 제출할 수 있게 한다.
- 인가 관점: [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)에 `POST /api/v2/listings`와 `POST /api/v2/listings/images`를 한 매처로 묶어 **`hasRole("USER")` 명시 매처**로 둔다 — 매처를 두지 않고 `anyRequest().authenticated()`에 맡기면 온보딩 스코프(`ROLE_ONBOARDING`) 토큰이 컨트롤러까지 도달한다(진단 v2가 `permitAll` 매처를 갖는 것과 달리 등록은 열지 않는다). 임대인 여부(`userType=LANDLORD`)는 매처로 표현할 수 없으므로 **두 엔드포인트 모두 서비스에서 재검사**해 세입자면 `403` + `FORBIDDEN`이다. 주소 검색(`GET /api/v1/listings/addresses`)도 같은 이중 인가를 쓰되, 매처를 **공개 조회 매처(`GET /api/v1/listings/*` `permitAll`)보다 먼저** 선언해야 한다 — 먼저 매칭된 규칙이 이기므로 뒤에 두면 인증 규칙이 무시되고 공개 API가 된다.
- 파생·미구현 관점: 폼 한 칸이 스키마 두 필드로 갈라지는 값은 서버가 파싱한다 — 지점 운영층 `1~2` → `building.usedFloorMin`·`usedFloorMax`, 이용 연령대 `20~35` → `ageMin`·`ageMax`. `min ≤ max`와 `usedFloorMax ≤ totalFloors`를 함께 검증한다. 주소는 `address.fullAddress`에 **받은 값 그대로**(정규화 없음) 저장하고, `address.city`·`district`는 도로명 주소를 공백으로 끊어 카탈로그(`CITY`·`DISTRICT`) 한국어 라벨과 완전히 같은 토큰을 찾아 그 코드로 채운다 — 못 찾으면 `ETC`이고 등록은 성공한다(지원 지역 판단은 관리자 승인 심사, [ADR-0046](../adr/0046-administrative-region-as-catalog-data.md)). **`location`(좌표)은 요청의 `address.lat`·`lng`로 채운다**([ADR-0042](../adr/0042-road-address-search-with-ncp-geocoding.md)) — 저장 계약에서도 **필수**이며 도메인 검증과 MongoDB validator가 함께 막는다(changeUnit `0116`). **`nearbyUniversityCodes`는 그 좌표에서 파생한다** — 서버가 대학 좌표 원장(`universities`, 14건)과 대조해 **반경 2km 안의 개별 대학 코드를 모두** 담는다(요청에 대학 칸은 없다). 대학가 밖이면 빈 배열이고 원장이 비어 있어도 등록은 성공한다([ADR-0045](../adr/0045-nearby-university-mapping-from-seeded-coordinates.md)).
- 검증 관점: 코드 필드는 `listingCatalog`의 `(category, code)`에 존재해야 한다([ADR-0037](../adr/0037-listing-localization-and-code-catalog.md)) — 없는 코드는 `400` + `LISTING_UNKNOWN_CATALOG_CODE`다(사용자 오타가 아니라 앱 코드표와 서버 카탈로그의 불일치라 `INVALID_INPUT`과 분리한다 — [error-response-guide](../api/error-response-guide.md)). `roomOffers`는 최소 1개다. **문자열 길이 상한은 두지 않는다**(매물 테이블 정의서에서 길이 컬럼을 삭제한 결정과 일관). 사진은 전용 코드를 쓴다 — 업로드에서 빈 파일은 `400` + `LISTING_IMAGE_REQUIRED`, 10MB 초과는 `413` + `LISTING_IMAGE_TOO_LARGE`, 허용 형식(JPEG·PNG·WebP·HEIC) 밖은 `415` + `LISTING_IMAGE_UNSUPPORTED_TYPE`이다. 등록에서 키 개수 위반은 `400` + `LISTING_IMAGE_REQUIRED`, 남의 키·없는 키·만료된 키는 `400` + `LISTING_IMAGE_KEY_NOT_FOUND`(셋을 구분해 알려주면 남의 키 존재 여부가 새어 나간다)다.
- 동의 관점: 등록 폼은 **이용약관 동의 2종**(개인정보 수집·이용 / 매물 정보 제공 및 노출)을 `consents`로 받으며 **둘 다 필수**다. 하나라도 빠지거나 `false`면 `422` + `LISTING_REQUIRED_AGREEMENT_MISSING`으로 등록 자체가 성립하지 않는다 — 따라서 **저장된 매물은 예외 없이 동의를 마친 매물**이고, 심사 단계가 동의 여부를 판단 기준으로 다시 쓰지 않는다. 서버는 동의 여부와 함께 **약관 버전**(설정값 `app.terms.listing-consent-version`)과 **동의 시각**을 매물 문서에 저장한다 — 동의 사실의 입증 책임이 사업자에게 있어 "코드가 막는다"는 주장만으로는 부족하기 때문이다. 회원 약관 버전(`users.terms_version`)과는 **별개 값**이다: 그쪽은 계정 단위로 가입 시 1회 기록되지만 매물 동의는 **매물마다 등록 시점**이라, 한 임대인이 서로 다른 시기에 올린 매물이 다른 약관 버전을 가질 수 있다. 동의 3종은 세입자 응답에 포함하지 않는다(설문 3종·사업자등록번호와 동일 취급).
- 사업자등록번호 관점: 등록 API는 사업자등록번호를 **형식(숫자 10자리)만 검증해 원문 저장**하고 **진위를 자동 검증하지 않는다** — 무상태 검증 API `POST /api/v1/auth/business/verify`(US-1-8)를 **호출하지 않으며**, 진위 확인은 **관리자가 승인 심사에서 수동으로** 한다(해당 엔드포인트 자체는 임대인이 직접 확인용으로 호출하도록 그대로 둔다). 원문은 매물 문서에만 저장하고 `user.businessRegistrationNumberHash`에는 쓰지 않는다(US-1-9와 일관, [ADR-0039](../adr/0039-listing-schema-v4-registration-form.md) §3, [ADR-0033](../adr/0033-business-registry-verification.md)의 매물 문서 한정 개정).
- 연락처 관점: 등록 폼이 받는 담당자 연락처는 **`contact`(담당자명·지점 대표 전화) 둘뿐**이다. 문자문의 칸은 받지 않는다 — 임대인이 거기 적게 되는 값은 온보딩에서 인증한 개인 번호(`users.phone_number`)라 [ADR-0034](../adr/0034-landlord-phone-sms-verification.md)의 마스킹 대상 PII가 매물 응답으로 평문 공개되고, 계정 단위 값이 매물마다 복제되며, 임대인 웹 로그인이 그 번호를 계정 매칭 키로 쓰기 시작하면(US-1-11·[ADR-0047](../adr/0047-web-local-credentials-and-phone-based-account-linking.md)) 사본이 늘수록 위험만 커진다([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md) Amended). **임대인 개인 연락처는 매물 문서에 복사하지 않는다** — 필요해지면 저장이 아니라 조회 시점에 `user` 모듈에서 가져오고(booking이 신청자 프로필을 실시간 조인하는 방식), 가져온 번호는 여전히 마스킹 대상이라 세입자에게 평문으로 나가지 않는다.
- 응답 관점: `201 Created` + 생성된 매물의 **상세 응답 구조(v4)** 를 반환한다. `contact`(담당자명·지점 대표 전화)는 세입자에게도 공개하므로 포함하고, `businessRegistrationNumber`와 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)은 US-3-4와 동일하게 **응답에서 제외**한다. `status`는 카탈로그 번역 대상이 아니라 **코드 문자열 그대로**(`"PENDING"`) 내려간다.
- 연계 관점: **임대인 매물 수정은 US-3-9**, 등록한 매물을 임대인이 다시 보는 조회는 **US-3-8**이 소유한다. 수정은 이 등록 API가 받는 속성을 그대로 다시 받아 전체를 교체하므로 좌표가 바뀌면 `nearbyUniversityCodes`를 다시 파생하고, **반려된 매물을 고쳐 `PENDING`으로 되돌리는 전이**(재심사 경로 — US-3-7)도 그쪽이 소유한다.
- 후속(이번 범위 아님): 등록 가능 지역 확대(`DISTRICT` 카탈로그와 enum을 함께 늘린다) · 재고.
- 시퀀스: [US-3-6 다이어그램](../architecture/sequence-diagrams/03-listings-favorites/us-3-6-listing-registration.md), API: [03-listings-favorites](../api/specs/03-listings-favorites.md).

**AC (Given / When / Then)**

- 시나리오: 정상 매물 등록
  Given 온보딩을 마친(`ACTIVE`) 임대인(`userType=LANDLORD`)이 정식 access 토큰(`ROLE_USER`)을 보유하고
  When 등록 폼 값(지점 정보·건물 정보·공용시설·주변시설·객실 1개 이상)을 담아 `POST /api/v2/listings`를 호출하면
  Then `201 Created`로 생성된 매물의 상세 응답(v4)을 반환하고, 서버가 `_id`·`roomOffers[].roomOfferId`(ObjectId)·`schemaVersion=4`·`status="PENDING"`·`favoriteCount=0`·`createdAt`/`updatedAt`·`rentalType="MONTHLY_RENT"`·`pricing.currency="KRW"`·`roomOffers[].status="ACTIVE"`를 채운다. `landlordId`는 요청 본문이 아니라 토큰에서 얻은 값으로 저장된다
- 시나리오: 등록 직후에는 세입자에게 보이지 않는다
  Given 위에서 등록한 매물이 `PENDING` 상태로 저장되어 있고
  When 세입자가 `GET /api/v2/listings`(목록)·`GET /api/v2/listings/map`(지도)·`GET /api/v2/listings/{listingId}`(상세)를 호출하면
  Then 목록·지도에는 해당 매물이 포함되지 않고(`PUBLISHED`만 조회 — US-3-1·US-3-2), 상세는 `404 Not Found` + `error.code=LISTING_NOT_FOUND`다. 노출은 관리자 승인(`PENDING → PUBLISHED`, US-3-7)이 있어야 시작된다
- 시나리오: 도로명 주소를 검색해 주소·좌표를 얻는다
  Given 온보딩을 마친 임대인이 등록 폼의 주소 칸에 `신촌로 12`를 입력하고
  When `GET /api/v1/listings/addresses?keyword=신촌로 12`를 호출하면
  Then `200 OK`로 후보 최대 5건(`roadAddress`·`jibunAddress`·`englishAddress`·`lat`·`lng`)을 반환한다. 도로명이 없는 결과는 제외하며, 일치하는 주소가 없으면 `data.items=[]`(에러가 아니다)다
- 시나리오: 등록할 수 없는 지역은 검색 단계에서 알려준다
  Given 임대인이 카탈로그에 없는 지역(예: `분당구 불정로 6`)을 검색했고
  When 응답 항목을 확인하면
  Then 그 후보도 결과에 그대로 포함되고 등록할 수 있다 — 카탈로그가 모르는 지역이면 `address.city`·`district`가 `ETC`로 저장된다
- 시나리오: 주소 검색은 임대인만 쓸 수 있다
  Given 세입자(`userType=TENANT`)의 정식 토큰이거나 토큰이 없고
  When `GET /api/v1/listings/addresses`를 호출하면
  Then 각각 `403` + `FORBIDDEN`, `401` + `UNAUTHENTICATED`다. 같은 `/api/v1/listings/*` 아래지만 **공개 조회와 달리 인증이 필요**하다
- 시나리오: 외부 지오코딩 장애
  Given NCP Geocoding이 오류·타임아웃이거나 서버에 자격증명이 주입되지 않았고
  When `GET /api/v1/listings/addresses`를 호출하면
  Then `502` + `UPSTREAM_ERROR`다. 자격증명이 없으면 **외부로 요청을 보내지 않고** 같은 결과를 준다. 다른 기능은 정상 동작한다
- 시나리오: 검색으로 받은 좌표가 그대로 저장된다
  Given 임대인이 검색 결과의 `roadAddress`·`lat`·`lng`를 담아 등록에 성공했고
  When 저장된 매물 문서를 확인하면
  Then `address.fullAddress`는 보낸 값 그대로이고 `address.city`·`district`는 주소 토큰에서 찾은 카탈로그 코드(못 찾으면 `ETC`)이며, **`location`은 요청의 좌표(`{ type: "Point", coordinates: [lng, lat] }`)로 채워진다**. **`nearbyUniversityCodes`는 그 좌표 반경 2km 안의 대학 코드로 채워진다** — 예: 신촌 좌표는 `["YONSEI","EWHA","HONGIK"]`, 대학가 밖이면 빈 배열([ADR-0045](../adr/0045-nearby-university-mapping-from-seeded-coordinates.md))
- 시나리오: 입력 검증 실패(좌표 누락·범위 위반)
  Given `address.lat`·`address.lng`를 빠뜨렸거나 WGS84 범위 밖의 값을 보내고
  When `POST /api/v2/listings`를 호출하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하고 매물을 생성하지 않는다. 좌표는 주소 검색이 준 값을 되돌려 보내는 필수 값이다
- 시나리오: 설문을 건너뛰고 등록
  Given 선호 국적·계약 어려움 설문에 답할 내용이 없어 키를 생략하거나 `null`·`[]`을 보내고
  When `POST /api/v2/listings`를 호출하면
  Then `201 Created`로 등록되고 저장 문서의 두 필드는 **빈 배열**이다(키는 항상 있다). 요청에서는 선택이지만 저장은 항상 배열이다
- 시나리오: 해당 시설이 하나도 없는 매물 등록
  Given 시설 8종 중 어느 칸에 고를 것이 하나도 없어 `["NONE"]`을 보내고
  When `POST /api/v2/listings`를 호출하면
  Then `201 Created`로 등록되고 저장된 값도 `["NONE"]`이다. 조회 응답에는 다른 코드와 똑같이 `{code, label}`로 나간다
- 시나리오: 수정 요청이 설문을 싣지 않음
  Given 수정 요청에 설문 2종을 담지 않고
  When `PUT /api/v2/listings/{listingId}`를 호출하면
  Then 저장돼 있던 값이 **빈 배열로 교체된다** — 수정은 전체 교체라 생략은 「유지」가 아니라 「지움」이다(`blogUrl`·`serviceFeedback`과 같다). 값을 유지하려면 임대인 상세가 준 값을 그대로 다시 실는다
- 시나리오: 입력 검증 실패(`NONE`을 다른 코드와 함께 보냄)
  Given 시설 8종 중 어느 하나가 `["NONE", "WIFI"]`처럼 `NONE`과 다른 코드를 함께 담고
  When `POST /api/v2/listings`나 `PUT /api/v2/listings/{listingId}`를 호출하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하고 `errors[].field`에 그 필드 이름을 실는다. `NONE`은 「해당 없음」이라 단독으로만 성립한다
- 시나리오: 입력 검증 실패(주소에서 행정구역을 못 뽑음)
  Given 지점 주소가 카탈로그에 없는 시·구여서 행정구역 코드를 판별할 수 없고
  When `POST /api/v2/listings`를 호출하면
  Then `201 Created`로 매물을 생성하되 `address.city`·`district`를 `ETC`로 저장한다. 주소를 이유로 등록을 거절하지 않는다 — 지원 지역인지는 관리자 승인 심사가 판단한다
- 시나리오: 입력 검증 실패(필수값 누락)
  Given 임대인이 지점명·주소·연락처·건물 총 층수·객실 정보 같은 필수값을 빠뜨리고
  When `POST /api/v2/listings`를 호출하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하고 `error.errors[]`에 `field`/`reason`을 담으며 매물을 생성하지 않는다. 본문 자체를 해석할 수 없으면(타입 불일치 등) `400` + `MALFORMED_REQUEST`다
- 시나리오: 입력 검증 실패(카탈로그에 없는 코드)
  Given 요청의 코드 필드(예: `type`·`genderPolicy`·`facilities.*`·`nearbyFacilities`·`languagesSupported`·`roomOffers[].filterTags`)에 `listingCatalog`의 `(category, code)`로 존재하지 않는 값이 섞여 있고
  When `POST /api/v2/listings`를 호출하면
  Then `400 Bad Request`, `error.code=LISTING_UNKNOWN_CATALOG_CODE`를 반환하고 매물을 생성하지 않는다(코드 집합의 정본은 `listingCatalog`다 — [ADR-0037](../adr/0037-listing-localization-and-code-catalog.md)). 사용자가 오타를 낸 것이 아니라 앱이 들고 있는 코드표가 서버 카탈로그와 어긋났다는 뜻이라 `INVALID_INPUT`과 분리하며, 프론트는 입력 교정 대신 코드 카탈로그 재조회를 안내한다
- 시나리오: 입력 검증 실패(운영층·이용 연령대 `min~max` 형식)
  Given 지점 운영층이나 이용 연령대를 `1~2`·`20~35` 형식이 아닌 값(`1-2`·`2~1`처럼 형식 위반이거나 최소>최대)으로 보내거나 `usedFloorMax`가 건물 총 층수(`totalFloors`)를 넘으면
  When `POST /api/v2/listings`를 호출하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환한다. 형식 위반은 필드를 특정할 수 있으므로 `error.errors[]`에 담고, `min ≤ max`·`usedFloorMax ≤ totalFloors` 같은 교차 필드 검증은 `errors=[]`일 수 있다
- 시나리오: 입력 검증 실패(객실 최소 개수)
  Given `roomOffers`가 비어 있고
  When `POST /api/v2/listings`를 호출하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하고 매물을 생성하지 않는다(객실 최소 1개). 반면 지점 소개글·이용 조건 같은 자유 입력 문자열에는 **길이 상한을 두지 않아** 길다는 이유로 거절하지 않는다
- 시나리오: 사진을 한 장씩 올려 키를 받는다
  Given 임대인이 등록 폼에서 사진 한 장을 고르고
  When `POST /api/v2/listings/images`에 그 파일 하나만 실어 호출하면
  Then `201 Created`와 함께 `key`(`uploads/{내 landlordId}/{uuid}.{ext}` 형태)·`url`을 반환하고, 그 URL로 사진이 보인다. 매물은 아직 만들어지지 않는다
- 시나리오: 업로드 검증 실패(빈 파일·크기·형식)
  Given `file` part가 비었거나, 10MB를 넘거나, 형식이 JPEG·PNG·WebP·HEIC가 아니고(part 이름이 틀려 `file`이 아예 없으면 `400` + `MALFORMED_REQUEST`다)
  When `POST /api/v2/listings/images`를 호출하면
  Then 각각 `400` + `LISTING_IMAGE_REQUIRED`, `413` + `LISTING_IMAGE_TOO_LARGE`, `415` + `LISTING_IMAGE_UNSUPPORTED_TYPE`을 반환하고 **저장소에 아무것도 올라가지 않는다**(검증이 업로드보다 앞선다)
- 시나리오: 올린 키로 등록하면 사진이 확정 위치로 옮겨 간다
  Given 임대인이 지점 사진 2장과 객실 1개분 사진 2장을 미리 올려 키 4개를 받았고
  When 그 키를 `imageKeys`·`roomOffers[].roomImageKeys`에 담아 `POST /api/v2/listings`를 호출하면
  Then `201 Created`를 반환하고 응답의 `imageUrls`·`roomOffers[].roomImageUrls`가 `https://{CDN}/listings/{listingId}/cover/…`·`…/rooms/{roomOfferId}/…` 형태이며(보낸 순서 유지), 그 키로 실제 객체가 존재하고 **`uploads/`의 임시본은 사라진다**
- 시나리오: 사진 키 개수 위반
  Given `imageKeys`가 1~5개가 아니거나 어떤 방의 `roomImageKeys`가 2~5개가 아니고
  When `POST /api/v2/listings`를 호출하면
  Then `400 Bad Request`, `error.code=LISTING_IMAGE_REQUIRED`를 반환하고 매물을 생성하지 않는다(지점 6장 이상, 방 1장이거나 6장 이상). 다만 배열이 아예 비었거나 누락된 경우는 본문 검증(`@NotEmpty`)이 먼저 걸려 `400` + `INVALID_INPUT`이고 위반 필드가 `errors[]`에 실린다
- 시나리오: 남의 키·없는 키·만료된 키
  Given 등록 요청의 사진 키가 다른 임대인의 `uploads/…`를 가리키거나, 존재하지 않거나, 올린 지 7일이 지났고
  When `POST /api/v2/listings`를 호출하면
  Then 셋 다 `400 Bad Request`, `error.code=LISTING_IMAGE_KEY_NOT_FOUND`를 반환하고 매물을 생성하지 않는다. 구분해 알려주지 않는 것은 남의 키가 있는지 없는지가 새어 나가지 않게 하기 위해서다
- 시나리오: 저장 실패 시 복사본만 되돌리고 임시본은 남긴다
  Given 사진 복사는 성공했지만 매물 문서 저장이 실패했고
  When 그 요청이 끝나면
  Then 매물은 생성되지 않고 `listings/` 아래 복사본도 남지 않지만, **`uploads/`의 임시본은 그대로 남아** 사용자가 같은 키로 다시 제출할 수 있다. 복사가 원본을 찾지 못해 실패하면 `400` + `LISTING_IMAGE_KEY_NOT_FOUND`이고, 그 밖의 저장소 실패만 `502` + `UPSTREAM_ERROR`다
- 시나리오: 등록하지 않은 사진은 7일 뒤 사라진다
  Given 임대인이 사진만 올리고 등록을 끝내지 않았고
  When 7일이 지나면
  Then 그 임시 사진은 자동으로 삭제되고, 같은 키로 등록을 시도하면 `400` + `LISTING_IMAGE_KEY_NOT_FOUND`다. **`listings/` 아래의 확정된 사진은 이 규칙에 걸리지 않는다**
- 시나리오: 사업자등록번호는 형식만 보고 저장한다
  Given 형식(숫자 10자리)은 맞지만 실제로는 폐업·미등록일 수도 있는 사업자등록번호를 담아
  When `POST /api/v2/listings`를 호출하면
  Then 서버는 외부 검증 API(`POST /api/v1/auth/business/verify`, US-1-8)를 **호출하지 않고** `201 Created`로 매물을 `PENDING`으로 저장하며, 원문을 매물 문서에 보관하되 응답에는 포함하지 않는다. 진위는 관리자가 승인 심사에서 수동으로 확인한다. 형식 자체가 어긋나면(자릿수·숫자 아님) `400` + `INVALID_INPUT`이다
- 시나리오: 인증·권한(임대인이 아닌 사용자)
  Given 온보딩을 마친(`ACTIVE`) 세입자(`userType=TENANT`)가 정식 토큰으로
  When `POST /api/v2/listings`나 `POST /api/v2/listings/images`를 호출하면
  Then `403 Forbidden`, `error.code=FORBIDDEN`을 반환하고 매물도 임시 사진도 만들어지지 않는다(역할 검사는 SecurityConfig 매처가 아니라 서비스에서 수행한다)
- 시나리오: 인증·권한(온보딩 미완료·토큰 없음/만료)
  Given 온보딩 스코프(`PENDING`·`TERMS_AGREED`, `ROLE_ONBOARDING`) 토큰이거나 `Authorization` 헤더가 없거나 토큰이 위조·만료되었고
  When `POST /api/v2/listings`를 호출하면
  Then 온보딩 토큰은 `403` + `AUTH_ONBOARDING_REQUIRED`(SecurityConfig의 `hasRole("USER")` 명시 매처에서 차단 — 매처가 없으면 `anyRequest().authenticated()`로 떨어져 컨트롤러까지 도달한다), 토큰 부재·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`다
- 시나리오: 응답 노출 범위
  Given 등록이 성공했고
  When `201` 응답 본문을 보면
  Then `contact`(담당자명·지점 대표 전화)는 포함되고(세입자에게도 공개하는 값 — US-3-4), 문자문의 번호는 요청·응답 어디에도 없으며, `businessRegistrationNumber`와 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)은 제외되며, `status`는 `{code,label}`이 아니라 코드 문자열 `"PENDING"` 그대로다
- 시나리오: 역 이름으로 인근 역 검색
  Given 임대인이 등록 폼의 역 칸에 "신촌"을 입력하고 주소 검색이 준 좌표를 함께 보내면
  When `GET /api/v1/listings/stations?keyword=신촌&lat=…&lng=…`를 호출하면
  Then `200 OK`로 `data.items[]`에 `name`·`roadAddress`·`lat`·`lng`·`distanceMeters`·`suggestedWalkMinutes`가 **거리순**으로 온다. 좌표를 빼면 정확도순이고 `distanceMeters`·`suggestedWalkMinutes`는 `null`이다
- 시나리오: 좌표로 인근 역 목록
  Given 임대인이 아무것도 입력하지 않았고
  When `GET /api/v1/listings/stations/nearby?lat=…&lng=…`를 호출하면
  Then `200 OK`로 반경 2km 안의 지하철역이 가까운 순으로 온다. 반경 내 역이 없으면 에러가 아니라 `data.items=[]`다
- 시나리오: 역 검색 입력 검증
  Given 클라이언트가
  When `keyword`를 누락·공백으로 보내거나 50자를 넘기거나, `lat`·`lng` 중 **하나만** 보내거나 WGS84 범위를 벗어나면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환한다
- 시나리오: 역 검색 인가
  Given 세입자(`userType=TENANT`) 정식 토큰이거나 토큰이 없고
  When 역 검색 API를 호출하면
  Then 세입자는 `403 FORBIDDEN`, 토큰 없음·위조는 `401 UNAUTHENTICATED`다 — 같은 `/api/v1/listings/*` 아래지만 공개 조회와 달리 인증이 필요하다
- 시나리오: `walkMinutes` 누락은 400이다
  Given 등록 요청의 `nearestTransit`에서 `walkMinutes` 키를 빼고
  When `POST /api/v2/listings`를 호출하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`이고 `errors[]`에 `nearestTransit.walkMinutes`가 실린다(예전에는 조용히 `0`이 저장됐다)

- 시나리오: 이용약관 동의 2종이 모두 있어야 등록된다
  Given 온보딩을 마친 임대인이 등록 폼에서 개인정보 수집·이용 동의와 매물 정보 제공 및 노출 동의를 모두 체크했고
  When `consents.privacyPolicyAgreed=true`·`consents.listingExposureAgreed=true`로 `POST /api/v2/listings`를 호출하면
  Then `201 Created`이고 매물 문서에 동의 여부와 함께 **약관 버전**(설정값)·**동의 시각**이 저장된다
- 시나리오: 동의가 빠지면 등록이 거절된다
  Given 임대인이 두 동의 중 하나를 체크하지 않았거나 `consents` 자체를 보내지 않았고
  When `POST /api/v2/listings`를 호출하면
  Then `422 Unprocessable Entity`, `error.code=LISTING_REQUIRED_AGREEMENT_MISSING`을 반환하고 매물도 확정 사진도 만들어지지 않는다

### US-3-7 — 관리자 매물 심사(승인·반려)

**As a** 관리자 계정(`userType=ADMIN`, `ACTIVE`)
**I want** 임대인이 올린 매물을 상태와 무관하게 조회하고, 내용을 확인해 승인하거나 사유와 함께 반려하기
**So that** 검증된 매물만 세입자 탐색에 노출되고, 문제가 있는 매물은 임대인이 무엇을 고쳐야 하는지 알 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 보안(관리자 전용 인가·심사 응답의 노출 범위), 감사(누가 무엇을 승인·반려했는지 로그)
- 계정 관점: **관리자는 회원가입 경로가 없다.** 운영자가 관리자 전용 계정을 임대인 웹 가입 흐름으로 만든 뒤 DB에서 `users.user_type`을 `ADMIN`으로 직접 승격한다. 로그인·계정 연동 방식은 임대인과 **완전히 동일**하다(이메일·비밀번호 `local_accounts`, 가입 전 SMS 인증, HttpOnly 쿠키 refresh) — 관리자 전용 인증 경로를 만들지 않는다. `userType`은 온보딩으로 확정되고 이후 불변이라는 규칙에 **`ADMIN` 승격만 예외**다.
- 역할 관점: `ADMIN`은 세입자·임대인과 **병존하지 않는 제3의 유형**이다. 승격하면 임대인 능력을 잃으므로(매물 등록 불가) **활동 중인 계정을 승격하지 않고 전용 계정을 새로 만든다**. 관리자는 **관리자 전용 API와 계정 관리(프로필·로그아웃)·공개 조회·학습 콘텐츠만** 호출할 수 있고, 세입자·임대인 기능(찜·예약·채팅·커뮤니티)은 각 서비스의 허용 목록 게이트가 `403 FORBIDDEN`으로 막는다.
- 인가 관점: [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)에 `/api/v1/admin/**`를 **`hasRole("USER")` 명시 매처**로 둔다 — 매처가 없으면 `anyRequest().authenticated()`로 떨어져 온보딩 스코프 토큰이 컨트롤러까지 도달한다. 관리자 여부(`userType=ADMIN`)는 매처로 표현할 수 없으므로 **서비스에서 재검사**해 `403` + `FORBIDDEN`이다. 임대인 게이트와 같은 이중 인가이며, 토큰에는 관리자 여부를 담지 않아 **권한 부여·회수가 즉시 반영**된다.
- 상태 관점: 매물 상태는 `PENDING`(심사 대기) · `PUBLISHED`(승인·공개) · `REJECTED`(반려) · `UPDATE_PENDING`(공개 중인 매물을 임대인이 고쳐 올린 수정 심사 대기 — US-3-9) **4종**이고, **승인·반려 모두 어느 상태에서든** 할 수 있다. **`UPDATE_PENDING`도 예외가 아니다** — 승인하면 `PUBLISHED`로 돌아가 다시 노출되고, 반려하면 `REJECTED`가 된다(수정본을 따로 보관하지 않으므로 반려해도 직전 공개본으로 되돌아가지는 않는다). 전이에 제약을 두지 않는 이유는 **관리자의 오판을 되돌릴 수단이 서버에 있어야** 하기 때문이다 — 잘못 반려한 매물을 되살리는 **재승인**, 공개 후 문제가 발견된 매물을 내리는 **사후 반려**, 이미 반려한 매물의 **사유 정정**이 모두 정상 경로다. 제약을 걸었다면 임대인 수정 API(US-3-9)가 나오기 전까지 잘못 처리된 매물이 서버에서 손댈 수 없는 상태로 묶였을 것이다. 다만 **이미 공개 중인 매물의 재승인은 아무 일도 하지 않는다** — 같은 값으로 저장해도 결과는 같지만 `updatedAt`이 바뀌면 목록 기본 정렬에서 그 매물만 위로 올라가기 때문이다.
- 조회 관점: 목록은 **모든 상태**를 대상으로 하며 `status` 쿼리 파라미터로 상태별 필터가 가능하다(다중 값 허용, 생략하면 전체). 세입자 조회 3종은 저장소에서 `PUBLISHED`를 고정하고 있으므로 재사용하지 않고 **심사 전용 조회 경로**를 따로 둔다 — 세입자 경로의 안전장치를 풀지 않기 위해서다. 기본 정렬은 등록 최신순이다.
- 노출 관점: 심사 응답은 **매물 문서에 저장된 모든 필드**를 담는다 — `landlordId`·`businessRegistrationNumber`·설문 3종·동의 3종·`rejectionReason`까지 세입자 응답이 감추는 값을 감추지 않는다. 표시 여부는 관리자 화면이 정한다. 노출해도 되는 근거는 매물 문서에 **임대인 개인 연락처가 저장되지 않기 때문**이고([ADR-0039](../adr/0039-listing-schema-v4-registration-form.md) Amended), `businessRegistrationNumber`는 오히려 **관리자가 심사에서 진위를 수동 확인해야 하는 값**이다([ADR-0033](../adr/0033-business-registry-verification.md) 개정). 표시 언어는 임대인 화면과 같이 한국어 고정이다.
- 반려 관점: 반려는 **사유가 필수**다(1~500자). 상태를 가리지 않으므로 같은 매물을 여러 번 반려해 사유를 고쳐 쓸 수 있다. 승인과 반려를 하나의 상태 변경 API로 묶지 않고 **액션 두 개로 나눈다** — 그래야 "반려에는 사유가 필요하다"를 요청 타입으로 강제할 수 있고, 승인 요청에 사유가 실려 오는 경우가 구조적으로 생기지 않는다. 사유는 임대인만 읽는 값이라 번역하지 않는다. 승인 시에는 이전 반려 사유를 지운다 — 수정을 거쳐 다시 올라온 매물이 지난 사유를 달고 공개되지 않게 하기 위해서다.
- 감사 관점: 승인·반려는 **누가 어느 매물을 어떻게 처리했는지 로그로 남긴다**. 심사 이력 테이블은 이번 범위가 아니다(후속).
- 후속(이번 범위 아님): 승인·반려 시 임대인 알림(도메인 이벤트) · 심사 이력 테이블 · 관리자 웹 프론트.
- 시퀀스: [US-3-7 다이어그램](../architecture/sequence-diagrams/03-listings-favorites/us-3-7-admin-listing-review.md), API: [03-listings-favorites](../api/specs/03-listings-favorites.md).

**AC (Given / When / Then)**

- 시나리오: 모든 상태의 매물을 조회한다
  Given 관리자(`userType=ADMIN`, `ACTIVE`)가 정식 access 토큰을 보유하고 `PENDING`·`PUBLISHED`·`REJECTED`·`UPDATE_PENDING` 매물이 섞여 있고
  When `GET /api/v1/admin/listings`를 호출하면
  Then `200 OK`로 **상태와 무관하게** 전부 조회되고, 등록 최신순 페이지네이션이 적용된다
- 시나리오: 상태별로 거른다
  Given 위와 같고
  When `GET /api/v1/admin/listings?status=PENDING`을 호출하면
  Then 심사 대기 매물만 반환한다. `status`를 여러 개 보내면 그 집합에 속한 매물을, 생략하면 전체를 반환한다
- 시나리오: 심사 상세는 저장된 전 필드를 준다
  Given 관리자가 심사 대기 매물 하나를 골랐고
  When `GET /api/v1/admin/listings/{listingId}`를 호출하면
  Then `200 OK`로 `landlordId`·`businessRegistrationNumber`·설문 3종·동의 3종·`rejectionReason`을 포함한 전 필드를 반환한다(세입자 상세와 달리 감추지 않는다)
- 시나리오: 승인하면 세입자에게 보인다
  Given `PENDING` 매물이 있고
  When 관리자가 `POST /api/v1/admin/listings/{listingId}/approval`을 호출하면
  Then `200 OK`이고 상태가 `PUBLISHED`로 바뀌며 `rejectionReason`이 비워진다. 이후 세입자의 목록·지도·상세 조회에 그 매물이 나타난다
- 시나리오: 사유와 함께 반려한다
  Given `PENDING` 매물이 있고
  When 관리자가 `POST /api/v1/admin/listings/{listingId}/rejection`을 `{ "reason": "사업자등록번호와 주소가 일치하지 않습니다" }`로 호출하면
  Then `200 OK`이고 상태가 `REJECTED`로 바뀌며 그 사유가 저장된다. 세입자 조회에는 여전히 나타나지 않는다
- 시나리오: 반려 사유는 필수다
  Given 관리자가 반려하려 하고
  When `reason`을 누락·공백으로 보내거나 500자를 넘기면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하고 상태가 바뀌지 않는다
- 시나리오: 잘못 반려한 매물을 되살린다
  Given 매물이 `REJECTED`이고
  When 관리자가 승인을 호출하면
  Then `200 OK`이고 상태가 `PUBLISHED`로 바뀌며 `rejectionReason`이 비워진다. 상태로 인한 거절은 없다
- 시나리오: 수정 심사 대기 매물도 승인·반려할 수 있다
  Given 임대인이 공개 중인 매물을 고쳐 제출해 상태가 `UPDATE_PENDING`이고(US-3-9)
  When 관리자가 승인 또는 사유와 함께 반려를 호출하면
  Then 승인은 `200 OK`이고 상태가 `PUBLISHED`로 돌아가 세입자 조회에 다시 나타나며, 반려는 `200 OK`이고 상태가 `REJECTED`가 된다. 상태로 인한 거절은 없고, 반려해도 **직전에 공개돼 있던 본문으로 되돌아가지는 않는다**(수정본을 따로 보관하지 않는다 — US-3-9)
- 시나리오: 이미 공개된 매물을 다시 승인해도 달라지지 않는다
  Given 매물이 `PUBLISHED`이고
  When 관리자가 승인을 다시 호출하면
  Then `200 OK`이고 상태·`updatedAt` 모두 그대로다. 목록 정렬이 흔들리지 않는다
- 시나리오: 공개된 매물을 내린다(사후 반려)
  Given 매물이 `PUBLISHED`로 세입자 조회에 노출되고 있고
  When 관리자가 사유와 함께 반려를 호출하면
  Then `200 OK`이고 상태가 `REJECTED`로 바뀌어 이후 세입자 조회에서 사라진다. 반려는 상태를 가리지 않으므로 `409`가 아니다
- 시나리오: 반려 사유를 고쳐 쓴다
  Given 매물이 이미 `REJECTED`이고
  When 관리자가 다른 사유로 반려를 다시 호출하면
  Then `200 OK`이고 `rejectionReason`이 새 사유로 바뀐다
- 시나리오: 존재하지 않는 매물
  Given 없는 `listingId`이거나 ObjectId 형식이 아니고
  When 심사 상세·승인·반려를 호출하면
  Then `404 Not Found`, `error.code=LISTING_NOT_FOUND`다
- 시나리오: 관리자가 아니면 막힌다
  Given 세입자·임대인의 정식 토큰이고
  When `/api/v1/admin/listings` 아래 어느 엔드포인트든 호출하면
  Then `403 Forbidden`, `error.code=FORBIDDEN`이다(역할 검사는 SecurityConfig 매처가 아니라 서비스에서 수행한다)
- 시나리오: 관리자는 세입자·임대인 API를 쓸 수 없다
  Given 관리자의 정식 토큰이고
  When 찜 토글·예약 조회·채팅방 목록·커뮤니티 글 작성 등 사용자 기능을 호출하면
  Then `403 Forbidden`, `error.code=FORBIDDEN`이다. 다만 프로필 조회·수정, 로그아웃, 매물 공개 조회, 진단·퀴즈·생활 팁은 그대로 사용할 수 있다
- 시나리오: 인증·권한(온보딩 미완료·토큰 없음/만료)
  Given 온보딩 스코프(`ROLE_ONBOARDING`) 토큰이거나 `Authorization` 헤더가 없거나 토큰이 위조·만료되었고
  When 관리자 API를 호출하면
  Then 온보딩 토큰은 `403` + `AUTH_ONBOARDING_REQUIRED`, 토큰 부재·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`다

### US-3-8 — 임대인 전용 매물 조회(내 매물 목록·상세)

**As a** 온보딩을 마친(`ACTIVE`) 임대인(`userType=LANDLORD`)
**I want** 내가 등록한 매물을 상태와 무관하게 목록·상세로 조회하고, 반려된 매물은 그 사유까지 확인하기
**So that** 심사가 어디까지 진행됐는지 알고, 고쳐야 할 매물의 현재 값을 그대로 수정 화면에 채울 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 보안(임대인 전용 인가·소유권 검사), 조회 성능(임대인·상태·최근 수정순 복합 인덱스)
- 경로 관점: `GET /api/v2/users/me/listings`(목록) · `GET /api/v2/users/me/listings/{listingId}`(상세)로 **`me` 스코프 아래**에 둔다. `/api/v2/listings/mine`으로 두지 않는 이유는 공개 조회 매처(`GET /api/v2/listings/*` `permitAll`)에 먼저 걸려 **비로그인에 열리기 때문**이다 — 먼저 매칭된 규칙이 이긴다. 다만 `me` 스코프도 자동으로 덮이지 않으므로 [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)의 정확 경로 나열에 두 경로를 **명시 매처로 추가**한다.
- 인가 관점: 등록(US-3-6)과 같은 **이중 인가**다 — 매처는 `hasRole("USER")`로 온보딩 스코프(`ROLE_ONBOARDING`) 토큰을 막고, 임대인 여부(`userType=LANDLORD`)는 매처로 표현할 수 없으므로 **서비스에서 재검사**해 세입자·관리자면 `403` + `FORBIDDEN`이다.
- 소유권 관점: 상세는 매물의 `landlordId`가 요청자와 같은지 확인하고, 다르면 **`403`이 아니라 `404` + `LISTING_NOT_FOUND`** 다. 존재를 숨기는 booking·chat·매물 조회의 기존 관용구와 같으며, 한 API가 상황에 따라 `403`과 `404`를 오가면 그 차이 자체가 남의 매물이 존재한다는 사실을 누설한다.
- 조회 범위 관점: 세입자 조회와 달리 **상태를 가리지 않는다** — `PENDING`·`PUBLISHED`·`REJECTED`·`UPDATE_PENDING`이 모두 나온다. 세입자 조회 3종은 저장소에서 `PUBLISHED`를 고정하고 있으므로 재사용하지 않고 **임대인 전용 조회 경로를 따로 둔다** — 심사 전용 조회를 따로 둔 US-3-7과 같은 이유로, 세입자 경로의 안전장치를 풀지 않기 위해서다.
- 필터·정렬 관점: `status` 쿼리 파라미터로 상태별 필터가 가능하다(관리자와 같은 다중 값 계약, 생략하면 전체). 정렬은 **`updatedAt` 내림차순 고정**이고 `sort` 파라미터를 열지 않는다 — 상태 필터가 붙어야 임대인·상태·수정시각 복합 인덱스의 중간 키가 묶여 정렬까지 인덱스로 받쳐지고, 지금 정렬 파라미터를 열면 세입자 목록의 `LISTING_INVALID_SORT_PARAM` 계약과 어긋나는 계약이 하나 더 생긴다(나중에 여는 것은 하위 호환을 깨지 않는다).
- 노출 관점(목록): 목록 항목은 **세입자 목록 카드와 같은 무게에 `rejectionReason` 하나만 더한 것**이다(상태는 카드가 이미 갖는다). 관리자 목록처럼 항목마다 상세 전체를 담지 않는다 — 임대인이 자기 목록 화면에서 자기 사업자등록번호·설문·동의 시각을 다시 볼 이유가 없고, 그 값들은 수정 폼이 쓰는 값이라 상세가 준다.
- 노출 관점(상세): 상세의 계약은 **"수정 요청에 실을 수 있는 전 필드 + 읽기 전용 표시값"** 이다 — 수정이 전체 교체(US-3-9)라 화면이 **등록 폼 전 필드를 프리필**해야 하므로 편집 가능한 값이 하나도 빠지면 안 된다. 세입자 상세가 감추는 `businessRegistrationNumber`와 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)을 포함하고, 라운드트립에 필요한 `roomOffers[].roomOfferId`와 **사진 키**(`imageKeys`·`roomOffers[].roomImageKeys`)를 URL과 함께 내려준다. 읽기 전용으로 `status`·`rejectionReason`을 더하고, `consents`는 **최초 동의 이력 표시용**으로 싣는다 — 수정 요청에서 체크박스를 새로 받으므로(US-3-9) 프리필이 아니라 참고값이다. **`INACTIVE` 방 상품도 이 응답에만 보인다** — 세입자·관리자 응답은 `ACTIVE` 방만 노출하므로, 내려둔 방을 다시 살리려면 임대인이 그 방을 볼 수 있어야 한다.
- 언어 관점: 표시 언어는 계정에서 선택한 언어(`users.lang`)를 따른다 — 관리자 심사 응답이 한국어 고정인 것과 다르다. 카탈로그 번역 컨텍스트는 목록 항목마다가 아니라 **한 번만** 만든다.
- 후속(이번 범위 아님): 임대인 매물 삭제 · 수정 신청 취소 · 정렬 파라미터 개방 · 심사 이력 조회.
- 시퀀스: [US-3-8 다이어그램](../architecture/sequence-diagrams/03-listings-favorites/us-3-8-landlord-listing-query.md), API: [03-listings-favorites](../api/specs/03-listings-favorites.md).

**AC (Given / When / Then)**

- 시나리오: 내 매물을 상태와 무관하게 목록으로 본다
  Given 온보딩을 마친(`ACTIVE`) 임대인이 정식 access 토큰(`ROLE_USER`)을 보유하고 자기 매물이 `PENDING`·`PUBLISHED`·`REJECTED`·`UPDATE_PENDING`으로 섞여 있고
  When `GET /api/v2/users/me/listings?page=0&size=20`을 호출하면
  Then `200 OK`로 `data.content[]`에 **상태와 무관하게** 자기 매물만 담기고 `data.page`에 `number/size/totalElements/totalPages/hasNext`가 포함된다. 각 항목은 세입자 목록 카드와 같은 필드에 `status`와 `rejectionReason`이 함께 실린다
- 시나리오: 상태별로 거른다
  Given 위와 같고
  When `GET /api/v2/users/me/listings?status=REJECTED`를 호출하면
  Then 반려된 내 매물만 반환한다. `status`를 여러 개 보내면 그 집합에 속한 매물을, 생략하면 전체를 반환한다
- 시나리오: 정렬은 최근 수정순 고정이다
  Given 내 매물이 여러 건 있고
  When 목록을 호출하면
  Then `updatedAt` 내림차순으로 반환된다. `sort` 파라미터는 받지 않으므로 보내도 정렬이 달라지지 않는다
- 시나리오: 남의 매물은 섞이지 않는다
  Given 다른 임대인의 매물이 같은 컬렉션에 존재하고
  When 목록을 호출하면
  Then 요청자의 `landlordId`와 일치하는 매물만 반환되고 `data.page.totalElements`에도 남의 매물이 잡히지 않는다
- 시나리오: 상세는 수정 폼이 필요한 값을 모두 준다
  Given 임대인이 자기 매물 하나를 골랐고
  When `GET /api/v2/users/me/listings/{listingId}`를 호출하면
  Then `200 OK`로 등록 요청에 실었던 전 필드(지점·건물·공용시설·주변시설·객실·설문 3종·`businessRegistrationNumber`)와 `roomOffers[].roomOfferId`·사진 키(`imageKeys`·`roomOffers[].roomImageKeys`)·사진 URL을 반환하고, 읽기 전용으로 `status`·`rejectionReason`, 참고값으로 `consents`(약관 버전·최초 동의 시각)를 함께 반환한다
- 시나리오: 내려둔 방 상품도 임대인에게는 보인다
  Given 매물에 `status=INACTIVE`인 방 상품이 있고
  When 임대인 상세를 조회하면
  Then `roomOffers[]`에 `INACTIVE` 방도 자기 `status`와 함께 포함된다(같은 매물의 세입자 상세·관리자 상세에는 `ACTIVE` 방만 나타난다)
- 시나리오: 반려 사유를 확인한다
  Given 관리자가 사유와 함께 반려한(`REJECTED`) 내 매물이 있고
  When 목록 또는 상세를 조회하면
  Then 두 응답 모두 `rejectionReason`에 그 사유 원문을 담는다(사유는 임대인만 읽는 값이라 번역하지 않는다)
- 시나리오: 남의 매물 상세는 존재조차 알려주지 않는다
  Given 다른 임대인이 소유한 매물의 `listingId`이고
  When 임대인 상세를 호출하면
  Then `403`이 아니라 `404 Not Found`, `error.code=LISTING_NOT_FOUND`를 반환한다
- 시나리오: 존재하지 않는 매물
  Given 없는 `listingId`이거나 ObjectId 형식이 아니고
  When 임대인 상세를 호출하면
  Then `404 Not Found`, `error.code=LISTING_NOT_FOUND`다
- 시나리오: 임대인이 아니면 막힌다
  Given 세입자(`TENANT`) 또는 관리자(`ADMIN`)의 정식 토큰이고
  When 임대인 목록·상세를 호출하면
  Then `403 Forbidden`, `error.code=FORBIDDEN`이다(역할 검사는 SecurityConfig 매처가 아니라 서비스에서 수행한다)
- 시나리오: 경계(매물 없음·페이지 범위 초과)
  Given 아직 등록한 매물이 없거나 마지막 페이지를 넘는 `page`가 요청되면
  When 목록을 호출하면
  Then `200 OK`로 `data.content`는 빈 배열, `data.page.hasNext=false`를 반환한다(에러 아님)
- 시나리오: 표시 언어를 따른다
  Given 계정의 표시 언어를 한국어(`ko`)로 선택한 임대인이
  When 목록·상세를 조회하면
  Then 고유 문구와 `{code,label}`의 label은 한국어로 반환된다. `status`·`rejectionReason`·ID·좌표·가격·사진 키는 번역 대상이 아니다
- 시나리오: 인증·권한(온보딩 미완료·토큰 없음/만료)
  Given 온보딩 스코프(`ROLE_ONBOARDING`) 토큰이거나 `Authorization` 헤더가 없거나 토큰이 위조·만료되었고
  When 임대인 목록·상세를 호출하면
  Then 온보딩 토큰은 `403` + `AUTH_ONBOARDING_REQUIRED`, 토큰 부재·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`다

### US-3-9 — 임대인 매물 수정(재심사·수정 심사)

**As a** 온보딩을 마친(`ACTIVE`) 임대인(`userType=LANDLORD`)
**I want** 반려된 내 매물이나 이미 공개된 내 매물을 등록 때와 같은 폼으로 다시 제출해 고치기
**So that** 반려 사유를 반영해 재심사를 받고, 공개 중인 매물의 정보도 심사를 거쳐 최신으로 유지할 수 있다

- 메타: 우선순위 **High**, 관련 NFR — 보안(임대인 전용 인가·소유권), 동시성(관리자 심사와의 경합), 정합성(사진 확정·삭제 순서와 상태 전이)
- 경로·메서드 관점: `PUT /api/v2/listings/{listingId}` → `200 OK`이며 등록(`POST /api/v2/listings`)의 형제로 같은 v4 스키마를 쓴다. **부분 수정(PATCH)이 아니라 전체 교체다** — `location`(좌표)·`address.city`/`district`·`nearbyUniversityCodes`가 모두 주소에서 **파생**되는 값이라 일부만 보내면 파생값이 본문과 어긋난 상태로 남는다. 전체 교체는 PUT이라는 [api-design-guide](../api/api-design-guide.md) 규약을 따르며, 이 저장소의 **첫 PUT 엔드포인트**다. 인가 매처는 등록과 별개로 `PUT /api/v2/listings/*`를 **명시**한다 — 공개 조회 매처는 GET만 열고 있어 명시하지 않으면 `anyRequest().authenticated()`로 떨어져 온보딩 스코프 토큰이 컨트롤러까지 도달한다. 임대인 여부는 등록·조회와 같이 **서비스에서 재검사**해 `403` + `FORBIDDEN`이고, 남의 매물은 **`404` + `LISTING_NOT_FOUND`** 다(US-3-8과 같은 판정).
- 요청 관점: **등록(US-3-6)이 받는 속성을 그대로 다시 보낸다** — 편집 대상에서 빠지는 값은 없고, 보내지 않은 값은 지워진다. 등록 요청과 다른 곳은 **두 곳뿐**이다: `roomOffers[]`가 `roomOfferId`(없으면 신규 방)와 `status`(`ACTIVE` 또는 `INACTIVE`)를 함께 받고, 사진 키 배열이 임시 키뿐 아니라 **이미 확정된 키도 받는다**. `status`·`rejectionReason`·`favoriteCount`·`landlordId`처럼 서버가 소유하는 값은 등록 요청에도 없던 값이라 수정 요청에도 칸을 두지 않는다 — 보내도 저장에 닿지 않는다. 화면이 채워 넣을 현재 값은 임대인 상세(US-3-8)가 준다.
- 상태 전이 관점: 수정할 수 있는 상태는 둘뿐이다 — **`REJECTED` + 수정 → `PENDING`**(재심사) · **`PUBLISHED` + 수정 → `UPDATE_PENDING`**(수정 심사 대기). **`PENDING`·`UPDATE_PENDING`에서는 수정할 수 없고** `422` + `LISTING_NOT_EDITABLE`이다 — 심사 중인 본문이 심사자가 보고 있는 화면 아래에서 바뀌지 않게 하기 위해서다. 어느 경로든 수정이 성공하면 서버가 **원래 상태와 무관하게 `rejectionReason`을 비운다** — 수정을 거쳐 다시 올라온 매물이 지난 사유를 달고 다니지 않게 하려는 것이고, 승인이 사유를 지우는 것(US-3-7)과 같은 규칙이다. 관리자 심사는 `UPDATE_PENDING`에서도 그대로 작동한다(승인 → `PUBLISHED`, 반려 → `REJECTED`).
- 노출 관점: **공개 중인 매물을 수정하면 심사가 끝날 때까지 세입자 노출에서 빠진다 — 의도된 동작이다.** 심사를 거치지 않은 내용이 세입자에게 도달하지 않는다는 심사 제도 자체의 요구이며, 오타 하나를 고쳐도 같다. 세입자 조회 경로는 모두 "상태가 정확히 `PUBLISHED`인가"를 묻고 있어 상태 한 값만 바뀌면 목록·지도·상세·진단 추천·찜 목록·최근 본 목록에서 **동시에** 사라지고, 승인되면 `favoriteCount`·찜 문서·최근 본 기록이 **그대로 복구**된다. 임대인 화면은 제출 전에 이 사실을 고지한다. 되돌릴 수 없는 것은 하나뿐이다 — **수정이 반려되면 직전에 승인됐던 본문은 서버에 남지 않는다**(수정본을 따로 보관하지 않고 문서를 제자리에서 덮어쓰기 때문이며, 교체된 사진도 함께 사라진다).
- 예약·문의 관점: 노출 차단은 **표시와 생성을 가른다**. 이미 신청된 예약의 카드에 실리는 매물명·사진·금액은 매물 상태와 방 상태를 **보지 않는 표시 전용 조회**로 읽으므로 심사 중에도 정상 렌더된다 — 그러지 않으면 세입자·임대인 양쪽의 예약 목록·상세 4개 화면에서 제목이 비고 금액이 `0`으로 찍힌다. 반면 **신규 예약 생성과 매물 문의(채팅 개설)는 계속 막히고**, 이미 열린 채팅방은 매물 스냅샷을 들고 있어 영향이 없다.
- 동의 관점: `consents`(개인정보 수집·이용 / 매물 정보 제공 및 노출)는 **수정 요청에도 그대로 있고 게이트도 그대로다** — 하나라도 빠지거나 `false`면 등록과 같은 `422` + `LISTING_REQUIRED_AGREEMENT_MISSING`이다. **다만 저장 값은 승계한다** — 매물 문서의 **약관 버전과 동의 시각은 최초 등록 시점 값 그대로**이고 수정이 덮어쓰지 않는다. 그 값은 "등록 시점 동의"라는 감사 기록이라 수정할 때마다 갱신하면 최초 동의 시각이 사라진다. 결과적으로 **게이트는 매번 작동하지만 문서에 실제로 가해지는 변경은 없다.** 약관 개정에 따른 재동의는 매물 단건 수정이 아니라 전 매물 대상 별도 흐름의 문제다.
- 방(객실) 관점: `roomOffers`는 **순서 있는 전량 제출**이다. `roomOfferId`가 없으면 신규 방이고 서버가 ObjectId를 새로 발급한다. **방을 내리는 것은 요청에서 빼는 게 아니라 `status=INACTIVE`로 보내는 것**이다 — 하드 삭제하면 예약·채팅이 들고 있는 `roomOfferId` 참조가 영구히 끊기고, 명시 필드라야 내린 방을 나중에 다시 `ACTIVE`로 되살릴 수 있다. 요청에서 **id 자체가 빠진** 방은 클라이언트 결함일 수도 삭제 의도일 수도 있으므로 안전망으로 `INACTIVE`로 바꿔 배열 맨 뒤에 원래 상대순서로 남긴다(어느 경우에도 하드 삭제하지 않는다). 문서에 없는 `roomOfferId`를 보내면 `400` + `INVALID_INPUT`이고, **저장 후 `ACTIVE` 방이 하나도 남지 않아도 `400` + `INVALID_INPUT`** 이다 — 상태는 `PUBLISHED`인데 세입자 목록·상세에는 잡히지 않는 유령 매물이 만들어지기 때문이다. 배열 순서는 요청이 정본이라 **요청 = 저장 = 응답** 순서가 그대로 선다.
- 사진 관점: 업로드 API(`POST /api/v2/listings/images`)는 그대로다. 수정 요청의 `imageKeys`·`roomOffers[].roomImageKeys`에는 **새로 올린 임시 키(`uploads/…`)와 이미 확정된 키(`listings/…`)를 섞어** 담고, 병합이 끝난 최종 배열이 곧 표시 순서다. 확정 키는 **그 자리에 원래 있던 것만** 허용한다 — 대표사진 자리에는 그 매물의 대표사진 키만, 방 사진 자리에는 **그 방의** 사진 키만 올 수 있고 커버↔방·방↔방 교차 참조는 `400` + `LISTING_IMAGE_KEY_NOT_FOUND`다. 확정 키의 경로가 역할(`cover/`·`rooms/{roomOfferId}/`)을 이미 담고 있어 자리를 옮기면 저장 경로가 역할을 거짓말하게 되기 때문이며, **방 사진을 대표사진으로 올리려면 다시 업로드해야 한다.** 남의 매물 키·문서에 없는 키도 같은 코드로 거절되므로 이 대조가 **소유권 검사를 겸한다**. `roomOfferId`가 없는 **신규 방에는 임시 키만** 담을 수 있다(확정 키는 아직 발급되지 않은 id를 포함해야 하므로 존재할 수 없다). 장수 규칙(대표 1~5장·방마다 2~5장)은 최종 배열 기준이다. **교체돼 참조를 잃은 옛 사진은 저장이 성공한 뒤에 지운다** — 저장 *전에* 지우면 검증·경합 실패 시 공개 중인 매물의 사진이 사라진다. `INACTIVE`로 내린 방과 안전망으로 뒤로 밀린 방의 사진은 문서에 그대로 남으므로 지워지지 않고, 그 방을 되살리면 사진도 함께 살아난다.
- 동시성 관점: 저장은 **읽은 시점의 상태가 그대로일 때만** 성공한다. 매물을 읽고 저장하기까지 사진 확정 복사(네트워크·다수 객체)가 끼어 간격이 짧지 않은데, 그 사이 관리자가 승인·반려하면 문서 전체 교체가 심사 결과를 지우거나 임대인의 수정이 통째로 사라진다 — **둘 다 아무 신호 없이** 일어난다. 상태가 바뀐 것을 감지하면 `409` + `LISTING_STATE_CHANGED`로 거절하고 아무것도 저장·삭제하지 않으며, 클라이언트는 **다시 조회한 뒤 재시도**한다. 같은 보호를 관리자 심사(US-3-7) 저장에도 건다.
- 파생·승계 관점: 주소를 바꾸면 등록과 똑같이 `address.city`·`district`를 **다시 파싱**하고(카탈로그에 없으면 `ETC`), `nearbyUniversityCodes`를 좌표에서 **다시 파생**한다(반경 2km). 대학가 밖으로 옮기면 빈 집합이 되어 진단 추천에서 빠지지만 수정 자체는 성공한다 — 등록과 같은 정책이다. `_id`·`landlordId`·`schemaVersion`·`createdAt`·`favoriteCount`·`consents`·`rentalType`은 **승계**하고, `status`·`rejectionReason`·`updatedAt`은 **전이가 정한다**.
- 감수 관점: `PENDING`·`UPDATE_PENDING`에서는 수정도 **수정 신청 취소도 할 수 없다** — 제자리 덮어쓰기라 되돌릴 구본이 없다. 또 `REJECTED`를 고쳐 올리면 최초 등록과 같은 `PENDING`이 되어 상태만으로는 재제출을 구분할 수 없다(관리자는 `createdAt`으로 안다). 둘 다 심사 이력(후속)이 근본 해결이다.
- 후속(이번 범위 아님): 임대인 매물 삭제 · 수정 신청 취소 · 심사 이력 테이블 · 승인·반려 알림(도메인 이벤트) · 고아 사진 정리 배치 · 예약 생성 시점 매물 스냅샷.
- 시퀀스: [US-3-9 다이어그램](../architecture/sequence-diagrams/03-listings-favorites/us-3-9-landlord-listing-update.md), API: [03-listings-favorites](../api/specs/03-listings-favorites.md).

**AC (Given / When / Then)**

- 시나리오: 반려된 매물을 고쳐 다시 심사를 받는다
  Given 온보딩을 마친(`ACTIVE`) 임대인이 정식 access 토큰(`ROLE_USER`)을 보유하고 자기 매물이 사유와 함께 `REJECTED`이고
  When 고친 값을 등록과 같은 전 필드로 담아 `PUT /api/v2/listings/{listingId}`를 호출하면
  Then `200 OK`이고 본문이 통째로 교체되며 상태가 `PENDING`으로 바뀌고 `updatedAt`이 갱신된다. **`rejectionReason`은 그대로 남는다** — 심사를 기다리는 동안 임대인이 다시 볼 수 있어야 하고 재심사하는 관리자도 이전 맥락을 본다. 세입자 조회에는 여전히 나타나지 않는다
- 시나리오: 공개된 매물을 고치면 심사 동안 내려간다
  Given 매물이 `PUBLISHED`로 세입자 조회에 노출되고 있고
  When 임대인이 수정을 호출하면
  Then `200 OK`이고 상태가 `UPDATE_PENDING`으로 바뀐다(`rejectionReason`은 공개 중이던 매물이라 애초에 없다). 그 즉시 목록·지도·상세·진단 추천·찜 목록·최근 본 목록에서 **모두 사라진다** — 의도된 동작이며 에러가 아니다
- 시나리오: 수정 심사가 끝나면 노출이 복구된다
  Given 위 매물이 `UPDATE_PENDING`이고 세입자의 찜 문서·최근 본 기록이 남아 있고
  When 관리자가 승인(US-3-7)을 호출하면
  Then `200 OK`이고 상태가 `PUBLISHED`로 돌아가며 `favoriteCount`·찜 문서·최근 본 기록이 그대로 유지된 채 세입자 조회에 다시 나타난다
- 시나리오: 수정 심사 중인 매물을 반려할 수도 있다
  Given 매물이 `UPDATE_PENDING`이고
  When 관리자가 사유와 함께 반려를 호출하면
  Then `200 OK`이고 상태가 `REJECTED`로 바뀐다. **직전에 공개돼 있던 본문으로는 되돌아가지 않는다**(수정본을 따로 보관하지 않는다)
- 시나리오: 심사 대기 중에는 수정할 수 없다
  Given 매물이 `PENDING`이고
  When 임대인이 수정을 호출하면
  Then `422 Unprocessable Entity`, `error.code=LISTING_NOT_EDITABLE`을 반환하고 본문·상태·사진이 하나도 바뀌지 않는다
- 시나리오: 수정 심사 대기 중에도 수정할 수 없다
  Given 매물이 `UPDATE_PENDING`이고
  When 임대인이 다시 수정을 호출하면
  Then `422 Unprocessable Entity`, `error.code=LISTING_NOT_EDITABLE`이다. 수정 신청을 취소하는 경로도 없으므로 심사 결과를 기다린다
- 시나리오: 심사 중에도 진행 중인 예약 카드는 그대로 보인다
  Given `PUBLISHED`였던 매물에 이미 신청된 예약이 있고 임대인이 수정을 제출해 `UPDATE_PENDING`이 되었고
  When 세입자·임대인이 예약 목록·상세를 조회하면
  Then 매물명·사진·주소·방 상품명·보증금·총 금액이 정상적으로 채워진다(그 방 상품이 `INACTIVE`여도 같다). 반면 같은 매물에 **새 예약을 생성하거나 문의를 시작하면** `404 Not Found`, `error.code=LISTING_NOT_FOUND`다
- 시나리오: 남의 매물은 수정할 수 없다
  Given 다른 임대인이 소유한 매물의 `listingId`이고
  When 수정을 호출하면
  Then `403`이 아니라 `404 Not Found`, `error.code=LISTING_NOT_FOUND`를 반환한다(존재 여부를 알려주지 않는다)
- 시나리오: 존재하지 않는 매물
  Given 없는 `listingId`이거나 ObjectId 형식이 아니고
  When 수정을 호출하면
  Then `404 Not Found`, `error.code=LISTING_NOT_FOUND`다
- 시나리오: 동의가 빠지면 수정이 거절된다
  Given 임대인이 두 동의 중 하나를 체크하지 않았거나 `consents` 자체를 보내지 않았고
  When 수정을 호출하면
  Then `422 Unprocessable Entity`, `error.code=LISTING_REQUIRED_AGREEMENT_MISSING`을 반환하고 본문도 확정 사진도 바뀌지 않는다
- 시나리오: 동의를 다시 받아도 최초 동의 이력은 그대로다
  Given 매물 문서에 등록 시점의 약관 버전과 동의 시각이 저장돼 있고
  When 임대인이 두 동의를 모두 `true`로 담아 수정에 성공하면
  Then `200 OK`이고 **약관 버전과 동의 시각은 등록 시점 값 그대로**다(수정 시각으로 갱신되지 않는다)
- 시나리오: 방 상품을 내리고 다시 살린다
  Given 매물에 `ACTIVE` 방 상품이 2개 있고
  When 한 방을 `roomOfferId`와 함께 `status="INACTIVE"`로 보내 수정하면
  Then `200 OK`이고 그 방은 문서에 남은 채 세입자 응답에서만 빠진다. 이후 같은 `roomOfferId`를 `status="ACTIVE"`로 다시 보내면 사진까지 그대로 되살아난다
- 시나리오: 방 상품을 모두 내릴 수는 없다
  Given 매물의 방 상품이 1개뿐이고
  When 그 방을 `status="INACTIVE"`로 보내 수정하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`을 반환하고 아무것도 저장되지 않는다(`ACTIVE` 방이 없는 매물은 상태만 공개인 유령 매물이 된다)
- 시나리오: 요청에서 빠진 방 상품은 지워지지 않는다
  Given 저장된 방 상품 중 하나의 `roomOfferId`를 요청에서 통째로 빠뜨렸고
  When 수정을 호출하면
  Then `200 OK`이고 그 방은 삭제되지 않고 `INACTIVE`로 바뀌어 배열 맨 뒤에 남는다(예약·채팅이 참조하는 `roomOfferId`를 끊지 않기 위해서다)
- 시나리오: 문서에 없는 방 상품 id
  Given 이 매물의 것이 아닌 `roomOfferId`를 보내고
  When 수정을 호출하면
  Then `400 Bad Request`, `error.code=INVALID_INPUT`이다
- 시나리오: 사진은 남길 것과 새로 올린 것을 섞어 보낸다
  Given 임대인이 대표사진 3장 중 1장만 바꾸려고 새 사진 1장을 `POST /api/v2/listings/images`로 올려 임시 키를 받았고
  When 남길 확정 키 2개와 새 임시 키 1개를 원하는 순서로 `imageKeys`에 담아 수정하면
  Then `200 OK`이고 임시 키만 확정 위치로 복사되며 남긴 사진은 그대로 유지되고, 배열 순서가 곧 표시 순서가 된다. **교체돼 참조를 잃은 옛 사진은 저장이 성공한 뒤에 삭제된다**
- 시나리오: 사진 키를 다른 자리에 넣을 수 없다
  Given 이 매물의 방 사진 확정 키를 최상위 `imageKeys`에 넣거나, 대표사진 키를 `roomOffers[].roomImageKeys`에 넣거나, 다른 방의 사진 키를 이 방에 넣거나, 남의 매물·문서에 없는 확정 키를 보내고
  When 수정을 호출하면
  Then `400 Bad Request`, `error.code=LISTING_IMAGE_KEY_NOT_FOUND`를 반환한다(방 사진을 대표사진으로 올리려면 다시 업로드한다)
- 시나리오: 신규 방에는 확정 키를 쓸 수 없다
  Given `roomOfferId` 없이 새 방을 추가하면서 `roomImageKeys`에 확정 키(`listings/…`)를 담았고
  When 수정을 호출하면
  Then `400 Bad Request`, `error.code=LISTING_IMAGE_KEY_NOT_FOUND`다(확정 키는 아직 발급되지 않은 `roomOfferId`를 포함해야 하므로 존재할 수 없다)
- 시나리오: 주소를 바꾸면 파생값도 다시 계산된다
  Given 임대인이 주소 검색(`GET /api/v1/listings/addresses`)으로 고른 다른 후보의 `roadAddress`·`lat`·`lng`를 담아 수정하고
  When 수정에 성공하면
  Then `location`이 새 좌표로, `address.city`·`district`가 새 주소에서 다시 파싱된 코드(못 찾으면 `ETC`)로, `nearbyUniversityCodes`가 새 좌표 반경 2km에서 다시 파생된 집합으로 바뀐다. 대학가 밖이면 빈 집합이 되지만 수정은 성공한다
- 시나리오: 동시성(수정 도중 관리자가 심사한다)
  Given 임대인이 `PUBLISHED` 매물을 읽어 수정을 제출했고 서버가 사진을 확정하는 사이 관리자가 그 매물을 반려해 `REJECTED`가 되었고
  When 임대인의 저장이 시도되면
  Then `409 Conflict`, `error.code=LISTING_STATE_CHANGED`를 반환하고 문서도 옛 사진도 바뀌지 않는다. 클라이언트는 임대인 상세(US-3-8)로 다시 조회한 뒤 재시도한다
- 시나리오: 임대인이 아니면 막힌다
  Given 세입자(`TENANT`) 또는 관리자(`ADMIN`)의 정식 토큰이고
  When 수정을 호출하면
  Then `403 Forbidden`, `error.code=FORBIDDEN`이다(역할 검사는 SecurityConfig 매처가 아니라 서비스에서 수행한다)
- 시나리오: 입력 검증 실패
  Given 카탈로그에 없는 코드·범위를 벗어난 값·`min > max`처럼 잘못된 값을 보내고
  When 수정을 호출하면
  Then 등록과 동일하게 `400 Bad Request` + `INVALID_INPUT`(없는 코드는 `LISTING_UNKNOWN_CATALOG_CODE`, 사진 장수 위반은 `LISTING_IMAGE_REQUIRED`)이고 아무것도 저장되지 않는다
- 시나리오: 인증·권한(온보딩 미완료·토큰 없음/만료)
  Given 온보딩 스코프(`ROLE_ONBOARDING`) 토큰이거나 `Authorization` 헤더가 없거나 토큰이 위조·만료되었고
  When 수정을 호출하면
  Then 온보딩 토큰은 `403` + `AUTH_ONBOARDING_REQUIRED`, 토큰 부재·위조는 `401` + `UNAUTHENTICATED`, 만료는 `401` + `TOKEN_EXPIRED`다

## 4. 매물 예약(신청) · (후속) 문의·인앱 채팅

> 관련 API 스펙: [04-booking-inquiry-chat](../api/specs/04-booking-inquiry-chat.md)
>
> **스코프(1차 MVP)**: **매물 예약(= 신청, Booking)** 은 인앱 채팅과 분리된 **독립 기능**으로 구현한다. (**본 서비스에서 "신청"과 "예약"은 같은 `Booking`을 가리키는 동의어다.**) `ACTIVE` 세입자가 방 상품(`roomOffer`)에 타겟 입주일·계약기간으로 예약을 신청해 내역을 저장하고(US-4-1), 내 예약을 목록·단건 상세로 조회한다(US-4-2). 예약 상세는 **매물 정보·예약 일시·타겟 입주일·계약기간·예약자 성명·보증금·총 금액**을 내려준다. **임대인은 자기 소유 매물(`listing.landlordId`=본인)에 신청된 예약을 목록·단건 상세로 조회한다(US-4-6)** — 예약 **생성**은 세입자 전용이지만 **조회는 세입자(내 예약)·임대인(내 매물에 신청된 예약)** 두 관점으로 나뉜다. 여기에 더해 **두 참여자 모두** 예약 내역을 자기 목록에서 지우고(US-4-7), 예약 상대를 차단하고(US-4-8), 예약을 신고할 수 있다(US-4-9) — 삭제·차단은 **요청자 본인의 목록에만** 반영되는 표시 상태(상대의 기록은 건드리지 않는다)이고, 신고는 **접수(capture)까지만** 담당한다(운영자 검토·제재는 범위 밖). 후속으로 분리·이연하는 것은 **예약(신청) 자체가 아니라 문의(inquiry)·인앱 채팅(US-4-3~US-4-5), 그리고 예약 신청 시 채팅방에 예약 카드를 자동 기록하던 기존 F-03 chat 결합**이다 — 예약 생성 시 `BOOKING_CARD` 자동 전송·`BookingCreatedEvent` 발행은 하지 않는다. 예약은 매물·회원과 cross-store 조인이 금지되므로([ADR-0005](../adr/0005-polyglot-persistence.md)) 가격·성명은 조회 시점에 애플리케이션 레벨로 조합한다(`listing :: api`·`user :: api` 공개 쿼리, [ADR-0002](../adr/0002-inter-module-communication-via-events.md)).

외국인 세입자가 매물의 방 상품에 예약을 신청하면 예약 내역(대상 방 상품·타겟 입주일·계약기간·상태)이 저장되고, 세입자는 자신의 예약 목록과 상세(예상 비용 포함)를 다시 확인할 수 있다.

공통 규약: 모든 응답은 공통 래퍼 `{ success, data, error }`, 인증은 `Authorization: Bearer <accessToken>`, 에러 형식·코드는 [error-response-guide](../api/error-response-guide.md)를 따른다.

### US-4-1 — 매물 예약 생성(신청 저장)

**As a** 온보딩을 마친(`ACTIVE`) 외국인 세입자
**I want** 원하는 방 상품(`roomOffer`)에 타겟 입주일과 계약기간을 골라 예약을 신청하고 그 내역이 저장되기를
**So that** 나중에 내 예약 내역을 다시 확인하고 임대인의 연락을 기다릴 수 있다.

- 우선순위: High
- 관련 NFR: 보안(`ACTIVE`·`TENANT` 게이트), 입력 검증
- 백엔드 관점: `Booking`을 `REQUESTED` 상태로 저장한다(필드: `tenantId`·`listingId`·`roomOfferId`·`moveInDate`·`contractPeriod`(정수 개월수)·`status`·`createdAt`). `tenantId`는 SecurityContext에서 얻고, 요청자가 `ACTIVE`이며 `userType=TENANT`인지 다른 보호 엔드포인트와 **동일한 게이트**로 검사한다(온보딩 미완료·비세입자 차단; 상태-게이트 1:1 일치). **예약은 세입자 전용이라 임대인은 예약을 수행할 수 없고, 세입자가 자기 소유 매물을 예약하는 상황 자체가 성립하지 않으므로 본인 매물 차단(소유자 조회)은 두지 않는다.** 매물·방 상품 존재·공개 여부는 `listing :: api`로 검증한다(cross-store 조인 금지, ADR-0005). **동일 세입자–동일 방 상품 예약은 1건만 허용한다**(중복 방지 — `bookings`에 UNIQUE `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`, 재신청 시 `409` + `BOOKING_ALREADY_EXISTS`). 상태 전이(수락/거절/취소)가 미구현이라 **모든 예약이 `REQUESTED`(=활성)**이므로 "활성 1건"이 곧 "전체 1건"이라 조건 없는 UNIQUE로 규칙이 정확히 표현된다 — ⚠️ 다만 상태 전이가 도입되면 `REJECTED`·`CANCELED` 건이 그 방 재신청을 영구 차단하므로 활성 상태만 거르는 **부분 유니크**로 교체해야 한다(MySQL은 부분 유니크 인덱스를 지원하지 않아 `active_room_offer_id` 트릭·앱 레벨 검사 등 표현 방식은 그때 정한다). 제약 강화는 마이그레이션 정책상 비호환이라 기존 중복 행 정리가 선행돼야 하나 `bookings`는 신규라 사실상 비어 있다(전진 마이그레이션 V18 — `ALTER TABLE bookings ADD CONSTRAINT uq_bookings_tenant_room_offer UNIQUE (tenant_id, room_offer_id);` · V17은 신고 사유 카탈로그가 차지한다). **예약 카드 전송·`BookingCreatedEvent` 발행은 후속(문의·인앱 채팅)** — 본 스토리에서는 하지 않는다.

**AC (Given/When/Then)**

- 정상
  - **Given** `ACTIVE` 세입자(`userType=TENANT`)가 공개 매물의 방 상품을 보고 있을 때
  - **When** `roomOfferId`·`moveInDate`(미래 날짜)·`contractPeriod`(양의 정수, 개월수)로 `POST /api/v1/listings/{listingId}/bookings`를 호출하면
  - **Then** `201 Created` + `Location: /api/v1/bookings/{bookingId}`로 응답하고, `data`에 `bookingId`·`status: "REQUESTED"`·`listingId`·`roomOfferId`·`moveInDate`·`contractPeriod`·`createdAt`가 포함된다.
- 입력 검증 실패
  - **When** `roomOfferId`나 `contractPeriod`를 누락하거나 `contractPeriod`가 양의 정수가 아니면(0·음수) → `400` + `INVALID_INPUT`(`errors[]`에 위반 필드). **When** `moveInDate`를 날짜 형식이 아닌 값/타입, 또는 `contractPeriod`를 숫자 아닌 타입으로 보내면 → `400` + `MALFORMED_REQUEST`.
  - **Then** 어느 경우에도 예약은 생성되지 않는다.
- 비즈니스 규칙(입주일)
  - **When** `moveInDate`가 (형식은 유효하나) 과거이면 → `422` + `BOOKING_INVALID_MOVE_IN_DATE`.
  - **Then** 이 경우 예약은 생성되지 않는다.
- 인증·권한·상태 게이트
  - **Given** `Authorization` 헤더가 없거나 만료된 토큰이면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`. **Given** 인증은 됐으나 온보딩 미완료(비`ACTIVE`) 사용자면 → 다른 보호 엔드포인트와 동일한 **온보딩 상태 게이트 에러**로 차단한다(코드 게이트와 1:1 일치). **Given** `userType`이 세입자가 아니면(임대인) → `403` + `FORBIDDEN`(예약은 세입자 전용).
  - **When** 존재하지 않거나 비공개/삭제된 매물·방 상품 ID로 호출하면 → `404` + `LISTING_NOT_FOUND`.
- 중복 방지(동일 세입자–동일 방 상품 활성 1건)
  - **Given** 세입자가 같은 방 상품에 이미 신청한 이력이 있을 때
  - **When** 같은 방 상품에 다시 신청하면
  - **Then** `409` + `BOOKING_ALREADY_EXISTS`("이미 신청한 매물입니다")로 거부되고 새 예약이 생기지 않는다 — `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)` UNIQUE로 동일 세입자–동일 방 상품에 활성 1건만 허용한다(상태 전이 미구현이라 전체 예약이 `REQUESTED`(=활성)이므로 조건 없는 UNIQUE로 표현). US-4-8의 사용자 단위 차단 결정은 이 규칙에 의존하지 않는다(아래 US-4-8 참조).

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
- 백엔드 관점: **별도 임대인 전용 API를 두지 않고** 조회 엔드포인트(`GET /api/v1/bookings`·`GET /api/v1/bookings/{bookingId}`)에서 요청자 `userType`으로 **분기**한다 — `LANDLORD`면 내 소유 매물에 신청된 예약을(`TENANT`면 내 예약, US-4-2). `userType`은 토큰 클레임이 아니라 `user :: api`(`getUserType`)로 서비스 계층에서 판정하며, **두 역할 모두 유효한 요청이라 역할 `403`은 없다**. 소유권은 예약 **생성 시** 매물 소유자(`listing.landlordId`)를 `Booking.landlordId`로 **비정규화 저장**해 두므로(생성은 이미 `listing :: api`로 매물을 조회 중이라 소유자 캡처 비용이 거의 없다), 임대인 **목록**은 booking 저장소에서 **`landlord_id = 요청자`** 단일 조건을 `createdAt` 내림차순 **오프셋 페이지네이션**(api-design-guide §4-1)으로 조회한다(cross-store 조인 없음, [ADR-0005](../adr/0005-polyglot-persistence.md); `chat_rooms` 비정규화 선례와 일치). `landlordId`는 매물 상태와 무관해 심사 중(`PENDING`·`UPDATE_PENDING`)이거나 반려된(`REJECTED`) 매물의 신청도 포함된다. 카드에 실리는 매물명·사진·금액도 **표시 전용 조회**로 읽어 매물 상태·방 상태를 보지 않는다 — 이미 성사된 예약이 임대인의 수정 심사 동안 빈 카드나 0원으로 보이지 않게 하기 위한 것이며, 신규 예약 **생성**은 그대로 공개 매물의 활성 방에만 허용된다(US-3-9). **상세**는 예약을 조회한 뒤 **`booking.landlordId == 요청자`인지 행 단위로 확인**한다(listing::api 왕복 없음). 응답 조립 시 매물 요약·가격은 `listing :: api`로, 신청자 프로필(성명·성별·국적·이메일)은 `user :: api`(신규 `getApplicantProfile`)로 조회 시점에 실시간 조인한다(스냅샷 없음, 마스킹 없이 평문 노출). **총 금액**(`totalAmount`)은 세입자 분기와 **동일한 필드·정의**(`보증금 + 월세 × 개월수`, 관리비 제외)다. 신규 에러코드는 없고 기존 `BOOKING_NOT_FOUND`(404) + 공통 `AUTH_ONBOARDING_REQUIRED`를 재사용한다.

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
  - **Given** 소유 매물이 `UPDATE_PENDING`(수정 심사 대기)이거나 `REJECTED`(반려) 상태일 때 → 그 매물에 신청된 예약도 목록·상세에 포함되고 **매물명·사진·금액이 정상 표시된다**(`landlordId`가 매물 상태와 무관하게 booking 행에 저장돼 있고, 카드 값은 표시 전용 조회로 읽어 `PUBLISHED` 한정이 아니다).
  - **Given** 신청된 방 상품이 이후 비공개/삭제됐을 때 → 상세 조회 시 예약 코어 내역(날짜·계약기간·상태)은 유지하되 매물 정보·가격 파트의 표기 정책은 **(확인 필요)**(US-4-2와 동일).

> 신설 의존: 조회 서비스가 `user :: api`(`getUserType`)로 `userType`을 판정해 세입자/임대인 동작을 분기한다(별도 임대인 API·역할 `403` 없음). 선행 작업 — ① 예약 **생성** 시 소유자 캡처를 위해 `listing :: api`의 매물 조회 뷰(`RoomOfferBookingView`)에 `landlordId` 추가 노출 + `Booking`에 `landlordId` 저장, ② `user :: api` 신청자 프로필 조회(`getApplicantProfile` — 성명·성별·국적·이메일), ③ booking 저장소의 `landlord_id` 컬럼 + `findByLandlordId`(페이지·카운트) + `(landlord_id, created_at)` 인덱스(신규 마이그레이션, database-design §4-5). 임대인 조회에 listing::api 소유권 조회 메서드는 불필요(소유권은 booking 행에서 판정). `booking → {listing::api, user::api}` 의존 화이트리스트는 이미 선언돼 있다. 신규 에러코드 없이 기존 `BOOKING_NOT_FOUND`(404)를 재사용한다. **임대인에게 신청자 이메일·성별·국적은 마스킹 없이 평문으로 노출한다(제품 결정).**

### US-4-7 — 예약 내역 삭제(내 목록에서 숨김)

> **[1차 MVP] 매물 예약(신청) 스토리다** — US-4-1·US-4-2·US-4-6과 함께 booking 독립 기능에 속하며, 세입자·임대인 **두 역할 공통**이다.

**As a** 예약(신청) 내역을 보유한 세입자 또는 임대인
**I want** 더 이상 보고 싶지 않은 예약을 내 예약 목록에서 지우기를
**So that** 상대의 기록을 훼손하지 않으면서 내 목록을 정리한다.

- 우선순위: High
- 관련 NFR: 보안(참여자만 삭제 — 존재 비노출 404), 정합성(참여자별 독립 삭제·페이지네이션), 멱등성(반복 DELETE)
- 백엔드 관점: `DELETE /api/v1/bookings/{bookingId}`는 요청자 역할에 따라 `bookings.tenant_deleted_at` 또는 `landlord_deleted_at`에 현재 시각(UTC)을 기록한다 — 예약 행 자체는 지우지 않는다. **참여자별 컬럼을 2개 두는 이유**: `bookings`는 `tenantId`와 `landlordId`가 **공유하는 1행**이라(US-4-1·US-4-6) 단일 삭제 flag를 두면 한쪽이 지울 때 상대의 기록까지 사라지는 데이터 손실이 된다. `status=CANCELED` 같은 상태 값으로도 표현할 수 없다 — `status`는 두 참여자가 함께 보는 **공유 필드**이고, 취소(의사 표시) ≠ 숨김(표시 상태)이며, 예약 상태 전이 자체가 아직 범위 밖이다(신청 직후 `REQUESTED` 고정, US-4-1). 이 결정은 소프트삭제를 `community`로 한정하던 전역 규약의 **의도된 예외**이며, 그 예외와 근거는 [database-design](../database/database-design.md) §2-2·§4-5에 함께 기록한다. 목록·상세 조회(US-4-2·US-4-6)는 요청자 관점의 `*_deleted_at IS NULL` 술어를 **저장소 쿼리로 내려** 필터한다 — 응용 계층 후처리로 걸러내면 별도 count 쿼리로 유도하는 `totalElements`/`hasNext`가 어긋난다. 반면 **삭제(변이) 경로는 필터되지 않은 조회**로 대상을 찾는다(필터된 조회를 쓰면 두 번째 DELETE가 404가 되어 멱등이 깨진다). 참여자가 아니거나 없는 예약은 `404` + `BOOKING_NOT_FOUND`(존재 비노출 — US-4-2·US-4-6과 동일). **기존 응답 DTO에 삭제 표시 필드를 추가하지 않는다** — 삭제는 "내 목록에서 사라짐"으로만 관측된다.

**AC (Given/When/Then)**

- 정상(삭제 — 내 목록·상세에서 제외)
  - **Given** 세입자와 임대인이 함께 참여한 예약 1건이 있을 때
  - **When** 세입자가 `DELETE /api/v1/bookings/{bookingId}`를 호출하면
  - **Then** `204 No Content`로 응답하고, 그 예약은 세입자의 `GET /api/v1/bookings` 목록과 `GET /api/v1/bookings/{bookingId}` 상세 **양쪽에서 모두 제외**된다(상세는 `404` + `BOOKING_NOT_FOUND`, 목록 `page.totalElements`도 그만큼 줄어든다). 예약 행은 삭제되지 않는다.
- 정상(단방향 — 상대 목록은 그대로)
  - **Given** 세입자가 위 예약을 삭제한 직후
  - **When** 같은 예약의 **임대인**이 목록(임대인 분기, US-4-6)과 단건 상세를 조회하면
  - **Then** 그 예약은 **그대로 보인다**(`200`) — 삭제는 요청자 본인 관점에만 반영된다.
- 입력 검증 실패
  - **When** `bookingId`를 숫자가 아닌 타입/형식으로 보내면 → `400` + `MALFORMED_REQUEST`.
  - **Then** 어떤 예약도 삭제 표시되지 않는다.
- 인증·권한
  - **Given** 토큰이 없거나 만료된 요청이면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`. **Given** 온보딩 미완료(비 `ACTIVE`) 요청이면 → `403` + `AUTH_ONBOARDING_REQUIRED`(현행 booking 매처가 `GET`만 덮으므로 SecurityConfig에 `DELETE /api/v1/bookings/*`를 `hasRole("USER")`로 명시하지 않으면 온보딩 토큰이 통과한다).
  - **When** **타인의 예약**(참여자가 아닌 예약)이나 존재하지 않는 `bookingId`로 호출하면 → `404` + `BOOKING_NOT_FOUND`(존재 여부를 노출하지 않도록 `403`이 아니라 404로 통일).
- 경계·동시성(멱등)
  - **Given** 이미 삭제한 예약을 지목할 때
  - **When** 같은 사용자가 `DELETE /api/v1/bookings/{bookingId}`를 **반복 호출**하면
  - **Then** 매번 `204`로 응답한다(멱등 — 변이 경로가 비필터 조회를 쓰므로 두 번째 호출도 404가 되지 않는다).
  - **Given** 두 참여자가 **동시에** 각자 같은 예약을 삭제하면 → 서로 다른 컬럼(`tenant_deleted_at`/`landlord_deleted_at`)을 쓰므로 상대의 삭제를 덮어쓰지 않고 둘 다 각자의 목록에서만 사라진다.

> 신설 의존: `bookings`에 `tenant_deleted_at`·`landlord_deleted_at`(`DATETIME(6)` NULL, NULL = 미삭제) 컬럼 추가(신규 마이그레이션, [database-design](../database/database-design.md) §4-5 — §2-2의 "소프트삭제는 community만" 규약에 대한 예외를 그 자리에 함께 기록), booking 저장소 **6개 읽기 경로 전부**(`findByTenantId`·`countByTenantId`·`findByIdAndTenantId`·`findByLandlordId`·`countByLandlordId`·`findByIdAndLandlordId`)에 요청자 관점 필터 술어 + 변이 경로용 **비필터 조회** 추가, SecurityConfig에 `DELETE /api/v1/bookings/*` 매처(`hasRole("USER")`). 신규 에러코드 없이 기존 `BOOKING_NOT_FOUND`(404)를 재사용한다. 삭제는 `booking` 모듈 내부 처리라 **새 모듈 의존 엣지가 없다**.

### US-4-8 — 예약 상대 차단

> **[1차 MVP] 매물 예약(신청) 스토리다** — 세입자·임대인 **두 역할 공통**이며, 차단 **생성**만 예약 경로에 있고 **목록·해제는 `user` 모듈**(`/api/v1/users/me/blocks`, [spec-01](../api/specs/01-auth-onboarding.md))에 있다.

**As a** 예약으로 상대(임대인 또는 세입자)와 엮인 사용자
**I want** 불쾌한 상대를 차단해 그 사람과의 예약을 내 목록에서 전부 치우고 더는 신청으로 엮이지 않기를
**So that** 원치 않는 상대와의 접촉을 스스로 끊고, 필요하면 되돌릴 수 있다.

- 우선순위: High
- 관련 NFR: 보안(참여자만 차단 — 존재 비노출 404), 멱등성(반복 차단), 정합성(차단 필터가 6개 읽기 경로 전부에 적용)
- 백엔드 관점: `POST /api/v1/bookings/{bookingId}/block`은 **상대(counterpart)를 서버가 도출**한다(`요청자 == tenantId ? landlordId : tenantId`) — 클라이언트가 `userId`를 보내지 않는다. **차단 단위가 예약이 아니라 사용자인 이유**(구조적 근거): 한 임대인은 매물을 **여러 개** 소유하고(`Listing.landlordId` — 매물이 임대인을 가리키는 N:1), 한 매물은 방 상품을 **여러 개** 갖는다(`Listing.roomOffers`가 `List<RoomOffer>`). 그래서 임대인 A를 예약 #1에서 차단해도 A의 **다른 방 상품**(같은 매물의 다른 방이든, A가 가진 다른 매물의 방이든)에 신청하는 순간 **새 예약 = 새 채팅방**이 생긴다 — 예약(방) 단위 차단은 **같은 방 재신청을 막든 막지 않든** 다른 방으로 우회된다. 차단은 본질적으로 **사람**에 대한 것이고, 대상을 예약으로 잡으면 상대가 방을 하나 더 가진 순간 무력해진다. **보조적으로** 짚자면, 같은 방 재신청은 이제 중복 방지 UNIQUE(`uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`, US-4-1 — 재신청 시 `409` + `BOOKING_ALREADY_EXISTS`)로 막히지만, 임대인은 방·매물을 **여러 개** 가지므로 **다른 방으로는 여전히 우회된다** — 그래서 차단은 여전히 **사용자 단위**여야 한다. 즉 같은 방 재신청 차단 여부와 무관하게 위 구조적 근거만으로 사용자 단위가 그대로 확정된다. 그래서 `user_blocks(blocker_id, blocked_user_id)` **사용자 단위**로 저장하고 행 존재 = 차단, 해제 = 행 삭제로 다룬다(`is_active` 같은 플래그 컬럼은 두지 않는다 — 레포에 전례가 없다). **생성만 `booking`에 있고 목록·해제는 `user`에 있는 이유**: 경로가 `/bookings/{bookingId}/block`이라 상대를 도출하려면 예약을 읽어야 하는데, `user`가 생성을 소유하면 `user → booking` 의존이 생기고 `booking → user :: api`가 이미 있어 **모듈 의존 사이클이 나 `ApplicationModules.verify()`가 깨진다**([ADR-0002](../adr/0002-inter-module-communication-via-events.md)). 그래서 컨트롤러만 `booking`에 두고 저장은 `user :: api` **공개 명령**으로 위임한다(차단 생성은 조회가 아니라 쓰기다 — 가리기 위한 조회만 공개 쿼리다). 반대로 **목록·해제는 예약에 걸 수 없다** — 차단하는 순간 그 예약이 내 목록에서 사라져 `bookingId`를 다시 얻을 수 없으므로, 되돌리는 경로는 예약과 무관한 `GET`/`DELETE /api/v1/users/me/blocks`(`user` 모듈)여야 한다. **의미론**: 목록 숨김은 **단방향**(차단자 기준 — 내 목록에서만 사라지고 상대 목록은 그대로), 신규 신청 차단은 **양방향**(어느 한쪽이라도 차단 관계면 `POST /api/v1/listings/{listingId}/bookings`가 `403` — 없으면 `201`은 나가는데 목록엔 영영 보이지 않는 "블랙홀 예약"이 생긴다). 차단은 `*_deleted_at`을 세팅하지 **않고** 조회 술어(`상대 id NOT IN (차단 목록)`)로만 숨긴다 — 해제하면 다시 보여야 하기 때문이다. 차단 목록은 `user :: api`(`findBlockedUserIds`)로 받아 **애플리케이션 레벨로 조합**한다(cross-module·cross-store 조인 금지, [ADR-0005](../adr/0005-polyglot-persistence.md) — `booking`이 `user_blocks`를 직접 조인하지 않는다). ⚠️ **빈 목록 함정**: 차단이 0건이면 `NOT IN ()`은 문법 오류이고 `NOT IN (null)`은 UNKNOWN이라 **모든 행이 사라진다** — 차단이 하나도 없는 사용자가 예약 목록을 통째로 잃는 회귀이므로, 빈 목록은 어댑터 **내부에서** sentinel `-1L` 한 건으로 정규화한다 — `users.id`는 `BIGINT AUTO_INCREMENT`(양의 정수만 발급)라 `-1`이 실제 식별자와 충돌할 수 없다.

**AC (Given/When/Then)**

- 정상(차단 — 그 상대와의 모든 예약이 사라짐)
  - **Given** 세입자가 임대인 A의 매물에 예약 3건을 신청해 둔 상태에서
  - **When** 그중 한 건으로 `POST /api/v1/bookings/{bookingId}/block`을 호출하면
  - **Then** `204 No Content`로 응답하고, 지목한 1건이 아니라 **A와의 예약 3건 전부**가 세입자의 목록·상세에서 사라진다(상세는 `404` + `BOOKING_NOT_FOUND`) — 차단은 예약 단위가 아니라 사용자 단위다.
- 정상(단방향 — 상대 목록은 그대로)
  - **Given** 세입자가 임대인 A를 차단한 직후
  - **When** 임대인 A가 `GET /api/v1/bookings`(임대인 분기, US-4-6)를 조회하면
  - **Then** 그 예약들은 **그대로 보인다** — 목록 숨김은 차단자 기준 단방향이다.
- 정상(재신청 차단 — 양방향)
  - **Given** 세입자와 임대인 A 사이에 **어느 방향이든** 차단 관계가 있을 때
  - **When** 세입자가 A의 매물에 `POST /api/v1/listings/{listingId}/bookings`로 새로 신청하면
  - **Then** `403` + `FORBIDDEN`(공통 코드)으로 거절되고 예약이 생성되지 않는다(신청은 되는데 목록엔 보이지 않는 "블랙홀 예약" 방지).
- 정상(되돌리기 — 차단 목록·해제)
  - **Given** 차단 때문에 그 상대와의 예약이 목록에서 사라져 `bookingId`를 다시 얻을 수 없을 때
  - **When** `GET /api/v1/users/me/blocks`로 차단 목록을 조회하고 `DELETE /api/v1/users/me/blocks/{userId}`로 해제하면
  - **Then** `user_blocks` 행이 삭제되고 그 상대와의 예약이 **다시 목록·상세에 나타나며** 신규 신청도 가능해진다(차단은 `*_deleted_at`을 건드리지 않으므로 복원된다). 단, 차단 전에 US-4-7로 직접 삭제한 예약은 계속 숨겨진 채다.
- 입력 검증 실패
  - **When** `bookingId`를 숫자가 아닌 타입/형식으로 보내면 → `400` + `MALFORMED_REQUEST`. 요청 본문은 받지 않는다 — 상대를 서버가 도출하므로 클라이언트가 임의의 `userId`를 지정할 수 없다.
  - **Then** 차단 행이 생성되지 않는다.
- 인증·권한
  - **Given** 토큰이 없거나 만료된 요청이면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`. **Given** 온보딩 미완료(비 `ACTIVE`) 요청이면 → `403` + `AUTH_ONBOARDING_REQUIRED`(현행 booking 매처는 단일 세그먼트 `GET`만 덮으므로 SecurityConfig에 `POST /api/v1/bookings/*/block`을 `hasRole("USER")`로 명시해야 한다).
  - **When** 참여자가 아닌 예약이나 존재하지 않는 `bookingId`로 호출하면 → `404` + `BOOKING_NOT_FOUND`(존재 비노출 — 제3자가 남의 예약 상대를 차단할 수 없다).
- 경계·동시성(멱등 · 차단 0건 회귀)
  - **Given** 이미 차단한 상대일 때 → 같은 예약(또는 그 상대와의 다른 예약)으로 `POST .../block`을 **반복·동시** 호출해도 매번 `204`이고 `(blocker_id, blocked_user_id)` 유니크로 행은 1개만 유지된다(멱등).
  - **Given** **차단이 0건인 사용자**가 → `GET /api/v1/bookings`를 호출하면 자기 예약이 **정상적으로 전부** 반환된다 — 빈 차단 목록이 `NOT IN` 술어에 그대로 들어가 결과가 통째로 비는 회귀가 없어야 한다(목록·카운트가 어긋나지 않는다).

> 신설 의존: `user_blocks(blocker_id, blocked_user_id, created_at)` 테이블 + `uq_user_blocks_blocker_blocked` 유니크(**`user` 모듈 소유**, 신규 마이그레이션, [database-design](../database/database-design.md)), `user :: api`에 **차단 공개 쿼리 `findBlockedUserIds(blockerId)`·`isBlockedBetween(a, b)`** (목록·상세에서 가릴 상대 선별 · 신규 신청 가드의 양방향 판정)와 **차단 생성 공개 명령**(`booking`이 예약에서 도출한 상대 식별자를 받는다) — **`booking → user :: api`는 이미 의존 화이트리스트에 선언돼 있어 새 모듈 의존 엣지가 생기지 않는다**(US-4-2에서 추가됨). 차단 **목록 조회·해제는 `user :: api` 표면이 아니다** — `user`가 자기 엔드포인트(`GET`/`DELETE /api/v1/users/me/blocks`)로 직접 제공하므로 `booking`이 호출하지 않는다([domain-model](../architecture/domain-model.md) §2). 그 밖에 booking 저장소 6개 읽기 경로의 차단 필터 술어(빈 목록 정규화 포함), `POST /api/v1/listings/{listingId}/bookings`의 **양방향** 차단 가드(`403` + 공통 `FORBIDDEN`), 차단 목록·해제 엔드포인트 `GET`/`DELETE /api/v1/users/me/blocks`([spec-01](../api/specs/01-auth-onboarding.md)), SecurityConfig 매처 — `POST /api/v1/bookings/*/block`과 `GET`·`DELETE /api/v1/users/me/blocks`·`/api/v1/users/me/blocks/*`를 `hasRole("USER")`로 추가(기존 `/api/v1/users/me` 매처는 **정확 경로**라 `/me/blocks`를 덮지 않는다)가 선행돼야 한다. 신규 에러코드는 없다(기존 `BOOKING_NOT_FOUND`(404)·공통 `FORBIDDEN`(403) 재사용).

### US-4-9 — 예약 신고(접수)

> **[1차 MVP] 매물 예약(신청) 스토리다** — 세입자·임대인 **두 역할 공통**이다. 본 스토리의 범위는 **신고 접수(capture)까지**이며, 운영자 검토·제재는 범위 밖이다. 게시글·댓글·채팅 메시지 신고는 별개로 [§7 신고 처리](#7-신고-처리)·[spec-07](../api/specs/07-reports.md)가 담당한다.

**As a** 예약으로 엮인 상대의 행위가 부적절하다고 판단한 참여자(세입자 또는 임대인)
**I want** 그 예약을 (필요하면 사유·상세와 함께) 신고해 접수된 사실이 서버에 남기를
**So that** 부적절한 상대를 운영에 알리고, 삭제·차단한 뒤에도 신고 근거가 보존된다.

- 우선순위: High
- 관련 NFR: 보안(참여자만 신고 — 존재 비노출 404), 개인정보(신고자·상세 원문 비노출), 정합성(동일 예약 다건 신고 허용 — 도배 방지는 후속 레이트리밋)
- 백엔드 관점: `POST /api/v1/bookings/{bookingId}/report`가 `booking_reports(reporter_id, booking_id, reason, detail, created_at)`에 신고를 접수한다. **범위가 접수까지라 `status` 컬럼을 두지 않는다** — 운영자 검토·제재·상태 전이가 범위 밖이라 표현할 상태가 없고, 접수 기록은 불변이다. **왜 `report` 모듈(07)이 아니라 `booking`인가**: 접수는 "대상이 실재하는가 / 요청자가 그 예약의 참여자인가"를 검증해야 하는데 그건 **예약만 아는 정보**이고, 불변식("참여자만 신고 가능")이 예약 상태에 의존하므로 소유자도 예약이다. `report`가 접수하면 `report → booking :: api` 포트를 새로 뚫어야 하지만 `booking`이 접수하면 **모듈 내부 호출이라 새 의존 엣지가 0개**다. 두 곳이 공존해도 충돌하지 않는다 — [report 모듈](../api/specs/07-reports.md)은 **게시글·댓글·채팅 메시지** 신고를, 본 스토리는 **예약** 신고를 담당해 **신고 대상이 겹치지 않는다**(`ReportTargetType`에 `BOOKING`을 추가하지 않는 것이 그 전제다). 사유(`reason`)는 **선택(nullable)** — 보내면 **활성 카탈로그 `code`인지 검증**한 뒤 그 code 문자열을 값 참조로 저장하고(비활성·미정의 code면 `400 INVALID_INPUT`, FK는 두지 않는다), 안 보내면 `NULL`로 접수한다. 사유 카탈로그는 `GET /api/v1/bookings/report-reasons`(**인증 필요** — `/api/v1/bookings/*`가 이미 `hasRole("USER")`라 자연스럽고, permitAll을 새로 만들면 본 범위 밖의 보안 완화가 된다)로 내려주며, **MySQL 카탈로그 테이블 `booking_report_reasons`**(**`booking` 모듈 소유**, `(code, lang)` 한 쌍이 한 라벨)의 행으로 관리한다 — 07의 `ReportReason`과 값이 같아도 **별개 카탈로그**다(카탈로그를 공유하면 `booking → report` 모듈 의존이 생긴다). **사유 라벨(`label`)은 서버가 사용자 표시 언어로 번역해 내려준다** — 표시 언어는 `user :: api` 공개 쿼리 `getLanguage(userId)`로 얻는다(`users.lang`이 있으면 그 값, 없으면 `en`을 소문자 코드 문자열로 회신하므로 **`booking`은 폴백 규칙을 알지 못한다**; `diagnosis`·`gamification`·`lifetip`이 이미 같은 경로를 쓴다, [domain-model](../architecture/domain-model.md) §2 · [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5). 지원 언어는 `Language` enum의 `EN`·`KO`·`JA` 3종이고 미지원·미설정은 `en` 폴백이다. **`code`는 언어와 무관하게 불변이고 `label`만 언어별로 달라진다**(클라이언트는 `code`로 분기하고 `label`은 표시에만 쓴다 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) Decision 6과 같은 원칙). 번역 라벨은 **MySQL 카탈로그 테이블 `booking_report_reasons`**(`booking` 모듈 소유)에 둔다 — `(code, lang)` 한 쌍이 한 라벨이라 사유 추가도 언어 추가도 **행 INSERT**로 끝나(코드 배포·스키마 변경 없이 동적 관리), 이 동적성 때문에 enum·리소스 번들 대신 카탈로그로 뒀다. 진단 문항·생활 팁 주제처럼 배포 없이 바뀌는 콘텐츠는 MongoDB 인라인 언어-키 맵을 쓰지만 `booking`은 MySQL이라 사유 카탈로그도 같은 저장소에 자연스럽게 두어 cross-store를 만들지 않는다([ADR-0005](../adr/0005-polyglot-persistence.md)). ⚠️ **라벨은 `getLanguage(userId)`가 회신한 코드로 카탈로그 행을 골라 조립한다**(그 언어 행이 없으면 `en` 폴백) — `LocaleContextHolder`/`Accept-Language`로 고르지 않는다. domain-model이 두 경로를 명시적으로 분리해 뒀다: `getLanguage`는 **본문 콘텐츠 번역에만** 쓰이고, 에러 메시지는 `Accept-Language`/`LocaleContextHolder` + 에러 메시지 번들(`messages`) 경로를 그대로 쓴다([ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)). 그래서 사유 라벨은 **에러 메시지 번들(`messages`)에 얹지 않고 카탈로그 테이블에 둔다** — `messages`는 다시 **에러 메시지 전용**이라 라벨을 섞으면 언어 출처가 다른 두 규약이 충돌한다(아래 "신설 의존" 참조). 라벨은 사용자가 고른 언어를, 에러 메시지는 요청 헤더 언어를 따른다. `booking → user :: api`는 **이미 의존 화이트리스트에 있어 새 모듈 의존 엣지가 생기지 않는다**(`booking/package-info.java`). **자기 신고 방지 코드는 두지 않는다** — 예약 생성은 세입자 전용이고(US-4-1) `userType`은 온보딩에서 확정된 뒤 불변이라 `tenantId != landlordId`가 **구조적으로 보장**되어 자기 신고 상황이 성립하지 않는다. **신고 대상 판정은 삭제·차단 상태와 무관**하다(증거 보존) — 신고는 US-4-7·US-4-8의 필터가 걸리지 않은 조회로 대상을 찾으므로, 같은 예약이 상세 조회에선 `404`인데 신고는 `201`이 되는 **의도된 비대칭**이 생긴다. 응답은 `reportId`·`bookingId`·`reason`·`createdAt`만 내려주고 `reporterId`·`detail` 원문은 노출하지 않는다. **동일 신고자가 동일 예약을 여러 번 신고할 수 있다(다건 허용)** — 새 사유·지속되는 문제를 다시 신고할 수 있어야 하기 때문이라 `BookingReport`에는 별도 비즈니스 키(유일성)를 두지 않고 `(reporter_id, booking_id)` 유니크도 없다. **신고 도배 방지는 레이트리밋(`429`)** 으로 다루며 이는 **후속·이연**이다(현재 미구현).

**AC (Given/When/Then)**

- 정상(사유·상세 포함 접수)
  - **Given** 해당 예약의 참여자인 인증 사용자가
  - **When** `reason`(`SPAM`|`ABUSE`|`SEXUAL_CONTENT`|`EXTERNAL_CONTACT`|`FALSE_INFO`|`ETC` 중 하나)·`detail`(최대 500자)로 `POST /api/v1/bookings/{bookingId}/report`를 호출하면
  - **Then** `201 Created` + `data`에 `reportId`·`bookingId`·`reason`·`createdAt`가 포함되고 `booking_reports`에 1행이 접수된다(`reporterId`·`detail` 원문은 응답에 담기지 않는다). 예약의 `status`나 노출 여부는 바뀌지 않는다(접수만 한다).
- 정상(사유 없이 접수 · 사유 카탈로그)
  - **Given** 참여자가 사유를 고르지 않았을 때
  - **When** `reason`·`detail` 없이 신고하면 → `201` + `data.reason: null`로 정상 접수된다(사유는 선택 — 신고 진입 장벽을 낮춘다).
  - **Then** `GET /api/v1/bookings/report-reasons`는 `200`으로 6종(`SPAM`·`ABUSE`·`SEXUAL_CONTENT`·`EXTERNAL_CONTACT`·`FALSE_INFO`·`ETC`)의 코드·라벨을 페이지네이션 없이 반환한다(고정·소규모 집합).
- 정상(사유 라벨 번역 — 표시 언어)
  - **Given** `users.lang=ja`인 사용자와 `lang`이 **미설정**인 사용자가 각각 있을 때
  - **When** 두 사용자가 `GET /api/v1/bookings/report-reasons`를 호출하면
  - **Then** 앞은 **일본어 라벨**을, 뒤는 **영어 라벨**(`en` 폴백)을 받는다 — 서버가 `user :: api getLanguage(userId)`가 회신한 코드로 카탈로그 테이블 `booking_report_reasons`에서 해당 `(code, lang)` 라벨 행을 고른다(그 언어 행이 없으면 `en` 폴백, `Accept-Language`/`LocaleContextHolder`가 아니다). **`code`는 두 응답에서 동일**하고 `label`만 달라진다(클라이언트는 `code`로 분기한다). `lang=ko`면 한국어 라벨을, 지원하지 않는 언어면 `en` 라벨을 받는다(지원 언어는 `EN`·`KO`·`JA` 3종).
- 정상(삭제·차단한 예약도 신고 가능 — 증거 보존)
  - **Given** 참여자가 그 예약을 이미 삭제(US-4-7)했거나 상대를 차단(US-4-8)해 목록에서 사라진 상태일 때
  - **When** 같은 `bookingId`로 신고하면
  - **Then** `201`로 **정상 접수**된다 — 같은 예약이 `GET /api/v1/bookings/{bookingId}`에선 `404`인데 신고는 `201`인 **의도된 비대칭**이다(신고는 비필터 조회로 대상을 찾는다). 신고했다고 예약이 목록에 다시 나타나지도 않는다.
- 입력 검증 실패
  - **When** `reason`이 정의되지 않은 값이거나 `detail`이 500자를 초과하면 → `400` + `INVALID_INPUT`(`errors[]`에 위반 필드). **When** `reason`을 문자열이 아닌 타입으로 보내면 → `400` + `MALFORMED_REQUEST`.
  - **Then** 어느 경우에도 신고가 접수되지 않는다.
- 인증·권한
  - **Given** 토큰이 없거나 만료된 요청이면 → `401` + `UNAUTHENTICATED`/`TOKEN_EXPIRED`. **Given** 온보딩 미완료(비 `ACTIVE`) 요청이면 → `403` + `AUTH_ONBOARDING_REQUIRED`(SecurityConfig에 `POST /api/v1/bookings/*/report`·`GET /api/v1/bookings/report-reasons`를 `hasRole("USER")`로 명시).
  - **When** **참여자가 아닌 제3자**가 신고하거나 존재하지 않는 `bookingId`로 호출하면 → `404` + `BOOKING_NOT_FOUND`(존재 비노출 — US-4-2·US-4-6·US-4-7과 동일하게 `403`이 아니라 404로 통일).
  - **Then** 자기 자신을 신고하는 경우는 **별도 에러코드로 막지 않는다** — 세입자만 예약을 만들 수 있고 `userType`이 온보딩 후 불변이라 `tenantId != landlordId`가 구조적으로 보장된다.
- 경계·동시성(동일 예약 다건 신고 허용)
  - **Given** 같은 사용자가 같은 예약을 이미 신고했을 때
  - **When** 같은 `bookingId`로 사유를 바꿔(또는 동시에) 다시 신고하면
  - **Then** `201`로 **정상 접수**된다 — 동일 신고자·동일 예약이라도 여러 건을 남길 수 있다(새 사유·지속되는 문제를 다시 신고할 수 있어야 하므로 `(reporter_id, booking_id)` 유니크·별도 유일성 제약을 두지 않고, 기존 접수를 덮어쓰지도 않는다). 신고 도배 방지는 **레이트리밋(`429`)** 으로 다루며 이는 **후속·이연**이다(현재 미구현).
  - **Given** 같은 예약을 **상대 참여자**가 신고하면 → `reporter_id`가 달라 물론 별개 신고로 `201` 접수된다.

> 신설 의존: `booking_reports(reporter_id, booking_id, reason, detail, created_at)` 테이블 + 보조 인덱스 `idx_booking_reports_booking(booking_id)`·`idx_booking_reports_reporter_created(reporter_id, created_at)`(**`booking` 모듈 소유**, 신규 마이그레이션, [database-design](../database/database-design.md) — 07이 예약해 둔 `reports` 테이블과 **이름이 겹치지 않는 별개 테이블**이라 충돌하지 않는다; **`(reporter_id, booking_id)` 유니크는 두지 않는다 — 동일 예약 다건 신고를 허용하고, 인덱스는 운영 조회·향후 레이트리밋 집계용이다**). 신고 도배 방지 레이트리밋(`429`)은 **후속·이연**이라 관련 에러코드는 신설하지 않는다(옛 설계의 `BOOKING_REPORT_ALREADY_EXISTS`(409)·유니크는 제거됐다). 예약 신고 사유 카탈로그 테이블 `booking_report_reasons(id, code, lang, label, display_order, active)` + `(code, lang)` 유니크(**`booking` 모듈 소유**, 신규 마이그레이션 **V17__create_booking_report_reasons.sql** — 테이블 + 6종×3언어(`en`·`ko`·`ja`) 시드, 07의 `ReportReason`과 값이 같아도 별개), `booking_reports.reason`은 이 카탈로그 `code`를 **값 참조**로 저장(선택·nullable·FK 없음), 신고 경로용 **비필터 조회**(삭제·차단 필터 우회 — US-4-7과 공유), SecurityConfig에 `POST /api/v1/bookings/*/report`·`GET /api/v1/bookings/report-reasons` 매처(`hasRole("USER")`). 사유 라벨 번역을 위해 `user :: api`의 **기존** 공개 쿼리 `getLanguage(userId)`를 소비한다 — 이미 있는 메서드이고 `booking → user :: api`도 이미 화이트리스트라 **새 모듈 의존 엣지가 없다**(`diagnosis`·`gamification`·`lifetip`과 같은 경로). 사유 라벨은 **카탈로그 테이블 `booking_report_reasons`의 행**으로 관리한다 — 6종×3언어(`en`·`ko`·`ja`) 라벨을 V17 마이그레이션에 시드하고, 사유·언어를 늘릴 때는 **코드 배포 없이 행만 INSERT**한다(그 언어 행이 없으면 `en` 폴백). **일본어(`ja`) 라벨 포함 3언어 전부가 본 범위**이며 감수하는 공백이 아니다(US-4-9의 `lang=ja` → 일본어 라벨 AC가 그 근거). **리소스 번들에 얹지 않는 이유**: [ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)이 `messages` 번들을 **에러 메시지 전용**으로 규정해 키는 `ErrorCode` 이름이고 **키 집합 == `ErrorCode` 전체 상수**이며 언어를 `Accept-Language`로 정하는 반면, 사유 라벨은 **본문 콘텐츠**라 언어가 `getLanguage(userId)`에서 오고 코드 배포 없이 행 추가로 바뀌어야 한다 — 번들에 섞으면 키 규약과 언어 출처가 동시에 깨지고 라벨 추가마다 배포가 필요해진다. 접수가 `booking` 모듈 내부 처리라 **새 모듈 의존 엣지가 없고**, `report` 모듈(§7)은 손대지 않는다.

## 5. 커뮤니티 (게시판 · 동네친구)

> 관련 API 스펙: [05-community](../api/specs/05-community.md)

자유게시판(FREE)·동네생활(NEIGHBORHOOD) 게시판에서 외국인 사용자가 텍스트 게시글을 작성/조회하고, 좋아요·댓글·공유로 상호작용하며, 동네생활 게시글의 작성자와 1:1 채팅을 시작하는 기능이다. 게시글은 텍스트(제목+본문)만 다루며 사진·동영상은 범위 외다. 채팅 메시지 송수신 자체는 04(채팅) 스펙을 `NEIGHBOR` 카테고리로 재사용한다.

공통 전제(모든 스토리에 적용):

- 모든 응답은 공통 래퍼 `{ success, data, error }`를 따른다. 에러는 [error-response-guide](../api/error-response-guide.md)가 정본이다.
- 인증은 `Authorization: Bearer <accessToken>`(서버 자체 JWT). 토큰 만료 시 `401 TOKEN_EXPIRED`, 미인증 시 `401 UNAUTHENTICATED`.
- 차단/탈퇴 사용자 처리: 탈퇴 사용자의 게시글·댓글은 본문을 유지하되 작성자 표기를 익명 처리(닉네임 `(탈퇴한 사용자)`, 국적 `null`)한다. 차단 관계인 상대가 작성한 게시글·댓글을 목록·상세에서 제외하는 것은 **(후속·이연)** 설계 의도다 — 차단 모델 자체는 `user` 모듈이 소유한 `user_blocks(blocker_id, blocked_user_id)`(**사용자 단위 전역 차단**, 행 존재 = 차단·해제 = 행 삭제)로 확정됐으나(US-4-8, [database-design](../database/database-design.md) §4-2), `community`는 현재 `allowedDependencies = {common}`이라 차단 관계를 **조회할 수 없다**. 실제로 걸려면 `community → user :: api`(`findBlockedUserIds`) 의존 신설이 선행돼야 하며, §5는 1차 MVP 범위 밖이라 그 배선은 이연한다 — 현 시점 소유자만 확정된 상태다. **(확인 필요: 적용 시점)**
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
  - Then 새 방을 만들지 않고 기존 `chatRoomId`를 `200 OK`로 반환한다(멱등). A가 B를 차단했거나 B가 A를 차단한 관계면 `403 POST_CHAT_BLOCKED`로 거부한다(차단 모델은 `user` 모듈이 소유한 `user_blocks` — **사용자 단위 전역 차단**이라 어느 방향이든 행이 있으면 차단 관계다. US-4-8, [database-design](../database/database-design.md) §4-2. 다만 `community`는 현재 `allowedDependencies = {common}`이라 이 판정을 하려면 `community → user :: api`(`isBlockedBetween`) 의존 신설이 선행돼야 하며, §5 자체가 1차 MVP 범위 밖이라 배선은 함께 이연한다).

## 6. 게이미피케이션 (퀴즈)

> 관련 API 스펙: [06-gamification](../api/specs/06-gamification.md)

외국인 세입자(임차인)를 주 대상으로 하되 **임대인과 아직 로그인하지 않은 사용자를 포함해 누구나** 한국 주거 관련 지식을 **요청할 때마다 무작위로 제공되는 4지선다 퀴즈**로 반복 학습하는 기능이다. 사용자는 문항·보기를 조회하고, 고른 보기를 제출하면 서버가 저장된 정답과 대조해 **정답 여부**를 즉시 돌려준다 — 정답·오답 모두 **해설**을 함께 반환하고, 오답이면 **정답 보기**를 추가로 반환한다. 정답 판정은 전적으로 서버가 수행하며(클라이언트 응답값 신뢰 금지), 채점은 **무상태(stateless)** 다 — 제출 기록·포인트 적립·하루 1회 제한·`(userId, quizDate)` 유니크 제약이 없고, 사용자는 횟수 제한 없이 반복해 풀 수 있다.

> **범위 변경(이전 모델 대체)**: 이전 범위의 "오늘의 퀴즈(하루 1개)"·포인트 적립(`QUIZ_CORRECT`)·`/points` 합계·내역 조회 모델은 본 범위에서 **랜덤·무상태·다국어 학습 퀴즈로 대체**된다. 따라서 포인트 관련 스토리·엔드포인트는 제외되고, `QUIZ_NOT_TODAY`·`QUIZ_ALREADY_SUBMITTED` 도메인 에러는 발생하지 않는다. 이 대체 모델은 API 스펙([06-gamification](../api/specs/06-gamification.md))·시퀀스 다이어그램(`sequence-diagrams/06-gamification/`)·도메인 모델·DB 설계·[ADR-0035](../adr/0035-gamification-quiz-random-stateless-catalog.md)에 반영 완료됐다("한 도메인 = 네 곳" 정합, [CLAUDE.md](../../CLAUDE.md)). 남은 후속은 스캐폴드 코드(`src/main/java/com/kohere/gamification/**`) 재구현이다.
>
> **다국어 번역이 기반**이다. 퀴즈 문항·보기·해설의 **표시 텍스트**는 사용자의 **표시 언어**로 번역해 반환한다 — 표시 언어는 `gamification`이 `user` 모듈 공개 query(`getLanguage`)를 호출해 취득하며 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**으로 정한다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141); `Accept-Language`·토큰 클레임에 의존하지 않음). **비로그인 사용자는 이 query를 호출하지 않고 `en` 고정**이다(#181). **임대인은 `users.lang`이 서버 고정값 `'ko'`라 한국어로 본다**(US-1-5·US-1-9). 해당 언어 번역이 없으면 **영어(`en`)로 폴백**한다(에러 아님). 보기 **키(A~D)는 언어와 무관하게 불변**이며 표시 텍스트만 언어별이다(채점은 키로 검증). 번역 저장은 `diagnosis`와 동일하게 문항 도큐먼트 안 **인라인 언어-키 맵**으로 임베드하는 방식을 따른다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md), US-2-6와 동일 패턴).
>
> 인증·권한 표기 기준(#181로 개정): 본 기능은 **로그인 여부·`userType`과 무관하게 누구나** 이용할 수 있다 — 조회·채점 모두 **로그인 없이 된다**(애플 심사 대응). [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)의 `/api/v1/quizzes/**` 매처를 `hasRole("USER")` → **`permitAll`로 수정**한다(진단과 달리 매처가 이미 있으므로 신규 추가가 아니라 수정이다 — 인가를 여는 수단이 `ROLE_GUEST` 주입이 아니라 `permitAll`인 이유는 2절 비회원 접근 기준과 같다). **매처와 함께 응용 계층의 세입자 게이트(`GamificationService.assertTenant`)도 제거한다** — 비로그인 게스트에게 열린 콘텐츠를 로그인한 임대인에게만 `403`으로 막는 것은 앞뒤가 맞지 않고 실효도 없기 때문이다(임대인이 로그아웃하면 그대로 볼 수 있다). 그 결과 `gamification`은 `getUserType`을 더 이상 호출하지 않고 `com.kohere.gamification.domain.TenantOnlyException`은 사용처가 사라지며, **퀴즈에서 `403 FORBIDDEN`(TenantOnly) 케이스가 완전히 없어진다** — 즉 퀴즈에는 이제 **어떤 역할 게이트도 없다**(예약의 세입자 전용 게이트(US-4-1)는 이 변경과 무관하게 그대로다). 상태를 저장하지 않으므로 타인 리소스 접근 개념이 없고, 퀴즈는 신원을 영속에 담지 않아 **게스트 경로에는 신원 소비자가 하나도 남지 않는다**(세션 키도 요구하지 않는다). 표시 언어만 호출자별로 갈린다 — 게스트는 **`en` 고정**이며 `getLanguage`를 호출하지 않고(`users` 행이 없어 호출하면 `404 USER_NOT_FOUND`), 로그인 사용자는 `users.lang`을 따르는데 **임대인은 온보딩에서 서버가 `lang='ko'`·`country='KR'`을 고정으로 심으므로 퀴즈를 한국어(`ko`)로 본다**(US-1-5·US-1-9; `ko` 번역이 없는 문항은 `en` 폴백). `permitAll` 전환의 부수효과로 **온보딩 미완료(PENDING/TERMS_AGREED, `ROLE_ONBOARDING`) 토큰도 통과**하며(`403 AUTH_ONBOARDING_REQUIRED`가 이 경로에서 사라진다) 이를 의도로 수용한다. **토큰을 보냈는데 만료된 요청만 `401 TOKEN_EXPIRED`를 유지**하고, 토큰 미전송·위조 토큰은 게스트로 처리한다.

### US-6-1 — 랜덤 퀴즈 조회

**As a** 퀴즈를 풀려는 **누구나** — 외국인 세입자(`userType=TENANT`)·임대인(`userType=LANDLORD`)·**아직 로그인하지 않은 사용자** (역할 게이트 없음 — #181)
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
- 시나리오: 비로그인 조회 — 토큰 없이 `2xx`

  - Given Authorization 헤더가 없는(또는 위조된 토큰을 보낸) 비로그인 사용자다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `401`이 아니라 `200 OK`와 회원과 동일한 응답(`quizId`·`question`·`choices`)을 받는다(`/api/v1/quizzes/**`가 `permitAll` — #181). 이 경로에서 `UNAUTHENTICATED`는 더 이상 발생하지 않는다
  - And 표시 텍스트는 **영어(`en`)** 로 내려오고, 서버는 `getLanguage`를 **한 번도 호출하지 않는다**(호출하면 `users` 행이 없어 `404 USER_NOT_FOUND`). `getUserType`은 게스트뿐 아니라 **어떤 호출자에게도 호출하지 않는다** — 세입자 게이트를 제거해 `gamification`에서 이 포트 사용이 사라졌기 때문이다(#181). 게스트 세션 키(`X-Guest-Session-Id`)도 요구하지 않는다(퀴즈는 신원을 저장하지 않는다)
- 시나리오: 인증 실패 — 만료된 토큰(게스트로 강등하지 않음)

  - Given 만료된 access token을 보낸다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `permitAll` 경로라도 `401 Unauthorized` 와 `error.code=TOKEN_EXPIRED`를 받는다(재발급 유도). 토큰 미전송·위조 토큰만 게스트로 처리한다
- 시나리오: 권한 — 로그인한 임대인도 허용(종전 `403`에서 변경)

  - Given 온보딩을 마친 임대인(`userType=LANDLORD`)이 유효한 access token으로 접근한다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `403 FORBIDDEN`이 아니라 `200 OK`와 세입자와 동일한 응답(`quizId`·`question`·`choices`)을 받는다 — 응용 계층 세입자 게이트(`assertTenant`)를 제거했으므로 **퀴즈에 역할 게이트가 없다**(#181). 비로그인 게스트에게 열린 콘텐츠를 임대인에게만 막을 근거가 없다
  - And 표시 텍스트는 **한국어(`ko`)** 로 내려온다 — 임대인의 `users.lang`은 온보딩에서 서버가 `'ko'`로 고정하기 때문이다(US-1-9). `ko` 번역이 없는 문항은 `en`으로 폴백한다
- 시나리오: 온보딩 미완료(비-`ACTIVE`) 접근 — 이제 허용

  - Given 온보딩을 마치지 않은(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`) 토큰으로 접근한다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `403 AUTH_ONBOARDING_REQUIRED`가 아니라 `200 OK`를 받는다 — `permitAll` 전환으로 `hasRole("USER")`가 하던 온보딩 차단이 이 경로에서 사라지며 이를 의도로 수용한다. 로그인 없이 볼 수 있는 콘텐츠를 온보딩 중인 사용자에게만 막을 이유가 없다
  - And 표시 언어는 `en` 고정이 아니라 **`users.lang`을 따른다**(온보딩 중이어도 `users` 행은 존재한다)
- 시나리오: 경계 — 사용 가능한 퀴즈가 없음

  - Given 인증은 정상이나 퀴즈 콘텐츠 풀이 비어 있다
  - When `GET /api/v1/quizzes/random`을 호출한다
  - Then `404 Not Found` 와 `error.code=QUIZ_NOT_FOUND`를 받는다

### US-6-2 — 퀴즈 정답 제출 및 즉시 피드백

**As a** 퀴즈를 푼 **누구나** — 외국인 세입자(`userType=TENANT`)·임대인(`userType=LANDLORD`)·**아직 로그인하지 않은 사용자** (역할 게이트 없음 — #181)
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
- 시나리오: 비로그인 채점 — 토큰 없이 `2xx`

  - Given Authorization 헤더가 없는(또는 위조된 토큰을 보낸) 비로그인 사용자가 보기를 골랐다
  - When `POST /api/v1/quizzes/{quizId}/answer`에 `{ "selectedChoice": "B" }`를 보낸다
  - Then `401`이 아니라 `200 OK`와 회원과 동일한 채점 결과(`correct`, 오답이면 `correctChoice`, 그리고 `explanation`)를 받는다(`/api/v1/quizzes/**`가 `permitAll` — #181). `explanation`은 **영어(`en`)** 이고 서버는 `getLanguage`를 호출하지 않는다(`getUserType`은 세입자 게이트 제거로 호출자 구분 없이 아예 호출하지 않는다)
- 시나리오: 인증 실패 — 만료된 토큰(게스트로 강등하지 않음)

  - Given 만료된 access token으로 채점을 요청한다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `permitAll` 경로라도 `401 Unauthorized` 와 `error.code=TOKEN_EXPIRED`를 받는다(재발급 유도)
- 시나리오: 권한 — 로그인한 임대인도 허용(종전 `403`에서 변경)

  - Given 온보딩을 마친 임대인(`userType=LANDLORD`)이 유효한 access token으로 채점을 요청한다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `403 FORBIDDEN`이 아니라 `200 OK`와 세입자와 동일한 채점 결과(`correct`, 오답이면 `correctChoice`, `explanation`)를 받는다 — `assertTenant` 제거로 **역할 게이트가 없다**(#181)
  - And `explanation`은 **한국어(`ko`)** 로 내려온다(임대인의 `users.lang`은 서버 고정값 `'ko'` — US-1-9; `ko` 해설이 없으면 `en` 폴백)
- 시나리오: 경계 — 존재하지 않는 퀴즈 채점

  - Given 경로의 `{quizId}`가 존재하지 않는다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `404 Not Found` 와 `error.code=QUIZ_NOT_FOUND`를 받는다
- 시나리오: 반복 채점 — 부작용 없음(무상태)

  - Given 세입자가 같은 퀴즈를 여러 번 채점 요청한다
  - When `POST /api/v1/quizzes/{quizId}/answer`를 반복 호출한다
  - Then 매번 채점 결과만 반환하고 제출 기록·포인트 적립 등 상태 변경이 없다(하루 1회 제한·`409 QUIZ_ALREADY_SUBMITTED` 없음)

### US-6-3 — 사용자 표시 언어 기반 퀴즈 문항·해설 번역 제공

**As a** 퀴즈를 푸는 **누구나** — 한국어가 익숙하지 않은 외국인 세입자(`userType=TENANT`)·임대인(`userType=LANDLORD`)·**비로그인 사용자**
**I want** 퀴즈 문항·보기·해설을 내 표시 언어에 맞게 번역된 텍스트로 받기
**So that** 모국어 또는 영어로 문제를 이해하고 정확히 답할 수 있다 (로그인 사용자의 번역 기준은 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**이며 `user` 공개 query `getLanguage`로 취득([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)), **임대인은 `users.lang`이 서버 고정값 `'ko'`라 한국어로 본다**, **비로그인 사용자는 `en` 고정이며 `getLanguage`를 호출하지 않는다**(#181), 미지원 언어는 영어로 폴백, 보기 키 A~D는 언어와 무관하게 불변)

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
- 시나리오: 비로그인 — `en` 고정 및 `getLanguage` 미호출

  - Given 로그인하지 않은 사용자가 `Accept-Language`를 무엇으로 보내든 무관하게 퀴즈를 조회·채점한다
  - When `GET /api/v1/quizzes/random` 또는 `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then 문항·보기·해설 표시 텍스트가 **영어(`en`)** 로 반환되고, 서버는 `user` 공개 query `getLanguage`를 **호출하지 않는다**(호출하면 `users` 행이 없어 `404 USER_NOT_FOUND`). `Accept-Language` 기반 게스트 언어 결정은 이번 범위 밖이다. 보기 키(A~D)는 회원과 동일하게 불변이다
- 시나리오: 임대인 — `users.lang` 서버 고정값(`ko`)을 따름

  - Given 온보딩을 마친 임대인이 퀴즈를 조회·채점한다(임대인 온보딩에서 서버가 `lang='ko'`·`country='KR'`을 고정으로 심었다 — US-1-9)
  - When `GET /api/v1/quizzes/random` 또는 `POST /api/v1/quizzes/{quizId}/answer`를 호출한다
  - Then `403`이 아니라 `200 OK`를 받고(#181로 역할 게이트 제거) 문항·보기·해설이 **한국어(`ko`)** 로 반환된다 — 임대인은 지구본에서 표시 언어를 고를 수 없어 `ko` 외의 값이 나올 수 없다(US-1-5). `ko` 번역이 없는 문항은 세입자와 동일하게 `en`으로 폴백한다

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

세입자(외국인)를 주 대상으로 하되 **임대인과 아직 로그인하지 않은 사용자를 포함해 누구나** 한국 생활에 필요한 정보를 **주제(topic)** 별로 묶어 조회하는 읽기 전용 큐레이션 기능이다. 홈 화면 진입점([project-brief §4](../project/project-brief.md))에서 시작하며, 사용자는 먼저 주제 목록을 보고(US-8-1), 특정 주제를 고르면 그 주제에 속한 생활 팁(**제목 · 내용 · 사진**) 전체 리스트를 받는다(US-8-2). 한 주제에는 여러 개의 제목-내용-사진 항목이 들어갈 수 있다(주제 : 팁 = **1 : N**). 콘텐츠는 운영이 시드로 적재하는 큐레이션 콘텐츠이며 사용자 작성·수정·좋아요·신고가 없다(UGC인 커뮤니티(5절)와 구분된다).

**번역이 이 기능의 바탕이다** — 주제명·주제 설명(짧은·긴)·제목·내용 표시 텍스트는 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**으로 정한 언어로 번역해 내려주며(US-8-3), 진단 i18n과 **완전히 동일한 전략**을 재사용한다([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141), US-2-6): 표시 문자열을 도큐먼트 안 **인라인 언어-키 맵**(`{ "en": …, "ja": …, "ko": … }`)으로 임베드하고, 서버가 `user` 모듈 공개 query `getLanguage(userId)`로 취득한 언어 키로 문자열을 골라 조립하며, 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). **비로그인 사용자는 이 query를 호출하지 않고 `en` 고정**이다(#181). `Accept-Language` 헤더·토큰 클레임은 쓰지 않는다. 주제·팁의 식별자(`code`/`id`)와 이미지 URL(주제의 카드 이미지 `LifeTipTopic.imageUrl`·배경 이미지 `backgroundImageUrl`, 팁의 사진 `LifeTip.imageUrl`)은 언어 무관 불변이고, 표시 텍스트(주제명·주제 설명·제목·내용)만 언어별이다.

> **인증·상태 게이트 기준(#181로 개정)**: 대상 액터는 **로그인 여부·`userType`과 무관한 누구나**이고, 모든 조회는 **로그인 없이 이용할 수 있다**(애플 심사 대응). [`SecurityConfig`](../../src/main/java/com/kohere/common/security/SecurityConfig.java)의 `/api/v1/life-tips/**` 매처를 `hasRole("USER")` → **`permitAll`로 수정**한다(매처가 이미 있으므로 신규 추가가 아니라 수정이다 — 인가를 여는 수단이 `ROLE_GUEST` 주입이 아니라 `permitAll`인 이유는 2절 비회원 접근 기준과 같다). **매처와 함께 응용 계층의 세입자 게이트(`LifeTipService.assertTenant`)도 제거한다** — 비로그인 게스트에게 열린 콘텐츠를 로그인한 임대인에게만 `403`으로 막는 것은 앞뒤가 맞지 않고 실효도 없기 때문이다(임대인이 로그아웃하면 그대로 볼 수 있다). 그 결과 `lifetip`은 `getUserType`을 더 이상 호출하지 않고 `com.kohere.lifetip.domain.TenantOnlyException`은 사용처가 사라지며, **생활 팁에서 `403 FORBIDDEN`(TenantOnly) 케이스가 완전히 없어진다** — 즉 생활 팁에는 이제 **어떤 역할 게이트도 없다**. 표시 언어만 호출자별로 갈린다 — 게스트는 **`en` 고정**이며 `getLanguage`를 호출하지 않고(`users` 행이 없어 호출하면 `404 USER_NOT_FOUND`), 로그인 사용자는 `users.lang`을 따르는데 **임대인은 온보딩에서 서버가 `lang='ko'`·`country='KR'`을 고정으로 심으므로 생활 팁을 한국어(`ko`)로 본다**([ADR-0034](../adr/0034-landlord-phone-sms-verification.md) 개정(#141), US-1-5·US-1-9; `ko` 번역이 없으면 `en` 폴백). "온보딩 미완료 사용자는 표시 언어를 정할 프로필이 확정되지 않아 대상이 아니다"라는 근거는 게스트에게 성립하지 않으므로(정할 프로필이 없으면 `en`으로 읽으면 된다) **온보딩 미완료(PENDING/TERMS_AGREED, ROLE_ONBOARDING) 토큰도 이제 통과**하며(`403 AUTH_ONBOARDING_REQUIRED`가 이 경로에서 사라진다) 이를 의도로 수용한다. 생활 팁은 신원을 영속에 담지 않으므로 게스트 세션 키(`X-Guest-Session-Id`)를 요구하지 않는다. **토큰을 보냈는데 만료된 요청만 `401 TOKEN_EXPIRED`를 유지**하고, 토큰 미전송·위조 토큰은 게스트로 처리한다.
>
> **註 — 세입자 게이트의 신설과 폐지**: 과거에는 생활 팁의 `userType=TENANT` 검사가 없어 임대인도 조회를 통과했으나(기존 결함), #141에서 응용 계층 `assertTenant` 게이트를 신설해 퀴즈(6절)와 동일하게 맞췄다. **#181에서 이 게이트를 퀴즈와 함께 다시 폐지한다** — `permitAll`로 게스트가 볼 수 있게 된 이상 로그인한 임대인만 막는 것이 실효가 없기 때문이다. 따라서 세 기능 모두 역할 게이트가 없는 상태로 정렬된다: **진단(2절)은 원래부터 세입자·임대인 공통**이었고, 퀴즈(6절)·생활 팁(8절)이 이번에 합류한다(임대인은 셋 다 `ko`로 본다). **예약(4절)의 세입자 전용 게이트(`com.kohere.booking.domain.TenantOnlyException`)는 이 변경과 무관하게 그대로 남는다**(US-4-1).

> **저장소**: 문서형·가변 스키마·언어-키 맵 임베드 특성상 **MongoDB**에 둔다([ADR-0005](../adr/0005-polyglot-persistence.md) 폴리글랏, [ADR-0028](../adr/0028-diagnosis-questions-catalog-store.md) 진단 카탈로그 저장 방식과 정합). 모듈 경계(`lifetip` 신설 여부)·저장소·MVP 편입 시점 확정은 이슈 #79에서 다룬다.

### US-8-1 — 생활 팁 주제 목록 조회

**As a** 생활 정보를 찾는 **누구나** — 세입자(외국인)·임대인(`userType=LANDLORD`)·**아직 로그인하지 않은 사용자** (역할 게이트 없음 — #181)
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
- 시나리오: 비로그인 조회 — 토큰 없이 `2xx`

  - **Given** Authorization 헤더가 없는(또는 위조된 토큰을 보낸) 비로그인 사용자다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** `401`이 아니라 `200 OK`와 회원과 동일한 `topics[]`(주제당 `code`·`name`·`shortDescription`·`longDescription`·`imageUrl`·`backgroundImageUrl` 6필드)를 받는다(`/api/v1/life-tips/**`가 `permitAll` — #181). 이 경로에서 `UNAUTHENTICATED`는 더 이상 발생하지 않는다
  - **And** 표시 텍스트(`name`·두 설명)는 **영어(`en`)** 로 내려오고, 서버는 `getLanguage`를 **한 번도 호출하지 않는다**(호출하면 `users` 행이 없어 `404 USER_NOT_FOUND`). `getUserType`은 게스트뿐 아니라 **어떤 호출자에게도 호출하지 않는다** — 세입자 게이트를 제거해 `lifetip`에서 이 포트 사용이 사라졌기 때문이다(#181). `code`와 이미지 2종은 언어와 무관하게 동일하다
- 시나리오: 상태 게이트 — 온보딩 미완료(이제 허용)

  - **Given** PENDING/TERMS_AGREED 상태(ROLE_ONBOARDING 토큰)의 사용자다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** `403 AUTH_ONBOARDING_REQUIRED`가 아니라 `200 OK`를 받는다 — `permitAll` 전환으로 `hasRole("USER")`가 하던 온보딩 차단이 사라지며 이를 의도로 수용한다. 표시 언어는 `en` 고정이 아니라 **`users.lang`을 따른다**(온보딩 중이어도 `users` 행은 존재한다)
- 시나리오: 권한 — 로그인한 임대인도 허용(종전 `403`에서 변경)

  - **Given** 온보딩을 마친 임대인(`userType=LANDLORD`)이 유효한 access token으로 접근한다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** `403 FORBIDDEN`이 아니라 `200 OK`와 세입자와 동일한 `topics[]`(주제당 6필드)를 받는다 — 응용 계층 세입자 게이트(`assertTenant`)를 제거했으므로 **생활 팁에 역할 게이트가 없다**(#181)
  - **And** 표시 텍스트(`name`·두 설명)는 **한국어(`ko`)** 로 내려온다 — 임대인의 `users.lang`은 온보딩에서 서버가 `'ko'`로 고정하기 때문이다(US-1-9). `ko` 번역이 없으면 `en`으로 폴백하며, `code`와 이미지 2종은 언어와 무관하게 동일하다
- 시나리오: 인증 실패 — 만료된 토큰(게스트로 강등하지 않음)

  - **Given** 만료된 access token을 보낸다
  - **When** `GET /api/v1/life-tips/topics`를 호출한다
  - **Then** `permitAll` 경로라도 `401 Unauthorized`와 `error.code=TOKEN_EXPIRED`를 받는다(재발급 유도)

### US-8-2 — 특정 주제의 생활 팁(제목·내용·사진) 목록 조회

**As a** 특정 주제의 생활 정보를 보려는 **누구나** — 세입자(외국인)·임대인(`userType=LANDLORD`)·**아직 로그인하지 않은 사용자** (역할 게이트 없음 — #181)
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
- 시나리오: 비로그인 조회 — 토큰 없이 `2xx`

  - **Given** Authorization 헤더가 없는(또는 위조된 토큰을 보낸) 비로그인 사용자다
  - **When** `GET /api/v1/life-tips/topics/MOVING_IN/tips`를 호출한다
  - **Then** `401`이 아니라 `200 OK`와 회원과 동일한 `tips[]`(각 `id`·`title`·`content`·`imageUrl`)를 노출 순서대로 받는다(`/api/v1/life-tips/**`가 `permitAll` — #181). `title`·`content`는 **영어(`en`)** 이고 서버는 `getLanguage`를 호출하지 않는다(`getUserType`은 세입자 게이트 제거로 호출자 구분 없이 아예 호출하지 않는다)
  - **And** 존재하지 않는 주제 `code`는 로그인 여부와 무관하게 `404 LIFE_TIP_TOPIC_NOT_FOUND`다(게스트라고 `401`로 바뀌지 않는다)
- 시나리오: 상태 게이트 — 온보딩 미완료(이제 허용)

  - **Given** PENDING/TERMS_AGREED 상태(ROLE_ONBOARDING 토큰)의 사용자다
  - **When** `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** `403 AUTH_ONBOARDING_REQUIRED`가 아니라 `200 OK`를 받으며, 표시 언어는 **`users.lang`을 따른다**(온보딩 중이어도 `users` 행은 존재한다)
- 시나리오: 권한 — 로그인한 임대인도 허용(종전 `403`에서 변경)

  - **Given** 온보딩을 마친 임대인(`userType=LANDLORD`)이 유효한 access token으로 접근한다
  - **When** `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** `403 FORBIDDEN`이 아니라 `200 OK`와 세입자와 동일한 `tips[]`(각 `id`·`title`·`content`·`imageUrl`)를 노출 순서대로 받는다 — `assertTenant` 제거로 **역할 게이트가 없다**(#181)
  - **And** `title`·`content`는 **한국어(`ko`)** 로 내려온다(임대인의 `users.lang`은 서버 고정값 `'ko'` — US-1-9; `ko` 번역이 없으면 `en` 폴백)
- 시나리오: 인증 실패 — 만료된 토큰(게스트로 강등하지 않음)

  - **Given** 만료된 access token을 보낸다
  - **When** `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** `permitAll` 경로라도 `401 Unauthorized`와 `error.code=TOKEN_EXPIRED`를 받는다(재발급 유도)

### US-8-3 — 사용자 표시 언어 기반 생활 팁 번역 제공

**As a** 생활 팁을 읽는 **누구나** — 한국어가 익숙하지 않은 세입자(외국인)·임대인(`userType=LANDLORD`)·**비로그인 사용자**
**I want** 주제명·제목·내용을 내 표시 언어에 맞게 번역된 텍스트로 받고
**So that** 모국어 또는 영어로 생활 정보를 이해할 수 있다

- **우선순위**: High (외국인 대상 서비스의 핵심 접근성 — 이 기능의 바탕)
- **관련 NFR**: 국제화(i18n), 일관성(번역 누락 시 `en` 폴백), 보안(본인 표시 언어 기반 — 사용자 선택값)
- **백엔드 관점**: 번역 전략은 진단 i18n([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md), US-2-6)과 **동일**하며 별도 메커니즘을 만들지 않는다. 로그인 사용자의 번역 기준은 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**이고([ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141)), 표시 언어는 `user` 모듈 공개 query `getLanguage(userId)`를 **동기 호출**해 취득한다. **비로그인 사용자는 `en` 고정이며 이 query를 호출하지 않는다** — `users` 행이 없어 호출하면 `404 USER_NOT_FOUND`가 되므로 분기의 요점은 기본값이 아니라 호출 회피다(#181). **임대인은 `users.lang`이 온보딩에서 심긴 서버 고정값 `'ko'`라 한국어로 읽는다**(선택 불가 — US-1-5·US-1-9). 반면 `getUserType`은 세입자 게이트 폐지로 **호출자 구분 없이 더 이상 호출하지 않는다**(#181). 게스트에 대한 `Accept-Language` 해석은 이번 범위 밖이다(도출 규칙은 `user`가 캡슐화하며 query 시그니처는 불변; `Accept-Language`·토큰 클레임 미사용; [ADR-0002](../adr/0002-inter-module-communication-via-events.md) Decision 5 — 모듈 의존 `lifetip → user` 추가). 번역 텍스트는 별도 메시지 컬렉션·키 없이 주제·팁 도큐먼트 안 **인라인 언어-키 맵**으로 임베드한다 — 주제는 `name: { "en": …, "ja": …, "ko": … }`, 팁은 `title`/`content` 각각 언어-키 맵. 서버는 사용자 언어 키로 문자열을 고르고 그 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). 주제·팁 식별자(`code`/`id`)와 `imageUrl`(사진)은 언어 무관 불변이고 표시 텍스트만 언어별이다. US-8-1·US-8-2와 동일 엔드포인트에서 처리하며 응답 스키마는 언어와 무관하게 동일하다(서버가 언어 문자열만 채운다).

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
- 시나리오: 비로그인 — `en` 고정 및 `getLanguage` 미호출

  - **Given** 로그인하지 않은 사용자가 `Accept-Language`를 무엇으로 보내든 무관하게 생활 팁을 조회한다
  - **When** `GET /api/v1/life-tips/topics` 또는 `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** 주제명·주제 설명(짧은·긴)·제목·내용이 **영어(`en`)** 로 반환되고, 서버는 `user` 공개 query `getLanguage`를 **호출하지 않는다**(호출하면 `users` 행이 없어 `404 USER_NOT_FOUND`). `code`/`id`와 이미지 URL은 회원과 동일하게 언어 무관 불변이다
- 시나리오: 임대인 — `users.lang` 서버 고정값(`ko`)을 따름

  - **Given** 온보딩을 마친 임대인이 생활 팁을 조회한다(임대인 온보딩에서 서버가 `lang='ko'`·`country='KR'`을 고정으로 심었다 — US-1-9)
  - **When** `GET /api/v1/life-tips/topics` 또는 `GET /api/v1/life-tips/topics/{topicCode}/tips`를 호출한다
  - **Then** `403`이 아니라 `200 OK`를 받고(#181로 역할 게이트 제거) 주제명·주제 설명·제목·내용이 **한국어(`ko`)** 로 반환된다 — 임대인은 지구본에서 표시 언어를 고를 수 없어 `ko` 외의 값이 나올 수 없다(US-1-5). `ko` 번역이 없으면 세입자와 동일하게 `en`으로 폴백하고, `code`/`id`·이미지 URL은 언어 무관 불변이다
