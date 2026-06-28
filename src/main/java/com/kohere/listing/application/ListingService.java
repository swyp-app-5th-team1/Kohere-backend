package com.kohere.listing.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.application.dto.FavoriteToggleResponse;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.domain.FavoriteRepository;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingInvalidBboxException;
import com.kohere.listing.domain.ListingInvalidSortParamException;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchCondition;
import com.kohere.listing.domain.ListingSort;
import com.kohere.listing.presentation.dto.ListingSearchRequest;
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

  private static final double BOUNDS_EXPANSION_RATIO = 0.2;
  private static final int MAX_PAGE_SIZE = 100;
  private static final double EARTH_RADIUS_METERS = 6_371_000.0;

  private final ListingRepository listingRepository;
  private final FavoriteRepository favoriteRepository;

  /** 지도 범위와 필터 조건을 적용해 매물 카드 목록을 페이지 단위로 반환한다. */
  public PageResponse<ListingSummaryResponse> getListings(ListingSearchRequest request) {
    ListingSearchCondition condition = buildSearchCondition(request);

    PageResponse<Listing> listings = listingRepository.search(condition);
    return PageResponse.of(
        listings.content().stream()
            .map(
                listing ->
                    ListingResponseMapper.toSummary(
                        listing, condition, distanceMeters(listing, condition)))
            .toList(),
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

  /** 컨트롤러에서 받은 값을 검증하고 저장소가 이해하기 쉬운 검색 조건으로 묶는다. */
  private static ListingSearchCondition buildSearchCondition(ListingSearchRequest request) {
    validateMoneyRange("monthlyRent", request.getMinBudget(), request.getMaxBudget());
    validateMoneyRange("deposit", request.getMinDeposit(), request.getMaxDeposit());
    validatePage(request.getPage(), request.getSize());
    validateCenter(request.getSort(), request.getCenterLat(), request.getCenterLng());

    ListingSearchCondition.BoundingBox bounds =
        buildExpandedBounds(
            request.getSwLat(), request.getSwLng(), request.getNeLat(), request.getNeLng());
    return new ListingSearchCondition(
        bounds,
        request.getMinBudget(),
        request.getMaxBudget(),
        request.getMinDeposit(),
        request.getMaxDeposit(),
        request.getType(),
        request.getConditions(),
        request.getArcRequired(),
        request.getResidentRegistration(),
        request.getSort(),
        request.getCenterLat(),
        request.getCenterLng(),
        request.getPage(),
        request.getSize());
  }

  /** 지도 좌표가 모두 있으면 유효성을 검사한 뒤 전체 범위를 20% 넓힌다. */
  private static ListingSearchCondition.BoundingBox buildExpandedBounds(
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
    return new ListingSearchCondition.BoundingBox(swLat, swLng, neLat, neLng)
        .expandedBy(BOUNDS_EXPANSION_RATIO);
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

  /** 거리순 정렬이나 거리 표시를 쓰려면 기준 좌표가 둘 다 있어야 한다. */
  private static void validateCenter(ListingSort sort, Double centerLat, Double centerLng) {
    boolean hasAny = centerLat != null || centerLng != null;
    if (hasAny && (centerLat == null || centerLng == null)) {
      throw new ListingInvalidSortParamException();
    }
    if (sort == ListingSort.DISTANCE && !hasAny) {
      throw new ListingInvalidSortParamException();
    }
    if (centerLat != null && (!isLatitude(centerLat) || !isLongitude(centerLng))) {
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
