# API 계약

이 문서는 구현해야 할 REST endpoint와 STOMP destination의 정본이다. 상세한 내부 처리 순서는 [04-feature-flows.md](04-feature-flows.md), STOMP 보안·재연결은 [03-websocket-stomp.md](03-websocket-stomp.md)를 참고한다.

이 문서의 REST JSON 예시는 공통 `ApiResponse.data` 내부 값만 표시한다. 실제 REST 응답은 프로젝트 공통 `success`, `data`, `error` 래퍼를 사용하며 STOMP payload에는 이 래퍼를 사용하지 않는다.

## 1. 사용자 REST API

| 구분 | Method | Path | 설명 | 성공 |
| --- | --- | --- | --- | --- |
| 기존 재사용 | POST | `/api/v1/listings/{listingId}/bookings` | 기존 입주 신청 생성 | 201 |
| 구현 | POST | `/api/v1/listings/{listingId}/inquiries` | 해당 매물의 채팅방을 보장하고 문의서 카드를 한 번 저장 | 신규 201 / 기존 200 |
| 골격 완성 | GET | `/api/v1/chat-rooms` | 내 채팅방 목록 | 200 |
| 신규 | GET | `/api/v1/chat-rooms/{roomId}` | 방 헤더·상대·매물 정보 | 200 |
| 골격 완성 | GET | `/api/v1/chat-rooms/{roomId}/messages` | 과거 메시지 또는 연결 중 놓친 메시지 조회 | 200 |
| 신규 | DELETE | `/api/v1/chat-rooms/{roomId}` | 나에게만 채팅방과 기존 이력 숨김 | 204 |
| 신규 | POST | `/api/v1/chat-rooms/{roomId}/block` | 방의 상대 사용자 차단 | 204 |
| 기존 재사용 | GET | `/api/v1/users/me/blocks` | 내 차단 목록 | 200 |
| 기존 재사용 | DELETE | `/api/v1/users/me/blocks/{userId}` | 차단 해제 | 204 |
| 구현 완료 | POST | `/api/v1/chat-rooms/{roomId}/reports` | 현재 채팅방의 상대방 신고 접수 | 신규 201 / 같은 방 재요청 200 |

모든 사용자용 endpoint는 로그인과 온보딩을 완료한 `ROLE_USER`만 사용할 수 있다.

## 2. 후속 고도화 API

운영자 신고 목록·상세·상태 변경 API와 관리자 전용 채팅방 삭제 복구 API는 이번 구현에 포함하지 않는다. 관리자 인증이 준비된 뒤 각각 [운영자 신고 처리 설계](future/01-admin-report-management.md)와 [관리자 채팅방 복구 설계](future/05-admin-chat-room-recovery.md)에 따라 별도로 구현한다.

관리자 복구를 고도화하더라도 일반 사용자용 `/api/v1/chat-rooms/{roomId}/restore`는 만들지 않는다.

## 3. STOMP 경로

| 종류 | 경로 | 설명 |
| --- | --- | --- |
| WebSocket 연결 | `/ws/chat` | 실시간 연결 시작 |
| 메시지 전송 | `/app/chat-rooms/{roomId}/messages` | 텍스트를 애플리케이션 서버에 전송 |
| 방 메시지 구독 | `/topic/chat-rooms/{roomId}` | 서버가 만든 새 INQUIRY_CARD·BOOKING_CARD 수신 |
| 저장 결과 | `/user/queue/chat-acks` | 내가 보낸 메시지의 DB 저장 결과 |
| 오류 | `/user/queue/chat-errors` | 권한·차단·본문 검증 오류 |
| 방 목록 이벤트 | `/user/queue/chat-room-events` | 새 방·목록 갱신·방 재노출 알림 |
| 받은 TEXT | `/user/queue/chat-translations` | 원문과 사용자 언어별 최종 번역 결과를 함께 수신 |
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

요청 본문은 없다. 서버가 JWT에서 `tenantId`를 얻고 listing 모듈에서 `landlordId`와 문의서에 필요한 공개 매물 정보를 찾는다. 따라서 앱이 사용자 ID·상대 사용자·카드 내용·가격을 직접 보내지 않는다.

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
- `created=true`이면 새 방·참여자 두 명·`INQUIRY_CARD`를 함께 저장한 결과다.
- 기존 방이면 `created=false`이며 `INQUIRY_CARD`를 새로 추가하지 않는다.
- 따라서 신청 이벤트로 먼저 생성된 방에는 `BOOKING_CARD`만 유지되고, 신청 완료 뒤 같은 API로 roomId를 받아도 문의서가 추가되지 않는다.
- 본인 매물 문의와 차단 관계는 거부
- 동시 요청에서도 `(listingId, tenantId, landlordId)` UNIQUE로 한 방만 생성

서버는 문의서 저장이 완료된 뒤 응답한다. 앱은 반환된 `chatRoomId`로 room topic을 구독하고 메시지 이력 API를 호출한다. `INQUIRY_CARD`는 대표 이미지, 매물 제목, city·district code, 매물 유형 code, 활성 방의 최소·최대 월세와 상세 이동용 `listingId`를 포함한다.

주요 오류는 다음과 같다.

- 로그인하지 않았거나 토큰이 만료됨: `401`
- 세입자가 아닌 사용자가 호출함: `403 FORBIDDEN`
- 두 사용자 중 어느 방향이든 차단 관계임: `403 CHAT_UNAVAILABLE`
- 매물이 없거나 공개 상태가 아님: `404 LISTING_NOT_FOUND`
- 본인이 소유한 매물에 문의함: `422 CHAT_SELF_INQUIRY_NOT_ALLOWED`

이 endpoint는 “같은 방을 보장하고, 새 방이면 문의서를 함께 저장한 뒤 roomId를 반환하는” 멱등 API다. 문의 API를 재시도해도 방과 문의서를 중복 생성하지 않는다. 신청 완료 후 방 진입도 같은 `roomId` 기준을 사용하지만 이미 방이 있으므로 문의서를 추가하지 않으며, `BOOKING_CARD`는 Booking 이벤트가 별도로 생성한다.

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

빈 방이면 `lastMessage`는 null이다. 마지막 메시지가 `INQUIRY_CARD` 또는 `BOOKING_CARD`이면 `preview`는 null이고 앱이 타입과 `myRole`에 맞는 고정 문구를 표시한다.

`counterpart.displayName`은 user 공개 API가 제공하는 현재 표시 이름이다. `blocked=true`는 어느 방향이든 차단 관계가 있어 새 채팅을 보낼 수 없다는 뜻이며, 상대가 나를 차단했는지는 별도 필드로 노출하지 않는다.

현재 user 모듈에는 프로필 이미지 계약이 없으므로 `counterpart`에는 이미지 URL을 넣지 않는다. 앱은 채팅방 목록과 헤더에서 기본 프로필 아이콘을 표시한다. `listing`에도 일반 채팅방에서 사용하지 않는 매물 대표 이미지를 넣지 않으며, 매물 이미지가 필요한 문의서와 신청 카드는 각각 `inquiryCard.thumbnailUrl`, `bookingCard.listing.thumbnailUrl`을 사용한다. 이 내용은 목록·단건 Swagger 응답 필드 설명에도 동일하게 명시한다.

마지막 메시지가 내가 받은 메시지이고 현재 언어 번역본이 저장돼 있으면 preview는 번역본을 우선 사용한다. 내가 보낸 메시지이거나 번역본이 없으면 원문을 사용한다.

마지막 메시지가 `BOOKING_CARD`이면 앱이 `myRole`에 맞는 고정 문구를 표시한다. 예를 들어 임차인은 “신청이 접수되었습니다”, 임대인은 “새로운 입주 신청이 도착했습니다”로 표시할 수 있다. 이 고정 문구는 Google 번역 결과가 아니라 앱의 `ko/en` UI 문구다.

마지막 메시지가 `INQUIRY_CARD`이면 앱은 `preview` 대신 “매물 문의가 시작되었습니다” 같은 고정 문구를 현재 화면 언어로 표시할 수 있다. 문의서 자체의 city·district·매물 유형도 서버 code를 기준으로 앱이 `ko/en` label을 선택한다.

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

메시지 응답은 `TEXT`, `INQUIRY_CARD`, `BOOKING_CARD` 세 종류다.

- `TEXT`: `originalContent`를 항상 포함하고, 로그인 사용자의 현재 언어에 맞는 번역본이 저장돼 있으면 `translation` 객체를, 없으면 null을 반환한다.
- `INQUIRY_CARD`: `inquiryCard`에 문의 시점의 대표 이미지·매물 제목·주소 code·매물 유형·최소/최대 월세를 포함한다. 원문과 번역본은 없다.
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
  "inquiryCard": null,
  "translation": {
    "status": "SUCCEEDED",
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
- 아직 처리 중이면 `translation=null`이다. `FAILED` 또는 `NOT_REQUIRED`가 확정되면 `translation.status`를 반환하고 `content`는 null이다.
- `FAILED`와 `NOT_REQUIRED`에서는 함께 반환된 `originalContent`를 표시한다.
- 백엔드는 `번역 중` 같은 사용자 표시 문구를 반환하지 않는다.

문의서 카드 응답 예시:

```json
{
  "messageId": 70050,
  "clientMessageId": null,
  "chatRoomId": 556,
  "senderId": null,
  "mine": false,
  "type": "INQUIRY_CARD",
  "originalContent": null,
  "bookingCard": null,
  "inquiryCard": {
    "listingId": "6858e2000000000000000001",
    "thumbnailUrl": "https://cdn.example.com/listings/cover.jpg",
    "title": "Hongdae Studio share",
    "city": "SEOUL",
    "district": "MAPO_GU",
    "listingType": "CO_LIVING",
    "monthlyRentMin": 350000,
    "monthlyRentMax": 500000
  },
  "translation": null,
  "sentAt": "2026-08-19T10:14:30.123456Z"
}
```

`thumbnailUrl`은 매물의 첫 번째 대표 이미지이며 이미지가 없으면 null이다. 월세 범위는 문의 시점의 `ACTIVE` 방 상품만 사용하고 단위는 KRW다. `View Detail`은 별도 API가 아니라 `inquiryCard.listingId`로 기존 매물 상세 화면을 연다. 문의서는 사용자 TEXT가 아니므로 `senderId`, `clientMessageId`, `originalContent`, `translation`이 null이고 `mine=false`다.

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
  "inquiryCard": null,
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

프런트는 body나 사용자 ID를 보내지 않는다. URL의 `roomId`는 목록·상세·문의 응답에서 받은 숫자를 사용하며, 서버는 JWT 사용자의 참여자 행만 숨긴다. 방이 없거나 비참여자면 `404 CHAT_ROOM_NOT_FOUND`, 숫자가 아닌 `roomId`는 `400 MALFORMED_REQUEST`다.

현재 범위의 삭제는 사용자별 논리 삭제다. 즉, 사용자의 화면에서는 즉시 사라지고 복원할 수 없지만 공유 원문을 곧바로 DB에서 지우지는 않는다. 3개월 만료 확정과 물리 삭제는 [후속 고도화](future/02-retention-and-physical-deletion.md)에서 서버 내부 정기 작업으로 구현한다.

### 5.6 차단

`POST /api/v1/chat-rooms/{roomId}/block`

요청 본문은 없다. 서버가 JWT 사용자와 방의 다른 참여자로 차단 관계를 만든다. 이미 차단돼 있어도 `204`이며, 이전 대화는 삭제하지 않는다.

### 5.7 채팅방 신고

`POST /api/v1/chat-rooms/{roomId}/reports`

```json
{
  "reason": "ABUSE_HARASSMENT_DISCRIMINATION"
}
```

프런트는 아래 고정 code를 `ko/en` 문구로 직접 표시하고 선택한 code만 보낸다. 별도의 신고 사유 조회 API와 상세 사유 입력은 없다.

| code | ko 표시 예시 | en 표시 예시 |
| --- | --- | --- |
| `ABUSE_HARASSMENT_DISCRIMINATION` | 욕설, 비방, 차별, 혐오 | Abuse, Harassment, Discrimination |
| `ILLEGAL_CONTENT` | 불법정보 | Illegal Content |
| `SEXUAL_INAPPROPRIATE_CONTENT` | 음란, 청소년 유해 | Sexual or Inappropriate Content |
| `PERSONAL_INFORMATION` | 개인정보 노출, 유포, 거래 | Personal Information |
| `SPAM` | 도배, 스팸 | Spam |
| `OTHER` | 기타 | Other |

서버가 DB 정본으로 결정하는 값:

- `reporterId`: JWT 사용자
- `reportedUserId`: 방의 다른 참여자
- `chatRoomId`: path의 roomId
- `status`: `RECEIVED`
- `receivedAt`: 서버 접수 시각
- `evidenceThroughMessageId`: 신고자에게 현재 보이는 마지막 TEXT 메시지

신고자는 현재 표시된 방만 신고할 수 있고, 신고자에게 보이는 TEXT가 최소 한 개 있어야 한다. 서버는 보이는 최근 TEXT 원문을 최대 20개 증거로 저장하며 `INQUIRY_CARD`, `BOOKING_CARD`, 번역문은 제외한다. 같은 신고자가 같은 방을 다시 신고하면 새 행을 만들거나 최초 사유·증거를 덮어쓰지 않고 기존 결과를 `200`으로 반환한다.

신규 접수는 `201`, 같은 방 재요청은 `200`이며 두 경우 모두 `reportId`, `chatRoomId`, `reason`, `status`, `receivedAt`을 반환한다. 이 응답으로 접수 성공을 확인하므로 사용자용 신고 상태 조회 API는 만들지 않는다. 신고 접수는 상대방 차단이나 채팅방 삭제를 자동 실행하지 않는다.

증거 원문, 신고 대상 사용자 ID, 보관 만료 시각과 후속 운영 정보는 사용자에게 반환하지 않는다. 관리자 조회·처리 API는 [후속 관리자 설계](future/01-admin-report-management.md)에서 별도로 구현한다.

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

클라이언트는 `type`, `inquiryCard`, `bookingCard`를 SEND할 수 없다. STOMP SEND는 사용자 `TEXT` 전용이고 `INQUIRY_CARD`와 `BOOKING_CARD`는 검증된 서버 흐름만 생성한다.

### 6.2 TEXT 저장 ACK

원문이 MySQL에 저장되면 SEND를 실행한 원래 session의 `/user/queue/chat-acks`로 즉시 ACK를 보낸다. ACK는 상대방 수신 확인이 아니라 서버 저장 성공을 뜻한다.

```json
{
  "version": 1,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "messageId": 70051,
  "sentAt": "2026-08-19T10:15:30.123456Z",
  "duplicate": false
}
```

발신 앱은 `clientMessageId`가 같은 임시 말풍선을 찾아 서버 `messageId`와 연결한다. 같은 `(roomId, senderId, clientMessageId)`가 재전송되면 기존 저장 결과를 `duplicate=true`로 돌려주고 새 DB 행과 번역 작업을 만들지 않는다. 같은 ID에 다른 본문이 오면 충돌로 거부한다.

### 6.3 서버가 만든 문의서·신청 카드 이벤트

문의서가 새로 저장되면 서버가 같은 room topic으로 다음 이벤트를 발행한다. 프런트가 보내는 이벤트가 아니므로 `clientMessageId`와 `senderId`는 null이다.

```json
{
  "version": 1,
  "eventType": "MESSAGE_CREATED",
  "messageId": 70050,
  "clientMessageId": null,
  "chatRoomId": 556,
  "senderId": null,
  "type": "INQUIRY_CARD",
  "originalContent": null,
  "bookingCard": null,
  "inquiryCard": {
    "listingId": "6858e2000000000000000001",
    "thumbnailUrl": "https://cdn.example.com/listings/cover.jpg",
    "title": "Hongdae Studio share",
    "city": "SEOUL",
    "district": "MAPO_GU",
    "listingType": "CO_LIVING",
    "monthlyRentMin": 350000,
    "monthlyRentMax": 500000
  },
  "sentAt": "2026-08-19T10:14:30.123456Z"
}
```

서버는 새 문의 방과 문의서를 함께 저장해 문의 API 재시도와 동시 호출이 방·카드·broadcast를 중복 생성하지 않게 한다. 기존 방을 반환하는 호출에서는 문의서 이벤트를 발행하지 않는다.

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
  "inquiryCard": null,
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

### 6.4 받은 TEXT 원문·번역 결합 이벤트

원문 저장 후 수신자 언어 번역이 최종 상태가 되면 해당 수신자의 `/user/queue/chat-translations`에만 보낸다. 공용 room topic으로 원문을 먼저 보내지 않으며, **원문과 번역 결과가 아래 한 이벤트에 함께 도착한다.**

```json
{
  "version": 1,
  "eventType": "MESSAGE_TRANSLATION_UPDATED",
  "messageId": 70051,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "chatRoomId": 556,
  "senderId": 7,
  "originalContent": "안녕하세요. 이번 주 토요일에 방문할 수 있을까요?",
  "status": "SUCCEEDED",
  "sourceLanguage": "ko",
  "targetLanguage": "en",
  "translatedContent": "Hello. Could I visit this Saturday?",
  "provider": "GOOGLE_CLOUD_TRANSLATION",
  "sentAt": "2026-08-19T10:15:30.123456Z",
  "translatedAt": "2026-08-19T10:15:30.523456Z"
}
```

클라이언트에 보내는 최종 `status`는 다음 세 값이다.

| status | 의미 | `translatedContent` |
| --- | --- | --- |
| `SUCCEEDED` | 번역 완료 | 필수 |
| `NOT_REQUIRED` | 원문과 대상 언어가 같아 번역 불필요 | null |
| `FAILED` | 영구 오류 또는 재시도 가능한 오류를 최대 5회 호출한 뒤 실패 | null |

내부 작업 상태인 `PENDING`과 `PROCESSING`은 클라이언트에 보내지 않는다. `SUCCEEDED`면 `translatedContent`를 기본 표시하고 원문 보기에서 `originalContent`로 전환한다. `NOT_REQUIRED` 또는 `FAILED`면 `originalContent`를 표시한다. 번역 실패에도 원문은 사라지지 않는다.

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

방 topic이 Simple Broker에 실제 등록되면 다음 이벤트를 반환한다. 빈 방이거나 사용자가 삭제한 과거 범위 밖에 새 메시지가 없으면
`highWatermark=null`이다. 즉 이 값은 채팅방 전체가 아니라 **현재 로그인 사용자에게 보이는 마지막 메시지 번호**다.

```json
{
  "version": 1,
  "eventType": "SUBSCRIPTION_READY",
  "requestId": null,
  "roomId": 556,
  "highWatermark": 70052
}
```
