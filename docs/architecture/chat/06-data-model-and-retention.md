# MySQL 데이터 모델과 보존

이 문서는 채팅·삭제·신고 데이터 구조와 생명주기의 정본이다. 외부 REST 계약은 [02-api-contracts.md](02-api-contracts.md)를 따른다.

## 1. 공통 원칙

- MySQL이 방·메시지·삭제 상태·신고의 정본이다.
- 시각은 UTC `DATETIME(6)`로 저장한다.
- 상태 값은 MySQL native ENUM 대신 `VARCHAR`를 사용한다.
- user, listing처럼 다른 모듈이 소유한 ID는 값으로 참조한다.
- `chat_messages.content`는 사용자가 보낸 불변 원문이며 기록·신고의 정본이다.
- 자동 번역은 사용자·대상 언어별 파생 데이터이며 원문을 덮어쓰지 않는다.
- 사용자별 가시성은 `chat_room_members`에 둔다.
- 읽음 기능은 이번 범위가 아니므로 `last_read_message_id`와 `unread_count`를 만들지 않는다.

## 2. 채팅 테이블

### 2.1 `chat_rooms`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 채팅방 PK |
| `category` | 이번 범위에서는 `LANDLORD` |
| `listing_id` | 매물 ID |
| `tenant_id` | 세입자 ID |
| `landlord_id` | 임대인 ID |
| `listing_snapshot` | 생성 당시 매물 제목·대표 이미지 JSON |
| `room_status` | `OPEN`, `ADMIN_CLOSED` |
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
| `room_hidden_at` | 현재 내 목록에서 숨긴 시각 |
| `deletion_state` | `NONE`, `PENDING`, `FINALIZED` |
| `deletion_generation` | 내부 삭제 cycle 번호, 단조 증가 |
| `undo_token` | 현재 cycle의 불투명 UUID |
| `delete_requested_at` | 현재 삭제 요청 시각 |
| `restore_until` | 내부 삭제 유예 만료 시각 |
| `pending_delete_through_message_id` | 아직 확정되지 않은 숨김 경계 |
| `cleared_through_message_id` | 복구 불가능하게 확정된 경계, 절대 감소하지 않음 |
| `history_cleared_at` | 최근 경계 확정 시각 |
| `created_at`, `updated_at` | 생성·변경 시각 |

```sql
UNIQUE (chat_room_id, user_id)
INDEX (user_id, room_hidden_at, chat_room_id)
INDEX (deletion_state, restore_until, id)
```

`room_hidden_at`은 지금 목록에 보이는지를 뜻하고, `deletion_state`는 이전 이력을 버리는 내부 cycle을 뜻한다. 새 메시지로 방이 다시 보여도 pending 삭제 cycle은 남을 수 있으므로 둘을 한 컬럼으로 합치지 않는다.

`deletion_generation`과 `restore_until`은 내부 상태다. 외부에는 현재 화면의 Undo 호출에 쓸 `undo_token`만 반환하며, token을 사용자에게 문자열로 표시하거나 삭제방 목록에 보관하지 않는다.

### 2.3 `chat_messages`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 서버 messageId, PK |
| `chat_room_id` | 방 ID |
| `sender_id` | 인증 Principal에서 결정한 발신자 |
| `type` | 이번 범위에서는 항상 `TEXT` |
| `content` | 사용자가 보낸 원문, DB `TEXT` |
| `client_message_id` | 클라이언트 UUID, `BINARY(16)` 권장 |
| `sent_at` | 서버 저장 시각 |

```sql
UNIQUE (chat_room_id, sender_id, client_message_id)
INDEX (chat_room_id, id DESC)
```

메시지는 저장 후 수정하지 않는다. 본문은 애플리케이션에서 공백·줄바꿈을 포함해 Unicode code point 3,000자로 제한하고 검색 인덱스는 만들지 않는다.

### 2.4 `chat_message_translations`

받은 메시지의 사용자별 번역 결과이자 재시작 후에도 복구 가능한 번역 작업 queue다.

| 컬럼 | 설명 |
| --- | --- |
| `id` | 번역 작업 PK |
| `message_id` | 원문 `chat_messages.id` |
| `recipient_user_id` | 번역본을 받을 1:1 수신자 |
| `target_language` | 원문 저장 시점 수신자의 `users.lang` snapshot |
| `detected_source_language` | provider가 감지한 원문 언어, 처리 전에는 null |
| `status` | `PENDING`, `PROCESSING`, `SUCCEEDED`, `NOT_REQUIRED`, `FAILED` |
| `translated_content` | 성공한 번역본, 그 외에는 null |
| `provider` | `GOOGLE_CLOUD_TRANSLATION` |
| `model` | 초기값 `NMT` |
| `attempt_count` | 호출 시도 횟수 |
| `next_attempt_at` | 다음 재시도 가능 시각 |
| `lease_until` | worker 중단 시 작업을 다시 회수하기 위한 기한 |
| `last_failure_code` | 원문을 포함하지 않는 분류 code |
| `translated_at` | 성공 또는 불필요 확정 시각 |
| `created_at`, `updated_at` | 생성·변경 시각 |

```sql
UNIQUE (message_id, recipient_user_id, target_language)
INDEX (status, next_attempt_at, id)
INDEX (recipient_user_id, message_id)
```

첫 구현에서는 원문 저장 시점의 대상 언어를 고정한다. 사용자가 언어를 바꾸면 이후 새 메시지부터 새 언어를 적용하며, 과거 대화를 자동으로 일괄 재번역하지 않는다.

## 3. 자동 번역 생명주기

1. 신규 원문과 수신자별 `PENDING` 번역 행을 같은 transaction에 저장한다.
2. 원문 commit 뒤 worker가 짧은 transaction으로 작업을 `PROCESSING` 상태로 확보한다.
3. DB lock을 풀고 Google Cloud Translation에 원문과 대상 언어를 보낸다.
4. 성공하면 번역본과 감지 언어를 저장하고 수신자 개인 STOMP queue에 결과를 보낸다.
5. 원문과 대상 언어가 같으면 `NOT_REQUIRED`로 끝낸다.
6. 일시 오류는 제한된 횟수만 backoff·jitter로 재시도하고 이후 `FAILED`로 끝낸다.
7. worker가 중단되면 `lease_until`이 지난 작업을 다른 실행이 다시 가져간다.

번역 완료·재시도·실패는 다음 값을 변경하지 않는다.

- 원문과 `clientMessageId` 멱등성 판정
- `chat_rooms.last_message_id`, `last_message_at`
- 방 재노출, 삭제 generation과 메시지 경계
- 메시지 cursor와 신고의 `evidence_through_message_id`

같은 `clientMessageId` 재시도는 새 번역 행이나 두 번째 Google 요청을 만들지 않는다. 번역 결과가 안전한 outbound 크기를 초과하면 원문을 자르지 않고 번역을 `FAILED`로 처리한다.

메시지 이력에서는 요청자의 삭제 경계를 원문과 번역본에 똑같이 적용한다. 원문을 물리 파기하는 transaction은 연결된 번역 행을 먼저 삭제해 고아 번역본을 남기지 않는다.

신고 evidence와 hash는 항상 원문으로 만든다. 번역 완료를 이유로 이미 저장한 evidence를 변경하지 않는다.

## 4. 신고 테이블

### 4.1 `report_reason_labels`

| 컬럼 | 설명 |
| --- | --- |
| `reason_code` | 언어 불변 code |
| `language` | `ko`, `en`, `ja` |
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
| `reporter_id` | 신고자 ID |
| `reported_user_id` | 방에서 서버가 도출한 상대 ID |
| `target_type` | `CHAT_ROOM` |
| `target_id` | 채팅방 ID |
| `reason_code` | 고정 신고 사유 |
| `status` | `RECEIVED`, `UNDER_REVIEW`, `ACTIONED`, `DISMISSED` |
| `received_at` | 접수 시각 |
| `review_due_at` | 접수 + 7일 처리 목표 시각 |
| `evidence_through_message_id` | 접수 시점 마지막 메시지 ID |
| `resolved_at` | 최종 처리 시각 |
| `retention_expires_at` | 최종 처리 + 6개월 |
| `resolution_code` | 운영 처리 결과 code, nullable |
| `version` | 운영자 동시 처리 optimistic lock |
| `active_slot` | 열린 신고이면 1, 최종 신고이면 null인 generated column |
| `created_at`, `updated_at` | 생성·변경 시각 |

자유 입력 상세 사유 컬럼은 만들지 않는다.

동시에 같은 방을 여러 번 신고해도 열린 행이 하나만 생기도록 MySQL 제약을 둔다.

```sql
active_slot TINYINT
  GENERATED ALWAYS AS (
    CASE
      WHEN status IN ('RECEIVED', 'UNDER_REVIEW') THEN 1
      ELSE NULL
    END
  ) STORED

UNIQUE (reporter_id, target_type, target_id, active_slot)
INDEX (status, review_due_at, id)
INDEX (retention_expires_at, id)
INDEX (target_type, target_id, received_at)
```

최종 상태는 `active_slot=NULL`이므로 MySQL UNIQUE가 여러 과거 행을 허용한다. 애플리케이션은 최신 최종 신고의 `evidence_through_message_id`보다 새 `room.last_message_id`가 있을 때만 재신고를 허용한다.

### 4.3 `report_evidence`

| 컬럼 | 설명 |
| --- | --- |
| `id` | evidence PK |
| `report_id` | 신고 ID |
| `evidence_version` | 신고 안에서 단조 증가하는 버전 |
| `schema_version` | snapshot JSON schema 버전 |
| `evidence_through_message_id` | 접수 증거 상한 |
| `snapshot` | 방·참여자·매물·메시지의 JSON snapshot |
| `captured_at` | snapshot 생성 시각 |
| `content_hash` | 무결성 확인용 hash |

```sql
UNIQUE (report_id, evidence_version)
```

- 접수 시 `evidence_version=1`로 최근 최대 20개 텍스트 메시지를 저장한다.
- 운영 최종 처리 시 실제 판단에 필요한 최소 증거를 새 버전으로 append한다.
- 기존 evidence와 hash를 덮어쓰지 않는다.
- 클라이언트가 보낸 본문은 증거로 신뢰하지 않고 MySQL 원문으로 snapshot을 만든다.
- 전체 채팅을 제한 없이 복제하지 않는다.

### 4.4 `report_status_history`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 상태 이력 PK |
| `report_id` | 신고 ID |
| `from_status`, `to_status` | 상태 전이 |
| `actor_type` | `REPORTER`, `ADMIN`, `SYSTEM` |
| `actor_id` | 행위자 ID, SYSTEM이면 null |
| `created_at` | 처리 시각 |

```sql
INDEX (report_id, created_at, id)
```

### 4.5 보존 hold

신고 evidence 보존과 원본 채팅방의 물리 purge 제어를 분리한다.

- report 소유 `report_retention_holds`: `id`, `report_id`, `reason`, `held_at`, `review_due_at`, `released_at`
- chat 소유 `chat_room_retention_holds`: `hold_id`, `chat_room_id`, `held_at`, `review_due_at`, `released_at`

report 모듈이 `chat::api` 공개 command로 원본 room hold를 생성·해제한다. chat purge job은 report 테이블을 직접 조회하지 않고 자기 모듈의 room hold만 확인한다.

일반 신고는 검토 중 source room hold를 만들고 최종 증거 버전을 저장한 뒤 해제한다. 별도 보존 사유는 반드시 review 기한을 가진 hold로 관리하며 무기한 boolean만 두지 않는다.

## 5. 삭제 상태 전이

내부 `3개월`은 90일 고정이 아니라 삭제 시각에 calendar month 3개를 더해 계산한다. 사용자에게 이 상태나 기한을 조회하는 API는 제공하지 않는다.

| 상황 | 내부 처리 |
| --- | --- |
| 처음 삭제 | generation 증가, room 숨김, 마지막 메시지까지 pending 경계, token·3개월 기한 생성 |
| 같은 삭제 재시도 | 같은 token·generation·기한 유지, 기한 연장 없음 |
| 실제 새 메시지 또는 직접 재진입 | room만 재노출, pending·확정 경계 유지 |
| pending 중 다시 삭제 | 같은 generation·기한 유지, pending 경계만 현재 마지막 ID까지 전진 |
| 현재 token으로 Undo | pending cycle 해제, 해당 cycle 이력 복원 |
| 오래된 token | stale 요청으로 거부 |
| 내부 기한 만료 | pending 경계를 cleared 경계에 합쳐 복구 불가능 확정 |
| 이전 cycle 종료 후 다시 삭제 | 새 generation·token·기한 생성 |

Undo 후 `cleared_through_message_id > 0`이면 상태는 `FINALIZED`, 그렇지 않으면 `NONE`으로 돌아간다. `cleared_through_message_id`는 절대 감소하지 않는다.

재진입 시 이미 내부 기한이 지났지만 batch가 아직 처리하지 않았다면 요청 트랜잭션이 먼저 pending 경계를 확정한 뒤 방을 재노출한다.

실제 신규 메시지 INSERT가 성공했을 때만 수신자의 방을 재노출한다. 중복 `clientMessageId`와 지연된 예약 이벤트의 방 존재 보장은 가시성을 바꾸지 않는다.

번역 작업의 완료·재시도·실패도 신규 메시지가 아니므로 방을 재노출하지 않는다.

## 6. 공유 메시지 물리 파기

한 사용자가 방을 숨겨도 상대가 같은 메시지를 계속 봐야 하므로 곧바로 `chat_messages`를 삭제할 수 없다.

```text
safePurgeThrough = min(
  memberA.clearedThroughMessageId,
  memberB.clearedThroughMessageId
)
```

예를 들어 A의 확정 경계가 100이고 B가 0이면 파기 가능한 범위는 없다. B도 120까지 확정한 뒤에야 100까지 공통 prefix를 파기할 수 있다.

물리 파기 조건:

1. 두 사용자 모두 해당 ID까지 복구 불가능
2. 활성 source room hold 없음
3. DELETE·신고 snapshot과 경합하지 않도록 room lock 사용
4. job 재실행에도 같은 결과가 되는 멱등 처리

prefix purge 후:

- 해당 원문의 `chat_message_translations`를 먼저 삭제
- 남은 메시지가 없으면 `last_message_id`, `last_message_at`을 null로 변경
- 남은 메시지가 있으면 실제 최신 메시지로 포인터 보정

방 전체 hard delete 조건:

- 두 member 모두 현재 `room_hidden_at IS NOT NULL`
- 현재 `PENDING` cycle 없음
- 마지막 메시지가 없거나 `last_message_id <= safePurgeThrough`
- 활성 source room hold 없음

한쪽에 빈 방이 다시 노출된 상태라면 메시지가 없어도 방 자체를 삭제하지 않는다.

## 7. 삭제 만료 batch

먼저 due 후보 ID와 room ID만 잠금 없이 page 조회한다.

```sql
WHERE deletion_state = 'PENDING'
  AND restore_until <= :now
ORDER BY restore_until, id
LIMIT :batchSize
```

각 후보는 짧은 트랜잭션으로 처리한다.

1. room을 먼저 `FOR UPDATE SKIP LOCKED`로 잠근다.
2. 두 member를 ID 오름차순으로 잠근다.
3. lock 후 due 조건을 다시 확인한다.
4. pending 경계를 cleared 경계에 합친다.
5. 현재 cycle 필드를 null로 만들고 `FINALIZED`로 전환한다.
6. 현재 방이 재노출된 상태면 `room_hidden_at=null`을 유지한다.
7. 두 member의 safe purge 경계를 다시 계산한다.
8. active source hold가 없을 때만 안전한 prefix를 파기한다.

SEND, ensure, 신고 snapshot도 같은 room → member 순서를 사용해 교착을 막는다.

## 8. BookingCreatedEvent publication

예약 저장 후 방 보상을 신뢰성 있게 수행하려면 Spring Modulith Event Publication Registry의 MySQL 테이블을 Flyway로 관리한다.

- booking row와 event publication을 같은 booking transaction에 저장
- event에 `eventId`, `bookingId`, `occurredAt`, `listingId`, `tenantId`, `landlordId` 포함
- chat listener 성공 시 publication 완료 처리
- 실패 publication은 backoff 후 재처리
- `eventId`·`bookingId`와 방 UNIQUE로 중복 생성 방지
- listener는 누락된 방의 존재만 보장하며 기존 삭제방을 재노출하지 않음

이 업무 이벤트 내구성은 실시간 STOMP Simple Broker와 별개다.
