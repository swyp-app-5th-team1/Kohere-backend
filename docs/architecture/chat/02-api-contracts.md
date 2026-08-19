# API 계약

이 문서는 구현해야 할 REST endpoint와 STOMP destination의 정본이다. 상세한 내부 처리 순서는 [04-feature-flows.md](04-feature-flows.md), STOMP 보안·재연결은 [03-websocket-stomp.md](03-websocket-stomp.md)를 참고한다.

## 1. 사용자 REST API

| 구분 | Method | Path | 설명 | 성공 |
| --- | --- | --- | --- | --- |
| 기존 재사용 | POST | `/api/v1/listings/{listingId}/bookings` | 기존 입주 신청 생성 | 201 |
| 골격 완성 | POST | `/api/v1/listings/{listingId}/inquiries` | 해당 매물의 채팅방 조회 또는 생성 | 신규 201 / 기존 200 |
| 골격 완성 | GET | `/api/v1/chat-rooms` | 내 채팅방 목록 | 200 |
| 신규 | GET | `/api/v1/chat-rooms/{roomId}` | 방 헤더·상대·매물 정보 | 200 |
| 골격 완성 | GET | `/api/v1/chat-rooms/{roomId}/messages` | 과거 메시지 또는 연결 중 놓친 메시지 조회 | 200 |
| 신규 | DELETE | `/api/v1/chat-rooms/{roomId}` | 나에게만 방과 과거 이력 숨김 | 200 |
| 신규 | POST | `/api/v1/chat-rooms/{roomId}/restore` | 삭제 직후 Undo | 200 |
| 신규 | POST | `/api/v1/chat-rooms/{roomId}/block` | 방의 상대 사용자 차단 | 204 |
| 기존 재사용 | GET | `/api/v1/users/me/blocks` | 내 차단 목록 | 200 |
| 기존 재사용 | DELETE | `/api/v1/users/me/blocks/{userId}` | 차단 해제 | 204 |
| 골격 완성 | GET | `/api/v1/reports/reasons` | 로그인 사용자 언어의 신고 사유 | 200 |
| 신규 | POST | `/api/v1/chat-rooms/{roomId}/reports` | 채팅방 단위 신고 | 신규 201 / 열린 신고 재시도 200 |
| 신규 | GET | `/api/v1/reports/{reportId}` | 내가 접수한 신고 상태 | 200 |

모든 사용자용 endpoint는 로그인과 온보딩을 완료한 `ROLE_USER`만 사용할 수 있다.

## 2. 운영자 신고 API

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/api/v1/admin/chat-reports` | 상태·처리 목표일별 신고 목록 |
| GET | `/api/v1/admin/chat-reports/{reportId}` | 신고·채팅방·증거 상세 |
| PATCH | `/api/v1/admin/chat-reports/{reportId}/status` | 검토 시작 또는 최종 처리 |

운영자 endpoint는 별도 `ADMIN` 인증과 상태 변경 감사 이력이 필요하다. 관리자 인증 기반이 아직 없다면 사용자 신고 접수를 먼저 구현하고, 운영자 API는 관리자 인증과 함께 공개한다.

## 3. STOMP 경로

| 종류 | 경로 | 설명 |
| --- | --- | --- |
| WebSocket 연결 | `/ws/chat` | 실시간 연결 시작 |
| 메시지 전송 | `/app/chat-rooms/{roomId}/messages` | 텍스트를 애플리케이션 서버에 전송 |
| 방 메시지 구독 | `/topic/chat-rooms/{roomId}` | 저장 완료된 새 메시지 수신 |
| 저장 결과 | `/user/queue/chat-acks` | 내가 보낸 메시지의 DB 저장 결과 |
| 오류 | `/user/queue/chat-errors` | 권한·차단·본문 검증 오류 |
| 방 목록 이벤트 | `/user/queue/chat-room-events` | 새 방·목록 갱신·방 재노출 알림 |
| 번역 결과 | `/user/queue/chat-translations` | 받은 메시지의 사용자 언어별 번역 결과 |
| 동기화 제어 | `/app/chat/control/ping` | 구독 준비 확인 요청 |
| 동기화 제어 | `/user/queue/chat-control` | pong과 `SUBSCRIPTION_READY` 수신 |

## 4. 만들지 않는 API

| Method | Path | 이유 |
| --- | --- | --- |
| POST | `/api/v1/chat-rooms/{roomId}/messages` | 메시지 전송은 STOMP 한 경로로 통일 |
| GET | `/api/v1/chat-rooms/deleted` | 복구 가능한 삭제방 정보를 사용자에게 제공하지 않음 |
| POST | `/api/v1/chat-rooms/{roomId}/read` | 읽음 기능은 후속 구현 |

방 목록에도 이번에는 `unreadCount`를 반환하지 않는다. 기존 코드에 위 endpoint나 필수 필드가 있더라도 구현 단계에서 비노출 또는 제거해 미구현 500 응답을 남기지 않는다.

## 5. 주요 REST 계약

### 5.1 방 조회 또는 생성

`POST /api/v1/listings/{listingId}/inquiries`

요청 본문은 없다. 서버가 JWT에서 `tenantId`를 얻고 listing 모듈에서 `landlordId`를 찾는다.

```json
{
  "chatRoomId": 556,
  "created": true,
  "listingId": "6858e2000000000000000001",
  "counterpartUserId": 42
}
```

- 신규 방이면 `201 Created`, `created=true`
- 기존 방이면 `200 OK`, `created=false`
- 본인 매물 문의와 차단 관계는 거부
- 동시 요청에서도 `(listingId, tenantId, landlordId)` UNIQUE로 한 방만 생성

이 endpoint는 이름이 `inquiries`이지만 실질적으로 “같은 방을 보장하고 roomId를 반환하는” 멱등 API다. 문의하기와 신청 완료 후 진입이 함께 사용한다.

### 5.2 채팅방 목록

`GET /api/v1/chat-rooms?page=0&size=20`

- `size` 기본 20, 최대 100
- 정렬: `COALESCE(lastMessageAt, createdAt) DESC, chatRoomId DESC`
- 응답: roomId, 매물 요약, 상대 ID·표시 이름, 마지막 메시지 preview·시각, 차단 여부
- 내 `roomHiddenAt`이 설정된 방은 제외
- `unreadCount`와 `category` query는 이번 범위에서 제외

마지막 메시지가 내가 받은 메시지이고 현재 언어 번역본이 저장돼 있으면 preview는 번역본을 우선 사용한다. 내가 보낸 메시지이거나 번역본이 없으면 원문을 사용한다.

### 5.3 채팅방 단건

`GET /api/v1/chat-rooms/{roomId}`

딥링크, 새로고침, 재연결 때 방 헤더를 가져온다. 요청자가 참여자가 아니거나 현재 숨긴 방이면 일반 조회에서 노출하지 않는다.

### 5.4 메시지 이력과 누락 메시지 조회

`GET /api/v1/chat-rooms/{roomId}/messages`

이 API는 메시지를 보내는 API가 아니다. MySQL에 이미 저장된 메시지를 가져오는 API다.

- 과거 대화: 채팅방에 들어가거나 위로 스크롤할 때 사용
- 누락 보충: WebSocket이 끊긴 동안 저장된 메시지를 재연결 후 가져올 때 사용

Simple Broker는 연결이 끊긴 사용자에게 과거 메시지를 다시 재생하지 않으므로 이 REST 조회가 필요하다.

두 조회 모드는 동시에 사용하지 않는다.

| query | 의미 | 정렬 |
| --- | --- | --- |
| `cursor={messageId}` | 이 ID보다 오래된 메시지 | 최신순 |
| `afterMessageId={messageId}` | 이 ID보다 새 메시지 | 오래된 순 |
| `size=30` | 페이지 크기, 최대 100 | - |

예시:

```text
GET /api/v1/chat-rooms/556/messages?cursor=70000&size=30
GET /api/v1/chat-rooms/556/messages?afterMessageId=69950&size=100
```

결과가 한 페이지를 넘으면 마지막 `messageId`를 다음 cursor로 사용한다. 사용자별 삭제 경계보다 오래된 메시지는 응답하지 않는다.

메시지 응답은 원문을 항상 포함하고, 로그인 사용자의 현재 언어에 맞는 번역본이 저장돼 있을 때만 `translation`을 포함한다.

```json
{
  "messageId": 70051,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "chatRoomId": 556,
  "senderId": 7,
  "isMine": false,
  "type": "TEXT",
  "originalContent": "Is the room still available?",
  "translation": {
    "content": "아직 방을 구할 수 있나요?",
    "sourceLanguage": "en",
    "targetLanguage": "ko",
    "provider": "GOOGLE_CLOUD_TRANSLATION"
  },
  "sentAt": "2026-08-19T10:15:30.123456Z"
}
```

- 내가 보낸 메시지는 `translation=null`로 반환하고 원문을 기본 표시한다.
- 받은 메시지는 번역본이 있으면 번역본을 기본 표시하고 원문 보기 기능을 제공한다.
- 번역본이 아직 없거나 생성에 실패했으면 `translation=null`이며, 이때의 화면 표시는 프런트엔드가 결정한다.
- 백엔드는 `번역 중` 같은 사용자 표시 문구를 반환하지 않는다.

### 5.5 채팅방 삭제

`DELETE /api/v1/chat-rooms/{roomId}`

상대의 방과 공유 메시지는 그대로 두고 요청자 화면에서만 숨긴다. 삭제 직후 화면의 Undo 버튼에 쓸 불투명 token만 반환한다.

```json
{
  "undoToken": "f13d1fc0-f194-4d59-8a07-28b1da43825a"
}
```

같은 삭제 cycle의 재시도는 같은 token을 반환하고 내부 3개월 기한을 연장하지 않는다. 앱은 이 token을 현재 화면 메모리에만 잠시 보관하며 삭제방 목록이나 복구 가능 기한을 사용자에게 보여 주지 않는다.

### 5.6 삭제 직후 Undo

`POST /api/v1/chat-rooms/{roomId}/restore`

```json
{
  "undoToken": "f13d1fc0-f194-4d59-8a07-28b1da43825a"
}
```

현재 삭제 cycle의 token이고 내부 복구 기한 전이면 방과 해당 cycle의 이력을 다시 보이게 한다. 앱을 종료한 뒤 복구 가능한 방을 찾아 주는 기능은 제공하지 않는다.

### 5.7 차단

`POST /api/v1/chat-rooms/{roomId}/block`

요청 본문은 없다. 서버가 JWT 사용자와 방의 다른 참여자로 차단 관계를 만든다. 이미 차단돼 있어도 `204`이며, 이전 대화는 삭제하지 않는다.

### 5.8 신고 사유

`GET /api/v1/reports/reasons`

code는 언어와 관계없이 같고 label만 사용자의 `ko/en/ja` 설정에 따라 바뀐다. 번역이 없으면 영어로 fallback한다.

```json
{
  "reasons": [
    { "code": "ABUSE", "label": "욕설, 비방, 차별, 혐오" },
    { "code": "ILLEGAL_CONTENT", "label": "불법정보" },
    { "code": "SEXUAL_CONTENT", "label": "음란, 청소년 유해" },
    { "code": "PERSONAL_INFORMATION", "label": "개인정보 노출, 유포, 거래" },
    { "code": "SPAM", "label": "도배, 스팸" },
    { "code": "ETC", "label": "기타" }
  ]
}
```

클라이언트는 label을 표시하고 신고할 때 code만 보낸다. 상세 사유 입력과 `detail` 필드는 없다.

### 5.9 채팅방 신고

`POST /api/v1/chat-rooms/{roomId}/reports`

```json
{
  "reason": "ABUSE"
}
```

서버가 결정하는 값:

- `reporterId`: JWT 사용자
- `reportedUserId`: 방의 다른 참여자
- `targetType`: `CHAT_ROOM`
- `targetId`: path의 roomId
- `status`: `RECEIVED`
- `receivedAt`: 서버 접수 시각
- `reviewDueAt`: 접수 시각 + 7일
- `evidenceThroughMessageId`: 접수 시점의 마지막 메시지

최소 한 개의 텍스트 메시지가 있어야 신고할 수 있다. 같은 신고자가 같은 방에 열린 신고를 다시 보내면 새 행을 만들지 않고 기존 신고를 `200`으로 반환한다. 최종 처리 뒤에는 직전 신고 이후 새 메시지가 생긴 경우에만 다시 신고할 수 있다.

### 5.10 내 신고 상태

`GET /api/v1/reports/{reportId}`

`report.reporterId`가 JWT 사용자와 같아야 한다. 다른 사용자의 신고와 존재하지 않는 신고는 모두 같은 `404 REPORT_NOT_FOUND`로 처리한다.

사용자에게 반환:

- reportId, chatRoomId
- reason code와 현재 언어 label
- status, receivedAt, reviewDueAt, resolvedAt

사용자에게 반환하지 않음:

- 증거 원문과 snapshot
- 신고 대상 사용자 ID
- 운영자 ID와 내부 note·hold 정보

## 6. STOMP 메시지 계약

### 6.1 메시지 전송

클라이언트는 `/app/chat-rooms/{roomId}/messages`로 다음 값만 보낸다.

```json
{
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "content": "안녕하세요. 이번 주 토요일에 방문할 수 있을까요?"
}
```

`senderId`, `messageId`, `roomId`, `type`, `sentAt`은 인증 Principal, destination, 서버 시계, MySQL이 결정한다.

본문은 공백과 줄바꿈을 포함해 Unicode code point 최대 3,000자다. `sourceLanguage`, `targetLanguage`와 번역문은 클라이언트가 보내지 않는다.

### 6.2 원문 저장 완료 이벤트

공용 room topic에는 모든 참여자에게 동일한 원문 저장 결과만 발행한다.

```json
{
  "version": 1,
  "eventType": "MESSAGE_CREATED",
  "messageId": 70051,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "chatRoomId": 556,
  "senderId": 7,
  "type": "TEXT",
  "originalContent": "안녕하세요. 이번 주 토요일에 방문할 수 있을까요?",
  "sentAt": "2026-08-19T10:15:30.123456Z"
}
```

같은 `(roomId, senderId, clientMessageId)`가 재전송되면 기존 저장 결과만 돌려주고 새 DB 행과 두 번째 room broadcast를 만들지 않는다. 같은 ID에 다른 본문이 오면 충돌로 거부한다.

### 6.3 번역 결과 이벤트

원문 저장 후 수신자 언어 번역이 끝나면 해당 수신자의 `/user/queue/chat-translations`에만 결과를 보낸다.

```json
{
  "version": 1,
  "eventType": "MESSAGE_TRANSLATION_UPDATED",
  "messageId": 70051,
  "chatRoomId": 556,
  "status": "SUCCEEDED",
  "sourceLanguage": "ko",
  "targetLanguage": "en",
  "translatedContent": "Hello. Could I visit this Saturday?",
  "provider": "GOOGLE_CLOUD_TRANSLATION",
  "translatedAt": "2026-08-19T10:15:30.523456Z"
}
```

클라이언트에 보내는 최종 `status`는 다음 세 값이다.

| status | 의미 | `translatedContent` |
| --- | --- | --- |
| `SUCCEEDED` | 번역 완료 | 필수 |
| `NOT_REQUIRED` | 원문과 대상 언어가 같아 번역 불필요 | null |
| `FAILED` | 제한된 자동 재시도 후 번역 실패 | null |

내부 작업 상태인 `PENDING`은 사용자 표시 문구가 아니며 클라이언트에 보내지 않는다. 프런트엔드는 `messageId`로 원문 이벤트와 번역 이벤트를 합치고, 번역본이 있으면 받은 메시지에 우선 표시한다.
