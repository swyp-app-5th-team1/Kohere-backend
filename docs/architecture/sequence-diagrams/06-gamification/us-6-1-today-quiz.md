# US-6-1 — 오늘의 퀴즈 조회

> 모듈: 게이미피케이션 (퀴즈 · 포인트) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/06-gamification.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant GAME as gamification 모듈
    participant DB as 저장소(추후 결정)

    U->>C: 오늘의 퀴즈 화면 진입
    C->>SEC: GET /api/v1/quizzes/today<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>GAME: 인증된 요청 전달 (userId)
    Note over GAME: 서버 기준 오늘(UTC date) 퀴즈 1건 조회<br/>인증 주체의 제출 여부 확인
    GAME->>DB: 오늘(UTC date) 퀴즈 1건 조회
    DB-->>GAME: 퀴즈(question, choices, correctChoice, explanation)
    GAME->>DB: (userId, quizDate) 제출 기록 조회
    DB-->>GAME: 제출 기록(없음 또는 1건)

    alt 아직 제출하지 않음
        Note over GAME: 정답(correctChoice)·해설(explanation) 가림
        GAME-->>C: 200 OK<br/>quizId, quizDate, question,<br/>choices[A~D], submitted=false
        C-->>U: 문제 화면 표시(보기 4개)
    else 이미 제출함
        Note over GAME: 제출을 마친 사용자에게만<br/>정답·해설 공개
        GAME-->>C: 200 OK<br/>submitted=true, result{selectedChoice,<br/>correct, correctChoice, explanation,<br/>earnedPoint, submittedAt}
        C-->>U: 결과 화면 표시(정답·해설)
    end
```

## 흐름 요약

- `GET /api/v1/quizzes/today`로 gamification 모듈이 서버 기준 오늘(UTC date)의 4지선다 퀴즈 1건과 인증 주체의 제출 여부(`submitted`)를 함께 응답한다(공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달).
- 모듈은 저장소(추후 결정)에서 오늘 퀴즈 1건과 `(userId, quizDate)` 제출 기록을 조회해 제출 여부를 판정한다.
- 미제출이면 정답·해설을 가린 문제만(`submitted=false`), 이미 제출했으면 직전 제출 결과(`result`)를 포함해 `200 OK`로 응답한다.
- 정답(`correctChoice`)·해설(`explanation`)·`result`는 제출을 마친 사용자에게만 노출된다.
