package com.kohere.user.infrastructure;

import com.kohere.user.domain.Gender;
import com.kohere.user.domain.Language;
import com.kohere.user.domain.Occupation;
import com.kohere.user.domain.UserStatus;
import com.kohere.user.domain.UserType;
import com.kohere.user.domain.VisaType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회원 JPA 영속 엔티티(MySQL {@code users}). 도메인 {@link com.kohere.user.domain.User}와 분리된 영속 모델이며 어댑터가 상호
 * 매핑한다(docs/convention/code-style.md §3-3). enum은 문자열(UPPER_SNAKE)로 저장한다(database-design §2-3).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserJpaEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String firstName;
  private String lastName;
  private String nickname;

  @Enumerated(EnumType.STRING)
  private Gender gender;

  private LocalDate birthDate;
  private String country;

  // 사용자가 고른 표시 언어(en/ko/ja). DB엔 소문자 코드 저장(LanguageConverter). NULL=미선택 → 표시 시 en 폴백(#141).
  @Convert(converter = LanguageConverter.class)
  @Column(length = 8)
  private Language lang;

  @Enumerated(EnumType.STRING)
  private Occupation occupation;

  private String email;

  // DB엔 상수명이 아니라 표시용 라벨(value, Short Term Visit(C-1~4, B) …)을 저장 — @Enumerated(상수명) 대신 컨버터
  // 사용(#138).
  @Convert(converter = VisaTypeConverter.class)
  @Column(length = 80)
  private VisaType visaType;

  @Enumerated(EnumType.STRING)
  private UserType userType;

  private String phoneNumber;
  private String businessRegistrationNumberHash;

  @Enumerated(EnumType.STRING)
  private UserStatus status;

  private boolean termsOfServiceAgreed;
  private boolean privacyPolicyAgreed;
  private boolean marketingAgreed;
  private String termsVersion;
  private Instant agreedAt;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant withdrawnAt;
}
