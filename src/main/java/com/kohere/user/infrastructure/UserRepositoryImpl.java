package com.kohere.user.infrastructure;

import com.kohere.user.domain.User;
import com.kohere.user.domain.UserRepository;
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

  @Override
  public boolean existsByNickname(String nickname) {
    return jpaRepository.existsByNickname(nickname);
  }

  @Override
  public User save(User user) {
    return toDomain(jpaRepository.save(toEntity(user)));
  }

  private static User toDomain(UserJpaEntity e) {
    return User.builder()
        .id(e.getId())
        .firstName(e.getFirstName())
        .lastName(e.getLastName())
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
        .firstName(u.getFirstName())
        .lastName(u.getLastName())
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
