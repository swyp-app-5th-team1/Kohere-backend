# 운영자 신고 처리 — 후속 고도화

사용자 신고 접수는 현재 범위에서 구현하지만, 운영자 화면·API와 최종 처리는 관리자 인증이 준비된 뒤 구현한다.

## 1. 관리자 REST API

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/api/v1/admin/chat-reports` | 상태·처리 목표일별 신고 목록 |
| GET | `/api/v1/admin/chat-reports/{reportId}` | 신고·채팅방·증거 상세 |
| PATCH | `/api/v1/admin/chat-reports/{reportId}/status` | 검토 시작 또는 최종 처리 |

- 모든 endpoint는 별도 `ADMIN` 인증이 필요하다.
- 목록·상세 조회와 상태 변경을 감사 로그로 남긴다.
- 사용자용 신고 상태 API에는 증거, 신고 대상 ID, 관리자 ID와 내부 메모를 노출하지 않는다.

## 2. 신고 상태와 추가 필드

상위 문서의 `reports`를 다음과 같이 확장한다.

| 항목 | 설명 |
| --- | --- |
| `status` | `RECEIVED → UNDER_REVIEW → ACTIONED 또는 DISMISSED` |
| `review_due_at` | 접수 후 처리 목표 시각 |
| `resolved_at` | 최종 처리 완료 시각 |
| `retention_expires_at` | 처리 완료 후 신고 자료 정리 예정 시각 |
| `resolution_code` | 운영 처리 결과 code |
| `version` | 운영자 동시 처리 optimistic lock |
| `active_slot` | 열린 신고 중복 방지용 generated column |

```sql
active_slot TINYINT
  GENERATED ALWAYS AS (
    CASE
      WHEN status IN ('RECEIVED', 'UNDER_REVIEW') THEN 1
      ELSE NULL
    END
  ) STORED

UNIQUE (reporter_id, target_type, target_id, active_slot)
INDEX (status, review_due_at, id)
INDEX (retention_expires_at, id)
```

현재의 단순 신고 UNIQUE를 이 구조로 migration하면 종결된 과거 신고는 유지하면서 같은 방의 열린 신고를 한 건으로 제한할 수 있다. 종결 뒤 재신고는 직전 증거 기준 이후 실제 새 메시지가 있을 때만 허용한다.

## 3. 처리 이력과 증거

### `report_status_history`

- `id`, `report_id`
- `from_status`, `to_status`
- `actor_type`: `REPORTER`, `ADMIN`, `SYSTEM`
- `actor_id`: SYSTEM이면 null
- `created_at`

상태 변경과 이력 저장은 한 트랜잭션에서 처리한다.

### 증거 버전

- 접수 시 저장한 최초 원문 evidence는 덮어쓰지 않는다.
- 운영자가 최종 판단에 사용한 최소 증거가 다르면 새 `evidence_version`으로 append한다.
- 번역본은 보조 표시일 뿐 증거 hash의 정본이 아니다.

## 4. 보존과 hold

- 최종 처리와 동시에 `resolved_at`, `retention_expires_at`을 기록한다.
- 처리 완료 후 6개월은 현재 제품 정책 초안이며 실제 운영 정책 확정 시 다시 검토한다.
- 진행 중인 분쟁 등 별도 보존 사유가 있으면 기간과 재검토일을 가진 hold를 생성한다.
- 무기한 boolean hold를 두지 않고 생성 사유·책임자·다음 검토일·해제 시각을 기록한다.
- 만료 정리 작업은 active hold가 없는 자료만 삭제한다.

## 5. 최종 처리 트랜잭션

다음을 한 번에 커밋한다.

1. 신고 `version` 확인
2. 필요한 경우 최종 evidence version append
3. 최종 상태와 status history 저장
4. `resolved_at`, `retention_expires_at` 저장
5. 원본 방 보존 hold가 있다면 안전하게 해제

증거 저장은 실패했는데 원본 보존만 먼저 해제되는 상태를 허용하지 않는다.

관련 그림은 [후속 시퀀스](03-sequence-diagrams.md#운영자가-신고를-처리하기), 테스트는 [후속 테스트·운영](04-testing-and-operations.md)을 따른다.
