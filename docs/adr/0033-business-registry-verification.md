# ADR-0033. 임대인 사업자등록번호는 온보딩과 분리된 무상태 API로 비즈노 조회·검증하고 결과를 응답으로만 반환한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0033 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-06-30 |
| 관련 문서 | [US-1-8·US-1-9](../requirements/user-stories.md), [01-auth-onboarding](../api/specs/01-auth-onboarding.md), [error-response-guide](../api/error-response-guide.md), [ADR-0003](./0003-jwt-auth-after-oauth-login.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0006](./0006-refresh-token-store-redis.md), [ADR-0015](./0015-sensitive-column-encryption.md), [ADR-0023](./0023-secrets-in-ssm-parameter-store.md), [ADR-0039](./0039-listing-schema-v4-registration-form.md) |

## Status

Proposed · **Amended(온보딩 분리·무상태, 2026-07-01)** · **Amended(매물 문서 한정 원문 저장, 2026-08-12)** · **Amended(등록 시점 자동 호출 철회, 2026-08-12)**

> **갱신 노트(Amended)**: 사업자번호 검증을 **온보딩과 분리**하고 **무상태(stateless)** 로 전환한다. 검증은 더 이상 온보딩 선행 게이트가 아니며, 온보딩을 마친(`ACTIVE`, `ROLE_USER`) 임대인이 **필요할 때** 별도로 호출하는 임대인 전용 API다(호출 시점은 아래 2026-08-12 「매물 등록 API」 개정 참조). **Redis VERIFIED 마커·사업자번호 해시 pepper·온보딩 제출 시 마커 대조·`AUTH_BUSINESS_NUMBER_NOT_VERIFIED`(422) 코드는 모두 제거**했다. 검증 결과는 서버에 저장하지 않고 **응답(HTTP body)에만** 담는다. `user.business_registration_number_hash` 컬럼은 스키마로 유지하되 **온보딩 시 NULL**이다. 아래 본문은 이 갱신을 반영한 최신 결정이고, 비즈노 요청/응답 계약과 판정 규칙(번호 일치 + 휴폐업 아님)은 그대로 유지한다.

> **개정(2026-08-12, [ADR-0039](./0039-listing-schema-v4-registration-form.md))**: **"원문 비저장·해시로만 영속"을 매물 문서 한정으로 개정**한다. 매물 스키마 v4는 임대인이 등록 폼에 입력한 사업자등록번호를 `listings` 문서의 `businessRegistrationNumber`에 **원문**으로 저장한다(세입자 응답에서는 제외). **온보딩·`users` 테이블에는 여전히 미채택**이며, `user.business_registration_number_hash`는 **매물 등록 시점에도 채우지 않는다** — 원문이 매물 문서에 있어 해시 사본이 불필요하다. `auth`의 **무상태 검증(§1·§3, 검증 결과 미저장)은 그대로 유지**된다. 아래 **§5·대안 D·Consequences는 이 항목으로 갈음한다**.

> **개정(2026-08-12, 매물 등록 API)**: **매물 등록 시점에는 이 검증을 호출하지 않는다** — 등록 API(`POST /api/v2/listings`)는 사업자등록번호를 **형식만 검증해 저장**하고, 진위는 **관리자가 승인 심사에서 수동으로 확인**한다. `POST /api/v1/auth/business/verify`는 임대인이 필요할 때 직접 호출하는 무상태 검증 엔드포인트로 **그대로 유지**된다. §1·§2·§3의 인가·판정·무상태 결정은 **그대로 유지**되고, 바뀌는 것은 **호출 시점뿐**이다 — 본문에서 "매물 등록 시점에 호출한다"로 적힌 서술은 이 항목으로 갈음한다. 관리자 승인(`PENDING → PUBLISHED`/`REJECTED`) API는 그 뒤 `POST /api/v1/admin/listings/{listingId}/approval`·`/rejection`으로 **나왔고**, 진위 확인은 예정대로 그 심사에서 **수동**으로 이뤄진다 — 임대인이 매물을 수정해 다시 심사에 올리는 경로(`PUT /api/v2/listings/{listingId}`)에서도 판정 주체는 같다([ADR-0039](./0039-listing-schema-v4-registration-form.md)).

## Context

- 온보딩은 두 역할(`TENANT`/`LANDLORD`)이 소셜 로그인·약관 동의까지 공통이고, 이후 본인 확인(세입자 이메일 인증 / 임대인 연락처 SMS 인증·사업자번호 검증)과 **온보딩 제출 엔드포인트에서 분기**한다(세입자 `POST /api/v1/auth/onboarding`, 임대인 `POST /api/v1/auth/landlord/onboarding`). 임대인은 이메일을 수집하지 않는다([ADR-0034](./0034-landlord-phone-sms-verification.md)). `userType`은 온보딩 제출로 확정·이후 불변이다([US-1-9](../requirements/user-stories.md)).
- **US-1-8**: 임대인은 **매물을 등록하려면 사업자등록번호**를 제출해야 하고, 서버는 그 번호가 **실재하고 정상 영업(계속) 상태인 사업자**인지 확인해야 한다. 미등록·휴업·폐업 사업자나 진위 불일치는 매물 등록 자격을 받을 수 없다. **온보딩(약관 동의 + 연락처 SMS 인증)은 사업자번호와 무관하게 완료**되며([ADR-0034](./0034-landlord-phone-sms-verification.md)), 사업자번호 검증은 온보딩 이후 매물 등록 시점의 별도 관심사다.
- 검증 사실의 출처는 **국세청 사업자등록정보(진위·상태)** 다. 우리 시스템은 사업자 마스터를 보유하지 않으므로 **외부에 위임**해야 한다. 이 검증은 온보딩 흐름의 선행 게이트가 아니라 **매물 등록 시점에 그때그때 조회하는 일회성 확인**이므로, 검증 사실을 서버에 남길 필요가 없다(무상태). 이메일/연락처 인증이 서버 외부 채널로 코드를 검증해 마커를 남기는 것과 달리, 사업자번호는 **국세청 마스터가 곧 진실의 원천**이라 재조회가 항상 가능하다.
- 제약: 모듈 내부는 DDD 4계층(도메인 포트 + 인프라 어댑터)이고 외부 연동은 인프라 어댑터로만 새어 나가야 한다([ADR-0001](./0001-bounded-context-module-decomposition.md) 계열). 임대인도 별도 모듈이 아니라 `user` 애그리거트이고, `auth`·`user`는 MySQL, 검증 마커·refresh는 Redis다([ADR-0005](./0005-polyglot-persistence.md), [ADR-0006](./0006-refresh-token-store-redis.md)). 민감 컬럼은 MVP에서 컬럼 암호화 대신 마스킹·저장소 암호화로 갈음한다([ADR-0015](./0015-sensitive-column-encryption.md)). 시크릿(외부 API 키)은 SSM Parameter Store에서 주입한다([ADR-0023](./0023-secrets-in-ssm-parameter-store.md)).
- 따라서 "사업자번호 검증을 어떻게(동기/비동기)·어디서(포트/어댑터)·무엇 기준으로 통과시키고, 검증 결과를 어떻게 반환할지(저장 vs. 무상태)"를 결정해야 한다.

## Decision

**사업자번호 검증은 온보딩과 분리된 임대인 전용 *무상태(stateless)* API(`POST /api/v1/auth/business/verify`)다. 온보딩을 마친(`ACTIVE`, `ROLE_USER`) 임대인이 *필요할 때 직접* 정식 토큰으로 호출하면, 비즈노 API(국세청 사업자등록정보 기반)로 *동기* 조회해 진위가 확인되고 *계속*(정상 영업) 상태이면 `verified:true`를 응답한다. 검증 결과는 서버에 저장하지 않고 응답(HTTP body)에만 담는다 — Redis 마커·해시 pepper·온보딩 제출 시 대조는 두지 않는다. 매물 등록 API는 이 검증을 호출하지 않는다(§1).**

1. **검증 엔드포인트(온보딩과 분리·무상태)**: `POST /api/v1/auth/business/verify`(임대인 전용). 요청 `{ businessRegistrationNumber(숫자 10자리 또는 하이픈 형식 123-45-67890) }`를 비즈노 API로 **동기 조회**해 진위 + **계속(정상) 상태**가 모두 충족되면 `{ businessRegistrationNumber(마스킹), verified:true }`를 응답한다. **정식 토큰(`ACTIVE`, `ROLE_USER`) 필수** — 온보딩 토큰(`PENDING`/`TERMS_AGREED`, `ROLE_ONBOARDING`)으로 호출하면 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌(`userType=TENANT`) `ACTIVE` 사용자면 `403 FORBIDDEN`이다. 온보딩 선행 게이트가 **아니다**. 또한 **매물 등록 시점에는 이 검증을 호출하지 않는다** — 등록 API(`POST /api/v2/listings`)는 사업자등록번호를 **형식만 검증해 저장**하고, 진위는 **관리자가 승인 심사에서 수동으로 확인**한다. `POST /api/v1/auth/business/verify`는 임대인이 필요할 때 직접 호출하는 무상태 검증 엔드포인트로 **그대로 유지**된다.
2. **아웃바운드 포트**: 도메인에 `BusinessRegistryVerifier` 포트를 둔다. 인프라 어댑터가 **비즈노 fapi**(국세청 사업자등록정보 진위·상태)를 호출한다 — 외부 연동·HTTP 세부는 어댑터 안에만 존재한다([ADR-0003](./0003-jwt-auth-after-oauth-login.md)의 `OidcTokenVerifier`/[ADR-0031](./0031-apple-sign-in-authorization-code-flow.md)의 `AppleAuthClient`와 같은 포트/어댑터 패턴). 요청은 `GET https://bizno.net/api/fapi?key={apiKey}&gb=1&q={사업자번호}&type=json`(RestClient, 필요한 설정은 API Key뿐)이다. 사업자번호는 **하이픈을 제거한 숫자 10자리**로 `q`에 보내고(사용자 입력은 하이픈 포함 가능), 응답 `items[]`에서 조회 번호와 `bno`(하이픈 포함)를 **양쪽 숫자만 정규화해 대조**한다. `items`는 고정 슬롯이라 빈 자리가 **`null`로 패딩**되므로 소비 시 null을 걸러낸다. 비즈노가 JSON을 `application/json`이 아닌 Content-Type(예 `text/html`)으로 반환할 수 있어 **선언 Content-Type과 무관하게 JSON으로 파싱**한다(Jackson 컨버터가 모든 미디어 타입 처리 — 바이트 스트림에서 UTF-8 자체 감지로 한글 `bstt`도 안전). 일치하고 상태 코드 `bsttcd`가 `01`(계속사업자)인 사업자가 있으면 정상으로 판정한다(`02` 휴업·`03` 폐업·미표기는 미검증, 미등록·4xx는 검증 실패, 5xx/타임아웃은 502). `bstt`(텍스트)·`EndDt`(폐업일)는 표시·로그용 참고값이다.
3. **무상태(검증 결과 미저장)**: 검증 결과를 서버 어디에도 남기지 않는다. **Redis VERIFIED 마커(`business-verify:verified:{userId}`)는 두지 않고**, `status` enum(상태 모델 `PENDING→TERMS_AGREED→ACTIVE→WITHDRAWN`은 불변)도 건드리지 않으며, `user.business_registration_number_hash` 컬럼에도 **쓰지 않는다**. 검증 결과는 오직 응답(HTTP body)의 `verified:true`로만 반환한다. 국세청 마스터가 진실의 원천이라 필요할 때 재조회하면 되므로 마커·TTL·솔트/pepper가 불필요하다.
4. **온보딩과 분리(사업자번호 게이트 없음)**: `POST /api/v1/auth/landlord/onboarding`은 **약관 동의 + 연락처(SMS) 인증만으로** 완료된다([ADR-0034](./0034-landlord-phone-sms-verification.md)). 요청 본문은 `{ name, phoneNumber, birthDate }`이고 `businessRegistrationNumber` 필드는 없다([#131](https://github.com/swyp-app-5th-team1/Kohere-backend/issues/131)로 `birthDate`가 추가됐으나 사업자번호 게이트와는 무관). 검증 게이트는 **약관 미동의(`PENDING`)→`422 AUTH_TERMS_AGREEMENT_REQUIRED`(이미 `ACTIVE`면 `409 AUTH_ONBOARDING_ALREADY_COMPLETED`) → 연락처 미인증→`422 AUTH_PHONE_NOT_VERIFIED`** 우선순위이며 **사업자번호 게이트는 없다**. 성공 시 `TERMS_AGREED→ACTIVE` + `userType=LANDLORD` 확정 + 닉네임 자동배정 + 정식 토큰을 발급한다. 사업자번호 검증은 이 흐름과 무관하며, **매물 등록 시점에도 호출되지 않는다**(§1) — 필요할 때 §1의 무상태 API를 임대인이 직접 호출한다.
5. **사업자번호 저장(`users`는 항상 NULL, 원문은 매물 문서)**: 무상태 검증 API는 사업자번호를 **어디에도 저장하지 않는다**(응답에 마스킹해 반환할 뿐). `user.business_registration_number_hash` 컬럼은 스키마로 **유지하되 항상 NULL**이다 — 온보딩은 사업자번호를 수집하지 않고, **매물 등록 시점에도 채우지 않는다**. 임대인이 등록 폼에 입력한 사업자등록번호는 `listings` 문서의 `businessRegistrationNumber`에 **원문으로** 저장하고 세입자 응답에서는 제외한다([ADR-0039](./0039-listing-schema-v4-registration-form.md)) — 원문이 매물 문서에 있으므로 `users`에 해시 사본을 두지 않는다(같은 사실을 두 저장소에 흩뜨리지 않는다). `auth` 검증 경로의 응답·로그·`toString`에는 종전대로 **마스킹**해 노출한다(예 `****567890`, [ADR-0015](./0015-sensitive-column-encryption.md)의 해시 + 마스킹 갈음). **해시 pepper(`app.auth.business-pepper` / `BUSINESS_PEPPER`)는 제거**했다(해셔 삭제).
6. **에러 매핑**: 검증 엔드포인트의 에러코드는 다음과 같다.
   - `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`(422) — 비즈노 조회에서 **미등록·휴업·폐업·진위 실패**.
   - 비즈노 **외부 장애**(타임아웃·5xx)는 신규 코드를 만들지 않고 기존 공통 `UPSTREAM_ERROR`(502)를 재사용한다.
   - 인가: 온보딩 토큰으로 호출 시 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌 `ACTIVE` 사용자면 `403 FORBIDDEN`(§1).
   - `AUTH_BUSINESS_NUMBER_NOT_VERIFIED`(422)는 **제거**했다 — 온보딩 제출 시 사업자번호 대조가 사라졌기 때문(§4). 문서 어디에도 이 코드를 남기지 않는다.
7. **보안 경로 티어**: `POST /auth/business/verify`는 **정식 토큰 전용 티어(`ACTIVE`, `ROLE_USER`)** 에 둔다 — 온보딩 토큰은 통과하지 못하고 `403 AUTH_ONBOARDING_REQUIRED`로 거부되며, `ACTIVE`라도 임대인이 아니면 `403 FORBIDDEN`이다(온보딩 엔드포인트가 온보딩 토큰 티어인 것과 대비된다). 외부 API 키 등 시크릿은 SSM에서 주입한다([ADR-0023](./0023-secrets-in-ssm-parameter-store.md)).

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. 비즈노 API 동기 검증 + 무상태 응답(채택)** | 실재·정상 상태를 즉시 판별(미등록/휴폐업 식별), 검증을 온보딩·매물 등록 어느 흐름의 게이트로도 두지 않아 임대인이 필요할 때 호출, 마커·TTL 동기화·저장소 분산 불필요(국세청 마스터 재조회) | 외부 HTTP 의존·지연·장애 모드, 외부 API 키 관리, 호출마다 재조회(캐시 없음) | — |
| B. 형식(체크섬)만 검증, 실재성 미확인 | 외부 의존 없음, 단순 | 휴·폐업·가짜 번호 통과 → US-1-8 충족 불가 | 실재·정상 상태 요구를 못 채움 |
| C. 검증 결과를 Redis 마커/DB에 저장(상태 유지) | 재조회 없이 이후 참조 가능 | 마커 TTL·컬럼 최신성 관리, 저장소 분산·정합 부담, 매물 등록 시점과 검증 시점 불일치 시 stale | 국세청 마스터가 진실의 원천이라 필요 시 재조회가 단순·정확 → 무상태로 충분 |
| D. 사업자번호 원문 평문 저장 | 재조회·관리자 확인 용이 | PII/사업자정보 노출면 확대([ADR-0015](./0015-sensitive-column-encryption.md) 취지에 역행) | **온보딩·`users`에는 미채택** — 무상태 검증은 원문 저장 자체가 불필요(응답 마스킹). **`listings` 문서에는 채택** — 사업자등록번호가 매물의 속성이라 원문으로 저장하고 세입자 응답에서 제외한다([ADR-0039](./0039-listing-schema-v4-registration-form.md)) |

## Consequences

- **긍정**: 미등록·휴업·폐업 사업자를 즉시 판별할 수단을 확보한다 — 등록을 자동 차단하는 게이트가 아니라 **관리자 승인 심사의 판단 근거**다. 포트/어댑터로 외부 연동을 인프라에 가둔다. **무상태**라 마커 저장·TTL 동기화·저장소 분산·stale 대조가 없어 단순하고, 검증을 온보딩 흐름에서 떼어내 온보딩(약관+연락처)을 가볍게 유지한다. `auth`·`users`는 사업자번호를 보관하지 않아 인증 경로의 노출면이 작다 — 원문 보관은 `listings` 문서 한 곳뿐이다([ADR-0039](./0039-listing-schema-v4-registration-form.md)).
- **부정/트레이드오프**: 임대인 검증 경로에 외부 HTTP(비즈노) 의존·지연·실패 모드가 추가되고, 외부 API 키 관리·요청 비용·rate-limit 부담이 생긴다. **무상태**이므로 호출할 때마다 재조회한다(캐시 없음 — 남용 시 rate-limit 필요). 외부 장애 시 `UPSTREAM_ERROR` 폴백 동작을 정의·관측해야 한다. 다만 **매물 등록 경로에는 이 의존이 없다** — 등록은 형식 검증만 하므로 비즈노 장애가 매물 등록을 막지 않고, 대신 진위 확인 책임이 관리자 승인 심사(수동)로 옮겨간다.
- **후속 작업(구현 PR)**: `ErrorCode`에 `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED` 유지·메시지 리소스 번들([ADR-0030](./0030-error-message-i18n-resource-bundle.md))(`AUTH_BUSINESS_NUMBER_NOT_VERIFIED`는 제거), `BusinessRegistryVerifier` 포트 + 비즈노 어댑터(RestClient·SSM 키), `SecurityConfig` 정식 토큰 티어(`ACTIVE`·`ROLE_USER`·임대인 인가), `business_registration_number_hash` 컬럼은 스키마 유지·**항상 NULL**(매물 등록도 채우지 않는다 — 원문은 `listings` 문서에 저장, [ADR-0039](./0039-listing-schema-v4-registration-form.md)). 문서 정합: [01-auth-onboarding](../api/specs/01-auth-onboarding.md)·[error-response-guide](../api/error-response-guide.md)·[domain-model](../architecture/domain-model.md)·[database-design](../database/database-design.md)·시퀀스 다이어그램 갱신.
- **미결(확인 필요)**:
  - 비즈노 호출 **타임아웃·재시도** 수치(connect/read, 재시도 횟수·백오프).
  - 검증 엔드포인트 **rate-limit** 임계값(사업자번호 추측·남용 방지 — 무상태라 캐시 완충이 없음).
  - 비즈노 회신 **상호·대표자명** 등 부가정보의 **저장 여부**(현재는 검증 응답 표시용으로만 사용 가정 — 저장 시 PII 정책 재검토).
  - 사업자등록번호 **유니크 제약**(현재 미적용) 채택 여부 — 본 ADR 범위 밖으로 이월. `business_registration_number_hash` 저장·해시 정책은 **컬럼을 채우지 않기로 정해 해소**됐다([ADR-0039](./0039-listing-schema-v4-registration-form.md)).
  - 임대인 탈퇴 시 매물 문서에 남는 사업자등록번호 원문 처리 — [ADR-0014](./0014-withdrawal-pii-anonymization.md)의 익명화 대상이 MySQL `users` 컬럼뿐이라 [ADR-0039](./0039-listing-schema-v4-registration-form.md)의 후속 작업으로 이월.

## Validation

- 정식 토큰(`ACTIVE`, `ROLE_USER`) 임대인이 정상(계속) 사업자번호로 `POST /api/v1/auth/business/verify`를 호출하면 `{ businessRegistrationNumber(마스킹), verified:true }`(200)를 받고, **서버 어디에도(Redis 마커·`business_registration_number_hash`·`status`) 저장이 남지 않는지** 확인.
- 미등록·휴업·폐업·진위 실패가 `422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`로 거부되는지, 비즈노 장애(타임아웃·5xx)가 `502 UPSTREAM_ERROR`로 매핑되는지 확인.
- 인가: 온보딩 토큰(`ROLE_ONBOARDING`)으로 호출 시 `403 AUTH_ONBOARDING_REQUIRED`, 임대인이 아닌(`userType=TENANT`) `ACTIVE` 사용자면 `403 FORBIDDEN`으로 거부되는지 확인.
- `auth` 검증 경로의 로그·응답·`toString`에 사업자번호 원문이 남지 않고 마스킹(`****567890`)되는지 확인(매물 문서에 저장하는 원문은 [ADR-0039](./0039-listing-schema-v4-registration-form.md) 소관이며 세입자 응답에서 제외된다).
- 임대인 온보딩(`POST /auth/landlord/onboarding`)이 `{ name, phoneNumber, birthDate }`만으로 완료되고(사업자번호 없이), 사업자번호 게이트나 `AUTH_BUSINESS_NUMBER_NOT_VERIFIED`가 관여하지 않으며, 온보딩 완료 시는 물론 **매물 등록 후에도** `business_registration_number_hash`가 NULL로 남는지 확인.
- 매물 등록(`POST /api/v2/listings`)이 `BusinessRegistryVerifier`를 **한 번도 호출하지 않고**(비즈노 어댑터 호출 0건), 사업자등록번호 형식 검증만으로 `status=PENDING` 매물이 저장되는지 확인 — 비즈노를 강제로 장애 상태로 두어도 등록이 성공해야 한다.
- **재검토 시점**: 비즈노 장애·rate-limit이 검증 성공률을 떨어뜨리면 결과 캐시(대안 C) 또는 다중 검증 제공자를 재검토한다. 관리자 승인 심사의 수동 확인이 병목이 되면 승인 API에서 이 검증을 호출하는 안을 재검토한다.
