package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kohere.listing.infrastructure.migration.ContactSmsDropChangeUnit;
import com.kohere.listing.infrastructure.migration.ListingConsentsChangeUnit;
import com.kohere.listing.infrastructure.migration.ListingConsentsDropChangeUnit;
import com.kohere.listing.infrastructure.migration.ListingLocationRequiredChangeUnit;
import com.kohere.listing.infrastructure.migration.ListingStatusEnumExpandChangeUnit;
import com.kohere.listing.infrastructure.migration.ListingStatusEnumShrinkChangeUnit;
import com.kohere.listing.infrastructure.migration.ListingV4BaselineChangeUnit;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 시드 픽스처가 <b>최신 MongoDB validator를 통과하는지</b> 검증한다.
 *
 * <p><b>왜 이 테스트가 따로 필요한가.</b> 테스트 프로파일은 {@code mongock.enabled: false}라 마이그레이션이 돌지 않고, 따라서 {@code
 * $jsonSchema} validator가 <b>어느 테스트에도 적용되지 않는다</b>. 픽스처에 필수 필드가 빠져 있어도 {@link ListingTestSeeds}의
 * insert는 그냥 통과하므로, 스키마 위반은 {@code ./gradlew build}가 아니라 <b>로컬·dev에 시드를 넣는 순간에야</b> 드러난다.
 *
 * <p>그렇다고 전역으로 {@code mongock.enabled: true}를 켜지는 않는다. 지금 테스트들은 validator가 없다는 전제 위에 서 있어(예: 픽스처를
 * 가져와 필드를 바꿔 직접 {@code insertOne} 하는 테스트) 한꺼번에 켜면 그 전제가 무너진다. 대신 <b>이 테스트 하나</b>가 changeUnit 체인을 직접
 * 적용하고 픽스처를 넣어 본다.
 *
 * <p><b>Spring 컨텍스트를 띄우지 않는다.</b> changeUnit도 {@link ListingTestSeeds}도 {@link MongoTemplate} 하나만
 * 있으면 되고, {@code @SpringBootTest}로 올리면 이 검증에 쓰지도 않는 MySQL·Redis 컨테이너와 컨텍스트가 스위트 내내 함께 살아 있게 된다 — 전체
 * 실행에서 Docker 메모리를 밀어내 다른 테스트의 컨테이너 기동을 깨뜨린다.
 *
 * <p>체인을 순서대로 다 돌리는 것은 <b>ChangeUnit 사이의 가정도 함께 검증</b>하기 위해서다 — {@code 0119} 이후 유닛들은 컬렉션이 없으면
 * {@link IllegalStateException}을 던지도록 방어하고 있는데, 그 방어선을 실제로 밟는 곳이 지금까지 없었다.
 */
@Testcontainers
class ListingSchemaMigrationTest {

  private static final String LISTINGS_COLLECTION = "listings";
  private static final String DATABASE = "kohere-schema-test";

  @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  private static MongoClient client;
  private static MongoTemplate mongoTemplate;

  @BeforeAll
  static void connect() {
    client = MongoClients.create(mongo.getConnectionString());
    mongoTemplate = new MongoTemplate(client, DATABASE);
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }

  /** validator까지 매번 새로 세우기 위해 컬렉션을 통째로 지운다. */
  @BeforeEach
  void dropListings() {
    mongoTemplate.getCollection(LISTINGS_COLLECTION).drop();
  }

  @Test
  @DisplayName("시드 픽스처가 최신 validator를 통과한다")
  void fixtureSatisfiesLatestValidator() {
    applyChangeUnitChain();

    assertThatNoException()
        .isThrownBy(() -> ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION));
    assertThat(mongoTemplate.getCollection(LISTINGS_COLLECTION).countDocuments()).isEqualTo(2);
  }

  /**
   * {@code 0123}이 {@code required}에서 {@code consents}를 실제로 풀었는지 고정한다.
   *
   * <p>같은 문서를 {@code 0122} validator에 넣으면 거부된다 — 이 테스트가 초록불이라는 사실 자체가 마지막 {@code collMod}가 제약을 풀었다는
   * 증거다. 정본 픽스처에 더는 {@code consents}가 없으므로 문서를 손보지 않고 그대로 넣는다.
   */
  @Test
  @DisplayName("동의(consents)가 없어도 저장된다")
  void acceptsListingWithoutConsents() {
    applyChangeUnitChain();

    Document withoutConsents = ListingTestSeeds.listingDocuments().getFirst();

    assertThatNoException()
        .isThrownBy(
            () -> mongoTemplate.getCollection(LISTINGS_COLLECTION).insertOne(withoutConsents));
  }

  /**
   * {@code 0123}의 {@code $unset}이 <b>이미 저장된 문서</b>의 동의 이력까지 지우는지 검증한다.
   *
   * <p>이 저장소 최초의 데이터 이행 배치다 — 검증이 없으면 {@code collMod}만 돌고 {@code $unset}이 빠져도 초록불이다. {@code
   * consents}가 아직 필수인 validator 위에 문서를 넣어야 하므로 체인을 {@code 0122}까지만 돌린 뒤 {@code 0123}을 따로 실행한다. 기존
   * 문서가 운영에서 겪는 순서 그대로다.
   */
  @Test
  @DisplayName("0123은 이미 저장된 문서의 동의(consents)도 지운다")
  void dropsConsentsFromStoredDocuments() {
    applyChainThrough0122();

    Document withConsents = ListingTestSeeds.listingDocuments().getFirst();
    withConsents.put("consents", legacyConsents());
    mongoTemplate.getCollection(LISTINGS_COLLECTION).insertOne(withConsents);

    new ListingConsentsDropChangeUnit().execution(mongoTemplate);

    Document stored = mongoTemplate.getCollection(LISTINGS_COLLECTION).find().first();
    assertThat(stored).isNotNull();
    assertThat(stored.containsKey("consents")).isFalse();
  }

  @Test
  @DisplayName("제거된 상태 값(PAUSED)은 저장이 거부된다")
  void rejectsRemovedStatus() {
    applyChangeUnitChain();

    Document paused = ListingTestSeeds.listingDocuments().getFirst();
    paused.put("status", "PAUSED");

    assertThatThrownBy(() -> mongoTemplate.getCollection(LISTINGS_COLLECTION).insertOne(paused))
        .isInstanceOf(MongoWriteException.class);
  }

  @Test
  @DisplayName("추가된 상태 값(UPDATE_PENDING)은 저장이 허용된다")
  void acceptsExpandedStatus() {
    // 0121이 validationAction=error로 조여 두었으므로 0122 없이는 이 저장이 거부된다.
    // 체인에서 0122를 빠뜨리면 rejectsRemovedStatus는 옛 validator를 검증하며 통과해 버린다 —
    // 이 테스트가 그 짝이다.
    applyChangeUnitChain();

    Document updatePending = ListingTestSeeds.listingDocuments().getFirst();
    updatePending.put("status", "UPDATE_PENDING");

    mongoTemplate.getCollection(LISTINGS_COLLECTION).insertOne(updatePending);

    assertThat(mongoTemplate.getCollection(LISTINGS_COLLECTION).countDocuments()).isEqualTo(1);
  }

  /**
   * 설문 2종은 요청에서 선택이 됐지만 <b>저장 계약은 그대로</b>다(#270) — 빈 배열은 통과하고 키 제거는 여전히 거부돼야 한다.
   *
   * <p>이 두 케이스가 「Mongock changeUnit 없이 간다」는 결정을 코드로 고정한다. 나머지 테스트는 {@code mongock.enabled: false}라
   * 실제 {@code $jsonSchema}를 밟지 않으므로, 누군가 {@code required}에서 두 필드를 빼도 여기 말고는 아무도 알아채지 못한다.
   */
  @Test
  @DisplayName("설문 2종은 빈 배열이면 저장되고 키가 없으면 거부된다")
  void acceptsEmptySurveyArraysButRejectsMissingKeys() {
    applyChangeUnitChain();

    Document emptySurvey = ListingTestSeeds.listingDocuments().getFirst();
    emptySurvey.put("preferredNationalities", java.util.List.of());
    emptySurvey.put("contractDifficulties", java.util.List.of());
    mongoTemplate.getCollection(LISTINGS_COLLECTION).insertOne(emptySurvey);
    assertThat(mongoTemplate.getCollection(LISTINGS_COLLECTION).countDocuments()).isEqualTo(1);

    Document missingSurvey = ListingTestSeeds.listingDocuments().get(1);
    missingSurvey.remove("preferredNationalities");
    assertThatThrownBy(
            () -> mongoTemplate.getCollection(LISTINGS_COLLECTION).insertOne(missingSurvey))
        .isInstanceOf(MongoWriteException.class);
  }

  @Test
  @DisplayName("baseline 없이 후속 changeUnit을 돌리면 기동을 막는다")
  void refusesToRunWithoutBaseline() {
    assertThatThrownBy(() -> new ListingConsentsChangeUnit().execution(mongoTemplate))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 운영과 같은 순서로 스키마를 세운다. 마지막 유닛의 collMod가 최종 validator다. */
  private void applyChangeUnitChain() {
    applyChainThrough0122();
    new ListingConsentsDropChangeUnit().execution(mongoTemplate);
  }

  /**
   * {@code 0123} 직전까지의 체인이다. {@code consents}가 아직 필수인 validator를 밟아야 하는 테스트만 여기서 멈춘다 — 그 밖에는 항상
   * {@link #applyChangeUnitChain()}을 쓴다.
   */
  private void applyChainThrough0122() {
    new ListingV4BaselineChangeUnit().execution(mongoTemplate);
    new ListingLocationRequiredChangeUnit().execution(mongoTemplate);
    new ContactSmsDropChangeUnit().execution(mongoTemplate);
    new ListingConsentsChangeUnit().execution(mongoTemplate);
    new ListingStatusEnumShrinkChangeUnit().execution(mongoTemplate);
    new ListingStatusEnumExpandChangeUnit().execution(mongoTemplate);
  }

  /** {@code 0122} validator가 요구하는 동의 4필드다. 이 모양이라야 {@code 0123} 이전 문서로 저장된다. */
  private static Document legacyConsents() {
    return new Document("privacyPolicyAgreed", true)
        .append("listingExposureAgreed", true)
        .append("version", "v1.0")
        .append("agreedAt", Date.from(Instant.parse("2025-01-01T00:00:00Z")));
  }
}
