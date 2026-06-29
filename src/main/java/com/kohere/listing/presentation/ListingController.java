package com.kohere.listing.presentation;

import com.kohere.common.response.PageResponse;
import com.kohere.listing.application.ListingService;
import com.kohere.listing.application.dto.FavoriteToggleResponse;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingMapResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.presentation.dto.ListingMapRequest;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 탐색·찜 REST 컨트롤러. 입력 검증·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다 (docs/convention/code-style.md §3-3).
 * 도메인 DTO만 반환하고, 공통 래퍼는 {@link com.kohere.common.response.ApiResponseWrapper}가 자동 적용한다(ADR-0013).
 *
 * <p>스펙: docs/api/specs/03-listings-favorites.md. TODO: 키워드 검색(GET /search)를 채운다.
 */
@RestController
@RequestMapping("/api/v1/listings")
@RequiredArgsConstructor
public class ListingController {

  private final ListingService listingService;

  /** 지도 바텀시트에서 사용할 매물 카드 목록을 지도 범위와 필터 조건으로 조회한다. */
  @GetMapping
  public PageResponse<ListingSummaryResponse> getListings(
      @ModelAttribute ListingSearchRequest request) {
    return listingService.getListings(request);
  }

  /** 지도 SDK가 클러스터링할 수 있도록 현재 지도 범위 안의 개별 매물 마커 좌표를 조회한다. */
  @GetMapping("/map")
  public ListingMapResponse getListingMap(@ModelAttribute ListingMapRequest request) {
    return listingService.getListingMap(request);
  }

  /** 매물 상세 화면에서 사용할 객체별 상세 정보를 반환한다. */
  @GetMapping("/{listingId}")
  public ListingDetailResponse getListing(@PathVariable String listingId) {
    return listingService.getListing(listingId);
  }

  /** 매물 찜 등록 API 예정 지점이다. */
  @PostMapping("/{listingId}/favorite")
  @ResponseStatus(HttpStatus.CREATED)
  public FavoriteToggleResponse addFavorite(@PathVariable String listingId) {
    return listingService.addFavorite(listingId);
  }

  /** 매물 찜 해제 API 예정 지점이다. */
  @DeleteMapping("/{listingId}/favorite")
  public FavoriteToggleResponse removeFavorite(@PathVariable String listingId) {
    return listingService.removeFavorite(listingId);
  }
}
