# 시퀀스 다이어그램

이 파일의 그림은 흐름을 쉽게 이해하기 위한 설명 자료다. 세부 요청·응답은 [API 계약](02-api-contracts.md), STOMP는 [실시간 계약](03-websocket-stomp.md), 삭제·신고 내부 상태는 [데이터 모델](06-data-model-and-retention.md)이 우선한다.

## 1. 문의와 신청이 같은 방을 사용하는 흐름

```mermaid
sequenceDiagram
    actor U as 사용자
    participant APP as 앱
    participant BOOK as Booking API
    participant PUB as Event Publication Registry
    participant CHAT as Chat API
    participant LIST as Listing API
    participant DB as MySQL

    alt 문의하기
        U->>APP: 문의하기 선택
        APP->>CHAT: POST /api/v1/listings/{listingId}/inquiries
        CHAT->>CHAT: INTERACTIVE_REOPEN
    else 매물 화면에서 신청하기
        U->>APP: 입주일·기간 선택
        APP->>BOOK: POST /api/v1/listings/{listingId}/bookings
        BOOK->>DB: REQUESTED 신청 저장
        BOOK->>PUB: BookingCreatedEvent publication 저장
        DB-->>BOOK: COMMIT
        BOOK-->>APP: 201 신청 완료
        APP->>CHAT: POST /api/v1/listings/{listingId}/inquiries
        CHAT->>CHAT: INTERACTIVE_REOPEN
        PUB-->>CHAT: BookingCreatedEvent 재처리 가능 전달
        CHAT->>DB: CREATE_ONLY 방 보상
        Note over CHAT,DB: 기존 삭제방의 가시성은 바꾸지 않음
    else 채팅 안 신청 버튼
        U->>APP: 신청하기 선택
        APP->>BOOK: 기존 Booking API 호출
        BOOK->>DB: 기존 예약 로직으로 저장
        BOOK-->>APP: 신청 결과
        Note over APP,CHAT: 이미 방 안이므로 새 방·특수 메시지 없음
    end

    opt 사용자 요청으로 방 보장
        CHAT->>LIST: 공개 매물·landlordId 조회
        LIST-->>CHAT: 매물 표시 사본·landlordId
        CHAT->>DB: 비즈니스 키로 원자적 ensure
        alt 기존 방
            DB-->>CHAT: 기존 roomId
            CHAT-->>APP: 200 created=false
        else 신규 방
            CHAT->>DB: room + tenant/landlord member 저장
            DB-->>CHAT: 새 roomId
            CHAT-->>APP: 201 created=true
        end
    end
```

## 2. WebSocket 연결과 구독 준비

```mermaid
sequenceDiagram
    participant APP as 앱
    participant WS as STOMP Interceptor
    participant CHAT as Chat Service
    participant DB as MySQL

    APP->>WS: WebSocket handshake /ws/chat
    WS-->>APP: transport 연결
    APP->>WS: STOMP CONNECT + Bearer token
    WS->>WS: JwtTokenService 검증<br/>ROLE_USER Principal 설정
    WS-->>APP: CONNECTED

    APP->>WS: SUBSCRIBE 개인 queue들<br/>control·ack·error·room·translation
    APP->>WS: SEND /app/chat/control/ping
    WS-->>APP: pong
    APP->>WS: SUBSCRIBE /topic/chat-rooms/{roomId}
    WS->>DB: 방 참여자 검사
    WS->>WS: SessionSubscribeEvent 확인
    WS->>DB: room lastMessageId 조회
    WS-->>APP: SUBSCRIPTION_READY(highWatermark)
    APP->>CHAT: GET messages?afterMessageId=syncCheckpoint
    CHAT-->>APP: highWatermark까지 DB catch-up
```

## 3. 메시지 저장과 자동 번역

```mermaid
sequenceDiagram
    actor S as 발신자
    participant APP as 발신자 앱
    participant WS as STOMP Handler
    participant CHAT as Chat Service
    participant USER as User API
    participant DB as MySQL
    participant BR as Simple Broker
    participant WORKER as Translation Worker
    participant GOOGLE as Google Translation
    participant OTHER as 수신자 앱

    S->>APP: 텍스트 전송
    APP->>APP: clientMessageId 생성<br/>임시 말풍선 표시
    APP->>WS: SEND /app/chat-rooms/{roomId}/messages
    WS->>CHAT: Principal + roomId + payload
    CHAT->>DB: 방·참여자 재검증
    CHAT->>USER: 양방향 차단·수신자 users.lang 확인
    USER-->>CHAT: 차단 없음 + target language

    alt 신규 clientMessageId
        CHAT->>DB: 원문 + 번역 작업 INSERT<br/>방 최신값·재노출 상태 UPDATE
        DB-->>CHAT: COMMIT, messageId, sentAt
        CHAT->>BR: 원문 저장 완료 publish
        BR-->>APP: room topic 원문
        BR-->>OTHER: room topic 원문
        CHAT-->>APP: application send result
        APP->>APP: 임시 말풍선 확정

        WORKER->>DB: 커밋된 번역 작업 조회
        WORKER->>GOOGLE: 원문 + target language
        alt 번역 성공
            GOOGLE-->>WORKER: 감지 언어 + 번역본
            WORKER->>DB: 번역본 저장
            WORKER->>BR: 수신자 개인 번역 결과
            BR-->>OTHER: 번역 결과
            OTHER->>OTHER: 번역본 우선 표시<br/>원문 보기 제공
        else 같은 언어 또는 번역 실패
            WORKER->>DB: 최종 상태 저장
            WORKER->>BR: NOT_REQUIRED 또는 FAILED
            BR-->>OTHER: 최종 결과
            Note over OTHER: 원문 유지 여부와 대기 표시는 앱이 결정
        end
    else 동일 clientMessageId 재시도
        DB-->>CHAT: 기존 메시지
        CHAT-->>APP: duplicate=true 저장 결과
        Note over CHAT,GOOGLE: 두 번째 INSERT·broadcast·번역 요청 없음
    end
```

## 4. 삭제·재진입·Undo·물리 파기

```mermaid
sequenceDiagram
    actor U as 삭제 사용자
    participant APP as 앱
    participant CHAT as Chat Service
    participant DB as MySQL
    participant JOB as Delete/Purge Job
    actor O as 상대방

    U->>APP: 채팅방 삭제
    APP->>CHAT: DELETE /api/v1/chat-rooms/{roomId}
    CHAT->>DB: room → member lock<br/>숨김·pending 경계·내부 기한 저장
    DB-->>CHAT: current undoToken
    CHAT-->>APP: 200 undoToken
    Note over O,DB: 상대 member와 상대 이력은 변경 없음

    alt 같은 DELETE 재시도
        APP->>CHAT: DELETE 재호출
        CHAT-->>APP: 같은 undoToken
        Note over CHAT,DB: 내부 기한 연장 없음
    else 현재 화면에서 즉시 Undo
        APP->>CHAT: POST /api/v1/chat-rooms/{roomId}/restore<br/>undoToken
        CHAT->>DB: token·내부 기한 검사<br/>삭제 주기 복원
        CHAT-->>APP: 200
    else 실제 새 대화로 재진입
        CHAT->>DB: 신규 메시지 저장 또는 직접 문의<br/>방만 재노출
        CHAT-->>APP: 확정 경계 이후 메시지만 표시
    end

    JOB->>DB: 만료 후보 조회
    JOB->>DB: room → 두 member 순서로 lock
    JOB->>DB: pending 경계를 복구 불가능 경계로 확정
    JOB->>DB: min(두 사용자 경계)·hold 확인
    alt 양쪽이 공통으로 버린 범위
        JOB->>DB: 안전한 message prefix 파기
    else 상대가 계속 보유
        Note over JOB,DB: 공유 메시지 유지
    end
```

## 5. 차단과 차단 후 전송

```mermaid
sequenceDiagram
    actor A as 차단 사용자
    participant APP as 앱
    participant CHAT as Chat Service
    participant USER as UserBlock Service
    participant DB as MySQL
    participant BR as Simple Broker
    actor B as 상대방

    A->>APP: 상대 차단
    APP->>CHAT: POST /api/v1/chat-rooms/{roomId}/block
    CHAT->>DB: 방 참여자 확인<br/>상대 userId 도출
    CHAT->>USER: block(A, B)
    USER->>DB: user_blocks 멱등 INSERT
    CHAT-->>APP: 204
    Note over A,B: 차단 이전 이력은 유지

    B->>CHAT: STOMP SEND 새 메시지
    CHAT->>USER: isBlockedBetween(A, B)
    USER-->>CHAT: true
    CHAT-->>B: CHAT_UNAVAILABLE
    Note over CHAT,DB: 메시지 INSERT 없음
    Note over CHAT,BR: broker publish 없음
```

## 6. 채팅방 신고·운영 처리·만료

```mermaid
sequenceDiagram
    actor U as 신고자
    participant APP as 앱
    participant REPORT as Report Service
    participant USER as User Language API
    participant CHAT as Chat Query API
    participant DB as MySQL
    actor ADMIN as 운영자
    participant JOB as Retention Job

    APP->>REPORT: GET /api/v1/reports/reasons
    REPORT->>USER: getLanguage(reporterId)
    USER-->>REPORT: ko / en / ja
    REPORT->>DB: 언어별 고정 label 조회
    REPORT-->>APP: code + localized label

    U->>APP: 사유 한 개 선택
    APP->>REPORT: POST /api/v1/chat-rooms/{roomId}/reports
    REPORT->>CHAT: 방·참여자·상대·증거 범위 요청
    CHAT->>DB: 같은 outer transaction에서 room lock
    CHAT-->>REPORT: 방 정보 사본 + 최근 메시지
    REPORT->>DB: report + evidence v1 + status history<br/>source room hold 저장 후 COMMIT
    REPORT-->>APP: 201 RECEIVED

    ADMIN->>REPORT: PATCH status=UNDER_REVIEW
    REPORT->>DB: 상태·상태 이력 저장
    ADMIN->>REPORT: PATCH 최종 상태
    REPORT->>DB: evidence v2 append<br/>resolvedAt·expiry·hold 해제 한 번에 COMMIT

    JOB->>DB: 보관기간이 끝난 최종 처리 신고 조회
    alt active hold 없음
        JOB->>DB: evidence·history·report 파기
    else active hold 있음
        Note over JOB,DB: 파기 보류 후 재검토
    end
```

## 7. 그림에서 생략한 분기

그림을 읽기 쉽게 하기 위해 다음 상세 분기는 표시하지 않았다.

- `BOOKING_ALREADY_EXISTS` 뒤 기존 방 진입
- 열린 신고 재시도의 `200 OK`
- 직전 신고 뒤 새 메시지가 없는 재신고 거부
- `GET /api/v1/reports/{reportId}`의 신고자 소유권 검사
- 오류별 STOMP user queue payload
- 삭제 token 만료·stale 요청 오류

이 분기는 [API 계약](02-api-contracts.md)과 [보안·동시성](07-security-and-concurrency.md)을 따른다.
