# Error Response Guide

> Kohere 백엔드의 표준 에러 응답과 예외 처리 전략의 **정본**이다. 모든 에러는 이 형식을 따른다.
> 관련 문서: [api-design-guide](./api-design-guide.md) · [code-style](../convention/code-style.md)

## 목적

에러 응답의 **형식·코드·HTTP status**를 한 곳에서 표준화해, 클라이언트가 어떤 API에서든 같은 방식으로 실패를 처리하게 한다. 예외는 컨트롤러가 아니라 **전역 핸들러(`@RestControllerAdvice`)** 에서 일관 변환한다([code-style](../convention/code-style.md) §5).

## 1. 표준 에러 응답 스키마

[api-design-guide](./api-design-guide.md) §3의 공통 래퍼를 그대로 쓴다. 실패 시 `success=false`, `data=null`, `error`에 상세를 담는다.

```jsonc
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_INPUT",          // 기계가 분기하는 식별자 (UPPER_SNAKE_CASE)
    "message": "입력값이 올바르지 않습니다.", // 사람이 읽는 설명 (로그/디버깅용, UI 노출은 클라 재량)
    "errors": [                          // (선택) 검증 실패 시 필드별 상세
      { "field": "email", "reason": "형식이 올바르지 않습니다." },
      { "field": "budget", "reason": "0 이상이어야 합니다." }
    ],
    "details": { }                       // (선택) 코드별 부가 데이터. 값이 없으면 키 자체가 없다
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `error.code` | string | 필수 | 에러 식별 코드. 클라이언트 분기의 기준(메시지로 분기 금지) |
| `error.message` | string | 필수 | 사람이 읽는 설명. 민감정보·스택트레이스 노출 금지 |
| `error.details` | object | 선택 | **그 코드의 스펙에 명시된 경우에만** 실리는 부가 데이터. 값이 없으면 `null`이 아니라 **키 자체가 없다**. `errors[]`가 입력 검증 전용인 것과 달리 그 틀에 들어가지 않는 값을 담는다(예: 웹 로그인 실패의 누적 실패 횟수 — [01-auth-onboarding §1-4](./specs/01-auth-onboarding.md)). 클라이언트 분기는 여전히 `code`로 하며 `details`는 **표시용**이다 |
| `error.errors[]` | array | 선택 | 입력 검증 실패 시 `field`/`reason` 목록. **`field`는 클라이언트가 보낸 요청 필드·쿼리 파라미터·경로 변수 이름**이라 그대로 입력 폼에 매핑할 수 있다. 여러 필드가 얽혔거나 요청과 무관한 상태 오류면 빈 배열이다 |

- `message`는 사용자에게 그대로 노출될 수 있으니 **내부 구현·민감정보를 담지 않는다.** `message`는 **서버가 `Accept-Language`로 번역**해 내려간다 — `ErrorCode` 코드를 키로 하는 리소스 번들(`messages[_<lang>].properties`)에서 해소하고, 미지원 언어·키 부재는 영어로 폴백한다([ADR-0030](../adr/0030-error-message-i18n-resource-bundle.md)). 클라이언트 분기는 언어 무관 `code`로 하며(메시지로 분기 금지), 추가 다국어 처리도 `code`로 매핑할 수 있다. (참고: 진단 표시 콘텐츠는 사용자 표시 언어(`users.lang`) 기반 번역 — [ADR-0029](../adr/0029-diagnosis-i18n-strategy.md) 개정(#141). 언어 결정 출처 단일화는 후속 과제.)

## 2. 예외 분류

| 분류 | 성격 | 대표 status | 예 |
| --- | --- | --- | --- |
| 입력 검증 | 요청 형식/제약 위반 | 400 | 필수값 누락, 형식 오류, enum 불일치 |
| 인증(Authentication) | 누구인지 모름 | 401 | 토큰 없음/만료/위조, 이메일·비밀번호 불일치 |
| 인가(Authorization) | 권한 없음 | 403 | 남의 리소스 수정, 차단 사용자 |
| 리소스 없음 | 대상 부재 | 404 | 존재하지 않는 매물/게시글 |
| 충돌/상태 | 비즈니스 규칙 위반 | 409 / 422 / 423 | 중복 가입, 이미 신청한 예약(`BOOKING_ALREADY_EXISTS`), 잠긴 계정(`AUTH_ACCOUNT_LOCKED`) |
| 레이트리밋 | 과다 호출 | 429 | 신고/메시지 도배(예약 신고 도배 방지 포함 — 후속) |
| 시스템 | 서버/외부 연동 실패 | 500 / 502 / 503 | DB 오류, 외부 API 연동 실패·타임아웃 |

- **도메인/비즈니스 예외**는 의미가 드러나는 커스텀 예외로 던지고(`~Exception`), 전역 핸들러가 status·code로 변환한다.

## 3. HTTP Status 매핑

| status | 사용 시점 | 대표 code |
| --- | --- | --- |
| 400 Bad Request | 입력 검증 실패, 파라미터 오류 | `INVALID_INPUT` |
| 401 Unauthorized | 인증 실패(토큰 없음/만료/위조) | `UNAUTHENTICATED`, `TOKEN_EXPIRED` |
| 403 Forbidden | 인증은 됐으나 권한 없음 | `FORBIDDEN` |
| 404 Not Found | 리소스 없음, 미정의 경로 | `*_NOT_FOUND`, `RESOURCE_NOT_FOUND` |
| 405 Method Not Allowed | 허용되지 않은 메서드 | `METHOD_NOT_ALLOWED` |
| 409 Conflict | 상태 충돌·중복 | `*_ALREADY_EXISTS`, `DUPLICATE_*`, `RESOURCE_CONFLICT`(문서화된 UNIQUE 제약의 중복 위반 — §4 주), `LISTING_STATE_CHANGED`(읽은 뒤 저장하기까지 대상 상태가 바뀜 — §4 listing) |
| 413 Payload Too Large | 업로드 크기 초과 | `PAYLOAD_TOO_LARGE`, `LISTING_IMAGE_TOO_LARGE` |
| 415 Unsupported Media Type | 지원하지 않는 파일 형식 | `LISTING_IMAGE_UNSUPPORTED_TYPE` |
| 422 Unprocessable Entity | 형식은 맞으나 비즈니스 규칙 위반 | 도메인별 코드 |
| 423 Locked | 계정이 잠겨 자격증명이 맞아도 처리 불가 | `AUTH_ACCOUNT_LOCKED` |
| 429 Too Many Requests | 레이트리밋 초과 | `TOO_MANY_REQUESTS` |
| 500 Internal Server Error | 처리되지 않은 서버 오류 | `INTERNAL_ERROR` |
| 502/503 | 외부 연동 실패/일시 불가 | `UPSTREAM_ERROR`, `SERVICE_UNAVAILABLE` |

> 400과 422: **요청 자체가 깨졌으면 400**, 요청은 정상이나 **도메인 규칙상 처리 불가**면 422를 쓴다. 팀 내 혼선을 줄이려 본 프로젝트는 비즈니스 규칙 위반에 **409(충돌형)** 또는 **422(그 외)** 를 사용한다.
>
> 409와 422를 가르는 것은 **부딪힌 상대가 있는가**다. 409는 거의 동시에 도착한 다른 요청·다른 주체와 충돌했다는 뜻이고(중복 가입, 이미 신청한 예약, 읽은 뒤 저장하기까지 관리자 심사가 끼어든 매물 수정), 422는 상대 없이 **대상의 선행조건이 아직 갖춰지지 않았다**는 뜻이다(휴대폰 미인증, 약관 미동의, 심사 중이라 손댈 수 없는 매물). 그래서 422는 요청을 그대로 다시 보내도 조건이 바뀌기 전까지 계속 같은 응답이고, 409는 **다시 읽어 재시도하는 것이 유효한 복구**인 경우가 많다.

> 423과 403: 잠긴 계정은 **권한이 없는 것이 아니라 대상(계정) 자체가 잠긴 상태**라 의미가 정확한 `423 Locked`를 쓴다 — 클라이언트 분기는 어차피 `error.code`로 하므로 인가 실패와 같은 403으로 뭉뚱그릴 이유가 없다(#229).

> **끝난 버전 경로의 404** — deprecated된 `/api/v1` 매물 조회(`GET /api/v1/listings/{listingId}` · `POST`·`DELETE /api/v1/listings/{listingId}/favorite`)는 **대상의 존재 여부와 무관하게** `LISTING_NOT_FOUND`(404)다. 리소스를 못 찾아서가 아니라 그 경로가 매물 데이터를 더는 제공하지 않기 때문이며, 같은 이유로 목록 계열(`GET /api/v1/listings`·`/map`·`/search`, `/api/v1/users/me/favorites`·`/recent-listings`)은 404가 아니라 **빈 페이지(200)** 다. 정본은 `/api/v2/listings*`이며 버전 정책은 [api-design-guide §2-1](./api-design-guide.md)·[ADR-0040](../adr/0040-listing-query-api-v2-and-v1-sunset.md)이다.

## 4. 에러 코드 카탈로그

코드는 **`DOMAIN_REASON`** 형태의 UPPER_SNAKE_CASE다. 공통 코드는 아래에, 도메인 코드는 각 [API 스펙](./specs/)과 함께 등록하고 이 표에 누적한다.

### 공통 (common 모듈)

| code | status | 의미 |
| --- | --- | --- |
| `INVALID_INPUT` | 400 | 입력 검증 실패 — 필드를 특정할 수 있으면 `errors[]`에 실린다. Bean Validation 위반과 서버 검증(`InvalidInputException`)이 같은 모양으로 내려간다 |
| `MALFORMED_REQUEST` | 400 | JSON 파싱 불가/타입 불일치 |
| `UNAUTHENTICATED` | 401 | 인증 필요 또는 인증 실패 |
| `TOKEN_EXPIRED` | 401 | 액세스 토큰 만료(재발급 유도) |
| `FORBIDDEN` | 403 | 권한 없음 |
| `RESOURCE_NOT_FOUND` | 404 | 일반 리소스 없음 |
| `METHOD_NOT_ALLOWED` | 405 | 미허용 메서드 |
| `RESOURCE_CONFLICT` | 409 | **아래 세 UNIQUE 제약의 중복 위반에서만** 나온다 — `uq_users_phone_number`(V23) · `uq_local_accounts_email` · `uq_local_accounts_user_id`(V22). 거의 동시에 도착한 다른 요청과 충돌했다는 뜻이고 **재시도가 유효한 복구**다. 도메인이 미리 판정할 수 있는 충돌은 각자의 코드를 쓴다(`BOOKING_ALREADY_EXISTS` 등). 그 밖의 제약 위반(NOT NULL·길이 초과 등)은 **이 코드가 아니라 `INTERNAL_ERROR`(500)** 그대로다(아래 주) |
| `PAYLOAD_TOO_LARGE` | 413 | 요청 총량이 서블릿 상한을 넘음. multipart 해석이 핸들러 탐색보다 앞서 일어나 어느 엔드포인트인지 알 수 없으므로 도메인 코드가 아니라 공통 코드다 |
| `TOO_MANY_REQUESTS` | 429 | 호출 한도 초과 |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류 |
| `UPSTREAM_ERROR` | 502 | 외부 연동 실패 |

> **`RESOURCE_CONFLICT`는 전역 핸들러가 `DataIntegrityViolationException`을 번역한 결과다(#229)** — 종전에는 이 예외가 `handleUnexpected`까지 흘러 **500 `INTERNAL_ERROR`** 가 됐는데, 그 status는 어느 스펙에도 없고 "다시 보내면 된다"는 신호도 주지 못한다. 번역이 **전역**인 이유는 위반이 드러나는 시점이 둘이기 때문이다 — IDENTITY INSERT는 `save` 호출에서 **즉시**(서비스 메서드 안), 기존 행 UPDATE는 플러시가 밀려 **커밋 시점**(`@Transactional` 프록시가 반환한 뒤)에 터진다. 후자는 서비스 안의 어떤 `try/catch`로도 잡히지 않으므로, 둘을 모두 덮는 자리는 트랜잭션 바깥의 전역 핸들러뿐이다.
>
> **번역은 무조건이 아니라 화이트리스트다.** `DataIntegrityViolationException`은 UNIQUE 중복만이 아니라 **NOT NULL 위반·길이 초과·잘못된 FK**까지 같은 타입으로 실어 오는데, 그것들은 재시도해도 절대 성공하지 않는 **서버 버그**다. 통째로 409로 낮추면 클라이언트에게 "충돌이니 다시 보내라"고 거짓말을 하고, 로그 레벨까지 WARN으로 떨어져 **알림 임계값 아래로 사라진다.** 그래서 핸들러는 원인 사슬의 Hibernate `ConstraintViolationException`에서 **제약 이름**을 읽어 위 세 개(`uq_users_phone_number`·`uq_local_accounts_email`·`uq_local_accounts_user_id`)와 대조하고, **일치할 때만** 409로 번역한다. 나머지는 종전 그대로 `handleUnexpected` → **ERROR 로그 + 500**이다. (판정 근거: MySQL은 UNIQUE 중복(오류 1062)에서만 `for key '<인덱스>'`를 메시지에 실어 Hibernate가 제약 이름을 뽑아내고, NOT NULL·길이 초과 메시지에는 그 조각이 없어 이름이 `null`이다. SQLState는 쓰지 않는다 — MySQL은 NOT NULL 위반에도 `23000`을 붙여 **정확히 걸러내려던 것을 다시 끌어들이기** 때문이다.)
>
> 코드가 도메인 무관한 공통 코드인 것은 위치 때문이다 — 여기서 `AUTH_*`로 번역하면 매물·커뮤니티의 제약 위반까지 인증 에러가 된다. 뜻을 좁혀 알려야 하는 충돌은 **미리 판정할 수 있는 충돌**이고 그건 도메인이 자기 자리에서 이미 흡수한다(`BookingRepositoryImpl` → `BOOKING_ALREADY_EXISTS`, `UserBlockServiceImpl` → 멱등 무시). 그렇게 지역에서 잡힌 예외는 전역 핸들러까지 오지 않으므로 기존 코드의 정밀도는 그대로다. **이 코드가 나올 수 있는 엔드포인트는 두 개뿐이다** — 임대인 웹 회원가입([§1-3](./specs/01-auth-onboarding.md))과 앱 임대인 온보딩([§5-2](./specs/01-auth-onboarding.md)). 다른 도메인의 스펙 에러 표에 이 코드가 없는 것은 누락이 아니라 **도달하지 않기 때문**이다.
>
> **게스트(비회원) 경로에서 달라지는 인증·인가 코드(#181)** — 퀴즈(`/api/v1/quizzes/**`)·생활 팁(`/api/v1/life-tips/**`)·**v2 진단(`/api/v2/diagnoses/**`)** 은 `permitAll`이라 **토큰 없이 호출할 수 있다**(인증 부재를 401로 막지 않는다 — 개별 엔드포인트가 게스트에게 무엇을 반환하는지는 [02-diagnosis-recommendation](./specs/02-diagnosis-recommendation.md) 게스트 접근 절을 따른다). **v1 진단(`/api/v1/diagnoses/**`)은 회원 전용으로 유지**되므로 이 표의 대상이 아니다 — 신규 `permitAll` 매처는 `/api/v2/diagnoses/**`만 대상이고 v1은 `anyRequest().authenticated()`에 남아 토큰이 필수다. 아래 세 경로에 한해 위 코드의 도달 가능성이 갈리며, **그 외 엔드포인트의 계약은 그대로다.**
>
> | code | status | 게스트 경로에서 | 근거 |
> | --- | --- | --- | --- |
> | `UNAUTHENTICATED` | 401 | **도달 불가** — 토큰 미전송·위조·형식 오류 토큰은 401이 아니라 **게스트로 처리**돼 2xx가 된다. 단 **v1 진단은 이 표 밖이라 종전대로 401**이다 | `permitAll`이라 `AuthenticationException`이 발생하지 않아 `RestAuthenticationEntryPoint`가 실행되지 않는다(게스트용 ROLE을 주입하는 대신 해당 매처만 `permitAll`로 연 결과다 — ROLE 주입은 미지정 엔드포인트까지 함께 열고 401을 403으로 바꿔버린다). v1 진단은 `permitAll`이 아니므로 이 면제가 적용되지 않는다 |
> | `TOKEN_EXPIRED` | 401 | **유지** — 만료된 access token을 보내면 게스트로 강등하지 않고 401이다 | 토큰을 **보냈는데** 만료된 것은 게스트가 아니라 재발급이 필요한 회원이다. 강등하면 §7의 재발급 플로우가 조용히 침묵한다 |
> | `AUTH_ONBOARDING_REQUIRED` | 403 | **도달 불가** — 온보딩 미완료(`ROLE_ONBOARDING`) 토큰으로 불러도 2xx다 | 퀴즈·생활 팁은 `hasRole("USER")` 매처가 `permitAll`로 바뀌어 `RestAccessDeniedHandler`가 실행되지 않으며, 인가 범위가 넓어지는 것을 **의도로 수용**한다(로그인 없이도 볼 수 있는 콘텐츠를 온보딩 중인 사용자에게만 막을 이유가 없다). 진단은 **원래 전용 매처가 없어** `anyRequest().authenticated()`로 떨어졌으므로 이 코드가 #181 이전에도 나오지 않았다 — v2 진단은 `permitAll`로 열려도, v1 진단은 매처를 추가하지 않아 그 자리에 그대로 남아도 인가 범위 변화가 없다 |
> | `FORBIDDEN` | 403 | **퀴즈·생활 팁에는 해당 없음** — 두 도메인에서 이 코드가 완전히 사라진다. 게스트·세입자·**임대인**·온보딩 미완료가 모두 2xx다(임대인은 종전 403에서 바뀌었다) | 세입자 전용 게이트(`assertTenant` → `TenantOnlyException`)를 **제거했다**(#181). `permitAll`로 게스트에게 열린 콘텐츠를 로그인한 임대인만 403으로 막는 것은 앞뒤가 맞지 않고 실효도 없다 — 임대인이 로그아웃하면 그대로 볼 수 있기 때문이다. **`booking`의 소유권·역할 403과 아래 진단 소유권 403은 이 표 밖이라 그대로다** |
>
> 진단 소유권 위반의 403 `FORBIDDEN`(`DiagnosisAccessDeniedException`)도 그대로이며, 게스트에도 같은 코드로 적용된다 — 소유권 검사는 **신원 종류가 같고 값이 같을 때만** 통과하고 한쪽이 null이면 거절하므로, 게스트가 회원 진단을·회원이 게스트 진단을 열면 403이다. 부재 진단은 종전대로 404 `DIAGNOSIS_NOT_FOUND`다.

### 도메인 코드 prefix 규약

| 모듈 | prefix 예 |
| --- | --- |
| auth/user | `AUTH_*`, `USER_*` (`AUTH_INVALID_SOCIAL_TOKEN`, `USER_NOT_FOUND`) |
| diagnosis | `DIAGNOSIS_*` |
| listing | `LISTING_*` |
| booking/chat | `BOOKING_*`, `CHAT_*` |
| community | `POST_*`, `COMMENT_*` |
| gamification | `QUIZ_*` |
| report | `REPORT_*` |

각 API 스펙 문서는 자신이 쓰는 도메인 코드를 표로 정의하고, 이 카탈로그와 충돌하지 않게 한다.

#### auth 도메인 코드

`AUTH_*` 전체 목록의 정본은 [01-auth-onboarding](./specs/01-auth-onboarding.md)이며, 여기에는 **임대인 웹 로그인·회원가입(#229)** 과 **웹 계정 복구(이메일 찾기·비밀번호 재설정 — #272)** 가 추가한 코드를 싣는다. 소셜 로그인·약관·온보딩 계열 코드는 그대로다.

| code | status | 의미 |
| --- | --- | --- |
| `AUTH_INVALID_CREDENTIALS` | 401 | 웹 로그인(`POST /api/v1/auth/login`) 실패. **이메일 없음과 비밀번호 불일치를 한 코드로 묶는다**(계정 존재 여부 비노출) |
| `AUTH_ACCOUNT_LOCKED` | 423 | 비밀번호 10회 연속 실패로 잠긴 계정. **비밀번호가 맞아도** 잠금이 우선한다. 해제는 비밀번호 재설정이 겸한다(아래 주) |
| `AUTH_EMAIL_ALREADY_REGISTERED` | 409 | 웹 회원가입(`POST /api/v1/auth/signup`)의 이메일을 이미 다른 사람이 웹 로그인 ID로 쓰고 있음(`local_accounts.email` 중복) |
| `AUTH_WEB_ACCOUNT_ALREADY_EXISTS` | 409 | 인증된 휴대폰 번호로 찾은 계정에 웹 자격증명이 이미 붙어 있음. 가입이 아니라 로그인으로 보낸다 |
| `AUTH_WEB_ACCOUNT_NOT_FOUND` | 404 | 이메일 찾기(`POST /api/v1/auth/email/find`)에서 인증된 휴대폰 번호·이름에 맞는 웹 계정이 없음. **이름 불일치도 같은 코드**다(아래 주) |
| `AUTH_PASSWORD_RESET_TOKEN_INVALID` | 422 | 비밀번호 재설정 링크의 토큰이 없거나, 만료됐거나, 이미 사용됨. 사전 확인(`POST /api/v1/auth/password/reset-token/verify`)과 확정(`POST /api/v1/auth/password/reset`)이 같은 코드를 쓴다 |

> **두 409는 다른 케이스다** — `AUTH_EMAIL_ALREADY_REGISTERED`는 "이 이메일은 남이 쓰고 있다"이고, `AUTH_WEB_ACCOUNT_ALREADY_EXISTS`는 "이 사람은 이미 웹 계정이 있다"이다. 앞은 이메일 중복 검사에서, 뒤는 번호 매칭 뒤 자격증명 유무 판정에서 난다. 중복 검사는 **`local_accounts.email`만 보고 `users.email`은 보지 않는다** — 앱 소셜 계정과 같은 주소로 웹 가입하는 것이 가장 흔한 정상 경로라, `users.email`까지 유일성을 걸면 본인이 본인 이메일로 가입하다 409를 맞는다. 응답에는 **마스킹된 이메일 등 기존 계정 정보를 싣지 않는다** — 번호 소유자에게 남의 로그인 ID를 흘리지 않기 위해서다.
>
> `AUTH_WEB_ACCOUNT_ALREADY_EXISTS`는 성공 응답의 `linked=true`와 **같은 조회의 다른 가지**다. 번호로 기존 계정을 찾은 것까지는 같고, 붙일 자리가 비어 있으면 연동 성공(`linked=true`), 이미 차 있으면 이 코드다. 이 지점에서 자격증명을 덮어쓰면 가입이 아니라 **로그인 ID 교체**가 되므로 오류로 끊는다.

> **계정 복구가 추가한 두 코드(#272)** — 임대인 웹의 이메일 찾기·비밀번호 재설정이 쓰는 코드다. 두 코드가 계정 존재를 다루는 방식이 정반대인 것은 **앞에 소유 증명 게이트가 있는지**가 갈리기 때문이다.
>
> `AUTH_WEB_ACCOUNT_NOT_FOUND`(404)는 **존재를 드러낸다.** 이메일 찾기는 SMS 인증 마커 뒤에서만 닿는 지점이라 호출자는 **소유를 증명한 자기 번호**로만 조회할 수 있어 열거 표면이 이미 닫혀 있다. 대신 **이름 불일치와 계정 미존재를 같은 404로 수렴**시킨다 — 나눠 주면 번호 하나로 이름을 맞혀 보는 오라클이 된다. 409 `AUTH_WEB_ACCOUNT_ALREADY_EXISTS`와 대칭 위치에 두되 뜻은 반대다("이미 있다" ↔ "없다").
>
> 반대로 **재설정 링크 발송(`POST /api/v1/auth/password/reset-link`)에는 전용 코드가 없다** — 가입되지 않은 이메일도 **같은 200**을 받고 메일만 나가지 않는다. 이 경로에는 선행 게이트가 없어 임의의 주소로 부를 수 있으므로, 응답을 가르는 순간 완전한 열거 오라클이 된다. **알려진 한계**: 발송이 동기라 가입 계정만 SMTP 왕복 시간을 쓰고 `UPSTREAM_ERROR`(502)가 날 수 있어 **응답 시간·status 분포로는 존재가 드러난다.** 받아들인 결과이며, 레이트리밋(429)은 열거 방어가 아니라 발송비·남용 방어다.
>
> `AUTH_PASSWORD_RESET_TOKEN_INVALID`(422)는 **토큰 부재·만료·이미 사용됨을 한 코드로 묶는다.** 셋을 구분해 알려주면 "그 토큰은 있었는데 이미 썼다"가 새어 나가고, 클라이언트가 할 일은 셋 다 같다 — 링크를 다시 요청한다. 사전 확인 엔드포인트가 **토큰을 소비하지 않는 것도 계약**이다: 메일 클라이언트·보안 스캐너가 링크를 미리 여는 일이 흔해, 소비하면 사용자가 클릭하기도 전에 링크가 죽는다.

> **잠금 해제는 비밀번호 재설정이 겸한다(#272)** — `AUTH_ACCOUNT_LOCKED`는 **시간이 지나도 저절로 풀리지 않는다.** 푸는 방법은 본인이 재설정 링크를 받아 새 비밀번호를 확정하는 것이고(`POST /api/v1/auth/password/reset` — 비밀번호 교체와 함께 `failed_login_attempts`를 0으로 되돌리고 `locked_at`을 비운다), 독립 잠금 해제 API는 두지 않는다. 잠긴 사람은 대개 비밀번호를 모르는 상태라 해제만 해 주면 곧바로 다시 잠기기 때문이다. 운영자가 DB의 `locked_at`을 비우는 수동 해제는 **예외 수단으로 남는다** — 재설정 경로는 토글로 켜며 지금은 `local`·`dev`에만 배포돼 있어 **prod에서는 여전히 수동 해제뿐**이다.
>
> 실패 횟수·잠금 여부를 Redis TTL이 아니라 `local_accounts` 컬럼에 두는 이유는 **해제 시점이 행에 남아 나중에 되짚을 수 있기 때문**이다 — 언제 잠겼고 언제 풀렸는지가 문의 대응·사고 조사에서 근거가 되는 반면, TTL 키는 만료되는 순간 흔적 없이 사라져 **"그 계정이 잠겼던 적이 있는가"조차 사후에 답할 수 없다.** 「TTL 만료로 저절로 풀리면 안 된다」도 그대로 유효하다 — 해제는 본인이 비밀번호를 바꾼 결과여야 하고, 기다리면 풀리는 잠금은 자동 대입을 시간당 상한으로 늦출 뿐 끊지 못한다. 다만 **`locked_at`만 비우면 그것으로 완전한 해제**다 — 잠기지 않았는데 카운터가 이미 상한 이상인 계정은 다음 실패를 1부터 다시 센다.
>
> 뒤집으면 **남의 이메일로 10회 틀려 그 계정을 잠글 수 있다**(의도적 잠금 DoS). 잠금을 채택하는 이상 피할 수 없는 고전적 부작용이며, 로그인 시도 레이트리밋(IP 60회/시간·이메일 20회/시간)으로 완화할 뿐 완전히 막지 못한다 — **수용한 리스크**다. 재설정으로 피해자가 스스로 풀 수 있게 됐지만(#272), 메일 왕복을 강제하는 방해 자체는 그대로다.
>
> **계정 열거도 수용한다** — `AUTH_INVALID_CREDENTIALS`의 `error.details`는 **등록된 계정의 비밀번호 불일치에만** 실리므로 그 유무로 가입 여부가 드러난다. 잠기기 전에 남은 시도를 알려 주는 것이 그 필드의 목적이라 받아들인 결과이며, 레이트리밋은 이메일 축이 이메일 단위라 열거를 막지 못한다(IP 축은 위조 가능). **해제 경로가 생긴 뒤에도 이 판단은 그대로다**(#272) — 잠기고 나면 메일 왕복과 재설정을 거쳐야 하므로, 잠기기 전에 알리는 사전 안내가 아끼는 시간은 사라지지 않는다.

> **웹 인증에서 의도적으로 신설하지 않은 코드** — 리뷰어가 누락으로 오해하지 않도록 근거를 남긴다.
> - **SMS 인증 없는 가입 제출**: 기존 `AUTH_PHONE_NOT_VERIFIED`(422)를 재사용한다. 임대인 온보딩의 게이트와 뜻이 같고, 이때는 **계정 생성도 연동도 하지 않는다**.
> - **가입용 인증번호 불일치·만료·시도 초과**: 기존 `AUTH_PHONE_VERIFICATION_FAILED`(422). 비로그인 경로라고 코드를 나누지 않는다.
> - **필수 약관 미동의**: 기존 `AUTH_REQUIRED_AGREEMENT_MISSING`(422). 웹 가입도 앱과 같은 약관 3필드를 받는다.
> - **웹 로그인 시도 남용**: 공통 `TOO_MANY_REQUESTS`(429) 하나로 낸다 — 같은 IP 60회/시간·같은 이메일 20회/시간이 모두 이 코드이며, 어느 축에 걸렸는지 구분하지 않는다. **자격증명 조회·BCrypt 대조보다 먼저** 판정하므로 이메일 존재 여부를 드러내지 않고, `permitAll` 경로에서 해시 비용을 강제하는 CPU 증폭도 이 지점에서 끊긴다.
> - **가입용 SMS 남용**: 공통 `TOO_MANY_REQUESTS`(429) 하나로 낸다 — 재발송 쿨다운 60초·번호 5회/시간·IP 20회/시간이 모두 이 코드다. 어느 한도에 걸렸는지 구분해 알려주면 한도를 역산할 수 있다. 발송 실패는 공통 `UPSTREAM_ERROR`(502)이며 챌린지를 저장하지 않는다.
> - **비밀번호 정책 위반**: `INVALID_INPUT`(400) + `errors[]`(`field=password`). 요청 DTO의 Bean Validation(`@Pattern`)으로 걸어 기존 흐름에 태운다.
> - **번호가 매칭되지 않아 계정이 갈라지는 경우**: **오류가 아니다.** 세입자·온보딩 미완료 계정은 `phone_number`가 NULL이라 구조적으로 매칭 후보에서 빠지고, 가입은 `linked=false`로 정상 성공한다. 여기에 더해 **번호 정규화 백필을 하지 않으므로**(하이픈 포함으로 저장된 기존 임대인 번호) 매칭에서 누락될 수 있는데, 이때도 오류 대신 별개 계정이 생긴다. 세입자→임대인 전환은 지원하지 않으며, 양쪽에 완주한 계정이 각각 생기면 **자동 병합 트리거가 없어 운영 수동 처리** 대상이다(알려진 제약).
> - **같은 번호의 동시 가입·온보딩 경합**: 공통 `RESOURCE_CONFLICT`(409)를 쓰고 `AUTH_*` 전용 코드를 두지 않는다 — 번역 지점이 전역 핸들러(모듈 밖)라 인증 도메인 코드를 낼 자리가 아니고, 클라이언트가 할 일은 어느 제약이든 **재시도** 하나다. 재시도는 상대가 만든 계정을 발견해 연동(§1-3)·병합(§5-2)으로 수렴한다. 이 두 흐름 밖에서 이 코드를 보면 그건 경합이 아니라 **화이트리스트 관리 실수**다(위 주).
> - **`reissue`·`logout`의 refresh 부재**: 쿠키·본문 어디에도 없으면 공통 `INVALID_INPUT`(400) + `errors[].field="refreshToken"`이고, 깨진 JSON 본문은 종전대로 `MALFORMED_REQUEST`(400)다. 토큰 자체가 만료·위조·재사용이면 기존 `AUTH_INVALID_REFRESH_TOKEN`(401)이다.

#### diagnosis 도메인 코드

| code | status | 의미 |
| --- | --- | --- |
| `DIAGNOSIS_NOT_FOUND` | 404 | 요청한 진단이 존재하지 않음 |
| `DIAGNOSIS_SESSION_NOT_FOUND` | 400 | 진행 중인 v2 흐름 세션 없이 `POST /api/v2/diagnoses/next`가 옴(앱 재시작·터미널 이후 재전송·만료) — 클라이언트가 `POST /api/v2/diagnoses/start`로 복구한다 |

> `DIAGNOSIS_SESSION_NOT_FOUND`는 접미사가 `_NOT_FOUND`지만 **400**이라 §3의 `*_NOT_FOUND`→404 관례에 대한 의도적 예외다. 없는 것은 클라이언트가 지목한 리소스가 아니라 **서버가 들고 있던 진행 세션**이고, 뜻도 "그 진단이 없다"가 아니라 "지금 이 요청은 보낼 수 없다 — `POST /api/v2/diagnoses/start`로 다시 시작하라"는 흐름 지시이기 때문이다. 상세는 [02-diagnosis-recommendation](./specs/02-diagnosis-recommendation.md) v2 절·[ADR-0036](../adr/0036-diagnosis-v2-server-driven-flow.md).

> **게스트가 가장 자주 만나는 코드다(#181)** — 게스트 진단 세션은 토큰이 아니라 **클라이언트가 에코하는 세션 키**로 이어진다: `POST /api/v2/diagnoses/start`가 게스트에게 세션 키를 발급하고, 클라이언트는 이후 `POST /api/v2/diagnoses/next`·추천 조회에 **`X-Guest-Session-Id` 헤더**로 되돌려보낸다. 따라서 헤더를 **빠뜨렸거나**, 값이 **다르거나**, 앱이 키를 **잃어버린** 요청은 서버가 세션을 찾지 못해 그대로 이 코드(400)가 된다 — 회원의 "앱 재시작·터미널 이후 재전송"과 원인만 다를 뿐 **코드도 복구법도 같다**(`POST /start`로 다시 시작). 값이 다르면 남의 세션에 닿는 것이 아니라 "세션 없음"이며(게스트 세션 키는 요청자마다 다르다), 퀴즈·생활 팁은 저장이 없어 세션 키를 요구하지 않으므로 이 코드가 나오지 않는다.

#### listing 도메인 코드

| code | status | 의미 |
| --- | --- | --- |
| `LISTING_NOT_FOUND` | 404 | 존재하지 않거나 비공개/삭제 또는 ACTIVE 방 상품이 없는 매물. **임대인 전용 조회·수정에서는 남의 매물도 이 코드다** — 소유권 실패를 403과 구분하지 않는다(아래 주). deprecated된 `/api/v1` 상세·찜 토글은 대상과 무관하게 항상 이 코드다(§3) |
| `LISTING_INVALID_SORT_PARAM` | 400 | `sort=DISTANCE`인데 bbox 네 좌표가 누락됨 |
| `LISTING_INVALID_BBOX` | 400 | bbox 좌표 불완전/범위 위반/모순(`swLat>=neLat` 등) |
| `LISTING_AREA_TOO_LARGE` | 400 | 지도 마커 결과가 너무 많아 한 번에 표시하기 어려움 |
| `LISTING_UNKNOWN_CATALOG_CODE` | 400 | 요청에 실린 코드 값이 `listingCatalog`의 `(category, code)`에 없음 |
| `LISTING_REQUIRED_AGREEMENT_MISSING` | 422 | 매물 등록·수정에 필요한 이용약관 동의 2종 중 하나 이상이 누락되거나 `false` |
| `LISTING_IMAGE_REQUIRED` | 400 | 업로드에 파일이 없거나 비었음. 등록·수정에서는 사진 키 개수가 규칙을 벗어남 — 지점 1~5개, 방마다 2~5개(수정은 **병합 후 최종 배열** 기준) |
| `LISTING_IMAGE_KEY_NOT_FOUND` | 400 | 등록·수정 요청의 사진 키가 남의 것이거나, 존재하지 않거나, 7일이 지나 만료됨. 수정에서는 **그 자리에 붙어 있지 않은 확정 키**도 이 코드다 |
| `LISTING_IMAGE_TOO_LARGE` | 413 | 사진 한 장이 10MB를 넘음 |
| `LISTING_IMAGE_UNSUPPORTED_TYPE` | 415 | 사진 형식이 `image/jpeg` · `image/png` · `image/webp` · `image/heic` 중 하나가 아님 |
| `LISTING_NOT_EDITABLE` | 422 | 심사 중(`PENDING`·`UPDATE_PENDING`)이라 임대인이 매물을 수정할 수 없음 |
| `LISTING_STATE_CHANGED` | 409 | 매물을 읽은 뒤 저장하기까지 그 상태가 바뀜 — 다시 읽어 재시도하면 성공할 수 있다 |

> 조회 계열 네 코드의 상세·발생 지점은 [03-listings-favorites](./specs/03-listings-favorites.md)가 정본이며 **경로는 `/api/v2/listings*`다** — deprecated된 `/api/v1` 목록·지도는 매물을 조회하지 않아 정상 호출이 빈 페이지(200)로 끝나고, 매물 데이터를 쓰지 않는 `GET /api/v1/listings/places`만 v1에 남아 종전 코드를 그대로 낸다. 가운데 여섯 코드는 사진 업로드(`POST /api/v2/listings/images`)와 매물 등록(`POST /api/v2/listings`)이, 마지막 두 코드는 임대인 매물 수정(`PUT /api/v2/listings/{listingId}`)이 추가한다.
>
> - **주소 때문에 등록이 거절되는 경로는 없다.** 도로명 주소에서 행정구역을 뽑지 못하면 `address.city`·`district`를 `ETC`로 저장하고 관리자 승인 심사가 확정한다 — 지원 지역 목록은 영업 범위 정책이지 저장 계약이 아니다([ADR-0046](../adr/0046-administrative-region-as-catalog-data.md)). 주소 검색 자체도 전용 코드를 두지 않는다 — 키워드 검증은 `INVALID_INPUT`, 외부 연동 실패는 `UPSTREAM_ERROR`다. **인근 역 검색(`GET /api/v1/listings/stations`·`/stations/nearby`)도 같다**([ADR-0044](../adr/0044-nearby-station-search-with-kakao-local.md)) — 키워드·좌표 검증은 `INVALID_INPUT`, 카카오 연동 실패는 `UPSTREAM_ERROR`다.
> - `LISTING_UNKNOWN_CATALOG_CODE`는 사용자가 오타를 낸 것이 아니라 **앱이 들고 있는 코드표가 서버 카탈로그와 어긋났다**는 신호라 `INVALID_INPUT`과 분리한다 — 사용자는 앱이 준 선택지에서 골랐을 뿐이라 입력 교정을 요구할 자리가 아니다(§7).
> - **사진 4종을 별도 코드로 두는 이유.** 업로드(`POST /api/v2/listings/images`)의 셋(`REQUIRED`·`TOO_LARGE`·`UNSUPPORTED_TYPE`)은 위반 대상이 JSON 필드가 아니라 **파일 part**라 `INVALID_INPUT`의 `errors[]` 구조에 담기지 않고, 사용자에게 요구할 행동도 각각 다르다 — 파일을 고르거나, 줄이거나, 형식을 바꾸는 것이다. `LISTING_IMAGE_KEY_NOT_FOUND`는 등록과 수정에서만 나며 **남의 키·없는 키·만료된 키를 한 코드로 묶는다** — 구분해 알려주면 남의 키가 있는지 없는지가 새어 나간다. 클라이언트가 할 일은 셋 다 같다(사진을 다시 올린다). 수정은 여기에 **자리가 어긋난 확정 키**를 더한다 — 이미 저장된 `listings/…` 키를 그대로 다시 보낼 수 있지만 **원래 붙어 있던 자리(대표사진 또는 그 방)에서만** 통과하며, 방 사진을 대표사진 칸에 넣거나 다른 방으로 옮기면 같은 코드로 거절된다.
> - **사진 저장소 실패는 공통 `UPSTREAM_ERROR`(502)** 다 — 외부 연동 실패라 매물 전용 코드를 두지 않는다. 등록 중 복사가 실패하면 매물은 저장되지 않고 이미 복사한 사진은 서버가 지우지만, **임시 사진은 그대로 남아** 사용자가 다시 제출할 수 있다([ADR-0041](../adr/0041-listing-image-upload-to-s3.md)). 수정도 같으며, 실패했을 때 되돌리는 것은 **이번에 복사한 사진뿐**이다 — 교체된 옛 사진은 저장이 성공한 뒤에 지우므로 어떤 실패 경로에서도 이미 저장돼 있던 사진이 사라지지 않는다.
> - **`LISTING_NOT_EDITABLE`이 422인 이유.** 요청은 멀쩡한데 **매물이 아직 손댈 수 있는 상태가 아니다** — 심사가 끝나 `PUBLISHED`나 `REJECTED`가 되면 같은 요청이 그대로 성공한다. 이 저장소는 이런 「나중에는 성공하는 선행조건 위반」을 예외 없이 422로 쓴다(`AUTH_PHONE_NOT_VERIFIED`·`AUTH_TERMS_AGREEMENT_REQUIRED`·`LISTING_REQUIRED_AGREEMENT_MISSING`). 403이 아닌 것은 **권한이 아니라 시점의 문제**이고, 임대인은 자기 매물에 대한 권한을 잃은 적이 없기 때문이다(§3).
> - **`LISTING_STATE_CHANGED`가 409인 이유.** 임대인이 수정 화면을 연 뒤 저장하기까지의 사이에 **관리자 심사가 끼어들어** 매물 상태가 달라졌다는 뜻이라, 부딪힌 상대가 있는 충돌이고 복구 행동은 **다시 읽어 재시도** 하나다(§3). 422가 아닌 것은 이 실패가 조건을 갖추길 기다려야 하는 상태가 아니라 **한 번 더 시도하면 그대로 통과할 수 있는** 실패이기 때문이다. 다만 **공통 `RESOURCE_CONFLICT`를 재사용하지는 않는다** — 그 코드는 전역 핸들러가 문서화된 **세 UNIQUE 제약**의 중복 위반만 번역한 결과라 매물 경로에 도달하지 않고, 「도메인이 미리 판정할 수 있는 충돌은 각자의 코드를 쓴다」는 위 주의 규칙에도 어긋난다.

> **매물 등록에서 의도적으로 신설하지 않은 코드** — 리뷰어가 누락으로 오해하지 않도록 근거를 남긴다.
> - **임대인 아님(`userType≠LANDLORD`)**: 공통 `FORBIDDEN`(403)을 쓴다. auth의 임대인 전용 게이트(`LandlordOnlyException` — 사업자등록번호 검증)·booking의 세입자 전용 게이트와 같은 처리이며, `LISTING_LANDLORD_ONLY` 같은 코드를 두지 않는다.
> - **온보딩 미완료 토큰**: SecurityConfig가 `POST /api/v2/listings`에 `hasRole("USER")` 매처를 명시하므로 `ROLE_ONBOARDING` 토큰은 컨트롤러에 닿기 전에 `AUTH_ONBOARDING_REQUIRED`(403)로 막힌다(매처를 두지 않고 `anyRequest().authenticated()`에 맡기면 온보딩 스코프 토큰이 그대로 통과한다). 토큰이 없거나 만료면 종전대로 `UNAUTHENTICATED`·`TOKEN_EXPIRED`(401)다.
> - **필수값 누락·형식 위반**: `INVALID_INPUT`(400) + `errors[]`로 충분하다. 지점 운영층 `1~2`·이용 연령대 `20~35`의 형식 위반, `min ≤ max`, `usedFloorMax ≤ totalFloors`, `roomOffers` 최소 1개가 모두 여기에 해당한다. 문자열 길이 제한은 두지 않으므로 그에 대한 코드도 없다.
> - **사업자등록번호**: 등록 API는 **형식만 보고 저장**하며 진위는 관리자 승인 심사에서 사람이 확인한다. `POST /api/v1/auth/business/verify`를 호출하지 않으므로 `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED`(422)는 이 경로에서 나오지 않고, 형식 위반은 `INVALID_INPUT`이다.

> **임대인 매물 수정·전용 조회에서 의도적으로 신설하지 않은 코드** — 리뷰어가 누락으로 오해하지 않도록 근거를 남긴다. 대상은 `PUT /api/v2/listings/{listingId}`와 `GET /api/v2/users/me/listings`·`/api/v2/users/me/listings/{listingId}`이며, 등록에서 정한 위 근거는 그대로 유효하다(임대인 아님은 `FORBIDDEN`, 온보딩 미완료는 `AUTH_ONBOARDING_REQUIRED`, 필수값 누락·형식 위반은 `INVALID_INPUT`).
> - **남의 매물을 수정·조회**: **인가 전용 코드를 만들지 않고 `LISTING_NOT_FOUND`(404)** 를 쓴다. 소유자가 아니면 그 매물이 존재하는지조차 알려주지 않으며, `booking`(`BOOKING_NOT_FOUND`)·`chat`이 이미 같은 규약이다. 403으로 나누면 **한 API가 상태에 따라 403과 404를 오가면서 그 차이 자체가 존재를 누설한다.** 반대로 임대인이 아닌 사용자(`userType≠LANDLORD`)는 등록과 같은 공통 `FORBIDDEN`(403)이다 — 그건 리소스가 아니라 **역할**에 대한 판정이라 무엇의 존재도 드러내지 않는다.
> - **수정 요청의 필수 약관 미동의**: 기존 `LISTING_REQUIRED_AGREEMENT_MISSING`(422)을 재사용한다. 수정 요청도 등록과 같은 동의 2종을 받고 게이트도 같으므로 코드를 나눌 이유가 없다. 저장되는 동의 값(버전·시각)은 **최초 등록 것을 승계**하지만, 그건 게이트가 아니라 저장 규칙이라 에러 계약과 무관하다.
> - **서버가 소유한 값을 요청에 실은 경우**: 코드를 두지 않는다. `status`·`rejectionReason`·`favoriteCount`는 **수정 요청 DTO에 칸이 없어** 애초에 바인딩되지 않으며, 수정이 성공하면 `rejectionReason`은 서버가 무조건 지운다. 거절할 대상이 없으므로 알릴 것도 없다.
> - **`INACTIVE`로 내리거나 되살리는 방**: 오류가 아니다. 방을 내리는 것은 요청에서 빼는 것이 아니라 `status=INACTIVE`로 보내는 것이고 되살리는 것도 같은 필드다. 다만 **저장 후 `ACTIVE` 방이 하나도 남지 않는 요청**과 **이 매물의 것이 아닌 `roomOfferId`** 는 `INVALID_INPUT`(400) + `errors[]`로 충분해 전용 코드를 두지 않는다.
> - **심사 중이라 세입자에게 보이지 않는 매물**: 세입자 경로는 종전대로 `LISTING_NOT_FOUND`(404)이며 새 코드를 만들지 않는다. 세입자에게 `PENDING`·`REJECTED`·`UPDATE_PENDING`은 전부 **"지금 이 매물 페이지는 볼 수 없다" 하나**이고, 구분해 알려주면 앱이 매물 심사 도메인의 의미를 인코딩하게 되어 상태가 늘 때마다 앱 대응이 필요해진다. 승인되면 그대로 되살아난다.

#### booking 도메인 코드

| code | status | 의미 |
| --- | --- | --- |
| `BOOKING_INVALID_MOVE_IN_DATE` | 422 | `moveInDate`가 과거 |
| `BOOKING_NOT_FOUND` | 404 | 예약이 없거나 조회 권한 밖(세입자: 본인 예약 아님 / 임대인: 내 소유 매물 신청 아님), 요청자가 삭제·차단으로 숨긴 예약, 또는 삭제·차단·신고 요청자가 참여자가 아님(404로 통일) |
| `BOOKING_ALREADY_EXISTS` | 409 | 동일 세입자가 동일 방 상품에 이미 신청함 (UNIQUE `(tenant_id, room_offer_id)` 위반) |

> 예약 신고는 `booking` 모듈이 접수를 소유하므로 `BOOKING_*` prefix를 쓴다. 1:1 채팅방 신고는 `report` 모듈이 별도로 접수하며, 아래 `REPORT_*` 코드는 현재 보이는 TEXT 증거가 없는 경우에 사용한다.

> **#169에서 의도적으로 신설하지 않은 코드** — 리뷰어가 누락으로 오해하지 않도록 근거를 남긴다.
> - **예약 삭제·차단·신고의 참여자 위반**: 기존 `BOOKING_NOT_FOUND`(404)를 재사용한다. 존재를 노출하지 않는 기존 booking 규약과 통일한다.
> - **차단 상대의 예약 신청 거부**: 공통 `FORBIDDEN`(403)을 쓴다. `BLOCK_*` prefix는 신설하지 않는다 — §4의 모듈 prefix 표에 없다.
> - **자기 신고**: 예약 생성이 TENANT 전용이고 `userType`은 온보딩 확정 후 불변이라 `tenant_id != landlord_id`가 구조적으로 보장된다. 발생할 수 없는 상황이므로 코드를 두지 않는다.
> - **동일 예약 중복 신고 거부**: 코드를 두지 않는다. 동일 신고자가 동일 예약을 **여러 번 신고할 수 있다(다건 허용)** — 새 사유·지속 문제를 다시 접수해야 하기 때문이다. 예전 `(reporter_id, booking_id)` 유일성·`BOOKING_REPORT_ALREADY_EXISTS`(409)는 제거됐다. 신고 도배 방지는 **레이트리밋(`TOO_MANY_REQUESTS`, 429)** 으로 다루며 **후속·이연**이다(현재 미구현).

> `CHAT_*` 코드(`CHAT_ROOM_NOT_FOUND`·`CHAT_SELF_INQUIRY_NOT_ALLOWED` 등)는 매물 문의·1:1 채팅 기능에서 사용한다. 상세는 [채팅 API 계약](../architecture/chat/02-api-contracts.md)을 따른다.

#### report 도메인 코드

| code | status | 의미 |
| --- | --- | --- |
| `REPORT_REQUIRES_TEXT_MESSAGE` | 422 | 신고자에게 현재 보이는 TEXT 원문이 없어 신고 증거를 만들 수 없음 |

사유 누락은 공통 `INVALID_INPUT`(400), 지원하지 않는 enum 문자열은 `MALFORMED_REQUEST`(400), 방 없음·비참여자·현재 숨긴 방은 `CHAT_ROOM_NOT_FOUND`(404)를 사용한다. 같은 사용자의 같은 방 재요청은 오류가 아니라 기존 신고와 `200`을 반환한다.

## 5. 예외 계층 / 전역 핸들러

```text
RuntimeException
 └─ BusinessException (추상)         // code(ErrorCode) 보유
     ├─ AuthException
     ├─ ListingNotFoundException
     ├─ DuplicateBookingException
     └─ ...
```

- `ErrorCode`는 **enum**으로 `code(String)` + `httpStatus` + 기본 `message`를 보유한다. 새 에러는 enum 상수 추가로 등록한다.
- **새 코드는 메시지 리소스 번들 2벌에도 함께 넣는다** — `src/main/resources/messages.properties`(영어)와 `messages_ko.properties`(한국어) **양쪽**이다. 키가 없으면 `Accept-Language`와 무관하게 enum의 기본 메시지(한국어)로 폴백하므로, 빠뜨려도 빌드·테스트는 통과한 채 **영어 클라이언트에 조용히 한국어가 나간다**이제 `ErrorCodeMessageBundleTest`가 **모든 코드에 두 벌이 다 있는지 강제**하므로 빠뜨리면 빌드가 깨진다. 이 테스트를 넣으면서 그때까지 누락돼 있던 세 키(`AUTH_EMAIL_REQUIRED`·`AUTH_EMAIL_MISMATCH`·`LISTING_REQUIRED_AGREEMENT_MISSING`)를 함께 채웠다.
- 모든 비즈니스 예외는 `BusinessException(ErrorCode)`를 상속해 던진다. 컨트롤러에서 `try/catch`로 응답을 만들지 않는다.
- 전역 핸들러는 **`@RestControllerAdvice`** 하나에 모은다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    ErrorCode ec = e.getErrorCode();
    return ResponseEntity.status(ec.getHttpStatus())
        .body(ApiResponse.error(ec.getCode(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class) // Bean Validation
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
    List<FieldErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
        .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
        .toList();
    return ResponseEntity.badRequest()
        .body(ApiResponse.error("INVALID_INPUT", "입력값이 올바르지 않습니다.", details));
  }

  @ExceptionHandler(Exception.class) // 최후의 보루
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    log.error("Unhandled exception", e); // 스택트레이스는 로그에만
    return ResponseEntity.status(500)
        .body(ApiResponse.error("INTERNAL_ERROR", "일시적인 오류가 발생했습니다."));
  }
}
```

- Spring MVC 표준 예외(`HttpMessageNotReadableException`, `NoHandlerFoundException`, `HttpRequestMethodNotSupportedException` 등)도 핸들러를 두어 위 코드(`MALFORMED_REQUEST`/`RESOURCE_NOT_FOUND`/`METHOD_NOT_ALLOWED`)로 매핑한다.
- 모듈러 모놀리식이므로 공통 타입(`ApiResponse`, `ErrorCode`, `BusinessException`, 핸들러)은 **`common` 모듈(OPEN)** 에 둔다([code-style](../convention/code-style.md) §3-1).

## 6. 로깅 / 재시도 정책

| 분류 | 로그 레벨 | 재시도 |
| --- | --- | --- |
| 4xx 클라이언트 오류(검증/인증/권한/404) | `WARN` 또는 `INFO` (스택트레이스 X) | 클라이언트가 입력 교정 후 재시도 |
| 409/422 비즈니스 규칙 | `INFO`/`WARN` | 상태 변경 후에만 의미 있음 |
| 429 | `WARN` | `Retry-After` 헤더 후 재시도 |
| 5xx 서버 오류 | `ERROR` (스택트레이스 포함) | 멱등 요청에 한해 지수 백오프 |
| 외부 연동(소셜 검증 등) | `ERROR` | 타임아웃·재시도·서킷브레이커 검토 |

- **민감정보(토큰, 비자번호, 전화번호 등)는 로그에 남기지 않는다.** 식별이 필요하면 마스킹한다.
- 요청 추적용 `traceId`(또는 `X-Request-Id`)를 로그에 남기고, 5xx 응답 `message`에 동일 식별자를 포함하는 것을 검토한다.

## 7. 클라이언트 처리 가이드

- 먼저 **HTTP status**로 큰 분기(2xx/4xx/5xx), 다음 **`error.code`** 로 세부 분기한다. **`message` 문자열로 분기하지 않는다.**
- `401 TOKEN_EXPIRED` → `POST /api/v1/auth/reissue`로 토큰 재발급 후 원요청 1회 재시도. 재발급도 실패하면 로그인 화면으로. **웹은 refresh를 HttpOnly 쿠키로 들고 있어 본문 없이 호출한다**(서버가 쿠키 우선·본문 fallback으로 읽으며, 둘 다 없으면 `400 INVALID_INPUT` + `errors[].field="refreshToken"`이다). 앱은 종전대로 본문에 담는다.
- `400 INVALID_INPUT` → `errors[]`의 `field`를 입력 폼에 매핑해 표시.
- **임대인 웹 로그인**(`POST /api/v1/auth/login`)은 `401 AUTH_INVALID_CREDENTIALS`와 `423 AUTH_ACCOUNT_LOCKED`를 다르게 안내한다 — 401은 어느 쪽이 틀렸는지 밝히지 않고 "이메일 또는 비밀번호를 확인하라"로, 423은 **재시도로 풀리지 않으므로** 재시도 버튼 대신 **비밀번호 재설정 화면으로 보낸다**(재설정이 잠금 해제를 겸한다 — §4). 가입(`POST /api/v1/auth/signup`)의 `409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS`는 **로그인 화면으로**, `409 AUTH_EMAIL_ALREADY_REGISTERED`는 **다른 이메일 입력**으로 보낸다.
- **이메일 찾기**(`POST /api/v1/auth/email/find`)의 `404 AUTH_WEB_ACCOUNT_NOT_FOUND`는 **이름·휴대폰 번호 입력을 다시 확인**하게 한다 — 서버가 이름 불일치와 계정 미존재를 구분해 주지 않으므로 "가입한 적 없다"로 단정해 안내하지 않는다.
- **비밀번호 재설정**(`POST /api/v1/auth/password/reset-token/verify`·`/reset`)의 `422 AUTH_PASSWORD_RESET_TOKEN_INVALID`는 재시도 버튼이 아니라 **재설정 링크를 다시 요청**하게 한다 — 그 토큰은 이미 만료됐거나 사용됐으므로 같은 값을 다시 보내도 결과가 같다.
- 매물 등록(`POST /api/v2/listings`)의 코드들은 사용자에게 요구할 행동이 서로 다르다: `400 LISTING_UNKNOWN_CATALOG_CODE`는 입력 교정이 아니라 **코드 카탈로그 재조회(또는 앱 갱신)** 를 안내한다 — 사용자가 앱이 준 선택지에서 골랐는데도 거절됐다는 뜻이기 때문이다. 사진 관련 `400 LISTING_IMAGE_REQUIRED`는 **장수 조정**, `413 LISTING_IMAGE_TOO_LARGE`는 **더 작은 파일**, `415 LISTING_IMAGE_UNSUPPORTED_TYPE`은 **지원 형식으로 변환**을 안내한다. `400 LISTING_IMAGE_KEY_NOT_FOUND`는 임시 사진이 사라졌다는 뜻이므로 **사진을 다시 올리게** 한다.
- **매물 수정**(`PUT /api/v2/listings/{listingId}`)의 두 실패는 안내가 정반대다: `422 LISTING_NOT_EDITABLE`은 재시도 버튼이 아니라 **심사가 끝나야 수정할 수 있다**는 안내이고(다시 보내도 상태가 바뀌기 전까지 같은 응답이다), `409 LISTING_STATE_CHANGED`는 **상세를 다시 불러와 폼을 채운 뒤 제출**하게 한다 — 그 사이 심사 결과가 반영됐을 수 있으므로 화면에 남아 있던 값을 그대로 재전송하지 않는다. 사진 키 규칙은 등록과 같되 **이미 저장된 확정 키는 원래 자리(대표사진 또는 그 방)에서만 다시 보낼 수 있고**, 자리를 옮기려면 그 사진을 다시 업로드한다.
- **공개 중이던 매물의 상세·찜 해제가 갑자기 `404 LISTING_NOT_FOUND`가 될 수 있다** — 임대인이 수정을 신청했거나 관리자가 사후 반려해 매물이 심사로 돌아간 경우다. 삭제된 매물로 단정해 안내하지 말고 **목록을 다시 불러오는 신호**로 다룬다. 찜 기록은 서버에 그대로 남아 승인되면 복구된다.
- **`/api/v1` 매물 조회가 주는 404·빈 목록은 데이터 상태가 아니다** — 그 경로가 끝났다는 뜻이므로 "삭제된 매물"로 안내하지 말고 앱 업데이트를 유도한다. 매물 데이터는 `/api/v2/listings*`에서 조회한다(§3).
- **게스트(비로그인)로 퀴즈·생활 팁·진단을 부를 땐 `Authorization` 헤더를 아예 보내지 않는다** — 만료된 토큰을 그대로 붙여 보내면 게스트로 처리되지 않고 `401 TOKEN_EXPIRED`다(재발급하거나 헤더를 떼고 재시도). 진단 v2는 `POST /api/v2/diagnoses/start` 응답의 게스트 세션 키를 보관했다가 이후 요청에 `X-Guest-Session-Id`로 에코해야 하며, 잃어버리면 `400 DIAGNOSIS_SESSION_NOT_FOUND`이므로 `/start`부터 다시 한다(#181).
- `5xx` → 사용자에게 일반 메시지 + 재시도 버튼. 자동 재시도는 멱등 요청에만.
- 다국어: `code`별 문구 테이블을 클라이언트가 보유한다(서버 `message`는 fallback).

## 체크리스트

- [ ] 모든 에러가 공통 래퍼(`success=false`/`error.code`/`message`)로 응답된다
- [ ] 컨트롤러에서 `try/catch`로 응답을 만들지 않고 전역 핸들러로 변환한다
- [ ] 새 에러는 `ErrorCode` enum + 메시지 번들 2벌(`messages`·`messages_ko`) + 카탈로그(§4) + 해당 도메인 [API 스펙](./specs/)의 에러 표에 **빠짐없이** 등록했고 status 매핑(§3)을 지켰다
- [ ] 비즈니스 예외는 `BusinessException`을 상속하고 의미 있는 이름을 가진다
- [ ] 검증 실패는 `INVALID_INPUT` + `errors[]`로 내려간다
- [ ] 5xx는 `ERROR` 로그(스택트레이스 포함), 4xx는 스택트레이스를 남기지 않는다
- [ ] 응답 `message`·로그에 민감정보를 노출하지 않는다
