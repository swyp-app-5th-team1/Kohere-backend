# 매물 등록 · 탐색 · 찜 API Spec

> [api-design-guide](../api-design-guide.md) · [error-response-guide](../error-response-guide.md)를 따른다. 모든 응답은 공통 래퍼.
> 관련 유저 스토리: [user-stories](../../requirements/user-stories.md)

임대인의 매물 등록, 매물 리스트/지도 조회, 매물 상세, 찜 토글·찜 목록, 최근 본 매물을 다룬다. 도메인 모듈은 `listing`이며 도메인 에러 코드 prefix는 `LISTING`이다. `listingId`는 MongoDB ObjectId의 24자리 hex 문자열이다. 좌표는 WGS84 십진수(소수 6자리 권장), 금액은 KRW 정수, 날짜·시각은 UTC ISO-8601, enum은 UPPER_SNAKE_CASE다. 목록은 모두 **오프셋 페이지네이션**(`page`·`size`)을 사용한다. **매물 API의 정본은 `/api/v2`다** — 사진 업로드·등록(`POST /api/v2/listings/images`·`POST /api/v2/listings`)에 이어 조회 계열 5종(목록·지도·상세·찜 토글·내 찜/최근 본)도 `/api/v2`로 옮겼고, 요청·응답 모두 스키마 v4 구조를 쓴다([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md)). **기존 `/api/v1` 조회 경로는 개정 전(v3) 응답 구조를 복원한 `deprecated` 스텁**이며 빈 결과나 `404 LISTING_NOT_FOUND`만 반환한다([ADR-0040](../../adr/0040-listing-query-api-v2-and-v1-sunset.md), 아래 [v1 조회 API 종료](#v1-조회-api-종료deprecated)). 장소 후보 검색(`GET /api/v1/listings/places`)·도로명 주소 검색(`GET /api/v1/listings/addresses`)·인근 역 검색(`GET /api/v1/listings/stations`·`/stations/nearby`)만 매물 데이터를 쓰지 않아 `/api/v1`에서 그대로 동작한다 — 그중 주소 검색과 역 검색은 등록 폼 전용이라 **임대인 인증이 필요하다**([ADR-0042](../../adr/0042-road-address-search-with-ncp-geocoding.md) · [ADR-0044](../../adr/0044-nearby-station-search-with-kakao-local.md)).

> **다국어 응답 규칙([ADR-0037](../../adr/0037-listing-localization-and-code-catalog.md))**: 매물명·주소·역명·방 이름·설명·유의사항·환불 정책 문구는 서버가 사용자 언어 문자열 하나를 선택해 반환한다. `type`·`rentalType`·`genderPolicy`·`arcRequired`·행정구역(`address.city`·`address.district`)·`languagesSupported`·`nearbyFacilities`·교통/건물/시설/조건처럼 UI에 표시하는 공통 코드는 `{ "code": "FEMALE_ONLY", "label": "Female Only" }` 형태다. 프론트는 **label을 표시**하고 **code를 필터 요청과 내부 비교에 사용**한다. 로그인 사용자는 계정에서 선택한 표시 언어(`users.lang`), 비로그인 사용자는 영어가 기본이며 미지원 언어도 영어로 폴백한다. 요청의 `type`·`conditions`는 계속 기존 UPPER_SNAKE code를 보낸다. 단 `status`(`ListingStatus`)는 임대인·관리자만 읽는 관리 상태라 번역 대상이 아니며 코드 문자열 그대로 내려간다.

공통 enum:

- `ListingType`: `GOSHIWON`, `CO_LIVING`, `SHARE_HOUSE`
- `RentalType`: `MONTHLY_RENT`
- `ListingStatus`(매물 상태): `PENDING`(심사 대기), `PUBLISHED`(승인·공개), `REJECTED`(반려), `UPDATE_PENDING`(수정 심사 대기 — 임대인이 공개 중인 매물을 수정해 재심사를 기다리는 상태)
  - **승인·반려 모두 어느 상태에서든** 할 수 있다. 상태 전이에 제약을 두지 않는 이유는 **관리자의 오판을 되돌릴 수단이 서버에 있어야** 하기 때문이다 — 잘못 반려한 매물을 되살리는 **재승인**(`REJECTED → PUBLISHED`), 공개 후 문제가 발견된 매물을 내리는 **사후 반려**(`PUBLISHED → REJECTED`), 이미 반려한 매물의 **사유 정정**(`REJECTED → REJECTED`)이 모두 정상 경로다.
  - 다만 **이미 공개 중인 매물의 재승인은 아무 일도 하지 않는다**. 같은 값으로 저장해도 결과는 같지만 `updatedAt`이 바뀌면 목록 기본 정렬(찜 수 → 최신 수정순)에서 그 매물만 위로 올라가기 때문이다.
  - 전이 주체는 관리자이며 상세는 [관리자 매물 심사](#관리자-매물-심사)를 본다.
  - **`UPDATE_PENDING`만 임대인이 만드는 상태다.** 공개 중인 매물을 임대인이 수정하면 이 상태가 되고, 그동안 세입자 조회에서 빠졌다가 관리자가 승인하면 `PUBLISHED`로 돌아온다. 반려된 매물을 수정하면 `PENDING`으로 돌아간다. 상세는 [임대인 매물 관리](#임대인-매물-관리)를 본다.
- `ListingSort`(이름 기반 정렬 프리셋): `RECOMMENDED`(기본), `PRICE_ASC`, `DISTANCE`
- `ConditionTag`(매물 옵션 필터 8종): `MOVE_IN_NOW`(즉시 입주), `FEMALE_ONLY`(여성 전용), `MEALS_INCLUDED`(식사 제공), `DOUBLE_ROOM`(2인실), `PRIVATE_BATH`(개인 욕실), `ENGLISH_OK`(영어 소통 가능), `ADDRESS_REGISTRATION`(전입신고 가능), `NO_MAINT_FEE`(관리비 없음)
- `ArcRequirement`(매물 루트 `arcRequired` 값): `REQUIRED`, `NOT_REQUIRED`. ARC 없이 입주할 수 있는지는 조건 태그가 아니라 이 필드로 표현한다
- **시설 8종**은 전부 `NONE`(해당 없음)을 갖는다 — `HeatingSystem`: `CENTRAL`, `INDIVIDUAL`, `NONE` / `KitchenFacility`·`LaundryFacility`·`LivingAmenity`·`SecurityFeature`·`CommonSpaceType`·`ProvidedSupply`·`NearbyFacility`도 각자 목록 끝에 `NONE`이 붙는다. **해당 시설이 하나도 없으면 `NONE` 하나만 보낸다** — 이 값은 다른 코드와 함께 보낼 수 없다(아래 등록 요청 참조).
  - **`NONE`과 `ETC`/`OTHER`는 다르다.** `ETC`(행정구역)·`OTHER`는 「목록에 없는 그 외의 값」이고, `NONE`은 「해당하는 것이 없음」이다. 응답에서도 `NONE`은 다른 코드와 똑같이 `{code, label}`로 나가므로 화면은 「없음」 칩을 그대로 그리면 된다.

> `ListingSort`는 api-design-guide §6의 일반 `?sort=field,(asc|desc)` 형식이 아닌 **이름 기반 정렬 프리셋**이다(추천 정렬 등 단일 필드로 표현되지 않는 정렬이 있어 enum으로 둔다). 찜 목록은 별도 정렬 파라미터 없이 `favoritedAt desc`로 고정된다.

## 엔드포인트 요약

| Method | Path | 설명 | 인증 | 성공 status |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/listings/addresses` | 도로명 주소 검색(임대인) — 등록 폼의 주소 칸을 채우고 좌표를 함께 받는다 | 필수(임대인) | 200 |
| GET | `/api/v1/listings/stations` | 인근 역 검색(임대인) — 역 이름으로 찾는다 | 필수(임대인) | 200 |
| GET | `/api/v1/listings/stations/nearby` | 인근 역 목록(임대인) — 매물 좌표 주변을 가까운 순으로 | 필수(임대인) | 200 |
| POST | `/api/v2/listings/images` | 매물 사진 업로드(임대인) — **한 장씩**, 저장 키를 돌려준다 | 필수(임대인) | 201 |
| POST | `/api/v2/listings` | 매물 등록(임대인) — 올려 둔 사진 키를 참조해 승인 대기(`PENDING`) 상태로 저장 | 필수(임대인) | 201 |
| PUT | `/api/v2/listings/{listingId}` | 매물 수정(임대인) — 등록과 같은 본문으로 **전체 교체**하고 재심사에 올린다 | 필수(임대인) | 200 |
| GET | `/api/v2/listings` | 매물 리스트(필터·정렬·오프셋 페이지) | 선택 | 200 |
| GET | `/api/v1/listings/places` | 네이버 지역 검색 장소 후보(최대 5개) — **유일하게 `/api/v1`에 남는 경로** | 불필요 | 200 |
| GET | `/api/v2/listings/map` | 지도 마커 조회(bbox 내 개별 매물 좌표) | 선택 | 200 |
| GET | `/api/v2/listings/{listingId}` | 매물 상세 조회(정식 로그인 시 최근 본 기록) | 선택 | 200 |
| POST | `/api/v2/listings/{listingId}/favorite` | 찜 등록(토글) | 필수 | 201 (신규) / 200 (이미 찜) |
| DELETE | `/api/v2/listings/{listingId}/favorite` | 찜 해제(토글) | 필수 | 200 |
| GET | `/api/v2/users/me/favorites` | 내 찜한 매물 목록 | 필수 | 200 |
| GET | `/api/v2/users/me/recent-listings` | 최근 본 매물(최신순 최대 10건) | 필수 | 200 |
| GET | `/api/v2/users/me/listings` | 내 매물 목록(임대인) — **모든 상태**, `status`로 필터, 최근 수정순 | 필수(임대인) | 200 |
| GET | `/api/v2/users/me/listings/{listingId}` | 내 매물 상세(임대인) — 수정 폼이 프리필할 **전 필드** | 필수(임대인) | 200 |
| GET | `/api/v1/admin/listings` | 매물 심사 목록(관리자) — **모든 상태**, `status`로 필터 | 필수(관리자) | 200 |
| GET | `/api/v1/admin/listings/{listingId}` | 심사용 상세(관리자) — 저장된 **전 필드** | 필수(관리자) | 200 |
| POST | `/api/v1/admin/listings/{listingId}/approval` | 매물 승인(관리자) → `PUBLISHED` | 필수(관리자) | 200 |
| POST | `/api/v1/admin/listings/{listingId}/rejection` | 매물 반려(관리자) → `REJECTED` + 사유 저장 | 필수(관리자) | 200 |

> 위 표의 `/api/v2` 경로가 정본이다. 같은 경로의 `/api/v1` 버전은 빈 결과·404만 돌려주는 `deprecated` 스텁으로만 남아 있다(아래 [v1 조회 API 종료](#v1-조회-api-종료deprecated)).
>
> 주소 검색·역 검색·사진 업로드·매물 등록·매물 수정·내 매물 조회만 임대인 전용이고 나머지는 세입자·비로그인 사용자를 위한 조회 API다. 여덟 경로는 `SecurityConfig`에 **`GET /api/v1/listings/addresses`·`GET /api/v1/listings/stations`·`GET /api/v1/listings/stations/nearby`·`POST /api/v2/listings`·`POST /api/v2/listings/images`·`PUT /api/v2/listings/*`·`GET /api/v2/users/me/listings`·`GET /api/v2/users/me/listings/*`를 `hasRole("USER")`로 못박은 명시 매처**를 둔다([ADR-0010](../../adr/0010-jwt-authentication-filter.md)) — 주소 검색과 `/stations` 매처는 공개 조회 매처(`GET /api/v1/listings/*` `permitAll`)보다 **먼저** 선언해야 한다(둘 다 한 세그먼트라 그 매처에 잡힌다). 먼저 매칭된 규칙이 이기므로 뒤에 두면 인증 규칙이 통째로 무시된다 — 명시하지 않고 `anyRequest().authenticated()`에 맡기면 온보딩 스코프(`ROLE_ONBOARDING`) 토큰도 컨트롤러에 도달한다. **매물 수정 매처는 공개 조회 매처와 겹치지 않는다** — 그쪽은 `GET`으로 한정돼 있어 `PUT`을 잡지 않으므로, 명시하지 않으면 곧바로 `anyRequest().authenticated()`로 떨어진다. 반대로 **내 매물 조회는 `/api/v2/listings/mine`에 둘 수 없다** — 그 경로는 공개 조회 매처(`GET /api/v2/listings/*` `permitAll`)에 먼저 잡혀 비로그인에 열리므로 `/api/v2/users/me` 아래에 둔다. 임대인 여부(`userType=LANDLORD`)는 서비스가 다시 검사해 `403 FORBIDDEN`으로 거른다. 등록된 매물은 `PENDING`이라 아래 **세입자용** 조회 API 어디에도 나오지 않는다 — 그쪽 조회는 `PUBLISHED` 한정이며, 임대인이 상태와 무관하게 자기 매물을 보는 경로는 [임대인 매물 관리](#임대인-매물-관리)의 내 매물 조회 2종이다.
>
> 관리자 심사 4종은 `SecurityConfig`에 **`/api/v1/admin/**`를 `hasRole("USER")`로 못박은 명시 매처**를 둔다 — 명시하지 않으면 `anyRequest().authenticated()`로 떨어져 온보딩 스코프 토큰이 컨트롤러까지 도달한다. 관리자 여부(`userType=ADMIN`)는 매처로 표현할 수 없으므로 **서비스가 다시 검사해 `403 FORBIDDEN`** 으로 거른다(임대인 게이트와 같은 이중 인가). 토큰에는 관리자 여부를 담지 않아 **권한 부여·회수가 즉시 반영**된다. 반대 방향도 막힌다 — 관리자는 찜·예약·채팅·커뮤니티 같은 세입자·임대인 기능을 호출할 수 없고, 각 서비스의 허용 목록 게이트(세입자 또는 임대인만 통과)가 `403 FORBIDDEN`을 낸다.
>
> 공개 조회도 `SecurityConfig`에 **`GET /api/v2/listings`·`GET /api/v2/listings/*` `permitAll` 명시 매처**가 필요하다 — 넣지 않으면 `anyRequest().authenticated()`로 떨어져 비회원 매물 탐색이 `401`이 된다(v1 공개 조회 매처와 같은 이유). 결국 `/api/v2/listings` 네임스페이스는 **GET은 공개, POST(등록)·PUT(수정)은 `hasRole("USER")`** 로 메서드별로 갈린다. 찜 토글(`POST`·`DELETE /api/v2/listings/*/favorite`)과 내 스코프(`GET /api/v2/users/me/favorites`·`/recent-listings`)는 v1과 동일하게 `hasRole("USER")` 매처를 유지하고, 내 매물 조회(`GET /api/v2/users/me/listings`·`/listings/*`)도 같은 나열에 함께 넣는다 — 이쪽은 v1 대응 경로가 없다.
>
> 목록·지도·장소 후보·상세는 가입 전부터 사용할 수 있는 공개 API다(경로는 v2 기준이고 장소 후보 검색만 `/api/v1`이다). 온보딩을 완료한 정식 사용자 토큰이 있으면 계정 언어를 적용하고, 상세에서는 실제 찜 상태와 최근 본 기록도 적용한다. 목록의 `favorited`는 현재 구현상 로그인 여부와 관계없이 항상 `false`다. 비로그인·온보딩 미완료·위조/형식 오류 토큰은 공개 조회에서 익명으로 처리해 영어와 `favorited=false`를 사용하며 최근 본 기록을 남기지 않는다. 단, 만료 토큰은 공개 매물 조회에서도 `401 TOKEN_EXPIRED`다. 찜·찜 목록·최근 본 목록은 온보딩 완료 사용자(`ROLE_USER`) 전용이며, 토큰 없음·위조는 `401 UNAUTHENTICATED`, 만료는 `401 TOKEN_EXPIRED`, 온보딩 미완료 토큰은 `403 AUTH_ONBOARDING_REQUIRED`다.

## v1 조회 API 종료(deprecated)

출시된 앱은 `/api/v1/listings` 계열을 호출한다. 그런데 스키마 v4 개편([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md))으로 **`/api/v1` 경로가 v4 구조를 반환하게 되어 구버전 앱이 깨진 상태**다. 이를 바로잡기 위해 조회 계열을 버전으로 분리한다([ADR-0040](../../adr/0040-listing-query-api-v2-and-v1-sunset.md)).

| | `/api/v1` | `/api/v2` |
| --- | --- | --- |
| 응답 구조 | **개정 전(v3) 그대로 복원** | 개정 후(v4) |
| 데이터 | **0건 · DB 미접근** | 실데이터 |
| 상태 | `deprecated` · 제거 시점 **미정** | 정본 |

구버전 앱은 계약이 깨진 응답 대신 **"매물 없음" 화면**을 보고 앱 업데이트로 유도된다. v1이 v4 데이터로 v3 모양을 조립하지 않으므로 하위 호환을 위해 없는 값을 날조할 일도 없다(`deposit: 0`으로 채우거나 빈 재고를 만들어 내지 않는다).

### v1 스텁 동작

| Method | Path | 동작 |
| --- | --- | --- |
| GET | `/api/v1/listings` | **빈 페이지** — `content: []`, `page.totalElements: 0` |
| GET | `/api/v1/listings/map` | **빈 결과** — `markers: []`, `total: 0` |
| GET | `/api/v1/listings/{listingId}` | **`404 LISTING_NOT_FOUND`** |
| POST | `/api/v1/listings/{listingId}/favorite` | **`404 LISTING_NOT_FOUND`** |
| DELETE | `/api/v1/listings/{listingId}/favorite` | **`404 LISTING_NOT_FOUND`** |
| GET | `/api/v1/users/me/favorites` | **빈 페이지** — `content: []`, `page.totalElements: 0` |
| GET | `/api/v1/users/me/recent-listings` | **빈 결과** — `content: []` |
| GET | `/api/v1/listings/places` | **그대로 동작** — 매물 데이터를 쓰지 않는다 |
| GET | `/api/v1/listings/addresses` | **그대로 동작** — 매물 데이터를 쓰지 않는다(등록 폼 전용, 임대인 인증 필요) |
| GET | `/api/v1/listings/stations` · `/stations/nearby` | **그대로 동작** — 매물 데이터를 쓰지 않는다(등록 폼 전용, 임대인 인증 필요) |

- 빈 결과·404는 **DB에 닿지 않고** 만든다. 필터·bbox·키워드 값과 무관하게 같은 응답이며, 매물이 실제로 있는지도 확인하지 않는다.
- 응답 래퍼(`{ success, data, error }`)와 페이지 구조(`page.number`·`size`·`totalElements`·`totalPages`·`hasNext`)는 v3 계약 그대로다 — 구버전 앱의 파싱이 깨지지 않아야 "매물 없음" 화면에 도달한다.
- 인증 규칙도 v3 그대로다. 공개 조회는 비로그인도 `200`(빈 결과)이고, 찜 토글·내 스코프는 여전히 `ROLE_USER` 전용이라 토큰이 없으면 `404`가 아니라 `401 UNAUTHENTICATED`다. 즉 **인가 판정이 먼저**고 스텁 응답은 그 뒤다.
- **`GET /api/v1/listings/places`·`/addresses`·`/stations`(+`/stations/nearby`)만 예외**로 살아 있다. 외부 API(네이버 지역 검색 · NCP Geocoding · 카카오 로컬)만 호출하고 매물 데이터를 쓰지 않아 v4 개편의 영향을 받지 않았기 때문이다. 라우팅상 리터럴 `places`·`addresses`·`stations` 세그먼트가 `{listingId}` 템플릿보다 먼저 매칭되므로 상세 스텁의 404와 충돌하지 않는다.
- **Swagger에서 v1 오퍼레이션은 `deprecated` 배지로 구분된다.** OpenAPI `deprecated: true`가 실려 Swagger UI가 취소선과 배지를 붙인다. summary에는 버전 표기를 두지 않는다 — 경로가 이미 `/api/v1`을 보여준다.
- **제거 시점은 정하지 않았다.** 구버전 앱 사용 비중을 보고 별도로 결정하며, 그때까지 v1 스텁은 위 표대로 유지된다.
- 진단 추천(`GET /api/v1/diagnoses/{id}/recommendations`)은 **이 종료 대상이 아니다** — 추천 응답 구조는 v4 개편 전후로 바뀌지 않았으므로 v1·v2 양쪽 모두 실데이터를 그대로 반환한다([ADR-0040](../../adr/0040-listing-query-api-v2-and-v1-sunset.md) Status · [02-diagnosis-recommendation](./02-diagnosis-recommendation.md)).

## 상세

> 아래 상세는 모두 **`/api/v2` 정본 경로** 기준이다(장소 후보 검색만 `/api/v1`). 같은 경로의 `/api/v1` 조회는 위 [v1 스텁 동작](#v1-스텁-동작)을 따른다.

### GET /api/v1/listings/addresses — 도로명 주소 검색(임대인)

- 설명: 등록 폼의 주소 칸을 채우기 위해 **표준 도로명 주소와 좌표**를 찾는다. 임대인이 후보 하나를 고르면 그 `roadAddress`·`lat`·`lng`를 매물 등록(`POST /api/v2/listings`)의 `address.fullAddress`·`address.lat`·`address.lng`에 그대로 담는다([ADR-0042](../../adr/0042-road-address-search-with-ncp-geocoding.md)).
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`) 중 **임대인**(`userType=LANDLORD`). 등록 폼 전용 API라 공개하지 않는다(공개로 두면 인증 없이 외부 호출 쿼터를 소모하는 지오코딩 프록시가 된다).
- 경로: 매물 데이터를 쓰지 않아 v4 개편의 영향을 받지 않으므로 **`/api/v1`에 둔다**(장소 후보 검색과 같은 이유). 다만 인증 정책은 정반대다.
- 책임 범위: 주소 후보만 반환하며 매물을 조회하지도, 저장하지도 않는다. 등록은 이 API를 부른 적이 있는지 알지 못한다 — **좌표를 되돌려 보내는 것은 클라이언트의 책임**이다.
- 외부 연동: 아웃바운드 포트 `AddressSearchClient`(인프라 어댑터 `NcpGeocodeClient` — NCP Maps Geocoding API)로 **동기 호출**한다. HTTP 오류·타임아웃·인증정보 누락·응답 형식 이상은 모두 `502 UPSTREAM_ERROR`다. 인증정보는 환경변수 `NAVER_GEOCODE_CLIENT_ID`/`NAVER_GEOCODE_CLIENT_SECRET`(SSM SecureString)로 주입하며, **네이버 지역 검색(`NAVER_SEARCH_*`)과는 콘솔도 값도 다르다.**

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `keyword` | string | 필수 | — | 검색할 주소. 앞뒤 공백 제거 후 1~100자 |

서버가 외부 호출에 고정하는 값:

| 이름 | 값 | 설명 |
| --- | --- | --- |
| `count` | `5` | 한 번에 받을 최대 후보 수 |
| `page` | `1` | 첫 페이지 고정(후보를 더 넘겨보는 UI가 없다) |
| `language` | `kor` | 한국어 응답. 영어 표기는 `englishAddress`로 함께 온다 |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "items": [
      {
        "roadAddress": "서울특별시 서대문구 신촌로 12",
        "jibunAddress": "서울특별시 서대문구 창천동 1-1",
        "englishAddress": "12, Sinchon-ro, Seodaemun-gu, Seoul, Republic of Korea",
        "lat": 37.5559918,
        "lng": 126.9368647
      }
    ]
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `roadAddress` | string | 표준 도로명 주소. **등록 요청의 `address.fullAddress`에 그대로 담는다.** 건물명이 붙어 있을 수 있으며 서버는 다듬지 않는다 |
| `jibunAddress` | string | 지번 주소. 사용자가 후보를 구분할 때 보조로 표시한다(등록에는 보내지 않는다) |
| `englishAddress` | string | 영문 표기. 외국인 세입자 화면의 참고용이며 등록에는 보내지 않는다 |
| `lat` / `lng` | number | WGS84 십진수 좌표. **등록 요청의 `address.lat`·`address.lng`에 그대로 담는다** |

주의사항:

- **모든 후보를 고를 수 있다.** 검색은 전국을 돌려주고 등록도 전국을 받는다 — 카탈로그(`CITY`·`DISTRICT`)가 모르는 지역이면 매물의 `address.city`·`district`가 `ETC`로 저장되고 관리자 승인 심사가 확정한다([ADR-0046](../../adr/0046-administrative-region-as-catalog-data.md)). 응답에 `city`·`district` 코드를 내려주지 않으므로 지원 지역이 늘어도 클라이언트는 그대로 둔다.
- **부분 키워드에는 약하다.** `신촌`처럼 도로명 일부만 보내면 결과가 비고, `신촌로 12`처럼 도로명 + 건물번호를 넣어야 후보가 나온다. 폼에서 그렇게 안내한다.
- 정상적으로 결과가 없으면 `200 OK`와 `data.items=[]`다(장애가 아니다).
- 도로명이 없는 결과(지번만 있는 주소)는 응답에서 제외한다 — 등록이 받는 것은 도로명 주소다.
- 외부 응답의 `status`·`meta`·`distance`·`addressElements`는 공개하지 않는다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `keyword` 누락·공백·길이(1~100자) 위반 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰(`ROLE_ONBOARDING`) |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
| 502 | `UPSTREAM_ERROR` | NCP HTTP 오류·타임아웃·인증정보 누락·응답 또는 좌표 형식 이상 |

### GET /api/v1/listings/stations — 인근 역 검색(임대인)

- 설명: 등록 폼의 **인근 역 칸**을 채우기 위해 역 이름으로 후보를 찾는다. 임대인이 하나를 고르면 그 `name`을 등록(`POST /api/v2/listings`)의 `nearestTransit.name`에 그대로 담는다([ADR-0044](../../adr/0044-nearby-station-search-with-kakao-local.md)).
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`) 중 **임대인**(`userType=LANDLORD`). 주소 검색과 같은 이유로 공개하지 않는다(공개로 두면 인증 없이 카카오 호출 쿼터를 소모하는 프록시가 된다).
- 경로: 매물 데이터를 쓰지 않아 v4 개편의 영향을 받지 않으므로 **`/api/v1`에 둔다**.
- 외부 연동: 아웃바운드 포트 `NearbyPlaceSearchClient`(어댑터 `KakaoLocalPlaceClient` — 카카오 로컬 *키워드로 장소 검색*)로 **동기 호출**한다. 서버가 `category_group_code=SW8`(지하철역)을 고정하므로 카페·음식점이 섞이지 않는다. HTTP 오류·타임아웃·REST 키 누락·응답 형식 이상은 모두 `502 UPSTREAM_ERROR`다. 인증정보는 환경변수 `KAKAO_REST_API_KEY`(SSM SecureString)로 주입하며 **네이버·NCP와 콘솔도 값도 다르다.**

Query 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `keyword` | string | 필수 | 검색할 역 이름. 앞뒤 공백 제거 후 1~50자 |
| `lat` | number | 선택 | 매물 위도(WGS84). 주소 검색이 준 값을 그대로 넘긴다 |
| `lng` | number | 선택 | 매물 경도(WGS84) |

- **`lat`·`lng`는 둘 다 있거나 둘 다 없어야 한다.** 하나만 오면 `400 INVALID_INPUT`이다.
- 좌표를 함께 보내면 **거리순 정렬**이 되고 `distanceMeters`·`suggestedWalkMinutes`가 채워진다. 없으면 정확도순이고 두 필드는 `null`이다.
- **폼은 좌표를 넘기는 것을 권장한다** — 등록 순서상 주소를 먼저 검색하므로 좌표는 이미 손에 있고, 그래야 전국에 같은 이름이 있는 역(예: `시청역`)을 거리로 가려낼 수 있다.

서버가 외부 호출에 고정하는 값:

| 이름 | 값 | 설명 |
| --- | --- | --- |
| `category_group_code` | `SW8` | 지하철역만 남긴다 |
| `size` | `10` | 한 번에 받을 최대 후보 수(카카오 상한 15) |
| `page` | `1` | 첫 페이지 고정 |
| `sort` | 좌표 있으면 `distance`, 없으면 `accuracy` | 거리순은 좌표가 있어야 의미가 있다 |

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "items": [
      {
        "name": "신촌역 2호선",
        "roadAddress": "서울 서대문구 신촌로 90",
        "jibunAddress": "서울 서대문구 창천동 30-33",
        "lat": 37.555134,
        "lng": 126.936893,
        "distanceMeters": 320,
        "suggestedWalkMinutes": 4
      }
    ]
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `name` | string | 역 이름. **등록 요청의 `nearestTransit.name`에 그대로 담는다.** 카카오 표기를 다듬지 않는다 |
| `roadAddress` | string | 역 출입구 도로명 주소. 후보를 구분할 때 보조로 표시한다. 제공되지 않으면 빈 문자열 |
| `jibunAddress` | string | 지번 주소. 보조 표시용 |
| `lat` / `lng` | number | 역의 WGS84 좌표. 폼이 지도에 핀을 찍는 용도이며 **등록에는 보내지 않는다** |
| `distanceMeters` | integer \| null | 매물 좌표에서 역까지의 **직선거리**. 좌표를 준 요청에만 채워진다 |
| `suggestedWalkMinutes` | integer \| null | 도보 시간 **제안값**(`ceil(distanceMeters / 80)`, 최소 1). `distanceMeters`가 있을 때만 채워진다 |

주의사항:

- **`suggestedWalkMinutes`는 제안이지 정답이 아니다.** 직선거리 기준이라 실제 보행 경로(육교·지하도·블록)보다 짧게 나온다. 서버는 이 값을 강제하지 않는다 — 등록 요청의 `nearestTransit.walkMinutes`는 클라이언트가 보낸 값을 그대로 저장하며, 실제 도보 시간과 맞는지는 승인 심사가 본다.
- **환승역은 노선별로 여러 건이 온다**(`신촌역 2호선`·`신촌역 경의중앙선`). 노선이 보이는 편이 선택에 도움이 되므로 서버가 합치지 않는다.
- 정상적으로 결과가 없으면 `200 OK`와 `data.items=[]`다(장애가 아니다).
- 외부 응답의 `id`·`place_url`·`phone`·`category_name`·`meta`는 공개하지 않는다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `keyword` 누락·공백·길이(1~50자) 위반 / `lat`·`lng`가 WGS84 범위를 벗어남 / 좌표를 하나만 보냄 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰(`ROLE_ONBOARDING`) |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
| 502 | `UPSTREAM_ERROR` | 카카오 HTTP 오류·타임아웃·REST 키 누락·응답 또는 좌표 형식 이상 |

### GET /api/v1/listings/stations/nearby — 인근 역 목록(임대인)

- 설명: 임대인이 아무것도 입력하지 않아도 **매물 좌표 주변의 역을 가까운 순으로** 보여준다. 응답 구조와 사용법은 위 역 검색과 같다.
- 인증·경로·외부 연동: 위와 동일하다. 다만 카카오 로컬 *카테고리로 장소 검색*을 호출한다.

Query 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `lat` | number | 필수 | 매물 위도(WGS84, -90~90) |
| `lng` | number | 필수 | 매물 경도(WGS84, -180~180) |

서버가 외부 호출에 고정하는 값:

| 이름 | 값 | 설명 |
| --- | --- | --- |
| `category_group_code` | `SW8` | 지하철역 |
| `radius` | `2000` | 반경 2km(도보 25분권). 카카오 허용 범위는 0~20,000m |
| `sort` | `distance` | 가까운 순 |
| `size` | `15` | 카카오 상한 |
| `page` | `1` | 첫 페이지 고정 |

성공 Response (200): 위 역 검색과 **같은 구조**다. 좌표가 항상 있으므로 `distanceMeters`·`suggestedWalkMinutes`가 늘 채워진다.

주의사항:

- 반경 2km 안에 역이 없으면 장애가 아니라 `200 OK`와 `data.items=[]`다.

발생 가능한 에러: 위 역 검색과 같다. 다만 `400 INVALID_INPUT`의 조건은 **`lat`·`lng` 누락 또는 WGS84 범위 위반**이다.

### POST /api/v2/listings/images — 매물 사진 업로드(임대인)

- 설명: 등록 폼에서 고른 사진을 **한 장씩** 올린다. 저장 위치(키)와 미리보기 URL을 돌려주며, 그 키를 모아 매물 등록(`POST /api/v2/listings`)에 보낸다. 매물을 만들지는 않는다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`) 중 **임대인**(`userType=LANDLORD`).
- Content-Type: **`multipart/form-data`** — 파일 part `file` 하나뿐이다.
- 왜 한 장씩인가: 요청이 파일마다 갈려야 브라우저가 **파일별 진행률·전송 속도**를 줄 수 있고, 실패한 파일만 다시 올릴 수 있다([ADR-0041](../../adr/0041-listing-image-upload-to-s3.md)).

Path·Query 파라미터: 없음

Request Parts:

| part | Content-Type | 개수 | 내용 |
| --- | --- | --- | --- |
| `file` | `image/jpeg` · `image/png` · `image/webp` · `image/heic` | 1 | 사진 파일. 장당 **10MB** 이하 |

성공 Response (201):

```jsonc
{
  "success": true,
  "data": {
    "key": "uploads/42/3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg",
    "url": "https://cdn.kohere.app/uploads/42/3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg"
  },
  "error": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `key` | string | 저장 키. **매물 등록 요청에 이 값을 그대로 담는다** |
| `url` | string | 폼에서 미리보기로 띄울 주소. 등록이 끝나면 사진이 확정 위치로 옮겨 가므로 **이 URL은 곧 무효가 된다** — 등록 후에는 등록 응답의 URL을 쓴다 |

업로드 주의사항:

- **이 사진은 아직 어느 매물의 것도 아니다.** 임대인별 임시 위치(`uploads/{landlordId}/…`)에 놓이고, 매물 등록이 그 키를 참조할 때 비로소 매물에 붙는다.
- **올린 뒤 7일 안에 등록해야 한다.** 그 뒤에는 임시 사진이 자동 삭제되고, 등록 요청은 `400 LISTING_IMAGE_KEY_NOT_FOUND`가 된다.
- **폼에서 뺀 사진은 그냥 등록 요청에 담지 않으면 된다.** 삭제 API는 없고, 참조되지 않은 임시 사진은 7일 뒤 사라진다.
- 서버는 형식을 변환하지 않는다 — HEIC를 보내면 HEIC로 저장된다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `LISTING_IMAGE_REQUIRED` | 올린 파일이 비었음 |
| 400 | `MALFORMED_REQUEST` | `file` part 자체가 없거나 multipart 형식 위반 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰(`ROLE_ONBOARDING`) |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
| 413 | `LISTING_IMAGE_TOO_LARGE` | 사진이 10MB를 넘음(요청이 서블릿 상한 32MB 이하라 핸들러까지 도달한 경우) |
| 413 | `PAYLOAD_TOO_LARGE` | 요청이 서블릿 상한 32MB를 넘음 — multipart 해석이 핸들러 탐색보다 앞서 일어나 어느 엔드포인트인지 알 수 없어 도메인 코드 대신 공통 코드가 나간다 |
| 415 | `LISTING_IMAGE_UNSUPPORTED_TYPE` | 형식이 `image/jpeg` · `image/png` · `image/webp` · `image/heic` 중 하나가 아님 |
| 502 | `UPSTREAM_ERROR` | 사진 저장소 업로드 실패 |

### POST /api/v2/listings — 매물 등록(임대인)

- 설명: 임대인이 등록 폼에서 입력한 지점·건물·공용시설·주변 시설·방 타입 정보와 **미리 올려 둔 사진의 키**를 하나의 매물로 저장한다. 저장 직후 상태는 **`PENDING`(승인 대기)** 이라 세입자용 조회 API에는 노출되지 않으며, 공개 전환은 [관리자 매물 심사](#관리자-매물-심사)의 승인 API가 담당한다. 등록한 내용을 나중에 고치는 것은 [임대인 매물 관리](#임대인-매물-관리)의 `PUT /api/v2/listings/{listingId}`다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`) 중 **임대인**(`userType=LANDLORD`). `landlordId`는 access 토큰에서 읽으며 요청 본문에 담지 않는다.
- 경로: 매물 도메인의 첫 `/api/v2` 엔드포인트였고, 이제 조회 계열 6종도 같은 네임스페이스로 옮겨 왔다. `/api/v2/listings`는 **GET이면 공개 매물 조회, POST면 임대인 등록**으로 메서드에 따라 갈린다. 요청·응답이 모두 스키마 v4 구조라 `deprecated`된 `/api/v1` 조회 스텁과 섞이지 않는다.
- Content-Type: **`application/json`**. 사진 파일은 이 요청에 싣지 않고 `POST /api/v2/listings/images`가 돌려준 **키**로 참조한다([ADR-0041](../../adr/0041-listing-image-upload-to-s3.md)).
- 선행 호출: 주소는 **`GET /api/v1/listings/addresses`로 먼저 검색**해 고른 후보의 `roadAddress`·`lat`·`lng`를 그대로 담는다([ADR-0042](../../adr/0042-road-address-search-with-ncp-geocoding.md)). 인근 역도 **`GET /api/v1/listings/stations`(또는 `/stations/nearby`)로 먼저 검색**해 고른 후보의 `name`을 담는다([ADR-0044](../../adr/0044-nearby-station-search-with-kakao-local.md)). 사진 키와 같은 방식이다 — 앞선 호출이 준 값을 되돌려 보낸다.

Path·Query 파라미터: 없음

Request Body:

```jsonc
{
  "title": "신촌 도보 5분 1인실 고시원",
  "type": "GOSHIWON",
  "contact": {
    "managerName": "Kim Woon-yeong",
    "phone": "+82) 10-1234-5678"
  },
  "businessRegistrationNumber": "1234567890",
  "blogUrl": "https://blog.naver.com/kohere-goshiwon",
  "address": {
    "fullAddress": "서울특별시 서대문구 신촌로 12",
    "detail": "3층 305호",
    "lat": 37.5559918,
    "lng": 126.9368647
  },
  "building": {
    "type": "VILLA",
    "totalFloors": 4,
    "usedFloorRange": "1~2",
    "parkingAvailable": true,
    "elevatorAvailable": true
  },
  "genderPolicy": "FEMALE_ONLY",
  "languagesSupported": ["ENGLISH", "CHINESE"],
  "ageRange": "20~35",
  "arcRequired": "NOT_REQUIRED",
  "facilities": {
    "heatingSystem": ["CENTRAL"],
    "kitchen": ["SHARED_REFRIGERATOR", "MICROWAVE"],
    "laundry": ["WASHER", "DRYING_RACK"],
    "livingAmenities": ["WIFI", "TV"],
    "securityFeatures": ["CCTV", "ENTRANCE_DOOR_LOCK"],
    "commonSpaces": ["SHARED_KITCHEN", "SHARED_TOILET"],
    "providedSupplies": ["BEDDING", "TISSUE"]
  },
  "nearbyFacilities": ["CONVENIENCE_STORE", "HOSPITAL_PHARMACY"],
  "nearestTransit": {
    "type": "SUBWAY",
    "name": "신촌역",
    "walkMinutes": 5
  },
  "description": "지하철역에서 도보 5분 거리의 관리가 잘 된 고시원입니다.",
  "extraNotes": "객실 내 취사 금지. 오후 11시 이후 정숙.",
  "refundPolicy": "입주 7일 전까지 취소하면 전액 환불합니다.",
  "imageKeys": [
    "uploads/42/3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg",
    "uploads/42/8d21b7f0-5c4d-4e6f-8a09-3c1d6e7f5b22.jpg"
  ],
  "roomOffers": [
    {
      "name": "스탠다드 1인실",
      "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
      "pricing": {
        "monthlyRent": 380000,
        "deposit": 200000,
        "maintenanceFee": 20000
      },
      "filterTags": ["ENGLISH_OK", "ADDRESS_REGISTRATION"],
      "roomImageKeys": [
        "uploads/42/7b2e8841-2a3b-4c5d-8e9f-0a1b2c3d4e55.jpg",
        "uploads/42/c14d05a6-6b7c-4d8e-9f01-2a3b4c5d6e66.jpg"
      ]
    }
  ],
  "preferredNationalities": ["JAPAN", "CHINA"],
  "contractDifficulties": ["LANGUAGE", "PAYMENT"],
  "serviceFeedback": "외국인 세입자용 계약서 번역 템플릿이 있으면 좋겠습니다."
}
```

요청 필드:

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `title` | string | 필수 | 지점명(한국어). 공백 불가 |
| `type` | `ListingType` | 필수 | 공간 유형. 카탈로그 `LISTING_TYPE` 대조 |
| `contact.managerName` | string | 필수 | 지점 운영자명 |
| `contact.phone` | string | 필수 | 지점 대표 전화(문의 수신). `+82) 10-1234-5678` 형식 |
| `businessRegistrationNumber` | string | 필수 | 숫자 10자리. **형식만 검증하고 그대로 저장**한다(아래 요청 주의사항) |
| `blogUrl` | string | 선택 | 지점 블로그. 값이 있으면 URL 형식 |
| `address.fullAddress` | string | 필수 | 도로명 주소. **주소 검색 응답의 `roadAddress`를 그대로** 보낸다. 서버는 받은 값을 정규화 없이 저장한다 |
| `address.detail` | string | 선택 | 동·호수 등 상세 주소. 미입력이면 `null` |
| `address.lat` | number | 필수 | 위도. **주소 검색 응답의 `lat`을 그대로** 보낸다. WGS84 범위(-90~90) |
| `address.lng` | number | 필수 | 경도. **주소 검색 응답의 `lng`을 그대로** 보낸다. WGS84 범위(-180~180) |
| `building.type` | `BuildingType` | 필수 | 건물 형태. 카탈로그 `BUILDING_TYPE` 대조 |
| `building.totalFloors` | integer | 필수 | 건물 총 층수. 1 이상 |
| `building.usedFloorRange` | string | 필수 | 지점 운영층을 `min~max` 1칸으로 받는다(예 `1~2`). 서버가 `usedFloorMin`·`usedFloorMax`로 파싱 |
| `building.parkingAvailable` | boolean | 필수 | 주차공간 유무 |
| `building.elevatorAvailable` | boolean | 필수 | 엘리베이터 유무 |
| `genderPolicy` | `GenderPolicy` | 필수 | 이용 성별구분. 카탈로그 `GENDER_POLICY` 대조 |
| `languagesSupported` | `SupportedLanguage[]` | 필수 | 외국어 응대(복수 선택). 카탈로그 `SUPPORTED_LANGUAGE` 대조 |
| `ageRange` | string | 필수 | 이용 연령대를 `min~max` 1칸으로 받는다(예 `20~35`). 서버가 `ageMin`·`ageMax`로 파싱 |
| `arcRequired` | `ArcRequirement` | 필수 | 외국인등록증(ARC) 필수 요구 여부. 카탈로그 `ARC_REQUIREMENT` 대조 |
| `facilities.heatingSystem` | `HeatingSystem[]` | 필수 | 난방시설. 카탈로그 `HEATING_SYSTEM` 대조. 없으면 `["NONE"]` |
| `facilities.kitchen` | `KitchenFacility[]` | 필수 | 주방시설(복수 선택). 카탈로그 `KITCHEN` 대조. 없으면 `["NONE"]` |
| `facilities.laundry` | `LaundryFacility[]` | 필수 | 세탁시설(복수 선택). 카탈로그 `LAUNDRY` 대조. 없으면 `["NONE"]` |
| `facilities.livingAmenities` | `LivingAmenity[]` | 필수 | 생활시설(복수 선택). 카탈로그 `LIVING_AMENITY` 대조. 없으면 `["NONE"]` |
| `facilities.securityFeatures` | `SecurityFeature[]` | 필수 | 안전시설(복수 선택). 카탈로그 `SECURITY_FEATURE` 대조. 없으면 `["NONE"]` |
| `facilities.commonSpaces` | `CommonSpaceType[]` | 필수 | 공용공간(복수 선택). 카탈로그 `COMMON_SPACE` 대조. 없으면 `["NONE"]` |
| `facilities.providedSupplies` | `ProvidedSupply[]` | 필수 | 제공비품(복수 선택). 카탈로그 `PROVIDED_SUPPLY` 대조. 없으면 `["NONE"]` |
| `nearbyFacilities` | `NearbyFacility[]` | 필수 | 주변 편의시설(복수 선택). 카탈로그 `NEARBY_FACILITY` 대조. 없으면 `["NONE"]` |
| `nearestTransit.type` | `TransitType` | 필수 | 현재 허용값은 `SUBWAY` 하나다. 카탈로그 `TRANSIT_TYPE` 대조 |
| `nearestTransit.name` | string | 필수 | 근처 지하철역명. **역 검색 응답의 `name`을 그대로** 보낸다 |
| `nearestTransit.walkMinutes` | integer | 필수 | 도보 소요시간(분). 0 이상. 역 검색이 준 `suggestedWalkMinutes`를 그대로 담으면 된다. **키를 생략하면 `400 INVALID_INPUT`이다** — 예전에는 조용히 `0`이 저장됐다([ADR-0044](../../adr/0044-nearby-station-search-with-kakao-local.md)) |
| `description` | string | 필수 | 지점 소개글 |
| `extraNotes` | string | 필수 | 이용 조건(생활 규칙)·유의사항 |
| `refundPolicy` | string | 필수 | 환불정책 문구 |
| `imageKeys` | string[] | 필수 | 지점 대표사진의 저장 키. **1~5개**. `POST /api/v2/listings/images` 응답의 `key`를 그대로 담는다. 첫 값이 카드·상세의 대표 이미지가 된다 |
| `roomOffers` | object[] | 필수 | 개별 객실(room) 타입. **최소 1개** |
| `roomOffers[].name` | string | 필수 | 객실 타입명 |
| `roomOffers[].contract.minStayMonths` | integer | 필수 | 이용 기간(최소, 개월). 1 이상 |
| `roomOffers[].contract.maxStayMonths` | integer | 필수 | 이용 기간(최대, 개월). `minStayMonths` 이상 |
| `roomOffers[].pricing.monthlyRent` | integer(KRW) | 필수 | 객실 비용(월 기준). 0 이상. **주 단위 가격은 받지 않는다** — 임대 유형이 월세 하나뿐이고 예산 필터·정렬·예약 총액이 모두 이 값을 기준으로 한다([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md)) |
| `roomOffers[].pricing.deposit` | integer(KRW) | 필수 | 보증금. 0 이상 |
| `roomOffers[].pricing.maintenanceFee` | integer(KRW) | 필수 | 관리비. 0 이상 |
| `roomOffers[].filterTags` | `ConditionTag[]` | 필수 | 타입별 매물 옵션(복수 선택). 카탈로그 `CONDITION_TAG` 대조 |
| `roomOffers[].roomImageKeys` | string[] | 필수 | 그 객실 사진의 저장 키. **2~5개**. 방마다 따로 담는다 |
| `preferredNationalities` | `Nationality[]` | 선택 | 설문 — 선호하는 국적(복수 선택). 키를 생략하거나 `null`·`[]`을 보내도 된다. 서버가 빈 배열로 정규화하므로 **저장 문서에는 항상 키가 있다**. 세입자 응답에 포함하지 않는다 |
| `contractDifficulties` | `ContractDifficulty[]` | 선택 | 설문 — 계약 과정에서 겪은 어려움(복수 선택). 키를 생략하거나 `null`·`[]`을 보내도 된다. 서버가 빈 배열로 정규화한다. 세입자 응답에 포함하지 않는다 |
| `serviceFeedback` | string | 선택 | 설문 — Kohere에 전하고 싶은 말. 응답에 포함하지 않는다 |

요청 주의사항:

- **서버가 정하는 값은 요청 본문에 없다.** `listingId` · `roomOffers[].roomOfferId`(둘 다 ObjectId 발급) · `schemaVersion`(`4`) · `status`(`PENDING`) · `favoriteCount`(`0`) · `createdAt`/`updatedAt` · `rentalType`(`MONTHLY_RENT` 고정) · `roomOffers[].pricing.currency`(`KRW` 고정) · `roomOffers[].status`(`ACTIVE`)를 클라이언트가 정하지 않는다. `landlordId`도 요청이 아니라 access 토큰에서 읽는다. **사진 URL(`imageUrls`·`roomOffers[].roomImageUrls`)도 마찬가지다** — 요청은 키만 보내고 서버가 확정 위치의 URL을 응답에 담아 준다.
- **사진은 미리 올려 둔다.** 먼저 `POST /api/v2/listings/images`로 한 장씩 올려 `key`를 받고, 그 키를 `imageKeys`(1~5개)와 `roomOffers[].roomImageKeys`(방마다 2~5개)에 담는다. 어느 사진이 어느 방 것인지는 **이 JSON 구조가 정한다** — 배열의 순서가 곧 표시 순서다.
- **자기가 올린 키만 쓸 수 있다.** 키는 `uploads/{내 landlordId}/…` 형태이며, 남의 키나 존재하지 않는 키(오타·7일 만료)는 `400 LISTING_IMAGE_KEY_NOT_FOUND`다. 셋을 한 코드로 묶은 것은 구분해 알려주면 남의 키가 있는지 없는지가 새어 나가기 때문이다.
- **등록에 성공하면 사진이 확정 위치로 옮겨 간다.** 업로드 때 받은 `uploads/…` URL은 무효가 되므로 **등록 응답의 URL을 쓴다.**
- **폼 1칸을 서버가 두 필드로 파싱한다.** `building.usedFloorRange`(`1~2`) → `usedFloorMin`·`usedFloorMax`, `ageRange`(`20~35`) → `ageMin`·`ageMax`. 형식 위반은 `400 INVALID_INPUT`이며, `min ≤ max`와 `usedFloorMax ≤ totalFloors`도 함께 검증한다. 원본 문자열은 저장하지 않는다.
- **주소는 먼저 검색한다.** 주소 칸은 자유 입력이 아니다 — `GET /api/v1/listings/addresses`로 검색해 고른 후보의 `roadAddress`·`lat`·`lng`를 그대로 담는다. 서버는 이 API를 부른 적이 있는지 확인하지 않으므로 값을 보관했다 되돌려 보내는 것은 클라이언트의 몫이며, **검색 결과가 아닌 좌표를 임의로 만들어 보내지 않는다**(승인 심사가 주소와 좌표의 일치를 본다).
- **주소는 파싱해 행정구역만 채운다.** `address.fullAddress`는 받은 값 그대로 저장하고(정규화 없음), `address.city`·`address.district`는 도로명 주소를 **공백으로 끊어** 카탈로그(`CITY`·`DISTRICT`)의 한국어 라벨과 **완전히 같은 토큰**을 찾아 그 코드로 채운다. 못 찾으면 `ETC`로 저장하며 **등록은 성공한다** — 지원 지역 판단은 관리자 승인 심사가 한다([ADR-0046](../../adr/0046-administrative-region-as-catalog-data.md)).
- **역도 먼저 검색한다.** `nearestTransit.name`도 자유 입력이 아니다 — `GET /api/v1/listings/stations`로 검색해 고른 후보의 `name`을 그대로 담는다. 서버는 이 API를 부른 적이 있는지 확인하지 않으므로 값을 보관했다 되돌려 보내는 것은 클라이언트의 몫이다.
- **좌표는 요청 값으로 채우고, 인근 대학은 그 좌표에서 파생한다.** `address.lat`·`lng`가 `location`으로 저장되어 지도·거리 정렬과 관리자 승인 조건(좌표 보유)을 만족한다. 서버는 이어서 대학 좌표 원장과 대조해 **반경 2km 안의 대학 코드를 모두** `nearbyUniversityCodes`에 담는다 — 요청에 대학 칸이 없는 이유이며, 이 값이 진단 추천의 대학 매칭 키다([ADR-0045](../../adr/0045-nearby-university-mapping-from-seeded-coordinates.md)). 대학가 밖 매물은 정상적으로 빈 배열이고 등록은 그대로 성공한다. `location`은 **저장 계약의 필수 필드**다 — 좌표 없는 매물은 어차피 관리자 승인을 통과하지 못하므로 저장 단계에서 막는다.
- **코드 필드는 `listingCatalog` 대조를 통과해야 한다.** 위 표의 각 코드 배열·단일 코드는 `(category, code)`가 카탈로그에 존재해야 하며, 없는 코드는 `400 LISTING_UNKNOWN_CATALOG_CODE`다. 사용자가 오타를 낸 것이 아니라 앱이 들고 있는 코드표가 서버 카탈로그와 어긋났다는 뜻이라 `INVALID_INPUT`과 분리한다 — 프론트는 입력 교정 대신 코드 카탈로그 재조회(또는 앱 갱신)를 안내한다.
- **문자열 길이 제한은 두지 않는다.** 서버·DB 어느 계층도 자유 입력 문구의 길이를 강제하지 않는다.
- **사업자등록번호는 등록 시점에 자동 검증하지 않는다.** 숫자 10자리 형식만 확인하고 원문을 매물 문서에 저장하며, 진위·영업 상태는 **관리자가 승인 심사에서 수동으로** 확인한다. 이 엔드포인트는 `POST /api/v1/auth/business/verify`(무상태 검증 — [01-auth-onboarding](./01-auth-onboarding.md))를 호출하지 않는다.
- **담당자 연락처는 두 칸뿐이다.** `contact`는 `managerName`·`phone`이며 `phone`은 **지점 대표 전화**다. 문자문의 칸(`contact.sms`)은 **받지 않는다** — 임대인이 거기 적게 되는 값은 온보딩에서 인증한 개인 번호(`users.phone_number`)라 [ADR-0034](../../adr/0034-landlord-phone-sms-verification.md)의 마스킹 대상 PII를 매물 응답으로 평문 공개하는 통로가 되고, 계정 단위 값을 매물마다 복제하게 된다([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md) Amended). 임대인 개인 연락처는 매물 문서에 복사하지 않으며, 필요해지면 저장이 아니라 조회 시점에 `user` 모듈에서 가져와 **마스킹해** 내보낸다.
- **다국어 문구는 한국어 한 값만 받는다.** 서버가 저장 시 `{ko, en}`의 **양쪽에 같은 값을 넣는다**(`en = ko`). 대상은 `title`·`address.fullAddress`·`address.detail`·`nearestTransit.name`·`description`·`extraNotes`·`refundPolicy`·`roomOffers[].name` 8종이다. 저장 계약이 두 언어를 모두 요구하므로(`LocalizedText`) 영어 문구가 비어 있는 문서는 만들 수 없다. **영어 번역은 관리자가 승인 심사에서 채운다** — 등록 직후 매물은 `PENDING`이라 세입자 조회에 노출되지 않으므로, 번역 없이 승인하지 않는 한 외국인 화면에 한국어가 나가지 않는다.

성공 Response (201):

```jsonc
{
  "success": true,
  "data": {
    "listingId": "68e0000000000000000000a1",
    "title": "신촌 도보 5분 1인실 고시원",
    "type": { "code": "GOSHIWON", "label": "고시원" },
    "status": "PENDING",
    "rentalType": { "code": "MONTHLY_RENT", "label": "월세" },
    "refundPolicy": "입주 7일 전까지 취소하면 전액 환불합니다.",
    "genderPolicy": { "code": "FEMALE_ONLY", "label": "여성 전용" },
    "arcRequired": { "code": "NOT_REQUIRED", "label": "외국인 등록증 불필요" },
    "ageMin": 20,
    "ageMax": 35,
    "languagesSupported": [
      { "code": "ENGLISH", "label": "영어" },
      { "code": "CHINESE", "label": "중국어" }
    ],
    "location": { "lat": 37.5559918, "lng": 126.9368647 },
    "address": {
      "city": { "code": "SEOUL", "label": "서울특별시" },
      "district": { "code": "SEODAEMUN_GU", "label": "서대문구" },
      "fullAddress": "서울특별시 서대문구 신촌로 12",
      "detail": "3층 305호"
    },
    "nearestTransit": {
      "type": { "code": "SUBWAY", "label": "지하철" },
      "name": "신촌역",
      "walkMinutes": 5
    },
    "nearbyFacilities": [
      { "code": "CONVENIENCE_STORE", "label": "편의점" },
      { "code": "HOSPITAL_PHARMACY", "label": "병원/약국" }
    ],
    "nearbyUniversityCodes": ["YONSEI", "EWHA", "HONGIK"],
    "building": {
      "type": { "code": "VILLA", "label": "빌라/연립" },
      "usedFloorMin": 1,
      "usedFloorMax": 2,
      "totalFloors": 4,
      "parkingAvailable": true,
      "elevatorAvailable": true
    },
    "facilities": {
      "heatingSystem": [{ "code": "CENTRAL", "label": "중앙난방" }],
      "kitchen": [
        { "code": "SHARED_REFRIGERATOR", "label": "공용 냉장고" },
        { "code": "MICROWAVE", "label": "전자레인지" }
      ],
      "laundry": [
        { "code": "WASHER", "label": "세탁기" },
        { "code": "DRYING_RACK", "label": "건조대" }
      ],
      "livingAmenities": [
        { "code": "WIFI", "label": "와이파이" },
        { "code": "TV", "label": "TV" }
      ],
      "securityFeatures": [
        { "code": "CCTV", "label": "CCTV" },
        { "code": "ENTRANCE_DOOR_LOCK", "label": "공동현관 도어락" }
      ],
      "commonSpaces": [
        { "code": "SHARED_KITCHEN", "label": "공용 주방" },
        { "code": "SHARED_TOILET", "label": "공용 화장실" }
      ],
      "providedSupplies": [
        { "code": "BEDDING", "label": "침구류" },
        { "code": "TISSUE", "label": "휴지" }
      ]
    },
    "conditions": [
      { "code": "ENGLISH_OK", "label": "영어 안내 가능" },
      { "code": "ADDRESS_REGISTRATION", "label": "전입신고 가능" }
    ],
    "roomOffers": [
      {
        "roomOfferId": "68e0000000000000000001a1",
        "name": "스탠다드 1인실",
        "status": "ACTIVE",
        "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
        "pricing": {
          "monthlyRent": 380000,
          "deposit": 200000,
          "maintenanceFee": 20000,
          "currency": "KRW"
        },
        "filterTags": [
          { "code": "ENGLISH_OK", "label": "영어 안내 가능" },
          { "code": "ADDRESS_REGISTRATION", "label": "전입신고 가능" }
        ],
        "roomImageUrls": [
          "https://cdn.kohere.app/listings/68e0000000000000000000a1/rooms/68e0000000000000000001a1/7b2e8841-2a3b-4c5d-8e9f-0a1b2c3d4e55.jpg",
          "https://cdn.kohere.app/listings/68e0000000000000000000a1/rooms/68e0000000000000000001a1/c14d05a6-6b7c-4d8e-9f01-2a3b4c5d6e66.jpg"
        ]
      }
    ],
    "description": "지하철역에서 도보 5분 거리의 관리가 잘 된 고시원입니다.",
    "extraNotes": "객실 내 취사 금지. 오후 11시 이후 정숙.",
    "contact": {
      "managerName": "Kim Woon-yeong",
      "phone": "+82) 10-1234-5678"
    },
    "blogUrl": "https://blog.naver.com/kohere-goshiwon",
    "imageUrls": [
      "https://cdn.kohere.app/listings/68e0000000000000000000a1/cover/3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg",
      "https://cdn.kohere.app/listings/68e0000000000000000000a1/cover/8d21b7f0-5c4d-4e6f-8a09-3c1d6e7f5b22.jpg"
    ],
    "favorited": false,
    "favoriteCount": 0,
    "createdAt": "2026-08-12T02:11:00Z",
    "updatedAt": "2026-08-12T02:11:00Z"
  },
  "error": null
}
```

- 응답은 매물 상세(`GET /api/v2/listings/{listingId}`)와 같은 v4 구조다. 등록 직후라 `favorited=false`, `favoriteCount=0`이다.
- **`imageUrls`·`roomOffers[].roomImageUrls`는 확정 위치의 URL이며, `imageKeys`·`roomImageKeys`에 보낸 순서를 그대로 유지한다.** 파일명(`{uuid}.{ext}`)은 업로드 때 받은 키의 것을 그대로 쓰므로 두 값을 눈으로 대조할 수 있다. 업로드 때 받은 임시 URL과 다르다 — 이쪽이 만료 없이 계속 쓰는 주소다.
- `status`는 `PENDING`이며 **코드 문자열 그대로** 내려간다. 임대인·관리자만 읽는 관리 상태라 카탈로그 번역 대상이 아니다.
- **`location`은 요청의 `address.lat`·`address.lng`를 그대로 옮긴 값이고, `nearbyUniversityCodes`는 그 좌표에서 서버가 파생한 값이다.** 좌표는 주소 검색이 준 것을 되돌려 받은 것이고, 대학은 반경 2km 안에 있는 것을 모두 담는다 — 위 예시의 신촌 좌표는 `YONSEI`·`EWHA`·`HONGIK` 셋이 모두 도보권이라 셋 다 들어간다([ADR-0045](../../adr/0045-nearby-university-mapping-from-seeded-coordinates.md)).
- `address.city`·`address.district`는 서버가 도로명 주소에서 파싱한 값이라 요청에 없던 필드가 응답에 나타난다. `address.fullAddress`는 입력값 그대로이고, 요청의 `lat`·`lng`는 `address` 안이 아니라 **최상위 `location`** 으로 옮겨 간다(상세 조회와 같은 구조).
- 상위 `conditions`는 ACTIVE 방 타입들의 `roomOffers[].filterTags` 합집합이다. 등록 요청에는 없고 서버가 계산한다.
- `contact`(담당자명·지점 대표 전화)는 **세입자에게 그대로 공개**하는 매물별 담당 연락처이므로 응답에 포함한다. 반면 `businessRegistrationNumber`와 임대인 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)은 저장은 하되 **응답에 포함하지 않는다**([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md)).
- `{code,label}`의 `label` 언어는 요청자 계정의 표시 언어를 따른다. 임대인은 `lang="ko"`로 고정이라 위 예시처럼 한국어 라벨이 내려간다.
- 등록된 매물은 `PENDING`이라 목록·지도·검색·상세·찜 어디에도 나오지 않는다. 공개 전환(`PENDING → PUBLISHED`/`REJECTED`)은 [관리자 매물 심사](#관리자-매물-심사)가, 등록 뒤 내용을 고치는 것은 [임대인 매물 관리](#임대인-매물-관리)의 `PUT /api/v2/listings/{listingId}`가 담당한다. 재고 관리는 **후속 작업**이다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 필수값 누락·빈값, 형식 위반(`usedFloorRange`·`ageRange`의 `min~max`, 전화번호, URL, 사업자등록번호 숫자 10자리, `{ko,en}` 한쪽 누락), 범위 위반(`ageMin>ageMax`, `usedFloorMin>usedFloorMax`, `usedFloorMax>totalFloors`, `minStayMonths>maxStayMonths`, 음수 금액, `address.lat`·`address.lng`가 WGS84 범위 밖), `address.lat`·`address.lng` 누락, `roomOffers` 0개, **시설 8종 중 어느 하나가 `NONE`을 다른 코드와 함께 담음**. 위반 필드는 `errors[]`에 실린다 |
| 400 | `LISTING_UNKNOWN_CATALOG_CODE` | 요청에 실린 코드 값이 `listingCatalog`의 `(category, code)`에 없음 |
| 400 | `LISTING_IMAGE_REQUIRED` | `imageKeys`가 1~5개가 아니거나, 어느 방의 `roomImageKeys`가 2~5개가 아님 |
| 400 | `LISTING_IMAGE_KEY_NOT_FOUND` | 키가 남의 것이거나, 존재하지 않거나, 7일이 지나 만료됨 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰(`ROLE_ONBOARDING`) — `hasRole("USER")` 매처에서 차단 |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자의 등록 요청 |
| 502 | `UPSTREAM_ERROR` | 사진 저장소 복사 실패. 매물은 저장되지 않고 이미 복사한 사진은 서버가 지운다. **임시 사진은 그대로 남아** 다시 제출할 수 있다 |

### GET /api/v2/listings — 매물 리스트

- 설명: 지도 바텀시트나 매물 리스트 화면에 표시할 매물 목록을 반환한다. 응답 항목 1개가 화면의 카드 1개가 된다.
- 인증: 선택. 로그인 사용자는 계정 표시 언어가 적용되지만, 목록의 `favorited`는 현재 구현상 항상 `false`다.

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `swLat` | number | 선택 | — | 현재 지도 화면의 남서쪽 위도. 지도 기준 목록을 갱신할 때 네 좌표를 모두 보낸다 |
| `swLng` | number | 선택 | — | 현재 지도 화면의 남서쪽 경도 |
| `neLat` | number | 선택 | — | 현재 지도 화면의 북동쪽 위도. `swLat`보다 커야 함 |
| `neLng` | number | 선택 | — | 현재 지도 화면의 북동쪽 경도. `swLng`보다 커야 함 |
| `minBudget` | integer(KRW) | 선택 | — | 월세 최소값. 조건에 맞는 방 타입이 있는 매물만 보여준다 |
| `maxBudget` | integer(KRW) | 선택 | — | 월세 최대값. 카드 가격은 응답의 `roomOffers[].pricing`으로 계산한다 |
| `minDeposit` | integer(KRW) | 선택 | — | 보증금 최소값 |
| `maxDeposit` | integer(KRW) | 선택 | — | 보증금 최대값 |
| `type` | `ListingType` | 선택 | — | 매물 유형 필터 칩. 다중 값 콤마 구분(`GOSHIWON,CO_LIVING`) |
| `conditions` | `ConditionTag[]` | 선택 | — | 옵션 필터 칩. `MOVE_IN_NOW`, `FEMALE_ONLY`, `PRIVATE_BATH`, `ADDRESS_REGISTRATION` 등을 반복 파라미터 또는 콤마로 전송 |
| `sort` | `ListingSort` | 선택 | `RECOMMENDED` | 정렬 방식. `RECOMMENDED`는 현재 `favoriteCount desc`, 동률이면 `updatedAt desc`; `PRICE_ASC`는 낮은 월세순; `DISTANCE`는 현재 지도 중심에서 가까운 순 |
| `page` | integer | 선택 | 0 | 0부터 시작하는 페이지 번호. 무한스크롤의 다음 페이지 요청에 사용 |
| `size` | integer | 선택 | 20 | 한 번에 가져올 매물 수(최대 100) |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "Single-room goshiwon, 5 minutes from Sinchon",
        "type": { "code": "GOSHIWON", "label": "Goshiwon" },
        "status": "PUBLISHED",
        "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
        "refundPolicy": "Full refund for cancellations made at least 7 days before move-in.",
        "genderPolicy": { "code": "FEMALE_ONLY", "label": "Female Only" },
        "arcRequired": { "code": "NOT_REQUIRED", "label": "ARC Not Required" },
        "ageMin": 20,
        "ageMax": 35,
        "languagesSupported": [
          { "code": "ENGLISH", "label": "English" },
          { "code": "CHINESE", "label": "Chinese" }
        ],
        "location": { "lat": 37.555134, "lng": 126.936893 },
        "address": {
          "city": { "code": "SEOUL", "label": "Seoul" },
          "district": { "code": "SEODAEMUN_GU", "label": "Seodaemun-gu" },
          "fullAddress": "Sinchon-ro, Seodaemun-gu, Seoul",
          "detail": null
        },
        "nearestTransit": {
          "type": { "code": "SUBWAY", "label": "Subway" },
          "name": "Sinchon Station",
          "walkMinutes": 5
        },
        "nearbyFacilities": [
          { "code": "CONVENIENCE_STORE", "label": "Convenience Store" },
          { "code": "HOSPITAL_PHARMACY", "label": "Hospital/Pharmacy" }
        ],
        "nearbyUniversityCodes": ["YONSEI"],
        "building": {
          "type": { "code": "VILLA", "label": "Villa" },
          "usedFloorMin": 1,
          "usedFloorMax": 2,
          "totalFloors": 4,
          "parkingAvailable": true,
          "elevatorAvailable": true
        },
        "facilities": {
          "heatingSystem": [{ "code": "CENTRAL", "label": "Central Heating" }],
          "kitchen": [{ "code": "MICROWAVE", "label": "Microwave" }],
          "laundry": [{ "code": "WASHER", "label": "Washer" }],
          "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
          "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
          "commonSpaces": [{ "code": "SHARED_TOILET", "label": "Shared Toilet" }],
          "providedSupplies": [{ "code": "BEDDING", "label": "Bedding" }]
        },
        "conditions": [
          { "code": "ENGLISH_OK", "label": "English OK" },
          { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" }
        ],
        "roomOffers": [
          {
            "roomOfferId": "6858e2000000000000000101",
            "name": "Standard Single Room",
            "status": "ACTIVE",
            "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
            "pricing": {
              "monthlyRent": 380000,
              "deposit": 200000,
              "maintenanceFee": 20000,
              "currency": "KRW"
            },
            "filterTags": [
              { "code": "ENGLISH_OK", "label": "English OK" },
              { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" }
            ],
            "roomImageUrls": []
          }
        ],
        "description": "A well-maintained goshiwon within a five-minute walk of the subway station.",
        "extraNotes": "No cooking inside rooms. Quiet hours after 11 PM.",
        "contact": {
          "managerName": "Kim Woon-yeong",
          "phone": "+82) 10-1234-5678"
        },
        "blogUrl": "https://blog.naver.com/kohere-goshiwon",
        "imageUrls": ["https://cdn.kohere.app/listings/6858e2000000000000000001/main.jpg"],
        "distanceMeters": 320,
        "favorited": false,
        "favoriteCount": 12,
        "createdAt": "2026-06-01T00:00:00Z",
        "updatedAt": "2026-06-10T00:00:00Z"
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 137,
      "totalPages": 7,
      "hasNext": true
    }
  },
  "error": null
}
```

- 카드 제목은 `title`, 대표 이미지는 `imageUrls[0]`, 주소는 `address.fullAddress`, 교통 배지는 `nearestTransit.name`과 `nearestTransit.walkMinutes`를 사용한다.
- 가격/보증금/관리비는 `roomOffers[].pricing`에서 읽는다. 여러 방 타입이 있으면 프론트에서 최저~최고 범위를 계산해 카드에 표시한다.
- 계약기간은 방 타입마다 다를 수 있으므로 `roomOffers[].contract.minStayMonths/maxStayMonths`를 사용한다.
- 조건 배지/Property Details features는 상위 `conditions`의 `label`을 표시한다. 이 값은 공개 가능한 ACTIVE 방 타입들의 `roomOffers[].filterTags` 합집합이다. 필터 요청에는 `code`를 보낸다.
- 필터가 있으면 `roomOffers[]`에는 조건을 통과한 방 타입만 들어온다. 필터가 없으면 노출 가능한 ACTIVE 방 타입 전체가 들어온다.
- 방 타입별 세부 조건 배지가 필요하면 각 `roomOffers[].filterTags`를 사용한다.
- 난방 방식은 `building.heatingSystem`이 아니라 `facilities.heatingSystem[]`에서 읽는다.
- `contact`는 매물별 담당 연락처(담당자명·지점 대표 전화)이며 세입자에게 그대로 공개한다. 임대인 개인 연락처와는 별개 값이다. `businessRegistrationNumber`와 임대인 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)은 응답에 포함하지 않는다.
- `distanceMeters`가 있으면 거리 라벨로 표시하고, 없으면 숨긴다.
- `favoriteCount`는 찜 수 표시값이다. 목록의 `favorited`는 현재 구현상 로그인 여부와 관계없이 항상 `false`이므로, 실제 하트 상태가 필요한 화면은 상세 또는 사용자 전용 목록의 값을 사용해야 한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 범위/enum 위반(`minBudget>maxBudget`, 미정의 `conditions`/`sort` 등), `size` 범위 초과 |
| 400 | `MALFORMED_REQUEST` | 타입 불일치(숫자 파라미터에 비숫자 등) |
| 400 | `LISTING_INVALID_BBOX` | bbox 네 좌표가 일부만 있거나 범위·방향이 올바르지 않음 |
| 400 | `LISTING_INVALID_SORT_PARAM` | `sort=DISTANCE`인데 bbox 네 좌표가 없음 |
| 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |

### GET /api/v1/listings/places — 네이버 장소 후보 검색

- 설명: 지도 검색창의 키워드를 네이버 지역 검색 API로 조회하고, 사용자가 선택할 장소 후보를 정확도순 최대 5개 반환한다.
- 인증: 불필요
- 책임 범위: 장소 후보만 반환하며 MongoDB 매물은 조회하지 않는다. 프론트가 후보 좌표로 지도를 이동한 뒤 계산한 bounds를 `/api/v2/listings`와 `/api/v2/listings/map`에 전달한다.
- 경로: 매물 데이터를 쓰지 않아 v4 개편의 영향을 받지 않았으므로 **`/api/v1`에 그대로 둔다** — 조회 계열 6종이 `/api/v2`로 옮겨간 뒤에도 이 엔드포인트만 `/api/v1`에서 정상 동작한다(위 [v1 조회 API 종료](#v1-조회-api-종료deprecated)).
- 외부 연동: 장소 검색은 아웃바운드 포트 `PlaceSearchClient`(인프라 어댑터 `NaverPlaceSearchClient` — 네이버 지역 검색 API)로 **동기 호출**한다. 네이버 API 장애·타임아웃·인증정보 누락·응답/좌표 형식 이상 등 연동 실패는 `502 UPSTREAM_ERROR`로 응답해 클라이언트가 재시도하도록 한다(공통 코드 — [error-response-guide](../error-response-guide.md)). 인증정보는 환경변수 `NAVER_SEARCH_CLIENT_ID`/`NAVER_SEARCH_CLIENT_SECRET`(SSM SecureString)로 주입한다.

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `keyword` | string | 필수 | — | 지도 검색창 입력값. 앞뒤 공백 제거 후 1~50자 |

서버가 네이버 호출에 고정하는 값:

| 이름 | 값 | 설명 |
| --- | --- | --- |
| `display` | `5` | 한 번에 받을 최대 장소 후보 수 |
| `start` | `1` | 지역 검색 API가 허용하는 검색 시작 위치 |
| `sort` | `random` | 네이버 문서상 정확도 내림차순 |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "items": [
      {
        "title": "<b>경희대학교</b> 서울캠퍼스",
        "address": "서울특별시 동대문구 회기동 1-5",
        "roadAddress": "서울특별시 동대문구 경희대로 26",
        "lat": 37.5964494,
        "lng": 127.0525009
      }
    ]
  },
  "error": null
}
```

- 네이버 원본의 `mapx/mapy`는 서버가 WGS84 십진수 `lng/lat`으로 변환한다.
- `title`은 검색어 강조를 위한 네이버의 `<b>` 태그를 그대로 유지한다.
- 정상적으로 검색 결과가 없으면 `200 OK`와 `data.items=[]`를 반환한다.
- 네이버 응답의 `lastBuildDate`, `total`, `start`, `display`, `link`, `category`, `description`, `telephone`은 공개하지 않는다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 키워드 누락·공백·길이(1~50자) 위반 |
| 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |
| 502 | `UPSTREAM_ERROR` | 네이버 HTTP 오류·타임아웃·인증정보 누락·응답 또는 좌표 형식 이상 |

### GET /api/v2/listings/map — 지도 마커 조회

- 설명: 지도에 찍을 마커 좌표만 반환한다. 지도 SDK 마커/클러스터 렌더링에 사용하고, 상세한 카드 정보는 `/api/v2/listings`로 가져온다.
- 인증: 선택

Query 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `swLat` | number | bbox 모드 필수 | 남서 위도 |
| `swLng` | number | bbox 모드 필수 | 남서 경도 |
| `neLat` | number | bbox 모드 필수 | 북동 위도(`swLat`보다 커야 함) |
| `neLng` | number | bbox 모드 필수 | 북동 경도(`swLng`보다 커야 함) |
| `minBudget`/`maxBudget`/`minDeposit`/`maxDeposit`/`type`/`conditions` | (리스트와 동일) | 선택 | 목록과 같은 필터를 보내면 지도 마커와 바텀시트 목록을 같은 조건으로 맞출 수 있음 |

> 지도 마커 조회는 bbox 4좌표가 모두 필요하다. 마커와 바텀시트 목록을 같이 갱신할 때는 목록 API에도 같은 필터 값을 보내면 된다.

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "markers": [
      { "listingId": "6858e2000000000000000001", "lat": 37.5489, "lng": 126.9412 }
    ],
    "total": 1
  },
  "error": null
}
```

- `markers[].lat/lng`는 지도 SDK에 넘길 좌표다.
- `markers[].listingId`는 마커 선택 상태, 목록 카드 선택 상태, 상세 진입을 연결하는 키다.
- `title`, 가격, 이미지 등 카드 정보는 포함하지 않는다. 마커를 눌렀을 때 카드가 필요하면 같은 `listingId`로 목록 결과에서 찾거나 상세 API를 호출한다.
- `LISTING_AREA_TOO_LARGE`가 오면 지도를 더 확대하거나 bbox를 좁혀 다시 호출한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `LISTING_INVALID_BBOX` | bbox 좌표 불완전/범위 위반/모순(`swLat>=neLat` 등) |
| 400 | `LISTING_AREA_TOO_LARGE` | 지도 마커 결과가 너무 많아 한 번에 표시하기 어려움 |
| 400 | `INVALID_INPUT` | 필터 enum/범위 위반 등 |
| 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |

### GET /api/v2/listings/{listingId} — 매물 상세

- 설명: 목록 카드나 지도 마커를 눌렀을 때 상세 화면을 그리기 위한 매물 정보를 반환한다.
- 인증: 선택. 비로그인·온보딩 미완료 사용자는 공개 상세만 받고, 온보딩 완료 사용자는 찜 상태·계정 언어·최근 본 기록이 적용된다.

Path 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 목록/검색/마커 응답에서 받은 `listingId` |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "listingId": "6858e2000000000000000001",
    "title": "Single-room goshiwon, 5 minutes from Sinchon",
    "type": { "code": "GOSHIWON", "label": "Goshiwon" },
    "status": "PUBLISHED",
    "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
    "refundPolicy": "Full refund for cancellations made at least 7 days before move-in.",
    "genderPolicy": { "code": "FEMALE_ONLY", "label": "Female Only" },
    "arcRequired": { "code": "NOT_REQUIRED", "label": "ARC Not Required" },
    "ageMin": 20,
    "ageMax": 35,
    "languagesSupported": [
      { "code": "ENGLISH", "label": "English" },
      { "code": "CHINESE", "label": "Chinese" }
    ],
    "location": { "lat": 37.555134, "lng": 126.936893 },
    "address": {
      "city": { "code": "SEOUL", "label": "Seoul" },
      "district": { "code": "SEODAEMUN_GU", "label": "Seodaemun-gu" },
      "fullAddress": "Sinchon-ro, Seodaemun-gu, Seoul",
      "detail": "Room 305, 3rd floor"
    },
    "nearestTransit": {
      "type": { "code": "SUBWAY", "label": "Subway" },
      "name": "Sinchon Station",
      "walkMinutes": 5
    },
    "nearbyFacilities": [
      { "code": "CONVENIENCE_STORE", "label": "Convenience Store" },
      { "code": "HOSPITAL_PHARMACY", "label": "Hospital/Pharmacy" },
      { "code": "PARK", "label": "Park" }
    ],
    "nearbyUniversityCodes": ["YONSEI", "EWHA"],
    "building": {
      "type": { "code": "VILLA", "label": "Villa" },
      "usedFloorMin": 2,
      "usedFloorMax": 3,
      "totalFloors": 5,
      "parkingAvailable": false,
      "elevatorAvailable": false
    },
    "facilities": {
      "heatingSystem": [{ "code": "CENTRAL", "label": "Central Heating" }],
      "kitchen": [{ "code": "SHARED_REFRIGERATOR", "label": "Shared Refrigerator" }],
      "laundry": [{ "code": "WASHER", "label": "Washer" }],
      "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
      "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
      "commonSpaces": [{ "code": "STUDY_ROOM", "label": "Study Room" }],
      "providedSupplies": [{ "code": "SLIPPERS", "label": "Slippers" }]
    },
    "conditions": [
      { "code": "FEMALE_ONLY", "label": "Female Only" },
      { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" },
      { "code": "NO_MAINT_FEE", "label": "No Maint. Fee" }
    ],
    "roomOffers": [
      {
        "roomOfferId": "6858e2000000000000000101",
        "name": "Standard Single Room",
        "status": "ACTIVE",
        "contract": { "minStayMonths": 2, "maxStayMonths": 6 },
        "pricing": {
          "monthlyRent": 300000,
          "deposit": 300000,
          "maintenanceFee": 0,
          "currency": "KRW"
        },
        "filterTags": [
          { "code": "FEMALE_ONLY", "label": "Female Only" },
          { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" },
          { "code": "NO_MAINT_FEE", "label": "No Maint. Fee" }
        ],
        "roomImageUrls": [
          "https://cdn.kohere.app/listings/6858e2000000000000000001/rooms/101.jpg"
        ]
      }
    ],
    "description": "A quiet goshiwon within walking distance of Sinchon Station.",
    "extraNotes": "No cooking inside rooms. Quiet hours after 11 PM.",
    "contact": {
      "managerName": "Kim Woon-yeong",
      "phone": "+82) 10-1234-5678"
    },
    "blogUrl": "https://blog.naver.com/kohere-goshiwon",
    "imageUrls": [
      "https://cdn.kohere.app/listings/6858e2000000000000000001/1.jpg",
      "https://cdn.kohere.app/listings/6858e2000000000000000001/2.jpg"
    ],
    "favorited": true,
    "favoriteCount": 12,
    "createdAt": "2026-05-30T02:11:00Z",
    "updatedAt": "2026-06-01T02:11:00Z"
  },
  "error": null
}
```

- 상단 제목/하트는 `title`, `favorited`, `favoriteCount`를 사용한다.
- 사진 갤러리는 `imageUrls`와 `roomOffers[].roomImageUrls`를 사용한다. 카드 대표 이미지는 `imageUrls[0]`를 우선 사용한다.
- 가격 영역은 `roomOffers[].pricing`, 계약기간은 방 타입마다 다를 수 있으므로 `roomOffers[].contract`, 주소/지도는 `address`와 `location`, 교통 정보는 `nearestTransit`으로 표시한다.
- `{code,label}` 형태는 `label`을 화면에 표시하고 `code`를 필터 요청·아이콘/분기 비교에 사용한다. `title`·주소·역명·방 이름·`refundPolicy`·`description`·`extraNotes`는 이미 사용자 언어 문자열 하나로 선택되어 온다.
- Property Details의 features/조건 배지는 상위 `conditions`를 사용한다. 방 타입별 조건은 `roomOffers[].filterTags`를 사용한다.
- 시설 섹션은 `building`, `facilities`를 사용한다. 난방 방식은 `building.heatingSystem`이 아니라 `facilities.heatingSystem[]`에서 읽는다. ARC 필요 여부는 `arcRequired`, 지원 언어는 `languagesSupported`, 주변 시설은 `nearbyFacilities`로 표시한다.
- `roomOffers[]`는 상세 화면의 Room Types 목록에 그대로 렌더링할 수 있는 ACTIVE 방 타입이다.
- 문의 영역은 `contact.managerName`·`contact.phone` 두 값을 그대로 표시한다. 임대인이 `POST /api/v2/listings`에서 입력한 **지점 대표 전화**라 임대인 개인 연락처와 별개 값이며 마스킹하지 않는다. 문자문의 번호는 **응답에 없다** — 개인 번호를 매물 문서에 복사하지 않기로 했다([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md) Amended). 같은 등록 요청이 담은 `businessRegistrationNumber`와 임대인 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)은 저장은 하되 응답에 포함하지 않는다([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md)).
- `blogUrl`은 있을 때만 지점 블로그 링크로 노출하고 `null`이면 숨긴다. 이용 연령대는 `ageMin`~`ageMax`로 표시한다.
- 상세는 `PUBLISHED` 매물만 반환한다. 등록 직후의 `PENDING`(승인 대기) 매물은 `listingId`를 알아도 `404 LISTING_NOT_FOUND`이며, 관리자 승인을 거쳐 `PUBLISHED`가 된 뒤에야 조회된다. 승인 조건에 `location` 보유가 들어가므로 상세에 오는 매물은 항상 좌표를 가진다.
- 온보딩 완료 사용자의 상세 조회가 성공하면 최근 본 목록이 자동 갱신된다. 프론트에서 최근 본 저장 API를 따로 호출할 필요는 없다.
- 비로그인·온보딩 미완료 사용자는 `favorited=false`와 영어 기본 문구를 받으며 최근 본 기록을 남기지 않는다.
- 로그인 전에 본 매물은 온보딩 완료 후 최근 본 목록으로 소급 이전하지 않는다. 정식 로그인 시점 이후의 상세 조회부터 기록한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |
| 404 | `LISTING_NOT_FOUND` | 없음/비공개/삭제 또는 ACTIVE 방 상품이 없는 매물 |

### POST /api/v2/listings/{listingId}/favorite — 찜 등록(토글)

- 설명: 사용자가 하트를 눌러 매물을 찜 상태로 만든다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

Path 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `listingId` | string | 필수 | 목록/상세 응답에서 받은 `listingId` |

Request Body: 없음

성공 Response — 201 Created (신규 찜) / 200 OK (이미 찜):

```jsonc
{
  "success": true,
  "data": {
    "favorited": true,
    "favoriteCount": 13
  },
  "error": null
}
```

- 응답의 `favorited=true`로 하트를 채우고, `favoriteCount`로 카드/상세의 찜 수를 갱신한다.
- 이미 찜한 매물에 다시 호출해도 에러가 아니며 현재 상태를 그대로 반환한다. 프론트는 status code와 무관하게 body 값으로 UI를 맞추면 된다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
| 404 | `LISTING_NOT_FOUND` | 없거나 비공개/삭제 또는 ACTIVE 방 상품이 없는 매물 |

### DELETE /api/v2/listings/{listingId}/favorite — 찜 해제(토글)

- 설명: 사용자가 하트를 다시 눌러 찜을 해제한다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

Path 파라미터: `listingId` (위와 동일)

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "favorited": false,
    "favoriteCount": 12
  },
  "error": null
}
```

> 응답의 `favorited=false`로 하트를 비우고, `favoriteCount`로 카드/상세의 찜 수를 갱신한다. 이미 해제된 매물이어도 에러가 아니라 현재 상태를 반환하며, 실제 찜 문서가 삭제된 경우에만 `favoriteCount`를 감소시킨다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
| 404 | `LISTING_NOT_FOUND` | 없거나 비공개/삭제 또는 ACTIVE 방 상품이 없는 매물 |

### GET /api/v2/users/me/favorites — 내 찜한 매물 목록

- 설명: 마이페이지의 찜한 매물 목록을 반환한다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `page` | integer | 선택 | 0 | 0부터 시작하는 페이지 번호 |
| `size` | integer | 선택 | 20 | 한 번에 가져올 찜 매물 수(최대 100) |

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "Single-room goshiwon, 5 minutes from Sinchon",
        "type": { "code": "GOSHIWON", "label": "Goshiwon" },
        "status": "PUBLISHED",
        "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
        "refundPolicy": "Full refund for cancellations made at least 7 days before move-in.",
        "genderPolicy": { "code": "FEMALE_ONLY", "label": "Female Only" },
        "arcRequired": { "code": "NOT_REQUIRED", "label": "ARC Not Required" },
        "ageMin": 20,
        "ageMax": 35,
        "languagesSupported": [{ "code": "ENGLISH", "label": "English" }],
        "location": { "lat": 37.555134, "lng": 126.936893 },
        "address": {
          "city": { "code": "SEOUL", "label": "Seoul" },
          "district": { "code": "SEODAEMUN_GU", "label": "Seodaemun-gu" },
          "fullAddress": "Sinchon-ro, Seodaemun-gu, Seoul",
          "detail": null
        },
        "nearestTransit": {
          "type": { "code": "SUBWAY", "label": "Subway" },
          "name": "Sinchon Station",
          "walkMinutes": 5
        },
        "nearbyFacilities": [{ "code": "CONVENIENCE_STORE", "label": "Convenience Store" }],
        "nearbyUniversityCodes": ["YONSEI"],
        "building": {
          "type": { "code": "VILLA", "label": "Villa" },
          "usedFloorMin": 1,
          "usedFloorMax": 2,
          "totalFloors": 4,
          "parkingAvailable": true,
          "elevatorAvailable": true
        },
        "facilities": {
          "heatingSystem": [{ "code": "CENTRAL", "label": "Central Heating" }],
          "kitchen": [{ "code": "MICROWAVE", "label": "Microwave" }],
          "laundry": [{ "code": "WASHER", "label": "Washer" }],
          "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
          "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
          "commonSpaces": [{ "code": "SHARED_TOILET", "label": "Shared Toilet" }],
          "providedSupplies": [{ "code": "BEDDING", "label": "Bedding" }]
        },
        "conditions": [{ "code": "ENGLISH_OK", "label": "English OK" }],
        "roomOffers": [
          {
            "roomOfferId": "6858e2000000000000000101",
            "name": "Standard Single Room",
            "status": "ACTIVE",
            "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
            "pricing": {
              "monthlyRent": 450000,
              "deposit": 0,
              "maintenanceFee": 0,
              "currency": "KRW"
            },
            "filterTags": [{ "code": "ENGLISH_OK", "label": "English OK" }],
            "roomImageUrls": []
          }
        ],
        "description": "A well-maintained goshiwon within a five-minute walk of the subway station.",
        "extraNotes": "No cooking inside rooms. Quiet hours after 11 PM.",
        "contact": {
          "managerName": "Kim Woon-yeong",
          "phone": "+82) 10-1234-5678"
        },
        "blogUrl": "https://blog.naver.com/kohere-goshiwon",
        "imageUrls": ["https://cdn.kohere.app/listings/6858e2000000000000000001/main.jpg"],
        "favorited": true,
        "favoriteCount": 13,
        "createdAt": "2026-06-01T00:00:00Z",
        "updatedAt": "2026-06-10T00:00:00Z",
        "favoritedAt": "2026-06-10T11:20:00Z"
      }
    ],
    "page": { "number": 0, "size": 20, "totalElements": 8, "totalPages": 1, "hasNext": false }
  },
  "error": null
}
```

- 항목 모두 `favorited=true`라 하트는 채운 상태로 표시한다.
- 카드 렌더링은 일반 목록과 같은 방식으로 `title`, `imageUrls[0]`, `address.fullAddress`, `roomOffers[].pricing`, `roomOffers[].contract`를 사용한다.
- `favoritedAt`은 찜한 시각 표시나 최신순 정렬 확인에 사용할 수 있다.
- 찜 해제 후에는 목록을 다시 조회하거나, 클라이언트에서 해당 `listingId` 항목을 제거하면 된다.
- 정렬은 별도 쿼리 없이 `favoritedAt desc`로 고정된다.
- 현재 저장소 조회는 `PUBLISHED` 상태만 검사한다. 따라서 ACTIVE 방 상품이 없는 공개 매물이 빈 `roomOffers[]`로 포함될 수 있으며, 이는 목록·상세·최근 본의 노출 조건과 다른 현재 구현 제약이다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `page` 음수 또는 `size` 범위 초과 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |

### GET /api/v2/users/me/recent-listings — 최근 본 매물

- 설명: 마이페이지나 홈의 최근 본 매물 영역에 표시할 매물을 최신 조회순으로 최대 10건 반환한다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`)

Query 파라미터: 없음. 온보딩 완료 사용자가 상세 조회를 호출하면 최근 본 기록이 자동으로 갱신된다. 로그인 전 조회 기록은 저장하거나 소급 이전하지 않는다.

Request Body: 없음

성공 Response (200):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "listingId": "6858e2000000000000000001",
        "title": "Hongdae Co-living Double Room",
        "type": { "code": "CO_LIVING", "label": "Co-living" },
        "status": "PUBLISHED",
        "rentalType": { "code": "MONTHLY_RENT", "label": "Monthly Rent" },
        "refundPolicy": "Full refund for cancellations made at least 7 days before move-in.",
        "genderPolicy": { "code": "ANY", "label": "Any Gender" },
        "arcRequired": { "code": "NOT_REQUIRED", "label": "ARC Not Required" },
        "ageMin": 19,
        "ageMax": 39,
        "languagesSupported": [{ "code": "ENGLISH", "label": "English" }],
        "location": { "lat": 37.5571, "lng": 126.9245 },
        "address": {
          "city": { "code": "SEOUL", "label": "Seoul" },
          "district": { "code": "MAPO_GU", "label": "Mapo-gu" },
          "fullAddress": "Mapo-gu, Seoul",
          "detail": null
        },
        "nearestTransit": {
          "type": { "code": "SUBWAY", "label": "Subway" },
          "name": "Hongik Univ. Station",
          "walkMinutes": 8
        },
        "nearbyFacilities": [{ "code": "CONVENIENCE_STORE", "label": "Convenience Store" }],
        "nearbyUniversityCodes": ["HONGIK"],
        "building": {
          "type": { "code": "OFFICETEL", "label": "Officetel" },
          "usedFloorMin": 5,
          "usedFloorMax": 7,
          "totalFloors": 12,
          "parkingAvailable": false,
          "elevatorAvailable": true
        },
        "facilities": {
          "heatingSystem": [{ "code": "INDIVIDUAL", "label": "Individual Heating" }],
          "kitchen": [{ "code": "SHARED_REFRIGERATOR", "label": "Shared Refrigerator" }],
          "laundry": [{ "code": "WASHER", "label": "Washer" }],
          "livingAmenities": [{ "code": "WIFI", "label": "Wi-Fi" }],
          "securityFeatures": [{ "code": "CCTV", "label": "CCTV" }],
          "commonSpaces": [{ "code": "LOUNGE", "label": "Lounge" }],
          "providedSupplies": [{ "code": "TISSUE", "label": "Toilet Paper" }]
        },
        "conditions": [
          { "code": "ENGLISH_OK", "label": "English OK" },
          { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" }
        ],
        "roomOffers": [
          {
            "roomOfferId": "6858e2000000000000000201",
            "name": "Co-living Double Room",
            "status": "ACTIVE",
            "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
            "pricing": {
              "monthlyRent": 580000,
              "deposit": 1000000,
              "maintenanceFee": 30000,
              "currency": "KRW"
            },
            "filterTags": [
              { "code": "ENGLISH_OK", "label": "English OK" },
              { "code": "ADDRESS_REGISTRATION", "label": "Address Registration" }
            ],
            "roomImageUrls": []
          }
        ],
        "description": "Co-living near Hongdae...",
        "extraNotes": "The shared lounge is open to all residents.",
        "contact": {
          "managerName": "Lee Ha-eun",
          "phone": "+82) 10-2222-3333"
        },
        "blogUrl": null,
        "imageUrls": ["https://cdn.kohere.app/listings/6858e2000000000000000001/main.jpg"],
        "favorited": false,
        "favoriteCount": 13,
        "createdAt": "2026-06-01T00:00:00Z",
        "updatedAt": "2026-06-10T00:00:00Z",
        "viewedAt": "2026-06-15T01:30:00Z"
      }
    ]
  },
  "error": null
}
```

- 카드 렌더링은 일반 목록과 같은 방식으로 `title`, `imageUrls[0]`, `address.fullAddress`, `roomOffers[].pricing`, `roomOffers[].contract`를 사용한다.
- `viewedAt`은 마지막으로 상세 화면을 본 시각이다. 필요하면 "최근 본 시간" 보조 문구에 사용한다.
- `favorited`로 현재 하트 상태를 바로 표시한다.
- 오래되었거나 더 이상 공개되지 않거나 ACTIVE 방 상품이 없는 매물은 응답에 포함되지 않는다. 빈 배열이면 최근 본 매물 없음 상태를 표시한다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |

## 도메인 에러 코드

> prefix는 `LISTING`. 공통 코드(`INVALID_INPUT`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `FORBIDDEN` 등)는 [error-response-guide](../error-response-guide.md) §4를 그대로 쓰며 여기서 재정의하지 않는다.

| code | status | 의미 |
| --- | --- | --- |
| `LISTING_NOT_FOUND` | 404 | 존재하지 않거나 비공개/삭제 또는 ACTIVE 방 상품이 없는 매물 |
| `LISTING_INVALID_SORT_PARAM` | 400 | `sort=DISTANCE`인데 bbox 네 좌표가 누락됨 |
| `LISTING_INVALID_BBOX` | 400 | bbox 좌표 불완전/범위 위반/모순(`swLat>=neLat` 등) |
| `LISTING_AREA_TOO_LARGE` | 400 | 지도 마커 결과가 너무 많아 한 번에 표시하기 어려움 |
| `LISTING_UNKNOWN_CATALOG_CODE` | 400 | 요청에 실린 코드 값이 `listingCatalog`의 `(category, code)`에 없음 |
| `LISTING_NOT_EDITABLE` | 422 | 심사 대기(`PENDING`)·수정 심사 대기(`UPDATE_PENDING`) 상태라 임대인이 수정할 수 없음 |
| `LISTING_STATE_CHANGED` | 409 | 조회 후 저장 사이에 매물 상태가 바뀜 — 다시 조회한 뒤 재시도한다 |

> `LISTING_NOT_FOUND`는 04-booking-inquiry-chat 스펙에서도 참조한다. 카탈로그 중복 등록을 피하기 위해 해당 코드의 정본 정의는 본 listing 스펙에 둔다. **deprecated된 v1 상세·찜 토글 스텁도 이 코드를 쓴다** — 매물을 찾지 못해서가 아니라 조회하지 않기 때문이며, 새 코드를 만들지 않아 구버전 앱이 이미 처리하던 에러 그대로 받는다(위 [v1 스텁 동작](#v1-스텁-동작)).
> `LISTING_UNKNOWN_CATALOG_CODE`는 매물 등록(`POST /api/v2/listings`)과 매물 수정(`PUT /api/v2/listings/{listingId}`)이 함께 쓴다 — 수정도 같은 카탈로그 대조를 그대로 통과해야 한다. 임대인 아님(403 `FORBIDDEN`)·온보딩 미완료(403 `AUTH_ONBOARDING_REQUIRED`)·필수값 누락과 형식 위반(400 `INVALID_INPUT`)은 공통 코드를 그대로 쓰며 `LISTING_*` 코드를 신설하지 않는다([error-response-guide](../error-response-guide.md) §4).
> **주소 검색(`GET /api/v1/listings/addresses`)도 전용 코드를 두지 않는다** — 키워드 검증은 `INVALID_INPUT`, 외부 연동 실패는 `UPSTREAM_ERROR`(502)이며 둘 다 공통 코드다. 지원하지 않는 지역이라고 거절하지 않는다 — 검색도 등록도 통과시키고, 행정구역만 `ETC`로 저장한다([ADR-0046](../../adr/0046-administrative-region-as-catalog-data.md)).
> `LISTING_NOT_EDITABLE`·`LISTING_STATE_CHANGED`는 [임대인 매물 관리](#임대인-매물-관리)의 매물 수정 전용이다. **남의 매물을 수정하거나 조회하려 한 경우에는 인가 전용 코드를 두지 않고 `404 LISTING_NOT_FOUND`를 쓴다** — 한 API가 상태에 따라 403과 404를 오가면 그 차이가 매물의 존재를 누설한다.
> 하트 토글은 이미 찜/미찜 상태여도 에러로 보지 않고 현재 하트 상태와 찜 수를 반환한다. 프론트는 응답 body의 `favorited`, `favoriteCount`만 보고 UI를 맞추면 된다.

---

## 관리자 매물 심사

> 관리자(`userType=ADMIN`)가 임대인이 올린 매물을 심사하는 4종이다. 경로는 `/api/v1/admin/listings`이며, 매물 조회 정본이 `/api/v2`인 것과 무관하게 **신규 네임스페이스라 `/api/v1`에서 시작한다**(대체할 v1 계약이 없으므로 버전 정책상 v2를 붙일 근거가 없다 — [api-design-guide §2-1](../api-design-guide.md)). 유저 스토리는 US-3-7이다.

### 관리자 계정과 인가

- **관리자는 회원가입 경로가 없다.** 운영자가 관리자 전용 계정을 임대인 웹 가입 흐름(`POST /api/v1/auth/signup`)으로 만든 뒤 DB에서 승격한다. 로그인·계정 연동은 임대인과 완전히 동일하다(이메일·비밀번호 `local_accounts`, 가입 전 SMS 인증, HttpOnly 쿠키 refresh — [ADR-0047](../../adr/0047-web-local-credentials-and-phone-based-account-linking.md)·[ADR-0048](../../adr/0048-web-refresh-token-httponly-cookie.md)).

  ```sql
  -- 승격은 반드시 ACTIVE 계정에만 한다. 온보딩 미완료 계정을 승격하면 ROLE_ONBOARDING 토큰만 받아
  -- 보안 매처를 통과하지 못한 채 권한만 갖는 계정이 된다.
  UPDATE users SET user_type = 'ADMIN' WHERE id = ? AND status = 'ACTIVE';
  ```

  **활동 중인 임대인·세입자 계정을 승격하지 않는다** — `ADMIN`은 제3의 유형이라 승격하면 이전 역할을 잃고(매물 등록 불가), 매물을 보유한 임대인을 승격하면 자기 매물을 자기가 심사할 수 있게 된다.

- **인가는 이중이다.** `SecurityConfig`의 `/api/v1/admin/**` → `hasRole("USER")` 매처가 1차로 거르고, 서비스가 `userType=ADMIN`을 다시 확인해 아니면 `403 FORBIDDEN`이다. 토큰에는 관리자 여부를 담지 않으므로 **권한 부여·회수가 즉시 반영**된다.
- **반대 방향도 막힌다.** 관리자가 호출할 수 있는 것과 없는 것은 아래와 같다. 막는 주체는 보안 매처가 아니라 **각 서비스의 허용 목록 게이트**(세입자 또는 임대인만 통과)이며 `403 FORBIDDEN`이다.

  | 호출할 수 있다 | 호출할 수 없다 |
  |---|---|
  | 웹 가입·로그인·토큰 재발급·로그아웃 | 이메일 인증(`POST /api/v1/auth/email/**`) |
  | 프로필 조회·수정·탈퇴(`/api/v1/users/me`) | 차단 목록·해제(`/api/v1/users/me/blocks`) |
  | 매물 공개 조회(`GET /api/v2/listings**`) | 찜 토글·내 찜 목록·최근 본 매물 |
  | 진단 · 퀴즈 · 생활 팁 | 예약 신청·조회·삭제·차단·신고 |
  | 매물 심사 4종 | 매물 문의·채팅방 전체 · 커뮤니티 전체 |
  | | 매물 등록·사진 업로드·주소/역 검색·사업자번호 검증 |

  온보딩 단계에서도 쓰이는 엔드포인트(연락처 인증 `POST /api/v1/auth/phone/**`)는 예외로 걸러지지 않는다 — 회원 유형이 온보딩 **제출** 시점에 확정되는데 임대인 온보딩은 연락처 인증을 **선행**으로 요구하므로, 유형으로 거르면 정상 가입이 막힌다.

### 심사 응답의 노출 범위

심사 상세·목록은 **매물 문서에 저장된 모든 필드**를 담는다 — 세입자 응답이 감추는 `landlordId`·`businessRegistrationNumber`·설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`)·`rejectionReason`을 감추지 않는다. 표시 여부는 관리자 화면이 정한다.

노출해도 되는 근거는 둘이다 — 매물 문서에는 **임대인 개인 연락처가 저장되지 않으므로**(`contact.phone`은 지점 대표 전화, [ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md) Amended) 마스킹 대상 PII가 이 응답에 없고, `businessRegistrationNumber`는 **관리자가 심사에서 진위를 수동 확인해야 하는 값**이라 오히려 필수다([ADR-0033](../../adr/0033-business-registry-verification.md) 개정). 표시 언어는 임대인 화면과 같이 한국어 고정이다.

### GET /api/v1/admin/listings — 매물 심사 목록

- 설명: **모든 상태**의 매물을 등록 최신순으로 조회한다. `status`로 상태별 필터가 가능하다.
- 인증: 필수(관리자)

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | `ListingStatus[]` | 선택 | 상태 필터. 콤마로 여러 개(`?status=PENDING,REJECTED`). **생략하면 전체** |
| `page` | integer | 선택 | 0부터. 기본 `0` |
| `size` | integer | 선택 | 기본 `20`, 최대 `100` |

발생 가능한 에러: `400 INVALID_INPUT`(정의되지 않은 `status`, `size` 범위 초과) · `403 FORBIDDEN`(관리자 아님) · `403 AUTH_ONBOARDING_REQUIRED` · `401 UNAUTHENTICATED`/`TOKEN_EXPIRED`.

### GET /api/v1/admin/listings/{listingId} — 심사용 상세

- 설명: 심사 대상 매물의 **저장된 전 필드**를 반환한다. 상태와 무관하게 조회된다.
- 인증: 필수(관리자)

발생 가능한 에러: `404 LISTING_NOT_FOUND`(없는 id·ObjectId 형식 아님) · `403 FORBIDDEN` · `401 UNAUTHENTICATED`/`TOKEN_EXPIRED`.

### POST /api/v1/admin/listings/{listingId}/approval — 매물 승인

- 설명: 매물을 `PUBLISHED`로 전이시킨다. 요청 본문이 없다. **상태를 가리지 않는다** — 심사 대기 매물의 승인뿐 아니라 잘못 반려한 매물을 되살리는 **재승인**도 이 경로다. 승인 시 이전 `rejectionReason`을 **비운다**. **이미 공개 중이면 아무 일도 하지 않는다**(정렬을 흔들지 않기 위해 `updatedAt`도 두지 않는다).
- 인증: 필수(관리자)
- 성공: `200 OK` + 심사 상세와 같은 구조. 상태 전이 액션이므로 `201`이 아니다.

승인 직후부터 그 매물은 세입자의 목록·지도·상세 조회에 나타난다.

발생 가능한 에러: `404 LISTING_NOT_FOUND` · `403 FORBIDDEN` · `401 UNAUTHENTICATED`/`TOKEN_EXPIRED`. **상태로 인한 거절은 없다.**

### POST /api/v1/admin/listings/{listingId}/rejection — 매물 반려

- 설명: 매물을 `REJECTED`로 전이시키고 사유를 저장한다. **상태를 가리지 않는다** — 심사 대기 매물의 1차 반려, 공개 매물을 내리는 **사후 반려**, 이미 반려한 매물의 **사유 정정**이 모두 이 경로다.
- 인증: 필수(관리자)
- 성공: `200 OK` + 심사 상세와 같은 구조.

```jsonc
{
  "reason": "사업자등록번호와 매물 주소가 일치하지 않습니다."
}
```

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | --- | --- |
| `reason` | string | 필수 | 반려 사유. 공백 불가, 1~500자. 임대인만 읽는 값이라 **번역하지 않는다** |

승인과 반려를 하나의 상태 변경 API로 묶지 않은 이유는 **"반려에는 사유가 필요하다"를 요청 타입으로 강제**하기 위해서다. `PATCH /{id}/status`였다면 `status` 값에 따라 `reason` 필수 여부가 갈리는 조건부 검증이 되고, 승인 요청에 사유가 실려 와도 타입으로 막을 수 없다.

발생 가능한 에러: `400 INVALID_INPUT`(`reason` 누락·공백·500자 초과) · `404 LISTING_NOT_FOUND` · `403 FORBIDDEN` · `401 UNAUTHENTICATED`/`TOKEN_EXPIRED`. **상태로 인한 거절은 없다.**

---

## 임대인 매물 관리

> 임대인(`userType=LANDLORD`)이 **자기 매물**을 수정하고 조회하는 3종이다. 수정은 `PUT /api/v2/listings/{listingId}`, 조회는 `GET /api/v2/users/me/listings`(+`/{listingId}`)이며 요청·응답 모두 매물 조회 정본과 같은 스키마 v4 구조다([ADR-0039](../../adr/0039-listing-schema-v4-registration-form.md)). 인가는 매물 등록과 같은 이중 인가이고(`hasRole("USER")` 매처 + 서비스의 `userType=LANDLORD` 재검사), **자기 매물이 아니면 `404 LISTING_NOT_FOUND`** 다 — `403`을 쓰지 않는 이유는 한 API가 상태에 따라 두 코드를 오가면 그 차이가 남의 매물의 존재를 알려주기 때문이다. 유저 스토리는 조회가 US-3-8, 수정이 US-3-9다.

### 수정 시 상태 전이

수정할 수 있는지는 **매물의 현재 상태 하나가 정한다.** 임대인이 고를 수 있는 것은 없다.

| 수정 전 | 수정 후 | 뜻 |
| --- | --- | --- |
| `REJECTED`(반려) | **`PENDING`** | 반려 사유를 고쳐 다시 심사에 올린다. 최초 등록과 같은 줄에 선다 |
| `PUBLISHED`(공개) | **`UPDATE_PENDING`** | 재심사에 올린다. **심사가 끝날 때까지 세입자 노출에서 빠진다**(아래) |
| `PENDING`(심사 대기) | **`422 LISTING_NOT_EDITABLE`** | 이미 심사 대기열에 있다 |
| `UPDATE_PENDING`(수정 심사 대기) | **`422 LISTING_NOT_EDITABLE`** | 같은 이유. **수정 신청 취소도 없다** |

- 반려 사유는 **수정으로 지워지지 않는다.** 고쳐서 다시 올린 매물이 사유를 그대로 들고 `PENDING`으로 간다 — 임대인은 심사를 기다리는 동안 무엇을 고치라고 했는지 다시 볼 수 있고, 재심사하는 관리자는 이 매물이 전에 왜 반려됐는지 안다. 「지금 고쳐야 한다(`REJECTED`)」와 「고쳐서 재심사 중(`PENDING`)」은 **상태가 이미 구분**하므로 값이 남아도 혼동되지 않는다. 지우는 것은 **승인 시점 하나뿐**이다. 임대인이 요청으로 이 값을 바꿀 수는 없다(요청 본문에 칸이 없다).
- 심사 쪽은 달라지는 것이 없다. [관리자 매물 심사](#관리자-매물-심사)의 승인·반려는 **상태를 가리지 않으므로** `UPDATE_PENDING` 매물의 승인(`→ PUBLISHED`)·반려(`→ REJECTED`)도 같은 경로 그대로다.
- `PENDING`·`UPDATE_PENDING`에서 손댈 수 없다는 것은 심사가 밀리면 임대인이 오타 하나도 못 고친 채 기다린다는 뜻이다. 수정 신청을 취소할 수단이 없는 것도 같은 이유다 — 서버가 **수정 전 본문을 보관하지 않는다.**

**공개 중인 매물을 수정하면 심사가 끝날 때까지 세입자에게 보이지 않는다.** 심사를 거치지 않은 내용이 세입자에게 도달하지 않아야 하므로 **의도된 동작**이며, 오타 하나를 고쳐도 마찬가지다. 임대인 화면은 수정을 제출하기 전에 이 사실을 고지한다.

| 세입자 쪽 | `UPDATE_PENDING` 동안 | 승인 뒤 |
| --- | --- | --- |
| 목록 · 지도 · 상세 · 진단 추천 | 안 보임 | 그대로 복구 |
| 내 찜 목록 · 최근 본 매물 | 안 보임(찜 문서·조회 기록은 **지워지지 않는다**) | 그대로 복구 |
| 찜 등록 · 찜 해제 | `404 LISTING_NOT_FOUND` | 정상 |
| 예약 신청 · 매물 문의(채팅 개설) | `404 LISTING_NOT_FOUND` | 정상 |
| **이미 잡힌 예약의 카드 표시** | **정상 표시** | 정상 |
| 이미 열린 채팅방 | 영향 없음 | 영향 없음 |

- 사라지는 것은 전부 **가역**이다. 승인되면 `favoriteCount`·찜 문서·최근 본 기록이 그대로 살아 있는 채 목록에 다시 나타난다.
- **진행 중인 예약의 카드만 예외로 계속 표시된다.** 예약 카드의 매물명·사진·금액은 예약 데이터가 아니라 매물에서 읽어 오는 값이라, 여기서까지 매물이 빠지면 이미 예약을 잡은 세입자·임대인의 화면이 빈 카드가 된다. 그래서 표시 전용 조회를 따로 두고 **매물 상태도 방 상태도 보지 않게** 했다. **신규 예약 생성은 계속 막힌다**([04-booking-inquiry-chat](./04-booking-inquiry-chat.md)).
- 수정이 반려되면 직전에 공개돼 있던 본문은 서버에 남지 않는다. 보관본이 없으므로 **되돌리는 것이 아니라 다시 쓰는 것**이다.

### PUT /api/v2/listings/{listingId} — 매물 수정(임대인)

- 설명: 임대인이 자기 매물의 내용을 고친다. **부분 수정이 아니라 전체 교체**이므로 등록 때 보낸 속성을 그대로 다시 보낸다 — 보내지 않은 필드는 지워진다.
- **설문 2종(`preferredNationalities`·`contractDifficulties`)은 선택이지만 전체 교체 규칙은 그대로 적용된다** — 보내지 않으면 저장돼 있던 응답이 **빈 배열로 지워진다**(`blogUrl`·`serviceFeedback`과 같다). 값을 유지하려면 `GET /api/v2/users/me/listings/{listingId}`가 준 값을 그대로 다시 실어야 한다. 저장에 성공하면 위 전이표대로 상태가 바뀌어 재심사에 오른다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`) 중 **임대인**(`userType=LANDLORD`). 소유자 판정은 access 토큰의 사용자와 매물의 `landlordId`를 대조하며, 남의 매물이면 `404 LISTING_NOT_FOUND`다.
- 메서드: **`PUT`이다.** `location`·`address.city`/`district`·`nearbyUniversityCodes`가 모두 주소에서 파생되는 값이라, 일부만 보내는 `PATCH`를 허용하면 파생값이 본문과 어긋난 상태로 남는다([api-design-guide](../api-design-guide.md)).
- 선행 호출: 수정 화면의 프리필은 `GET /api/v2/users/me/listings/{listingId}`가 준다. 주소·역은 등록과 같이 `GET /api/v1/listings/addresses`·`GET /api/v1/listings/stations`로 다시 검색해 고른 값을 담고, 새로 넣을 사진은 `POST /api/v2/listings/images`로 먼저 올려 키를 받는다.
- Content-Type: **`application/json`**

Path 파라미터: `listingId` — 수정할 매물 id.

Request Body: **매물 등록(`POST /api/v2/listings`)의 요청 본문과 같다.** 필드 이름·타입·필수 여부·검증·카탈로그 대조가 모두 같으므로 여기서 되풀이하지 않으며, **다른 곳은 아래 두 가지뿐이다.**

| 다른 점 | 필드 | 설명 |
| --- | --- | --- |
| 방마다 식별자와 상태를 함께 보낸다 | `roomOffers[].roomOfferId` | string(ObjectId) · 선택. 기존 방이면 **그 id를 그대로**, 새로 추가하는 방이면 `null`(또는 생략)이다. 그 매물의 방이 아닌 id는 `400 INVALID_INPUT` |
| " | `roomOffers[].status` | `ACTIVE` \| `INACTIVE` · 필수. 방을 내리는 것은 **요청에서 빼는 것이 아니라 `INACTIVE`로 보내는 것**이다 |
| 사진 키에 기존 사진을 섞는다 | `imageKeys` · `roomOffers[].roomImageKeys` | 새로 올린 **임시 키**(`uploads/…`)와 그 자리에 이미 있던 **확정 키**(`listings/…`)를 섞어 보낸다(아래 사진 규칙) |

요청 주의사항:

- **서버가 정하는 값은 여전히 요청 본문에 없다.** `status`·`rejectionReason`·`favoriteCount`·`createdAt`·`schemaVersion`·`landlordId`는 칸 자체가 없어 요청으로 바꿀 수 없다. 특히 **`rejectionReason`은 수정에 성공하면 서버가 무조건 비운다.**
- **방은 하드 삭제하지 않는다.** 예약·채팅이 `roomOfferId`를 참조하므로 내린 방도 문서에 남으며, 나중에 같은 id를 `ACTIVE`로 다시 보내면 사진까지 그대로 되살아난다. **요청 배열의 순서가 곧 저장 순서**이고 `ACTIVE`와 `INACTIVE`가 섞여 있어도 그대로 둔다.
- **요청에서 id가 통째로 빠진 기존 방**은 삭제가 아니라 안전망으로 `INACTIVE` 전환 뒤 배열 맨 뒤로 밀린다(원래 상대순서 유지). 그렇게 밀린 방은 아래 임대인 상세에도 나오지 않아 되살릴 수 없으므로, 방을 내릴 때는 반드시 `status=INACTIVE`로 보낸다.
- **저장 결과에 `ACTIVE` 방이 하나도 없으면 `400 INVALID_INPUT`이다.** 전부 내린 매물은 상태만 공개인 채 목록·상세 어디에도 나오지 않는 유령이 되기 때문이다.
- 사진 장수 제한(대표 1~5장, 방마다 2~5장)은 **임시 키와 확정 키를 합친 최종 배열** 기준이다.
- 등록과 마찬가지로 **주소·역은 먼저 검색한다.** 좌표가 바뀌면 `address.city`·`address.district`와 `nearbyUniversityCodes`를 서버가 다시 파생한다 — 주소를 대학가 밖으로 옮기면 인근 대학이 빈 배열이 되어 진단 추천에서 빠지며, 등록과 같은 정책이라 저장 자체는 성공한다.

사진 키 규칙 — **자리마다 허용되는 키가 다르다.**

| 보낸 키 | 넣은 자리 | 판정 |
| --- | --- | --- |
| 임시 키 `uploads/{내 landlordId}/…` | 최상위 `imageKeys` · 아무 방의 `roomImageKeys` | 허용 — **JSON에서 놓인 자리가 역할을 정한다**(등록과 동일). 저장에 성공하면 확정 위치로 옮겨 간다 |
| 확정 키 `listings/{listingId}/cover/…` | 최상위 `imageKeys` | 허용 — 그대로 유지되고 다시 복사하지 않는다 |
| 확정 키 `listings/{listingId}/rooms/{roomOfferId}/…` | **그 `roomOfferId`의** `roomImageKeys` | 허용 — 그대로 유지된다 |
| 확정 키(어느 방의 사진) | 최상위 `imageKeys` | `400 LISTING_IMAGE_KEY_NOT_FOUND` — **방 사진을 대표사진으로 옮길 수 없다.** 승격하려면 다시 업로드한다 |
| 확정 키(대표사진) | 어느 방의 `roomImageKeys` | `400 LISTING_IMAGE_KEY_NOT_FOUND` — 반대 방향도 금지다 |
| 확정 키(다른 방의 사진) | 그 방이 아닌 방 | `400 LISTING_IMAGE_KEY_NOT_FOUND` — 같은 매물이라도 방이 다르면 안 된다 |
| 확정 키 | `roomOfferId`가 `null`인 **새 방** | `400 LISTING_IMAGE_KEY_NOT_FOUND` — 아직 id가 없어 그 방의 확정 키는 존재할 수 없다. **새 방은 임시 키만 쓴다** |
| 남의 매물의 확정 키 · 문서에 없는 확정 키 · 만료된 임시 키 | 어디든 | `400 LISTING_IMAGE_KEY_NOT_FOUND` — 등록과 같은 코드다. 구분해 알려주면 남의 사진이 있는지 없는지가 새어 나간다 |

확정 키의 경로에 역할(`cover` / `rooms/{roomOfferId}`)이 이미 박혀 있어서 생기는 비대칭이다 — 임시 키는 역할 정보가 없으니 JSON 위치가 정하고, 확정 키는 경로가 이미 정해 뒀으니 **그 자리에서 온 것만** 다시 받는다. 이 대조가 소유권 검사도 겸한다.

- **교체된 사진은 저장이 성공한 뒤에 지운다.** 옛 문서에는 있고 이번 최종 배열에는 없는 확정 사진만 대상이다. 저장 *전에* 지우지 않는 이유는 검증이나 저장이 실패했을 때 **공개 중인 매물의 사진이 사라지기** 때문이다(등록이 "복사 → 저장 → 실패 시 되돌리기"를 계약으로 둔 것과 같은 이유 — [ADR-0041](../../adr/0041-listing-image-upload-to-s3.md)).
- `INACTIVE`로 내린 방의 사진은 문서에 그대로 남으므로 **지워지지 않는다.** 그 방을 되살리면 사진도 함께 돌아온다.

성공 Response (200): **매물 등록·매물 상세와 같은 v4 구조**이며 `status`만 전이 결과(`PENDING` 또는 `UPDATE_PENDING`)로 내려간다. `imageUrls`·`roomOffers[].roomImageUrls`는 이번 요청의 최종 배열 순서를 그대로 따르고, 유지한 사진의 URL은 수정 전과 같다. 상태 전이 액션이므로 `201`이 아니다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 등록과 같은 필수값·형식·범위 위반. 더해서 **그 매물의 것이 아닌 `roomOfferId`**, **저장 결과에 `ACTIVE` 방 0개** |
| 400 | `LISTING_UNKNOWN_CATALOG_CODE` | 요청에 실린 코드 값이 `listingCatalog`의 `(category, code)`에 없음 |
| 400 | `LISTING_IMAGE_REQUIRED` | 최종 배열이 대표 1~5장 · 방 2~5장을 벗어남 |
| 400 | `LISTING_IMAGE_KEY_NOT_FOUND` | 키가 남의 것·없는 것·만료된 것이거나, **확정 키를 원래 자리가 아닌 곳에** 넣음(위 사진 규칙) |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 불가/타입 불일치 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰(`ROLE_ONBOARDING`) |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
| 404 | `LISTING_NOT_FOUND` | 없는 id · ObjectId 형식 아님 · **남의 매물** |
| 409 | `LISTING_STATE_CHANGED` | 요청을 처리하는 사이에 관리자가 승인·반려해 상태가 바뀜. **다시 조회한 뒤 재시도**한다 |
| 422 | `LISTING_NOT_EDITABLE` | 매물이 `PENDING`·`UPDATE_PENDING`이라 수정할 수 없음 |
| 502 | `UPSTREAM_ERROR` | 사진 저장소 복사 실패. 매물은 바뀌지 않고 이번에 복사한 사진은 서버가 지운다. **기존 사진과 임시 사진은 그대로 남아** 다시 제출할 수 있다 |

> `409 LISTING_STATE_CHANGED`는 임대인이 화면을 연 뒤 제출하기까지의 간격이 아니라 **서버가 매물을 읽고 저장하기까지의 간격**에서 난다(사진을 옮기는 동안 관리자가 심사를 끝낸 경우). 저장은 읽을 때의 상태가 그대로일 때만 이뤄지므로 관리자의 승인·반려가 임대인의 전체 교체에 조용히 덮이지 않는다. 클라이언트는 이 코드를 실패가 아니라 **재조회 신호**로 다룬다.

### GET /api/v2/users/me/listings — 내 매물 목록(임대인)

- 설명: 임대인 웹의 「내 매물」 화면 목록이다. **상태를 가리지 않고** 자기 매물만 최근 수정순으로 반환한다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`) 중 **임대인**(`userType=LANDLORD`)
- 경로: `/api/v2/users/me` 아래에 둔다. `/api/v2/listings/mine`은 공개 조회 매처(`GET /api/v2/listings/*` `permitAll`)에 먼저 잡혀 **비로그인에 열린다.**

Query 파라미터:

| 이름 | 타입 | 필수 | 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `status` | `ListingStatus[]` | 선택 | — | 상태 필터. 콤마로 여러 개(`?status=REJECTED,UPDATE_PENDING`). **생략하면 전체**. 관리자 심사 목록과 같은 계약이다 |
| `page` | integer | 선택 | 0 | 0부터 시작하는 페이지 번호 |
| `size` | integer | 선택 | 20 | 한 번에 가져올 매물 수(최대 100) |

**정렬 파라미터는 없다.** `updatedAt desc`(최근 수정순) 고정이라 방금 고친 매물이 맨 위에 온다. 찜 목록이 `favoritedAt desc`로 고정된 것과 같은 방식이며, 나중에 정렬을 여는 것은 하위 호환을 깨지 않는 추가다.

Request Body: 없음

성공 Response (200): 세입자 목록(`GET /api/v2/listings`)의 카드와 **같은 항목 구조**에 `rejectionReason` 한 필드가 더 붙는다. 페이지 구조(`page.number`·`size`·`totalElements`·`totalPages`·`hasNext`)도 같다.

| 필드 | 설명 |
| --- | --- |
| `status` | 카드의 상태 배지에 쓴다. `PENDING`·`PUBLISHED`·`REJECTED`·`UPDATE_PENDING` 넷 다 나올 수 있다 |
| `rejectionReason` | 반려 사유. `REJECTED`와, 고쳐서 재심사 중인 `PENDING`에 값이 있다. 승인되면 사라진다 |
| `favoriteCount` | 찜 수. 심사 때문에 세입자 목록에서 빠져 있는 동안에도 **줄어들지 않는다** |

- 목록 항목은 카드용이라 `businessRegistrationNumber`·설문 3종·사진 키를 담지 않는다. 그 값들은 수정 폼이 쓰는 것이라 아래 상세가 준다.
- `roomOffers[]`와 상위 `conditions`는 세입자 목록과 같은 기준이라 **`ACTIVE` 방만** 반영한다. 내린 방까지 보려면 상세를 쓴다.
- 표시 언어는 임대인 계정의 표시 언어를 따르며, `status`는 관리 상태라 번역 없이 코드 문자열 그대로 내려간다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 정의되지 않은 `status`, `page` 음수, `size` 범위 초과 |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |

### GET /api/v2/users/me/listings/{listingId} — 내 매물 상세(임대인)

- 설명: 수정 화면이 폼을 채우는 데 쓰는 상세다. 계약은 **"수정 요청에 실을 수 있는 전 필드 + 읽기 전용 표시값"** 이므로 편집 대상은 하나도 빠지지 않는다. 상태와 무관하게 조회된다.
- 인증: 필수 — 온보딩 완료 사용자(`ROLE_USER`) 중 **임대인**(`userType=LANDLORD`). 자기 매물이 아니면 `404 LISTING_NOT_FOUND`다.

응답 노출 범위:

| 묶음 | 내용 | 왜 |
| --- | --- | --- |
| 매물 상세 전 필드 | 세입자 상세(`GET /api/v2/listings/{listingId}`)가 주는 것 전부 | 프리필의 본체 |
| 세입자에게 감추는 값 | `businessRegistrationNumber` · 설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`) | **전부 등록·수정 요청 필드**다. 설문 3종은 선택이지만 **보내지 않으면 빈 값으로 덮이므로**(전체 교체) 유지하려면 이 응답이 준 값을 그대로 다시 실어야 한다 |
| 사진 키 | `imageKeys` · `roomOffers[].roomImageKeys` | 유지할 사진을 그대로 되돌려 보내려면 URL이 아니라 **키**가 필요하다. 미리보기용 URL(`imageUrls`·`roomImageUrls`)도 함께 내려간다 |
| 방 식별자·상태 | `roomOffers[].roomOfferId` · `roomOffers[].status` | 수정 요청이 요구하는 값이다. **`INACTIVE` 방도 포함해** 내려간다(세입자·관리자 응답은 `ACTIVE`만 준다) — 그래야 되살릴 수 있다 |
| 읽기 전용 | `status` · `rejectionReason` | 같은 화면이 상태 배지와 반려 사유를 보여준다. 요청에는 칸이 없다 |

- 요청에서 id가 빠져 안전망으로 내려간 방은 **이 응답에도 나오지 않는다.** 방을 내릴 때 요청에서 빼지 말고 `status=INACTIVE`로 보내야 하는 이유다.
- 표시 언어는 임대인 계정의 표시 언어를 따른다. 등록·수정이 다국어 문구를 한국어 한 값으로만 받으므로 영어 문구도 같은 값일 수 있다.

발생 가능한 에러:

| status | code | 시점 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 토큰 없음·위조·형식 오류 / 만료 |
| 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
| 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
| 404 | `LISTING_NOT_FOUND` | 없는 id · ObjectId 형식 아님 · **남의 매물** |
