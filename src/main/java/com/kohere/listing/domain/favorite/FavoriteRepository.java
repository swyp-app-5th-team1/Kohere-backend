package com.kohere.listing.domain.favorite;

import com.kohere.common.response.PageResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 찜 영속 포트.
 *
 * <p>찜은 {@code (userId, listingId)} 조합이 비즈니스 키다. 저장소 구현체는 이 조합에 유니크 인덱스를 걸어 같은 사용자가 같은 매물을 두 번 찜하지
 * 못하게 하고, 응용 계층은 반환값으로 신규 생성/기존 존재를 구분해 HTTP status를 결정한다.
 */
public interface FavoriteRepository {

  /** 현재 사용자가 해당 매물을 이미 찜했는지 확인한다. 잘못된 ObjectId 형식이면 찜이 없는 것으로 취급한다. */
  Optional<Favorite> findByUserIdAndListingId(Long userId, String listingId);

  /**
   * 현재 사용자가 주어진 매물 목록 중 실제로 찜한 매물 id만 반환한다.
   *
   * <p>최근 본 매물처럼 최대 10개 안팎의 카드 목록에서 하트 상태를 채울 때 사용한다. 각 카드마다 단건 조회를 반복하지 않고 한 번의 {@code in} 조회로 끝내기
   * 위한 메서드다.
   */
  Set<String> findFavoritedListingIds(Long userId, List<String> listingIds);

  /**
   * 찜을 신규 저장한다.
   *
   * <p>동일한 {@code (userId, listingId)}가 이미 있으면 중복 예외를 외부로 전파하지 않고 {@code false}를 반환한다. 이 덕분에 네트워크
   * 재시도나 동시 탭 요청이 들어와도 API는 멱등하게 "이미 찜됨" 상태를 반환할 수 있다.
   */
  boolean saveIfAbsent(Favorite favorite);

  /** 사용자의 찜을 삭제한다. 실제로 삭제된 문서가 있으면 {@code true}, 원래 찜하지 않은 상태면 {@code false}를 반환한다. */
  boolean deleteByUserIdAndListingId(Long userId, String listingId);

  /**
   * 내 찜 목록을 최근 찜한 순으로 조회한다.
   *
   * <p>프론트에는 현재 볼 수 있는 공개 매물만 내려가야 하므로 {@code ListingStatus.PUBLISHED}가 아닌 매물은 응답과 {@code
   * totalElements}에서 모두 제외한다.
   */
  PageResponse<FavoriteListing> findPublishedByUserIdOrderByFavoritedAtDesc(
      Long userId, int page, int size);
}
