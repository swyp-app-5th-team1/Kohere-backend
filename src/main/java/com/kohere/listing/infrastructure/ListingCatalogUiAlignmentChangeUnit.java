package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.ListingCatalogCategory;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 매물 상세 Figma와 일치하는 라벨 및 신규 시설 코드를 {@code listingCatalog}에 반영한다.
 *
 * <p>최초 카탈로그를 만든 0108 변경 단위는 이미 실행된 환경에서 다시 실행되지 않는다. 따라서 신규 환경은 {@link
 * ListingCatalogSeedFixtures}의 정본을 사용하고, 기존 개발·스테이지·운영 환경은 이 0112 변경 단위가 동일한 결과로 맞춘다.
 *
 * <p>이 변경은 번역 사전인 {@code listingCatalog}만 수정한다. 실제 매물에 어떤 시설이 있는지는 각 {@code listings.facilities}
 * 코드가 결정하므로, 기존 매물에 신규 시설을 임의로 추가하지 않는다.
 */
@ChangeUnit(id = "listing-catalog-ui-alignment", order = "0112", author = "kohere")
public class ListingCatalogUiAlignmentChangeUnit {

  /**
   * 기존 라벨은 필요한 필드만 수정하고 신규 항목은 같은 명령으로 upsert한다.
   *
   * <p>문서 전체 교체 대신 {@code $set}을 사용하므로 향후 카탈로그 문서에 다른 메타데이터가 추가되더라도 이 마이그레이션이 삭제하지 않는다. {@code
   * upsert=true}이므로 이미 존재하는 문서는 갱신되고 없는 문서는 결정적 {@code _id=CATEGORY:CODE}로 생성된다.
   */
  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> collection =
        mongo.getCollection(ListingCatalogDocument.COLLECTION_NAME);

    for (CatalogChange change : changes()) {
      collection.updateOne(
          new Document("_id", change.id()),
          change.updateDocument(),
          new UpdateOptions().upsert(true));
    }
  }

  /**
   * 이번 변경에서 관리할 8개 카탈로그 정본이다.
   *
   * <p>앞의 네 항목은 기존 코드의 영어 라벨 교정이고, 뒤의 네 항목은 향후 매물 등록에서 사용할 신규 시설 코드다. 마이그레이션은 배포 후에도 변경되면 안 되므로 목록을
   * 시드에서 동적으로 가져오지 않고 당시 확정값으로 고정한다.
   */
  static List<CatalogChange> changes() {
    return List.of(
        new CatalogChange(
            ListingCatalogCategory.CONDITION_TAG, "NO_MAINT_FEE", "관리비 없음", "No Maint. Fee"),
        new CatalogChange(ListingCatalogCategory.CONDITION_TAG, "NO_ARC", "외국인등록증 없이 가능", "No ARC"),
        new CatalogChange(ListingCatalogCategory.LAUNDRY, "DRYER", "건조기", "Clothes Dryer"),
        new CatalogChange(ListingCatalogCategory.PROVIDED_SUPPLY, "TISSUE", "휴지", "Toilet Paper"),
        new CatalogChange(ListingCatalogCategory.LAUNDRY, "IRON", "다리미", "Iron"),
        new CatalogChange(
            ListingCatalogCategory.KITCHEN, "ELECTRIC_KETTLE", "전기포트", "Electric Kettle"),
        new CatalogChange(
            ListingCatalogCategory.COMMON_SPACE, "MEETING_ROOM", "회의실", "Meeting Room"),
        new CatalogChange(ListingCatalogCategory.COMMON_SPACE, "ROOFTOP", "옥상", "Rooftop"));
  }

  /**
   * 한 카탈로그 코드에 적용할 category·code·한국어·영어 정본이다.
   *
   * <p>{@link #updateDocument()}는 MongoDB의 중첩 필드인 {@code label.ko}, {@code label.en}만 직접 지정한다. 이
   * 방식은 기존 문서의 다른 필드를 보존하면서도 신규 upsert 문서에는 필요한 category와 code를 함께 채운다.
   */
  record CatalogChange(ListingCatalogCategory category, String code, String ko, String en) {

    /** 카탈로그 시드와 동일한 결정적 문서 식별자를 반환한다. */
    String id() {
      return category.name() + ":" + code;
    }

    /** MongoDB에서 기존 필드를 보존하며 정본 값만 갱신할 {@code $set} 문서를 만든다. */
    Document updateDocument() {
      Document fields =
          new Document("category", category.name())
              .append("code", code)
              .append("label.ko", ko)
              .append("label.en", en);
      return new Document("$set", fields);
    }
  }

  /** UI 정본과 신규 지원 코드를 과거 상태로 자동 되돌리지 않는 forward-only 변경이다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
