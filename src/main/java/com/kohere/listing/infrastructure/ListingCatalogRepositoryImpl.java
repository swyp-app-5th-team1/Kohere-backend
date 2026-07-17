package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.ListingCatalogEntry;
import com.kohere.listing.domain.ListingCatalogRepository;
import com.kohere.listing.domain.LocalizedText;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Spring Data 문서를 Listing 도메인의 카탈로그 항목으로 변환하는 저장소 어댑터다. */
@Repository
@RequiredArgsConstructor
class ListingCatalogRepositoryImpl implements ListingCatalogRepository {

  private final ListingCatalogMongoRepository mongoRepository;

  /**
   * 전체 카탈로그를 한 번에 읽어 도메인 모델로 변환한다.
   *
   * <p>Listing 목록 응답에서 코드마다 MongoDB를 다시 조회하지 않도록 상위 서비스가 이 결과로 요청 단위 번역 맵을 만든다.
   */
  @Override
  public List<ListingCatalogEntry> findAll() {
    return mongoRepository.findAll().stream().map(ListingCatalogRepositoryImpl::toDomain).toList();
  }

  /** 저장 문서의 언어-키 맵에서 필수 한국어·영어 값을 꺼내 도메인 항목을 만든다. */
  private static ListingCatalogEntry toDomain(ListingCatalogDocument document) {
    Map<String, String> label = document.getLabel();
    return new ListingCatalogEntry(
        document.getCategory(),
        document.getCode(),
        new LocalizedText(
            label == null ? null : label.get("ko"), label == null ? null : label.get("en")));
  }
}
