# US-1-11 — 임대인 웹 회원가입하기 (단일 폼 · 기존 앱 계정 연동)

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)
>
> 임대인 전용 웹(매물 등록 클라이언트)의 회원가입이다. 소셜이 아니라 **이메일 + 비밀번호**이며, 폼 한 페이지에서 받은 값으로 **한 트랜잭션 안에서 `ACTIVE`까지 완주**한다 — 웹에는 약관 화면도 온보딩 화면도 따로 없어 `PENDING`·`TERMS_AGREED` 같은 부분 완료 상태를 남기면 재개할 화면이 없는 죽은 계정이 된다. 그러면서도 **상태 체인과 도메인 메서드는 앱과 동일한 것을 쓴다**(`createPendingUser` → `agreeToTerms` → `completeLandlordOnboarding`) — 앱 계정과 데이터 모양이 같아야 연동이 성립하기 때문이다.
>
> **가입은 "계정 생성"이 아니라 "자격증명 추가"다.** 웹에서 등록한 매물의 예약(`booking.landlordId`)이 앱 소셜 로그인에서도 보이려면 웹 계정과 앱 계정이 **같은 `users` 행(= 같은 `user_id`)** 을 써야 한다. 그래서 인증된 휴대폰 번호로 기존 계정을 찾으면 그 `user_id`에 `local_accounts` 행만 붙이고(`linked=true`), 못 찾을 때만 새 `users` 행을 만든다(`linked=false`). 매칭 키는 **번호 단독**이며 이름은 조건이 아니다 — 소유 증명은 전적으로 [US-1-13](us-1-13-signup-phone-verification.md) SMS 인증이 담당한다.

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 웹(브라우저)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant USER as user 모듈
    participant SQL as MySQL
    participant RDS as Redis

    Note over U,C: 가입 폼은 한 페이지 — 휴대폰 인증(US-1-13)을 먼저 통과한다<br/>연동 여부와 무관하게 항상 전체 필드를 받는다(분기 없음)
    U->>C: 이름·생년월일·휴대폰 번호·이메일·비밀번호·약관 동의 입력
    C->>SEC: POST /api/v1/auth/signup<br/>{ name, birthDate, phoneNumber, email, password,<br/>termsOfServiceAgreed, privacyPolicyAgreed, marketingAgreed }
    Note over SEC: permitAll 경로 — 가입 전이라 토큰이 없다<br/>SecurityConfig 공개 티어와 PublicPaths.ALL에 함께 등록
    SEC->>AUTH: 인증 주체 없이 요청 전달
    Note over AUTH: Bean Validation — 비밀번호는 영문자·숫자·ASCII 특수문자 각 1자 이상,<br/>길이 8~20, 공백 불허. 위반 시 400 INVALID_INPUT (errors 배열에 field=password)
    Note over AUTH,SQL: 아래 MySQL 쓰기는 한 트랜잭션이다 — 어느 단계에서 실패해도 함께 롤백한다<br/>users만 생기고 local_accounts가 없는(로그인 불가) 계정도,<br/>자격증명만 뜬 계정도 남기지 않는다<br/>단 아래 Redis 두 단계는 이 보장 밖이다(각 단계 주석 참조)
    Note over AUTH: phoneNumber 정규화(숫자만 남김)
    AUTH->>RDS: signup-phone:verified:{정규화번호} 조회
    RDS-->>AUTH: 인증 마커(있음/없음)
    alt 인증 마커 없음
        AUTH-->>C: 422 AUTH_PHONE_NOT_VERIFIED
        Note over AUTH: 계정 생성도 연동도 하지 않는다<br/>이름과 번호만으로는 절대 연동되지 않는다
        C-->>U: 휴대폰 인증 안내(US-1-13)
    else 필수 약관 미동의 (이용약관·개인정보 중 하나라도 false)
        AUTH-->>C: 422 AUTH_REQUIRED_AGREEMENT_MISSING
        C-->>U: 약관 동의 안내
    else 마커·약관 게이트 통과
        AUTH->>SQL: local_accounts에서 email 중복 조회<br/>(users.email은 보지 않는다)
        SQL-->>AUTH: 중복(있음/없음)
        alt 이메일 중복
            AUTH-->>C: 409 AUTH_EMAIL_ALREADY_REGISTERED
            C-->>U: 다른 이메일 입력 안내
        else 사용 가능한 로그인 ID
            AUTH->>SQL: SELECT id FROM users<br/>WHERE phone_number = 정규화번호<br/>AND status = 'ACTIVE' AND user_type = 'LANDLORD'<br/>FOR UPDATE
            SQL-->>AUTH: 기존 계정(있음/없음)
            alt 번호 매칭됨 — 연동 경로
                AUTH->>SQL: 그 user_id의 local_accounts 행 조회
                SQL-->>AUTH: 웹 자격증명(있음/없음)
                alt 이미 웹 계정이 붙어 있음
                    AUTH-->>C: 409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS<br/>(마스킹 이메일도 싣지 않는다 — code·message만)
                    C-->>U: 로그인 화면으로
                else 붙일 자리가 비어 있음
                    AUTH->>SQL: local_accounts INSERT<br/>(user_id=기존 id, email, BCrypt password_hash,<br/>name·birth_date는 폼 스냅샷)
                    Note over AUTH,SQL: users는 한 칼럼도 바꾸지 않는다<br/>name·birthDate·email 모두 기존 값을 유지한다
                    SQL-->>AUTH: 저장 완료 → linked=true
                end
            else 번호 매칭 없음 — 신규 경로
                AUTH->>USER: 공개 명령: PENDING 회원 생성(name, email)
                USER->>SQL: users INSERT (status=PENDING, name·email)
                SQL-->>USER: 저장 완료
                USER-->>AUTH: userId(PENDING)
                AUTH->>USER: 공개 명령: 약관 동의(marketingAgreed 전달)
                USER->>SQL: PENDING→TERMS_AGREED 전이<br/>이용약관·개인정보=true, termsVersion·agreedAt 기록
                SQL-->>USER: 갱신 완료
                AUTH->>USER: 공개 명령: 임대인 온보딩 완료(정규화번호, birthDate)
                USER->>SQL: TERMS_AGREED→ACTIVE 전이 + userType=LANDLORD 확정(이후 불변)<br/>phone_number·birth_date·nickname 확정 + country='KR'·lang='ko' 고정
                SQL-->>USER: 갱신 완료
                USER-->>AUTH: user{ userType: LANDLORD, status: ACTIVE, nickname }
                AUTH->>SQL: local_accounts INSERT (user_id=새 id, email, BCrypt password_hash, name·birth_date)
                SQL-->>AUTH: 저장 완료 → linked=false
            end
            Note over AUTH: 정식 accessToken+refreshToken 발급<br/>(issueFullTokens — 앱과 같은 메서드, 규칙을 두 벌로 만들지 않는다)
            AUTH->>RDS: refreshToken 해시 저장(14일 TTL — 앱과 동일)<br/>Redis라 롤백되지 않는다 — 커밋 시점에 실패하면 해시만 남는다(알려진 제약)
            RDS-->>AUTH: 저장 완료
            AUTH->>RDS: signup-phone:verified:{정규화번호} 삭제(마커 소비)<br/>커밋 이후에 실행된다(afterCommit) — 롤백된 가입이 마커를 태우지 않게 한다
            RDS-->>AUTH: 삭제 완료
            AUTH-->>C: 200 OK<br/>Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Lax;<br/>Path=/api/v1/auth; Max-Age=1209600<br/>{ linked, onboardingRequired: false, status: ACTIVE,<br/>tokenType: Bearer, accessToken, expiresIn: 3600, email, name }
            C-->>U: 가입 완료, 매물 등록 진입
        end
    end
```

## 흐름 요약

- **`POST /api/v1/auth/signup`은 permitAll이다.** 가입 전이라 토큰이 없으므로 `SecurityConfig` 공개 티어와 [`PublicPaths.ALL`](../../../../src/main/java/com/kohere/common/security/PublicPaths.java)에 **함께** 등록한다(한쪽만 등록하면 만료 토큰을 든 브라우저가 가입에서 401을 맞는다). 필터는 주체를 세우지 않고 그대로 통과시킨다.
- **본문 검증은 게이트보다 먼저, Bean Validation이 끝낸다.** 비밀번호는 영문자·숫자·ASCII 특수문자 각 1자 이상 · 길이 8~20 · 공백 불허이며 위반 시 `400 INVALID_INPUT`(`errors[].field=password`)이고, `name`·`phoneNumber` 누락과 `email`·`birthDate` 형식 위반도 같은 `400`이다(부수효과 없음). 검증을 통과한 뒤의 게이트·상태 전이·INSERT는 **전부 한 트랜잭션**이라 어느 단계에서 실패해도 전체 롤백한다 — `users`만 생기고 `local_accounts`가 없는(로그인 불가) 계정도, 자격증명만 뜬 계정도 남기지 않는다.
- **게이트 순서는 인증 마커 → 약관 → 이메일 중복 → 번호 매칭이다.** 정규화한 번호로 Redis 마커(`signup-phone:verified:{정규화번호}`)를 먼저 확인해 없으면 `422 AUTH_PHONE_NOT_VERIFIED`로 끊고 **계정 생성도 연동도 하지 않는다** — 번호는 *조회 키*이지 *인증 수단*이 아니며, 소유 증명은 [US-1-13](us-1-13-signup-phone-verification.md)이 전담한다. 이어 필수 약관 2종(`termsOfServiceAgreed`·`privacyPolicyAgreed`)이 모두 `true`가 아니면 `422 AUTH_REQUIRED_AGREEMENT_MISSING`이다(`marketingAgreed`는 선택).
- **이메일 중복은 `local_accounts.email`만 본다.** 이메일은 **연동 키가 아니라 웹 로그인 ID**여서 유일해야 하지만, `users.email`은 소셜 provider 진본이고 로그인 판정에 쓰이지 않는다(소셜은 `(provider, provider_user_id)`로 판정한다). `users.email`까지 검사하면 **본인이 본인 소셜 이메일로 가입하려다 409**를 맞는 가장 흔한 정상 경로가 막힌다. `users.email`에 UNIQUE를 걸지 않는 것도 같은 이유다. 그 반대급부로 **남의 소셜 이메일과 같은 주소로 웹 가입하는 것은 막히지 않으며**(`users.email`이 같은 사용자가 둘 생길 수 있다) 이는 수용한 제약이다 — 매물·예약은 `user_id`로 갈려 섞이지 않고, 이메일은 웹 로그인 ID일 뿐 계정 복구 수단이 아니라 탈취 경로가 되지 않는다.
- **연동 판정은 `SELECT ... FOR UPDATE`로 대상 행을 잠근 뒤 수행한다.** 조건은 정규화 번호 + `status='ACTIVE'` + `user_type='LANDLORD'`이며, 뒤 두 조건은 지금은 중복이지만(번호가 채워진 계정은 사실상 `ACTIVE` 임대인뿐이다) **암묵 불변식에 기대지 않기 위해 명시**한다.
- **`linked=true`(성공)와 `409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS`는 같은 조회의 서로 다른 가지다.** 번호로 기존 계정을 찾은 것까지는 같고, **그 계정에 웹 자격증명이 이미 붙어 있는지**에서 갈린다 — 앱만 쓰던 사람이 웹에 처음 가입하면 붙일 자리가 비어 있어 연동 성공이고, 웹 계정이 있는 사람이 또 가입하면 자리가 이미 차 있어 409다. 이 경우 남은 동작은 기존 자격증명을 **덮어쓰는 것**뿐인데 그건 가입이 아니라 자격증명 교체이고, 로그인 ID까지 조용히 바뀌는 놀라운 동작이라 하지 않는다. 응답에는 **마스킹 이메일도 싣지 않고** 공통 에러 스키마(`code`·`message`)만 내려 번호 소유자에게 남의 이메일 일부가 새지 않게 한다.
- **연동 경로는 `users`를 한 칼럼도 건드리지 않는다.** 폼의 `name`·`birthDate`는 `local_accounts`에 **스냅샷으로만** 저장하고(`social_accounts.name`과 같은 취급), 폼 `email`도 `local_accounts.email`에만 들어간다. 기존 값은 온보딩을 마친 확정 값이고 폼 값은 방금 입력한 미검증 값이라, 덮어쓰면 "가입했더니 내 프로필이 바뀌었다"가 된다. 이름이 달라도 **연동을 막지 않는다** — SMS 인증이 이미 번호 소유를 증명했으므로 이름이 보안에 기여하는 바가 없고, 앱 이름(`Kim Imdae`)과 웹 이름(`김임대`)의 불일치는 자연스럽다.
- **표시 규칙: 응답의 `email`·`name`은 언제나 `users`의 값이다.** 그래서 연동된 계정은 응답에 **소셜 진본 이메일**이 나갈 수 있으며, 이것은 의도된 동작이다(신규 가입 경로에서는 폼 값이 `users`에도 들어가므로 두 값이 100% 같다).
- **신규 경로는 앱과 같은 도메인 메서드를 순서대로 호출한다.** `createPendingUser(name, email)` → `agreeToTerms(userId, marketingAgreed)` → `completeLandlordOnboarding(userId, {정규화번호, birthDate})`. `user` 모듈에 새 생성 메서드를 만들지 않으며, 세 호출이 `@Transactional(REQUIRED)` 전파로 호출자 트랜잭션에 참여해 원자성이 성립한다. 서버 고정값(`country='KR'`·`lang='ko'`·닉네임 자동 생성·`userType=LANDLORD`)도 앱과 동일하다. **번호를 `users.phone_number`에 기록하는 것이 중요하다** — 그래야 반대 방향([US-1-15](us-1-15-landlord-account-merge.md))에서 이 계정이 매칭 후보가 된다.
- **토큰은 `issueFullTokens`를 그대로 쓰고 refresh만 채널이 다르다.** access는 응답 본문, refresh는 **`Set-Cookie`(HttpOnly·Secure·`SameSite=Lax`·`Path=/api/v1/auth`·`Max-Age=1209600`)** 로만 내려가며 **응답 본문에 `refreshToken` 필드가 없다**. TTL은 앱과 동일한 14일이다([ADR-0006](../../../adr/0006-refresh-token-store-redis.md)의 Redis 해시 저장·회전 규칙은 그대로다). 마지막으로 인증 마커를 삭제해 재사용을 막는다.
- **동시성의 최종 방어선은 DB 제약이다.** 같은 번호로 웹 가입과 앱 임대인 온보딩이 거의 동시에 도착하면 둘 다 "기존 계정 없음"으로 판정해 계정이 갈라질 수 있다(check-then-act). 이를 막는 유일한 수단이 **`users.phone_number` UNIQUE**이며, 늦은 쪽이 제약 위반으로 실패하고 재시도하면 상대가 만든 계정을 발견해 정상 연동·병합된다. 애플리케이션 조회만으로는 막을 수 없다(아직 없는 행은 잠글 수 없다). 실패한 요청의 응답은 **`409 RESOURCE_CONFLICT`** 다 — 전역 예외 핸들러가 제약 위반을 번역하며(종전 `500 INTERNAL_ERROR`), 클라이언트에게 "그대로 다시 보내면 된다"를 알리는 것이 목적이다([US-1-15](us-1-15-landlord-account-merge.md) 흐름 요약).
- **제약 — 롤백된 가입의 refresh 해시가 Redis에 남는다.** "가입 전체가 한 트랜잭션"은 **MySQL 쓰기에만** 걸린다. `issueFullTokens`는 트랜잭션 안에서 불리지만 refresh 해시를 Redis에 남기고, Redis는 커밋과 함께 롤백되지 않는다 — 바로 위 UNIQUE 경합처럼 **커밋 시점**에 터지는 실패는 이미 토큰이 발급된 뒤라, `users`·`local_accounts`는 깨끗이 되돌아가는데 해시 하나가 **14일 TTL로 살아남는다**. 원문은 409 응답으로 대체돼 클라이언트에 닿지 않으므로 세션을 열 수 없고(악용 불가) 항목은 스스로 사라지지만, 그 사이 `refresh:user:{id}` 인덱스가 실제 세션보다 많아 보인다. 없애려면 토큰 발급을 트랜잭션 밖으로 들어내야 해서 문제 크기에 비해 변경이 크다 — **수용한 한계**다(같은 모양의 제약이 [US-1-15](us-1-15-landlord-account-merge.md)에도 있다). 반대로 SMS 인증 마커 삭제는 같은 이유로 **커밋 이후**에 미뤄 두었다 — 그쪽 잔여물은 사용자를 막으므로 순서를 지킬 값이 있었다.
- **제약 — 번호 정규화 백필이 없다.** 정규화는 입력 경로에서만 수행하고 기존 데이터는 손대지 않으므로, `users.phone_number`에 하이픈을 포함해 저장된 기존 임대인 행은 매칭에서 누락돼 **연동되지 않고 새 계정이 생길 수 있다**(`users.phone_number` UNIQUE는 그대로 추가한다).
- **제약 — 번호가 NULL인 계정은 매칭 후보에 들어오지 않는다.** 소셜 로그인만 하고 임대인 온보딩을 마치지 않은 앱 계정은 `phone_number`가 비어 있어 웹이 새 계정으로 가입되지만, 그 앱 계정이 나중에 온보딩을 마칠 때 [US-1-15](us-1-15-landlord-account-merge.md)가 병합해 최종적으로 하나로 수렴한다(정상 동작). 반면 **세입자**는 정의상 `phone_number`를 채우지 않아 **역할 검사 분기를 두지 않아도 구조적으로 제외**되며, 앱에서 세입자로 가입한 사람이 웹에서 임대인 가입을 하면 별개 계정이 생기고 서버는 두 계정이 동일인인지 알 방법이 없어 안내도 하지 않는다.
- **제약 — 앱·웹 양쪽에 같은 번호의 완주 계정이 각각 있으면 자동 병합하지 않는다.** 양쪽 모두 매물·예약을 보유했을 수 있어 데이터 이관 판단이 필요하고 트리거도 없다 — 운영 수동 처리 대상이다.

## 가입 제출의 분기

| 판정 | 조건 | 결과 |
| --- | --- | --- |
| **필드 검증** | 비밀번호 정책 위반, 필수 필드 누락·형식 오류 | `400 INVALID_INPUT` (`errors[]`에 field·reason) — 아무것도 쓰지 않는다 |
| **인증 마커** | `signup-phone:verified:{정규화번호}` 없음 | `422 AUTH_PHONE_NOT_VERIFIED` — 아무것도 쓰지 않는다 |
| **약관** | 필수 2종 중 하나라도 `false` | `422 AUTH_REQUIRED_AGREEMENT_MISSING` |
| **로그인 ID** | `local_accounts.email` 중복 | `409 AUTH_EMAIL_ALREADY_REGISTERED` |
| **번호 매칭 O · 웹 계정 O** | 매칭된 `user_id`에 `local_accounts` 행 존재 | `409 AUTH_WEB_ACCOUNT_ALREADY_EXISTS` — 로그인 화면으로 |
| **번호 매칭 O · 웹 계정 X** | 붙일 자리가 비어 있음 | `200` · `linked=true` — `local_accounts`만 INSERT, `users` 미변경 |
| **번호 매칭 X** | 그 번호를 가진 `ACTIVE`·`LANDLORD` 계정 없음 | `200` · `linked=false` — `users` 신규 생성(PENDING→TERMS_AGREED→ACTIVE) + `local_accounts` |

어느 게이트에서 끊기든 그 시점까지의 쓰기는 전부 롤백되며, 매칭된 계정의 기존 자격증명을 덮어쓰는 경로는 없다.
