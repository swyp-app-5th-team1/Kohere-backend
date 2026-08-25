package com.kohere.user.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.request.PhoneNumbers;
import com.kohere.user.api.ApplicantProfileView;
import com.kohere.user.api.LandlordOnboardingProfile;
import com.kohere.user.api.OnboardingProfile;
import com.kohere.user.api.TermsAgreementView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import com.kohere.user.api.UserProfileView;
import com.kohere.user.domain.Country;
import com.kohere.user.domain.CountryRepository;
import com.kohere.user.domain.Gender;
import com.kohere.user.domain.Language;
import com.kohere.user.domain.NicknameGenerator;
import com.kohere.user.domain.Occupation;
import com.kohere.user.domain.User;
import com.kohere.user.domain.UserNotFoundException;
import com.kohere.user.domain.UserRepository;
import com.kohere.user.domain.UserType;
import com.kohere.user.domain.VisaType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * user 공개 API 구현. auth가 호출하는 회원 생성·약관 동의·온보딩 완료·계정 조회를 처리한다. 약관 버전은 서버 설정값(app.terms.version)을 약관
 * 동의 시 기록한다(ADR-0012). gender·occupation·visaType은 원시 문자열로 받아 enum으로 변환하고(유효하지 않으면 INVALID_INPUT —
 * 단, occupation은 선택이라 null은 미설정으로 저장한다, #187), country는 {@link CountryRepository}로 존재를 검증한다. 닉네임은
 * {@link NicknameGenerator}로 생성한다.
 */
@Service
public class UserAccountServiceImpl implements UserAccountService {

  private static final String DEFAULT_LANGUAGE = "en";

  private final UserRepository userRepository;
  private final CountryRepository countryRepository;
  private final NicknameGenerator nicknameGenerator;
  private final String termsVersion;

  public UserAccountServiceImpl(
      UserRepository userRepository,
      CountryRepository countryRepository,
      NicknameGenerator nicknameGenerator,
      @Value("${app.terms.version}") String termsVersion) {
    this.userRepository = userRepository;
    this.countryRepository = countryRepository;
    this.nicknameGenerator = nicknameGenerator;
    this.termsVersion = termsVersion;
  }

  @Override
  @Transactional
  public long createPendingUser(String name, String email) {
    return userRepository.save(User.createPending(name, email, Instant.now())).getId();
  }

  @Override
  @Transactional
  public TermsAgreementView agreeToTerms(long userId, boolean marketingAgreed) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    User saved =
        userRepository.save(user.agreeToTerms(marketingAgreed, termsVersion, Instant.now()));
    return new TermsAgreementView(
        saved.getStatus().name(),
        saved.isTermsOfServiceAgreed(),
        saved.isPrivacyPolicyAgreed(),
        saved.isMarketingAgreed(),
        saved.getAgreedAt());
  }

  @Override
  @Transactional
  public UserProfileView completeOnboarding(long userId, OnboardingProfile profile) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    Gender gender = parseEnum(Gender.class, "gender", profile.gender());
    Occupation occupation = parseOccupation(profile.occupation());
    VisaType visaType = parseEnum(VisaType.class, "visaType", profile.visaType());
    if (profile.country() == null || !countryRepository.existsByCode(profile.country())) {
      throw new InvalidInputException(
          "country", "validation.unsupportedCountry", profile.country());
    }
    Language lang = parseLanguage(profile.lang());
    String nickname = nicknameGenerator.generateUnique();
    User active =
        user.completeOnboarding(
            nickname,
            gender,
            profile.birthDate(),
            profile.country(),
            occupation,
            visaType,
            lang,
            Instant.now());
    return toProfileView(userRepository.save(active));
  }

  /**
   * 임대인 온보딩 완료. 연락처는 저장 직전에 한 번 더 표준형으로 접는다({@link PhoneNumbers}) — 호출자(앱 온보딩·웹 가입·테스트 시드)가 이미 접어
   * 넘기지만, {@code users.phone_number}의 UNIQUE(V23)는 표기가 하나라도 어긋나면 계정이 갈라지는 것을 막지 못하므로 규약이 아니라 <b>쓰기
   * 경계</b>에서 불변식을 세운다. {@code normalize}는 멱등이라 겹쳐 불러도 결과가 같다(#229 D10).
   */
  @Override
  @Transactional
  public UserProfileView completeLandlordOnboarding(
      long userId, LandlordOnboardingProfile profile) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    String nickname = nicknameGenerator.generateUnique();
    User active =
        user.completeLandlordOnboarding(
            PhoneNumbers.normalize(profile.phoneNumber()),
            profile.birthDate(),
            nickname,
            Instant.now());
    return toProfileView(userRepository.save(active));
  }

  /**
   * 연동 대상 조회 + 행 잠금. 번호는 <b>읽기 경계에서도</b> 한 번 더 표준형으로 접는다({@link PhoneNumbers}) — 쓰기 경계({@link
   * #completeLandlordOnboarding})와 같은 형태로 접어야 저장된 값과 조회 키가 어긋나지 않는다. {@code normalize}는 멱등이라 이미 접힌
   * 값을 다시 넣어도 결과가 같다(#229 D10).
   *
   * <p><b>{@code readOnly = true}가 아니다.</b> 잠금 조회는 읽기처럼 보이지만 {@code SELECT … FOR UPDATE}라 읽기 전용
   * 트랜잭션에서는 실행할 수 없다(MySQL은 READ ONLY 트랜잭션에서 거부한다). 실제 호출은 {@code REQUIRED} 전파로 웹 가입의 쓰기 트랜잭션에
   * 참여하지만, 단독 호출에도 성립하도록 여기서 읽기 전용을 선언하지 않는다.
   */
  @Override
  @Transactional
  public Optional<Long> findActiveLandlordIdByPhoneNumber(String normalizedPhoneNumber) {
    return userRepository.findActiveLandlordIdByPhoneNumberForUpdate(
        PhoneNumbers.normalize(normalizedPhoneNumber));
  }

  /**
   * 이메일 찾기용 읽기 조회 — 잠금이 없으므로 {@code readOnly = true}가 성립한다(위 두 {@code FOR UPDATE} 조회가 읽기 전용을 걸지 못하는
   * 것과 갈리는 지점이다. MySQL은 READ ONLY 트랜잭션에서 잠금 읽기를 거부한다).
   *
   * <p>번호를 여기서 한 번 더 접는 이유는 위 조회들과 같다 — 마커를 남긴 경계와 <b>같은 규칙</b>으로 접어야 표기 차이로 조용히 어긋나지 않는다.
   */
  @Override
  @Transactional(readOnly = true)
  public List<Long> findActiveWebUserIdsByPhoneNumber(String normalizedPhoneNumber) {
    return userRepository.findActiveWebUserIdsByPhoneNumber(
        PhoneNumbers.normalize(normalizedPhoneNumber));
  }

  /**
   * 병합 대상 조회 + 행 잠금(자기 제외). 번호를 읽기 경계에서 다시 접는 이유와 {@code readOnly}를 걸지 않는 이유는 {@link
   * #findActiveLandlordIdByPhoneNumber}와 같다.
   *
   * <p>잠금으로 읽은 행을 그대로 {@link UserProfileView}로 매핑한다 — 온보딩 완료 응답과 <b>같은 매퍼</b>({@link
   * #toProfileView})를 쓰므로 병합 응답의 {@code user}가 일반 온보딩 응답과 한 필드도 다르지 않다(연락처 마스킹 규칙까지 같다).
   */
  @Override
  @Transactional
  public Optional<UserProfileView> findActiveLandlordProfileByPhoneNumberExcluding(
      String normalizedPhoneNumber, long excludedUserId) {
    return userRepository
        .findActiveLandlordByPhoneNumberExcludingForUpdate(
            PhoneNumbers.normalize(normalizedPhoneNumber), excludedUserId)
        .map(this::toProfileView);
  }

  /**
   * 병합에서 진 임시 계정의 행을 지운다. 상태를 확인하지 않는 것은 의도다 — 지울지 말지는 <b>호출자(병합 판정)</b>가 정하고, 여기서 "PENDING만 지운다"
   * 같은 조건을 덧붙이면 판정이 두 곳으로 갈려 한쪽만 고친 버그가 숨는다. 탈퇴 이벤트를 발행하지 않는 이유는 포트 {@link
   * UserAccountService#deleteAccount} 참조(자격증명은 지우는 것이 아니라 옮기는 중이다).
   */
  @Override
  @Transactional
  public void deleteAccount(long userId) {
    userRepository.deleteById(userId);
  }

  @Override
  @Transactional(readOnly = true)
  public UserAccountView getAccount(long userId) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    return new UserAccountView(
        user.getId(), user.getStatus().name(), user.getName(), user.getEmail());
  }

  @Override
  @Transactional(readOnly = true)
  public String getUserType(long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(UserNotFoundException::new)
        .getUserType()
        .name();
  }

  @Override
  @Transactional(readOnly = true)
  public String getUserName(long userId) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    return user.getName() == null ? "" : user.getName();
  }

  @Override
  @Transactional(readOnly = true)
  public ApplicantProfileView getApplicantProfile(long userId) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    Country country =
        user.getCountry() == null
            ? null
            : countryRepository.findByCode(user.getCountry()).orElse(null);
    return new ApplicantProfileView(
        user.getId(),
        user.getName() == null ? "" : user.getName(),
        user.getGender() == null ? null : user.getGender().name(),
        user.getCountry(),
        country == null ? null : country.name(),
        user.getEmail());
  }

  @Override
  @Transactional(readOnly = true)
  public String getLanguage(long userId) {
    Language lang =
        userRepository.findById(userId).orElseThrow(UserNotFoundException::new).getLang();
    return lang == null ? DEFAULT_LANGUAGE : lang.code();
  }

  /**
   * 온보딩 완료 응답 뷰로 매핑한다. {@code name}은 세입자·임대인 공통 단일 이름이다(#192). {@code userType}에 따라 갈리는 건 세입자 전용
   * 필드(성별·국적·직업·비자정보)와 임대인 전용 마스킹된 {@code phoneNumber}뿐이며, 나머지는 {@code null}로 두어 응답에서 생략되게
   * 한다(UserProfileView {@code @JsonInclude(NON_NULL)}). 임대인 온보딩 응답의 연락처는 마스킹한다(spec §5-2 — 프로필 조회
   * §8의 평문과 구분).
   */
  private UserProfileView toProfileView(User u) {
    boolean landlord = u.getUserType() == UserType.LANDLORD;
    // 임대인은 gender·occupation·visaType 미수집(null) — country·lang은 서버가 KR·ko 고정(#141). 세입자/임대인
    // 공용이라 null 가드한다. 이메일·이름은 소셜 로그인 시점에 세팅된 공통 값이다(#192).
    Country country =
        u.getCountry() == null ? null : countryRepository.findByCode(u.getCountry()).orElse(null);
    return new UserProfileView(
        u.getId(),
        u.getName(),
        u.getNickname(),
        u.getGender() == null ? null : u.getGender().name(),
        u.getBirthDate(),
        u.getCountry(),
        country == null ? null : country.name(),
        country == null ? null : country.flag(),
        u.getLang() == null ? null : u.getLang().code(),
        u.getOccupation() == null ? null : u.getOccupation().name(),
        u.getEmail(),
        u.getVisaType() == null ? null : u.getVisaType().name(),
        landlord ? maskPhone(u.getPhoneNumber()) : null,
        u.getUserType() == null ? null : u.getUserType().name(),
        u.getStatus().name(),
        u.isMarketingAgreed(),
        u.getCreatedAt());
  }

  /** 응답용 연락처 마스킹(예: {@code 01012345678} → {@code 010-****-5678}). 임대인 온보딩 응답 전용. */
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

  /**
   * 표시 언어 코드를 {@link Language}로 파싱. 선택값이라 {@code null}은 미설정({@code null}), 지원 목록 밖은 {@code
   * INVALID_INPUT}(#141).
   */
  private static Language parseLanguage(String code) {
    if (code == null) {
      return null;
    }
    return Language.from(code)
        .orElseThrow(
            () -> new InvalidInputException("lang", "validation.unsupportedLanguage", code));
  }

  /**
   * 직업 문자열을 {@link Occupation}으로 파싱. 선택값이라 {@code null}은 미설정({@code null})으로 두고, 값이 있으면(빈 문자열
   * {@code ""} 포함) enum 목록 밖은 {@code INVALID_INPUT}(#187).
   */
  private static Occupation parseOccupation(String value) {
    if (value == null) {
      return null;
    }
    return parseEnum(Occupation.class, "occupation", value);
  }

  /**
   * 원시 문자열을 enum으로 파싱. 어느 요청 필드가 문제인지 응답 {@code errors[]}에 실어야 클라이언트가 고칠 수 있으므로 요청 필드명을 함께
   * 넘긴다(#151).
   */
  private static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidInputException(field, "validation.notAllowed", value);
    }
  }
}
