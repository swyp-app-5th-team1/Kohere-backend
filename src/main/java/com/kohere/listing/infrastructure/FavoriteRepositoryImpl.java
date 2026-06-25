package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.Favorite;
import com.kohere.listing.domain.FavoriteRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 찜 영속 어댑터(스켈레톤 placeholder). 도메인 포트 {@link FavoriteRepository}를 구현한다. 현재는 미구현이며 JPA 어댑터로 교체한다. */
@Repository
public class FavoriteRepositoryImpl implements FavoriteRepository {

  @Override
  public Optional<Favorite> findByUserIdAndListingId(Long userId, String listingId) {
    throw new UnsupportedOperationException("TODO: JPA 구현으로 교체");
  }
}
