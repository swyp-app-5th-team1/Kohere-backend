package com.kohere.listing.presentation.dto;

import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.SupportedLanguage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;

/**
 * 매물 수정 요청 DTO({@code PUT /api/v2/listings/{listingId}}, 임대인 전용).
 *
 * <p><b>등록 때 보낸 속성을 그대로 다시 보낸다.</b> 부분 수정이 아니라 전체 교체다 — 주소에서 좌표·행정구역·주변 대학 코드가 파생되므로 일부만 보내면 서버가
 * 파생값을 어느 기준으로 다시 만들지 알 수 없고, 보내지 않은 필드가 지워진 것인지 그대로 두라는 것인지도 구분되지 않는다.
 *
 * <p>등록 요청과 다른 곳은 <b>두 곳뿐</b>이다.
 *
 * <ul>
 *   <li>{@code roomOffers[]}에 {@code roomOfferId}와 {@code status}가 있다. 방을 내리는 것은 요청에서 빼는 것이 아니라
 *       {@code INACTIVE}로 보내는 것이다.
 *   <li>사진 키에 <b>이미 확정된 키</b>를 섞어 보낼 수 있다. 그대로 둘 사진은 확정 키로, 새로 올린 사진은 임시 키로 가리킨다.
 * </ul>
 *
 * <p>{@code consents}는 등록과 똑같이 받고 게이트도 같다 — 둘 다 {@code true}가 아니면 {@code 422}다. 다만 저장된 동의 버전과 시각은
 * <b>등록 시점 것을 그대로 둔다</b>. 그 값이 최초 동의의 증빙이라 수정할 때마다 덮어쓰면 기록이 사라진다.
 *
 * <p>요청에 없는 값은 서버가 정한다 — {@code status}(전이가 정한다) · {@code rejectionReason}(항상 지운다) · {@code
 * favoriteCount}·{@code createdAt}·{@code landlordId}·{@code schemaVersion}(모두 승계) · {@code
 * updatedAt}. 애초에 칸이 없으므로 클라이언트가 보낼 수도 없다.
 *
 * <p>docs/api/specs/03-listings-favorites.md 「임대인 매물 관리」 · 시퀀스 US-3-9.
 */
public record ListingUpdateRequest(
    @NotBlank String title,
    @NotNull ListingType type,
    @NotNull @Valid ListingRegisterRequest.ContactRequest contact,
    @NotBlank String businessRegistrationNumber,
    String blogUrl,
    @NotNull @Valid ListingRegisterRequest.AddressRequest address,
    @NotNull @Valid ListingRegisterRequest.BuildingRequest building,
    @NotNull Listing.GenderPolicy genderPolicy,
    @NotEmpty Set<SupportedLanguage> languagesSupported,
    @NotBlank String ageRange,
    @NotNull ArcRequirement arcRequired,
    @NotNull @Valid ListingRegisterRequest.FacilitiesRequest facilities,
    @NotNull @Valid ListingRegisterRequest.NearestTransitRequest nearestTransit,
    @NotEmpty Set<NearbyFacility> nearbyFacilities,
    @NotBlank String description,
    @NotBlank String extraNotes,
    @NotBlank String refundPolicy,
    @NotEmpty List<String> imageKeys,
    @NotEmpty @Valid List<RoomOfferUpdateRequest> roomOffers,
    Set<Nationality> preferredNationalities,
    Set<ContractDifficulty> contractDifficulties,
    String serviceFeedback,
    @NotNull @Valid ListingRegisterRequest.ConsentsRequest consents)
    implements ListingWriteRequest {

  /**
   * 방 상품 하나다. 등록과 달리 <b>식별자와 상태</b>를 함께 보낸다.
   *
   * <p>{@code roomOfferId}가 {@code null}이면 새로 추가하는 방이고, 값이 있으면 그 방을 고치는 것이다. 문서에 없는 식별자를 보내면 {@code
   * 400}이다.
   *
   * <p>{@code status}로 방을 내리고 되살린다. 요청에서 방을 <b>빼는</b> 방식으로 내리지 않는 이유는 되살릴 수단이 없어지기 때문이다 — 응답에 보이지
   * 않는 방은 다시 제출할 수도 없다. 다만 클라이언트 결함으로 식별자가 통째로 빠지는 경우에 대비해, 그때도 삭제하지 않고 비활성으로 보존한다.
   *
   * <p>저장된 뒤 활성 방이 하나도 남지 않으면 {@code 400}이다. 상태만 공개인데 목록·상세에는 나타나지 않는 매물이 만들어지기 때문이다.
   *
   * <p>{@code roomImageKeys}에는 그대로 둘 확정 키와 새로 올린 임시 키를 섞을 수 있다. 단 <b>신규 방({@code roomOfferId}가
   * {@code null})은 임시 키만</b> 가능하다 — 확정 키는 방 식별자를 포함하는데 그 값이 아직 발급되지 않았다.
   */
  public record RoomOfferUpdateRequest(
      String roomOfferId,
      @NotNull Listing.RoomOfferStatus status,
      @NotBlank String name,
      @NotNull @Valid ListingRegisterRequest.ContractRequest contract,
      @NotNull @Valid ListingRegisterRequest.PricingRequest pricing,
      @NotEmpty Set<ConditionTag> filterTags,
      @NotEmpty List<String> roomImageKeys) {

    /** 새로 추가하는 방인지 본다. */
    public boolean isNew() {
      return roomOfferId == null || roomOfferId.isBlank();
    }
  }

  /** 방마다의 조건 태그다. 카탈로그 대조가 등록과 같은 방식으로 이 값을 훑는다. */
  public List<Set<ConditionTag>> roomFilterTags() {
    return roomOffers.stream().map(RoomOfferUpdateRequest::filterTags).toList();
  }

  /** 방마다의 사진 키다. 바깥 리스트 순서가 {@code roomOffers} 순서다. */
  public List<List<String>> roomImageKeys() {
    return roomOffers.stream().map(RoomOfferUpdateRequest::roomImageKeys).toList();
  }

  /** 최소 하나의 방이 활성이어야 한다. 도메인 검증이 저장 직전에 한 번 더 본다. */
  @AssertTrue(message = "roomOffers에는 활성 상태인 방이 1개 이상 있어야 합니다.")
  public boolean isAnyRoomActive() {
    return roomOffers != null
        && roomOffers.stream().anyMatch(o -> o.status() == Listing.RoomOfferStatus.ACTIVE);
  }
}
