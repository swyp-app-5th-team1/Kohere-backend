package com.kohere.listing.presentation;

import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.listing.application.ListingService;
import com.kohere.listing.application.dto.FavoriteToggleResponse;
import com.kohere.listing.application.dto.FavoriteToggleResult;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingKeywordSearchResponse;
import com.kohere.listing.application.dto.ListingMapResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.presentation.dto.ListingKeywordSearchRequest;
import com.kohere.listing.presentation.dto.ListingMapRequest;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 탐색·찜 REST 컨트롤러. 입력 검증·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다 (docs/convention/code-style.md §3-3).
 * 도메인 DTO만 반환하고, 공통 래퍼는 {@link com.kohere.common.response.ApiResponseWrapper}가 자동 적용한다(ADR-0013).
 *
 * <p>스펙: docs/api/specs/03-listings-favorites.md.
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

  /**
   * 학교명·지역명·지하철역명 키워드로 검색된 장소 주변의 방 상품 카드 목록을 조회한다.
   *
   * <p>검색어가 POI 사전에 없으면 에러가 아니라 {@code matchedPlace=null}, {@code content=[]}로 반환해 프론트가 "검색된 장소가
   * 없어요" 상태를 표시할 수 있게 한다.
   */
  @GetMapping("/search")
  public ListingKeywordSearchResponse searchListings(
      @ModelAttribute ListingKeywordSearchRequest request) {
    return listingService.searchListings(request);
  }

  /** 매물 상세 화면에서 사용할 객체별 상세 정보를 반환한다. */
  @GetMapping("/{listingId}")
  public ListingDetailResponse getListing(@PathVariable String listingId) {
    return listingService.getListing(listingId);
  }

  /**
   * 로그인 사용자가 공개 매물을 찜한다.
   *
   * <p>프론트는 응답 body의 {@code favorited=true}와 {@code favoriteCount}로 버튼 상태와 숫자를 갱신하면 된다. 새로 찜이 생성되면
   * {@code 201 Created}, 이미 찜한 상태에서 재호출되면 {@code 200 OK}가 내려가지만 body 구조는 동일하다.
   */
  @PostMapping("/{listingId}/favorite")
  public ResponseEntity<FavoriteToggleResponse> addFavorite(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String listingId) {
    FavoriteToggleResult result = listingService.addFavorite(principal.userId(), listingId);
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(result.response());
  }

  /**
   * 로그인 사용자의 매물 찜을 해제한다.
   *
   * <p>해제 후에는 항상 {@code favorited=false}를 반환한다. 사용자가 이미 찜을 해제한 상태에서 같은 요청을 다시 보내도 {@code 200 OK}로
   * 현재 상태를 돌려주므로, 프론트는 중복 탭·재시도 상황을 별도 에러로 다루지 않아도 된다.
   */
  @DeleteMapping("/{listingId}/favorite")
  public FavoriteToggleResponse removeFavorite(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String listingId) {
    return listingService.removeFavorite(principal.userId(), listingId);
  }
}
