# 신청 · 문의 (인앱 채팅) API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

## 개요

세입자(외국인 사용자)가 매물에 **신청(예약, Booking)** 하거나 임대인에게 **문의** 하면, 해당 매물 임대인과의 1:1 **채팅방(ChatRoom)** 을 통해 소통한다.

- **신청하기**: 입주 희망일 + 계약기간으로 예약을 생성하고, 임대인과의 채팅방에 **예약 정보 카드** 시스템 메시지를 자동 전송·고정한다. 동일 매물 중복 신청을 막는다.
- **문의하기**: 임대인과의 채팅방을 생성(없으면)하거나 기존 방을 반환하고, **매물 정보 카드** 를 고정한다.
- **채팅**: 채팅방 리스트 조회, 메시지 조회(커서 페이지네이션), 텍스트 메시지 전송(이미지 불가), 읽음 처리.
- 본인이 참여하지 않은 방에 접근하면 `403 FORBIDDEN`. 새 메시지 수신 시 도메인 이벤트로 푸시 알림을 발행한다.

### 핵심 개념·enum

| 개념 | 값 | 설명 |
| --- | --- | --- |
| 계약기간 `contractPeriod` | `ONE_MONTH`, `THREE_MONTHS`, `SIX_MONTHS`, `TWELVE_MONTHS` | 신청 시 선택하는 계약 기간 |
| 예약 상태 `status` (Booking) | `REQUESTED`, `ACCEPTED`, `REJECTED`, `CANCELED` | 신청 직후 `REQUESTED`. 임대인 수락/거절·세입자 취소는 본 스펙 범위 밖(확장 시 정의) |
| 채팅방 카테고리 `category` | `LANDLORD`, `NEIGHBOR` | 본 기능의 신청·문의로 생성되는 방은 모두 `LANDLORD`. `NEIGHBOR`(이웃 채팅)는 다른 기능에서 생성되며 본 스펙은 리스트 조회의 필터 값으로만 노출한다 |
| 메시지 타입 `type` | `TEXT`, `BOOKING_CARD`, `LISTING_CARD`, `SYSTEM` | `BOOKING_CARD`/`LISTING_CARD`는 서버가 생성하는 고정 카드. 사용자 전송은 `TEXT`만 허용 |
| 참여자 역할 | 세입자(요청자) / 임대인(매물 소유자) | 방은 (매물, 세입자, 임대인) 조합으로 유일 |

- 날짜만 표기는 `YYYY-MM-DD`(예: `moveInDate`), 시각은 ISO-8601 UTC(예: `2026-06-15T08:30:00Z`).
- 금액은 KRW 정수(예: `monthlyRent: 500000`).
- `listingId`는 MongoDB ObjectId의 24자리 hex 문자열이다. `bookingId`·`chatRoomId`·`messageId`는 각 모듈 저장소의 숫자 식별자를 유지한다.
- **고정 메시지(pinned)**: 채팅방 상단에 고정되는 카드. `BOOKING_CARD`/`LISTING_CARD`가 `pinned: true`로 내려간다.

---

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/listings/{listingId}/bookings` | 매물 신청(예약 생성) + 예약 카드 자동 전송 | 필수 | 201 |
| POST | `/api/v1/listings/{listingId}/inquiries` | 매물 문의(채팅방 생성/조회) + 매물 카드 고정 | 필수 | 201 (신규) / 200 (기존 반환) |
| GET | `/api/v1/chat-rooms` | 내 채팅방 리스트 조회(오프셋 페이지네이션) | 필수 | 200 |
| GET | `/api/v1/chat-rooms/{roomId}/messages` | 채팅방 메시지 조회(커서 페이지네이션) | 필수 | 200 |
| POST | `/api/v1/chat-rooms/{roomId}/messages` | 텍스트 메시지 전송 | 필수 | 201 |
| POST | `/api/v1/chat-rooms/{roomId}/read` | 읽음 처리(마지막 읽은 메시지까지 갱신) | 필수 | 200 |

> 신청/문의는 매물에 종속되는 액션이므로 `/listings/{listingId}` 하위 1단계 중첩으로 둔다(api-design-guide §2). 채팅방·메시지는 독립 컬렉션으로 둔다.

---

## 상세

### 1. POST `/api/v1/listings/{listingId}/bookings` — 매물 신청(예약 생성)

입주 희망일과 계약기간으로 예약을 생성한다. 생성과 동시에 임대인과의 채팅방을 보장(없으면 생성)하고, 그 방에 **예약 정보 카드**(`BOOKING_CARD`) 시스템 메시지를 전송·고정한다. **동일 세입자–동일 매물의 활성 예약은 1건만** 허용한다(중복 신청 방지). 새 메시지에 대해 임대인에게 푸시 알림 이벤트를 발행한다.

- **인증**: 필수. 요청자는 세입자가 된다. 본인이 소유한 매물에는 신청할 수 없다.
- **멱등성**: 예약은 중복 위험 POST이므로 `Idempotency-Key` 헤더를 선택 지원한다(api-design-guide §6). 헤더가 없어도 (세입자, 매물) 유니크 제약으로 활성 예약은 1건만 보장된다.

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 신청 대상 매물 ID(ObjectId hex 문자열) |

#### Request Body

```json
{
  "moveInDate": "2026-07-01",
  "contractPeriod": "SIX_MONTHS",
  "message": "안녕하세요, 7월 입주 희망합니다."
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `moveInDate` | string(`YYYY-MM-DD`) | 필수 | 날짜 형식(`YYYY-MM-DD`). 형식 위반은 `MALFORMED_REQUEST`, 형식은 맞으나 과거/입주 가능일 이전이면 `BOOKING_INVALID_MOVE_IN_DATE`(422) |
| `contractPeriod` | enum | 필수 | `ONE_MONTH`/`THREE_MONTHS`/`SIX_MONTHS`/`TWELVE_MONTHS` 중 하나. 누락·미정의 값은 `INVALID_INPUT`(400) |
| `message` | string | 선택 | 첫 인사 메시지. 최대 500자. 있으면 `TEXT` 메시지로 함께 전송 |

#### 성공 Response — 201 Created

`Location: /api/v1/chat-rooms/{roomId}`

```json
{
  "success": true,
  "data": {
    "bookingId": 9001,
    "listingId": "6858e2000000000000000001",
    "status": "REQUESTED",
    "moveInDate": "2026-07-01",
    "contractPeriod": "SIX_MONTHS",
    "chatRoomId": 555,
    "bookingCard": {
      "messageId": 70001,
      "type": "BOOKING_CARD",
      "pinned": true,
      "moveInDate": "2026-07-01",
      "contractPeriod": "SIX_MONTHS",
      "monthlyRent": 500000,
      "listingTitle": "강남역 도보 5분 원룸"
    },
    "createdAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 필수값 누락, `contractPeriod` enum 불일치, `message` 길이 초과 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가 또는 필드 타입 불일치(예: `moveInDate` 날짜 형식 위반) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 404 | `LISTING_NOT_FOUND` | 매물이 없거나 비공개/삭제됨 |
| 409 | `BOOKING_ALREADY_EXISTS` | 동일 세입자–매물의 활성 예약(`REQUESTED`/`ACCEPTED`)이 이미 존재 |
| 422 | `BOOKING_INVALID_MOVE_IN_DATE` | `moveInDate`가 과거이거나 매물의 입주 가능일 이전 |
| 422 | `BOOKING_SELF_NOT_ALLOWED` | 본인이 소유한 매물에 신청 |

---

### 2. POST `/api/v1/listings/{listingId}/inquiries` — 매물 문의(채팅방 생성/조회)

해당 매물 임대인과의 채팅방을 반환한다. 방이 없으면 새로 만들고 **매물 정보 카드**(`LISTING_CARD`)를 고정한 뒤 `201`을, 이미 있으면 기존 방을 `200`으로 반환한다(멱등적 보장). 신규 생성 시에만 매물 카드 메시지가 추가된다.

- **인증**: 필수. 요청자는 세입자가 된다. 본인이 소유한 매물에는 문의할 수 없다.

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 문의 대상 매물 ID(ObjectId hex 문자열) |

#### Request Body

본문 없음(빈 본문). 첫 메시지를 함께 보내려면 방 생성 후 메시지 전송 API(아래 5)를 호출한다.

#### 성공 Response — 201 Created (신규 생성) / 200 OK (기존 방 반환)

신규는 `Location: /api/v1/chat-rooms/{roomId}` 헤더를 포함한다.

```json
{
  "success": true,
  "data": {
    "chatRoomId": 556,
    "category": "LANDLORD",
    "created": true,
    "listing": {
      "listingId": "6858e2000000000000000001",
      "title": "강남역 도보 5분 원룸",
      "thumbnailUrl": "https://cdn.kohere.com/listings/6858e2000000000000000001/thumb.jpg",
      "monthlyRent": 500000
    },
    "counterpart": {
      "userId": 42,
      "nickname": "집주인A",
      "profileImageUrl": "https://cdn.kohere.com/users/42/profile.jpg"
    },
    "listingCard": {
      "messageId": 80001,
      "type": "LISTING_CARD",
      "pinned": true,
      "listingId": "6858e2000000000000000001",
      "title": "강남역 도보 5분 원룸",
      "monthlyRent": 500000
    }
  },
  "error": null
}
```

> `created`가 `true`면 신규(201), `false`면 기존 방(200). POST이지만 기존 방 반환은 생성 아닌 액션이므로 `200`을 쓴다(api-design-guide §1). 기존 방 반환 시 `listingCard`는 이미 고정된 카드 정보를 그대로 담는다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 404 | `LISTING_NOT_FOUND` | 매물이 없거나 비공개/삭제됨 |
| 422 | `CHAT_SELF_INQUIRY_NOT_ALLOWED` | 본인이 소유한 매물에 문의 |

---

### 3. GET `/api/v1/chat-rooms` — 내 채팅방 리스트

요청자가 참여한 채팅방 목록을 마지막 메시지 시각 내림차순으로 반환한다. **오프셋 페이지네이션**(api-design-guide §4-1).

- **인증**: 필수. 본인이 참여한 방만 반환된다(타인 방은 애초에 목록에 없음).

#### Query 파라미터

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `category` | enum | 선택 | (전체) | `LANDLORD` / `NEIGHBOR`. 미지정 시 전체. 미정의 값은 `INVALID_INPUT`(api-design-guide §5) |
| `page` | int | 선택 | 0 | 0-base 페이지 번호 |
| `size` | int | 선택 | 20 | 페이지 크기(최대 100) |

> 정렬은 `lastMessageAt,desc` 고정(쿼리로 변경 불가).

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "chatRoomId": 555,
        "category": "LANDLORD",
        "listing": {
          "listingId": "6858e2000000000000000001",
          "title": "강남역 도보 5분 원룸",
          "thumbnailUrl": "https://cdn.kohere.com/listings/6858e2000000000000000001/thumb.jpg"
        },
        "counterpart": {
          "userId": 42,
          "nickname": "집주인A",
          "profileImageUrl": "https://cdn.kohere.com/users/42/profile.jpg"
        },
        "lastMessage": {
          "type": "TEXT",
          "preview": "네, 내일 방문 가능합니다.",
          "sentAt": "2026-06-15T09:10:00Z"
        },
        "unreadCount": 3,
        "lastMessageAt": "2026-06-15T09:10:00Z"
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 4,
      "totalPages": 1,
      "hasNext": false
    }
  },
  "error": null
}
```

> `lastMessage.preview`: `TEXT`는 본문 앞부분, 카드/시스템 메시지는 타입에 대응하는 요약 문구(클라이언트가 `type`으로 다국어 매핑). `unreadCount`는 요청자가 아직 읽지 않은 메시지 수. 참여 중인 방이 없으면 `content: []` + `page.totalElements: 0` + `page.hasNext: false`(에러 아님).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `category` enum 불일치, `size` 범위 초과 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |

---

### 4. GET `/api/v1/chat-rooms/{roomId}/messages` — 채팅방 메시지 조회

채팅방의 메시지를 최신순으로 **커서 페이지네이션**(api-design-guide §4-2)으로 반환한다. 고정 카드(`pinned: true`)도 메시지 목록에 포함되며 상단 고정 표시는 클라이언트가 `pinned`로 처리한다.

- **인증**: 필수. **본인이 참여하지 않은 방이면 `403 FORBIDDEN`.**

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `roomId` | Long | 필수 | 채팅방 ID |

#### Query 파라미터

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `cursor` | string | 선택 | (첫 페이지 생략) | `nextCursor` 토큰. 해당 메시지보다 **이전(과거)** 메시지를 조회 |
| `size` | int | 선택 | 30 | 페이지 크기(최대 100) |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "messageId": 70050,
        "type": "TEXT",
        "senderId": 42,
        "mine": false,
        "content": "네, 내일 방문 가능합니다.",
        "pinned": false,
        "sentAt": "2026-06-15T09:10:00Z"
      },
      {
        "messageId": 70001,
        "type": "BOOKING_CARD",
        "senderId": null,
        "mine": false,
        "pinned": true,
        "card": {
          "moveInDate": "2026-07-01",
          "contractPeriod": "SIX_MONTHS",
          "monthlyRent": 500000,
          "listingTitle": "강남역 도보 5분 원룸"
        },
        "sentAt": "2026-06-15T08:30:00Z"
      }
    ],
    "nextCursor": "70001",
    "hasNext": true
  },
  "error": null
}
```

> 시스템·카드 메시지는 `senderId: null`(서버 생성). `mine`은 요청자가 보낸 메시지면 `true`. `content`는 `TEXT`에만, `card`는 카드 타입에만 존재한다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `size` 범위 초과, `cursor` 형식 오류 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `FORBIDDEN` | 요청자가 해당 방의 참여자가 아님 |
| 404 | `CHAT_ROOM_NOT_FOUND` | 방이 존재하지 않음 |

---

### 5. POST `/api/v1/chat-rooms/{roomId}/messages` — 텍스트 메시지 전송

채팅방에 **텍스트 메시지**(`TEXT`)를 보낸다. **이미지·파일 전송은 허용하지 않는다.** 전송 시 방의 `lastMessageAt`을 갱신하고, 상대방에게 푸시 알림 도메인 이벤트를 발행한다.

- **인증**: 필수. **본인이 참여하지 않은 방이면 `403 FORBIDDEN`.**
- 도배 방지를 위해 레이트리밋을 둘 수 있다(초과 시 `429 TOO_MANY_REQUESTS`).

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `roomId` | Long | 필수 | 채팅방 ID |

#### Request Body

```json
{
  "content": "안녕하세요, 내일 방문 가능할까요?"
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `content` | string | 필수 | 공백 제외 1~1000자. 빈 문자열·공백만 불가 |

> 카드/시스템 메시지는 사용자가 보낼 수 없다(서버 전용). `type`을 본문으로 받지 않으며 항상 `TEXT`로 저장한다.

#### 성공 Response — 201 Created

```json
{
  "success": true,
  "data": {
    "messageId": 70051,
    "type": "TEXT",
    "senderId": 7,
    "mine": true,
    "content": "안녕하세요, 내일 방문 가능할까요?",
    "pinned": false,
    "sentAt": "2026-06-15T09:12:00Z"
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `content` 누락/빈값/길이 초과 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `FORBIDDEN` | 요청자가 해당 방의 참여자가 아님 |
| 404 | `CHAT_ROOM_NOT_FOUND` | 방이 존재하지 않음 |
| 422 | `CHAT_ROOM_INACTIVE` | 비활성(차단/나간) 방에 전송. 차단·나가기 기능 도입 시에만 발생(현 범위에서는 트리거되지 않는 예약 코드) |
| 429 | `TOO_MANY_REQUESTS` | 메시지 도배(레이트리밋 초과) |

---

### 6. POST `/api/v1/chat-rooms/{roomId}/read` — 읽음 처리

요청자의 "마지막 읽은 메시지" 위치를 갱신해 안읽음 수를 0으로 만든다. 상태 전이 액션이므로 동사형 서브경로를 쓴다(api-design-guide §1). 멱등적이다(같은 값으로 반복 호출해도 동일 결과).

- **인증**: 필수. **본인이 참여하지 않은 방이면 `403 FORBIDDEN`.**

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `roomId` | Long | 필수 | 채팅방 ID |

#### Request Body

```json
{
  "lastReadMessageId": 70051
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `lastReadMessageId` | Long | 선택 | 읽음으로 표시할 마지막 메시지 ID. 생략 시 방의 가장 최신 메시지까지 읽음 처리. 해당 방의 메시지여야 함. 현재 읽음 위치보다 과거 ID면 무시(전진만) |

#### 성공 Response — 200 OK

```json
{
  "success": true,
  "data": {
    "chatRoomId": 555,
    "lastReadMessageId": 70051,
    "unreadCount": 0
  },
  "error": null
}
```

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `MALFORMED_REQUEST` | `lastReadMessageId`가 숫자가 아닌 타입(JSON 타입 불일치) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `FORBIDDEN` | 요청자가 해당 방의 참여자가 아님 |
| 404 | `CHAT_ROOM_NOT_FOUND` | 방이 존재하지 않음 |
| 422 | `CHAT_MESSAGE_NOT_IN_ROOM` | `lastReadMessageId`가 해당 방의 메시지가 아님 |

---

## 도메인 에러 코드

> 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN`, `TOO_MANY_REQUESTS` 등)는 [error-response-guide](../error-response-guide.md) §4를 따르며 여기서 재정의하지 않는다. 아래는 본 기능 고유 코드만 정의한다. prefix는 `BOOKING` / `CHAT`.

| code | status | 의미 |
| --- | --- | --- |
| `BOOKING_ALREADY_EXISTS` | 409 | 동일 세입자–매물에 활성 예약(`REQUESTED`/`ACCEPTED`)이 이미 존재(중복 신청) |
| `BOOKING_INVALID_MOVE_IN_DATE` | 422 | 입주 희망일이 과거이거나 매물의 입주 가능일 이전 |
| `BOOKING_SELF_NOT_ALLOWED` | 422 | 본인 소유 매물에 신청 시도 |
| `CHAT_ROOM_NOT_FOUND` | 404 | 채팅방이 존재하지 않음 |
| `CHAT_SELF_INQUIRY_NOT_ALLOWED` | 422 | 본인 소유 매물에 문의 시도 |
| `CHAT_ROOM_INACTIVE` | 422 | 비활성(차단/나간) 채팅방에 메시지 전송. 차단·나가기 기능 도입 시 활성화되는 예약 코드 |
| `CHAT_MESSAGE_NOT_IN_ROOM` | 422 | 읽음 처리 시 지정한 메시지가 해당 방에 속하지 않음 |

> 매물 부재(`404`)는 listing 모듈의 `LISTING_NOT_FOUND` 코드를 참조해 응답한다. 해당 코드는 listing 스펙이 카탈로그에 등록하는 것을 원칙으로 하며, 본 기능에서는 재정의하지 않는다.
