package com.kohere.listing.infrastructure;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 기존 매물의 영문 주변시설에서 편의점 브랜드를 공통 UI 문구로 통일한다.
 *
 * <p>0113 마이그레이션은 이마트24를 {@code Convenience Store}로 바꿨지만, 영문 브랜드인 {@code CU}와 {@code GS25}는 원문을
 * 유지했다. 주변시설 문자열은 프론트에서 별도 가공 없이 그대로 표시되므로 같은 시설 종류가 매물마다 서로 다른 이름으로 노출됐다.
 *
 * <p>이미 머지된 0113은 환경에 따라 실행됐을 수 있어 수정하지 않는다. 이 0114 변경 단위가 0113 실행 여부와 관계없이 기존 영문값을 한 번 더 정규화해 모든
 * 환경을 같은 최종 상태로 맞춘다.
 */
@ChangeUnit(id = "listing-convenience-store-label-unification", order = "0114", author = "kohere")
public class ListingConvenienceStoreLabelUnificationChangeUnit {

  private static final String NEARBY_PLACES_EN_PATH = "nearestTransit.nearbyPlacesDescription.en";
  private static final String CONVENIENCE_STORE = "Convenience Store";

  /**
   * 편의점으로 취급할 과거 한국어·영문 표기다.
   *
   * <p>대소문자 차이를 무시하기 위해 소문자 키로 관리한다. 0113 적용 전 데이터가 남은 환경도 안전하게 처리할 수 있도록 한국어 이마트24 표기도 포함한다.
   */
  private static final Set<String> CONVENIENCE_STORE_ALIASES =
      Set.of("cu", "gs25", "emart24", "e-mart24", "이마트24", "convenience store");

  /**
   * 모든 매물을 확인하고 실제로 달라지는 영문 주변시설 값만 부분 갱신한다.
   *
   * <p>{@code ko} 원문과 주소·가격·재고 등 다른 필드는 전혀 변경하지 않는다. 같은 값을 다시 처리하면 결과가 달라지지 않아 배포 재시도에도 안전하다.
   */
  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(ListingDocument.COLLECTION_NAME);

    for (Document listing : listings.find(Filters.type(NEARBY_PLACES_EN_PATH, "string"))) {
      String current = nearbyPlacesEnglish(listing);
      String unified = unify(current);
      if (current == null || current.equals(unified)) {
        continue;
      }
      listings.updateOne(
          Filters.eq("_id", listing.get("_id")), Updates.set(NEARBY_PLACES_EN_PATH, unified));
    }
  }

  /**
   * 쉼표로 구분된 영문 주변시설에서 편의점 별칭을 {@code Convenience Store} 하나로 통일한다.
   *
   * <p>{@code GS25, CU}처럼 여러 편의점 브랜드가 함께 있더라도 UI에는 시설 종류가 한 번만 필요하므로 첫 번째 위치에 {@code Convenience
   * Store}를 넣고 나머지는 제거한다. 편의점이 아닌 항목의 순서와 표기는 그대로 보존한다.
   */
  static String unify(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }

    List<String> unified = new ArrayList<>();
    boolean convenienceStoreAdded = false;
    for (String rawItem : value.split(",")) {
      String item = rawItem.trim();
      if (item.isEmpty()) {
        continue;
      }

      if (isConvenienceStore(item)) {
        if (!convenienceStoreAdded) {
          unified.add(CONVENIENCE_STORE);
          convenienceStoreAdded = true;
        }
        continue;
      }
      unified.add(item);
    }
    return unified.isEmpty() ? value : String.join(", ", unified);
  }

  /** 한 항목이 편의점의 과거 표기인지 대소문자를 무시하고 확인한다. */
  private static boolean isConvenienceStore(String value) {
    return CONVENIENCE_STORE_ALIASES.contains(value.toLowerCase(Locale.ROOT));
  }

  /** listings 문서에서 영문 주변시설 문자열을 안전하게 읽는다. */
  private static String nearbyPlacesEnglish(Document listing) {
    Object nearestTransitValue = listing.get("nearestTransit");
    if (!(nearestTransitValue instanceof Document nearestTransit)) {
      return null;
    }
    Object nearbyPlacesValue = nearestTransit.get("nearbyPlacesDescription");
    if (!(nearbyPlacesValue instanceof Document nearbyPlaces)) {
      return null;
    }
    return nearbyPlaces.getString("en");
  }

  /**
   * UI 표시 정본을 과거 브랜드 표기로 자동 복원하지 않는 forward-only 변경이다.
   *
   * <p>운영 적용 전 대상 listings 문서를 백업하고, 문제가 생기면 백업본으로 복구한다.
   */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
