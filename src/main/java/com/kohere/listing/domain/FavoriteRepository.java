package com.kohere.listing.domain;

import java.util.Optional;

/** 찜 영속 포트. TODO: 사용자별 찜 목록 조회·카운트 집계 메서드를 추가한다. */
public interface FavoriteRepository {

  Optional<Favorite> findByUserIdAndListingId(Long userId, String listingId);
}
