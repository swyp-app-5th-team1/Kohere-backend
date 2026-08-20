package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.codeField;
import static com.kohere.docs.ApiDocsFields.enumField;
import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static com.kohere.docs.ApiDocsFields.optCodeField;
import static com.kohere.docs.ApiDocsFields.optField;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;

import com.kohere.booking.domain.BookingStatus;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;
import org.springframework.restdocs.snippet.Snippet;

/**
 * Swagger <b>Bookings</b> 태그의 오퍼레이션 문구·에러 코드 배열·필드 기술자 한 벌(#151). 예약 생성·목록·상세(§1~§3)와 내역 삭제·상대
 * 차단·신고 접수·신고 사유 목록(§4~§7)까지 이 태그의 문서 자산 전부가 여기에 모여 있고, {@code BookingDocsTest}·{@code
 * BookingManagementDocsTest}는 테스트 흐름만 갖고 static import로 가져다 쓴다.
 *
 * <p><b>왜 별도 클래스인가</b> — 예약 생성({@code POST /api/v1/listings/{listingId}/bookings})·목록({@code GET
 * /api/v1/bookings})·상세({@code GET /api/v1/bookings/{bookingId}}) 세 오퍼레이션의 스니펫이 {@code
 * BookingDocsTest}와 {@code BookingManagementDocsTest} <b>두 파일에 걸쳐</b> 있다. 특히 차단 관계 403({@code
 * booking-create-blocked})은 관리 테스트에 있으면서 생성 오퍼레이션에 병합된다. 같은 {@code (path, method)} 모델들의
 * summary/description은 <b>첫 non-blank 하나</b>만 채택되고, 같은 {@code (path, method, status)}의 필드 기술자는
 * {@code (path, type)} 기준 dedup·last-wins라 승자가 파일 순회 순서에 좌우된다. 그래서 문구·필드·에러 코드 배열을 여기 한 벌만
 * 둔다({@link ApiDocsFields} 클래스 주석 참조).
 *
 * <p><b>역할 분기 필드의 합집합</b> — 목록·상세는 요청자 {@code userType}으로 응답 DTO가 갈리는데(세입자 {@code
 * BookingSummaryResponse}/{@code BookingDetailResponse} · 임대인 {@code
 * LandlordBookingSummaryResponse}/{@code LandlordBookingDetailResponse}) 생성기는 status별 스키마를 하나만 만든다.
 * 그래서 한 오퍼레이션·한 status의 기술자 목록은 두 분기의 <b>합집합</b>이고, 한쪽에만 있는 키는 {@code optional()}로 낮춘 뒤
 * description에 「null이 아니라 필드 자체가 없다」를 적는다. 느슨해진 계약은 테스트의 {@code doesNotExist()} 단정으로 되메운다(#151 규약
 * 13).
 */
public final class BookingDocsFields {

  private BookingDocsFields() {}

  /**
   * 신고 사유 카탈로그({@code booking_report_reasons})의 현재 활성 code — enum이 아니라 <b>DB 행</b>이라 배포 없이 늘어난다
   * (V17 시드 6종). 그래서 {@code enumField}가 아니라 {@code codeField}로 값을 직접 나열한다. 값이 늘어날 수 있다는 사정은 서버 쪽
   * 이야기라 description에 적지 않고, 대신 사유 목록 API를 쓰라고만 안내한다.
   *
   * <p>§6 신고 접수·§7 사유 목록이 공유하며, {@code BookingManagementDocsTest}의 항목 수 단정에서도 쓴다.
   */
  public static final List<String> REPORT_REASON_CODES =
      List.of("SPAM", "ABUSE", "SEXUAL_CONTENT", "EXTERNAL_CONTACT", "FALSE_INFO", "ETC");

  // ── §1 예약 생성 — POST /api/v1/listings/{listingId}/bookings ──────────────

  public static final String CREATE_SUMMARY = "매물 예약 신청";

  public static final String CREATE_DESCRIPTION =
      """
      매물 화면 또는 채팅방에서 선택한 객실 상품에 **입주 신청을 저장**한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자**(`userType=TENANT`) 전용이다.

      **프론트 요청 전에 준비할 값**

      - `listingId`: `/api/v2` 매물 목록·상세에서 받은 매물 ID를 URL에 넣는다.
      - `roomOfferId`: 같은 매물 상세의 `roomOffers[]`에서 사용자가 선택한 객실 상품 ID를 본문에 넣는다.
      - `moveInDate`: 사용자가 고른 입주 희망일을 `YYYY-MM-DD`로 보낸다.
      - `contractPeriod`: 희망 계약 기간을 개월 수 정수로 보낸다.

      **서버에서 실제로 진행되는 과정**

      1. access token에서 신청자 ID를 확인하고 세입자 계정인지 검사한다.
      2. 매물과 객실 상품이 공개 상태인지, 실제 임대인이 누구인지, 차단 관계가 없는지 검사한다.
      3. 예약을 MySQL에 `REQUESTED` 상태로 저장한다.
      4. 같은 커밋에 `BookingCreatedEvent`도 기록한다. 이 기록 덕분에 서버가 중간에 종료돼도 채팅 처리를 다시 시도할 수 있다.
      5. 백엔드가 비동기로 문의하기와 같은 1:1 채팅방을 찾거나 만들고, 신청 시점 정보를 `BOOKING_CARD` 한 장으로 저장한다.

      프론트가 BOOKING_CARD를 만들거나 전송하는 API는 없다. 카드에는 `clientMessageId`도 보내지 않는다.

      **201 응답 뒤 프론트 권장 흐름**

      1. 이 API의 `201 Created`를 받는다.
      2. `POST /api/v1/listings/{listingId}/inquiries`를 호출해 기존 또는 새 `chatRoomId`를 받는다.
      3. 해당 채팅방 화면을 연다.
      4. `GET /api/v1/chat-rooms/{chatRoomId}/messages`로 저장된 TEXT와 BOOKING_CARD를 함께 조회한다.

      문의 중 신청한 경우에도 새 채팅방을 만들지 않고 이전 대화가 있던 같은 `chatRoomId`에 카드가 이어진다.

      **응답 주의사항**

      - 응답 본문은 예약 코어 내역만 담고 매물 요약·가격·성명은 상세 조회에서 내려준다.
      - `201 Created`는 예약과 재처리 가능한 이벤트가 저장됐다는 뜻이다. 비동기 카드 저장 완료 시각과 완전히 같지는 않을 수 있다.
      - 신청 직후 첫 메시지 이력 조회에서 카드가 아직 없다면 짧게 기다린 뒤 같은 이력 API를 한 번 다시 조회할 수 있다. 지속 polling은 필요하지 않다.
      - 이후 STOMP 실시간 연결 단계에서는 저장 완료 BOOKING_CARD를 채팅방 구독으로 자동 수신한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `roomOfferId` 누락·공백, `contractPeriod` 누락·0·음수, `moveInDate` 누락이거나 `YYYY-MM-DD` 형식이 아님 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 임대인 등 비세입자(`userType≠TENANT`)가 호출 |
      | 403 | `FORBIDDEN` | 요청자와 매물 소유자 사이에 어느 방향이든 차단 관계가 있음 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 404 | `LISTING_NOT_FOUND` | 경로의 매물 또는 `roomOfferId` 방 상품이 없거나 비공개·삭제됨 |
      | 409 | `BOOKING_ALREADY_EXISTS` | 동일 세입자가 동일 방 상품(`roomOfferId`)에 재신청 |
      | 422 | `BOOKING_INVALID_MOVE_IN_DATE` | `moveInDate`가 오늘 이전이거나 방 상품의 입주 가능일 이전 |
      """;

  public static final String[] CREATE_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] CREATE_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] CREATE_403 = {"FORBIDDEN", "AUTH_ONBOARDING_REQUIRED"};
  public static final String[] CREATE_404 = {"LISTING_NOT_FOUND"};
  public static final String[] CREATE_409 = {"BOOKING_ALREADY_EXISTS"};
  public static final String[] CREATE_422 = {"BOOKING_INVALID_MOVE_IN_DATE"};

  public static ParameterDescriptor[] createPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId")
          .description("예약 대상 매물 ID(ObjectId 24자리 hex). /api/v2 매물 목록·상세 응답에서 받은 listingId를 그대로 사용")
    };
  }

  public static List<FieldDescriptor> createRequestFields() {
    return List.of(
        field(
            "roomOfferId",
            JsonFieldType.STRING,
            "매물 상세 roomOffers[]에서 사용자가 선택한 객실 상품 ID(ObjectId 24자리 hex). 누락·공백은 400"),
        field(
            "moveInDate",
            JsonFieldType.STRING,
            "타겟 입주일(`YYYY-MM-DD`). 형식 위반은 400 `INVALID_INPUT`, 과거·입주 가능일 이전은 422"),
        field(
            "contractPeriod",
            JsonFieldType.NUMBER,
            "계약 개월수(1 이상의 정수). 0·음수는 400. 총 금액 계산에 그대로 쓰인다"));
  }

  public static List<FieldDescriptor> createResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field(
            "data.bookingId",
            JsonFieldType.NUMBER,
            "생성된 예약 ID. 이후 예약 상세 조회에 사용하며 BOOKING_CARD 중복 저장을 막는 서버 기준값이기도 함"),
        enumField("data.status", BookingStatus.class, BOOKING_STATUS_DESCRIPTION),
        field("data.listingId", JsonFieldType.STRING, "예약 대상 매물 ID"),
        field("data.roomOfferId", JsonFieldType.STRING, "예약 대상 방 상품 ID"),
        field("data.moveInDate", JsonFieldType.STRING, "타겟 입주일(`YYYY-MM-DD`)"),
        field("data.contractPeriod", JsonFieldType.NUMBER, "계약 개월수"),
        field("data.createdAt", JsonFieldType.STRING, "예약 접수 일시(ISO-8601 UTC)"),
        errorNull());
  }

  /**
   * 201의 {@code Location} 헤더. 저장소에서 유일한 {@code responseHeaders} 선언이라 Swagger의 Headers 표도 여기서만 나온다.
   */
  public static Snippet createResponseHeaders() {
    return responseHeaders(
        headerWithName(HttpHeaders.LOCATION)
            .description("생성된 예약의 조회 경로 — `/api/v1/bookings/{bookingId}`"));
  }

  // ── §2 예약 목록 — GET /api/v1/bookings ────────────────────────────────────

  public static final String LIST_SUMMARY = "예약 목록 조회";

  public static final String LIST_DESCRIPTION =
      """
      예약을 `createdAt` 내림차순 오프셋 페이지로 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 세입자·임대인 모두 유효한 요청이라 역할 403은 없다.

      **요청 주의사항**

      - 정렬은 `createdAt,desc` 고정이며 쿼리로 바꿀 수 없다.

      **응답 주의사항**

      - 요청자 `userType`으로 **반환 대상이 갈린다** — 세입자는 본인이 신청한 예약, 임대인은 본인 소유 매물에 신청된 예약(일시중지 매물 포함)이다.
      - **Schema 탭의 필드 목록은 세입자·임대인 두 분기의 합집합이며 실제 한 응답에 둘 다 나오지 않는다.** `(세입자만)`·`(임대인만)`이 붙은 필드는 다른 분기의 응답에서 값이 null이 아니라 **필드 자체가 생략**된다. 실제 형태는 Examples에서 역할을 골라 본다.
      - 요청자가 삭제한 예약과 요청자가 차단한 상대의 예약은 목록에서 빠진다. 삭제·차단 상태 자체는 응답 필드로 노출하지 않는다(사라지는 것으로만 관측된다).
      - 매물·방 상품이 삭제·비공개면 `title`·`thumbnailUrl`·`roomOfferName`이 null이 된다(예약 코어 내역은 그대로 유지된다).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `page`가 음수이거나 `size`가 1~100 밖 — 조용히 보정하지 않고 거절한다 |
      | 400 | `MALFORMED_REQUEST` | `page`·`size`가 정수가 아님(쿼리 파라미터 타입 불일치) |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      """;

  public static final String[] LIST_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] LIST_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] LIST_403 = {"AUTH_ONBOARDING_REQUIRED"};

  public static ParameterDescriptor[] listQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0). 음수는 400"),
      parameterWithName("size").optional().description("페이지 크기(기본 20). 1~100 밖은 400")
    };
  }

  /** 세입자·임대인 두 분기의 합집합. 한쪽에만 있는 키는 {@code optional()}이다. */
  public static List<FieldDescriptor> listResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field("data.content[].bookingId", JsonFieldType.NUMBER, "예약 식별자"),
        field("data.content[].listingId", JsonFieldType.STRING, "매물 ID"),
        optField("data.content[].title", JsonFieldType.STRING, TITLE_DESCRIPTION),
        optField("data.content[].thumbnailUrl", JsonFieldType.STRING, THUMBNAIL_DESCRIPTION),
        field("data.content[].roomOfferId", JsonFieldType.STRING, "방 상품 ID"),
        optField("data.content[].roomOfferName", JsonFieldType.STRING, ROOM_OFFER_NAME_DESCRIPTION),
        optField("data.content[].applicantName", JsonFieldType.STRING, APPLICANT_NAME_DESCRIPTION),
        field("data.content[].moveInDate", JsonFieldType.STRING, "타겟 입주일(`YYYY-MM-DD`)"),
        field("data.content[].contractPeriod", JsonFieldType.NUMBER, "계약 개월수"),
        enumField("data.content[].status", BookingStatus.class, BOOKING_STATUS_DESCRIPTION),
        field("data.content[].createdAt", JsonFieldType.STRING, "예약 접수 일시(ISO-8601 UTC)"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호(0-base)"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field(
            "data.page.totalElements",
            JsonFieldType.NUMBER,
            "요청자에게 보이는 전체 건수 — 요청자가 삭제했거나 상대를 차단해 목록에서 빠진 예약은 세지 않는다"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"),
        errorNull());
  }

  // ── §3 예약 단건 상세 — GET /api/v1/bookings/{bookingId} ───────────────────

  public static final String DETAIL_SUMMARY = "예약 단건 상세 조회";

  public static final String DETAIL_DESCRIPTION =
      """
      예약 1건의 상세를 조회한다. 매물 정보·가격·성명은 신청 시점 스냅샷이 아니라 조회 시점의 현재 값이다(가격이 바뀌면 현재가 기준).

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 세입자·임대인 모두 유효한 요청이라 역할 403은 없다.

      **응답 주의사항**

      - 요청자 `userType`으로 **응답 본문이 갈린다** — 세입자는 본인 예약 + 본인 성명(`tenantName`), 임대인은 본인 소유 매물에 신청된 예약 + 신청자 프로필(`applicant*`)이다.
      - **Schema 탭의 필드 목록은 세입자·임대인 두 분기의 합집합이며 실제 한 응답에 둘 다 나오지 않는다.** `tenantName`은 세입자 분기에만, `applicantId`·`applicantName`·`applicantGender`·`applicantCountry`·`applicantCountryName`·`applicantEmail`은 임대인 분기에만 있는 키로, 반대 분기에서는 null이 아니라 **필드 자체가 없다**.
      - 매물·방 상품이 삭제·비공개면 `title`·`thumbnailUrl`·`address`·`roomOfferName`이 null이 되고 `deposit`·`totalAmount`는 **0**이 된다(null이 아니다).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 404 | `BOOKING_NOT_FOUND` | 예약이 없거나 조회 권한 밖(세입자: 타인 예약 / 임대인: 내 소유 매물 신청 아님)이거나, 요청자가 이미 삭제했거나 상대를 차단한 예약 — 전부 404로 통일한다(존재 비노출) |
      """;

  public static final String[] DETAIL_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] DETAIL_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] DETAIL_404 = {"BOOKING_NOT_FOUND"};

  public static ParameterDescriptor[] detailPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("bookingId").description("예약 식별자 — 세입자는 본인 예약, 임대인은 본인 소유 매물에 신청된 예약")
    };
  }

  /** 세입자·임대인 두 분기의 합집합. 한쪽에만 있는 키는 {@code optional()}이다. */
  public static List<FieldDescriptor> detailResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field("data.bookingId", JsonFieldType.NUMBER, "예약 식별자"),
        enumField("data.status", BookingStatus.class, BOOKING_STATUS_DESCRIPTION),
        field("data.listingId", JsonFieldType.STRING, "매물 ID"),
        field("data.roomOfferId", JsonFieldType.STRING, "방 상품 ID"),
        optField("data.title", JsonFieldType.STRING, TITLE_DESCRIPTION),
        optField("data.thumbnailUrl", JsonFieldType.STRING, THUMBNAIL_DESCRIPTION),
        optField("data.address", JsonFieldType.STRING, "매물 주소 — 조회 시점 값. 매물·방 상품이 삭제·비공개면 null"),
        optField(
            "data.roomOfferName", JsonFieldType.STRING, "방 상품명 — 조회 시점 값. 매물·방 상품이 삭제·비공개면 null"),
        field("data.createdAt", JsonFieldType.STRING, "예약 접수 일시(ISO-8601 UTC)"),
        field("data.moveInDate", JsonFieldType.STRING, "타겟 입주일(`YYYY-MM-DD`)"),
        field("data.contractPeriod", JsonFieldType.NUMBER, "계약 개월수"),
        optField(
            "data.tenantName",
            JsonFieldType.STRING,
            "예약자(요청자 본인) 성명(세입자만) — 값은 null이 되지 않는다(이름이 없으면 빈 문자열)"),
        optField("data.applicantId", JsonFieldType.NUMBER, "신청자(세입자) 식별자(임대인만)"),
        optField("data.applicantName", JsonFieldType.STRING, APPLICANT_NAME_DESCRIPTION),
        optCodeField(
            "data.applicantGender",
            List.of("MALE", "FEMALE"),
            "신청자 성별(임대인만) — 마스킹 없이 평문. 미수집이거나 탈퇴 익명화된 신청자는 null"),
        optField(
            "data.applicantCountry",
            JsonFieldType.STRING,
            "신청자 국적 ISO 3166-1 alpha-2 코드(임대인만) — 미수집·탈퇴 익명화 시 null"),
        optField(
            "data.applicantCountryName",
            JsonFieldType.STRING,
            "신청자 국적 표시명(임대인만) — 서버가 국적 코드로 변환, 국적 미수집·미등록 코드면 null"),
        optField(
            "data.applicantEmail",
            JsonFieldType.STRING,
            "신청자 이메일(임대인만) — 마스킹 없이 평문. 탈퇴 익명화 시 null"),
        field(
            "data.deposit",
            JsonFieldType.NUMBER,
            "보증금(KRW 정수) — 조회 시점 현재가. 매물·방 상품이 삭제·비공개면 null이 아니라 **0**"),
        field(
            "data.totalAmount",
            JsonFieldType.NUMBER,
            "총 금액 = 보증금 + 월세 × 계약 개월수(관리비 제외, 세입자·임대인 동일 정의)."
                + " 매물·방 상품이 삭제·비공개면 null이 아니라 **0**"),
        errorNull());
  }

  // ── §4 예약 내역 삭제 — DELETE /api/v1/bookings/{bookingId} ────────────────

  public static final String DELETE_SUMMARY = "예약 내역 삭제";

  public static final String DELETE_DESCRIPTION =
      """
      내 목록·상세에서 예약을 영구히 숨긴다. 되돌리는 엔드포인트가 없어 요청자는 그 예약을 다시 볼 수 없고, 세입자는 같은 방 상품에 다시 신청해도 409 `BOOKING_ALREADY_EXISTS`가 돌아온다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 해당 예약의 참여자(세입자 또는 임대인)만 호출할 수 있고, 세입자·임대인 공통이라 역할 403은 없다.

      **요청 주의사항**

      - **취소가 아니다.** 요청자에게만 숨겨지므로 **상대 목록에는 그대로 보이고** 상대에게 알림도 가지 않는다. 두 참여자의 삭제는 서로 완전히 독립이다.
      - 멱등이다 — 이미 삭제한 예약을 다시 삭제해도 204다.
      - 차단(`POST .../block`)과 무관하다. 삭제해도 상대는 여전히 새 신청을 보낼 수 있다.
      - 삭제해도 신고(`POST .../report`)는 계속 가능하다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `MALFORMED_REQUEST` | `bookingId`가 숫자가 아님(경로 변수 타입 불일치) |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 404 | `BOOKING_NOT_FOUND` | 요청자가 참여자가 아니거나 없는 예약 — 403이 아니라 404로 통일한다(존재 비노출) |
      """;

  public static final String[] DELETE_400 = {"MALFORMED_REQUEST"};
  public static final String[] DELETE_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] DELETE_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] DELETE_404 = {"BOOKING_NOT_FOUND"};

  public static ParameterDescriptor[] deletePathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("bookingId").description("삭제할 예약 식별자 — 요청자가 참여한 예약이어야 한다")
    };
  }

  // ── §5 예약 상대 차단 — POST /api/v1/bookings/{bookingId}/block ────────────

  public static final String BLOCK_SUMMARY = "예약 상대 차단";

  public static final String BLOCK_DESCRIPTION =
      """
      해당 예약의 상대 참여자를 사용자 단위로 차단한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 해당 예약의 참여자만 호출할 수 있고, 세입자·임대인 공통이라 역할 403은 없다.

      **요청 주의사항**

      - **차단 대상을 요청 본문으로 보내지 않는다.** 서버가 예약에서 상대를 도출한다 — 요청자가 세입자면 임대인, 임대인이면 세입자다.
      - 멱등이다 — 이미 차단한 상대를 다시 차단해도 409가 아니라 204다.
      - 차단 목록 조회·해제는 예약과 무관해 `GET`·`DELETE /api/v1/users/me/blocks`(Users)에 있다.

      **응답 주의사항**

      - 차단은 예약 단위가 아니라 **사용자 단위**다. 이후 요청자의 목록·상세에서 **그 상대와의 모든 예약**이 사라진다(이 예약 1건만이 아니다).
      - 숨김은 **단방향**이다 — 상대의 목록은 그대로다. 다만 신규 신청 차단은 **양방향**이라 상대가 새 예약을 신청해도 403이고, 요청자 본인도 차단을 풀기 전까지 그 상대의 매물에 새로 신청할 수 없다(403).
      - 삭제(`DELETE /api/v1/bookings/{bookingId}`)와 독립이다. 차단만 했다면 해제 시 그 예약들이 다시 보이고, 삭제까지 했다면 해제해도 계속 숨겨진다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `MALFORMED_REQUEST` | `bookingId`가 숫자가 아님(경로 변수 타입 불일치) |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 404 | `BOOKING_NOT_FOUND` | 요청자가 참여자가 아니거나 없는 예약 — 404로 통일한다(존재 비노출) |
      """;

  public static final String[] BLOCK_400 = {"MALFORMED_REQUEST"};
  public static final String[] BLOCK_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] BLOCK_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] BLOCK_404 = {"BOOKING_NOT_FOUND"};

  public static ParameterDescriptor[] blockPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("bookingId").description("차단 대상 상대를 도출할 예약 식별자 — 요청자가 참여한 예약이어야 한다")
    };
  }

  // ── §6 예약 신고 접수 — POST /api/v1/bookings/{bookingId}/report ───────────

  public static final String REPORT_SUMMARY = "예약 신고 접수";

  public static final String REPORT_DESCRIPTION =
      """
      예약 1건에 대한 신고를 접수·저장한다. 범위는 접수까지이고 운영자 검토·제재는 포함하지 않는다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 해당 예약의 참여자만 호출할 수 있고, 세입자·임대인 공통이라 역할 403은 없다.

      **요청 주의사항**

      - 신고자는 access 토큰으로 식별하며 본문에 넣지 않는다.
      - 본문 전체를 `{}`로 보내도 접수된다.
      - **다건 허용** — 동일 신고자가 동일 예약을 여러 번 신고할 수 있다(409가 없다).
      - **삭제·차단 상태와 무관하다** — 이미 삭제했거나 상대를 차단한 예약도 신고할 수 있다. 그래서 같은 예약이 `GET /api/v1/bookings/{bookingId}`에서는 404인데 여기서는 201일 수 있다.


      **응답 주의사항**

      - 응답에 신고자 식별자와 `detail` 원문은 노출하지 않는다. 상태(`status`) 필드도 없고, 접수한 신고를 취소·수정하는 엔드포인트도 없다 — 한 번 보내면 되돌릴 수 없다.
      - 단건 조회 엔드포인트가 없어 201이지만 `Location` 헤더를 주지 않는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 미정의·비활성 `reason` 코드이거나 `detail`이 500자 초과 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON 파싱 불가 또는 필드 타입 불일치 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 404 | `BOOKING_NOT_FOUND` | 요청자가 참여자가 아니거나 없는 예약 — 403이 아니라 404로 통일한다(존재 비노출) |
      """;

  public static final String[] REPORT_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] REPORT_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] REPORT_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] REPORT_404 = {"BOOKING_NOT_FOUND"};

  public static ParameterDescriptor[] reportPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("bookingId").description("신고 대상 예약 식별자 — 요청자가 참여한 예약이어야 한다")
    };
  }

  public static List<FieldDescriptor> reportRequestFields() {
    return List.of(
        optCodeField(
            "reason",
            REPORT_REASON_CODES,
            "신고 사유(선택) — " + REASON_CODE_DESCRIPTION + ". 생략·null이면 사유 없이 접수된다"),
        optField(
            "detail",
            JsonFieldType.STRING,
            "신고 상세(선택) — 자유 입력 최대 500자. 초과는 400 `INVALID_INPUT`. 응답에는 노출하지 않는다"));
  }

  public static List<FieldDescriptor> reportResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        field("data.reportId", JsonFieldType.NUMBER, "접수된 신고 식별자"),
        field("data.bookingId", JsonFieldType.NUMBER, "신고 대상 예약 식별자"),
        optCodeField(
            "data.reason",
            REPORT_REASON_CODES,
            "접수된 신고 사유 — " + REASON_CODE_DESCRIPTION + ". 사유 없이 신고했으면 null"),
        field("data.createdAt", JsonFieldType.STRING, "신고 접수 일시(ISO-8601 UTC)"),
        errorNull());
  }

  // ── §7 예약 신고 사유 목록 — GET /api/v1/bookings/report-reasons ───────────

  public static final String REPORT_REASONS_SUMMARY = "예약 신고 사유 목록";

  public static final String REPORT_REASONS_DESCRIPTION =
      """
      예약 신고(`POST /api/v1/bookings/{bookingId}/report`)에 쓸 사유 선택지를 반환한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 역할 분기가 없다.

      **요청 주의사항**

      - `label`은 요청자의 표시 언어(프로필의 `lang`, 미설정·미지원이면 `en` 폴백)로 서버가 번역하며, 파라미터로 언어를 고를 수 없다.

      **응답 주의사항**

      - 지원 언어는 `en`·`ko`·`ja` 3종이다(임대인은 `ko` 고정). 반환되는 **사유 집합과 순서는 언어와 무관하게 같고**, 요청 언어에 라벨이 없는 사유만 `en` 라벨로 내려온다.
      - **정렬은 서버가 정한 순서로 고정**이며 클라이언트는 받은 순서를 그대로 노출한다.
      - 신고 사유 선택지는 **이 엔드포인트의 응답으로 구성한다.**

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      """;

  public static final String[] REPORT_REASONS_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] REPORT_REASONS_403 = {"AUTH_ONBOARDING_REQUIRED"};

  public static List<FieldDescriptor> reportReasonsResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 성공 응답은 항상 true"),
        codeField("data.reasons[].code", REPORT_REASON_CODES, REASON_CODE_DESCRIPTION),
        field(
            "data.reasons[].label",
            JsonFieldType.STRING,
            "사유 표시명 — 요청자의 표시 언어로 서버가 번역한 문자열. 화면에 그대로 노출한다(클라이언트가 code로 다시 매핑하지 않는다)"),
        errorNull());
  }

  // ── 두 오퍼레이션 이상이 공유하는 필드 문구 ────────────────────────────────

  private static final String REASON_CODE_DESCRIPTION =
      "신고 사유 코드 — 언어 무관 불변 식별자." + " **`GET /api/v1/bookings/report-reasons`의 응답을 쓴다.**";

  private static final String BOOKING_STATUS_DESCRIPTION =
      "예약 상태 — 신청 직후 `REQUESTED` 고정. MVP에서는 `REQUESTED`만 반환된다(수락·거절·취소 엔드포인트 미구현이라 나머지 값으로 전이할 경로가 없다)";

  private static final String TITLE_DESCRIPTION = "매물 제목 — 조회 시점 값. 매물·방 상품이 삭제·비공개면 null";

  private static final String THUMBNAIL_DESCRIPTION =
      "매물 대표 이미지 URL — 조회 시점 값. 매물·방 상품이 삭제·비공개거나 매물에 등록된 이미지가 한 장도 없으면 null";

  private static final String ROOM_OFFER_NAME_DESCRIPTION = "방 상품명(임대인만) — 매물·방 상품이 삭제·비공개면 null";

  private static final String APPLICANT_NAME_DESCRIPTION =
      "신청자(세입자) 성명(임대인만) — 값은 null이 되지 않는다(이름이 없으면 빈 문자열)";
}
