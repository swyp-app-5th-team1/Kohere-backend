package com.kohere.listing.domain.recent;

import java.time.Instant;
import java.util.List;

/**
 * 최근 본 매물 영속 포트.
 *
 * <p>최근 본 매물은 상세 조회의 부가 기록이므로, 응용 계층은 이 포트의 실패가 상세 조회 자체를 깨뜨리지 않게 다룬다. 저장소 구현체는 {@code (userId,
 * listingId)} 유니크 인덱스와 upsert로 중복 생성을 막고, 사용자별 보관 상한을 넘은 오래된 기록을 정리한다.
 */
public interface RecentListingRepository {

  /**
   * 최근 본 매물을 저장하거나 마지막 조회 시각을 갱신한다.
   *
   * <p>같은 사용자가 같은 매물을 다시 조회하면 문서를 새로 만들지 않고 {@code viewedAt}만 바꾼다. 이 덕분에 상세 화면을 여러 번 열어도 최근 본 목록에는
   * 같은 매물이 한 번만 나타난다.
   */
  void upsertViewedAt(Long userId, String listingId, Instant viewedAt);

  /**
   * 사용자의 최근 본 기록 중 공개 상태라 화면에 보여줄 수 있는 매물만 최신순으로 조회한다.
   *
   * <p>{@code DRAFT}, {@code PAUSED}, {@code DELETED} 매물은 최근 본 기록이 남아 있어도 목록에서 숨긴다. 숨긴 매물 때문에 화면이
   * 비지 않도록, limit은 공개 매물 필터를 적용한 뒤의 최종 응답 개수 기준이다.
   */
  List<RecentListingView> findPublishedByUserIdOrderByViewedAtDesc(Long userId, int limit);

  /**
   * 사용자별 최근 본 기록을 최신 {@code keepCount}개만 남기고 오래된 기록부터 삭제한다.
   *
   * <p>API 응답은 최대 10개지만 DB에는 비공개 전환 매물을 숨긴 뒤에도 10개를 채울 여유를 두기 위해 30개까지 보관한다.
   */
  void deleteOldByUserIdKeepingLatest(Long userId, int keepCount);
}
