package com.kohere.listing.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.Test;

/** {@link ListingCatalogLabelFieldChangeUnit}의 필드명 이행과 재실행 안전성을 검증한다. */
class ListingCatalogLabelFieldChangeUnitTest {

  /** 기존 {@code labels:{ko,en}}의 값은 손실 없이 {@code label:{ko,en}}로 이동해야 한다. */
  @Test
  void migrate_기존labels를_label로_변경하고_번역값을_보존한다() {
    Document legacyLabel = new Document("ko", "월세").append("en", "Monthly Rent");
    Document source =
        new Document("_id", "RENTAL_TYPE:MONTHLY_RENT")
            .append("category", "RENTAL_TYPE")
            .append("code", "MONTHLY_RENT")
            .append("labels", legacyLabel);

    Document migrated = ListingCatalogLabelFieldChangeUnit.migrate(source);

    assertThat(migrated).doesNotContainKey("labels");
    assertThat(migrated.get("label", Document.class))
        .containsEntry("ko", "월세")
        .containsEntry("en", "Monthly Rent");
    // 변환 함수가 입력 객체를 직접 수정하면 반복 처리 중 예상치 못한 부작용이 생길 수 있다.
    assertThat(source).containsKey("labels");
  }

  /** 두 필드가 모두 존재하는 비정상 문서는 새 {@code label}을 보존해 운영자가 수정한 번역을 덮어쓰지 않는다. */
  @Test
  void migrate_label과_labels가_함께있으면_새label을_우선보존한다() {
    Document source =
        new Document("labels", new Document("ko", "과거 값").append("en", "Legacy"))
            .append("label", new Document("ko", "새 값").append("en", "Current"));

    Document migrated = ListingCatalogLabelFieldChangeUnit.migrate(source);

    assertThat(migrated).doesNotContainKey("labels");
    assertThat(migrated.get("label", Document.class))
        .containsEntry("ko", "새 값")
        .containsEntry("en", "Current");
  }

  /** 이미 이행된 문서를 다시 변환해도 결과가 달라지지 않아야 한다. */
  @Test
  void migrate_재실행해도_동일한_멱등변환이다() {
    Document current =
        new Document("code", "SUBWAY")
            .append("label", new Document("ko", "지하철").append("en", "Subway"));

    Document migrated = ListingCatalogLabelFieldChangeUnit.migrate(current);

    assertThat(migrated).isEqualTo(current);
  }
}
