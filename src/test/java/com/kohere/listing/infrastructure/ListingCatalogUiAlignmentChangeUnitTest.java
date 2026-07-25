package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/** {@link ListingCatalogUiAlignmentChangeUnit}이 기존 MongoDB에 적용할 변경 내용을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ListingCatalogUiAlignmentChangeUnitTest {

  @Mock private MongoTemplate mongo;
  @Mock private MongoCollection<Document> collection;

  /**
   * 마이그레이션 정본은 네 기존 문구와 네 신규 시설을 빠짐없이 한 번씩 포함해야 한다.
   *
   * <p>문서 id 중복이 있으면 같은 실행 안에서 앞선 값을 뒤 항목이 덮어쓸 수 있으므로 총 개수와 고유 id 개수를 함께 검증한다.
   */
  @Test
  void changes_기존라벨_네건과_신규시설_네건을_정확히_정의한다() {
    List<ListingCatalogUiAlignmentChangeUnit.CatalogChange> changes =
        ListingCatalogUiAlignmentChangeUnit.changes();
    Map<String, ListingCatalogUiAlignmentChangeUnit.CatalogChange> changesById =
        changes.stream()
            .collect(
                Collectors.toMap(
                    ListingCatalogUiAlignmentChangeUnit.CatalogChange::id, Function.identity()));

    assertThat(changes).hasSize(8);
    assertThat(changesById).hasSize(8);
    assertThat(changesById.keySet())
        .containsExactlyInAnyOrder(
            "CONDITION_TAG:NO_MAINT_FEE",
            "CONDITION_TAG:NO_ARC",
            "LAUNDRY:DRYER",
            "PROVIDED_SUPPLY:TISSUE",
            "LAUNDRY:IRON",
            "KITCHEN:ELECTRIC_KETTLE",
            "COMMON_SPACE:MEETING_ROOM",
            "COMMON_SPACE:ROOFTOP");

    assertLabel(changesById, "CONDITION_TAG:NO_MAINT_FEE", "관리비 없음", "No Maint. Fee");
    assertLabel(changesById, "CONDITION_TAG:NO_ARC", "외국인등록증 없이 가능", "No ARC");
    assertLabel(changesById, "LAUNDRY:DRYER", "건조기", "Clothes Dryer");
    assertLabel(changesById, "PROVIDED_SUPPLY:TISSUE", "휴지", "Toilet Paper");
    assertLabel(changesById, "LAUNDRY:IRON", "다리미", "Iron");
    assertLabel(changesById, "KITCHEN:ELECTRIC_KETTLE", "전기포트", "Electric Kettle");
    assertLabel(changesById, "COMMON_SPACE:MEETING_ROOM", "회의실", "Meeting Room");
    assertLabel(changesById, "COMMON_SPACE:ROOFTOP", "옥상", "Rooftop");
  }

  /**
   * 실행 시 8개 문서를 모두 {@code $set + upsert} 방식으로 갱신해야 한다.
   *
   * <p>전체 문서를 replace하지 않는지 확인해 기존 문서의 향후 메타데이터가 보존되도록 하고, upsert가 켜져 있는지 확인해 신규 시설 문서도 함께 생성되도록
   * 보장한다.
   */
  @Test
  void execution_각카탈로그를_set과_upsert로_안전하게_반영한다() {
    when(mongo.getCollection(ListingCatalogDocument.COLLECTION_NAME)).thenReturn(collection);

    new ListingCatalogUiAlignmentChangeUnit().execution(mongo);

    ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
    ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
    ArgumentCaptor<UpdateOptions> optionsCaptor = ArgumentCaptor.forClass(UpdateOptions.class);
    verify(collection, times(8))
        .updateOne(filterCaptor.capture(), updateCaptor.capture(), optionsCaptor.capture());

    Set<String> updatedIds = new HashSet<>();
    for (int index = 0; index < filterCaptor.getAllValues().size(); index++) {
      Document filter = (Document) filterCaptor.getAllValues().get(index);
      Document update = (Document) updateCaptor.getAllValues().get(index);
      Document fields = update.get("$set", Document.class);

      String id = filter.getString("_id");
      updatedIds.add(id);
      assertThat(update.keySet()).containsExactly("$set");
      assertThat(fields)
          .containsKeys("category", "code", "label.ko", "label.en")
          .doesNotContainKey("labels");
      assertThat(id).isEqualTo(fields.getString("category") + ":" + fields.getString("code"));
      assertThat(optionsCaptor.getAllValues().get(index).isUpsert()).isTrue();
    }

    assertThat(updatedIds)
        .containsExactlyInAnyOrder(
            "CONDITION_TAG:NO_MAINT_FEE",
            "CONDITION_TAG:NO_ARC",
            "LAUNDRY:DRYER",
            "PROVIDED_SUPPLY:TISSUE",
            "LAUNDRY:IRON",
            "KITCHEN:ELECTRIC_KETTLE",
            "COMMON_SPACE:MEETING_ROOM",
            "COMMON_SPACE:ROOFTOP");
  }

  /** id로 찾은 변경 항목이 기대한 한국어·영어 정본을 가지는지 읽기 쉬운 실패 메시지와 함께 검증한다. */
  private static void assertLabel(
      Map<String, ListingCatalogUiAlignmentChangeUnit.CatalogChange> changesById,
      String id,
      String expectedKo,
      String expectedEn) {
    ListingCatalogUiAlignmentChangeUnit.CatalogChange change = changesById.get(id);

    assertThat(change).as("누락된 카탈로그 변경: %s", id).isNotNull();
    assertThat(change.ko()).as("%s 한국어 라벨", id).isEqualTo(expectedKo);
    assertThat(change.en()).as("%s 영어 라벨", id).isEqualTo(expectedEn);
  }
}
