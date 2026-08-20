# 시퀀스 다이어그램

이 파일은 **다이어그램 하나에 하나의 시나리오만 표현한다.** 서로 다른 사용자 행동이나 서로 다른 시간대의 백그라운드 작업을 한 그림의 `alt`로 섞지 않는다.

- 같은 요청에서 기존 데이터가 있는 경우와 없는 경우처럼 결과만 달라지는 분기는 한 그림에 표시할 수 있다.
- 세부 요청·응답은 [API 계약](02-api-contracts.md), STOMP는 [실시간 계약](03-websocket-stomp.md), 삭제·신고 내부 상태는 [데이터 모델](06-data-model-and-retention.md)이 우선한다.

## 1. 방 진입과 신청

### 매물에서 문의하기

```mermaid
sequenceDiagram
    actor U as 세입자
    participant APP as 앱
    participant CHAT as 채팅방 API
    participant LIST as 매물 API
    participant DB as MySQL

    U->>APP: 문의하기 누름
    APP->>CHAT: 이 매물의 채팅방 열기
    CHAT->>LIST: 매물과 임대인 확인
    LIST-->>CHAT: 매물·임대인 정보
    CHAT->>DB: 같은 매물·세입자·임대인의 방 조회

    alt 기존 방이 있음
        DB-->>CHAT: 기존 roomId
    else 기존 방이 없음
        CHAT->>DB: 방과 두 참여자 생성
        DB-->>CHAT: 새 roomId
    end

    CHAT-->>APP: roomId
    APP-->>U: 채팅방 화면 열기
```

**쉽게 설명하면:** 문의하기를 누르면 서버는 `매물·세입자·임대인` 조합으로 기존 방을 찾는다. 이미 있으면 같은 `roomId`를 반환하고, 없을 때만 새 방을 만든다. `roomId`는 이후 방 정보·메시지 조회와 실시간 구독에 사용하는 채팅방 번호다.

**결과:** 같은 매물·세입자·임대인 조합은 항상 같은 채팅방을 사용한다.

### 매물에서 신청한 뒤 채팅방 열기

```mermaid
sequenceDiagram
    actor U as 세입자
    participant APP as 앱
    participant BOOK as 기존 신청 API
    participant DB as MySQL
    participant CHAT as 채팅방 API

    U->>APP: 입주일·기간 선택 후 신청
    APP->>BOOK: 신청 생성 요청
    BOOK->>DB: 신청과 신청 완료 이벤트 저장
    DB-->>BOOK: 저장 완료
    BOOK-->>APP: 신청 완료
    APP->>CHAT: 이 매물의 채팅방 열기
    CHAT->>DB: 같은 매물·세입자·임대인의 방 조회 또는 생성
    DB-->>CHAT: 기존 또는 새 roomId
    CHAT-->>APP: roomId
    APP->>CHAT: 채팅방 메시지 이력 조회
    CHAT-->>APP: 현재 저장 완료된 TEXT·BOOKING_CARD
    APP-->>U: 채팅방 화면 열기
```

**쉽게 설명하면:** 이 그림은 앱이 해야 하는 HTTP 호출만 보여 준다. 먼저 기존 신청 API로 신청을 저장한 다음 문의하기 API로 채팅방 번호를 받는다. 이미 문의했던 방이 있으면 같은 `roomId`를 그대로 사용한다. 마지막으로 메시지 이력 API를 호출하면 현재 저장이 끝난 일반 대화와 신청 카드를 함께 받는다.

신청 카드 자체는 앱이 보내지 않는다. 서버가 자동 저장하는 과정은 아래 **서버가 신청 후 채팅방과 신청 카드를 자동 보완하기** 그림에 따로 표시한다. 비동기 처리가 아직 끝나지 않아 첫 이력 조회에 카드가 없다면 짧게 기다린 뒤 이력 API를 한 번 다시 조회할 수 있다.

**결과:** 신청은 기존 Booking 기능이 처리하고, 신청 완료 후 문의하기와 같은 채팅방으로 이동하며 그 안에 신청 카드가 표시된다.

### 채팅방 안에서 신청하기

```mermaid
sequenceDiagram
    actor U as 세입자
    participant APP as 앱
    participant BOOK as 기존 신청 API
    participant DB as MySQL

    U->>APP: 현재 채팅방에서 신청하기 누름
    APP->>U: 기존 신청 화면 표시
    U->>APP: 입주일·기간 선택 후 신청
    APP->>BOOK: 신청 생성 요청
    BOOK->>DB: 신청과 신청 완료 이벤트 저장
    DB-->>BOOK: 저장 완료
    BOOK-->>APP: 신청 결과
    APP-->>U: 현재 채팅방 화면 유지
```

**쉽게 설명하면:** 이 그림은 채팅방 안에서 신청하는 사용자의 HTTP 흐름만 보여 준다. 이미 채팅방을 열고 있으므로 앱은 신청 API만 호출하고 현재 화면을 유지한다. 서버가 이벤트를 처리해 같은 방에 카드를 저장하는 과정은 다음 백그라운드 그림과 같다.

**결과:** 새 채팅방은 만들지 않고 현재 채팅방에 `BOOKING_CARD`만 추가한다.

### 삭제 후 같은 매물에서 새 대화 시작하기

```mermaid
sequenceDiagram
    actor U as 방을 삭제했던 사용자
    participant APP as 앱
    participant CHAT as 채팅방 API
    participant DB as MySQL
    actor O as 상대방

    Note over U,DB: 사용자는 이전에 이 방을 삭제해<br/>자신의 화면에서만 숨긴 상태
    Note over O,DB: 상대방의 방과 대화는 그대로 유지

    U->>APP: 같은 매물에서 문의하기 누름
    APP->>CHAT: 이 매물의 채팅방 열기
    CHAT->>DB: 같은 매물·세입자·임대인의 방 조회
    DB-->>CHAT: 숨겨진 기존 roomId
    CHAT->>DB: 내 화면에 채팅방을 다시 표시<br/>기존 메시지 숨김 경계는 유지
    CHAT-->>APP: 기존 roomId
    APP->>CHAT: 방 정보와 메시지 조회
    CHAT-->>APP: 숨김 경계 이후 메시지만 반환
    APP-->>U: 같은 채팅방 화면 열기

    Note over U,O: 삭제 취소가 아니므로<br/>지웠던 과거 대화와 상대방 화면은 변하지 않음
```

**쉽게 설명하면:** 같은 매물에서 다시 문의하면 서버는 새 채팅방을 만들지 않고 기존 `roomId`를 반환한다. 이때 채팅방 목록의 숨김 상태만 해제하고 삭제할 때 저장한 메시지 숨김 경계는 변경하지 않는다. 따라서 같은 채팅방 화면은 다시 열리지만 삭제했던 과거 메시지는 볼 수 없다.

**결과:** 새 대화를 시작하기 위해 같은 채팅방을 다시 표시하지만 삭제한 과거 이력은 복원하지 않는다.

### 서버가 신청 후 채팅방과 신청 카드를 자동 보완하기

```mermaid
sequenceDiagram
    participant BOOK as Booking Service
    participant DB as MySQL
    participant EVENTS as 이벤트 저장소
    participant CHAT as Chat Application

    BOOK->>DB: 신청 저장
    BOOK->>EVENTS: 같은 트랜잭션에 신청 시점 카드 사본 저장
    DB-->>BOOK: COMMIT
    EVENTS-->>CHAT: 저장된 신청 완료 이벤트 전달
    CHAT->>DB: 같은 매물·세입자·임대인의 방 존재 여부 확인

    alt 채팅방이 없음
        CHAT->>DB: 방과 두 참여자 생성
    else 채팅방이 이미 있음
        Note over CHAT,DB: 기존 roomId 사용
    end

    CHAT->>DB: 같은 roomId에 bookingId 카드 저장 시도

    alt 같은 bookingId 카드가 없음
        CHAT->>DB: BOOKING_CARD와 마지막 메시지 상태 함께 저장
    else 카드가 이미 있음
        Note over CHAT,DB: 기존 카드 반환, 다른 상태 변경 없음
    end
```

**쉽게 설명하면:** 이 그림은 사용자나 앱이 호출하지 않는 백엔드 자동 작업이다. 예약과 함께 `BookingCreatedEvent`가 MySQL에 기록되고, Chat Application이 이를 받아 같은 방을 보장한 뒤 카드 한 장을 저장한다. 카드에 필요한 대표 이미지·매물·신청자·금액은 이벤트 안에 신청 시점 사본으로 이미 들어 있으므로 처리 중 Booking API를 다시 호출하지 않는다.

listener가 실패하면 이벤트 행은 미완료로 남고 서버 재기동 때 다시 전달된다. 같은 이벤트가 다시 와도 `(chatRoomId, bookingId)` 중복 방지로 카드가 두 장 생기지 않는다. 현재 단계의 프론트 정본은 메시지 이력 API이며, STOMP 저장 완료 전달은 다음 실시간 단계에서 연결한다.

**결과:** 신청 뒤 채팅방과 신청 카드가 누락되지 않으며, 같은 이벤트를 다시 처리해도 방이나 카드가 중복 생성되지 않는다.

## 2. WebSocket 연결과 메시지

### WebSocket에 접속하고 JWT로 인증하기

```mermaid
sequenceDiagram
    participant APP as 앱
    participant WS as STOMP 인증 처리

    APP->>WS: WebSocket 연결 요청 /ws/chat
    WS-->>APP: WebSocket 연결 완료
    APP->>WS: STOMP CONNECT + Bearer access token
    WS->>WS: 기존 JwtTokenService로 검증<br/>사용자 신원을 세션에 저장
    WS-->>APP: STOMP CONNECTED
```

**쉽게 설명하면:** 앱은 WebSocket에 연결한 다음 STOMP `CONNECT` 헤더에 JWT를 넣는다. STOMP 메시지는 일반 REST 인증 필터를 지나지 않기 때문에 `ChannelInterceptor`가 이를 받아 기존 `JwtTokenService`로 검증한다. 검증된 `userId`는 연결 세션에 저장되고 이후 전송·구독의 사용자 신원으로 사용된다.

**결과:** 이후 STOMP 요청은 연결된 세션의 검증된 userId를 사용한다.

### 채팅방 연결 시 앱이 누락 메시지를 자동 보충하기

```mermaid
sequenceDiagram
    participant APP as 앱
    participant WS as STOMP 구독 처리
    participant CHAT as Chat API
    participant DB as MySQL

    APP->>WS: 개인 수신 경로 구독
    APP->>WS: 채팅방 실시간 경로 구독
    WS->>DB: 사용자가 방 참여자인지 확인
    DB-->>WS: 참여자 확인 완료
    WS->>DB: 구독 시점의 마지막 messageId 조회
    WS-->>APP: 실시간 수신 준비 완료 + 기준 messageId
    APP->>CHAT: 마지막 동기화 이후 놓친 메시지 조회
    CHAT->>DB: MySQL 메시지 조회
    DB-->>CHAT: 누락 메시지
    CHAT-->>APP: 누락 메시지 반환
```

**쉽게 설명하면:** 구독은 특정 채팅방의 새 메시지를 STOMP로 실시간 수신하도록 등록하는 것이다. 앱은 구독 준비가 끝난 직후와 재연결 직후에만 REST API를 자동 호출해 마지막 `messageId` 이후의 누락분을 MySQL에서 한 번 보충한다. 사용자가 별도 버튼을 누를 필요는 없으며 계속 반복 조회하는 polling 방식도 아니다. 이것은 메시지 누락 방지 기능이며 읽음 여부를 계산하는 기능은 아니다.

**결과:** 마지막 동기화 이후부터 구독 기준 시점까지 빠진 메시지도 MySQL에서 보충한다.

### 새 메시지를 저장하고 실시간 전달하기

```mermaid
sequenceDiagram
    actor S as 발신자
    participant APP as 발신자 앱
    participant CHAT as 채팅 서버
    participant DB as MySQL
    participant BR as Simple Broker
    participant OTHER as 수신자 앱

    S->>APP: 텍스트 전송
    APP->>APP: clientMessageId 생성
    APP->>CHAT: STOMP로 원문 전송
    CHAT->>CHAT: JWT 사용자·방 참여·차단·3,000자 검사
    CHAT->>DB: 원문과 사용자별 번역 대기 작업 저장
    DB-->>CHAT: COMMIT + messageId
    CHAT->>BR: 저장 완료된 원문 발행
    BR-->>APP: 원문 메시지
    BR-->>OTHER: 원문 메시지
    CHAT-->>APP: 저장 성공 결과
```

**쉽게 설명하면:** 프런트엔드가 UUID 형태의 `clientMessageId`를 먼저 만들고 서버는 이를 재전송 중복을 판별하는 값으로 사용한다. 서버는 원문, `번역 대기(PENDING)` 행, 방의 마지막 메시지 정보를 함께 저장한다. 필요하면 수신자가 숨긴 방을 다시 표시하는 상태도 같은 트랜잭션에서 바꾼다. `PENDING`은 원문 메시지가 아니라 번역만 아직 처리되지 않았다는 뜻이며, 모든 DB 저장이 성공해 `messageId`가 만들어진 뒤에만 Broker가 메시지를 전달한다.

**결과:** 원문 저장과 전달은 Google 번역 완료를 기다리지 않는다.

### 저장된 메시지를 자동 번역하기

```mermaid
sequenceDiagram
    participant WORKER as Translation Worker
    participant DB as MySQL
    participant GOOGLE as Google Translation
    participant BR as Simple Broker
    participant APP as 수신자 앱

    Note over WORKER,DB: 원문과 번역 대기 작업은 이미 저장 완료
    WORKER->>DB: 번역 대기 작업 조회
    DB-->>WORKER: 원문·수신 언어
    WORKER->>GOOGLE: 원문을 수신 언어로 번역 요청
    GOOGLE-->>WORKER: 번역 결과
    WORKER->>DB: 번역본 저장
    WORKER->>BR: 수신자 전용 번역 결과 발행
    BR-->>APP: 번역 결과
    APP->>APP: 번역본 우선 표시<br/>원문 보기 제공
```

**쉽게 설명하면:** `Translation Worker`는 직접 번역하는 프로그램이 아니라 번역 작업을 관리하는 Spring 백그라운드 컴포넌트다. DB에서 `PENDING` 작업을 찾아 Google API에 원문을 보내고, Google이 만든 번역본을 DB에 저장한 뒤 수신자에게 전달한다. 원문은 수정하지 않는다.

**결과:** 저장된 원문은 바꾸지 않고 수신자용 번역본만 추가한다.

### 자동 번역이 최종 실패한 경우

```mermaid
sequenceDiagram
    participant WORKER as Translation Worker
    participant DB as MySQL
    participant GOOGLE as Google Translation

    WORKER->>DB: 번역 대기 작업 조회
    DB-->>WORKER: 원문·수신 언어
    loop 최초 요청 포함 최대 5회
        WORKER->>DB: 호출 직전 attempt_count 증가
        WORKER->>GOOGLE: 번역 요청
        GOOGLE-->>WORKER: 재시도 가능한 오류 또는 시간 초과
        Note over WORKER,GOOGLE: 예: 0.5초 → 1초 → 2초 → 4초의 짧은 간격
    end
    WORKER->>DB: 최종 실패 상태 저장
```

**쉽게 설명하면:** Google API의 일시 오류나 시간 초과가 발생하면 Worker가 같은 작업 안에서 아주 짧게 기다렸다가 최초 요청을 포함해 최대 5번 호출한다. 다음 시도 일정을 DB에 예약하는 방식은 아니다. 서버가 중간에 꺼지면 저장된 시도 횟수를 보고 남은 횟수만 이어서 처리한다. 계속 실패하면 번역 작업만 `FAILED`가 되고, 원문 메시지는 이미 정상 저장된 데이터이므로 사라지지 않는다.

**결과:** 번역에 실패해도 이미 저장·전달된 원문 메시지는 그대로 유지한다.

### 네트워크 오류로 같은 메시지를 다시 보낸 경우

```mermaid
sequenceDiagram
    participant APP as 발신자 앱
    participant CHAT as 채팅 서버
    participant DB as MySQL

    Note over APP,CHAT: 앱이 이전 전송 결과를 받지 못한 상태
    APP->>CHAT: 같은 clientMessageId로 다시 전송
    CHAT->>DB: 같은 방·발신자·clientMessageId 확인
    DB-->>CHAT: 이미 저장된 messageId
    CHAT-->>APP: 기존 저장 성공 결과
    Note over CHAT,DB: 새 메시지·실시간 재발행·번역 작업 없음
```

**쉽게 설명하면:** 앱이 응답을 받지 못하면 같은 논리적 메시지를 동일한 `clientMessageId`로 다시 보낸다. MySQL UNIQUE 제약으로 이미 저장된 메시지를 찾아 기존 `messageId`를 반환하므로 두 개로 저장되지 않는다. 새 메시지를 작성할 때는 새로운 `clientMessageId`를 만들어야 한다.

**결과:** 네트워크 재시도에도 메시지는 한 번만 저장된다.

## 3. 채팅방 삭제와 새 메시지

### 사용자가 채팅방을 삭제하기

```mermaid
sequenceDiagram
    actor U as 삭제 사용자
    participant APP as 앱
    participant CHAT as Chat API
    participant DB as MySQL
    actor O as 상대방

    U->>APP: 채팅방 삭제 선택
    APP->>CHAT: DELETE /api/v1/chat-rooms/{roomId}
    CHAT->>DB: 채팅방 참여자 확인과 현재 상태 잠금
    CHAT->>DB: 내 목록에서 채팅방 숨김<br/>현재 마지막 messageId를 숨김 경계로 저장
    DB-->>CHAT: COMMIT
    CHAT-->>APP: 204 No Content
    APP-->>U: 내 채팅방 목록에서 제거
    Note over U,DB: 저장된 숨김 경계는 사용자에게 복원되지 않음
    Note over O,DB: 상대방의 방과 대화는 변경 없음
    Note over APP,CHAT: 같은 숨김 상태에서 재요청해도<br/>새 삭제 기록을 만들지 않음
```

**쉽게 설명하면:** 삭제하면 요청한 사용자의 채팅방 목록에서만 채팅방을 숨기고, 삭제 시점까지의 메시지를 숨김 경계로 기록한다. 서버는 JSON 본문 없이 `204`를 반환하며 Undo나 복원 API는 제공하지 않는다. 상대방 화면은 그대로 유지되고 같은 요청을 반복해도 추가 변경 없이 성공한다.

**결과:** 사용자가 삭제한 과거 대화는 복원할 수 없지만 공유 메시지를 즉시 DB에서 물리 삭제하지는 않는다.

### 숨긴 방에 실제 새 메시지가 도착하기

```mermaid
sequenceDiagram
    actor S as 상대방
    participant CHAT as 채팅 서버
    participant DB as MySQL
    participant BR as Simple Broker
    participant APP as 방을 숨긴 사용자 앱

    S->>CHAT: 새 메시지 전송
    CHAT->>CHAT: 참여자·차단·본문 검사
    CHAT->>DB: 새 메시지 저장과 수신자 방 재표시를 함께 처리
    DB-->>CHAT: COMMIT
    CHAT->>BR: 새 메시지와 방 갱신 이벤트 발행
    BR-->>APP: 새 메시지 도착
    APP->>APP: 방을 목록에 다시 표시
```

**쉽게 설명하면:** 사용자가 방을 숨겼더라도 차단하지 않았다면 상대방은 새 메시지를 보낼 수 있다. 서버는 `새 메시지 저장`과 `수신자의 방을 다시 표시`하는 변경을 한 트랜잭션으로 함께 처리한다. 그래서 메시지만 저장되고 방은 계속 안 보이거나, 방만 나타나고 메시지가 없는 상태가 생기지 않는다. 방은 다시 나타나지만 사용자가 숨긴 과거 메시지는 복원되지 않는다.

**결과:** 실제 새 메시지가 생긴 경우에만 방이 다시 나타나며, 사용자가 숨긴 과거 범위는 복원하지 않는다.

3개월 만료와 실제 DB 물리 삭제 시퀀스는 현재 구현에서 제외하고 [후속 고도화 시퀀스](future/03-sequence-diagrams.md)로 분리한다.

## 4. 차단

### 채팅방에서 상대방 차단하기

```mermaid
sequenceDiagram
    actor U as 차단 사용자
    participant APP as 앱
    participant CHAT as Chat API
    participant BLOCK as 기존 차단 기능
    participant DB as MySQL

    U->>APP: 상대방 차단 선택
    APP->>CHAT: 현재 채팅방에서 차단 요청
    CHAT->>DB: 방 참여자 확인과 상대 userId 결정
    CHAT->>BLOCK: 현재 사용자와 상대방 차단 관계 저장 요청
    BLOCK->>DB: user_blocks 저장
    BLOCK-->>CHAT: 차단 완료
    CHAT-->>APP: 차단 완료
    Note over U,DB: 차단 이전 대화는 그대로 유지
```

**쉽게 설명하면:** 앱은 상대 사용자 번호를 직접 보내지 않고 현재 `roomId`만 사용한다. 서버가 방의 두 참여자를 확인해 차단할 상대를 결정하므로 클라이언트가 임의의 사용자를 차단 대상으로 꾸미는 것을 막을 수 있다.

**결과:** 클라이언트가 상대 userId를 지정하지 않고 서버가 방 정보로 상대를 결정한다.

### 차단된 관계에서 메시지 보내기

```mermaid
sequenceDiagram
    actor S as 발신 시도 사용자
    participant CHAT as 채팅 서버
    participant BLOCK as 기존 차단 기능
    participant DB as MySQL
    participant BR as Simple Broker

    S->>CHAT: 새 메시지 전송 시도
    CHAT->>BLOCK: 두 사용자 사이 차단 여부 확인
    BLOCK-->>CHAT: 차단 관계 있음
    CHAT-->>S: 메시지를 보낼 수 없음
    Note over CHAT,DB: 메시지 저장 없음
    Note over CHAT,BR: 실시간 전달 없음
```

**쉽게 설명하면:** 서버는 메시지를 저장하기 전에 두 사용자 사이의 차단 여부를 확인한다. 어느 한쪽이라도 차단했다면 원문, 번역 작업, 실시간 이벤트를 모두 만들지 않는다.

**결과:** 차단 이후 메시지는 저장하거나 상대방에게 전달하지 않는다.

## 5. 신고

### 채팅방 신고 접수하기

```mermaid
sequenceDiagram
    actor U as 신고자
    participant APP as 앱
    participant REPORT as Report API
    participant USER as User API
    participant CHAT as Chat Query API
    participant DB as MySQL

    U->>APP: 채팅방에서 신고하기 선택
    APP->>REPORT: 신고 사유 목록 요청
    REPORT->>USER: 신고자의 표시 언어 조회
    USER-->>REPORT: ko 또는 en
    REPORT->>DB: 해당 언어의 고정 신고 사유 조회
    DB-->>REPORT: 신고 사유 코드와 표시 문구
    REPORT-->>APP: 신고 사유 목록
    U->>APP: 사유 한 개 선택 후 신고
    APP->>REPORT: 채팅방 신고 접수 요청
    REPORT->>CHAT: 방 참여자·상대방·증거 원문 확인
    CHAT->>DB: 방 정보와 최근 원문 조회
    DB-->>CHAT: 신고에 필요한 정보
    CHAT-->>REPORT: 신고 대상과 증거 사본
    REPORT->>DB: 신고·최근 원문 증거 저장
    DB-->>REPORT: COMMIT
    REPORT-->>APP: 신고 접수 완료
```

**쉽게 설명하면:** 사용자는 고정 신고 사유 한 개와 `roomId`만 보낸다. 서버는 신고자가 실제 방 참여자인지 확인하고 방의 다른 사용자를 신고 대상으로 결정한다. 신고 대상은 특정 메시지 번호가 아니라 채팅방이며, 최근 원문과 기준 `messageId`는 서버가 직접 증거로 확보한다.

**결과:** 앱은 고정 사유만 보내며 신고자·상대방·방·접수 시각·증거는 서버가 결정한다.

운영자 처리와 신고 자료 만료 정리 시퀀스는 [후속 고도화 시퀀스](future/03-sequence-diagrams.md)로 분리한다.

## 6. 그림에서 생략한 상세

그림은 한 시나리오의 핵심 흐름만 보여 준다. 다음 세부 규칙은 정본 문서에서 확인한다.

- 동시 방 생성과 메시지 중복 저장을 막는 MySQL UNIQUE 처리
- STOMP destination, 개인 queue payload, heartbeat와 재연결 규칙
- 신고 중복 접수 조건
- 번역 재시도 횟수·간격과 Google API 오류 분류
- 트랜잭션 lock 순서

관리자 처리와 물리 삭제의 상세는 [후속 고도화 문서](future/README.md)를 따른다.
