package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.codeArrayField;
import static com.kohere.docs.ApiDocsFields.codeField;
import static com.kohere.docs.ApiDocsFields.codeList;
import static com.kohere.docs.ApiDocsFields.enumArrayField;
import static com.kohere.docs.ApiDocsFields.enumField;
import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static com.kohere.docs.ApiDocsFields.optEnumArrayField;
import static com.kohere.docs.ApiDocsFields.optField;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;

import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.KitchenFacility;
import com.kohere.listing.domain.LaundryFacility;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.LivingAmenity;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.ProvidedSupply;
import com.kohere.listing.domain.SecurityFeature;
import com.kohere.listing.domain.SupportedLanguage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;

/**
 * Listings 태그 오퍼레이션의 문서 자산(#151 후속 정리).
 *
 * <p>{@code ListingDocsTest}에 흩어져 있던 오퍼레이션 문구 상수(summary·description)와 파라미터·응답 필드 기술자를 태그 단위로 모은
 * 것이다. 매물 목록({@code GET /listings})·지도 마커({@code GET /listings/map})·장소 후보({@code GET
 * /listings/places})·상세({@code GET /listings/{listingId}})·찜 등록/해제({@code POST·DELETE
 * /listings/{listingId}/favorite})·내 찜 목록({@code GET /users/me/favorites})·최근 본 매물({@code GET
 * /users/me/recent-listings})에 매물 등록({@code POST /api/v2/listings})을 더한 9개 오퍼레이션을 다룬다.
 *
 * <p>같은 {@code (path, method)} 오퍼레이션의 성공 스니펫과 에러 스니펫은 <b>같은 상수</b>를, 같은 {@code (path, method,
 * status)}의 스니펫은 <b>같은 기술자 헬퍼</b>를 써야 한다({@link ApiDocsFields} 클래스 주석 참조). 여기 한 벌만 두는 이유다.
 */
public final class ListingDocsFields {

  private ListingDocsFields() {}

  /**
   * 주변 대학 코드 목록이다.
   *
   * <p>매물 문서는 이 값을 문자열로 들고 있고 정본은 DB 카탈로그({@code UNIVERSITY} 카테고리)라 enum 클래스가 없다. 그래서 {@code
   * enumField}가 아니라 값을 직접 나열한다.
   */
  private static final List<String> UNIVERSITY_CODES =
      List.of(
          "SNU",
          "CAU",
          "SOONGSIL",
          "KHU",
          "HUFS",
          "KOREA",
          "SKKU",
          "SUNGSHIN",
          "KONKUK",
          "SEJONG",
          "HYU",
          "YONSEI",
          "EWHA",
          "HONGIK");

  /** 공개 조회 응답에 실제로 도달할 수 있는 매물 상태다. 심사·중단·삭제 상태는 조회 결과에서 제외되므로 나열하지 않는다. */
  private static final List<String> PUBLIC_LISTING_STATUSES = List.of("PUBLISHED");

  /** 등록 응답에 도달할 수 있는 매물 상태다. 승인·반려 전이는 후속 관리자 API가 담당한다. */
  private static final List<String> REGISTERED_LISTING_STATUSES = List.of("PENDING");

  /** 공개 조회 응답에 실제로 도달할 수 있는 방 타입 상태다. */
  private static final List<String> PUBLIC_ROOM_OFFER_STATUSES = List.of("ACTIVE");

  /**
   * 같은 매물 문서를 내려주는 세 갈래다. 상태의 도달 가능한 값이 갈려 인자로 구분한다.
   *
   * <p>공개 조회는 {@code PUBLISHED}만, 등록 응답은 {@code PENDING}만 나오고, 임대인·관리자 응답은 상태를 가리지 않아 네 값이 모두 나온다.
   * 좌표는 세 갈래가 같다 — 등록이 주소 검색이 준 좌표를 채우기 때문이다(ADR-0042).
   */
  private enum ListingDocumentVariant {
    PUBLIC_QUERY,
    REGISTERED,
    MANAGED
  }

  /**
   * 매물 본문을 통째로 받는 두 갈래다. 수정은 부분 수정이 아니라 <b>전체 교체</b>라 등록과 같은 본문을 다시 받는다.
   *
   * <p>다른 곳은 방의 식별자·상태 두 칸과 사진 키의 종류뿐이다. 같은 필드라도 응답에 나오는지가 갈리는데, 등록 응답은 세입자 상세라 사업자등록번호·설문을 감추고 수정
   * 응답은 임대인 상세라 그대로 싣는다.
   */
  private enum ListingWriteVariant {
    REGISTER,
    UPDATE
  }

  // ── §1 매물 목록 — GET /api/v2/listings ────────────────────────────────────

  public static final String LISTINGS_LIST_SUMMARY = "지도 바텀시트 매물 리스트 조회";

  public static final String LISTINGS_LIST_DESCRIPTION =
      """
      지도 화면의 바텀시트나 리스트 화면에 보여줄 매물 카드를 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다.

      **응답 주의사항**

      - 표시 문구의 **언어가 토큰에 따라 달라진다** — 온보딩을 완료한 로그인 사용자는 계정 언어, 그 외(비로그인·온보딩 미완료)는 영어다. 같은 매물이라도 로그인 전후로 문구가 바뀌므로 응답을 캐시한다면 인증 상태를 키에 넣는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 범위/enum 위반(`minBudget>maxBudget`, 미정의 `conditions`/`sort` 등), `size` 범위 초과 |
      | 400 | `LISTING_INVALID_BBOX` | bbox 네 좌표가 일부만 있거나 범위·방향이 올바르지 않음 |
      | 400 | `LISTING_INVALID_SORT_PARAM` | `sort=DISTANCE`인데 bbox 네 좌표가 없음 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |
      """;

  // ── §2 지도 마커 — GET /api/v2/listings/map ────────────────────────────────

  public static final String LISTINGS_MAP_SUMMARY = "지도 마커 조회";

  public static final String LISTINGS_MAP_DESCRIPTION =
      """
      현재 지도 영역에 표시할 매물 마커 좌표만 빠르게 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다.

      **응답 주의사항**

      - 가격·이미지·주소가 필요한 바텀시트는 `GET /api/v2/listings`를 **같은 필터로 함께 호출**한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `LISTING_INVALID_BBOX` | bbox 좌표 불완전/범위 위반/모순(`swLat>=neLat` 등) |
      | 400 | `LISTING_AREA_TOO_LARGE` | 지도 마커 결과가 너무 많아 한 번에 표시하기 어려움 |
      | 400 | `INVALID_INPUT` | 필터 enum/범위 위반 등 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |
      """;

  // ── §4 네이버 장소 후보 — GET /api/v1/listings/places ──────────────────────

  public static final String LISTING_PLACES_SUMMARY = "네이버 장소 후보 검색";

  public static final String LISTING_PLACES_DESCRIPTION =
      """
      지도 검색창의 `keyword`로 네이버 지역 검색을 호출하고 정확도순 장소 후보를 최대 5개 반환한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다.

      **응답 주의사항**

      - 매물은 이 응답에 없다. 고른 장소의 좌표로 `GET /api/v2/listings`·`/map`을 이어서 호출한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 키워드 누락·공백·길이(1~50자) 위반 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |
      | 502 | `UPSTREAM_ERROR` | 네이버 HTTP 오류·타임아웃·인증정보 누락·응답 또는 좌표 형식 이상 |
      """;

  // ── §5 매물 상세 — GET /api/v2/listings/{listingId} ────────────────────────

  public static final String LISTING_DETAIL_SUMMARY = "매물 상세 조회";

  public static final String LISTING_DETAIL_DESCRIPTION =
      """
      목록 카드나 지도 마커에서 매물을 선택한 뒤 상세 화면 전체를 구성할 때 사용한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다.

      **응답 주의사항**

      - 온보딩을 완료한 로그인 사용자는 실제 찜 상태와 계정 언어가 적용되고, 조회한 매물이 최근 본 목록에 기록된다.
      - 비로그인·온보딩 미완료 사용자는 `favorited=false`이며 최근 본 기록을 남기지 않는다. 로그인 전에 조회한 매물은 로그인 후 최근 본 목록으로 소급해 옮기지 않는다.
      - 역명은 이 API에서만 정식 이름으로 내려간다. 목록·검색·찜·최근 본 응답은 영어일 때 `Station`을 `Sta.`로 줄인다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `TOKEN_EXPIRED` | 만료된 access token을 보낸 공개 조회 |
      | 404 | `LISTING_NOT_FOUND` | 없거나 현재 공개되지 않은 매물 |
      """;

  // ── §6 찜 등록 — POST /api/v2/listings/{listingId}/favorite ────────────────

  public static final String FAVORITE_ADD_SUMMARY = "매물 찜 등록";

  public static final String FAVORITE_ADD_DESCRIPTION =
      """
      공개 매물을 찜할 때 호출한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **응답 주의사항**

      - 처음 찜한 경우 `201 Created`, 이미 찜한 매물을 다시 요청한 경우 `200 OK`다. 두 응답 모두 `favorited=true`와 변경 후 `favoriteCount`를 반환한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조·형식 오류 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      | 404 | `LISTING_NOT_FOUND` | 없거나 현재 공개되지 않은 매물 |
      """;

  // ── §7 찜 해제 — DELETE /api/v2/listings/{listingId}/favorite ──────────────

  public static final String FAVORITE_REMOVE_SUMMARY = "매물 찜 해제";

  public static final String FAVORITE_REMOVE_DESCRIPTION =
      """
      매물 찜을 해제할 때 호출한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **응답 주의사항**

      - 이미 찜하지 않은 상태에서 다시 호출해도 에러가 아니다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조·형식 오류 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      | 404 | `LISTING_NOT_FOUND` | 없거나 현재 공개되지 않은 매물 |
      """;

  // ── §8 내 찜 목록 — GET /api/v2/users/me/favorites ─────────────────────────

  public static final String FAVORITES_LIST_SUMMARY = "내 찜한 매물 목록";

  public static final String FAVORITES_LIST_DESCRIPTION =
      """
      마이페이지의 찜한 매물 목록을 페이지 단위로 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **응답 주의사항**

      - 목록의 `favorited`는 항상 `true`다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `page` 음수 또는 `size` 범위 초과 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조·형식 오류 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      """;

  // ── §9 최근 본 매물 — GET /api/v2/users/me/recent-listings ─────────────────

  public static final String RECENT_LISTINGS_SUMMARY = "최근 본 매물 목록";

  public static final String RECENT_LISTINGS_DESCRIPTION =
      """
      마이페이지나 홈의 최근 본 매물 영역에 사용할 최대 10개 매물을 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **응답 주의사항**

      - 온보딩 완료 사용자가 매물 상세 API를 호출하면 최근 본 기록이 서버에서 자동 갱신된다. 비로그인·온보딩 미완료 상태의 조회 기록은 저장하거나 로그인 후 소급 이전하지 않는다.
      - 오래되었거나 더 이상 공개 상태가 아닌 매물은 응답에 포함되지 않는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조·형식 오류 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      """;

  // ── §10-1 매물 사진 업로드 — POST /api/v2/listings/images ──────────────────

  public static final String LISTING_IMAGE_UPLOAD_SUMMARY = "매물 사진 업로드(임대인)";

  public static final String LISTING_IMAGE_UPLOAD_DESCRIPTION =
      """
      등록 폼에서 고른 사진을 **한 장씩** 올린다. 저장 위치(`key`)와 미리보기 주소(`url`)를 돌려주며, 그 키를 모아 매물 등록(`POST /api/v2/listings`)에 보낸다. 매물을 만들지는 않는다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **임대인**(`userType=LANDLORD`) 전용이다.
      - `Content-Type: multipart/form-data` — 파일 part `file` 하나뿐이다.

      **요청 주의사항**

      - **`file` part 하나만 싣는다**

      | part | Content-Type | 개수 | 제한 |
      |---|---|---|---|
      | `file` | `image/jpeg` · `image/png` · `image/webp` · `image/heic` | 1 | 장당 **10MB** 이하 |

      - **왜 한 장씩인가** — 요청이 파일마다 갈려야 브라우저가 **파일별 진행률·전송 속도**를 줄 수 있고, 실패한 파일만 다시 올릴 수 있다. 한 요청에 몰아 실으면 진행 이벤트가 요청 전체 하나뿐이라 그 화면을 만들 수 없다.

      **응답 주의사항**

      - `key`는 등록 요청의 `imageKeys`·`roomOffers[].roomImageKeys`에 담을 값이다.
      - `url`은 폼 미리보기용이다. 등록이 끝나면 사진이 확정 위치로 옮겨 가므로 **이 URL은 곧 무효가 된다** — 이후에는 등록 응답의 URL을 쓴다.
      - 이 사진은 아직 어느 매물의 것도 아니다. **올린 뒤 7일 안에 등록**해야 하며, 참조되지 않은 사진은 자동 삭제된다.
      - 폼에서 뺀 사진은 등록 요청에 담지 않으면 된다. 삭제 API는 없다.
      - 서버는 형식을 변환하지 않는다 — HEIC를 보내면 HEIC로 저장된다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `LISTING_IMAGE_REQUIRED` | `file` part가 없거나 빈 파일 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 413 | `LISTING_IMAGE_TOO_LARGE` | 사진이 10MB를 넘음 |
      | 415 | `LISTING_IMAGE_UNSUPPORTED_TYPE` | 형식이 허용 목록에 없음 |
      | 502 | `UPSTREAM_ERROR` | 사진 저장소 업로드 실패 |
      """;

  public static final String[] LISTING_IMAGE_UPLOAD_400 = {"LISTING_IMAGE_REQUIRED"};

  public static final String[] LISTING_IMAGE_UPLOAD_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  public static final String[] LISTING_IMAGE_UPLOAD_403 = {"FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"};

  public static final String[] LISTING_IMAGE_UPLOAD_413 = {"LISTING_IMAGE_TOO_LARGE"};

  public static final String[] LISTING_IMAGE_UPLOAD_415 = {"LISTING_IMAGE_UNSUPPORTED_TYPE"};

  /** 업로드 응답 필드. 두 값의 쓰임이 달라 설명에서 갈라 준다. */
  public static List<FieldDescriptor> imageUploadResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(field("data", JsonFieldType.OBJECT, "업로드 결과"));
    fields.add(
        field(
            "data.key",
            JsonFieldType.STRING,
            "저장 키. 매물 등록 요청의 imageKeys·roomOffers[].roomImageKeys에 그대로 담는다"));
    fields.add(
        field("data.url", JsonFieldType.STRING, "폼 미리보기용 주소. 등록이 끝나면 사진이 확정 위치로 옮겨 가 무효가 된다"));
    fields.add(errorNull());
    return fields;
  }

  // ── §10 매물 등록 — POST /api/v2/listings ──────────────────────────────────

  public static final String LISTING_REGISTER_SUMMARY = "매물 등록(임대인)";

  public static final String LISTING_REGISTER_DESCRIPTION =
      """
      임대인이 등록 폼에 입력한 지점·건물·공용시설·주변 시설·방 타입과 **먼저 검색해 둔 주소**·**미리 올려 둔 사진의 키**를 매물 하나로 저장한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **임대인**(`userType=LANDLORD`) 전용이다.

      **요청 주의사항**

      - **시설 8종은 「없음」을 `NONE`으로 보낸다.** 해당 시설이 하나도 없으면 `["NONE"]` **하나만** 싱는다 — `NONE`을 다른 코드와 함께 보내면 `400 INVALID_INPUT`이고 `error.errors[].field`에 그 필드 이름이 실린다. ※ **이 규칙은 아래 스키마에 드러나지 않는다** — `NONE`은 허용값 목록의 평범한 값이라 타입상으로는 조합이 유효해 보인다.
      - `NONE`은 행정구역의 `ETC`나 설문의 `OTHER`와 다르다 — 그쪽은 「목록 밖의 값」이고 `NONE`은 「해당하는 것이 없음」이다.
      - **주소를 먼저 검색한다** — 주소 칸은 자유 입력이 아니다. `GET /api/v1/listings/addresses`로 검색해 고른 후보의 값을 아래 세 필드에 그대로 담는다. 검색 결과는 모두 고를 수 있다. **검색 결과가 아닌 좌표를 임의로 만들어 보내지 않는다.**

      | 검색 응답 | 등록 요청 | 내용 |
      |---|---|---|
      | `roadAddress` | `address.fullAddress` | 표준 도로명 주소. 서버가 여기서 시·도와 구·군을 뽑는다 |
      | `lat` | `address.lat` | 위도. 매물의 `location`이 된다 |
      | `lng` | `address.lng` | 경도 |

      - **사진을 먼저 올린다** — 사진 파일은 이 요청에 싣지 않는다. `POST /api/v2/listings/images`로 **한 장씩** 올려 받은 `key`를 아래 두 필드에 담는다.

      | 필드 | 개수 | 내용 |
      |---|---|---|
      | `imageKeys` | 1~5 | 지점 대표사진. 첫 값이 카드·상세의 대표 이미지가 된다 |
      | `roomOffers[].roomImageKeys` | 방마다 2~5 | 그 객실의 사진 |

      - `building.usedFloorRange`·`ageRange`는 **요청과 응답의 모양이 다르다.** 보낼 때는 `min~max` 문자열 한 칸이지만 응답은 `building.usedFloorMin`/`usedFloorMax`, `ageMin`/`ageMax`로 갈라져 돌아온다.
      - **주소와 좌표는 검색 응답 그대로 보낸다.** `address.fullAddress`는 정규화 없이 저장되고, `address.lat`·`lng`는 응답의 최상위 `location`으로 옮겨 간다. 건물명이 붙은 도로명 주소도 그대로 두면 된다.
      - **사진 URL은 보내지 않는다.** 키를 보내면 서버가 확정 위치로 옮겨 응답의 `imageUrls`·`roomOffers[].roomImageUrls`에 담아 준다. 순서는 보낸 순서를 유지한다.
      - **자기가 올린 키만 쓸 수 있다.** 남의 키·없는 키·올린 지 7일이 지난 키는 모두 `400 LISTING_IMAGE_KEY_NOT_FOUND`다.
      - **등록에 성공하면 업로드 때 받은 미리보기 URL은 무효가 된다.** 이후에는 등록 응답의 URL을 쓴다.
      - 사업자등록번호 진위는 이후 승인 심사에서 확인한다. `POST /api/v1/auth/business/verify`를 **미리 호출할 필요 없다.**
      - 코드 값은 서버가 가진 코드표에 있는 것만 받는다. 400 `LISTING_UNKNOWN_CATALOG_CODE`는 입력 오타가 아니라 앱의 코드표가 서버와 어긋났다는 뜻이므로, 입력 교정 대신 코드표 재조회(앱 갱신)를 안내한다.
      - 자유 입력 문구에는 길이 제한이 없다.

      **응답 주의사항**

      - 본문은 매물 상세(`GET /api/v2/listings/{listingId}`)와 같은 구조이고 `status`는 항상 `PENDING`이다. **등록 직후 매물은 목록·지도·검색·상세·찜 어디에도 나오지 않으며** 그 상세를 조회하면 404다. 공개 전환은 후속 관리자 승인이 한다.
      - `location`은 요청의 `address.lat`·`address.lng`를 옮긴 값이고, `nearbyUniversityCodes`는 **서버가 채운다**(요청 필드가 아니다). 인근 대학이 없으면 빈 배열이다.
      - `{code,label}`의 `label` 언어는 요청자 계정의 표시 언어를 따른다(임대인은 한국어).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 필수값 누락·빈값, `usedFloorRange`·`ageRange`의 `min~max` 형식 위반, 범위 위반(두 값의 최소가 최대보다 큼, 운영층 최대가 `building.totalFloors` 초과, `minStayMonths>maxStayMonths`, 음수 금액), `address.lat`·`address.lng` 누락 또는 WGS84 범위 밖, `roomOffers` 0개, **시설 8종 중 어느 하나가 `NONE`을 다른 코드와 함께 담음**. 위반 필드는 `error.errors[]`에 실린다 |
      | 400 | `LISTING_UNKNOWN_CATALOG_CODE` | 본문에 실린 코드 값이 서버 코드표에 없음 |
      | 400 | `LISTING_IMAGE_REQUIRED` | `imageKeys`가 1~5개가 아니거나, 어느 방의 `roomImageKeys`가 2~5개가 아님 |
      | 400 | `LISTING_IMAGE_KEY_NOT_FOUND` | 사진 키가 남의 것이거나, 존재하지 않거나, 7일이 지나 만료됨 |
      | 400 | `MALFORMED_REQUEST` | 본문 JSON 파싱 불가 또는 타입 불일치 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자의 등록 요청 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 502 | `UPSTREAM_ERROR` | 사진 저장소 복사 실패. 매물은 저장되지 않고 이미 복사한 사진은 서버가 지운다. 임시 사진은 그대로 남아 다시 제출할 수 있다 |
      """;

  public static final String[] LISTING_REGISTER_400 = {
    "INVALID_INPUT",
    "LISTING_UNKNOWN_CATALOG_CODE",
    "LISTING_IMAGE_REQUIRED",
    "LISTING_IMAGE_KEY_NOT_FOUND",
    "MALFORMED_REQUEST"
  };

  public static final String[] LISTING_REGISTER_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  public static final String[] LISTING_REGISTER_403 = {"FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"};

  // ── §11 도로명 주소 검색 — GET /api/v1/listings/addresses ──────────────────

  public static final String LISTING_ADDRESS_SEARCH_SUMMARY = "도로명 주소 검색(임대인)";

  public static final String LISTING_ADDRESS_SEARCH_DESCRIPTION =
      """
      매물 등록 폼의 주소 칸을 채울 표준 도로명 주소와 좌표를 찾는다. 등록 전에 **먼저 호출하는 API**다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **임대인**(`userType=LANDLORD`) 전용이다. 같은 `/api/v1/listings/*` 아래지만 공개 조회와 달리 인증이 필요하다.

      **요청 주의사항**

      - **고른 후보의 값을 등록 요청에 그대로 옮긴다** — 서버는 이 검색을 호출했는지 확인하지 않는다. 사용자가 고른 후보 **한 건**을 클라이언트가 보관했다가 **가공 없이 값 그대로** 실어 보낸다.

      | 검색 응답 | 등록 요청 |
      |---|---|
      | `roadAddress` | `address.fullAddress` |
      | `lat` | `address.lat` |
      | `lng` | `address.lng` |

      - **도로명 + 건물번호까지 넣어야 결과가 나온다.** `신촌`처럼 일부만 보내면 후보가 비고, `신촌로 12`면 나온다. 폼에서 그렇게 안내한다.
      - 서버가 외부 호출 조건(최대 5건·첫 페이지·한국어)을 고정하므로 프론트는 `keyword`만 보낸다.

      **응답 주의사항**

      - **모든 후보를 고를 수 있다.** 지원 지역을 미리 거르지 않으므로 어느 후보를 골라도 등록된다.
      - `roadAddress`에는 건물명이 붙어 올 수 있다. 서버가 다듬지 않으므로 **보이는 그대로** 등록에 실으면 된다.
      - 일치하는 주소가 없으면 `200`과 `data.items=[]`다(에러가 아니다). 도로명이 없는 결과는 서버가 제외한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 키워드 누락·공백·길이(1~100자) 위반 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 502 | `UPSTREAM_ERROR` | 외부 지오코딩 오류·타임아웃·인증정보 누락·응답 또는 좌표 형식 이상 |
      """;

  public static final String[] LISTING_ADDRESS_SEARCH_400 = {"INVALID_INPUT"};

  public static final String[] LISTING_ADDRESS_SEARCH_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  public static final String[] LISTING_ADDRESS_SEARCH_403 = {
    "FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"
  };

  public static final String[] LISTING_ADDRESS_SEARCH_502 = {"UPSTREAM_ERROR"};

  // ── §12 인근 역 검색 — GET /api/v1/listings/stations(+/nearby) ─────────────

  public static final String LISTING_STATION_SEARCH_SUMMARY = "인근 역 검색(임대인)";

  public static final String LISTING_STATION_SEARCH_DESCRIPTION =
      """
      매물 등록 폼의 인근 역 칸을 채울 표준 역 이름을 찾는다. 등록 전에 **먼저 호출하는 API**다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **임대인**(`userType=LANDLORD`) 전용이다. 같은 `/api/v1/listings/*` 아래지만 공개 조회와 달리 인증이 필요하다.

      **요청 주의사항**

      - **고른 후보의 값을 등록 요청에 그대로 옮긴다** — 서버는 이 검색을 호출했는지 확인하지 않는다. 사용자가 고른 후보 **한 건**을 클라이언트가 보관했다가 **가공 없이 값 그대로** 실어 보낸다.

      | 검색 응답 | 등록 요청 |
      |---|---|
      | `name` | `nearestTransit.name` |
      | `suggestedWalkMinutes` | `nearestTransit.walkMinutes` |

      - **좌표는 선택이지만 함께 보내는 것을 권한다** — `lat`·`lng`는 **둘 다 있거나 둘 다 없어야 한다.** 하나만 보내면 `400 INVALID_INPUT`이다. 좌표를 주면 거리순으로 정렬되고 `distanceMeters`·`suggestedWalkMinutes`가 채워진다. 없으면 정확도순이고 두 필드는 `null`이다. 주소를 먼저 검색하므로 좌표는 이미 손에 있다. 그래야 전국에 같은 이름이 있는 역(예: `시청역`)을 거리로 가려낼 수 있다.

      **응답 주의사항**

      - `suggestedWalkMinutes`는 **직선거리 기준 하한 제안**이다. 실제 보행 경로는 더 길다.
      - 환승역은 노선별로 여러 건이 온다(`신촌역 2호선`·`신촌역 경의중앙선`). 서버가 합치지 않는다.
      - 일치하는 역이 없으면 에러가 아니라 `data.items=[]`다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 키워드 누락·공백·길이(1~50자) 위반, 좌표를 하나만 보냄, 좌표가 WGS84 범위를 벗어남 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 502 | `UPSTREAM_ERROR` | 카카오 로컬 오류·타임아웃·REST 키 누락·응답 또는 좌표 형식 이상 |
      """;

  public static final String LISTING_NEARBY_STATION_SUMMARY = "인근 역 목록(임대인)";

  public static final String LISTING_NEARBY_STATION_DESCRIPTION =
      """
      매물 좌표 주변의 지하철역을 가까운 순으로 반환한다. 임대인이 아무것도 입력하지 않아도 후보를 보여주기 위한 경로이며, 응답 구조와 사용법은 인근 역 검색과 같다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — **임대인**(`userType=LANDLORD`) 전용이다.

      **요청 주의사항**

      - **서버가 고정하는 값** — 반경 **2km**(도보 25분권) · 거리순 정렬 · 최대 15건이다. 프론트는 매물 좌표만 보낸다.

      **응답 주의사항**

      - 좌표가 항상 있으므로 `distanceMeters`·`suggestedWalkMinutes`가 늘 채워진다.
      - 반경 안에 역이 없으면 에러가 아니라 `data.items=[]`다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `lat`·`lng` 누락 또는 WGS84 범위 위반 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 502 | `UPSTREAM_ERROR` | 카카오 로컬 오류·타임아웃·REST 키 누락·응답 또는 좌표 형식 이상 |
      """;

  public static final String[] LISTING_STATION_SEARCH_400 = {"INVALID_INPUT"};

  public static final String[] LISTING_STATION_SEARCH_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  public static final String[] LISTING_STATION_SEARCH_403 = {
    "FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"
  };

  public static final String[] LISTING_STATION_SEARCH_502 = {"UPSTREAM_ERROR"};

  // ── 공통 실패 응답 문구 ────────────────────────────────────────────────────

  public static String errorDescription() {
    return "실패 응답 — 공통 래퍼(success=false·data=null·error). 클라이언트는 error.code로 분기한다"
        + "(error-response-guide §1·§4).";
  }

  // ── 파라미터 기술자 ────────────────────────────────────────────────────────

  /** 주소 검색 API가 프론트에서 받는 유일한 검색 조건을 문서화한다. */
  public static ParameterDescriptor[] stationQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("keyword").description("등록 폼 역 칸 입력값(1~50자). 예: `신촌`"),
      parameterWithName("lat")
          .optional()
          .description("매물 위도(WGS84). 주소 검색이 준 값을 그대로 넘긴다. lng와 함께 보내야 한다"),
      parameterWithName("lng").optional().description("매물 경도(WGS84). lat와 함께 보내야 한다")
    };
  }

  /** 좌표만으로 인근 역을 받는 API의 query parameter 문서 정의다. */
  public static ParameterDescriptor[] nearbyStationQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("lat").description("매물 위도(WGS84, -90~90)"),
      parameterWithName("lng").description("매물 경도(WGS84, -180~180)")
    };
  }

  /** 주소 검색 API가 프론트에서 받는 유일한 검색 조건을 문서화한다. */
  public static ParameterDescriptor[] addressQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("keyword")
          .description("등록 폼 주소 칸 입력값(1~100자). 도로명 + 건물번호까지 넣어야 후보가 나온다. 예: `신촌로 12`")
    };
  }

  /** 네이버 장소 후보 API가 프론트에서 받는 유일한 검색 조건을 문서화한다. */
  public static ParameterDescriptor[] placeQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("keyword")
          .description("지도 검색창 입력값(1~50자). 서버가 trim한 뒤 네이버 지역 검색 API의 query로 전달")
    };
  }

  /** 목록 API의 query parameter 문서 정의다. */
  public static ParameterDescriptor[] listQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("swLat")
          .optional()
          .description("현재 지도 화면의 남서쪽 위도. 지도 영역 기준으로 목록을 갱신할 때 네 좌표를 모두 보낸다"),
      parameterWithName("swLng")
          .optional()
          .description("현재 지도 화면의 남서쪽 경도. swLat와 함께 지도 viewport의 왼쪽 아래 좌표"),
      parameterWithName("neLat").optional().description("현재 지도 화면의 북동쪽 위도. swLat보다 큰 값이어야 함"),
      parameterWithName("neLng").optional().description("현재 지도 화면의 북동쪽 경도. swLng보다 큰 값이어야 함"),
      parameterWithName("minBudget")
          .optional()
          .description("월세 최소값(KRW). 이 범위에 맞는 방 타입이 있는 매물만 보이고, 응답 roomOffers[]도 조건에 맞는 방 타입만 포함"),
      parameterWithName("maxBudget")
          .optional()
          .description(
              "월세 최대값(KRW). 예산 필터의 상한값이며 minBudget과 같은 방식으로 roomOffers[]를 좁힌다. 카드 가격은 응답 roomOffers[].pricing으로 계산"),
      parameterWithName("minDeposit")
          .optional()
          .description("보증금 최소값(KRW). 이 범위에 맞는 방 타입이 있는 매물만 보이고, 응답 roomOffers[]도 조건에 맞는 방 타입만 포함"),
      parameterWithName("maxDeposit")
          .optional()
          .description("보증금 최대값(KRW). 보증금 필터의 상한값. minDeposit과 같은 방식으로 roomOffers[]를 좁힌다"),
      parameterWithName("type")
          .optional()
          .description("매물 유형 필터 칩 — " + codeList(ListingType.class) + " 중 하나"),
      parameterWithName("conditions")
          .optional()
          .description(
              "옵션 필터 칩 코드 — "
                  + codeList(ConditionTag.class)
                  + ". 반복 파라미터나 콤마로 보낼 수 있음. 보낸 조건을 모두 가진 방 타입이 있는 매물만 남고, 응답 roomOffers[]도 그 방 타입만 포함"),
      parameterWithName("sort")
          .optional()
          .description(
              "정렬 방식. RECOMMENDED는 기본 추천순, PRICE_ASC는 조건에 맞는 방 타입 중 가장 낮은 월세순, DISTANCE는 현재 지도 중심에서 가까운 순. "
                  + "DISTANCE를 쓰려면 지도 좌표 네 값을 함께 보내야 함"),
      parameterWithName("page").optional().description("0부터 시작하는 페이지 번호. 무한스크롤이면 다음 페이지 요청에 사용"),
      parameterWithName("size").optional().description("한 번에 가져올 매물 수. 기본 20, 최대 100")
    };
  }

  /** 지도 마커 API의 query parameter 문서 정의다. */
  public static ParameterDescriptor[] mapQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("swLat").description("현재 지도 화면의 남서쪽 위도. 지도 마커 조회는 bbox 네 값이 모두 필수"),
      parameterWithName("swLng").description("현재 지도 화면의 남서쪽 경도"),
      parameterWithName("neLat").description("현재 지도 화면의 북동쪽 위도. swLat보다 큰 값"),
      parameterWithName("neLng").description("현재 지도 화면의 북동쪽 경도. swLng보다 큰 값"),
      parameterWithName("minBudget")
          .optional()
          .description("월세 최소값(KRW). 목록 필터와 같은 값으로 마커도 같이 갱신할 때 사용"),
      parameterWithName("maxBudget")
          .optional()
          .description("월세 최대값(KRW). 조건에 맞는 방 타입이 있는 매물의 마커만 반환"),
      parameterWithName("minDeposit").optional().description("보증금 최소값(KRW)"),
      parameterWithName("maxDeposit").optional().description("보증금 최대값(KRW)"),
      parameterWithName("type")
          .optional()
          .description("매물 유형 필터 칩 — " + codeList(ListingType.class) + " 중 하나"),
      parameterWithName("conditions")
          .optional()
          .description(
              "옵션 필터 칩 코드 — "
                  + codeList(ConditionTag.class)
                  + ". 목록 API와 같은 필터를 보내면 지도 마커와 바텀시트 목록을 같은 조건으로 맞출 수 있음")
    };
  }

  /** 찜 등록/해제 API의 path parameter 문서 정의다. */
  public static ParameterDescriptor[] favoritePathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId")
          .description("하트를 누른 매물의 listingId. 목록/상세 응답의 listingId를 그대로 path에 넣으면 됨")
    };
  }

  /** 내 찜 목록 API의 query parameter 문서 정의다. */
  public static ParameterDescriptor[] favoritesQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0부터 시작하는 페이지 번호"),
      parameterWithName("size").optional().description("한 번에 가져올 찜 매물 수. 기본 20, 최대 100")
    };
  }

  // ── 요청 필드 기술자 ───────────────────────────────────────────────────────

  /**
   * 매물 등록 요청 본문 문서 정의다.
   *
   * <p>코드 값 배열은 전부 {@code enumArrayField}로 싣는다 — 배열에 스칼라 {@code codeField}를 쓰면 타입 검증이 건너뛰어 테스트는
   * 통과하고 문서만 문자열로 틀린다(ADR-0017).
   */
  public static List<FieldDescriptor> registerRequestFields() {
    return writeRequestFields(ListingWriteVariant.REGISTER);
  }

  /**
   * 매물 수정 요청 본문 문서 정의다.
   *
   * <p>수정은 <b>전체 교체</b>라 등록과 같은 본문을 다시 받는다. 그래서 기술자를 등록과 공유하고 갈래로만 가른다 — {@code roomOffers[]}에
   * {@code roomOfferId}·{@code status} 두 칸이 더 있고, 사진 키에 기존 확정 키를 섞을 수 있다.
   *
   * <p>{@link #registerRequestFields()}를 그대로 재사용하면 그 두 칸이 문서화되지 않은 채 남아 스니펫 생성이 <b>런타임에</b> 실패한다.
   */
  public static List<FieldDescriptor> updateRequestFields() {
    return writeRequestFields(ListingWriteVariant.UPDATE);
  }

  /** 등록·수정이 공유하는 요청 본문 기술자다. 갈래마다 다른 문구와 필드만 {@code variant}로 가른다. */
  private static List<FieldDescriptor> writeRequestFields(ListingWriteVariant variant) {
    boolean update = variant == ListingWriteVariant.UPDATE;
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("title", JsonFieldType.STRING, "지점명"));
    fields.add(enumField("type", ListingType.class, "공간 유형"));
    fields.add(field("contact", JsonFieldType.OBJECT, "세입자에게 공개할 매물 담당 연락처"));
    fields.add(field("contact.managerName", JsonFieldType.STRING, "지점 운영자명. 세입자에게 그대로 공개된다"));
    fields.add(
        field("contact.phone", JsonFieldType.STRING, "지점 대표 전화(문의 수신). 예: `+82) 10-1234-5678`"));
    fields.add(
        field(
            "businessRegistrationNumber",
            JsonFieldType.STRING,
            update
                ? "사업자등록번호 숫자 10자리. 형식만 확인하고 저장하며, 임대인 상세 응답에 그대로 실려 다시 제출할 수 있다"
                : "사업자등록번호 숫자 10자리. 형식만 확인하고 저장하며 응답에는 나오지 않는다"));
    fields.add(optField("blogUrl", JsonFieldType.STRING, "지점 블로그·홈페이지 주소. 없으면 생략"));
    fields.add(field("address", JsonFieldType.OBJECT, "매물 주소"));
    fields.add(
        field(
            "address.fullAddress",
            JsonFieldType.STRING,
            "도로명 주소 한 줄. 주소 검색 응답의 roadAddress를 그대로 보낸다. 서버가 여기서 시·도와 구·군을 뽑는다"));
    fields.add(optField("address.detail", JsonFieldType.STRING, "동·호수 등 상세 주소. 없으면 생략"));
    fields.add(
        field(
            "address.lat",
            JsonFieldType.NUMBER,
            "위도. 주소 검색 응답의 lat을 그대로 보낸다. 매물의 location이 되며 WGS84 범위(-90~90)를 벗어나면 400"));
    fields.add(
        field(
            "address.lng",
            JsonFieldType.NUMBER,
            "경도. 주소 검색 응답의 lng을 그대로 보낸다. WGS84 범위(-180~180)를 벗어나면 400"));
    fields.add(field("building", JsonFieldType.OBJECT, "건물 정보"));
    fields.add(enumField("building.type", Listing.BuildingType.class, "건물 형태"));
    fields.add(field("building.totalFloors", JsonFieldType.NUMBER, "건물 총 층수. 1 이상"));
    fields.add(
        field(
            "building.usedFloorRange",
            JsonFieldType.STRING,
            "지점이 사용하는 층을 `min~max` 한 칸으로. 예: `1~2`. 최대 층은 totalFloors를 넘을 수 없다"));
    fields.add(field("building.parkingAvailable", JsonFieldType.BOOLEAN, "주차공간 유무"));
    fields.add(field("building.elevatorAvailable", JsonFieldType.BOOLEAN, "엘리베이터 유무"));
    fields.add(enumField("genderPolicy", Listing.GenderPolicy.class, "이용 성별구분"));
    fields.add(
        enumArrayField("languagesSupported", SupportedLanguage.class, "응대 가능한 외국어. 1개 이상 선택"));
    fields.add(field("ageRange", JsonFieldType.STRING, "이용 연령대를 `min~max` 한 칸으로. 예: `20~35`"));
    fields.add(enumField("arcRequired", ArcRequirement.class, "입주에 외국인등록증(ARC)이 필요한지 여부"));
    fields.add(field("facilities", JsonFieldType.OBJECT, "공용 시설·비품"));
    fields.add(
        enumArrayField(
            "facilities.heatingSystem",
            Listing.HeatingSystem.class,
            "난방시설. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다)"));
    fields.add(
        enumArrayField(
            "facilities.kitchen",
            KitchenFacility.class,
            "주방시설. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다)"));
    fields.add(
        enumArrayField(
            "facilities.laundry",
            LaundryFacility.class,
            "세탁시설. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다)"));
    fields.add(
        enumArrayField(
            "facilities.livingAmenities",
            LivingAmenity.class,
            "생활시설. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다)"));
    fields.add(
        enumArrayField(
            "facilities.securityFeatures",
            SecurityFeature.class,
            "안전시설. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다)"));
    fields.add(
        enumArrayField(
            "facilities.commonSpaces",
            Listing.CommonSpaceType.class,
            "공용공간. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다). 수량 없이 종류만 보낸다"));
    fields.add(
        enumArrayField(
            "facilities.providedSupplies",
            ProvidedSupply.class,
            "제공비품. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다)"));
    fields.add(
        enumArrayField(
            "nearbyFacilities",
            NearbyFacility.class,
            "주변 편의시설. 1개 이상. 없으면 NONE 하나만 보낸다(다른 코드와 같이 보낼 수 없다)"));
    fields.add(field("nearestTransit", JsonFieldType.OBJECT, "가장 가까운 대중교통"));
    fields.add(enumField("nearestTransit.type", Listing.TransitType.class, "가까운 교통수단"));
    fields.add(
        field(
            "nearestTransit.name",
            JsonFieldType.STRING,
            "근처 지하철역명. GET /api/v1/listings/stations 응답의 name을 그대로 보낸다"));
    fields.add(
        field(
            "nearestTransit.walkMinutes",
            JsonFieldType.NUMBER,
            "역까지 도보 소요시간(분). 0 이상. 역 검색이 준 suggestedWalkMinutes를 그대로 담으면 된다."
                + " 키를 생략하면 400 INVALID_INPUT이다"));
    fields.add(field("description", JsonFieldType.STRING, "지점 소개글"));
    fields.add(field("extraNotes", JsonFieldType.STRING, "생활 규칙과 유의사항"));
    fields.add(field("refundPolicy", JsonFieldType.STRING, "환불정책 문구"));
    fields.add(
        field(
            "imageKeys",
            JsonFieldType.ARRAY,
            update
                ? "지점 대표사진의 저장 키 1~5개. 새로 올린 임시 키(uploads/…)와 그대로 둘 기존 확정 키(listings/…)를"
                    + " 섞어 보낼 수 있다. 첫 값이 대표 이미지이며 장수는 둘을 합친 최종 배열 기준이다"
                : "지점 대표사진의 저장 키 1~5개. `POST /api/v2/listings/images` 응답의 key를 그대로 담는다. 첫 값이 대표 이미지"));
    fields.add(
        field(
            "roomOffers",
            JsonFieldType.ARRAY,
            update ? "객실 타입 목록. 1개 이상이며 저장 결과에 ACTIVE인 방이 최소 1개 있어야 한다" : "객실 타입 목록. 1개 이상"));
    if (update) {
      fields.add(
          optField(
              "roomOffers[].roomOfferId",
              JsonFieldType.STRING,
              "고칠 방의 식별자. 임대인 상세 응답 rooms[].roomOfferId를 그대로 담는다."
                  + " 새로 추가하는 방은 null이거나 키를 생략하며, 그 매물의 방이 아닌 값은 400 INVALID_INPUT"));
      fields.add(
          enumField(
              "roomOffers[].status",
              Listing.RoomOfferStatus.class,
              "방 상태. 방을 내리는 것은 요청에서 빼는 것이 아니라 INACTIVE로 보내는 것이다 — 빼면 응답에 보이지 않아 되살릴 수 없다"));
    }
    fields.add(field("roomOffers[].name", JsonFieldType.STRING, "객실 타입명"));
    fields.add(field("roomOffers[].contract", JsonFieldType.OBJECT, "방 타입별 이용 기간"));
    fields.add(
        field("roomOffers[].contract.minStayMonths", JsonFieldType.NUMBER, "최소 이용 개월. 1 이상"));
    fields.add(
        field(
            "roomOffers[].contract.maxStayMonths",
            JsonFieldType.NUMBER,
            "최대 이용 개월. minStayMonths 이상"));
    fields.add(field("roomOffers[].pricing", JsonFieldType.OBJECT, "방 타입별 비용"));
    fields.add(
        field("roomOffers[].pricing.monthlyRent", JsonFieldType.NUMBER, "월 기준 객실 비용(KRW). 0 이상"));
    fields.add(field("roomOffers[].pricing.deposit", JsonFieldType.NUMBER, "보증금(KRW). 0 이상"));
    fields.add(
        field("roomOffers[].pricing.maintenanceFee", JsonFieldType.NUMBER, "관리비(KRW). 0 이상"));
    fields.add(
        enumArrayField(
            "roomOffers[].filterTags",
            ConditionTag.class,
            "해당 객실 타입의 옵션. 1개 이상이며, 응답 상위 conditions는 이 값들의 합집합이다"));
    fields.add(
        field(
            "roomOffers[].roomImageKeys",
            JsonFieldType.ARRAY,
            update
                ? "그 객실 사진의 저장 키 2~5개. 임시 키(uploads/…)와 그 방의 기존 확정 키(listings/…)를 섞을 수 있다."
                    + " 신규 방(roomOfferId가 null)은 아직 확정 키가 없어 임시 키만 가능하다"
                : "그 객실 사진의 저장 키 2~5개"));
    fields.add(
        optEnumArrayField(
            "preferredNationalities",
            Nationality.class,
            update
                ? "설문 — 선호하는 입주자 국적(선택). 보내지 않으면 저장된 값이 빈 배열로 지워진다"
                : "설문 — 선호하는 입주자 국적(선택). 보내지 않으면 빈 배열로 저장되며 세입자 응답에는 나오지 않는다"));
    fields.add(
        optEnumArrayField(
            "contractDifficulties",
            ContractDifficulty.class,
            update
                ? "설문 — 계약 과정에서 겪은 어려움(선택). 보내지 않으면 저장된 값이 빈 배열로 지워진다"
                : "설문 — 계약 과정에서 겪은 어려움(선택). 보내지 않으면 빈 배열로 저장되며 세입자 응답에는 나오지 않는다"));
    fields.add(
        optField(
            "serviceFeedback",
            JsonFieldType.STRING,
            update
                ? "설문 — 서비스에 전하고 싶은 말. 임대인 상세 응답에 그대로 실린다"
                : "설문 — 서비스에 전하고 싶은 말. 응답에는 나오지 않는다"));
    fields.add(
        field(
            "consents",
            JsonFieldType.OBJECT,
            update
                ? "매물 이용약관 동의 2종. 수정할 때도 새로 받으며 객체가 없으면 400."
                    + " 저장된 version·agreedAt은 최초 등록 때의 값을 승계하고 이번 요청으로 덮지 않는다"
                : "매물 이용약관 동의 2종. 객체가 없으면 400"));
    fields.add(
        field(
            "consents.privacyPolicyAgreed",
            JsonFieldType.BOOLEAN,
            "개인정보 수집·이용 동의. true가 아니면 422 LISTING_REQUIRED_AGREEMENT_MISSING"));
    fields.add(
        field(
            "consents.listingExposureAgreed",
            JsonFieldType.BOOLEAN,
            "매물 정보 제공 및 노출 동의. true가 아니면 422 LISTING_REQUIRED_AGREEMENT_MISSING"));
    return List.copyOf(fields);
  }

  // ── 응답 필드 기술자 ───────────────────────────────────────────────────────

  /** 주소 검색 API 응답 필드 문서 정의다. 등록에 그대로 실리는 값과 보조 표시값을 설명에서 갈라 준다. */
  public static List<FieldDescriptor> stationSearchResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.items[].name",
            JsonFieldType.STRING,
            "역 이름. 등록 요청의 nearestTransit.name에 그대로 담는다. 환승역은 노선별로 따로 오며 서버는 다듬지 않는다"),
        field(
            "data.items[].roadAddress",
            JsonFieldType.STRING,
            "역 출입구 도로명 주소. 후보를 구분해 보여줄 때만 쓴다. 제공되지 않으면 빈 문자열"),
        field("data.items[].jibunAddress", JsonFieldType.STRING, "지번 주소. 보조 표시용. 제공되지 않으면 빈 문자열"),
        field("data.items[].lat", JsonFieldType.NUMBER, "역의 WGS84 위도. 지도 핀 용도이며 등록에는 보내지 않는다"),
        field("data.items[].lng", JsonFieldType.NUMBER, "역의 WGS84 경도"),
        optField(
            "data.items[].distanceMeters",
            JsonFieldType.NUMBER,
            "매물 좌표에서 역까지의 직선거리(m). 좌표를 주지 않은 요청이면 null"),
        optField(
            "data.items[].suggestedWalkMinutes",
            JsonFieldType.NUMBER,
            "도보 시간 제안값(분). 직선거리 ÷ 80m를 올림한 하한이다. 거리를 모르면 null"),
        errorNull());
  }

  /** 주소 검색 성공 응답 필드 문서 정의다. */
  public static List<FieldDescriptor> addressSearchResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.items[].roadAddress",
            JsonFieldType.STRING,
            "표준 도로명 주소. 등록 요청의 address.fullAddress에 그대로 담는다. 건물명이 붙어 있을 수 있고 서버는 다듬지 않는다"),
        field(
            "data.items[].jibunAddress",
            JsonFieldType.STRING,
            "지번 주소. 후보를 구분해 보여줄 때만 쓰고 등록에는 보내지 않는다. 제공되지 않으면 빈 문자열"),
        field(
            "data.items[].englishAddress",
            JsonFieldType.STRING,
            "영문 표기. 보조 표시용이며 등록에는 보내지 않는다. 제공되지 않으면 빈 문자열"),
        field("data.items[].lat", JsonFieldType.NUMBER, "WGS84 위도. 등록 요청의 address.lat에 그대로 담는다"),
        field("data.items[].lng", JsonFieldType.NUMBER, "WGS84 경도. 등록 요청의 address.lng에 그대로 담는다"),
        errorNull());
  }

  /** 네이버 원본 메타데이터를 제외하고 프론트에 공개하는 장소 후보 필드만 문서화한다. */
  public static List<FieldDescriptor> placeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.items[].title", JsonFieldType.STRING, "검색어 강조 <b> 태그를 포함할 수 있는 네이버 장소명"),
        field("data.items[].address", JsonFieldType.STRING, "지번 주소. 네이버가 제공하지 않으면 빈 문자열"),
        field("data.items[].roadAddress", JsonFieldType.STRING, "도로명 주소. 네이버가 제공하지 않으면 빈 문자열"),
        field("data.items[].lat", JsonFieldType.NUMBER, "선택 시 지도 중심 이동에 사용할 WGS84 위도"),
        field("data.items[].lng", JsonFieldType.NUMBER, "선택 시 지도 중심 이동에 사용할 WGS84 경도"),
        errorNull());
  }

  /** 지도 마커 API 응답 필드 문서 정의다. */
  public static List<FieldDescriptor> mapResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.markers[].listingId", JsonFieldType.STRING, "마커 선택 시 목록 항목/상세 화면과 연결할 매물 ID"),
        field("data.markers[].lat", JsonFieldType.NUMBER, "지도 SDK에 넘길 마커 위도"),
        field("data.markers[].lng", JsonFieldType.NUMBER, "지도 SDK에 넘길 마커 경도"),
        field("data.total", JsonFieldType.NUMBER, "현재 지도 영역과 필터 조건에 맞는 마커 수. 클러스터/빈 상태 판단에 사용"),
        errorNull());
  }

  /** 찜 등록/해제 API 응답 필드 문서 정의다. */
  public static List<FieldDescriptor> favoriteToggleResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.favorited", JsonFieldType.BOOLEAN, "요청 처리 후 하트 상태. true면 채운 하트, false면 빈 하트로 갱신"),
        field("data.favoriteCount", JsonFieldType.NUMBER, "요청 처리 후 화면에 표시할 최신 찜 수"),
        errorNull());
  }

  /** 내 찜 목록 API 응답 필드 문서 정의다. */
  public static List<FieldDescriptor> favoritesResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data.content[]", null));
    fields.add(field("data.content[].favoritedAt", JsonFieldType.STRING, "찜 목록 정렬/보조 문구에 쓸 찜한 시각"));
    fields.addAll(pageFields("공개 상태라 실제 응답 가능한 내 찜 매물 수"));
    fields.add(errorNull());
    return fields;
  }

  /** 최근 본 매물 목록 API 응답 필드 문서 정의다. */
  public static List<FieldDescriptor> recentListingsResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data.content[]", null));
    fields.add(
        field("data.content[].viewedAt", JsonFieldType.STRING, "최근 본 목록 정렬/보조 문구에 쓸 마지막 상세 조회 시각"));
    fields.add(errorNull());
    return fields;
  }

  /** 목록 API 응답 필드 문서 정의다. */
  public static List<FieldDescriptor> listResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(
        listingDocumentFields("data.content[]", "지도 중심에서 매물까지의 직선거리(미터). 카드 거리 라벨에 사용하고 없으면 숨김"));
    fields.addAll(pageFields("필터와 지도 범위에 맞는 전체 매물 수"));
    fields.add(errorNull());
    return fields;
  }

  /**
   * 매물 등록 201 응답 필드 문서 정의다.
   *
   * <p>상세 조회와 같은 매물 문서지만 상태가 {@code PENDING}이라 그 한 필드만 다르다.
   */
  public static List<FieldDescriptor> registerResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data", null, ListingDocumentVariant.REGISTERED));
    fields.add(errorNull());
    return fields;
  }

  /** 상세 API 응답 필드 문서 정의다. */
  public static List<FieldDescriptor> detailResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data", null));
    fields.add(errorNull());
    return fields;
  }

  // ── 관리자 매물 심사 — /api/v1/admin/listings ─────────────────────────────

  public static final String ADMIN_LISTING_LIST_SUMMARY = "매물 심사 목록 조회(관리자)";

  public static final String ADMIN_LISTING_LIST_DESCRIPTION =
      """
      심사 화면에 보여줄 매물을 조회한다. 세입자용 목록과 달리 **`status`와 무관하게 모두** 나온다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **관리자**(`userType=ADMIN`) 전용이다.

      **요청 주의사항**

      - `status`를 **보내지 않으면 전체**다. 콤마로 여러 개 보낼 수 있다(`?status=PENDING,REJECTED`).
      - `sort`는 `createdAt,asc`만 인식하고, 그 밖의 값이나 생략은 **등록 최신순**이다.

      **응답 주의사항**

      - **설문 3종의 부재 표현이 다르다** — `preferredNationalities`·`contractDifficulties`는 답하지 않았어도 **빈 배열**로 실리고, `serviceFeedback`은 값이 `null`이 아니라 **필드 자체가 생략된다**.
      - 각 항목의 `listing`은 매물 상세(`GET /api/v2/listings/{listingId}`)와 같은 구조다. 그 바깥에 **세입자 응답에는 없는 값**이 함께 실린다 — `landlordId`·`businessRegistrationNumber`·`preferredNationalities`·`contractDifficulties`·`serviceFeedback`·`consents`·`rejectionReason`.
      - `serviceFeedback`·`rejectionReason`·`consents`는 값이 null이 아니라 **필드 자체가 생략**된다.
      - `listing.favorited`는 **항상 false**다. 관리자에게는 찜 개념이 없다.
      - `{code,label}`의 `label`은 **항상 한국어**다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `status`에 정의되지 않은 값, `size`가 1~100 밖 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조 |
      | 401 | `TOKEN_EXPIRED` | 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 상태가 `PENDING`·`TERMS_AGREED`인 회원 |
      | 403 | `FORBIDDEN` | 관리자가 아닌 회원 |
      """;

  public static final String ADMIN_LISTING_DETAIL_SUMMARY = "매물 심사 상세 조회(관리자)";

  public static final String ADMIN_LISTING_DETAIL_DESCRIPTION =
      """
      심사할 매물 한 건을 조회한다. 세입자용 상세와 달리 **저장된 값을 감추지 않고**, `status`와 무관하게 조회된다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **관리자**(`userType=ADMIN`) 전용이다.

      **응답 주의사항**

      - **설문 3종의 부재 표현이 다르다** — `preferredNationalities`·`contractDifficulties`는 답하지 않았어도 **빈 배열**로 실리고, `serviceFeedback`은 값이 `null`이 아니라 **필드 자체가 생략된다**.
      - `listing`은 매물 상세(`GET /api/v2/listings/{listingId}`)와 같은 구조다. 그 바깥에 **세입자 응답에는 없는 값**이 함께 실린다 — `landlordId`·`businessRegistrationNumber`·`preferredNationalities`·`contractDifficulties`·`serviceFeedback`·`consents`·`rejectionReason`.
      - `businessRegistrationNumber`는 **마스킹 없이 원문**이다. 심사에서 진위를 직접 확인하는 값이다.
      - `serviceFeedback`·`rejectionReason`·`consents`는 값이 null이 아니라 **필드 자체가 생략**된다. `rejectionReason`은 `status`가 `REJECTED`일 때만 실린다.
      - `listing.favorited`는 **항상 false**다.
      - `{code,label}`의 `label`은 **항상 한국어**다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조 |
      | 401 | `TOKEN_EXPIRED` | 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 상태가 `PENDING`·`TERMS_AGREED`인 회원 |
      | 403 | `FORBIDDEN` | 관리자가 아닌 회원 |
      | 404 | `LISTING_NOT_FOUND` | 없는 `listingId`이거나 24자리 hex 형식이 아님 |
      """;

  public static final String ADMIN_LISTING_APPROVAL_SUMMARY = "매물 승인(관리자)";

  public static final String ADMIN_LISTING_APPROVAL_DESCRIPTION =
      """
      매물을 `PUBLISHED`로 바꿔 세입자에게 공개한다. **요청 본문이 없다.**

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **관리자**(`userType=ADMIN`) 전용이다.

      **요청 주의사항**

      - **`status`를 가리지 않는다.** `PENDING`은 물론 `REJECTED`인 매물도 승인할 수 있다.
      - **이미 `PUBLISHED`이면 아무것도 바뀌지 않는다.** `updatedAt`도 그대로라 목록 정렬이 흔들리지 않는다. 반복 호출해도 안전하다.

      **응답 주의사항**

      - 응답 구조는 심사 상세(`GET /api/v1/admin/listings/{listingId}`)와 같다.
      - `status`가 `PUBLISHED`가 되고 `rejectionReason`은 **지워진다**(필드 자체가 생략된다).
      - 승인 직후부터 그 매물이 세입자의 목록·지도·상세 조회에 나타난다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조 |
      | 401 | `TOKEN_EXPIRED` | 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 상태가 `PENDING`·`TERMS_AGREED`인 회원 |
      | 403 | `FORBIDDEN` | 관리자가 아닌 회원 |
      | 404 | `LISTING_NOT_FOUND` | 없는 `listingId`이거나 24자리 hex 형식이 아님 |
      """;

  public static final String ADMIN_LISTING_REJECTION_SUMMARY = "매물 반려(관리자)";

  public static final String ADMIN_LISTING_REJECTION_DESCRIPTION =
      """
      매물을 `REJECTED`로 바꾸고 사유를 남긴다. 반려된 매물은 세입자 조회에서 사라진다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **관리자**(`userType=ADMIN`) 전용이다.

      **요청 주의사항**

      - **`status`를 가리지 않는다.** 심사 대기 매물은 물론 이미 공개된 매물도 이 요청으로 내릴 수 있다.
      - **이미 `REJECTED`인 매물에도 보낼 수 있다.** 그때는 `reason`이 새 값으로 덮인다.
      - `reason`은 임대인이 읽는 값이라 **번역하지 않는다.** 보낸 문자열이 그대로 저장된다.

      **응답 주의사항**

      - 응답 구조는 심사 상세(`GET /api/v1/admin/listings/{listingId}`)와 같다.
      - `status`가 `REJECTED`가 되고 `rejectionReason`에 보낸 사유가 실린다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `reason` 누락·공백만·500자 초과 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조 |
      | 401 | `TOKEN_EXPIRED` | 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 상태가 `PENDING`·`TERMS_AGREED`인 회원 |
      | 403 | `FORBIDDEN` | 관리자가 아닌 회원 |
      | 404 | `LISTING_NOT_FOUND` | 없는 `listingId`이거나 24자리 hex 형식이 아님 |
      """;

  public static final String[] ADMIN_LISTING_400 = {"INVALID_INPUT"};
  public static final String[] ADMIN_LISTING_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] ADMIN_LISTING_403 = {"FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"};
  public static final String[] ADMIN_LISTING_404 = {"LISTING_NOT_FOUND"};

  /** 반려 요청 본문 필드다. */
  public static List<FieldDescriptor> rejectionRequestFields() {
    return List.of(field("reason", JsonFieldType.STRING, "반려 사유. 공백 불가, 1~500자. 번역하지 않는다"));
  }

  /** 심사 상세 200 응답 필드다. 세입자 상세를 그대로 싣고 감춰진 값을 나란히 더한다. */
  public static List<FieldDescriptor> adminListingResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(adminListingFields("data"));
    fields.add(errorNull());
    return fields;
  }

  /** 심사 목록 200 응답 필드다. */
  public static List<FieldDescriptor> adminListingPageResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(adminListingFields("data.content[]"));
    fields.addAll(pageFields("심사 대상 매물 총 개수"));
    fields.add(errorNull());
    return fields;
  }

  /**
   * 심사 응답 한 건의 필드다. 세입자 상세는 {@code listing} 아래에 그대로 실린다.
   *
   * <p>{@code listing.status}는 {@link ListingDocumentVariant#MANAGED}다 — 심사 목록·상세는 상태를 가리지 않으므로
   * 승인·반려를 거친 매물까지 네 값이 모두 나온다.
   */
  private static List<FieldDescriptor> adminListingFields(String prefix) {
    List<FieldDescriptor> fields =
        new ArrayList<>(
            listingDocumentFields(prefix + ".listing", null, ListingDocumentVariant.MANAGED));
    fields.add(field(prefix + ".landlordId", JsonFieldType.NUMBER, "매물 소유 임대인 계정 id. 세입자 응답에는 없다"));
    fields.add(
        field(
            prefix + ".businessRegistrationNumber",
            JsonFieldType.STRING,
            "사업자등록번호 원문. 심사에서 진위를 수동 확인하는 값이라 관리자에게만 내려간다"));
    fields.add(
        enumArrayField(
            prefix + ".preferredNationalities",
            Nationality.class,
            "등록 폼 설문 — 선호 국적. 답하지 않았으면 빈 배열이다"));
    fields.add(
        enumArrayField(
            prefix + ".contractDifficulties",
            ContractDifficulty.class,
            "등록 폼 설문 — 계약 시 어려움. 답하지 않았으면 빈 배열이다"));
    fields.add(
        optField(
            prefix + ".serviceFeedback", JsonFieldType.STRING, "등록 폼 설문 — 서비스 의견. 없으면 키가 빠진다"));
    fields.add(field(prefix + ".consents", JsonFieldType.OBJECT, "등록 시 받은 이용약관 동의"));
    fields.add(
        field(prefix + ".consents.privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보 수집·이용 동의"));
    fields.add(
        field(
            prefix + ".consents.listingExposureAgreed", JsonFieldType.BOOLEAN, "매물 정보 제공 및 노출 동의"));
    fields.add(
        field(prefix + ".consents.version", JsonFieldType.STRING, "동의한 약관 버전. 회원 약관 버전과 별개 값이다"));
    fields.add(field(prefix + ".consents.agreedAt", JsonFieldType.STRING, "동의 시각(UTC ISO-8601)"));
    fields.add(
        optField(prefix + ".rejectionReason", JsonFieldType.STRING, "반려 사유. 반려 상태가 아니면 키가 빠진다"));
    return fields;
  }

  // ── 임대인 매물 관리 — /api/v2/users/me/listings · PUT /api/v2/listings/{id} ─

  public static final String LANDLORD_LISTING_LIST_SUMMARY = "내 매물 목록 조회(임대인)";

  public static final String LANDLORD_LISTING_LIST_DESCRIPTION =
      """
      임대인 웹 「내 매물」 화면의 목록이다. **상태를 가리지 않고** 자기 매물만 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **임대인**(`userType=LANDLORD`) 전용이다.

      **요청 주의사항**

      - `status`를 **보내지 않으면 전체**다. 콤마로 여러 개 보낼 수 있다(`?status=REJECTED,UPDATE_PENDING`). 관리자 심사 목록과 같은 계약이다.
      - **정렬 파라미터가 없다.** 최근 수정순(`updatedAt` 내림차순) 고정이라 방금 고친 매물이 맨 위에 온다. 나중에 정렬을 여는 것은 하위 호환을 깨지 않는다.
      - 이 경로가 `/api/v2/listings/mine`이 아닌 이유는 그쪽이 공개 조회 매처(`GET /api/v2/listings/*` `permitAll`)에 먼저 잡혀 **비로그인에 열리기** 때문이다.

      **응답 주의사항**

      - 각 항목의 `listing`은 세입자 목록 카드(`GET /api/v2/listings`)와 같은 구조다. 그 바깥에 `rejectionReason` **한 필드만** 더 붙는다.
      - `rejectionReason`은 반려된 매물에만 있고, 그 외에는 값이 null이 아니라 **필드 자체가 생략**된다.
      - `listing.status`는 `PENDING`·`PUBLISHED`·`REJECTED`·`UPDATE_PENDING` **넷 다** 나올 수 있다. 카드의 상태 배지에 그대로 쓴다.
      - `listing.favoriteCount`는 심사 때문에 세입자 목록에서 빠져 있는 동안에도 **줄어들지 않는다**.
      - `listing.roomOffers[]`와 `listing.conditions`는 세입자 목록과 같은 기준이라 **`ACTIVE` 방만** 반영한다. 내린 방까지 보려면 상세를 쓴다.
      - 수정 폼이 쓰는 값(`businessRegistrationNumber`·설문 3종·`consents`·사진 키)은 카드에 없다. 아래 상세가 준다.
      - `{code,label}`의 `label`은 임대인 계정의 표시 언어를 따르고, `status`는 관리 상태라 번역 없이 코드 문자열 그대로다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 정의되지 않은 `status`, `page` 음수, `size`가 1~100 밖 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조 |
      | 401 | `TOKEN_EXPIRED` | 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
      """;

  public static final String LANDLORD_LISTING_DETAIL_SUMMARY = "내 매물 상세 조회(임대인)";

  public static final String LANDLORD_LISTING_DETAIL_DESCRIPTION =
      """
      수정 화면이 폼을 채우는 데 쓰는 상세다. 계약이 **「수정 요청에 실을 수 있는 전 필드 + 읽기 전용 표시값」** 이라 편집 대상은 하나도 빠지지 않는다. 상태와 무관하게 조회된다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **임대인**(`userType=LANDLORD`) 전용이다. 자기 매물이 아니면 `404 LISTING_NOT_FOUND`다.

      **응답 주의사항**

      - `listing`은 세입자 상세(`GET /api/v2/listings/{listingId}`)와 같은 구조이며 **표시·미리보기용**이다. 다시 제출할 값은 그 바깥에 있다.
      - 세입자에게 감추는 값이 함께 실린다 — `businessRegistrationNumber`·설문 3종(`preferredNationalities`·`contractDifficulties`·`serviceFeedback`). **전부 수정 요청 필드**다. 설문 3종은 선택이지만 **보내지 않으면 빈 값으로 덮이므로**, 유지하려면 이 응답이 준 값을 그대로 다시 실어야 한다.
      - 사진은 URL과 **키**를 함께 준다. 그대로 둘 사진은 `imageKeys`·`rooms[].roomImageKeys`의 확정 키를 수정 요청에 되돌려 보낸다. **URL에서 키를 역산하지 않는다** — 주소 형식에 묶이면 CDN을 바꿀 때 같이 깨진다.
      - `rooms[]`는 **`INACTIVE` 방까지** 담는다(`listing.roomOffers[]`는 `ACTIVE`만 담는다). 내렸던 방을 되살리려면 이 배열이 필요하다.
      - `serviceFeedback`·`rejectionReason`·`consents`는 값이 null이 아니라 **필드 자체가 생략**된다.
      - `consents`는 최초 동의 이력(`version`·`agreedAt`)의 **표시용 참고값**이다. 수정 폼은 동의를 새로 받으므로 프리필이 아니다.
      - `status`·`rejectionReason`은 읽기 전용이다. 수정 요청에는 칸이 없다.
      - 표시 언어는 임대인 계정의 표시 언어를 따른다. 등록·수정이 다국어 문구를 한국어 한 값으로만 받으므로 영어 문구도 같은 값일 수 있다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조 |
      | 401 | `TOKEN_EXPIRED` | 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
      | 404 | `LISTING_NOT_FOUND` | 없는 `listingId`, 24자리 hex 형식이 아님, **남의 매물** |
      """;

  public static final String LISTING_UPDATE_SUMMARY = "매물 수정(임대인)";

  public static final String LISTING_UPDATE_DESCRIPTION =
      """
      임대인이 자기 매물의 내용을 고쳐 다시 심사에 올린다. **부분 수정이 아니라 전체 교체**이므로 등록 때 보낸 속성을 그대로 다시 보낸다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **임대인**(`userType=LANDLORD`) 전용이다. 남의 매물이면 `404 LISTING_NOT_FOUND`다.

      **요청 주의사항**

      - **본문은 매물 등록(`POST /api/v2/listings`)과 같다.** 필드 이름·타입·필수 여부·검증·카탈로그 대조가 모두 같고 다른 곳은 아래 둘뿐이다.

      | 다른 점 | 필드 | 내용 |
      |---|---|---|
      | 방마다 식별자와 상태를 보낸다 | `roomOffers[].roomOfferId` | 기존 방이면 그 id를 그대로, 새 방이면 `null`(또는 생략). 그 매물의 방이 아닌 id는 `400 INVALID_INPUT` |
      | " | `roomOffers[].status` | `ACTIVE`·`INACTIVE`. 방을 내리는 것은 요청에서 빼는 것이 아니라 `INACTIVE`로 보내는 것이다 |
      | 사진 키에 기존 사진을 섞는다 | `imageKeys` · `roomOffers[].roomImageKeys` | 새로 올린 **임시 키**(`uploads/…`)와 그 자리에 이미 있던 **확정 키**(`listings/…`)를 섞어 보낸다 |

      - **시설 8종은 「없음」을 `NONE`으로 보낸다.** 해당 시설이 하나도 없으면 `["NONE"]` **하나만** 싱는다 — `NONE`을 다른 코드와 함께 보내면 `400 INVALID_INPUT`이고 `error.errors[].field`에 그 필드 이름이 실린다. ※ **이 규칙은 아래 스키마에 드러나지 않는다** — `NONE`은 허용값 목록의 평범한 값이라 타입상으로는 조합이 유효해 보인다.
      - `NONE`은 행정구역의 `ETC`나 설문의 `OTHER`와 다르다 — 그쪽은 「목록 밖의 값」이고 `NONE`은 「해당하는 것이 없음」이다.
      - **보내지 않은 필드는 지워진다.** 주소에서 좌표·행정구역·주변 대학이 파생되므로 일부만 보내면 파생값이 본문과 어긋난다. 폼 프리필은 `GET /api/v2/users/me/listings/{listingId}`가 준다.
      - **수정할 수 있는지는 매물의 현재 상태 하나가 정한다.** 임대인이 고를 것은 없다.

      | 수정 전 | 수정 후 | 뜻 |
      |---|---|---|
      | `REJECTED` | `PENDING` | 반려 사유를 고쳐 다시 심사에 올린다. 최초 등록과 같은 줄에 선다 |
      | `PUBLISHED` | `UPDATE_PENDING` | 재심사에 올린다. 심사가 끝날 때까지 세입자 노출에서 빠진다 |
      | `PENDING`·`UPDATE_PENDING` | `422 LISTING_NOT_EDITABLE` | 이미 심사 대기열에 있다. **수정 신청 취소도 없다** |

      - **공개 중인 매물을 고치면 승인될 때까지 세입자에게 보이지 않는다.** 목록·지도·상세·진단 추천·찜 목록·최근 본 매물에서 빠지고 찜·문의·예약 신청은 `404`다. 심사를 거치지 않은 내용이 세입자에게 도달하지 않게 하려는 **의도된 동작**이므로 오타 하나를 고쳐도 마찬가지다. 제출 전에 임대인에게 고지한다.
      - 사라지는 것은 전부 **가역**이다. 승인되면 `favoriteCount`·찜 문서·최근 본 기록이 그대로 살아 있는 채 돌아온다. **이미 잡힌 예약의 카드만 예외로 계속 표시된다.**
      - **사진 키는 자리마다 허용되는 것이 다르다.** 임시 키는 역할 정보가 없어 **JSON에서 놓인 자리**가 역할을 정하고(등록과 동일), 확정 키는 경로에 역할이 이미 박혀 있어 **원래 있던 자리에만** 다시 넣을 수 있다. 대표사진과 방 사진을 서로 옮기거나, 다른 방의 사진을 넣거나, `roomOfferId`가 `null`인 새 방에 확정 키를 넣으면 `400 LISTING_IMAGE_KEY_NOT_FOUND`다. 승격하려면 다시 업로드한다.
      - 사진 장수 제한(대표 1~5장, 방마다 2~5장)은 **임시 키와 확정 키를 합친 최종 배열** 기준이다.
      - **방은 하드 삭제하지 않는다.** 예약·채팅이 `roomOfferId`를 참조하므로 내린 방도 문서에 남고, 같은 id를 `ACTIVE`로 다시 보내면 사진까지 되살아난다. **요청 배열의 순서가 곧 저장 순서**이며 `ACTIVE`와 `INACTIVE`가 섞여 있어도 그대로 둔다.
      - **요청에서 id가 통째로 빠진 기존 방**은 삭제가 아니라 안전망으로 `INACTIVE`가 되어 배열 맨 뒤로 밀린다. 방을 내릴 때는 반드시 `status=INACTIVE`로 보낸다.
      - **저장 결과에 `ACTIVE` 방이 하나도 없으면 `400 INVALID_INPUT`이다.** 상태만 공개인 채 목록·상세 어디에도 나오지 않는 매물이 만들어지기 때문이다.
      - `consents`는 등록과 똑같이 담고 게이트도 같다 — 둘 다 `true`가 아니면 `422 LISTING_REQUIRED_AGREEMENT_MISSING`이다. 다만 저장되는 `version`·`agreedAt`은 **최초 등록 때의 값을 승계**한다. 최초 동의의 증빙이라 수정할 때마다 덮으면 기록이 사라진다.
      - **서버가 정하는 값에는 칸이 없다** — `status`·`rejectionReason`·`favoriteCount`·`createdAt`·`schemaVersion`·`landlordId`. 특히 `rejectionReason`은 수정에 성공하면 서버가 **무조건 비운다**.

      **응답 주의사항**

      - 본문은 내 매물 상세(`GET /api/v2/users/me/listings/{listingId}`)와 같은 구조이고 `status`만 전이 결과(`PENDING` 또는 `UPDATE_PENDING`)다. 상태 전이 액션이라 `201`이 아니라 `200`이다.
      - `listing.imageUrls`·`rooms[].roomImageUrls`는 이번 요청의 최종 배열 순서를 그대로 따르고, 그대로 둔 사진의 URL은 수정 전과 같다.
      - 교체된 사진은 **저장이 성공한 뒤에** 지운다. `INACTIVE`로 내린 방의 사진은 문서에 남아 지워지지 않으며, 그 방을 되살리면 사진도 함께 돌아온다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 등록과 같은 필수값·형식·범위 위반(**시설 8종의 `NONE` 단독 규칙** 포함). 더해서 그 매물의 것이 아닌 `roomOfferId`, 저장 결과에 `ACTIVE` 방 0개 |
      | 400 | `LISTING_UNKNOWN_CATALOG_CODE` | 본문에 실린 코드 값이 서버 코드표에 없음 |
      | 400 | `LISTING_IMAGE_REQUIRED` | 최종 배열이 대표 1~5장 · 방 2~5장을 벗어남 |
      | 400 | `LISTING_IMAGE_KEY_NOT_FOUND` | 키가 남의 것·없는 것·만료된 것이거나, 확정 키를 원래 자리가 아닌 곳에 넣음 |
      | 400 | `MALFORMED_REQUEST` | 본문 JSON 파싱 불가 또는 타입 불일치 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음·위조 |
      | 401 | `TOKEN_EXPIRED` | 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료 토큰 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 사용자 |
      | 404 | `LISTING_NOT_FOUND` | 없는 `listingId`, 24자리 hex 형식이 아님, **남의 매물** |
      | 409 | `LISTING_STATE_CHANGED` | 서버가 매물을 읽고 저장하기까지의 사이에 관리자가 승인·반려해 상태가 바뀜. 실패가 아니라 **재조회 신호**다 |
      | 422 | `LISTING_NOT_EDITABLE` | 매물이 `PENDING`·`UPDATE_PENDING`이라 수정할 수 없음 |
      | 422 | `LISTING_REQUIRED_AGREEMENT_MISSING` | 동의 2종 중 하나 이상이 누락되거나 `false` |
      | 502 | `UPSTREAM_ERROR` | 사진 저장소 복사 실패. 매물은 바뀌지 않고 이번에 복사한 사진은 서버가 지운다. 기존 사진과 임시 사진은 그대로 남아 다시 제출할 수 있다 |
      """;

  // 관리자 심사와 코드 구성이 같아 보여도 ADMIN_LISTING_*를 재사용하지 않는다 — 같은 (path, type)에 서로 다른
  // 배열이 실리면 dedup 승자가 파일 순회 순서에 좌우된다(ApiDocsFields 클래스 주석). 오퍼레이션마다 한 벌씩 둔다.

  public static final String[] LANDLORD_LISTING_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] LANDLORD_LISTING_403 = {"FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"};
  public static final String[] LANDLORD_LISTING_404 = {"LISTING_NOT_FOUND"};

  public static final String[] LISTING_UPDATE_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  public static final String[] LISTING_UPDATE_400 = {
    "INVALID_INPUT",
    "LISTING_UNKNOWN_CATALOG_CODE",
    "LISTING_IMAGE_REQUIRED",
    "LISTING_IMAGE_KEY_NOT_FOUND",
    "MALFORMED_REQUEST"
  };
  public static final String[] LISTING_UPDATE_403 = {"FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"};
  public static final String[] LISTING_UPDATE_404 = {"LISTING_NOT_FOUND"};
  public static final String[] LISTING_UPDATE_409 = {"LISTING_STATE_CHANGED"};
  public static final String[] LISTING_UPDATE_422 = {
    "LISTING_NOT_EDITABLE", "LISTING_REQUIRED_AGREEMENT_MISSING"
  };

  /**
   * 임대인 상세 200 응답 필드다. 수정({@code PUT /api/v2/listings/{listingId}})의 성공 응답도 같은 타입이라 이 한 벌을 함께 쓴다.
   *
   * <p>세입자 상세는 {@code data.listing} 아래에 그대로 실리고, 그 바깥이 <b>다시 제출할 값</b>이다.
   */
  public static List<FieldDescriptor> landlordListingResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data.listing", null, ListingDocumentVariant.MANAGED));
    fields.add(
        enumField(
            "data.status",
            Listing.ListingStatus.class,
            "심사·게시 상태. 읽기 전용이라 수정 요청에는 칸이 없다. 수정에 성공하면 PENDING(반려본) 또는 UPDATE_PENDING(공개본)이 된다"));
    fields.add(
        optField(
            "data.rejectionReason",
            JsonFieldType.STRING,
            "반려 사유. 읽기 전용이며 반려 상태가 아니면 키가 빠진다. 수정에 성공하면 서버가 무조건 비운다"));
    fields.add(
        field(
            "data.businessRegistrationNumber",
            JsonFieldType.STRING,
            "사업자등록번호. 세입자에게는 감추지만 수정 요청 필드라 그대로 되돌려 보낸다"));
    fields.add(
        enumArrayField(
            "data.preferredNationalities",
            Nationality.class,
            "등록 폼 설문 — 선호 국적. 수정 요청 필드이며 답하지 않았으면 빈 배열이다"));
    fields.add(
        enumArrayField(
            "data.contractDifficulties",
            ContractDifficulty.class,
            "등록 폼 설문 — 계약 시 어려움. 수정 요청 필드이며 답하지 않았으면 빈 배열이다"));
    fields.add(
        optField(
            "data.serviceFeedback",
            JsonFieldType.STRING,
            "등록 폼 설문 — 서비스 의견. 수정 요청 필드이며 값이 없으면 키가 빠진다"));
    fields.add(
        optField(
            "data.consents",
            JsonFieldType.OBJECT,
            "최초 동의 이력. 표시용 참고값이라 프리필이 아니며, 수정 폼은 동의를 새로 받는다."
                + " 동의를 받기 전에 저장된 문서에는 없어 필드 자체가 빠진다"));
    fields.add(
        optField(
            "data.consents.privacyPolicyAgreed",
            JsonFieldType.BOOLEAN,
            "개인정보 수집·이용 동의. consents가 없으면 함께 빠진다"));
    fields.add(
        optField(
            "data.consents.listingExposureAgreed",
            JsonFieldType.BOOLEAN,
            "매물 정보 제공 및 노출 동의. consents가 없으면 함께 빠진다"));
    fields.add(
        optField("data.consents.version", JsonFieldType.STRING, "동의한 약관 버전. 수정해도 최초 등록 때 값 그대로다"));
    fields.add(
        optField(
            "data.consents.agreedAt",
            JsonFieldType.STRING,
            "최초 동의 시각(UTC ISO-8601). 수정할 때마다 갱신하지 않는다"));
    fields.add(
        field(
            "data.imageKeys",
            JsonFieldType.ARRAY,
            "대표사진의 저장 키. 그대로 둘 사진을 수정 요청 imageKeys에 되돌려 보낸다."
                + " 미리보기 주소는 listing.imageUrls에 같은 순서로 있다"));
    fields.addAll(landlordRoomFields("data.rooms"));
    fields.add(errorNull());
    return fields;
  }

  /**
   * 임대인 목록 200 응답 필드다. 카드는 세입자 목록과 같고 반려 사유 하나만 더 붙는다.
   *
   * <p>{@code distanceMeters}를 여기서 따로 더하는 이유는 <b>지도 검색이 아니라 소유 기준</b>이라 값이 늘 {@code null}이기 때문이다.
   * 카드 레코드가 이 키를 {@code NON_NULL}로 감추지 않아 <b>null인 채로 응답에 남으므로</b>, 기술자가 없으면 문서화되지 않은 필드로 스니펫 생성이
   * 실패한다.
   */
  public static List<FieldDescriptor> landlordListingPageResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(
        listingDocumentFields("data.content[].listing", null, ListingDocumentVariant.MANAGED));
    fields.add(
        optField(
            "data.content[].listing.distanceMeters",
            JsonFieldType.NUMBER,
            "지도 중심에서 매물까지의 직선거리(미터). 내 매물 목록은 지도 검색이 아니라 소유 기준이라 항상 null이다"));
    fields.add(
        optField(
            "data.content[].rejectionReason",
            JsonFieldType.STRING,
            "반려 사유. 반려 상태가 아니면 값이 null이 아니라 키가 빠진다"));
    fields.addAll(pageFields("상태를 가리지 않는 내 매물 총 개수"));
    fields.add(errorNull());
    return fields;
  }

  /**
   * 수정 요청이 그대로 되돌려 보낼 방 배열이다.
   *
   * <p>중첩된 세입자 상세의 {@code listing.roomOffers[]}와 겹쳐 보이지만 쓰임이 다르다 — 그쪽은 <b>활성 방만</b> 담은 표시용이고, 이쪽은
   * 식별자·상태·라벨 없는 원본 코드·사진 키를 <b>비활성 방까지</b> 준다.
   */
  private static List<FieldDescriptor> landlordRoomFields(String prefix) {
    return List.of(
        field(
            prefix + "[].roomOfferId",
            JsonFieldType.STRING,
            "방 식별자. 수정 요청 roomOffers[].roomOfferId에 그대로 담는다"),
        enumField(
            prefix + "[].status",
            Listing.RoomOfferStatus.class,
            "방 상태. INACTIVE인 방도 함께 내려온다 — 되살리려면 ACTIVE로 바꿔 다시 보낸다"),
        field(prefix + "[].name", JsonFieldType.STRING, "객실 타입명. 라벨이 아니라 저장된 원문이라 그대로 다시 보낸다"),
        field(prefix + "[].contract.minStayMonths", JsonFieldType.NUMBER, "최소 이용 개월"),
        field(prefix + "[].contract.maxStayMonths", JsonFieldType.NUMBER, "최대 이용 개월"),
        field(prefix + "[].pricing.monthlyRent", JsonFieldType.NUMBER, "월 기준 객실 비용(KRW)"),
        field(prefix + "[].pricing.deposit", JsonFieldType.NUMBER, "보증금(KRW)"),
        field(prefix + "[].pricing.maintenanceFee", JsonFieldType.NUMBER, "관리비(KRW)"),
        enumField(prefix + "[].pricing.currency", Listing.Currency.class, "금액 통화. 수정 요청에는 칸이 없다"),
        enumArrayField(
            prefix + "[].filterTags",
            ConditionTag.class,
            "라벨 없는 원본 조건 코드. 수정 요청 roomOffers[].filterTags에 그대로 담는다"),
        field(
            prefix + "[].roomImageUrls",
            JsonFieldType.ARRAY,
            "방 사진 미리보기 주소. 폼 표시용이며 수정 요청에는 URL이 아니라 키를 보낸다"),
        field(
            prefix + "[].roomImageKeys",
            JsonFieldType.ARRAY,
            "방 사진의 저장 키. 그대로 둘 사진을 수정 요청 roomOffers[].roomImageKeys에 되돌려 보낸다"));
  }

  private static List<FieldDescriptor> listingDocumentFields(
      String prefix, String distanceDescription) {
    return listingDocumentFields(prefix, distanceDescription, ListingDocumentVariant.PUBLIC_QUERY);
  }

  private static List<FieldDescriptor> listingDocumentFields(
      String prefix, String distanceDescription, ListingDocumentVariant variant) {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(
        field(prefix + ".listingId", JsonFieldType.STRING, "상세 이동, 하트 토글, 예약 진입에 사용할 매물 ID"));
    fields.add(field(prefix + ".title", JsonFieldType.STRING, "카드와 상세 상단에 표시할 매물 이름"));
    fields.add(enumField(prefix + ".type.code", ListingType.class, "매물 유형의 서버 코드. 필터 요청에 이 값을 사용"));
    fields.add(
        field(
            prefix + ".type.label",
            JsonFieldType.STRING,
            "현재 사용자 언어의 매물 유형 표시명. 화면 배지에는 이 값을 그대로 표시"));
    fields.add(statusField(prefix, variant));
    fields.add(
        enumField(prefix + ".rentalType.code", Listing.RentalType.class, "임대 방식 서버 코드. 요청/비교용"));
    fields.add(
        field(
            prefix + ".rentalType.label",
            JsonFieldType.STRING,
            "가격 영역에 표시할 현재 언어의 임대 방식. 예: Monthly Rent"));
    fields.add(
        field(
            prefix + ".refundPolicy",
            JsonFieldType.STRING,
            "상세 화면에 그대로 보여줄 환불 정책 문장. 현재 사용자 언어로 선택된 문자열 하나이며 별도 코드는 없음"));
    fields.add(
        enumField(
            prefix + ".genderPolicy.code", Listing.GenderPolicy.class, "성별 제한의 서버 코드. 필터 요청에 사용"));
    fields.add(
        field(prefix + ".genderPolicy.label", JsonFieldType.STRING, "성별 제한 배지에 표시할 현재 언어의 문구"));
    fields.add(
        enumField(
            prefix + ".arcRequired.code",
            ArcRequirement.class,
            "외국인등록증(ARC) 요구 여부의 서버 코드. 진단 arcStatus와 1:1로 대응"));
    fields.add(
        field(prefix + ".arcRequired.label", JsonFieldType.STRING, "ARC 요구 여부로 표시할 현재 언어의 문구"));
    fields.add(field(prefix + ".ageMin", JsonFieldType.NUMBER, "입주 가능한 최소 연령"));
    fields.add(field(prefix + ".ageMax", JsonFieldType.NUMBER, "입주 가능한 최대 연령"));
    fields.add(
        enumField(
            prefix + ".languagesSupported[].code",
            SupportedLanguage.class,
            "임대인이 응대 가능한 외국어의 서버 코드"));
    fields.add(
        field(
            prefix + ".languagesSupported[].label",
            JsonFieldType.STRING,
            "응대 가능 언어로 표시할 현재 언어의 문구"));
    fields.add(
        field(prefix + ".contact.managerName", JsonFieldType.STRING, "매물 담당자 이름. 문의 화면에 표시"));
    fields.add(
        field(
            prefix + ".contact.phone",
            JsonFieldType.STRING,
            "전화 문의를 받는 지점 대표 전화. 임대인 개인 연락처와 별개 값이라 마스킹하지 않는다"));
    fields.add(
        optField(
            prefix + ".blogUrl",
            JsonFieldType.STRING,
            "매물 홍보용 블로그 주소. 임대인이 입력하지 않으면 값이 null이 아니라 필드 자체가 생략된다"));
    fields.addAll(locationFields(prefix));
    fields.add(
        field(
            prefix + ".address.city.code",
            JsonFieldType.STRING,
            "지역 필터에 사용할 시·도 서버 코드. 서버가 주소에서 판별하며, 아는 지역이 아니면 ETC다"));
    fields.add(
        field(prefix + ".address.city.label", JsonFieldType.STRING, "주소 보조 표시에 쓸 현재 언어의 시·도 이름"));
    fields.add(
        field(
            prefix + ".address.district.code",
            JsonFieldType.STRING,
            "지역 필터에 사용할 구·군 서버 코드. 서버가 주소에서 판별하며, 아는 지역이 아니면 ETC다"));
    fields.add(
        field(
            prefix + ".address.district.label", JsonFieldType.STRING, "주소 보조 표시에 쓸 현재 언어의 구·군 이름"));
    fields.add(
        field(prefix + ".address.fullAddress", JsonFieldType.STRING, "카드와 상세 주소 영역에 표시할 주소"));
    fields.add(
        optField(
            prefix + ".address.detail", JsonFieldType.STRING, "동·호수 같은 상세주소. null이면 상세주소 줄을 숨김"));
    fields.add(
        enumField(
            prefix + ".nearestTransit.type.code",
            Listing.TransitType.class,
            "교통 배지 아이콘 분기용 서버 코드"));
    fields.add(
        field(
            prefix + ".nearestTransit.type.label",
            JsonFieldType.STRING,
            "교통수단 이름으로 표시할 현재 언어의 문구. 예: Subway"));
    fields.add(
        field(
            prefix + ".nearestTransit.name",
            JsonFieldType.STRING,
            "교통 배지에 표시할 역 이름. 카드 응답은 영어일 때 Station을 Sta.로 줄인 이름을 주고, 상세 응답은 정식 이름을 준다"));
    fields.add(
        enumField(
            prefix + ".nearbyFacilities[].code",
            NearbyFacility.class,
            "주변 편의시설의 서버 코드. 교통과 무관한 값이라 매물 루트가 소유한다"));
    fields.add(
        field(
            prefix + ".nearbyFacilities[].label", JsonFieldType.STRING, "주변 편의시설로 표시할 현재 언어의 문구"));
    fields.add(
        field(
            prefix + ".nearestTransit.walkMinutes",
            JsonFieldType.NUMBER,
            "'도보 N분' 문구에 사용할 분 단위 값"));
    fields.add(
        codeArrayField(
            prefix + ".nearbyUniversityCodes",
            UNIVERSITY_CODES,
            "학교 주변 배지나 학교 필터 매칭에 사용할 학교 코드 목록. 서버가 채우며 요청으로는 보내지 않는다"));
    fields.add(
        enumField(
            prefix + ".building.type.code", Listing.BuildingType.class, "건물 유형 서버 코드. 요청/비교용"));
    fields.add(
        field(prefix + ".building.type.label", JsonFieldType.STRING, "건물 정보 섹션에 표시할 현재 언어의 건물 유형"));
    fields.add(field(prefix + ".building.usedFloorMin", JsonFieldType.NUMBER, "매물이 사용하는 시작 층"));
    fields.add(field(prefix + ".building.usedFloorMax", JsonFieldType.NUMBER, "매물이 사용하는 마지막 층"));
    fields.add(field(prefix + ".building.totalFloors", JsonFieldType.NUMBER, "건물 전체 층수"));
    fields.add(
        field(prefix + ".building.parkingAvailable", JsonFieldType.BOOLEAN, "주차 가능 아이콘/텍스트 표시 여부"));
    fields.add(
        field(
            prefix + ".building.elevatorAvailable", JsonFieldType.BOOLEAN, "엘리베이터 아이콘/텍스트 표시 여부"));
    addCodeLabelArrayFields(
        fields,
        prefix + ".facilities.heatingSystem",
        Listing.HeatingSystem.class,
        "난방 방식",
        "난방 방식 code/label 목록. building이 아니라 여기서 읽고 label을 표시");
    addCodeLabelArrayFields(
        fields,
        prefix + ".facilities.kitchen",
        KitchenFacility.class,
        "주방/조리 시설",
        "주방/조리 시설 code/label 목록");
    addCodeLabelArrayFields(
        fields,
        prefix + ".facilities.laundry",
        LaundryFacility.class,
        "세탁 시설",
        "세탁 시설 code/label 목록");
    addCodeLabelArrayFields(
        fields,
        prefix + ".facilities.livingAmenities",
        LivingAmenity.class,
        "생활 편의시설",
        "생활 편의시설 code/label 목록");
    addCodeLabelArrayFields(
        fields,
        prefix + ".facilities.securityFeatures",
        SecurityFeature.class,
        "보안 시설",
        "보안 시설 code/label 목록");
    addCodeLabelArrayFields(
        fields,
        prefix + ".facilities.commonSpaces",
        Listing.CommonSpaceType.class,
        "공용공간",
        "공용공간 code/label 목록. 수량 없이 종류만 내려간다");
    addCodeLabelArrayFields(
        fields,
        prefix + ".facilities.providedSupplies",
        ProvidedSupply.class,
        "제공 물품",
        "제공 물품 code/label 목록");
    addCodeLabelArrayFields(
        fields,
        prefix + ".conditions",
        ConditionTag.class,
        "매물 조건",
        "매물 단위 조건 배지 목록. 응답에 포함된 방 타입들의 filterTags 합집합이다. "
            + "카드 조건 배지나 상세 Property Details의 features에 바로 사용하고, 방 타입별 조건은 roomOffers[].filterTags를 사용");
    fields.add(
        field(
            prefix + ".roomOffers[].roomOfferId",
            JsonFieldType.STRING,
            "방 타입 선택, 예약/문의 진입에 사용할 방 타입 ID"));
    fields.add(
        field(prefix + ".roomOffers[].name", JsonFieldType.STRING, "Room Types 영역에 표시할 방 타입 이름"));
    fields.add(
        codeField(
            prefix + ".roomOffers[].status",
            PUBLIC_ROOM_OFFER_STATUSES,
            "방 타입 상태. 공개 조회에는 ACTIVE만 내려오므로 그대로 표시 가능"));
    fields.add(
        field(
            prefix + ".roomOffers[].contract.minStayMonths",
            JsonFieldType.NUMBER,
            "계약기간 라벨의 최소 개월 수. 예: 1개월부터"));
    fields.add(
        field(
            prefix + ".roomOffers[].contract.maxStayMonths",
            JsonFieldType.NUMBER,
            "계약기간 라벨의 최대 개월 수. 예: 최대 12개월"));
    fields.add(
        field(
            prefix + ".roomOffers[].pricing.monthlyRent",
            JsonFieldType.NUMBER,
            "월세 표시값(KRW). 카드 가격 범위 계산에도 사용"));
    fields.add(
        field(prefix + ".roomOffers[].pricing.deposit", JsonFieldType.NUMBER, "보증금 표시값(KRW)"));
    fields.add(
        field(
            prefix + ".roomOffers[].pricing.maintenanceFee",
            JsonFieldType.NUMBER,
            "관리비 표시값(KRW). 0이면 관리비 없음 배지로 표시 가능"));
    fields.add(
        enumField(prefix + ".roomOffers[].pricing.currency", Listing.Currency.class, "금액 통화"));
    addCodeLabelArrayFields(
        fields,
        prefix + ".roomOffers[].filterTags",
        ConditionTag.class,
        "방 조건",
        "해당 방 타입에만 붙는 조건 배지 목록. 매물 전체 조건 배지는 상위 conditions를 사용");
    fields.add(
        field(
            prefix + ".roomOffers[].roomImageUrls",
            JsonFieldType.ARRAY,
            "방 타입별 이미지 목록. 비어 있으면 공용 imageUrls 사용 가능"));
    fields.add(
        field(
            prefix + ".description",
            JsonFieldType.STRING,
            "현재 사용자 언어로 서버가 선택한 상세 설명. 프론트는 별도 ko/en 분기 없이 그대로 표시"));
    fields.add(field(prefix + ".extraNotes", JsonFieldType.STRING, "상세 화면의 추가 안내/주의사항"));
    fields.add(
        field(
            prefix + ".imageUrls",
            JsonFieldType.ARRAY,
            "카드 썸네일과 상세 갤러리에 사용할 공용 이미지 목록. 카드 대표 이미지는 첫 번째 값 사용"));
    if (distanceDescription != null) {
      fields.add(field(prefix + ".distanceMeters", JsonFieldType.NUMBER, distanceDescription));
    }
    fields.add(
        field(prefix + ".favorited", JsonFieldType.BOOLEAN, "현재 사용자의 하트 상태. true면 채운 하트로 표시"));
    fields.add(field(prefix + ".favoriteCount", JsonFieldType.NUMBER, "카드/상세에 표시할 찜 수"));
    fields.add(field(prefix + ".createdAt", JsonFieldType.STRING, "매물 생성 시각. 일반 UI에서 필요 없으면 숨김"));
    fields.add(field(prefix + ".updatedAt", JsonFieldType.STRING, "매물 수정 시각. 최신 정보 표시가 필요할 때 사용"));
    return fields;
  }

  /**
   * 매물 상태는 갈래마다 도달 가능한 값이 달라 갈래별로 싣는다.
   *
   * <p>세입자·등록 갈래는 도달 가능한 값이 하나뿐이라 enum 전체가 아니라 그 값만 싣고, 상태를 가리지 않는 임대인·관리자 갈래만 enum 전체를 싣는다. 뒤쪽에
   * {@code codeField}로 값을 손으로 나열하면 상태가 늘 때 문서가 남으므로 {@code enumField}를 쓴다.
   */
  private static FieldDescriptor statusField(String prefix, ListingDocumentVariant variant) {
    if (variant == ListingDocumentVariant.MANAGED) {
      return enumField(
          prefix + ".status",
          Listing.ListingStatus.class,
          "심사·게시 상태. 임대인·관리자 조회는 상태를 가리지 않아 네 값이 모두 나온다. 관리 상태라 번역 없이 코드 문자열 그대로다");
    }
    return variant == ListingDocumentVariant.REGISTERED
        ? codeField(
            prefix + ".status",
            REGISTERED_LISTING_STATUSES,
            "심사 상태. 등록 직후에는 항상 PENDING이며, 이 상태의 매물은 조회·검색·상세 어디에도 나오지 않는다")
        : codeField(
            prefix + ".status",
            PUBLIC_LISTING_STATUSES,
            "공개 상태. 공개 조회에는 PUBLISHED만 내려오므로 별도 필터링 없이 표시 가능");
  }

  /** 좌표는 등록·조회 양쪽에 있다 — 등록이 주소 검색이 준 좌표를 그대로 채우기 때문이다(ADR-0042). */
  private static List<FieldDescriptor> locationFields(String prefix) {
    return List.of(
        field(prefix + ".location.lat", JsonFieldType.NUMBER, "상세 지도 또는 선택 마커 중심에 사용할 위도"),
        field(prefix + ".location.lng", JsonFieldType.NUMBER, "상세 지도 또는 선택 마커 중심에 사용할 경도"));
  }

  private static List<FieldDescriptor> pageFields(String totalElementsDescription) {
    return List.of(
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, totalElementsDescription),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"));
  }

  /**
   * {@code [{code,label}]} 배열 하나를 배열·code·label 세 기술자로 문서화한다.
   *
   * <p>배열 자체는 {@link JsonFieldType#ARRAY}이고 코드값은 원소의 {@code code} 프로퍼티라, 배열 경로가 아니라 {@code [].code}
   * 스칼라 경로에 enum을 싣는다(ADR-0017 「배열 원소 코드값」 규약).
   */
  private static void addCodeLabelArrayFields(
      List<FieldDescriptor> fields,
      String arrayPath,
      Class<? extends Enum<?>> codeType,
      String subject,
      String arrayDescription) {
    fields.add(field(arrayPath, JsonFieldType.ARRAY, arrayDescription));
    fields.add(enumField(arrayPath + "[].code", codeType, subject + " 서버 코드. 필터 요청에 이 값을 사용"));
    fields.add(
        field(
            arrayPath + "[].label",
            JsonFieldType.STRING,
            subject + "의 현재 사용자 언어 표시명. 화면에는 이 값을 사용"));
  }
}
