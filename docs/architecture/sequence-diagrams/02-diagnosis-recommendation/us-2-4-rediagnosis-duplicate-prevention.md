# US-2-4 — 재진단(새 진단 생성)과 중복 제출 방지

> 모듈: 맞춤 진단 & 매물 추천 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/02-diagnosis-recommendation.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant DIAG as diagnosis 모듈
    participant DB as MongoDB

    U->>C: 조건 변경 후 재진단 제출
    C->>SEC: POST /api/v1/diagnoses<br/>변경된 region/purposes/conditions/budget/arcStatus<br/>Idempotency-Key: key (정책 도입 시)<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>DIAG: 인증된 요청 전달 (userId)
    Note over DIAG: 입력 재검증<br/>동일 키 동시요청 직렬화(정책 도입 시)
    alt 신규 키 또는 키 미사용
        Note over DIAG: 항상 새 diagnosis 레코드 생성<br/>(기존 진단 덮어쓰지 않음)
        DIAG->>DB: 새 diagnosis 저장
        DB-->>DIAG: 저장 완료(새 diagnosisId)
        DIAG-->>C: 201 Created<br/>Location: /api/v1/diagnoses/{diagnosisId}<br/>새 diagnosisId, status COMPLETED
        C-->>U: 새 진단 결과로 이동<br/>(기존 이력 보존)
    else 동일 키 + 다른 본문(정책 도입 시)
        DIAG->>DB: 동일 키 기존 진단 조회
        DB-->>DIAG: 기존 진단(본문 불일치)
        DIAG-->>C: 409 DIAGNOSIS_IDEMPOTENCY_CONFLICT
        C-->>U: 중복/충돌 안내
    end
```

## 흐름 요약

- 재진단도 US-2-1과 동일한 `POST /api/v1/diagnoses`이며, 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 diagnosis 모듈로 전달하고 diagnosis 모듈이 항상 새 diagnosis를 MongoDB에 저장해 새 `diagnosisId`를 발급하며 기존 진단을 덮어쓰지 않고 이력을 보존한다(`201 Created`). 멱등 충돌 판정 시에는 동일 키의 기존 진단을 MongoDB에서 조회한다.
- 더블탭·재시도 중복 방지를 위해 diagnosis 모듈이 `Idempotency-Key` 헤더를 검토하며, 동일 키 동시 제출은 1건만 생성하고 같은 결과를 반환한다(정책 미확정 — 확인 필요).
- 동일 키 + 다른 본문은 `409 DIAGNOSIS_IDEMPOTENCY_CONFLICT`로 응답한다(정책 도입 시).
