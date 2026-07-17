# US-8-1 — 생활 팁 주제 목록 조회 (국가 기반 번역)

> 모듈: 생활 팁 (주제별 생활 정보) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/08-life-tips.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant TIP as lifetip 모듈
    participant USER as user 모듈
    participant DB as MongoDB

    U->>C: 홈에서 생활 팁 진입
    C->>SEC: GET /api/v1/life-tips/topics<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)

    alt 인증 누락/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED<br/>(만료 시 TOKEN_EXPIRED)
        C-->>U: 재로그인 유도
    else 온보딩 스코프 토큰(ROLE_ONBOARDING, PENDING/TERMS_AGREED)
        Note over SEC: 정식 인증(ROLE_USER=ACTIVE) 티어 미충족<br/>(AccessDeniedHandler, 모듈 도달 전)
        SEC-->>C: 403 AUTH_ONBOARDING_REQUIRED
        C-->>U: 온보딩 완료 유도
    else 정식 인증(ROLE_USER = ACTIVE 세입자)
        SEC->>TIP: 인증된 요청 전달 (userId)
        Note over TIP: 번역 언어 결정을 위해 표시 언어 필요<br/>(JWT 클레임 비의존 — 항상 user 공개 query로 취득)
        TIP->>USER: user 공개 query 동기 호출 getLanguage(userId)<br/>(표시 언어 조회 — user가 countries.lang으로 도출, ADR-0002 Decision 5)
        USER-->>TIP: 표시 언어 lang
        TIP->>DB: lifeTipTopics 전체 조회(order 오름차순)
        DB-->>TIP: 주제 카탈로그(각 _id=code, name·shortDescription·longDescription 언어-키 맵,<br/>imageUrl·backgroundImageUrl, order)
        Note over TIP: 주제 수는 고정·소규모 → 페이지네이션 없이 전체 배열<br/>(api-design-guide §4 목록 규약 미적용, US-7-3과 동일 성격)
        loop 각 주제(노출 순서 order)
            alt 도큐먼트에 그 언어 키 존재
                Note over TIP: name·shortDescription·longDescription 언어-키 맵에서 그 언어 값 선택<br/>name=name[lang], shortDescription=shortDescription[lang], longDescription=longDescription[lang]<br/>(code·imageUrl·backgroundImageUrl은 언어 무관·불변)
            else 미지원 언어
                Note over TIP: name·shortDescription·longDescription을 영어(en)로 폴백<br/>(에러 아님 — 기본 언어=영어)
            end
            Note over TIP: imageUrl·backgroundImageUrl은 그대로 실음 (언어 무관, 4필드 모두 필수)
        end
        TIP-->>C: 200 OK<br/>data.topics[]: { code(UPPER_SNAKE), name(번역),<br/>shortDescription(번역), longDescription(번역),<br/>imageUrl, backgroundImageUrl }<br/>(노출 순서대로, 페이지 객체 없음)
        C-->>U: 주제 목록 표시(내 언어)
    end
```

## 흐름 요약

- 홈 진입점에서 `GET /api/v1/life-tips/topics`로 `lifetip 모듈`이 생활 팁 주제 전체를 노출 순서대로 반환한다. 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달한다. 이 엔드포인트는 **정식 인증(ROLE_USER = ACTIVE 세입자)** 티어다 — 온보딩 미완료(PENDING/TERMS_AGREED, ROLE_ONBOARDING) 토큰은 SEC(AccessDeniedHandler)가 모듈 도달 전에 `403 AUTH_ONBOARDING_REQUIRED`, 인증 누락/만료/위조는 EntryPoint가 `401 UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)로 처리한다(진단 보호 엔드포인트와 동일 게이트). 등록 국가→언어를 도출하려면 온보딩으로 국가·언어가 확정된 ACTIVE 세입자여야 하므로 대상은 ROLE_USER=ACTIVE로 한정한다.
- 번역 언어 결정을 위한 표시 언어는 `user`가 보유한다(`countries.lang`). `lifetip` 모듈은 JWT 클레임에 의존하지 않고 **항상 `user`의 공개 query(`getLanguage`)를 동기 호출**해 표시 언어(`lang`)를 취득한다(즉시 결과가 필요한 조회 → ADR-0002 Decision 5). 이를 위해 모듈 의존 `lifetip → user`를 추가한다(`Accept-Language` 헤더·토큰 클레임 미사용).
- 모듈은 **MongoDB `lifeTipTopics` 컬렉션**에서 주제 전체를 `order` 오름차순으로 조회한다. 각 주제는 언어 무관 식별 `code`(`_id`, UPPER_SNAKE)와 노출 순서 `order`, 표시 텍스트 `name`·`shortDescription`·`longDescription`(각각 인라인 언어-키 맵), 언어 무관 불변 이미지 `imageUrl`·`backgroundImageUrl`(절대 CDN URL 문자열)을 갖는다. 서버가 사용자 언어 값(`name[lang]`·`shortDescription[lang]`·`longDescription[lang]`)으로 표시 텍스트를 채우고, 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). `imageUrl`·`backgroundImageUrl`은 언어 선택(`pickLabel`)을 타지 않고 그대로 싣는다(주제의 4필드는 모두 필수·NOT NULL이라 "이미지 없는 주제" 경계 케이스가 없다 — 사진 유무로 nullable인 `LifeTip.imageUrl`과 구분).
- 응답 `data.topics[]`는 `{ code, name, shortDescription, longDescription, imageUrl, backgroundImageUrl }`이며, 주제 수는 고정·소규모라 **페이지네이션 없이 전체 배열**을 한 번에 반환한다(api-design-guide §4 목록 규약 미적용, US-7-3과 동일 성격). `code`·`imageUrl`·`backgroundImageUrl`은 언어와 무관하게 동일(불변)하고 표시 텍스트(`name`·`shortDescription`·`longDescription`)만 언어별이다. `code`는 US-8-2에서 특정 주제의 팁을 지정하는 path 키로 쓰인다. 홈 화면 주제 카드는 `imageUrl`+`shortDescription`, 주제 상세 상단은 `backgroundImageUrl`+`longDescription`으로 그리며, 6필드가 이 한 응답에 함께 실려 앱이 목록에서 받은 주제 객체를 상세 화면까지 들고 간다.
