package com.kohere.listing.application;

import com.kohere.common.response.PageResponse;
import com.kohere.listing.application.dto.AdminListingDetailResponse;
import com.kohere.listing.domain.AdminOnlyListingException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.ListingStateChangedException;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 매물 심사 응용 서비스다(US-3-7).
 *
 * <p><b>인가는 두 겹이다.</b> 보안 필터가 {@code /api/v1/admin/**}를 {@code hasRole("USER")}로 걸러 온보딩 토큰을 막고, 여기서
 * {@code userType=ADMIN}을 다시 확인한다. 매처는 경로 단위로만 판단할 수 있어 {@code userType}을 볼 수 없고, 토큰에 관리자 여부를 실으면
 * 권한을 회수해도 토큰 수명만큼 관리자로 남는다. 그래서 <b>판정은 매 요청 DB 조회</b>다 — 임대인 게이트({@code
 * ListingRegisterService#requireLandlord})가 이미 쓰는 방식이다.
 *
 * <p><b>승인·반려 모두 상태를 가리지 않는다.</b> 심사 대기 매물의 1차 처리뿐 아니라 잘못 반려한 매물을 되살리는 재승인({@code REJECTED →
 * PUBLISHED}), 공개 매물을 내리는 사후 반려({@code PUBLISHED → REJECTED}), 이미 반려한 매물의 사유 정정이 모두 정상 경로다 — 관리자의
 * 오판을 되돌릴 수단이 서버에 있어야 하기 때문이다. 이미 공개 중인 매물의 재승인만 아무 일도 하지 않는다({@code Listing#approve}).
 *
 * <p><b>임대인 이름은 매물에 없다.</b> 심사 응답의 {@code landlordName}은 {@code users.name}이 정본이라 매물 문서에 스냅샷하지 않고
 * {@code user :: api}로 <b>조회 시점에 조합</b>한다(ADR-0002 Decision 5 — 즉시 결과가 필요한 조회는 동기 공개 API, ADR-0005
 * — cross-store 조인 금지). 임대인이 프로필에서 이름을 고치면 심사 화면도 그대로 따라와야 하고, 등록 당시의 이름을 보존할 요구가 없기 때문이다. 부재는 에러가
 * 아니라 키 생략으로 다룬다({@link #landlordNameOf}).
 *
 * <p>docs/api/specs/03-listings-favorites.md 「관리자 매물 심사」 · 시퀀스 US-3-7.
 */
@Service
@RequiredArgsConstructor
public class AdminListingService {

  private static final Logger log = LoggerFactory.getLogger(AdminListingService.class);

  /** 관리자만 통과시킨다. {@code user::api}가 문자열을 주므로 enum을 모듈 밖으로 흘리지 않는다. */
  private static final String USER_TYPE_ADMIN = "ADMIN";

  /** 관리자 화면은 임대인 화면과 같이 한국어 고정이다. 세입자 언어 조회를 타지 않는다. */
  private static final String ADMIN_LANGUAGE = "ko";

  private final ListingRepository listingRepository;
  private final ListingLocalizationService listingLocalizationService;
  private final UserAccountService userAccountService;
  private final LandlordNameLookup landlordNameLookup;

  /**
   * 모든 상태의 매물을 조회한다. {@code statuses}가 비면 상태 조건을 걸지 않는다.
   *
   * <p>페이지 단위로 두 가지를 <b>루프 밖에서 한 번만</b> 마련한다. 번역 컨텍스트는 관리자 화면이 한국어 고정이라 매물마다 같은 사전이 나오는데, 매물마다 만들면
   * 카탈로그 원장을 페이지 크기만큼 다시 읽는다(임대인 목록이 이미 같은 이유로 밖에서 만든다). 임대인 이름은 매물마다 다를 수 있어 사전 대신 <b>기억해 두는
   * 맵</b>을 쓴다 — 한 임대인이 지점마다 매물을 올리는 것이 정상이라 페이지 안 중복이 흔하다.
   */
  @Transactional(readOnly = true)
  public PageResponse<AdminListingDetailResponse> list(
      long adminId, Set<Listing.ListingStatus> statuses, int page, int size, String sort) {
    requireAdmin(adminId);

    PageResponse<Listing> found = listingRepository.findForAdmin(statuses, page, size, sort);
    ListingLocalizationContext localization = listingLocalizationService.contextFor(ADMIN_LANGUAGE);
    Map<Long, String> landlordNames = new HashMap<>();
    List<AdminListingDetailResponse> content =
        found.content().stream()
            .map(
                listing ->
                    toResponse(listing, localization, rememberedName(listing, landlordNames)))
            .toList();
    return new PageResponse<>(content, found.page());
  }

  /** 심사 상세. 상태와 무관하게 조회된다. */
  @Transactional(readOnly = true)
  public AdminListingDetailResponse detail(long adminId, String listingId) {
    requireAdmin(adminId);
    return toResponse(findListing(listingId));
  }

  /** 심사를 통과시켜 공개한다. 승인 직후부터 세입자 조회에 나타난다. */
  @Transactional
  public AdminListingDetailResponse approve(long adminId, String listingId) {
    requireAdmin(adminId);

    Listing found = findListing(listingId);
    Listing approved = saveTransition(found, found.approve(Instant.now()));
    log.info("[ADMIN] 매물 승인 (adminId={}, listingId={})", adminId, listingId);
    return toResponse(approved);
  }

  /**
   * 사유와 함께 반려한다. 사유는 임대인만 읽는 값이라 번역하지 않는다.
   *
   * <p>상태를 가리지 않는다 — 공개 매물을 내리는 사후 반려와 이미 반려한 매물의 사유 정정도 이 경로다.
   */
  @Transactional
  public AdminListingDetailResponse reject(long adminId, String listingId, String reason) {
    requireAdmin(adminId);

    Listing found = findListing(listingId);
    Listing rejected = saveTransition(found, found.reject(reason, Instant.now()));
    log.info("[ADMIN] 매물 반려 (adminId={}, listingId={})", adminId, listingId);
    return toResponse(rejected);
  }

  /**
   * 심사 결정을 <b>읽은 시점의 상태를 조건으로</b> 저장한다.
   *
   * <p>임대인 수정이 같은 문서를 통째로 교체하므로, 조건 없이 저장하면 관리자의 결정과 임대인의 수정이 서로를 소리 없이 덮는다. 한쪽만 조건을 걸면 다른 쪽이 계속
   * 덮으므로 양쪽에 건다.
   *
   * <p>이미 공개 중인 매물의 재승인은 {@link Listing#approve}가 자기 자신을 돌려주므로 <b>저장 자체를 하지 않는다</b> — 조건 비교로 이어지지
   * 않게 여기서 먼저 걸러 낸다. 그래야 "바뀐 것이 없음"과 "누가 끼어들었음"이 섞이지 않는다.
   */
  private Listing saveTransition(Listing before, Listing after) {
    if (after == before) {
      return before;
    }
    return listingRepository
        .saveIfStatus(after, before.getStatus())
        .orElseThrow(ListingStateChangedException::new);
  }

  /**
   * 관리자 여부를 DB로 확인한다. 모든 public 메서드의 첫 줄이다.
   *
   * <p>보안 매처가 이미 온보딩 완료까지는 걸렀지만 그것으로는 역할을 알 수 없다. 여기서 확인하므로 권한을 회수하면 살아 있는 토큰으로도 심사할 수 없다.
   */
  private void requireAdmin(long adminId) {
    if (!USER_TYPE_ADMIN.equals(userAccountService.getUserType(adminId))) {
      throw new AdminOnlyListingException();
    }
  }

  private Listing findListing(String listingId) {
    return listingRepository.findById(listingId).orElseThrow(ListingNotFoundException::new);
  }

  /**
   * 단건 심사 응답으로 변환한다(상세·승인·반려). 번역 컨텍스트도 임대인 이름도 한 번씩만 필요해 여기서 바로 마련한다.
   *
   * <p>목록은 이 메서드를 쓰지 않는다 — 둘 다 페이지 단위로 아껴야 하므로 {@link #list}가 밖에서 만들어 넘긴다.
   */
  private AdminListingDetailResponse toResponse(Listing listing) {
    return toResponse(
        listing,
        listingLocalizationService.contextFor(ADMIN_LANGUAGE),
        landlordNameOf(listing.getLandlordId()));
  }

  /**
   * 심사 응답으로 변환한다.
   *
   * <p>세입자 응답을 만드는 {@link ListingResponseMapper#toDetail}을 그대로 쓰고 그것이 감추는 값을 더한다. {@code
   * favorited}는 관리자에게 의미가 없어 {@code false} 고정이다.
   */
  private AdminListingDetailResponse toResponse(
      Listing listing, ListingLocalizationContext localization, String landlordName) {
    return AdminListingDetailResponse.of(
        listing, ListingResponseMapper.toDetail(listing, false, localization), landlordName);
  }

  /**
   * 목록에서 임대인 이름을 <b>페이지 안 한 번만</b> 조회하도록 기억해 둔다.
   *
   * <p>{@code computeIfAbsent}를 쓰지 않는 것은 값이 {@code null}일 수 있어서다 — 매핑 함수가 {@code null}을 돌려주면 그 매핑은
   * 저장되지 않아, 정작 이름을 알 수 없는 임대인만 매물 수만큼 반복 조회된다. "물어봤지만 없더라"도 기억해야 한다.
   */
  private String rememberedName(Listing listing, Map<Long, String> landlordNames) {
    Long landlordId = listing.getLandlordId();
    if (!landlordNames.containsKey(landlordId)) {
      landlordNames.put(landlordId, landlordNameOf(landlordId));
    }
    return landlordNames.get(landlordId);
  }

  /**
   * 임대인 계정 이름을 조회한다. <b>알 수 없으면 {@code null}</b>이고 응답에서는 그 키가 빠진다.
   *
   * <p>이름을 알 수 없다고 심사가 멈춰서는 안 된다 — 매물 한 건의 임대인 때문에 <b>심사 목록 전체가 죽으면</b> 관리자가 아무것도 처리할 수 없다. 심사 대상은
   * 매물이고 이름은 표시 보조값이다. 같은 쿼리를 쓰는 {@code chat}(상대 이름)·{@code booking}(예약자 성명)이 부재를 그대로 흘리는 것과 갈리는
   * 지점이다 — 거기서는 상대가 없으면 그 대화·예약 자체가 성립하지 않아 실패가 곧 정답이지만, 심사는 임대인 계정이 사라져도 그 매물을 내릴지 판단해야 한다.
   *
   * <p>부재를 삼키는 일이 <b>별도 빈</b>({@link LandlordNameLookup})에 있는 이유는 그 일이 <b>이 메서드의 트랜잭션 밖에서</b> 일어나야
   * 하기 때문이다. 여기서 잡으면 잡히기도 전에 트랜잭션이 rollback-only로 표시돼 커밋이 터진다 — 자세한 사정은 그 클래스에 적어 두었다.
   */
  private String landlordNameOf(Long landlordId) {
    return landlordNameLookup.nameOf(landlordId);
  }
}
