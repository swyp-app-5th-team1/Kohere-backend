# 프론트엔드 실시간 채팅 STOMP 연동 가이드

> 대상: 앱·웹 프론트엔드 개발자
>
> 최종 수정: 2026-08-21
>
> Swagger: `Chats → GET /api/v1/chat/stomp-guide`

이 문서는 Kohere 1:1 채팅을 프론트엔드에서 연결하는 순서를 쉽게 설명한다. Swagger는 HTTP API 문서 도구라 WebSocket/STOMP를 직접 실행할 수 없다. Swagger와 이 문서에서 주소·payload를 확인한 뒤 앱의 STOMP 클라이언트로 실제 연결한다.

정확한 protocol 규칙이 필요하면 [03-websocket-stomp.md](03-websocket-stomp.md)를 따른다.

## 1. 핵심 용어

| 용어 | 쉬운 설명 |
| --- | --- |
| WebSocket | 서버와 연결을 계속 유지해 새 메시지를 즉시 주고받는 통로 |
| STOMP | WebSocket 통로에서 연결·구독·전송을 구분하는 메시지 규칙 |
| SEND | 서버에 메시지나 제어 요청을 보내는 동작 |
| SUBSCRIBE | 특정 경로에서 발생하는 새 이벤트를 계속 받겠다고 등록하는 동작 |
| topic | 같은 채팅방 참여자에게 새 메시지를 방송하는 실시간 경로 |
| 개인 queue | ACK·오류처럼 현재 사용자 session만 받아야 하는 결과 경로 |
| ACK | 내가 보낸 TEXT가 MySQL에 저장됐다는 애플리케이션 결과 |

topic과 queue는 데이터를 저장하는 장소가 아니다. 채팅 기록의 정본은 MySQL이며, 실시간 이벤트를 놓치면 REST 메시지 이력 API로 복구한다.

## 2. 연결 주소

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

## 3. JWT 인증

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

## 4. 전체 연결 순서

```text
WebSocket 연결
  → STOMP CONNECT(JWT)
  → 개인 queue 먼저 구독
  → PING 전송 / PONG 확인
  → 채팅방 topic 구독
  → SUBSCRIPTION_READY 확인
  → REST로 누락 메시지 보충
  → 실시간 TEXT·BOOKING_CARD 수신/전송
```

사용자가 버튼을 눌러 각 단계를 실행하는 것이 아니다. 채팅방 화면에 들어갈 때 프론트 코드가 자동으로 수행한다.

## 5. 먼저 구독할 개인 queue

개인 queue는 서버가 **현재 사용자 session에만 보내는 처리 결과와 알림을 받는 경로**다. TEXT를 보내기 전에 먼저 구독해야 저장 성공 ACK나 오류를 놓치지 않는다.

| queue | 받는 내용 | 프론트 처리 |
| --- | --- | --- |
| `/user/queue/chat-control` | `PONG`, `SUBSCRIPTION_READY` | 연결·방 구독 준비 상태 확인 |
| `/user/queue/chat-acks` | 내가 보낸 TEXT의 DB 저장 성공 결과 | 임시 말풍선을 전송 완료로 변경 |
| `/user/queue/chat-errors` | 내가 보낸 TEXT의 검증·권한 오류 | 해당 말풍선을 실패 상태로 표시 |
| `/user/queue/chat-room-events` | 채팅방 생성·갱신·재표시 알림 | REST 채팅방 목록 다시 조회 |
| `/user/queue/chat-translations` | 사용자별 자동 번역 결과 | 추후 번역 기능에서 사용. 현재는 이벤트 없음 |

다른 사용자의 개인 queue는 구독할 수 없다. 한 WebSocket session은 최대 16개 구독을 유지할 수 있으며 같은 destination을 중복 구독하지 않는다.

## 6. 개인 queue 준비 확인: PING/PONG

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

## 7. 채팅방 topic 구독

다음 경로의 `{roomId}`를 서버에서 받은 숫자 채팅방 ID로 바꿔 구독한다.

```text
/topic/chat-rooms/{roomId}
```

예를 들어 `roomId=556`이면 다음 경로를 구독한다.

```text
/topic/chat-rooms/556
```

이 topic으로 새 `TEXT`와 서버가 만든 `BOOKING_CARD`가 실시간으로 온다.

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

## 8. 저장된 메시지 이력 조회

실시간 연결 전후로 MySQL에 저장된 메시지는 REST API로 조회한다.

```http
GET /api/v1/chat-rooms/{roomId}/messages
```

| 상황 | 요청 방식 |
| --- | --- |
| 채팅방 최초 진입 | `cursor`, `afterMessageId` 없이 최근 메시지 조회 |
| 과거 메시지 추가 조회 | 이전 응답의 `nextCursor`를 `cursor`로 전달 |
| 재연결 누락 보충 | 마지막 연속 동기화 `messageId`를 `afterMessageId`로 전달 |

이 요청은 일정 간격으로 계속 실행하는 polling이 아니다. 최초 진입, 위로 스크롤, 재연결 누락 보충에만 사용한다.

## 9. TEXT 전송

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

## 10. 실시간 메시지 수신

TEXT가 MySQL에 저장되면 room topic으로 다음 이벤트가 온다.

```json
{
  "version": 1,
  "eventType": "MESSAGE_CREATED",
  "messageId": 70051,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "chatRoomId": 556,
  "senderId": 7,
  "type": "TEXT",
  "originalContent": "안녕하세요",
  "bookingCard": null,
  "sentAt": "2026-08-19T10:15:30.123456Z"
}
```

| 필드 | 의미 |
| --- | --- |
| `version` | payload 형식 버전. 현재 1 |
| `eventType` | 저장 완료 메시지인 `MESSAGE_CREATED` |
| `messageId` | MySQL이 발급한 최종 메시지 번호. 정렬과 중복 제거 기준 |
| `clientMessageId` | 프론트가 보낸 TEXT UUID. `BOOKING_CARD`는 null |
| `chatRoomId` | 메시지가 속한 채팅방 ID |
| `senderId` | TEXT를 보낸 사용자 ID. `BOOKING_CARD`는 null |
| `type` | `TEXT` 또는 `BOOKING_CARD` |
| `originalContent` | TEXT 원문. `BOOKING_CARD`는 null |
| `bookingCard` | 신청 카드 데이터. TEXT는 null |
| `sentAt` | 서버 저장 시각 |

프론트는 `messageId`를 최종 중복 제거 기준으로 사용한다.

## 11. BOOKING_CARD 수신

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

임차인과 임대인은 같은 카드 payload를 받는다. 앱은 채팅방 상세 응답의 `myRole`을 보고 역할별 문구와 UI를 다르게 표시한다. `BOOKING_CARD`에는 프론트 임시 말풍선과 ACK가 없다.

실시간 이벤트를 놓쳐도 `GET /api/v1/chat-rooms/{roomId}/messages` 응답에서 저장된 카드를 다시 받을 수 있다.

## 12. ACK와 임시 말풍선

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
4. ACK 또는 room topic의 `MESSAGE_CREATED`가 오면 같은 `clientMessageId`의 임시 말풍선을 찾는다.
5. 서버 `messageId`와 `sentAt`을 연결하고 `전송 완료`로 변경한다.

room topic 이벤트와 ACK 중 무엇이 먼저 올지는 보장하지 않는다. 둘 다 같은 `clientMessageId`로 합칠 수 있어야 하며, 최종 중복 제거는 `messageId`로 한다.

## 13. 오류 처리

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

## 14. 채팅방 목록 이벤트

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

| `eventType` | 의미 |
| --- | --- |
| `ROOM_CREATED` | 사용자 목록에 새 채팅방이 생성됨 |
| `ROOM_UPDATED` | 기존 채팅방의 마지막 메시지 등이 바뀜 |
| `ROOM_REOPENED` | 새 활동으로 숨긴 채팅방이 다시 목록에 표시됨 |

이 이벤트는 목록 전체 데이터가 아니라 **목록 REST API를 다시 조회하라는 신호**다. `ROOM_REOPENED`도 삭제한 과거 메시지를 복원했다는 뜻이 아니다.

## 15. 사용자가 삭제한 채팅방

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

## 16. 재연결과 누락 복구

WebSocket 연결은 앱 백그라운드 전환, 네트워크 변경, 서버 재시작, 토큰 만료 등으로 끊길 수 있다.

1. 짧은 지연을 두고 WebSocket을 다시 연결한다.
2. 새 access token으로 STOMP CONNECT한다.
3. 개인 queue를 다시 구독하고 PING/PONG을 확인한다.
4. 현재 채팅방 topic을 다시 구독한다.
5. `SUBSCRIPTION_READY`를 기다린다.
6. 마지막으로 REST에서 연속 확인한 `messageId` 이후를 조회한다.
7. `highWatermark`까지 확인한 뒤 실시간 이벤트와 `messageId`로 합친다.

Simple Broker는 연결이 끊긴 동안의 이벤트를 다시 재생하지 않는다. 하지만 메시지는 MySQL에 저장되어 있으므로 REST 이력으로 복구할 수 있다.

## 17. 프론트 구현 체크리스트

- [ ] 개발 `wss`, 로컬 `ws` 주소를 환경별로 사용한다.
- [ ] JWT는 URL이 아니라 STOMP CONNECT native header에 넣는다.
- [ ] TEXT SEND 전에 개인 queue를 먼저 구독한다.
- [ ] PING과 같은 `requestId`의 PONG을 확인한다.
- [ ] 서버가 반환한 숫자 `roomId`로 room topic을 구독한다.
- [ ] `SUBSCRIPTION_READY` 뒤 REST 누락 보충을 실행한다.
- [ ] 프론트에서 TEXT마다 UUID `clientMessageId`를 생성한다.
- [ ] 재시도에는 같은 `clientMessageId`를 사용한다.
- [ ] ACK와 room topic의 도착 순서에 의존하지 않는다.
- [ ] 최종 메시지 중복은 서버 `messageId`로 제거한다.
- [ ] `type=TEXT`와 `type=BOOKING_CARD`를 다른 UI로 렌더링한다.
- [ ] 앱 로직은 오류 `message`가 아니라 `code`로 분기한다.
- [ ] 재연결 때 개인 queue와 room topic을 모두 다시 구독한다.

## 18. 현재 범위

현재 구현된 기능:

- WebSocket/STOMP 연결과 JWT 인증
- 개인 queue와 room topic 구독 권한 검사
- PING/PONG과 `SUBSCRIPTION_READY`
- TEXT 저장·실시간 전달·ACK·오류
- BOOKING_CARD 저장·실시간 전달
- REST 메시지 이력을 이용한 누락 복구
- 요청자에게만 적용되는 채팅방 삭제 API
- 채팅방의 상대방을 찾는 사용자 차단 API

아직 구현하지 않은 기능:

- Google 자동 번역 결과 전달
- 푸시 알림
- 읽음 표시와 안 읽은 메시지 수
- 사용자 신고 API
