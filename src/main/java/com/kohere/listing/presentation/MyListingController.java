package com.kohere.listing.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.listing.application.ListingService;
import com.kohere.listing.application.dto.FavoriteListingResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 스코프(me)의 매물 관련 조회 — 찜 목록, 최근 본 매물. {@code /users/me} 경로를 쓰지만 매물 도메인의 책임이므로 listing 모듈에 둔다. 스펙:
 * docs/api/specs/03-listings-favorites.md.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class MyListingController {

  private final ListingService listingService;

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

  @GetMapping("/recent-listings")
  public ApiResponse<List<ListingSummaryResponse>> getRecentListings() {
    return ApiResponse.success(listingService.getRecentListings());
  }
}
