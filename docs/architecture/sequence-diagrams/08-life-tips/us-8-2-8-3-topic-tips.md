# US-8-2 · US-8-3 — 주제별 생활 팁(제목·내용·사진) 조회 + 표시 언어 기반 번역

> 모듈: 생활 팁 (주제별 생활 정보) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/08-life-tips.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant TIP as lifetip 모듈
    participant USER as user 모듈
    participant DB as MongoDB

    U->>C: 주제 선택(예 MOVING_IN)
    C->>SEC: GET /api/v1/life-tips/topics/{topicCode}/tips<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)

    alt 인증 누락/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED<br/>(만료 시 TOKEN_EXPIRED)
        C-->>U: 재로그인 유도
    else 온보딩 스코프 토큰(ROLE_ONBOARDING, PENDING/TERMS_AGREED)
        Note over SEC: 정식 인증(ROLE_USER=ACTIVE) 티어 미충족<br/>(AccessDeniedHandler, 모듈 도달 전)
        SEC-->>C: 403 AUTH_ONBOARDING_REQUIRED
        C-->>U: 온보딩 완료 유도
    else 정식 인증(ROLE_USER = ACTIVE 세입자)
        SEC->>TIP: 인증된 요청 전달 (userId, topicCode)
        Note over TIP: 번역 언어 결정을 위해 표시 언어 필요<br/>(JWT 클레임 비의존 — 항상 user 공개 query로 취득)
        TIP->>USER: user 공개 query 동기 호출 getLanguage(userId)<br/>(표시 언어 조회 — users.lang(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 en, ADR-0002 Decision 5)
        USER-->>TIP: 표시 언어 lang
        TIP->>DB: lifeTipTopics에서 topicCode 주제 존재 확인
        DB-->>TIP: 주제 1건 또는 없음
        alt 주제 코드 미존재
            TIP-->>C: 404 LIFE_TIP_TOPIC_NOT_FOUND
            C-->>U: 존재하지 않는 주제 안내
        else 주제 존재
            TIP->>DB: lifeTips에서 해당 주제 팁 전체 조회<br/>(topicCode 일치, order 오름차순)<br/>(애플리케이션 레벨 조인 — DB 조인 없음)
            DB-->>TIP: 팁 목록(각 _id, title/content 언어-키 맵, imageUrl, order)
            Note over TIP: 주제당 팁 수 제한적 → 페이지네이션 없이 전체 리스트<br/>("해당 주제에 맞는 제목-내용-사진의 모든 리스트")
            loop 각 팁(노출 순서 order)
                alt 도큐먼트에 그 언어 키 존재
                    Note over TIP: title/content 언어-키 맵에서 그 언어 값 선택<br/>title=title[lang], content=content[lang]<br/>(id·imageUrl은 언어 무관·불변)
                else 미지원 언어
                    Note over TIP: title/content를 영어(en)로 폴백<br/>(에러 아님 — 기본 언어=영어)
                end
                Note over TIP: 사진 없는 팁은 imageUrl=null (언어 무관)
            end
            TIP-->>C: 200 OK<br/>data.tips[]: { id, title(번역), content(번역),<br/>imageUrl(url\|null) }<br/>(노출 순서대로, 페이지 객체 없음)
            C-->>U: 주제별 팁 표시(제목·내용·사진, 내 언어)
        end
    end
```

## 흐름 요약

- `GET /api/v1/life-tips/topics/{topicCode}/tips`로 `lifetip 모듈`이 선택한 주제(`topicCode`)에 속한 생활 팁 전체를 노출 순서(`order`)대로 반환한다. 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달한다. US-8-1과 동일한 **정식 인증(ROLE_USER = ACTIVE 세입자)** 게이트다 — 온보딩 미완료(ROLE_ONBOARDING) 토큰은 SEC가 모듈 도달 전에 `403 AUTH_ONBOARDING_REQUIRED`, 인증 누락/만료/위조는 `401 UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)다.
- 번역 언어 결정을 위한 표시 언어는 `user`가 보유하며, `lifetip` 모듈은 JWT 클레임에 의존하지 않고 **항상 `user`의 공개 query(`getLanguage(userId)`)를 동기 호출**해 표시 언어(`lang`)를 취득한다(`user`가 **`users.lang`(사용자가 고른 표시 언어)이 있으면 그 값, 없으면 `en`**으로 정함 — ADR-0002 Decision 5 · [ADR-0029](../../../adr/0029-diagnosis-i18n-strategy.md) 개정(#141) · 모듈 의존 `lifetip → user` 추가 · `Accept-Language`·토큰 클레임 미사용).
- 모듈은 먼저 **MongoDB `lifeTipTopics`** 에서 `topicCode` 주제의 존재를 확인한다 — 카탈로그에 없는 주제 `code`는 `404 LIFE_TIP_TOPIC_NOT_FOUND`(신규 도메인 에러코드, `*_NOT_FOUND` 규약 · `ErrorCode` 등록 필요)다.
- 주제가 존재하면 **MongoDB `lifeTips`** 에서 `topicCode`가 일치하는 팁 전체를 `{ topicCode: 1, order: 1 }` 복합 인덱스로 노출 순서대로 조회한다(주제 : 팁 = 1 : N · 애플리케이션 레벨 조인, cross-collection DB 조인 없음). 각 팁의 `title`·`content`는 인라인 언어-키 맵에서 사용자 언어 값(`title[lang]`/`content[lang]`)으로 채우고, 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). `imageUrl`(사진)은 언어 무관이며 사진이 없는 팁은 `null`(또는 생략)로 둔다.
- 응답 `data.tips[]`는 `{ id, title, content, imageUrl }`이며, 주제당 팁 수가 제한적이므로 **페이지네이션 없이 전체 리스트**를 한 번에 반환한다. `id`·`imageUrl`은 언어와 무관하게 동일(불변)하고 표시 텍스트(`title`·`content`)만 언어별이다(US-8-3 — 응답 스키마는 언어와 무관하게 동일, 서버가 언어 문자열만 채운다).
