# 프론트엔드 문의서(INQUIRY_CARD) 연동 가이드

> 대상: 앱·웹 프론트엔드 개발자
>
> 최종 수정: 2026-08-28
>
> 관련 Swagger: `Chats → POST /api/v1/listings/{listingId}/inquiries`, `Chats → GET /api/v1/chat-rooms/{roomId}/messages`

이 문서는 사용자가 매물 상세 화면에서 **문의하기**를 눌렀을 때 채팅방에 표시되는 문의서 카드를 설명한다. 전체 채팅 연결 방법은 [09-frontend-stomp-guide.md](09-frontend-stomp-guide.md)를 참고하고, 이 문서는 `INQUIRY_CARD`에 필요한 내용만 자세히 다룬다.

## 1. 한 번에 이해하기

```text
사용자가 문의하기 선택
  → 프론트가 listingId로 문의 API 호출
  → 서버가 기존 채팅방을 찾거나 새 채팅방을 생성
  → 새 채팅방이면 서버가 INQUIRY_CARD를 첫 메시지로 함께 저장
  → 프론트가 chatRoomId로 메시지 이력 조회
  → type=INQUIRY_CARD를 문의서 UI로 표시
  → View Detail 선택 시 inquiryCard.listingId로 매물 상세 화면 이동
```

중요한 규칙은 다음 세 가지다.

1. 프론트는 문의서 내용을 만들어 보내지 않고 `listingId`만 보낸다.
2. `INQUIRY_CARD`는 **새 문의 채팅방이 만들어질 때 한 번만** 저장된다.
3. 문의서의 `View Detail`은 `inquiryCard.listingId`를 사용한다.

## 2. BOOKING_CARD와 무엇이 다른가

| 구분 | INQUIRY_CARD | BOOKING_CARD |
| --- | --- | --- |
| 의미 | 어떤 매물에 관해 문의를 시작했는지 보여 주는 문의서 | 입주 신청이 완료됐음을 보여 주는 신청서 |
| 생성 시점 | 문의하기로 새 채팅방을 만들 때 | 입주 신청 저장이 완료될 때 |
| 주요 정보 | 대표 이미지, 매물 제목, 지역, 건물 유형, 월세 범위 | 신청자, 선택한 방, 입주일, 계약 기간, 보증금, 비용 |
| 생성 주체 | 서버 | 서버 |
| 프론트 SEND 여부 | 보내지 않음 | 보내지 않음 |
| 메시지 `type` | `INQUIRY_CARD` | `BOOKING_CARD` |
| 응답 객체 | `inquiryCard` | `bookingCard` |

문의하기와 입주 신청은 매물·임차인·임대인이 같으면 같은 채팅방을 사용한다. 문의 후 신청하면 기존 문의서와 대화 뒤에 `BOOKING_CARD`가 추가된다.

## 3. 문의하기 API 호출

매물 상세 화면의 문의하기 버튼을 눌렀을 때 호출한다.

```http
POST /api/v1/listings/{listingId}/inquiries
Authorization: Bearer <accessToken>
```

- `{listingId}`에는 현재 보고 있는 매물 ID를 넣는다.
- 요청 body는 없다.
- 사용자 ID, 임대인 ID, 이미지, 제목, 가격은 보내지 않는다.
- 서버가 access token으로 현재 사용자를 확인하고 매물 정본에서 카드 정보를 조회한다.

### 새 채팅방을 만든 응답

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

HTTP 상태는 `201 Created`다. `created=true`는 이번 요청에서 다음 데이터가 모두 저장됐다는 뜻이다.

- 새 채팅방
- 임차인·임대인 참여자 두 명
- 첫 메시지 `INQUIRY_CARD`

### 기존 채팅방을 찾은 응답

```json
{
  "success": true,
  "data": {
    "chatRoomId": 556,
    "created": false
  },
  "error": null
}
```

HTTP 상태는 `200 OK`다. `created=false`는 이미 같은 매물·임차인·임대인의 채팅방이 있다는 뜻이다. 이때 서버는 문의서를 다시 저장하지 않는다.

| 응답 필드 | 의미 | 프론트 처리 |
| --- | --- | --- |
| `data.chatRoomId` | 열어야 할 서버 채팅방 ID | 상세 조회, 메시지 이력 조회, STOMP 구독에 사용 |
| `data.created` | 이번 요청에서 새 방과 문의서를 만들었는지 여부 | 화면 이동을 나누기 위한 값이 아니며 두 경우 모두 같은 채팅방을 연다 |

`created=false`여도 오류가 아니다. 사용자가 문의하기를 다시 눌렀거나, 입주 신청 과정에서 이미 같은 채팅방이 만들어진 정상 상황이다.

## 4. 권장 화면 진입 순서

문의 API가 새 방과 문의서를 저장하자마자 실시간 이벤트를 발행할 수 있다. 프론트는 그 시점에 새 방의 topic을 아직 구독하지 않았을 수 있으므로 다음 순서를 권장한다.

```text
1. POST /api/v1/listings/{listingId}/inquiries
2. 응답의 chatRoomId 저장
3. GET /api/v1/chat-rooms/{roomId}로 채팅방 상단 정보 조회
4. GET /api/v1/chat-rooms/{roomId}/messages?size=30으로 저장된 메시지 조회
5. STOMP 연결 후 /topic/chat-rooms/{roomId} 구독
6. SUBSCRIPTION_READY 기준으로 필요하면 누락 메시지 보충
```

핵심은 `created=true`일 때도 문의서를 STOMP에서만 기다리지 않는 것이다. 메시지 이력 API를 호출하면 이미 MySQL에 저장된 문의서를 안정적으로 받을 수 있다.

## 5. 메시지 이력에서 문의서 받기

```http
GET /api/v1/chat-rooms/{roomId}/messages?size=30
Authorization: Bearer <accessToken>
```

문의서가 포함된 전체 응답 예시는 다음과 같다.

```json
{
  "success": true,
  "data": {
    "content": [
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
        "sentAt": "2026-08-28T10:14:30.123456Z"
      }
    ],
    "nextCursor": null,
    "hasNext": false
  },
  "error": null
}
```

### 메시지 공통 필드

| 필드 | 문의서에서 오는 값 | 의미 |
| --- | --- | --- |
| `messageId` | 숫자 | 서버가 저장한 문의서 메시지 ID. REST·STOMP 중복 제거와 정렬 기준 |
| `clientMessageId` | `null` | 사용자가 직접 보낸 TEXT가 아니므로 프론트 UUID가 없음 |
| `chatRoomId` | 숫자 | 문의서가 속한 채팅방 ID |
| `senderId` | `null` | 특정 사용자가 보낸 말풍선이 아니라 서버가 만든 카드임 |
| `mine` | `false` | 서버 생성 카드이므로 임차인·임대인 모두 false |
| `type` | `INQUIRY_CARD` | 프론트가 문의서 컴포넌트를 선택하는 기준 |
| `originalContent` | `null` | 일반 TEXT 본문이 없음 |
| `bookingCard` | `null` | 신청서가 아니라 문의서임 |
| `inquiryCard` | 객체 | 문의서 UI에 표시할 값 |
| `translation` | `null` | 문의서는 Google 번역 대상이 아님 |
| `sentAt` | ISO-8601 시각 | 문의서가 서버에 저장된 시각 |

### inquiryCard 내부 필드

| 필드 | 타입 | null 가능 | 의미와 사용 방법 |
| --- | --- | --- | --- |
| `listingId` | string | 불가 | 문의한 매물 ID. View Detail의 화면 이동에 사용 |
| `thumbnailUrl` | string | 가능 | 문의 당시 첫 번째 대표 이미지 URL. null이면 기본 이미지 또는 이미지 없음 UI 표시 |
| `title` | string | 불가 | 문의 당시 매물 제목 |
| `city` | string | 불가 | 주소의 city code. 프론트가 현재 언어의 label로 변환 |
| `district` | string | 불가 | 주소의 district code. 프론트가 현재 언어의 label로 변환 |
| `listingType` | string | 불가 | 건물 유형 code: `GOSHIWON`, `CO_LIVING`, `SHARE_HOUSE` |
| `monthlyRentMin` | number | 불가 | 문의 당시 공개된 활성 방 중 최소 월세. 단위는 KRW |
| `monthlyRentMax` | number | 불가 | 문의 당시 공개된 활성 방 중 최대 월세. 단위는 KRW |

문의서는 생성 시점의 화면 정보를 저장한 사본이다. 이후 매물 제목·가격·이미지가 바뀌더라도 기존 채팅방의 문의서는 자동으로 바뀌지 않는다.

## 6. 문의서 UI 표시 규칙

### 이미지

```text
thumbnailUrl이 값 있음 → 해당 URL의 이미지 표시
thumbnailUrl이 null     → 앱 기본 매물 이미지 또는 이미지 없음 UI 표시
```

### 지역

`city`와 `district`는 번역된 문장이 아니라 안정적인 code다. 프론트가 기존 매물 code/label 기준을 재사용해 현재 화면 언어에 맞게 표시한다.

```text
ko 예시: 서울 · 마포구
en 예시: Seoul · Mapo-gu
```

서버 code를 화면에 그대로 노출하지 않는다. 프론트에 아직 해당 code의 label이 없을 때만 code 자체를 임시 fallback으로 사용할 수 있다.

### 건물 유형

| `listingType` | 한국어 예시 | 영어 예시 |
| --- | --- | --- |
| `GOSHIWON` | 고시원 | Goshiwon |
| `CO_LIVING` | 코리빙 | Co-living |
| `SHARE_HOUSE` | 쉐어하우스 | Share house |

고정 label은 Google 번역 결과를 사용하지 않고 앱의 `ko/en` 리소스로 표시한다.

### 월세 범위

```text
monthlyRentMin == monthlyRentMax
  → 한 금액만 표시

monthlyRentMin != monthlyRentMax
  → 최소 금액 ~ 최대 금액으로 표시
```

예시는 다음과 같다.

```text
ko: 월 ₩350,000 ~ ₩500,000
en: ₩350,000–₩500,000/mo
```

금액은 원 단위 정수이므로 프론트가 천 단위 구분 기호와 월세 label을 적용한다.

## 7. View Detail 처리

문의서의 `View Detail`을 누르면 별도 채팅 API를 호출하지 않는다.

```text
inquiryCard.listingId 읽기
  → 기존 매물 상세 화면 route로 이동
  → 매물 상세 정본이 필요하면 GET /api/v2/listings/{listingId} 사용
```

예시:

```typescript
function onInquiryCardViewDetail(card: InquiryCard) {
  navigateToListingDetail(card.listingId);
}
```

매물이 나중에 비공개·삭제됐다면 매물 상세 조회가 실패할 수 있다. 그래도 채팅 이력에 저장된 문의서 자체는 그대로 표시할 수 있다.

## 8. STOMP에서 실시간 문의서 받기

새 문의서가 저장되면 같은 채팅방 topic으로 `MESSAGE_CREATED` 이벤트가 올 수 있다.

```text
/topic/chat-rooms/{roomId}
```

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
  "sentAt": "2026-08-28T10:14:30.123456Z"
}
```

REST 응답과 달리 실시간 payload에는 `version`과 `eventType`이 있고, `mine`·`translation`은 없다. `inquiryCard` 내부 필드는 REST와 동일하다.

- 프론트는 문의서를 `/app/chat-rooms/{roomId}/messages`로 SEND하지 않는다.
- 서버 카드에는 임시 말풍선이 없으므로 `/user/queue/chat-acks` ACK도 오지 않는다.
- REST와 STOMP에서 같은 `messageId`를 받으면 한 번만 표시한다.
- 신규 방의 topic 구독 전에 이벤트가 발행될 수 있으므로 첫 표시는 반드시 REST 이력으로도 확인한다.

## 9. 프론트 분기 예시

```typescript
type MessageType = "TEXT" | "INQUIRY_CARD" | "BOOKING_CARD";

type InquiryCard = {
  listingId: string;
  thumbnailUrl: string | null;
  title: string;
  city: string;
  district: string;
  listingType: "GOSHIWON" | "CO_LIVING" | "SHARE_HOUSE";
  monthlyRentMin: number;
  monthlyRentMax: number;
};

function renderChatMessage(message: ChatMessage) {
  switch (message.type as MessageType) {
    case "INQUIRY_CARD":
      if (!message.inquiryCard) {
        return renderInvalidMessageFallback();
      }
      return renderInquiryCard(message.inquiryCard);

    case "BOOKING_CARD":
      return renderBookingCard(message.bookingCard);

    case "TEXT":
      return renderTextMessage(message);
  }
}
```

`type=INQUIRY_CARD`인데 `inquiryCard=null`인 응답은 정상 계약이 아니다. 앱 전체를 중단하지 말고 해당 메시지만 fallback UI로 처리하고 오류 수집 도구에 기록하는 방식을 권장한다.

## 10. 상황별 결과

| 상황 | 문의 API 결과 | INQUIRY_CARD 결과 |
| --- | --- | --- |
| 처음 문의함 | `201`, `created=true` | 새 방의 첫 메시지로 한 번 저장 |
| 같은 매물에 문의하기를 다시 누름 | `200`, `created=false` | 새로 추가하지 않음 |
| 문의 후 입주 신청함 | 기존 방 사용 | 기존 문의서는 유지되고 뒤에 BOOKING_CARD 추가 |
| 입주 신청으로 방이 먼저 생긴 뒤 문의 API 호출 | `200`, `created=false` | 뒤늦게 문의서를 추가하지 않음 |
| 두 요청이 거의 동시에 처음 문의함 | 한 요청만 신규 생성 | DB에는 문의서 한 장만 저장 |
| 과거에 방을 삭제한 사용자가 다시 문의함 | 기존 roomId 반환 | 삭제 전 이력은 해당 사용자에게 다시 보이지 않으며 문의서도 중복 생성하지 않음 |

## 11. 주요 오류 처리

| HTTP 상태·code | 의미 | 프론트 처리 예시 |
| --- | --- | --- |
| `401 UNAUTHENTICATED` | 로그인 정보가 없거나 access token 만료 | 로그인 갱신 또는 로그인 화면 이동 |
| `403 FORBIDDEN` | 임차인이 아닌 사용자가 문의함 | 문의하기 버튼 노출 정책 확인 후 안내 |
| `403 CHAT_UNAVAILABLE` | 상대와 차단 관계여서 채팅 불가 | 채팅을 시작할 수 없다는 안내 |
| `404 LISTING_NOT_FOUND` | 매물이 없거나 현재 공개 상태가 아님 | 매물 목록으로 이동하거나 새로고침 안내 |
| `422 CHAT_SELF_INQUIRY_NOT_ALLOWED` | 본인 소유 매물에 문의함 | 본인 매물에는 문의할 수 없다는 안내 |

프론트 분기는 현지화에 따라 달라질 수 있는 `error.message`가 아니라 안정적인 `error.code`를 기준으로 한다.

## 12. 구현 체크리스트

- [ ] 문의하기 요청에는 `listingId`만 URL로 전달하고 body를 보내지 않는다.
- [ ] `created=true`와 `created=false` 모두 응답의 `chatRoomId`로 같은 채팅방 화면을 연다.
- [ ] 새 방 진입 직후 메시지 이력 API를 호출한다.
- [ ] `type=INQUIRY_CARD`를 TEXT나 BOOKING_CARD와 다른 문의서 UI로 표시한다.
- [ ] `thumbnailUrl=null` fallback을 준비한다.
- [ ] city·district·listingType code를 현재 `ko/en` label로 표시한다.
- [ ] 월세 숫자에 KRW 형식을 적용하고 최소·최대 값이 같을 때 한 금액만 표시한다.
- [ ] View Detail은 `inquiryCard.listingId`로 기존 매물 상세 화면을 연다.
- [ ] 카드를 STOMP SEND하지 않고 ACK를 기다리지 않는다.
- [ ] REST와 STOMP 결과는 `messageId`로 중복 제거한다.
- [ ] 문의서의 고정 label과 code를 Google 번역 결과로 처리하지 않는다.
