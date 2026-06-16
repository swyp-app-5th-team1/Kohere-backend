# US-4-3 — 채팅방 리스트 조회

> 모듈: 신청 · 문의 (인앱 채팅) · [유저 스토리](../../../requirements/user-stories.md) · [API 스펙](../../../api/specs/04-booking-inquiry-chat.md)

```mermaid
sequenceDiagram
    actor U as 사용자
    participant C as 앱(클라이언트)
    participant SEC as 공통 보안 필터
    participant CHAT as chat 모듈
    participant DB as 저장소(추후 결정)

    U->>C: 채팅 탭 진입
    C->>SEC: GET /api/v1/chat-rooms?category=LANDLORD&page=0&size=20<br/>Authorization: Bearer <token>
    Note over SEC: JWT 검증 (서명·만료·클레임)

    alt 토큰 없음/만료/위조
        SEC-->>C: 401 UNAUTHENTICATED (만료 시 TOKEN_EXPIRED)
        C-->>U: 로그인 필요/세션 만료 안내
    else 토큰 유효
        SEC->>CHAT: 인증된 요청 전달 (userId)
        Note over CHAT: 요청자가 참여한 방만 조회<br/>lastMessageAt 내림차순 정렬<br/>요청자 읽음 위치 기준 unreadCount 계산
        CHAT->>DB: 채팅방 리스트 조회<br/>(참여자=userId, category 필터, lastMessageAt desc, page/size)
        DB-->>CHAT: 채팅방 페이지 + lastMessage + unreadCount
        CHAT-->>C: 200 OK<br/>data.content[]: chatRoomId, category, listing(thumbnailUrl),<br/>counterpart, lastMessage(preview/sentAt), unreadCount, lastMessageAt<br/>data.page: number/size/totalElements/totalPages/hasNext
        C-->>U: 채팅방 목록 표시(안읽음 수·미리보기)
    end
```

## 흐름 요약

- 보호 엔드포인트이므로 **공통 보안 필터(SEC)** 가 컨트롤러 앞단에서 `Authorization: Bearer <token>`의 JWT(서명·만료·클레임)를 검증한 뒤 인증된 요청(userId)을 **chat 모듈**로 전달한다. 토큰이 없거나 만료/위조면 필터가 `401 UNAUTHENTICATED`(만료 시 `TOKEN_EXPIRED`)로 막는다.
- 세입자가 `GET /api/v1/chat-rooms`(선택 `category`·`page`·`size`)를 호출하면 **chat 모듈**이 **저장소(추후 결정)**에서 요청자가 참여한 방만 `lastMessageAt` 내림차순으로 오프셋 페이지네이션해 조회·반환한다.
- 각 항목에 `listing.thumbnailUrl`·`counterpart`·`lastMessage`·`unreadCount`가 담기고 `page` 메타(`number`/`size`/`totalElements`/`totalPages`/`hasNext`)를 포함해 chat 모듈이 `200 OK`로 응답한다. 매물 썸네일 등은 chat 모듈이 저장소(추후 결정)에 보유한 비정규화 뷰로 응답하므로 listing 모듈 조회가 필요 없다.
- 참여 중인 방이 없으면 빈 `content[]` + `totalElements=0`을 반환(에러 아님)하며, 타인 방은 목록에 절대 포함되지 않는다.
