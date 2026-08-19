# MySQL 데이터 모델과 현재 저장 흐름

이 문서는 **이번 구현 범위**의 채팅·자동 번역·사용자별 숨김·신고 접수 데이터 구조를 설명한다. 외부 REST 계약은 [02-api-contracts.md](02-api-contracts.md)를 따른다.

관리자 신고 처리, 삭제 후 3개월 만료 확정, 메시지·방의 물리 삭제는 현재 migration과 batch 범위에 포함하지 않는다. 해당 설계는 [후속 고도화 문서](future/README.md)로 분리한다.

## 1. 공통 원칙

- MySQL이 방·메시지·번역 결과·사용자별 숨김·신고 접수의 정본이다.
- 시각은 UTC `DATETIME(6)`로 저장한다.
- 상태 값은 MySQL native ENUM 대신 `VARCHAR`를 사용한다.
- user, listing처럼 다른 모듈이 소유한 ID는 값으로 참조한다.
- `chat_messages.content`는 사용자가 보낸 `TEXT`의 불변 원문이며 기록·신고의 정본이다.
- `BOOKING_CARD`는 기존 Booking 데이터를 재사용해 만든 서버 메시지이며 신청 시점의 구조화 값을 `payload`에 저장한다.
- 자동 번역은 사용자·대상 언어별 파생 데이터이며 원문을 덮어쓰지 않는다.
- 방과 과거 메시지의 가시성은 `chat_room_members`에서 사용자별로 관리한다.
- 읽음 기능은 이번 범위가 아니므로 `last_read_message_id`와 `unread_count`를 만들지 않는다.
- 이번 구현은 메시지를 물리 삭제하지 않는다. 사용자의 삭제는 복원 기능이 없는 사용자별 논리 삭제(숨김)다.

## 2. 채팅 테이블

### 2.1 `chat_rooms`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 채팅방 PK |
| `category` | 이번 범위에서는 `LANDLORD` |
| `listing_id` | 매물 ID |
| `tenant_id` | 세입자 ID |
| `landlord_id` | 임대인 ID |
| `listing_snapshot` | 생성 당시 매물 제목·대표 이미지·주소 JSON |
| `last_message_id` | 현재 마지막 메시지 ID, 빈 방이면 null |
| `last_message_at` | 현재 마지막 메시지 시각, 빈 방이면 null |
| `created_at`, `updated_at` | 생성·변경 시각 |

필수 제약·인덱스:

```sql
UNIQUE (listing_id, tenant_id, landlord_id)
CHECK (tenant_id <> landlord_id)
INDEX (tenant_id, last_message_at, id)
INDEX (landlord_id, last_message_at, id)
```

`room_offer_id`는 방의 유일키에 포함하지 않는다. 매물이 나중에 비공개·삭제돼도 기존 방 헤더는 `listing_snapshot`으로 표시한다.

### 2.2 `chat_room_members`

방을 만들 때 tenant와 landlord의 두 행을 한 트랜잭션으로 생성한다.

| 컬럼 | 설명 |
| --- | --- |
| `id` | member PK |
| `chat_room_id` | 방 ID |
| `user_id` | 참여 사용자 ID |
| `counterpart_id` | 1:1 상대 사용자 ID |
| `member_role` | `TENANT`, `LANDLORD` |
| `room_hidden_at` | 현재 내 목록에서 숨긴 시각, 보이면 null |
| `history_hidden_through_message_id` | 이 사용자에게 숨길 마지막 과거 messageId |
| `delete_requested_at` | 최근 삭제 요청 시각 |
| `created_at`, `updated_at` | 생성·변경 시각 |

```sql
UNIQUE (chat_room_id, user_id)
INDEX (user_id, room_hidden_at, chat_room_id)
```

`room_hidden_at`은 방 목록에 보이는지를, `history_hidden_through_message_id`는 메시지 조회에서 어디까지 숨길지를 뜻한다. 두 값이 분리되어 있으므로 새 메시지로 방이 다시 나타나도 예전에 숨긴 메시지는 복원되지 않는다.

사용자가 삭제한 채팅방과 과거 이력을 되돌리는 token이나 복원 API는 만들지 않는다. 같은 매물에서 다시 문의하거나 실제 새 메시지를 받아 채팅방이 다시 표시돼도 `history_hidden_through_message_id`는 낮아지지 않는다.

### 2.3 `chat_messages`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 서버 `messageId`, PK |
| `chat_room_id` | 방 ID |
| `sender_id` | `TEXT`는 인증 Principal의 발신자, 서버 카드면 null |
| `type` | `TEXT`, `BOOKING_CARD` |
| `content` | 사용자가 보낸 TEXT 원문, 카드면 null |
| `payload` | `BOOKING_CARD`의 매물·신청자·입주 조건·금액 JSON, TEXT면 null |
| `booking_id` | 카드 생성 출처인 신청 ID, TEXT면 null |
| `client_message_id` | TEXT에서 프런트엔드가 만든 UUID, 카드면 null; `BINARY(16)` 권장 |
| `sent_at` | 서버 저장 시각 |

```sql
UNIQUE (chat_room_id, sender_id, client_message_id)
UNIQUE (chat_room_id, booking_id)
INDEX (chat_room_id, id DESC)
```

두 타입의 값은 다음처럼 구분한다.

| 타입 | `sender_id` | `content` | `client_message_id` | `booking_id`·`payload` |
| --- | --- | --- | --- | --- |
| `TEXT` | 필수 | 필수 | 필수 | null |
| `BOOKING_CARD` | null | null | null | 필수 |

메시지는 저장 후 수정하지 않는다. TEXT 본문은 애플리케이션에서 공백·줄바꿈을 포함해 Unicode code point 3,000자로 제한하고 검색 인덱스는 만들지 않는다. 카드 payload는 기존 `BookingDetailResponse`와 `LandlordBookingDetailResponse`를 만드는 조회·계산 로직을 채팅용 공개 `BookingCardView`로 재사용해 만든다.

카드에 저장한 신청자 이름·성별·국가·이메일은 탈퇴 후에도 원래 값으로 남겨 두지 않는다. user 탈퇴·익명화 이벤트를 받으면 해당 사용자의 `BOOKING_CARD` payload도 ADR-0014의 익명화 규칙에 맞춰 갱신한다. 신청 조건과 금액처럼 거래 사실에 필요한 비식별 값은 별도 보존 정책을 따른다.

### 2.4 `chat_message_translations`

받은 `TEXT` 메시지의 사용자별 번역 결과이자 서버 재시작 후에도 복구할 수 있는 번역 작업이다. `BOOKING_CARD`에는 번역 행을 만들지 않는다.

| 컬럼 | 설명 |
| --- | --- |
| `id` | 번역 작업 PK |
| `message_id` | 원문 `chat_messages.id` |
| `recipient_user_id` | 번역본을 받을 1:1 수신자 |
| `target_language` | 원문 저장 시점 수신자의 `users.lang` snapshot |
| `detected_source_language` | Google이 감지한 원문 언어, 처리 전에는 null |
| `status` | `PENDING`, `PROCESSING`, `SUCCEEDED`, `NOT_REQUIRED`, `FAILED` |
| `translated_content` | 성공한 번역본, 그 외에는 null |
| `provider` | `GOOGLE_CLOUD_TRANSLATION` |
| `model` | 초기값 `NMT` |
| `attempt_count` | 호출 시도 횟수 |
| `lease_until` | Worker 중단 시 작업을 다시 회수하기 위한 기한 |
| `last_failure_code` | 원문을 포함하지 않는 오류 분류 code |
| `translated_at` | 성공 또는 번역 불필요 확정 시각 |
| `created_at`, `updated_at` | 생성·변경 시각 |

```sql
UNIQUE (message_id, recipient_user_id, target_language)
INDEX (status, id)
INDEX (status, lease_until, id)
INDEX (recipient_user_id, message_id)
```

첫 구현의 지원 언어는 `ko`, `en`이다. 사용자가 언어를 바꾸면 이후 새 메시지부터 적용하며 과거 대화를 자동으로 일괄 재번역하지 않는다.

## 3. 자동 번역 저장 흐름

1. 신규 원문과 수신자용 `PENDING` 번역 행을 같은 트랜잭션에 저장한다.
2. `PENDING`은 **원문 전송 대기**가 아니라 **번역 처리 대기**라는 뜻이다. 이때 `translated_content`는 null이다.
3. 원문 커밋 뒤 Translation Worker가 짧은 트랜잭션으로 작업을 `PROCESSING` 상태로 확보한다.
4. DB lock을 풀고 Google Cloud Translation에 원문과 대상 언어를 보낸다.
5. 성공하면 번역본과 감지 언어를 저장하고 수신자 개인 STOMP queue에 결과를 보낸다.
6. 원문과 대상 언어가 같으면 `NOT_REQUIRED`로 끝낸다.
7. provider 호출 직전에 `attempt_count`를 증가시켜 커밋한다.
8. timeout·연결 오류·429·5xx는 같은 Worker 작업 안에서 예를 들어 0.5초, 1초, 2초, 4초의 짧은 간격을 두고 최초 요청을 포함해 최대 5회 호출한다.
9. 별도의 다음 재시도 시각은 저장하지 않으며 5회 모두 실패하면 `FAILED`로 끝낸다. 잘못된 요청이나 인증·설정 오류는 즉시 `FAILED`로 끝낼 수 있다.
10. Worker가 중단되면 `lease_until`이 지난 `PROCESSING` 작업을 다시 가져와 `5 - attempt_count`의 남은 횟수만 이어서 처리한다.

Worker는 Google을 대신해 번역하는 구성 요소가 아니다. 번역할 작업을 찾고, Google API를 호출하고, 결과 저장·재시도·수신자 전달을 관리하는 백엔드 컴포넌트다. 작업은 커밋 직후 바로 깨운다. 기본 60초의 복구 조회는 신호 유실이나 서버 재시작 뒤 남은 `PENDING`·lease 만료 작업을 찾는 안전망일 뿐, 일반 재시도 시각을 관리하는 polling이 아니다.

번역 완료·재시도·실패는 다음 값을 변경하지 않는다.

- 원문과 `clientMessageId` 멱등성 판정
- `chat_rooms.last_message_id`, `last_message_at`
- 방 재표시와 메시지 숨김 경계
- 메시지 cursor와 신고의 `evidence_through_message_id`

같은 `clientMessageId` 재시도는 새 번역 행이나 두 번째 Google 요청을 만들지 않는다. 메시지 이력에서는 사용자의 숨김 경계를 원문과 번역본에 동일하게 적용한다. 신고 evidence와 hash는 항상 원문으로 만든다.

`BOOKING_CARD`의 이름·국적·입주일·금액 같은 구조화 값은 Google 번역에 보내지 않는다. 카드의 “이름”, “입주희망일” 같은 고정 라벨은 앱이 사용자 언어 `ko/en`에 맞춰 표시한다.

## 4. 신고 접수 테이블

### 4.1 `report_reason_labels`

| 컬럼 | 설명 |
| --- | --- |
| `reason_code` | 언어 불변 code |
| `language` | `ko`, `en` |
| `label` | 사용자 표시 문구 |
| `display_order` | 화면 순서 |
| `active` | 노출 여부 |

```sql
PRIMARY KEY (reason_code, language)
```

신고 행에는 label이 아니라 code만 저장한다. 고정 code는 `ABUSE`, `ILLEGAL_CONTENT`, `SEXUAL_CONTENT`, `PERSONAL_INFORMATION`, `SPAM`, `ETC`다.

### 4.2 `reports`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 신고 번호 |
| `reporter_id` | JWT에서 얻은 신고자 ID |
| `reported_user_id` | 방에서 서버가 도출한 상대 ID |
| `target_type` | `CHAT_ROOM` |
| `target_id` | 채팅방 ID |
| `reason_code` | 고정 신고 사유 |
| `status` | 현재 범위에서는 `RECEIVED` |
| `received_at` | 서버 접수 시각 |
| `evidence_through_message_id` | 접수 시점 마지막 메시지 ID |
| `created_at`, `updated_at` | 생성·변경 시각 |

```sql
UNIQUE (reporter_id, target_type, target_id)
INDEX (target_type, target_id, received_at)
```

자유 입력 상세 사유 컬럼은 만들지 않는다. 같은 사용자가 같은 방을 반복 신고하면 기존 접수 결과를 반환한다. 운영자가 신고를 종결한 뒤 재신고를 허용하는 규칙과 DB 제약 변경은 [후속 관리자 설계](future/01-admin-report-management.md)에서 다룬다.

### 4.3 `report_evidence`

| 컬럼 | 설명 |
| --- | --- |
| `id` | evidence PK |
| `report_id` | 신고 ID |
| `schema_version` | snapshot JSON schema 버전 |
| `evidence_through_message_id` | 접수 증거 상한 |
| `snapshot` | 방·참여자·매물·최근 원문 메시지의 JSON snapshot |
| `captured_at` | snapshot 생성 시각 |
| `content_hash` | 원문 증거 무결성 확인용 hash |

```sql
UNIQUE (report_id)
```

- 접수 시 최근 최대 20개 텍스트 원문을 저장한다.
- `BOOKING_CARD` payload는 텍스트 원문 증거 최대 20건에 포함하지 않는다.
- 클라이언트가 보낸 본문은 증거로 신뢰하지 않고 MySQL 원문으로 snapshot을 만든다.
- 전체 채팅을 제한 없이 복제하지 않는다.
- 이후 사용자 방 숨김과 관계없이 접수된 evidence는 유지하고 사용자 API로 원문을 노출하지 않는다.

운영자 상태 이력, 최종 증거 버전, 처리 완료 시각, 보관 만료일과 법적 hold는 [후속 관리자 설계](future/01-admin-report-management.md)로 분리한다.

## 5. 사용자별 채팅방 숨김과 삭제 경계

| 상황 | 현재 구현의 처리 |
| --- | --- |
| 처음 삭제 | 채팅방을 요청자 목록에서 숨기고 현재 마지막 messageId를 숨김 경계로 저장한 뒤 `204` 반환 |
| 같은 숨김 상태에서 DELETE 재시도 | 상태와 삭제 시각을 변경하지 않고 `204` 반환 |
| 다시 표시된 뒤 재삭제 | 숨김 경계를 현재 마지막 messageId까지 앞으로 이동하고 새 삭제 시각 기록 |
| 실제 새 메시지 수신 | 방만 다시 표시하고 기존 숨김 경계는 유지 |
| 새 신청 카드 최초 저장 | 실제 신규 활동으로 처리하고 필요하면 방을 다시 표시하되 기존 숨김 경계는 유지 |
| 같은 매물에서 직접 문의 | 같은 `roomId`를 다시 표시하고 기존 숨김 경계는 유지 |
| 중복 메시지 재전송·번역 완료·동일 bookingId 이벤트 재처리 | 방 표시 상태를 변경하지 않음 |

숨김 경계는 다음처럼 단조 증가시킨다.

```text
historyHiddenThroughMessageId = max(기존 숨김 경계, 삭제 시점의 마지막 messageId)
```

메시지 조회는 `messageId > history_hidden_through_message_id` 범위만 반환한다. 따라서 채팅방이 다시 나타나도 이전에 숨긴 대화는 보이지 않고 새 메시지만 보인다. 사용자용 Undo·복원 기능은 제공하지 않는다.

현재 구현에서는 숨긴 원문과 번역본을 MySQL에서 물리 삭제하지 않는다. 삭제 후 3개월이 지난 범위를 자동 확정하고 양쪽 공통 범위를 실제로 지우는 작업은 [후속 보존 설계](future/02-retention-and-physical-deletion.md)에서 다룬다.

## 6. `BookingCreatedEvent` publication

예약 저장 후 방과 신청 카드를 신뢰성 있게 보장하려면 Spring Modulith Event Publication Registry의 MySQL 테이블을 Flyway로 관리한다.

- booking row와 event publication을 같은 booking transaction에 저장
- event에 `eventId`, `bookingId`, `occurredAt`, `listingId`, `tenantId`, `landlordId` 포함
- chat listener 성공 시 publication 완료 처리
- 실패 publication은 backoff 후 재처리
- 방 UNIQUE로 문의·신청이 같은 `roomId`에 수렴
- 기존 Booking 상세 조립 로직을 공개 `BookingCardView`로 재사용해 카드 payload 생성
- `(chat_room_id, booking_id)` UNIQUE로 같은 신청 카드 중복 저장 방지
- listener 성공 조건은 방과 `BOOKING_CARD`가 모두 저장된 상태
- 동일 event·booking 재처리는 카드, broadcast, 마지막 메시지와 방 재표시를 다시 만들지 않음

이 업무 이벤트 내구성은 실시간 STOMP Simple Broker와 별개다.
