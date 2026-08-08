package com.kohere.listing.domain.catalog;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.domain.LocalizedText;

/**
 * 모든 매물이 공유하는 코드 하나와 한국어·영어 표시 문구를 묶은 카탈로그 항목이다.
 *
 * <p>실제 매물 문서에는 {@code FEMALE_ONLY} 같은 코드만 저장하고, 사용자에게 보여줄 문구는 이 항목에서 찾는다. 따라서 같은 번역을 매물마다 반복 저장하지
 * 않으면서도 검색·검증에는 안정적인 코드를 계속 사용할 수 있다.
 *
 * @param category 코드의 사용 영역
 * @param code 언어와 무관한 고정 코드
 * @param label 하나의 코드에 대한 한국어·영어 표시 문구
 */
public record ListingCatalogEntry(
    ListingCatalogCategory category, String code, LocalizedText label) {

  /** 카탈로그 키와 번역 문구가 모두 유효한지 생성 시점에 검증한다. */
  public ListingCatalogEntry {
    if (category == null) {
      throw new InvalidInputException("listingCatalog.category가 필요합니다.");
    }
    if (code == null || code.isBlank()) {
      throw new InvalidInputException("listingCatalog.code가 필요합니다.");
    }
    if (label == null) {
      throw new InvalidInputException("listingCatalog.label이 필요합니다.");
    }
  }
}
