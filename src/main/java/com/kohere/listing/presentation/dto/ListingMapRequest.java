package com.kohere.listing.presentation.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ListingType;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/** 지도 마커 조회 쿼리 파라미터다. 서버는 bbox 범위와 필터에 맞는 개별 매물 좌표만 반환한다. */
@Getter
@Setter
public class ListingMapRequest {

  private Double swLat;
  private Double swLng;
  private Double neLat;
  private Double neLng;
  private Integer minBudget;
  private Integer maxBudget;
  private Integer minDeposit;
  private Integer maxDeposit;
  private Set<ListingType> type;
  private Set<ConditionTag> conditions;
  private Boolean arcRequired;
  private Boolean residentRegistration;
}
