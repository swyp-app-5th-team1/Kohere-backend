# 클라이언트 동작·테스트·구현 순서

## 1. 클라이언트 동작

### 1.1 채팅방 진입

1. REST로 방 헤더를 조회한다.
2. WebSocket에 CONNECT한다.
3. 저장 결과·오류·room event·translation·control user queue를 구독한다.
4. control ping/pong으로 개인 queue 준비를 확인한다.
5. room topic을 구독한다.
6. `SUBSCRIPTION_READY(roomId, highWatermark)`를 기다린다.
7. REST 최근 이력과 필요한 `afterMessageId` catch-up을 high-watermark까지 완료한다.
8. REST 결과와 구독 직후 topic 이벤트를 `messageId`, `clientMessageId`로 합친다.

SUBSCRIBE frame을 보낸 시점만으로 구독이 준비됐다고 가정하지 않는다. 같은 메시지가 REST와 topic 양쪽에서 도착하는 것은 정상이며 ID로 제거한다.

### 1.2 재연결

1. 지수 backoff와 jitter로 재연결한다.
2. 갱신한 access token으로 CONNECT한다.
3. control barrier와 room `SUBSCRIPTION_READY`를 다시 완료한다.
4. 마지막 연속 DB sync checkpoint를 `afterMessageId`로 조회한다.
5. high-watermark까지 모든 page를 읽는다.
6. 완료 후에만 checkpoint를 전진시킨다.
7. 그동안 받은 live topic 이벤트와 ID로 병합한다.

topic에서 받은 최대 messageId와 연속 DB sync checkpoint는 다르다. broker publish 실패로 중간 ID가 빠질 수 있으므로 checkpoint는 REST가 연속 구간을 확인한 뒤에만 전진한다.

### 1.3 임시 말풍선

- 전송 직전 프런트엔드가 `clientMessageId`를 생성한다.
- 저장 결과 전에는 `sending` 상태로 표시할 수 있다.
- topic 또는 저장 결과가 오면 같은 `clientMessageId`를 서버 `messageId`와 합친다.
- timeout이면 `failed` 또는 retry 상태로 바꾼다.
- retry는 같은 `clientMessageId`를 사용한다.
- 백엔드는 임시 말풍선 자체를 저장하지 않는다.

### 1.4 자동 번역 표시

- 내가 보낸 메시지는 원문을 기본 표시한다.
- 받은 메시지는 `translation`이 있으면 번역본을 기본 표시하고 `원문 보기`를 제공한다.
- 원문 보기와 번역문 보기 전환은 프런트엔드의 메시지별 화면 상태다.
- 번역본이 아직 없거나 실패했을 때 대기 문구·원문 표시 시점은 프런트엔드가 결정한다.
- 백엔드는 `번역 중` 같은 표시 문자열을 만들지 않는다.
- 개인 translation 이벤트가 원문보다 먼저 오면 `messageId`로 잠시 보관해 나중에 합친다.
- translation 이벤트를 놓치면 다음 REST 메시지 이력의 저장된 번역본으로 복구한다.
- 자동 번역 결과에는 Google 표시 요구사항에 맞는 출처 안내를 붙인다.

### 1.5 삭제 UX

- DELETE 성공 직후 현재 화면에만 Undo를 잠시 표시한다.
- `undoToken`은 앱 메모리에만 두고 영구 저장하지 않는다.
- 삭제방 목록, 복구 가능 방, 내부 만료일을 노출하지 않는다.
- 앱 종료 후 이전 삭제방을 찾아 복구하는 기능은 제공하지 않는다.

## 2. 테스트 계획

### 2.1 도메인·서비스

- 본인 매물 문의 거부
- 같은 `(listingId, tenantId, landlordId)`가 같은 방 반환
- 다른 listing은 다른 방 반환
- 방 참여자만 조회·전송·삭제·Undo·차단·신고 가능
- 공백-only, 2,999자, 3,000자, 3,001자 경계
- emoji·결합문자의 Unicode code point 경계
- 같은 clientMessageId 재시도는 DB 한 행
- 같은 clientMessageId와 다른 content는 충돌
- 차단 관계에서 DB INSERT와 broker publish 모두 0회
- CREATE_ONLY booking event가 삭제방을 재노출하지 않음

### 2.2 MySQL 통합

- Flyway 전체 적용과 JPA validate
- 동시 방 생성 시 UNIQUE로 한 방
- 방 생성과 두 member 저장의 원자성
- 동시 같은 메시지 재시도 시 한 메시지
- 과거 cursor와 forward `afterMessageId` 정렬·페이지
- 사용자별 삭제 경계 적용
- DELETE가 opaque undoToken만 반환
- DELETE 재시도가 내부 deadline을 연장하지 않음
- pending 중 재삭제가 같은 cycle에서 경계만 전진
- 오래된 token과 내부 기한 만료 Undo 거부
- 두 member 공통 경계까지만 purge
- prefix purge 후 room 마지막 메시지 포인터 정합성
- 신고 snapshot과 purge 동시성
- 열린 신고 동시 접수가 active-slot UNIQUE로 한 행
- 최종 처리 시 6개월 만료일 계산

### 2.3 WebSocket·STOMP 통합

- 정상 JWT CONNECT와 `Principal.name=userId`
- token 누락·위조·만료·온보딩 미완료 거부
- 연결 중 token 만료 도달 시 session 종료
- JWT 인증 interceptor가 인가 interceptor보다 먼저 실행
- 제3자의 room SUBSCRIBE·SEND 거부
- 타 user queue, raw queue, wildcard destination 거부
- 허용하지 않은 destination deny-all
- DB commit 이전 broadcast 0회
- 두 참여자가 저장 완료 room topic 수신
- 발신 session만 application send result 수신
- 중복 retry는 결과만 받고 room broadcast 한 번
- 64 KiB transport와 3,000 code point 경계
- control ping/pong과 `SUBSCRIPTION_READY` barrier
- 최초 구독과 REST 조회 사이 race 복구
- 중간 publish 실패를 연속 DB checkpoint가 복구
- 10초 heartbeat, 연결 손실, 서버 재시작 후 reconnect

### 2.4 REST·신고 사유 현지화

- 사용자 언어별 동일 code·다른 label
- 번역 누락 시 영어 fallback
- 방 신고에서 reporter와 reported user를 서버가 결정
- 자유 입력 detail을 받지 않음
- 빈 방 신고 거부
- 열린 신고 재시도 200, 신규 201
- 최종 처리 뒤 새 대화 없는 재신고 거부
- 삭제 상태별 신고 가능 범위
- 다른 사용자의 reportId 조회를 동일 404 처리
- 운영 상태 전이와 optimistic lock
- 7일 reviewDueAt과 처리 완료 + 6개월 retention expiry
- evidence append-only version과 hash 유지

### 2.5 채팅 메시지 자동 번역

- 수신자의 `users.lang`이 `en`, `ko`, `ja`일 때 대상 언어가 정확함
- 미설정 언어가 기존 규칙대로 `en`으로 fallback
- 발신자의 `users.lang`을 원문 언어로 사용하지 않고 provider 자동 감지
- 원문 commit·ACK·room broadcast가 Google timeout·4xx·5xx와 독립
- `PENDING → PROCESSING → SUCCEEDED | NOT_REQUIRED | FAILED` 상태 전이
- lease 만료 작업을 worker가 안전하게 다시 처리
- 같은 `clientMessageId` 재시도와 동시 worker 실행에도 번역 행 한 개
- 번역 결과는 수신자만 받고 발신자·제3자는 받지 않음
- 차단·비참여·길이 초과 본문은 Google 호출 0회
- translation 이벤트 유실 후 REST 이력으로 복구
- 번역 결과가 원문보다 먼저 도착해도 `messageId`로 병합
- 번역 완료가 방 재노출·마지막 메시지·삭제 경계를 변경하지 않음
- 원문 물리 파기 시 연결된 번역 행도 파기
- 신고 evidence와 hash가 번역 여부와 관계없이 원문 기준
- 원문·번역문의 markup을 plain text로 처리
- 로그·APM에 원문, 번역문, provider payload가 없음

### 2.6 모듈·이벤트

- Spring Modulith allowed dependency 검증
- `report -> chat::api` 단방향 유지
- booking과 chat의 순환 의존 없음
- BookingCreatedEvent publication 저장·listener 실패·재처리
- 같은 eventId·bookingId 재처리의 방 생성 멱등성
- 일반 신고 source hold 생성·최종 처리 해제의 원자성

## 3. 구현 순서

### 1단계: 계약과 DB

1. 이 폴더의 API·STOMP 계약을 DTO와 error code로 확정
2. 구현 직전 최신 Flyway 번호 확인
3. chat room·member·message migration
4. message translation 작업·결과 migration과 worker index
5. report reason·report·evidence·history·hold migration
6. Spring Modulith Event Publication Registry migration·재처리 설정
7. JPA entity와 repository adapter

### 2단계: 방과 REST

1. `ChatListingQueryService`, `ChatCounterpartQueryService`, `ChatReportQueryService` 공개 interface
2. `package-info.java`의 allowed dependency와 `@NamedInterface` 정합화
3. `ensureLandlordRoom`의 `INTERACTIVE_REOPEN`, `CREATE_ONLY` 구현
4. 문의 API와 durable BookingCreatedEvent 보상 listener
5. 방 목록·단건·과거/누락 메시지 조회
6. 기존 `/read`, REST message POST, `unreadCount` 비노출

### 3단계: WebSocket·STOMP

1. WebSocket starter와 필요한 security messaging 의존성
2. endpoint, Simple Broker, transport limit, heartbeat 설정
3. CONNECT JWT interceptor와 ACTIVE 확인
4. token expiresAt session lifecycle
5. 인증·인가 interceptor 순서와 destination deny-all
6. STOMP text message handler와 `String.codePointCount` 기반 3,000자 검증
7. control ping/pong과 subscription high-watermark barrier
8. commit 후 room broadcast, application send result, error, room event
9. 개인 translation queue와 exact destination 인가

### 4단계: 채팅 메시지 자동 번역

1. provider 독립 `MessageTranslationPort`
2. Google Cloud Translation Advanced v3 NMT adapter와 `text/plain` 요청
3. GCP project·인증·timeout·quota·provider 비활성화용 stub 설정
4. 원문 transaction의 `PENDING` 작업 저장과 lease 기반 worker
5. 자동 언어 감지, 대상 `users.lang` snapshot, 제한된 retry
6. REST 이력 translation projection과 수신자 개인 STOMP 결과
7. 원문 파기 시 번역 행 정리와 신고 원문 불변식

### 5단계: 삭제·Undo·차단

1. member 삭제 상태, generation, undoToken, 경계
2. DELETE와 restore endpoint
3. 내부 만료와 안전한 physical purge batch
4. 기존 `UserBlockService` 연결
5. room ensure·SEND에 양방향 차단 guard

### 6단계: 채팅방 신고

1. 고정 reason catalog와 `ko/en/ja` label
2. room participant·counterpart·evidence query
3. report·append-only evidence·status history·hold
4. 신고 접수와 내 신고 상태 조회
5. 운영자 목록·상세·상태 변경
6. 처리 목표와 보존 만료 batch

### 7단계: 검증과 운영

1. 단위·MySQL·REST·STOMP E2E 테스트
2. REST Docs와 STOMP protocol 문서화
3. 연결·지연·거부·재연결·batch 지표
4. 본문 없는 구조화 로그
5. 다중 인스턴스 전환 조건 점검

## 4. 운영 지표

- 현재 WebSocket session 수
- CONNECT·SUBSCRIBE·SEND 거부 수와 code
- 메시지 DB 저장 latency와 commit 후 publish 실패
- duplicate clientMessageId 처리 수
- reconnect·REST catch-up 횟수와 page 수
- 번역 처리 latency, 성공·불필요·실패·재시도 수
- 번역 작업 backlog·최대 지연과 처리 문자 수
- Event Publication Registry 미완료 건수·최대 지연
- 삭제·신고 만료 batch backlog와 실패
- 관리자 신고 처리 목표일 초과 건수

## 5. Broker 전환 기준

다음 중 하나가 발생하기 전에 Simple Broker를 외부 broker relay로 전환한다.

- 애플리케이션 JVM이 두 개 이상
- rolling deployment 중 구·신 인스턴스가 동시에 WebSocket을 수신
- 서버 간 메시지 fan-out 필요
- 실시간 전달 재시도·durable queue가 제품 요구가 됨

전환 후에도 유지할 계약:

- SEND·SUBSCRIBE destination
- MySQL message schema
- `clientMessageId` 멱등성
- 서비스의 참여자·차단 검증
- MySQL commit 후 publish 원칙

변경 대상은 broker 설정·연결·운영 계층이다. 외부 broker의 TLS, credential, heartbeat, 장애 정책, 다중 인스턴스 user-destination 설정을 전환 작업에 포함한다.

## 6. 구현 완료 기준

- 문의와 신청이 같은 비즈니스 키의 roomId로 수렴한다.
- 예약 직후 방 보장 실패를 client retry와 durable booking event가 보상한다.
- 로그인 완료 사용자만 REST와 STOMP를 사용할 수 있다.
- 비참여자는 roomId를 알아도 조회·구독·전송하지 못한다.
- 메시지는 MySQL commit 후에만 두 참여자에게 전달된다.
- 네트워크 재시도에도 같은 메시지가 중복 저장되지 않는다.
- 원문은 공백·줄바꿈 포함 Unicode code point 3,000자까지 허용한다.
- 받은 메시지는 사용자 언어 번역본을 우선 표시하고 원문을 확인할 수 있다.
- 내가 보낸 메시지는 원문을 우선 표시한다.
- 번역 장애가 원문 저장·ACK·실시간 전달을 실패시키지 않는다.
- 번역 결과는 지정 수신자에게만 전달되고 REST 이력으로 복구된다.
- 원문 파기 시 번역본도 함께 파기되고 신고 증거는 원문을 유지한다.
- WebSocket 단절 중 메시지를 REST catch-up으로 복구한다.
- 삭제는 상대 기록에 영향을 주지 않고 현재 화면에서 Undo할 수 있다.
- 사용자에게 삭제방 목록이나 복구 가능 상태를 제공하지 않는다.
- 내부 유예 만료 후 해당 삭제 경계가 복구 불가능하게 확정된다.
- 차단 이후 메시지는 저장·전달되지 않고 과거 기록은 유지된다.
- 신고는 방·신고자·상대·사유·접수·처리·만료·증거를 갖는다.
- 신고 UI와 API에 자유 입력 상세 사유가 없다.
- 신고 사유 label은 로그인 사용자 언어로 반환된다.
- 읽음 기능, 카드 메시지, 그룹 채팅은 포함되지 않는다.
- 단일 EC2에서 Simple Broker로 동작하고 재연결 시 MySQL로 복구된다.

## 7. 참고 자료

- [Spring STOMP 활성화](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/enable.html)
- [Spring Simple Broker](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-simple-broker.html)
- [Spring STOMP Broker Relay](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html)
- [Spring STOMP interceptor](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/interceptors.html)
- [Spring STOMP token 인증](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html)
- [Spring Security WebSocket](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html)
- [WebSocket transport message limit](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/config/annotation/WebSocketTransportRegistration.html)
- [Google Cloud Translation Advanced 개요](https://docs.cloud.google.com/translate/docs/api-overview)
- [Google Cloud Translation 텍스트 번역](https://docs.cloud.google.com/translate/docs/translate-text)
- [Google Cloud Translation 할당량과 요청 크기](https://docs.cloud.google.com/translate/quotas)
- [Google Cloud Translation 인증](https://docs.cloud.google.com/translate/docs/authentication)
- [Google Cloud Translation 데이터 사용](https://docs.cloud.google.com/translate/data-usage)
- [Google Cloud Translation 표시 요구사항](https://docs.cloud.google.com/translate/attribution)
- [기존 예약·문의·채팅 API 초안](../../api/specs/04-booking-inquiry-chat.md)
- [기존 신고 API 초안](../../api/specs/07-reports.md)
- [기존 도메인 모델](../domain-model.md)
- [기존 DB 논리 설계](../../database/database-design.md)
