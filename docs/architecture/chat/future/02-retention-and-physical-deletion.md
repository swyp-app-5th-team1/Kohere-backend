# 3개월 만료와 물리 삭제 — 후속 고도화

현재의 DELETE는 사용자별 soft delete다. 이 문서는 그 이후 자동 보존 만료와 실제 DB 삭제를 구현할 때 적용할 후속 설계다.

## 1. 핵심 규칙

- `3개월`은 90일 고정이 아니라 삭제 시각에 calendar month 3개를 더해 계산한다.
- 한 사용자만 삭제한 경우 3개월이 지나도 상대방이 보는 공유 메시지는 물리 삭제하지 않는다.
- 양쪽 사용자가 모두 삭제했고 각자의 3개월 기한이 지난 공통 범위만 물리 삭제할 수 있다.
- 신고·분쟁 보존 hold가 있으면 물리 삭제를 미룬다.
- 백업 데이터 파기 자동화는 이 설계의 범위에 포함하지 않는다.

## 2. API가 아니라 정기 작업

외부에 다음과 같은 API를 만들지 않는다.

```text
DELETE /admin/chat-messages/expired
POST /chat-rooms/{roomId}/hard-delete
```

대신 서버 내부 scheduled job이 만료 후보를 작은 batch로 조회하고 재실행해도 같은 결과가 되도록 처리한다. 사용자는 3개월 기한, 삭제 후보 목록, 물리 삭제 실행 버튼을 받지 않는다.

## 3. 데이터 모델 확장안

후속 migration에서 삭제 주기와 물리 파기 경계를 별도로 추가한다.

### `chat_room_member_deletions`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 삭제 주기 PK |
| `chat_room_member_id` | 사용자별 member ID |
| `hidden_through_message_id` | 이 삭제에서 숨긴 마지막 messageId |
| `requested_at` | 삭제 시각 |
| `purge_eligible_at` | 삭제 시각 + 3 calendar months |
| `status` | `WAITING`, `CANCELLED`, `FINALIZED` |
| `finalized_at` | 사용자별 만료 확정 시각 |

즉시 Undo가 성공하면 해당 주기를 `CANCELLED`로 바꾼다. 방이 새 메시지로 다시 나타나는 것은 Undo가 아니므로 기존 삭제 주기를 취소하지 않는다.

### `chat_room_members` 확장

- `purge_confirmed_through_message_id`: 3개월이 지나 물리 삭제 후보로 확정된 사용자별 경계

### `chat_room_retention_holds`

- `hold_id`, `chat_room_id`, `reason`
- `held_at`, `review_due_at`, `released_at`

신고 모듈은 공개 chat command로 hold를 만들고 해제한다. 삭제 job이 report 내부 테이블을 직접 조회하지 않게 한다.

## 4. 사용자별 만료 확정

정기 작업은 `status=WAITING AND purge_eligible_at<=now`인 삭제 주기를 찾는다.

1. room을 먼저 잠근다.
2. 두 member를 ID 오름차순으로 잠근다.
3. lock 후 만료 조건과 Undo 여부를 다시 확인한다.
4. 해당 사용자의 `purge_confirmed_through_message_id`를 앞으로 이동한다.
5. 삭제 주기를 `FINALIZED`로 바꾼다.

이 단계는 사용자별 경계를 확정할 뿐 상대방이 아직 보유한 메시지를 지우지 않는다.

## 5. 공유 메시지 물리 삭제

```text
safePurgeThrough = min(
  memberA.purgeConfirmedThroughMessageId,
  memberB.purgeConfirmedThroughMessageId
)
```

예를 들어 A가 100번까지 확정했고 B가 0이면 물리 삭제 범위는 없다. B도 120번까지 확정한 뒤에야 양쪽 공통 범위인 100번까지 삭제할 수 있다.

물리 삭제 조건:

1. 두 사용자 모두 해당 ID까지 3개월 만료 확정
2. active room hold 없음
3. room → 두 member 순서로 잠금
4. 재실행해도 같은 결과인 멱등 처리

삭제 순서:

1. 대상 메시지의 `chat_message_translations`
2. 대상 `chat_messages`
3. 남은 실제 최신 메시지로 `last_message_id`, `last_message_at` 보정
4. 양쪽 방도 숨김 상태이고 메시지가 하나도 없을 때만 member와 room 전체 삭제 검토

한쪽 사용자에게 빈 방이 다시 표시된 상태라면 메시지가 없어도 방 자체를 삭제하지 않는다.

## 6. 잠금과 장애 복구

- 후보 ID는 잠금 없이 page 조회한다.
- 실제 처리 트랜잭션은 항상 room → 두 member(ID 오름차순) 순서로 잠근다.
- lock 후 만료 조건과 hold를 다시 확인한다.
- 한 batch 실패가 다른 방의 정리를 막지 않도록 방 단위 짧은 트랜잭션으로 처리한다.
- 실패 후보는 다음 실행에서 재처리하고 지표·경보를 남긴다.

관련 그림은 [후속 시퀀스](03-sequence-diagrams.md#한-사용자의-3개월-삭제-기한이-끝나기), 테스트는 [후속 테스트·운영](04-testing-and-operations.md)을 따른다.
