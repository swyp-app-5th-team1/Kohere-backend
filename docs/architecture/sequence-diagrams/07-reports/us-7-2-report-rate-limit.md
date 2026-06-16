# US-7-2 — 신고 도배(레이트리밋) 방어

> 모듈: 신고 처리 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/07-reports.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant REPORT as report 모듈
    participant DB as 저장소(추후 결정)

    U->>C: 단시간에 반복 신고 시도
    C->>SEC: POST /api/v1/reports<br/>Authorization: Bearer accessToken<br/>targetType, targetId, reason
    Note over SEC: JWT 검증 (서명·만료·클레임)
    alt 토큰 없음/위조
        SEC-->>C: 401 UNAUTHENTICATED
    else 토큰 만료
        SEC-->>C: 401 TOKEN_EXPIRED
    else 토큰 유효
        SEC->>REPORT: 인증된 요청 전달 (userId)
        Note over REPORT: reporterId 기준 윈도우 카운팅<br/>레이트리밋을 입력 검증보다 먼저 적용
        REPORT->>DB: 기간 내 신고 횟수 조회<br/>(reporterId, window)
        DB-->>REPORT: 현재 카운트
        alt 한도 초과
            REPORT-->>C: 429 Too Many Requests<br/>error.code=TOO_MANY_REQUESTS<br/>Retry-After 헤더 포함
            C-->>U: 잠시 후 다시 시도하세요
        else 한도 내
            Note over REPORT: status=RECEIVED 저장
            REPORT->>DB: 신고 저장(status=RECEIVED)·횟수 기록<br/>(reporterId, window)
            DB-->>REPORT: reportId, createdAt(UTC ISO-8601)
            REPORT-->>C: 201 Created<br/>reportId, status=RECEIVED,<br/>createdAt(UTC ISO-8601)
            C-->>U: 신고가 접수되었습니다
        end
    end
```

## 흐름 요약

- 앱이 `POST /api/v1/reports`를 호출하면 `공통 보안 필터(SEC)`가 컨트롤러 앞단에서 JWT를 검증한 뒤 `userId`와 함께 `report 모듈`로 전달하며(토큰 없음/위조 시 `401 UNAUTHENTICATED`, 만료 시 `401 TOKEN_EXPIRED`로 필터가 차단), `report 모듈`은 `저장소(추후 결정)`에서 `reporterId` 기준 기간 내 신고 횟수를 조회해 윈도우 카운팅을 평가한다.
- 한도를 초과하면 입력 검증보다 먼저 `429 TOO_MANY_REQUESTS`와 `Retry-After` 헤더를 반환해 도배를 차단한다.
- 한도 내라면 `report 모듈`이 `저장소(추후 결정)`에 신고를 `status=RECEIVED`로 저장하고 기간 내 횟수를 기록한 뒤 `201 Created`로 접수한다.
