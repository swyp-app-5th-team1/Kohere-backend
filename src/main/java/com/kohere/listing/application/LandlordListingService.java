package com.kohere.listing.application;

import com.kohere.common.response.PageResponse;
import com.kohere.listing.application.dto.LandlordListingDetailResponse;
import com.kohere.listing.application.dto.LandlordListingSummaryResponse;
import com.kohere.listing.domain.LandlordOnlyListingException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.image.ListingImageStorage;
import com.kohere.user.api.UserAccountService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임대인이 자기 매물을 조회하는 응용 서비스다(US-3-8).
 *
 * <p><b>인가는 세 겹이다.</b> 보안 필터가 {@code /api/v2/users/me/listings}를 {@code hasRole("USER")}로 걸러 온보딩
 * 토큰을 막고, 여기서 {@code userType=LANDLORD}를 다시 확인하며, 마지막으로 매물마다 <b>소유권</b>을 본다. 앞의 둘은 "임대인인가"를, 마지막은
 * "그 임대인의 것인가"를 묻는 서로 다른 질문이다.
 *
 * <p>남의 매물이면 {@code 403}이 아니라 <b>{@code 404}</b>다. 있는 매물과 없는 매물을 다른 코드로 알려주면 그 차이가 곧 존재 여부를 알려주는
 * 통로가 되고, 세입자 상세({@code requirePublishedListing})도 같은 이유로 404로 통일돼 있다.
 *
 * <p>이 경로를 {@code /api/v2/listings/mine}에 두지 않은 이유는 {@code GET /api/v2/listings/*}가 {@code
 * permitAll}이라 <b>비로그인에 열려 버리기 때문</b>이다. 경로 하나로 인가가 갈리는 구조라 매처가 아니라 네임스페이스로 가른다.
 *
 * <p>docs/api/specs/03-listings-favorites.md 「임대인 매물 관리」 · 시퀀스 US-3-8.
 */
@Service
@RequiredArgsConstructor
public class LandlordListingService {

  /** {@code user::api}가 문자열로 주는 임대인 구분값이다. */
  private static final String USER_TYPE_LANDLORD = "LANDLORD";

  private final ListingRepository listingRepository;
  private final ListingLocalizationService listingLocalizationService;
  private final ListingImageStorage listingImageStorage;
  private final UserAccountService userAccountService;

  /**
   * 내 매물을 상태와 무관하게 조회한다. {@code statuses}가 비면 상태 조건을 걸지 않는다.
   *
   * <p>번역 컨텍스트를 <b>루프 밖에서 한 번</b> 만든다 — 매물마다 만들면 카탈로그 원장을 페이지 크기만큼 다시 읽는다.
   */
  @Transactional(readOnly = true)
  public PageResponse<LandlordListingSummaryResponse> list(
      long landlordId, Set<Listing.ListingStatus> statuses, int page, int size) {
    requireLandlord(landlordId);

    PageResponse<Listing> found =
        listingRepository.findByLandlord(landlordId, statuses, page, size);
    ListingLocalizationContext localization = localizationFor(landlordId);
    List<LandlordListingSummaryResponse> content =
        found.content().stream().map(listing -> toSummary(listing, localization)).toList();
    return new PageResponse<>(content, found.page());
  }

  /** 수정 화면이 폼을 채우는 데 쓰는 상세다. 상태와 무관하게 조회된다. */
  @Transactional(readOnly = true)
  public LandlordListingDetailResponse detail(long landlordId, String listingId) {
    requireLandlord(landlordId);
    return toDetail(findOwnListing(landlordId, listingId), localizationFor(landlordId));
  }

  /**
   * 임대인 여부를 DB로 확인한다. 모든 public 메서드의 첫 줄이다.
   *
   * <p>보안 매처가 이미 온보딩 완료까지는 걸렀지만 그것으로는 역할을 알 수 없다. 토큰에 역할을 담지 않으므로 권한을 회수하면 살아 있는 토큰으로도 통과하지 못한다 —
   * 등록 게이트({@code ListingRegisterService#requireLandlord})와 완전히 같은 모양이다.
   */
  private void requireLandlord(long landlordId) {
    if (!USER_TYPE_LANDLORD.equals(userAccountService.getUserType(landlordId))) {
      throw new LandlordOnlyListingException();
    }
  }

  /** 내 매물 한 건을 찾는다. 없거나 남의 것이면 구분하지 않고 {@code 404}다. */
  Listing findOwnListing(long landlordId, String listingId) {
    return listingRepository
        .findById(listingId)
        .filter(listing -> listing.isOwnedBy(landlordId))
        .orElseThrow(ListingNotFoundException::new);
  }

  private ListingLocalizationContext localizationFor(long landlordId) {
    return listingLocalizationService.contextFor(userAccountService.getLanguage(landlordId));
  }

  /**
   * 목록 카드로 변환한다.
   *
   * <p>세입자 카드 매퍼는 조건을 통과한 방이 최소 1개인 검색 결과를 받는다. 내 매물 목록은 검색이 아니라 소유 기준이라, 활성 방이 없는 문서(검증 이전에 저장된
   * 레거시)도 카드가 만들어지도록 전체 방으로 물러선다.
   */
  private LandlordListingSummaryResponse toSummary(
      Listing listing, ListingLocalizationContext localization) {
    List<Listing.RoomOffer> active =
        listing.getRoomOffers().stream()
            .filter(offer -> offer.status() == Listing.RoomOfferStatus.ACTIVE)
            .toList();
    List<Listing.RoomOffer> cardOffers = active.isEmpty() ? listing.getRoomOffers() : active;
    return new LandlordListingSummaryResponse(
        ListingResponseMapper.toSummary(
            new ListingSearchResult(listing, cardOffers), null, localization),
        listing.getRejectionReason());
  }

  /**
   * 수정 폼이 그대로 되돌려 보낼 수 있는 상세로 변환한다.
   *
   * <p>{@code rooms}는 <b>비활성 방까지</b> 담는다. 중첩된 세입자 상세는 활성 방만 보여주므로, 그것만으로는 내렸던 방을 되살릴 수 없다.
   *
   * <p>사진은 URL이 아니라 <b>키</b>도 함께 준다. URL에서 키를 역산하게 하면 클라이언트가 주소 형식에 묶여 CDN을 바꿀 때 같이 깨진다.
   */
  /** 저장 직후의 매물을 상세 응답으로 만든다. 수정 경로가 조회와 같은 모양을 돌려주도록 공유한다. */
  LandlordListingDetailResponse toDetail(Listing listing, long landlordId) {
    return toDetail(listing, localizationFor(landlordId));
  }

  private LandlordListingDetailResponse toDetail(
      Listing listing, ListingLocalizationContext localization) {
    return new LandlordListingDetailResponse(
        ListingResponseMapper.toDetail(listing, false, localization),
        listing.getStatus(),
        listing.getRejectionReason(),
        listing.getBusinessRegistrationNumber(),
        listing.getPreferredNationalities(),
        listing.getContractDifficulties(),
        listing.getServiceFeedback(),
        keysOf(listing.getImageUrls()),
        listing.getRoomOffers().stream().map(this::toRoom).toList());
  }

  private LandlordListingDetailResponse.LandlordRoomResponse toRoom(Listing.RoomOffer offer) {
    return new LandlordListingDetailResponse.LandlordRoomResponse(
        offer.roomOfferId(),
        offer.status(),
        offer.name() == null ? null : offer.name().ko(),
        offer.contract(),
        offer.pricing(),
        offer.filterTags(),
        offer.roomImageUrls(),
        keysOf(offer.roomImageUrls()));
  }

  /** 저장된 URL에서 저장 키를 되돌린다. 형식이 다른 값(레거시·외부 주소)은 조용히 빠진다. */
  private List<String> keysOf(List<String> urls) {
    return urls.stream()
        .map(listingImageStorage::keyOf)
        .flatMap(java.util.Optional::stream)
        .toList();
  }
}
