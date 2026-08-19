# US-4-4 — 채팅 메시지 조회·전송·읽음 처리

> **대체됨:** 아래 그림은 이전 REST 전송·읽음 설계를 보존한 과거 자료다. 현재 구현에는 사용하지 않는다. 최신 시나리오는 [채팅 시퀀스 다이어그램](../../chat/05-sequence-diagrams.md), 정확한 계약은 [채팅 API](../../chat/02-api-contracts.md)와 [STOMP 계약](../../chat/03-websocket-stomp.md)을 따른다.

> **[후속·이연]** 이 다이어그램은 인앱 채팅(기존 F-03 chat 결합)으로 1차 MVP에서 후속으로 분리·이연되었다. 매물 예약(신청, US-4-1·US-4-2)과는 별개 기능이며, 파일명은 legacy이고 대응 US 번호는 **US-4-5(채팅 메시지 조회·전송·읽음 처리)** 로 재정합됨.
>
> 모듈: 신청 · 문의 (인앱 채팅) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/04-booking-inquiry-chat.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant CHAT as chat 모듈
    participant DB as 저장소(추후 결정)

    U->>C: 채팅방 진입
    C->>SEC: GET /api/v1/chat-rooms/{roomId}/messages?cursor=...&size=30<br/>Authorization: Bearer <token>
    Note over SEC: JWT 검증 (서명·만료·클레임)
    alt 토큰 없음/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED (만료 시 TOKEN_EXPIRED)
        C-->>U: 로그인 필요/세션 만료 안내
    else 토큰 유효
        SEC->>CHAT: 인증된 요청 전달 (userId)
        Note over CHAT: 방 존재 확인(없으면 404 CHAT_ROOM_NOT_FOUND)<br/>요청자가 방 참여자인지 검사(아니면 403 FORBIDDEN)
        CHAT->>DB: 메시지 조회(roomId, cursor, size 최신순)
        DB-->>CHAT: 메시지 페이지(고정 카드 포함), nextCursor
        CHAT-->>C: 200 OK<br/>data.content[] (최신순, 고정 카드 pinned=true 포함),<br/>nextCursor, hasNext
        C-->>U: 메시지·고정 카드 표시
    end

    U->>C: 텍스트 메시지 입력·전송
    C->>SEC: POST /api/v1/chat-rooms/{roomId}/messages<br/>Authorization: Bearer <token><br/>content=안녕하세요 내일 방문 가능할까요?
    Note over SEC: JWT 검증 (서명·만료·클레임)
    alt 토큰 없음/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED (만료 시 TOKEN_EXPIRED)
        C-->>U: 로그인 필요/세션 만료 안내
    else 토큰 유효
        SEC->>CHAT: 인증된 요청 전달 (userId)
        Note over CHAT: 참여자 검사(아니면 403 FORBIDDEN)<br/>TEXT로 저장(서버가 type 고정)<br/>lastMessageAt 갱신 + 상대 푸시 알림 이벤트 발행
        CHAT->>DB: 메시지 저장(type=TEXT) + lastMessageAt 갱신
        DB-->>CHAT: messageId, sentAt
        CHAT-->>C: 201 Created<br/>data: messageId, type=TEXT, mine=true, content, sentAt
        C-->>U: 전송한 메시지 표시
    end

    U->>C: 읽음 처리
    C->>SEC: POST /api/v1/chat-rooms/{roomId}/read<br/>Authorization: Bearer <token><br/>lastReadMessageId=70051
    Note over SEC: JWT 검증 (서명·만료·클레임)
    alt 토큰 없음/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED (만료 시 TOKEN_EXPIRED)
        C-->>U: 로그인 필요/세션 만료 안내
    else 토큰 유효
        SEC->>CHAT: 인증된 요청 전달 (userId)
        Note over CHAT: 참여자 검사(아니면 403 FORBIDDEN)<br/>읽음 위치 전진만(멱등)
        CHAT->>DB: 읽음 위치 갱신(userId, lastReadMessageId 전진만)
        DB-->>CHAT: 갱신된 읽음 위치
        CHAT-->>C: 200 OK<br/>data: chatRoomId, lastReadMessageId, unreadCount=0
        C-->>U: 안읽음 수 0으로 갱신
    end
```

## 흐름 요약

- 세 API 모두 보호 엔드포인트이므로 **공통 보안 필터(SEC)** 가 컨트롤러 앞단에서 각 요청의 `Authorization: Bearer <token>` JWT(서명·만료·클레임)를 검증한 뒤 인증된 요청(userId)을 **chat 모듈**로 전달한다. 토큰이 없거나 만료/위조면 필터가 `401 UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)로 막는다.
- `GET /api/v1/chat-rooms/{roomId}/messages`는 **chat 모듈**이 **저장소(추후 결정)**에서 최신순 커서 페이지네이션(`cursor`·`size`)으로 메시지를 조회·반환하며 고정 카드(`pinned=true`)도 목록에 포함된다.
- `POST /api/v1/chat-rooms/{roomId}/messages`는 `content`만 받아 chat 모듈이 `TEXT`로 **저장소(추후 결정)**에 저장(서버가 type 고정)하고 `lastMessageAt`을 갱신한 뒤 `201 Created` + `mine=true` 메시지를 반환하며 상대 푸시 이벤트를 발행한다.
- `POST /api/v1/chat-rooms/{roomId}/read`는 저장소(추후 결정)의 읽음 위치를 전진만 시키는 멱등 연산으로 chat 모듈이 `200 OK` + `unreadCount=0`을 반환하고, 모든 메시지 API는 방이 없으면 `404 CHAT_ROOM_NOT_FOUND`, 비참여자면 `403 FORBIDDEN`으로 거절한다(거절 경로에서는 저장소 접근 없음). 방 참여 여부(`403 FORBIDDEN`)는 토큰이 유효한 상태에서의 권한 판단이므로 필터가 아니라 chat 모듈이 처리한다.
