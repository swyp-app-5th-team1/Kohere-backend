# US-1-12 — 임대인 웹 로그인하기 (계정 잠금 · refresh 쿠키 재발급)

> 모듈: 소셜 로그인 · 온보딩 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/01-auth-onboarding.md)
>
> 웹 계정([US-1-11](us-1-11-web-signup.md))을 만든 임대인의 이메일·비밀번호 로그인이다. 기존 `/auth/social-login`에 `provider=LOCAL`을 끼워넣지 않고 **`POST /api/v1/auth/login`을 신설**한다 — `SocialLoginRequest`가 이미 provider별 조건부 자격 필드로 복잡해 `password`까지 섞으면 검증 분기가 3중이 된다. 반대로 **토큰 발급·회전·재사용 탐지는 기존 로직을 그대로 재사용**하고([US-1-3](us-1-3-token-reissue.md)) 규칙을 두 벌로 만들지 않는다.
>
> 앱과 다른 것은 **refresh의 보관 채널 하나뿐**이다. 브라우저는 localStorage에 refresh를 두면 XSS 한 번에 통째로 털리므로 **`Set-Cookie`(HttpOnly·Secure·`SameSite=Lax`·`Path=/api/v1/auth`)** 로 내리고, `reissue`·`logout`은 **쿠키 우선 · 요청 본문 fallback**으로 읽는다. 앱은 기존 본문 방식이 그대로 동작하므로 **하위 호환이 깨지지 않아 v2가 필요 없다**. 웹 계정은 가입이 한 트랜잭션으로 `ACTIVE`까지 완주하므로 **온보딩 재개 분기가 존재하지 않는다** — `onboardingRequired`는 항상 `false`, `status`는 항상 `ACTIVE`다.

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 웹(브라우저)
    participant SEC as 공통 보안 필터
    participant AUTH as auth 모듈
    participant SQL as MySQL
    participant RDS as Redis

    U->>C: 이메일·비밀번호 입력 후 "로그인"
    C->>SEC: POST /api/v1/auth/login<br/>{ email, password }
    Note over SEC: permitAll 경로 — 로그인 전이라 토큰이 없다<br/>SecurityConfig 공개 티어와 PublicPaths.ALL에 함께 등록
    SEC->>AUTH: 인증 주체 없이 요청 전달
    AUTH->>RDS: 시도 카운터 INCR<br/>web-login:rate:ip:{IP} · web-login:rate:email:{소문자 이메일}
    RDS-->>AUTH: 1시간 창의 누적 시도 수
    alt IP 60회/시간 또는 이메일 20회/시간 초과
        Note over AUTH: 조회·해시 이전에 끊는다 — 뒤로 밀면 막힌 요청도<br/>BCrypt 비용을 이미 치른 뒤라 증폭을 막지 못한다
        AUTH-->>C: 429 TOO_MANY_REQUESTS
        C-->>U: 잠시 후 다시 시도 안내
    end
    AUTH->>SQL: local_accounts를 email로 조회
    SQL-->>AUTH: 웹 자격증명(있음/없음)
    alt 이메일에 해당하는 행 없음
        Note over AUTH: code·status·문구는 비밀번호 불일치와 같다<br/>단 error.details는 싣지 않는다(올릴 카운터가 없다)
        AUTH-->>C: 401 AUTH_INVALID_CREDENTIALS
        C-->>U: 이메일 또는 비밀번호 오류 안내
    else locked_at이 채워져 있음 (잠금)
        Note over AUTH: 잠금 판정이 비밀번호 대조보다 먼저다<br/>비밀번호가 맞아도 423이 이긴다
        AUTH-->>C: 423 AUTH_ACCOUNT_LOCKED
        C-->>U: 잠금 안내 → 비밀번호 재설정으로 해제 유도(US-1-17)<br/>(시간 경과 자동 해제는 없다 — 본인이 재설정해야 풀린다)
    else 잠금 아님 · BCrypt 대조 불일치
        AUTH->>SQL: failed_login_attempts += 1<br/>10회째면 locked_at = now()를 함께 기록(잠금 확정)
        SQL-->>AUTH: 갱신 완료
        AUTH-->>C: 401 AUTH_INVALID_CREDENTIALS<br/>error.details = { failedAttempts, maxFailedAttempts }<br/>(10회째 응답도 401이며, 그 다음 요청부터 423)
        C-->>U: 이메일 또는 비밀번호 오류 안내
    else 잠금 아님 · BCrypt 대조 일치
        AUTH->>SQL: failed_login_attempts = 0 리셋
        SQL-->>AUTH: 갱신 완료
        Note over AUTH: 정식 accessToken+refreshToken 발급(issueFullTokens)
        AUTH->>RDS: refreshToken 해시 저장(14일 TTL — 앱과 동일)
        RDS-->>AUTH: 저장 완료
        AUTH-->>C: 200 OK<br/>Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Lax;<br/>Path=/api/v1/auth; Max-Age=1209600<br/>{ onboardingRequired: false, status: ACTIVE,<br/>tokenType: Bearer, accessToken, expiresIn: 3600, email, name }
        C-->>U: 매물 관리 화면
    end

    Note over U,C: access 토큰 만료 — 브라우저가 refresh 쿠키를 자동으로 첨부한다
    C->>SEC: POST /api/v1/auth/reissue<br/>Cookie: refreshToken=... (요청 본문 없음)
    Note over SEC: PublicPaths 경로 — 만료된 access 토큰이 함께 실려 와도<br/>401 TOKEN_EXPIRED로 끊지 않는다
    SEC->>AUTH: 요청 전달
    Note over AUTH: refresh를 쿠키 우선 · 본문 fallback으로 읽는다<br/>둘 다 없거나 공백이면 400 INVALID_INPUT (errors 배열에 field=refreshToken)<br/>깨진 JSON 본문은 기존대로 400 MALFORMED_REQUEST
    AUTH->>RDS: refreshToken 해시 조회·상태 확인
    RDS-->>AUTH: 유효·무효
    alt 유효 (ACTIVE · 미만료)
        AUTH->>RDS: 제출 refresh를 ROTATED 전이(보존) + 새 ACTIVE refresh 저장
        RDS-->>AUTH: 갱신 완료
        Note over AUTH: 쿠키로 온 요청은 쿠키로 돌려준다<br/>본문으로 온 요청(앱)은 기존대로 본문에 담는다
        AUTH-->>C: 200 OK<br/>Set-Cookie: 회전된 refreshToken (동일 속성)<br/>{ tokenType: Bearer, accessToken, expiresIn: 3600 }
        C-->>U: 끊김 없이 기능 제공
    else 위조·만료·무효화(REVOKED)·재사용 탐지(ROTATED)
        Note over AUTH: 판정과 부수효과는 US-1-3과 동일하다<br/>재사용 탐지(ROTATED)만 사용자 refresh를 일괄 무효화한다
        AUTH-->>C: 401 AUTH_INVALID_REFRESH_TOKEN
        C-->>U: 재로그인 유도
    end

    U->>C: 로그아웃 선택
    C->>SEC: POST /api/v1/auth/logout<br/>Authorization: Bearer accessToken<br/>Cookie: refreshToken=... (요청 본문 없음)
    Note over SEC: 로그아웃은 정식 인증(ROLE_USER) 경로 — access 토큰이 필요하다
    SEC->>AUTH: 인증된 요청 전달 (userId)
    AUTH->>RDS: 쿠키에서 읽은 refreshToken 무효화(REVOKED · 이미 무효화면 멱등)
    RDS-->>AUTH: 무효화 완료
    AUTH-->>C: 204 No Content<br/>Set-Cookie: refreshToken=; Max-Age=0 (삭제 쿠키)
    C-->>U: 세션 종료, 로그인 화면
```

## 흐름 요약

- **`POST /api/v1/auth/login`은 permitAll이다.** `SecurityConfig` 공개 티어와 [`PublicPaths.ALL`](../../../../src/main/java/com/kohere/common/security/PublicPaths.java)에 **함께** 등록한다 — 한쪽만 등록하면 만료된 access 토큰이 남아 있는 브라우저가 로그인 화면에서 `401 TOKEN_EXPIRED`를 맞는다. 조회는 `local_accounts.email`로 하며, 이메일이 유일해야 계정을 특정할 수 있어 가입 시 중복을 막는다(US-1-11).
- **레이트리밋이 가장 먼저다.** 자격증명 조회·BCrypt 대조보다 **앞에서** IP 60회/시간·이메일 20회/시간을 세고(`app.auth.web.login.*`), 어느 한쪽이라도 넘으면 `429 TOO_MANY_REQUESTS`다. 막는 대상이 둘이다 — ① 남의 이메일로 10회 틀려 잠그는 DoS ② `permitAll`이라 **선행 조건 없이** BCrypt 한 라운드를 강제할 수 있는 CPU 증폭(가입은 SMS 인증 마커 게이트 뒤에 있고 그 마커 발급도 이미 한도에 걸린다). 순서를 뒤로 미루면 ②가 그대로 열린다. 카운터는 가입용 SMS 한도와 같은 관용구(Redis 고정 창 `INCR` + 첫 증가에만 `EXPIRE`)이며, **IP 축은 `X-Forwarded-For`가 호출자 손에 있어 위조 가능**하므로 비용 가드일 뿐이고 잠금 DoS를 실제로 묶는 것은 **이메일 축**이다(잠글 대상을 지정하려면 그 이메일을 보내야 한다).
- **이메일 없음과 비밀번호 불일치는 완전히 같은 응답이다** — 둘 다 `401 AUTH_INVALID_CREDENTIALS`. 응답을 구분하면 그 자체가 "이 이메일은 가입돼 있다"는 계정 열거 신호가 된다.
- **잠금 판정이 비밀번호 대조보다 먼저다.** `locked_at`이 채워져 있으면 **제출한 비밀번호가 맞아도** `423 AUTH_ACCOUNT_LOCKED`를 반환한다. 순서를 뒤집으면 잠금이 사실상 무력해진다.
- **실패 카운터는 `local_accounts`의 컬럼에 둔다.** 비밀번호 대조에 실패하면 `failed_login_attempts`를 올리고 **10회째 실패에서 `locked_at`을 함께 기록**해 잠금을 확정한다. 그 10회째 응답 자체는 여전히 `401`이고, **그 다음 요청부터 `423`** 이 나간다. 로그인에 성공하면 카운터를 **0으로 리셋**한다(성공은 잠금 전에만 가능하므로 이 경로로 `locked_at`이 되돌아가지는 않는다 — `locked_at`을 비우는 것은 [US-1-17](us-1-17-password-reset.md)의 재설정 확정과 운영자 수동 해제뿐이다). 리셋 경로가 하나 더 있다 — **잠기지 않았는데 카운터가 이미 상한 이상**인 계정(= 운영자가 방금 `locked_at`을 비운 계정)은 다음 실패를 `1`부터 다시 센다. 그래야 "`locked_at`을 비운다"는 수동 해제 절차가 그것만으로 완결된다(재설정은 `locked_at`과 카운터를 **같은 UPDATE에서 함께** 비우므로 이 보정에 기대지 않는다). 카운터를 Redis TTL로 두면 안 되는 이유는 두 가지다 — ① 만료와 함께 **잠금이 저절로 풀려** "해제는 본인이 재설정으로 한다"는 정책이 무너지고(시간 경과 자동 해제는 만들지 않는다), ② **언제 잠겼고 언제 풀렸는지가 행에 남지 않아** 사후에 확인할 방법이 사라진다. 그래서 MySQL 컬럼이어야 한다. `V22` 마이그레이션 주석에는 예전 근거인 「해제 기능이 없어서」가 그대로 적혀 있는데, 이미 적용된 마이그레이션은 체크섬 때문에 문구를 고칠 수 없어 **문서 쪽만 갱신했다**.
- **웹 로그인에는 온보딩 재개 분기가 없다.** 웹 가입은 한 트랜잭션으로 `ACTIVE`까지 완주하므로 `PENDING`·`TERMS_AGREED` 상태로 로그인하는 경로가 존재하지 않는다 — `onboardingRequired`는 항상 `false`, `status`는 항상 `"ACTIVE"`다. 응답의 `email`·`name`은 US-1-11과 같은 표시 규칙에 따라 **`users`의 값**이다.
- **refresh는 응답 본문에 없다.** access만 본문으로 내리고 refresh는 `Set-Cookie`로만 전달한다(`HttpOnly`·`Secure`·`SameSite=Lax`·`Path=/api/v1/auth`·`Max-Age=1209600`). `Path`를 `/api/v1/auth`로 좁혀 매물·예약 같은 일반 API 요청에는 쿠키가 아예 실리지 않게 한다. TTL은 앱과 동일한 14일이며 서버 저장은 기존 Redis 해시 방식 그대로다([ADR-0006](../../../adr/0006-refresh-token-store-redis.md)). `Secure=true`가 기본값이고 http로 도는 `local` 프로파일에서만 `false`로 내린다.
- **재발급은 왕복 전체가 무본문이다.** access가 만료되면 브라우저가 `POST /api/v1/auth/reissue`를 호출하는데 **본문을 싣지 않아도 쿠키가 자동 첨부**된다. 서버는 **쿠키 우선 · 본문 fallback**으로 refresh를 읽고, 둘 다 없거나 공백이면 `400 INVALID_INPUT`(`errors[].field=refreshToken`)을 반환한다 — 깨진 JSON 본문은 종전대로 `400 MALFORMED_REQUEST`다. 회전·재사용 탐지 판정은 [US-1-3](us-1-3-token-reissue.md)과 완전히 동일하며, **응답 채널만 요청이 온 채널을 따른다** — 쿠키로 왔으면 회전된 refresh를 다시 `Set-Cookie`로, 본문으로 왔으면 기존대로 본문에 담아 앱 하위 호환을 유지한다.
- **로그아웃은 access 토큰이 필요하다.** `/api/v1/auth/logout`은 공개 티어가 아니라 정식 인증(`ROLE_USER`) 경로라 `Authorization: Bearer accessToken`을 함께 보내야 한다. 서버는 쿠키에서 읽은 refresh를 `REVOKED`로 무효화하고(이미 무효화면 멱등) `204 No Content`와 함께 **`Max-Age=0` 삭제 쿠키**를 내려 브라우저에 남은 refresh까지 제거한다.

## 실패 응답 정리

| 경우 | 응답 | `error.details` | 부수효과 |
| --- | --- | --- | --- |
| 같은 IP 60회/시간 또는 같은 이메일 20회/시간 초과 | `429 TOO_MANY_REQUESTS` | **없음** | 없음 — **자격증명 조회·해시 대조 전에** 끊는다 |
| 이메일에 해당하는 `local_accounts` 행 없음 | `401 AUTH_INVALID_CREDENTIALS` | **없음** | 없음(올릴 카운터가 없다) |
| 비밀번호 불일치(1~9회째) | `401 AUTH_INVALID_CREDENTIALS` | `{ failedAttempts: 1~9, maxFailedAttempts: 10 }` | `failed_login_attempts += 1` |
| 비밀번호 불일치(10회째) | `401 AUTH_INVALID_CREDENTIALS` | `{ failedAttempts: 10, maxFailedAttempts: 10 }` | `failed_login_attempts=10` + **`locked_at=now()`** |
| 잠긴 계정 — 비밀번호 오답 | `423 AUTH_ACCOUNT_LOCKED` | **없음** | 없음(대조를 하지 않는다) |
| 잠긴 계정 — **비밀번호 정답** | `423 AUTH_ACCOUNT_LOCKED` | **없음** | 없음 — **잠금이 우선한다** |
| `reissue`·`logout`에 쿠키·본문 refresh가 모두 없음 | `400 INVALID_INPUT` (`errors[].field=refreshToken`) | **없음** | 없음 |
| `reissue`·`logout` 본문 JSON이 깨짐 | `400 MALFORMED_REQUEST` | **없음** | 없음(종전과 동일) |
| refresh 위조·만료·REVOKED | `401 AUTH_INVALID_REFRESH_TOKEN` | **없음** | 없음(다른 세션 보존) |
| refresh 재사용 탐지(ROTATED) | `401 AUTH_INVALID_REFRESH_TOKEN` | **없음** | **사용자 refresh 일괄 무효화**([US-1-3](us-1-3-token-reissue.md)) |

> **`error.details` 열이 곧 계정 열거 표면이다** — 「없음」이 아닌 행은 단 둘이고 둘 다 **등록된 계정**의 비밀번호 불일치다. 그래서 아무 비밀번호나 한 번 넣어 보면 그 이메일의 가입 여부가 드러난다(알려진 제약).
>
> **`423` 두 행의 사용자 안내는 「운영 문의」가 아니라 비밀번호 재설정이다** — 잠금은 [US-1-17](us-1-17-password-reset.md)의 재설정 확정이 `locked_at`을 비우면서 풀린다(잠금 해제 전용 API는 없다). 다만 그 경로는 아직 `local`·`dev` 한정이라 prod에서는 운영자 수동 해제가 남아 있다(아래 「알려진 제약」).

## 알려진 제약

- **잠금 해제는 본인이 비밀번호 재설정으로 한다 — 경로는 그것 하나뿐이다.** 시간 경과 자동 해제도, 잠금 해제 전용 API도, 전용 화면도 만들지 않는다(진입 화면은 둘이어도 API는 하나다). 해제의 실체는 [US-1-17](us-1-17-password-reset.md)의 재설정 확정이 `password_hash` 교체와 함께 `failed_login_attempts = 0`·`locked_at = NULL`을 **같은 UPDATE에서** 처리하는 것이다 — 비밀번호를 모르는 채 잠금만 풀어 주면 곧바로 10회를 다시 틀려 잠기므로, 해제와 재설정을 가르는 것은 사용자에게 아무 의미가 없다. **다만 그 경로는 아직 `local`·`dev`에만 배포한다** — prod의 잠긴 계정을 푸는 유일한 수단은 여전히 **운영자가 DB에서 `locked_at`을 비우는 것**이고(그 한 줄이 완전한 해제가 되도록 코드가 맞춰져 있다 — 위 실패 카운터 항목), 잠금 발생 시 임대인이 연락할 창구와 처리 절차를 운영에서 정해두어야 한다. 재설정이 prod에 배포된 뒤에도 **가입 메일함을 열 수 없는 임대인**에게는 이 수동 절차가 남는다.
- **의도적 계정 잠금(DoS)을 완전히 막을 수 없다.** 남의 이메일로 10회 틀리면 그 사람 계정을 잠글 수 있다. 잠금 정책의 고전적 부작용이며, 시도 레이트리밋(IP 60회/시간·이메일 20회/시간)으로 **시간당 잠글 수 있는 계정 수를 묶을** 뿐 원천 차단은 불가능하다 — 이메일 축이 위조 불가라 한 이메일당 시간당 20회가 상한이지만, 10회면 잠기므로 여전히 시간당 한 계정은 잠글 수 있다. 이 리스크를 수용하고 진행한다.
- **계정 열거를 수용한다.** `error.details`가 등록된 계정의 비밀번호 불일치에만 실리므로, 임의의 비밀번호로 한 번 호출하면 그 이메일의 가입 여부를 알 수 있다. **해제 경로가 생긴 뒤에도 이 필드는 그대로 둔다** — 목적이 "풀 방법이 없으니 미리 알려준다"가 아니라 **잠기기 전에 남은 시도를 알려 주는 사전 안내**이고, 복구 수단이 있든 없든 아홉 번째에 멈추는 편이 잠긴 뒤 메일함을 오가는 것보다 낫기 때문이다. 잠긴 계정의 `423`이 이미 존재를 드러내고 있다는 점까지 포함해 **계정 열거 수용을 그대로 재확인한다.** 레이트리밋은 이메일 축이 이메일 단위라 열거를 막지 못하고 IP 축은 위조 가능하므로 완화책으로 셈하지 않는다.
- **동일 오리진 배치에 인프라 근거가 아직 없다.** CSRF 방어를 `SameSite=Lax`에 맡기고 `csrf.disable()`을 유지하는 것은 **웹과 API가 같은 오리진에 배포된다는 전제** 위에 서 있는데, `docker-compose.yml`·리버스 프록시 설정 어디에도 웹 클라이언트 서비스가 없다. 다른 호스트로 배포되면 CORS 설정이 필요해지고 **쿠키 refresh가 CSRF 구멍이 된다** — 배포 형태가 확정되기 전에는 이 전제가 검증되지 않은 상태로 남는다.
