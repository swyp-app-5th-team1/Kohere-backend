package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.TestcontainersConfiguration;
import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 채팅 협력용 {@link ChatListingQueryService}가 실제 MongoDB 매물을 공개 상태로 제한하고 채팅에 필요한 최소 정보만 반환하는지 검증한다.
 *
 * <p>listing 모듈의 정본 v4 시드를 사용해 MongoDB 문서 매핑까지 함께 확인한다. 이 테스트를 통해 chat 모듈이 매물 내부 모델이나 저장 구조에 직접
 * 의존하지 않아도 안전하게 임대인과 표시 정보를 받을 수 있음을 보장한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class ChatListingQueryIntegrationTest {

  private static final String LISTINGS_COLLECTION = "listings";

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private ChatListingQueryService chatListingQueryService;
  @Autowired private MongoTemplate mongoTemplate;

  /** 테스트가 서로의 매물 상태에 영향을 주지 않도록 매번 컬렉션을 비운다. */
  @BeforeEach
  void cleanListings() {
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
  }

  /** 공개 매물의 실제 임대인과 채팅방·문의서에 필요한 공개 정보를 함께 반환한다. */
  @Test
  @DisplayName("공개 매물을 채팅방 생성용 최소 정보로 조회한다")
  void findsPublishedListingForChat() {
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);

    ChatListingView result =
        chatListingQueryService.findPublishedListing(ListingTestSeeds.LISTING_ID).orElseThrow();

    assertThat(result.listingId()).isEqualTo(ListingTestSeeds.LISTING_ID);
    assertThat(result.landlordId()).isEqualTo(11L);
    assertThat(result.title()).isEqualTo("Sillim Stay");
    assertThat(result.address()).isEqualTo("56-15 Na-ro, Sillim-dong, Gwanak-gu, Seoul");
    assertThat(result.thumbnailUrl())
        .isEqualTo("https://cdn.kohere.app/listings/68e0000000000000000000a1/1.jpg");
    assertThat(result.city()).isEqualTo("SEOUL");
    assertThat(result.district()).isEqualTo("GWANAK_GU");
    assertThat(result.listingType()).isEqualTo("GOSHIWON");
    assertThat(result.monthlyRentMin()).isEqualTo(380_000);
    assertThat(result.monthlyRentMax()).isEqualTo(520_000);
  }

  /** 공개되지 않은 매물의 상대방과 표시 정보는 chat 모듈에 전달하지 않는다. */
  @Test
  @DisplayName("공개 상태가 아니면 빈 값을 반환한다")
  void returnsEmptyWhenListingIsNotPublished() {
    Document rejectedListing = ListingTestSeeds.listingDocuments().getFirst();
    rejectedListing.put("status", "REJECTED");
    mongoTemplate.getCollection(LISTINGS_COLLECTION).insertOne(rejectedListing);

    assertThat(chatListingQueryService.findPublishedListing(ListingTestSeeds.LISTING_ID)).isEmpty();
  }

  /** 존재하지 않는 식별자는 채팅방을 만들 수 없다는 뜻의 빈 결과로 처리한다. */
  @Test
  @DisplayName("존재하지 않는 매물이면 빈 값을 반환한다")
  void returnsEmptyWhenListingDoesNotExist() {
    assertThat(chatListingQueryService.findPublishedListing("68e0000000000000000000ff")).isEmpty();
  }

  /** MongoDB ObjectId 형식이 잘못돼도 저장소 예외를 외부 모듈로 누출하지 않는다. */
  @Test
  @DisplayName("잘못된 매물 식별자 형식이면 예외 없이 빈 값을 반환한다")
  void returnsEmptyWhenListingIdIsInvalid() {
    assertThat(chatListingQueryService.findPublishedListing("not-object-id")).isEmpty();
  }
}
