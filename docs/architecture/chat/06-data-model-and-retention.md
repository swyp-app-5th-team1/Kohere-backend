# MySQL 데이터 모델과 현재 저장 흐름

이 문서는 채팅·자동 번역·사용자별 숨김·신고 접수 데이터 구조를 설명한다. 현재 `V24`는 `chat_rooms`·`chat_room_members`·`chat_messages`, `V26`은 `chat_reports`·`chat_report_evidence`, `V27`은 `chat_message_translations`를 물리화한다. 문의서 보완은 구현 시점 최신 번호 다음 migration(현재 예상 `V29`)으로 기존 `chat_messages`를 확장하며 기존 행을 다시 만들지 않는다. 외부 REST 계약은 [02-api-contracts.md](02-api-contracts.md)를 따른다.

관리자 신고 처리, 삭제 후 3개월 만료 확정, 메시지·방의 물리 삭제는 현재 migration과 batch 범위에 포함하지 않는다. 해당 설계는 [후속 고도화 문서](future/README.md)로 분리한다.

## 1. 공통 원칙

- MySQL의 `V24` 세 테이블이 방·사용자별 숨김·공유 메시지의 현재 정본이다.
- 시각은 UTC `DATETIME(6)`로 저장한다.
- 상태 값은 MySQL native ENUM 대신 `VARCHAR`를 사용한다.
- user, listing, booking처럼 다른 모듈이 소유한 ID뿐 아니라 chat 테이블 사이 ID도 값으로 참조하고 FK를 만들지 않는다. 존재·참여 권한과 저장 순서는 애플리케이션 트랜잭션으로 검증하며, DB UNIQUE/CHECK가 중복과 잘못된 필드 조합을 최종 차단한다.
- `chat_messages`는 전송 중 임시 큐나 사용자별 사본이 아니라 모든 채팅방에서 저장이 완료된 메시지의 공유 정본이다.
- `chat_messages.content`는 사용자가 보낸 `TEXT`의 불변 원문이며 기록·신고의 정본이다.
- `INQUIRY_CARD`는 문의로 새 방을 만들 때 공개 Listing 데이터로 함께 만드는 서버 메시지이며 문의 시점의 매물 요약을 `inquiry_payload`에 저장한다.
- `BOOKING_CARD`는 기존 Booking 데이터를 재사용해 만든 서버 메시지이며 신청 시점의 구조화 값을 `payload`에 저장한다.
- 자동 번역은 사용자·대상 언어별 파생 데이터이며 원문을 덮어쓰지 않는다.
- 방과 과거 메시지의 가시성은 `chat_room_members`에서 사용자별로 관리한다.
- 읽음 기능은 이번 범위가 아니므로 `last_read_message_id`와 `unread_count`를 만들지 않는다.
- 이번 구현은 메시지를 물리 삭제하지 않는다. 사용자의 삭제는 복원 기능이 없는 사용자별 논리 삭제(숨김)다.

## 2. 채팅 테이블

### 2.1 `chat_rooms`

| 컬럼 | MySQL 타입 | 설명 |
| --- | --- | --- |
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 채팅방 PK |
| `category` | `VARCHAR(16) NOT NULL DEFAULT 'LANDLORD'` | 이번 범위에서는 `LANDLORD` |
| `listing_id` | `VARCHAR(24) NOT NULL` | MongoDB 매물 ObjectId 문자열 |
| `tenant_id` | `BIGINT NOT NULL` | 세입자 `users.id` 값 참조 |
| `landlord_id` | `BIGINT NOT NULL` | 임대인 `users.id` 값 참조 |
| `listing_snapshot` | `JSON NOT NULL` | 생성 당시 매물 제목·주소 |
| `last_message_id` | `BIGINT NULL` | 현재 마지막 메시지 ID, 빈 방이면 null |
| `last_message_at` | `DATETIME(6) NULL` | 현재 마지막 메시지 시각, 빈 방이면 null |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | 생성·변경 시각 |

필수 제약·인덱스:

```sql
CONSTRAINT uq_chat_rooms_listing_participants
  UNIQUE (listing_id, tenant_id, landlord_id)
CONSTRAINT ck_chat_rooms_distinct_participants
  CHECK (tenant_id <> landlord_id)
CONSTRAINT ck_chat_rooms_category
  CHECK (category = 'LANDLORD')
CONSTRAINT ck_chat_rooms_listing_snapshot_object
  CHECK (JSON_TYPE(listing_snapshot) = 'OBJECT')
CONSTRAINT ck_chat_rooms_last_message_pair
  CHECK (
    (last_message_id IS NULL AND last_message_at IS NULL)
    OR (last_message_id IS NOT NULL AND last_message_at IS NOT NULL)
  )
INDEX idx_chat_rooms_tenant_last_message
  (tenant_id, last_message_at DESC, id DESC)
INDEX idx_chat_rooms_landlord_last_message
  (landlord_id, last_message_at DESC, id DESC)
```

`room_offer_id`는 방의 유일키에 포함하지 않는다. 문의와 신청은 `(listing_id, tenant_id, landlord_id)`가 같으면 하나의 채팅방을 함께 사용한다. 매물이 나중에 비공개·삭제돼도 기존 방 헤더는 `listing_snapshot`으로 표시한다.

`last_message_id`는 메시지를 하나만 보관한다는 뜻이 아니다. 모든 메시지는 `chat_messages`에 계속 저장하고, 목록에서 가장 최근 메시지를 빠르게 찾기 위한 포인터만 방에 둔다. `last_message_at`과 항상 함께 채우거나 함께 비워야 미리보기와 정렬 기준이 어긋나지 않는다.

두 참여자 인덱스는 먼저 로그인 사용자의 방으로 범위를 줄인다. 빈 방도 생성 시각에 맞춰 섞는 최종 정렬식 `COALESCE(last_message_at, created_at)`은 함수 계산이므로 현재 인덱스만으로 정렬 전체를 해결하지 못할 수 있다. 사용자별 방 수가 작은 초기 범위에서는 좁혀진 결과의 정렬을 허용하고, 실제 방 수·쿼리 계획이 커지면 별도 `activity_at` 컬럼이나 함수 인덱스를 측정 후 추가한다.

### 2.2 `chat_room_members`

방을 만들 때 tenant와 landlord의 두 행을 한 트랜잭션으로 생성한다.

| 컬럼 | MySQL 타입 | 설명 |
| --- | --- | --- |
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | member PK |
| `chat_room_id` | `BIGINT NOT NULL` | `chat_rooms.id` 값 참조 |
| `user_id` | `BIGINT NOT NULL` | 참여 사용자 `users.id` |
| `counterpart_id` | `BIGINT NOT NULL` | 1:1 상대 `users.id` |
| `member_role` | `VARCHAR(16) NOT NULL` | `TENANT`, `LANDLORD` |
| `room_hidden_at` | `DATETIME(6) NULL` | 현재 내 목록에서 숨긴 시각, 보이면 null |
| `history_hidden_through_message_id` | `BIGINT NOT NULL DEFAULT 0` | 이 사용자에게 숨길 마지막 과거 messageId, `0`이면 숨긴 이력 없음 |
| `delete_requested_at` | `DATETIME(6) NULL` | 최근 삭제 요청 시각 |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | 생성·변경 시각 |

```sql
CONSTRAINT uq_chat_room_members_room_user
  UNIQUE (chat_room_id, user_id)
CONSTRAINT ck_chat_room_members_distinct_counterpart
  CHECK (user_id <> counterpart_id)
CONSTRAINT ck_chat_room_members_history_boundary
  CHECK (history_hidden_through_message_id >= 0)
CONSTRAINT ck_chat_room_members_role
  CHECK (member_role IN ('TENANT', 'LANDLORD'))
INDEX idx_chat_room_members_user_visibility
  (user_id, room_hidden_at, chat_room_id)
```

`room_hidden_at`은 방 목록에 보이는지를, `history_hidden_through_message_id`는 메시지 조회에서 어디까지 숨길지를 뜻한다. 숨긴 적이 없을 때 경계는 `0`이다. null 대신 `0`을 쓰면 `messageId > history_hidden_through_message_id` 조회에서 SQL null 비교 때문에 정상 메시지까지 전부 누락되는 일을 피할 수 있다. 두 값이 분리되어 있으므로 새 메시지로 방이 다시 나타나도 예전에 숨긴 메시지는 복원되지 않는다.

사용자가 삭제한 채팅방과 과거 이력을 되돌리는 token이나 복원 API는 만들지 않는다. 같은 매물에서 다시 문의하거나 실제 새 메시지를 받아 채팅방이 다시 표시돼도 `history_hidden_through_message_id`는 낮아지지 않는다.

### 2.3 `chat_messages`

| 컬럼 | MySQL 타입 | 설명 |
| --- | --- | --- |
| `id` | `BIGINT NOT NULL AUTO_INCREMENT` | 서버 `messageId`, PK |
| `chat_room_id` | `BIGINT NOT NULL` | `chat_rooms.id` 값 참조 |
| `sender_id` | `BIGINT NULL` | `TEXT`는 인증 Principal의 발신자, 서버 카드면 null |
| `type` | `VARCHAR(32) NOT NULL` | `TEXT`, `INQUIRY_CARD`, `BOOKING_CARD` |
| `content` | `TEXT NULL` | 사용자가 보낸 TEXT 원문, 카드면 null |
| `payload` | `JSON NULL` | `BOOKING_CARD`의 매물·신청자·입주 조건·금액, TEXT면 null |
| `inquiry_payload` | `JSON NULL` | `INQUIRY_CARD`의 매물 요약, 다른 타입이면 null |
| `booking_id` | `BIGINT NULL` | 카드 생성 출처인 신청 ID, TEXT면 null |
| `client_message_id` | `BINARY(16) NULL` | TEXT에서 프런트엔드가 만든 UUID, 카드면 null |
| `sent_at` | `DATETIME(6) NOT NULL` | 서버 저장 시각 |

```sql
CONSTRAINT uq_chat_messages_client_message
  UNIQUE (chat_room_id, sender_id, client_message_id)
CONSTRAINT uq_chat_messages_booking
  UNIQUE (chat_room_id, booking_id)
CONSTRAINT ck_chat_messages_type
  CHECK (type IN ('TEXT', 'INQUIRY_CARD', 'BOOKING_CARD'))
CONSTRAINT ck_chat_messages_type_fields
  CHECK (
    (
      type = 'TEXT'
      AND sender_id IS NOT NULL
      AND content IS NOT NULL
      AND CHAR_LENGTH(content) BETWEEN 1 AND 3000
      AND client_message_id IS NOT NULL
      AND booking_id IS NULL
      AND payload IS NULL
      AND inquiry_payload IS NULL
    )
    OR
    (
      type = 'INQUIRY_CARD'
      AND sender_id IS NULL
      AND content IS NULL
      AND client_message_id IS NULL
      AND booking_id IS NULL
      AND payload IS NULL
      AND inquiry_payload IS NOT NULL
      AND JSON_TYPE(inquiry_payload) = 'OBJECT'
    )
    OR
    (
      type = 'BOOKING_CARD'
      AND sender_id IS NULL
      AND content IS NULL
      AND client_message_id IS NULL
      AND booking_id IS NOT NULL
      AND payload IS NOT NULL
      AND inquiry_payload IS NULL
      AND JSON_TYPE(payload) = 'OBJECT'
    )
  )
INDEX idx_chat_messages_room_id_desc (chat_room_id, id DESC)
```

`client_message_id`는 권장 사항이 아니라 TEXT에 필수인 실제 `BINARY(16)` 컬럼이다. 프런트엔드가 전송 전에 UUID를 만들고, 같은 요청을 재전송했을 때 서버가 두 번째 메시지를 저장하지 않는 멱등 키로 사용한다.

세 타입의 값은 다음처럼 구분한다.

| 타입 | `sender_id` | `content` | `client_message_id` | `inquiry_payload` | `booking_id`·`payload` |
| --- | --- | --- | --- | --- | --- |
| `TEXT` | 필수 | 1~3,000자 필수 | 필수 | null | null |
| `INQUIRY_CARD` | null | null | null | 필수 | null |
| `BOOKING_CARD` | null | null | null | null | 필수 |

`chat_messages`는 임차인용·임대인용 행을 따로 만들지 않는다. 한 메시지를 한 번 저장하고 `chat_room_id`로 소속 방, `sender_id`로 TEXT 발신자를 구분한다. 수신자는 `chat_room_members`에서 서버가 결정하므로 `receiver_id` 컬럼은 없다.

메시지의 ID·방·종류·전송 시각과 TEXT 원문은 저장 후 수정하지 않는다. 다만 `BOOKING_CARD` 안의 신청자 개인정보는 아래 익명화 규칙이 적용될 때에만 가린다. DB CHECK는 TEXT `content`를 1~3,000자로 제한하고 타입별 필드를 배타적으로 강제한다. 따라서 TEXT에 카드 payload가 섞이거나, 서버 카드가 사용자 발신자로 저장되거나, 본문 없는 TEXT가 정본에 남지 않는다. 현재 허용 타입은 `TEXT`, `INQUIRY_CARD`, `BOOKING_CARD`이며 일반 `LISTING_CARD`나 `SYSTEM` 타입은 없다. `InquiryCardPayload`는 대표 이미지 URL, 매물 제목, city·district·매물 유형 code, 활성 방 최소·최대 월세, listingId를 보존하고, `BookingCardPayload`는 매물·신청자·객실·입주 조건·금액 스냅샷을 보존한다.

`INQUIRY_CARD`는 새 문의 방·두 참여자와 같은 생성 트랜잭션에서만 저장한다. `(listing_id, tenant_id, landlord_id)` 방 UNIQUE에 의해 동시 문의 중 한 요청만 신규 방과 문의서를 커밋하고, 충돌한 요청은 기존 roomId를 반환하므로 문의서가 두 장 생기지 않는다. 기존 방을 반환하는 경로는 문의 메시지를 INSERT하지 않는다.

`(chat_room_id, sender_id, client_message_id)` UNIQUE는 TEXT 재전송 중복을 막고, `(chat_room_id, booking_id)` UNIQUE는 같은 신청 이벤트가 재처리될 때 카드가 두 장 생기는 것을 막는다. `(chat_room_id, id DESC)` 인덱스는 과거 페이지 조회와 재연결 누락 보충에서 같은 messageId 범위 조회를 빠르게 한다.

카드에 저장한 신청자 이름·성별·국가·이메일은 탈퇴 후에도 원래 값으로 남겨 두지 않는다. user 탈퇴·익명화 이벤트를 받으면 해당 사용자의 `BOOKING_CARD` payload도 ADR-0014의 익명화 규칙에 맞춰 갱신한다. 신청 조건과 금액처럼 거래 사실에 필요한 비식별 값은 별도 보존 정책을 따른다.

### 2.4 `chat_message_translations`

받은 `TEXT` 메시지의 사용자별 번역 결과이자 서버 재시작 후에도 복구할 수 있는 번역 작업이다. `INQUIRY_CARD`와 `BOOKING_CARD`에는 번역 행을 만들지 않는다.

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
INDEX (status, lease_until, id)
INDEX (recipient_user_id, message_id)
```

첫 구현의 지원 언어는 `ko`, `en`이다. 사용자가 언어를 바꾸면 이후 새 메시지부터 적용하며 과거 대화를 자동으로 일괄 재번역하지 않는다.

## 3. 자동 번역 저장 흐름

1. 신규 원문과 수신자용 `PENDING` 번역 행을 같은 트랜잭션에 저장한다.
2. `PENDING`은 **원문 전송 대기**가 아니라 **번역 처리 대기**라는 뜻이다. 이때 `translated_content`는 null이다.
3. 원문 커밋 뒤 Translation Worker가 짧은 트랜잭션으로 작업을 `PROCESSING` 상태로 확보한다.
4. DB lock을 풀고 Google Cloud Translation에 원문과 대상 언어를 보낸다.
5. 성공하면 번역본과 감지 언어를 저장하고 수신자 개인 STOMP queue에 원문과 번역본을 한 이벤트로 보낸다.
6. 원문과 대상 언어가 같으면 `NOT_REQUIRED`로 끝낸다.
7. provider 호출 직전에 `attempt_count`를 증가시켜 커밋한다.
8. timeout·연결 오류·429·5xx는 같은 Worker 작업 안에서 기본 0.2초부터 짧게 기다리며 최초 요청을 포함해 최대 5회 호출한다. 전체 기본 5초 전달 기한을 넘겨 새 호출을 시작하지 않는다.
9. 별도의 다음 재시도 시각은 저장하지 않으며 5회 또는 전달 기한까지 성공하지 못하면 `FAILED`로 끝낸다. 잘못된 요청이나 인증·설정 오류는 즉시 `FAILED`로 끝낼 수 있다.
10. Worker가 중단되면 `lease_until`이 지난 `PROCESSING` 작업을 다시 가져와 `5 - attempt_count`의 남은 횟수만 이어서 처리한다.

Worker는 Google을 대신해 번역하는 구성 요소가 아니다. 번역할 작업을 찾고, Google API를 호출하고, 결과 저장·재시도·수신자 전달을 관리하는 백엔드 컴포넌트다. 작업은 커밋 직후 바로 깨운다. 기본 60초의 복구 조회는 신호 유실이나 서버 재시작 뒤 남은 `PENDING`·lease 만료 작업을 찾는 안전망일 뿐, 일반 재시도 시각을 관리하는 polling이 아니다. 원문을 공용 topic으로 먼저 보내지 않으므로 수신자는 최종 상태가 정해진 뒤 원문과 번역 결과를 한 payload로 받는다. 실패 상태에도 원문이 포함된다.

번역 완료·재시도·실패는 다음 값을 변경하지 않는다.

- 원문과 `clientMessageId` 멱등성 판정
- `chat_rooms.last_message_id`, `last_message_at`
- 방 재표시와 메시지 숨김 경계
- 메시지 cursor와 신고의 `evidence_through_message_id`

같은 `clientMessageId` 재시도는 새 번역 행이나 두 번째 Google 요청을 만들지 않는다. 메시지 이력에서는 사용자의 숨김 경계를 원문과 번역본에 동일하게 적용한다. 신고 evidence와 hash는 항상 원문으로 만든다.

`INQUIRY_CARD`와 `BOOKING_CARD`의 구조화 값은 Google 번역에 보내지 않는다. 문의서의 city·district·매물 유형 code와 두 카드의 고정 라벨은 앱이 사용자 언어 `ko/en`에 맞춰 표시한다.

## 4. 신고 접수 테이블

신고 사유는 프런트가 `ko/en` 문구로 표시하는 고정 enum이다. 서버는 표시 label을 저장하거나 사유 목록 API를 제공하지 않고 언어와 무관한 code만 `chat_reports.reason`에 저장한다.

### 4.1 `chat_reports`

| 컬럼 | MySQL 타입 | 설명 |
| --- | --- | --- |
| `id` | `BIGINT` | 신고 번호, auto increment |
| `chat_room_id` | `BIGINT` | 신고한 1:1 채팅방 ID |
| `reporter_id` | `BIGINT` | JWT에서 얻은 신고자 ID |
| `reported_user_id` | `BIGINT` | 방에서 서버가 도출한 상대 ID |
| `reason` | `VARCHAR(64)` | 고정 신고 사유 code |
| `status` | `VARCHAR(32)` | 현재는 `RECEIVED`만 허용 |
| `evidence_through_message_id` | `BIGINT` | 신고자에게 보이는 마지막 증거 TEXT ID |
| `received_at` | `DATETIME(6)` | 서버 접수 시각 |
| `retention_expires_at` | `DATETIME(6)` | 접수 시각에서 UTC 달력 기준 1년 뒤 |
| `created_at`, `updated_at` | `DATETIME(6)` | 생성·변경 시각 |

```sql
UNIQUE (reporter_id, chat_room_id)
INDEX (received_at DESC, id DESC)
INDEX (reported_user_id, received_at DESC, id DESC)
INDEX (retention_expires_at, id)
```

고정 code는 `ABUSE_HARASSMENT_DISCRIMINATION`, `ILLEGAL_CONTENT`, `SEXUAL_INAPPROPRIATE_CONTENT`, `PERSONAL_INFORMATION`, `SPAM`, `OTHER`다. 자유 입력 상세 사유 컬럼은 만들지 않는다. 같은 사용자가 같은 방을 반복 신고하면 첫 행의 사유와 증거를 변경하지 않고 기존 접수 결과를 반환한다.

### 4.2 `chat_report_evidence`

| 컬럼 | MySQL 타입 | 설명 |
| --- | --- | --- |
| `id` | `BIGINT` | evidence PK, auto increment |
| `report_id` | `BIGINT` | `chat_reports.id` 값 참조 |
| `schema_version` | `INT` | 현재 snapshot JSON schema 버전 `1` |
| `evidence_through_message_id` | `BIGINT` | 접수 증거 상한 TEXT ID |
| `snapshot` | `JSON` | 방·참여자·매물·최근 원문 TEXT의 스냅샷 |
| `content_hash` | `VARCHAR(64)` | snapshot record의 SHA-256 검증값 |
| `captured_at` | `DATETIME(6)` | snapshot 생성 시각 |
| `created_at` | `DATETIME(6)` | DB 생성 시각 |

```sql
UNIQUE (report_id)
INDEX (captured_at DESC, id DESC)
```

- 접수 시 신고자에게 현재 보이는 최근 TEXT 원문을 최대 20개 저장한다.
- `INQUIRY_CARD`, `BOOKING_CARD` payload와 자동 번역문은 증거에 포함하지 않는다.
- 클라이언트가 증거를 보내지 않으며 MySQL의 `chat_messages.content`로 snapshot을 만든다.
- 신고 기본 행과 evidence 행은 같은 트랜잭션으로 저장해 한쪽만 남지 않게 한다.
- 사용자에게는 evidence를 반환하지 않는다. 이후 사용자가 방을 숨겨도 이미 접수된 evidence는 독립적으로 유지한다.

두 테이블은 목록용 작은 접수 정보와 민감한 대형 JSON을 분리한다. 그래서 후속 관리자 목록은 증거 JSON을 매번 읽지 않아도 되고 증거 접근·파기 정책도 별도로 적용할 수 있다. FK는 기존 저장소 관례대로 만들지 않으며 애플리케이션 트랜잭션과 UNIQUE가 관계를 보장한다.

운영자 상태 변경, 법적 hold와 실제 만료 정리 작업은 [후속 관리자 설계](future/01-admin-report-management.md)로 분리한다.

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

예약 저장 후 방과 신청 카드를 신뢰성 있게 보장하기 위해 V25의 Spring Modulith JPA `event_publication` 테이블을 Flyway로 관리한다.

- booking row와 event publication을 같은 booking transaction에 저장
- event에 `eventId`, `bookingId`, `occurredAt`, `listingId`, `tenantId`, `landlordId`와 신청 시점 카드 사본 포함
- chat listener 성공 시 `completion-mode=delete`로 publication 삭제
- listener 실패나 서버 종료로 미완료인 publication은 다음 서버 기동 때 다시 전달
- 방 UNIQUE로 문의·신청이 같은 `roomId`에 수렴
- Booking Service가 이미 검증한 `RoomOfferBookingView`와 `ApplicantProfileView`를 `BookingCreatedEventFactory`로 조립해 처리 시점 재조회 제거
- `(chat_room_id, booking_id)` UNIQUE로 같은 신청 카드 중복 저장 방지
- listener 성공 조건은 방과 `BOOKING_CARD`가 모두 저장된 상태
- 동일 event·booking 재처리는 카드, 마지막 메시지와 방 재표시를 다시 만들지 않음
- 신규 카드 INSERT, 방의 마지막 메시지 포인터, 필요한 참여자 방 재표시는 하나의 카드 저장 트랜잭션으로 처리

이 업무 이벤트 내구성은 실시간 STOMP Simple Broker와 별개다. 현재는 MySQL 메시지 이력에서 카드를 복구하며, topic 발행은 STOMP 구현 단계에서
커밋 이후 동작으로 추가한다.
