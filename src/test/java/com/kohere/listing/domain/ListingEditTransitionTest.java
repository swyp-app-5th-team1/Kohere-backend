package com.kohere.listing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 임대인 수정의 상태 전이와 승계 불변식을 검증한다(US-3-9).
 *
 * <p>여기서 지키는 것은 두 가지다 — <b>심사 중에는 손댈 수 없다</b>는 게이트와, 수정이 <b>건드리면 안 되는 값</b>이 그대로 남는다는 계약이다. 후자가 깨지면
 * 임대인이 매물을 고칠 때마다 찜 수가 초기화되거나 최초 동의 시각이 덮이는데, 어느 쪽도 응답만 봐서는 드러나지 않는다.
 */
class ListingEditTransitionTest {

  private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");
  private static final Instant CREATED = Instant.parse("2026-06-01T00:00:00Z");
  private static final Instant AGREED = Instant.parse("2026-06-01T00:00:00Z");

  @Test
  @DisplayName("반려된 매물을 수정하면 심사 대기로 돌아간다")
  void editingRejectedListingReturnsToPending() {
    Listing rejected = listing(Listing.ListingStatus.REJECTED);
    Listing edited = rejected.afterEdit(rejected.toBuilder(), NOW);

    assertThat(edited.getStatus()).isEqualTo(Listing.ListingStatus.PENDING);
    assertThat(edited.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("공개 중인 매물을 수정하면 수정 심사 대기가 된다")
  void editingPublishedListingMovesToUpdatePending() {
    Listing published = listing(Listing.ListingStatus.PUBLISHED);
    Listing edited = published.afterEdit(published.toBuilder(), NOW);

    assertThat(edited.getStatus()).isEqualTo(Listing.ListingStatus.UPDATE_PENDING);
  }

  @Test
  @DisplayName("수정은 반려 사유를 지우지 않는다 — 지우는 것은 승인뿐이다")
  void editPreservesRejectionReason() {
    // 사유가 남아야 임대인은 심사를 기다리는 동안 무엇을 고치라고 했는지 다시 볼 수 있고,
    // 재심사하는 관리자는 이 매물이 전에 왜 반려됐는지 알 수 있다.
    // 「지금 고쳐야 한다(REJECTED)」와 「고쳐서 재심사 중(PENDING)」은 상태가 이미 구분한다.
    Listing rejected =
        listing(Listing.ListingStatus.REJECTED).toBuilder()
            .rejectionReason("사업자등록번호가 일치하지 않습니다")
            .build();

    Listing resubmitted = rejected.afterEdit(rejected.toBuilder(), NOW);

    assertThat(resubmitted.getStatus()).isEqualTo(Listing.ListingStatus.PENDING);
    assertThat(resubmitted.getRejectionReason()).isEqualTo("사업자등록번호가 일치하지 않습니다");
    // 공개되는 시점에야 사라진다.
    assertThat(resubmitted.approve(NOW).getRejectionReason()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = Listing.ListingStatus.class,
      names = {"PENDING", "UPDATE_PENDING"})
  @DisplayName("심사 중인 매물은 수정할 수 없다")
  void listingUnderReviewCannotBeEdited(Listing.ListingStatus status) {
    Listing underReview = listing(status);

    assertThatThrownBy(underReview::requireEditable)
        .isInstanceOf(ListingNotEditableException.class);
    assertThatThrownBy(() -> underReview.afterEdit(underReview.toBuilder(), NOW))
        .isInstanceOf(ListingNotEditableException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = Listing.ListingStatus.class,
      names = {"REJECTED", "PUBLISHED"})
  @DisplayName("반려·공개 매물은 수정할 수 있다")
  void editableStatusesPassTheGate(Listing.ListingStatus status) {
    listing(status).requireEditable();
  }

  @Test
  @DisplayName("수정은 소유자·생성 시각·찜 수·동의를 그대로 승계한다")
  void editPreservesInheritedValues() {
    // 요청 DTO에 칸이 없는 값들이다. 조립 헬퍼가 이 값을 만들게 하면
    // 수정할 때마다 최초 동의 시각이 덮이고 찜 수가 초기화된다.
    Listing before = listing(Listing.ListingStatus.PUBLISHED);

    Listing after = before.afterEdit(before.toBuilder(), NOW);

    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getLandlordId()).isEqualTo(before.getLandlordId());
    assertThat(after.getSchemaVersion()).isEqualTo(before.getSchemaVersion());
    assertThat(after.getCreatedAt()).isEqualTo(CREATED);
    assertThat(after.getFavoriteCount()).isEqualTo(7);
    assertThat(after.getConsents()).isEqualTo(before.getConsents());
    assertThat(after.getConsents().agreedAt()).isEqualTo(AGREED);
  }

  @Test
  @DisplayName("소유권은 계정 id가 일치할 때만 인정된다")
  void ownershipRequiresMatchingAccount() {
    Listing listing = listing(Listing.ListingStatus.PUBLISHED);

    assertThat(listing.isOwnedBy(7L)).isTrue();
    assertThat(listing.isOwnedBy(8L)).isFalse();
    assertThat(Listing.builder().build().isOwnedBy(7L)).isFalse();
  }

  private static Listing listing(Listing.ListingStatus status) {
    return Listing.builder()
        .id("68e0000000000000000000a1")
        .schemaVersion(4)
        .landlordId(7L)
        .status(status)
        .favoriteCount(7)
        .imageUrls(List.of("https://cdn.example.com/listings/68e0000000000000000000a1/cover/a.jpg"))
        .nearbyUniversityCodes(Set.of())
        .roomOffers(List.of())
        .consents(new Listing.Consents(true, true, "v1.0", AGREED))
        .createdAt(CREATED)
        .updatedAt(CREATED)
        .build();
  }
}
