package com.kohere.listing.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.listing.application.LandlordListingService;
import com.kohere.listing.application.ListingService;
import com.kohere.listing.application.dto.FavoriteListingResponse;
import com.kohere.listing.application.dto.LandlordListingDetailResponse;
import com.kohere.listing.application.dto.LandlordListingSummaryResponse;
import com.kohere.listing.application.dto.RecentListingsResponse;
import com.kohere.listing.presentation.dto.LandlordListingSearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 스코프(me)의 매물 관련 조회 — 찜 목록, 최근 본 매물. {@code /users/me} 경로를 쓰지만 매물 도메인의 책임이므로 listing 모듈에 둔다.
 *
 * <p>v1({@link com.kohere.listing.presentation.v1.MyListingV1Controller})은 빈 목록만 준다 — 응답에 매물 카드가
 * 들어가 v4 스키마 개편의 영향을 그대로 받았다(ADR-0040). 저장된 찜·조회 이력 자체는 그대로라 이 경로에서 온전히 보인다.
 *
 * <p>스펙: docs/api/specs/03-listings-favorites.md.
 */
@RestController
@RequestMapping("/api/v2/users/me")
@RequiredArgsConstructor
public class MyListingV2Controller {

  private final ListingService listingService;
  private final LandlordListingService landlordListingService;

  /**
   * 로그인 사용자의 찜 목록을 조회한다.
   *
   * <p>정렬은 MVP 기준으로 최근 찜한 순 고정이다. 응답 항목은 모두 현재 사용자 기준 {@code favorited=true}이며, 공개 중인 매물만 포함된다.
   */
  @GetMapping("/favorites")
  public ApiResponse<PageResponse<FavoriteListingResponse>> getMyFavorites(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(listingService.getMyFavorites(principal.userId(), page, size));
  }

  /**
   * 로그인 사용자의 최근 본 매물 목록을 조회한다.
   *
   * <p>응답은 최신 조회순 최대 10개이며, 사용자가 과거에 본 매물이라도 현재 공개 상태가 아니면 제외된다. 프론트는 일반 매물 카드와 거의 같은 필드를 쓰고 {@code
   * viewedAt}으로 정렬 기준을 확인할 수 있다.
   */
  @GetMapping("/recent-listings")
  public ApiResponse<RecentListingsResponse> getRecentListings(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponse.success(listingService.getRecentListings(principal.userId()));
  }

  /**
   * 임대인이 자기 매물을 조회한다(US-3-8). <b>상태와 무관하게</b> 자기 것만 나온다.
   *
   * <p>이 경로가 {@code /api/v2/listings/mine}이 아닌 이유는 {@code GET /api/v2/listings/*}가 {@code
   * permitAll} 이기 때문이다 — 그 아래 두면 비로그인에 열린다. 임대인 여부와 소유권은 서비스가 다시 본다.
   */
  @GetMapping("/listings")
  public ApiResponse<PageResponse<LandlordListingSummaryResponse>> getMyListings(
      @AuthenticationPrincipal AuthPrincipal principal,
      @Valid @ModelAttribute LandlordListingSearchRequest request) {
    return ApiResponse.success(
        landlordListingService.list(
            principal.userId(), request.status(), request.page(), request.size()));
  }

  /** 수정 화면이 폼을 채우는 데 쓰는 내 매물 상세다. 남의 매물이면 {@code 404}다. */
  @GetMapping("/listings/{listingId}")
  public ApiResponse<LandlordListingDetailResponse> getMyListing(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String listingId) {
    return ApiResponse.success(landlordListingService.detail(principal.userId(), listingId));
  }
}
