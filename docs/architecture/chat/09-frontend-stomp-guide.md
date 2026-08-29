# 프론트엔드 1:1 채팅 REST·STOMP 연동 가이드

> 대상: 앱·웹 프론트엔드 개발자
>
> 최종 수정: 2026-08-28
>
> Swagger: `Chats → GET /api/v1/chat/stomp-guide`

이 문서는 Kohere 1:1 채팅 화면을 프론트엔드에서 구현할 때 필요한 REST API와 WebSocket/STOMP 연결 순서를 쉽게 설명한다. REST는 채팅방·저장된 이력을 조회하거나 삭제·차단·신고할 때 사용하고, STOMP는 새 메시지를 실시간으로 주고받을 때 사용한다.

Swagger에서는 REST API를 직접 실행할 수 있다. WebSocket/STOMP는 Swagger에서 직접 실행할 수 없으므로 Swagger와 이 문서에서 주소·payload를 확인한 뒤 앱의 STOMP 클라이언트로 연결한다.

정확한 protocol 규칙이 필요하면 [03-websocket-stomp.md](03-websocket-stomp.md)를 따른다.

문의서 카드만 연동할 때 필요한 API·응답·UI 규칙은 [10-inquiry-card-frontend-guide.md](10-inquiry-card-frontend-guide.md)에 따로 모았다.

iOS FCM 토큰 등록·갱신·로그아웃 삭제와 푸시 클릭 채팅방 이동은 [11-frontend-push-notification-guide.md](11-frontend-push-notification-guide.md)를 참고한다.

## 1. 핵심 용어

| 용어 | 쉬운 설명 |
| --- | --- |
| WebSocket | 서버와 연결을 계속 유지해 새 메시지를 즉시 주고받는 통로 |
| STOMP | WebSocket 통로에서 연결·구독·전송을 구분하는 메시지 규칙 |
| SEND | 서버에 메시지나 제어 요청을 보내는 동작 |
| SUBSCRIBE | 특정 경로에서 발생하는 새 이벤트를 계속 받겠다고 등록하는 동작 |
| topic | 같은 채팅방 참여자에게 서버 생성 INQUIRY_CARD·BOOKING_CARD를 방송하는 실시간 경로 |
| 개인 queue | ACK·오류처럼 현재 사용자 session만 받아야 하는 결과 경로 |
| ACK | 내가 보낸 TEXT가 MySQL에 저장됐다는 애플리케이션 결과 |

topic과 queue는 데이터를 저장하는 장소가 아니다. 채팅 기록의 정본은 MySQL이며, 실시간 이벤트를 놓치면 REST 메시지 이력 API로 복구한다.

## 2. 프론트에서 사용하는 전체 흐름

### 채팅 탭에서 기존 채팅방을 여는 경우

```text
내 채팅방 목록 조회
  → 사용자가 채팅방 선택
  → 채팅방 상세와 저장된 메시지 조회
  → WebSocket/STOMP 연결·구독
  → 새 TEXT·INQUIRY_CARD·BOOKING_CARD 실시간 수신
```

### 매물 화면에서 문의하는 경우

```text
사용자가 문의하기 선택
  → 서버가 기존 채팅방을 찾거나 새 방을 생성
  → 대화 흐름상 필요하면 문의서를 저장
  → 응답으로 받은 chatRoomId로 채팅방 상세·메시지 조회
  → WebSocket/STOMP 연결·구독
```

문의하기로 새 방이 만들어지면 서버가 매물 요약 `INQUIRY_CARD`를 첫 메시지로 함께 저장한다. 이미 방이 있어도 문의서가 없거나 최근 문의서 뒤에 `TEXT`·`BOOKING_CARD`가 있거나 요청자가 최근 문의서를 볼 수 없으면 새 문의서를 저장한다. 요청자에게 보이는 최근 문의서가 마지막 메시지이면 같은 문의서를 연속 저장하지 않는다. 문의하기와 입주 신청은 매물·임차인·임대인이 같으면 같은 `chatRoomId`를 사용한다.

모든 REST 요청에는 로그인으로 받은 access token을 다음 header로 보낸다.

```http
Authorization: Bearer <accessToken>
```

### REST 공통 응답 형태

채팅 REST API는 삭제·차단의 `204 No Content`를 제외하면 다음 공통 형태로 응답한다.

| 필드 | 의미 |
| --- | --- |
| `success` | 요청 성공 여부. 성공하면 true, 실패하면 false |
| `data` | 성공 결과. 실패하면 null이며 실제 내부 필드는 각 API 표에서 설명 |
| `error` | 실패 정보. 성공하면 null |
| `error.code` | 프론트 분기 처리에 사용하는 안정적인 오류 코드 |
| `error.message` | 사용자에게 표시할 수 있는 현지화된 오류 문구 |
| `error.errors` | 입력값별 오류 목록. 필드 오류가 없으면 빈 배열 |
| `error.errors[].field` | 잘못된 요청 필드 이름 |
| `error.errors[].reason` | 해당 필드가 잘못된 이유 |

프론트 로직은 바뀔 수 있는 `error.message` 문구가 아니라 `error.code`를 기준으로 처리한다.

## 3. 문의 채팅방 열기

매물 상세 화면에서 사용자가 `문의하기`를 눌렀을 때 호출한다.

```http
POST /api/v1/listings/{listingId}/inquiries
Authorization: Bearer <accessToken>
```

요청 body는 없다. 프론트는 URL에 매물 `listingId`만 넣는다. 서버가 access token에서 현재 사용자를 확인하고 매물 정보에서 임대인을 찾아 채팅방을 결정한다.

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

| 필드 | 의미 |
| --- | --- |
| `chatRoomId` | 채팅방 화면·REST 조회·STOMP 구독에 사용하는 서버 채팅방 ID |
| `created` | 이번 요청에서 새 채팅방을 만들었으면 true, 기존 방을 찾았으면 false. 문의서 생성 여부와는 무관 |

- 새 방이면 `201 Created`와 `created=true`가 오며 문의서가 이미 MySQL에 저장된 상태다.
- 이미 같은 매물·임차인·임대인의 방이 있으면 `200 OK`와 `created=false`가 온다.
- `created=false`여도 대화 흐름상 필요하면 기존 방에 새 문의서가 추가될 수 있다.
- 프론트는 메시지 이력에서 실제 저장된 문의서를 확인한다.
- 프론트 처리는 두 경우가 같다. 반환된 `chatRoomId`로 같은 채팅방 화면을 연다.
- 사용자가 과거에 이 방을 삭제했다가 다시 문의한 경우에도 기존 `chatRoomId`를 사용하지만, 그 사용자가 삭제 전에 보던 과거 메시지는 다시 보여 주지 않는다.
- 신청 완료 뒤 roomId만 찾으려고 이 API를 호출하지 않는다. 이 API 호출 자체가 실제 문의 행동이므로 새 문의서가 저장될 수 있다.

새 문의서는 새 방과 기존 방 모두 POST 응답 전에 저장될 수 있다. 프론트가 room topic을 구독하기 전이면 실시간 이벤트를 놓칠 수 있으므로 문의 응답을 받은 뒤에는 항상 메시지 이력 API를 호출한다. 이후 연결 중 생기는 서버 카드는 room topic으로 받는다.

## 4. 내 채팅방 목록 조회

채팅 탭을 열었을 때 현재 사용자에게 보이는 채팅방을 최근 활동 순으로 조회한다.

```http
GET /api/v1/chat-rooms?page=0&size=20
Authorization: Bearer <accessToken>
```

| query | 의미 |
| --- | --- |
| `page` | 0부터 시작하는 페이지 번호. 첫 페이지는 0 |
| `size` | 한 번에 받을 채팅방 수. 기본 20, 최대 100 |

```json
{
  "success": true,
  "data": {
    "content": [
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
          "preview": "Hello",
          "sentAt": "2026-08-19T10:15:30.123456Z"
        },
        "blocked": false
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 1,
      "totalPages": 1,
      "hasNext": false
    }
  },
  "error": null
}
```

| 필드 | 프론트에서 사용하는 곳 |
| --- | --- |
| `data.content` | 화면에 표시할 채팅방 배열. 방이 없으면 빈 배열 |
| `data.content[].chatRoomId` | 채팅방 선택 후 상세·메시지 조회와 STOMP 구독에 사용할 서버 채팅방 ID |
| `data.content[].myRole` | `TENANT` 또는 `LANDLORD`. BOOKING_CARD의 역할별 UI를 고를 때 사용 |
| `data.content[].listing.listingId` | 이 채팅이 어떤 매물에 관한 것인지 나타내는 매물 ID |
| `data.content[].listing.title` | 채팅 목록에 표시할 매물 제목 |
| `data.content[].listing.address` | 채팅 목록에 표시할 매물 주소 |
| `data.content[].counterpart.userId` | 상대방의 서버 사용자 ID |
| `data.content[].counterpart.displayName` | 목록에 표시할 상대방 이름. 프로필 이미지는 아직 없으므로 기본 아이콘 사용 |
| `data.content[].lastMessage` | 현재 사용자에게 보이는 마지막 메시지. 메시지가 없으면 null |
| `data.content[].lastMessage.messageId` | 마지막 메시지의 서버 ID. 정렬·중복 제거 기준 |
| `data.content[].lastMessage.type` | 마지막 메시지 종류. `TEXT`, `INQUIRY_CARD`, `BOOKING_CARD` |
| `data.content[].lastMessage.preview` | 목록 두 번째 줄에 표시할 TEXT 원문. 카드이면 null일 수 있음 |
| `data.content[].lastMessage.sentAt` | 마지막 메시지가 저장된 시각 |
| `data.content[].blocked` | 어느 방향이든 차단 관계가 있어 새 TEXT를 보낼 수 없으면 true |
| `data.page.number` | 현재 페이지 번호. 첫 페이지는 0 |
| `data.page.size` | 한 페이지에 요청한 최대 채팅방 수 |
| `data.page.totalElements` | 현재 사용자에게 보이는 전체 채팅방 수 |
| `data.page.totalPages` | 전체 페이지 수. 채팅방이 없으면 0 |
| `data.page.hasNext` | 다음 채팅방 페이지가 더 있으면 true |

`lastMessage.type=BOOKING_CARD`이면 `preview`는 null일 수 있다. 이때 프론트가 `myRole`에 맞게 “신청이 접수되었습니다” 또는 “새로운 입주 신청이 도착했습니다” 같은 고정 문구를 `ko/en`으로 표시한다.

`lastMessage.type=INQUIRY_CARD`이면 프론트가 “매물 문의가 시작되었습니다” 같은 고정 문구를 현재 화면 언어로 표시한다.

## 5. 채팅방 상세 조회

목록에서 채팅방을 선택했거나 문의 API에서 `chatRoomId`를 받은 뒤, 채팅방 상단 제목·상대·매물 정보를 가져올 때 호출한다. 메시지 배열은 이 응답에 포함되지 않는다.

```http
GET /api/v1/chat-rooms/{roomId}
Authorization: Bearer <accessToken>
```

```json
{
  "success": true,
  "data": {
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
  },
  "error": null
}
```

| 필드 | 의미 |
| --- | --- |
| `data.chatRoomId` | 현재 연 채팅방 ID. 메시지 조회와 STOMP 구독에 사용 |
| `data.myRole` | 현재 사용자의 역할. `TENANT`=임차인, `LANDLORD`=임대인 |
| `data.listing.listingId` | 이 채팅이 어떤 매물에 관한 것인지 나타내는 매물 ID |
| `data.listing.title` | 채팅방 상단에 표시할 매물 제목 |
| `data.listing.address` | 채팅방 상단에 표시할 매물 주소 |
| `data.counterpart.userId` | 현재 대화 상대의 `users.id` |
| `data.counterpart.displayName` | 채팅방 상단에 표시할 상대방 이름 |
| `data.blocked` | 어느 방향이든 차단 관계가 있어 새 TEXT를 보낼 수 없으면 true |

- `listing`은 방을 만들 때 저장한 표시용 정보다. 매물이 나중에 비공개돼도 기존 채팅방에서 어떤 매물에 관한 대화인지 보여 줄 수 있다.
- `counterpart.userId`는 상대방의 `users.id`다. 차단 해제처럼 상대 ID가 필요한 기존 user API에서 사용할 수 있다.
- `blocked=true`이면 과거 대화는 보여 주되 TEXT 입력창을 비활성화한다.
- 방이 없거나 현재 사용자가 참여자가 아니거나 현재 사용자에게 숨겨진 방이면 `404 CHAT_ROOM_NOT_FOUND`다.

## 6. 연결 주소

| 환경 | WebSocket 주소 |
| --- | --- |
| 개발 서버 | `wss://dev.kohere.app/ws/chat` |
| 로컬 서버 | `ws://localhost:8080/ws/chat` |

웹에서 현재 host를 그대로 사용한다면 다음처럼 만들 수 있다.

```javascript
const webSocketScheme = location.protocol === "https:" ? "wss:" : "ws:";
const webSocketUrl = `${webSocketScheme}//${location.host}/ws/chat`;
```

`https` 페이지에서는 보안 연결인 `wss`를 사용해야 한다.

## 7. JWT 인증

WebSocket 통로가 열린 뒤 STOMP `CONNECT`의 native header에 access token을 넣는다.

```text
Authorization: Bearer {accessToken}
```

JWT를 다음처럼 URL에 넣으면 안 된다.

```text
wss://dev.kohere.app/ws/chat?token=eyJ...
```

URL은 브라우저 방문 기록, 프록시·서버 로그, APM 등에 남을 수 있어 토큰이 노출될 위험이 있다. 브라우저 WebSocket handshake에는 임의 HTTP `Authorization` header를 안정적으로 넣기 어려우므로 실제 인증은 STOMP CONNECT header에서 처리한다.

연결할 수 있는 사용자는 로그인과 온보딩을 완료한 `ROLE_USER`다. 토큰을 갱신했다면 기존 연결에 토큰만 바꾸지 말고 새 토큰으로 다시 연결한다.

## 8. 실시간 연결 순서

```text
WebSocket 연결
  → STOMP CONNECT(JWT)
  → 개인 queue 먼저 구독
  → PING 전송 / PONG 확인
  → 채팅방 topic 구독
  → SUBSCRIPTION_READY 확인
  → REST로 누락 메시지 보충
  → TEXT 전송·원문+번역 결합 수신 / INQUIRY_CARD·BOOKING_CARD 수신
```

사용자가 버튼을 눌러 각 단계를 실행하는 것이 아니다. 채팅방 화면에 들어갈 때 프론트 코드가 자동으로 수행한다.

## 9. 먼저 구독할 개인 queue

개인 queue는 서버가 **현재 사용자 session에만 보내는 처리 결과와 알림을 받는 경로**다. TEXT를 보내기 전에 먼저 구독해야 저장 성공 ACK나 오류를 놓치지 않는다.

| queue | 받는 내용 | 프론트 처리 |
| --- | --- | --- |
| `/user/queue/chat-control` | `PONG`, `SUBSCRIPTION_READY` | 연결·방 구독 준비 상태 확인 |
| `/user/queue/chat-acks` | 내가 보낸 TEXT의 DB 저장 성공 결과 | 임시 말풍선을 전송 완료로 변경 |
| `/user/queue/chat-errors` | 내가 보낸 TEXT의 검증·권한 오류 | 해당 말풍선을 실패 상태로 표시 |
| `/user/queue/chat-room-events` | 채팅방 생성·갱신·재표시 알림 | REST 채팅방 목록 다시 조회 |
| `/user/queue/chat-translations` | 받은 TEXT의 원문과 최종 번역 결과 | 번역 성공이면 번역문 우선 표시, 실패·불필요면 원문 표시 |

다른 사용자의 개인 queue는 구독할 수 없다. 한 WebSocket session은 최대 16개 구독을 유지할 수 있으며 같은 destination을 중복 구독하지 않는다.

## 10. 개인 queue 준비 확인: PING/PONG

개인 queue를 구독한 뒤 `/app/chat/control/ping`으로 다음 값을 보낸다.

```json
{
  "version": 1,
  "requestId": "c24b8f3f-38cf-49f0-a81e-f24f73028db0"
}
```

`requestId`는 프론트가 ping마다 생성하는 UUID다. `/user/queue/chat-control`에 같은 `requestId`의 `PONG`이 오면 개인 queue를 정상적으로 받을 수 있다는 뜻이다.

```json
{
  "version": 1,
  "eventType": "PONG",
  "requestId": "c24b8f3f-38cf-49f0-a81e-f24f73028db0",
  "roomId": null,
  "highWatermark": null
}
```

| 필드 | 의미 |
| --- | --- |
| `version` | payload 형식 버전. 현재 1 |
| `eventType` | 개인 queue 준비 확인 응답인 `PONG` |
| `requestId` | PING에서 프론트가 보낸 UUID. 어떤 PING의 결과인지 연결할 때 사용 |
| `roomId` | 방 구독 응답이 아니므로 null |
| `highWatermark` | 방 구독 응답이 아니므로 null |

## 11. 채팅방 topic 구독

다음 경로의 `{roomId}`를 서버에서 받은 숫자 채팅방 ID로 바꿔 구독한다.

```text
/topic/chat-rooms/{roomId}
```

예를 들어 `roomId=556`이면 다음 경로를 구독한다.

```text
/topic/chat-rooms/556
```

이 topic으로 서버가 만든 새 `INQUIRY_CARD`와 `BOOKING_CARD`가 실시간으로 온다. 받은 사용자 `TEXT`는 원문과 번역 결과를 함께 보호해야 하므로 수신자 개인 `/user/queue/chat-translations`로 온다.

### SUBSCRIPTION_READY

SUBSCRIBE frame을 보낸 것만으로 서버 broker 등록이 끝났다고 볼 수 없다. 실제 구독 등록이 완료되면 `/user/queue/chat-control`로 다음 이벤트가 온다.

```json
{
  "version": 1,
  "eventType": "SUBSCRIPTION_READY",
  "requestId": null,
  "roomId": 556,
  "highWatermark": 70052
}
```

| 필드 | 의미 |
| --- | --- |
| `version` | payload 형식 버전. 현재 1 |
| `eventType` | 구독 준비 완료를 나타내는 `SUBSCRIPTION_READY` |
| `requestId` | PING 응답이 아니므로 null |
| `roomId` | 실제 구독 준비가 끝난 채팅방 번호 |
| `highWatermark` | 준비 완료 시점에 현재 사용자에게 보이는 마지막 서버 `messageId`. 메시지가 없으면 null |

`highWatermark`는 메시지 개수가 아니다. 구독 등록 중 놓칠 수 있는 메시지를 **어디까지 REST로 확인해야 하는지 알려주는 종료 기준**이다.

- `afterMessageId`: 프론트가 이전에 REST로 연속 확인한 마지막 메시지 번호. 누락 보충의 시작점
- `highWatermark`: 이번 구독 준비 시점에 서버가 알려 준 마지막 메시지 번호. 누락 보충의 종료점

재연결 시 프론트는 `afterMessageId` 이후 메시지를 REST로 조회해 `highWatermark`까지 확인한 뒤 동기화 checkpoint를 전진시킨다. topic에서 더 큰 ID 하나를 받았다는 이유만으로 checkpoint를 바로 올리면 중간 메시지를 영구히 놓칠 수 있다.

`SUBSCRIPTION_READY`가 5초 안에 오지 않으면 같은 socket에 구독을 계속 추가하지 말고 연결을 닫은 뒤 재연결한다.

## 12. 저장된 메시지 이력 조회

채팅방에 들어갈 때, 위로 스크롤할 때, WebSocket 재연결 뒤 놓친 메시지를 보충할 때 호출한다. 이 API는 메시지를 보내는 API가 아니라 MySQL에 이미 저장된 메시지를 읽는 API다.

```http
GET /api/v1/chat-rooms/{roomId}/messages?size=30
Authorization: Bearer <accessToken>
```

| 상황 | 요청 방식 |
| --- | --- |
| 채팅방 최초 진입 | `cursor`, `afterMessageId` 없이 최근 메시지를 조회 |
| 위로 스크롤해 과거 이력 조회 | 이전 응답의 `nextCursor`를 `cursor`로 전달 |
| 재연결 뒤 누락 보충 | 프론트가 마지막으로 연속 확인한 `messageId`를 `afterMessageId`로 전달 |

`cursor`와 `afterMessageId`는 서로 반대 방향의 조회이므로 한 요청에 같이 보내면 `400 INVALID_INPUT`이다. `size`는 기본 30, 최대 100이다.

```http
# 70051보다 오래된 메시지 조회
GET /api/v1/chat-rooms/556/messages?cursor=70051&size=30

# 70051보다 새로 저장된 메시지 조회
GET /api/v1/chat-rooms/556/messages?afterMessageId=70051&size=100
```

TEXT 한 건이 포함된 응답 예시는 다음과 같다.

```json
{
  "success": true,
  "data": {
    "content": [
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
    ],
    "nextCursor": "70051",
    "hasNext": true
  },
  "error": null
}
```

| 필드 | 의미 |
| --- | --- |
| `content` | 화면에 추가할 메시지 배열 |
| `messageId` | 서버가 만든 최종 메시지 ID. REST·STOMP 결과를 합칠 때 중복 제거 기준 |
| `clientMessageId` | TEXT 전송 때 발신 프론트가 만든 UUID. 서버 카드는 null |
| `chatRoomId` | 메시지가 속한 서버 채팅방 ID |
| `senderId` | TEXT를 보낸 사용자의 서버 ID. 서버 카드는 null |
| `mine` | 현재 로그인 사용자가 보낸 TEXT이면 true |
| `type` | `TEXT`, `INQUIRY_CARD`, `BOOKING_CARD` |
| `originalContent` | 수정하지 않은 TEXT 원문. 서버 카드는 null |
| `translation` | 현재 사용자를 위한 최종 번역 결과. 없거나 아직 처리 중이면 null |
| `translation.status` | `SUCCEEDED`, `NOT_REQUIRED`, `FAILED` 중 최종 번역 상태 |
| `translation.content` | 번역 성공 시 표시할 번역문. 그 외 상태는 null |
| `translation.sourceLanguage` | 번역 API가 감지한 원문 언어 코드 |
| `translation.targetLanguage` | 현재 사용자의 대상 언어인 `ko` 또는 `en` |
| `translation.provider` | 번역 제공자. 현재 `GOOGLE_CLOUD_TRANSLATION` |
| `inquiryCard` | 서버가 저장한 문의서 카드. 다른 타입은 null이며 내부 필드는 15.1절의 표와 동일 |
| `bookingCard` | 서버가 저장한 신청 카드. 다른 타입은 null이며 내부 필드는 15.2절의 표와 동일 |
| `sentAt` | 메시지가 서버에 저장된 시각 |
| `nextCursor` | 다음 페이지가 있을 때 다음 요청에 그대로 넣을 메시지 ID |
| `hasNext` | 같은 방향으로 조회할 다음 페이지가 있으면 true |

`hasNext=true`이면 현재 요청이 `cursor` 방식이었는지 `afterMessageId` 방식이었는지 유지한 채 `nextCursor` 값을 다음 요청에 넣는다. `hasNext=false`이면 더 요청하지 않는다.

- 받은 TEXT에서 `translation.status=SUCCEEDED`이면 `translation.content`를 기본 표시하고 원문 보기에서 `originalContent`를 보여 준다.
- `translation=null`, `FAILED`, `NOT_REQUIRED`이면 `originalContent`를 표시한다.
- 내가 보낸 TEXT는 `mine=true`이며 번역 없이 원문을 표시한다.
- `type=INQUIRY_CARD`이면 `inquiryCard`를 문의서 UI로 그린다.
- `type=BOOKING_CARD`이면 `bookingCard`를 카드 UI로 그린다.
- 사용자가 과거에 채팅방을 삭제했다면 그 사용자에게 숨겨진 삭제 이전 메시지는 이 응답에 포함되지 않는다. 삭제하지 않은 상대방은 같은 방의 전체 과거 이력을 계속 조회할 수 있다.

이 요청은 일정 간격으로 계속 실행하는 polling이 아니다. 최초 진입, 위로 스크롤, 재연결 누락 보충에만 사용한다.

## 13. TEXT 전송

다음 경로의 `{roomId}`를 현재 채팅방 ID로 바꿔 SEND한다.

```text
/app/chat-rooms/{roomId}/messages
```

payload는 다음 두 값만 보낸다.

```json
{
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "content": "안녕하세요"
}
```

| 필드 | 의미 |
| --- | --- |
| `clientMessageId` | 프론트가 전송 직전에 생성한 UUID. 임시 말풍선 연결과 중복 저장 방지에 사용 |
| `content` | 사용자가 입력한 원문. 공백·줄바꿈 포함 최대 3,000 Unicode 문자 |

네트워크 문제로 같은 메시지를 다시 보낼 때는 새로운 UUID를 만들지 않고 같은 `clientMessageId`를 사용한다. 같은 UUID에 다른 본문을 넣으면 충돌 오류가 발생한다.

프론트가 `senderId`, `messageId`, `roomId`, `type`, `sentAt`, `bookingCard`를 payload에 넣지 않는다. 이 값들은 인증 사용자, destination, 서버와 MySQL이 결정한다.

## 14. 받은 TEXT 원문·번역 동시 수신

TEXT 번역 작업이 끝나면 수신자의 `/user/queue/chat-translations`로 다음 이벤트가 온다. 원문을 먼저 보내고 나중에 번역본을 추가하는 두 단계 방식이 아니라, **원문과 최종 번역 결과가 아래 payload 하나에 함께** 온다.

```json
{
  "version": 1,
  "eventType": "MESSAGE_TRANSLATION_UPDATED",
  "messageId": 70051,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "chatRoomId": 556,
  "senderId": 7,
  "originalContent": "안녕하세요",
  "status": "SUCCEEDED",
  "sourceLanguage": "ko",
  "targetLanguage": "en",
  "translatedContent": "Hello",
  "provider": "GOOGLE_CLOUD_TRANSLATION",
  "sentAt": "2026-08-19T10:15:30.123456Z",
  "translatedAt": "2026-08-19T10:15:30.423456Z"
}
```

| 필드 | 의미 |
| --- | --- |
| `version` | payload 형식 버전. 현재 1 |
| `eventType` | 받은 TEXT 최종 결과인 `MESSAGE_TRANSLATION_UPDATED` |
| `messageId` | MySQL이 발급한 최종 메시지 번호. 정렬과 중복 제거 기준 |
| `clientMessageId` | 발신 프론트가 만든 TEXT UUID |
| `chatRoomId` | 메시지가 속한 채팅방 ID |
| `senderId` | TEXT를 보낸 사용자 ID |
| `originalContent` | 사용자가 입력한 수정하지 않은 원문 |
| `status` | `SUCCEEDED`, `NOT_REQUIRED`, `FAILED` 중 최종 상태 |
| `sourceLanguage` | Google이 감지한 원문 언어. 실패하면 null일 수 있음 |
| `targetLanguage` | 수신자의 대상 언어 `ko` 또는 `en` |
| `translatedContent` | 성공한 번역문. 번역 불필요·실패면 null |
| `provider` | 현재 `GOOGLE_CLOUD_TRANSLATION` |
| `sentAt` | 원문이 서버에 저장된 시각 |
| `translatedAt` | 번역 작업이 끝난 시각 |

`SUCCEEDED`면 `translatedContent`를 먼저 표시하고 원문 보기에서 `originalContent`로 전환한다. `NOT_REQUIRED` 또는 `FAILED`면 `originalContent`를 표시한다. 프론트는 `messageId`를 최종 중복 제거 기준으로 사용한다.

## 15. 서버 생성 카드 수신

프론트는 카드를 직접 SEND하지 않는다. 서버가 저장을 완료한 `INQUIRY_CARD`와 `BOOKING_CARD`만 room topic으로 오며, 실시간 이벤트를 놓쳐도 메시지 이력 API에서 다시 받을 수 있다.

### 15.1 INQUIRY_CARD 문의서

문의하기 API는 새 채팅방이면 첫 `INQUIRY_CARD`를 저장한다. 기존 방에서도 문의서가 없거나 최근 문의서 뒤에 `TEXT`·`BOOKING_CARD`가 있거나 요청자가 최근 문의서를 볼 수 없으면 새 카드를 저장한다. 요청자에게 보이는 최근 문의서가 마지막 메시지인 경우에는 새 문의서 이벤트가 오지 않는다.

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

다음 표는 room topic의 실시간 이벤트와 메시지 이력 REST 응답의 `inquiryCard`에서 공통으로 사용하는 값이다.

| 필드 | 의미 |
| --- | --- |
| `version` | 실시간 payload 형식 버전. 현재 1 |
| `eventType` | 새 저장 메시지 이벤트인 `MESSAGE_CREATED` |
| `messageId` | MySQL이 발급한 INQUIRY_CARD의 최종 메시지 ID |
| `clientMessageId` | 서버가 만든 카드이므로 null |
| `chatRoomId` | 카드가 저장된 채팅방 ID |
| `senderId` | 사용자가 직접 보낸 TEXT가 아니므로 null |
| `type` | 문의서 카드임을 나타내는 `INQUIRY_CARD` |
| `originalContent` | TEXT 원문이 없으므로 null |
| `bookingCard` | 문의서 메시지이므로 null |
| `inquiryCard` | 문의서 UI를 그리는 전체 데이터 객체 |
| `inquiryCard.listingId` | 문의한 매물 ID. `View Detail`을 누르면 이 ID로 기존 매물 상세 화면을 연다. |
| `inquiryCard.thumbnailUrl` | 문의 당시 매물의 첫 번째 대표 이미지 URL. 이미지가 없으면 null |
| `inquiryCard.title` | 문의 당시 매물 제목. 개별 RoomOffer 이름이 아니다. |
| `inquiryCard.city` | 주소의 city code. 프론트가 현재 언어의 label로 표시 |
| `inquiryCard.district` | 주소의 district code. 프론트가 현재 언어의 label로 표시 |
| `inquiryCard.listingType` | `GOSHIWON`, `CO_LIVING`, `SHARE_HOUSE` 중 하나. 프론트가 현재 언어의 label로 표시 |
| `inquiryCard.monthlyRentMin` | 문의 당시 `ACTIVE` 방 상품 중 최소 월세(KRW) |
| `inquiryCard.monthlyRentMax` | 문의 당시 `ACTIVE` 방 상품 중 최대 월세(KRW) |
| `sentAt` | INQUIRY_CARD가 서버에 저장된 시각 |

프론트는 `thumbnailUrl`이 null이면 이미지 없음 UI를 사용한다. 최소·최대 월세가 같으면 한 금액만 표시할 수 있고, 다르면 범위로 표시한다. city·district·매물 유형의 label은 Google 번역 결과가 아니라 앱이 서버 code를 기준으로 `ko/en` 현지화한다.

### 15.2 BOOKING_CARD 신청서

입주 신청이 저장되면 서버가 같은 채팅방에 `BOOKING_CARD`를 자동 저장하고 room topic으로 전달한다. 프론트가 카드를 SEND하지 않는다.

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

다음 표는 room topic의 실시간 이벤트와 메시지 이력 REST 응답의 `bookingCard`에서 공통으로 사용하는 값이다.

| 필드 | 의미 |
| --- | --- |
| `version` | 실시간 payload 형식 버전. 현재 1 |
| `eventType` | 새 저장 메시지 이벤트인 `MESSAGE_CREATED` |
| `messageId` | MySQL이 발급한 BOOKING_CARD의 최종 메시지 ID |
| `clientMessageId` | 서버가 만든 카드이므로 null |
| `chatRoomId` | 카드가 저장된 채팅방 ID |
| `senderId` | 사용자가 직접 보낸 메시지가 아니므로 null |
| `type` | 신청 카드임을 나타내는 `BOOKING_CARD` |
| `originalContent` | TEXT 원문이 없으므로 null |
| `inquiryCard` | 신청서 메시지이므로 null |
| `bookingCard` | 신청 카드 UI를 그리는 전체 데이터 객체 |
| `bookingCard.bookingId` | 이 카드가 나타내는 입주 신청 ID |
| `bookingCard.listing` | 신청 당시 매물 표시 정보를 묶은 객체 |
| `bookingCard.listing.listingId` | 신청 대상 매물 ID |
| `bookingCard.listing.thumbnailUrl` | 신청 카드 대표 이미지 URL. 이미지가 없으면 null |
| `bookingCard.listing.title` | 신청 당시 매물 제목 |
| `bookingCard.listing.address` | 신청 당시 매물 주소 |
| `bookingCard.listing.monthlyRent` | 신청 당시 월 임대료(KRW) |
| `bookingCard.applicant` | 임대인용 카드에 표시할 신청자 정보를 묶은 객체 |
| `bookingCard.applicant.userId` | 신청자의 서버 사용자 ID |
| `bookingCard.applicant.name` | 신청 당시 신청자 이름 |
| `bookingCard.applicant.gender` | 신청자 성별 코드 |
| `bookingCard.applicant.country` | 신청자 국가 코드 |
| `bookingCard.applicant.countryName` | 신청자 국가 표시 이름 |
| `bookingCard.applicant.email` | 신청 당시 신청자 이메일 |
| `bookingCard.roomOfferId` | 신청한 객실 상품 ID |
| `bookingCard.roomOfferName` | 신청한 객실 표시 이름 |
| `bookingCard.moveInDate` | 입주 희망일(`YYYY-MM-DD`) |
| `bookingCard.contractPeriod` | 계약 희망 기간(개월) |
| `bookingCard.deposit` | 보증금(KRW) |
| `bookingCard.totalAmount` | 총 초기 비용(KRW) |
| `sentAt` | BOOKING_CARD가 서버에 저장된 시각 |

임차인과 임대인은 같은 카드 payload를 받는다. 앱은 채팅방 상세 응답의 `myRole`을 보고 역할별 문구와 UI를 다르게 표시한다. `BOOKING_CARD`에는 프론트 임시 말풍선과 ACK가 없다.

실시간 이벤트를 놓쳐도 `GET /api/v1/chat-rooms/{roomId}/messages` 응답에서 저장된 카드를 다시 받을 수 있다.

## 16. ACK와 임시 말풍선

ACK는 STOMP protocol의 수신 확인이 아니라 **내가 보낸 TEXT가 MySQL에 저장됐다는 애플리케이션 결과**다. 원래 SEND한 session의 `/user/queue/chat-acks`로만 온다.

```json
{
  "version": 1,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "messageId": 70051,
  "sentAt": "2026-08-19T10:15:30.123456Z",
  "duplicate": false
}
```

| 필드 | 의미 |
| --- | --- |
| `version` | ACK payload 형식 버전. 현재 1 |
| `clientMessageId` | 어떤 임시 말풍선의 결과인지 찾는 UUID |
| `messageId` | 서버에 저장된 최종 메시지 번호 |
| `sentAt` | 서버에 처음 저장된 시각 |
| `duplicate` | 같은 UUID·같은 본문이 이미 저장돼 기존 결과를 돌려준 경우 true |

### 임시 말풍선 처리

임시 말풍선은 사용자가 전송 버튼을 누른 즉시 앱이 먼저 보여 주는 `전송 중` 메시지다.

1. 프론트가 UUID를 만든다.
2. 이 UUID를 가진 임시 말풍선을 화면에 즉시 추가한다.
3. 같은 UUID를 `clientMessageId`로 서버에 SEND한다.
4. ACK가 오면 같은 `clientMessageId`의 임시 말풍선을 찾는다.
5. 서버 `messageId`와 `sentAt`을 연결하고 `전송 완료`로 변경한다.

발신 앱은 ACK로 자신이 만든 임시 말풍선을 확정한다. 수신자용 번역 이벤트는 별도 사용자의 개인 queue로 가므로 발신 임시 말풍선과 합치지 않는다.

## 17. 오류 처리

개별 TEXT 전송 오류는 원래 SEND한 session의 `/user/queue/chat-errors`로 온다.

```json
{
  "version": 1,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "code": "CHAT_MESSAGE_TOO_LONG",
  "message": "메시지는 3,000자 이하여야 합니다."
}
```

| 필드 | 의미 |
| --- | --- |
| `version` | 오류 payload 형식 버전. 현재 1 |
| `clientMessageId` | 실패한 임시 말풍선을 찾는 UUID. JSON 자체를 해석하지 못하면 null일 수 있음 |
| `code` | 앱의 분기 처리에 사용하는 안정적인 오류 코드 |
| `message` | 사용자에게 보여 줄 수 있는 현지화 문구 |

앱의 재시도·로그인 이동 같은 로직은 `message` 문자열이 아니라 `code`를 기준으로 처리한다. `message`는 사용자 언어와 문구 개선에 따라 바뀔 수 있기 때문이다.

| 주요 code | 프론트 처리 예시 |
| --- | --- |
| `UNAUTHENTICATED` | 로그인 상태 확인 |
| `TOKEN_EXPIRED` | 토큰 갱신 후 WebSocket 재연결 |
| `AUTH_ONBOARDING_REQUIRED` | 온보딩 화면 이동 |
| `FORBIDDEN` | 허용되지 않은 destination·구독 안내 |
| `CHAT_ROOM_NOT_FOUND` | 목록·상세를 다시 조회하고 현재 방 상태 확인 |
| `CHAT_MESSAGE_TOO_LONG` | 3,000자 제한 안내 |
| `CHAT_CLIENT_MESSAGE_CONFLICT` | 같은 UUID에 다른 본문을 사용한 클라이언트 오류 처리 |

CONNECT 인증 실패는 STOMP `ERROR` frame 뒤 연결이 종료된다.

## 18. 채팅방 목록 이벤트

`/user/queue/chat-room-events`로 다음 형태의 이벤트가 온다.

```json
{
  "version": 1,
  "eventType": "ROOM_UPDATED",
  "roomId": 556,
  "lastMessageId": 70052,
  "occurredAt": "2026-08-19T10:15:30.123456Z"
}
```

| 필드 | 의미 |
| --- | --- |
| `version` | payload 형식 버전. 현재 1 |
| `eventType` | 목록 변경 종류. 아래 `ROOM_CREATED`, `ROOM_UPDATED`, `ROOM_REOPENED` 중 하나 |
| `roomId` | 변경된 채팅방 ID |
| `lastMessageId` | 이벤트 시점의 마지막 메시지 ID. 빈 방이면 null |
| `occurredAt` | 채팅방 목록 변경이 서버에 확정된 시각 |

| `eventType` | 의미 |
| --- | --- |
| `ROOM_CREATED` | 사용자 목록에 새 채팅방이 생성됨. 문의로 시작하면 `lastMessageId`는 함께 저장된 INQUIRY_CARD ID |
| `ROOM_UPDATED` | 기존 채팅방의 마지막 메시지 등이 바뀜 |
| `ROOM_REOPENED` | 새 활동으로 숨긴 채팅방이 다시 목록에 표시됨 |

이 이벤트는 목록 전체 데이터가 아니라 **목록 REST API를 다시 조회하라는 신호**다. `ROOM_REOPENED`도 삭제한 과거 메시지를 복원했다는 뜻이 아니다.

## 19. 사용자가 삭제한 채팅방

- 요청은 `DELETE /api/v1/chat-rooms/{roomId}`이며 body와 사용자 ID를 보내지 않는다.
- 성공하면 `204 No Content`가 오고 JSON 본문은 없다. 프론트는 해당 채팅방을 목록과 현재 화면에서 제거한다.
- 채팅방 삭제는 요청한 사용자에게만 방과 기존 이력을 숨긴다.
- 삭제하지 않은 상대방의 채팅방과 전체 과거 이력은 그대로 유지된다.
- 숨긴 사용자는 그 상태에서 room topic을 직접 구독하거나 TEXT를 보낼 수 없다.
- 같은 매물에 직접 다시 문의하거나 실제 새 메시지가 도착하면 같은 `roomId`의 채팅방이 목록에 다시 나타난다.
- 방이 다시 나타나도 숨긴 사용자는 삭제 이후 새 메시지만 볼 수 있으며 과거 이력은 복원되지 않는다.
- 일반 사용자의 삭제 복원 API는 제공하지 않는다.

```http
DELETE /api/v1/chat-rooms/556
Authorization: Bearer <accessToken>

HTTP/1.1 204 No Content
```

이미 숨긴 방에 같은 요청을 다시 보내도 `204`다. 방이 없거나 요청자가 참여자가 아니면
`404 CHAT_ROOM_NOT_FOUND`를 반환한다.

## 20. 채팅 상대 차단

채팅방의 더보기 메뉴에서 사용자가 `차단`을 확인했을 때 호출한다.

```http
POST /api/v1/chat-rooms/{roomId}/block
Authorization: Bearer <accessToken>
```

요청 body와 상대 사용자 ID는 보내지 않는다. 서버가 현재 채팅방의 두 참여자 중 로그인 사용자가 아닌 상대방을 찾아 차단한다.

```http
POST /api/v1/chat-rooms/556/block

HTTP/1.1 204 No Content
```

- 성공 응답은 `204 No Content`이며 JSON 본문은 없다.
- 프론트는 `204`를 받으면 메시지 입력창을 비활성화하고 현재 화면의 `blocked`를 true로 처리한다.
- 차단은 두 사용자 사이 전체에 적용되므로 차단 뒤에는 어느 방향으로도 새 TEXT를 보낼 수 없다.
- 기존 채팅방과 차단 전에 저장된 메시지는 양쪽 모두 계속 볼 수 있다.
- 차단만으로 채팅방이 목록에서 사라지지는 않는다. 숨기려면 삭제 API를 별도로 호출한다.
- 이미 차단한 상대방을 다시 차단해도 `204`다.

차단 해제는 채팅 전용 API가 아니라 기존 사용자 차단 API를 사용한다. `{userId}`에는 채팅방 상세의 `counterpart.userId`를 넣는다.

```http
DELETE /api/v1/users/me/blocks/{userId}
```

## 21. 채팅 상대 신고

채팅방 신고 화면에서 사용자가 사유 하나를 선택하고 `신고하기`를 눌렀을 때 호출한다.

```http
POST /api/v1/chat-rooms/{roomId}/reports
Authorization: Bearer <accessToken>
Content-Type: application/json
```

프론트는 선택된 언어와 관계없이 아래의 고정 `reason` 코드 하나만 보낸다.

```json
{
  "reason": "ILLEGAL_CONTENT"
}
```

신고자 ID, 신고할 상대 사용자 ID, 상세 사유, 증거 메시지는 보내지 않는다. 서버가 access token과 채팅방 참여자 정보를 확인하고, 신고자에게 현재 보이는 최근 TEXT 원문을 최대 20개까지 증거로 저장한다.

```json
{
  "success": true,
  "data": {
    "reportId": 91,
    "chatRoomId": 556,
    "reason": "ILLEGAL_CONTENT",
    "status": "RECEIVED",
    "receivedAt": "2026-08-22T10:15:30.123456Z"
  },
  "error": null
}
```

| 필드 | 의미 |
| --- | --- |
| `reportId` | DB에 저장된 신고 접수 ID. 이 값이 오면 접수가 완료된 것 |
| `chatRoomId` | 신고가 접수된 채팅방 ID |
| `reason` | 사용자가 선택해 보낸 신고 사유 코드 |
| `status` | 현재 사용자 접수 단계에서는 `RECEIVED` |
| `receivedAt` | 서버가 최초 신고를 접수한 시각 |

- 처음 접수되면 `201 Created`가 온다.
- 같은 사용자가 같은 채팅방을 다시 신고하면 중복 저장하지 않고 기존 결과와 `200 OK`가 온다.
- 두 경우 모두 위와 같은 응답 body가 오므로 프론트는 성공 안내를 표시하면 된다.
- 신고는 상대방 차단이나 채팅방 삭제를 자동 실행하지 않는다. 함께 필요하면 차단·삭제 API를 각각 호출한다.
- 신고자에게 현재 보이는 TEXT가 한 개도 없으면 `422 REPORT_REQUIRES_TEXT_MESSAGE`다.

프론트는 `reason` 코드를 아래처럼 `ko/en` 문구로 바꿔 표시한다. 서버는 문구가 아닌 코드를 저장하므로 화면 언어가 바뀌어도 같은 신고 사유로 처리할 수 있다.

| reason code | ko 표시 예시 | en 표시 예시 |
| --- | --- | --- |
| `ABUSE_HARASSMENT_DISCRIMINATION` | 욕설, 비방, 차별, 혐오 | Abuse, Harassment, Discrimination |
| `ILLEGAL_CONTENT` | 불법정보 | Illegal Content |
| `SEXUAL_INAPPROPRIATE_CONTENT` | 음란, 청소년 유해 | Sexual or Inappropriate Content |
| `PERSONAL_INFORMATION` | 개인정보 노출, 유포, 거래 | Personal Information |
| `SPAM` | 도배, 스팸 | Spam |
| `OTHER` | 기타 | Other |

## 22. 재연결과 누락 복구

WebSocket 연결은 앱 백그라운드 전환, 네트워크 변경, 서버 재시작, 토큰 만료 등으로 끊길 수 있다.

1. 짧은 지연을 두고 WebSocket을 다시 연결한다.
2. 새 access token으로 STOMP CONNECT한다.
3. 개인 queue를 다시 구독하고 PING/PONG을 확인한다.
4. 현재 채팅방 topic을 다시 구독한다.
5. `SUBSCRIPTION_READY`를 기다린다.
6. 마지막으로 REST에서 연속 확인한 `messageId` 이후를 조회한다.
7. `highWatermark`까지 확인한 뒤 실시간 이벤트와 `messageId`로 합친다.

Simple Broker는 연결이 끊긴 동안의 이벤트를 다시 재생하지 않는다. 하지만 메시지는 MySQL에 저장되어 있으므로 REST 이력으로 복구할 수 있다.

## 23. 프론트 구현 체크리스트

- [ ] 문의하기 응답의 신규·기존 여부와 관계없이 반환된 `chatRoomId`로 채팅방을 연다.
- [ ] 문의하기의 `created`는 방 생성 여부로만 사용하고, 값과 관계없이 메시지 이력에서 실제 INQUIRY_CARD를 읽는다.
- [ ] 목록의 `counterpart`에는 아직 프로필 이미지가 없으므로 기본 아이콘을 사용한다.
- [ ] 목록·상세의 `blocked=true`이면 과거 이력은 표시하고 TEXT 입력창만 비활성화한다.
- [ ] 최근 이력의 `nextCursor`는 `hasNext=true`일 때 같은 조회 방향의 다음 요청에 사용한다.
- [ ] 개발 `wss`, 로컬 `ws` 주소를 환경별로 사용한다.
- [ ] JWT는 URL이 아니라 STOMP CONNECT native header에 넣는다.
- [ ] TEXT SEND 전에 개인 queue를 먼저 구독한다.
- [ ] PING과 같은 `requestId`의 PONG을 확인한다.
- [ ] 서버가 반환한 숫자 `roomId`로 room topic을 구독한다.
- [ ] `SUBSCRIPTION_READY` 뒤 REST 누락 보충을 실행한다.
- [ ] 프론트에서 TEXT마다 UUID `clientMessageId`를 생성한다.
- [ ] 재시도에는 같은 `clientMessageId`를 사용한다.
- [ ] ACK가 오면 같은 `clientMessageId`의 발신 임시 말풍선을 확정한다.
- [ ] 최종 메시지 중복은 서버 `messageId`로 제거한다.
- [ ] translation queue의 `SUCCEEDED`는 번역문 우선, `NOT_REQUIRED`·`FAILED`는 원문으로 렌더링한다.
- [ ] room topic과 REST 이력의 `INQUIRY_CARD`는 문의서 UI로 렌더링한다.
- [ ] 문의서의 city·district·listingType code는 앱 언어에 맞는 label로 표시한다.
- [ ] 문의서의 `View Detail`은 `inquiryCard.listingId`로 기존 매물 상세 화면을 연다.
- [ ] room topic의 `BOOKING_CARD`는 역할별 카드 UI로 렌더링한다.
- [ ] 앱 로직은 오류 `message`가 아니라 `code`로 분기한다.
- [ ] 재연결 때 개인 queue와 room topic을 모두 다시 구독한다.
- [ ] DELETE·차단 성공의 `204`에는 JSON body가 없으므로 HTTP status로 성공을 판단한다.
- [ ] 신고 화면은 고정 `reason` 코드를 `ko/en` 문구로 표시하고 선택한 코드 하나만 서버에 보낸다.

## 24. 현재 범위

현재 구현된 기능:

- 매물 문의 채팅방 조회·생성
- 내 채팅방 목록·상세·메시지 이력 REST 조회
- WebSocket/STOMP 연결과 JWT 인증
- 개인 queue와 room topic 구독 권한 검사
- PING/PONG과 `SUBSCRIPTION_READY`
- TEXT 저장·실시간 전달·ACK·오류
- Google 자동 번역과 수신자 원문·번역 결합 이벤트
- BOOKING_CARD 저장·실시간 전달
- REST 메시지 이력을 이용한 누락 복구
- 요청자에게만 적용되는 채팅방 삭제 API
- 채팅방의 상대방을 찾는 사용자 차단 API
- 고정 사유를 사용하는 채팅방 상대 신고 접수와 원문 증거 저장
- iOS 앱 설치본별 FCM 토큰 등록·갱신·로그아웃 삭제 REST API
- 새 TEXT·INQUIRY_CARD·BOOKING_CARD의 iOS FCM 푸시 발송 코드

현재 보완이 필요한 기능:

- 기존 방의 대화·신청·삭제 상태에 따른 INQUIRY_CARD 재전송 판단
- dev Firebase/APNs 연결과 실제 iPhone 푸시·딥링크 E2E 검증
- 읽음 표시와 안 읽은 메시지 수
- 사용자용 삭제 채팅방 복원
- 관리자용 신고 처리·삭제 채팅방 복구
