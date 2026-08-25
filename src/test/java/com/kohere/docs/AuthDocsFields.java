package com.kohere.docs;

import static com.kohere.docs.ApiDocsFields.codeField;
import static com.kohere.docs.ApiDocsFields.enumField;
import static com.kohere.docs.ApiDocsFields.errorNull;
import static com.kohere.docs.ApiDocsFields.field;
import static com.kohere.docs.ApiDocsFields.optCodeField;
import static com.kohere.docs.ApiDocsFields.optEnumField;
import static com.kohere.docs.ApiDocsFields.optField;
import static com.kohere.docs.UserDocsFields.COUNTRY_CODES;
import static com.kohere.docs.UserDocsFields.LANG_CODES;
import static com.kohere.docs.UserDocsFields.TOKEN_TYPES;

import com.kohere.auth.domain.Provider;
import com.kohere.user.domain.Gender;
import com.kohere.user.domain.Occupation;
import com.kohere.user.domain.UserStatus;
import com.kohere.user.domain.VisaType;
import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;

/**
 * {@code Auth} 태그({@code /api/v1/auth/**} 21개 오퍼레이션)의 문구·에러코드 상수와 요청/응답 필드 기술자(#151).
 *
 * <p><b>왜 한 클래스인가</b> — 같은 태그의 오퍼레이션이 {@code AuthOnboardingDocsTest}(세입자 트랙: 소셜 로그인·약관 동의·이메일
 * 인증·온보딩·재발급·로그아웃) · {@code LandlordOnboardingDocsTest}(임대인 트랙: 연락처 SMS 인증·사업자등록번호 검증·임대인 온보딩) ·
 * {@code WebLandlordAuthDocsTest}(임대인 웹: 가입용 SMS 인증·회원가입·로그인) · {@code
 * WebAccountRecoveryDocsTest}(임대인 웹 계정 복구: 이메일 찾기·비밀번호 재설정) <b>네 파일</b>에 걸쳐 문서화된다.
 * 오퍼레이션(path+method)당 summary/description은 <b>1벌</b>만 두고 그 오퍼레이션의 성공·에러 스니펫이 전부 같은 문자열·같은 태그를 써야
 * 하며(생성기가 같은 {@code (path, method)} 모델의 문구 중 첫 non-blank 하나만 채택한다), 같은 {@code (path, method,
 * status)}의 필드 기술자는 {@code (path, type)} 기준 dedup·last-wins라 승자가 파일 순회 순서에 좌우된다({@link
 * ApiDocsFields} 클래스 주석 참조). 그래서 문구·에러코드 배열·필드 기술자를 태그 단위로 여기 한 벌만 둔다.
 *
 * <p>{@code error} 코드 배열은 <b>오퍼레이션+status 단위</b> 상수다. 대응 스펙: docs/api/specs/01-auth-onboarding.md.
 */
public final class AuthDocsFields {

  private AuthDocsFields() {}

  /**
   * 웹 가입·로그인 응답의 {@code status} 허용값. {@link UserStatus} 전체가 아니라 <b>ACTIVE 하나</b>인 것이 계약이다(스펙
   * §1-3·§1-4) — 웹 가입은 한 트랜잭션으로 완주해 {@code PENDING}·{@code TERMS_AGREED}로 응답할 자리가 없다. {@code
   * enumField}로 적으면 도달할 수 없는 값 셋이 문서에 남는다.
   */
  private static final List<String> WEB_ACCOUNT_STATUSES = List.of("ACTIVE");

  // ── 소셜 로그인 — POST /api/v1/auth/social-login ────────────────────────────

  public static final String SOCIAL_LOGIN_SUMMARY = "소셜 로그인";

  public static final String SOCIAL_LOGIN_DESCRIPTION =
      """
      소셜 자격(Apple/Google)을 검증하고 서버 토큰을 발급한다. 신규면 계정을 만들고 온보딩 전용 토큰만 준다.

      **헤더**

      - 인증 불필요 — 토큰 없이 호출한다.

      **요청 주의사항**

      - provider별 자격이 다르다 — `GOOGLE`은 `idToken`, `APPLE`은 1회용 `authorizationCode`(약 5분 만료)다.
      - `email`·`name`은 최초 로그인에서만 캡처하고 재로그인 요청 값은 무시한다.


      **응답 주의사항**

      - 응답 `status`가 클라이언트 재개 지점을 정한다 — `PENDING`은 약관 동의, `TERMS_AGREED`는 온보딩, `ACTIVE`는 홈이다.
      - 온보딩 미완료(`PENDING`·`TERMS_AGREED`) 응답은 `refreshToken`이 null이고 `accessToken`으로 쓸 수 있는 API가 온보딩 단계로 제한된다(`expiresIn` 1800). `ACTIVE`는 access+refresh 둘 다 받는다(3600).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `provider` 누락·빈값이거나 `APPLE`·`GOOGLE` 밖의 값 |
      | 400 | `AUTH_MISSING_CREDENTIAL` | provider별 필수 자격이 누락·빈값 — `GOOGLE`의 `idToken` 또는 `APPLE`의 `authorizationCode`를 보내지 않음 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `AUTH_INVALID_SOCIAL_TOKEN` | Google `idToken`의 서명·`aud`·`iss`·`exp` 검증 실패, Apple 인가코드 교환 실패(만료·재사용 코드), 교환 결과 검증 실패 |
      | 422 | `AUTH_EMAIL_MISMATCH` | 최초 로그인에서 요청 `email`이 토큰의 email 클레임과 불일치 |
      | 422 | `AUTH_EMAIL_REQUIRED` | 최초 로그인에서 토큰 클레임·요청 어느 쪽에도 `email`이 없어 provider 진본 이메일을 확정할 수 없음 |
      | 502 | `UPSTREAM_ERROR` | Apple `/auth/token` 인가코드 교환이 타임아웃·5xx·I/O 오류로 실패 — 자격 문제가 아니므로 401과 달리 그대로 재시도할 수 있다 |
      """;

  public static final String[] SOCIAL_LOGIN_400 = {
    "INVALID_INPUT", "AUTH_MISSING_CREDENTIAL", "MALFORMED_REQUEST"
  };
  public static final String[] SOCIAL_LOGIN_401 = {"AUTH_INVALID_SOCIAL_TOKEN"};
  public static final String[] SOCIAL_LOGIN_422 = {"AUTH_EMAIL_MISMATCH", "AUTH_EMAIL_REQUIRED"};
  public static final String[] SOCIAL_LOGIN_502 = {"UPSTREAM_ERROR"};

  // ── 약관 동의 — POST /api/v1/auth/terms ─────────────────────────────────────

  public static final String TERMS_SUMMARY = "약관 동의";

  public static final String TERMS_DESCRIPTION =
      """
      이용약관·개인정보처리방침·마케팅 동의를 기록하고 `PENDING`을 `TERMS_AGREED`로 전이한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `PENDING`·`TERMS_AGREED`인 회원의 토큰(소셜 로그인 직후 발급).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 필수 동의 2종(`termsOfServiceAgreed`·`privacyPolicyAgreed`) 중 하나라도 누락(null) |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 온보딩을 마친(`ACTIVE`) 사용자의 재요청 |
      | 422 | `AUTH_REQUIRED_AGREEMENT_MISSING` | 필수 동의 2종 중 하나라도 `false` |
      """;

  public static final String[] TERMS_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] TERMS_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] TERMS_409 = {"AUTH_ONBOARDING_ALREADY_COMPLETED"};
  public static final String[] TERMS_422 = {"AUTH_REQUIRED_AGREEMENT_MISSING"};

  // ── 이메일 인증번호 발송 — POST /api/v1/auth/email/verification-code ─────────

  public static final String EMAIL_CODE_SUMMARY = "이메일 인증번호 발송";

  public static final String EMAIL_CODE_DESCRIPTION =
      """
      입력한 이메일로 인증번호를 동기 발송한다. 응답 `email`은 마스킹된다(예 `mi***@example.com`).

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **요청 주의사항**

      - 발송이 실패하면(메일 provider 장애·타임아웃) 인증번호가 새로 발급되지 않는다 — 502를 받으면 다시 발송해야 인증번호를 받는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `email` 누락·빈값이거나 이메일 형식 위반 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 상태가 `PENDING`·`TERMS_AGREED`인 토큰으로 호출 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 회원용 인증 기능은 호출할 수 없다 |
      | 429 | `TOO_MANY_REQUESTS` | 재발송 간격을 채우지 않은 재요청 |
      | 502 | `UPSTREAM_ERROR` | 메일 provider 장애·타임아웃으로 발송 실패 |
      """;

  public static final String[] EMAIL_CODE_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] EMAIL_CODE_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] EMAIL_CODE_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] EMAIL_CODE_429 = {"TOO_MANY_REQUESTS"};
  public static final String[] EMAIL_CODE_502 = {"UPSTREAM_ERROR"};

  // ── 이메일 인증번호 확인 — POST /api/v1/auth/email/verify ────────────────────

  public static final String EMAIL_VERIFY_SUMMARY = "이메일 인증번호 확인";

  public static final String EMAIL_VERIFY_DESCRIPTION =
      """
      발송된 인증번호를 확인해 이메일 인증을 완료한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). **세입자·임대인** 전용이다(`userType`이 `TENANT`·`LANDLORD`).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `email`·`code` 누락·빈값이거나 `email`이 이메일 형식 위반 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 상태가 `PENDING`·`TERMS_AGREED`인 토큰으로 호출 |
      | 403 | `FORBIDDEN` | 관리자(`userType=ADMIN`) — 회원용 인증 기능은 호출할 수 없다 |
      | 422 | `AUTH_EMAIL_VERIFICATION_FAILED` | 인증번호를 받은 적이 없거나 만료됐거나 코드가 틀림 — 어느 쪽인지 구분해 주지 않는다 |
      | 429 | `TOO_MANY_REQUESTS` | 코드 불일치가 시도 상한까지 누적돼 잠김 |
      """;

  public static final String[] EMAIL_VERIFY_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] EMAIL_VERIFY_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] EMAIL_VERIFY_403 = {"AUTH_ONBOARDING_REQUIRED"};
  public static final String[] EMAIL_VERIFY_422 = {"AUTH_EMAIL_VERIFICATION_FAILED"};
  public static final String[] EMAIL_VERIFY_429 = {"TOO_MANY_REQUESTS"};

  // ── 세입자 온보딩 제출 — POST /api/v1/auth/onboarding ────────────────────────

  public static final String ONBOARDING_SUMMARY = "세입자 온보딩 제출";

  public static final String ONBOARDING_DESCRIPTION =
      """
      세입자 필수 프로필을 제출해 `TERMS_AGREED`를 `ACTIVE`로 전이하고, 닉네임 배정·access·refresh 토큰 발급까지 한 번에 끝낸다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `TERMS_AGREED`인 회원의 토큰.

      **요청 주의사항**

      - 이름·이메일은 소셜 로그인 시점에 확정돼 여기서 받지 않는다.


      **응답 주의사항**

      - 응답 `data.user`의 `phoneNumber`는 세입자 미수집이라 값이 null이 아니라 **필드 자체가 생략**된다.
      - `data.linked`는 **항상 `false`**다 — 임대인과 같은 응답 타입을 쓸 뿐이고, 계정 병합의 매칭 키인 휴대폰 번호를 세입자는 수집하지 않아 병합 분기 자체가 없다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 필수 필드 누락·빈값, `birthDate`가 `YYYY-MM-DD` 형식이 아니거나 미래 날짜, `gender`·`visaType`·`occupation`·`lang` 값이 지원 목록 밖 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 온보딩을 완료(`ACTIVE`)한 사용자의 재요청 |
      | 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태에서 제출 |
      """;

  public static final String[] ONBOARDING_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] ONBOARDING_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] ONBOARDING_409 = {"AUTH_ONBOARDING_ALREADY_COMPLETED"};
  public static final String[] ONBOARDING_422 = {"AUTH_TERMS_AGREEMENT_REQUIRED"};

  // ── 토큰 재발급 — POST /api/v1/auth/reissue ──────────────────────────────────

  public static final String REISSUE_SUMMARY = "토큰 재발급";

  public static final String REISSUE_DESCRIPTION =
      """
      refresh 토큰으로 access 토큰을 재발급한다. refresh 토큰은 항상 회전한다.

      **헤더**

      - 인증 불필요 — 토큰 없이 호출한다.
      - 만료된 access 토큰이 붙어 있어도 401로 막지 않는다 — 모든 요청에 토큰을 붙이는 클라이언트가 이 호출만 헤더를 벗길 필요가 없다.

      **요청 주의사항**

      - refresh 토큰은 **쿠키(`refreshToken`) 우선 · 요청 본문 fallback**으로 읽는다 — 쿠키가 있으면 본문은 보지 않는다.
      - 웹(브라우저)은 HttpOnly 쿠키가 자동으로 실리므로 **본문 없이** 호출한다. 앱은 종전대로 본문에 담아 보내며 동작이 바뀌지 않는다.
      - 본문을 아예 보내지 않는 것은 오류가 아니다 — 쿠키·본문 어느 쪽에도 값이 없거나 공백일 때만 400 `INVALID_INPUT`이다.

      **응답 주의사항**

      - **응답 채널이 요청 채널을 따른다** — 쿠키로 보냈으면 회전된 refresh가 `Set-Cookie`로만 오고 본문 `refreshToken`은 null이다. 본문으로 보냈으면 종전대로 본문에 담겨 오고 `Set-Cookie`는 붙지 않는다.
      - 응답의 새 refresh 토큰으로 반드시 교체한다(쿠키 경로는 브라우저가 교체한다). 이전 토큰은 즉시 무효가 되고, 다시 쓰면 탈취로 간주해 그 사용자의 모든 세션이 끊긴다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 쿠키·본문 어느 쪽에도 `refreshToken`이 없거나 공백 |
      | 400 | `MALFORMED_REQUEST` | 보낸 요청 본문을 JSON으로 해석할 수 없음(본문을 보내지 않는 것은 오류가 아니다) |
      | 401 | `AUTH_INVALID_REFRESH_TOKEN` | 제출한 refresh 토큰의 만료·위조·무효화·재사용 탐지 — 넷을 구분하지 않고 이 코드 하나로 응답한다 |
      """;

  // 400의 두 코드가 가리키는 상황이 갈렸다(#229 D12) — 값을 못 찾은 것은 INVALID_INPUT, 본문이 깨진 것은
  // MALFORMED_REQUEST다. 본문 없는 요청은 이제 어느 쪽도 아니다(쿠키 경로의 정상 모양).
  public static final String[] REISSUE_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] REISSUE_401 = {"AUTH_INVALID_REFRESH_TOKEN"};

  // ── 로그아웃 — POST /api/v1/auth/logout ──────────────────────────────────────

  public static final String LOGOUT_SUMMARY = "로그아웃";

  public static final String LOGOUT_DESCRIPTION =
      """
      제출한 refresh 토큰을 무효화한다. 이미 무효한 토큰이어도 204다(멱등).

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료).

      **요청 주의사항**

      - refresh 토큰을 읽는 규칙은 재발급과 같다 — **쿠키(`refreshToken`) 우선 · 요청 본문 fallback**이며 쿠키가 있으면 본문은 보지 않는다.
      - 본문을 아예 보내지 않는 것은 오류가 아니다 — 쿠키·본문 어느 쪽에도 값이 없거나 공백일 때만 400 `INVALID_INPUT`이다.

      **응답 주의사항**

      - 쿠키로 보낸 요청에는 `Max-Age=0` 삭제 쿠키를 함께 내려 브라우저에 남은 refresh까지 지운다. 본문으로 보낸 요청에는 쿠키를 내리지 않는다.
      - 로그아웃해도 access 토큰은 무효화되지 않는다 — 남은 만료 시간까지 그대로 API가 호출되므로 클라이언트가 직접 지운다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 쿠키·본문 어느 쪽에도 `refreshToken`이 없거나 공백 |
      | 400 | `MALFORMED_REQUEST` | 보낸 요청 본문을 JSON으로 해석할 수 없음(본문을 보내지 않는 것은 오류가 아니다) |
      | 401 | `UNAUTHENTICATED` | access token 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 온보딩 미완료(`PENDING`·`TERMS_AGREED`) 토큰으로 접근 |
      """;

  public static final String[] LOGOUT_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] LOGOUT_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] LOGOUT_403 = {"AUTH_ONBOARDING_REQUIRED"};

  // ── 연락처 인증번호 발송 — POST /api/v1/auth/phone/verification-code ─────────

  public static final String PHONE_CODE_SUMMARY = "연락처 인증번호 발송";

  public static final String PHONE_CODE_DESCRIPTION =
      """
      입력한 휴대폰 번호로 SMS 인증번호를 동기 발송한다. 응답 `phoneNumber`는 마스킹된다(예 `010-****-5678`).

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `TERMS_AGREED`·`ACTIVE`인 회원의 토큰. 약관 동의 전(`PENDING`)에 호출하면 422다.
      - 온보딩 중에도, 온보딩을 마친 뒤 프로필 연락처를 바꿀 때도 이 엔드포인트를 쓴다.

      **요청 주의사항**

      - 발송이 실패하면(SMS provider 장애·타임아웃) 인증번호가 새로 발급되지 않는다 — 502를 받으면 다시 발송해야 인증번호를 받는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber` 누락·빈값이거나 휴대폰 번호 형식 위반(하이픈은 선택) |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태에서 호출 |
      | 429 | `TOO_MANY_REQUESTS` | 재발송 간격을 채우지 않은 재요청 |
      | 502 | `UPSTREAM_ERROR` | SMS provider 장애·타임아웃으로 발송 실패 |
      """;

  public static final String[] PHONE_CODE_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] PHONE_CODE_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] PHONE_CODE_422 = {"AUTH_TERMS_AGREEMENT_REQUIRED"};
  public static final String[] PHONE_CODE_429 = {"TOO_MANY_REQUESTS"};
  public static final String[] PHONE_CODE_502 = {"UPSTREAM_ERROR"};

  // ── 연락처 인증번호 확인 — POST /api/v1/auth/phone/verify ────────────────────

  public static final String PHONE_VERIFY_SUMMARY = "연락처 인증번호 확인";

  public static final String PHONE_VERIFY_DESCRIPTION =
      """
      발송된 인증번호를 확인해 연락처 인증을 완료한다. 임대인 온보딩(`POST /api/v1/auth/landlord/onboarding`)과 프로필 연락처 변경(`PATCH /api/v1/users/me`)의 선행 단계다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태와 무관하게 허용한다(`PENDING`·`TERMS_AGREED`·`ACTIVE`). 발송(`POST /api/v1/auth/phone/verification-code`)과 달리 약관 동의를 보지 않지만, 발송을 먼저 해야 확인할 인증번호가 있다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber`·`code` 누락·빈값이거나 `phoneNumber`가 휴대폰 번호 형식 위반 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 422 | `AUTH_PHONE_VERIFICATION_FAILED` | 인증번호를 받은 적이 없거나 만료됐거나 코드가 틀림 — 어느 쪽인지 구분해 주지 않는다 |
      | 429 | `TOO_MANY_REQUESTS` | 코드 불일치가 시도 상한까지 누적돼 잠김 |
      """;

  public static final String[] PHONE_VERIFY_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] PHONE_VERIFY_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] PHONE_VERIFY_422 = {"AUTH_PHONE_VERIFICATION_FAILED"};
  public static final String[] PHONE_VERIFY_429 = {"TOO_MANY_REQUESTS"};

  // ── 사업자등록번호 검증 — POST /api/v1/auth/business/verify ──────────────────

  public static final String BUSINESS_SUMMARY = "사업자등록번호 검증";

  public static final String BUSINESS_DESCRIPTION =
      """
      사업자등록번호를 외부 registry로 검증한다. 검증 결과는 서버에 남지 않고 응답 본문으로만 돌아오므로 필요할 때마다 다시 호출한다. 번호는 마스킹된다(예 `****567890`).

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `ACTIVE`인 회원의 토큰(온보딩 완료). 임대인 전용이다.

      **요청 주의사항**

      - 임대인 온보딩(`POST /api/v1/auth/landlord/onboarding`)에는 포함되지 않는다 — 온보딩을 마친 임대인이 매물 등록 시점에 따로 호출한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `businessRegistrationNumber` 누락·빈값이거나 허용 형식 위반 — 외부 호출 전에 거른다 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 403 | `AUTH_ONBOARDING_REQUIRED` | 상태가 `PENDING`·`TERMS_AGREED`인 토큰으로 호출 — 보안 필터가 거른다 |
      | 403 | `FORBIDDEN` | 임대인이 아닌(`userType=TENANT`) 정식 사용자의 요청 |
      | 422 | `AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED` | 외부 registry 조회 결과가 미등록·휴폐업·진위 실패 |
      | 502 | `UPSTREAM_ERROR` | 외부 검증 API 장애·타임아웃 |
      """;

  public static final String[] BUSINESS_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] BUSINESS_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};

  // 403이 둘로 갈린다 — 온보딩 토큰(필터)은 AUTH_ONBOARDING_REQUIRED, 정식 토큰이지만 세입자(서비스)는 FORBIDDEN이다.
  public static final String[] BUSINESS_403 = {"AUTH_ONBOARDING_REQUIRED", "FORBIDDEN"};
  public static final String[] BUSINESS_422 = {"AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED"};
  public static final String[] BUSINESS_502 = {"UPSTREAM_ERROR"};

  // ── 임대인 온보딩 제출 — POST /api/v1/auth/landlord/onboarding ───────────────

  public static final String LANDLORD_ONBOARDING_SUMMARY = "임대인 온보딩 제출";

  public static final String LANDLORD_ONBOARDING_DESCRIPTION =
      """
      연락처·생년월일을 제출해 `TERMS_AGREED`를 `ACTIVE`로 전이하고 `userType`을 `LANDLORD`로 확정한 뒤 access·refresh 토큰을 발급한다.

      **헤더**

      - `Authorization: Bearer <accessToken>` — 상태가 `TERMS_AGREED`인 회원의 토큰.

      **요청 주의사항**

      - `phoneNumber`는 `POST /api/v1/auth/phone/verification-code`·`/verify`로 사전 인증한 번호와 일치해야 한다(약관 검사가 연락처 검사보다 먼저다).
      - 이름·이메일은 소셜 로그인 시점에 확정돼 여기서 받지 않고, 사업자등록번호도 받지 않는다(온보딩 후 `POST /api/v1/auth/business/verify`로 따로 검증).

      **응답 주의사항**

      - 국적·표시 언어는 서버가 `KR`·`ko`로 고정 부여한다.
      - 응답 `data.user`의 세입자 전용 필드(`gender`·`occupation`·`visaType`)는 값이 null이 아니라 **필드 자체가 생략**되고, `phoneNumber`는 마스킹된다.
      - **`data.linked`가 계정 병합 여부다** — `true`면 아래 「계정 병합」이 일어난 것이라 `data.user`와 토큰이 요청에 쓴 계정이 아닌 웹 계정 기준이다. `user.id`를 요청 토큰과 비교해 추론하지 말고 이 필드를 본다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber` 누락·빈값·휴대폰 번호 형식 위반, `birthDate` 누락이거나 `YYYY-MM-DD` 형식이 아니거나 미래 날짜 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `UNAUTHENTICATED` | 토큰 누락·위조 |
      | 401 | `TOKEN_EXPIRED` | 만료된 access token으로 호출 |
      | 409 | `AUTH_ONBOARDING_ALREADY_COMPLETED` | 이미 온보딩을 완료(`ACTIVE`)한 사용자의 재요청 |
      | 409 | `RESOURCE_CONFLICT` | 같은 번호의 임대인 웹 회원가입(`POST /api/v1/auth/signup`)이 거의 동시에 계정을 확정해 연락처 유니크 제약에 걸림 — **그대로 다시 제출하면** 그 계정과 병합돼 성공한다 |
      | 422 | `AUTH_TERMS_AGREEMENT_REQUIRED` | 약관 미동의(`PENDING`) 상태에서 제출 |
      | 422 | `AUTH_PHONE_NOT_VERIFIED` | 제출한 `phoneNumber`가 미인증이거나 사전 인증한 번호와 불일치 |

      **계정 병합**

      - 인증한 연락처로 **이미 웹에서 가입한 임대인 계정**(같은 번호의 다른 `ACTIVE`·`LANDLORD` 계정)이 발견되면 두 계정을 하나로 합친다 — 앱 소셜 자격 매핑을 그 계정으로 옮기고 방금 만들어진 임시 계정은 삭제한다.
      - 병합이 일어나면 **`data.linked=true`** 다(일반 온보딩은 `false`). 이때 응답의 `data.user`와 토큰은 **합쳐진(웹) 계정 기준**이라 `data.user.id`가 요청 토큰의 사용자와 다르다. 클라이언트는 **응답의 토큰으로 교체**해야 하며(요청에 쓴 토큰의 계정은 더 이상 존재하지 않는다), 표시할 이름·이메일도 살아남은 계정의 값이다. 이후 소셜 로그인은 항상 같은 계정으로 귀결된다.
      - `linked`는 웹 회원가입(`POST /api/v1/auth/signup`)의 같은 이름 필드와 **의미가 같다** — 방향만 반대(연결/병합)이고 클라이언트에게는 둘 다 "계정이 하나로 합쳐졌다"는 한 가지 사실이다. 응답 필드 추가는 하위 호환이라 버전은 `/api/v1` 그대로다.
      """;

  public static final String[] LANDLORD_ONBOARDING_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] LANDLORD_ONBOARDING_401 = {"UNAUTHENTICATED", "TOKEN_EXPIRED"};
  public static final String[] LANDLORD_ONBOARDING_409 = {
    "AUTH_ONBOARDING_ALREADY_COMPLETED", "RESOURCE_CONFLICT"
  };
  public static final String[] LANDLORD_ONBOARDING_422 = {
    "AUTH_TERMS_AGREEMENT_REQUIRED", "AUTH_PHONE_NOT_VERIFIED"
  };

  // ── 가입용 인증번호 발송 — POST /api/v1/auth/phone/signup/verification-code ──

  public static final String SIGNUP_PHONE_CODE_SUMMARY = "가입용 연락처 인증번호 발송";

  public static final String SIGNUP_PHONE_CODE_DESCRIPTION =
      """
      임대인 웹 회원가입(`POST /api/v1/auth/signup`) 전에 번호 소유를 증명하도록 SMS 인증번호를 발송한다. 응답 `phoneNumber`는 마스킹된다(예 `010-****-5678`).

      **헤더**

      - 인증 불필요 — 계정이 아직 없는 가입 전 단계라 토큰 없이 호출한다.

      **요청 주의사항**

      - 온보딩용 발송(`POST /api/v1/auth/phone/verification-code`)과 정책(6자리·5분 만료·시도 5회·재발송 60초)은 같지만 **챌린지 키가 번호**라 로그인이 필요 없다.
      - 가입 이력이 있는 번호든 없는 번호든 **응답이 같다** — 이 응답으로 계정 존재 여부를 알 수 없다.
      - 발송이 실패하면(SMS provider 장애·타임아웃) 인증번호가 새로 발급되지 않는다 — 502를 받으면 다시 발송해야 인증번호를 받는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber` 누락·빈값이거나 휴대폰 번호 형식 위반(하이픈은 선택) |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 429 | `TOO_MANY_REQUESTS` | 재발송 간격 60초 미달, 같은 번호 5회/1시간 초과, 같은 IP 20회/1시간 초과 — 어느 축에 걸렸는지 구분해 알리지 않는다 |
      | 502 | `UPSTREAM_ERROR` | SMS provider 장애·타임아웃으로 발송 실패 |
      """;

  public static final String[] SIGNUP_PHONE_CODE_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] SIGNUP_PHONE_CODE_429 = {"TOO_MANY_REQUESTS"};
  public static final String[] SIGNUP_PHONE_CODE_502 = {"UPSTREAM_ERROR"};

  // ── 가입용 인증번호 확인 — POST /api/v1/auth/phone/signup/verify ──────────────

  public static final String SIGNUP_PHONE_VERIFY_SUMMARY = "가입용 연락처 인증번호 확인";

  public static final String SIGNUP_PHONE_VERIFY_DESCRIPTION =
      """
      발송된 인증번호를 확인해 가입용 연락처 인증을 완료한다. 임대인 웹 회원가입(`POST /api/v1/auth/signup`)의 선행 단계다.

      **헤더**

      - 인증 불필요 — 계정이 아직 없는 가입 전 단계라 토큰 없이 호출한다.

      **응답 주의사항**

      - 인증 마커는 **30분간만** 유효하다 — 그 안에 회원가입을 제출해야 하고, 넘기면 422 `AUTH_PHONE_NOT_VERIFIED`라 발송부터 다시 한다.
      - 마커는 가입 제출이 **1회 소비**한다 — 같은 인증으로 두 번 가입할 수 없다.
      - 이 응답은 **연동 대상 계정의 유무를 알려주지 않는다** — 가입 폼은 연동 여부와 무관하게 항상 전체 필드를 받는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber`·`code` 누락·빈값이거나 `phoneNumber`가 휴대폰 번호 형식 위반 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 422 | `AUTH_PHONE_VERIFICATION_FAILED` | 코드 불일치·만료·시도 상한(5회) 초과, 또는 인증번호를 받은 적이 없음 — 비로그인 경로라 **시도 초과도 429가 아니라 이 코드**이며 어느 쪽인지 구분해 주지 않는다 |
      """;

  public static final String[] SIGNUP_PHONE_VERIFY_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] SIGNUP_PHONE_VERIFY_422 = {"AUTH_PHONE_VERIFICATION_FAILED"};

  // ── 임대인 웹 회원가입 — POST /api/v1/auth/signup ────────────────────────────

  public static final String WEB_SIGNUP_SUMMARY = "임대인 웹 회원가입";

  public static final String WEB_SIGNUP_DESCRIPTION =
      """
      가입 폼 한 페이지의 값을 받아 한 트랜잭션에서 `ACTIVE`까지 완주한다. 같은 번호의 앱 임대인 계정이 있으면 새 계정을 만들지 않고 그 계정에 웹 자격증명만 붙인다(`linked=true`).

      **헤더**

      - 인증 불필요 — 계정이 아직 없는 가입 단계라 토큰 없이 호출한다.

      **요청 주의사항**

      - **선행 조건은 `POST /api/v1/auth/phone/signup/verification-code`·`/verify`** 다. 제출 번호의 인증 마커가 없으면 422이고 계정 생성도 연동도 하지 않는다.
      - 연동 판정 키는 **정규화한 `phoneNumber` 단독**이다 — 이름은 매칭 조건이 아니다(앱 이름과 웹 이름이 달라도 연동된다).
      - 이메일 중복은 **웹 로그인 ID(`local_accounts.email`)** 에만 건다 — 앱 소셜 계정과 같은 이메일로 가입하는 것은 정상이다.
      - `password`는 영문자 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, **길이 8~20**, 공백 불가다. 위반은 400 `INVALID_INPUT`이며 `errors[].field=password`로 온다.
      - 폼은 연동 여부와 무관하게 항상 전체 필드를 받는다(화면이 하나다).

      **응답 주의사항**

      - **refresh 토큰은 응답 본문에 없다** — `Set-Cookie: refreshToken=…; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=1209600`으로만 내려간다. 브라우저가 자동 보관하므로 클라이언트가 저장할 것이 없다(`local` 프로파일에서만 `Secure`가 빠진다).
      - `onboardingRequired`는 항상 `false`, `status`는 항상 `"ACTIVE"`다 — 웹에는 부분 완료 상태가 없다.
      - `email`·`name`은 **회원 프로필(`users`)의 값**이다 — 연동된 계정이면 폼에 적은 웹 이메일이 아니라 소셜 진본 이메일이 나갈 수 있다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | 필수 필드 누락·빈값, `email` 형식 위반, `phoneNumber` 형식 위반, `birthDate` 형식 위반·미래 날짜, **비밀번호 정책 위반**(`errors[].field=password`) |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 409 | `AUTH_EMAIL_ALREADY_REGISTERED` | 같은 이메일의 웹 자격증명이 이미 있음(로그인 ID 중복) |
      | 409 | `AUTH_WEB_ACCOUNT_ALREADY_EXISTS` | 번호로 매칭된 계정에 이미 웹 자격증명이 붙어 있음 → 로그인으로 유도한다. 그 계정의 이메일은 마스킹해서도 응답에 싣지 않는다 |
      | 409 | `RESOURCE_CONFLICT` | 같은 번호의 앱 임대인 온보딩(`POST /api/v1/auth/landlord/onboarding`)이 거의 동시에 계정을 확정해 연락처 유니크 제약에 걸림 — **그대로 다시 제출하면** 그 계정과 연동돼 성공한다 |
      | 422 | `AUTH_PHONE_NOT_VERIFIED` | 제출 `phoneNumber`의 가입용 인증 마커가 없거나 만료(이미 가입에 소비한 마커 포함) |
      | 422 | `AUTH_REQUIRED_AGREEMENT_MISSING` | 필수 동의 2종(`termsOfServiceAgreed`·`privacyPolicyAgreed`) 중 하나라도 `false` |
      """;

  public static final String[] WEB_SIGNUP_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] WEB_SIGNUP_409 = {
    "AUTH_EMAIL_ALREADY_REGISTERED", "AUTH_WEB_ACCOUNT_ALREADY_EXISTS", "RESOURCE_CONFLICT"
  };
  public static final String[] WEB_SIGNUP_422 = {
    "AUTH_PHONE_NOT_VERIFIED", "AUTH_REQUIRED_AGREEMENT_MISSING"
  };

  // ── 임대인 웹 로그인 — POST /api/v1/auth/login ───────────────────────────────

  public static final String WEB_LOGIN_SUMMARY = "임대인 웹 로그인";

  public static final String WEB_LOGIN_DESCRIPTION =
      """
      웹 로그인 ID(이메일)와 비밀번호로 정식 토큰을 발급한다. 앱 소셜 로그인과 같은 발급·회전 로직을 쓰고 refresh만 쿠키로 내려간다.

      **헤더**

      - 인증 불필요 — 로그인 이전 단계라 토큰 없이 호출한다.

      **요청 주의사항**

      - 로그인 ID는 회원 프로필 이메일이 아니라 **가입할 때 정한 웹 이메일**이다.
      - **등록되지 않은 이메일과 비밀번호 불일치는 똑같은 401**이다 — `error.code`·문구가 같다.
      - **비밀번호 10회 연속 실패면 계정이 잠긴다**(423). 잠긴 뒤에는 **비밀번호가 맞아도** 423이며 시간이 지나도 자동으로 풀리지 않는다 — 해제는 본인이 `POST /api/v1/auth/password/reset-link` → `POST /api/v1/auth/password/reset`를 완주하는 것뿐이다(별도 잠금 해제 API는 없다).
      - 시도 자체에 한도가 있다 — 자격증명을 조회하기 **전에** 같은 IP 60회/시간·같은 이메일 20회/시간을 세고 초과하면 429다.

      **응답 주의사항**

      - **refresh 토큰은 응답 본문에 없다** — 회원가입과 같은 속성의 `Set-Cookie: refreshToken`으로만 내려간다.
      - `onboardingRequired`는 항상 `false`, `status`는 항상 `"ACTIVE"`다 — 웹 로그인에는 온보딩 재개 분기가 없다.
      - `email`·`name`은 **회원 프로필(`users`)의 값**이라 로그인에 쓴 이메일과 다를 수 있다(앱 계정에 연동된 임대인).
      - **비밀번호가 틀린 401에는 `error.details`가 실린다** — `failedAttempts`(누적 실패)와 `maxFailedAttempts`(잠금 상한)다. **`failedAttempts`가 `maxFailedAttempts`에 닿은 응답이 곧 잠금 시점**이므로, 두 값이 같아지면 그 응답이 401이어도 잠금 안내를 띄운다.
      - 그 밖의 실패(등록되지 않은 이메일, 잠긴 계정, 시도 한도 초과)에서는 값이 `null`이 아니라 **`error.details` 필드 자체가 생략된다**.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `email`·`password` 누락·빈값이거나 `email` 형식 위반 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 401 | `AUTH_INVALID_CREDENTIALS` | 등록되지 않은 이메일 **또는** 비밀번호 불일치 — `error.code`가 같다. 탈퇴 등으로 `ACTIVE`가 아닌 계정도 같은 코드다 |
      | 423 | `AUTH_ACCOUNT_LOCKED` | 비밀번호 10회 연속 실패로 잠긴 계정 — 비밀번호가 맞아도 잠금이 우선한다. 시간 경과 자동 해제는 없고 본인이 비밀번호 재설정을 완주해야 풀린다 |
      | 429 | `TOO_MANY_REQUESTS` | 로그인 시도 한도 초과(IP 60회/시간 또는 이메일 20회/시간) — 자격증명 조회 전에 판정하므로 이메일 존재 여부와 무관하고, 어느 축에 걸렸는지 구분해 알리지 않는다 |
      """;

  public static final String[] WEB_LOGIN_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] WEB_LOGIN_401 = {"AUTH_INVALID_CREDENTIALS"};

  /**
   * 웹 로그인 401 응답 기술자(#270). {@code error.details}가 실리는 케이스와 실리지 않는 케이스가 <b>같은 {@code (path, method,
   * status)}</b>라 반드시 이 한 벌을 공유해야 한다 — 한쪽만 상세를 선언하면 dedup·last-wins로 승자가 파일 순회 순서에 좌우된다.
   */
  public static List<FieldDescriptor> webLogin401Fields() {
    return ApiDocsFields.errorFieldsWith(
        List.of(
            optField(
                "error.details.failedAttempts",
                JsonFieldType.NUMBER,
                "이 계정에 누적된 연속 실패 횟수(비밀번호 불일치에서만)"),
            optField(
                "error.details.maxFailedAttempts",
                JsonFieldType.NUMBER,
                "계정을 잠그는 상한(비밀번호 불일치에서만) — failedAttempts가 이 값에 닿은 응답이 곧 잠금 시점이다")),
        WEB_LOGIN_401);
  }

  public static final String[] WEB_LOGIN_423 = {"AUTH_ACCOUNT_LOCKED"};
  public static final String[] WEB_LOGIN_429 = {"TOO_MANY_REQUESTS"};

  // ── 이메일 찾기용 인증번호 발송 — POST /api/v1/auth/phone/find-email/verification-code ──

  public static final String FIND_EMAIL_PHONE_CODE_SUMMARY = "이메일 찾기용 연락처 인증번호 발송";

  public static final String FIND_EMAIL_PHONE_CODE_DESCRIPTION =
      """
      웹 로그인 ID(이메일)를 잊은 임대인이 번호 소유를 증명하도록 SMS 인증번호를 발송한다. 응답 `phoneNumber`는 마스킹된다(예 `010-****-5678`).

      **헤더**

      - 인증 불필요 — 로그인 ID를 모르는 단계라 인증할 수단 자체가 없다.

      **요청 주의사항**

      - 인증번호 정책(6자리·5분 만료·검증 시도 5회·재발송 60초)은 가입용 발송(`POST /api/v1/auth/phone/signup/verification-code`)과 같지만 **챌린지·마커 키스페이스가 다르다** — 가입용 마커로는 이메일 찾기(`POST /api/v1/auth/email/find`)를 통과할 수 없고 그 반대도 마찬가지다.
      - 레이트리밋 예산도 가입용과 **합산하지 않는다** — 가입 SMS를 태워 막힌 사람이 복구 경로까지 막히지 않게 한다.
      - 가입 이력이 있는 번호든 없는 번호든 **응답이 같다** — 이 응답으로 계정 존재 여부를 알 수 없다. 계정 판정은 이름까지 받는 `POST /api/v1/auth/email/find`에서만 한다.
      - 발송이 실패하면(SMS provider 장애·타임아웃) 인증번호가 새로 발급되지 않는다 — 502를 받으면 다시 발송해야 인증번호를 받는다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber` 누락·빈값이거나 휴대폰 번호 형식 위반(하이픈은 선택) |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 429 | `TOO_MANY_REQUESTS` | 재발송 간격 60초 미달, 같은 번호 5회/1시간 초과, 같은 IP 20회/1시간 초과 — 어느 축에 걸렸는지 구분해 알리지 않는다 |
      | 502 | `UPSTREAM_ERROR` | SMS provider 장애·타임아웃으로 발송 실패 |
      """;

  public static final String[] FIND_EMAIL_PHONE_CODE_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] FIND_EMAIL_PHONE_CODE_429 = {"TOO_MANY_REQUESTS"};
  public static final String[] FIND_EMAIL_PHONE_CODE_502 = {"UPSTREAM_ERROR"};

  // ── 이메일 찾기용 인증번호 확인 — POST /api/v1/auth/phone/find-email/verify ───

  public static final String FIND_EMAIL_PHONE_VERIFY_SUMMARY = "이메일 찾기용 연락처 인증번호 확인";

  public static final String FIND_EMAIL_PHONE_VERIFY_DESCRIPTION =
      """
      발송된 인증번호를 확인해 **이메일 찾기 전용** 검증 마커(30분)를 만든다. 가입 이메일 찾기(`POST /api/v1/auth/email/find`)의 선행 단계다.

      **헤더**

      - 인증 불필요 — 로그인 ID를 모르는 단계다.

      **요청 주의사항**

      - `code`에는 자릿수·형식 제약을 두지 않는다(빈값만 거른다) — 검증이 해시 대조이고, 형제 엔드포인트(`POST /api/v1/auth/phone/signup/verify`)가 같은 입력에 다른 status를 내지 않게 맞춘 것이다.

      **응답 주의사항**

      - 마커는 **30분간만** 유효하고 소비처는 `POST /api/v1/auth/email/find` **하나뿐**이다 — 넘기면 422 `AUTH_PHONE_NOT_VERIFIED`라 발송부터 다시 한다.
      - **가입용 마커와 서로 통하지 않는다** — 키스페이스가 갈려 있어 조회 자체가 실패한다.
      - 이 응답은 **계정의 유무를 말하지 않는다** — "번호를 검증했다"는 사실만 알린다. 여기서 미리 알려 주면 발송·확인 두 번만으로 번호 열거가 성립한다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber`·`code` 누락·빈값이거나 `phoneNumber`가 휴대폰 번호 형식 위반 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 422 | `AUTH_PHONE_VERIFICATION_FAILED` | 코드 불일치·만료·검증 시도 상한(5회) 초과, 또는 인증번호를 받은 적이 없음 — 비로그인 경로라 **시도 초과도 429가 아니라 이 코드**이며 어느 쪽인지 구분해 주지 않는다 |
      """;

  public static final String[] FIND_EMAIL_PHONE_VERIFY_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] FIND_EMAIL_PHONE_VERIFY_422 = {"AUTH_PHONE_VERIFICATION_FAILED"};

  // ── 가입 이메일 찾기 — POST /api/v1/auth/email/find ───────────────────────────

  public static final String FIND_EMAIL_SUMMARY = "가입 이메일 찾기";

  public static final String FIND_EMAIL_DESCRIPTION =
      """
      연락처 인증 마커를 소비하고 제출한 이름을 대조해, 그 번호로 가입된 웹 계정의 **마스킹된 로그인 이메일**을 돌려준다.

      **헤더**

      - 인증 불필요 — 로그인 ID를 모르는 단계다.

      **요청 주의사항**

      - **선행 조건은 `POST /api/v1/auth/phone/find-email/verification-code`·`/verify`** 다. 마커가 없거나 만료면 422이고 계정 조회 자체를 하지 않는다. 가입용 마커로는 통과하지 못한다.
      - `name`은 **`local_accounts.name`(가입 폼에 직접 적은 값)과 대조**하며 회원 프로필 이름(`users.name`) 폴백이 없다. 공백을 모두 지우고 대소문자를 무시해 비교하므로 `홍 길동`·`홍길동`은 같은 이름이다.

      **응답 주의사항**

      - 응답 `email`은 **웹 로그인 ID(`local_accounts.email`)** 를 마스킹한 값이다 — 회원 프로필 이메일이 아니다. 연동된 계정은 두 값이 다를 수 있고, 이 화면이 알려 줘야 하는 것은 로그인 화면에 입력할 ID다.
      - **성공하면 마커를 소비(삭제)한다** — 마커 하나로 무제한 반복 조회하는 것을 막는다. 실패한 조회는 마커를 태우지 않으므로 이름 오타는 다시 시도할 수 있다.
      - 이 경로만 **계정 존재를 404로 드러낸다** — SMS 인증 마커 뒤라 호출자가 조회할 수 있는 번호가 자기 번호 하나로 닫혀 있기 때문이다(재설정 링크 발송은 반대로 존재를 숨긴다).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `phoneNumber`·`name` 누락·빈값, `phoneNumber` 형식 위반, `name` 200자 초과 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 404 | `AUTH_WEB_ACCOUNT_NOT_FOUND` | 그 번호로 가입된 웹 계정이 없음 **또는** 제출한 이름이 불일치 — **두 경우를 구분하지 않는다**(이름 오라클 차단) |
      | 422 | `AUTH_PHONE_NOT_VERIFIED` | 이메일 찾기용 검증 마커가 없거나 만료(이미 소비한 마커 포함) |
      """;

  public static final String[] FIND_EMAIL_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] FIND_EMAIL_404 = {"AUTH_WEB_ACCOUNT_NOT_FOUND"};
  public static final String[] FIND_EMAIL_422 = {"AUTH_PHONE_NOT_VERIFIED"};

  // ── 비밀번호 재설정 링크 발송 — POST /api/v1/auth/password/reset-link ─────────

  public static final String PASSWORD_RESET_LINK_SUMMARY = "비밀번호 재설정 링크 발송";

  public static final String PASSWORD_RESET_LINK_DESCRIPTION =
      """
      웹 로그인 ID(이메일)로 비밀번호 재설정 링크를 메일 발송한다. **「비밀번호를 잊었다」와 「계정이 잠겼다」(로그인 423)의 진입점이 같다** — 화면은 둘이지만 API는 하나이고 별도 잠금 해제 엔드포인트는 없다.

      **헤더**

      - 인증 불필요 — 비밀번호를 모르거나 계정이 잠긴 상태라 인증할 수단이 없다.

      **요청 주의사항**

      - 이름·연락처 같은 추가 확인 값을 받지 않는다 — 소유 증명은 **메일 수신 자체**가 한다.
      - 조회 대상은 `local_accounts.email`(웹 로그인 ID)이고 회원 프로필 이메일은 보지 않는다.
      - **가입되지 않은 이메일도 같은 200**이다(`expiresIn`까지 같다) — 메일만 보내지 않는다. 선행 게이트가 없어 임의의 이메일로 부를 수 있으므로, 응답을 가르는 순간 완전한 가입 여부 오라클이 된다.
      - **다만 발송이 동기라 응답 시간과 502 발생 여부로는 존재가 드러난다** — 본문이 같다는 수준까지만 방어하고 타이밍 누출은 알면서 남긴다.

      **응답 주의사항**

      - 링크는 서버 설정(`app.web.base-url`)으로만 조립한다 — 요청 `Host`·`X-Forwarded-Host`로 만들지 않는다(호스트 헤더 포이즈닝으로 공격자 도메인이 박힌 링크를 남의 메일함에 보낼 수 있다).
      - 토큰은 **일회용 불투명 토큰**이고 서버는 해시만 보관한다. 소비는 확정(`POST /api/v1/auth/password/reset`)에서 단 한 번이다.
      - **`local`·`dev`에서만 켠다**(`app.auth.web.password-reset.enabled`) — 꺼진 환경에서는 이 경로 자체가 없다(404).

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `email` 누락·빈값·형식 위반·255자 초과 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 429 | `TOO_MANY_REQUESTS` | 같은 이메일 5회/1시간 또는 같은 IP 20회/1시간 초과 — 어느 축인지 구분해 알리지 않는다. 로그인 시도 한도와 버킷을 공유하지 않는다 |
      | 502 | `UPSTREAM_ERROR` | 메일 발송 실패(SMTP 장애·타임아웃). **가입된 이메일에서만 날 수 있어 그 자체가 존재 신호**이며 위 타이밍 누출과 같은 성격으로 수용한다 |
      """;

  public static final String[] PASSWORD_RESET_LINK_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] PASSWORD_RESET_LINK_429 = {"TOO_MANY_REQUESTS"};
  public static final String[] PASSWORD_RESET_LINK_502 = {"UPSTREAM_ERROR"};

  // ── 재설정 토큰 사전 확인 — POST /api/v1/auth/password/reset-token/verify ─────

  public static final String PASSWORD_RESET_TOKEN_VERIFY_SUMMARY = "재설정 토큰 사전 확인";

  public static final String PASSWORD_RESET_TOKEN_VERIFY_DESCRIPTION =
      """
      재설정 화면(SPA)이 도착하자마자 호출해 **링크가 아직 살아 있는지**와 **어느 계정의 링크인지**를 확인한다. 이 절이 없으면 사용자는 새 비밀번호를 두 번 입력하고 제출한 뒤에야 "만료된 링크"를 듣는다.

      **헤더**

      - 인증 불필요 — 로그인하지 못하는 상태에서 부르는 경로다.

      **요청 주의사항**

      - **토큰은 쿼리스트링이 아니라 본문으로 받는다** — 쿼리 파라미터는 액세스 로그·리퍼러에 원문 그대로 남는데, 이 값 하나로 남의 비밀번호를 바꿀 수 있다.
      - 형식 검증은 빈값 거르기뿐이다 — 접두(`pr_`)나 길이를 강제하면 「모양만으로 걸러진 요청」과 실제로 없는 토큰이 다른 status를 받아 그 차이가 토큰의 생김새를 알려 준다.

      **응답 주의사항**

      - **토큰을 소비하지 않는 것이 계약이다** — 메일 클라이언트의 링크 프리뷰·메일 게이트웨이의 URL 안전 검사처럼 사용자가 클릭하기 전에 링크가 열리는 경우가 흔하다. 여기서 태우면 정작 본인이 눌렀을 때 이미 죽은 링크다.
      - `expiresIn`은 고정값이 아니라 **남은 초**다 — 호출할 때마다 줄어들며 화면의 카운트다운은 이 값에서 시작한다.
      - `email`은 마스킹된다 — 토큰만 있으면 부를 수 있는 경로라 평문을 실으면 유출된 링크 하나가 계정 이메일까지 함께 넘긴다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `token` 누락·빈값 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 422 | `AUTH_PASSWORD_RESET_TOKEN_INVALID` | 토큰 부재·만료·이미 사용됨 — **세 경우를 구분하지 않는다**(구분하면 "존재했지만 이미 쓰였다"까지 알려 주는 오라클이 된다) |
      """;

  public static final String[] PASSWORD_RESET_TOKEN_VERIFY_400 = {
    "INVALID_INPUT", "MALFORMED_REQUEST"
  };
  public static final String[] PASSWORD_RESET_TOKEN_VERIFY_422 = {
    "AUTH_PASSWORD_RESET_TOKEN_INVALID"
  };

  // ── 비밀번호 재설정 확정 — POST /api/v1/auth/password/reset ───────────────────

  public static final String PASSWORD_RESET_SUMMARY = "비밀번호 재설정 확정";

  public static final String PASSWORD_RESET_DESCRIPTION =
      """
      토큰과 새 비밀번호를 받아 **토큰 소비 · 비밀번호 교체 · 잠금 해제 · 실패 카운터 초기화 · 기존 세션 전량 무효화**를 한 번에 끝낸다. **이 호출이 곧 계정 잠금 해제**다.

      **헤더**

      - 인증 불필요 — 로그인하지 못하는 상태에서 부르는 경로다.

      **요청 주의사항**

      - `newPassword` 정책은 **회원가입 `password`와 같은 규칙**이다 — 영문자 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, 길이 8~20, 공백 불가. 위반은 400 `INVALID_INPUT`(`errors[].field=newPassword`)이고 **이때 토큰은 소비되지 않는다**(Bean Validation이 토큰 판정보다 먼저다).

      **응답 주의사항**

      - **204 No Content — 본문도 `Set-Cookie`도 없다.** 재설정은 세션을 만드는 자리가 아니다: 방금 전량 무효화한 자리에 새 세션을 끼워 넣으면 유출된 링크를 주운 쪽이 그대로 로그인 상태가 된다. 클라이언트는 204를 받으면 로그인 화면으로 보낸다.
      - 잠겨 있던 계정은 이 시점에 잠금과 실패 카운터가 이미 사라진 상태다.
      - 로그인 시도 한도는 **이메일 축만** 초기화된다 — IP 축은 그 IP를 쓰는 모든 호출자의 것이라 지우지 않는다. 같은 IP에서 이미 한도를 태웠다면 재설정 직후에도 429일 수 있다.
      - 성공하면 **토큰은 그 자리에서 죽는다** — 같은 링크를 두 번 제출하면 두 번째는 422다.

      **에러 코드**

      | status | `error.code` | 발생 조건 |
      |---|---|---|
      | 400 | `INVALID_INPUT` | `token`·`newPassword` 누락·빈값, 비밀번호 정책 위반(`errors[].field=newPassword`) — 토큰은 소비되지 않는다 |
      | 400 | `MALFORMED_REQUEST` | 요청 본문 JSON을 해석할 수 없음 |
      | 422 | `AUTH_PASSWORD_RESET_TOKEN_INVALID` | 토큰 부재·만료·이미 사용됨. 이 판정이 첫 단계라 실패하면 비밀번호·잠금·세션 어느 것도 건드리지 않는다 |
      """;

  public static final String[] PASSWORD_RESET_400 = {"INVALID_INPUT", "MALFORMED_REQUEST"};
  public static final String[] PASSWORD_RESET_422 = {"AUTH_PASSWORD_RESET_TOKEN_INVALID"};

  // ---- 성공 응답/요청 필드 기술자 ----

  public static List<FieldDescriptor> socialLoginRequestFields() {
    return List.of(
        enumField("provider", Provider.class, "소셜 제공자(필수). 누락·빈값·허용 외 문자열 모두 400 INVALID_INPUT"),
        optField("idToken", JsonFieldType.STRING, "Google OIDC ID 토큰 — GOOGLE 필수, APPLE 미사용"),
        optField(
            "authorizationCode",
            JsonFieldType.STRING,
            "Apple 1회용 인가코드(약 5분 만료) — APPLE 필수, GOOGLE 미사용"),
        optField(
            "email",
            JsonFieldType.STRING,
            "앱이 네이티브 SDK에서 받은 이메일(선택 — 최초 로그인에서는 사실상 필수, 토큰 email 클레임과 교차 검증). 재로그인 값은 무시한다"),
        optField(
            "name",
            JsonFieldType.STRING,
            "앱이 네이티브 SDK에서 받은 표시 이름(선택 — 최초 로그인에서만 캡처, 검증 없이 신뢰). Apple은 최초 1회만 제공한다"));
  }

  public static List<FieldDescriptor> socialLoginResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.onboardingRequired",
            JsonFieldType.BOOLEAN,
            "온보딩 필요 여부 — 신규·온보딩 미완료는 true, 기존 ACTIVE는 false"),
        enumField(
            "data.status",
            UserStatus.class,
            "사용자 상태 — 클라이언트 재개 지점 분기. 이 응답에는 PENDING·TERMS_AGREED·ACTIVE만 나온다(WITHDRAWN 계정은 로그인되지 않는다)"),
        field("data.email", JsonFieldType.STRING, "사용자 이메일(provider 진본) — 모든 분기에서 프리필용 반환"),
        optField(
            "data.name",
            JsonFieldType.STRING,
            "사용자 이름(단일 name) — 모든 분기에서 프리필용 반환. 아직 캡처된 이름이 없으면 null이다"),
        codeField("data.tokenType", TOKEN_TYPES, "토큰 타입 — 항상 Bearer"),
        field(
            "data.accessToken",
            JsonFieldType.STRING,
            "access 토큰(JWT). 온보딩 미완료면 온보딩 단계 전용이라 쓸 수 있는 API가 제한된다"),
        optField(
            "data.refreshToken", JsonFieldType.STRING, "refresh 토큰(불투명). 온보딩 미완료 응답에서는 null이다"),
        field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(온보딩 1800 / 정식 3600)"),
        errorNull());
  }

  public static List<FieldDescriptor> termsRequestFields() {
    return List.of(
        field("termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의(필수). false면 422"),
        field("privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의(필수). false면 422"),
        optField("marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의(선택, 보내지 않으면 false)"));
  }

  public static List<FieldDescriptor> termsResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        enumField("data.status", UserStatus.class, "전이 후 상태 — 이 응답에서는 항상 TERMS_AGREED"),
        field("data.termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의 여부"),
        field("data.privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의 여부"),
        field("data.marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의 여부"),
        field("data.agreedAt", JsonFieldType.STRING, "동의 시각(ISO-8601 UTC)"),
        errorNull());
  }

  public static List<FieldDescriptor> emailCodeRequestFields() {
    return List.of(field("email", JsonFieldType.STRING, "인증번호를 받을 이메일(필수, 이메일 형식)"));
  }

  public static List<FieldDescriptor> emailCodeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.email", JsonFieldType.STRING, "마스킹된 이메일(예: mi***@example.com)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "인증번호 만료까지 초"),
        errorNull());
  }

  public static List<FieldDescriptor> emailVerifyRequestFields() {
    return List.of(
        field("email", JsonFieldType.STRING, "인증번호를 발송한 이메일과 일치(필수)"),
        field("code", JsonFieldType.STRING, "발송된 인증번호(필수, 빈값 불가)"));
  }

  public static List<FieldDescriptor> emailVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.email", JsonFieldType.STRING, "마스킹된 이메일"),
        field("data.verified", JsonFieldType.BOOLEAN, "검증 완료 여부 — 성공 응답은 항상 true"),
        errorNull());
  }

  public static List<FieldDescriptor> onboardingRequestFields() {
    return List.of(
        enumField("gender", Gender.class, "성별(필수). 빈값·목록 밖 값은 400 INVALID_INPUT"),
        field("birthDate", JsonFieldType.STRING, "생년월일 YYYY-MM-DD(필수, 과거 날짜만)"),
        codeField(
            "country",
            COUNTRY_CODES,
            "국적 ISO 3166-1 alpha-2 코드(필수). 값 목록을 내려주는 API가 없어 여기 나열한 15개가 지원 코드의 전부다"),
        optEnumField(
            "occupation", Occupation.class, "직업(선택 — 보내지 않거나 null이면 저장하지 않고 프로필 응답에서 필드 자체가 생략된다)"),
        enumField("visaType", VisaType.class, "비자정보(필수). 요청·응답 모두 상수명으로 주고받는다"),
        optCodeField("lang", LANG_CODES, "표시 언어 ISO 639-1 소문자(선택 — 보내지 않으면 설정하지 않고 표시는 en으로 폴백)"));
  }

  /**
   * 재발급·로그아웃 요청 본문의 {@code refreshToken}. <b>선택 필드다</b> — 서버가 쿠키({@code refreshToken}) 우선 · 본문
   * fallback으로 읽으므로 브라우저는 본문 자체를 보내지 않는다(#229 D12 · ADR-0048 §3). 채널별 차이는 호출부가 {@code
   * description}으로 적는다.
   */
  public static List<FieldDescriptor> refreshTokenRequestField(String description) {
    return List.of(optField("refreshToken", JsonFieldType.STRING, description));
  }

  public static List<FieldDescriptor> reissueResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        codeField("data.tokenType", TOKEN_TYPES, "토큰 타입 — 항상 Bearer"),
        field("data.accessToken", JsonFieldType.STRING, "새 access 토큰(JWT)"),
        optField(
            "data.refreshToken",
            JsonFieldType.STRING,
            "새 refresh 토큰. 이 값으로 교체해야 하며 제출한 토큰은 즉시 무효가 된다. 쿠키로 제출한 요청(웹)에서는 null이고 회전된 값이 Set-Cookie로만 내려간다"),
        field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(3600)"),
        errorNull());
  }

  public static List<FieldDescriptor> phoneCodeRequestFields() {
    return List.of(
        field(
            "phoneNumber",
            JsonFieldType.STRING,
            "인증번호를 받을 휴대폰 번호(필수, 빈값 불가 — 하이픈은 선택이며 서버가 숫자만 남겨 정규화한다)"));
  }

  public static List<FieldDescriptor> phoneCodeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "인증번호 만료까지 초"),
        errorNull());
  }

  public static List<FieldDescriptor> phoneVerifyRequestFields() {
    return List.of(
        field("phoneNumber", JsonFieldType.STRING, "인증번호를 발송한 연락처와 일치(필수)"),
        field("code", JsonFieldType.STRING, "발송된 인증번호(필수, 빈값 불가)"));
  }

  public static List<FieldDescriptor> phoneVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field(
            "data.verified",
            JsonFieldType.BOOLEAN,
            "검증 완료 여부 — 성공 응답은 항상 true. 인증된 상태는 30분만 유지되므로 그 안에 임대인 온보딩·프로필 연락처 변경을 제출해야 하고, 넘기면 422 AUTH_PHONE_NOT_VERIFIED라 다시 인증해야 한다"),
        errorNull());
  }

  public static List<FieldDescriptor> businessVerifyRequestFields() {
    return List.of(
        field(
            "businessRegistrationNumber",
            JsonFieldType.STRING,
            "사업자등록번호(필수) — 숫자 10자리 또는 하이픈 형식(123-45-67890) 둘 다 허용하며 동일하게 처리된다"));
  }

  public static List<FieldDescriptor> businessVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.businessRegistrationNumber", JsonFieldType.STRING, "마스킹된 사업자등록번호(예: ****567890)"),
        field("data.verified", JsonFieldType.BOOLEAN, "정상 사업자 검증 완료 여부 — 성공 응답은 항상 true"),
        errorNull());
  }

  public static List<FieldDescriptor> landlordOnboardingRequestFields() {
    return List.of(
        field(
            "phoneNumber",
            JsonFieldType.STRING,
            "사전 SMS 인증된 연락처와 일치(필수, 빈값 불가 — 하이픈 표기 차이는 정규화로 흡수한다). 불일치·미인증은 422"),
        field("birthDate", JsonFieldType.STRING, "생년월일 YYYY-MM-DD(필수, 과거 날짜만 — 형식 위반·미래는 400)"));
  }

  /**
   * 가입용 발송·확인은 온보딩용({@link #phoneCodeRequestFields})과 응답 모양이 같지만 <b>기술자를 공유하지 않는다</b> — path가 달라 다른
   * 오퍼레이션이고, 같은 문구를 쓰면 "로그인이 필요 없다"·"마커 30분" 같은 이 경로만의 계약을 적을 자리가 사라진다.
   */
  public static List<FieldDescriptor> signupPhoneCodeRequestFields() {
    return List.of(
        field(
            "phoneNumber",
            JsonFieldType.STRING,
            "인증번호를 받을 휴대폰 번호(필수, 빈값 불가 — 하이픈은 선택이며 서버가 숫자만 남겨 정규화한다). 이 번호가 곧 챌린지 키라 로그인이 필요 없다"));
  }

  public static List<FieldDescriptor> signupPhoneCodeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "인증번호 만료까지 초"),
        errorNull());
  }

  public static List<FieldDescriptor> signupPhoneVerifyRequestFields() {
    return List.of(
        field("phoneNumber", JsonFieldType.STRING, "인증번호를 발송한 번호와 일치(필수 — 하이픈 표기 차이는 정규화로 흡수한다)"),
        field("code", JsonFieldType.STRING, "발송된 인증번호(필수, 빈값 불가)"));
  }

  public static List<FieldDescriptor> signupPhoneVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field(
            "data.verified",
            JsonFieldType.BOOLEAN,
            "검증 완료 여부 — 성공 응답은 항상 true. 인증 마커는 30분만 유지되므로 그 안에 회원가입을 제출해야 하고, 넘기면 422 AUTH_PHONE_NOT_VERIFIED라 다시 인증해야 한다"),
        errorNull());
  }

  public static List<FieldDescriptor> webSignupRequestFields() {
    return List.of(
        field("name", JsonFieldType.STRING, "성·이름을 합친 단일 이름(필수, 빈값 불가, 200자 이내)"),
        field("birthDate", JsonFieldType.STRING, "생년월일 YYYY-MM-DD(필수, 과거 날짜만 — 형식 위반·미래는 400)"),
        field(
            "phoneNumber",
            JsonFieldType.STRING,
            "가입용 SMS 인증을 마친 휴대폰 번호(필수 — 하이픈은 선택이며 서버가 정규화한다). 미인증·만료는 422이고, 이 값이 기존 앱 계정과의 유일한 연동 키다"),
        field(
            "email",
            JsonFieldType.STRING,
            "웹 로그인 ID(필수, 이메일 형식, 255자 이내). 중복이면 409 AUTH_EMAIL_ALREADY_REGISTERED다. 신규 가입일 때만 회원 프로필 이메일로도 기록한다"),
        field(
            "password",
            JsonFieldType.STRING,
            "비밀번호(필수) — 영문자 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, 길이 8~20, 공백 불가. 위반은 400 INVALID_INPUT(errors[].field=password)"),
        field("termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의(필수). false면 422"),
        field("privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의(필수). false면 422"),
        optField("marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의(선택, 보내지 않으면 false)"));
  }

  /**
   * 가입 성공 응답. <b>{@code data.refreshToken}이 없는 것이 계약이다</b>(ADR-0048) — 회전 대상 refresh는 {@code
   * Set-Cookie}로만 내려가므로 여기에 기술자를 추가하면 안 된다.
   */
  public static List<FieldDescriptor> webSignupResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.linked",
            JsonFieldType.BOOLEAN,
            "기존 앱 계정에 웹 자격증명만 붙였으면 true(새 회원을 만들지 않았다), 새 계정을 만들었으면 false"),
        field(
            "data.onboardingRequired",
            JsonFieldType.BOOLEAN,
            "온보딩 필요 여부 — 웹 가입은 한 번에 완주해 항상 false"),
        codeField("data.status", WEB_ACCOUNT_STATUSES, "사용자 상태 — 웹 계정은 부분 완료가 없어 항상 ACTIVE"),
        codeField("data.tokenType", TOKEN_TYPES, "토큰 타입 — 항상 Bearer"),
        field("data.accessToken", JsonFieldType.STRING, "정식 access 토큰(JWT)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(3600)"),
        field(
            "data.email",
            JsonFieldType.STRING,
            "회원 프로필 이메일 — 연동된 계정이면 폼에 적은 웹 이메일이 아니라 소셜 진본 이메일이 나갈 수 있다"),
        optField(
            "data.name",
            JsonFieldType.STRING,
            "회원 프로필 이름 — 연동 시에는 폼 값이 아니라 기존 값이며, 기존 값이 없으면 null이다"),
        errorNull());
  }

  public static List<FieldDescriptor> webLoginRequestFields() {
    return List.of(
        field(
            "email",
            JsonFieldType.STRING,
            "웹 로그인 ID(필수, 이메일 형식, 빈값 불가). 형식은 맞지만 등록되지 않은 주소는 400이 아니라 401이다"),
        field("password", JsonFieldType.STRING, "비밀번호(필수, 빈값 불가). 값이 틀리면 401이다"));
  }

  /** 로그인 성공 응답. {@link #webSignupResponseFields}에서 {@code linked}만 뺀 모양이며 refresh가 없는 이유도 같다. */
  public static List<FieldDescriptor> webLoginResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.onboardingRequired",
            JsonFieldType.BOOLEAN,
            "온보딩 필요 여부 — 웹 로그인은 재개 분기가 없어 항상 false"),
        codeField("data.status", WEB_ACCOUNT_STATUSES, "사용자 상태 — 웹 계정은 부분 완료가 없어 항상 ACTIVE"),
        codeField("data.tokenType", TOKEN_TYPES, "토큰 타입 — 항상 Bearer"),
        field("data.accessToken", JsonFieldType.STRING, "정식 access 토큰(JWT)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(3600)"),
        field(
            "data.email",
            JsonFieldType.STRING,
            "회원 프로필 이메일 — 로그인 ID가 아니라 프로필의 정본이라 앱 계정에 연동된 임대인은 로그인에 쓴 주소와 다를 수 있다"),
        optField("data.name", JsonFieldType.STRING, "회원 프로필 이름 — 아직 이름이 없으면 null이다"),
        errorNull());
  }

  /**
   * 이메일 찾기용 발송·확인은 가입용({@link #signupPhoneCodeRequestFields})과 응답 모양이 같지만 <b>기술자를 공유하지 않는다</b> —
   * path가 달라 다른 오퍼레이션이고, 같은 문구를 쓰면 "키스페이스가 갈려 마커가 서로 통하지 않는다"는 이 경로만의 계약을 적을 자리가 사라진다.
   */
  public static List<FieldDescriptor> findEmailPhoneCodeRequestFields() {
    return List.of(
        field(
            "phoneNumber",
            JsonFieldType.STRING,
            "인증번호를 받을 휴대폰 번호(필수, 빈값 불가 — 하이픈은 선택이며 서버가 숫자만 남겨 정규화한다). 가입 이력이 없는 번호도 같은 응답이라 이 값으로 계정 존재를 알 수 없다"));
  }

  public static List<FieldDescriptor> findEmailPhoneCodeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "인증번호 만료까지 초"),
        errorNull());
  }

  public static List<FieldDescriptor> findEmailPhoneVerifyRequestFields() {
    return List.of(
        field("phoneNumber", JsonFieldType.STRING, "인증번호를 발송한 번호와 일치(필수 — 하이픈 표기 차이는 정규화로 흡수한다)"),
        field("code", JsonFieldType.STRING, "발송된 인증번호(필수, 빈값 불가 — 자릿수·형식은 검증하지 않는다)"));
  }

  public static List<FieldDescriptor> findEmailPhoneVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field(
            "data.verified",
            JsonFieldType.BOOLEAN,
            "검증 완료 여부 — 성공 응답은 항상 true. 마커는 30분만 유지되고 소비처는 가입 이메일 찾기 하나뿐이라, 가입용 마커와 서로 통하지 않는다"),
        errorNull());
  }

  public static List<FieldDescriptor> findEmailRequestFields() {
    return List.of(
        field(
            "phoneNumber",
            JsonFieldType.STRING,
            "이메일 찾기용 SMS 인증을 마친 휴대폰 번호(필수 — 하이픈은 선택이며 서버가 정규화한다). 마커 부재·만료는 422이고 가입용 마커로는 통과하지 못한다"),
        field(
            "name",
            JsonFieldType.STRING,
            "가입 폼에 적은 이름(필수, 빈값 불가, 200자 이내). local_accounts.name과 대조하며(회원 프로필 이름 폴백 없음) 불일치는 계정 미존재와 같은 404다"));
  }

  public static List<FieldDescriptor> findEmailResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.email",
            JsonFieldType.STRING,
            "웹 로그인 ID(local_accounts.email)를 마스킹한 값(예: ki***@work.com) — 회원 프로필 이메일이 아니라 로그인 화면에 입력할 ID다"),
        errorNull());
  }

  public static List<FieldDescriptor> passwordResetLinkRequestFields() {
    return List.of(
        field(
            "email",
            JsonFieldType.STRING,
            "웹 로그인 ID(필수, 이메일 형식, 255자 이내). 형식만 맞으면 가입 여부와 무관하게 200이며 미가입 주소는 메일만 나가지 않는다"));
  }

  public static List<FieldDescriptor> passwordResetLinkResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.expiresIn",
            JsonFieldType.NUMBER,
            "링크 유효 시간(초). 가입 여부와 무관하게 항상 같은 값이며 「계정을 찾지 못했다」를 담을 필드는 응답에 아예 없다"),
        errorNull());
  }

  public static List<FieldDescriptor> passwordResetTokenVerifyRequestFields() {
    return List.of(
        field(
            "token",
            JsonFieldType.STRING,
            "메일 링크의 token 값(필수, 빈값 불가). 서버는 해시로 대조하며 이 호출은 토큰을 소비하지 않는다"));
  }

  public static List<FieldDescriptor> passwordResetTokenVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.email", JsonFieldType.STRING, "토큰이 가리키는 계정의 로그인 이메일을 마스킹한 값(예: ki***@work.com)"),
        field(
            "data.expiresIn",
            JsonFieldType.NUMBER,
            "링크 만료까지 남은 초 — 발급 시 고정값이 아니라 호출 시점 기준이라 호출할 때마다 줄어든다"),
        errorNull());
  }

  /**
   * 재설정 확정 요청. <b>대응하는 응답 기술자가 없는 것이 계약이다</b> — 성공 응답이 204라 본문이 없고, {@code responseFields}는 strict라
   * 빈 본문에 기술자를 넘기면 그 자리에서 실패한다. {@code Set-Cookie}도 싣지 않는다(새 세션을 만들지 않는다).
   */
  public static List<FieldDescriptor> passwordResetRequestFields() {
    return List.of(
        field(
            "token",
            JsonFieldType.STRING,
            "메일 링크의 token 값(필수, 빈값 불가). 부재·만료·이미 사용됨은 모두 422이며, 성공하면 그 자리에서 소비된다"),
        field(
            "newPassword",
            JsonFieldType.STRING,
            "새 비밀번호(필수) — 회원가입과 같은 정책: 영문자 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, 길이 8~20, 공백 불가. 위반은 400 INVALID_INPUT(errors[].field=newPassword)이며 이때 토큰은 소비되지 않는다"));
  }
}
