package com.kohere.listing.domain.recent;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 최근 본 매물 기록 애그리거트.
 *
 * <p>최근 본 매물은 사용자가 상세 화면을 연 사실만 저장한다. 매물 제목, 가격, 주소 같은 화면 정보는 {@link Listing}의 책임이므로 이 애그리거트에는 복사해
 * 두지 않는다. 대신 {@code (userId, listingId)} 조합을 유니크 키로 두고, 같은 매물을 다시 보면 새 기록을 만들지 않고 {@code viewedAt}만
 * 최신 시각으로 갱신한다.
 */
@Getter
@Builder
public class RecentListing {

  private final String id;
  private final Long userId;
  private final String listingId;
  private final Instant viewedAt;
}
