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
import java.util.List;
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

  /** 모든 상태의 매물을 조회한다. {@code statuses}가 비면 상태 조건을 걸지 않는다. */
  @Transactional(readOnly = true)
  public PageResponse<AdminListingDetailResponse> list(
      long adminId, Set<Listing.ListingStatus> statuses, int page, int size, String sort) {
    requireAdmin(adminId);

    PageResponse<Listing> found = listingRepository.findForAdmin(statuses, page, size, sort);
    List<AdminListingDetailResponse> content =
        found.content().stream().map(this::toResponse).toList();
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
   * 관리자 여부를 DB로 확인한다. 모든 public 메서드의 첫 줄이다.
   *
   * <p>보안 매처가 이미 온보딩 완료까지는 걸렀지만 그것으로는 역할을 알 수 없다. 여기서 확인하므로 권한을 회수하면 살아 있는 토큰으로도 심사할 수 없다.
   */
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

  private void requireAdmin(long adminId) {
    if (!USER_TYPE_ADMIN.equals(userAccountService.getUserType(adminId))) {
      throw new AdminOnlyListingException();
    }
  }

  private Listing findListing(String listingId) {
    return listingRepository.findById(listingId).orElseThrow(ListingNotFoundException::new);
  }

  /**
   * 심사 응답으로 변환한다.
   *
   * <p>세입자 응답을 만드는 {@link ListingResponseMapper#toDetail}을 그대로 쓰고 그것이 감추는 값을 더한다. {@code
   * favorited}는 관리자에게 의미가 없어 {@code false} 고정이다.
   */
  private AdminListingDetailResponse toResponse(Listing listing) {
    ListingLocalizationContext localization = listingLocalizationService.contextFor(ADMIN_LANGUAGE);
    return AdminListingDetailResponse.of(
        listing, ListingResponseMapper.toDetail(listing, false, localization));
  }
}
