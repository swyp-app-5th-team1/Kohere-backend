# US-6-3 — 사용자 국가 기반 퀴즈 문항·해설 번역 제공

> 모듈: 게이미피케이션 (퀴즈) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/06-gamification.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant GAME as gamification 모듈
    participant USER as user 모듈
    participant DB as MongoDB

    Note over U,DB: 퀴즈 조회(US-6-1)·정답 제출(US-6-2) 두 흐름 모두<br/>표시 문자열을 사용자 국가 기반 언어로 번역해 내려준다

    U->>C: 퀴즈 화면 진입 / 보기 선택 후 제출
    C->>SEC: GET /api/v1/quizzes/random<br/>또는 POST /api/v1/quizzes/{quizId}/answer<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>GAME: 인증된 요청 전달 (userId)
    Note over GAME: 번역 언어 결정을 위해 표시 언어 필요<br/>(JWT 클레임 비의존 — 항상 user 공개 query로 취득)
    GAME->>USER: user 공개 query 동기 호출 getLanguage(userId)<br/>(표시 언어 조회 — user가 countries.lang으로 도출, ADR-0002 Decision 5)
    USER-->>GAME: 표시 언어 lang
    GAME->>DB: 대상 퀴즈 도큐먼트 조회 (quizzes)
    DB-->>GAME: question·choices[].text·explanation 언어-키 맵 (+ correctChoice)

    alt 도큐먼트에 그 언어 키 존재
        Note over GAME: 언어-키 맵에서 그 언어 값 선택·조립<br/>question=question[lang], text=choices[].text[lang],<br/>explanation=explanation[lang](채점 시 — 정답·오답 공통)<br/>(선택지 키 A~D는 언어 불변 — 채점은 키 기준)
    else 미지원 언어
        Note over GAME: 언어-키 맵을 영어(en)로 폴백<br/>(에러 아님 — 기본 언어=영어)
    end
    GAME-->>C: 200 OK<br/>번역된 question / choices[].text (조회)<br/>또는 번역된 explanation (채점 제출 — 정답·오답 공통)
    C-->>U: 사용자 언어로 문항·해설 표시
```

## 흐름 요약

- 퀴즈 조회(US-6-1)와 정답 제출(US-6-2) **두 흐름 모두**에서 gamification 모듈은 표시 문자열을 사용자 국가 기반 언어로 번역해 내려준다 — 조회 응답의 `question`·`choices[].text`, 채점 응답의 `explanation`(정답·오답 공통)이 대상이다.
- 표시 언어는 `user`가 보유한다(`countries.lang`). gamification 모듈은 JWT 클레임에 의존하지 않고 **항상 `user`의 공개 query(`getLanguage`)를 동기 호출**해 표시 언어(`lang`)를 취득한다(즉시 결과가 필요한 조회 → ADR-0002 Decision 5). 이를 위해 모듈 의존 `gamification → user`를 추가한다.
- 번역 문자열은 별도 컬렉션 없이 **같은 `quizzes` 도큐먼트에 임베드**하되 **언어 코드를 키로 하는 맵**으로 둔다 — 예: `question: { "en": ..., "ja": ..., "ko": ... }`, `choices: [ { "key": "A", "text": { "en": ..., "ja": ... } } ]`, `explanation: { "en": ..., "ko": ... }`. 서버가 그 `lang` 값으로 표시 문자열을 조립한다(진단 `diagnosisQuestions`의 인라인 언어-키 맵 패턴과 동일).
- 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님; 기본 언어=영어). `Accept-Language` 헤더에 의존하지 않는다.
- 선택지 키 `A~D`는 **언어와 무관하게 동일**(언어 불변)하며, 채점은 이 키를 기준으로 한다(번역 여부와 무관).
