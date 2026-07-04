package com.kohere.user.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.user.api.PhoneVerificationChecker;
import com.kohere.user.api.UserWithdrawnEvent;
import com.kohere.user.application.dto.UserProfileResponse;
import com.kohere.user.domain.Country;
import com.kohere.user.domain.CountryRepository;
import com.kohere.user.domain.User;
import com.kohere.user.domain.UserNotFoundException;
import com.kohere.user.domain.UserRepository;
import com.kohere.user.domain.UserStatus;
import com.kohere.user.domain.UserType;
import com.kohere.user.presentation.dto.UpdateProfileRequest;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 프로필·계정 lifecycle 유스케이스(/users/me). 인증 주체(userId)는 컨트롤러가 SecurityContext에서 받아 전달한다.
 *
 * <p>국적은 {@code country}(ISO 코드)만 저장하고 표시명·국기는 {@link CountryRepository}로 resolve한다. 탈퇴는 WITHDRAWN
 * 전이 + PII 즉시 익명화(도메인) 후 {@link UserWithdrawnEvent}를 발행해 auth가 social_accounts 삭제·refresh 무효화를
 * 수행하도록 한다(ADR-0002/0014).
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final CountryRepository countryRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final PhoneVerificationChecker phoneVerificationChecker;

  @Transactional(readOnly = true)
  public UserProfileResponse getMyProfile(long userId) {
    return toResponse(activeUser(userId));
  }

  @Transactional
  public UserProfileResponse updateMyProfile(long userId, UpdateProfileRequest request) {
    User user = activeUser(userId);
    User updated =
        user.getUserType() == UserType.LANDLORD
            ? updateLandlordProfile(user, request)
            : updateTenantProfile(user, request);
    return toResponse(userRepository.save(updated));
  }

  /** 세입자 프로필 수정 — 성·이름·성별·생년월일·국적·직업·비자정보·마케팅 동의. country는 존재 검증한다. */
  private User updateTenantProfile(User user, UpdateProfileRequest request) {
    if (request.country() != null && !countryRepository.existsByCode(request.country())) {
      throw new InvalidInputException("country 값이 올바르지 않습니다: " + request.country());
    }
    return user.updateProfile(
        request.firstName(),
        request.lastName(),
        request.gender(),
        request.birthDate(),
        request.country(),
        request.occupation(),
        request.visaType(),
        request.marketingAgreed(),
        Instant.now());
  }

  /**
   * 임대인 프로필 수정 — 이름·연락처·마케팅 동의. 연락처를 새 번호로 바꿀 때는 그 번호가 SMS로 사전 재인증(§4-1·§4-2)됐는지 확인하고(미인증·불일치 422
   * AUTH_PHONE_NOT_VERIFIED), 검증 완료된 경우에만 반영한다(ADR-0034).
   */
  private User updateLandlordProfile(User user, UpdateProfileRequest request) {
    if (request.phoneNumber() != null && !request.phoneNumber().equals(user.getPhoneNumber())) {
      phoneVerificationChecker.assertPhoneVerified(user.getId(), request.phoneNumber());
    }
    return user.updateLandlordProfile(
        request.name(), request.phoneNumber(), request.marketingAgreed(), Instant.now());
  }

  @Transactional
  public void withdraw(long userId) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    userRepository.save(user.withdraw(Instant.now()));
    eventPublisher.publishEvent(new UserWithdrawnEvent(userId));
  }

  private User activeUser(long userId) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    if (user.getStatus() == UserStatus.WITHDRAWN) {
      throw new UserNotFoundException();
    }
    return user;
  }

  /**
   * 프로필 응답으로 매핑한다. {@code userType}에 따라 필드를 분기한다 — 세입자는 성·이름·성별·국적·직업·비자정보를, 임대인은 전체 이름({@code
   * name})·연락처를 채우고 나머지는 {@code null}로 두어 응답에서 생략되게 한다(UserProfileResponse
   * {@code @JsonInclude(NON_NULL)}). 본인 조회이므로 임대인 {@code phoneNumber}는 평문으로 반환한다(spec §8 — 온보딩 응답
   * §5-2의 마스킹과 구분).
   */
  private UserProfileResponse toResponse(User u) {
    boolean landlord = u.getUserType() == UserType.LANDLORD;
    Country country =
        u.getCountry() == null ? null : countryRepository.findByCode(u.getCountry()).orElse(null);
    return new UserProfileResponse(
        u.getId(),
        u.getUserType(),
        landlord ? null : u.getFirstName(),
        landlord ? null : u.getLastName(),
        landlord ? u.getFirstName() : null,
        u.getNickname(),
        u.getGender(),
        u.getBirthDate(),
        u.getCountry(),
        country == null ? null : country.name(),
        country == null ? null : country.flag(),
        u.getOccupation(),
        u.getEmail(),
        u.getVisaType(),
        landlord ? u.getPhoneNumber() : null,
        u.getStatus(),
        u.isTermsOfServiceAgreed(),
        u.isPrivacyPolicyAgreed(),
        u.isMarketingAgreed(),
        u.getCreatedAt());
  }
}
