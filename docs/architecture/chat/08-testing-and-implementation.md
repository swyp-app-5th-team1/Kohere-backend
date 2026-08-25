# 클라이언트 동작·테스트·개발 계획

## 이 문서를 읽는 방법

- **개발 순서만 빠르게 보고 싶다면:** [3. 개발 계획](#3-개발-계획)만 읽는다.
- **앱에서 어떻게 동작하는지 알고 싶다면:** [1. 클라이언트 동작](#1-클라이언트-동작)을 읽는다.
- **무엇을 검증해야 하는지 알고 싶다면:** [2. 테스트 계획](#2-테스트-계획)을 읽는다.
- 각 개발 단계 앞부분은 쉬운 설명이고, 그 아래 번호 목록은 백엔드 구현자를 위한 기술 상세다.

## 1. 클라이언트 동작

### 1.1 채팅방 진입

1. REST로 방 헤더를 조회한다.
2. WebSocket에 CONNECT한다.
3. 저장 결과·오류·room event·translation·control user queue를 구독한다.
4. control ping/pong으로 개인 queue 준비를 확인한다.
5. room topic을 구독한다.
6. `SUBSCRIPTION_READY(roomId, highWatermark)`를 기다린다.
7. REST 최근 이력과 필요한 `afterMessageId` catch-up을 high-watermark까지 완료한다.
8. REST 결과와 구독 직후 topic 이벤트를 `messageId`로 합친다. 사용자 TEXT의 임시 말풍선만 `clientMessageId`도 함께 사용한다.

SUBSCRIBE frame을 보낸 시점만으로 구독이 준비됐다고 가정하지 않는다. 같은 메시지가 REST와 topic 양쪽에서 도착하는 것은 정상이며 ID로 제거한다. 이 REST catch-up은 앱이 진입·재연결 때 한 번 자동 실행하며 주기적인 polling으로 사용하지 않는다.

`SUBSCRIPTION_READY`가 5초 안에 오지 않으면 같은 연결에서 구독 요청을 반복해 쌓지 않는다. socket을 닫고 재연결한 뒤 위 순서를 처음부터 다시 실행한다. 서버도 준비 이벤트를 만드는 중 DB·broker 오류가 나면 해당 socket을 닫아 반쪽 구독을 정리한다.

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
- 발신 ACK가 오면 같은 `clientMessageId`의 임시 말풍선을 서버 `messageId`와 합친다.
- timeout이면 `failed` 또는 retry 상태로 바꾼다.
- retry는 같은 `clientMessageId`를 사용한다.
- 백엔드는 임시 말풍선 자체를 저장하지 않는다.

### 1.4 자동 번역 표시

- 내가 보낸 메시지는 원문을 기본 표시한다.
- 받은 메시지는 `translation`이 있으면 번역본을 기본 표시하고 `원문 보기`를 제공한다.
- 원문 보기와 번역문 보기 전환은 프런트엔드의 메시지별 화면 상태다.
- 수신자는 원문과 최종 번역 상태를 개인 queue의 한 이벤트로 받는다. `FAILED` 또는 `NOT_REQUIRED`면 원문을 표시한다.
- 백엔드는 `번역 중` 같은 표시 문자열을 만들지 않는다.
- translation 이벤트를 놓치면 다음 REST 메시지 이력의 저장된 번역본으로 복구한다.
- 자동 번역 결과에는 Google 표시 요구사항에 맞는 출처 안내를 붙인다.

### 1.5 신청 카드 표시

- 메시지 `type=BOOKING_CARD`이면 `bookingCard` 데이터로 신청 카드 UI를 그린다.
- 채팅방 상세의 `myRole=TENANT`이면 임차인용 “신청이 접수되었습니다” 화면을 표시한다.
- `myRole=LANDLORD`이면 임대인용 “새로운 입주 신청이 도착했습니다”와 신청자 상세 화면을 표시한다.
- 이름·성별·국적·입주일 같은 값은 서버 응답을 사용하고, 고정 라벨은 앱이 `ko/en`으로 표시한다.
- 카드에는 임시 말풍선이나 `clientMessageId`를 사용하지 않는다.
- 카드 데이터는 Google 번역 결과와 합치지 않는다.

### 1.6 삭제 UX

- DELETE가 `204 No Content`로 성공하면 앱 목록에서 해당 채팅방을 제거한다.
- 응답 본문이나 복원 token은 없고, Undo 버튼도 제공하지 않는다.
- 숨긴 채팅방 목록, 복구 가능 상태, 내부 삭제 상태를 사용자에게 노출하지 않는다.
- 같은 매물에서 새 대화를 시작해 같은 채팅방이 다시 표시돼도 삭제 이전 메시지는 보여 주지 않는다.

### 1.7 기존 기능 연동 시 프런트엔드 변경

- 차단: 기존 사용자 차단 저장 기능은 재사용하지만 채팅 화면에서는 새 room 기반 차단 API를 호출한다.
- 삭제: 채팅방 DELETE가 `204`이면 해당 채팅방을 목록에서 제거한다. 응답 본문과 복원 UI는 없다.
- 신고: 상세 사유 입력 없이 프런트가 `ko/en`으로 표시한 고정 사유 code 한 개를 room 신고 API로 보낸다.
- 메시지: 새 전송마다 프런트엔드가 `clientMessageId` UUID를 만들고 같은 메시지 재시도에는 같은 값을 사용한다.
- 신청 카드: 앱이 직접 카드 생성 API를 호출하거나 payload를 보내지 않는다. 기존 신청 API 성공 뒤 같은 채팅방을 열고, REST 이력 또는 room topic의 `BOOKING_CARD`를 역할별 UI로 표시한다.

## 2. 테스트 계획

### 2.1 도메인·서비스

- 본인 매물 문의 거부
- 같은 `(listingId, tenantId, landlordId)`가 같은 방 반환
- 문의로 만든 기존 방에 신청해도 같은 roomId에 BOOKING_CARD 한 장 추가
- 신청이 먼저여도 문의와 같은 기준의 방 생성
- 기존 Booking 상세 조립 로직으로 임차인·임대인 카드 데이터 생성
- 같은 bookingId 이벤트 재처리는 카드 한 장과 broadcast 한 번
- 다른 listing은 다른 방 반환
- 방 참여자만 조회·전송·삭제·차단·신고 가능
- 공백-only, 2,999자, 3,000자, 3,001자 경계
- emoji·결합문자의 Unicode code point 경계
- 같은 clientMessageId 재시도는 DB 한 행
- 같은 clientMessageId와 다른 content는 충돌
- 차단 관계에서 DB INSERT와 broker publish 모두 0회
- 지연된 예약 이벤트의 방 존재 보장이 삭제방을 재노출하지 않음

### 2.2 MySQL 통합

- Flyway 전체 적용과 JPA validate
- 동시 방 생성 시 UNIQUE로 한 방
- 방 생성과 두 member 저장의 원자성
- 동시 같은 메시지 재시도 시 한 메시지
- `(chatRoomId, bookingId)` UNIQUE로 신청 카드 한 장
- TEXT는 content/clientMessageId, BOOKING_CARD는 bookingId/payload만 저장
- 과거 cursor와 forward `afterMessageId` 정렬·페이지
- 사용자별 삭제 경계 적용
- DELETE가 `204`와 빈 응답 본문을 반환
- 같은 숨김 상태의 DELETE 재시도가 새 삭제 기록을 만들지 않음
- 숨김 경계가 DELETE 재시도나 재진입으로 감소하지 않음
- 사용자 복원 endpoint가 노출되지 않음
- 새 메시지·직접 문의가 방만 다시 표시하고 과거 숨김 경계는 유지
- 동일 방 신고 동시 접수가 UNIQUE로 한 행

### 2.3 WebSocket·STOMP 통합

- 정상 JWT CONNECT와 `Principal.name=userId`
- token 누락·위조·만료·온보딩 미완료 거부
- 연결 중 token 만료 도달 시 session 종료
- JWT 인증 interceptor가 인가 interceptor보다 먼저 실행
- 제3자의 room SUBSCRIBE·SEND 거부
- 타 user queue, raw queue, wildcard destination 거부
- 허용하지 않은 destination deny-all
- DB commit 이전 broadcast 0회
- 발신 session은 저장 ACK를 받고 수신자는 원문·번역 결합 개인 이벤트 수신
- 두 참여자가 서버 생성 BOOKING_CARD를 실시간 수신하고 제3자는 수신하지 못함
- 클라이언트의 type·bookingId·bookingCard·payload 직접 SEND 거부
- 카드 실시간 이벤트를 놓쳐도 REST 이력에서 복구
- 발신 session만 application send result 수신
- 중복 retry는 결과만 받고 room broadcast 한 번
- 64 KiB transport와 3,000 code point 경계
- control ping/pong과 `SUBSCRIPTION_READY` barrier
- 중복 destination·128자 초과 ID·session당 16개 초과 구독 거부
- 준비 이벤트 생성 실패나 5초 timeout 뒤 socket 종료·재연결
- 최초 구독과 REST 조회 사이 race 복구
- 중간 publish 실패를 연속 DB checkpoint가 복구
- 10초 heartbeat, 연결 손실, 서버 재시작 후 reconnect

### 2.4 REST·채팅방 신고

- 프런트 고정 사유 6종과 backend enum code 일치
- 방 신고에서 reporter와 reported user를 서버가 결정
- 자유 입력 detail을 받지 않음
- 빈 방 신고 거부
- 동일 방 신고 재시도 200, 신규 201
- 현재 보이는 메시지 범위만 신고 evidence로 사용
- 최근 TEXT 최대 20개만 저장하고 BOOKING_CARD·번역문 제외
- 사용자용 사유 목록·신고 상태 조회 API가 노출되지 않음
- 최초 evidence snapshot과 hash 유지

### 2.5 채팅 메시지 자동 번역

- 수신자의 `users.lang`이 `en`, `ko`일 때 대상 언어가 정확함
- 미설정 언어가 기존 규칙대로 `en`으로 fallback
- 현재 채팅 미지원 언어 값은 `en`으로 정규화
- 발신자의 `users.lang`을 원문 언어로 사용하지 않고 provider 자동 감지
- 원문 commit·발신자 ACK가 Google timeout·4xx·5xx와 독립이며, 수신자 메시지 이벤트는 최종 번역 상태와 원문을 함께 전달
- `PENDING → PROCESSING → SUCCEEDED | NOT_REQUIRED | FAILED` 상태 전이
- 재시도 가능한 오류는 최초 요청 포함 최대 5회만 호출하고 별도 다음 재시도 시각을 저장하지 않음
- timeout·연결 오류·429·5xx는 최대 5회, 잘못된 요청·인증·설정 오류는 한 번에 `FAILED`
- n번째 호출 뒤 Worker가 중단되면 lease 회수 후 남은 `5-n`회만 실행
- 5번째 호출 중 Worker가 중단되면 lease 만료 뒤 추가 호출 없이 `FAILED`로 종결하고 원문 전달
- 여러 Worker가 경합해도 같은 번역의 provider 총 호출은 5회 이하
- lease 만료 작업을 worker가 안전하게 다시 처리
- 같은 `clientMessageId` 재시도와 동시 worker 실행에도 번역 행 한 개
- 번역 결과는 수신자만 받고 발신자·제3자는 받지 않음
- 차단·비참여·길이 초과 본문은 Google 호출 0회
- BOOKING_CARD는 번역 행과 Google 호출 0회
- translation 이벤트 유실 후 REST 이력으로 복구
- 수신자 이벤트 한 건에 원문과 최종 번역 상태가 함께 포함됨
- 번역 완료가 방 재노출·마지막 메시지·삭제 경계를 변경하지 않음
- 방을 숨겨도 원문과 번역 행은 물리 삭제되지 않음
- 신고 evidence와 hash가 번역 여부와 관계없이 원문 기준
- 원문·번역문의 markup을 plain text로 처리
- 로그·APM에 원문, 번역문, BOOKING_CARD payload, provider payload가 없음

### 2.6 모듈·이벤트

- Spring Modulith allowed dependency 검증
- `report -> chat::api` 단방향 유지
- booking과 chat의 순환 의존 없음
- BookingCreatedEvent publication 저장·listener 실패·재처리
- 같은 eventId·bookingId 재처리의 방·BOOKING_CARD 저장 멱등성

## 3. 개발 계획

> 채팅 기능을 한 번에 모두 만드는 것이 아니다. 먼저 저장과 보안을 준비하고, 채팅방과 메시지 저장을 완성한 다음 실시간 통신·자동 번역·신고를 차례로 붙인다.

사용자가 보는 최종 흐름은 다음과 같다.

```text
문의 또는 신청
  → 기존 채팅방 열기 또는 새 채팅방 만들기
  → 신청이면 같은 방에 신청 카드 표시
  → 사용자가 보낸 TEXT 저장
  → 상대방에게 실시간 전달
  → 받은 사용자 언어로 자동 번역
  → 필요하면 삭제·차단·신고
```

### 3.1 진행 원칙

- 아래 순서대로 구현하며, 각 단계의 완료 조건과 자동 테스트를 통과한 뒤 다음 단계로 넘어간다.
- 한 단계는 하나의 독립적인 PR로 나누는 것을 권장한다.
- MySQL과 REST 조회를 먼저 완성하고, 그 위에 STOMP 실시간 전달과 자동 번역을 올린다.
- Swagger·REST Docs는 REST 계약의 정본이고, STOMP는 별도 protocol 문서와 통합 테스트로 검증한다.
- 개발 도중 일부 단계만 운영 배포하지 않는다. 사용자 기능 출시는 9단계 통합 검증이 끝난 뒤 진행한다.
- 관리자 신고 처리와 3개월 만료·물리 삭제는 이번 계획에 포함하지 않는다. 해당 계획은 [후속 고도화 문서](future/04-testing-and-operations.md)를 따른다.

### 3.2 단계 요약

| 단계 | 쉽게 말하면 | 이 단계가 끝나면 |
| --- | --- | --- |
| 1 | 앱과 서버가 주고받을 내용 정하기 | 프런트엔드와 백엔드가 같은 규칙으로 개발할 수 있음 |
| 2 | 저장 공간과 로그인 확인 기능 준비하기 | 채팅 데이터를 안전하게 저장하고 사용자 권한을 확인할 수 있음 |
| 3 | 문의·신청과 채팅방 연결하기 | 같은 채팅방이 열리고 신청이면 카드가 한 번 표시됨 |
| 4 | 삭제와 차단 만들기 | 삭제는 내 화면에만 적용되고 차단 후 새 메시지가 막힘 |
| 5 | 메시지를 중복 없이 저장하기 | 네트워크 때문에 다시 보내도 메시지가 한 번만 저장됨 |
| 6 | 실시간 채팅 연결하기 | 두 사용자가 바로 메시지를 주고받고 누락분도 보충함 |
| 7 | 받은 메시지 자동 번역하기 | 새 메시지를 한국어 또는 영어로 볼 수 있음 |
| 8 | 채팅방 신고 만들기 | 고정 사유로 신고하고 POST 응답에서 접수 결과를 확인할 수 있음 |
| 9 | 전체 점검 후 출시 준비하기 | 신청부터 신고까지 전체 흐름과 장애·보안을 확인함 |

### 3.3 자동 처리와 앱 요청 구분

| 상황 | 누가 처리하는가 | 방식 |
| --- | --- | --- |
| 신청 성공 후 즉시 채팅방 열기 | 앱 | 일반 HTTP API를 한 번 호출 |
| 앱 종료 등으로 빠진 채팅방·신청 카드 보완 | 서버 | 저장된 신청 완료 이벤트를 자동 처리 |
| 연결 중 빠진 메시지 보충 | 앱 | STOMP 연결·재연결 직후 REST API를 한 번 자동 호출 |
| 새 메시지 실시간 수신 | 앱과 서버 | WebSocket·STOMP 연결 사용 |
| 새 메시지 자동 번역 | 서버 | 원문 저장 후 Translation Worker가 Google API 호출 |

채팅방 보완과 누락 메시지 보충은 사용자가 버튼을 누르는 기능이 아니다. 누락 메시지 보충도 일정 간격으로 계속 조회하는 polling이 아니라 연결·재연결 시 한 번 실행한다.

### 3.4 용어를 쉽게 설명하면

| 용어 | 쉬운 의미 |
| --- | --- |
| REST API | 앱이 서버에 정보를 요청하고 응답받는 일반적인 방식 |
| Flyway migration | 모든 서버 DB에 같은 테이블 변경을 순서대로 적용하는 기록 파일 |
| WebSocket·STOMP | 새 메시지를 바로 주고받기 위한 실시간 연결 방식 |
| `clientMessageId` | 재전송해도 메시지가 두 번 저장되지 않도록 앱이 만드는 UUID |
| REST catch-up | 연결이 끊긴 동안 빠진 메시지를 MySQL에서 다시 가져오는 절차 |
| Translation Worker | 저장된 번역 작업을 찾아 Google API 호출과 결과 저장을 관리하는 서버 기능 |
| `PENDING` | 원문이 아니라 번역 작업이 아직 처리되기 전인 내부 상태 |
| `BOOKING_CARD` | 신청 저장 후 서버가 같은 채팅방에 만드는 신청 정보 카드 |
| `payload` | 신청 카드에 필요한 매물·신청자·입주 조건·금액을 묶은 JSON 데이터 |

### 3.5 구현자용 상세 계획

아래 단계는 위의 쉬운 계획을 실제 코드와 테스트로 구현하는 순서다.

#### 1단계: 앱과 서버가 주고받을 규칙 정하기

구현 전에 프런트엔드와 백엔드가 함께 사용할 계약을 먼저 고정한다.

1. [REST API 계약](02-api-contracts.md)의 path, 요청·응답, status와 오류 code를 DTO 수준으로 확정한다.
2. [STOMP 계약](03-websocket-stomp.md)의 endpoint, destination, event schema와 version을 확정한다.
3. Markdown 계약에 `roomId`, cursor, `clientMessageId`의 역할과 예시를 작성한다. 실제 동작하는 REST endpoint는 각 구현 단계에서 REST Docs와 Swagger에 추가한다.
4. 프런트엔드가 새 메시지마다 UUID `clientMessageId`를 만들고 재시도에는 같은 값을 사용한다는 책임을 명시한다.
5. 기존 골격의 REST 메시지 전송, `/read`, `unreadCount`는 이번 API에서 비노출한다.
6. 사용자 SEND는 TEXT 전용이고, `BOOKING_CARD`는 서버가 생성한다는 REST·STOMP 계약을 확정한다.
7. 삭제는 `204 No Content`, 응답 본문 없음, 사용자 복원 없음으로 고정한다.
8. 지원 언어는 `ko/en`, TEXT 자동 번역은 항상 활성화, 과거 메시지 일괄 번역은 하지 않는 것으로 고정한다.

완료 조건:

- REST 문서와 STOMP 문서에서 같은 필드의 이름과 의미가 일치한다.
- 프런트엔드는 서버 구현 전에도 확정된 JSON 예시로 mock 화면을 만들 수 있다.
- 존재하지 않는 REST 메시지 전송 API가 Swagger에 노출되지 않는다.
- 미구현 신규 endpoint를 Swagger에 먼저 노출해 500 응답을 만들지 않는다.

#### 2단계: 저장 공간과 로그인 보안 준비하기

쉽게 말하면 채팅방과 메시지를 저장할 테이블을 만들고, 로그인한 참여자만 사용할 수 있도록 기반을 준비한다.

1. 구현 직전 최신 Flyway 번호를 확인하고 채팅방·참여자·TEXT·BOOKING_CARD 메시지 migration을 추가한다.
2. `chat_rooms`, `chat_room_members`, `chat_messages`의 UNIQUE·조회 인덱스·기본값을 적용한다. `chat_messages`에는 nullable `booking_id`, `payload`와 타입별 필드 조건을 포함한다.
3. 자동 번역 작업·결과와 신고 접수 테이블은 각 기능 단계에서 별도 migration으로 추가할 수 있도록 번호를 예약하지 않고 순서대로 작성한다.
4. JPA entity, repository port와 adapter를 구현하고 `ddl-auto=validate`로 migration과 매핑을 비교한다.
5. `chat -> listing::api`, `chat -> user::api` 공개 interface와 Modulith allowed dependency를 정리한다. `report -> chat::api`는 실제 신고 증거 계약을 정의하는 8단계에서 추가해 사용하지 않는 모듈 권한을 미리 열지 않는다.
6. chat·inquiry·report REST를 `ROLE_USER`로 명시하고 온보딩 token 접근을 차단한다.
7. STOMP에서 재사용할 JWT 검증 결과에 `userId`, 온보딩 완료 여부와 검증된 만료시각을 제공한다. 토큰 발급 흐름은 변경하지 않는다.

완료 조건:

- 빈 DB에 Flyway 전체 migration이 성공하고 JPA validation이 통과한다.
- 중복 채팅방·중복 참여자·중복 메시지를 DB 제약으로 막을 수 있다.
- Spring Modulith 구조 테스트와 REST Security 경계 테스트가 통과한다.

#### 3단계: 문의·신청에서 채팅방 열기

쉽게 말하면 문의하기와 신청하기가 같은 채팅방으로 이어지고, 신청하면 기존 Booking 데이터를 이용한 신청 카드가 그 방에 한 번 표시되게 만든다. 앱이 채팅방 열기 요청을 놓쳐도 서버가 저장된 신청 완료 이벤트로 자동 보완한다.

1. `listingId`로 매물·임대인·표시용 snapshot을 조회하는 공개 API를 구현한다.
2. `(listingId, tenantId, landlordId)`를 기준으로 채팅방을 원자적으로 조회하거나 생성한다.
3. 새 채팅방이면 세입자와 임대인 참여자 두 행을 같은 트랜잭션으로 저장한다.
4. 문의 API는 신규 `201` 또는 기존 `200`과 동일한 `roomId`를 반환한다.
5. 채팅방 목록·단건·과거 cursor·`afterMessageId` 누락 조회를 구현한다.
6. Booking Service가 예약 생성 때 이미 조회한 `RoomOfferBookingView`·`ApplicantProfileView`와 상세 조회와 같은 금액 공식을 `BookingCreatedEventFactory`에서 재사용한다.
7. `BookingCreatedEvent`에 멱등 event ID, bookingId, 발생 시각, 참여자와 신청 시점 카드 사본을 포함하고 예약 저장 뒤 실제로 발행한다.
8. `spring-modulith-starter-jpa`와 V25 `event_publication`으로 MySQL-backed publication을 저장하고 미완료 이벤트의 재기동 재전달을 구성한다.
9. 신청 성공 뒤 앱이 같은 문의 API로 채팅방에 진입하도록 하고, event handler는 같은 방에 `(chatRoomId, bookingId)` 기준으로 카드를 한 번 저장한다.
10. 신규 카드만 마지막 메시지·방 재표시를 만들고 동일 bookingId 재처리는 아무 상태도 변경하지 않는다. 실시간 발행은 STOMP 단계에서 commit 이후로 연결한다.
11. 사용자 탈퇴·익명화 이벤트가 오면 저장된 BOOKING_CARD의 신청자 PII도 ADR-0014에 맞게 익명화한다.

완료 조건:

- 동시 문의와 신청에도 같은 비즈니스 키가 정확히 한 `roomId`로 수렴한다.
- 문의 후 신청과 신청 후 문의 모두 같은 방을 사용하고 bookingId당 카드가 한 장만 존재한다.
- 임차인과 임대인이 같은 카드 데이터를 역할에 맞는 UI로 표시할 수 있다.
- 다른 매물은 다른 채팅방을 반환한다.
- 본인 매물 문의와 비참여자의 채팅방 조회가 거부된다.
- 목록·단건·이력 REST Docs 테스트가 통과한다.

#### 4단계: 사용자별 삭제와 차단 만들기

쉽게 말하면 삭제는 요청한 사용자 화면에만 적용하고, 차단하면 두 사용자 사이의 새 채팅만 막는다.

1. `room_hidden_at`, 단조 증가하는 메시지 숨김 경계와 실제 `delete_requested_at`을 구현한다.
2. `DELETE /api/v1/chat-rooms/{roomId}`는 요청자에게만 적용하고 `204`를 반환한다.
3. 같은 숨김 상태의 DELETE 재시도는 삭제 시각과 경계를 변경하지 않는다.
4. 직접 문의로 채팅방이 다시 표시돼도 삭제 이전 메시지·preview를 반환하지 않는다.
5. 채팅방의 다른 참여자를 서버에서 도출해 기존 `UserBlockService`를 호출한다.
6. 방 생성은 어느 방향의 차단도 있으면 `CHAT_UNAVAILABLE`로 거부하고, 같은 정책을 다음 단계의 메시지 저장에도 적용한다.
7. 차단 전 메시지 이력은 유지하고 신고 권한도 유지한다.

완료 조건:

- 삭제가 상대방 화면과 메시지 범위를 변경하지 않는다.
- 삭제한 사용자는 같은 채팅방이 다시 표시돼도 숨김 경계 이전 메시지를 보지 못한다.
- 반복 삭제는 멱등이고 사용자용 복원 endpoint는 존재하지 않는다.
- 차단 endpoint가 멱등으로 동작하고 신규 채팅방 생성이 거부된다.
- 차단 전 이력은 양쪽에 그대로 유지된다.

#### 5단계: 메시지를 중복 없이 저장하기

이 단계에서는 실시간 연결보다 먼저 메시지를 정확히 한 번 저장하는 기능을 완성한다. 저장이 안전해야 이후 WebSocket을 붙여도 중복이나 유실 문제를 구분하기 쉽다.

1. 발신자는 인증 Principal, 채팅방은 destination, 전송 시각은 서버 시계에서 결정한다.
2. 프런트엔드 UUID `clientMessageId`와 공백-only·Unicode code point 3,000자 제한을 검증한다.
3. 방 참여자와 양방향 차단을 DB 쓰기 직전에 다시 확인한다.
4. 원문 메시지 INSERT, 채팅방 마지막 메시지 갱신과 실제 새 메시지를 받은 사용자의 채팅방 재표시를 한 트랜잭션으로 처리한다.
5. `(chatRoomId, senderId, clientMessageId)` UNIQUE를 최종 중복 판정으로 사용한다.
6. 같은 ID·같은 원문 재시도는 기존 결과를 반환하고, 같은 ID·다른 원문은 충돌로 거부한다.
7. 중복 재시도는 채팅방 재표시, 마지막 메시지 갱신과 후속 번역 작업을 만들지 않는다.

완료 조건:

- 서비스·MySQL 통합 테스트에서 메시지가 한 번만 저장된다.
- rollback된 메시지는 마지막 메시지 포인터나 사용자 가시성을 변경하지 않는다.
- 실제 신규 메시지만 숨긴 수신자 채팅방을 다시 표시한다.
- 차단 관계에서는 메시지 INSERT가 0건이다.

#### 6단계: 실시간 채팅 연결하기

쉽게 말하면 5단계에서 완성한 메시지 저장 기능을 WebSocket·STOMP에 연결해 상대방 화면에 즉시 전달한다.

한 번에 전부 열지 않고 다음처럼 나눠 구현한다.

1. **연결·인증 기반(완료)**: `/ws/chat` handshake, CONNECT JWT, ACTIVE 계정, Origin, heartbeat, 64 KiB, token 만료 close를 구현했다.
2. **구독 권한·누락 보충(완료)**: 정확한 개인 queue와 참여자의 보이는 room topic만 허용하고, PING/PONG 및 실제 broker 등록 뒤 `SUBSCRIPTION_READY`를 구현했다. 앱은 기존 REST `afterMessageId` 조회로 누락분을 자동 보충하며 주기 polling은 하지 않는다.
3. **TEXT 실시간 저장·전송(완료)**: 기존 MySQL 저장 유스케이스를 STOMP handler에 연결했다. 신규 원문 commit 뒤 발신 session에는 ACK를 보낸다. 같은 UUID·같은 본문 재시도는 기존 결과 ACK만 보내며 다시 저장하거나 번역 작업을 만들지 않는다.
4. **BOOKING_CARD 실시간 연결(완료)**: 새 카드 commit만 topic에 발행하고 중복 event는 재발행하지 않는다. 새 방은 `ROOM_CREATED`, 기존 방은 `ROOM_UPDATED`, 숨긴 방이 실제 신청 활동으로 다시 표시되면 `ROOM_REOPENED` 목록 신호를 보낸다.

1. 연결 단계에는 WebSocket 의존성을 추가한다. destination 권한은 JWT interceptor 뒤에 실행되는 채팅 전용 allowlist interceptor에서 정확한 경로와 DB 참여 상태로 검사한다. 현재 HTTP 보안과 충돌하는 기본 CONNECT CSRF를 켜지 않기 위해 `@EnableWebSocketSecurity`는 사용하지 않는다.
2. `/ws/chat`, Spring Simple Broker, 64 KiB transport 제한과 heartbeat를 설정한다.
3. `/ws/chat` HTTP handshake는 transport 진입만 허용하고, 실제 사용자 인증은 STOMP CONNECT의 Bearer token을 `ChannelInterceptor`에서 수행한다.
4. 기존 `JwtTokenService`로 검증한 사용자와 만료시각을 WebSocket session Principal에 저장한다.
5. handshake 경로에 실린 HTTP Bearer token은 인증 수단으로 사용하지 않으며 실제 CONNECT token과 URL query token 정책을 구분한다.
6. JWT 인증 interceptor가 destination 인가보다 먼저 실행되도록 순서를 고정한다.
7. SEND·SUBSCRIBE와 개인 queue를 exact allowlist로 제한하고 그 밖의 destination은 deny-all한다.
8. STOMP handler는 5단계의 동일한 메시지 저장 유스케이스를 호출한다.
9. MySQL commit 뒤에만 발신 session ACK와 방 목록 갱신 신호를 보낸다. 수신자 TEXT는 번역 최종 상태 뒤 개인 queue로 보낸다.
10. control barrier, `SUBSCRIPTION_READY`, high-watermark와 REST catch-up을 구현한다.
11. 서버 생성 `BOOKING_CARD`도 신규 커밋 뒤 room topic으로 전달하고 놓친 카드는 REST 이력으로 복구한다.

완료 조건:

- 정상 발신자는 저장 ACK를 받고 수신자는 원문·번역 결합 메시지를 실시간 수신하며 제3자는 구독·전송하지 못한다.
- 클라이언트는 BOOKING_CARD를 SEND하지 못하고 서버가 만든 카드만 두 참여자가 수신한다.
- 차단 관계에서 room broadcast는 0건이다.
- Swagger 없이도 `WebSocketStompClient` 통합 테스트로 CONNECT·SUBSCRIBE·SEND를 자동 검증한다.
- 간단한 수동 smoke test는 Postman raw WebSocket 또는 테스트 앱으로 확인한다.
- 연결 종료·서버 재시작 뒤 REST catch-up이 놓친 메시지를 복구한다.

#### 7단계: 받은 새 메시지 자동 번역하기(완료)

쉽게 말하면 원문은 먼저 정상 저장해 발신자에게 ACK를 보내고, 수신자에게는 Google 처리 뒤 원문과 최종 번역 결과를 한 이벤트로 함께 전달한다.

1. `chat_message_translations`와 worker 조회 인덱스를 Flyway로 추가한다.
2. 신규 TEXT 원문과 수신자별 `PENDING` 번역 작업을 같은 트랜잭션에 저장한다. BOOKING_CARD에는 번역 작업을 만들지 않는다.
3. provider 독립 `ChatTranslationClient`, Google Cloud Translation Advanced v3 adapter와 번역 비활성 환경용 구현을 둔다.
4. 번역 대상은 수신자의 `users.lang`에서 정한다. `ko`는 한국어, 그 밖의 값이나 미설정 값은 현재 지원 범위의 `en`으로 정규화하고 원문 언어는 provider가 자동 감지한다.
5. Translation Worker는 commit 직후 작업을 깨우고, 놓친 작업은 기본 60초의 설정 가능한 복구 조회로 다시 찾는다. 이 조회는 일반 번역 지연이나 재시도 시각을 만드는 polling이 아니라 신호 유실·재시작의 안전망이며, 최대 시도에서 멈춘 작업도 최종 실패로 정리한다.
6. worker는 timeout·연결 오류·429·5xx에 기본 0.2초부터 짧고 설정 가능한 backoff를 적용한다. 최초 요청 포함 최대 5회와 전체 기본 5초 전달 기한 안에서 `SUCCEEDED`, `NOT_REQUIRED`, `FAILED`로 저장한다.
7. provider 호출 직전에 `attempt_count`를 증가시키며, 다음 재시도 시각은 저장하지 않고 상태와 crash 복구용 lease만 저장한다. 재시작 뒤에는 남은 횟수만 이어서 처리하고, `attempt_count=5`에서 lease가 끝난 작업은 추가 호출 없이 `FAILED`로 종결한다.
8. 최종 결과는 수신자 개인 queue로만 전달한다. `SUCCEEDED`는 원문+번역본, `NOT_REQUIRED`·`FAILED`는 원문+상태를 한 payload로 보내며 REST 이력도 같은 저장 결과를 복구한다.
9. 재시도 가능한 오류는 최초 포함 최대 5회, 전체 기본 5초 기한 안에서 처리한다. 번역 실패·지연은 원문 저장과 발신자 ACK 성공을 변경하지 않는다.
10. 기능 도입 전 과거 메시지는 일괄 번역하지 않고 도입 후 신규 메시지만 처리한다.

Worker는 단순 메모리 `@Async` 호출만으로 구성하지 않는다. MySQL의 작업 상태와 lease가 정본이므로 프로세스가 재시작돼도 미완료 작업을 다시 찾을 수 있어야 한다.

완료 조건:

- 한국인 임대인은 외국어 원문과 한국어 번역본을, 외국인 세입자는 한국어 원문과 영어 번역본을 조회할 수 있다.
- 같은 메시지·수신자·대상 언어의 번역 행은 한 개만 생긴다.
- Google 장애에도 원문 채팅은 정상 동작하고, worker 재시작 뒤 미완료 작업을 다시 처리한다.
- 번역 완료가 채팅방 재표시·마지막 메시지·삭제 경계를 변경하지 않는다.

#### 8단계: 채팅방 신고 접수하기

쉽게 말하면 사용자가 고정 사유 하나를 골라 현재 채팅방의 상대를 신고할 수 있게 만든다.

1. `V26`으로 `chat_reports`와 `chat_report_evidence`를 추가한다. 사유 문구는 프런트가 관리하므로 별도 label catalog는 만들지 않는다.
2. 신고 body는 고정 `reason` 하나만 받고 자유 입력 `detail`은 받지 않는다.
3. 서버가 인증 사용자, 채팅방의 상대 사용자와 현재 보이는 원문 증거 범위를 결정한다.
4. 최소 한 개의 TEXT 원문이 있는 방만 신고할 수 있고 최근 최대 20개 TEXT 원문을 evidence로 저장한다. BOOKING_CARD payload와 번역문은 제외한다.
5. 신고·evidence를 한 트랜잭션으로 저장하고 접수 시각에서 UTC 달력 기준 1년 뒤의 보관 만료 시각도 기록한다.
6. 신규 신고는 `201`, 같은 사용자의 같은 채팅방 재시도는 최초 사유·증거를 유지한 채 기존 신고를 `200`으로 반환한다.
7. 앱은 POST 응답의 `reportId`로 성공을 확인한다. 사용자용 사유 목록과 신고 상태 조회 API는 만들지 않는다.
8. `report -> chat::api` 공개 조회 계약과 Modulith allowed dependency를 추가한다. report 모듈에서 user API는 직접 사용하지 않는다.
9. 관리자 목록·처리·완료 API와 신고자료 만료 정리 작업은 구현하지 않는다.

완료 조건:

- 신고자와 상대 사용자를 client body 없이 서버가 정확히 결정한다.
- 삭제·차단 상태에서도 정책상 허용된 기존 채팅방 신고가 가능하다.
- 신고 접수가 자동 차단이나 채팅방 삭제를 만들지 않는다.
- 신고 UI를 운영에 공개하기 전 실제 검토가 가능한 운영 절차를 별도로 준비한다.

관리자 전용 채팅방 삭제 복구도 현재 단계에서는 구현하지 않는다. 후속 API와 제약은 [관리자 복구 설계](future/05-admin-chat-room-recovery.md)에만 기록한다.

#### 9단계: 전체 흐름을 점검하고 출시 준비하기

쉽게 말하면 신청부터 실시간 메시지·번역·삭제·차단·신고까지 실제 앱 흐름대로 확인한 뒤 출시 여부를 결정한다.

1. `./gradlew test`와 Modulith·Flyway·JPA 검증을 전체 실행한다.
2. 두 사용자 기준 문의 → 신청 카드 → 구독 → TEXT 메시지 → 번역 → 삭제/차단 → 신고 E2E를 수행한다.
3. 중복 전송, 번역 지연·실패, WebSocket 단절, 서버 재시작 시나리오를 검증한다.
4. Swagger·REST Docs의 REST 예시와 별도 STOMP protocol 예시를 프런트엔드에 전달한다.
5. 연결 수, 저장 지연, broker 실패, 재연결, 번역 backlog·실패 지표와 경보를 구성한다.
6. JWT·원문·번역문·BOOKING_CARD payload·provider payload·신고 evidence가 로그에 남지 않는지 확인한다.
7. GCP credential, quota, 비용 경보와 Google 자동 번역 표시 요건을 확인한다.
8. 단일 EC2·단일 JVM인지 확인하고 다중 인스턴스 전환 조건을 운영 문서에 남긴다.

완료 조건:

- 아래 구현 완료 기준을 모두 만족한다.
- 프런트엔드와 백엔드의 REST·STOMP contract test 결과가 일치한다.
- 관리자 API와 3개월 물리 삭제가 현재 배포 범위에 섞이지 않는다.

## 4. 운영 지표

- 현재 WebSocket session 수
- CONNECT·SUBSCRIBE·SEND 거부 수와 code
- 메시지 DB 저장 latency와 commit 후 publish 실패
- duplicate clientMessageId 처리 수
- reconnect·REST catch-up 횟수와 page 수
- 번역 처리 latency, 성공·불필요·실패·재시도 수
- 번역 작업 backlog·최대 지연과 처리 문자 수
- Event Publication Registry 미완료 건수·최대 지연

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
- 예약 직후 방·신청 카드 보장 실패를 client retry와 durable booking event가 보상한다.
- 기존 Booking 상세 데이터 조립 로직을 재사용하며 bookingId당 BOOKING_CARD는 한 장만 저장된다.
- 임차인과 임대인은 같은 카드 데이터를 역할에 맞는 UI로 표시한다.
- 로그인 완료 사용자만 REST와 STOMP를 사용할 수 있다.
- 비참여자는 roomId를 알아도 조회·구독·전송하지 못한다.
- 메시지는 MySQL commit 후에만 두 참여자에게 전달된다.
- 네트워크 재시도에도 같은 메시지가 중복 저장되지 않는다.
- 원문은 공백·줄바꿈 포함 Unicode code point 3,000자까지 허용한다.
- 받은 메시지는 사용자 언어 번역본을 우선 표시하고 원문을 확인할 수 있다.
- 내가 보낸 메시지는 원문을 우선 표시한다.
- 번역 장애가 원문 저장·발신자 ACK를 취소하지 않으며, 수신자는 최종 `FAILED`와 원문을 한 이벤트로 받는다.
- 번역 결과는 지정 수신자에게만 전달되고 REST 이력으로 복구된다.
- 후속 물리 삭제를 구현할 때 원문과 번역본의 생명주기를 함께 처리한다.
- WebSocket 단절 중 메시지를 REST catch-up으로 복구한다.
- 삭제는 상대 기록에 영향을 주지 않으며 사용자 복원 기능을 제공하지 않는다.
- 사용자에게 숨긴 채팅방 목록이나 복구 가능 상태를 제공하지 않는다.
- 같은 채팅방이 다시 표시돼도 삭제 이전 이력은 계속 숨긴다.
- 차단 이후 메시지는 저장·전달되지 않고 과거 기록은 유지된다.
- 신고는 방·신고자·상대·사유·접수 시각·원문 증거를 갖는다.
- 신고 UI와 API에 자유 입력 상세 사유가 없다.
- 신고 사유 label은 프런트가 동일 code를 기준으로 `ko/en`으로 표시한다.
- 읽음 기능, `LISTING_CARD`, `SYSTEM` 메시지, 그룹 채팅은 포함되지 않는다. `BOOKING_CARD`는 포함한다.
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
