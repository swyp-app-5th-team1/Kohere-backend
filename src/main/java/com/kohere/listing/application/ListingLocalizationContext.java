package com.kohere.listing.application;

import com.kohere.listing.application.dto.CodeLabelResponse;
import com.kohere.listing.domain.ListingCatalogCategory;
import com.kohere.listing.domain.LocalizedText;
import java.util.Map;

/**
 * 매물 응답 하나를 만들 때 사용할 언어와 공통 코드 번역을 함께 보관하는 요청 단위 읽기 전용 객체다.
 *
 * <p>응답 매퍼가 코드 하나마다 MongoDB를 다시 조회하지 않도록, 서비스가 요청 시작 시 전체 카탈로그를 한 번 읽어 이 객체로 전달한다.
 */
record ListingLocalizationContext(String language, Map<CatalogKey, LocalizedText> catalogLabels) {

  /** 매물 고유 문구에서 현재 사용자 언어의 문자열 하나를 선택한다. */
  String text(LocalizedText text) {
    return text == null ? null : text.resolve(language);
  }

  /** enum 값을 프론트 표시용 code/label 응답으로 바꾼다. */
  CodeLabelResponse codeLabel(ListingCatalogCategory category, Enum<?> code) {
    return codeLabel(category, code.name());
  }

  /** 문자열 코드를 프론트 표시용 code/label 응답으로 바꾼다. */
  CodeLabelResponse codeLabel(ListingCatalogCategory category, String code) {
    LocalizedText labelTranslations = catalogLabels.get(new CatalogKey(category, code));
    // 배포 순서 문제로 새 코드의 카탈로그가 아직 없더라도 API 전체를 실패시키지 않고 code를 임시 label로 사용한다.
    String label = labelTranslations == null ? code : labelTranslations.resolve(language);
    return new CodeLabelResponse(code, label);
  }

  /** 카탈로그 맵에서 category가 다른 동명 코드를 구분하기 위한 내부 키다. */
  record CatalogKey(ListingCatalogCategory category, String code) {}
}
