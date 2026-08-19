# 관리자 전용 채팅방 삭제 복구 — 후속 고도화

> 상태: 설계만 기록하며 현재 API·Swagger·애플리케이션 코드에는 추가하지 않는다.

일반 사용자는 삭제한 채팅방을 복원할 수 없다. 이 문서는 고객 지원이나 운영상 꼭 필요한 경우에만 관리자가 특정 사용자의 최근 삭제를 제한적으로 되돌리는 후속 기능을 설명한다.

## 1. 복구 범위

- `ADMIN` 권한만 실행할 수 있다.
- 특정 사용자의 **가장 최근 삭제 1건**만 복구한다.
- 대상 사용자의 채팅방을 다시 표시하고, 그 삭제 직전의 메시지 숨김 경계로 되돌린다.
- 상대방의 화면, 차단 관계, 신고 상태는 변경하지 않는다.
- 3개월 만료가 확정되거나 메시지가 물리 삭제된 뒤에는 복구하지 않는다.
- 일반 사용자용 restore endpoint와 복구 버튼은 만들지 않는다.

관리자 복구는 같은 매물에서 다시 문의해 새 대화를 시작하는 현재 기능과 다르다. 새 대화 시작은 과거 이력을 계속 숨기지만, 관리자 복구는 기록된 삭제 직전 경계를 사용해 해당 삭제의 영향을 되돌린다.

## 2. 후속 관리자 API

현재는 만들지 않으며 관리자 인증이 준비된 뒤에만 추가한다.

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/api/v1/admin/chat-room-deletions` | `roomId`, `userId`, `status`로 복구 후보 조회 |
| POST | `/api/v1/admin/chat-room-deletions/{deletionId}/restore` | 선택한 사용자의 최근 삭제 복구 |

복구 요청은 내부 감사 사유를 필수로 받는다.

```json
{
  "reason": "고객 지원 확인 후 오삭제 복구"
}
```

성공 응답은 `deletionId`, `chatRoomId`, `targetUserId`, `restoredAt`만 반환한다. 메시지 원문과 상대방 개인정보는 응답에 포함하지 않는다.

## 3. 필요한 삭제 snapshot

정확한 복구를 하려면 삭제 당시 다음 값이 보존되어야 한다.

| 값 | 설명 |
| --- | --- |
| `previous_hidden_through_message_id` | 삭제 직전 메시지 숨김 경계 |
| `hidden_through_message_id` | 해당 삭제로 새로 숨긴 경계 |
| `requested_at` | 실제 사용자 삭제 시각 |
| `purge_eligible_at` | 후속 3개월 만료 예정 시각 |
| `status` | `WAITING`, `RESTORED`, `FINALIZED` |
| `restored_at`, `restored_by_admin_id` | 관리자 복구 감사 정보 |

현재 구현의 `delete_requested_at`만으로는 여러 번 삭제한 사용자의 정확한 이전 경계를 복원할 수 없다. 따라서 이 후속 기능이 도입되기 전에 삭제 snapshot 저장을 함께 배포해야 한다. snapshot을 남기기 전에 발생한 과거 삭제는 추측해서 복구하지 않는다.

## 4. 처리 흐름

1. 관리자 권한과 필수 사유를 검사한다.
2. room → 두 member(ID 오름차순) → deletion 순서로 잠근다.
3. 선택한 삭제가 해당 사용자의 가장 최근 삭제이고 `WAITING`인지 다시 확인한다.
4. 대상 사용자의 숨김 경계를 `previous_hidden_through_message_id`로 되돌리고 채팅방을 다시 표시한다.
5. 삭제 기록을 `RESTORED`로 바꾸고 관리자·사유·복구 전후 경계를 감사 기록에 저장한다.
6. 커밋 후 대상 사용자에게만 채팅방 목록 갱신 이벤트를 보낸다.

같은 복구 요청을 반복하면 새 감사 기록을 만들지 않고 기존 성공 결과를 반환한다. 더 최근 삭제가 있거나 이미 `FINALIZED`이면 `409 Conflict`로 거부한다.

## 5. 감사 기록

`chat_room_admin_restore_audits`는 append-only로 관리한다.

- `deletion_id`, `chat_room_id`, `target_user_id`
- `admin_user_id`, 필수 `reason`
- 복구 전·후 메시지 숨김 경계
- `restored_at`, 요청 추적용 `request_id`

관리자가 복구하더라도 원래 삭제 기록을 지우지 않는다. 관리자 복구와 3개월 만료 작업이 동시에 실행되면 동일한 잠금 순서와 상태 재검사로 둘 중 하나만 성공해야 한다.
