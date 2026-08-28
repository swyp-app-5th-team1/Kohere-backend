package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.application.ListingImageConfirmer.ConfirmedListingImages;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.LandlordOnlyListingException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingNotEditableException;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingStateChangedException;
import com.kohere.listing.domain.LocalizedText;
import com.kohere.listing.domain.image.ListingImageStorage;
import com.kohere.listing.presentation.dto.ListingRegisterRequest;
import com.kohere.listing.presentation.dto.ListingUpdateRequest;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 임대인 매물 수정 서비스의 <b>게이트와 보상 순서</b>를 검증한다(US-3-9).
 *
 * <p>본문 조립(카탈로그 대조·다국어 복사·파생값)은 등록과 같은 {@link ListingWriteAssembler}를 그대로 타므로 여기서 다시 확인하지 않고, 성공
 * 경로의 응답 모양은 문서화 테스트가 실제 저장소로 확인한다. 이 테스트가 지키는 것은 <b>거절이 어디서 일어나는가</b>다.
 *
 * <p>특히 <b>사진을 확정 위치로 복사하기 전에</b> 인가·소유권·상태 게이트가 모두 끝나는지 본다. 순서가 뒤집히면 거절될 요청이 확정 위치에 흔적을 남기고, 그 객체는
 * 아무도 참조하지 않는 채 만료 규칙도 없는 접두에 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListingUpdateServiceTest {

  private static final long LANDLORD_ID = 7L;
  private static final long TENANT_ID = 8L;
  private static final String LISTING_ID = "68e0000000000000000000a1";
  private static final String ROOM_OFFER_ID = "68e0000000000000000001a1";
  private static final String COVER_KEY = "listings/" + LISTING_ID + "/cover/a.jpg";
  private static final String COVER_URL = "https://cdn.test/" + COVER_KEY;
  private static final String ROOM_KEY =
      "listings/" + LISTING_ID + "/rooms/" + ROOM_OFFER_ID + "/r1.jpg";
  private static final String ROOM_URL = "https://cdn.test/" + ROOM_KEY;
  private static final String ROOM_KEY_2 =
      "listings/" + LISTING_ID + "/rooms/" + ROOM_OFFER_ID + "/r2.jpg";
  private static final String ROOM_URL_2 = "https://cdn.test/" + ROOM_KEY_2;

  @Mock private ListingRepository listingRepository;
  @Mock private ListingWriteAssembler listingWriteAssembler;
  @Mock private ListingImageConfirmer listingImageConfirmer;
  @Mock private ListingImageStorage listingImageStorage;
  @Mock private LandlordListingService landlordListingService;
  @Mock private UserAccountService userAccountService;

  private ListingUpdateService service;

  @BeforeEach
  void setUp() {
    service =
        new ListingUpdateService(
            listingRepository,
            listingWriteAssembler,
            listingImageConfirmer,
            listingImageStorage,
            landlordListingService,
            userAccountService);

    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    // 저장된 URL은 이 저장소가 만든 것이므로 키가 그대로 돌아온다.
    given(listingImageStorage.keyOf(anyString()))
        .willAnswer(
            invocation -> {
              String url = invocation.getArgument(0);
              return url.startsWith("https://cdn.test/")
                  ? Optional.of(url.substring("https://cdn.test/".length()))
                  : Optional.empty();
            });
  }

  @Test
  @DisplayName("임대인이 아니면 수정할 수 없다")
  void rejectsNonLandlord() {
    assertThatThrownBy(() -> service.update(TENANT_ID, LISTING_ID, request()))
        .isInstanceOf(LandlordOnlyListingException.class);

    // 매물을 읽기도 전에 막힌다 — 남의 매물 존재 여부가 응답 시간으로도 새지 않는다.
    verify(landlordListingService, never()).findOwnListing(anyLong(), anyString());
    verify(listingImageConfirmer, never()).confirmEdit(anyString(), anyList(), any(), anyMap());
  }

  @Test
  @DisplayName("남의 매물이면 404다 — 소유권 판정은 조회 서비스가 한 곳에서 맡는다")
  void propagatesNotFoundForOtherLandlordListing() {
    given(landlordListingService.findOwnListing(LANDLORD_ID, LISTING_ID))
        .willThrow(new ListingNotFoundException());

    assertThatThrownBy(() -> service.update(LANDLORD_ID, LISTING_ID, request()))
        .isInstanceOf(ListingNotFoundException.class);

    verify(listingImageConfirmer, never()).confirmEdit(anyString(), anyList(), any(), anyMap());
  }

  @ParameterizedTest
  @EnumSource(
      value = Listing.ListingStatus.class,
      names = {"PENDING", "UPDATE_PENDING"})
  @DisplayName("심사 중인 매물은 사진을 복사하기 전에 거절한다")
  void rejectsListingUnderReviewBeforeCopyingImages(Listing.ListingStatus status) {
    given(landlordListingService.findOwnListing(LANDLORD_ID, LISTING_ID))
        .willReturn(listing(status));

    assertThatThrownBy(() -> service.update(LANDLORD_ID, LISTING_ID, request()))
        .isInstanceOf(ListingNotEditableException.class);

    // 이 단정이 이 테스트의 핵심이다. 게이트가 복사 뒤로 밀리면 거절된 요청이
    // 확정 위치에 객체를 남기는데, 그 접두에는 만료 규칙이 없다.
    verify(listingImageConfirmer, never()).confirmEdit(anyString(), anyList(), any(), anyMap());
    verify(listingRepository, never()).saveIfStatus(any(), any());
  }

  @Test
  @DisplayName("문서에 없는 방 식별자를 보내면 400이다")
  void rejectsUnknownRoomOfferId() {
    given(landlordListingService.findOwnListing(LANDLORD_ID, LISTING_ID))
        .willReturn(listing(Listing.ListingStatus.REJECTED));

    ListingUpdateRequest unknownRoom = request("68e00000000000000000ffff");

    assertThatThrownBy(() -> service.update(LANDLORD_ID, LISTING_ID, unknownRoom))
        .isInstanceOf(InvalidInputException.class);

    verify(listingImageConfirmer, never()).confirmEdit(anyString(), anyList(), any(), anyMap());
  }

  @Test
  @DisplayName("조회와 저장 사이에 상태가 바뀌면 409이고 이번 복사분만 되돌린다")
  void rejectsWhenStatusChangedBetweenReadAndSave() {
    Listing current = listing(Listing.ListingStatus.REJECTED);
    givenAssemblyReaches(current);
    // 조건부 교체가 빈 값 — 그 사이 관리자가 심사를 끝냈다는 뜻이다.
    given(listingRepository.saveIfStatus(any(), any())).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(LANDLORD_ID, LISTING_ID, request()))
        .isInstanceOf(ListingStateChangedException.class);

    verify(listingImageConfirmer).rollback(any());
    // 옛 확정본은 아직 문서가 참조하고 있다. 지우면 공개 중인 매물의 사진이 사라진다.
    verify(listingImageConfirmer, never()).discardReplaced(any());
    verify(listingImageConfirmer, never()).discardPendingKeys(any());
  }

  @Test
  @DisplayName("저장에 성공해야 임시본과 참조를 잃은 확정본을 치운다")
  void cleansUpOnlyAfterSuccessfulSave() {
    Listing current = listing(Listing.ListingStatus.REJECTED);
    givenAssemblyReaches(current);
    given(listingRepository.saveIfStatus(any(), any()))
        .willAnswer(invocation -> Optional.of(invocation.getArgument(0)));

    service.update(LANDLORD_ID, LISTING_ID, request());

    verify(listingImageConfirmer).discardPendingKeys(any());
    verify(listingImageConfirmer).discardReplaced(any());
    verify(listingImageConfirmer, never()).rollback(any());
  }

  /** 조립·확정 복사를 통과시켜 저장까지 닿게 한다. 조립 자체는 등록과 공유라 여기서 검증하지 않는다. */
  private void givenAssemblyReaches(Listing current) {
    given(landlordListingService.findOwnListing(LANDLORD_ID, LISTING_ID)).willReturn(current);
    given(listingWriteAssembler.catalog()).willReturn(ListingCatalogCodes.of(List.of()));
    given(listingWriteAssembler.apply(any(), any(), anyList(), any()))
        .willAnswer(invocation -> invocation.getArgument(0));
    given(listingImageConfirmer.confirmEdit(anyString(), anyList(), any(), anyMap()))
        .willReturn(
            new ConfirmedListingImages(
                List.of(COVER_URL), List.of(List.of(ROOM_URL, ROOM_URL_2)), List.of()));
  }

  private static ListingUpdateRequest request() {
    return request(ROOM_OFFER_ID);
  }

  private static ListingUpdateRequest request(String roomOfferId) {
    return new ListingUpdateRequest(
        "코히어 고시원",
        com.kohere.listing.domain.ListingType.GOSHIWON,
        new ListingRegisterRequest.ContactRequest("김담당", "02-1234-5678"),
        "1112233344",
        null,
        new ListingRegisterRequest.AddressRequest("서울특별시 서대문구 신촌로 1", "3층", 37.5595, 126.9425),
        new ListingRegisterRequest.BuildingRequest(
            Listing.BuildingType.COMMERCIAL_BUILDING, 8, "3~5", false, true),
        Listing.GenderPolicy.ANY,
        Set.of(com.kohere.listing.domain.SupportedLanguage.ENGLISH),
        "20~35",
        com.kohere.listing.domain.ArcRequirement.NOT_REQUIRED,
        new ListingRegisterRequest.FacilitiesRequest(
            Set.of(Listing.HeatingSystem.INDIVIDUAL),
            Set.of(com.kohere.listing.domain.KitchenFacility.INDUCTION),
            Set.of(com.kohere.listing.domain.LaundryFacility.WASHER),
            Set.of(com.kohere.listing.domain.LivingAmenity.WIFI),
            Set.of(com.kohere.listing.domain.SecurityFeature.CCTV),
            Set.of(Listing.CommonSpaceType.SHARED_KITCHEN),
            Set.of(com.kohere.listing.domain.ProvidedSupply.BEDDING)),
        new ListingRegisterRequest.NearestTransitRequest(Listing.TransitType.SUBWAY, "신촌역", 7),
        Set.of(com.kohere.listing.domain.NearbyFacility.CONVENIENCE_STORE),
        "역세권 고시원입니다.",
        "취사는 공용 주방만 가능합니다.",
        "입주 7일 전까지 전액 환불",
        List.of(COVER_KEY),
        List.of(
            new ListingUpdateRequest.RoomOfferUpdateRequest(
                roomOfferId,
                Listing.RoomOfferStatus.ACTIVE,
                "스탠다드 1인실",
                new ListingRegisterRequest.ContractRequest(1, 12),
                new ListingRegisterRequest.PricingRequest(500000, 1000000, 50000),
                Set.of(ConditionTag.MOVE_IN_NOW),
                List.of(ROOM_KEY, ROOM_KEY_2))),
        Set.of(com.kohere.listing.domain.Nationality.JAPAN),
        Set.of(com.kohere.listing.domain.ContractDifficulty.LANGUAGE),
        null);
  }

  private static Listing listing(Listing.ListingStatus status) {
    return Listing.builder()
        .id(LISTING_ID)
        .schemaVersion(4)
        .landlordId(LANDLORD_ID)
        .status(status)
        .favoriteCount(3)
        .imageUrls(List.of(COVER_URL))
        .nearbyUniversityCodes(Set.of())
        .roomOffers(
            List.of(
                new Listing.RoomOffer(
                    ROOM_OFFER_ID,
                    LocalizedText.same("스탠다드 1인실"),
                    Listing.RoomOfferStatus.ACTIVE,
                    new Listing.Contract(1, 12),
                    new Listing.Pricing(500000, 1000000, 50000, Listing.Currency.KRW),
                    Set.of(ConditionTag.MOVE_IN_NOW),
                    List.of(ROOM_URL, ROOM_URL_2))))
        .createdAt(Instant.parse("2026-06-01T00:00:00Z"))
        .updatedAt(Instant.parse("2026-06-01T00:00:00Z"))
        .build();
  }
}
