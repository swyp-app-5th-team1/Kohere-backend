# 매물 예약(신청) · (후속) 문의 · 인앱 채팅 API Spec

> **채팅 계약 변경 안내:** 이 문서의 예약(Booking) API는 계속 유효하지만, 후반의 문의·채팅 §8~§12는 현재 구현 계약이 아니다. 채팅방·메시지·STOMP·읽음 이연의 정본은 [새 채팅 설계](../../architecture/chat/README.md)와 [API 계약](../../architecture/chat/02-api-contracts.md)이다. 특히 REST 메시지 전송, `/read`, `category`, `unreadCount`, `LISTING_CARD`, `SYSTEM`은 현재 범위에 포함하지 않는다.

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

## 개요

세입자(외국인 사용자)가 매물의 방 상품(`roomOffer`)에 **예약(= 신청, Booking)** 을 신청하고 자신의 예약 내역을 목록·단건 상세로 다시 확인하며, **임대인은 자기 소유 매물에 신청된 예약을 목록·단건 상세로 조회**한다. **본 서비스에서 "신청"과 "예약"은 같은 `Booking`을 가리키는 동의어다.**

- **[1차 MVP] 매물 예약(신청)**: 세입자가 방 상품에 타겟 입주일 + 계약기간(개월수)으로 예약을 생성·저장하고, 내 예약을 목록·단건 상세로 조회한다. **임대인은 자기 소유 매물(`listing.landlordId`=본인)에 신청된 예약을 목록·단건 상세로 조회한다**(소유권 스코프). 인앱 채팅과 **분리된 독립 기능**이다. MVP의 예약은 "신청" 성격이나 **동일 세입자–동일 방 상품에 활성 1건만 허용**하며(중복 방지 UNIQUE, 재신청 시 `409 BOOKING_ALREADY_EXISTS`), 본인 매물 차단은 없다(예약 **생성**은 세입자 전용; 조회는 세입자=내 예약, 임대인=내 매물에 신청된 예약으로 갈린다). 더해 참여자는 자기 예약 내역을 **삭제**(내 목록에서만 숨김)하고, 예약 **상대를 차단**하며, 예약을 **신고 접수**할 수 있다(#169).
- **1:1 문의 · 인앱 채팅**: 이 문서에서 분리되었다. 현재 계약은 [채팅 API 계약](../../architecture/chat/02-api-contracts.md)을 따른다. 신청이 저장되면 동일한 매물 채팅방에 `BOOKING_CARD`가 한 번 기록된다.

문서 구조:

- **[1차 MVP] 매물 예약(신청)** — 엔드포인트 요약 · 1. 예약 생성 · 2. 예약 목록(userType 분기) · 3. 예약 상세(userType 분기) · 4. 예약 내역 삭제 · 5. 예약 상대 차단 · 6. 예약 신고 접수 · 7. 예약 신고 사유 목록
- **1:1 문의 · 인앱 채팅** — [별도 채팅 설계 문서](../../architecture/chat/README.md)

### 핵심 개념·enum

| 개념 | 값 | 설명 |
| --- | --- | --- |
| 계약기간 `contractPeriod` | 정수(개월수, 예: `1`·`3`·`6`·`12`·`24` …) | 예약(신청) 시 입력하는 계약 기간(개월 단위 양의 정수). 총 금액 계산에 개월수로 그대로 쓴다 |
| 예약 상태 `status` (Booking) | `REQUESTED`, `ACCEPTED`, `REJECTED`, `CANCELED` | 신청 직후 `REQUESTED` 고정. 임대인 수락/거절·세입자 취소 등 상태 전이는 본 스펙 범위 밖(확장 시 정의) |

- 날짜만 표기는 `YYYY-MM-DD`(예: `moveInDate`), 시각은 ISO-8601 UTC(예: `2026-06-15T08:30:00Z`).
- 금액은 KRW 정수(예: `monthlyRent: 500000`).
- `listingId`·`roomOfferId`는 MongoDB ObjectId의 24자리 hex 문자열이다. `bookingId`는 booking 모듈 저장소의 숫자 식별자(`Long`)다.
- **총 금액**은 예약 상세에서만 계산해 내려준다: `totalAmount = deposit + monthlyRent × 계약 개월수`(관리비 `maintenanceFee`는 제외). 아래 [3. 예약 상세](#3-get-apiv1bookingsbookingid--예약-단건-상세) 참조.

### 저장·조합 규약 (매물 예약)

- **Booking 저장 필드**: `id`(bookingId, `Long`, PK) · `tenantId`(`Long`) · `listingId`(string) · `roomOfferId`(string) · `landlordId`(`Long`, **생성 시 매물 소유자(`listing.landlordId`) 스냅샷** — 임대인 조회 스코프) · `moveInDate`(`LocalDate`) · `contractPeriod`(정수, 개월수) · `status`(enum, 생성 시 `REQUESTED` 고정) · `createdAt`(`Instant`). 저장소는 **MySQL** — `bookings`는 이미 [`V9__bookings.sql`](../../../src/main/resources/db/migration/V9__bookings.sql)·[`V11__add_bookings_landlord_id.sql`](../../../src/main/resources/db/migration/V11__add_bookings_landlord_id.sql)로 **MySQL에 배포된 사실**이다([database-design](../../database/database-design.md) §4-5). 여기에 **동일 세입자–동일 방 상품 활성 1건만 허용**하는 UNIQUE `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`를 두며(전진 마이그레이션 **V18**, 아래 [중복 방지] bullet), 재신청은 `409 BOOKING_ALREADY_EXISTS`로 막는다. 다만 [ADR-0005](../../adr/0005-polyglot-persistence.md) 폴리글랏 배치 표엔 `booking` 매핑이 **아직 미반영**(추후 결정)이라 ADR 차원의 반영만 열려 있다 **(확인 필요)**.
- **중복 방지**: 동일 세입자–동일 방 상품에 예약(신청)은 **활성 1건만** 허용한다 — `bookings`에 UNIQUE `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`를 두며, 재신청은 신규 생성 없이 `409 BOOKING_ALREADY_EXISTS`다. 지금은 예약 상태 전이(수락/거절/취소)가 미구현이라 **모든 예약이 `REQUESTED`(=활성)**여서 "활성 1건"이 곧 "전체 1건"이므로, 조건 없는 `UNIQUE (tenant_id, room_offer_id)`로 규칙이 정확히 표현된다. ⚠️ **caveat**: 상태 전이가 도입되면 `REJECTED`·`CANCELED` 건이 그 방 재신청을 영구 차단하므로 **활성 상태만 대상으로 하는 부분 유니크**로 교체해야 하는데, MySQL은 부분 유니크 인덱스를 지원하지 않아(대안: `active_room_offer_id` nullable 컬럼 + UNIQUE 트릭, 또는 앱 레벨 검사) 표현 방식은 그때 정한다. 전진 마이그레이션은 **V18** — `V18__add_bookings_unique_tenant_room_offer.sql`:

  ```sql
  ALTER TABLE bookings ADD CONSTRAINT uq_bookings_tenant_room_offer UNIQUE (tenant_id, room_offer_id);
  ```

  (V14~V17은 본 #169의 삭제·차단·신고·사유 카탈로그용으로 이미 계획된 번호라 건드리지 않고 중복 제약만 V18로 둔다. 제약 강화는 [migration-policy](../../database/migration-policy.md) §3상 비호환이라 기존 중복 행 정리가 선행돼야 하나 `bookings`는 신규라 사실상 비어 있다.) 이 제약으로 [`V9__bookings.sql:2`](../../../src/main/resources/db/migration/V9__bookings.sql)의 verbatim 주석 `-- MVP의 예약은 "신청" 성격이라 중복 방지 유니크 제약을 두지 않는다(같은 방 상품에 다건 신청 허용).`은 **뒤집힌다**(V9 파일 자체는 이미 배포돼 수정하지 않고, V18이 제약을 덧댄다). [database-design](../../database/database-design.md) §2-4 유니크 목록·§4-5에도 이 제약을 반영한다. 이 중복 방지는 차단이 예약 단위가 아니라 **사용자 단위**여야 하는 **보조** 근거였다(§5) — 같은 방 재신청은 이제 UNIQUE로 막히지만, 임대인은 방·매물을 여러 개 가져 **다른 방으로는 여전히 우회되므로** 사용자 단위 차단의 **주 근거인 구조적 근거**가 살아남는다(§5).
- **스냅샷 없음 — 조회 시점 실시간 조인**: 가격·매물 요약·예약자 성명은 예약에 스냅샷 저장하지 않고, 조회 시점에 애플리케이션 레벨로 조합한다. `listing :: api`로 `(listingId, roomOfferId)`의 매물 요약·`pricing`(보증금·월세)을, `user :: api`(`getUserName`)로 예약자 성명을 조회한다(둘 다 신규 공개 조회 메서드 필요). cross-store 조인·트랜잭션은 금지된다([ADR-0005](../../adr/0005-polyglot-persistence.md), [ADR-0002](../../adr/0002-inter-module-communication-via-events.md)). 가격 변경 시 상세는 **현재가 기준**으로 계산한다.
- **표시와 생성의 비대칭 — 이미 성사된 예약의 카드는 매물 상태·방 상태를 보지 않는다**: 예약 카드가 보여 주는 매물명·대표 사진·주소·방 상품명·보증금·월세는 예약 행에 없고 **조회 시점에 `listing :: api`로 매번 물어보는 값**이라(위 [스냅샷 없음] bullet), 그 조회가 공개 매물만 대상으로 하면 임대인이 매물을 수정해 심사에 넣거나 방을 비활성으로 내리는 순간 **이미 잡힌 예약의 카드가 매물명 없이 보증금 0원으로 렌더된다**. 예약은 매물 상태가 바뀌었다고 취소되지 않으므로 그 표시는 사실과 다르다. 그래서 **표시 경로는 매물 `status`와 방 `status`를 둘 다 무시하는 표시 전용 공개 쿼리**를 쓰고, 예약 **생성**(§1)만 기존대로 **공개 매물의 활성 방**에만 허용한다 — **표시는 열고 생성은 닫는 이 비대칭은 의도한 것이다.** 표시 전용 조회가 노출을 넓히지 않는 근거는 그것이 매물을 *찾는* 데 쓸 수 없다는 점이다 — `(listingId, roomOfferId)`를 이미 쥐고 있어야 하고 그 출처는 §2·§3의 소유권·표시 필터를 통과한 **요청자 스코프의 `Booking` 행**뿐이며, 돌려주는 값도 **그 사람이 예약할 때 이미 본 값**이라 새로 드러나는 정보가 없다.
- **예약 응답에 매물 심사 상태를 싣지 않는다**: 세입자에게 `PENDING`·`REJECTED`·`UPDATE_PENDING`은 전부 "지금 이 매물 페이지는 볼 수 없다" 하나이고(반려도 재제출로 되살아나므로 영구적이지 않다) 다르게 보여 줄 근거가 없다. 원본 `status`를 실으면 앱이 **매물 심사 도메인의 의미를 인코딩**하게 돼 상태가 하나 늘 때마다 앱 대응이 필요해진다. 카드를 탭했을 때 매물 상세가 `404`인 것은 사후 반려로 지금도 나는 상황이라 클라이언트가 이미 다뤄야 한다. 필요해지면 `listingViewable` 같은 **additive 불리언 필드**로 나중에 더해도 하위 호환이 깨지지 않는다.
- **예약 조회의 userType 분기(§2·§3)**: 조회 엔드포인트(`GET /api/v1/bookings`·`GET /api/v1/bookings/{bookingId}`)는 **별도 임대인 전용 API 없이** 요청자 `userType`으로 동작을 분기한다 — `TENANT`면 **내 예약**(요청자 `tenantId` 기준), `LANDLORD`면 **내 소유 매물에 신청된 예약**(요청자가 소유한 매물 기준)을 반환한다. `userType`은 토큰 클레임이 아니라 서비스 계층에서 `user::api`(`getUserType`)로 판정한다(`ROLE_LANDLORD` 없음 — URL 티어는 `ROLE_USER`). 두 역할 모두 유효한 요청이라 **역할에 따른 `403`은 없다**(권한 밖 리소스는 아래 404 통일로 처리).
- **임대인 분기 — 소유권 스코프(생성 시 landlordId 비정규화)**: 예약 **생성 시** 매물 소유자(`listing.landlordId`)를 `Booking.landlordId`로 **함께 저장**한다 — 생성은 이미 `listing::api`로 매물·방 상품을 조회(검증)하므로 소유자 스냅샷을 같이 캡처하는 비용은 거의 없다. 임대인 **목록** 조회는 booking 저장소에서 **`landlord_id = 요청자`** 단일 조건으로 `createdAt` 내림차순 조회한다(cross-store 조인 없음 — 소유권 판정이 booking 행에 있다). **상세**는 예약을 조회한 뒤 **`booking.landlordId == 요청자`인지 행 단위로 확인**하고, 예약이 없거나 내 소유 매물의 신청이 아니면 `404 BOOKING_NOT_FOUND`로 통일한다(존재 비노출 — 세입자 분기의 '타인 예약→404'와 동일 규약). `landlordId`는 매물 상태와 무관하게 저장돼 **심사 중(`PENDING`·`UPDATE_PENDING`)이거나 반려(`REJECTED`)된 매물의 신청도 자동 포함된다**. `landlordId`는 생성 시점 스냅샷이라 **소유권 이전 시 stale**하나, 소유권 이전은 MVP 범위 밖이라 충분하다(이전 도입 시 백필 또는 조회 시점 해석으로 전환). 이 방식은 `chat_rooms`가 `tenant_id`·`landlord_id`를 비정규화하는 선례와 일치한다.
- **신청자 프로필 조인(임대인 상세)**: 임대인 상세 분기는 신청자(세입자) 프로필 — 성명·**성별**·**국적**·**이메일** — 을 `user::api`(신규 `getApplicantProfile(tenantId)`)로 조회해 조합한다(목록 분기는 신청자 성명 `getUserName`만, 경량). 신청자는 세입자라 프로필이 존재하며, 탈퇴 회원은 PII 익명화([ADR-0014](../../adr/0014-withdrawal-pii-anonymization.md))로 값이 비어 있을 수 있다. 임대인에게 세입자 이메일·성별·국적은 **마스킹 없이 평문으로 노출**한다(제품 결정).
- **표시 상태(참여자별 삭제) 저장 필드**: `tenantDeletedAt`·`landlordDeletedAt`(`Instant`, nullable — NULL = 미삭제). 예약은 `tenantId`·`landlordId`가 **공유하는 1행**이라 삭제 플래그를 하나만 두면 한쪽이 지울 때 상대 기록까지 사라진다. 그래서 **참여자별로 2컬럼**을 둔다(§4). 두 필드는 예약 응답 DTO에 노출하지 않는다 — 삭제·차단은 "내 목록에서 사라짐"으로만 관측된다.
- **차단 저장 위치**: 차단은 예약이 아니라 **사용자 단위**이며 `user` 모듈이 `user_blocks(blocker_id, blocked_user_id)`를 소유한다. `booking`은 `user :: api`의 신규 공개 표면 **3개** — 조회 경로 필터용 **공개 쿼리** `findBlockedUserIds(blockerId)`·신규 신청 가드용 **공개 쿼리** `isBlockedBetween(a, b)`, 그리고 예약에서 도출한 상대 식별자를 받는 **차단 생성 공개 명령**(§5) — 호출로만 접근하며 `user_blocks`를 직접 조인하지 않는다(모듈 경계·**애플리케이션 레벨 조인**, [ADR-0002](../../adr/0002-inter-module-communication-via-events.md)·[ADR-0005](../../adr/0005-polyglot-persistence.md)). 차단 목록·해제 엔드포인트는 [01-auth-onboarding](01-auth-onboarding.md)(`/api/v1/users/me/blocks`)에 있다.
- **예약 신고 저장 필드**: `booking` 모듈이 `booking_reports`를 소유한다 — `id`(`Long`, PK) · `reporterId`(`Long`) · `bookingId`(`Long`) · `reason`(신고 사유 카탈로그 `booking_report_reasons`의 **code 문자열 값 참조**, **nullable** · FK 없음) · `detail`(자유 텍스트, nullable) · `createdAt`(`Instant`). **유일성 제약이 없다** — 동일 신고자가 동일 예약을 여러 번 신고할 수 있고(다건 허용; 새 사유·지속 문제 재신고), 도배 방지는 후속 레이트리밋(`429`)으로 이연한다(현재 미구현). 대신 운영 조회·향후 레이트리밋 집계용 보조 인덱스 `idx_booking_reports_booking (booking_id)`·`idx_booking_reports_reporter_created (reporter_id, created_at)`를 둔다. **`status` 컬럼이 없다** — 본 스펙의 범위는 **접수(capture)까지**이고 운영자 검토·제재·상태 전이는 범위 밖이라 전이할 상태가 없는 **불변 기록**이기 때문이다. 이 표는 [07-reports](07-reports.md)가 예약한 `reports` 테이블과 **별개**다.
- **모듈 의존**: `booking → { listing::api, user::api }` — `booking/package-info.java` 의존 화이트리스트에 이미 선언돼 있다. 삭제·차단·신고(§4~§7)도 **새 모듈 의존 엣지를 만들지 않는다** — 삭제·신고는 booking 모듈 내부이고, 차단 저장은 이미 화이트리스트에 있는 `user::api` 호출이다. 예약 **생성** 시 소유자 캡처를 위해 `listing::api`의 매물 조회 뷰(`RoomOfferBookingView`)에 `landlordId`를 추가 노출하고, 임대인 **상세** 분기의 신청자 프로필 조회를 위해 `user::api`에 `getApplicantProfile` 공개 메서드가 신규로 필요하다. 임대인 조회에 listing::api 소유권 조회 메서드는 **불필요**하다 — 소유권은 booking 행(`landlord_id`)에서 판정한다.
- **인증·상태 게이트**: 예약 조회(§2·§3)는 온보딩을 마친 `ACTIVE` 사용자 전용이다(세입자·임대인 공통 — `userType`으로 결과만 분기하며 역할 `403`은 없다). 예약 **생성**(§1)은 세입자 전용(`userType=TENANT`)이라 임대인은 `403 FORBIDDEN`이다. 두 경우 모두 비 `ACTIVE`(온보딩 미완료)는 다른 보호 엔드포인트와 **동일한 온보딩 상태 게이트**(`403 AUTH_ONBOARDING_REQUIRED`)로 검사한다. 삭제·차단·신고(§4~§7)도 같은 게이트를 따른다 — 세입자·임대인 공통이라 역할 `403`은 없다.
- **URL 티어 매처 신설 필요(§4~§7)**: 현행 booking의 SecurityConfig 매처는 `HttpMethod.GET` + 단일 세그먼트(`/api/v1/bookings/*`)만 `hasRole("USER")`로 잡는다. 신규 경로(`DELETE /api/v1/bookings/*`, `POST /api/v1/bookings/*/block`, `POST /api/v1/bookings/*/report`)는 그 매처에 걸리지 않아 `anyRequest().authenticated()`로 떨어지고, 그러면 **온보딩용 `ROLE_ONBOARDING` 토큰까지 통과**한다. 따라서 신규 경로는 **명시 매처로 전부 `hasRole("USER")`** 를 선언한다. `GET /api/v1/bookings/report-reasons`는 기존 `GET /api/v1/bookings/*`가 이미 커버하지만 의도를 드러내기 위해 더 구체적인 경로를 앞에 둔다. 차단 목록·해제(`/api/v1/users/me/blocks`)도 마찬가지로 명시가 필요하다 — 기존 `/api/v1/users/me` 매처는 **정확 경로**라 하위 경로를 덮지 않는다([01-auth-onboarding](01-auth-onboarding.md) 참조).

---

## [1차 MVP] 매물 예약(신청)

### 엔드포인트 요약(1차 MVP)

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/listings/{listingId}/bookings` | 매물 예약(신청) 생성·저장 | 필수 | 201 |
| GET | `/api/v1/bookings` | 예약 목록 — `userType` 분기(세입자=내 예약 / 임대인=내 매물에 신청된 예약, 오프셋 페이지네이션) | 필수 | 200 |
| GET | `/api/v1/bookings/{bookingId}` | 예약 단건 상세 — `userType` 분기(세입자=내 예약 / 임대인=내 매물에 신청된 예약) | 필수 | 200 |
| DELETE | `/api/v1/bookings/{bookingId}` | 예약 내역 삭제(요청자 목록에서만 숨김, 멱등) | 필수 | 204 |
| POST | `/api/v1/bookings/{bookingId}/block` | 예약 상대 차단(상대는 서버가 도출, 멱등) | 필수 | 204 |
| POST | `/api/v1/bookings/{bookingId}/report` | 예약 신고 접수 | 필수 | 201 |
| GET | `/api/v1/bookings/report-reasons` | 예약 신고 사유 목록 | 필수 | 200 |

> 예약 생성은 매물의 방 상품에 종속되는 액션이므로 `/listings/{listingId}` 하위 1단계 중첩으로 둔다(api-design-guide §2). 조회는 예약을 독립 컬렉션(`/bookings`)으로 두고 **별도 임대인 전용 경로 없이** 요청자 `userType`으로 반환 대상을 분기한다(세입자=내 예약, 임대인=내 소유 매물에 신청된 예약).
> 차단·신고는 특정 예약을 맥락으로 삼는 액션이라 `/bookings/{bookingId}` 하위 동사형 서브경로로 둔다(api-design-guide §1). **차단 목록·해제는 여기 없다** — `GET`/`DELETE /api/v1/users/me/blocks`로 [01-auth-onboarding](01-auth-onboarding.md)이 담당한다(§5의 근거 참조).
> `report-reasons`는 고정 메타라 `{bookingId}` 자리와 겹치지 않도록 **리터럴 세그먼트**로 둔다 — 라우팅은 리터럴 경로가 `{bookingId}` 템플릿보다 먼저 매칭되므로 충돌하지 않는다.
> **예약 경로는 `/api/v1` 그대로다.** 매물 **조회** 계열 6종이 `/api/v2`로 이관되고 `/api/v1` 조회가 `deprecated` 스텁이 됐지만([ADR-0040](../../adr/0040-listing-query-api-v2-and-v1-sunset.md) · [03-listings-favorites](03-listings-favorites.md)), 예약 생성의 중첩 경로는 `POST /api/v1/listings/{listingId}/bookings`로 유지한다 — 예약은 매물 조회 HTTP 응답이 아니라 **`listing :: api` 모듈 공개 쿼리**로 매물·방 상품을 검증하므로 매물 조회 API의 버전 분리에 영향을 받지 않고, 예약 요청·응답 계약도 v4 개편으로 바뀌지 않았다. 다만 요청에 실을 `listingId`·`roomOfferId`는 이제 **`/api/v2` 조회 응답**에서 얻는다(v1 조회는 빈 결과·404라 예약 진입점이 v2 화면에만 있다).

---

### 1. POST `/api/v1/listings/{listingId}/bookings` — 매물 예약(신청) 생성

방 상품(`roomOffer`)에 타겟 입주일과 계약기간(개월수)으로 예약을 생성·저장한다. 신청 직후 상태는 `REQUESTED` 고정이다. **동일 세입자–동일 방 상품에 예약은 활성 1건만** 허용된다(UNIQUE `uq_bookings_tenant_room_offer`) — 이미 신청한 방 상품에 다시 신청하면 `409 BOOKING_ALREADY_EXISTS`다.

#### 신청 후 채팅방·신청 카드 자동 처리

예약이 저장되면 서버는 같은 트랜잭션에 `BookingCreatedEvent`를 기록한다. Chat Application은 이 이벤트를 비동기로 받아 문의하기와 같은
`(listingId, tenantId, landlordId)` 채팅방을 찾거나 만들고, 신청 시점의 매물 대표 이미지·신청자·입주 조건·금액을 `BOOKING_CARD` 한 장으로
저장한다. 프런트가 카드를 생성하거나 전송하는 별도 API는 없다.

프런트 권장 호출 순서는 다음과 같다.

1. 이 신청 API 호출
2. 성공 뒤 `POST /api/v1/listings/{listingId}/inquiries`로 동일 채팅방의 `chatRoomId` 확보
3. `GET /api/v1/chat-rooms/{chatRoomId}/messages`로 기존 TEXT와 BOOKING_CARD 조회

신청 이전에 문의 대화가 있었다면 새 채팅방이 아니라 기존 `roomId`의 메시지 이력 뒤에 카드가 추가된다. 이벤트 listener는 비동기이므로 첫 이력 조회에서
카드가 아직 없다면 짧게 기다린 뒤 같은 이력 API를 한 번 다시 조회할 수 있다. 같은 이벤트가 재처리돼도 `(chatRoomId, bookingId)` UNIQUE 때문에
카드는 한 장만 존재한다.

- **인증**: 필수. 요청자는 `ACTIVE` 상태의 세입자(`userType=TENANT`)여야 한다. **예약은 세입자 전용** — 임대인(매물 소유자)은 예약할 수 없으며(비세입자 `403 FORBIDDEN`), 세입자가 자기 소유 매물을 예약하는 상황 자체가 성립하지 않으므로 본인 매물 차단은 두지 않는다.
- 매물·방 상품 존재·공개 여부는 `listing :: api`로 검증한다(소유자 조회 불요; cross-store 조인 금지, ADR-0005). 이 검증은 모듈 공개 쿼리라 **매물 조회 API의 v1/v2 분리와 무관**하다 — `deprecated`된 `/api/v1` 조회 스텁이 빈 결과를 주더라도 예약 생성은 실데이터로 판정한다.
- `listingId`·`roomOfferId`는 **`/api/v2` 매물 조회 응답**(상세 `GET /api/v2/listings/{listingId}`의 `listingId`·`roomOffers[].roomOfferId`)에서 얻은 값을 그대로 보낸다([03-listings-favorites](03-listings-favorites.md)).
- **차단 가드(양방향)**: 요청자와 매물 소유자(`listing.landlordId`) 사이에 **어느 방향이든** 차단 관계가 있으면 `403 FORBIDDEN`이다(`user :: api`의 `isBlockedBetween(요청자, 소유자)`로 판정). 판정은 매물·방 상품 검증 뒤, 예약 저장 전에 한다.
- **중복 신청 가드**: 요청자가 **이미 같은 방 상품(`roomOfferId`)에 신청한 예약**이 있으면 `409 BOOKING_ALREADY_EXISTS`다 — DB UNIQUE `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`가 저장 시점에 이를 보장하며, 제약 위반은 이 코드로 변환한다. 지금은 상태 전이가 없어 모든 예약이 활성(`REQUESTED`)이라 "활성 1건"이 곧 "전체 1건"이다(위 §1 [저장·조합 규약]의 중복 방지 bullet).

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 예약 대상 매물 ID(ObjectId hex 문자열) |

#### Request Body

```json
{
  "roomOfferId": "6858e2000000000000000abc",
  "moveInDate": "2026-07-01",
  "contractPeriod": 6
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `roomOfferId` | string | 필수 | 예약 대상 방 상품 ID(ObjectId hex 문자열). 누락은 `INVALID_INPUT`(400) |
| `moveInDate` | string(`YYYY-MM-DD`) | 필수 | 타겟 입주일. 누락·형식 위반은 `INVALID_INPUT`(400, `errors[]`에 필드 반환), 형식은 맞으나 과거면 `BOOKING_INVALID_MOVE_IN_DATE`(422) |
| `contractPeriod` | integer | 필수 | 계약 개월수(양의 정수, 1 이상). 누락·0·음수는 `INVALID_INPUT`(400), 숫자 아닌 타입은 `MALFORMED_REQUEST`(400) |

#### 성공 Response — 201 Created

`Location: /api/v1/bookings/{bookingId}`

```json
{
  "success": true,
  "data": {
    "bookingId": 9001,
    "status": "REQUESTED",
    "listingId": "6858e2000000000000000001",
    "roomOfferId": "6858e2000000000000000abc",
    "moveInDate": "2026-07-01",
    "contractPeriod": 6,
    "createdAt": "2026-06-15T08:30:00Z"
  },
  "error": null
}
```

> 생성 응답은 예약 코어 내역만 담는다. `201`은 예약과 재처리 가능한 이벤트가 저장됐다는 뜻이며 BOOKING_CARD 저장 완료 응답은 아니다. 매물 요약·가격·예약자 성명은 [3. 예약 상세](#3-get-apiv1bookingsbookingid--예약-단건-상세)에서 조회 시점 조인으로 내려준다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 필수값 누락(`roomOfferId`/`contractPeriod`/`moveInDate`), `contractPeriod`가 양의 정수 아님(0·음수), `moveInDate`가 `YYYY-MM-DD` 형식이 아님 |
| 400 | `MALFORMED_REQUEST` | 요청 본문을 JSON으로 해석할 수 없거나 필드 타입이 맞지 않음 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비`ACTIVE`) |
| 403 | `FORBIDDEN` | 세입자(`TENANT`)가 아닌 사용자(임대인)의 예약 시도 |
| 403 | `FORBIDDEN` | 요청자와 매물 소유자 사이에 차단 관계(양방향 중 어느 쪽이든)가 존재 |
| 404 | `LISTING_NOT_FOUND` | 매물 또는 방 상품이 없거나, 매물이 비공개(심사 중·반려)이거나 방이 비활성임 — **생성은 공개 매물의 활성 방에만 허용**된다(표시는 다르다 — §1 [저장·조합 규약]의 비대칭 bullet) |
| 409 | `BOOKING_ALREADY_EXISTS` | 동일 세입자가 동일 방 상품에 이미 신청함 |
| 422 | `BOOKING_INVALID_MOVE_IN_DATE` | `moveInDate`가 과거 |

> 온보딩 미완료(비`ACTIVE`) 사용자는 다른 보호 엔드포인트와 동일한 온보딩 상태 게이트 에러로 차단한다(코드 게이트와 1:1 일치, [error-response-guide](../error-response-guide.md)).
>
> **차단 가드가 양방향인 이유 — 블랙홀 예약 방지**: 목록 숨김은 **단방향**(차단자 기준)이지만 생성 가드는 **양방향**이다. 가드가 없으면 임대인이 차단한 세입자의 신청도 `201`로 저장되는데, 그 예약은 임대인 목록에서 차단 필터에 걸려 **영영 보이지 않는다** — 세입자는 신청이 접수됐다고 믿고 임대인은 존재조차 모르는 예약이 남는다. 어느 방향의 차단이든 생성 자체를 `403`으로 막아 이 상태를 만들지 않는다. 차단 관계는 존재 비노출 대상이 아니므로(요청자가 차단했다면 본인이 아는 사실이고, 상대가 차단한 경우도 매물 자체는 공개돼 있어 숨길 실익이 없다) 404가 아닌 공통 `403 FORBIDDEN`을 쓴다.

---

### 2. GET `/api/v1/bookings` — 예약 목록(userType 분기)

요청자 `userType`으로 반환 대상을 분기한다 — **세입자(`TENANT`)** 는 **내 예약**(본인 `tenantId`)을, **임대인(`LANDLORD`)** 은 **내 소유 매물(`listing.landlordId`=본인)에 신청된 예약**(매물 상태 무관 — 심사 중·반려 매물의 신청도 포함)을 반환한다. 둘 다 `createdAt` 내림차순 **오프셋 페이지네이션**(api-design-guide §4-1)이며, 다른 스코프의 예약은 목록에 포함되지 않는다. **별도 임대인 전용 경로는 없다.**

- **인증**: 필수. `ACTIVE` 사용자 전용(세입자·임대인 공통, 역할 `403` 없음). `userType`은 토큰 클레임이 아니라 서비스 계층에서 `user :: api`(`getUserType`)로 판정한다.
- **임대인 분기 소유권 스코프**: 예약 생성 시 저장된 `Booking.landlordId`로 booking 저장소에서 **`landlord_id = 요청자`** 단일 조건으로 조회한다(cross-store 조인 없음, [ADR-0005](../../adr/0005-polyglot-persistence.md)). 소유 매물 상태와 무관해 심사 중(`PENDING`·`UPDATE_PENDING`)·반려(`REJECTED`) 매물의 신청도 포함되며, 신청이 없으면 빈 목록.
- **표시 필터(§4·§5) — 두 분기 공통**: 다음 예약은 목록에서 **제외**된다.
  - **요청자가 삭제한 예약**: 세입자 분기는 `tenant_deleted_at IS NULL`, 임대인 분기는 `landlord_deleted_at IS NULL`인 것만 반환한다. 삭제는 **요청자에게만** 적용되며 **상대 목록에는 그대로 보인다**.
  - **요청자가 차단한 상대의 예약**: 세입자 분기는 `landlord_id`가, 임대인 분기는 `tenant_id`가 요청자의 차단 목록에 있으면 제외한다. 차단은 예약 단위가 아니라 **사용자 단위**라 **그 상대와의 모든 예약**이 한꺼번에 사라진다. 숨김은 **단방향**이다 — 내가 A를 차단해도 A의 목록은 그대로다.
- **필터는 리포지토리로 내린다**: 차단 목록은 `user :: api`(`findBlockedUserIds(요청자)`)로 먼저 받아 술어의 파라미터로 넘긴다. 응용 계층에서 조회 후 걸러내면 **별도 count 쿼리로 유도하는 `totalElements`/`totalPages`/`hasNext`와 어긋난다**(빈 페이지·잘못된 총계).

#### Query 파라미터

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `page` | int | 선택 | 0 | 0-base 페이지 번호 |
| `size` | int | 선택 | 20 | 페이지 크기(최대 100). 범위 초과는 `INVALID_INPUT`(400) |

> 정렬은 `createdAt,desc` 고정(쿼리로 변경 불가). MVP는 상태 전이가 없어 신청이 모두 `REQUESTED`이므로 `status` 필터는 두지 않는다. 매물 요약은 `listing :: api`의 **표시 전용 조회**(매물 상태·방 상태 무관 — §1 [저장·조합 규약]의 비대칭 bullet)로, 신청자 성명(임대인 분기)은 `user :: api`(`getUserName`)로 조회 시점에 실시간 조인한다.

#### 성공 Response — 200 OK (세입자 `TENANT` 분기)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "bookingId": 9001,
        "listing": {
          "listingId": "6858e2000000000000000001",
          "title": "강남역 도보 5분 원룸",
          "thumbnailUrl": "https://cdn.kohere.com/listings/6858e2000000000000000001/thumb.jpg"
        },
        "roomOfferId": "6858e2000000000000000abc",
        "moveInDate": "2026-07-01",
        "contractPeriod": 6,
        "status": "REQUESTED",
        "createdAt": "2026-06-15T08:30:00Z"
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 2,
      "totalPages": 1,
      "hasNext": false
    }
  },
  "error": null
}
```

> 세입자 분기: 예약자가 본인이라 신청자 정보를 담지 않는다. 예약이 하나도 없으면 `content: []` + `page.totalElements: 0` + `page.hasNext: false`(에러 아님) — 전부 삭제·차단으로 걸러진 경우도 같다.
>
> 삭제·차단 상태는 **응답 필드로 노출하지 않는다**. 항목이 목록에서 사라지는 것으로만 관측되며, 그래서 기존 응답 스키마는 그대로다.

#### 성공 Response — 200 OK (임대인 `LANDLORD` 분기)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "bookingId": 9001,
        "listing": {
          "listingId": "6858e2000000000000000001",
          "title": "강남역 도보 5분 원룸",
          "thumbnailUrl": "https://cdn.kohere.com/listings/6858e2000000000000000001/thumb.jpg"
        },
        "roomOfferId": "6858e2000000000000000abc",
        "roomOfferName": "원룸 A타입",
        "applicant": {
          "name": "John Doe"
        },
        "moveInDate": "2026-07-01",
        "contractPeriod": 6,
        "status": "REQUESTED",
        "createdAt": "2026-06-15T08:30:00Z"
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 3,
      "totalPages": 1,
      "hasNext": false
    }
  },
  "error": null
}
```

> 임대인 분기: 각 항목에 신청자 성명(`applicant.name`)·방 상품명(`roomOfferName`)이 추가된다. 신청자 상세(성별·국적·이메일)는 단건 상세(§3 임대인 분기)에서 내려준다. 소유 매물에 신청이 없으면 `content: []` + `page.totalElements: 0`(삭제·차단으로 전부 걸러진 경우도 같다).

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `page`/`size` 범위 위반(음수 `page`, `size` 1 미만·100 초과). 보정하지 않고 거절한다 |
| 400 | `MALFORMED_REQUEST` | `page`/`size`가 정수가 아님(쿼리 파라미터 타입 불일치) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비`ACTIVE`) |

---

### 3. GET `/api/v1/bookings/{bookingId}` — 예약 단건 상세

요청자 `userType`으로 분기한다 — **세입자(`TENANT`)** 는 **내 예약 1건**을, **임대인(`LANDLORD`)** 은 **내 소유 매물에 신청된 예약 1건**을 상세 조회한다. **가격·매물 정보·성명(신청자 정보)은 스냅샷이 아니라 조회 시점에 실시간 조인**한다(가격 변경 시 현재가 기준). 별도 임대인 전용 경로는 없다.

- **인증**: 필수. `ACTIVE` 사용자 전용(역할 `403` 없음). **조회 권한 밖이면 `404 BOOKING_NOT_FOUND`로 통일**(존재 비노출) — 세입자는 본인 예약이 아닐 때, 임대인은 내 소유 매물의 신청이 아닐 때.
- **표시 필터(§4·§5)**: 상세도 목록(§2)과 **같은 술어**를 쓴다 — 요청자가 삭제한 예약, 요청자가 차단한 상대의 예약은 조회되지 않으며 **`404 BOOKING_NOT_FOUND`로 통일**한다(권한 밖과 같은 코드 — 존재 비노출). 상대에게는 여전히 `200`으로 보인다. 목록·상세가 같은 술어를 공유하므로 "목록엔 없는데 상세는 열리는" 불일치가 생기지 않는다.
- **임대인 분기 소유권 확인**: 예약을 조회한 뒤 **`booking.landlordId == 요청자`인지 행 단위로 확인**한다(생성 시 저장된 값; listing::api 왕복 없음). 불일치·부재는 아래 `404 BOOKING_NOT_FOUND`로 통일한다.
- **실시간 조인**: `listing :: api`의 **표시 전용 조회**로 `(listingId, roomOfferId)`의 매물 요약·주소·방 상품명·`pricing`(보증금·월세)을 얻는다 — 매물 `status`와 방 `status`를 보지 않으므로 심사 중·반려 매물이나 비활성 방의 예약도 값이 정상으로 채워진다(§1 [저장·조합 규약]의 비대칭 bullet). 성명은 **세입자 분기**가 `user :: api`(`getUserName`)로 예약자 본인(`tenantName`)을, **임대인 분기**가 `user :: api`(신규 `getApplicantProfile`)로 신청자 프로필(성명·성별·국적·이메일)을 조회한다.
- **금액**: 세입자·임대인 분기 **모두 동일한 필드·정의**로 **총 금액** `totalAmount = deposit + monthlyRent × contractPeriod`(`contractPeriod`는 계약 개월수 정수, **관리비 제외**)를 내려준다.

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `bookingId` | Long | 필수 | 예약 ID |

#### 성공 Response — 200 OK (세입자 `TENANT` 분기)

```json
{
  "success": true,
  "data": {
    "bookingId": 9001,
    "status": "REQUESTED",
    "createdAt": "2026-06-15T08:30:00Z",
    "moveInDate": "2026-07-01",
    "contractPeriod": 6,
    "listing": {
      "listingId": "6858e2000000000000000001",
      "title": "강남역 도보 5분 원룸",
      "thumbnailUrl": "https://cdn.kohere.com/listings/6858e2000000000000000001/thumb.jpg",
      "address": "서울특별시 강남구 역삼동 …",
      "roomOfferId": "6858e2000000000000000abc",
      "roomOfferName": "원룸 A타입"
    },
    "tenantName": "John Doe",
    "deposit": 5000000,
    "totalAmount": 8000000
  },
  "error": null
}
```

> 세입자 분기: `deposit` 5,000,000 + `monthlyRent` 500,000 × `contractPeriod` 6 = `totalAmount` 8,000,000(관리비 미포함). 예약자가 본인이라 `tenantName`은 본인 성명이다.

#### 성공 Response — 200 OK (임대인 `LANDLORD` 분기)

```json
{
  "success": true,
  "data": {
    "bookingId": 9001,
    "status": "REQUESTED",
    "createdAt": "2026-06-15T08:30:00Z",
    "moveInDate": "2026-07-01",
    "contractPeriod": 6,
    "listing": {
      "listingId": "6858e2000000000000000001",
      "title": "강남역 도보 5분 원룸",
      "thumbnailUrl": "https://cdn.kohere.com/listings/6858e2000000000000000001/thumb.jpg",
      "address": "서울특별시 강남구 역삼동 …",
      "roomOfferId": "6858e2000000000000000abc",
      "roomOfferName": "원룸 A타입"
    },
    "applicant": {
      "userId": 7,
      "name": "John Doe",
      "gender": "MALE",
      "country": "US",
      "countryName": "United States",
      "email": "john.doe@example.com"
    },
    "deposit": 5000000,
    "totalAmount": 8000000
  },
  "error": null
}
```

> 임대인 분기: 예약자 본인(`tenantName`) 대신 **신청자 프로필**(`applicant`: `userId`·`name`·`gender`·`country`·`countryName`·`email`)을 담고, `deposit`·`totalAmount`(총 금액)는 세입자 분기와 **동일한 필드·정의**다. `applicant.gender`는 user 소유 `Gender` enum 문자열(UPPER_SNAKE), `country`는 ISO 3166-1 alpha-2 코드, `countryName`은 서버가 참조로 resolve한 표시명이며, **마스킹 없이 평문으로 임대인에게 노출**한다. 신청자가 탈퇴한 경우 PII 익명화([ADR-0014](../../adr/0014-withdrawal-pii-anonymization.md))로 `name`·`gender`·`country`·`email`이 비어 있을 수 있다.
>
> 공통: **매물이 이후 비공개(심사 중·반려)로 바뀌거나 방 상품이 비활성으로 내려가도 이 상세는 그대로 `200`이고 매물 정보·가격 파트도 정상 값으로 채워진다** — 표시 경로가 매물 `status`와 방 `status`를 보지 않기 때문이고(§1 [저장·조합 규약]의 비대칭 bullet), 예약은 매물 상태가 바뀌었다고 취소되지 않아 금액을 0원으로 보여 줄 수 없기 때문이다. 예약 코어 내역(날짜·계약기간·상태)도 물론 유지된다. 매물 상태는 응답에 싣지 않으므로 세입자는 카드만 보고는 그 매물이 지금 볼 수 있는지를 알 수 없고, 카드를 탭해 매물 상세로 들어가면 `404`를 받는다 — 사후 반려로 이미 나던 상황이라 클라이언트가 그대로 다룬다. **매물 문서 자체가 없을 때만** 조인 대상이 없어 아래 `404 LISTING_NOT_FOUND`다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비`ACTIVE`) |
| 404 | `BOOKING_NOT_FOUND` | 예약이 없거나 **조회 권한 밖**(세입자: 본인 예약 아님 / 임대인: 내 소유 매물 신청 아님), 또는 **요청자가 삭제했거나(§4) 상대를 차단한(§5) 예약** — 404로 통일 |
| 404 | `LISTING_NOT_FOUND` | 조인 대상 매물 문서·방 상품이 **실재하지 않음**. 매물이 비공개(심사 중·반려)이거나 방이 비활성인 것은 여기 해당하지 않는다 — 위 blockquote 참조 |

---

### 4. DELETE `/api/v1/bookings/{bookingId}` — 예약 내역 삭제

요청자의 예약 목록(§2)·상세(§3)에서 해당 예약을 **숨긴다**. 행 자체는 지우지 않으며 **상대 참여자에게는 그대로 보인다** — 삭제는 "내 목록에서 치우기"이지 예약의 취소나 파기가 아니다.

- **인증**: 필수. `ACTIVE` 사용자 전용(세입자·임대인 공통, 역할 `403` 없음).
- **참여자별 소프트삭제**: 요청자가 세입자(`booking.tenantId == 요청자`)면 `tenant_deleted_at`을, 임대인(`booking.landlordId == 요청자`)이면 `landlord_deleted_at`을 현재 시각(UTC)으로 기록한다. 두 참여자의 삭제는 서로 **완전히 독립**이다.
- **권한**: 요청자가 참여자(세입자 또는 임대인)가 아니거나 예약이 없으면 `404 BOOKING_NOT_FOUND`(존재 비노출 — §3과 동일 규약, `403`을 쓰지 않는다).
- **차단과 무관**: 삭제는 `user_blocks`를 건드리지 않는다. 삭제한 예약의 상대는 여전히 새 신청을 보낼 수 있다.

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `bookingId` | Long | 필수 | 예약 ID |

#### Request Body

본문 없음(빈 본문).

#### 성공 Response — 204 No Content

응답 본문 없음(공통 래퍼도 없다 — api-design-guide §3-2 "삭제(204): 본문 없음").

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `MALFORMED_REQUEST` | `bookingId`가 숫자가 아님(경로 변수 타입 불일치) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비`ACTIVE`) |
| 404 | `BOOKING_NOT_FOUND` | 예약이 없거나 요청자가 해당 예약의 참여자가 아님 — 404로 통일(존재 비노출) |

> **멱등**: 이미 삭제한 예약을 다시 `DELETE` 해도 `204`다(타임스탬프는 최초 삭제 시각을 유지해도, 갱신해도 관측 차이가 없다). 이를 위해 **변이 경로는 삭제·차단 필터가 걸리지 않은 조회**를 쓴다 — §2·§3의 필터된 조회를 재사용하면 두 번째 `DELETE`가 대상을 찾지 못해 `404`가 되어 멱등이 깨진다. 신고(§6)도 같은 이유로 비필터 조회를 쓴다.
>
> **왜 `status=CANCELED`가 아닌가**: `bookings`는 세입자·임대인이 **공유하는 1행**이고 `status`는 두 참여자가 함께 보는 **공유 필드**라 참여자별 숨김을 표현할 수 없다 — 한쪽이 지우면 상대 화면의 상태까지 바뀐다. 취소(계약 의사의 철회, 상대에게 알려야 하는 사실)와 숨김(내 목록 정리, 상대와 무관)은 **다른 개념**이며, 애초에 상태 전이 자체가 미구현이라 `status`는 `REQUESTED`로 고정돼 있다(위 [핵심 개념·enum](#핵심-개념enum)). 같은 이유로 **단일 삭제 flag**(`deleted_at` 1개)도 쓰지 않는다 — 한쪽이 지우면 상대 기록까지 사라지는 데이터 손실이다.

---

### 5. POST `/api/v1/bookings/{bookingId}/block` — 예약 상대 차단

해당 예약의 **상대 참여자를 사용자 단위로 차단**한다. 상대(counterpart)는 **서버가 예약에서 도출**한다 — 요청자가 세입자면 `booking.landlordId`, 임대인이면 `booking.tenantId`. 클라이언트는 `userId`를 보내지 않는다(다른 사용자를 임의로 차단하는 경로를 만들지 않기 위해서다 — 차단하려면 그 상대와의 예약이 실재해야 한다).

- **인증**: 필수. `ACTIVE` 사용자 전용(세입자·임대인 공통, 역할 `403` 없음).
- **권한**: 요청자가 참여자가 아니거나 예약이 없으면 `404 BOOKING_NOT_FOUND`(존재 비노출).
- **저장**: `user :: api` 호출로 `user_blocks(blocker_id=요청자, blocked_user_id=상대)` 행을 만든다. **행의 존재 = 차단**이며 `is_active` 같은 플래그를 두지 않는다(해제는 행 삭제).
- **효과 ① 목록 숨김(단방향)**: 이후 요청자의 목록·상세에서 **그 상대와의 모든 예약**이 사라진다(이 예약 1건만이 아니다). **상대의 목록은 그대로다** — 차단당한 쪽은 자기 목록·상세**만으로는** 차단을 알 수 없다(자기 화면에서 사라지는 것이 없다). 단 효과 ②의 양방향 가드 때문에 상대가 **신규 예약을 신청하면 `403`이 돌아와** 관계를 알 수 있다 — 차단 관계는 애초에 존재 비노출 대상이 아니다(§1의 `403 FORBIDDEN` 근거 참조).
- **효과 ② 신규 신청 차단(양방향)**: 차단 관계가 있으면 어느 쪽도 상대에게 새 예약을 신청할 수 없다(§1의 `403 FORBIDDEN`).
- **`*_deleted_at`을 세팅하지 않는다**: 숨김은 차단 필터가 만든다. 그래서 **차단을 해제하면 그 예약들이 다시 보인다** — 삭제(§4)와 차단은 독립적으로 쌓이며, 삭제까지 했다면 해제해도 그 예약은 계속 숨겨진다.

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `bookingId` | Long | 필수 | 차단할 상대가 참여한 예약 ID |

#### Request Body

본문 없음(빈 본문). 상대는 서버가 예약에서 도출한다.

#### 성공 Response — 204 No Content

응답 본문 없음.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `MALFORMED_REQUEST` | `bookingId`가 숫자가 아님(경로 변수 타입 불일치) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비`ACTIVE`) |
| 404 | `BOOKING_NOT_FOUND` | 예약이 없거나 요청자가 해당 예약의 참여자가 아님 — 404로 통일(존재 비노출) |

> **멱등**: 이미 차단한 상대를 다시 차단해도 `204`(`409`가 아니다). 유니크 `(blocker_id, blocked_user_id)`가 중복 행을 막으며, 제약 위반은 에러로 올리지 않고 성공으로 흡수한다 — 차단의 관측 가능한 결과("그 사람이 안 보인다")가 이미 성립해 있기 때문이다. 삭제(§4)와 마찬가지로 **비필터 조회**를 쓴다 — 첫 차단으로 그 예약이 목록에서 사라지므로, 필터된 조회를 쓰면 두 번째 요청이 `404`가 된다.
>
> **왜 예약 단위가 아니라 사용자 단위인가 — 구조적 근거**: 상대는 **방을 여러 개 가진다**. [`Listing`](../../../src/main/java/com/kohere/listing/domain/Listing.java)은 소유자를 `landlordId` **하나**로 갖고 방 상품을 `List<RoomOffer> roomOffers`로 갖는다 — 즉 **한 임대인이 매물(`Listing`)을 여러 개, 한 매물이 방 상품(`roomOffer`)을 여러 개** 소유한다. 그래서 임대인 A를 예약 #1에서 차단해도 A의 **다른 방 상품**(같은 매물의 다른 방이든, A의 다른 매물이든)에 신청하면 새 예약 행이 생기고, 방·예약 단위 차단은 **같은 방 재신청을 막든 안 막든** 그 경로로 우회된다((후속·이연) 채팅이 붙으면 새 예약마다 새 채팅방까지 생긴다). 차단의 의미는 "이 예약을 안 보겠다"가 아니라 "이 **사람**을 안 보겠다"다 — 대상이 예약이면 상대가 방을 하나 더 가진 순간 무력해지므로 `user_blocks(blocker_id, blocked_user_id)`만이 대상을 정확히 표현한다.
>
> **보조 근거(중복 방지 반영 — 이 결정의 전제가 아니다)**: 같은 방 재신청은 이제 UNIQUE `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)`로 막힌다(§1 — 동일 세입자–동일 방 상품 활성 1건, 재신청 시 `409 BOOKING_ALREADY_EXISTS`). 그럼에도 임대인은 **방·매물을 여러 개** 가지므로 상대의 **다른 방으로는 여전히 우회된다** — 그래서 차단은 여전히 사용자 단위여야 한다. 즉 결론은 그대로이며, 위 **구조적 근거가 유일하게 살아남는 근거**다.
>
> **왜 차단 *생성*만 여기 있고 목록·해제는 [01-auth-onboarding](01-auth-onboarding.md)에 있는가**: 경로가 `/bookings/{bookingId}/block`이라 **예약에서 상대를 도출**해야 하는데, 그 도출은 `booking`만 할 수 있다. 만약 `user`가 생성까지 소유하면 상대를 알아내려 `user → booking` 의존이 생기고, `booking → user::api`가 이미 있어 **의존 사이클이 나 `ApplicationModules.verify()`(ModularityTest)가 깨진다**. 그래서 컨트롤러·권한 판정은 `booking`에 두고, 저장은 이미 화이트리스트에 있는 `user::api` 호출로 위임한다(새 의존 엣지 0개). 반대로 **해제 경로는 예약과 무관해야 한다** — 차단하는 순간 그 예약이 내 목록에서 사라져 `bookingId`를 다시 얻을 방법이 없어지므로, `/bookings/{bookingId}/unblock`은 **호출 자체가 불가능한 죽은 경로**가 된다. 그래서 목록·해제는 `GET`/`DELETE /api/v1/users/me/blocks`로 `user`가 소유한다.

---

### 6. POST `/api/v1/bookings/{bookingId}/report` — 예약 신고 접수

예약 1건에 대한 신고를 **접수·저장**한다. 신고자는 JWT subject로 식별하며, 요청자는 해당 예약의 참여자여야 한다. 동일 예약을 **여러 번 신고할 수 있다(다건 허용)** — 새 사유·지속되는 문제를 다시 신고할 수 있어야 하기 때문이다. 신고 도배 방지는 레이트리밋(`429`)으로 다루며 **후속·이연**이다(현재 미구현).

- **인증**: 필수. `ACTIVE` 사용자 전용(세입자·임대인 공통, 역할 `403` 없음).
- **권한**: 요청자가 해당 예약의 참여자(세입자 또는 임대인)가 아니거나 예약이 없으면 `404 BOOKING_NOT_FOUND`. **`403`이 아니다** — 예약의 존재 여부를 노출하지 않는 기존 booking 규약(§3)과 통일한다.
- **다건 신고 허용**: 동일 신고자–동일 예약 신고에 **유일성 제약을 두지 않는다** — 같은 예약을 여러 번 신고할 수 있다(새 사유·지속되는 문제를 다시 신고). 접수는 `409`를 반환하지 않으며, `BookingReport`는 **별도 비즈니스 키가 없다**. 신고 도배 방지는 **레이트리밋(`429`)** 으로 다루며 **후속·이연**이다(현재 미구현). 두 참여자가 서로를 신고하는 것은 `reporterId`가 달라 각각 접수된다.
- **범위는 접수까지**: 접수 사실만 기록한다. 운영자 검토·제재·상태 전이는 본 스펙 범위 밖이다.
- **자기 신고 차단 코드 없음**: 예약 **생성이 세입자 전용**(§1)이고 `userType`은 온보딩 확정 후 불변이라 `tenantId != landlordId`가 **구조적으로 보장**된다 — 자기 자신이 상대인 예약이 애초에 만들어질 수 없어 판정할 상황이 없다. 그래서 별도 에러 코드를 두지 않는다.

#### Path 파라미터

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `bookingId` | Long | 필수 | 신고 대상 예약 ID |

#### Request Body

```jsonc
{
  "reason": "ABUSE",             // 선택(nullable). 신고 사유 카탈로그(booking_report_reasons)의 활성 code 문자열(예: SPAM | ABUSE | SEXUAL_CONTENT | EXTERNAL_CONTACT | FALSE_INFO | ETC)
  "detail": "욕설이 계속됩니다"    // 선택. 자유 텍스트(최대 500자)
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `reason` | string(code) | 선택 | 신고 사유 카탈로그(`booking_report_reasons`)의 **활성 `code`** 문자열 하나 — [7. 예약 신고 사유 목록](#7-get-apiv1bookingsreport-reasons--예약-신고-사유-목록)이 내려주는 `code`다. **생략·`null` 허용**(그대로 `null`로 저장). 미정의·비활성 code는 `INVALID_INPUT`(400) |
| `detail` | string | 선택 | 최대 500자. 초과는 `INVALID_INPUT`(400) |

> 본문 전체를 생략(빈 본문)해도 접수된다 — 두 필드 모두 선택이다. **`reason`을 필수로 두지 않는 이유**: 사용자가 사유를 고르기 전에 이탈하면 신고 자체가 유실되는데, 접수 사실("이 예약에 문제가 있다는 신고가 있었다")만으로도 운영 판단의 근거가 되기 때문이다. 사유를 보내면 저장하고, 안 보내면 `NULL`로 남긴다.

#### 성공 Response — 201 Created

```jsonc
{
  "success": true,
  "data": {
    "reportId": 3001,
    "bookingId": 9001,
    "reason": "ABUSE",                     // 저장된 카탈로그 code(미지정 시 null)
    "createdAt": "2026-06-15T08:30:00Z"    // UTC ISO-8601
  },
  "error": null
}
```

> 응답에 `reporterId` 등 신고자 식별 정보와 `detail` 원문은 **노출하지 않는다**(민감정보·프라이버시 보호, [error-response-guide](../error-response-guide.md) §6). `status` 필드가 없다 — **접수까지만이 범위**라 전이할 상태가 없는 불변 기록이기 때문이다(`booking_reports`에 `status` 컬럼 자체를 두지 않는다). `Location` 헤더는 단건 조회 엔드포인트가 없으므로 부여하지 않는다(api-design-guide §3-2의 `Location`은 선택).
>
> **삭제·차단과 신고는 의도적으로 비대칭이다**: 신고 대상 판정은 **삭제(§4)·차단(§5) 상태와 무관**하다 — 이미 삭제한 예약도, 상대를 차단한 뒤에도 신고할 수 있다. 사용자는 보통 **먼저 치우고 나서 신고**하며, 그 순서 때문에 신고가 막히면 안 된다(**증거 보존**). 그래서 신고 경로는 §2·§3과 달리 **필터되지 않은 조회**로 참여자 여부만 본다. 결과적으로 **같은 예약 행이 `GET /api/v1/bookings/{bookingId}`에서는 `404`인데 `POST .../report`에서는 `201`**이 되는데, 이는 버그가 아니라 의도된 설계다 — 표시 여부(내 화면 정리)와 신고 자격(참여자였는가)은 서로 다른 질문이다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 미정의·비활성 `reason` code(활성 카탈로그 code 아님), `detail` 500자 초과 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가 또는 필드 타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비`ACTIVE`) |
| 404 | `BOOKING_NOT_FOUND` | 예약이 없거나 요청자가 해당 예약의 참여자가 아님 — 404로 통일(존재 비노출) |

> 신고 접수는 중복 거부(`409`)가 없다 — 동일 예약 다건 신고를 허용하며(위 [다건 신고 허용]), 도배 방지는 후속 레이트리밋(`429`)으로 이연한다(현재 미구현).
>
> 검증·권한 평가 순서: 인증(401) → 온보딩 게이트(403) → 입력 검증(400) → 예약 존재·참여자(404).
>
> **왜 예약 신고가 [07-reports](07-reports.md)가 아니라 여기인가**: 신고 **접수**는 "대상이 실재하는가 / 요청자가 그 대상의 참여자인가"를 반드시 검증해야 하는데, 그건 **예약만 아는 정보**다(참여자 판정은 `bookings` 행의 `tenant_id`·`landlord_id`에 있다). 접수를 `report` 모듈에 두면 그 판정을 위해 `report → booking` 포트를 새로 뚫어야 하지만, `booking`이 접수하면 **모듈 내부 호출**이라 새 의존 엣지가 0개다. 그리고 `report` 모듈은 **게시글(`POST`)·댓글(`COMMENT`)·채팅 메시지(`MESSAGE`)** 신고를 담당하므로(미구현) **본 스펙의 예약과 대상이 겹치지 않는다** — 두 곳이 공존해도 같은 대상을 두고 충돌하지 않는다. `booking_reports`도 07이 예약한 `reports` 테이블과 별개 테이블이다.

---

### 7. GET `/api/v1/bookings/report-reasons` — 예약 신고 사유 목록

예약 신고(§6)의 사유 목록을 신고 사유 카탈로그(`booking_report_reasons`)에서 읽어 메타로 반환한다. 클라이언트는 이 목록으로 신고 사유 선택지를 구성한다. **`label`은 서버가 요청자의 표시 언어로 골라 내려주고, `code`는 언어와 무관한 불변 식별자다.**

- **인증**: 필수. `ACTIVE` 사용자 전용.
- **페이지네이션**: 없음. 사유는 고정·소규모 집합이라 전체를 한 번에 반환한다(api-design-guide §4 비적용).
- **표시 언어 취득**: `user :: api`의 공개 쿼리 **`getLanguage(userId)`** 로 요청자의 표시 언어 코드를 받는다 — `user`가 `users.lang`(사용자가 고른 표시 언어)이 있으면 그 값을, 없으면 `en`을 소문자 코드 문자열로 회신하므로 **`booking`은 폴백 규칙을 알지 못한다**(도출 규칙 변경은 `user` 안에서 끝난다 — [ADR-0002](../../adr/0002-inter-module-communication-via-events.md) Decision 5). `diagnosis`·`gamification`·`lifetip`이 이미 같은 경로로 본문 콘텐츠를 번역한다.
- **지원 언어**: `Language` enum의 `EN`·`KO`·`JA` 3종. 미지원·미설정 언어는 `en`으로 폴백한다(임대인은 `lang`이 `ko` 고정이라 항상 `ko`).
- **새 모듈 의존 엣지 없음**: `booking → user::api`는 `booking/package-info.java` 의존 화이트리스트에 **이미 선언돼 있다**(§1 예약 생성의 성명 조회·차단 가드가 같은 포트를 쓴다). 번역을 위해 새로 뚫는 포트가 없다.

#### Path / Query 파라미터

없음.

#### Request Body

없음.

#### 성공 Response — 200 OK

아래는 요청자의 표시 언어가 `en`인 경우다(`users.lang = 'en'`, 또는 **미설정이라 `en`으로 폴백**).

```jsonc
{
  "success": true,
  "data": {
    "reasons": [
      // code는 언어 무관 불변, label만 표시 언어(getLanguage(userId))로 번역된다
      { "code": "SPAM", "label": "Spam/Advertisement" },
      { "code": "ABUSE", "label": "Abuse/Harassment" },
      { "code": "SEXUAL_CONTENT", "label": "Sexual content" },
      { "code": "EXTERNAL_CONTACT", "label": "Soliciting outside contact" },
      { "code": "FALSE_INFO", "label": "False information" },
      { "code": "ETC", "label": "Other" }
    ]
  },
  "error": null
}
```

표시 언어가 `ko`면 **같은 6개 `code`** 에 `label`만 바뀐다 — `스팸/광고` · `욕설/괴롭힘` · `성적 콘텐츠` · `외부 연락처 유도` · `허위 정보` · `기타`. 항목의 **개수·순서·`code`는 언어와 무관하게 동일**하다.

> **`label` 번역은 서버 책임이다** — `code`는 언어 무관 불변이고 `label`만 언어별로 갈린다([ADR-0029](../../adr/0029-diagnosis-i18n-strategy.md) Decision 6과 같은 원칙: 식별자는 고정, 표시 문자열만 번역).
>
> **그러면 이 엔드포인트가 왜 필요한가** — `reason`은 선택(nullable, §6)이라 클라이언트가 6개 상수를 하드코딩할 수도 있다. 그럼에도 서버가 내려줘야 하는 이유는 **번역** 하나다: 사용자의 표시 언어(`users.lang`)는 **서버만 안다**(토큰 클레임에 없어 클라이언트가 스스로 판정할 수 없다) 그리고 라벨 자체도 서버 카탈로그 테이블(`booking_report_reasons`)에만 있다. 즉 `code` 목록은 클라이언트가 알 수 있어도 **그 사용자에게 보여줄 `label`은 서버만 만들 수 있다.**
>
> **왜 MySQL 카탈로그 테이블(`booking_report_reasons`)인가 — enum·리소스 번들이 아니라**: 신고 사유는 **코드 배포 없이 행 추가로 동적 관리**하려는 데이터다 — 사유를 늘리는 것도, 표시 언어를 늘리는 것도 `(code, lang, label)` 행을 **INSERT**하면 끝이고 JVM enum이나 `.properties` 파일을 고쳐 재배포할 필요가 없다. 그래서 사유는 JVM enum이 아니라(`BookingReportReason` enum은 두지 않는다) **카탈로그 행(code 문자열)**이다. 진단 문항·생활 팁 주제처럼 배포 없이 운영자가 바꿔야 하는 콘텐츠지만, `booking`은 **MySQL**이라 그 동적 콘텐츠를 위해 MongoDB 인라인 언어-키 맵이나 cross-store 컬렉션을 새로 만드는 대신 **같은 MySQL 안의 카탈로그 테이블**에 둔다([ADR-0005](../../adr/0005-polyglot-persistence.md) — cross-store 조인 금지).
>
> ⚠️ **라벨은 카탈로그 테이블 행에 있다 — 리소스 번들이 아니다**: `booking_report_reasons`의 컬럼은 `id`(PK, auto) · `code`(VARCHAR32) · `lang`(VARCHAR8) · `label`(VARCHAR100) · `display_order`(INT) · `active`(BIT)이며 UNIQUE `(code, lang)`다. **`(code, lang)` 한 쌍이 한 라벨**이라 사유 `SPAM`의 en/ko/ja 라벨은 서로 다른 3개 행이다. 마이그레이션 [`V17__create_booking_report_reasons.sql`](../../../src/main/resources/db/migration/V17__create_booking_report_reasons.sql)이 테이블과 6종×3언어(en/ko/ja) 시드를 함께 배포한다. 사유·언어 추가는 이 표에 행을 **INSERT**하는 것으로 끝나고 코드 배포·스키마 변경이 없다.
>
> **`messages` 번들은 에러 메시지 전용이다 — 라벨을 섞지 않는다**: [ADR-0030](../../adr/0030-error-message-i18n-resource-bundle.md)이 `messages` 리소스 번들을 **에러 메시지 전용**으로 규정한다 — Decision 1은 "**키는 `ErrorCode` 이름(언어 무관 식별자)**"이라 못박고, Validation의 "**`messages.properties`(영어)의 키 집합이 `ErrorCode` 전체 상수와 일치하는지 관측**"이 그 불변식을 강제한다. 신고 사유 라벨을 그 번들에 넣으면 **`ErrorCode`에 대응 상수가 없는 키**가 생겨 이 커버리지 불변식이 깨진다 — 그래서 라벨은 번들이 아니라 **카탈로그 테이블**에 둔다. 게다가 라벨의 언어 출처도 에러 메시지와 다르다 — `messages` 경로의 Locale은 `Accept-Language`/`LocaleContextHolder`에서 오지만(ADR-0030 Decision 3), 신고 사유 라벨은 **본문 콘텐츠**라 `user :: api getLanguage(userId)`에서 온다([domain-model](../../architecture/domain-model.md) §2가 두 경로를 명시적으로 분리한다, 아래 註 참조). 카탈로그의 `lang` 컬럼이 그 사용자 표시 언어와 직접 매칭된다.
>
> ⚠️ **라벨은 `getLanguage(userId)`가 회신한 언어의 행으로 고른다** — `LocaleContextHolder`/`Accept-Language`가 **아니다**. [domain-model](../../architecture/domain-model.md) §2가 두 경로를 명시적으로 분리해 뒀다: `getLanguage`는 **본문 콘텐츠 번역에만** 쓰이고, **에러 메시지**는 `Accept-Language`/`LocaleContextHolder` 경로를 그대로 쓴다([ADR-0030](../../adr/0030-error-message-i18n-resource-bundle.md)). `label`은 본문 콘텐츠이므로 전자다 — 서버는 `booking_report_reasons`에서 **`active = true`인 사유를 `display_order`로 정렬**해 고르되, 각 `code`마다 `lang = getLanguage(userId)`인 라벨 행을 쓰고 **그 언어 행이 없으면 `lang = 'en'` 행으로 폴백**한다. 사용자가 표시 언어를 `ja`로 골랐는데 기기 `Accept-Language`가 `en`이면 `label`은 `ja`여야 한다.
>
> ⚠️ **일본어 라벨은 #169 구현 범위다**: V17 시드는 6종 사유의 en/ko/**ja** 라벨 행을 **모두 넣는다**. `ja` 행은 선택이나 후속 과제가 아니라 **본 이슈에서 함께 시드한다**: US-4-9의 **정상 AC**가 "`users.lang=ja`인 사용자는 **일본어 라벨**을, `lang` 미설정 사용자는 영어 라벨(`en` 폴백)을 받는다"를 요구하므로, `ja` 사용자에게 영어 `label`이 내려가면 **정상 AC가 실패한다**. 즉 `en` 폴백은 `ja`에 대해 허용된 상태가 아니라 **`(code, 'ja')` 행이 누락됐을 때의 실패 양상**이다(응답이 깨지지 않고 조용히 영어로 폴백하므로 더더욱 ja 시드 행을 구현 시 챙긴다). `en` 폴백이 **정상 동작인 경우는 지원 언어(`EN`·`KO`·`JA`) 밖이거나 `lang` 미설정인 사용자뿐**이다.

#### 발생 가능한 에러

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음/만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비`ACTIVE`) |

> **왜 인증이 필요한가** — [07-reports](07-reports.md)의 `GET /api/v1/reports/reasons`는 **인증 불필요**지만 그 규약을 승계하지 않는다. 기존 SecurityConfig의 booking 매처가 `GET /api/v1/bookings/*`를 이미 `hasRole("USER")`로 잡고 있어 인증 필수가 자연스러운 반면, 이 경로만 `permitAll`로 새로 여는 것은 **#169 범위 밖의 보안 완화**이기 때문이다.
>
> **07-reports의 신고 사유와 값은 같지만 별개 카탈로그다** — 예약 신고 사유는 `booking`이 소유한 `booking_report_reasons` 테이블 행이고, 07-reports가 담당할 사유와 `code` 값이 겹치더라도 별개 소스다. 사유 목록을 공유하면 `booking → report` **모듈 의존이 새로 생기는데**, 그 대가로 얻는 건 사유 6종의 중복 제거뿐이다. 두 카탈로그는 독립적으로 진화할 수 있다(예약 맥락에만 필요한 사유가 생겨도 게시글 신고 목록을 건드리지 않고 `booking_report_reasons`에 행만 추가한다).

---

## 문의 · 인앱 채팅

문의와 채팅 계약은 별도 문서로 이전했다. 이 문서의 이전 §8~§12 내용은 더 이상 사용하지 않는다.

- 현재 REST 계약: [02-api-contracts.md](../../architecture/chat/02-api-contracts.md)
- 실시간 메시지 계약: [03-websocket-stomp.md](../../architecture/chat/03-websocket-stomp.md)
- 읽음 처리와 `unreadCount`는 후속 개발이며, 현재 endpoint를 노출하지 않는다.

## 도메인 에러 코드

> 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN`, `TOO_MANY_REQUESTS` 등)는 [error-response-guide](../error-response-guide.md) §4를 따르며 여기서 재정의하지 않는다. 아래는 예약 기능 고유 코드만 정의한다. 채팅 오류 계약은 [채팅 보안·동시성 문서](../../architecture/chat/07-security-and-concurrency.md)를 따른다.

| code | status | 의미 | 스코프 |
| --- | --- | --- | --- |
| `BOOKING_INVALID_MOVE_IN_DATE` | 422 | `moveInDate`가 과거 | 1차 MVP |
| `BOOKING_ALREADY_EXISTS` | 409 | 동일 세입자가 동일 방 상품에 이미 신청함. DB 유니크 제약 `uq_bookings_tenant_room_offer (tenant_id, room_offer_id)` 위반. ErrorCode·messages 번들에 이미 선언돼 있던 코드가 본 결정으로 실사용된다 | 1차 MVP |
| `BOOKING_NOT_FOUND` | 404 | 예약이 없거나 조회 권한 밖(세입자: 본인 예약 아님 / 임대인: 내 소유 매물의 신청 아님), 또는 삭제(§4)·차단(§5)으로 요청자에게 숨겨짐 — 존재 여부를 노출하지 않도록 404로 통일. 삭제·차단·신고(§4~§6)에서 요청자가 참여자가 아닌 경우도 이 코드다(`403`이 아니다) | 1차 MVP |

> 매물·방 상품 부재(`404`)는 listing 모듈의 `LISTING_NOT_FOUND` 코드를 참조해 응답한다. 해당 코드는 listing 스펙이 카탈로그에 등록하는 것을 원칙으로 하며, 본 기능에서는 재정의하지 않는다.
>
> 삭제(§4)·차단(§5)에는 **신설 코드가 없다** — 둘 다 성공은 `204`, 실패는 기존 `BOOKING_NOT_FOUND`뿐이고 멱등이라 "이미 삭제됨"·"이미 차단됨" 상태를 에러로 표현하지 않는다. `BLOCK_*` prefix도 만들지 않는다. §1의 차단 가드 위반은 공통 코드 `FORBIDDEN`(403)을 쓴다(error-response-guide §3 인가 매핑 준수). 신고 접수(§6)에도 **신설 코드가 없다** — 동일 예약 다건 신고를 허용해 중복 거부(`409`)가 없고, 도배 방지는 후속 레이트리밋(공통 `TOO_MANY_REQUESTS`, `429`)으로 이연한다(현재 미구현).
