# US-4-2 — 매물 문의(채팅방 생성/조회) 및 매물 카드 고정

> 모듈: 신청 · 문의 (인앱 채팅) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/04-booking-inquiry-chat.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant CHAT as chat 모듈
    participant LIST as listing 모듈
    participant MDB as MongoDB
    participant XDB as 저장소(추후 결정)

    U->>C: 매물 상세에서 문의하기
    C->>SEC: POST /api/v1/listings/{listingId}/inquiries<br/>Authorization: Bearer <token> (본문 없음)
    Note over SEC: JWT 검증 (서명·만료·클레임)

    alt 토큰 없음/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED (만료 시 TOKEN_EXPIRED)
        C-->>U: 로그인 필요/세션 만료 안내
    else 토큰 유효
        SEC->>CHAT: 인증된 요청 전달 (userId)
        CHAT->>LIST: 매물 조회·공개 검증(listingId)
        LIST->>MDB: ObjectId listingId로 매물 조회
        MDB-->>LIST: 건물 매물·대표 roomOffer 또는 없음
        LIST-->>CHAT: 매물 정보(임대인, title, 대표 monthlyRent, thumbnailUrl) 또는 없음

        alt 매물 없음/비공개
            CHAT-->>C: 404 LISTING_NOT_FOUND
            C-->>U: 매물을 찾을 수 없음 안내
        else 본인 소유 매물
            CHAT-->>C: 422 CHAT_SELF_INQUIRY_NOT_ALLOWED
            C-->>U: 본인 매물 문의 불가 안내
        else 채팅방 신규 생성
            Note over CHAT: (매물,세입자,임대인) 키로 channel upsert<br/>매물 정보 카드 LISTING_CARD 고정(pinned=true) 추가
            CHAT->>XDB: 채팅방 조회/생성((매물,세입자,임대인) 키)<br/>+ LISTING_CARD 메시지 저장(pinned=true)
            XDB-->>CHAT: roomId, listingCard messageId
            CHAT-->>C: 201 Created<br/>Location /api/v1/chat-rooms/{roomId}<br/>data: chatRoomId, category=LANDLORD, created=true,<br/>listing, counterpart, listingCard(pinned=true)
            C-->>U: 새 채팅방·매물 카드 표시
        else 기존 채팅방 존재 (멱등)
            CHAT->>XDB: 채팅방 조회((매물,세입자,임대인) 키)
            XDB-->>CHAT: 기존 roomId, 기존 listingCard
            CHAT-->>C: 200 OK<br/>data: chatRoomId, created=false,<br/>기존 listingCard 그대로
            C-->>U: 기존 채팅방으로 이동
        end
    end
```

## 흐름 요약

- 보호 엔드포인트이므로 **공통 보안 필터(SEC)** 가 컨트롤러 앞단에서 `Authorization: Bearer <token>`의 JWT(서명·만료·클레임)를 검증한 뒤 인증된 요청(userId)을 **chat 모듈**로 전달한다. 토큰이 없거나 만료/위조면 필터가 `401 UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)로 막는다.
- 세입자가 `POST /api/v1/listings/{listingId}/inquiries`(빈 본문)를 호출하면 **chat 모듈**이 **listing 모듈**에 매물 조회·공개 검증을 동기 호출(`->>`)해 임대인·매물 대표 정보를 받은 뒤 (매물, 세입자, 임대인) 키로 `category=LANDLORD` 채팅방을 **저장소(추후 결정)**에 upsert(조회/생성)한다. `listingId`는 MongoDB ObjectId 문자열이며, 매물 조회는 listing 모듈이 MongoDB에서, 채팅방·카드 저장은 chat 모듈이 저장소(추후 결정)에서 각자 자기 데이터를 읽고 쓴다.
- 신규 생성 시에만 chat 모듈이 매물 정보 카드(listing 참조)를 `LISTING_CARD`·`pinned=true`로 저장소(추후 결정)에 추가하고 `201 Created` + `created=true`를, 기존 방이면 저장소(추후 결정)에서 방·카드를 조회해 `200 OK` + `created=false`로 동일 방을 반환(멱등)한다.
- 본인 소유 매물 문의는 `422 CHAT_SELF_INQUIRY_NOT_ALLOWED`, 없는 매물은 `404 LISTING_NOT_FOUND`로 거절되며, 거절 경로에서는 채팅방 도메인 상태를 저장소(추후 결정)에 쓰지 않는다. 이 비즈니스 판단은 필터가 아니라 chat 모듈의 몫이다.
