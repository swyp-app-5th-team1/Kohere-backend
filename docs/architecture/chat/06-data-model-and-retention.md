# MySQL 데이터 모델과 현재 저장 흐름

이 문서는 **이번 구현 범위**의 채팅·자동 번역·사용자별 숨김·신고 접수 데이터 구조를 설명한다. 외부 REST 계약은 [02-api-contracts.md](02-api-contracts.md)를 따른다.

관리자 신고 처리, 삭제 후 3개월 만료 확정, 메시지·방의 물리 삭제는 현재 migration과 batch 범위에 포함하지 않는다. 해당 설계는 [후속 고도화 문서](future/README.md)로 분리한다.

## 1. 공통 원칙

- MySQL이 방·메시지·번역 결과·사용자별 숨김·신고 접수의 정본이다.
- 시각은 UTC `DATETIME(6)`로 저장한다.
- 상태 값은 MySQL native ENUM 대신 `VARCHAR`를 사용한다.
- user, listing처럼 다른 모듈이 소유한 ID는 값으로 참조한다.
- `chat_messages.content`는 사용자가 보낸 불변 원문이며 기록·신고의 정본이다.
- 자동 번역은 사용자·대상 언어별 파생 데이터이며 원문을 덮어쓰지 않는다.
- 방과 과거 메시지의 가시성은 `chat_room_members`에서 사용자별로 관리한다.
- 읽음 기능은 이번 범위가 아니므로 `last_read_message_id`와 `unread_count`를 만들지 않는다.
- 이번 구현은 메시지를 물리 삭제하지 않는다. 사용자의 삭제는 soft delete, 즉 사용자별 숨김이다.

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
| `undo_token` | 현재 화면의 1회성 Undo용 불투명 UUID |
| `undo_expires_at` | 짧은 화면 Undo 가능 시간의 만료 시각 |
| `undo_previous_hidden_through_message_id` | Undo할 때 되돌릴 직전 숨김 경계 |
| `delete_requested_at` | 최근 삭제 요청 시각 |
| `created_at`, `updated_at` | 생성·변경 시각 |

```sql
UNIQUE (chat_room_id, user_id)
INDEX (user_id, room_hidden_at, chat_room_id)
```

`room_hidden_at`은 방 목록에 보이는지를, `history_hidden_through_message_id`는 메시지 조회에서 어디까지 숨길지를 뜻한다. 두 값이 분리되어 있으므로 새 메시지로 방이 다시 나타나도 예전에 숨긴 메시지는 복원되지 않는다.

`undo_expires_at`은 현재 화면에 잠시 제공하는 Undo 시간이다. 3개월 보존기간과 같은 개념이 아니며 정확한 시간은 제품 설정값으로 정한다. 앱은 `undo_token`을 메모리에만 잠시 보관하고 삭제방 목록이나 복구 API를 제공하지 않는다.

### 2.3 `chat_messages`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 서버 `messageId`, PK |
| `chat_room_id` | 방 ID |
| `sender_id` | 인증 Principal에서 결정한 발신자 |
| `type` | 이번 범위에서는 항상 `TEXT` |
| `content` | 사용자가 보낸 원문, DB `TEXT` |
| `client_message_id` | 프런트엔드가 만든 UUID, `BINARY(16)` 권장 |
| `sent_at` | 서버 저장 시각 |

```sql
UNIQUE (chat_room_id, sender_id, client_message_id)
INDEX (chat_room_id, id DESC)
```

메시지는 저장 후 수정하지 않는다. 본문은 애플리케이션에서 공백·줄바꿈을 포함해 Unicode code point 3,000자로 제한하고 검색 인덱스는 만들지 않는다.

### 2.4 `chat_message_translations`

받은 메시지의 사용자별 번역 결과이자 서버 재시작 후에도 복구할 수 있는 번역 작업이다.

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
| `next_attempt_at` | 다음 재시도 가능 시각 |
| `lease_until` | Worker 중단 시 작업을 다시 회수하기 위한 기한 |
| `last_failure_code` | 원문을 포함하지 않는 오류 분류 code |
| `translated_at` | 성공 또는 번역 불필요 확정 시각 |
| `created_at`, `updated_at` | 생성·변경 시각 |

```sql
UNIQUE (message_id, recipient_user_id, target_language)
INDEX (status, next_attempt_at, id)
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
7. 일시 오류는 제한된 횟수만 재시도하고 이후 `FAILED`로 끝낸다.
8. Worker가 중단되면 `lease_until`이 지난 작업을 다시 가져온다.

Worker는 Google을 대신해 번역하는 구성 요소가 아니다. 번역할 작업을 찾고, Google API를 호출하고, 결과 저장·재시도·수신자 전달을 관리하는 백엔드 컴포넌트다. 작업은 커밋 직후 바로 깨우고, 주기 조회는 신호 유실이나 서버 재시작 뒤 남은 `PENDING` 작업을 복구하는 안전망으로 사용한다. 정확한 복구 조회 주기는 구현 시 운영 지표로 조정한다.

번역 완료·재시도·실패는 다음 값을 변경하지 않는다.

- 원문과 `clientMessageId` 멱등성 판정
- `chat_rooms.last_message_id`, `last_message_at`
- 방 재표시와 메시지 숨김 경계
- 메시지 cursor와 신고의 `evidence_through_message_id`

같은 `clientMessageId` 재시도는 새 번역 행이나 두 번째 Google 요청을 만들지 않는다. 메시지 이력에서는 사용자의 숨김 경계를 원문과 번역본에 동일하게 적용한다. 신고 evidence와 hash는 항상 원문으로 만든다.

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
- 클라이언트가 보낸 본문은 증거로 신뢰하지 않고 MySQL 원문으로 snapshot을 만든다.
- 전체 채팅을 제한 없이 복제하지 않는다.
- 이후 사용자 방 숨김과 관계없이 접수된 evidence는 유지하고 사용자 API로 원문을 노출하지 않는다.

운영자 상태 이력, 최종 증거 버전, 처리 완료 시각, 보관 만료일과 법적 hold는 [후속 관리자 설계](future/01-admin-report-management.md)로 분리한다.

## 5. 사용자별 방 숨김과 Undo

| 상황 | 현재 구현의 처리 |
| --- | --- |
| 처음 삭제 | 방을 요청자 목록에서 숨기고 현재 마지막 messageId를 숨김 경계로 저장하며 `undo_token` 생성 |
| 같은 숨김 상태에서 DELETE 재시도 | 같은 결과와 token 반환, 새 삭제 기록 없음 |
| 현재 화면에서 Undo | token과 짧은 만료시간을 검증하고 직전 숨김 경계로 복원 |
| 오래되거나 다른 token | stale 또는 만료 오류로 거부 |
| 실제 새 메시지 수신 | 방만 다시 표시하고 기존 숨김 경계는 유지 |
| 같은 매물에서 직접 문의 | 같은 `roomId`를 다시 표시하고 기존 숨김 경계는 유지 |
| 중복 메시지 재전송·번역 완료·지연 예약 이벤트 | 방 표시 상태를 변경하지 않음 |

메시지 조회는 `messageId > history_hidden_through_message_id` 범위만 반환한다. 따라서 방이 다시 나타나도 이전에 숨긴 대화는 보이지 않고 새 메시지만 보인다.

현재 구현에서는 숨긴 원문과 번역본을 MySQL에서 물리 삭제하지 않는다. 삭제 후 3개월이 지난 범위를 자동 확정하고 양쪽 공통 범위를 실제로 지우는 작업은 [후속 보존 설계](future/02-retention-and-physical-deletion.md)에서 다룬다.

## 6. `BookingCreatedEvent` publication

예약 저장 후 방 보상을 신뢰성 있게 수행하려면 Spring Modulith Event Publication Registry의 MySQL 테이블을 Flyway로 관리한다.

- booking row와 event publication을 같은 booking transaction에 저장
- event에 `eventId`, `bookingId`, `occurredAt`, `listingId`, `tenantId`, `landlordId` 포함
- chat listener 성공 시 publication 완료 처리
- 실패 publication은 backoff 후 재처리
- `eventId`·`bookingId`와 방 UNIQUE로 중복 생성 방지
- listener는 누락된 방의 존재만 보장하며 기존 숨김 상태는 변경하지 않음

이 업무 이벤트 내구성은 실시간 STOMP Simple Broker와 별개다.
