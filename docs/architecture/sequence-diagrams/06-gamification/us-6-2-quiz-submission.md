# US-6-2 — 퀴즈 정답 제출 및 즉시 피드백

> 모듈: 게이미피케이션 (퀴즈 · 포인트) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/06-gamification.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant GAME as gamification 모듈
    participant DB as 저장소(추후 결정)

    U->>C: 보기 선택(예: B) 후 제출
    C->>SEC: POST /api/v1/quizzes/{quizId}/submission<br/>Authorization: Bearer accessToken<br/>{ selectedChoice: "B" }
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>GAME: 인증된 요청 전달 (userId)
    Note over GAME: selectedChoice(A~D) 검증<br/>(userId, quizDate) 유니크 제약으로 하루 1회 보장<br/>저장된 정답과 대조해 서버가 판정(단일 트랜잭션)
    GAME->>DB: 대상 퀴즈(정답·해설) 조회
    DB-->>GAME: correctChoice, explanation

    alt 정답
        Note over GAME: QUIZ_CORRECT 사유 포인트 적립 로그 기록<br/>제출 기록 1건 생성
        GAME->>DB: 제출 기록 1건 저장 (userId, quizDate 유니크)
        DB-->>GAME: 저장 성공(중복 시 제약 위반)
        GAME->>DB: QUIZ_CORRECT 포인트 적립 로그 저장
        DB-->>GAME: 적립 반영 후 totalPoint
        GAME-->>C: 201 Created<br/>Location: /api/v1/quizzes/{quizId}/submission<br/>correct=true, correctChoice, explanation,<br/>earnedPoint=10, totalPoint, submittedAt
        C-->>U: 정답 안내 + 적립 포인트 표시
    else 오답
        Note over GAME: 제출 기록만 생성<br/>포인트 적립 로그 없음
        GAME->>DB: 제출 기록 1건 저장 (userId, quizDate 유니크)
        DB-->>GAME: 저장 성공(중복 시 제약 위반)
        GAME-->>C: 201 Created<br/>correct=false, correctChoice, explanation,<br/>earnedPoint=0, totalPoint, submittedAt
        C-->>U: 오답 안내 + 정답·해설 표시
    end
```

## 흐름 요약

- `POST /api/v1/quizzes/{quizId}/submission`에 `selectedChoice`(A~D)를 보내면 gamification 모듈이 저장소(추후 결정)의 정답과 대조해 판정하고 `201 Created`로 즉시 피드백한다(공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달).
- 정답이면 `correct=true`·`earnedPoint=10`과 함께 제출 기록 1건과 `QUIZ_CORRECT` 적립 로그 1건이 저장소(추후 결정)에 저장되고, 오답이면 `earnedPoint=0`으로 제출 기록만 저장된다.
- `(userId, quizDate)` 유니크 제약으로 하루 1회·동시 중복 제출을 차단하며 채점·적립은 같은 모듈의 단일 트랜잭션으로 처리해 포인트가 1회만 적립된다.
