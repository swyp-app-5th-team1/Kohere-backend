package com.kohere.listing.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.application.dto.FavoriteListingResponse;
import com.kohere.listing.application.dto.FavoriteToggleResponse;
import com.kohere.listing.application.dto.FavoriteToggleResult;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingKeywordSearchResponse;
import com.kohere.listing.application.dto.ListingMapResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.application.dto.MatchedPlaceResponse;
import com.kohere.listing.application.dto.RecentListingsResponse;
import com.kohere.listing.domain.Favorite;
import com.kohere.listing.domain.FavoriteListing;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingAreaTooLargeException;
import com.kohere.listing.domain.ListingInvalidBboxException;
import com.kohere.listing.domain.ListingInvalidSortParamException;
import com.kohere.listing.domain.ListingMapSearchResult;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchCondition;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.ListingSort;
import com.kohere.listing.domain.RecentListingRepository;
import com.kohere.listing.domain.RecentListingView;
import com.kohere.listing.domain.SearchPlace;
import com.kohere.listing.domain.SearchPlaceRepository;
import com.kohere.listing.presentation.dto.ListingKeywordSearchRequest;
import com.kohere.listing.presentation.dto.ListingMapRequest;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 매물 탐색·찜 유스케이스 조율. 도메인(포트)을 호출하고 흐름만 조율한다. 도메인 규칙은 엔티티/도메인 서비스에 둔다 (docs/convention/code-style.md
 * §3-3).
 *
 * <p>의존성은 생성자 주입({@code @RequiredArgsConstructor})으로 받는다(§3-4). 인증 주체(userId)는 presentation 계층이
 * {@code @AuthenticationPrincipal}에서 꺼내 명시적으로 전달한다.
 *
 * <p>TODO: 영속 계층(JPA) 도입 시 유스케이스에 트랜잭션 경계({@code @Transactional})를 추가한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingService {

  private static final double BOUNDS_EXPANSION_RATIO = 0.2;

  /** 지도 SDK에 한 번에 넘기는 마커가 너무 많아지지 않도록 둔 서버 방어 상한이다. */
  private static final int MAX_MAP_MARKERS = 500;

  /** 키워드로 매칭된 장소 주변에서 보여줄 MVP 기본 검색 반경이다. */
  private static final int KEYWORD_SEARCH_RADIUS_METERS = 3_000;

  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_KEYWORD_LENGTH = 50;
  private static final int RECENT_LISTINGS_RESPONSE_LIMIT = 10;
  private static final int RECENT_LISTINGS_STORAGE_LIMIT = 30;
  private static final double EARTH_RADIUS_METERS = 6_371_000.0;
  private static final double METERS_PER_LATITUDE_DEGREE = 111_320.0;

  private final ListingRepository listingRepository;
  private final FavoriteRepository favoriteRepository;
  private final RecentListingRepository recentListingRepository;
  private final SearchPlaceRepository searchPlaceRepository;

  /**
   * 지도 범위와 필터 조건을 적용해 목록 카드 페이지를 반환한다.
   *
   * <p>목록 카드 1개는 건물/숙소 매물({@code Listing}) 1개다. 같은 고시원 안에 조건에 맞는 방 상품이 여러 개 있어도 {@code listingId}는
   * 한 번만 내려가며, 가격·보증금·관리비·계약기간·재고는 조건을 통과한 방 상품들의 범위와 합계로 내려간다.
   */
  public PageResponse<ListingSummaryResponse> getListings(ListingSearchRequest request) {
    ListingSearchCondition condition = buildSearchCondition(request);

    PageResponse<ListingSearchResult> listings = listingRepository.search(condition);
    return PageResponse.of(
        listings.content().stream()
            .map(
                result ->
                    ListingResponseMapper.toSummary(
                        result, distanceMeters(result.listing(), condition)))
            .toList(),
        listings.page());
  }

  /**
   * 학교명·지역명·지하철역명 키워드를 POI로 매칭하고, 그 장소 주변의 매물 카드 목록을 반환한다.
   *
   * <p>검색어가 POI에 없다는 것은 서버 오류가 아니므로 404를 던지지 않는다. 프론트가 "검색된 장소가 없어요" 상태를 쉽게 구분할 수 있도록 {@code
   * matchedPlace=null}, {@code content=[]}, {@code totalElements=0}으로 200 OK를 반환한다.
   */
  public ListingKeywordSearchResponse searchListings(ListingKeywordSearchRequest request) {
    String keyword = validateAndNormalizeKeyword(request.getKeyword());
    validatePage(request.getPage(), request.getSize());

    return SearchPlaceMatcher.bestMatch(keyword, searchPlaceRepository.findActive())
        .map(place -> searchListingsAround(place, request))
        .orElseGet(() -> emptyKeywordSearchResponse(request.getPage(), request.getSize()));
  }

  /** 지도 SDK 클러스터링에 사용할 개별 매물 마커 좌표를 반환한다. */
  public ListingMapResponse getListingMap(ListingMapRequest request) {
    ListingSearchCondition condition = buildMapSearchCondition(request);
    ListingMapSearchResult result = listingRepository.searchForMap(condition, MAX_MAP_MARKERS);
    if (result.total() > MAX_MAP_MARKERS) {
      throw new ListingAreaTooLargeException();
    }
    return new ListingMapResponse(
        result.listings().stream().map(ListingResponseMapper::toMapMarker).toList(),
        result.total());
  }

  /**
   * 단일 매물 상세 정보를 조회하고 최근 본 매물 기록을 남긴다.
   *
   * <p>상세 응답은 사용자가 명시적으로 요청한 핵심 기능이고, 최근 본 저장은 그 뒤에 따라오는 부가 기능이다. 그래서 매물 조회가 성공하면 먼저 상세 DTO를 구성하고,
   * 최근 본 저장이나 오래된 기록 정리가 실패하더라도 상세 조회 자체는 실패시키지 않는다. 실패 내용은 운영자가 확인할 수 있도록 warn 로그로 남긴다.
   */
  public ListingDetailResponse getListing(Long userId, String listingId) {
    Listing listing = requirePublishedListing(listingId);
    boolean favorited =
        favoriteRepository.findByUserIdAndListingId(userId, listing.getId()).isPresent();
    ListingDetailResponse response = ListingResponseMapper.toDetail(listing, favorited);

    recordRecentListing(userId, listing.getId());

    return response;
  }

  /**
   * 내가 찜한 공개 매물 목록을 최근 찜한 순으로 반환한다.
   *
   * <p>프론트가 별도 정렬 파라미터를 보내지 않아도 항상 {@code favoritedAt desc} 기준이다. {@code DRAFT}, {@code PAUSED},
   * {@code DELETED} 같은 비공개 매물은 목록과 총 개수에서 제외하므로, 화면의 페이지 정보는 실제 표시 가능한 카드 수와 일치한다.
   */
  public PageResponse<FavoriteListingResponse> getMyFavorites(Long userId, int page, int size) {
    validatePage(page, size);
    PageResponse<FavoriteListing> favorites =
        favoriteRepository.findPublishedByUserIdOrderByFavoritedAtDesc(userId, page, size);
    return PageResponse.of(
        favorites.content().stream().map(ListingResponseMapper::toFavoriteListing).toList(),
        favorites.page());
  }

  /**
   * 로그인 사용자가 최근 본 공개 매물 목록을 최신순 최대 10개 반환한다.
   *
   * <p>DB에는 사용자별 최대 30개까지 보관하지만, 화면 응답은 그중 공개 상태이고 활성 방 상품이 있어 실제 카드로 보여줄 수 있는 매물만 최대 10개 내려준다.
   * 비공개/삭제/노출중지 매물은 사용자가 과거에 봤더라도 목록에서 숨긴다.
   */
  public RecentListingsResponse getRecentListings(Long userId) {
    List<RecentListingView> recentListings =
        recentListingRepository.findPublishedByUserIdOrderByViewedAtDesc(
            userId, RECENT_LISTINGS_RESPONSE_LIMIT);
    List<String> listingIds = recentListings.stream().map(view -> view.listing().getId()).toList();
    Set<String> favoritedListingIds =
        favoriteRepository.findFavoritedListingIds(userId, listingIds);

    return new RecentListingsResponse(
        recentListings.stream()
            .map(
                view ->
                    ListingResponseMapper.toRecentListing(
                        view, favoritedListingIds.contains(view.listing().getId())))
            .toList());
  }

  /**
   * 매물을 찜한다.
   *
   * <p>대상 매물은 반드시 {@code PUBLISHED} 상태여야 한다. 처음 찜하면 {@code created=true}와 증가한 {@code
   * favoriteCount}를 반환하고, 이미 찜한 상태라면 저장·카운트 증감 없이 {@code created=false}와 현재 {@code favoriteCount}를
   * 반환한다.
   */
  public FavoriteToggleResult addFavorite(Long userId, String listingId) {
    Listing listing = requirePublishedListing(listingId);
    if (favoriteRepository.findByUserIdAndListingId(userId, listingId).isPresent()) {
      return new FavoriteToggleResult(
          false, new FavoriteToggleResponse(true, listing.getFavoriteCount()));
    }

    boolean created =
        favoriteRepository.saveIfAbsent(
            Favorite.builder()
                .userId(userId)
                .listingId(listing.getId())
                .favoritedAt(Instant.now())
                .build());
    int favoriteCount =
        created
            ? listingRepository.increaseFavoriteCount(listingId)
            : requirePublishedListing(listingId).getFavoriteCount();
    return new FavoriteToggleResult(created, new FavoriteToggleResponse(true, favoriteCount));
  }

  /**
   * 매물 찜을 해제한다.
   *
   * <p>찜 기록이 있으면 {@code favorites} 문서를 삭제하고 {@code favoriteCount}를 1 감소시킨다. 원래 찜하지 않은 매물이어도 에러가 아니라
   * 현재 상태({@code favorited=false})를 반환한다. 단, 대상 매물 자체가 없거나 공개 상태가 아니면 스펙대로 {@code
   * LISTING_NOT_FOUND}가 된다.
   */
  public FavoriteToggleResponse removeFavorite(Long userId, String listingId) {
    Listing listing = requirePublishedListing(listingId);
    boolean deleted = favoriteRepository.deleteByUserIdAndListingId(userId, listingId);
    int favoriteCount =
        deleted ? listingRepository.decreaseFavoriteCount(listingId) : listing.getFavoriteCount();
    return new FavoriteToggleResponse(false, favoriteCount);
  }

  /**
   * 공개 중인 매물만 사용자 액션 대상으로 취급한다.
   *
   * <p>존재하지 않는 매물, 잘못된 ObjectId, {@code DRAFT}/{@code PAUSED}/{@code DELETED} 매물은 모두 사용자에게 구분하지 않고
   * {@code LISTING_NOT_FOUND}로 응답한다. 비공개 리소스의 존재 여부를 노출하지 않기 위한 정책이다.
   */
  private Listing requirePublishedListing(String listingId) {
    return listingRepository
        .findById(listingId)
        .filter(listing -> listing.getStatus() == Listing.ListingStatus.PUBLISHED)
        .orElseThrow(ListingNotFoundException::new);
  }

  /**
   * 최근 본 매물 기록을 저장하고 사용자별 보관 상한을 정리한다.
   *
   * <p>최근 본 기록 저장은 상세 조회를 돕는 부가 기능이다. MongoDB 일시 장애나 중복 키 경쟁 같은 예외가 여기서 발생해도 사용자가 보고 있던 상세 화면을 깨뜨리면
   * 안 되므로, 이 메서드는 예외를 밖으로 던지지 않고 로그만 남긴다.
   */
  private void recordRecentListing(Long userId, String listingId) {
    try {
      recentListingRepository.upsertViewedAt(userId, listingId, Instant.now());
      recentListingRepository.deleteOldByUserIdKeepingLatest(userId, RECENT_LISTINGS_STORAGE_LIMIT);
    } catch (RuntimeException e) {
      log.warn("최근 본 매물 저장/정리에 실패했습니다. userId={}, listingId={}", userId, listingId, e);
    }
  }

  /** 컨트롤러에서 받은 값을 검증하고 저장소가 이해하기 쉬운 검색 조건으로 묶는다. */
  private static ListingSearchCondition buildSearchCondition(ListingSearchRequest request) {
    validateMoneyRange("monthlyRent", request.getMinBudget(), request.getMaxBudget());
    validateMoneyRange("deposit", request.getMinDeposit(), request.getMaxDeposit());
    validatePage(request.getPage(), request.getSize());

    ListingSearchCondition.BoundingBox viewportBounds =
        buildBounds(request.getSwLat(), request.getSwLng(), request.getNeLat(), request.getNeLng());
    validateDistanceSort(request.getSort(), viewportBounds);

    return new ListingSearchCondition(
        expandedBounds(viewportBounds),
        null,
        request.getMinBudget(),
        request.getMaxBudget(),
        request.getMinDeposit(),
        request.getMaxDeposit(),
        request.getType(),
        request.getConditions(),
        request.getSort(),
        centerLat(viewportBounds),
        centerLng(viewportBounds),
        request.getPage(),
        request.getSize());
  }

  /** 지도 마커 조회는 bbox가 필수이며, 목록과 같은 필터를 적용한다. */
  private static ListingSearchCondition buildMapSearchCondition(ListingMapRequest request) {
    validateMoneyRange("monthlyRent", request.getMinBudget(), request.getMaxBudget());
    validateMoneyRange("deposit", request.getMinDeposit(), request.getMaxDeposit());

    ListingSearchCondition.BoundingBox viewportBounds =
        buildBounds(request.getSwLat(), request.getSwLng(), request.getNeLat(), request.getNeLng());
    if (viewportBounds == null) {
      throw new ListingInvalidBboxException();
    }
    return new ListingSearchCondition(
        expandedBounds(viewportBounds),
        null,
        request.getMinBudget(),
        request.getMaxBudget(),
        request.getMinDeposit(),
        request.getMaxDeposit(),
        request.getType(),
        request.getConditions(),
        ListingSort.RECOMMENDED,
        null,
        null,
        0,
        MAX_MAP_MARKERS);
  }

  /** 매칭된 POI 좌표를 중심으로 3km 반경의 매물 카드를 조회한다. */
  private static ListingSearchCondition buildKeywordSearchCondition(
      SearchPlace place, ListingKeywordSearchRequest request) {
    ListingSearchCondition.BoundingBox bounds =
        boundsAround(place.getLat(), place.getLng(), KEYWORD_SEARCH_RADIUS_METERS);
    return new ListingSearchCondition(
        bounds,
        KEYWORD_SEARCH_RADIUS_METERS,
        null,
        null,
        null,
        null,
        null,
        null,
        request.getSort(),
        place.getLat(),
        place.getLng(),
        request.getPage(),
        request.getSize());
  }

  /**
   * POI 주변 검색 결과를 응답 DTO로 조립한다.
   *
   * <p>목록 API와 같은 매물 카드 구조를 재사용하되, {@code distanceMeters}는 요청 bbox 중심이 아니라 검색된 POI 좌표를 기준으로 계산된다.
   */
  private ListingKeywordSearchResponse searchListingsAround(
      SearchPlace place, ListingKeywordSearchRequest request) {
    ListingSearchCondition condition = buildKeywordSearchCondition(place, request);
    PageResponse<ListingSearchResult> listings = listingRepository.search(condition);
    return new ListingKeywordSearchResponse(
        new MatchedPlaceResponse(place.getType(), place.getName(), place.getLat(), place.getLng()),
        listings.content().stream()
            .map(
                result ->
                    ListingResponseMapper.toSummary(
                        result, distanceMeters(result.listing(), condition)))
            .toList(),
        listings.page());
  }

  /** POI 매칭이 없을 때 프론트가 빈 상태를 바로 판단할 수 있는 페이지 응답을 만든다. */
  private static ListingKeywordSearchResponse emptyKeywordSearchResponse(int page, int size) {
    return new ListingKeywordSearchResponse(null, List.of(), new PageInfo(page, size, 0, 0, false));
  }

  /** keyword는 필수이며, 앞뒤 공백 제거 후 1~50자 안에 있어야 한다. */
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

  /** 지도 좌표가 모두 있으면 유효성을 검사한 뒤 원본 지도 화면 bbox로 묶는다. */
  private static ListingSearchCondition.BoundingBox buildBounds(
      Double swLat, Double swLng, Double neLat, Double neLng) {
    boolean hasAny = swLat != null || swLng != null || neLat != null || neLng != null;
    if (!hasAny) {
      return null;
    }
    if (swLat == null || swLng == null || neLat == null || neLng == null) {
      throw new ListingInvalidBboxException();
    }
    if (!isLatitude(swLat)
        || !isLatitude(neLat)
        || !isLongitude(swLng)
        || !isLongitude(neLng)
        || swLat >= neLat
        || swLng >= neLng) {
      throw new ListingInvalidBboxException();
    }
    return new ListingSearchCondition.BoundingBox(swLat, swLng, neLat, neLng);
  }

  /** UX상 화면 가장자리에 걸친 매물도 보이도록 MongoDB 조회 bbox만 20% 넓힌다. */
  private static ListingSearchCondition.BoundingBox expandedBounds(
      ListingSearchCondition.BoundingBox bounds) {
    return bounds == null ? null : bounds.expandedBy(BOUNDS_EXPANSION_RATIO);
  }

  /**
   * 중심 좌표와 반경(m)을 MongoDB 1차 후보 조회용 bbox로 바꾼다.
   *
   * <p>정확한 3km 판정은 저장소의 거리 계산 필터가 한 번 더 수행한다. 여기서 만든 bbox는 MongoDB가 너무 넓은 범위를 읽지 않도록 후보를 먼저 줄이는
   * 용도다.
   */
  private static ListingSearchCondition.BoundingBox boundsAround(
      double centerLat, double centerLng, int radiusMeters) {
    double latDelta = radiusMeters / METERS_PER_LATITUDE_DEGREE;
    double lngMeter =
        METERS_PER_LATITUDE_DEGREE * Math.max(0.000001, Math.cos(Math.toRadians(centerLat)));
    double lngDelta = radiusMeters / lngMeter;
    return new ListingSearchCondition.BoundingBox(
        clamp(centerLat - latDelta, -90.0, 90.0),
        clamp(centerLng - lngDelta, -180.0, 180.0),
        clamp(centerLat + latDelta, -90.0, 90.0),
        clamp(centerLng + lngDelta, -180.0, 180.0));
  }

  /** bbox가 있으면 원본 지도 화면 중심 위도를 반환한다. */
  private static Double centerLat(ListingSearchCondition.BoundingBox bounds) {
    return bounds == null ? null : bounds.centerLat();
  }

  /** bbox가 있으면 원본 지도 화면 중심 경도를 반환한다. */
  private static Double centerLng(ListingSearchCondition.BoundingBox bounds) {
    return bounds == null ? null : bounds.centerLng();
  }

  /** 돈 필터는 음수가 아니어야 하고, 최소값이 최대값보다 클 수 없다. */
  private static void validateMoneyRange(String field, Integer min, Integer max) {
    if (min != null && min < 0) {
      throw new InvalidInputException(field + " 최소값은 0 이상이어야 합니다.");
    }
    if (max != null && max < 0) {
      throw new InvalidInputException(field + " 최대값은 0 이상이어야 합니다.");
    }
    if (min != null && max != null && min > max) {
      throw new InvalidInputException(field + " 최소값은 최대값보다 클 수 없습니다.");
    }
  }

  /** 페이지 번호와 크기가 API 약속 범위 안에 있는지 확인한다. */
  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page는 0 이상이어야 합니다.");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidInputException("size는 1 이상 100 이하이어야 합니다.");
    }
  }

  /** 거리순 정렬은 요청 bbox의 중심 좌표를 기준으로 하므로 bbox 네 값이 모두 필요하다. */
  private static void validateDistanceSort(
      ListingSort sort, ListingSearchCondition.BoundingBox bounds) {
    if (sort == ListingSort.DISTANCE && bounds == null) {
      throw new ListingInvalidSortParamException();
    }
  }

  /** 위도 값이 지구 좌표 범위 안에 있는지 확인한다. */
  private static boolean isLatitude(double value) {
    return value >= -90.0 && value <= 90.0;
  }

  /** 경도 값이 지구 좌표 범위 안에 있는지 확인한다. */
  private static boolean isLongitude(double value) {
    return value >= -180.0 && value <= 180.0;
  }

  /** 좌표 계산 결과가 WGS84 범위를 벗어나지 않도록 자른다. */
  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  /** 기준 좌표가 있으면 매물까지의 직선 거리를 미터 단위로 계산한다. */
  private static Integer distanceMeters(Listing listing, ListingSearchCondition condition) {
    if (!condition.hasCenter()) {
      return null;
    }
    double lat1 = Math.toRadians(condition.centerLat());
    double lat2 = Math.toRadians(listing.getLocation().latitude());
    double latDelta = lat2 - lat1;
    double lngDelta = Math.toRadians(listing.getLocation().longitude() - condition.centerLng());
    double a =
        Math.sin(latDelta / 2.0) * Math.sin(latDelta / 2.0)
            + Math.cos(lat1) * Math.cos(lat2) * Math.sin(lngDelta / 2.0) * Math.sin(lngDelta / 2.0);
    double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    return (int) Math.round(EARTH_RADIUS_METERS * c);
  }
}
