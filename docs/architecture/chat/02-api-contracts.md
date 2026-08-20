# API 계약

이 문서는 구현해야 할 REST endpoint와 STOMP destination의 정본이다. 상세한 내부 처리 순서는 [04-feature-flows.md](04-feature-flows.md), STOMP 보안·재연결은 [03-websocket-stomp.md](03-websocket-stomp.md)를 참고한다.

이 문서의 REST JSON 예시는 공통 `ApiResponse.data` 내부 값만 표시한다. 실제 REST 응답은 프로젝트 공통 `success`, `data`, `error` 래퍼를 사용하며 STOMP payload에는 이 래퍼를 사용하지 않는다.

## 1. 사용자 REST API

| 구분 | Method | Path | 설명 | 성공 |
| --- | --- | --- | --- | --- |
| 기존 재사용 | POST | `/api/v1/listings/{listingId}/bookings` | 기존 입주 신청 생성 | 201 |
| 골격 완성 | POST | `/api/v1/listings/{listingId}/inquiries` | 해당 매물의 채팅방 조회 또는 생성 | 신규 201 / 기존 200 |
| 골격 완성 | GET | `/api/v1/chat-rooms` | 내 채팅방 목록 | 200 |
| 신규 | GET | `/api/v1/chat-rooms/{roomId}` | 방 헤더·상대·매물 정보 | 200 |
| 골격 완성 | GET | `/api/v1/chat-rooms/{roomId}/messages` | 과거 메시지 또는 연결 중 놓친 메시지 조회 | 200 |
| 신규 | DELETE | `/api/v1/chat-rooms/{roomId}` | 나에게만 채팅방과 기존 이력 숨김 | 204 |
| 신규 | POST | `/api/v1/chat-rooms/{roomId}/block` | 방의 상대 사용자 차단 | 204 |
| 기존 재사용 | GET | `/api/v1/users/me/blocks` | 내 차단 목록 | 200 |
| 기존 재사용 | DELETE | `/api/v1/users/me/blocks/{userId}` | 차단 해제 | 204 |
| 골격 완성 | GET | `/api/v1/reports/reasons` | 로그인 사용자 언어의 신고 사유 | 200 |
| 신규 | POST | `/api/v1/chat-rooms/{roomId}/reports` | 채팅방 단위 신고 | 신규 201 / 열린 신고 재시도 200 |
| 신규 | GET | `/api/v1/reports/{reportId}` | 내가 접수한 신고 상태 | 200 |

모든 사용자용 endpoint는 로그인과 온보딩을 완료한 `ROLE_USER`만 사용할 수 있다.

## 2. 후속 고도화 API

운영자 신고 목록·상세·상태 변경 API와 관리자 전용 채팅방 삭제 복구 API는 이번 구현에 포함하지 않는다. 관리자 인증이 준비된 뒤 각각 [운영자 신고 처리 설계](future/01-admin-report-management.md)와 [관리자 채팅방 복구 설계](future/05-admin-chat-room-recovery.md)에 따라 별도로 구현한다.

관리자 복구를 고도화하더라도 일반 사용자용 `/api/v1/chat-rooms/{roomId}/restore`는 만들지 않는다.

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
| GET | `/api/v1/chat-rooms/deleted` | 삭제한 채팅방 목록을 사용자에게 제공하지 않음 |
| POST | `/api/v1/chat-rooms/{roomId}/restore` | 사용자가 삭제한 채팅방은 복원하지 않음 |
| POST | `/api/v1/chat-rooms/{roomId}/read` | 읽음 기능은 후속 구현 |

방 목록에도 이번에는 `unreadCount`를 반환하지 않는다. 기존 코드에 위 endpoint나 필수 필드가 있더라도 구현 단계에서 비노출 또는 제거해 미구현 500 응답을 남기지 않는다.

## 5. 주요 REST 계약

### 5.1 방 조회 또는 생성

`POST /api/v1/listings/{listingId}/inquiries`

요청 본문은 없다. 서버가 JWT에서 `tenantId`를 얻고 listing 모듈에서 `landlordId`를 찾는다. 따라서 앱이 사용자 ID를 보내거나 상대 사용자를 직접 선택하지 않는다.

```json
{
  "success": true,
  "data": {
    "chatRoomId": 556,
    "created": true
  },
  "error": null
}
```

`listingId`는 요청 URL에 이미 있고 상대 정보는 채팅방 단건 조회에서 제공하므로 이 응답에 중복해서 넣지 않는다.

- 신규 방이면 `201 Created`, `created=true`
- 기존 방이면 `200 OK`, `created=false`
- 본인 매물 문의와 차단 관계는 거부
- 동시 요청에서도 `(listingId, tenantId, landlordId)` UNIQUE로 한 방만 생성

주요 오류는 다음과 같다.

- 로그인하지 않았거나 토큰이 만료됨: `401`
- 세입자가 아닌 사용자가 호출함: `403 FORBIDDEN`
- 두 사용자 중 어느 방향이든 차단 관계임: `403 CHAT_UNAVAILABLE`
- 매물이 없거나 공개 상태가 아님: `404 LISTING_NOT_FOUND`
- 본인이 소유한 매물에 문의함: `422 CHAT_SELF_INQUIRY_NOT_ALLOWED`

이 endpoint는 이름이 `inquiries`이지만 실질적으로 “같은 방을 보장하고 roomId를 반환하는” 멱등 API다. 문의하기와 신청 완료 후 진입이 함께 사용한다.

### 5.2 채팅방 목록

`GET /api/v1/chat-rooms?page=0&size=20`

- `size` 기본 20, 최대 100
- 정렬: `COALESCE(lastMessageAt, createdAt) DESC, chatRoomId DESC`
- 응답: roomId, 매물 요약, 상대 ID·표시 이름, 마지막 메시지 preview·시각, 차단 여부
- 내 `roomHiddenAt`이 설정된 방은 제외
- `unreadCount`와 `category` query는 이번 범위에서 제외

목록 항목 예시:

```json
{
  "chatRoomId": 556,
  "myRole": "LANDLORD",
  "listing": {
    "listingId": "6858e2000000000000000001",
    "title": "Hongdae Studio share",
    "address": "Seogyo-dong, Mapo-gu"
  },
  "counterpart": {
    "userId": 7,
    "displayName": "Gil dong Hong"
  },
  "lastMessage": {
    "messageId": 70051,
    "type": "TEXT",
    "preview": "안녕하세요",
    "sentAt": "2026-08-19T10:15:30.123456Z"
  },
  "blocked": false
}
```

빈 방이면 `lastMessage`는 null이다. 마지막 메시지가 `BOOKING_CARD`이면 `preview`는 null이고 앱이 `myRole`에 맞는 고정 문구를 표시한다.

`counterpart.displayName`은 user 공개 API가 제공하는 현재 표시 이름이다. `blocked=true`는 어느 방향이든 차단 관계가 있어 새 채팅을 보낼 수 없다는 뜻이며, 상대가 나를 차단했는지는 별도 필드로 노출하지 않는다.

현재 user 모듈에는 프로필 이미지 계약이 없으므로 `counterpart`에는 이미지 URL을 넣지 않는다. 앱은 채팅방 목록과 헤더에서 기본 프로필 아이콘을 표시한다. `listing`에도 일반 채팅방에서 사용하지 않는 매물 대표 이미지를 넣지 않으며, 매물 이미지가 필요한 신청 카드는 `bookingCard.listing.thumbnailUrl`을 별도로 사용한다. 이 내용은 목록·단건 Swagger 응답 필드 설명에도 동일하게 명시한다.

마지막 메시지가 내가 받은 메시지이고 현재 언어 번역본이 저장돼 있으면 preview는 번역본을 우선 사용한다. 내가 보낸 메시지이거나 번역본이 없으면 원문을 사용한다.

마지막 메시지가 `BOOKING_CARD`이면 앱이 `myRole`에 맞는 고정 문구를 표시한다. 예를 들어 임차인은 “신청이 접수되었습니다”, 임대인은 “새로운 입주 신청이 도착했습니다”로 표시할 수 있다. 이 고정 문구는 Google 번역 결과가 아니라 앱의 `ko/en` UI 문구다.

### 5.3 채팅방 단건

`GET /api/v1/chat-rooms/{roomId}`

딥링크, 새로고침, 재연결 때 방 헤더를 가져온다. 요청자가 참여자가 아니거나 현재 숨긴 방이면 일반 조회에서 노출하지 않는다.

방이 없거나, 요청자가 참여자가 아니거나, 요청자에게 숨겨진 방이면 모두 `404 CHAT_ROOM_NOT_FOUND`를 반환한다. 제3자가 roomId를 바꿔
보며 실제 방의 존재 여부를 구분하지 못하게 하기 위한 규칙이다.

응답의 `myRole`은 앱이 신청 카드를 임차인용 또는 임대인용으로 표시할 때 사용한다.

```json
{
  "chatRoomId": 556,
  "myRole": "LANDLORD",
  "listing": {
    "listingId": "6858e2000000000000000001",
    "title": "Hongdae Studio share",
    "address": "Seogyo-dong, Mapo-gu"
  },
  "counterpart": {
    "userId": 7,
    "displayName": "Gil dong Hong"
  },
  "blocked": false
}
```

### 5.4 메시지 이력과 누락 메시지 조회

`GET /api/v1/chat-rooms/{roomId}/messages`

이 API는 메시지를 보내는 API가 아니다. MySQL에 이미 저장된 메시지를 가져오는 API다.

- 과거 대화: 채팅방에 들어가거나 위로 스크롤할 때 사용
- 누락 보충: WebSocket이 끊긴 동안 저장된 메시지를 재연결 후 가져올 때 사용

Simple Broker는 연결이 끊긴 사용자에게 과거 메시지를 다시 재생하지 않으므로 이 REST 조회가 필요하다. 앱은 채팅방 구독 준비 직후와 WebSocket 재연결 직후 이 API를 자동으로 호출한다. 계속 일정 간격으로 조회하는 polling 방식은 아니다.

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

공통 응답의 `data`는 다음 모양이다. `content`가 실제 메시지 배열이며, `hasNext=true`일 때 `nextCursor`를 같은 조회 방향의 다음 요청에 사용한다.

```json
{
  "content": [],
  "nextCursor": null,
  "hasNext": false
}
```

메시지 응답은 `TEXT`와 `BOOKING_CARD` 두 종류다.

- `TEXT`: `originalContent`를 항상 포함하고, 로그인 사용자의 현재 언어에 맞는 번역본이 저장돼 있으면 `translation` 객체를, 없으면 null을 반환한다.
- `BOOKING_CARD`: `bookingCard`에 신청 시점의 매물·신청자·입주 조건·금액 스냅샷을 포함한다. 원문과 번역본은 없다.

```json
{
  "messageId": 70051,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "chatRoomId": 556,
  "senderId": 7,
  "mine": false,
  "type": "TEXT",
  "originalContent": "Is the room still available?",
  "bookingCard": null,
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

신청 카드 응답 예시:

```json
{
  "messageId": 70052,
  "clientMessageId": null,
  "chatRoomId": 556,
  "senderId": null,
  "mine": false,
  "type": "BOOKING_CARD",
  "originalContent": null,
  "translation": null,
  "bookingCard": {
    "bookingId": 123,
    "listing": {
      "listingId": "6858e2000000000000000001",
      "thumbnailUrl": "https://cdn.example.com/listings/cover.jpg",
      "title": "Hongdae Studio share",
      "address": "Seogyo-dong, Mapo-gu",
      "monthlyRent": 420000
    },
    "applicant": {
      "userId": 7,
      "name": "Gil dong Hong",
      "gender": "MALE",
      "country": "MN",
      "countryName": "Mongolia",
      "email": "kohere@gmail.com"
    },
    "roomOfferId": "room-a",
    "roomOfferName": "Room A",
    "moveInDate": "2026-06-15",
    "contractPeriod": 3,
    "deposit": 0,
    "totalAmount": 1260000
  },
  "sentAt": "2026-08-19T10:15:30.123456Z"
}
```

`BOOKING_CARD`는 신청을 저장할 때 이미 조회한 매물·객실·신청자 정보와 기존 금액 계산 방식을 재사용해 만든다. Booking 모듈은 이 정보를 `BookingCreatedEvent`에 신청 시점 사본으로 담고, Chat 모듈은 별도의 Booking 조회 API 없이 카드를 저장한다. 프런트엔드는 `myRole=TENANT|LANDLORD`에 따라 같은 데이터를 서로 다른 카드 UI로 배치한다. 카드의 고정 라벨은 앱이 `ko/en`으로 표시하며 이 구조화 데이터는 Google 번역에 보내지 않는다.

### 5.5 채팅방 삭제

`DELETE /api/v1/chat-rooms/{roomId}`

상대방의 채팅방과 공유 메시지는 그대로 두고 요청자의 화면에서만 채팅방과 기존 이력을 숨긴다. 성공 응답은 `204 No Content`이며 JSON 응답 본문은 없다.

- 사용자가 삭제를 취소하거나 과거 이력을 복원하는 API는 제공하지 않는다.
- 같은 숨김 상태에서 DELETE를 재시도해도 `204`이며 삭제 시각과 숨김 경계를 다시 변경하지 않는다.
- 삭제 시각은 사용자별로 기록해 후속 3개월 보존 정책의 기준으로 사용한다.
- 같은 매물에서 다시 문의하거나 실제 새 메시지가 도착하면 내부적으로 같은 채팅방을 다시 표시할 수 있지만, 삭제 이전 이력은 계속 숨긴다. 이는 삭제 복원이 아니라 새 대화의 시작이다.

현재 범위의 삭제는 사용자별 논리 삭제다. 즉, 사용자의 화면에서는 즉시 사라지고 복원할 수 없지만 공유 원문을 곧바로 DB에서 지우지는 않는다. 3개월 만료 확정과 물리 삭제는 [후속 고도화](future/02-retention-and-physical-deletion.md)에서 서버 내부 정기 작업으로 구현한다.

### 5.6 차단

`POST /api/v1/chat-rooms/{roomId}/block`

요청 본문은 없다. 서버가 JWT 사용자와 방의 다른 참여자로 차단 관계를 만든다. 이미 차단돼 있어도 `204`이며, 이전 대화는 삭제하지 않는다.

### 5.7 신고 사유

`GET /api/v1/reports/reasons`

code는 언어와 관계없이 같고 label만 사용자의 `ko/en` 설정에 따라 바뀐다. 번역이 없으면 영어로 fallback한다.

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

### 5.8 채팅방 신고

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
- `evidenceThroughMessageId`: 접수 시점의 마지막 메시지

최소 한 개의 텍스트 메시지가 있어야 신고할 수 있다. 같은 신고자가 같은 방을 다시 신고하면 새 행을 만들지 않고 기존 신고를 `200`으로 반환한다. 처리 완료 후 재신고 규칙은 운영자 처리와 함께 후속 고도화에서 정한다.

### 5.9 내 신고 상태

`GET /api/v1/reports/{reportId}`

`report.reporterId`가 JWT 사용자와 같아야 한다. 다른 사용자의 신고와 존재하지 않는 신고는 모두 같은 `404 REPORT_NOT_FOUND`로 처리한다.

사용자에게 반환:

- reportId, chatRoomId
- reason code와 현재 언어 label
- status, receivedAt

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

클라이언트는 `type`이나 `bookingCard`를 SEND할 수 없다. STOMP SEND는 사용자 `TEXT` 전용이고 `BOOKING_CARD`는 신청 저장 후 서버만 생성한다.

### 6.2 TEXT 원문 저장 완료 이벤트

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
  "bookingCard": null,
  "sentAt": "2026-08-19T10:15:30.123456Z"
}
```

같은 `(roomId, senderId, clientMessageId)`가 재전송되면 기존 저장 결과만 돌려주고 새 DB 행과 두 번째 room broadcast를 만들지 않는다. 같은 ID에 다른 본문이 오면 충돌로 거부한다.

### 6.3 서버가 만든 신청 카드 이벤트

신청 저장이 완료되면 서버가 같은 room topic으로 다음 이벤트를 발행한다. 프런트엔드가 보내는 이벤트가 아니므로 `clientMessageId`와 `senderId`는 null이다.

```json
{
  "version": 1,
  "eventType": "MESSAGE_CREATED",
  "messageId": 70052,
  "clientMessageId": null,
  "chatRoomId": 556,
  "senderId": null,
  "type": "BOOKING_CARD",
  "originalContent": null,
  "bookingCard": {
    "bookingId": 123,
    "listing": {
      "listingId": "6858e2000000000000000001",
      "thumbnailUrl": "https://cdn.example.com/listings/cover.jpg",
      "title": "Hongdae Studio share",
      "address": "Seogyo-dong, Mapo-gu",
      "monthlyRent": 420000
    },
    "applicant": {
      "userId": 7,
      "name": "Gil dong Hong",
      "gender": "MALE",
      "country": "MN",
      "countryName": "Mongolia",
      "email": "kohere@gmail.com"
    },
    "roomOfferId": "room-a",
    "roomOfferName": "Room A",
    "moveInDate": "2026-06-15",
    "contractPeriod": 3,
    "deposit": 0,
    "totalAmount": 1260000
  },
  "sentAt": "2026-08-19T10:15:30.123456Z"
}
```

서버는 `(chatRoomId, bookingId)`를 유일하게 저장해 같은 신청 이벤트가 재처리돼도 카드와 broadcast를 두 번 만들지 않는다.

### 6.4 번역 결과 이벤트

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
| `FAILED` | 재시도 가능한 오류를 포함해 최대 5회 호출 후 번역 실패 | null |

내부 작업 상태인 `PENDING`은 사용자 표시 문구가 아니며 클라이언트에 보내지 않는다. 프런트엔드는 `messageId`로 원문 이벤트와 번역 이벤트를 합치고, 번역본이 있으면 받은 메시지에 우선 표시한다.

### 6.5 구독 준비 제어 이벤트

앱은 control queue를 구독한 뒤 다음 ping을 보낸다.

```json
{
  "version": 1,
  "requestId": "c24b8f3f-38cf-49f0-a81e-f24f73028db0"
}
```

서버는 같은 `requestId`로 `PONG`을 반환한다.

```json
{
  "version": 1,
  "eventType": "PONG",
  "requestId": "c24b8f3f-38cf-49f0-a81e-f24f73028db0",
  "roomId": null,
  "highWatermark": null
}
```

방 topic 구독이 준비되면 다음 이벤트를 반환한다. 빈 방은 `highWatermark=null`이다.

```json
{
  "version": 1,
  "eventType": "SUBSCRIPTION_READY",
  "requestId": null,
  "roomId": 556,
  "highWatermark": 70052
}
```
