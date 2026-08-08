package com.kohere.listing.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.application.dto.ListingPlaceSearchResponse;
import com.kohere.listing.domain.place.PlaceSearchClient;
import com.kohere.listing.domain.place.PlaceSearchResult;
import com.kohere.listing.presentation.dto.ListingPlaceSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 지도 검색창의 키워드를 검증하고 외부 장소 후보 검색을 조율한다.
 *
 * <p>이 유스케이스는 장소 후보만 반환하며 MongoDB 매물은 조회하지 않는다. 사용자가 후보를 선택한 뒤의 지도 이동·bounds 계산과 기존 {@code
 * /api/v1/listings}, {@code /api/v1/listings/map} 호출은 프론트와 기존 매물 조회 흐름의 책임으로 남긴다.
 */
@Service
@RequiredArgsConstructor
public class ListingPlaceSearchService {

  private static final int MAX_KEYWORD_LENGTH = 50;

  private final PlaceSearchClient placeSearchClient;

  /**
   * 검색어를 정규화한 뒤 외부 장소 검색 결과를 프론트 응답으로 변환한다.
   *
   * @param request 검색창 원본 입력을 담은 요청 DTO
   * @return 네이버 검색 순서를 유지한 장소 후보 최대 5개
   * @throws InvalidInputException 검색어가 누락·공백이거나 50자를 초과한 경우
   */
  public ListingPlaceSearchResponse search(ListingPlaceSearchRequest request) {
    String keyword = validateAndNormalizeKeyword(request == null ? null : request.getKeyword());
    return new ListingPlaceSearchResponse(
        placeSearchClient.search(keyword).stream()
            .map(ListingPlaceSearchService::toResponseItem)
            .toList());
  }

  /**
   * 사용자 입력의 앞뒤 공백을 제거하고 API가 허용하는 1~50자 검색어인지 확인한다.
   *
   * <p>네이버를 호출하기 전에 검증해 잘못된 입력이 일일 외부 API 호출량을 소모하지 않게 한다.
   */
  private static String validateAndNormalizeKeyword(String keyword) {
    if (keyword == null || keyword.trim().isEmpty()) {
      throw new InvalidInputException("keyword는 필수입니다.");
    }
    String normalized = keyword.trim();
    if (normalized.length() > MAX_KEYWORD_LENGTH) {
      throw new InvalidInputException("keyword는 1자 이상 50자 이하이어야 합니다.");
    }
    return normalized;
  }

  /** 제공자 독립 도메인 값을 presentation 계약에 맞는 응답 항목으로 복사한다. */
  private static ListingPlaceSearchResponse.Item toResponseItem(PlaceSearchResult result) {
    return new ListingPlaceSearchResponse.Item(
        result.title(), result.address(), result.roadAddress(), result.lat(), result.lng());
  }
}
