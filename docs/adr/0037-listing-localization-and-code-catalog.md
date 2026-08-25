# ADR-0037. 매물 고유 문구는 listings에 임베드하고 공통 코드는 listingCatalog에서 번역한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0037 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-07-17 |
| 관련 문서 | [ADR-0002](./0002-inter-module-communication-via-events.md), [ADR-0029](./0029-diagnosis-i18n-strategy.md), [ADR-0032](./0032-mongodb-migration-runner.md), [ADR-0039](./0039-listing-schema-v4-registration-form.md), [ADR-0040](./0040-listing-query-api-v2-and-v1-sunset.md), [listing API](../api/specs/03-listings-favorites.md) |

## Status

Accepted

## Context

- Kohere의 MVP 기본 사용자는 외국인이므로 매물 목록·상세에 남아 있는 한국어 표시 문구를 영어로 제공해야 한다.
- 매물명·주소·역명·방 이름·상세 설명은 매물마다 달라지지만, `FEMALE_ONLY`, `SUBWAY`, `CENTRAL` 같은 값은 검색·필터·검증에서 사용하는 안정적인 코드다.
- 코드를 번역 문자열로 교체하면 MongoDB 필터와 프론트 요청 값이 깨진다. 반대로 모든 공통 코드 번역을 매물마다 중복 저장하면 번역 수정 시 전체 매물을 갱신해야 한다.
- 기존 팀 로직은 `UserAccountService.getLanguage(userId)`로 사용자가 선택한 표시 언어(`users.lang`)를 얻고 미지원 언어를 영어로 폴백한다.

## Decision

1. **매물마다 달라지는 표시 문구는 `listings` 문서에 `{ko,en}`으로 임베드한다.** 대상은 `title`, `address.fullAddress/detail`, `nearestTransit.name`, `refundPolicy`, `description`, `extraNotes`, `roomOffers[].name`이다. 주변 편의시설은 자유 문구가 아니라 루트 `nearbyFacilities`의 코드 배열이라 번역 대상이 아니라 카탈로그 결합 대상이다(결정 3).
2. **언어와 무관한 코드는 번역하지 않고 그대로 저장한다.** `type`, `rentalType`, `genderPolicy`, `nearestTransit.type`, 건물·시설 코드, `roomOffers[].filterTags` 등이 해당한다. 가격·좌표·ID·상태·통화도 번역 대상이 아니다.
3. **UI에 표시하는 공통 코드 번역은 `listingCatalog` 컬렉션에 코드당 한 번 저장한다.** 문서 정본은 `{category, code, label:{ko,en}}`이며 `(category,code)`를 UNIQUE로 강제한다. `label`은 팀의 기존 진단 카탈로그와 같은 이름으로, 코드 하나의 표시명에 대한 언어별 값을 뜻한다. 특정 매물에 포함된 코드만이 아니라 현재 Listing UI가 사용할 수 있는 전체 허용 코드를 시드한다. MVP에 필요하지 않은 `displayOrder`, `active`는 두지 않는다. 카테고리는 19종·112건이다 — `ARC_REQUIREMENT`(2)·`BUILDING_TYPE`(7)·`CITY`(4)·`COMMON_SPACE`(8)·`CONDITION_TAG`(8)·`DISTRICT`(10)·`GENDER_POLICY`(4)·`HEATING_SYSTEM`(3)·`KITCHEN`(10)·`LAUNDRY`(5)·`LISTING_TYPE`(3)·`LIVING_AMENITY`(9)·`NEARBY_FACILITY`(6)·`PROVIDED_SUPPLY`(7)·`RENTAL_TYPE`(1)·`SECURITY_FEATURE`(7)·`SUPPORTED_LANGUAGE`(3)·`TRANSIT_TYPE`(1)·`UNIVERSITY`(14). 시설 8종은 각자 `NONE`(해당 없음)을 포함한다. `status`(`ListingStatus`)는 임대인 전용 상태값이라 카탈로그 대상이 아니다.
4. **API는 공통 표시 코드를 `{code,label}`로 반환한다.** 프론트는 `label`을 화면에 표시하고 `code`를 필터 요청·비즈니스 비교에 사용한다. 필터 요청 파라미터는 기존 UPPER_SNAKE code를 그대로 받으므로 요청 계약은 바뀌지 않는다.
5. **응답 조립 시 사용자 언어를 선택한다.** 온보딩 완료 로그인 사용자는 `UserAccountService.getLanguage(userId)`, 그 외 공개 목록·검색·상세 조회는 영어를 사용한다. Listing MVP는 `ko`만 한국어로 선택하고 `en` 및 그 밖의 언어는 영어로 폴백한다.
6. **v2→v3 변환은 Mongock forward-only ChangeUnit으로 수행한다.** 기존 validator를 잠시 해제하고 고유 문구를 다국어 문서로 변환한 뒤 v3 strict validator를 적용한다. 기존 ID·가격·필터 code는 보존한다. 레거시 시설 표시 문자열은 표준 code로 정규화한다.
7. **초기 `listingCatalog.labels` 필드는 후속 Mongock ChangeUnit에서 `label`로 이행한다.** 기존 환경과 신규 환경 모두 같은 마이그레이션 체인을 거치며, API는 이 전후 모두 선택한 언어의 문자열을 `{code,label}`로 반환한다. **단 스키마 v4는 예외다** — listing 마이그레이션 체인이 baseline으로 리셋되어 결정 6·7을 포함한 `0099`~`0114`가 삭제되고 정본은 수동 주입되므로, 위 마이그레이션 체인은 v3까지에만 해당한다. v4 카탈로그는 처음부터 `label` 단수 필드다([ADR-0039](./0039-listing-schema-v4-registration-form.md)).

## Consequences

- 프론트는 표시 문자열 사전을 별도로 유지하지 않아도 되고, 같은 응답의 `label`로 렌더링하면서 `code`로 기존 필터 요청을 유지할 수 있다.
- 공통 번역 수정은 `listingCatalog` 한 문서만 바꾸면 되며 모든 매물 문서를 재작성하지 않는다.
- 매물 고유 번역은 해당 매물과 함께 읽혀 추가 조회가 필요 없다. 공통 카탈로그는 요청 시작 시 한 번에 읽어 응답 전체에서 재사용한다.
- API의 표시 코드 필드는 문자열에서 `{code,label}` 객체로 바뀌는 하위 호환 불가 변경이다. 프론트는 표시 위치를 `.label`로 수정해야 한다.
  - **개정(2026-08-13, [ADR-0040](./0040-listing-query-api-v2-and-v1-sunset.md))**: 이 변경은 **경로 버전을 올리지 않고 `/api/v1` 응답을 깬 선례**다 — [api-design-guide](../api/api-design-guide.md)의 "하위 호환이 깨지는 변경은 `/api/v2`로 올린다"와 어긋난다. **이후 하위 호환 불가 변경은 경로 버전을 올린다**([ADR-0040](./0040-listing-query-api-v2-and-v1-sunset.md)이 매물 조회를 `/api/v2`로 이관하며 이 선례를 대체한다). 이 줄을 근거로 v1을 다시 깨지 않는다.
- 새 공통 코드를 enum/저장 데이터에 추가할 때 같은 배포에서 `listingCatalog` 정본도 추가해야 한다. 누락 시 API는 장애 대신 임시로 code를 label로 사용하지만 이는 운영 점검 대상이다.

## Validation

- 단위 테스트가 영어 기본·한국어 선택·동일 code 보존을 검증한다.
- 마이그레이션 테스트가 `{ko,en}` 변환, 잘못 분류된 시설 정규화, 재실행 멱등성을 검증한다.
- MongoDB 통합 테스트가 v4 validator·저장·검색·상세·찜·최근 본 흐름을 검증한다.
- REST Docs 테스트가 Swagger 예시를 `{code,label}`과 영어 기본 응답으로 생성한다.
