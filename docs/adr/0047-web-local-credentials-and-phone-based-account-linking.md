# ADR-0047. 웹 로컬 자격증명을 별도 테이블로 분리하고 휴대폰 번호로 계정을 공유한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0047 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-08-16 |
| 기준 코드 | `feature/229-web-landlord-auth` @ `86654fb`. 본 ADR의 파일·경로 참조는 전부 이 시점 기준이며, 재검증 없이 인용하지 않는다 |
| 관련 문서 | [ADR-0001](./0001-bounded-context-module-decomposition.md), [ADR-0003](./0003-jwt-auth-after-oauth-login.md), [ADR-0005](./0005-polyglot-persistence.md), [ADR-0008](./0008-mysql-migration-flyway.md), [ADR-0014](./0014-withdrawal-pii-anonymization.md), [ADR-0030](./0030-error-message-i18n-resource-bundle.md), [ADR-0034](./0034-landlord-phone-sms-verification.md), [ADR-0048](./0048-web-refresh-token-httponly-cookie.md), [US-1-11·US-1-12·US-1-13·US-1-15](../requirements/user-stories.md), [01-auth-onboarding](../api/specs/01-auth-onboarding.md), [error-response-guide §4](../api/error-response-guide.md), [database-design §4](../database/database-design.md), [code-style §3](../convention/code-style.md) |

## Status

Proposed

## Context

임대인이 매물을 등록하는 **웹**(앱과 별개 클라이언트)에 로그인·회원가입을 붙인다. 소셜이 아니라 **이메일 + 비밀번호**다. 지금까지 인증은 소셜 OIDC 하나뿐이었으므로([ADR-0003](./0003-jwt-auth-after-oauth-login.md)) 서버에 비밀번호 자격증명이 존재한 적이 없다.

**진짜 제약은 인증 방식이 아니라 소유권 사슬이다.** 임대인 데이터는 `users.id` 하나에 전부 매달려 있다.

```
매물 등록  listing.landlordId = principal.userId()          (ListingV2Controller.register)
예약 생성  booking.landlordId = offer.landlordId()          (BookingService — 비정규화 복사)
예약 조회  getUserType(userId)=="LANDLORD"
             → bookingRepository.findVisibleByLandlordId(userId, …)   (BookingService)
```

즉 **웹 가입이 새 `users` 행을 만드는 순간 `landlordId`가 갈라지고, 앱은 웹에서 등록한 매물의 예약을 영원히 보지 못한다.** 채팅도 같은 축을 쓸 예정이라 같은 결과가 된다. 이 기능의 존재 이유가 "웹에서 등록한 매물의 신청을 앱에서 본다"이므로, 계정이 갈라지면 기능 자체가 성립하지 않는다.

두 클라이언트의 계정을 같다고 판정할 재료는 하나뿐이다. `users.phone_number`는 **임대인 온보딩의 SMS 인증값으로만 채워지고 세입자는 NULL**이며([V8](../../src/main/resources/db/migration/V8__users_landlord_fields.sql)), 그 번호는 이미 소유가 증명된 값이다([ADR-0034](./0034-landlord-phone-sms-verification.md)). 반대로 소셜 로그인 시점에 서버가 아는 것은 provider·`sub`·`email`·`name`뿐이라 **그 시점엔 번호를 모른다** — `AuthService`가 `createPendingUser(name, email)`로 `users` 행을 먼저 만든다.

기존 SMS 인증은 그대로 재사용할 수 없다. 챌린지 키가 `phone-verify:code:{userId}`라 **계정이 없는 단계에서 쓸 수 없고**, 경로도 인증 필요 티어다([ADR-0034](./0034-landlord-phone-sms-verification.md) §8).

현재 `users.phone_number`에는 인덱스도 UNIQUE도 없다(V8). [ADR-0034](./0034-landlord-phone-sms-verification.md)가 "연락처 유니크 제약(동일 번호 다계정 허용 여부)"을 미결로 남겨 둔 그 자리다.

따라서 **① 웹 자격증명을 어디에 둘지 ② 두 클라이언트의 계정을 무엇으로 같다고 볼지 ③ 그 판정을 언제 할지**를 결정해야 한다.

## Decision

**웹 로컬 자격증명을 `local_accounts` 테이블로 신설해 `social_accounts`와 대칭으로 한 `users` 행에 매단다. 계정 동일성은 SMS 인증을 통과한 휴대폰 번호 단독으로 판정하고, `users.phone_number`에 UNIQUE를 걸어 그 판정을 DB가 지키게 한다.**

### 1. `users`에 비밀번호 컬럼을 붙이지 않는다 — 자격증명은 `auth`, 프로필은 `user`

`users`는 `user` 모듈이 소유하는 엔티티다. 거기에 `password_hash`·`failed_login_attempts`·`locked_at`을 붙이면 **`auth`가 `user`의 테이블을 쓰는 구조**가 되어 모듈 경계가 뚫린다([ADR-0001](./0001-bounded-context-module-decomposition.md)). 자격증명 검증은 명백히 `auth`의 일이고 `user`는 그 값을 읽을 이유가 없다.

그리고 그 자리는 이미 대칭이 잡혀 있다 — 소셜 자격증명은 `users`가 아니라 `social_accounts`에 있다. 로컬 자격증명도 같은 모양으로 둔다.

```
users (id=42, name, phone_number, user_type=LANDLORD, status=ACTIVE)   ← 사람 = 소유권 축
  ├── social_accounts (provider, provider_user_id, ...)                ← 앱 로그인
  └── local_accounts  (email, password_hash, ...)                      ← 웹 로그인   신규
```

부수 효과로 **세입자 다수의 `users` 행에 영원히 NULL인 자격증명 컬럼 넷이 생기지 않는다.**

`local_accounts`(V22)는 `uq_local_accounts_email`(로그인 ID 유일성)과 `uq_local_accounts_user_id`(**한 계정에 웹 자격증명 하나**)를 갖는다. 후자가 "이미 웹 계정이 있는데 또 붙는" 상태를 DB 레벨에서 막는다. **FK는 걸지 않는다** — `social_accounts`가 V1에서 의도적으로 생략한 선례를 따른다. 비밀번호는 **BCrypt**로만 보관하고(`password_hash VARCHAR(100)` — BCrypt 60자 + 여유), 원문은 저장·로그 어디에도 남기지 않는다. 비밀번호 정책은 **영문자·숫자·ASCII 특수문자 각 1자 이상 · 길이 8~20 · 공백 불허**이며 요청 DTO의 `@Pattern`으로 걸어 기존 `INVALID_INPUT` 흐름에 태운다.

### 2. 한 `users` 행을 공유한다 — 웹 가입은 "계정 생성"이 아니라 "자격증명 추가"다

§Context의 사슬이 전부 `landlordId` 하나를 본다. 그래서 **웹 계정과 앱 계정이 같은 `users` 행을 쓰면 예약·채팅 연동은 추가 코드 없이 성립한다.** 옮길 데이터가 없기 때문이다.

- 번호로 기존 `users`를 찾으면 → 그 `user_id`에 `local_accounts` 행만 INSERT한다. **`users`는 건드리지 않는다**(`linked=true`).
- 못 찾으면 → 새 `users`를 만들되 **앱과 같은 상태 체인**(`PENDING → TERMS_AGREED → ACTIVE`)을 한 트랜잭션에서 연속 전이시킨다(`linked=false`). 기존 도메인 메서드(`createPendingUser`·`agreeToTerms`·`completeLandlordOnboarding`)를 순서대로 부를 뿐 `user` 모듈에 새 생성 메서드를 만들지 않는다 — `@Transactional(REQUIRED)` 전파로 원자성이 성립한다.
- 찾았는데 그 계정에 이미 `local_accounts`가 있으면 → `409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS`다. 붙일 자리가 이미 찼고, 남은 동작은 기존 자격증명 덮어쓰기뿐인데 그건 가입이 아니라 **자격증명 교체**다.

웹에 `PENDING`·`TERMS_AGREED` 같은 부분 완료 상태를 남기지 않는 이유는 단순하다 — **웹에는 온보딩 재개 화면이 없어서 로그인해도 갈 곳이 없는 죽은 계정이 된다.** 그럼에도 상태 체인 자체는 그대로 태운다. 앱 계정과 데이터 모양이 같아야 연동이 성립하기 때문이다.

신규 엔드포인트는 넷이고 전부 `POST`·전부 permitAll이다(계정 복구 여섯 경로가 뒤에 더해진다 — 아래 Amended(#272)).

| Path | 역할 | 스토리 |
|---|---|---|
| `/api/v1/auth/phone/signup/verification-code` | 가입용 인증번호 발송(번호 키) | US-1-13 |
| `/api/v1/auth/phone/signup/verify` | 가입용 인증번호 확인 | US-1-13 |
| `/api/v1/auth/signup` | 웹 회원가입 + 기존 계정 연동 | US-1-11 |
| `/api/v1/auth/login` | 웹 로그인 | US-1-12 |

### 3. 매칭 키는 휴대폰 번호 단독이고, SMS 소유 증명이 반드시 앞선다

**휴대폰 번호는 비밀이 아니다.** 번호를 아는 것만으로 기존 계정에 자격증명을 붙일 수 있으면, 남의 번호를 아는 사람이 비밀번호를 심어 그 계정의 매물·예약·신청자 PII를 통째로 가져간다.

> **번호는 조회 키이지 인증 수단이 아니다. 소유 증명은 전적으로 SMS 인증이 담당한다.** 인증 마커가 없으면 `422 AUTH_PHONE_NOT_VERIFIED`이고 **계정 생성도 연동도 하지 않는다.**

**이름은 매칭 조건에 넣지 않는다.** SMS가 이미 소유를 증명하므로 이름을 더해도 막히는 공격이 늘지 않는 반면, 실패는 크게 는다 — 앱 이름은 소셜 SDK 표기(`Kim Imdae`)이고 웹 이름은 직접 입력(`김임대`)이라 불일치가 자연스럽다. 불일치하면 **계정이 조용히 갈라지고 사용자는 "앱에서 내 매물의 예약이 안 보인다"만 겪는다.** 원인을 알 수 없는 실패를 만드는 조건은 걸지 않는다.

가입 전 단계라 기존 인증을 못 쓰므로 **번호 키 챌린지**를 신설한다(`signup-phone:code:{정규화번호}` / `signup-phone:verified:{정규화번호}`). 인증번호 정책(6자리·TTL 5분·마커 30분·시도 5회·재발송 60초)과 `VerificationSmsSender` 포트는 [ADR-0034](./0034-landlord-phone-sms-verification.md)를 그대로 재사용한다. **permitAll SMS 발송은 문자 폭탄·발송비 남용 표면**이므로 번호 5회/시간 + IP 20회/시간의 이중 레이트리밋을 건다(초과 시 `429 TOO_MANY_REQUESTS`). 가입 이력 유무와 무관하게 같은 응답을 내려 계정 존재 여부를 노출하지 않는다.

번호는 **입력 경로에서만 정규화**한다(숫자만 남김). 기존 데이터는 백필하지 않는다(§Consequences).

### 4. 앱 방향의 연동 지점은 로그인이 아니라 임대인 온보딩이다 — 그리고 연결이 아니라 병합이다

연동은 어느 쪽으로 먼저 가입하든 성립해야 하는데, **판정 가능 시점이 두 방향에서 다르다.** 서버가 번호를 언제 아는지가 다르기 때문이다.

| 방향 | 판정 시점 | 그때 번호를 아는가 | 기존 행 상태 | 동작 |
|---|---|---|---|---|
| 웹 → 앱 | 가입 제출(US-1-11) | O — SMS 인증을 이미 통과 | 아직 안 만듦 | **연결**(`local_accounts` INSERT) |
| 앱 → 웹 | **소셜 로그인 시점엔 불가** | X — 소셜은 `name`·`email`만 준다 | PENDING 행이 이미 있음 | **병합**(온보딩 제출에서) |

소셜 로그인은 신원 확인 직후 `createPendingUser`로 행을 만든다. 그 시점에 번호가 없으므로 **로그인에서는 판정할 방법이 없다.** 번호를 처음 아는 지점은 임대인 온보딩의 SMS 인증이고, 그때는 이미 임시 행이 있으므로 병합이 된다.

병합이 안전한 이유는 **그 임시 계정이 방금 만들어져 매물·예약이 하나도 없기 때문**이다. 실제로 옮기는 것은 `social_accounts` 한 행뿐이다.

```
verified = 정규화된 인증 번호
target = SELECT * FROM users                     ← 잠근 행에서 응답 프로필까지 만든다(재조회 없음)
          WHERE phone_number = :verified AND id <> :currentUserId
            AND status='ACTIVE' AND user_type='LANDLORD'
          FOR UPDATE
├─ 없음 → 기존 동작 그대로 (US-1-9 무변경)                     응답 linked=false
└─ 있음 → UPDATE social_accounts SET user_id = :targetId WHERE user_id = :currentUserId
          DELETE FROM users WHERE id = :currentUserId
          issueFullTokens(:targetId)      ← currentUserId가 아니다
                                                              응답 linked=true
```

**병합 여부는 응답 필드 `linked`로 명시한다 — 웹 가입(§2)과 같은 이름을 쓴다.** 두 방향은 구현이 다르지만(자격증명 INSERT / 계정 병합) 클라이언트가 받는 사실은 **"계정이 하나로 합쳐졌다"** 하나이므로, 같은 개념에 단어를 두 벌 두지 않는다. 명시하지 않으면 앱은 *자기가 보낸 토큰에 박힌 `userId`* 를 응답 `user.id`와 대조해야만 병합을 알 수 있는데, 그 비교를 빠뜨려도 **그 화면까지는 아무 이상이 없고** 낡은 토큰으로 다음 API를 부르고 나서야 깨진다. 병합 뒤 표시되는 이름·이메일이 방금 입력한 값이 아닌 이유(§6 — 대상 프로필을 덮어쓰지 않는다)를 앱이 사용자에게 설명하려면 그 사실을 **알아야** 한다. 값은 병합 분기를 고른 그 조회 결과에서 그대로 나오므로 플래그와 실제 실행 경로가 갈릴 수 없고, 토큰은 여전히 응답 프로필의 id에서만 발급된다. **응답 필드 추가는 하위 호환이라 `/api/v1`을 유지한다.**

웹 가입(§2)의 같은 조회가 **식별자만** 돌려받는 것과 달리 병합은 **프로필까지** 받는다 — 응답의 `user`가 대상 계정 기준이라 어차피 필요한 값이고, 잠근 행에서 바로 만들면 조회가 한 번으로 끝나며 응답이 그 잠긴 행의 값임이 보장된다. 덮어쓰기 위험은 없다: 병합은 대상 행을 한 칼럼도 쓰지 않는다(§6).

`status='ACTIVE' AND user_type='LANDLORD'`는 지금은 중복이다(번호가 채워진 계정은 사실상 `ACTIVE` 임대인뿐이다). **그래도 명시한다** — 나중에 누군가 다른 경로에서 `PENDING` 계정에 번호를 채워도 병합이 오작동하지 않게 하기 위해서다. 암묵적 불변식에 기대지 않는다. 영향 행 수는 단언하지 않는다(UPDATE는 N행이어도 안전하고, 대상 쪽에 `social_accounts`가 여러 행인 것은 한 사람이 Google·Apple로 들어온 정상 상태다).

임시 계정 행은 **하드 삭제**한다 — 미완료 계정(`PENDING`·`TERMS_AGREED`)을 `DELETE`로 정리한 [V21](../../src/main/resources/db/migration/V21__delete_incomplete_accounts.sql)의 선례를 따른다. 탈퇴의 상태 전이·익명화([ADR-0014](./0014-withdrawal-pii-anonymization.md))를 쓰지 않는 이유는, 이 행이 사람의 계정이 아니라 **몇 분 전에 만들어진 빈 껍데기**라 보존할 이력이 없기 때문이다. 다만 **그 계정의 진단 기록은 지우지 않는다** — 현재는 탈퇴조차 진단을 지우지 않으므로(`UserWithdrawnEvent` 구독자는 `auth` 하나뿐), 병합이 탈퇴보다 공격적으로 지우는 비대칭을 만들지 않는다.

### 5. `users.phone_number`에 UNIQUE — check-then-act를 막는 유일한 수단

같은 번호로 **웹 가입 제출**과 **앱 임대인 온보딩 제출**이 거의 동시에 도착하면, 양쪽이 "그 번호를 가진 `users`가 있는가"를 상대의 커밋 전에 조회해 **둘 다 없음으로 판정**한다 → 웹은 새 계정을 만들고 앱은 병합 없이 자기 계정을 `ACTIVE`로 전이시킨다 → **같은 번호의 `ACTIVE` 계정이 둘** 생기고, 이후 병합 트리거가 없다.

애플리케이션 레벨 조회로는 막을 수 없다(아직 없는 행은 잠글 수 없다). **V23에서 `uq_users_phone_number`를 건다** — 두 번째 트랜잭션이 DB 제약으로 실패하므로 계정이 갈라지지 않고, 실패한 쪽은 재시도하면 상대가 만든 계정을 발견해 정상 연동·병합된다.

그 실패는 **`409 RESOURCE_CONFLICT`(공통 코드)로 번역해 내려간다.** 번역하지 않으면 `DataIntegrityViolationException`이 전역 핸들러의 마지막 그물까지 흘러 **500 `INTERNAL_ERROR`** 가 되는데, 그 status는 에러 카탈로그에 없고 재시도로 복구된다는 신호도 주지 못한다. 번역 지점이 **전역 핸들러**인 것은 위반이 드러나는 시점이 쓰기 종류마다 다르기 때문이다 — IDENTITY INSERT(`local_accounts`)는 `save`에서 즉시, 기존 행 UPDATE(`users.phone_number` — 임대인 온보딩)는 플러시가 밀려 **`@Transactional` 프록시가 반환한 뒤 커밋에서** 터진다. 후자는 서비스 안의 어떤 `catch`로도 잡히지 않으므로 둘을 함께 덮는 자리는 트랜잭션 바깥뿐이고, 그 자리에서는 도메인 코드를 낼 수 없으므로 코드도 도메인 무관하다(`AUTH_*`로 번역하면 다른 모듈의 제약 위반까지 인증 에러가 된다). 미리 판정할 수 있는 충돌은 종전대로 도메인이 자기 코드로 낸다(`BOOKING_ALREADY_EXISTS`, 차단의 멱등 흡수).

번역은 **무조건이 아니라 화이트리스트**다. 핸들러는 원인 사슬의 Hibernate `ConstraintViolationException`에서 제약 이름을 읽어 이 결정이 만든 세 개(`uq_users_phone_number` · `uq_local_accounts_email` · `uq_local_accounts_user_id`)와 대조하고, **일치할 때만** 409를 낸다. 같은 예외 타입으로 오는 NOT NULL 위반·길이 초과는 재시도가 무의미한 **서버 버그**라 종전대로 ERROR 로그 + 500으로 남긴다 — 전부 409로 낮추면 클라이언트에게 재시도하라고 거짓말을 하면서 로그 레벨까지 WARN으로 떨어져 알림이 사라진다([error-response-guide §4](../api/error-response-guide.md)).

MySQL의 UNIQUE는 **NULL 중복을 허용**하므로 세입자(정의상 NULL)와 탈퇴자(익명화로 NULL — [ADR-0014](./0014-withdrawal-pii-anonymization.md))는 영향받지 않는다. 이 제약이 [ADR-0034](./0034-landlord-phone-sms-verification.md)의 미결 항목("연락처 유니크 제약 — 현재 미적용")을 닫는다.

> **Amended — 유형별 복합키로 완화(`V28`).** 관리자(`ADMIN`) 유형이 생기면서 이 결정의 "번호 하나당 계정 하나"를 **"번호 하나당 유형별로 계정 하나"** 로 좁힌다. 관리자는 가입 경로가 없어 운영자가 웹 가입 계정을 승격해 만드는데, 승격하면 그 계정이 `LANDLORD` 조회에서 빠지면서도 `phone_number`는 계속 점유해 **같은 사람이 그 번호로 임대인 계정을 따로 만들 수 없게** 되기 때문이다.
>
> **막으려던 경쟁은 그대로 막힌다.** 위 시나리오에서 경쟁하는 두 INSERT는 **둘 다 `LANDLORD`** 이므로 복합키에서도 `(LANDLORD, 010X)`로 충돌한다. 공존이 허용되는 것은 유형이 다른 `(ADMIN, 010X)`뿐이다.
>
> **애플리케이션 코드는 바뀌지 않는다.** 번호로 조회하는 두 쿼리(`findByPhoneNumberAndStatusAndUserType`·`...AndIdNot`)가 이미 `userType`으로 필터하므로 전역 유일성을 가정하는 코드가 없다. **제약 이름도 `uq_users_phone_number` 그대로 둔다** — `GlobalExceptionHandler`가 제약 이름 화이트리스트로 이 위반을 `409 RESOURCE_CONFLICT`로 번역하므로, 이름을 바꾸면 그 경합이 `500 INTERNAL_ERROR`로 떨어진다.
>
> 제약을 **느슨하게** 하는 변경이라 기존 행이 전부 새 제약을 자동으로 만족한다 — 선행 정리 쿼리가 필요 없고 `V23`(제약 강화)과 반대 방향이다.

### 6. 정본은 `users`, 자격증명 테이블은 스냅샷이다

웹 폼은 연동 여부와 무관하게 항상 전체 필드를 받는다(화면이 하나이고 분기가 없다). 그 `name`·`birth_date`를 버리지 않고 `local_accounts`에 함께 저장한다 — 새 패턴이 아니라 **기존 구조의 대칭 적용**이다. `social_accounts`가 이미 `email`(V1)과 `name`([V20](../../src/main/resources/db/migration/V20__social_accounts_name.sql) — *"provider가 준 표시 이름 스냅샷을 보관한다(User.name과 별개)"*)을 들고 있다.

| 테이블 | 역할 | 값의 성격 |
|---|---|---|
| `users` | 사람 · 소유권 축(`landlordId`) | **정본** |
| `social_accounts` | 앱 자격증명 + provider가 준 `email`·`name` | 스냅샷 |
| `local_accounts` | 웹 자격증명 + 웹 폼이 준 `name`·`birth_date` | 스냅샷 |

> **표시 규칙: 모든 응답은 `users`의 값을 쓴다. `local_accounts`의 사본은 어떤 응답에도 싣지 않는다.**

규칙을 못박는 이유는, "웹 요청이면 local·앱이면 users"로 갈리는 순간 같은 사람이 웹에선 `김임대`·앱에선 `Kim Imdae`로 보이고 프로필 수정이 어느 테이블을 바꿔야 하는지 모호해지기 때문이다. 따름정리는 둘이다 — **① 프로필 수정(`PATCH /users/me`)은 `users`만 바꾼다**(스냅샷은 갱신 대상이 아니다), **② 연동 시 `users`를 덮어쓰지 않는다.** 기존 값은 온보딩을 마친 확정 값이고 폼 값은 방금 입력한 미검증 값이다.

`users.email`도 같은 원칙이다. **연동 시에는 건드리지 않고**(소셜 진본 유지) 웹 이메일은 `local_accounts.email`에만 둔다. **신규 가입일 때만** 폼 이메일을 `users.email`에도 기록한다. 그래서 이메일 중복 검사는 **`local_accounts.email`에만** 건다 — `users.email`까지 유일성을 걸면 "본인이 본인 소셜 이메일로 웹 가입하다 409를 맞는" 가장 흔한 정상 경로가 막힌다. 소셜 로그인은 `(provider, provider_user_id)`로 판정하므로 `users.email`은 로그인에 쓰이지 않는다. **`users.email`에는 UNIQUE를 추가하지 않는다.**

### 7. 에러 매핑

| 코드 | status | 의미 |
|---|---|---|
| `AUTH_INVALID_CREDENTIALS` | 401 | 웹 로그인 실패. **이메일 없음과 비밀번호 불일치를 같은 응답으로** 낸다(존재 여부 비노출) |
| `AUTH_ACCOUNT_LOCKED` | **423** | 비밀번호 10회 연속 실패로 잠긴 계정. **비밀번호가 맞아도 잠금이 우선**이다. 해제는 비밀번호 재설정이 겸한다(아래 Amended #272) |
| `AUTH_EMAIL_ALREADY_REGISTERED` | 409 | 그 이메일을 이미 남이 웹 로그인 ID로 쓰고 있다 |
| `AUTH_WEB_ACCOUNT_ALREADY_EXISTS` | 409 | 번호로 찾은 계정에 이미 웹 자격증명이 있다. 로그인 화면으로 보낸다 |
| `RESOURCE_CONFLICT`(공통) | 409 | 위 세 UNIQUE 제약의 중복 위반 — 같은 번호의 가입·온보딩이 거의 동시에 확정됐다(§5). **재시도가 유효한 복구**다. 그 밖의 제약 위반은 이 코드가 아니라 500이다 |

`AUTH_WEB_ACCOUNT_ALREADY_EXISTS` 응답에는 **마스킹 이메일을 싣지 않는다.** 공통 스키마(code/message)만 낸다 — SMS 인증을 통과해야 닿는 지점이라 무차별 열거는 불가능하지만, 번호 소유자에게 남의 이메일 일부를 노출할 이유도 없다. (같은 게이트 뒤의 이메일 찾기가 반대로 마스킹 이메일을 돌려주는 이유는 아래 Amended(#272).)

실패 횟수·잠금 여부는 **`local_accounts`의 컬럼**(`failed_login_attempts`·`locked_at`)에 둔다. Redis TTL로 두면 만료와 함께 잠금이 저절로 풀려 "해제 기능 없음"이라는 정책이 깨진다. **근거 교체: 아래 Amended(#272)** — 본인이 비밀번호 재설정으로 푸는 해제 경로가 생겼고, 컬럼을 쓰는 이유는 이제 **명시적 해제 시점의 기록·감사 가능성**이다(TTL 만료로 저절로 풀리면 안 된다는 점은 그대로다). 네 코드 모두 `messages.properties`(영어)·`messages_ko.properties`(한국어) **양쪽**에 키를 넣는다([ADR-0030](./0030-error-message-i18n-resource-bundle.md)) — 기존에 `AUTH_EMAIL_REQUIRED`·`AUTH_EMAIL_MISMATCH`가 두 파일 모두에서 누락돼 조용히 한국어를 내보내고 있다.

## Alternatives

| 대안 | 장점 | 단점 | 채택 안 한 이유 |
|---|---|---|---|
| **A. `local_accounts` 분리 + 번호로 `users` 공유(채택)** | 소유권 사슬이 그대로 성립(옮길 데이터 0), `social_accounts`와 대칭, 앱 흐름 무변경 | 테이블 하나 증가, 계정 동일성이 번호 하나에 걸린다 | — (채택) |
| B. `users`에 `password_hash` 등 컬럼 추가 | 테이블이 늘지 않고 조인이 없다 | `auth`가 `user` 테이블을 쓰게 되어 모듈 경계가 뚫리고, 세입자 행에 영원히 NULL인 컬럼 넷이 남는다 | 자격증명(`auth`)과 프로필(`user`)의 소유가 갈리는 것이 이 코드베이스의 기존 경계다 |
| C. `/auth/social-login`에 `provider=LOCAL`을 끼워 넣는다 | 엔드포인트가 늘지 않는다 | `SocialLoginRequest`가 이미 provider별 조건부 자격 필드로 복잡한데 `password`까지 섞으면 검증 분기가 3중이 되고, OIDC 검증이 없는 경로가 OIDC 엔드포인트 안에 숨는다 | 로그인 수단이 다르면 계약도 달라야 한다 — `LOCAL`은 `idToken`도 `authorizationCode`도 없다 |
| D. 웹 전용 계정 스키마(별도 `users`) | 웹 도메인이 앱과 완전히 독립 | `landlordId`가 갈라져 **앱이 웹 등록 매물에 영구히 눈이 먼다** — 예약·채팅을 보이게 하려면 두 id를 잇는 매핑 테이블과 모든 조회의 이중 조회가 필요하다 | 이 기능의 목적을 정면으로 부순다(§Context) |
| E. 이름 + 번호로 매칭 | 조건이 하나 더 있어 안전해 보인다 | SMS가 이미 소유를 증명하므로 보안 기여가 0인데, 소셜 표기와 직접 입력의 불일치로 **계정이 조용히 갈라진다** | 막는 공격은 없고 만드는 실패는 많다(§3) |
| F. 이메일로 매칭 | 소셜 이메일과 웹 이메일이 같은 경우가 많다 | 소셜 이메일은 provider가 준 값이고 웹 이메일은 미검증 입력이다 — 남의 소셜 이메일을 적으면 그 계정에 붙는다 | 소유 증명이 없는 값을 매칭 키로 쓸 수 없다 |

## Amended (#270) — 정보 최소화 원칙을 「타인 식별 정보」로 좁힌다

이 ADR은 에러 응답에 부가 정보를 싣지 않는다는 방침을 세웠고, 그 근거로 `AUTH_WEB_ACCOUNT_ALREADY_EXISTS` 응답에 마스킹 이메일조차 싣지 않기로 했다. 그 원칙을 **「타인을 식별할 수 있는 정보」에 한정**하는 것으로 좁힌다.

- **로그인 실패 응답은 `error.details`에 `failedAttempts`·`maxFailedAttempts`를 싣는다.** 잠금 해제 경로가 없는 정책이라, 잠기기 **전에** 남은 시도를 알려 주지 않으면 임대인은 손쓸 수 없는 상태에서만 사실을 알게 된다. **근거 교체: 아래 Amended(#272)** — 해제 경로가 생긴 뒤에도 필드는 그대로 유지한다.
- **이 값은 타인 식별 정보가 아니다** — 호출자가 이미 이메일과 비밀번호를 제출한 그 계정의 상태 하나뿐이고, 남의 이메일·이름 같은 것이 새지 않는다.
- **대신 계정 열거를 수용한다.** `details`가 등록된 계정에만 실리므로 그 유무로 가입 여부가 드러난다. 잠긴 계정의 `423`이 이미 존재를 드러내고 있고 그것을 「의도된 것」으로 받아들인 이상, 같은 엔드포인트에서 열거를 막고 있다고 말할 수 없다.
- 잠금 임계값은 **10회**, 시도 한도는 **IP 60회/시간·이메일 20회/시간**으로 함께 올린다. 이메일 한도를 임계값의 2배로 두는 것이 계약이다 — 같거나 낮으면 한 시간 창 안에서 잠금 도달이 사실상 불가능해진다.

## Amended (#272) — 잠금 해제 경로를 열고, 표시 규칙·이름 미매칭 결정에 예외를 둔다

이 ADR은 잠긴 계정을 본인이 풀 수단을 두지 않았고(§7·§Consequences), 자격증명 테이블의 사본을 어떤 응답에도 싣지 않기로 했으며(§6), 계정 매칭에서 이름을 뺐다(§3·대안 E). **임대인 웹 계정 복구**(이메일 찾기 · 비밀번호 재설정 — [US-1-16·US-1-17](../requirements/user-stories.md), [01-auth-onboarding](../api/specs/01-auth-onboarding.md))가 그 셋을 각각 건드린다. 어느 것도 원 결정을 뒤집지 않는다 — **적용 범위를 좁히거나 근거를 갈아 끼울 뿐**이고, 원 결정이 막으려던 실패는 그대로 막힌다.

**먼저 배포 범위를 못박는다.** 복구 경로 여섯 개는 토글(`app.auth.web.password-reset.enabled`, base 기본 `false`)로 켜며 지금은 `local`·`dev`에만 있다. **prod에는 아직 이 경로가 없으므로** 아래의 "본인이 해제한다"를 prod 운영 절차의 근거로 삼지 않는다.

- **잠금 해제 경로를 만든다 — 본인이 비밀번호 재설정으로 푼다.** `POST /api/v1/auth/password/reset`이 비밀번호를 교체하면서 같은 자리에서 `failed_login_attempts`를 0으로 되돌리고 `locked_at`을 비운다. 독립 잠금 해제 API를 따로 두지 않는 이유는 **잠긴 사람은 대개 비밀번호를 모르는 상태**라서다 — 해제만 해 주면 곧바로 다시 10회를 틀린다. 화면은 「비밀번호 찾기」와 「잠금 안내」 둘이지만 부르는 계약은 하나다. 재설정은 기존 refresh를 전량 무효화하고 **새 세션을 발급하지 않는다** — 복구는 로그인 화면으로 되돌아가는 것으로 끝난다. **시간 경과 자동 해제는 여전히 없고**, 운영자가 `locked_at`을 비우는 수동 해제는 **예외 수단으로 남는다**(prod 미배포, 메일 주소 자체가 잘못 입력돼 링크를 받을 수 없는 계정).
- **잠금을 컬럼에 두는 근거를 갈아 끼운다.** 종전 근거는 「해제 기능이 없으니 TTL로 두면 만료와 함께 풀려 정책이 깨진다」(§7)였는데, 해제가 생긴 지금 그 문장만 남으면 **"그럼 TTL이어도 되지 않나"로 읽힌다.** 새 근거는 **명시적 해제 시점의 기록과 감사 가능성**이다 — 컬럼은 언제 잠겼고 언제 풀렸는지가 행에 남아 문의 대응·사고 조사에서 근거가 되는 반면, TTL 키는 만료되는 순간 흔적 없이 사라져 「그 계정이 잠겼던 적이 있는가」조차 사후에 답할 수 없다. **「TTL 만료로 저절로 풀리면 안 된다」도 그대로 유효하다** — 해제는 본인이 비밀번호를 바꾼 결과여야 하고, 기다리면 풀리는 잠금은 자동 대입을 시간당 상한으로 늦출 뿐 끊지 못한다. **V22의 컬럼 주석은 고치지 않는다** — 이미 적용된 Flyway 스크립트를 손대면 체크섬이 어긋나 기동이 실패한다. 근거의 정본은 이 절과 [error-response-guide §4](../api/error-response-guide.md)다.
- **§6 표시 규칙의 예외 — 자격증명 회수는 프로필 표시가 아니다.** 「모든 응답은 `users`의 값을 쓴다. `local_accounts`의 사본은 어떤 응답에도 싣지 않는다」를 **표시용 프로필 필드(`name`·`email`)에 한정**한다. 그 규칙이 막으려던 것은 같은 사람이 웹에선 `김임대`, 앱에선 `Kim Imdae`로 보이는 **표기 분열**이다. 이메일 찾기(`POST /api/v1/auth/email/find`)가 돌려주는 값은 표기가 아니라 **로그인 ID 자체**이고, 그 ID의 정본은 정의상 `local_accounts.email`이다. 여기서 규칙을 곧이곧대로 지켜 `users.email`을 주면 **연동 계정 사용자는 그 주소로는 로그인이 되지 않는 이메일을 안내받는다** — 연동 시 `users.email`은 소셜 진본 그대로 두고 웹 이메일은 `local_accounts`에만 넣기 때문이다(§6). 틀린 값을 주는 정도가 아니라 **기능이 통째로 무효가 된다.** 게다가 `users.email`에는 UNIQUE가 없어(§6) 같은 주소를 가진 행이 여럿일 수 있다 — 애초에 로그인 ID로 성립하지 않는 값이다. 응답은 마스킹해서 낸다(`ki***@work.com`).
- **§3 이름 미매칭 결정의 예외 — 이메일 찾기는 이름을 대조한다.** 계정 연동 판정에서 이름을 뺀 결정(§3·대안 E)은 **그대로 유지한다.** 거기서 이름을 조건에 걸면 불일치가 계정을 **조용히 갈라놓고** 사용자는 "앱에서 내 매물의 예약이 안 보인다"만 겪는다 — 문제는 게이트가 세다는 것이 아니라 실패가 보이지 않는다는 것이었다. 이메일 찾기에는 그런 우회 경로가 없다. 이름이 어긋나면 `404 AUTH_WEB_ACCOUNT_NOT_FOUND`로 **눈에 보이게** 끝나고 사용자는 다시 입력한다. 게이트가 무력화되지 않으니 조건을 하나 더 걸어 실제로 얻는 것이 있다 — SMS 마커는 **번호 소유만** 증명하는데, 번호는 재사용·명의 이전으로 손이 바뀐다. 실패 응답은 **이름 불일치와 계정 미존재를 같은 404로 수렴**시켜 이름 오라클을 막는다.
  - **대조 대상은 `local_accounts.name` 단독이고 `users.name` 폴백을 두지 않는다.** 그 컬럼은 연동·신규 **두 경로 모두에서 가입 폼 값**으로 채워진다 — 웹 가입 폼은 분기가 없고 `local_accounts` 등록이 연동/신규 분기 **바깥**에 있어서다(§2·§6). `@NotBlank`라 값이 비어 있는 행도 없다. 반면 경로에 따라 갈리는 것은 `users.name` 하나뿐이다(연동이면 소셜 표기, 신규면 폼 값 — §6). 즉 **폴백이 메워 줄 빈칸이 애초에 없고**, 폴백을 두면 소셜 표기(`Kim Imdae`)까지 통과시켜 **게이트를 넓히기만 한다.**
- **`AUTH_WEB_ACCOUNT_ALREADY_EXISTS`에 마스킹 이메일을 싣지 않기로 한 결정과 모순되지 않는다.** 두 지점은 같은 SMS 게이트 뒤에서 같은 모양의 데이터(마스킹된 웹 로그인 ID)를 다루는데 결론이 반대다. 갈리는 것은 **그 이메일이 누구 것인가**다. 가입에서 그 409가 나오는 자리는 "내가 인증한 번호로 찾은 계정에 이미 웹 자격증명이 있다"인데, 번호는 위와 같은 이유로 손이 바뀔 수 있어 **그 계정이 남의 것일 수 있다** — 번호 소유자에게 **타인의 로그인 ID 일부**를 흘리는 셈이고, 그 화면에서 사용자가 할 일(로그인 화면으로 간다)에 그 값이 필요하지도 않다. 이메일 찾기는 반대다 — 호출자는 번호에 더해 **이름까지** 제출해 대조를 통과했고(위 항목), 돌려받는 것은 **자기 계정의 로그인 ID**이며, 그 값이 없으면 기능 자체가 성립하지 않는다. 원칙은 #270이 좁혀 둔 그대로다: 막는 것은 **타인 식별 정보**이지 자기 자신의 로그인 ID가 아니다.
- **로그인 실패 `error.details`의 근거를 교체한다(필드는 유지).** #270은 「잠금 해제 경로가 없어 잠기기 전에 알려야 한다」를 근거로 `failedAttempts`·`maxFailedAttempts`를 실었다. 해제가 생겼으니 그 근거는 무효다. 새 근거는 **해제가 있어도 사전 안내가 훨씬 싸다**는 것이다 — 잠기고 나면 메일 왕복과 재설정을 거쳐야 하고 그동안 임대인은 매물·예약에 손대지 못한다. 잠기기 **전에** 남은 시도를 알리는 것은 그 왕복을 **아예 만들지 않는** 값이라 UX상 여전히 필요하다. **계정 열거 수용도 다시 확인한다** — `details`가 등록된 계정의 비밀번호 불일치에만 실려 가입 여부가 드러나는 것은 그대로 받아들인다. 이번 변경은 표면을 하나 더한다: 재설정 링크 발송은 미가입 이메일에도 **같은 200**을 주지만 발송이 동기라 **응답 시간·502 분포로 존재가 드러난다.** 둘 다 「받아들인다」이고, 레이트리밋은 열거 방어가 아니라 발송비·남용 방어다.

## Consequences

- **긍정**
  - **웹에서 등록한 매물의 예약이 앱에서 그대로 보인다** — `landlordId`가 하나라 추가 코드가 없다. 매물·예약 데이터를 어느 방향에서도 옮기지 않는다.
  - 앱 흐름과 데이터 모양이 바뀌지 않는다(상태 체인·도메인 메서드 재사용). 세입자 흐름은 전혀 영향받지 않는다.
  - 자격증명 테이블이 `social_accounts`·`local_accounts` 둘로 대칭이 되어, 세 번째 채널이 생겨도 같은 모양으로 붙는다.
  - **대칭은 생애 끝까지 간다** — 탈퇴 정리(`UserWithdrawnEventListener`)가 두 테이블을 같은 자리에서 지운다. 한쪽만 지우면 탈퇴자가 남은 채널로 다시 로그인하고(탈퇴는 `users` 행을 지우지 않고 `user_type`도 그대로다) 그 쪽 유니크 제약이 살아남아 재가입까지 막는다.
  - `users.phone_number` UNIQUE가 [ADR-0034](./0034-landlord-phone-sms-verification.md)의 미결을 닫고, 동시 가입으로 계정이 갈라지는 경로를 DB 레벨에서 막는다.
- **부정/트레이드오프** — 아래는 전부 **알고 수용한 한계**이며 운영·후속에서 다뤄야 한다.
  - **번호 정규화 백필이 없다.** 정규화는 입력 경로에만 적용하고 기존 행은 손대지 않는다. 하이픈으로 저장된 기존 임대인 번호는 **매칭에서 누락될 수 있다** — 그 임대인은 웹 가입 시 연동되지 않고 별개 계정이 생긴다.
  - **잠금 해제 경로가 없다.** 시간 경과 자동 해제도 없다. 운영자가 DB에서 `locked_at`을 비우는 것이 유일한 방법이므로 **대응 창구를 운영에서 먼저 정해야 한다.** **개정: Amended(#272)** — 본인이 비밀번호 재설정으로 해제한다(`local`·`dev` 한정). 시간 경과 자동 해제가 없다는 것과, prod에서는 수동 해제가 유일하다는 것은 그대로다.
  - **의도적 계정 잠금(DoS)이 가능하다.** 남의 이메일로 10회 틀리면 그 계정을 잠글 수 있다. 잠금 정책의 고전적 부작용이며 로그인 시도 레이트리밋(IP 60회/시간·이메일 20회/시간, `app.auth.web.login.*`)으로 완화하되 완전히 막을 수는 없다. 그 한도는 자격증명 조회·해시 대조보다 **먼저** 평가해 `permitAll` 경로의 BCrypt 비용 증폭도 함께 막는다.
  - **세입자는 임대인이 될 수 없다.** `user_type`은 온보딩 확정 후 불변이고 세입자는 `phone_number`가 NULL이라 **구조적으로 매칭 후보에서 빠진다.** 앱에서 세입자로 가입한 사람이 웹에서 임대인 가입을 하면 별개 계정이 생기며, **서버는 두 계정이 동일인인지 알 방법이 없어 막을 수도 안내할 수도 없다.** 매칭 로직에 역할 분기를 추가하지 않는다.
  - **양쪽 모두 완주한 계정은 자동 병합하지 않는다.** 앱·웹 양쪽에 같은 번호의 `ACTIVE` 계정이 각각 있으면(위 백필 누락 등으로) 온보딩을 다시 타지 않으므로 트리거가 없다. **운영 수동 처리 대상**이며 코드·화면을 만들지 않는다.
  - **병합해도 임시 계정의 진단 기록은 남는다.** 사라진 `users` 행을 가리키는 진단 문서가 남을 수 있다 — 조회 주체가 없어 실질 영향은 없지만 고아 문서다.
  - **앱스토어 심사용 고정코드 우회는 웹에 적용되지 않는다.** `FixedVerificationPolicy`가 `userId` + Google 소셜 계정 기반이라 비로그인 번호 키 경로에 걸 자리가 없다. 앱 심사용 기능이라 웹과 무관하다.
  - **계정 열거가 가능하다(#270).** 로그인 실패 응답의 `error.details`가 등록된 계정의 비밀번호 불일치에만 실려, 임의의 비밀번호로 한 번 호출하면 그 이메일의 가입 여부를 알 수 있다. 잠기기 전에 남은 시도를 알려 주기 위해 수용한 결과다. 재설정 링크 발송이 동기라 **응답 시간·502 분포로도 존재가 드러난다**(Amended #272에서 재확인) — 둘 다 받아들인다.
  - **S3 pending 키가 id를 품는다.** 업로드 임시 키가 `uploads/{landlordId}/{uuid}.{ext}`라(`ListingImageKeys.pending`) 병합으로 id가 바뀌면 진행 중이던 pending 업로드는 고아가 된다. 병합은 가입 직후에만 일어나 실무상 무해하다.
- **후속 작업**
  - 신규 4경로는 `SecurityConfig`의 permitAll 매처와 **`PublicPaths.ALL` 두 곳**에 등록한다. 한쪽만 넣으면 만료 토큰을 든 브라우저가 로그인에서 `401 TOKEN_EXPIRED`를 맞는다(#181이 고친 버그와 같은 모양).
  - V23 적용 전 `SELECT phone_number, COUNT(*) FROM users WHERE phone_number IS NOT NULL GROUP BY phone_number HAVING COUNT(*) > 1`로 중복을 점검한다 — 있으면 제약 추가가 실패한다.
  - 잠긴 계정의 대응 창구와 `locked_at` 해제 절차를 운영에서 정한다(코드 변경 없음) — 복구 경로가 `local`·`dev`뿐이라 **prod에서는 이 절차가 유일한 해제 수단으로 남는다**(Amended #272).
  - 양쪽 완주 계정의 수동 병합 런북을 만든다.
  - `chat`은 아직 미영속(`ChatRoomRepositoryImpl`이 `UnsupportedOperationException`)이라 병합에서 다룰 것이 없다. **영속이 붙을 때 병합 대상에 채팅방이 들어가는지 다시 본다.**
  - 기존 하이픈 번호의 백필 여부는 실제 누락 사례가 나온 뒤 판단한다.

## Validation

- **양방향 통합 테스트가 이 기능의 존재 이유다** — 둘 다 통과해야 한다.
  - **앱 먼저**: 앱 임대인 온보딩 완료 → 웹 가입(연동) → 웹에서 매물 등록 → 앱에서 `GET /api/v1/bookings`(임대인 분기)에 그 매물의 신청이 보인다.
  - **웹 먼저**: 웹 가입 → 웹에서 매물 등록 → 앱 소셜 로그인(임시 계정) → 임대인 온보딩(병합) → 같은 조회가 된다.
- 연동 가입은 **`users` 행을 늘리지 않고** `users.name`·`birth_date`·`email`도 바꾸지 않는다. 폼 값은 `local_accounts`에만 들어가고 응답의 `name`·`email`은 `users`에서 나간다.
- 같은 번호에 웹 자격증명이 이미 있으면 `409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS`, 이메일만 겹치면 `409 AUTH_EMAIL_ALREADY_REGISTERED`로 **서로 다른 코드**가 나간다.
- SMS 인증 마커 없이 가입을 제출하면 `422 AUTH_PHONE_NOT_VERIFIED`이고 **`users`·`local_accounts` 어디에도 행이 생기지 않는다.**
- 온보딩 미완료 앱 계정(번호 NULL)과 세입자 계정은 매칭되지 않아 `linked=false`로 새 계정이 생긴다.
- 존재하지 않는 이메일과 비밀번호 불일치가 **같은 `401 AUTH_INVALID_CREDENTIALS`** 를 낸다(`error.details`만 갈린다 — 아래 Amended). 10회 실패 후에는 올바른 비밀번호도 `423 AUTH_ACCOUNT_LOCKED`이며, **비밀번호 재설정을 확정하면 그 계정이 다시 로그인된다**(Amended #272).
- 같은 번호로 웹 가입과 앱 온보딩을 동시에 제출하면 한쪽만 성공하고, 실패한 쪽을 재시도하면 연동·병합으로 수렴한다.
- 병합 후 앱 소셜 로그인이 항상 대상 id로 귀결되고 임시 행이 남지 않는다.
- **두 방향 모두 응답 `linked`로 결과를 알린다** — 웹 가입은 연동일 때 `true`, 앱 임대인 온보딩은 병합일 때 `true`, 일반 경로는 각각 `false`다. 세입자 온보딩은 응답 타입만 공유하므로 언제나 `false`다.
- **재검토 시점**: 하이픈 번호 누락이 실제 문의로 올라오거나, 양쪽 완주 계정의 수동 처리가 반복되면 백필·계정 연결 화면을 다시 검토한다. `chat`이 영속되면 병합 범위를 다시 본다.
