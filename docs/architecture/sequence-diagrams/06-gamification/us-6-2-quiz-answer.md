# US-6-2 — 퀴즈 정답 제출 및 즉시 피드백

> 모듈: 게이미피케이션 (퀴즈) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/06-gamification.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant GAME as gamification 모듈
    participant USER as user 모듈
    participant DB as MongoDB

    U->>C: 보기 선택(예: B) 후 제출
    C->>SEC: POST /api/v1/quizzes/{quizId}/answer<br/>Authorization: Bearer accessToken<br/>{ selectedChoice: "B" }
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>GAME: 인증된 요청 전달 (userId)
    Note over GAME: selectedChoice(A~D) 검증 후<br/>저장된 correctChoice와 대조해 서버가 판정<br/>무상태(제출·포인트 없음, 반복 무부작용)

    alt quizId 대상 퀴즈 없음
        GAME-->>C: 404 QUIZ_NOT_FOUND
        C-->>U: 퀴즈 없음 안내
    else 대상 퀴즈 존재
        Note over GAME: 오답 해설 번역을 위해 표시 언어 필요<br/>(JWT 클레임 비의존 — 항상 user 공개 query로 취득)
        GAME->>USER: user 공개 query 동기 호출 getLanguage(userId)<br/>(표시 언어 조회 — user가 countries.lang으로 도출, ADR-0002 Decision 5)
        USER-->>GAME: 표시 언어 lang
        GAME->>DB: quizId로 대상 퀴즈 도큐먼트 조회 (quizzes)
        DB-->>GAME: 퀴즈 도큐먼트(question·choices·correctChoice·explanation 언어-키 맵)
        Note over GAME: 채점은 correctChoice로, 오답 해설은 explanation만 사용<br/>(question·choices는 채점 응답에 쓰지 않음)

        alt 정답 (selectedChoice == correctChoice)
            Note over GAME: 채점만 수행 — DB 쓰기·제출 기록·포인트 없음<br/>(정답 시 explanation 노출 여부 미확정, 확인 필요)
            GAME-->>C: 200 OK<br/>{ quizId, selectedChoice, correct: true }
            C-->>U: 정답 안내
        else 오답 (selectedChoice != correctChoice)
            alt 도큐먼트에 그 언어 키 존재
                Note over GAME: explanation 언어-키 맵에서 그 언어 값 선택<br/>explanation=explanation[lang]
            else 미지원 언어
                Note over GAME: 언어-키 맵을 영어(en)로 폴백<br/>(에러 아님 — 기본 언어=영어)
            end
            GAME-->>C: 200 OK<br/>{ quizId, selectedChoice, correct: false,<br/>correctChoice, explanation(번역) }
            C-->>U: 오답 안내 + 정답·해설 표시
        end
    end
```

## 흐름 요약

- `POST /api/v1/quizzes/{quizId}/answer`에 `selectedChoice`(A~D)를 보내면 gamification 모듈이 MongoDB `quizzes`에서 `quizId`로 퀴즈 도큐먼트를 읽어(랜덤 조회와 동일한 단건 도큐먼트 읽기) 저장된 `correctChoice`와 대조해 서버가 판정하고 `200 OK`로 즉시 피드백한다. 채점에는 `correctChoice`, 오답 해설에는 `explanation`만 사용한다(`question`·`choices`는 채점 응답에 쓰지 않는다)(공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달). **무상태 채점** — 제출 기록·포인트 적립·저장이 전혀 없고, 같은 요청을 반복해도 부작용이 없다(멱등·재생 가능). `201 Created`/`Location`이 아니다.
- 정답이면 `{ quizId, selectedChoice, correct: true }`만 응답한다. 오답이면 `{ quizId, selectedChoice, correct: false, correctChoice, explanation(번역) }`을 응답한다 — 오답 사유(`explanation`)는 사용자 표시 언어로 번역한다.
- 오답 해설 번역을 위해 gamification 모듈은 `user`의 공개 query(`getLanguage`)를 동기 호출해 표시 언어(`lang`)를 취득하고(ADR-0002 Decision 5), `quizzes` 도큐먼트의 `explanation` 언어-키 맵에서 그 언어 값을 고른다. 해당 언어 키가 없으면 **영어(`en`)로 폴백**한다(에러 아님). 선택지 키 `A~D`는 **언어 불변**이며 채점은 키 기준으로 한다.
- 오류 경계: `selectedChoice`가 A~D가 아니면 `400 INVALID_INPUT`, JSON 파싱 실패 등은 `400 MALFORMED_REQUEST`, `quizId`에 해당하는 퀴즈가 없으면 `404 QUIZ_NOT_FOUND`로 반환한다(하루 1회 제한·중복 제출 개념이 없으므로 `409`/`422`는 없다).
- (확인 필요) 정답일 때에도 `explanation`을 함께 반환할지 여부는 미확정이다 — 현재는 정답 응답에 `explanation`을 포함하지 않는다.
- `/api/v1/quizzes/**`는 `hasRole("USER")`(ACTIVE)로 게이팅하고 응용 계층에서 `userType=TENANT`를 검사한다 — 비-ACTIVE는 `403 AUTH_ONBOARDING_REQUIRED`, 세입자가 아니면 `403 FORBIDDEN`으로 거부한다.
