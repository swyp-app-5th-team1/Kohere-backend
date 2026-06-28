package com.kohere.listing.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 매물 목록 조회에 쓰는 검색 조건이다. 컨트롤러가 받은 쿼리 파라미터를 서비스가 검증한 뒤 이 객체에 담아 저장소로 넘긴다. */
public record ListingSearchCondition(
    BoundingBox bounds,
    Integer minBudget,
    Integer maxBudget,
    Integer minDeposit,
    Integer maxDeposit,
    Set<ListingType> types,
    Set<ConditionTag> conditions,
    Boolean arcRequired,
    Boolean residentRegistration,
    ListingSort sort,
    Double centerLat,
    Double centerLng,
    int page,
    int size) {

  /** null 컬렉션과 null 정렬값을 안전한 기본값으로 바꿔 둔다. */
  public ListingSearchCondition {
    types = types == null ? Set.of() : Set.copyOf(types);
    conditions = conditions == null ? Set.of() : Set.copyOf(conditions);
    sort = sort == null ? ListingSort.RECOMMENDED : sort;
  }

  /** 전입신고 필터를 조건 태그와 같은 방식으로 다루기 위해 최종 조건 태그 묶음을 만든다. */
  public Set<ConditionTag> effectiveConditions() {
    EnumSet<ConditionTag> effective =
        conditions.isEmpty() ? EnumSet.noneOf(ConditionTag.class) : EnumSet.copyOf(conditions);
    if (Boolean.TRUE.equals(residentRegistration)) {
      effective.add(ConditionTag.RESIDENT_REGISTRATION);
    }
    return Collections.unmodifiableSet(effective);
  }

  /** 거리 계산에 필요한 기준 좌표가 둘 다 있는지 알려준다. */
  public boolean hasCenter() {
    return centerLat != null && centerLng != null;
  }

  /** 지도에서 보이는 네모 범위를 표현한다. 프론트는 보이는 범위를 보내고, 서버는 UX를 위해 이 범위를 조금 넓혀 조회한다. */
  public record BoundingBox(double swLat, double swLng, double neLat, double neLng) {

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
