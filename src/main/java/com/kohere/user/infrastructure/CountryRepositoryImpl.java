package com.kohere.user.infrastructure;

import com.kohere.user.domain.Country;
import com.kohere.user.domain.CountryRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 국가 참조 어댑터. 도메인 포트 {@link CountryRepository}를 JPA로 구현한다. */
@Repository
@RequiredArgsConstructor
public class CountryRepositoryImpl implements CountryRepository {

  private final CountryJpaRepository jpaRepository;

  @Override
  public boolean existsByCode(String code) {
    return code != null && jpaRepository.existsById(code);
  }

  @Override
  public Optional<Country> findByCode(String code) {
    if (code == null) {
      return Optional.empty();
    }
    return jpaRepository
        .findById(code)
        .map(e -> new Country(e.getCode(), e.getName(), e.getFlag()));
  }
}
