package com.kohere.user.infrastructure;

import com.kohere.user.domain.User;
import com.kohere.user.domain.UserRepository;
import com.kohere.user.domain.UserStatus;
import com.kohere.user.domain.UserType;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 회원 영속 어댑터. 도메인 포트 {@link UserRepository}를 JPA로 구현하고 도메인↔엔티티를 매핑한다 (docs/convention/code-style.md
 * §3-3, 의존성 역전).
 */
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

  private final UserJpaRepository jpaRepository;

  @Override
  public Optional<User> findById(Long id) {
    return jpaRepository.findById(id).map(UserRepositoryImpl::toDomain);
  }

  /**
   * 잠금 조회는 엔티티를 읽고 식별자만 꺼내 돌려준다 — 포트가 {@code Optional<Long>}인 이유는 {@link
   * UserRepository#findActiveLandlordIdByPhoneNumberForUpdate} 참조. 조건의 {@code ACTIVE}·{@code
   * LANDLORD}는 여기서 상수로 고정한다(호출자가 바꿀 수 있는 값이 아니라 연동 규칙이다).
   */
  /**
   * 이메일 찾기용 읽기 조회 — 잠금 없이 {@code ACTIVE} + ({@code LANDLORD}·{@code ADMIN})를 모아 식별자만 남긴다. 두 역할을 함께
   * 보는 이유와 다건이 정상인 이유는 포트 {@link
   * com.kohere.user.domain.UserRepository#findActiveWebUserIdsByPhoneNumber} 참조.
   */
  @Override
  public List<Long> findActiveWebUserIdsByPhoneNumber(String phoneNumber) {
    return jpaRepository
        .findByPhoneNumberAndStatusAndUserTypeIn(
            phoneNumber, UserStatus.ACTIVE, List.of(UserType.LANDLORD, UserType.ADMIN))
        .stream()
        .map(UserJpaEntity::getId)
        .toList();
  }

  @Override
  public Optional<Long> findActiveLandlordIdByPhoneNumberForUpdate(String phoneNumber) {
    return jpaRepository
        .findByPhoneNumberAndStatusAndUserType(phoneNumber, UserStatus.ACTIVE, UserType.LANDLORD)
        .map(UserJpaEntity::getId);
  }

  /**
   * 병합 대상 잠금 조회 — 조건은 위와 같고 <b>자기 자신({@code excludedUserId})만 뺀다</b>. 여기서는 도메인 객체를 돌려주는데, 그 이유는 포트
   * {@link UserRepository#findActiveLandlordByPhoneNumberExcludingForUpdate} 참조(대상 프로필이 그대로 응답에
   * 실린다).
   */
  @Override
  public Optional<User> findActiveLandlordByPhoneNumberExcludingForUpdate(
      String phoneNumber, long excludedUserId) {
    return jpaRepository
        .findByPhoneNumberAndStatusAndUserTypeAndIdNot(
            phoneNumber, UserStatus.ACTIVE, UserType.LANDLORD, excludedUserId)
        .map(UserRepositoryImpl::toDomain);
  }

  @Override
  public boolean existsByNickname(String nickname) {
    return jpaRepository.existsByNickname(nickname);
  }

  /**
   * 병합에서 진 쪽 임시 계정을 지운다. {@code deleteById}는 대상이 없으면 아무 일도 하지 않으므로(Spring Data는 조회 후 삭제한다) 별도 존재
   * 검사를 두지 않는다 — 병합은 잠금 조회와 같은 트랜잭션이라 그 사이 사라질 수 없고, 사라졌다면 이미 목적이 달성된 상태다.
   */
  @Override
  public void deleteById(long userId) {
    jpaRepository.deleteById(userId);
  }

  @Override
  public User save(User user) {
    return toDomain(jpaRepository.save(toEntity(user)));
  }

  private static User toDomain(UserJpaEntity e) {
    return User.builder()
        .id(e.getId())
        .name(e.getName())
        .nickname(e.getNickname())
        .gender(e.getGender())
        .birthDate(e.getBirthDate())
        .country(e.getCountry())
        .lang(e.getLang())
        .occupation(e.getOccupation())
        .email(e.getEmail())
        .visaType(e.getVisaType())
        .userType(e.getUserType())
        .phoneNumber(e.getPhoneNumber())
        .businessRegistrationNumberHash(e.getBusinessRegistrationNumberHash())
        .status(e.getStatus())
        .termsOfServiceAgreed(e.isTermsOfServiceAgreed())
        .privacyPolicyAgreed(e.isPrivacyPolicyAgreed())
        .marketingAgreed(e.isMarketingAgreed())
        .termsVersion(e.getTermsVersion())
        .agreedAt(e.getAgreedAt())
        .createdAt(e.getCreatedAt())
        .updatedAt(e.getUpdatedAt())
        .withdrawnAt(e.getWithdrawnAt())
        .build();
  }

  private static UserJpaEntity toEntity(User u) {
    return UserJpaEntity.builder()
        .id(u.getId())
        .name(u.getName())
        .nickname(u.getNickname())
        .gender(u.getGender())
        .birthDate(u.getBirthDate())
        .country(u.getCountry())
        .lang(u.getLang())
        .occupation(u.getOccupation())
        .email(u.getEmail())
        .visaType(u.getVisaType())
        .userType(u.getUserType())
        .phoneNumber(u.getPhoneNumber())
        .businessRegistrationNumberHash(u.getBusinessRegistrationNumberHash())
        .status(u.getStatus())
        .termsOfServiceAgreed(u.isTermsOfServiceAgreed())
        .privacyPolicyAgreed(u.isPrivacyPolicyAgreed())
        .marketingAgreed(u.isMarketingAgreed())
        .termsVersion(u.getTermsVersion())
        .agreedAt(u.getAgreedAt())
        .createdAt(u.getCreatedAt())
        .updatedAt(u.getUpdatedAt())
        .withdrawnAt(u.getWithdrawnAt())
        .build();
  }
}
