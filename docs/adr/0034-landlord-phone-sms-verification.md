# ADR-0034. 임대인 연락처는 SMS 인증번호로 검증하고 이메일은 수집하지 않는다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0034 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-30 |
| 관련 문서 | [US-1-9·US-1-10](../requirements/user-stories.md), [01-auth-onboarding](../api/specs/01-auth-onboarding.md), [error-response-guide](../api/error-response-guide.md), [ADR-0003](./0003-jwt-auth-after-oauth-login.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0006](./0006-refresh-token-store-redis.md), [ADR-0015](./0015-sensitive-column-encryption.md), [ADR-0023](./0023-secrets-in-ssm-parameter-store.md), [ADR-0033](./0033-business-registry-verification.md) |

## Status

Proposed

> **개정(2026-07-08, [#131](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/131))**: 임대인 온보딩에서 **생년월일(`birthDate`)을 필수 수집**하도록 범위를 조정했다(세입자와 동일 규칙 — `YYYY-MM-DD`·과거 날짜만). **이메일 미수집(§5)·연락처 SMS 인증 결정은 그대로 유지**되며, 변경은 `birthDate`에 한정된다. 아래 §4의 요청 본문이 `{ name, phoneNumber, birthDate }`가 되고 `GET /users/me` 임대인 응답에도 `birthDate`가 포함된다(단, 프로필 수정 `PATCH /users/me`의 임대인 수정 대상에는 넣지 않는다 — 조회 전용). `gender`·`country`·`occupation`·`visaType`·`email`은 **여전히 미수집**이다.

> **개정(2026-07-16, [#141](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/141))**: 임대인에게 **표시 언어 `lang='ko'`와 국적 `country='KR'`을 서버가 고정 부여**하도록 개정한다. 임대인은 한국인 사업자이므로 기본 표시 언어가 한국어여야 하는데, 표시 언어의 기본값은 `en`(사용자가 `lang`을 고르지 않으면 영어, [ADR-0029](./0029-diagnosis-i18n-strategy.md) 개정)이라 서버가 `lang='ko'`를 심어 주지 않으면 **모든 임대인이 영어**로 표시된다. 이는 **온보딩 요청 본문 변경이 아니다** — §4의 요청 본문은 여전히 `{ name, phoneNumber, birthDate }` 세 필드이고 클라이언트는 `lang`·`country` 어느 것도 보내지 않는다. **서버가 온보딩 완료 시점에 두 값을 심고**, 기존 행은 `V13__users_lang.sql`에서 백필한다. 따라서 위 개정의 "`country`는 여전히 미수집"은 **이 항목으로 갈음한다**(클라이언트로부터 수집하지 않되 서버가 `KR`로 확정한다). `gender`·`occupation`·`visaType`·`email` **미수집은 그대로 유지**된다. 결과로 임대인 프로필 응답에 `country`·`countryName`·`countryFlag`가 포함된다([01-auth-onboarding](../api/specs/01-auth-onboarding.md) §5-2·§8 갱신). 임대인은 `lang`을 **변경할 수 없다** — 표시 언어 선택은 세입자(`TENANT`) 전용이다([#141](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/141)).

## Context

- 임대인 온보딩(US-1-9)은 본래 세입자와 **이메일 인증(US-1-6)을 공통 선행**으로 두고, 온보딩 제출에서 제출 `email`을 사전 인증값과 대조했다. 검증 게이트는 **약관 → 이메일 → 사업자번호** 순이었다.
- **요구사항 변경**: 임대인 트랙에서 **이메일 인증을 없애고 연락처(휴대폰) 인증으로 대체**하며, **임대인의 이메일 정보는 수집하지 않는다**. 세입자(`TENANT`)는 종전과 동일하게 이메일 인증·`email` 수집을 유지한다 — 변경은 임대인(`LANDLORD`) 트랙에 한정된다.
- 임대인 연락처(`phoneNumber`)는 온보딩 필수값으로 이미 받고 있었으나(US-1-9) **소유 검증 단계가 없었다**. "연락처 인증기능"은 이 번호가 **실제 본인 휴대폰인지** 확인하는 단계를 요구한다.
- 검증 사실의 출처는 사용자가 수신하는 **SMS 인증번호**다. 이메일 인증(`verification-code`/`verify`)이 코드 1건을 서버 외부 채널(메일)로 검증한 뒤 마커를 남기는 것과 **대칭 구조**가 자연스럽다(검증 단계 분리 → 온보딩 제출 시 대조). 한편 사업자번호 검증([ADR-0033](./0033-business-registry-verification.md))은 온보딩과 분리된 **무상태(stateless) 임대인 전용 검증 API**로, 온보딩 선행·마커·대조가 아니라 온보딩 완료 후 매물 등록 시점에 정식 토큰으로 호출되는 별개 경로다.
- 제약: 모듈 내부는 DDD 4계층(도메인 포트 + 인프라 어댑터)이고 외부 연동(SMS 게이트웨이)은 인프라 어댑터로만 새어 나가야 한다([ADR-0001](./0001-bounded-context-module-decomposition.md) 계열). `auth`·`user`는 MySQL, 검증 마커·refresh는 Redis다([ADR-0005](./0005-polyglot-persistence.md), [ADR-0006](./0006-refresh-token-store-redis.md)). 민감정보(연락처)는 MVP에서 컬럼 암호화 대신 마스킹·저장소 암호화로 갈음한다([ADR-0015](./0015-sensitive-column-encryption.md)). 외부 API 키(SMS provider)는 SSM Parameter Store에서 주입한다([ADR-0023](./0023-secrets-in-ssm-parameter-store.md)).
- 따라서 "임대인 연락처를 어떻게(인증 방식)·어디서(포트/어댑터) 검증하고, 검증 사실을 어떻게 보관·대조하며, 이메일 제거 범위를 어디까지 둘지"를 결정해야 한다.

## Decision

**임대인 연락처는 SMS 인증번호(휴대폰 소유 확인)로 검증하고, 검증 성공한 번호만 VERIFIED 마커로 남긴다. 검증은 이메일 인증과 대칭으로 별도 엔드포인트에서 선행하고, 임대인 온보딩 제출 시 마커를 대조한다. 임대인의 이메일은 온보딩·프로필·영속 어디에서도 수집·저장하지 않는다(세입자는 종전대로 유지).**

1. **검증 엔드포인트(선행, 2단계)**: 이메일 인증(`/auth/email/verification-code`·`/auth/email/verify`)과 대칭으로 둔다.
   - `POST /api/v1/auth/phone/verification-code` — `{ phoneNumber }`로 인증번호를 SMS 발송. **발송 성공 후에만** 챌린지를 저장한다.
   - `POST /api/v1/auth/phone/verify` — `{ phoneNumber, code }`로 인증번호를 확인. 일치 시 VERIFIED 마커를 남긴다.
   - 두 엔드포인트 모두 **약관 동의(`TERMS_AGREED`)를 선행 게이트**로 둔다(이메일 인증과 동일). `PENDING`이면 `422 AUTH_TERMS_AGREEMENT_REQUIRED`, `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`.
2. **아웃바운드 포트**: 도메인에 `VerificationSmsSender` 포트를 둔다. 인프라 어댑터가 **SOLAPI(국내 SMS API)** 를 SDK로 호출한다 — 외부 연동·SDK 세부는 어댑터 안에만 존재한다([ADR-0003](./0003-jwt-auth-after-oauth-login.md)의 포트/어댑터, [ADR-0033](./0033-business-registry-verification.md)의 `BusinessRegistryVerifier`와 동일 패턴). 이메일의 `VerificationEmailSender`(SMTP)와 대칭이다. 인증번호 생성·해시·검증은 서버가 보유하고 어댑터는 발송만 담당한다.
3. **챌린지·VERIFIED 마커(Redis)**: 이메일 인증 마커와 동일 패턴이다([ADR-0006](./0006-refresh-token-store-redis.md)의 키-값+TTL). **인증번호 정책은 이메일 인증과 통일한다** — 인증번호 6자리·코드 TTL 5분·검증 마커 TTL 30분·검증 시도 상한 5회·재발송 간격 60초.
   - `phone-verify:code:{userId}` — `{ phoneNumber, codeHash, attempts, issuedAt, expiresAt, status }`, TTL=인증번호 만료(5분 — 이메일과 동일). 인증번호는 **단방향 해시(SHA-256(+pepper))로만** 보관(원문은 SMS로만).
   - `phone-verify:verified:{userId}` — 검증 완료 `phoneNumber`, TTL=온보딩 토큰 만료(30분 — 이메일과 동일).
4. **온보딩 제출 시 대조**: `POST /api/v1/auth/landlord/onboarding`은 검증 게이트를 **약관 미동의 → 연락처 미인증** 우선순위로 통과시킨다(약관 → 연락처 두 단계만이며 **사업자번호 게이트는 없다**). 요청 본문은 `{ name, phoneNumber, birthDate }` 세 필드다(사업자번호·이메일 필드 없음; `birthDate`는 필수·과거 날짜만 — [#131](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/131)). 제출 `phoneNumber`가 마커 값과 일치할 때만 통과하고, 성공 시 `TERMS_AGREED→ACTIVE` + `userType=LANDLORD` 확정 + 닉네임 자동배정 + 정식 토큰을 발급한다. **사업자번호 검증은 온보딩과 분리된 별도 API**([ADR-0033](./0033-business-registry-verification.md))로, 온보딩을 마친(`ACTIVE`) 임대인이 매물 등록 시점에 정식 토큰으로 호출하는 무상태 검증이며 온보딩 선행·게이트가 아니다.
5. **이메일 미수집(임대인)**: 임대인 온보딩 요청 본문·프로필 응답·`users.email` 어디에도 이메일을 두지 않는다. 임대인은 `users.email`이 **NULL**이다. 단, 소셜 로그인 단계에서 OIDC가 회신하는 제공자 이메일(`auth.SocialAccount.email` / `social_accounts.email`)은 **연락 이메일이 아닌 소셜 연동 메타데이터**이므로 역할과 무관하게 종전대로 보관한다(둘은 별개 — [database-design §4-2](../database/database-design.md)). 세입자는 `users.email` 수집·인증을 **종전대로 유지**한다.
6. **연락처 저장·변경**: 검증 통과한 `phoneNumber`를 `users.phone_number`로 영속한다(임대인 전용, 세입자 NULL). 응답·로그·`toString`에는 **마스킹**해 노출한다(예 `010-****-5678`). 본인 `GET /users/me`만 평문 반환. **프로필에서 연락처를 변경할 때(US-1-5)도 새 번호를 SMS 재인증해 VERIFIED된 뒤에만 반영**하며(미인증·불일치 `422 AUTH_PHONE_NOT_VERIFIED`), 검증 엔드포인트(`/auth/phone/verification-code`·`/auth/phone/verify`)와 마커를 그대로 재사용한다 — 이때는 정식 토큰(`ACTIVE`) 컨텍스트를 허용하도록 보안 경로 티어를 확장한다.
7. **에러 매핑**: 신규 도메인 에러코드 2종을 추가한다(이메일 인증의 `AUTH_EMAIL_*`와 대칭).
   - `AUTH_PHONE_VERIFICATION_FAILED`(422) — 검증 엔드포인트에서 **인증번호 불일치·만료·미발송**.
   - `AUTH_PHONE_NOT_VERIFIED`(422) — 온보딩 제출 시 **미인증(마커 없음)·불일치**.
   - SMS **발송 장애**(타임아웃·5xx)는 신규 코드를 만들지 않고 기존 공통 `UPSTREAM_ERROR`(502)를 재사용한다(이메일 발송 실패와 대칭).
   - 재발송·검증 시도 레이트리밋 초과는 `429 TOO_MANY_REQUESTS`(임계값 확인 필요).
8. **보안 경로 티어**: `/auth/phone/verification-code`·`/auth/phone/verify` 두 신규 엔드포인트는 **온보딩 토큰 허용 티어**(약관·이메일 인증·온보딩과 동일)에 둔다(사업자번호 검증은 온보딩 완료 후 정식 토큰으로 호출되는 별도 API라 이 티어가 아니다). **단 프로필 연락처 변경(US-1-5, §6)을 위해 정식 토큰(`ACTIVE`, `ROLE_USER`)도 함께 허용**한다 — `SecurityConfig`에서 `/auth/phone/**`를 온보딩 토큰·정식 토큰 양쪽이 통과하는 경로로 설정한다(검증·마커 로직은 두 컨텍스트 공용). SOLAPI API Key·Secret은 환경변수로 주입하고(운영은 SSM, [ADR-0023](./0023-secrets-in-ssm-parameter-store.md)) 1급 시크릿(apiSecret)은 로그에 노출하지 않는다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. SMS 인증번호 + VERIFIED 마커(채택)** | 이메일 인증과 대칭(구조·코드 재사용), 휴대폰 소유를 즉시 확인, 검증과 온보딩 분리 | SMS provider 외부 의존·발송 비용·장애 모드 추가 | — |
| B. 휴대폰 본인인증(PASS/NICE 등) | 소유 + 실명·CI/DI까지 확보 | 외부 본인확인 연동·약관·CI/DI 저장 등 범위·비용·PII 노출면 급증, MVP 과투자 | 연락처 소유 확인 목적에 과함(실명 검증은 요구사항 아님) |
| C. 인증 없이 연락처 수집만 | 외부 의존 없음, 단순 | 본인 휴대폰 여부 미확인 → "연락처 인증기능" 요구 미충족 | 검증 단계 부재로 요구사항 미달 |
| D. 이메일 인증 유지(임대인) | 변경 없음 | 요구사항(이메일 미수집·연락처 인증)과 정면 배치 | 요구사항 변경으로 폐기 |

## Consequences

- **긍정**: 임대인 휴대폰 소유를 온보딩 단계에서 확인해 연락처 신뢰도를 확보한다. 이메일 인증(US-1-6)과 동일한 포트/어댑터·Redis 마커·게이트 패턴이라 구조가 일관되고 코드 재사용이 크다. 임대인 이메일을 보관하지 않아 PII 노출면이 줄고, 세입자 흐름은 영향받지 않는다.
- **부정/트레이드오프**: 임대인 사인업 경로에 SMS provider 외부 의존·발송 비용·rate-limit·장애 모드가 추가된다(이메일과 동일 성격). SMS 발송 실패 시 `UPSTREAM_ERROR` 폴백·관측을 정의해야 하고, provider·단가·국가(국내외 번호) 정책을 확정해야 한다.
- **후속 작업(구현 PR)**: `ErrorCode`에 두 코드 추가 + 메시지 리소스 번들([ADR-0030](./0030-error-message-i18n-resource-bundle.md)), `VerificationSmsSender` 포트 + SOLAPI 어댑터(SOLAPI Java SDK `com.solapi:sdk`·자격은 env/SSM 주입), `PhoneVerification` 도메인·Redis 마커 저장/대조(이메일 인증 클래스 대칭 복제), `SecurityConfig`에 `/auth/phone/**` 경로 티어, `user`에 `phone_number` 영속(임대인). 문서 정합: [01-auth-onboarding](../api/specs/01-auth-onboarding.md)·[user-stories](../requirements/user-stories.md)·[domain-model](../architecture/domain-model.md)·[database-design](../database/database-design.md)·시퀀스 다이어그램(US-1-9·US-1-10)·[ADR-0033](./0033-business-registry-verification.md)(온보딩과 분리된 무상태 사업자번호 검증 API) 갱신.
- **결정됨**:
  - SMS **provider = SOLAPI**(국내 SMS API). 어댑터가 인증번호를 발송만 하고, OTP 생성·해시·검증은 서버 보유. **국내 발신 제한이 없고 개발자 경험(SDK·콘솔)이 우수**해 채택했다. API Key·Secret은 env/SSM로 주입하고 발신번호는 SOLAPI 콘솔에 사전 등록한다.
  - 인증번호 **정책 = 이메일 인증(US-1-6)과 통일**: 6자리·코드 TTL 5분·검증 마커 TTL 30분·검증 시도 5회·재발송 간격 60초.
  - **프로필 연락처 변경 시 SMS 재인증 필수**(US-1-5): 새 번호를 재인증(VERIFIED)해야 반영, 미인증 `422 AUTH_PHONE_NOT_VERIFIED`.
- **미결(확인 필요)**:
  - SOLAPI **단가·발송 한도**·발신번호 **사전등록**(국내 규정) 정책 확정.
  - 연락처 **유니크 제약**(동일 번호 다계정 허용 여부 — 현재 미적용).

## Validation

- 약관 동의(`TERMS_AGREED`) 임대인 가입자가 `POST /api/v1/auth/phone/verification-code`로 SMS를 받고, **발송 성공 후에만** `phone-verify:code:{userId}` 챌린지(해시)가 TTL과 함께 생성되는지 확인.
- `POST /api/v1/auth/phone/verify`에서 인증번호 일치 시 `phone-verify:verified:{userId}` 마커가 생성되고, 불일치·만료·미발송이 `422 AUTH_PHONE_VERIFICATION_FAILED`로, SMS 발송 장애가 `502 UPSTREAM_ERROR`로 매핑되는지 확인.
- 온보딩 제출에서 마커가 없거나 제출 `phoneNumber`가 마커와 불일치할 때 `422 AUTH_PHONE_NOT_VERIFIED`로 거부되고, 게이트 우선순위(약관→연락처)대로 첫 위반이 보고되는지 확인.
- 임대인 온보딩 요청·프로필 응답·`users` 영속 어디에도 이메일이 없고(`users.email` NULL), 세입자 이메일 인증·수집은 종전대로 동작하는지 확인.
- 연락처 원문이 로그·타 사용자 응답에 마스킹(`010-****-5678`)되고 본인 `GET /users/me`만 평문인지 확인.
- **재검토 시점**: SMS 발송 장애·비용·rate-limit이 임대인 사인업 성공률을 떨어뜨리면 provider 다중화 또는 본인인증(대안 B)을 재검토한다.
