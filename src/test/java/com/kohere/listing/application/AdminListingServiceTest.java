package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.domain.AdminOnlyListingException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.catalog.ListingCatalogRepository;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.domain.UserNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 관리자 매물 심사 서비스의 <b>인가·상태 전이·임대인 이름 조합</b>을 검증한다(US-3-7).
 *
 * <p>응답 조립(라벨·번역)은 세입자 상세와 같은 매퍼를 그대로 쓰므로 여기서 다시 확인하지 않는다. 이 테스트가 지키는 것은 셋이다 — 관리자가 아니면 아무것도 못 한다는
 * 것, <b>승인·반려가 상태를 가리지 않는다</b>는 것(재승인·사후 반려), 그리고 임대인 이름은 <b>표시 보조값</b>이라 알 수 없어도 심사가 멈추지 않는다는 것.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminListingServiceTest {

  private static final long ADMIN_ID = 1L;
  private static final long LANDLORD_ID = 2L;
  private static final String LISTING_ID = "68e0000000000000000000a1";

  @Mock private ListingRepository listingRepository;
  @Mock private ListingCatalogRepository catalogRepository;
  @Mock private UserAccountService userAccountService;

  private AdminListingService service;

  @BeforeEach
  void setUp() {
    // LandlordNameLookup은 목이 아니라 실물을 쓴다 — 빈 문자열·부재를 null로 접는 규칙이 이 테스트의
    // 검증 대상이기 때문이다. 다만 직접 생성이라 @Transactional은 걸리지 않으므로, 그것이 지키는
    // 성질(호출자 트랜잭션을 망가뜨리지 않는다)은 LandlordNameLookupIntegrationTest가 따로 본다.
    service =
        new AdminListingService(
            listingRepository,
            new ListingLocalizationService(catalogRepository),
            userAccountService,
            new LandlordNameLookup(userAccountService));
    given(catalogRepository.findAll()).willReturn(List.of());
    // 심사도 「읽은 시점의 상태」를 조건으로 저장한다 — 임대인 수정이 같은 문서를 통째로
    // 교체하므로 조건 없이 쓰면 서로를 소리 없이 덮는다.
    given(listingRepository.saveIfStatus(any(), any()))
        .willAnswer(invocation -> Optional.of(invocation.getArgument(0)));
  }

  @Test
  @DisplayName("관리자가 아니면 심사 API를 쓸 수 없다")
  void rejectsNonAdmin() {
    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");

    assertThatThrownBy(() -> service.list(LANDLORD_ID, Set.of(), 0, 20, null))
        .isInstanceOf(AdminOnlyListingException.class);
    assertThatThrownBy(() -> service.detail(LANDLORD_ID, LISTING_ID))
        .isInstanceOf(AdminOnlyListingException.class);
    assertThatThrownBy(() -> service.approve(LANDLORD_ID, LISTING_ID))
        .isInstanceOf(AdminOnlyListingException.class);
    assertThatThrownBy(() -> service.reject(LANDLORD_ID, LISTING_ID, "사유"))
        .isInstanceOf(AdminOnlyListingException.class);

    // 인가에서 끊기므로 저장소를 건드리지 않는다.
    verify(listingRepository, never()).findById(anyString());
    verify(listingRepository, never()).findForAdmin(anySet(), anyInt(), anyInt(), any());
  }

  @Test
  @DisplayName("승인하면 공개 상태로 저장한다")
  void approvePublishesListing() {
    givenAdmin();
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(pending()));

    assertThat(service.approve(ADMIN_ID, LISTING_ID).listing().status())
        .isEqualTo(Listing.ListingStatus.PUBLISHED);
  }

  @Test
  @DisplayName("반려하면 사유와 함께 저장한다")
  void rejectStoresReason() {
    givenAdmin();
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(pending()));

    var response = service.reject(ADMIN_ID, LISTING_ID, "주소가 일치하지 않습니다");

    assertThat(response.listing().status()).isEqualTo(Listing.ListingStatus.REJECTED);
    assertThat(response.rejectionReason()).isEqualTo("주소가 일치하지 않습니다");
  }

  @Test
  @DisplayName("반려는 이미 공개된 매물에도 할 수 있다")
  void rejectAllowedOnPublishedListing() {
    // 사후 반려 — 문제가 발견된 공개 매물을 내리는 유일한 수단이다.
    givenAdmin();
    Listing published = pending().toBuilder().status(Listing.ListingStatus.PUBLISHED).build();
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(published));

    var response = service.reject(ADMIN_ID, LISTING_ID, "허위 매물로 확인됨");

    assertThat(response.listing().status()).isEqualTo(Listing.ListingStatus.REJECTED);
    assertThat(response.rejectionReason()).isEqualTo("허위 매물로 확인됨");
  }

  @Test
  @DisplayName("잘못 반려한 매물을 되살릴 수 있다")
  void approveRevivesRejectedListing() {
    givenAdmin();
    Listing rejected =
        pending().toBuilder()
            .status(Listing.ListingStatus.REJECTED)
            .rejectionReason("오판이었던 사유")
            .build();
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(rejected));

    var response = service.approve(ADMIN_ID, LISTING_ID);

    assertThat(response.listing().status()).isEqualTo(Listing.ListingStatus.PUBLISHED);
    assertThat(response.rejectionReason()).isNull();
  }

  @Test
  @DisplayName("없는 매물은 404로 끊는다")
  void rejectsMissingListing() {
    givenAdmin();
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.detail(ADMIN_ID, LISTING_ID))
        .isInstanceOf(ListingNotFoundException.class);
  }

  @Test
  @DisplayName("응답에 등록 임대인의 계정 이름을 싣는다")
  void includesLandlordName() {
    givenAdmin();
    given(userAccountService.getUserName(ListingReviewFixtures.LANDLORD_ID)).willReturn("김임대");
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(pending()));

    var response = service.detail(ADMIN_ID, LISTING_ID);

    assertThat(response.landlordName()).isEqualTo("김임대");
    // 등록 폼의 지점 담당자와는 다른 값이다 — 같은 응답에 나란히 실린다.
    assertThat(response.listing().contact().managerName()).isEqualTo("김담당");
  }

  @Test
  @DisplayName("이름이 비어 있으면 null로 접는다")
  void foldsBlankNameToNull() {
    // getUserName은 이름 미설정을 빈 문자열로 준다(소셜 provider 미제공·탈퇴 익명화).
    // 그대로 실으면 "이름이 없다"와 "이름이 빈칸이다"가 클라이언트에서 갈리지 않는다.
    givenAdmin();
    given(userAccountService.getUserName(ListingReviewFixtures.LANDLORD_ID)).willReturn("");
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(pending()));

    assertThat(service.detail(ADMIN_ID, LISTING_ID).landlordName()).isNull();
  }

  @Test
  @DisplayName("임대인 계정을 찾지 못해도 심사는 멈추지 않는다")
  void survivesMissingLandlordAccount() {
    // 이 테스트가 D4의 회귀 방어선이다. 예외를 그대로 흘리면 매물 한 건의 임대인 때문에
    // 심사 목록 전체가 404 USER_NOT_FOUND가 되는데, 그 코드는 관리자 화면이 해석할 수 없다.
    givenAdmin();
    given(userAccountService.getUserName(ListingReviewFixtures.LANDLORD_ID))
        .willThrow(new UserNotFoundException());
    given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(pending()));

    var response = service.detail(ADMIN_ID, LISTING_ID);

    assertThat(response.landlordName()).isNull();
    // 이름만 비고 나머지는 그대로다 — 심사에 필요한 값은 매물 문서에서 온다.
    assertThat(response.businessRegistrationNumber()).isNotBlank();
  }

  @Test
  @DisplayName("목록은 임대인별로 한 번만 이름을 묻는다")
  void asksEachLandlordOnce() {
    // 한 임대인이 지점마다 매물을 올리는 것이 정상이라 페이지 안 중복이 흔하다.
    givenAdmin();
    long otherLandlordId = ListingReviewFixtures.LANDLORD_ID + 1;
    Listing mine = pending();
    Listing others = pending().toBuilder().landlordId(otherLandlordId).build();
    given(listingRepository.findForAdmin(anySet(), anyInt(), anyInt(), any()))
        .willReturn(
            new PageResponse<>(
                List.of(mine, mine, others, mine), new PageInfo(0, 20, 4, 1, false)));
    given(userAccountService.getUserName(ListingReviewFixtures.LANDLORD_ID)).willReturn("김임대");
    given(userAccountService.getUserName(otherLandlordId)).willReturn("이임대");

    var page = service.list(ADMIN_ID, Set.of(), 0, 20, null);

    assertThat(page.content())
        .extracting("landlordName")
        .containsExactly("김임대", "김임대", "이임대", "김임대");
    // 매물 4건이지만 임대인은 둘뿐이라 조회도 두 번이어야 한다.
    verify(userAccountService, times(1)).getUserName(ListingReviewFixtures.LANDLORD_ID);
    verify(userAccountService, times(1)).getUserName(otherLandlordId);
  }

  @Test
  @DisplayName("이름을 알 수 없는 임대인도 페이지 안에서 한 번만 묻는다")
  void remembersUnknownLandlordToo() {
    // computeIfAbsent였다면 null을 돌려준 매핑이 저장되지 않아, 정작 이름을 알 수 없는
    // 임대인만 매물 수만큼 반복 조회된다.
    givenAdmin();
    Listing listing = pending();
    given(listingRepository.findForAdmin(anySet(), anyInt(), anyInt(), any()))
        .willReturn(
            new PageResponse<>(
                List.of(listing, listing, listing), new PageInfo(0, 20, 3, 1, false)));
    given(userAccountService.getUserName(ListingReviewFixtures.LANDLORD_ID))
        .willThrow(new UserNotFoundException());

    var page = service.list(ADMIN_ID, Set.of(), 0, 20, null);

    assertThat(page.content()).allSatisfy(item -> assertThat(item.landlordName()).isNull());
    verify(userAccountService, times(1)).getUserName(ListingReviewFixtures.LANDLORD_ID);
  }

  @Test
  @DisplayName("번역 컨텍스트는 페이지마다 한 번만 만든다")
  void buildsLocalizationContextOncePerPage() {
    // 관리자 화면은 한국어 고정이라 매물마다 같은 사전이 나온다. 매물마다 만들면
    // 카탈로그 원장을 페이지 크기만큼 다시 읽는다.
    givenAdmin();
    Listing listing = pending();
    given(listingRepository.findForAdmin(anySet(), anyInt(), anyInt(), any()))
        .willReturn(
            new PageResponse<>(
                List.of(listing, listing, listing), new PageInfo(0, 20, 3, 1, false)));
    given(userAccountService.getUserName(ListingReviewFixtures.LANDLORD_ID)).willReturn("김임대");

    service.list(ADMIN_ID, Set.of(), 0, 20, null);

    verify(catalogRepository, times(1)).findAll();
  }

  private void givenAdmin() {
    given(userAccountService.getUserType(ADMIN_ID)).willReturn("ADMIN");
  }

  /** 심사 응답 조립에 필요한 필드를 모두 채운 심사 대기 매물이다. */
  private static Listing pending() {
    return ListingReviewFixtures.pendingListing();
  }
}
