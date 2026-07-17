package com.kohere.lifetip.infrastructure;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * 주제 카탈로그({@code lifeTipTopics})의 <b>모든</b> 주제에 이미지·짧은/긴 설명 4필드를 예시 기본값으로 백필한다(order 0001, issue
 * #150).
 *
 * <p>order 0000 캐노니컬 시드는 이미 배포된 환경에서 changelog에 기록돼 <b>다시 실행되지 않는다</b> — 그 환경의 주제 도큐먼트는 {@code
 * shortDescription}·{@code longDescription}·{@code imageUrl}·{@code backgroundImageUrl}가 없다. 이때 배포
 * 환경의 실제 주제 {@code code}는 시드의 예시 코드와 다를 수 있으므로 코드로 찾지 않고 컬렉션 <b>전체</b>를 {@code updateMulti}로 갱신해
 * {@link LifeTipCatalogSeedChangeUnit}의 예시 기본값 4필드를 채운다. 운영이 이후 실제 이미지·설명을 DB에서 갱신한다. 신규 환경은 0000
 * 시드가 이미 같은 예시 값을 넣었고 여기서 다시 set 하므로 멱등이다.
 *
 * <p>{@code test} 프로파일은 {@code mongock.enabled=false}라 실행되지 않는다.
 */
@ChangeUnit(id = "lifetip-topic-media-and-descriptions", order = "0001", author = "kohere")
public class LifeTipTopicMediaBackfillChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    mongo.updateMulti(
        new Query(),
        new Update()
            .set("shortDescription", LifeTipCatalogSeedChangeUnit.EXAMPLE_SHORT_DESCRIPTION)
            .set("longDescription", LifeTipCatalogSeedChangeUnit.EXAMPLE_LONG_DESCRIPTION)
            .set("imageUrl", LifeTipCatalogSeedChangeUnit.EXAMPLE_IMAGE_URL)
            .set("backgroundImageUrl", LifeTipCatalogSeedChangeUnit.EXAMPLE_BACKGROUND_IMAGE_URL),
        LifeTipTopicDocument.class);
  }

  /** forward-only: 백필은 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
