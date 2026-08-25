package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.codeField;
import static com.kohere.docs.ApiDocsFields.enumField;
import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static com.kohere.docs.ApiDocsFields.optCodeField;
import static com.kohere.docs.ApiDocsFields.optEnumField;
import static com.kohere.docs.ApiDocsFields.optField;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;

import com.kohere.user.domain.Gender;
import com.kohere.user.domain.Occupation;
import com.kohere.user.domain.UserStatus;
import com.kohere.user.domain.UserType;
import com.kohere.user.domain.VisaType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;

/**
 * {@code Users} 태그(프로필 조회·수정·탈퇴 + 내 차단 목록·해제)의 문구·에러코드 상수와 필드 기술자(#151). 회원 프로필 응답은 세입자·임대인
 * <b>합집합</b>이다.
 *
 * <p><b>왜 공용인가</b> — {@code GET /users/me}의 200 스니펫은 두 파일에서 캡처된다({@code AuthOnboardingDocsTest}의
 * {@code user-get-me}(세입자), {@code LandlordOnboardingDocsTest}의 {@code user-get-me-landlord}(임대인)).
 * 같은 {@code (path, method, status)}의 필드 기술자는 합집합이 아니라 {@code (path, type)} 기준 dedup·last-wins라, 두
 * 파일이 서로 다른 기술자를 쓰면 한쪽의 {@code optional}·enum이 조용히 사라지고 승자가 파일 순회 순서(로컬 NTFS ↔ CI ext4)에 좌우된다. 그래서
 * 양쪽이 <b>이 클래스의 같은 메서드</b>를 호출한다.
 *
 * <p><b>역할 전용 필드는 전부 optional</b> — 응답 DTO가 NON_NULL 직렬화라 값이 없으면 「null」이 아니라 <b>키 자체가 사라진다</b>. 문서가
 * 그만큼 느슨해지므로 호출부는 그 필드가 없어야 하는 케이스에 {@code jsonPath(...).doesNotExist()} 단정을 함께 둔다.
 *
 * <p>대응 스펙: docs/api/specs/01-auth-onboarding.md §5·§5-2(온보딩 응답의 {@code data.user})·§8(GET
 * /users/me)·§9(PATCH /users/me)·§10(DELETE /users/me)·§11·§12(차단 목록·해제).
 */
public final class UserDocsFields {

  private UserDocsFields() {}

  /**
   * 표시 언어 코드. {@code Language} enum이 있지만 와이어 값이 상수명({@code EN})이 아니라 ISO 639-1 <b>소문자</b>라 {@code
   * Enum.toString()}을 값으로 쓰는 {@code enumField}를 쓸 수 없다.
   */
  public static final List<String> LANG_CODES = List.of("en", "ko", "ja");

  /**
   * 국적 코드(ISO 3166-1 alpha-2). enum이 아니라 {@code countries} 테이블 카탈로그이며 <b>값 목록을 내려주는 API가 없다</b> —
   * 앱이 조회할 곳이 없으므로 여기서 전량 나열한다. 시드는 {@code
   * src/main/resources/db/migration/V4__countries_reference.sql}.
   */
  public static final List<String> COUNTRY_CODES =
      List.of(
          "KR", "CN", "VN", "US", "UZ", "TH", "PH", "NP", "MN", "JP", "ID", "KH", "RU", "IN", "MM");

  /** 토큰 타입. 서버가 상수로 채우며 {@code Bearer} 외의 값은 없다. */
  public static final List<String> TOKEN_TYPES = List.of("Bearer");

  /** 국적 코드 설명 — 요청·응답 양쪽에서 같은 문구를 쓴다. */
  private static final String COUNTRY_NOTE =
      "국적 ISO 3166-1 alpha-2 코드. 값 목록을 내려주는 API가 없어 여기 나열한 15개가 지원 코드의 전부다";

  // ---- GET /api/v1/users/me 오퍼레이션 문구(두 파일이 공유) ----

  public static final String ME_SUMMARY = "내 프로필 조회";

  public static final String ME_DESCRIPTION =
      """
      인증된 본인의 프로필 전체를 반환한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료).

      **응답 주의사항**

      - 응답 필드는 `userType`으로 갈린다. 스키마에 `(세입자만)`·`(임대인만)`이 붙은 필드는 다른 역할의 응답에서 값이 null이 아니라 **필드 자체가 생략**된다.
      - 역할이 맞아도 세입자 `occupation`·`lang`은 미설정이면 같은 방식으로 생략된다 — `lang`이 없으면 표시는 `en` 폴백.
      - `country`·`countryName`·`countryFlag`는 임대인도 받는다 — 서버가 `KR`을 고정 부여한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(`PENDING`·`TERMS_AGREED`) 토큰으로 접근 |
      | 404 | `USER_NOT_FOUND` | 사용자가 `WITHDRAWN`이거나 삭제되어 없음 |
      """;

  public static final String[] ME_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] ME_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] ME_404 = {"USER_NOT_FOUND"};

  // ---- PATCH /api/v1/users/me 오퍼레이션 문구(두 파일이 공유) ----
  // GET과 마찬가지로 세입자 예시(AuthOnboardingDocsTest의 user-patch-me)와 임대인 예시
  // (LandlordOnboardingDocsTest의 user-patch-me-landlord)가 같은 오퍼레이션이라 문구·요청 필드 기술자를 공유한다.

  public static final String PATCH_ME_SUMMARY = "내 프로필 부분 수정";

  public static final String PATCH_ME_DESCRIPTION =
      """
      보낸 필드만 바뀐다. 필드를 빼도 `null`을 보내도 기존 값이 남으므로 값을 지울 수는 없다. 응답은 수정된 프로필 전체이며 `GET /users/me`와 동일 스키마다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료).

      **요청 주의사항**

      - 수정 가능 필드가 `userType`으로 갈린다 — 임대인은 `name`·`phoneNumber`·`marketingAgreed`만 바꿀 수 있고, `lang`은 `ko` 고정이라 바꿀 수 없으며 `birthDate`는 조회 전용이다.
      - 임대인 `phoneNumber` 변경은 새 번호를 SMS로 재인증한 뒤에만 반영된다.


      **응답 주의사항**

      - 역할 전용 필드(`(세입자만)`·`(임대인만)`)는 다른 역할의 응답에서 값이 null이 아니라 **필드 자체가 생략**된다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `gender`·`occupation`·`visaType`가 허용 목록 밖, `birthDate`가 `YYYY-MM-DD` 형식이 아니거나 미래 날짜, `country` 미존재, `lang` 미지원 코드 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(`PENDING`·`TERMS_AGREED`) 토큰으로 접근 |
      | 404 | `USER_NOT_FOUND` | 사용자가 `WITHDRAWN`이거나 삭제되어 없음 |
      | 422 | `AUTH_PHONE_NOT_VERIFIED` | (임대인) 새 `phoneNumber`가 SMS 재인증되지 않았거나 검증한 번호와 불일치 |
      """;

  public static final String[] PATCH_ME_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] PATCH_ME_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] PATCH_ME_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] PATCH_ME_404 = {"USER_NOT_FOUND"};
  public static final String[] PATCH_ME_422 = {"AUTH_PHONE_NOT_VERIFIED"};

  // ---- DELETE /api/v1/users/me 오퍼레이션 문구 ----

  public static final String WITHDRAW_SUMMARY = "회원 탈퇴";

  public static final String WITHDRAW_DESCRIPTION =
      """
      본인 계정을 탈퇴 처리한다(`WITHDRAWN`). 되돌릴 수 없다 — 이름·닉네임·이메일·연락처·생년월일·국적·표시 언어·직업·비자정보가 즉시 삭제되고 복구할 수단이 없으며, 모든 기기의 세션이 그 자리에서 끊긴다. 같은 소셜 계정으로 다시 로그인해도 이전 계정으로 돌아오지 않고 데이터가 없는 새 계정으로 가입된다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태와 무관하게 허용한다(`PENDING`·`TERMS_AGREED`·`ACTIVE`).
      - 온보딩 미완료(`PENDING`·`TERMS_AGREED`) 사용자도 탈퇴할 수 있다 — 온보딩을 끝내고 다시 호출할 필요가 없다.

      **응답 주의사항**

      - 성공 응답에는 본문이 없다(204).
      - Apple로 가입한 계정은 Apple 쪽 앱 연동까지 함께 해제된다 — Apple 장애로 해제가 실패해도 탈퇴는 그대로 완료되고 에러를 돌려주지 않는다.
      - `Set-Cookie: refreshToken=; Max-Age=0; Path=/api/v1/auth` 를 함께 내려 **임대인 웹의 refresh 쿠키를 브라우저에서도 지운다**.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 404 | `USER_NOT_FOUND` | 사용자가 삭제되어 없음 — 탈퇴 후 같은 토큰으로 프로필을 조회할 때도 같다 |
      | 409 | `USER_ALREADY_WITHDRAWN` | 이미 `WITHDRAWN`된 사용자가 탈퇴를 재요청 |
      """;

  public static final String[] WITHDRAW_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] WITHDRAW_404 = {"USER_NOT_FOUND"};
  public static final String[] WITHDRAW_409 = {"USER_ALREADY_WITHDRAWN"};

  // ---- GET /api/v1/users/me/blocks 오퍼레이션 문구 ----

  public static final String BLOCKS_LIST_SUMMARY = "내 차단 목록 조회";

  public static final String BLOCKS_LIST_DESCRIPTION =
      """
      내가 차단한 상대 목록을 페이지로 조회한다.

      차단은 예약 문맥(`POST /api/v1/bookings/{bookingId}/block`)에서만 생성된다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `page`가 음수이거나 `size`가 1~100 밖 — 조용히 보정하지 않고 거절한다 |
      | 400 | `MALFORMED_REQUEST` | `page`·`size`가 정수가 아님(쿼리 파라미터 타입 불일치) |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      """;

  public static final String[] BLOCKS_LIST_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] BLOCKS_LIST_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] BLOCKS_LIST_403 = {"AUTH_ONBOARDING_REQUIRED"};

  // ---- DELETE /api/v1/users/me/blocks/{userId} 오퍼레이션 문구 ----

  public static final String UNBLOCK_SUMMARY = "차단 해제";

  public static final String UNBLOCK_DESCRIPTION =
      """
      차단을 해제한다. 차단하지 않은 상대를 해제해도 204다(멱등).

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `MALFORMED_REQUEST` | 경로의 `userId`가 정수가 아님(경로 변수 타입 불일치) |
      | 401 | `UNAUTHENTICATED` | 토큰 없음 또는 위조 |
      | 401 | `TOKEN_EXPIRED` | 액세스 토큰 만료 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(비 `ACTIVE`) |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 세입자·임대인 기능은 호출할 수 없다 |
      """;

  public static final String[] UNBLOCK_400 = {"MALFORMED_REQUEST"};
  public static final String[] UNBLOCK_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] UNBLOCK_403 = {"AUTH_ONBOARDING_REQUIRED"};

  // ---- 필드 기술자 ----

  /**
   * 회원 프로필 공통 필드(세입자·임대인 합집합). {@code prefix}는 {@code "data."}(GET·PATCH /users/me 응답) 또는 {@code
   * "data.user."}(온보딩 응답의 {@code UserProfileView})다 — 두 DTO는 이 필드 집합을 공유하고, {@code
   * termsOfServiceAgreed}·{@code privacyPolicyAgreed}만 프로필 응답에 더 있다.
   */
  public static List<FieldDescriptor> profileFields(String prefix) {
    return List.of(
        field(prefix + "id", JsonFieldType.NUMBER, "회원 ID"),
        enumField(prefix + "userType", UserType.class, "회원 역할 — 아래 역할 전용 필드의 유무가 이 값으로 갈린다"),
        optField(
            prefix + "name",
            JsonFieldType.STRING,
            "이름(성·이름을 합친 단일 이름). 소셜 로그인 시 provider 값으로 채우며 provider가 주지 않으면 필드 자체가 생략된다"),
        field(prefix + "nickname", JsonFieldType.STRING, "닉네임(서버가 형용사+사물로 배정, 전역 유니크·수정 불가)"),
        optEnumField(prefix + "gender", Gender.class, "성별(세입자만)"),
        field(prefix + "birthDate", JsonFieldType.STRING, "생년월일(YYYY-MM-DD)"),
        codeField(prefix + "country", COUNTRY_CODES, COUNTRY_NOTE + ". 임대인은 서버가 KR로 고정 부여한다"),
        optField(
            prefix + "countryName",
            JsonFieldType.STRING,
            "국가 표시명 — country 코드로 서버가 채운다. 표시 언어와 무관하게 영문 한 가지다(예 South Korea)"),
        optField(prefix + "countryFlag", JsonFieldType.STRING, "국기 이미지 URL(flagcdn.com SVG)"),
        optCodeField(
            prefix + "lang",
            LANG_CODES,
            "표시 언어 ISO 639-1 소문자(UPPER_SNAKE 예외) — 세입자 선택값(미선택 시 표시는 en 폴백), 임대인은 ko 고정"),
        optEnumField(prefix + "occupation", Occupation.class, "직업(세입자만, 온보딩 선택값)"),
        field(
            prefix + "email",
            JsonFieldType.STRING,
            "이메일(소셜 로그인 provider 진본 — 세입자·임대인 공통 보유, 본인 조회라 평문)"),
        optEnumField(prefix + "visaType", VisaType.class, "비자정보(세입자만) — 온보딩 필수라 세입자 응답에는 항상 있다"),
        optField(
            prefix + "phoneNumber",
            JsonFieldType.STRING,
            "연락처(임대인만) — GET/PATCH /users/me는 본인 조회라 평문, 온보딩 응답은 마스킹(010-****-5678)"),
        enumField(
            prefix + "status", UserStatus.class, "회원 상태 — 이 응답에서는 항상 ACTIVE(미완료는 403, 탈퇴는 404)"),
        field(prefix + "marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의 여부"),
        field(prefix + "createdAt", JsonFieldType.STRING, "가입 시각(ISO-8601 UTC)"));
  }

  /**
   * {@code GET /users/me}·{@code PATCH /users/me} 200 응답. 두 오퍼레이션의 응답 스키마는 동일하다(스펙 §9 「수정된 프로필 전체를
   * GET /users/me와 동일 스키마로 반환」).
   */
  public static List<FieldDescriptor> meResponseFields() {
    List<FieldDescriptor> descriptors = new ArrayList<>();
    descriptors.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    descriptors.addAll(profileFields("data."));
    descriptors.add(field("data.termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의 여부"));
    descriptors.add(field("data.privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의 여부"));
    descriptors.add(errorNull());
    return List.copyOf(descriptors);
  }

  /**
   * {@code PATCH /users/me} 요청 본문(세입자·임대인 <b>합집합</b>). 전 필드가 선택이라 역할별로 보내는 부분집합이 다르다 — 응답과 마찬가지로 같은
   * 오퍼레이션의 스니펫이 서로 다른 기술자를 쓰면 {@code (path, type)} dedup에서 한쪽이 조용히 사라지므로 두 파일이 이 메서드를 함께 호출한다.
   */
  public static List<FieldDescriptor> patchRequestFields() {
    return List.of(
        optField(
            "name",
            JsonFieldType.STRING,
            "이름(세입자·임대인 공통 단일 이름, 선택) — 보낸 문자열을 그대로 저장한다. 빈 문자열·공백도 거절하지 않으므로 클라이언트가 걸러야 한다"),
        optEnumField("gender", Gender.class, "성별(세입자만·선택)"),
        optField("birthDate", JsonFieldType.STRING, "생년월일 YYYY-MM-DD(세입자 전용·선택, 과거 날짜만)"),
        optCodeField(
            "country",
            COUNTRY_CODES,
            "국적 ISO 3166-1 alpha-2 코드(세입자만·선택). 값 목록을 내려주는 API가 없어 여기 나열한 15개가 전부다"),
        optEnumField("occupation", Occupation.class, "직업(세입자만·선택)"),
        optEnumField("visaType", VisaType.class, "비자정보(세입자만·선택)"),
        optCodeField(
            "lang", LANG_CODES, "표시 언어 ISO 639-1 소문자(세입자 전용·선택 — 목록 밖 값·빈 문자열은 INVALID_INPUT)"),
        optField(
            "phoneNumber",
            JsonFieldType.STRING,
            "연락처(임대인만·선택) — 휴대폰 번호 형식만 받는다(하이픈 선택, 예 01012345678·010-1234-5678). 형식 위반은 400 INVALID_INPUT."
                + " 서버는 숫자만 남긴 표준형으로 접어 저장·비교하므로 「지금 번호와 같은지」도 하이픈 표기가 아니라 표준형으로 판정한다."
                + " 표준형이 지금 번호와 다르면 그 번호를 SMS로 재인증한 뒤에만 반영되고, 미인증·불일치면 422. 같으면 재인증 없이 통과한다."
                + " 표준형은 계정마다 유일해야 하므로 다른 계정이 쓰는 번호로는 바꿀 수 없다"),
        optField("marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의(선택)"));
  }

  /**
   * 온보딩 응답의 {@code data.user}({@code UserProfileView})와 정식 토큰 3종. 세입자 {@code POST
   * /auth/onboarding}과 임대인 {@code POST /auth/landlord/onboarding}이 같은 응답 타입({@code
   * OnboardingResponse})을 쓴다.
   *
   * <p>{@code data.linked}는 <b>임대인 병합(US-1-15)에서만 true가 될 수 있지만 기술자는 두 오퍼레이션이 공유한다</b> — 같은 응답 타입이라
   * 세입자 응답에도 필드가 나가고, 기술자를 한쪽에만 두면 다른 쪽 스니펫이 "문서화되지 않은 필드"로 실패한다. 그래서 설명 문구가 두 경로를 모두 덮는다.
   */
  public static List<FieldDescriptor> onboardingResponseFields() {
    List<FieldDescriptor> descriptors = new ArrayList<>();
    descriptors.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    descriptors.add(
        field(
            "data.linked",
            JsonFieldType.BOOLEAN,
            "기존 웹 임대인 계정과 병합됐으면 true. true면 data.user와 아래 토큰이 모두 요청에 쓴 계정이 아니라"
                + " 합쳐진 웹 계정 기준이며 그 계정의 id는 data.user.id다 — 요청에 쓴 임시 계정은 삭제됐으므로"
                + " 클라이언트는 반드시 이 응답의 accessToken·refreshToken으로 교체해야 하고, 화면에 표시할 이름·이메일도"
                + " 방금 입력한 값이 아니라 살아남은 계정의 값이다(예: \"기존 웹 계정과 연결되었습니다\" 안내)."
                + " 세입자 온보딩(POST /auth/onboarding)은 매칭 키인 휴대폰 번호를 수집하지 않아 병합 분기 자체가 없어 항상 false다"));
    descriptors.addAll(profileFields("data.user."));
    descriptors.add(codeField("data.tokenType", TOKEN_TYPES, "토큰 타입 — 항상 Bearer"));
    descriptors.add(field("data.accessToken", JsonFieldType.STRING, "access 토큰(JWT)"));
    descriptors.add(field("data.refreshToken", JsonFieldType.STRING, "정식 refresh 토큰(불투명)"));
    descriptors.add(field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(3600)"));
    descriptors.add(errorNull());
    return List.copyOf(descriptors);
  }

  /** {@code GET /users/me/blocks} 페이지 파라미터. */
  public static ParameterDescriptor[] blocksListQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0). 음수는 400"),
      parameterWithName("size").optional().description("페이지 크기(기본 20). 1~100 밖은 400")
    };
  }

  /** {@code GET /users/me/blocks} 200 응답. */
  public static List<FieldDescriptor> blocksListResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부"),
        field("data.content[].userId", JsonFieldType.NUMBER, "차단한 상대 식별자"),
        field(
            "data.content[].name",
            JsonFieldType.STRING,
            "차단한 상대 이름(마스킹 없는 평문). 상대가 탈퇴했거나 이름이 없으면 빈 문자열이며 항목은 그대로 남는다"),
        field("data.content[].blockedAt", JsonFieldType.STRING, "차단 시각(UTC)"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "전체 건수"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"),
        errorNull());
  }

  /** {@code DELETE /users/me/blocks/{userId}} 경로 파라미터. */
  public static ParameterDescriptor[] unblockPathParameters() {
    return new ParameterDescriptor[] {parameterWithName("userId").description("차단을 해제할 상대 식별자")};
  }
}
