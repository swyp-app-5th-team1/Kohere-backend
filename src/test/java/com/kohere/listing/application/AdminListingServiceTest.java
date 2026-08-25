package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.listing.domain.AdminOnlyListingException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingNotFoundException;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.listing.domain.catalog.ListingCatalogRepository;
import com.kohere.user.api.UserAccountService;
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
 * 관리자 매물 심사 서비스의 <b>인가와 상태 전이</b>를 검증한다(US-3-7).
 *
 * <p>응답 조립(라벨·번역)은 세입자 상세와 같은 매퍼를 그대로 쓰므로 여기서 다시 확인하지 않는다. 이 테스트가 지키는 것은 두 가지다 — 관리자가 아니면 아무것도 못
 * 한다는 것, 그리고 심사 대상이 {@code PENDING}뿐이라는 것.
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
    service =
        new AdminListingService(
            listingRepository,
            new ListingLocalizationService(catalogRepository),
            userAccountService);
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

  private void givenAdmin() {
    given(userAccountService.getUserType(ADMIN_ID)).willReturn("ADMIN");
  }

  /** 심사 응답 조립에 필요한 필드를 모두 채운 심사 대기 매물이다. */
  private static Listing pending() {
    return ListingReviewFixtures.pendingListing();
  }
}
