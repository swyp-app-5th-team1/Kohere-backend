package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.codeArrayField;
import static com.kohere.docs.ApiDocsFields.codeField;
import static com.kohere.docs.ApiDocsFields.enumField;
import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static com.kohere.docs.ApiDocsFields.optCodeArrayField;
import static com.kohere.docs.ApiDocsFields.optCodeField;
import static com.kohere.docs.ApiDocsFields.optEnumField;
import static com.kohere.docs.ApiDocsFields.optField;
import static com.kohere.docs.ApiDocsParams.optCodeParam;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;

import com.kohere.diagnosis.application.dto.FlowResultCode;
import com.kohere.diagnosis.application.dto.RecommendationResultCode;
import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import com.kohere.diagnosis.domain.District;
import com.kohere.diagnosis.domain.Purpose;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.domain.UniversityGroup;
import java.util.ArrayList;
import java.util.List;
import org.springframework.restdocs.headers.HeaderDescriptor;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;

/**
 * Swagger {@code Diagnosis} 태그의 문서 자산 정본(#151) — 오퍼레이션 문구(summary·description)·status별 에러코드 배열·코드
 * 카탈로그·파라미터·필드 기술자를 한곳에 모은다. 조회({@code DiagnosisDocsTest})·흐름({@code DiagnosisV2DocsTest}) 두 문서
 * 테스트에는 테스트 흐름만 남고 여기 것을 static import로 부른다.
 *
 * <p><b>왜 별도 클래스인가</b> — 두 문서 테스트가 같은 코드 목록·필드 기술자를 쓴다. 파일마다 사본을 두면 같은 필드가 Swagger에서 다르게 보이고, 같은
 * {@code (path, method, status)}에 서로 다른 기술자가 붙으면 {@code (path, type)} dedup에서 한쪽이 조용히 사라진다.
 *
 * <p>추천 응답({@code content[]}·{@code markers[]}·{@code page})은 페이지 조회와 마커 조회가 마커 모양을 공유하므로 같은 헬퍼를
 * 공유해 두 문서가 어긋나지 않게 한다.
 */
public final class DiagnosisDocsFields {

  private DiagnosisDocsFields() {}

  // ---------------------------------------------------------------------------------------------
  // 코드 카탈로그 — enum 클래스가 없거나(MongoDB 카탈로그), 요청/응답 허용 집합이 다른 값들
  // ---------------------------------------------------------------------------------------------

  /**
   * 진단 문항의 제출 필드명({@code diagnosisQuestions.field}). enum 클래스가 아니라 MongoDB 카탈로그 문자열이라 직접 나열한다.
   *
   * <p>{@code regionRetry}는 ① 지역 0건 예외질문 전용이다 — 정본 6슬롯 문항은 조회하므로 내려오지 않는다.
   */
  public static final List<String> QUESTION_FIELD_CODES =
      List.of(
          "region",
          "regionRetry",
          "purpose",
          "university",
          "district",
          "conditions",
          "monthlyRent",
          "arcStatus");

  /**
   * 문항의 선택 방식({@code select.type}). MongoDB 카탈로그 문자열이며 enum 클래스가 없다.
   *
   * <p>{@code NUMBER}는 존재하지 않는 값이다 — 숫자 2개를 입력받는 ⑤ 월세 문항은 {@code NUMBER_RANGE}다.
   */
  public static final List<String> SELECT_TYPE_CODES = List.of("SINGLE", "MULTI", "NUMBER_RANGE");

  /**
   * ④ 주거 조건 요청({@code codes[]})의 허용 코드 8개. 파생 조건 {@code NO_ARC}는 ⑥ {@code arcStatus} 답에서 서버가 만들어 넣는
   * 값이라 사용자가 직접 고를 수 없다({@code DiagnosisCondition.userSelectable()}).
   */
  public static final List<String> SELECTABLE_CONDITION_CODES =
      List.of(
          "MOVE_IN_NOW",
          "FEMALE_ONLY",
          "MEALS_INCLUDED",
          "DOUBLE_ROOM",
          "PRIVATE_BATH",
          "ENGLISH_OK",
          "ADDRESS_REGISTRATION",
          "NO_MAINT_FEE");

  /** 진단 응답의 {@code conditions[]} 허용 코드 9개 — 요청 8개 + 서버 파생 {@code NO_ARC}. */
  public static final List<String> DIAGNOSIS_CONDITION_CODES = withNoArc();

  /** 추천 매물 카드의 주거 유형 코드(listing {@code ListingType}). */
  public static final List<String> LISTING_TYPE_CODES =
      List.of("GOSHIWON", "CO_LIVING", "SHARE_HOUSE", "OTHER");

  /** 추천 매물 카드의 조건 배지 코드(listing {@code ConditionTag}) — 진단 조건과 이름이 1:1로 통일돼 있다. */
  public static final List<String> LISTING_CONDITION_CODES = withNoArc();

  /** 진단 이력 정렬 허용값({@code DiagnosisService.HISTORY_SORT_KEYS} × 방향). */
  public static final List<String> HISTORY_SORT_VALUES =
      List.of("submittedAt,desc", "submittedAt,asc");

  /** 추천 정렬 허용값({@code DiagnosisRecommendationReader.SORT_KEYS} × 방향). */
  public static final List<String> RECOMMENDATION_SORT_VALUES =
      List.of(
          "recommended,desc",
          "recommended,asc",
          "price,asc",
          "price,desc",
          "distance,asc",
          "distance,desc");

  /** {@code POST /start}는 언제나 ① 지역 질문 하나만 낸다 — 다른 결과코드로 갈 수 없다. */
  public static final List<String> START_RESULT_CODES = List.of("NEXT_QUESTION");

  /** 게스트 세션 키 에코 헤더(#181). 발급은 {@code /start} 응답이고 소비처는 {@code /next}·추천 조회다. */
  public static final String GUEST_SESSION_HEADER = "X-Guest-Session-Id";

  private static List<String> withNoArc() {
    List<String> codes = new ArrayList<>(SELECTABLE_CONDITION_CODES);
    codes.add("NO_ARC");
    return List.copyOf(codes);
  }

  // ---------------------------------------------------------------------------------------------
  // 오퍼레이션 GET /api/v1/diagnoses/{diagnosisId} — 진단 단건 상세
  // ---------------------------------------------------------------------------------------------

  // 요청 본문이 없는 오퍼레이션이라 MALFORMED_REQUEST에 도달할 수 없다 — 400은 INVALID_INPUT 하나다.

  // ---------------------------------------------------------------------------------------------

  // ---------------------------------------------------------------------------------------------

  public static final String DETAIL_SUMMARY = "진단 단건 상세";

  public static final String DETAIL_DESCRIPTION =
      """
      확정된 진단 1건의 입력값 전체를 다시 보여준다(입력 다시 보기 화면).

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 본인 소유 진단만 조회된다.
      - 게스트가 v2로 만든 진단은 회원 토큰으로 조회할 수 없다 — `diagnosisId`를 알아도 403이다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `FORBIDDEN` | 타인 소유 진단 접근 |
      | 404 | `DIAGNOSIS_NOT_FOUND` | 진단이 존재하지 않거나 폐기 기록 |
      """;

  public static final String[] DETAIL_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] DETAIL_403 = {"FORBIDDEN"};
  public static final String[] DETAIL_404 = {"DIAGNOSIS_NOT_FOUND"};

  /** {@code GET /api/v1/diagnoses/{diagnosisId}} 200 응답. */
  public static List<FieldDescriptor> detailResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(diagnosisSummaryFields("data."));
    fields.add(enumField("data.status", DiagnosisStatus.class, "진단 상태 — 확정 진단이므로 `COMPLETED`"));
    fields.add(field("data.submittedAt", JsonFieldType.STRING, "확정 시각(ISO-8601 UTC)"));
    fields.add(errorNull());
    return List.copyOf(fields);
  }

  /** 진단 식별자 path 파라미터(성공·에러 스니펫 공용). */
  public static ParameterDescriptor[] diagnosisIdPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("diagnosisId").description("진단 식별자(본인 소유 확정 진단)")
    };
  }

  // ---------------------------------------------------------------------------------------------
  // 진단 입력 요약(이력 항목·단건 상세 공용 형태)
  // ---------------------------------------------------------------------------------------------

  /**
   * 진단 입력 요약 필드. {@code prefix}는 {@code "data."} 또는 {@code "data.content[]."}다.
   *
   * <p><b>{@code GET /latest}에는 쓰지 않는다</b> — 그쪽은 {@code completed=false}일 때 같은 필드가 전부 {@code null}로
   * 실려 optional이어야 하는데, 여기서 optional을 낮추면 이력·상세의 「항상 있다」 계약까지 함께 풀린다.
   */
  public static List<FieldDescriptor> diagnosisSummaryFields(String prefix) {
    return List.of(
        field(prefix + "diagnosisId", JsonFieldType.NUMBER, "진단 식별자"),
        enumField(prefix + "region", Region.class, "① 지역(단일 선택)"),
        enumField(prefix + "purpose", Purpose.class, "② 입국 목적(단일 선택) — ③ 문항을 가른다"),
        optEnumField(
            prefix + "university",
            UniversityGroup.class,
            "③ 대학 그룹 — `purpose=STUDY`일 때만 채워지고 `NON_STUDY`면 `null`이다"),
        optEnumField(
            prefix + "district",
            District.class,
            "③ 지역(구) — `purpose=NON_STUDY`일 때만 채워지고 `STUDY`면 `null`이다"),
        codeArrayField(
            prefix + "conditions",
            DIAGNOSIS_CONDITION_CODES,
            "주거 조건 코드 목록 — ④에서 고른 최대 3개 + ⑥ `arcStatus=NO_ARC`일 때 서버가 더하는 `NO_ARC`"),
        field(prefix + "monthlyRentMin", JsonFieldType.NUMBER, "⑤ 월세 범위 하한(KRW 정수)"),
        field(prefix + "monthlyRentMax", JsonFieldType.NUMBER, "⑤ 월세 범위 상한(KRW 정수)"),
        enumField(prefix + "arcStatus", ArcStatus.class, "⑥ ARC(외국인등록증) 발급 상태(단일 선택)"));
  }

  // ---------------------------------------------------------------------------------------------
  // 추천 응답(페이지 조회 공용 형태)
  // ---------------------------------------------------------------------------------------------

  /**
   * 추천 매물 카드와 지도 마커. 0건 응답도 같은 헬퍼를 쓰도록 <b>배열 원소 필드는 전부 optional</b>이다 — 0건이면 배열이 비어 원소 자체가 없다(값이
   * null인 것이 아니다).
   */
  public static List<FieldDescriptor> recommendationContentFields() {
    return List.of(
        field(
            "data.content",
            JsonFieldType.ARRAY,
            "추천 매물 카드 목록(현재 페이지). 조건에 맞는 매물이 0건이면 빈 배열이며 에러가 아니다"),
        optField(
            "data.content[].listingId",
            JsonFieldType.STRING,
            "매물 식별자(ObjectId hex 문자열) — `markers[]`와 이 값으로 연결한다"),
        optField("data.content[].title", JsonFieldType.STRING, "매물 제목(사용자 언어로 선택된 문자열)"),
        optCodeField(
            "data.content[].type.code", LISTING_TYPE_CODES, "주거 유형의 언어 무관 서버 코드. 필터 재요청에 쓴다"),
        optField("data.content[].type.label", JsonFieldType.STRING, "주거 유형 표시명(사용자 언어). 화면에 쓴다"),
        optField("data.content[].monthlyRentMin", JsonFieldType.NUMBER, "매물 월세 범위 하한(KRW 정수)"),
        optField("data.content[].monthlyRentMax", JsonFieldType.NUMBER, "매물 월세 범위 상한(KRW 정수)"),
        optField("data.content[].minDeposit", JsonFieldType.NUMBER, "매물 보증금 범위 하한(KRW 정수)"),
        optField("data.content[].maxDeposit", JsonFieldType.NUMBER, "매물 보증금 범위 상한(KRW 정수)"),
        optField(
            "data.content[].thumbnailUrl", JsonFieldType.STRING, "썸네일 URL. 등록된 이미지가 없으면 `null`"),
        optField("data.content[].lat", JsonFieldType.NUMBER, "매물 위도(WGS84)"),
        optField("data.content[].lng", JsonFieldType.NUMBER, "매물 경도(WGS84)"),
        optField(
            "data.content[].conditions",
            JsonFieldType.ARRAY,
            "카드 조건 배지 목록. `label`을 표시하고 `code`는 필터 재요청에 쓴다"),
        optCodeField(
            "data.content[].conditions[].code", LISTING_CONDITION_CODES, "조건의 언어 무관 서버 코드"),
        optField("data.content[].conditions[].label", JsonFieldType.STRING, "조건 배지 표시 문구(사용자 언어)"),
        field("data.markers", JsonFieldType.ARRAY, "현재 페이지 매물의 지도 마커. 0건이면 빈 배열"),
        optField(
            "data.markers[].listingId",
            JsonFieldType.STRING,
            "마커가 가리키는 매물 식별자 — `content[]`와 같은 값이다"),
        optField("data.markers[].lat", JsonFieldType.NUMBER, "마커 위도(WGS84)"),
        optField("data.markers[].lng", JsonFieldType.NUMBER, "마커 경도(WGS84)"));
  }

  /** 오프셋 페이지 메타(api-design-guide §4-1). */
  public static List<FieldDescriptor> pageFields() {
    return List.of(
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호(0-base)"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "전체 건수(0건이면 0)"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수(0건이면 0)"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"));
  }

  /** 추천 페이지 조회 query 파라미터. */
  public static ParameterDescriptor[] recommendationQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 `0`, 0 이상)"),
      parameterWithName("size").optional().description("페이지 크기(기본 `20`, 1~100)"),
      optCodeParam(
          "sort",
          RECOMMENDATION_SORT_VALUES,
          "정렬 — `키,방향` 한 문자열. 허용 키는 `recommended`·`price`·`distance`, 방향은 `asc`·`desc`(기본 `recommended,desc`)")
    };
  }

  /** 진단 이력 query 파라미터. */
  public static ParameterDescriptor[] historyQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 `0`, 0 이상)"),
      parameterWithName("size").optional().description("페이지 크기(기본 `20`, 1~100)"),
      optCodeParam(
          "sort",
          HISTORY_SORT_VALUES,
          "정렬 — `키,방향` 한 문자열. 허용 키는 `submittedAt` 하나이고 방향은 `asc`·`desc`(기본 `submittedAt,desc`)")
    };
  }

  /** 진단 문항 표(흐름 description 공용). 마크다운 표가 그대로 렌더된다. */
  public static final String QUESTION_TABLE =
      """
      | 단계 | `field` | `select.type` | 선택지(`options[].code`) |
      | --- | --- | --- | --- |
      | ① 지역 | `region` | `SINGLE` | `SEOUL` · `BUSAN` · `GYEONGGI` |
      | ② 입국 목적 | `purpose` | `SINGLE` | `STUDY` · `NON_STUDY` |
      | ③ 대학(`STUDY`) | `university` | `SINGLE` | `HUFS_KHU_KOREA` · `SKKU_SUNGSHIN` · `SNU_CAU_SOONGSIL` · `HONGIK_YONSEI_EWHA` · `KONKUK_SEJONG_HYU` · `ETC` |
      | ③ 지역구(`NON_STUDY`) | `district` | `SINGLE` | `GURO_GU` · `YEONGDEUNGPO_GU` · `GEUMCHEON_GU` · `GWANAK_GU` · `DONGDAEMUN_GU` · `ETC` |
      | ④ 주거 조건 | `conditions` | `MULTI`(최대 3개) | `MOVE_IN_NOW` · `FEMALE_ONLY` · `MEALS_INCLUDED` · `DOUBLE_ROOM` · `PRIVATE_BATH` · `ENGLISH_OK` · `ADDRESS_REGISTRATION` · `NO_MAINT_FEE` |
      | ⑤ 월세 범위 | `monthlyRent` | `NUMBER_RANGE` | 없음(빈 배열) — `min`·`max` 두 숫자를 입력받는다 |
      | ⑥ ARC 발급 | `arcStatus` | `SINGLE` | `ARC_ISSUED` · `NO_ARC` |
      """;

  /** 예시 응답의 선택지가 테스트 시드라 실제 목록과 다르다는 주의(모든 문항 스니펫 공용). */
  public static final String SEED_NOTE =
      "예시 응답의 `options[]`는 문서 테스트가 심은 축약 시드라 실제 운영 목록보다 짧다."
          + " 실제 허용 코드는 위 표와 스키마 탭의 `enum` 목록이 정본이다.";

  /** 표시 언어 규칙(문항·라벨 번역 공용). */
  public static final String LANGUAGE_NOTE =
      "표시 문자열(`question`·`options[].label`)만 사용자 표시 언어로 번역되고 `code`는 언어와 무관하게 같다."
          + " 언어는 `users.lang`(미설정 시 `en`)을 따르며 미지원 언어는 `en`으로 폴백한다.";

  // ---------------------------------------------------------------------------------------------
  // ---------------------------------------------------------------------------------------------

  // ---------------------------------------------------------------------------------------------
  // ---------------------------------------------------------------------------------------------

  /**
   * 단계 답 요청 바디. 단일/다중/월세 범위 세 형태가 같은 오퍼레이션이라 <b>기술자를 한 벌로 합친다</b> — 같은 {@code (path, method,
   * status)}에 서로 다른 기술자를 쓰면 {@code (path, type)} dedup에서 한쪽이 조용히 사라진다.
   */
  public static List<FieldDescriptor> answerRequestFields() {
    return List.of(
        codeField(
            "field",
            QUESTION_FIELD_CODES,
            "답을 적용할 문항 필드명 — 직전 응답의 `data.question.field`를 그대로 싣는다."
                + " 지역 0건 예외질문 응답은 `regionRetry`다"),
        optField(
            "code",
            JsonFieldType.STRING,
            "단일 선택(`SINGLE`) 답의 코드. 선택지 `options[].code` 중 하나이며, 다중·범위 단계에서는 보내지 않는다"),
        optCodeArrayField(
            "codes",
            SELECTABLE_CONDITION_CODES,
            "다중 선택(`MULTI`) 답의 코드 집합 — ④ `conditions` 전용이며 서로 다른 코드 최대 3개."
                + " 같은 코드를 여러 번 실어도 에러가 아니라 하나로 합쳐진 뒤 개수를 센다."
                + " 파생 조건 `NO_ARC`는 ⑥에서 서버가 만들므로 여기서 고를 수 없다"),
        optField("min", JsonFieldType.NUMBER, "⑤ `monthlyRent` 월세 하한(KRW 정수, 0 이상이고 `max` 이하)"),
        optField("max", JsonFieldType.NUMBER, "⑤ `monthlyRent` 월세 상한(KRW 정수, `min` 이상)"));
  }

  // ---------------------------------------------------------------------------------------------
  // ---------------------------------------------------------------------------------------------

  public static final String HISTORY_SUMMARY = "내 진단 이력 목록";

  public static final String HISTORY_DESCRIPTION =
      """
      내가 확정한 진단 이력을 오프셋 페이지네이션으로 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 토큰의 사용자 본인 이력만 조회된다.

      **응답 주의사항**

      - 확정(`COMPLETED`) 진단만 나온다. 진행 중(`IN_PROGRESS`)과 v2 폐기 기록(`DISCARDED`)은 빠진다.
      - 게스트가 v2로 만든 진단은 이 목록의 대상이 아니다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `page`/`size` 범위 위반, 허용되지 않은 `sort` 키 또는 방향 |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      """;

  public static final String[] HISTORY_400 = {"INVALID_INPUT"};
  public static final String[] HISTORY_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  public static List<FieldDescriptor> historyResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(field("data.content", JsonFieldType.ARRAY, "확정 진단 목록(현재 페이지). 이력이 없으면 빈 배열"));
    fields.addAll(diagnosisSummaryFields("data.content[]."));
    fields.add(
        enumField("data.content[].status", DiagnosisStatus.class, "진단 상태 — 이력에는 `COMPLETED`만 나온다"));
    fields.add(field("data.content[].submittedAt", JsonFieldType.STRING, "확정 시각(ISO-8601 UTC)"));
    fields.addAll(pageFields());
    fields.add(errorNull());
    return List.copyOf(fields);
  }

  // ---------------------------------------------------------------------------------------------
  // ---------------------------------------------------------------------------------------------

  public static final String LATEST_SUMMARY = "최근 진단 단건";

  public static final String LATEST_DESCRIPTION =
      """
      홈 화면의 "진단 시작 / 재진단" 분기를 위해 가장 최근 확정 진단 1건을 조회한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 토큰의 사용자 본인 진단만 조회된다.

      **응답 주의사항**

      - 확정 이력이 없어도 404가 아니라 200이고 `data.completed=false`다. 클라이언트는 이 한 필드로 분기한다.
      - 진행 중(`IN_PROGRESS`) 진단은 대상이 아니다 — 확정된 것만 본다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      """;

  public static final String[] LATEST_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  /**
   * {@code GET /latest} 전용 응답 기술자.
   *
   * <p>이력·상세와 필드 이름이 같지만 <b>헬퍼를 공유하지 않는다</b> — {@code LatestDiagnosisResponse}에는
   * {@code @JsonInclude}가 없어 {@code completed=false}면 요약 10개 필드가 전부 <b>명시적 null</b>로 실린다. 공유하면
   * 이력·상세의 「항상 있다」 계약까지 함께 풀린다.
   *
   * <p>확정 이력 있음({@code diagnosis-latest})·없음({@code diagnosis-latest-not-completed}) 두 스니펫이 <b>이 헬퍼
   * 하나</b>를 쓴다 — 같은 {@code (path, method, status)}라 기술자가 어차피 하나로 접힌다. 그래서 요약 필드는 전부 {@code
   * optional}이고, 두 스니펫이 값 단정({@code isEmpty()} 포함)으로 계약을 되메운다.
   */
  public static List<FieldDescriptor> latestResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.completed",
            JsonFieldType.BOOLEAN,
            "확정 진단 존재 여부. `false`면 아래 요약 필드가 전부 `null`이다(키는 남는다). 클라이언트는 이 한 필드로 분기한다"),
        optField(
            "data.diagnosisId", JsonFieldType.NUMBER, "최근 확정 진단 식별자. `completed=false`면 `null`"),
        optEnumField("data.region", Region.class, "① 지역. `completed=false`면 `null`"),
        optEnumField("data.purpose", Purpose.class, "② 입국 목적. `completed=false`면 `null`"),
        optEnumField(
            "data.university",
            UniversityGroup.class,
            "③ 대학 그룹 — `purpose=STUDY`일 때만 채워진다. `NON_STUDY`이거나 `completed=false`면 `null`"),
        optEnumField(
            "data.district",
            District.class,
            "③ 지역(구) — `purpose=NON_STUDY`일 때만 채워진다. `STUDY`이거나 `completed=false`면 `null`"),
        optCodeArrayField(
            "data.conditions",
            DIAGNOSIS_CONDITION_CODES,
            "주거 조건 코드 목록(④ 선택 + ⑥ 파생 `NO_ARC`). `completed=false`면 빈 배열이 아니라 `null`"),
        optField(
            "data.monthlyRentMin", JsonFieldType.NUMBER, "⑤ 월세 하한(KRW). `completed=false`면 `null`"),
        optField(
            "data.monthlyRentMax", JsonFieldType.NUMBER, "⑤ 월세 상한(KRW). `completed=false`면 `null`"),
        optEnumField("data.arcStatus", ArcStatus.class, "⑥ ARC 발급 상태. `completed=false`면 `null`"),
        optField(
            "data.submittedAt",
            JsonFieldType.STRING,
            "확정 시각(ISO-8601 UTC). `completed=false`면 `null`"),
        errorNull());
  }

  // ---------------------------------------------------------------------------------------------
  // ---------------------------------------------------------------------------------------------

  // ---------------------------------------------------------------------------------------------
  // v2 오퍼레이션 POST /api/v2/diagnoses/start — v2 진단 시작
  // ---------------------------------------------------------------------------------------------

  public static final String V2_START_SUMMARY = "v2 진단 시작";

  public static final String V2_START_DESCRIPTION =
      """
      진단을 처음부터 시작하고 ① 지역 질문을 받는다. 시작 시점은 클라이언트가 정한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다.

      **요청 주의사항**

      - 요청 본문이 없다.
      - 진행 중 세션이 있어도 버리고 새 세션을 만든다 — 다시 시작하면 언제나 ① 지역부터다.
      - 요청에 `X-Guest-Session-Id`를 실어도 무시하고 새 키를 발급한다.

      **응답 주의사항**

      - 게스트로 호출하면 응답 `data.guestSessionId`에 세션 키(`anonymous<uuid>`)가 실린다. **키가 내려오는 유일한 지점**이며 회원 응답에서는 이 필드가 통째로 생략된다.
      """
          + "- "
          + LANGUAGE_NOTE
          + " 게스트는 `users` 행이 없어 `en` 고정이다."
          + """

      """
          + "- "
          + SEED_NOTE
          + """

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 — 게스트로 조용히 강등하지 않는다. 토큰 미전송·위조는 게스트로 처리하므로 `UNAUTHENTICATED`는 이 엔드포인트에서 발생하지 않는다 |
      """;

  public static final String[] V2_START_401 = {"TOKEN_EXPIRED"};

  /**
   * {@code POST /start} 200 응답. 회원·게스트 두 스니펫이 같은 헬퍼를 쓴다 — 다른 점은 {@code guestSessionId} 하나뿐이고, 그 필드는
   * 회원 응답에서 <b>값이 null이 아니라 키 자체가 생략</b>된다({@code NON_NULL}).
   */
  public static List<FieldDescriptor> startResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(
        codeField(
            "data.resultCode",
            START_RESULT_CODES,
            "결과코드 — `/start`는 언제나 `NEXT_QUESTION`이다(다른 코드로 갈 수 없다)"));
    fields.addAll(questionFields(false));
    fields.add(
        optField(
            "data.guestSessionId",
            JsonFieldType.STRING,
            "게스트 세션 키(`anonymous<uuid>`) — 비회원 `/start` 응답에만 실린다."
                + " 회원 응답에서는 값이 null이 아니라 **필드 자체가 생략**된다."
                + " 클라이언트는 이 값을 보관했다가 이후 `/next`·추천 조회에 `X-Guest-Session-Id` 헤더로 에코한다"));
    fields.add(errorNull());
    return List.copyOf(fields);
  }

  // ---------------------------------------------------------------------------------------------
  // v2 오퍼레이션 POST /api/v2/diagnoses/next — v2 문항 답 적용
  // ---------------------------------------------------------------------------------------------

  public static final String V2_NEXT_SUMMARY = "v2 문항 답 적용";

  public static final String V2_NEXT_DESCRIPTION =
      """
      현재 문항의 답 1개를 적용하고 서버가 정한 다음 결과를 결과코드로 받는다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다. 회원은 이 토큰으로 진행 세션을 찾는다.
      - `X-Guest-Session-Id` — 게스트는 이 헤더로 진행 세션을 찾는다. `POST /api/v2/diagnoses/start` 응답의 `guestSessionId`를 그대로 되돌려보낸다.

      **요청 주의사항**

      - `step`은 클라이언트가 지정하지 않는다. 서버가 낸 문항의 `field`를 그대로 요청 `field`에 싣는다.
      - 단계별로 보낼 `field`와 선택지는 아래와 같다.

      """
          + QUESTION_TABLE
          + """

      **응답 주의사항**

      - `resultCode`가 다음 행동을 정한다.

      | `resultCode` | 의미 | 실리는 필드 |
      | --- | --- | --- |
      | `NEXT_QUESTION` | 다음 질문이 남음. ① 지역 0건 예외질문(`field=regionRetry`)도 이 코드다 | `question` |
      | `COMPLETED` | 마지막 슬롯(⑥ `arcStatus`)까지 답해 서버가 자동 확정 | `diagnosisId` |
      | `RESTART` | 지역 예외질문에 "예"(`YES`) — 이 진단은 확정되지 않고 끝난다. 이어서 답할 수 없고 `POST /start`로 ① 지역부터 다시 시작한다 | 없음 |
      | `TERMINATED` | 지역 예외질문에 "아니오"(`NO`) — 진단 종료. 에러가 아니라 정상 결과이며, 확정되지 않아 `diagnosisId`가 없고 그때까지 답한 내용도 다시 조회할 수 없다 | 없음 |

      - `COMPLETED`에도 추천 매물은 실리지 않는다. 클라이언트가 `diagnosisId`로 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`를 별도 호출한다.
      - 매칭 0건은 이 응답이 아니라 그 추천 응답의 `resultCode=NO_MATCH`로 드러난다.
      - ① 지역 답 직후 그 지역 매물이 0건이면 서버가 `field=regionRetry` 예외질문(`YES`/`NO`)을 끼워 넣는다 — 다른 단계에서는 예외질문이 끼어들지 않는다.
      """
          + "- "
          + LANGUAGE_NOTE
          + """

      """
          + "- "
          + SEED_NOTE
          + """

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `DIAGNOSIS_SESSION_NOT_FOUND` | 진행 중 세션 없이 호출(앱 재시작·터미널 이후 재전송·게스트 키 누락/불일치) → `POST /start`로 복구 |
      | 400 | `INVALID_INPUT` | `field` 없음, 현재 문항과 다른 `field`, 미정의 코드, `regionRetry`가 `YES`/`NO` 아님 |
      | 400 | `MALFORMED_REQUEST` | 본문 JSON 해석 불가(검증 이전) |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 — 토큰 미전송·위조는 게스트로 처리하므로 `UNAUTHENTICATED`는 발생하지 않는다 |
      """;

  public static final String[] V2_NEXT_400 = {
    "DIAGNOSIS_SESSION_NOT_FOUND", "INVALID_INPUT", "MALFORMED_REQUEST"
  };
  public static final String[] V2_NEXT_401 = {"TOKEN_EXPIRED"};

  /** {@code POST /next} 요청 바디 — 해당하는 쪽만 채우고 나머지는 보내지 않는다. */
  public static List<FieldDescriptor> nextRequestFields() {
    return List.of(
        codeField(
            "field",
            QUESTION_FIELD_CODES,
            "현재 문항의 제출 필드명 — 서버가 직전에 낸 `question.field`와 같아야 한다(다르면 `INVALID_INPUT`)"),
        optField(
            "code",
            JsonFieldType.STRING,
            "단일 선택(`SINGLE`) 답의 코드. 선택지 `options[].code` 중 하나이며,"
                + " ① 지역 0건 예외질문(`regionRetry`)은 `YES` 또는 `NO`다"),
        optCodeArrayField(
            "codes",
            SELECTABLE_CONDITION_CODES,
            "다중 선택(`MULTI`) 답의 코드 집합 — ④ `conditions` 전용이며 최대 3개·중복 불가."
                + " 파생 조건 `NO_ARC`는 ⑥에서 서버가 만들므로 여기서 고를 수 없다"),
        optField("min", JsonFieldType.NUMBER, "⑤ `monthlyRent` 월세 하한(KRW 정수, 0 이상이고 `max` 이하)"),
        optField("max", JsonFieldType.NUMBER, "⑤ `monthlyRent` 월세 상한(KRW 정수, `min` 이상)"));
  }

  /**
   * {@code POST /next} 200 응답. {@code resultCode} 네 갈래의 스니펫이 <b>모두 이 헬퍼 하나</b>를 쓴다 — 같은 {@code
   * (path, method, status)}라 기술자가 어차피 하나로 접히므로 여러 벌을 두면 승자가 파일 순회 순서에 좌우된다. 갈래마다 사라지는 payload는
   * {@code optional}이고, 각 스니펫이 {@code doesNotExist()}로 「이 케이스에는 없다」를 못 박는다.
   */
  public static List<FieldDescriptor> nextResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(
        enumField(
            "data.resultCode",
            FlowResultCode.class,
            "다음에 할 일을 알리는 결과코드. `NEXT_QUESTION`은 `question`을, `COMPLETED`는 `diagnosisId`를 함께 싣고"
                + " `RESTART`·`TERMINATED`는 payload 없이 이 코드 하나만 온다"));
    fields.addAll(questionFields(true));
    fields.add(
        optField(
            "data.diagnosisId",
            JsonFieldType.NUMBER,
            "확정된 진단 식별자 — `resultCode=COMPLETED`일 때만 실린다."
                + " 그 외 결과코드에서는 값이 null이 아니라 **필드 자체가 생략**된다."
                + " 이 id로 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`를 별도 호출한다"));
    fields.add(errorNull());
    return List.copyOf(fields);
  }

  /**
   * 흐름 응답의 {@code question} payload.
   *
   * @param optional {@code true}면 {@code NEXT_QUESTION}이 아닌 갈래에서 통째로 생략되는 {@code /next}용
   */
  public static List<FieldDescriptor> questionFields(boolean optional) {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(
        describe(
            optional,
            "data.question",
            JsonFieldType.OBJECT,
            "다음 문항 1개 — `resultCode=NEXT_QUESTION`일 때 실린다."
                + " `/next`의 다른 결과코드(`COMPLETED`·`RESTART`·`TERMINATED`)에서는"
                + " 값이 null이 아니라 **필드 자체가 생략**된다(`/start`는 언제나 이 필드가 있다)"));
    fields.add(
        describe(
            optional,
            "data.question.step",
            JsonFieldType.NUMBER,
            "문항의 단계 번호(1~6). ① 지역 0건 예외질문(`regionRetry`)도 ① 지역과 같은 `1`로 내려오므로,"
                + " 이 번호만으로 진행률을 세면 단계가 되돌아간 것처럼 보인다"));
    fields.add(
        codeFieldMaybeOptional(
            optional,
            "data.question.field",
            QUESTION_FIELD_CODES,
            "제출 필드명 — 다음 `/next` 요청의 `field`에 그대로 싣는다."
                + " `regionRetry`는 ① 지역 0건 예외질문이며 `YES`/`NO`로 답한다"));
    fields.add(
        describe(
            optional,
            "data.question.question",
            JsonFieldType.STRING,
            "사용자 표시 언어로 번역된 질문 문구(미지원 언어는 영어 폴백, 게스트는 영어)"));
    fields.add(
        codeFieldMaybeOptional(
            optional,
            "data.question.select.type",
            SELECT_TYPE_CODES,
            "선택 방식 — `SINGLE`은 1택, `MULTI`는 다중, `NUMBER_RANGE`는 선택지가 아니라 `min`·`max` 숫자 2개 입력"));
    fields.add(
        describe(
            optional,
            "data.question.select.max",
            JsonFieldType.NUMBER,
            "최대 선택 개수 — ④ `MULTI`는 3, 그 외는 1"));
    fields.add(
        describe(
            optional,
            "data.question.options",
            JsonFieldType.ARRAY,
            "선택지 목록. ⑤ `monthlyRent`(`NUMBER_RANGE`)만 빈 배열이며 클라이언트가 숫자 입력 2개를 그린다"));
    fields.add(
        optField(
            "data.question.options[].code",
            JsonFieldType.STRING,
            "선택지 코드 — 언어와 무관하게 같고 다음 `/next` 요청의 `code`/`codes`에 그대로 싣는다."
                + " `NUMBER_RANGE` 문항은 배열이 비어 원소가 없다"));
    fields.add(optField("data.question.options[].label", JsonFieldType.STRING, "번역된 선택지 표시 라벨"));
    return fields;
  }

  private static FieldDescriptor describe(
      boolean optional, String path, JsonFieldType type, String description) {
    return optional ? optField(path, type, description) : field(path, type, description);
  }

  private static FieldDescriptor codeFieldMaybeOptional(
      boolean optional, String path, List<String> allowedValues, String description) {
    return optional
        ? optCodeField(path, allowedValues, description)
        : codeField(path, allowedValues, description);
  }

  /**
   * 게스트 세션 키 에코 요청 헤더 서술자({@code /next}·추천 조회 공용).
   *
   * <p><b>{@code optional()}이 필수다</b> — {@code HeaderDescriptorWithType.fromHeaderDescriptor}가
   * {@code required = !optional}로 옮기므로, 빠뜨리면 회원도 반드시 보내야 하는 헤더로 문서화된다(#151 실측 버그).
   */
  public static HeaderDescriptor guestSessionHeader() {
    return headerWithName(GUEST_SESSION_HEADER)
        .optional()
        .description(
            "게스트 세션 키(`anonymous<uuid>`) — `/start` 응답의 `guestSessionId`를 그대로 실어 보낸다."
                + " **회원은 보내지 않는다**(`Authorization` 토큰으로 식별하며 실려 와도 무시한다)."
                + " 게스트가 빠뜨렸거나 남의 키면 세션을 못 찾아 `400 DIAGNOSIS_SESSION_NOT_FOUND`다");
  }

  // ---------------------------------------------------------------------------------------------
  // v2 오퍼레이션 GET /api/v2/diagnoses/{diagnosisId}/recommendations — v2 진단 결과 추천 매물
  // ---------------------------------------------------------------------------------------------

  public static final String V2_RECOMMENDATIONS_SUMMARY = "v2 진단 결과 추천 매물";

  public static final String V2_RECOMMENDATIONS_DESCRIPTION =
      """
      v2에서 확정된 진단 조건에 맞는 매물 카드와 지도 마커를 조회한다. 조회 시점·페이지·정렬은 클라이언트가 정한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다. 회원은 이 토큰으로 소유권을 증명한다.
      - `X-Guest-Session-Id` — 게스트는 이 헤더로 소유권을 증명한다.
      - 진단을 만든 쪽만 조회할 수 있다 — 게스트가 만든 진단은 회원 토큰으로, 회원이 만든 진단은 게스트 키로 열리지 않는다.

      **응답 주의사항**

      - `title`과 `label`은 사용자 표시 언어로 선택되어 온다(게스트는 `en` 고정).
      - **조정 제안(`suggestions`) 필드가 없다** — 매칭 사유만 `resultCode`로 준다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `page`/`size` 범위 위반, 허용되지 않은 `sort` 키 또는 방향 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 — 토큰 미전송·위조는 게스트로 처리하므로 `UNAUTHENTICATED`는 발생하지 않는다 |
      | 403 | `FORBIDDEN` | 타인 소유 진단, 게스트↔회원 교차 조회(양방향), 신원 없는 요청(토큰도 게스트 키도 보내지 않음) |
      | 404 | `DIAGNOSIS_NOT_FOUND` | 진단이 존재하지 않거나 폐기 기록 |
      """;

  public static final String[] V2_RECOMMENDATIONS_400 = {"INVALID_INPUT"};
  public static final String[] V2_RECOMMENDATIONS_401 = {"TOKEN_EXPIRED"};
  public static final String[] V2_RECOMMENDATIONS_403 = {"FORBIDDEN"};
  public static final String[] V2_RECOMMENDATIONS_404 = {"DIAGNOSIS_NOT_FOUND"};

  /**
   * {@code GET /{id}/recommendations} 200 응답. MATCHED·NO_MATCH 두 스니펫이 같은 헬퍼를 쓴다 — 0건이면 배열이 비어 원소가
   * 없으므로 원소 필드는 {@code optional}이고, 각 스니펫이 값 단정/{@code doesNotExist()}로 계약을 되메운다.
   */
  public static List<FieldDescriptor> v2RecommendationFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(
        enumField(
            "data.resultCode",
            RecommendationResultCode.class,
            "매칭 결과 — 현재 페이지의 `content`가 비었으면 `NO_MATCH`, 아니면 `MATCHED`다(에러가 아니며 조정 제안은 주지 않는다)."
                + " 마지막 페이지를 넘겨 요청해도 `NO_MATCH`이므로, 진짜 0건인지는 `page.totalElements`로 가린다"));
    fields.addAll(recommendationContentFields());
    fields.addAll(pageFields());
    fields.add(errorNull());
    return List.copyOf(fields);
  }

  public static ParameterDescriptor[] v2DiagnosisIdPathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("diagnosisId")
          .description("`resultCode=COMPLETED` 응답으로 받은 확정 진단 식별자(본인 신원 소유)")
    };
  }

  // ---------------------------------------------------------------------------------------------
  // 오퍼레이션 GET /api/v2/diagnoses/{diagnosisId}/recommendations/map — 진단 추천 전체 마커
  // ---------------------------------------------------------------------------------------------

  public static final String V2_RECOMMENDATION_MAP_SUMMARY = "v2 진단 추천 전체 마커";

  public static final String V2_RECOMMENDATION_MAP_DESCRIPTION =
      """
      확정 진단의 추천 매물을 **페이지 없이 마커만** 조회한다. 매칭 조건은 `GET /api/v2/diagnoses/{diagnosisId}/recommendations`와 동일하며, 같은 진단이면 같은 매물 집합이다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 선택. 없으면 게스트로 응답한다.
      - `X-Guest-Session-Id` — 게스트는 이 헤더로 소유권을 증명한다.
      - 진단을 만든 쪽만 조회할 수 있다 — 게스트↔회원 교차 조회는 양방향 모두 거절된다.

      **응답 주의사항**

      - 쿼리 파라미터가 없다. 페이지도 정렬도 받지 않는다.
      - `markers`는 **최대 500건**이다. 조건에 맞는 매물이 그보다 많으면 **잘라서** 준다(에러가 아니다).
      - `total`은 절단 전 전체 매물 수다. `markers` 길이보다 크면 일부만 실린 것이다. `count`와 조회가 별개 질의라 경계에서 1~2건 오차가 날 수 있는 참고값이다.
      - 잘릴 때 남는 집합은 추천 기본 정렬(찜 수·최근 수정) 상위다 — `sort=price`로 조회한 목록과 다른 집합일 수 있다.
      - **확정된 진단만 조회된다.** 폐기 기록과 아직 끝내지 않은 진단은 조회되지 않는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 — 토큰 미전송·위조는 게스트로 처리하므로 `UNAUTHENTICATED`는 발생하지 않는다 |
      | 403 | `FORBIDDEN` | 타인 소유 진단, 게스트↔회원 교차 조회(양방향), 신원 없는 요청 |
      | 404 | `DIAGNOSIS_NOT_FOUND` | 진단이 존재하지 않거나, 폐기 기록이거나, 아직 확정되지 않음 |

      `INVALID_INPUT`(400)은 발생하지 않는다 — 쿼리 파라미터를 받지 않는다. 다만 `diagnosisId`에 숫자가 아닌 값이 오면 공통 `MALFORMED_REQUEST`(400)다.
      """;

  public static final String[] V2_RECOMMENDATION_MAP_401 = {"TOKEN_EXPIRED"};
  public static final String[] V2_RECOMMENDATION_MAP_403 = {"FORBIDDEN"};
  public static final String[] V2_RECOMMENDATION_MAP_404 = {"DIAGNOSIS_NOT_FOUND"};

  /**
   * {@code GET /{id}/recommendations/map} 200 응답. 매칭 있음·0건·게스트 <b>세 스니펫이 이 헬퍼 하나</b>를 쓴다 — 같은
   * {@code (path, 200)}에 필드 기술자를 두 벌 두면 스키마가 둘 생기고 파일 순회 순서에 따라 하나가 조용히 버려진다.
   *
   * <p>그래서 배열 원소는 {@code optional}이다(0건 스니펫에는 원소가 없다). 낮춘 대가는 0건 스니펫의 {@code
   * jsonPath("$.data.markers[0]").doesNotExist()} 단정이 되메운다.
   */
  public static List<FieldDescriptor> v2RecommendationMapFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.markers", JsonFieldType.ARRAY, "추천 조건에 맞는 매물의 지도 마커. 0건이면 빈 배열이며 최대 500개다"),
        optField("data.markers[].listingId", JsonFieldType.STRING, "마커가 가리키는 매물 식별자"),
        optField("data.markers[].lat", JsonFieldType.NUMBER, "마커 위도(WGS84)"),
        optField("data.markers[].lng", JsonFieldType.NUMBER, "마커 경도(WGS84)"),
        field(
            "data.total",
            JsonFieldType.NUMBER,
            "추천 조건에 맞는 전체 매물 수(절단 전). `markers` 길이보다 크면 서버 상한에 걸려 일부만 실린 것이다"),
        errorNull());
  }
}
