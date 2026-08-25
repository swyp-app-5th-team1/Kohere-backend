package com.kohere.auth.application;

import com.kohere.auth.application.dto.BusinessVerifyResponse;
import com.kohere.auth.application.dto.EmailVerificationCodeResponse;
import com.kohere.auth.application.dto.EmailVerifyResponse;
import com.kohere.auth.application.dto.OnboardingResponse;
import com.kohere.auth.application.dto.PhoneVerificationCodeResponse;
import com.kohere.auth.application.dto.PhoneVerifyResponse;
import com.kohere.auth.application.dto.SocialLoginResponse;
import com.kohere.auth.application.dto.TermsResponse;
import com.kohere.auth.application.dto.TokenResponse;
import com.kohere.auth.domain.AppUserOnlyException;
import com.kohere.auth.domain.AppleAuthClient;
import com.kohere.auth.domain.EmailMismatchException;
import com.kohere.auth.domain.EmailRequiredException;
import com.kohere.auth.domain.InvalidRefreshTokenException;
import com.kohere.auth.domain.LandlordOnlyException;
import com.kohere.auth.domain.MissingCredentialException;
import com.kohere.auth.domain.OidcTokenVerifier;
import com.kohere.auth.domain.OidcUser;
import com.kohere.auth.domain.OnboardingAlreadyCompletedException;
import com.kohere.auth.domain.Provider;
import com.kohere.auth.domain.RefreshToken;
import com.kohere.auth.domain.RefreshTokenHasher;
import com.kohere.auth.domain.RefreshTokenRepository;
import com.kohere.auth.domain.RefreshTokenStatus;
import com.kohere.auth.domain.RequiredAgreementMissingException;
import com.kohere.auth.domain.SocialAccount;
import com.kohere.auth.domain.SocialAccountRepository;
import com.kohere.auth.domain.TermsAgreementRequiredException;
import com.kohere.auth.presentation.dto.BusinessVerifyRequest;
import com.kohere.auth.presentation.dto.EmailVerificationCodeRequest;
import com.kohere.auth.presentation.dto.EmailVerifyRequest;
import com.kohere.auth.presentation.dto.LandlordOnboardingRequest;
import com.kohere.auth.presentation.dto.OnboardingRequest;
import com.kohere.auth.presentation.dto.PhoneVerificationCodeRequest;
import com.kohere.auth.presentation.dto.PhoneVerifyRequest;
import com.kohere.auth.presentation.dto.SocialLoginRequest;
import com.kohere.auth.presentation.dto.TermsRequest;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.request.PhoneNumbers;
import com.kohere.common.request.RequestDates;
import com.kohere.common.security.JwtTokenService;
import com.kohere.user.api.LandlordOnboardingProfile;
import com.kohere.user.api.OnboardingProfile;
import com.kohere.user.api.TermsAgreementView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import com.kohere.user.api.UserProfileView;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 인증·온보딩 유스케이스 조율(ADR-0003/0006/0010/0011). 소셜 OIDC 검증·서버 JWT 발급·refresh 회전/재사용 탐지/무효화를 담당하고, 약관
 * 동의·회원 생성·상태 전이는 user 공개 API({@link UserAccountService})와 협력한다(user 내부 타입 비참조). 이메일 인증은 {@link
 * EmailVerificationService}에 위임한다.
 *
 * <p>상태 흐름: 소셜 로그인(PENDING) → 약관 동의(TERMS_AGREED) → 온보딩(ACTIVE). 응답의 {@code status}로 클라이언트가 재개 지점을
 * 분기한다. 이름·이메일은 소셜 로그인 시점에 provider 값으로 확정하고(#192), 세입자 이메일 인증(§3·§4)은 온보딩 흐름이 아니라 정식(ACTIVE) 사용자
 * 전용이다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

  private static final String TOKEN_TYPE = "Bearer";
  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String STATUS_PENDING = "PENDING";
  private static final String USER_TYPE_LANDLORD = "LANDLORD";
  private static final String USER_TYPE_TENANT = "TENANT";

  /**
   * 세입자 온보딩 응답의 {@code linked} — <b>상수 false다.</b> 병합 매칭 키는 SMS로 인증한 휴대폰 번호 단독인데 세입자는 온보딩에서 번호를
   * 수집하지 않아 대조할 열쇠가 없다(ADR-0047 §3). 리터럴 {@code false}를 그냥 넘기면 "아직 안 붙인 것"과 구분되지 않으므로, 값이 아니라 이름으로
   * 이유를 남긴다.
   */
  private static final boolean TENANT_NEVER_MERGES = false;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final OidcTokenVerifier oidcTokenVerifier;
  private final SocialAccountRepository socialAccountRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final RefreshTokenHasher refreshTokenHasher;
  private final JwtTokenService jwtTokenService;
  private final UserAccountService userAccountService;
  private final EmailVerificationService emailVerificationService;
  private final PhoneVerificationService phoneVerificationService;
  private final BusinessVerificationService businessVerificationService;
  private final AuthProperties authProperties;
  private final AppleAuthClient appleAuthClient;

  /**
   * 소셜 로그인. provider별 자격을 검증해 신원을 얻은 뒤 (기존 ACTIVE)→정식 토큰 / (기존 PENDING·TERMS_AGREED·신규)→온보딩 임시 토큰을
   * 발급한다. <b>Google</b>은 전달받은 {@code idToken}을, <b>Apple</b>은 {@code authorizationCode}를 {@code
   * /auth/token}에서 교환해 받은 {@code id_token}을 검증 대상으로 한다(ADR-0031). 응답 {@code status}로 클라이언트가 재개 지점을
   * 분기한다.
   */
  @Transactional
  public SocialLoginResponse socialLogin(SocialLoginRequest request) {
    ResolvedIdentity identity = resolveIdentity(request);
    OidcUser oidcUser = identity.oidcUser();
    String appleRefreshToken = identity.appleRefreshToken();

    Optional<SocialAccount> existing =
        socialAccountRepository.findByProviderAndProviderUserId(
            oidcUser.provider(), oidcUser.subject());

    if (existing.isPresent()) {
      SocialAccount account = existing.get();
      long userId = account.getUserId();
      // 기존 로그인: User는 건드리지 않고 SocialAccount 스냅샷만 upsert한다(provider 값 변경 반영).
      // email은 토큰 값이 있으면 갱신·없으면 기존값 보존, name은 요청 값이 non-blank일 때만 갱신(없으면 기존값 보존 —
      // Apple 재로그인은 name 미제공), appleRefreshToken은 Apple이 새로 반환했을 때만 갱신(ADR-0031 #4).
      String snapEmail =
          StringUtils.hasText(oidcUser.email()) ? oidcUser.email() : account.getEmail();
      String snapName = StringUtils.hasText(request.name()) ? request.name() : account.getName();
      String snapAppleRefreshToken =
          providerOf(request) == Provider.APPLE && StringUtils.hasText(appleRefreshToken)
              ? appleRefreshToken
              : account.getAppleRefreshToken();
      socialAccountRepository.save(
          account.toBuilder()
              .email(snapEmail)
              .name(snapName)
              .appleRefreshToken(snapAppleRefreshToken)
              .build());
      UserAccountView acct = userAccountService.getAccount(userId);
      if (STATUS_ACTIVE.equals(acct.status())) {
        TokenResponse tokens = issueFullTokens(userId);
        return new SocialLoginResponse(
            false,
            STATUS_ACTIVE,
            tokens.tokenType(),
            tokens.accessToken(),
            tokens.refreshToken(),
            tokens.expiresIn(),
            acct.email(),
            acct.name());
      }
      // 기존 미완료(PENDING·TERMS_AGREED) 재로그인 → 온보딩 임시 토큰 재발급(신규 행 미생성)
      return onboardingResponse(userId, acct.status(), acct.email(), acct.name());
    }

    // 신규(최초 로그인): email 교차 검증 후 provider 진본으로 확정한다. name은 검증하지 않고 요청 값을 신뢰한다(#192).
    String tokenEmail = oidcUser.email();
    String reqEmail = request.email();
    if (StringUtils.hasText(tokenEmail)
        && StringUtils.hasText(reqEmail)
        && !tokenEmail.equalsIgnoreCase(reqEmail)) {
      throw new EmailMismatchException();
    }
    String email = StringUtils.hasText(tokenEmail) ? tokenEmail : reqEmail;
    if (!StringUtils.hasText(email)) {
      throw new EmailRequiredException();
    }
    // user PENDING 회원 생성(이름·이메일 즉시 세팅) + social_accounts 매핑 생성
    long userId = userAccountService.createPendingUser(request.name(), email);
    socialAccountRepository.save(
        SocialAccount.builder()
            .provider(oidcUser.provider())
            .providerUserId(oidcUser.subject())
            .email(email)
            .name(request.name())
            .userId(userId)
            .linkedAt(Instant.now())
            .appleRefreshToken(appleRefreshToken)
            .build());
    return onboardingResponse(userId, STATUS_PENDING, email, request.name());
  }

  /**
   * provider별 자격을 검증해 신원(과 Apple refresh token)을 얻는다. Google은 전달받은 {@code idToken}을 그대로 검증하고,
   * Apple은 {@code authorizationCode}를 {@code /auth/token}에서 교환해 받은 {@code id_token}을 동일 {@link
   * OidcTokenVerifier}로 재검증한다 — 교환 응답을 맹신하지 않는다(ADR-0031 #1). provider별 필수 자격 누락은 400 {@code
   * AUTH_MISSING_CREDENTIAL}.
   */
  /**
   * 요청의 {@code provider} 문자열을 {@link Provider}로 파싱한다. 허용 외 값은 {@code MALFORMED_REQUEST}가 아니라 {@code
   * INVALID_INPUT} + {@code errors[].field}다 — 본문 JSON은 멀쩡하고 값 하나가 틀린 상황이기 때문이다(#151).
   */
  private static Provider providerOf(SocialLoginRequest request) {
    try {
      return Provider.valueOf(request.provider());
    } catch (IllegalArgumentException e) {
      throw new InvalidInputException("provider", "validation.notAllowed", request.provider());
    }
  }

  private ResolvedIdentity resolveIdentity(SocialLoginRequest request) {
    return switch (providerOf(request)) {
      case GOOGLE -> {
        requireCredential(request.idToken());
        yield new ResolvedIdentity(
            oidcTokenVerifier.verify(Provider.GOOGLE, request.idToken()), null);
      }
      case APPLE -> {
        requireCredential(request.authorizationCode());
        // 인가코드는 1회용(약 5분)이라 즉시 교환한다. 교환은 HTTP라 롤백되지 않는 반면 받아온 refresh token은
        // 커밋 전까지 메모리에만 있어, 이후 단계가 실패하면 소모된 인가코드만 남고 토큰은 사라진다.
        // Apple은 재로그인마다 새 인가코드로 refresh token을 다시 내려주므로(ADR-0031 #4 — 최초 1회만 내려오는 건
        // refresh token이 아니라 email·fullName이다) 다음 로그인에 백필되지만, 그 창 안에 탈퇴하면 /auth/revoke를
        // 스킵한다(skip+WARN, UserWithdrawnEventListener). ADR-0031이 수용한 best-effort 범위(durable 재시도
        // 없음)다.
        // 교환을 트랜잭션 밖으로 빼면 외부 호출(connect 3s + read 5s) 동안 DB 커넥션을 잡지 않아 롤백을 부르는
        // 원인(풀 고갈·락 대기·트랜잭션 타임아웃)이 줄어 빈도는 낮아지지만, 토큰이 커밋 전까지 메모리에만 있는 건
        // 같아서 창이 없어지지는 않는다 — 없애려면 토큰을 별도 커밋으로 먼저 저장해야 하는데 신규 가입은 아직 붙일
        // social_accounts 행이 없어 구조 변경이 필요하다.
        AppleAuthClient.AppleTokens tokens =
            appleAuthClient.exchangeAuthorizationCode(request.authorizationCode());
        yield new ResolvedIdentity(
            oidcTokenVerifier.verify(Provider.APPLE, tokens.idToken()), tokens.refreshToken());
      }
    };
  }

  private static void requireCredential(String credential) {
    if (!StringUtils.hasText(credential)) {
      throw new MissingCredentialException();
    }
  }

  /** {@link #resolveIdentity} 결과 — 검증된 신원과 (Apple만) 교환받은 refresh token(없으면 null). */
  private record ResolvedIdentity(OidcUser oidcUser, String appleRefreshToken) {}

  /** 약관 동의(PENDING→TERMS_AGREED). 필수 약관 미동의는 422. 토큰은 갱신하지 않는다(상태만 전이). */
  @Transactional
  public TermsResponse agreeToTerms(long userId, TermsRequest request) {
    if (!Boolean.TRUE.equals(request.termsOfServiceAgreed())
        || !Boolean.TRUE.equals(request.privacyPolicyAgreed())) {
      throw new RequiredAgreementMissingException();
    }
    TermsAgreementView view =
        userAccountService.agreeToTerms(userId, Boolean.TRUE.equals(request.marketingAgreed()));
    return new TermsResponse(
        view.status(),
        view.termsOfServiceAgreed(),
        view.privacyPolicyAgreed(),
        view.marketingAgreed(),
        view.agreedAt());
  }

  /**
   * 이메일 인증번호 발송. 정식(ACTIVE) 사용자 전용으로, <b>상태</b> 게이트(정식 토큰·ROLE_USER)는 보안 필터(SecurityConfig)가
   * 담당한다(#192 — 온보딩 단계 전용→정식 전용 반전). <b>유형</b> 게이트는 여기서 건다 — {@link #requireAppUser}로 세입자·임대인만 통과시켜
   * 관리자를 막는다(#265). 상태 게이트가 ACTIVE를 보장하므로 이 시점의 {@code userType}은 이미 확정돼 있다. 동기 발송 성공 시에만 챌린지를
   * 저장한다(발송 실패 502).
   */
  @Transactional(readOnly = true)
  public EmailVerificationCodeResponse sendEmailVerificationCode(
      long userId, EmailVerificationCodeRequest request) {
    requireAppUser(userId);
    long expiresIn = emailVerificationService.sendCode(userId, request.email());
    return new EmailVerificationCodeResponse(maskEmail(request.email()), expiresIn);
  }

  /** 이메일 인증번호 확인. 성공 시 이메일을 검증 완료로 마킹한다. */
  @Transactional(readOnly = true)
  public EmailVerifyResponse verifyEmail(long userId, EmailVerifyRequest request) {
    requireAppUser(userId);
    emailVerificationService.verify(userId, request.email(), request.code());
    return new EmailVerifyResponse(maskEmail(request.email()), true);
  }

  /**
   * 온보딩 완료. 약관 미동의(PENDING)면 422 AUTH_TERMS_AGREEMENT_REQUIRED로, 이미 완료(ACTIVE)면 409로 거절한다. 이름·이메일은
   * 소셜 로그인 시점에 이미 세팅됐으므로 온보딩에서 받지 않는다(#192 — 이메일 인증 선행 게이트도 제거). user에 TERMS_AGREED→ACTIVE 전이를 위임한
   * 뒤 정식 토큰을 발급한다.
   */
  @Transactional
  public OnboardingResponse onboarding(long userId, OnboardingRequest request) {
    assertTermsAgreed(userId);
    UserProfileView user =
        userAccountService.completeOnboarding(
            userId,
            new OnboardingProfile(
                request.gender(),
                RequestDates.parsePast("birthDate", request.birthDate()),
                request.country(),
                request.occupation(),
                request.visaType(),
                request.lang()));
    TokenResponse tokens = issueFullTokens(userId);
    // 세입자는 병합 분기가 아예 없다 — 응답 타입을 임대인과 공유할 뿐이다(상수 javadoc 참조).
    return new OnboardingResponse(
        TENANT_NEVER_MERGES,
        user,
        tokens.tokenType(),
        tokens.accessToken(),
        tokens.refreshToken(),
        tokens.expiresIn());
  }

  /**
   * 연락처 인증번호 발송. 약관 동의(TERMS_AGREED) 이상이면 진행한다 — 온보딩(US-1-10, TERMS_AGREED)과 프로필 연락처 변경(US-1-5,
   * ACTIVE) 두 컨텍스트 모두 허용하고, 약관 미동의(PENDING)만 422 AUTH_TERMS_AGREEMENT_REQUIRED로 거절한다(세입자 이메일 인증 §3과
   * 대칭이나, 정식 토큰 재인증을 허용해 프로필 연락처 변경을 지원 — ADR-0034 §6·§8). 동기 발송 성공 시에만 챌린지를 저장한다(발송 실패 502).
   */
  @Transactional(readOnly = true)
  public PhoneVerificationCodeResponse sendPhoneVerificationCode(
      long userId, PhoneVerificationCodeRequest request) {
    assertPhoneVerificationAllowed(userId);
    long expiresIn = phoneVerificationService.sendCode(userId, request.phoneNumber());
    return new PhoneVerificationCodeResponse(maskPhone(request.phoneNumber()), expiresIn);
  }

  /** 임대인 연락처 인증번호 확인. 성공 시 연락처를 검증 완료로 마킹한다. */
  @Transactional(readOnly = true)
  public PhoneVerifyResponse verifyPhone(long userId, PhoneVerifyRequest request) {
    phoneVerificationService.verify(userId, request.phoneNumber(), request.code());
    return new PhoneVerifyResponse(maskPhone(request.phoneNumber()), true);
  }

  /**
   * 임대인 사업자등록번호 검증(무상태). 온보딩과 분리된 임대인 전용 API로, 온보딩을 마친(ACTIVE) 임대인이 나중에(매물 등록 시점) 호출한다 — 정식
   * 토큰(ACTIVE, ROLE_USER)은 보안 필터가, 임대인(userType=LANDLORD) 여부는 {@link #assertLandlord}가 확인한다(임대인 아님
   * 403 FORBIDDEN). 외부 검증 서비스로 동기 검증해 정상(계속) 사업자면 verified=true를 응답하고(미등록·휴폐업·진위실패 422, 외부 장애 502),
   * 결과는 영속하지 않는다. 시퀀스 US-1-8.
   */
  @Transactional(readOnly = true)
  public BusinessVerifyResponse verifyBusiness(long userId, BusinessVerifyRequest request) {
    assertLandlord(userId);
    businessVerificationService.verify(request.businessRegistrationNumber());
    return new BusinessVerifyResponse(
        maskBusinessNumber(request.businessRegistrationNumber()), true);
  }

  /**
   * 임대인 온보딩 완료. 검증 게이트를 약관 미동의 → 연락처 미인증 우선순위로 통과시킨다 — 약관 미동의(PENDING) 422
   * AUTH_TERMS_AGREEMENT_REQUIRED(이미 ACTIVE면 409), 제출 phoneNumber 미인증·불일치 422
   * AUTH_PHONE_NOT_VERIFIED. 통과 시 user에 TERMS_AGREED→ACTIVE 전이(userType=LANDLORD 확정)를 위임하고 정식 토큰을
   * 발급한다. 사업자등록번호는 온보딩에서 수집하지 않으며, 온보딩 후 별도 검증 API(POST /auth/business/verify)로 검증한다(ADR-0033, 시퀀스
   * US-1-9).
   *
   * <p><b>게이트를 통과한 뒤 병합 분기가 하나 붙는다</b>(US-1-15) — 인증된 번호로 <b>자기가 아닌</b> ACTIVE·LANDLORD 계정이 잡히면 웹에서
   * 먼저 가입한 같은 사람이므로 계정을 합친다({@link #mergeIntoExistingLandlord}). 못 찾으면 US-1-9 동작이 그대로다.
   *
   * <p><b>분기 순서가 곧 보안 계약이다.</b> 병합은 반드시 SMS 인증 게이트 <b>뒤</b>여야 한다 — 앞에 두면 번호만 아는 사람이 남의 웹 계정을 흡수하는
   * 경로가 열린다. 번호는 조회 키일 뿐 인증 수단이 아니고, 소유 증명은 전적으로 {@link PhoneVerificationService}가 담당한다(ADR-0047
   * §3). 인증 마커가 없으면 병합 판정 자체에 닿지 않는다.
   *
   * <p><b>생년월일은 분기 전에 파싱한다.</b> 병합 경로는 대상 프로필을 덮어쓰지 않아 이 값을 버리지만(ADR-0047 §6), 파싱을 완료 호출 안으로 미루면
   * <b>같은 잘못된 요청이 한쪽에선 400, 다른 쪽에선 200</b>이 되어 "무엇이 유효한 요청인가"가 병합 여부에 따라 갈린다. 게이트 순서(약관 → 연락처 → 본문
   * 날짜)는 종전 그대로 유지한다.
   *
   * <p><b>토큰과 응답 프로필은 언제나 같은 계정 기준이다</b> — {@code issueFullTokens(user.id())}가 그것을 구조로 못박는다. 병합이면
   * {@code user.id()}가 요청 토큰의 {@code userId}와 다르며(대상 계정), 클라이언트는 응답의 토큰으로 교체해야 한다(스펙 §5-2). 두 값을 따로
   * 넘겨받는 코드였다면 병합 분기에서 <b>토큰만 임시 계정으로 발급</b>하는 실수가 조용히 성립한다 — 방금 지운 행을 가리키는 토큰이다.
   *
   * <p><b>병합 여부는 응답의 {@code linked}로 명시해서 알린다</b>(US-1-15). 종전에는 {@code user.id()}가 요청 토큰의 {@code
   * userId}와 다른 것을 클라이언트가 스스로 눈치채야 병합을 알 수 있었는데, 그 비교를 빠뜨려도 화면은 멀쩡해 보이고 낡은 토큰으로 다음 호출을 하고 나서야 깨진다.
   * 그래서 서버가 아는 사실을 필드로 내린다 — 이름은 반대 방향({@code SignupResponse.linked})과 <b>같은 단어</b>다.
   */
  @Transactional
  public OnboardingResponse landlordOnboarding(long userId, LandlordOnboardingRequest request) {
    assertTermsAgreed(userId);
    phoneVerificationService.assertVerified(userId, request.phoneNumber());
    // users.phone_number·병합 조회 모두 인증 마커와 같은 표준형을 쓴다 — UNIQUE(V23)가 표기 차이로 뚫리지 않게(#229 D10).
    String verifiedPhoneNumber = PhoneNumbers.normalize(request.phoneNumber());
    LocalDate birthDate = RequestDates.parsePast("birthDate", request.birthDate());
    // 분기 판정을 Optional 하나로 묶어 둔다 — linked와 실제로 실행한 경로가 같은 값에서 나오므로 둘이 갈릴 수 없다.
    // (프로필만 받아 두고 linked를 따로 계산하면 "id가 다르면 병합" 같은 추정 규칙이 서버 안에 생긴다.)
    Optional<UserProfileView> mergeTarget =
        userAccountService.findActiveLandlordProfileByPhoneNumberExcluding(
            verifiedPhoneNumber, userId);
    boolean linked = mergeTarget.isPresent();
    UserProfileView user =
        mergeTarget
            .map(target -> mergeIntoExistingLandlord(userId, target))
            .orElseGet(
                () ->
                    userAccountService.completeLandlordOnboarding(
                        userId, new LandlordOnboardingProfile(verifiedPhoneNumber, birthDate)));
    // 토큰은 여전히 user.id()에서만 나온다 — linked를 도입해도 "토큰은 임시 계정, 응답은 대상 계정"이
    // 성립할 자리를 만들지 않는다(위 javadoc의 불변식).
    TokenResponse tokens = issueFullTokens(user.id());
    return new OnboardingResponse(
        linked,
        user,
        tokens.tokenType(),
        tokens.accessToken(),
        tokens.refreshToken(),
        tokens.expiresIn());
  }

  /**
   * 웹 계정 병합(US-1-15) — 같은 번호의 기존 임대인 계정으로 <b>앱 로그인의 열쇠를 옮기고 임시 계정을 지운다</b>. 대상 계정은 이미
   * ACTIVE·LANDLORD이므로 상태 전이도, 프로필 덮어쓰기도 없다. 옮기는 것은 {@code social_accounts} 행뿐이며 응답 프로필은 잠금 조회가 읽어
   * 둔 대상 값 그대로다.
   *
   * <p><b>병합이 안전한 이유는 임시 계정이 비어 있기 때문이다.</b> 실제로 이 id를 참조할 수 있는 것이 무엇인지 전부 훑으면 — {@code
   * social_accounts}(여기서 옮긴다) · {@code local_accounts}(웹 가입은 ACTIVE 임대인에만 붙으므로 임시 계정에는 생길 수 없다) ·
   * {@code bookings}·{@code booking_reports}·{@code user_blocks}·매물({@code
   * listings.landlordId})·찜·최근 본 매물(전부 {@code hasRole("USER")} 게이트라 온보딩 스코프 토큰이 닿지 못한다) · refresh
   * 토큰(정식 토큰을 받은 적이 없어 {@code refresh:user:{id}} 인덱스가 비어 있다) · {@code chat}·{@code
   * community}·{@code report}(영속 구현이 아직 없다)가 남고, <b>실제로 남는 것은 두 가지뿐</b>이다.
   *
   * <p>① <b>진단 문서(MongoDB {@code diagnoses}·{@code diagnosisFlowSessions})는 지우지 않는다</b>(#229 D3).
   * v2 진단이 permitAll이라 온보딩 스코프로도 문서가 만들어질 수 있어 사라진 계정을 가리키는 고아 문서가 남지만, <b>현재는 탈퇴조차 진단을 지우지
   * 않는다</b>({@link UserWithdrawnEventListener} 하나뿐인 구독자가 자격증명·refresh만 정리한다). 병합이 탈퇴보다 공격적으로 지우는
   * 비대칭을 만들지 않으며, 그 문서를 조회할 주체가 없어 실질 영향도 없다(수용한 제약).<br>
   * ② <b>Redis 연락처 인증 마커({@code phone-verify:verified:{임시 id}})</b>는 TTL(30분)로 자연 소멸한다. {@code
   * users.id}는 AUTO_INCREMENT라 지운 값이 재사용되지 않으므로 다른 계정이 이 마커를 주워 갈 수 없다.
   *
   * <p><b>소유권 사슬은 병합으로 흔들리지 않는다.</b> {@code listings.landlordId}·{@code bookings.landlord_id}·사진 S3
   * 키 접두사({@code uploads/{landlordId}/…})가 모두 id를 품지만, 병합이 <b>살리는 쪽이 이미 그 매물을 소유한 계정</b>이고 지우는 쪽은
   * 아무것도 가진 적이 없다 — 그래서 다시 쓸 데이터가 한 건도 없다. 유일한 실제 엣지는 <b>임시 계정 id로 진행 중이던 pending 업로드</b>가 고아가 되는
   * 것인데(키에 id가 박혀 있다), 병합은 소셜 로그인 직후 온보딩 제출에서만 일어나고 그 시점엔 업로드가 있을 수 없다(매물 등록·사진 업로드가 ACTIVE 전용이다).
   *
   * <p><b>두 DB 쓰기는 한 트랜잭션이다</b>(호출자 {@link #landlordOnboarding}의 트랜잭션에 {@code REQUIRED}로 참여). 매핑
   * 이전과 행 삭제 사이에서 실패해 커밋되면 {@code social_accounts}가 어느 계정에도 붙지 않아 <b>그 사용자의 앱 로그인이 영구히 깨진다</b> —
   * 그래서 삭제가 실패하면 이전도 함께 되돌아가는 것이 이 메서드가 보장하는 전부다.
   *
   * <p><b>그 보장은 DB 쓰기에서 끝난다.</b> {@link #issueFullTokens} 호출 자체는 이 트랜잭션 안에 있지만, 그 안에서 refresh 해시를
   * 쓰는 곳이 Redis라 <b>MySQL 롤백에 함께 되돌아가지 않는다</b>. 실제로 갈리는 순간은 {@code uq_users_phone_number} 경합이 <b>커밋
   * 시점</b>에 터질 때다 — 그때는 이미 토큰이 발급된 뒤라 DB만 되돌아가고 <b>refresh 토큰 해시가 14일 TTL로 Redis에 남는다</b>. 원문은 409
   * 응답으로 대체돼 클라이언트에 전달되지 않으므로 그 항목으로 세션을 열 수 없고(악용 불가), TTL로 자연 소멸한다. 대신 그 사이 {@code
   * refresh:user:{id}} 인덱스가 실제 세션보다 많아 보인다 — <b>수용한 한계</b>이며, 없애려면 토큰 발급을 두 트랜잭션 메서드 밖으로 들어내야 해서 문제
   * 크기에 비해 변경이 크다(US-1-15 시퀀스 문서의 알려진 제약).
   */
  private UserProfileView mergeIntoExistingLandlord(long temporaryUserId, UserProfileView target) {
    socialAccountRepository.reassignUserId(temporaryUserId, target.id());
    userAccountService.deleteAccount(temporaryUserId);
    return target;
  }

  /**
   * 재발급. 항상 회전 — 제출 토큰을 ROTATED로 폐기한다. <b>ROTATED 재제출(재사용 탐지)</b>은 탈취 정황이므로 사용자 전 토큰을 일괄 무효화하고,
   * <b>REVOKED(로그아웃·탈퇴)·만료</b>는 권한이 이미 0이라 해당 요청만 거부해 다른 기기 세션을 보존한다(OAuth 2.0 reuse detection).
   *
   * <p><b>인자는 채널이 이미 정해진 refresh 원문이다</b> — 쿠키에서 왔는지 요청 본문에서 왔는지는 컨트롤러가 흡수하고 여기서는 구분하지 않는다(ADR-0048
   * §3). 판정 규칙이 채널마다 갈리지 않게 하는 것이 이 분업의 목적이며, 그래서 서블릿 타입도 이 계층까지 오지 않는다.
   */
  @Transactional
  public TokenResponse reissue(String refreshToken) {
    String tokenHash = refreshTokenHasher.hash(requireRefreshToken(refreshToken));
    RefreshToken token =
        refreshTokenRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(InvalidRefreshTokenException::new);

    // 회전된 토큰 재등장 = 재사용/탈취 정황 → 사용자 전 세션 무효화
    if (token.getStatus() == RefreshTokenStatus.ROTATED) {
      refreshTokenRepository.revokeAllByUserId(token.getUserId());
      throw new InvalidRefreshTokenException();
    }
    // 이미 무효화(REVOKED)·만료 = 이 요청만 거부(다른 세션 보존)
    if (!token.isUsable(Instant.now())) {
      throw new InvalidRefreshTokenException();
    }
    refreshTokenRepository.save(token.rotate());
    return issueFullTokens(token.getUserId());
  }

  /**
   * 로그아웃. 제출 refresh를 REVOKED로 무효화(이미 무효화돼도 멱등). 인자의 출처(쿠키·본문)를 구분하지 않는 것은 {@link #reissue}와 같다.
   */
  @Transactional
  public void logout(String refreshToken) {
    String tokenHash = refreshTokenHasher.hash(requireRefreshToken(refreshToken));
    refreshTokenRepository
        .findByTokenHash(tokenHash)
        .ifPresent(token -> refreshTokenRepository.save(token.revoke()));
  }

  /**
   * 쿠키·본문 어느 쪽에서도 refresh를 찾지 못한 요청을 거른다 — 400 {@code INVALID_INPUT} + {@code
   * errors[].field=refreshToken}(#229 D12). 종전에는 본문 없는 요청이 {@code MALFORMED_REQUEST}였는데, 본문이 선택이 된
   * 뒤로는 본문이 깨진 것이 아니라 <b>값이 빠진 것</b>이라 코드가 바뀐다.
   *
   * <p><b>DTO의 {@code @NotBlank}로는 이 판정을 대신할 수 없다.</b> 본문 없는 요청은 Bean Validation을 아예 타지 않고(인자가
   * null이면 검증을 건너뛴다), 반대로 제약을 남겨 두면 쿠키에 멀쩡한 토큰이 있는데 본문의 빈 문자열 때문에 거절되는 <b>한 채널이 다른 채널을 막는</b> 상황이
   * 생긴다. 그래서 두 채널을 합친 뒤 한 곳에서 판정한다.
   */
  private static String requireRefreshToken(String refreshToken) {
    if (!StringUtils.hasText(refreshToken)) {
      throw new InvalidInputException("refreshToken", "validation.refreshTokenRequired");
    }
    return refreshToken;
  }

  /**
   * 이메일 인증·온보딩 선행 게이트 — 약관 동의(TERMS_AGREED)를 마쳐야 진행한다. 약관 미동의(PENDING)면 약관 동의 선행 안내(422
   * AUTH_TERMS_AGREEMENT_REQUIRED), 이미 온보딩 완료(ACTIVE)면 409. 상태 소유자는 user이므로 공개 API로 조회만 한다(판정 책임은
   * 흐름을 조율하는 auth).
   */
  private void assertTermsAgreed(long userId) {
    String status = userAccountService.getAccount(userId).status();
    if (STATUS_ACTIVE.equals(status)) {
      throw new OnboardingAlreadyCompletedException();
    }
    if (STATUS_PENDING.equals(status)) {
      throw new TermsAgreementRequiredException();
    }
  }

  /**
   * 연락처 인증 선행 게이트 — 약관 동의(TERMS_AGREED) 이상이면 통과한다. 온보딩(US-1-10, TERMS_AGREED)과 프로필 연락처 변경(US-1-5,
   * ACTIVE) 두 컨텍스트 모두 허용하고, 약관 미동의(PENDING)만 422 AUTH_TERMS_AGREEMENT_REQUIRED로 막는다 — 세입자 이메일
   * 인증(온보딩 전용, ACTIVE는 409)과 달리 정식 토큰(ACTIVE) 재인증을 허용한다(ADR-0034 §6·§8). 상태 소유자는 user이므로 공개 API로
   * 조회만 한다.
   */
  private void assertPhoneVerificationAllowed(long userId) {
    if (STATUS_PENDING.equals(userAccountService.getAccount(userId).status())) {
      throw new TermsAgreementRequiredException();
    }
  }

  /**
   * 사업자번호 검증 선행 게이트 — 임대인 전용. 정식 토큰(ACTIVE, ROLE_USER)은 보안 필터({@link
   * com.kohere.common.security.SecurityConfig})가 보장하므로, 여기서는 userType이 LANDLORD인지만 확인한다(임대인 아님 403
   * FORBIDDEN). 상태 소유자는 user이므로 공개 API로 조회만 한다(판정 책임은 흐름을 조율하는 auth).
   */
  /**
   * 세입자·임대인만 통과시키는 허용 목록 게이트다.
   *
   * <p>이메일 인증은 회원 본인의 연락 수단을 확인하는 절차라 관리자에게는 쓸 일이 없다. 관리자를 콕 집어 거부하지 않고 아는 두 유형만 통과시키므로 이 코드가
   * {@code ADMIN}을 알 필요가 없고, 나중에 회원 유형이 늘어도 자동으로 거부된다.
   */
  /**
   * 세입자·임대인만 통과시킨다(관리자 403). 허용 목록이라 새 유형이 생겨도 기본이 차단이다.
   *
   * <p><b>연락처 인증(§4-1·§4-2)에는 이 게이트를 걸 수 없다.</b> {@code userType}은 온보딩 <b>제출</b> 시점에 확정되는데, 임대인
   * 온보딩은 연락처 인증을 <b>선행</b>으로 요구한다 — 그 시점의 {@code userType}은 아직 null이라 허용 목록이 정상 가입을 막아 버린다. 그래서 이
   * 게이트는 보안 필터가 ACTIVE를 보장하는 엔드포인트(이메일 인증)에만 건다.
   */
  private void requireAppUser(long userId) {
    String userType = userAccountService.getUserType(userId);
    if (!USER_TYPE_TENANT.equals(userType) && !USER_TYPE_LANDLORD.equals(userType)) {
      throw new AppUserOnlyException();
    }
  }

  private void assertLandlord(long userId) {
    if (!USER_TYPE_LANDLORD.equals(userAccountService.getUserType(userId))) {
      throw new LandlordOnlyException();
    }
  }

  private SocialLoginResponse onboardingResponse(
      long userId, String status, String email, String name) {
    return new SocialLoginResponse(
        true,
        status,
        TOKEN_TYPE,
        jwtTokenService.issueOnboardingToken(userId),
        null,
        jwtTokenService.onboardingTtlSeconds(),
        email,
        name);
  }

  /**
   * 정식 토큰 발급 — access(JWT)와 불투명 refresh를 만들고 refresh 해시를 저장소에 남긴다(ADR-0003/0006/0011).
   *
   * <p><b>package-private인 것은 의도다.</b> 임대인 웹 트랙({@link WebAuthService})도 같은 토큰을 발급해야 하는데, 발급·회전·재사용
   * 탐지 규칙이 두 벌이 되면 <b>한쪽만 고친 버그가 조용히 살아남는다</b>(ADR-0048 §3). 그렇다고 {@code public}으로 올리면 auth 밖에서도
   * 토큰을 찍어낼 수 있는 문이 생기므로, 같은 패키지의 응용 서비스만 닿는 최소 가시성으로 연다. 웹과 앱이 실제로 다른 것은 refresh를 <b>본문으로 내리느냐 쿠키로
   * 내리느냐</b>뿐이고 그건 컨트롤러 한 겹의 일이다.
   */
  TokenResponse issueFullTokens(long userId) {
    String accessToken = jwtTokenService.issueAccessToken(userId);
    String rawRefresh = generateRefreshToken();
    Instant now = Instant.now();
    refreshTokenRepository.save(
        RefreshToken.issue(
            refreshTokenHasher.hash(rawRefresh),
            userId,
            now,
            now.plusSeconds(authProperties.getRefreshTtlSeconds())));
    return new TokenResponse(
        TOKEN_TYPE, accessToken, rawRefresh, jwtTokenService.accessTtlSeconds());
  }

  private static String generateRefreshToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** 응답·로그용 이메일 마스킹(예: {@code minh@example.com} → {@code mi***@example.com}). */
  private static String maskEmail(String email) {
    if (email == null) {
      return null;
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    String local = email.substring(0, at);
    String domain = email.substring(at);
    String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
    return visible + "***" + domain;
  }

  /** 응답·로그용 연락처 마스킹(예: {@code 01012345678} → {@code 010-****-5678}). */
  private static String maskPhone(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() < 4) {
      return "***";
    }
    String prefix = digits.substring(0, Math.min(3, digits.length() - 4));
    String suffix = digits.substring(digits.length() - 4);
    return prefix + "-****-" + suffix;
  }

  /** 응답·로그용 사업자등록번호 마스킹(예: {@code 1234567890} → {@code ****567890}). */
  private static String maskBusinessNumber(String number) {
    if (number == null) {
      return null;
    }
    String digits = number.replaceAll("\\D", "");
    if (digits.length() <= 6) {
      return "****" + digits;
    }
    return "****" + digits.substring(digits.length() - 6);
  }
}
