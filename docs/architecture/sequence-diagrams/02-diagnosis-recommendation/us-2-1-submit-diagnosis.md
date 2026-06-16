# US-2-1 — 5단계 진단 제출 및 저장

> 모듈: 맞춤 진단 & 매물 추천 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/02-diagnosis-recommendation.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant DIAG as diagnosis 모듈
    participant DB as MongoDB

    U->>C: 5단계 진단 응답 입력<br/>(지역/목적/조건/예산/ARC)
    C->>SEC: POST /api/v1/diagnoses<br/>region, purposes[], conditions[],<br/>monthlyBudgetMax, arcStatus<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>DIAG: 인증된 요청 전달 (userId)
    Note over DIAG: 입력 재검증<br/>(region 1택, purposes 최소 1개,<br/>conditions 최대 3개, budget 0 이상)
    alt 검증 통과
        Note over DIAG: enum 정규화·중복 제거 후<br/>diagnosis 레코드 생성(status COMPLETED)
        DIAG->>DB: diagnosis 레코드 저장
        DB-->>DIAG: 저장 완료(diagnosisId)
        DIAG-->>C: 201 Created<br/>Location: /api/v1/diagnoses/{diagnosisId}<br/>data.diagnosisId, status COMPLETED, submittedAt
        C-->>U: 진단 완료, 결과 보기로 이동
    else 검증 실패(조건 4개 이상/예산 음수/필수 누락)
        DIAG-->>C: 400 INVALID_INPUT<br/>errors[]: field, reason
        C-->>U: 입력 오류 안내
    end
```

## 흐름 요약

- 사용자가 입력한 5단계 응답을 `POST /api/v1/diagnoses`로 제출하면 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 diagnosis 모듈로 전달하고, diagnosis 모듈이 입력을 재검증한다.
- 검증 통과 시 diagnosis 모듈이 새 진단 레코드(status `COMPLETED`)를 MongoDB에 저장하고 `201 Created` + `Location` 헤더와 `diagnosisId`를 반환한다.
- 조건 4개 이상·예산 음수·필수값 누락 등은 `400 INVALID_INPUT` + `errors[]`로 응답한다.
