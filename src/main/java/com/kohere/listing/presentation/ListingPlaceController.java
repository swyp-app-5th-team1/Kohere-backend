package com.kohere.listing.presentation;

import com.kohere.listing.application.ListingPlaceSearchService;
import com.kohere.listing.application.dto.ListingPlaceSearchResponse;
import com.kohere.listing.presentation.dto.ListingPlaceSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지도 검색창에 표시할 외부 장소 후보를 제공하는 REST 컨트롤러다.
 *
 * <p>기존 {@code /api/v1/listings/search}의 POI·주변 매물 검색 계약을 변경하지 않기 위해 별도 리소스로 분리한다. 컨트롤러는 요청 DTO를 응용
 * 서비스에 전달하고 응답 DTO만 반환하며, 공통 {@code ApiResponseWrapper}가 성공 응답 봉투를 자동 적용한다.
 */
@RestController
@RequestMapping("/api/v1/listings/places")
@RequiredArgsConstructor
public class ListingPlaceController {

  private final ListingPlaceSearchService listingPlaceSearchService;

  /**
   * 검색 키워드와 관련된 네이버 장소 후보를 최대 5개 반환한다.
   *
   * @param request {@code keyword} 쿼리 파라미터를 담은 요청 DTO
   * @return 제목·주소·WGS84 좌표만 포함한 장소 후보 목록
   */
  @GetMapping
  public ListingPlaceSearchResponse searchPlaces(
      @ModelAttribute ListingPlaceSearchRequest request) {
    return listingPlaceSearchService.search(request);
  }
}
