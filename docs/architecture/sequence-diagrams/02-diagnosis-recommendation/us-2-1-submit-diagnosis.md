# US-2-1 — 진단 제출(진행 중 진단 확정 및 저장)

> 모듈: 맞춤 진단 & 매물 추천 · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/02-diagnosis-recommendation.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant DIAG as diagnosis 모듈
    participant DB as MongoDB

    Note over U,C: 단계별 답은 이미 서버에 저장됨<br/>(진행 중 진단 IN_PROGRESS — US-2-5)
    U->>C: 진단 제출(확정)
    C->>SEC: POST /api/v1/diagnoses<br/>(진행 중 진단 확정 요청)<br/>Authorization: Bearer accessToken
    Note over SEC: JWT 검증 (서명·만료·클레임)
    SEC->>DIAG: 인증된 요청 전달 (userId)
    DIAG->>DB: 진행 중 진단(IN_PROGRESS) 조회 (userId)
    DB-->>DIAG: 저장된 단계별 답
    Note over DIAG: 저장된 답 재검증<br/>(region 1택, purpose 1택,<br/>STUDY면 university 필수·NON_STUDY면 district 필수<br/>(목적에 맞는 하나만, 위반=INVALID_INPUT),<br/>conditions(④ 사용자 선택) 최대 3개<br/>(⑥ arcStatus=NO_ARC에서 파생한 NO_ARC 조건은 이 계산에서 제외),<br/>monthlyRentMin·monthlyRentMax 각 0 이상 정수 AND monthlyRentMin ≤ monthlyRentMax)
    alt 검증 통과
        Note over DIAG: enum 정규화·중복 제거 후<br/>status IN_PROGRESS → COMPLETED 확정
        DIAG->>DB: 진단 상태 COMPLETED로 전이 저장
        DB-->>DIAG: 확정 완료(diagnosisId, submittedAt)
        DIAG-->>C: 201 Created<br/>Location: /api/v1/diagnoses/{diagnosisId}<br/>data.diagnosisId, status COMPLETED, submittedAt
        C-->>U: 진단 완료, 결과 보기로 이동
    else 검증 실패(④ 조건 4개 이상/월세 음수 또는 최소>최대/필수 단계 미완료)
        DIAG-->>C: 400 INVALID_INPUT<br/>errors[]: field, reason
        C-->>U: 입력 오류 안내
    end
```

## 흐름 요약

- 단계별 답은 진단 진행 중 서버가 이미 저장해 둔다 — 사용자당 진행 중 진단 1건(`status=IN_PROGRESS`)에 단계마다 채워 간다(US-2-5). 제출은 별도 단계로, `POST /api/v1/diagnoses`는 6필드 누적 답을 다시 보내는 요청이 아니라 **서버에 저장된 진행 중 진단을 확정하는 요청**이다.
- 공통 보안 필터(SEC)가 컨트롤러 앞단에서 JWT를 검증한 뒤 diagnosis 모듈로 전달하면, diagnosis 모듈이 진행 중 진단을 조회해 저장된 답을 재검증한다.
- 입국 목적별 대학·지역 선택은 조건부 필수다 — `STUDY`면 `university`, `NON_STUDY`면 `district`가 채워져야 하며 목적에 맞는 하나만 채워진다(`university`·`district` 두 필드 분리, 값·조건부 필수 규칙은 02 스펙 ③ 참조).
- 검증 통과 시 diagnosis 모듈이 진단 상태를 `IN_PROGRESS` → `COMPLETED`로 전이해 MongoDB에 확정 저장하고 `201 Created` + `Location` 헤더와 `diagnosisId`·`submittedAt`을 반환한다. 진단 생성(`COMPLETED`)은 이 제출 시점이며, 재진단은 새 진행 중 진단을 시작한다.
- ④ 조건 4개 이상(⑥ arcStatus=NO_ARC에서 파생한 NO_ARC는 이 계산에서 제외)·월세 음수(`0 이상이어야 합니다.`)·`monthlyRentMin`>`monthlyRentMax`(`monthlyRentMin은 monthlyRentMax 이하여야 합니다.`)·필수 단계 미완료·목적별 대학/지역 누락 등은 공통 `400 INVALID_INPUT` + `errors[]`로 응답한다(진단 도메인 별도 코드 없음).
