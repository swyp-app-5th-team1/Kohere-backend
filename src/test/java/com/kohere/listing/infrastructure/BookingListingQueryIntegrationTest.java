package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.TestcontainersConfiguration;
import com.kohere.listing.api.BookingListingQueryService;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.KitchenFacility;
import com.kohere.listing.domain.LaundryFacility;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.LivingAmenity;
import com.kohere.listing.domain.LocalizedText;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.ProvidedSupply;
import com.kohere.listing.domain.SecurityFeature;
import com.kohere.listing.domain.SupportedLanguage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * booking 협력용 {@link BookingListingQueryService} 구현이 실제 MongoDB에서 공개(PUBLISHED)·활성(ACTIVE) 방 상품만
 * {@link RoomOfferBookingView}로 매핑하고, 그 외에는 {@code Optional.empty()}를 반환하는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class BookingListingQueryIntegrationTest {

  private static final String LISTING_ID = "6858e2000000000000000002";
  private static final String ROOM_OFFER_ID = "6858e2000000000000000102";

  /** 저장 불변식(활성 방 1개 이상)을 만족시키려고 함께 두는 다른 방이다. */
  private static final String OTHER_ROOM_OFFER_ID = "6858e2000000000000000103";

  private static final String LISTINGS_COLLECTION = "listings";

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private ListingRepository listingRepository;
  @Autowired private BookingListingQueryService bookingListingQueryService;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void cleanListings() {
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
  }

  @Test
  @DisplayName("공개 매물의 활성 방 상품을 요약·가격으로 매핑한다")
  void findsPublishedActiveRoomOffer() {
    listingRepository.save(publishedListing());

    Optional<RoomOfferBookingView> result =
        bookingListingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID);

    assertThat(result).isPresent();
    RoomOfferBookingView view = result.orElseThrow();
    assertThat(view.listingId()).isEqualTo(LISTING_ID);
    assertThat(view.roomOfferId()).isEqualTo(ROOM_OFFER_ID);
    assertThat(view.title()).isEqualTo("테스트 고시원");
    assertThat(view.thumbnailUrl()).isEqualTo("https://cdn.kohere.com/listings/thumb.jpg");
    assertThat(view.address()).isEqualTo("서울특별시 관악구 테스트로 1");
    assertThat(view.roomOfferName()).isEqualTo("스탠다드 1인실");
    assertThat(view.deposit()).isEqualTo(500000);
    assertThat(view.monthlyRent()).isEqualTo(550000);
    assertThat(view.landlordId()).isEqualTo(1L);
  }

  @Test
  @DisplayName("roomOfferId가 일치하지 않으면 빈 값")
  void emptyWhenRoomOfferIdMismatch() {
    listingRepository.save(publishedListing());

    assertThat(
            bookingListingQueryService.findPublishedRoomOffer(
                LISTING_ID, "6858e20000000000000001ff"))
        .isEmpty();
  }

  @Test
  @DisplayName("공개 상태(PUBLISHED)가 아니면 빈 값")
  void emptyWhenListingNotPublished() {
    listingRepository.save(listingBuilder().status(Listing.ListingStatus.REJECTED).build());

    assertThat(bookingListingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .isEmpty();
  }

  @Test
  @DisplayName("방 상품이 비활성(INACTIVE)이면 빈 값")
  void emptyWhenRoomOfferInactive() {
    // 저장 불변식이 활성 방 1개 이상을 요구하므로 대상 방만 비활성으로 둔다.
    listingRepository.save(
        listingBuilder()
            .roomOffers(
                List.of(
                    roomOffer(
                        ROOM_OFFER_ID,
                        "스탠다드 1인실",
                        Listing.RoomOfferStatus.INACTIVE,
                        500000,
                        550000),
                    roomOffer(
                        OTHER_ROOM_OFFER_ID,
                        "스탠다드 2인실",
                        Listing.RoomOfferStatus.ACTIVE,
                        600000,
                        650000)))
            .build());

    assertThat(bookingListingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .isEmpty();
  }

  @Test
  @DisplayName("존재하지 않는 매물이면 빈 값")
  void emptyWhenListingMissing() {
    assertThat(
            bookingListingQueryService.findPublishedRoomOffer(
                "6858e20000000000000000ff", ROOM_OFFER_ID))
        .isEmpty();
  }

  @Test
  @DisplayName("잘못된 ObjectId 형식이면 예외 없이 빈 값")
  void emptyWhenInvalidObjectId() {
    assertThat(bookingListingQueryService.findPublishedRoomOffer("not-object-id", ROOM_OFFER_ID))
        .isEmpty();
  }

  private static Listing publishedListing() {
    return listingBuilder().build();
  }

  private static Listing.ListingBuilder listingBuilder() {
    return Listing.builder()
        .id(LISTING_ID)
        .schemaVersion(4)
        .landlordId(1L)
        .contact(new Listing.Contact("김담당", "+82) 10-1111-2222"))
        .businessRegistrationNumber("1112233344")
        .blogUrl(null)
        .ageMin(20)
        .ageMax(35)
        .title(LocalizedText.same("테스트 고시원"))
        .type(ListingType.GOSHIWON)
        .rentalType(Listing.RentalType.MONTHLY_RENT)
        .status(Listing.ListingStatus.PUBLISHED)
        .rejectionReason(null)
        .genderPolicy(Listing.GenderPolicy.ANY)
        .languagesSupported(Set.of(SupportedLanguage.ENGLISH))
        .favoriteCount(0)
        .imageUrls(List.of("https://cdn.kohere.com/listings/thumb.jpg"))
        .nearbyUniversityCodes(Set.of("SNU"))
        .createdAt(Instant.parse("2026-06-24T00:00:00Z"))
        .updatedAt(Instant.parse("2026-06-24T00:00:00Z"))
        .address(
            new Listing.Address("SEOUL", "GWANAK_GU", LocalizedText.same("서울특별시 관악구 테스트로 1"), null))
        .building(new Listing.Building(Listing.BuildingType.VILLA, 1, 2, 4, true, true))
        .description(LocalizedText.same("테스트 설명"))
        .extraNotes(LocalizedText.same("테스트 주의사항"))
        .facilities(
            new Listing.Facilities(
                Set.of(Listing.HeatingSystem.CENTRAL),
                Set.of(KitchenFacility.SHARED_REFRIGERATOR),
                Set.of(LaundryFacility.WASHER),
                Set.of(LivingAmenity.WIFI),
                Set.of(SecurityFeature.CCTV),
                Set.of(Listing.CommonSpaceType.SHARED_TOILET),
                Set.of(ProvidedSupply.BEDDING)))
        .location(new Listing.GeoPoint(126.951422, 37.459471))
        .nearestTransit(
            new Listing.NearestTransit(Listing.TransitType.SUBWAY, LocalizedText.same("서울대입구역"), 5))
        .nearbyFacilities(Set.of(NearbyFacility.CONVENIENCE_STORE))
        .arcRequired(ArcRequirement.NOT_REQUIRED)
        .refundPolicy(LocalizedText.same("입주 7일 전 취소 시 전액 환불"))
        .roomOffers(
            List.of(
                roomOffer(
                    ROOM_OFFER_ID, "스탠다드 1인실", Listing.RoomOfferStatus.ACTIVE, 500000, 550000)))
        .preferredNationalities(Set.of(Nationality.JAPAN))
        .contractDifficulties(Set.of(ContractDifficulty.LANGUAGE))
        .serviceFeedback(null);
  }

  private static Listing.RoomOffer roomOffer(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      int deposit,
      int monthlyRent) {
    return new Listing.RoomOffer(
        roomOfferId,
        LocalizedText.same(name),
        status,
        new Listing.Contract(2, 6),
        new Listing.Pricing(monthlyRent, deposit, 0, Listing.Currency.KRW),
        Set.of(ConditionTag.FEMALE_ONLY),
        List.of());
  }
}
