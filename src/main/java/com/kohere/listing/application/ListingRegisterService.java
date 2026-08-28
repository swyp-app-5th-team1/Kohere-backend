package com.kohere.listing.application;

import com.kohere.listing.application.ListingImageConfirmer.ConfirmedListingImages;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.LandlordOnlyListingException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.image.ListingImageKeySet;
import com.kohere.listing.presentation.dto.ListingRegisterRequest;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 임대인이 올린 등록 요청을 검증해 매물 문서로 저장한다({@code POST /api/v2/listings}).
 *
 * <p>인가는 두 겹이다 — 보안 필터가 {@code hasRole("USER")}로 정식 회원만 통과시키고, 여기서 {@code userType}이 임대인인지 다시 확인한다.
 * 필터만으로는 세입자 토큰을 막을 수 없다.
 *
 * <p>등록 직후 상태는 {@code PENDING}이라 조회·검색·상세에 노출되지 않는다. 관리자 승인에서 {@code PUBLISHED}로 전이하며, 그 승인 심사가
 * 사업자등록번호 진위와 영어 번역을 함께 확인한다.
 *
 * <p>docs/api/specs/03-listings-favorites.md · 시퀀스 US-3-6.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ListingRegisterService {

  /** {@code user::api}가 문자열로 주는 임대인 구분값이다. */
  private static final String USER_TYPE_LANDLORD = "LANDLORD";

  private final ListingRepository listingRepository;
  private final ListingWriteAssembler listingWriteAssembler;
  private final ListingImageConfirmer listingImageConfirmer;
  private final ListingLocalizationService listingLocalizationService;
  private final UserAccountService userAccountService;

  /**
   * 등록 요청을 저장하고 생성된 매물을 상세 응답 구조로 돌려준다.
   *
   * <p>순서가 중요하다 — 입력 검증을 모두 끝낸 뒤에 사진을 확정 위치로 복사하고, 그다음 저장한다. 검증이 복사보다 앞서야 거절된 요청이 확정 위치에 흔적을 남기지
   * 않고, 저장이 실패하면 복사본을 지워 사진 없는 매물이 남지 않는다. 임시본은 어느 실패에서도 지우지 않아 사용자가 그대로 다시 제출할 수 있다(ADR-0041 §4).
   *
   * <p>응답 언어는 다른 조회와 같이 임대인 계정의 표시 언어를 따른다. 다만 등록 직후 문서는 두 언어에 같은 한국어가 들어 있어 어느 언어를 골라도 결과가 같다.
   */
  public ListingDetailResponse register(long landlordId, ListingRegisterRequest request) {
    requireLandlord(landlordId);
    // 사진 키 검사가 가장 앞이다 — 저장소를 부르기 전에 장수와 소유권이 끝나므로, 거절될 요청은 확정 위치에 흔적을 남기지 않는다.
    ListingImageKeySet keys =
        ListingImageKeySet.of(
            landlordId,
            request.imageKeys(),
            request.roomOffers().stream()
                .map(ListingRegisterRequest.RoomOfferRequest::roomImageKeys)
                .toList());
    ListingCatalogCodes catalog = listingWriteAssembler.catalog();
    // 요청을 통째로 매물로 조립해 본다 — 카탈로그 대조·범위 파싱·주소 판별이 모두 여기서 끝난다.
    // 식별자와 사진 URL만 아직 비어 있다.
    Listing draft = toListing(landlordId, request, catalog);

    String listingId = listingRepository.nextIdentity();
    List<String> roomOfferIds =
        request.roomOffers().stream().map(unused -> listingRepository.nextIdentity()).toList();
    ConfirmedListingImages confirmed = listingImageConfirmer.confirm(listingId, roomOfferIds, keys);

    Listing saved;
    try {
      saved = listingRepository.save(withStoredImages(draft, listingId, roomOfferIds, confirmed));
    } catch (RuntimeException e) {
      listingImageConfirmer.rollback(confirmed);
      throw e;
    }
    // 저장이 끝나야 임시본을 치운다. 실패해도 만료 규칙이 대신 치우므로 재시도하지 않는다.
    listingImageConfirmer.discardPending(keys);
    return ListingResponseMapper.toDetail(
        saved,
        false,
        listingLocalizationService.contextFor(userAccountService.getLanguage(landlordId)));
  }

  /**
   * 조립해 둔 매물에 발급한 식별자와 업로드 결과를 채운다.
   *
   * <p>확정 키가 식별자를 포함해서 저장보다 먼저 발급했고, URL은 복사가 끝나야 알 수 있다. 두 값만 마지막에 얹는다.
   */
  private static Listing withStoredImages(
      Listing draft,
      String listingId,
      List<String> roomOfferIds,
      ConfirmedListingImages confirmed) {
    List<Listing.RoomOffer> roomOffers = new ArrayList<>();
    for (int i = 0; i < roomOfferIds.size(); i++) {
      Listing.RoomOffer roomOffer = draft.getRoomOffers().get(i);
      roomOffers.add(
          new Listing.RoomOffer(
              roomOfferIds.get(i),
              roomOffer.name(),
              roomOffer.status(),
              roomOffer.contract(),
              roomOffer.pricing(),
              roomOffer.filterTags(),
              confirmed.roomUrls().get(i)));
    }
    return draft.toBuilder()
        .id(listingId)
        .imageUrls(confirmed.coverUrls())
        .roomOffers(List.copyOf(roomOffers))
        .build();
  }

  private void requireLandlord(long landlordId) {
    if (!USER_TYPE_LANDLORD.equals(userAccountService.getUserType(landlordId))) {
      throw new LandlordOnlyListingException();
    }
  }

  /**
   * 등록 요청을 매물로 조립한다.
   *
   * <p>요청이 정하는 값은 {@link ListingWriteAssembler}가 채운다 — 수정도 같은 조립을 쓰므로 두 경로가 갈라지지 않는다. 여기서는 <b>등록만
   * 정하는 값</b>을 얹는다: 스키마 버전·소유자·최초 상태·찜 수·생성 시각이다.
   */
  private Listing toListing(
      long landlordId, ListingRegisterRequest request, ListingCatalogCodes catalog) {
    Instant now = Instant.now();
    return listingWriteAssembler
        .apply(Listing.builder(), request, roomFilterTags(request), catalog)
        .schemaVersion(4)
        .landlordId(landlordId)
        .status(Listing.ListingStatus.PENDING)
        .favoriteCount(0)
        .imageUrls(List.of())
        .createdAt(now)
        .updatedAt(now)
        .roomOffers(request.roomOffers().stream().map(ListingRegisterService::toRoomOffer).toList())
        .build();
  }

  /** 카탈로그 대조에 넘길 방별 조건 태그다. */
  private static List<Set<ConditionTag>> roomFilterTags(ListingRegisterRequest request) {
    return request.roomOffers().stream()
        .map(ListingRegisterRequest.RoomOfferRequest::filterTags)
        .toList();
  }

  private static Listing.RoomOffer toRoomOffer(ListingRegisterRequest.RoomOfferRequest request) {
    return new Listing.RoomOffer(
        null,
        ListingWriteAssembler.bilingual(request.name()),
        Listing.RoomOfferStatus.ACTIVE,
        new Listing.Contract(
            request.contract().minStayMonths(), request.contract().maxStayMonths()),
        new Listing.Pricing(
            request.pricing().monthlyRent(),
            request.pricing().deposit(),
            request.pricing().maintenanceFee(),
            Listing.Currency.KRW),
        request.filterTags(),
        List.of());
  }
}
