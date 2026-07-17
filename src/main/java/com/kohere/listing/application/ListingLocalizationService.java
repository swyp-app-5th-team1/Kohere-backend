package com.kohere.listing.application;

import com.kohere.listing.domain.ListingCatalogEntry;
import com.kohere.listing.domain.ListingCatalogRepository;
import com.kohere.listing.domain.LocalizedText;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** listings 응답에 필요한 사용자 언어와 공통 코드 번역 사전을 요청 단위로 준비한다. */
@Service
@RequiredArgsConstructor
class ListingLocalizationService {

  private final ListingCatalogRepository listingCatalogRepository;

  /**
   * 지정 언어의 응답을 만들 수 있는 컨텍스트를 반환한다.
   *
   * <p>현재 MVP 지원 언어는 한국어와 영어다. {@code null}, 일본어 등 지원하지 않는 값은 외국인 앱의 기본 언어인 영어로 처리한다.
   */
  ListingLocalizationContext contextFor(String requestedLanguage) {
    String language =
        "ko".equalsIgnoreCase(requestedLanguage) ? "ko" : LocalizedText.DEFAULT_LANGUAGE;
    Map<ListingLocalizationContext.CatalogKey, LocalizedText> labelsByCode = new LinkedHashMap<>();
    for (ListingCatalogEntry entry : listingCatalogRepository.findAll()) {
      labelsByCode.put(
          new ListingLocalizationContext.CatalogKey(entry.category(), entry.code()), entry.label());
    }
    return new ListingLocalizationContext(language, Map.copyOf(labelsByCode));
  }
}
