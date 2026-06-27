package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.ListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 로컬 개발 환경에만 Numbers 기반 임시 매물을 애플리케이션 저장 흐름으로 적재한다. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.seed.listings-enabled", havingValue = "true")
@RequiredArgsConstructor
class ListingSeedRunner implements ApplicationRunner {

  private final ListingRepository listingRepository;

  /** seed fixture 목록을 애플리케이션 저장 흐름으로 저장한다. */
  @Override
  public void run(ApplicationArguments args) {
    ListingSeedFixtures.listings().forEach(listingRepository::save);
  }
}
