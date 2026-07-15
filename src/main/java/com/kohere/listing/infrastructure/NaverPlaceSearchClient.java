package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.PlaceSearchClient;
import com.kohere.listing.domain.PlaceSearchResult;
import com.kohere.listing.domain.PlaceSearchUpstreamException;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 네이버 지역 검색 API를 {@link PlaceSearchClient} 포트에 연결하는 HTTP 어댑터다.
 *
 * <p>요청 파라미터는 네이버 계약과 Kohere UX에 맞춰 {@code display=5}, {@code start=1}, {@code sort=random}으로 서버가
 * 고정한다. 프론트에는 원본 좌표 문자열을 노출하지 않고 WGS84 십진수 좌표로 변환해 전달한다. 외부 HTTP 오류, 인증정보 누락, 본문·좌표 계약 위반은 모두
 * {@link PlaceSearchUpstreamException}으로 통일한다.
 */
@Component
public class NaverPlaceSearchClient implements PlaceSearchClient {

  private static final String SEARCH_PATH = "/v1/search/local.json";
  private static final String CLIENT_ID_HEADER = "X-Naver-Client-Id";
  private static final String CLIENT_SECRET_HEADER = "X-Naver-Client-Secret";
  private static final int MAX_RESULTS = 5;
  private static final int FIRST_RESULT_POSITION = 1;
  private static final String ACCURACY_SORT = "random";
  private static final double NAVER_COORDINATE_SCALE = 10_000_000.0;

  private final NaverSearchProperties properties;
  private final RestClient restClient;

  /**
   * 인증정보 설정과 네이버 전용 HTTP 클라이언트를 명시적으로 주입받는다.
   *
   * @param properties URL·인증정보·타임아웃 설정
   * @param restClient 네이버 검색 전용 HTTP 클라이언트
   */
  public NaverPlaceSearchClient(
      NaverSearchProperties properties, @Qualifier("naverSearchRestClient") RestClient restClient) {
    this.properties = properties;
    this.restClient = restClient;
  }

  /**
   * 네이버 지역 검색 결과를 최대 5개의 제공자 독립 장소 값으로 변환한다.
   *
   * <p>정상적으로 결과가 없는 {@code items=[]}는 빈 목록으로 반환하지만, 본문 또는 {@code items} 자체가 누락된 응답은 정상 빈 결과와 구분해 상류
   * 계약 위반으로 처리한다.
   *
   * @param keyword 검증과 trim이 끝난 검색어
   * @return 네이버 정확도순 장소 후보 최대 5개
   */
  @Override
  public List<PlaceSearchResult> search(String keyword) {
    requireConfiguredCredentials();

    NaverLocalSearchResponse response;
    try {
      response =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(SEARCH_PATH)
                          .queryParam("query", keyword)
                          .queryParam("display", MAX_RESULTS)
                          .queryParam("start", FIRST_RESULT_POSITION)
                          .queryParam("sort", ACCURACY_SORT)
                          .build())
              .header(CLIENT_ID_HEADER, properties.getClientId())
              .header(CLIENT_SECRET_HEADER, properties.getClientSecret())
              .retrieve()
              .body(NaverLocalSearchResponse.class);
    } catch (RestClientException e) {
      throw new PlaceSearchUpstreamException(e);
    }

    if (response == null || response.items() == null) {
      throw malformedResponse("Naver local search response missing items");
    }

    try {
      return response.items().stream()
          .limit(MAX_RESULTS)
          .map(NaverPlaceSearchClient::toPlaceSearchResult)
          .toList();
    } catch (IllegalArgumentException e) {
      throw new PlaceSearchUpstreamException(e);
    }
  }

  /** 로컬에서 키 없이 다른 기능을 실행하는 것은 허용하되, 실제 장소 검색 요청을 무인증으로 네이버에 보내지는 않는다. */
  private void requireConfiguredCredentials() {
    if (!properties.isConfigured()) {
      throw malformedResponse("Naver search credentials are not configured");
    }
  }

  /**
   * 네이버 원본 항목의 필수 표시값과 좌표를 검증한 뒤 프론트 친화적인 WGS84 값으로 변환한다.
   *
   * @param item 네이버 원본 장소 항목
   * @return 제공자 필드명이 제거된 내부 장소 검색 결과
   */
  private static PlaceSearchResult toPlaceSearchResult(NaverLocalSearchResponse.Item item) {
    if (item == null
        || !StringUtils.hasText(item.title())
        || !StringUtils.hasText(item.mapx())
        || !StringUtils.hasText(item.mapy())) {
      throw new IllegalArgumentException("Naver local search item missing required fields");
    }

    double lng = parseCoordinate(item.mapx(), NAVER_COORDINATE_SCALE);
    double lat = parseCoordinate(item.mapy(), NAVER_COORDINATE_SCALE);
    if (!isLatitude(lat) || !isLongitude(lng)) {
      throw new IllegalArgumentException("Naver local search item has invalid WGS84 coordinates");
    }

    return new PlaceSearchResult(
        item.title(),
        Objects.requireNonNullElse(item.address(), ""),
        Objects.requireNonNullElse(item.roadAddress(), ""),
        lat,
        lng);
  }

  /** 네이버가 10^7 배율의 정수 문자열로 제공하는 좌표를 십진수 각도로 되돌린다. */
  private static double parseCoordinate(String value, double scale) {
    return Long.parseLong(value) / scale;
  }

  /** 위도가 WGS84 유효 범위 안에 있는지 확인한다. */
  private static boolean isLatitude(double value) {
    return value >= -90.0 && value <= 90.0;
  }

  /** 경도가 WGS84 유효 범위 안에 있는지 확인한다. */
  private static boolean isLongitude(double value) {
    return value >= -180.0 && value <= 180.0;
  }

  /** 외부 응답·설정 계약 위반을 일관된 502 예외로 감싼다. */
  private static PlaceSearchUpstreamException malformedResponse(String message) {
    return new PlaceSearchUpstreamException(new IllegalStateException(message));
  }
}
