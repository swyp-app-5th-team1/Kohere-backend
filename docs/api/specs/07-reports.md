# 채팅방 신고 API 안내

기존의 범용 `POST /api/v1/reports` 초안은 현재 제품 흐름과 맞지 않아 사용하지 않는다. 사용자 신고는 1:1 채팅방의 상대방을 대상으로 하는 아래 API 한 개로 통일한다.

```http
POST /api/v1/chat-rooms/{roomId}/reports
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "reason": "ILLEGAL_CONTENT"
}
```

프런트는 고정 신고 사유를 `ko/en`으로 직접 표시하고 선택된 code만 보낸다. 자유 입력 상세 사유, 신고자 ID, 신고 대상 사용자 ID와 증거 메시지는 보내지 않는다. 서버가 JWT와 채팅방 정본에서 이 값을 결정한다.

신규 접수는 `201 Created`, 같은 사용자가 같은 채팅방을 다시 신고하면 기존 결과와 `200 OK`다. 두 응답의 JSON 구조는 같으며 `reportId`가 반환되면 접수가 완료된 것이다. 사용자용 신고 사유 목록 API와 신고 상태 조회 API는 만들지 않는다.

정확한 reason code, 요청·응답 필드, 증거 범위와 오류는 [채팅 API 계약](../../architecture/chat/02-api-contracts.md#57-채팅방-신고)을 따른다. 관리자 목록·상세·처리는 [후속 관리자 설계](../../architecture/chat/future/01-admin-report-management.md)에서 별도로 구현한다.
