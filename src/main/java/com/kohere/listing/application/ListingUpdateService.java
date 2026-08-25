package com.kohere.listing.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.application.ListingImageConfirmer.ConfirmedListingImages;
import com.kohere.listing.application.dto.LandlordListingDetailResponse;
import com.kohere.listing.domain.LandlordOnlyListingException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingStateChangedException;
import com.kohere.listing.domain.image.ListingImageEditKeys;
import com.kohere.listing.domain.image.ListingImageStorage;
import com.kohere.listing.presentation.dto.ListingUpdateRequest;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 임대인이 자기 매물을 고쳐 다시 심사에 올린다(US-3-9).
 *
 * <p><b>전체 교체다.</b> 등록 때 보낸 속성을 그대로 다시 받아 본문을 덮어쓴다 — 주소에서 좌표·행정구역·주변 대학이 파생되므로 일부만 받으면 파생값을 어느 기준으로
 * 다시 만들지 알 수 없고, 보내지 않은 필드가 「지워라」인지 「그대로 둬라」인지도 구분되지 않는다.
 *
 * <p><b>수정본을 따로 보관하지 않는다.</b> 문서를 제자리에서 덮어쓰고 상태만 전이시킨다. 그러면 세입자 조회 경로들이 {@code PUBLISHED}만 통과시키므로
 * 심사 중인 매물이 <b>아무것도 하지 않아도</b> 노출에서 빠진다 — 심사를 거치지 않은 내용이 세입자에게 도달하지 않게 하는 것이 목적이므로 이는 결함이 아니라 의도다.
 * 대가는 <b>수정이 반려되면 직전 공개본이 서버에 남지 않는다</b>는 것이다.
 *
 * <p>순서가 계약이다 — 거절될 요청이 확정 위치에 흔적을 남기지 않도록 검증을 모두 끝낸 뒤 사진을 복사하고, 저장이 성공한 뒤에야 임시본과 <b>참조를 잃은
 * 확정본</b>을 치운다. 저장 전에 옛 사진을 지우면 저장이 실패했을 때 공개 중인 매물의 사진이 사라진다.
 *
 * <p>docs/api/specs/03-listings-favorites.md 「임대인 매물 관리」 · 시퀀스 US-3-9.
 */
@Service
@RequiredArgsConstructor
public class ListingUpdateService {

  private static final Logger log = LoggerFactory.getLogger(ListingUpdateService.class);

  /** {@code user::api}가 문자열로 주는 임대인 구분값이다. */
  private static final String USER_TYPE_LANDLORD = "LANDLORD";

  private final ListingRepository listingRepository;
  private final ListingWriteAssembler listingWriteAssembler;
  private final ListingImageConfirmer listingImageConfirmer;
  private final ListingImageStorage listingImageStorage;
  private final LandlordListingService landlordListingService;
  private final UserAccountService userAccountService;

  /**
   * 수정 요청을 저장하고 갱신된 매물을 임대인 상세로 돌려준다.
   *
   * <p>저장은 <b>읽은 시점의 상태를 조건으로 건 교체</b>다. 읽기와 저장 사이에 사진 확정 복사(네트워크)가 끼어 창이 수백 ms에 이르는데, 그 사이 관리자가
   * 심사를 끝내면 조건 없는 저장은 그 결정을 소리 없이 덮는다.
   */
  public LandlordListingDetailResponse update(
      long landlordId, String listingId, ListingUpdateRequest request) {
    requireLandlord(landlordId);
    Listing current = landlordListingService.findOwnListing(landlordId, listingId);
    // 사진을 복사하기 전에 상태 게이트를 통과시킨다 — 거절될 요청이 확정 위치에 흔적을 남기지 않는다.
    current.requireEditable();

    List<String> roomOfferIds = assignRoomOfferIds(current, request);
    ListingImageEditKeys keys = editKeys(landlordId, current, request, roomOfferIds);
    Map<String, String> keptUrlByKey = keptUrlByKey(current);

    ConfirmedListingImages confirmed =
        listingImageConfirmer.confirmEdit(listingId, roomOfferIds, keys, keptUrlByKey);

    Listing edited =
        current.afterEdit(
            listingWriteAssembler
                .apply(
                    current.toBuilder(),
                    request,
                    request.roomFilterTags(),
                    listingWriteAssembler.catalog())
                .imageUrls(confirmed.coverUrls())
                .roomOffers(mergeRoomOffers(current, request, roomOfferIds, confirmed)),
            Instant.now());

    Listing saved;
    try {
      saved =
          listingRepository
              .saveIfStatus(edited, current.getStatus())
              .orElseThrow(ListingStateChangedException::new);
    } catch (RuntimeException e) {
      // 이번에 복사한 것만 되돌린다. 옛 확정본은 아직 문서가 참조하고 있으므로 건드리지 않는다.
      listingImageConfirmer.rollback(confirmed);
      throw e;
    }

    listingImageConfirmer.discardPendingKeys(keys.pendingKeys());
    listingImageConfirmer.discardReplaced(dereferencedKeys(current, saved));
    log.info(
        "[LANDLORD] 매물 수정 (landlordId={}, listingId={}, {} → {})",
        landlordId,
        listingId,
        current.getStatus(),
        saved.getStatus());
    return landlordListingService.toDetail(saved, landlordId);
  }

  private void requireLandlord(long landlordId) {
    if (!USER_TYPE_LANDLORD.equals(userAccountService.getUserType(landlordId))) {
      throw new LandlordOnlyListingException();
    }
  }

  /**
   * 요청 순서대로 방 식별자를 정한다. {@code null}이면 새로 발급하고, 값이 있으면 문서에 실재하는지 본다.
   *
   * <p>문서에 없는 식별자를 보내면 {@code 400}이다 — 남의 매물 방을 끌어오거나 오타로 새 방을 만드는 것을 막는다.
   */
  private List<String> assignRoomOfferIds(Listing current, ListingUpdateRequest request) {
    Set<String> known = roomOfferIdsOf(current);
    List<String> ids = new ArrayList<>();
    for (ListingUpdateRequest.RoomOfferUpdateRequest room : request.roomOffers()) {
      if (room.isNew()) {
        ids.add(listingRepository.nextIdentity());
        continue;
      }
      if (!known.contains(room.roomOfferId())) {
        throw new InvalidInputException("roomOffers.roomOfferId", "validation.required");
      }
      ids.add(room.roomOfferId());
    }
    return List.copyOf(ids);
  }

  /**
   * 요청의 사진 키를 자리별 허용 집합과 대조한다.
   *
   * <p>허용 집합은 <b>현재 문서에서 파생</b>한다 — 커버는 문서의 대표사진에서, 방은 <b>그 방의</b> 사진에서만 온다. 접두만 보면 남의 매물 사진이나 다른
   * 방의 사진이 통과하므로, 이 멤버십 대조가 곧 소유권 검사다. 신규 방은 허용 집합이 비어 임시 키만 통과한다.
   */
  private ListingImageEditKeys editKeys(
      long landlordId, Listing current, ListingUpdateRequest request, List<String> roomOfferIds) {
    Map<String, Listing.RoomOffer> byId = roomOffersById(current);
    List<Set<String>> allowedRoomKeys =
        request.roomOffers().stream()
            .map(
                room ->
                    room.isNew()
                        ? Set.<String>of()
                        : keySetOf(byId.get(room.roomOfferId()).roomImageUrls()))
            .toList();
    return ListingImageEditKeys.of(
        landlordId,
        request.imageKeys(),
        keySetOf(current.getImageUrls()),
        request.roomImageKeys(),
        allowedRoomKeys);
  }

  /**
   * 요청 순서대로 방을 조립하고, 요청에서 <b>식별자가 통째로 빠진</b> 방을 비활성으로 보존해 뒤에 붙인다.
   *
   * <p>방을 내리는 정상 경로는 {@code status=INACTIVE}로 보내는 것이다. 여기서 보존하는 것은 클라이언트 결함으로 식별자가 누락된 경우인데, 그때도
   * 지우지 않는 이유는 <b>예약과 채팅이 그 식별자를 참조</b>하고 있어 지우면 이미 잡힌 예약의 카드가 되살아나지 못하기 때문이다.
   */
  private List<Listing.RoomOffer> mergeRoomOffers(
      Listing current,
      ListingUpdateRequest request,
      List<String> roomOfferIds,
      ConfirmedListingImages confirmed) {
    List<Listing.RoomOffer> merged = new ArrayList<>();
    for (int i = 0; i < request.roomOffers().size(); i++) {
      ListingUpdateRequest.RoomOfferUpdateRequest room = request.roomOffers().get(i);
      merged.add(
          new Listing.RoomOffer(
              roomOfferIds.get(i),
              ListingWriteAssembler.bilingual(room.name()),
              room.status(),
              new Listing.Contract(
                  room.contract().minStayMonths(), room.contract().maxStayMonths()),
              new Listing.Pricing(
                  room.pricing().monthlyRent(),
                  room.pricing().deposit(),
                  room.pricing().maintenanceFee(),
                  Listing.Currency.KRW),
              room.filterTags(),
              confirmed.roomUrls().get(i)));
    }
    Set<String> submitted = Set.copyOf(roomOfferIds);
    current.getRoomOffers().stream()
        .filter(offer -> !submitted.contains(offer.roomOfferId()))
        .map(
            offer ->
                new Listing.RoomOffer(
                    offer.roomOfferId(),
                    offer.name(),
                    Listing.RoomOfferStatus.INACTIVE,
                    offer.contract(),
                    offer.pricing(),
                    offer.filterTags(),
                    offer.roomImageUrls()))
        .forEach(merged::add);
    return List.copyOf(merged);
  }

  /** 저장 성공 뒤 참조를 잃은 확정 키다 — 옛 문서에는 있고 새 문서에는 없는 것. */
  private Set<String> dereferencedKeys(Listing before, Listing after) {
    Set<String> old = new LinkedHashSet<>(confirmedKeysOf(before));
    old.removeAll(confirmedKeysOf(after));
    return old;
  }

  /** 문서가 참조하는 확정 키 전부다. 커버와 <b>모든 방</b>(비활성 포함)을 함께 훑는다. */
  private Set<String> confirmedKeysOf(Listing listing) {
    Set<String> keys = new LinkedHashSet<>(keySetOf(listing.getImageUrls()));
    listing.getRoomOffers().forEach(offer -> keys.addAll(keySetOf(offer.roomImageUrls())));
    return keys;
  }

  /** 그대로 두는 확정 키의 읽기 URL이다. 복사 없이 문서의 값을 그대로 옮긴다. */
  private Map<String, String> keptUrlByKey(Listing listing) {
    Map<String, String> byKey = new LinkedHashMap<>();
    urls(listing)
        .forEach(url -> listingImageStorage.keyOf(url).ifPresent(key -> byKey.put(key, url)));
    return byKey;
  }

  private static List<String> urls(Listing listing) {
    List<String> all = new ArrayList<>(listing.getImageUrls());
    listing.getRoomOffers().forEach(offer -> all.addAll(offer.roomImageUrls()));
    return all;
  }

  private Set<String> keySetOf(List<String> urls) {
    Set<String> keys = new LinkedHashSet<>();
    urls.forEach(url -> listingImageStorage.keyOf(url).ifPresent(keys::add));
    return keys;
  }

  private static Set<String> roomOfferIdsOf(Listing listing) {
    return listing.getRoomOffers().stream()
        .map(Listing.RoomOffer::roomOfferId)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private static Map<String, Listing.RoomOffer> roomOffersById(Listing listing) {
    Map<String, Listing.RoomOffer> byId = new LinkedHashMap<>();
    listing.getRoomOffers().forEach(offer -> byId.put(offer.roomOfferId(), offer));
    return byId;
  }
}
