package com.kohere.user.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.user.api.LandlordOnboardingProfile;
import com.kohere.user.api.OnboardingProfile;
import com.kohere.user.api.TermsAgreementView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import com.kohere.user.api.UserProfileView;
import com.kohere.user.domain.Country;
import com.kohere.user.domain.CountryRepository;
import com.kohere.user.domain.Gender;
import com.kohere.user.domain.NicknameGenerator;
import com.kohere.user.domain.Occupation;
import com.kohere.user.domain.User;
import com.kohere.user.domain.UserNotFoundException;
import com.kohere.user.domain.UserRepository;
import com.kohere.user.domain.UserType;
import com.kohere.user.domain.VisaType;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * user 공개 API 구현. auth가 호출하는 회원 생성·약관 동의·온보딩 완료·계정 조회를 처리한다. 약관 버전은 서버 설정값(app.terms.version)을 약관
 * 동의 시 기록한다(ADR-0012). gender·occupation·visaType은 원시 문자열로 받아 enum으로 변환하고(유효하지 않으면 INVALID_INPUT),
 * country는 {@link CountryRepository}로 존재를 검증한다. 닉네임은 {@link NicknameGenerator}로 생성한다.
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
  public long createPendingUser() {
    return userRepository.save(User.createPending(Instant.now())).getId();
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
    Gender gender = parseEnum(Gender.class, profile.gender());
    Occupation occupation = parseEnum(Occupation.class, profile.occupation());
    VisaType visaType = parseVisaType(profile.visaType());
    if (profile.country() == null || !countryRepository.existsByCode(profile.country())) {
      throw new InvalidInputException("country 값이 올바르지 않습니다: " + profile.country());
    }
    String nickname = nicknameGenerator.generateUnique();
    User active =
        user.completeOnboarding(
            profile.firstName(),
            profile.lastName(),
            nickname,
            gender,
            profile.birthDate(),
            profile.country(),
            occupation,
            profile.email(),
            visaType,
            Instant.now());
    return toProfileView(userRepository.save(active));
  }

  @Override
  @Transactional
  public UserProfileView completeLandlordOnboarding(
      long userId, LandlordOnboardingProfile profile) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    String nickname = nicknameGenerator.generateUnique();
    User active =
        user.completeLandlordOnboarding(
            profile.name(), profile.phoneNumber(), nickname, Instant.now());
    return toProfileView(userRepository.save(active));
  }

  @Override
  @Transactional(readOnly = true)
  public UserAccountView getAccount(long userId) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    return new UserAccountView(user.getId(), user.getStatus().name());
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
    String first = user.getFirstName() == null ? "" : user.getFirstName();
    String last = user.getLastName() == null ? "" : user.getLastName();
    return (first + " " + last).trim();
  }

  @Override
  @Transactional(readOnly = true)
  public String getLanguage(long userId) {
    String country =
        userRepository.findById(userId).orElseThrow(UserNotFoundException::new).getCountry();
    if (!StringUtils.hasText(country)) {
      return DEFAULT_LANGUAGE;
    }
    return countryRepository
        .findByCode(country)
        .map(Country::lang)
        .filter(StringUtils::hasText)
        .orElse(DEFAULT_LANGUAGE);
  }

  /**
   * 온보딩 완료 응답 뷰로 매핑한다. {@code userType}에 따라 세입자/임대인 필드를 분기한다 — 세입자는 {@code firstName}·{@code
   * lastName}과 성별·국적·직업·비자정보를 채우고, 임대인은 전체 이름을 {@code name}에, 마스킹된 연락처를 {@code phoneNumber}에 채운다.
   * 나머지는 {@code null}로 두어 응답에서 생략되게 한다(UserProfileView {@code @JsonInclude(NON_NULL)}). 임대인 온보딩 응답의
   * 연락처는 마스킹한다(spec §5-2 — 프로필 조회 §8의 평문과 구분).
   */
  private UserProfileView toProfileView(User u) {
    boolean landlord = u.getUserType() == UserType.LANDLORD;
    // 임대인은 country·gender·occupation·visaType·email 미수집(null) — 세입자/임대인 공용이라 null 가드한다.
    Country country =
        u.getCountry() == null ? null : countryRepository.findByCode(u.getCountry()).orElse(null);
    return new UserProfileView(
        u.getId(),
        landlord ? null : u.getFirstName(),
        landlord ? null : u.getLastName(),
        landlord ? u.getFirstName() : null,
        u.getNickname(),
        u.getGender() == null ? null : u.getGender().name(),
        u.getBirthDate(),
        u.getCountry(),
        country == null ? null : country.name(),
        country == null ? null : country.flag(),
        u.getOccupation() == null ? null : u.getOccupation().name(),
        u.getEmail(),
        u.getVisaType() == null ? null : u.getVisaType().getValue(),
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

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidInputException(type.getSimpleName() + " 값이 올바르지 않습니다: " + value);
    }
  }

  // VisaType은 상수명이 아니라 value(STUDY_D-2 …)로 주고받으므로 name 기반 parseEnum과 분리한다(#93).
  private static VisaType parseVisaType(String value) {
    try {
      return VisaType.fromValue(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidInputException("VisaType 값이 올바르지 않습니다: " + value);
    }
  }
}
