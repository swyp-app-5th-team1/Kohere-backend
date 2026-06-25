package com.kohere.listing.application;

import com.kohere.common.response.PageResponse;
import com.kohere.listing.application.dto.FavoriteToggleResponse;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 매물 탐색·찜 유스케이스 조율. 도메인(포트)을 호출하고 흐름만 조율한다. 도메인 규칙은 엔티티/도메인 서비스에 둔다 (docs/convention/code-style.md
 * §3-3).
 *
 * <p>의존성은 생성자 주입({@code @RequiredArgsConstructor})으로 받는다(§3-4). 인증 주체(userId)는 SecurityContext에서
 * 가져온다(TODO: 보안 설정 후 연동).
 *
 * <p>TODO: 영속 계층(JPA) 도입 시 유스케이스에 트랜잭션 경계({@code @Transactional})를 추가한다.
 */
@Service
@RequiredArgsConstructor
public class ListingService {

  private final ListingRepository listingRepository;
  private final FavoriteRepository favoriteRepository;

  /** 공개 상태 매물을 페이지 단위로 조회하고 목록용 요약 응답으로 변환한다. */
  public PageResponse<ListingSummaryResponse> getListings(int page, int size) {
    PageResponse<com.kohere.listing.domain.Listing> listings =
        listingRepository.findPublished(page, size);
    return PageResponse.of(
        listings.content().stream().map(ListingResponseMapper::toSummary).toList(),
        listings.page());
  }

  /** 단일 매물 상세 정보를 조회하고 상세 화면 섹션별 응답으로 변환한다. */
  public ListingDetailResponse getListing(String listingId) {
    return listingRepository
        .findById(listingId)
        .filter(listing -> listing.getStatus() == Listing.ListingStatus.PUBLISHED)
        .map(ListingResponseMapper::toDetail)
        .orElseThrow(ListingNotFoundException::new);
  }

  /** 내가 찜한 매물 목록 조회 예정 지점이다. */
  public PageResponse<ListingSummaryResponse> getMyFavorites(int page, int size) {
    throw new UnsupportedOperationException("TODO: 내 찜 목록 조회");
  }

  /** 최근 본 매물 조회 예정 지점이다. */
  public List<ListingSummaryResponse> getRecentListings() {
    throw new UnsupportedOperationException("TODO: 최근 본 매물(7일·최대 5건)");
  }

  /** 매물 찜 등록 예정 지점이다. */
  public FavoriteToggleResponse addFavorite(String listingId) {
    throw new UnsupportedOperationException("TODO: 찜 등록(토글, 멱등)");
  }

  /** 매물 찜 해제 예정 지점이다. */
  public FavoriteToggleResponse removeFavorite(String listingId) {
    throw new UnsupportedOperationException("TODO: 찜 해제(토글, 멱등)");
  }
}
