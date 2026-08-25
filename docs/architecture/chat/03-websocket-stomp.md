# WebSocket·STOMP 계약

이 문서는 실시간 연결, 인증, 구독, 메시지 전송, 재연결 동기화의 정본이다. REST endpoint는 [02-api-contracts.md](02-api-contracts.md)를 따른다.

> **현재 구현 상태:** 연결·JWT 인증, 구독 권한·누락 보충, TEXT 자동 번역과 서버 생성 BOOKING_CARD의 실시간 전달까지 구현했다. 정확한 다섯 개인 queue와 사용자가 참여 중이며 현재 보이는 room topic만 구독할 수 있다. `PING/PONG`, 실제 broker 등록 뒤 `SUBSCRIPTION_READY`도 동작한다. 신규 TEXT는 발신자에게 저장 ACK를 즉시 보내고, 수신자에게는 번역 최종 상태가 정해진 뒤 원문과 번역본을 개인 queue의 한 이벤트로 전달한다. 신규 BOOKING_CARD는 room topic으로 전달한다.

## 1. 기본 원칙

- WebSocket은 연결을 유지하는 통로다.
- STOMP는 그 통로에서 `CONNECT`, `SUBSCRIBE`, `SEND`를 구분하는 규약이다.
- MySQL이 기록의 정본이고 broker는 저장 완료 메시지를 실시간 전달한다.
- 클라이언트가 보낸 메시지를 broker로 바로 보내지 않는다.
- 애플리케이션이 인증·참여자·차단·본문·중복을 검사하고 MySQL 커밋을 마친 뒤 발행한다.
- 공용 room topic은 서버 생성 `BOOKING_CARD`를 전달한다. 받은 `TEXT`는 원문과 사용자별 최종 번역 결과를 개인 queue의 한 이벤트로 전달한다.
- 번역은 원문 커밋 후 비동기로 처리하며 실패해도 메시지 저장 성공을 바꾸지 않는다.
- 실시간 수신을 놓치면 REST 메시지 조회로 보충한다.

## 2. Endpoint와 destination

| 구분 | 값 |
| --- | --- |
| Handshake endpoint | `/ws/chat` |
| Application prefix | `/app` |
| Broker prefix | `/topic`, `/queue` |
| User prefix | `/user` |
| CONNECT header | `Authorization: Bearer <access-token>` |
| 메시지 SEND | `/app/chat-rooms/{roomId}/messages` |
| 제어 SEND | `/app/chat/control/ping` |
| 방 SUBSCRIBE | `/topic/chat-rooms/{roomId}` |
| 저장 결과 SUBSCRIBE | `/user/queue/chat-acks` |
| 오류 SUBSCRIBE | `/user/queue/chat-errors` |
| 방 이벤트 SUBSCRIBE | `/user/queue/chat-room-events` |
| 번역 결과 SUBSCRIBE | `/user/queue/chat-translations` |
| 제어 SUBSCRIBE | `/user/queue/chat-control` |

### Command 인가

| STOMP command | 허용 조건 |
| --- | --- |
| `CONNECT` | 유효한 access token과 `ROLE_USER` |
| `SUBSCRIBE` room topic | 정확한 경로이며 현재 사용자가 방 참여자 |
| `SUBSCRIBE` user queue | 위 다섯 개의 정확한 본인 queue만 허용 |
| `SEND` message | 정확한 `/app/chat-rooms/{양의 roomId}/messages`이며 현재 보이는 방의 참여자 |
| `SEND` control | 정확한 control ping만 허용 |
| `DISCONNECT`, `UNSUBSCRIBE`, heartbeat | 인증 session의 lifecycle frame |
| 그 밖의 MESSAGE·SUBSCRIBE | 거부 |

클라이언트가 `/topic/**`, `/queue/**`, `/user/**`로 직접 SEND하거나 raw queue, 다른 사용자의 user destination, wildcard destination을 구독하는 것은 모두 거부한다.

## 3. STOMP frame과 크기 제한

STOMP frame은 command, header, body를 묶은 하나의 논리적 메시지다. Spring WebSocket/STOMP의 기본 수신 message limit은 64 KiB, 즉 65,536바이트다. WebSocket 조각이 여러 개여도 Spring이 조립한 논리 메시지 전체에 적용된다.

구현에서는 기본값에 우연히 의존하지 않고 transport limit을 `64 * 1024`로 명시한다.

본문 3,000자는 STOMP가 요구하는 값이 아니라 제품 정책이다.

- transport 단계: STOMP frame 전체가 64 KiB를 넘으면 연결 종료 또는 protocol 오류
- 애플리케이션 단계: frame 안의 `content`가 공백·줄바꿈 포함 Unicode code point 3,000자를 넘으면 `CHAT_MESSAGE_TOO_LONG`
- 빈 값과 공백만 있는 값은 거부

transport 검사가 payload binding보다 먼저 실행되므로 64 KiB를 넘은 frame은 애플리케이션 오류 응답까지 도달하지 못할 수 있다. 글자 수는 Java UTF-16 `length()`가 아니라 `String.codePointCount(...)` 기준으로 검사한다.

## 4. CONNECT 인증

브라우저 WebSocket handshake에는 임의 HTTP `Authorization` header를 안정적으로 넣기 어렵다. 다음 방식으로 통일한다.

1. `/ws/chat`의 HTTP handshake는 transport 진입만 위해 `permitAll` 처리한다.
2. 실제 인증은 STOMP CONNECT native header의 Bearer token으로 수행한다.
3. inbound `ChannelInterceptor`가 기존 `JwtTokenService`를 호출한다.
4. 서명·issuer·만료를 검증하고 온보딩 미완료 토큰을 거부한다.
5. CONNECT 시 user 공개 API로 현재 계정이 ACTIVE인지 확인한다.
6. 성공하면 `ROLE_USER` Authentication을 session에 저장한다.
7. `Principal.name`은 반드시 `String.valueOf(userId)`로 고정한다.
8. 검증된 `expiresAt`을 session에 보관하고 만료되면 연결을 종료한다.
9. 클라이언트는 access token을 갱신한 뒤 재연결한다.

기존 `JwtTokenService`의 서명 검증 코드를 복사하지 않는다. 필요하면 같은 서비스에 `AuthPrincipal + expiresAt`을 돌려주는 검증 결과 타입을 추가한다. 현재 `AuthPrincipal` 자체가 안정적인 Principal 이름을 제공하지 않는다면 WebSocket 전용 Principal 또는 Authentication을 둔다.

JWT 인증 interceptor는 메시지 authorization interceptor보다 먼저 실행돼야 한다. URL query parameter에 token을 넣지 않으며 운영은 WSS와 정확한 Origin allowlist만 허용한다.

현재 stateless HTTP 보안과 별개로 Spring WebSocket Security의 기본 CONNECT CSRF 요구를 무심코 활성화하지 않는다. 이번 계약은 STOMP Bearer token + Origin allowlist를 명시적으로 구성한다.

## 5. SUBSCRIBE 권한과 준비 barrier

Simple Broker가 room SUBSCRIBE를 직접 처리하므로 일반 controller만으로는 권한을 검사할 수 없다. inbound interceptor가 다음을 확인한다.

1. destination이 `/topic/chat-rooms/{양의 Long}` 형식인가
2. STOMP subscription `id`가 있고 같은 session에서 중복되지 않는가
3. session이 `ROLE_USER`인가
4. 방이 존재하는가
5. 사용자가 tenant 또는 landlord 참여자이며 현재 그 방을 숨기지 않았는가

한 session은 최대 16개 구독만 유지하며 subscription `id`는 최대 128자다. 같은 session에서 같은 destination을 여러 번 구독하는 것도 거부한다. 앱에 필요한 다섯 개인 queue와 현재 채팅방 topic을 충분히 담으면서, 잘못된 클라이언트가 구독을 무한히 늘리는 것을 막기 위한 제한이다.

Spring Simple Broker의 STOMP `RECEIPT`에 의존하지 않는다. SUBSCRIBE frame을 전송한 시점과 실제 구독 등록 시점 사이의 누락 구간을 막기 위해 application-level barrier를 사용한다.

1. 클라이언트가 `/user/queue/chat-control`을 구독한다.
2. UUID `requestId`가 포함된 `/app/chat/control/ping`을 보내 같은 ID의 `PONG`이 올 때까지 재시도한다.
3. room topic을 SUBSCRIBE한다.
4. 서버가 Simple Broker의 구독 처리가 실제로 끝난 것을 `ExecutorChannelInterceptor.afterMessageHandled`에서 확인한다.
5. 서버가 현재 사용자에게 보이는 마지막 `messageId`를 high-watermark로 읽는다.
6. 원래 session에 `SUBSCRIPTION_READY`를 보낸다.
7. 클라이언트가 high-watermark까지 REST catch-up을 한 번 자동 실행한다.

이 REST catch-up은 채팅방 구독 준비 직후와 재연결 직후의 누락 구간만 메우는 절차다. 연결 중에는 STOMP로 새 메시지를 받으며 일정 간격의 polling은 하지 않는다.

서버가 구독 등록 뒤 high-watermark를 만들지 못하면 반쪽 구독을 남기지 않고 해당 socket을 닫는다. 앱도 `SUBSCRIPTION_READY`가 5초 안에 오지 않으면 같은 socket에 SUBSCRIBE를 계속 추가하지 말고 연결을 닫은 뒤 재연결한다.

제어 이벤트 예시:

```json
{
  "version": 1,
  "eventType": "SUBSCRIPTION_READY",
  "roomId": 556,
  "highWatermark": 70051
}
```

빈 방이거나 사용자의 삭제 경계 이후에 보이는 메시지가 없으면 `highWatermark`는 `null`이다.

ping·pong과 `SUBSCRIPTION_READY`의 정확한 JSON은 [API 계약 §6.5](02-api-contracts.md#65-구독-준비-제어-이벤트)를 따른다.

## 6. SEND payload와 서버 처리

클라이언트가 보내는 STOMP 메시지는 `TEXT` 전용이며 다음 두 값만 보낸다.

```json
{
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "content": "안녕하세요. 이번 주 토요일에 방문할 수 있을까요?"
}
```

서버 처리 순서:

1. session Principal과 token 만료 확인
2. destination 형식과 roomId 확인
3. UUID와 공백이 아닌 본문·Unicode code point 3,000자 제한 검사
4. 트랜잭션에서 방을 잠그고 방 존재·참여자·발신자 표시 상태 재검증
5. 양방향 차단 재검증
6. `(roomId, senderId, clientMessageId)`로 신규·중복 판정
7. 신규이면 TEXT 원문과 수신자용 `PENDING` 번역 작업을 함께 저장
8. 신규이면 방의 마지막 메시지 포인터를 갱신하고, 숨겼던 수신자의 방만 다시 표시
9. 트랜잭션 커밋
10. 두 참여자에게 방 목록 갱신 신호 전송
11. 발신 session에 애플리케이션 저장 결과 ACK 전송
12. 번역 Worker를 즉시 깨우고, 최종 결과를 수신자 개인 queue로 전달

커밋 전에는 broker에 발행하지 않는다. 중복 재시도는 기존 결과 ACK만 다시 보내고 두 번째 broadcast·방 재표시·마지막 메시지 갱신을 만들지 않는다. 사용자가 숨긴 방에서 오래된 화면으로 SEND하면 직접 문의로 방을 다시 표시하기 전까지 `CHAT_ROOM_NOT_FOUND`로 거부한다.

Google API는 7번 저장 트랜잭션 안에서 호출하지 않는다. 원문과 번역 작업이 모두 커밋된 뒤 별도 Worker가 호출하므로 Google 장애가 원문 저장 성공을 되돌리지 않는다.

### 서버가 만드는 BOOKING_CARD

`BOOKING_CARD`는 위 SEND destination으로 들어오지 않는다. 기존 Booking Service가 신청을 저장한 뒤 공개 `BookingCreatedEvent`를 발행하면 Chat Application이 다음 순서로 자동 처리한다.

1. `(listingId, tenantId, landlordId)`로 문의하기와 동일한 채팅방을 조회하거나 생성한다.
2. 이벤트에 함께 전달된 신청 시점의 매물·신청자·입주 조건·금액 사본을 사용한다.
3. 별도의 Booking 조회 API를 호출하지 않고 해당 사본을 카드 payload로 저장한다.
4. `(chatRoomId, bookingId)` UNIQUE로 같은 신청 카드의 중복 저장을 막는다.
5. 신규 카드가 커밋된 경우에만 room topic으로 `BOOKING_CARD` 저장 완료 이벤트를 발행한다.
6. 같은 예약 이벤트가 다시 전달되면 기존 카드를 반환하고 room topic에는 다시 발행하지 않는다.
7. 앱이 실시간 이벤트를 놓치더라도 메시지 이력 REST API에서 저장된 카드를 다시 조회할 수 있다.

카드에는 프런트 UUID `clientMessageId`가 없고, 서버 생성 메시지이므로 `senderId`도 null이다. 임차인·임대인은 같은 카드 데이터를 받고 앱이 채팅방의 `myRole`에 따라 화면을 다르게 배치한다. 카드 데이터는 Google 번역 대상이 아니다.

## 7. 채팅 메시지 자동 번역

Google 번역 호출은 메시지 저장 transaction 안에서 실행하지 않는다. 애플리케이션이 원문 커밋 후 DB-backed worker에서 Google Cloud Translation Advanced v3의 NMT 번역을 호출한다.

```text
원문·PENDING 저장 → MySQL COMMIT → 발신자 ACK
                                  ↓
                         사용자 언어별 비동기 번역
                                  ↓
                  번역 저장 → 수신자에게 원문+번역 한 번에 전달
```

처리 순서:

1. 신규 원문과 수신자·대상 언어의 `PENDING` 작업을 같은 DB transaction에 저장한다.
2. 대상 언어는 수신자의 `users.lang`에서 읽고 클라이언트 값은 받지 않는다.
3. worker가 커밋된 작업을 가져와 `text/plain`으로 Google 번역을 호출한다.
4. 원문 언어는 사용자 설정으로 추정하지 않고 provider의 자동 감지를 사용한다.
5. 성공하면 사용자별 번역본을 저장하고 `/user/queue/chat-translations`로 원문과 번역본을 한 payload에 담아 보낸다.
6. 같은 언어면 `NOT_REQUIRED`로 끝낸다.
7. 재시도 가능한 오류는 한 작업 안에서 짧은 간격으로 Google 호출을 최대 5회 시도하고 계속 실패하면 `FAILED`로 끝낸다.

다섯 번은 최초 요청을 포함한 총 호출 횟수다. 같은 Worker 실행 안에서 기본 0.2초부터 짧고 설정 가능한 backoff를 두되, 전체 전달 기한 기본 5초를 넘겨 새 호출을 시작하지 않는다. 별도의 다음 재시도 시각은 저장하지 않는다. timeout·연결 오류·429·5xx만 재시도하고 잘못된 요청이나 인증·설정 오류는 5회를 채우지 않고 바로 `FAILED`로 끝낼 수 있다. 마지막 호출 중 프로세스가 종료되어 `attempt_count=5`, `PROCESSING`으로 남으면 lease 만료 뒤 Google을 다시 호출하지 않고 `FAILED`로 종결한다.

`PENDING`과 `PROCESSING`은 내부 작업 상태일 뿐 `번역 중`이라는 화면 문구를 백엔드가 만들거나 보내지 않는다. 외부 payload는 [API 계약의 받은 TEXT 원문·번역 결합 이벤트](02-api-contracts.md#64-받은-text-원문번역-결합-이벤트)를 따른다.

번역 실패는 원문 SEND 실패가 아니므로 `/user/queue/chat-errors`로 보내지 않는다. `FAILED` 최종 이벤트에도 원문을 함께 넣으므로 수신자는 원문을 표시할 수 있고, 발신자의 저장 ACK도 그대로 유지한다.

받은 TEXT 이벤트는 공용 room topic이 아닌 대상 사용자의 개인 queue로만 보낸다. 원문과 번역 결과가 같은 payload에 있으므로 프런트엔드가 두 이벤트의 도착 순서를 조정하거나 나중에 합칠 필요가 없다.

Simple Broker는 번역 이벤트를 재생하지 않는다. 연결 중 놓친 번역본은 다음 REST 메시지 이력 조회에서 MySQL 결과로 복구한다.

## 8. clientMessageId

`clientMessageId`는 사용자가 보내는 `TEXT`에 대해 서버가 아니라 클라이언트가 전송 직전에 만드는 UUID다. 서버 생성 `BOOKING_CARD`에는 사용하지 않는다.

네트워크 timeout 뒤 클라이언트가 같은 메시지를 다시 보내더라도 같은 UUID를 사용한다. 서버는 `(roomId, senderId, clientMessageId)` UNIQUE 제약으로 이미 저장된 요청을 알아낸다.

- 같은 ID·같은 본문: 기존 `messageId`와 `sentAt` 반환
- 같은 ID·다른 본문: `CHAT_CLIENT_MESSAGE_CONFLICT`
- 새 ID: 새 메시지 저장

`clientMessageId`는 SET에 별도로 저장하는 값이 아니다. `chat_messages` 행의 일반 컬럼이며 MySQL UNIQUE index가 중복을 막는다.

프런트엔드는 이 값으로 임시 말풍선을 서버 메시지와 연결할 수 있다. 백엔드는 임시 말풍선 자체를 저장하지 않는다.

## 9. 저장 결과·오류·방 이벤트

### 저장 결과

이 문서의 ACK는 STOMP protocol delivery ACK가 아니라 애플리케이션 수준의 DB 저장 결과다. 원래 SEND를 보낸 session에만 전달한다.

```json
{
  "version": 1,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "messageId": 70051,
  "sentAt": "2026-08-19T10:15:30.123456Z",
  "duplicate": false
}
```

### 오류

```json
{
  "version": 1,
  "clientMessageId": "b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849",
  "code": "CHAT_MESSAGE_TOO_LONG",
  "message": "메시지는 3,000자 이하여야 합니다."
}
```

CONNECT 인증 실패는 STOMP ERROR 후 연결을 종료한다. 개별 SEND 오류는 원래 발신 session의 `/user/queue/chat-errors`로만 보낸다.

### 방 이벤트

```json
{
  "version": 1,
  "eventType": "ROOM_CREATED",
  "roomId": 556,
  "lastMessageId": null,
  "occurredAt": "2026-08-19T10:15:30.123456Z"
}
```

`eventType`은 `ROOM_CREATED`, `ROOM_UPDATED`, `ROOM_REOPENED` 중 하나다. `ROOM_REOPENED`는 직접 문의나 실제 새 메시지로 숨긴 채팅방이 목록에 다시 나타났다는 뜻이며, 삭제한 과거 메시지를 복원했다는 뜻이 아니다. 이 이벤트는 목록을 즉시 갱신하기 위한 신호일 뿐 정본이 아니며, 오프라인에서 놓치면 다음 REST 채팅방 목록 조회로 보충한다.

사용자가 보낸 TEXT는 원래 발신 session의 ACK로 임시 말풍선을 확정한다. 다른 사용자가 보낸 TEXT는 개인 translation queue의 결합 이벤트를 `messageId`로 중복 제거한다. 서버 생성 `BOOKING_CARD`에는 ACK가 없으며 room topic 이벤트의 `messageId`와 `bookingCard.bookingId`로 중복을 제거한다.

## 10. 재연결과 누락 복구

Simple Broker는 끊긴 session에 메시지를 재생하지 않는다. 재연결은 다음 순서다.

1. 지수 backoff와 jitter로 WebSocket 재연결
2. 새 access token으로 CONNECT
3. control queue ping/pong과 room `SUBSCRIPTION_READY` barrier 완료
4. 마지막 연속 DB 동기화 checkpoint를 `afterMessageId`로 REST 조회
5. high-watermark까지 모든 page 조회
6. 완료한 뒤에만 checkpoint를 high-watermark로 전진
7. 같은 기간의 live room topic·translation queue 이벤트와 `messageId`로 병합

topic에서 받은 가장 큰 ID를 DB checkpoint로 사용하면 안 된다. 예를 들어 DB 101번 발행은 실패하고 102번만 topic에서 받았을 때 checkpoint를 102로 올리면 101번을 영구히 놓친다. 화면은 102번을 즉시 표시할 수 있지만 **연속 DB 범위 확인 checkpoint는 REST catch-up이 끝날 때만 전진**한다.

`chat-translations` 개인 queue도 room topic보다 먼저 구독한다. 번역 이벤트를 놓쳤더라도 별도 번역 checkpoint를 만들지 않고 REST 메시지 응답의 `translation`으로 복구한다.

## 11. Broker와 heartbeat

현재 실제 운영은 EC2 한 대·애플리케이션 JVM 한 개이므로 Spring `enableSimpleBroker`를 사용한다.

- 구독 정보는 JVM 메모리에만 존재한다.
- 서버 재시작 시 연결·구독은 사라지지만 MySQL 메시지는 유지된다.
- 클라이언트가 reconnect → resubscribe → REST catch-up으로 복구한다.
- heartbeat는 양방향 10초를 기본으로 하고 broker용 `TaskScheduler`를 명시한다.

두 번째 JVM, rolling deployment 중 인스턴스 겹침, 서버 간 fan-out, durable delivery 요구가 생기기 전에 RabbitMQ STOMP Broker Relay 또는 동등한 공유 broker로 전환한다. sticky session만으로는 서버 간 전달 문제가 해결되지 않는다.
