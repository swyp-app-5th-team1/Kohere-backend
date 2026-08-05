# ADR-0037. 매물 고유 문구는 listings에 임베드하고 공통 코드는 listingCatalog에서 번역한다

| 항목 | 값 |
|---|---|
| 번호 | ADR-0037 |
| 작성자 | Kohere Backend 팀 |
| 작성일 | 2026-07-17 |
| 관련 문서 | [ADR-0002](./0002-inter-module-communication-via-events.md), [ADR-0029](./0029-diagnosis-i18n-strategy.md), [ADR-0032](./0032-mongodb-migration-runner.md), [listing API](../api/specs/03-listings-favorites.md) |

## Status

Accepted

## Context

- Kohere의 MVP 기본 사용자는 외국인이므로 매물 목록·상세에 남아 있는 한국어 표시 문구를 영어로 제공해야 한다.
- 매물명·주소·역명·방 이름·상세 설명은 매물마다 달라지지만, `FEMALE_ONLY`, `SUBWAY`, `CENTRAL` 같은 값은 검색·필터·검증에서 사용하는 안정적인 코드다.
- 코드를 번역 문자열로 교체하면 MongoDB 필터와 프론트 요청 값이 깨진다. 반대로 모든 공통 코드 번역을 매물마다 중복 저장하면 번역 수정 시 전체 매물을 갱신해야 한다.
- 기존 팀 로직은 `UserAccountService.getLanguage(userId)`로 사용자가 선택한 표시 언어(`users.lang`)를 얻고 미지원 언어를 영어로 폴백한다.

## Decision

1. **매물마다 달라지는 표시 문구는 `listings` 문서에 `{ko,en}`으로 임베드한다.** 대상은 `title`, `address.fullAddress/detail`, `nearestTransit.name/nearbyPlacesDescription`, `refundPolicy.description`, `roomOffers[].name`이다. 기존 `descriptions.ko/en`은 유지한다. 사용자가 이번 범위에서 제외한 `descriptions.extraNotes`는 단일 문자열로 보존한다.
2. **언어와 무관한 코드는 번역하지 않고 그대로 저장한다.** `type`, `rentalType`, `genderPolicy`, `nearestTransit.type`, 건물·시설 코드, `roomOffers[].filterTags` 등이 해당한다. 가격·재고·좌표·ID·상태·통화도 번역 대상이 아니다.
3. **UI에 표시하는 공통 코드 번역은 `listingCatalog` 컬렉션에 코드당 한 번 저장한다.** 문서 정본은 `{category, code, label:{ko,en}}`이며 `(category,code)`를 UNIQUE로 강제한다. `label`은 팀의 기존 진단 카탈로그와 같은 이름으로, 코드 하나의 표시명에 대한 언어별 값을 뜻한다. 특정 매물에 포함된 코드만이 아니라 현재 Listing UI가 사용할 수 있는 전체 허용 코드를 시드한다. MVP에 필요하지 않은 `displayOrder`, `active`는 두지 않는다.
4. **API는 공통 표시 코드를 `{code,label}`로 반환한다.** 프론트는 `label`을 화면에 표시하고 `code`를 필터 요청·비즈니스 비교에 사용한다. 필터 요청 파라미터는 기존 UPPER_SNAKE code를 그대로 받으므로 요청 계약은 바뀌지 않는다.
5. **응답 조립 시 사용자 언어를 선택한다.** 온보딩 완료 로그인 사용자는 `UserAccountService.getLanguage(userId)`, 그 외 공개 목록·검색·상세 조회는 영어를 사용한다. Listing MVP는 `ko`만 한국어로 선택하고 `en` 및 그 밖의 언어는 영어로 폴백한다.
6. **v2→v3 변환은 Mongock forward-only ChangeUnit으로 수행한다.** 기존 validator를 잠시 해제하고 고유 문구를 다국어 문서로 변환한 뒤 v3 strict validator를 적용한다. 기존 ID·가격·재고·필터 code는 보존한다. 레거시 시설 표시 문자열은 표준 code로 정규화한다.
7. **초기 `listingCatalog.labels` 필드는 후속 Mongock ChangeUnit에서 `label`로 이행한다.** 기존 환경과 신규 환경 모두 같은 마이그레이션 체인을 거치며, API는 이 전후 모두 선택한 언어의 문자열을 `{code,label}`로 반환한다.

## Consequences

- 프론트는 표시 문자열 사전을 별도로 유지하지 않아도 되고, 같은 응답의 `label`로 렌더링하면서 `code`로 기존 필터 요청을 유지할 수 있다.
- 공통 번역 수정은 `listingCatalog` 한 문서만 바꾸면 되며 모든 매물 문서를 재작성하지 않는다.
- 매물 고유 번역은 해당 매물과 함께 읽혀 추가 조회가 필요 없다. 공통 카탈로그는 요청 시작 시 한 번에 읽어 응답 전체에서 재사용한다.
- API의 표시 코드 필드는 문자열에서 `{code,label}` 객체로 바뀌는 하위 호환 불가 변경이다. 프론트는 표시 위치를 `.label`로 수정해야 한다.
- 새 공통 코드를 enum/저장 데이터에 추가할 때 같은 배포에서 `listingCatalog` 정본도 추가해야 한다. 누락 시 API는 장애 대신 임시로 code를 label로 사용하지만 이는 운영 점검 대상이다.

## Validation

- 단위 테스트가 영어 기본·한국어 선택·동일 code 보존을 검증한다.
- 마이그레이션 테스트가 `{ko,en}` 변환, 잘못 분류된 시설 정규화, 재실행 멱등성을 검증한다.
- MongoDB 통합 테스트가 v3 validator·저장·검색·상세·찜·최근 본 흐름을 검증한다.
- REST Docs 테스트가 Swagger 예시를 `{code,label}`과 영어 기본 응답으로 생성한다.
