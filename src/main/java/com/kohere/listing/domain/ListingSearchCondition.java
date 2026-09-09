package com.kohere.listing.domain;

import java.util.Set;

/**
 * 매물 검색에 쓰는 내부 조건이다.
 *
 * <p>컨트롤러가 받은 쿼리 파라미터를 서비스가 검증·보정한 뒤 이 객체에 담아 저장소로 넘긴다. {@code centerLat}와 {@code centerLng}는 프론트가
 * 보내는 값이 아니라, 서비스가 요청 bbox의 원본 중심점으로 계산한 거리 기준 좌표다.
 *
 * <p>{@code listingIds}는 지도 마커에서 고른 매물만 카드로 받을 때 쓰는 추가 필터다. 다른 조건과 AND로 걸리며, <b>지도 마커 조회는 이 값을 받지
 * 않으므로 항상 빈 집합</b>이다 — 목록과 지도가 같은 criteria 빌더를 공유하기 때문에 여기서 비워 두는 것이 그 경계다.
 */
public record ListingSearchCondition(
    BoundingBox bounds,
    Integer radiusMeters,
    Integer minBudget,
    Integer maxBudget,
    Integer minDeposit,
    Integer maxDeposit,
    Set<ListingType> types,
    Set<ConditionTag> conditions,
    Set<String> listingIds,
    ListingSort sort,
    Double centerLat,
    Double centerLng,
    int page,
    int size) {

  /** null 컬렉션과 null 정렬값을 안전한 기본값으로 바꿔 둔다. */
  public ListingSearchCondition {
    types = types == null ? Set.of() : Set.copyOf(types);
    listingIds = listingIds == null ? Set.of() : Set.copyOf(listingIds);
    conditions = conditions == null ? Set.of() : Set.copyOf(conditions);
    sort = sort == null ? ListingSort.RECOMMENDED : sort;
    if (radiusMeters != null && radiusMeters <= 0) {
      radiusMeters = null;
    }
  }

  /** roomOffers.filterTags에 저장되는 조건이다. v4에서는 요청 조건과 저장 태그가 1:1이라 그대로 반환한다. */
  public Set<ConditionTag> roomOfferConditions() {
    return conditions;
  }

  /** 거리 계산에 필요한 기준 좌표가 둘 다 있는지 알려준다. */
  public boolean hasCenter() {
    return centerLat != null && centerLng != null;
  }

  /** 중심 좌표와 반경이 모두 있어 실제 반경 필터를 적용할 수 있는지 알려준다. */
  public boolean hasRadius() {
    return hasCenter() && radiusMeters != null;
  }

  /** 지도에서 보이는 네모 범위를 표현한다. */
  public record BoundingBox(double swLat, double swLng, double neLat, double neLng) {

    /** 지도 화면 중심 위도다. 거리순 정렬과 거리 표시 기준으로 사용한다. */
    public double centerLat() {
      return (swLat + neLat) / 2.0;
    }

    /** 지도 화면 중심 경도다. 거리순 정렬과 거리 표시 기준으로 사용한다. */
    public double centerLng() {
      return (swLng + neLng) / 2.0;
    }

    /** 전체 가로·세로가 ratio만큼 커지도록 상하좌우를 같은 비율로 넓힌다. */
    public BoundingBox expandedBy(double ratio) {
      double latPadding = (neLat - swLat) * ratio / 2.0;
      double lngPadding = (neLng - swLng) * ratio / 2.0;
      return new BoundingBox(
          clamp(swLat - latPadding, -90.0, 90.0),
          clamp(swLng - lngPadding, -180.0, 180.0),
          clamp(neLat + latPadding, -90.0, 90.0),
          clamp(neLng + lngPadding, -180.0, 180.0));
    }

    /** 위도·경도가 지구 좌표 범위를 넘지 않도록 자른다. */
    private static double clamp(double value, double min, double max) {
      return Math.max(min, Math.min(max, value));
    }
  }
}
