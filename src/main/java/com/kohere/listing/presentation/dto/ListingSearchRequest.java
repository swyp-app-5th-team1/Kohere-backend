package com.kohere.listing.presentation.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ListingSort;
import com.kohere.listing.domain.ListingType;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/** 매물 목록 조회 쿼리 파라미터를 한곳에 모아 둔 요청 DTO다. 컨트롤러가 긴 {@code @RequestParam} 목록을 직접 들고 있지 않도록 해준다. */
@Getter
@Setter
public class ListingSearchRequest {

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
  private ListingSort sort = ListingSort.RECOMMENDED;
  private int page = 0;
  private int size = 20;
}
