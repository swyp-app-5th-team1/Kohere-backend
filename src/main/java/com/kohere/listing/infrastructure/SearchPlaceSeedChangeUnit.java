package com.kohere.listing.infrastructure;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * 키워드 검색 장소 사전(searchPlaces) 캐노니컬 베이스라인을 MongoDB에 적재한다.
 *
 * <p>POI는 사용자 데이터가 아니라 서버가 소유하는 레퍼런스 카탈로그다. 따라서 앱 코드와 함께 배포되는 Mongock changeUnit으로 환경당 정확히 1회 생성한다.
 * test 프로파일은 Mongock을 끄므로 테스트는 필요한 POI를 직접 시드한다.
 */
@ChangeUnit(id = "listing-search-place-seed", order = "0100", author = "kohere")
public class SearchPlaceSeedChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    // 베이스라인 카탈로그이므로 searchPlaces만 비우고 다시 넣는다. listings 같은 사용자·운영 매물 데이터는 건드리지 않는다.
    mongo.remove(new Query(), SearchPlaceDocument.class);
    mongo.insertAll(SearchPlaceSeedFixtures.documents());
  }

  /** forward-only: 레퍼런스 카탈로그 시드는 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
