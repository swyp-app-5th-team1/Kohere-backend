# US-2-3 — 진단 이력 조회 및 최근 진단 다시 보기

> 모듈: 맞춤 진단 & 매물 추천 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/02-diagnosis-recommendation.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant DIAG as diagnosis 모듈
    participant DB as MongoDB

    U->>C: 홈 진입
    C->>SEC: GET /api/v1/diagnoses/latest<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>DIAG: 인증된 요청 전달 (userId)
    Note over DIAG: 본인 최신 진단 1건 조회
    DIAG->>DB: 진단 이력 조회(최신 1건)
    DB-->>DIAG: 최신 진단 또는 없음
    alt 진단 이력 있음
        DIAG-->>C: 200 OK<br/>data.completed true, diagnosisId,<br/>region, purposes[], submittedAt 등
        C-->>U: 재진단 문구 + 최근 진단 노출
    else 이력 없음(최초 사용자)
        DIAG-->>C: 200 OK<br/>data.completed false
        C-->>U: 진단 시작 문구 노출
    end

    U->>C: 진단 이력 목록 보기
    C->>SEC: GET /api/v1/diagnoses<br/>?page=0&size=20&sort=submittedAt,desc<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>DIAG: 인증된 요청 전달 (userId)
    DIAG->>DB: 진단 이력 조회(최신순 페이지)
    DB-->>DIAG: 진단 목록 + 페이지 메타
    DIAG-->>C: 200 OK<br/>data.content[] (최신순), data.page

    U->>C: 지난 진단 다시 보기
    C->>SEC: GET /api/v1/diagnoses/{diagnosisId}<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>DIAG: 인증된 요청 전달 (userId)
    Note over DIAG: 소유권 검증
    DIAG->>DB: 진단 단건 조회
    DB-->>DIAG: 진단 입력 전체
    DIAG-->>C: 200 OK<br/>region, purposes[], conditions[],<br/>monthlyBudgetMax, arcStatus,<br/>status, submittedAt
    C-->>U: 진단 입력 전체 표시
```

## 흐름 요약

- 홈에서 `GET /api/v1/diagnoses/latest`로 diagnosis 모듈이 MongoDB에서 최신 1건을 조회해 `completed` 값으로 "진단 시작/재진단" 문구를 분기한다(이력 없음도 `200 OK`). 모든 요청은 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 모듈로 전달한다.
- `GET /api/v1/diagnoses`로 diagnosis 모듈이 MongoDB에서 진단 이력을 최신순(`submittedAt,desc`) 오프셋 페이지네이션으로 조회한다.
- `GET /api/v1/diagnoses/{diagnosisId}`로 diagnosis 모듈이 소유권을 검증한 뒤 MongoDB에서 본인 소유 진단의 입력 전체를 조회해 다시 본다(타인 `403 FORBIDDEN`, 부재 `404 DIAGNOSIS_NOT_FOUND`).
