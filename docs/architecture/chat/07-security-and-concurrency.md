# 보안·트랜잭션·동시성

이 문서는 모든 채팅 기능에 공통으로 적용되는 서버 방어 규칙의 정본이다.

관리자 신고 처리와 3개월 만료·물리 삭제의 보안·동시성 규칙은 [후속 고도화 문서](future/README.md)로 분리한다.

## 1. 인증과 신원

- 사용자용 chat·inquiry·report REST는 `hasRole("USER")`로 명시한다.
- 온보딩 전 `ROLE_ONBOARDING` 사용자는 채팅을 사용할 수 없다.
- REST controller는 기존 예약 controller처럼 `AuthPrincipal.userId`를 service에 전달한다.
- STOMP 인증은 [03-websocket-stomp.md](03-websocket-stomp.md)의 CONNECT 계약을 따른다.
- 사용자 `TEXT` 발신자·신고자·차단자는 항상 인증 Principal에서 얻는다. `INQUIRY_CARD`는 검증된 문의 REST 흐름이, `BOOKING_CARD`는 신뢰된 서버 이벤트가 만들며 두 카드의 `senderId`는 null이다.
- body의 `senderId`, `reporterId`, `tenantId`, `landlordId`, `blockedUserId`를 신뢰하지 않는다.
- 신고 대상과 차단 대상은 room의 다른 참여자로 서버가 결정한다.

## 2. 권한 검사

### REST

방 목록은 자신의 member 행이 있는 방만 반환한다. 방 단건·메시지 이력·삭제·차단·신고는 다음을 검사한다.

1. 로그인 완료 사용자
2. 방 존재
3. 요청자가 tenant 또는 landlord 참여자
4. 기능별 숨김 상태와 차단 규칙

다른 사용자의 방은 roomId를 알아도 조회하거나 변경할 수 없다.

### STOMP

- CONNECT에서 JWT와 ACTIVE 상태 확인
- SUBSCRIBE에서 exact destination과 room 참여자 확인
- SEND interceptor에서 destination과 인증 확인
- 실제 DB 쓰기 직전 service transaction에서 참여자·양방향 차단을 다시 확인
- broker/user prefix로 향하는 클라이언트 직접 MESSAGE는 거부
- 클라이언트 payload의 `type`, `inquiryCard`, `bookingCard`, `payload`, `bookingId`는 받지 않으며 서버 카드 위조 전송을 거부
- 정확히 허용한 다섯 user queue 외에는 deny-all
- 번역 결과는 해당 `recipientUserId`의 `/user/queue/chat-translations`에만 전달

interceptor 검사만으로 service 검사를 생략하지 않는다. 구독 뒤 차단되거나 내부 호출로 service가 실행되는 경우도 방어해야 한다.

## 3. 사용자 TEXT 검증 순서

사용자가 보낸 TEXT를 저장하기 전에 다음을 검사한다.

1. session Principal과 token 만료
2. 양의 roomId와 허용 destination
3. 유효한 UUID `clientMessageId`
4. null·빈 값·공백만 있는 본문 여부
5. Unicode code point 최대 3,000자
6. 사용자·방 단위 rate limit
7. 방 존재와 참여자
8. 두 사용자 사이 어느 방향의 차단도 없는지

차단된 전송은 `chat_messages` INSERT, room 갱신, broker publish 전에 종료한다. 거부된 본문을 로그나 별도 실패 queue에 남기지 않는다.

참여자·차단·본문 검증을 통과해 MySQL에 저장된 TEXT 원문만 외부 번역 provider로 보낼 수 있다. 클라이언트가 보낸 원문 언어·대상 언어·수신자 ID는 신뢰하지 않는다. `INQUIRY_CARD`와 `BOOKING_CARD` payload는 Google 번역에 보내지 않는다.

## 4. 방 생성 동시성

선행 조회는 최적화일 뿐 최종 중복 방지 수단이 아니다.

1. 비즈니스 키로 기존 방 조회
2. 없으면 MySQL native atomic insert/upsert로 roomId 확보
3. 신규 방이면 같은 transaction에서 tenant·landlord member 두 행 생성
4. 문의 경로는 방을 잠근 뒤 최근 문의서·마지막 메시지·요청자의 숨김 경계로 `INQUIRY_CARD` 저장 필요 여부 판단
5. 필요하면 서버가 조회한 매물 요약으로 `INQUIRY_CARD`를 저장하고, 필요하지 않으면 기존 상태 유지
6. 동시 요청 모두 같은 roomId 반환

필수 DB 방어:

```sql
UNIQUE (listing_id, tenant_id, landlord_id)
UNIQUE (chat_room_id, user_id)
```

JPA flush의 unique exception을 catch한 rollback-only transaction 안에서 재조회하지 않는다. native atomic statement를 사용하거나 충돌 transaction을 끝낸 뒤 안전한 새 read transaction으로 조회한다.

## 5. 메시지 저장 동시성

### 사용자 TEXT

하나의 transaction에서 다음을 처리한다.

- room과 두 member를 고정 순서로 lock
- 참여자·차단·상태 재검증
- MySQL UNIQUE 기반 원자적 메시지 insert/upsert
- 신규일 때만 room 마지막 메시지 갱신
- 신규일 때만 필요한 member 재노출

최종 판정은 다음 제약이 담당한다.

```sql
UNIQUE (chat_room_id, sender_id, client_message_id)
```

| 결과 | 처리 |
| --- | --- |
| 신규 ID | 메시지 저장·room 갱신·commit 후 broadcast |
| 중복 ID·동일 본문 | 상태 변경 없이 기존 저장 결과만 반환 |
| 중복 ID·다른 본문 | `CHAT_CLIENT_MESSAGE_CONFLICT` |

선행 SELECT만으로 중복을 판단하지 않는다. 두 동시 요청이 모두 조회를 통과할 수 있기 때문이다. 중복 retry는 삭제방 재노출이나 `last_message_at` 변경도 만들지 않는다.

### 서버 INQUIRY_CARD

문의서의 생성 요청은 임차인의 인증된 `POST /api/v1/listings/{listingId}/inquiries`에서만 시작한다. 클라이언트가 카드 필드나 임대인 ID를 보내지 않는다.

1. Listing 공개 API에서 공개 상태, 실제 임대인과 문의서용 매물 요약을 조회한다.
2. 본인 매물과 양방향 차단을 검사한다.
3. 기존 방을 먼저 찾고 없으면 새 방 생성을 시도한다.
4. 새 방이면 방·두 member·첫 `INQUIRY_CARD`·방 마지막 메시지 상태를 한 트랜잭션으로 저장한다.
5. 기존 방 또는 방 UNIQUE 충돌로 기존 방에 수렴한 요청은 방 lock을 얻은 뒤 최근 문의서·마지막 메시지·요청자의 숨김 경계를 다시 읽는다.
6. 문의서가 없거나 최근 문의서 뒤에 다른 메시지가 있거나 요청자가 최근 문의서를 볼 수 없을 때만 새 문의서를 저장한다.
7. 신규 문의서 transaction이 커밋된 뒤에만 room topic과 방 목록 queue로 이벤트를 발행한다.

따라서 방만 남고 첫 문의서가 빠지는 중간 상태와 동시 문의로 카드가 두 장 생기는 상태를 만들지 않는다. 요청자에게 보이는 문의서가 이미 마지막 메시지인 재시도는 메시지·마지막 포인터·broadcast를 변경하지 않는다. 반대로 신청 카드나 TEXT 뒤의 문의, 또는 삭제로 최근 문의서를 볼 수 없는 사용자의 문의는 기존 방에도 새 카드를 저장한다.

### 서버 BOOKING_CARD

신청 카드의 생성 요청은 클라이언트가 아니라 MySQL에 저장된 `BookingCreatedEvent`에서만 시작한다.

1. room과 두 member를 TEXT와 같은 순서로 잠근다.
2. 문의하기와 같은 `(listingId, tenantId, landlordId)` 방을 조회하거나 생성한다.
3. booking 공개 API에서 기존 신청 상세 데이터를 조회해 카드 payload를 만든다.
4. `(chatRoomId, bookingId)` UNIQUE를 최종 중복 판정으로 사용한다.
5. 신규 카드일 때만 `last_message_id`, `last_message_at`, 필요한 방 재표시를 갱신한다.
6. commit 후에만 room topic으로 카드를 발행한다.

같은 `bookingId` 이벤트 재처리는 기존 카드를 그대로 사용하며 두 번째 카드, broadcast, 마지막 메시지 갱신과 방 재표시를 만들지 않는다. 새 `bookingId`의 카드 최초 저장은 실제 신규 활동으로 처리한다.

## 6. 신고 트랜잭션

### 접수

`ReportCreator`가 신규 신고 생성 transaction을 연다. 그 안에서 `chat::api`의 증거 조회가 같은 MySQL transaction에 참여한다.

1. room lock
2. reporter 참여자 여부와 reported user 도출
3. 신고자에게 현재 보이는 범위와 마지막 TEXT messageId 확인
4. 최근 TEXT 원문 snapshot 생성 (`INQUIRY_CARD`, `BOOKING_CARD` payload 제외)
5. `chat_reports`와 `chat_report_evidence` 저장
6. 한 번에 commit

신고 중복은 `(reporterId, chatRoomId)` UNIQUE가 최종 방어한다. unique 충돌로 신규 생성 transaction이 롤백된 뒤 `ReportService`가 기존 신고를 다시 조회해 `200`으로 반환한다. 두 번째 reason으로 최초 신고와 evidence를 덮어쓰지 않는다.

## 7. Lock 순서

교착을 줄이기 위해 room을 공통 직렬화 지점으로 사용한다.

```text
chat_room → chat_room_members(ID 오름차순) → 필요한 message/report row
```

TEXT SEND, 문의 방 보장·INQUIRY_CARD 판단/저장, BOOKING_CARD 저장, DELETE, 신고 snapshot이 같은 순서를 따른다. 후속 물리 삭제 batch도 이 순서를 유지해야 하며 상세는 [후속 보존 설계](future/02-retention-and-physical-deletion.md)를 따른다.

## 8. 커밋과 broker 발행

- DB commit 전에 room topic에 발행하지 않는다.
- rollback된 메시지가 화면에 나타나면 안 된다.
- commit 후 Simple Broker 발행이 실패해도 MySQL 행은 유지한다.
- 누락된 실시간 전달은 REST `afterMessageId` catch-up으로 복구한다.
- 강한 실시간 재전송 보장이 제품 요구가 되면 transactional outbox를 추가한다.

신청의 `BookingCreatedEvent` 내구성과 채팅 메시지의 실시간 broker 전달은 별개다. 전자는 MySQL-backed Event Publication Registry로 채팅방과 `BOOKING_CARD` 저장 완료까지 재처리하고, 후자는 초기에는 Simple Broker와 REST 복구를 사용한다.

## 9. 번역 worker와 외부 API

- TEXT 원문 메시지 commit과 번역 성공을 하나의 transaction이나 성공 조건으로 묶지 않는다.
- 외부 Google 호출 중 room·member·message DB lock을 보유하지 않는다.
- 번역 작업의 최종 멱등성은 `(messageId, recipientUserId, targetLanguage)` UNIQUE가 보장한다.
- 같은 `clientMessageId` 재시도와 worker 재시도는 번역 행과 Google 요청을 불필요하게 중복 생성하지 않는다.
- timeout·연결 오류·429·5xx만 한 Worker 작업 안에서 짧고 설정 가능한 backoff를 두고 최초 요청 포함 최대 5회 호출한다.
- 호출 직전에 `attempt_count`를 증가시키고, crash 복구 뒤에는 남은 횟수만 처리해 총 provider 호출이 5회를 넘지 않게 한다.
- 별도의 다음 재시도 시각은 저장하지 않으며, 5회 실패 또는 잘못된 요청·인증·설정 오류는 `FAILED`로 끝낸다.
- provider 장애가 길어질 때 요청 폭증을 막기 위해 timeout과 circuit breaker를 둔다.
- Google 장애·지원하지 않는 언어·과도한 결과 크기는 원문 SEND 오류가 아니다.
- 최종 번역 실패는 수신자 개인 번역 이벤트로 알리고 원문을 유지한다.
- translation queue 이벤트를 놓치면 참여자·삭제 경계를 검사한 REST 메시지 이력으로 복구한다.
- 원문과 번역문은 모두 신뢰하지 않는 plain text로 취급하고 앱에서 HTML로 렌더링하지 않는다.
- INQUIRY_CARD와 BOOKING_CARD에는 번역 작업을 만들지 않고 payload의 고정 라벨은 앱에서 `ko/en`으로 표시한다.

Google credential은 서버의 secret 또는 AWS와 GCP 사이의 Workload Identity Federation으로 관리한다. 기존 소셜 로그인용 Google client ID와 혼용하거나 모바일 앱에 포함하지 않는다. 운영 호출은 TLS를 사용하며 project quota와 비용 경보를 설정한다.

채팅 원문이 외부 번역 provider로 전송된다는 사실과 Google 자동 번역 결과임을 사용자 안내·개인정보 문서·화면 표시에 반영한다. provider의 데이터 사용·보존 조건은 운영 도입 전에 다시 검토한다.

## 10. 오류 code

| 상황 | code |
| --- | --- |
| 토큰 없음·위조 | `UNAUTHENTICATED` |
| 토큰 만료 | `TOKEN_EXPIRED` |
| 온보딩 미완료 | `AUTH_ONBOARDING_REQUIRED` |
| 방 없음 | `CHAT_ROOM_NOT_FOUND` |
| 비참여자 | `FORBIDDEN` |
| 본인 매물 문의 | `CHAT_SELF_INQUIRY_NOT_ALLOWED` |
| 차단 관계 | `CHAT_UNAVAILABLE` |
| 빈 메시지 | `INVALID_INPUT` |
| 3,000자 초과 | `CHAT_MESSAGE_TOO_LONG` |
| 같은 clientMessageId에 다른 본문 | `CHAT_CLIENT_MESSAGE_CONFLICT` |
| 신고 사유 누락 | `INVALID_INPUT` |
| 지원하지 않는 신고 사유 문자열 | `MALFORMED_REQUEST` |
| 현재 보이는 TEXT가 없는 방 신고 | `REPORT_REQUIRES_TEXT_MESSAGE` |
| 전송률 초과 | `TOO_MANY_REQUESTS` |

차단 여부를 상대에게 상세히 노출하지 않기 위해 전송에는 일반 `CHAT_UNAVAILABLE`을 사용한다. CONNECT 실패는 STOMP ERROR 후 close하고, 개별 SEND 오류는 원 발신 session의 user queue로만 보낸다.

번역 실패는 이 표의 SEND 오류로 바꾸지 않는다. `/user/queue/chat-translations`의 `FAILED` 최종 결과로만 전달한다.

## 11. 로그와 데이터 노출

애플리케이션 로그·APM tag·오류 추적에 다음 값을 남기지 않는다.

- JWT와 STOMP Authorization header
- 원문과 번역문
- INQUIRY_CARD payload의 매물 이미지·제목·지역·유형·가격 정보
- BOOKING_CARD payload의 신청자 이름·성별·국적·이메일과 매물·금액 정보
- Google 번역 request·response와 credential
- 신고 evidence 원문

구조화 로그에는 필요한 최소 값만 남긴다.

- roomId, messageId, senderId
- clientMessageId의 원문 대신 필요하면 제한된 진단용 식별값
- 결과 code와 처리 시간
- WebSocket 연결·구독·재연결 수
- 번역 대상 언어, 상태, provider 지연, 재시도 횟수와 비민감 오류 분류

사용자 신고 접수 응답에는 evidence, reportedUserId, 보관 만료 시각, 운영자 ID와 내부 처리 정보를 반환하지 않는다. 사용자용 신고 단건 조회 API는 만들지 않고 접수 POST의 `reportId`로 성공을 확인한다.

## 12. 보안 체크리스트

- [ ] 모든 사용자용 chat·inquiry·report REST에 `ROLE_USER`
- [ ] CONNECT JWT, ACTIVE 계정, token expiresAt 검증
- [ ] `Principal.name == userId` 보장
- [ ] 인증 interceptor가 메시지 인가 interceptor보다 먼저 실행
- [ ] SEND·SUBSCRIBE room 참여자 검사
- [ ] 클라이언트의 INQUIRY_CARD·BOOKING_CARD·bookingId·payload 직접 전송 거부
- [ ] 서비스 transaction에서 SEND 권한 재검증
- [ ] 정확한 destination 외 deny-all
- [ ] 개인 번역 결과를 지정 수신자에게만 전달
- [ ] WSS와 Origin allowlist
- [ ] URL query token 금지
- [ ] 차단 후 본문 저장·발행 금지
- [ ] 거부된 본문의 Google 호출 금지
- [ ] 사용자·방 단위 rate limit
- [ ] 원문·번역문·INQUIRY_CARD·BOOKING_CARD payload·provider payload·token·evidence 로그 금지
- [ ] Google credential 서버 보관, timeout·retry·quota 설정
- [ ] 원문·번역문 plain text 처리와 출력 escaping
- [ ] 외부 번역 처리 고지와 Google 자동 번역 attribution

관리자 전용 보안 체크리스트는 [후속 관리자 설계](future/01-admin-report-management.md)에 둔다.
