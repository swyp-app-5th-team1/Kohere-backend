package com.kohere.listing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 매물 심사 상태 전이 불변식을 검증한다(US-3-7).
 *
 * <p>승인·반려는 <b>상태를 가리지 않는다</b>. 잘못 반려한 매물을 되살리는 재승인, 공개 후 문제가 발견된 매물을 내리는 사후 반려, 이미 반려한 매물의 사유 정정이
 * 모두 정상 경로다 — 관리자의 오판을 되돌릴 수단이 서버에 있어야 하기 때문이다. 임대인이 고쳐 올린 {@code UPDATE_PENDING} 매물도 같은 문을 통과한다.
 */
class ListingReviewTransitionTest {

  private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");
  private static final Instant EARLIER = Instant.parse("2026-08-01T00:00:00Z");

  @Test
  @DisplayName("승인하면 공개 상태가 되고 갱신 시각이 바뀐다")
  void approveMakesListingPublished() {
    Listing approved = pending().approve(NOW);

    assertThat(approved.getStatus()).isEqualTo(Listing.ListingStatus.PUBLISHED);
    assertThat(approved.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("승인은 이전 반려 사유를 지운다")
  void approveClearsPreviousRejectionReason() {
    // 반려됐다 임대인이 고쳐 다시 올라온 매물은 이전 사유를 달고 PENDING으로 돌아온다.
    // 그 매물이 승인돼 공개될 때 지난 사유가 남아 있으면 안 된다.
    Listing resubmitted = pending().toBuilder().rejectionReason("주소가 일치하지 않습니다").build();

    assertThat(resubmitted.approve(NOW).getRejectionReason()).isNull();
  }

  @Test
  @DisplayName("반려하면 사유가 저장된다")
  void rejectStoresReason() {
    Listing rejected = pending().reject("사업자등록번호와 주소가 일치하지 않습니다", NOW);

    assertThat(rejected.getStatus()).isEqualTo(Listing.ListingStatus.REJECTED);
    assertThat(rejected.getRejectionReason()).isEqualTo("사업자등록번호와 주소가 일치하지 않습니다");
    assertThat(rejected.getUpdatedAt()).isEqualTo(NOW);
  }

  @ParameterizedTest
  @EnumSource(Listing.ListingStatus.class)
  @DisplayName("승인은 어느 상태에서든 할 수 있다")
  void approveAllowedFromAnyStatus(Listing.ListingStatus status) {
    // 잘못 반려한 매물을 되살리는 재승인(REJECTED)이 정상 경로다 — 관리자의 오판을 되돌릴
    // 수단이 서버에 없으면 임대인 수정 API가 나오기 전까지 그 매물이 묶인다.
    Listing listing = pending().toBuilder().status(status).rejectionReason("이전 사유").build();

    Listing approved = listing.approve(NOW);

    assertThat(approved.getStatus()).isEqualTo(Listing.ListingStatus.PUBLISHED);
  }

  @Test
  @DisplayName("이미 공개 중인 매물의 재승인은 아무 일도 하지 않는다")
  void approveIsNoOpWhenAlreadyPublished() {
    // updatedAt 이 바뀌면 세입자 목록의 기본 정렬(찜 수 → 최신 수정순)에서 그 매물만 위로 올라간다.
    Listing published = pending().toBuilder().status(Listing.ListingStatus.PUBLISHED).build();

    assertThat(published.approve(NOW)).isSameAs(published);
    assertThat(published.approve(NOW).getUpdatedAt()).isEqualTo(EARLIER);
  }

  @ParameterizedTest
  @EnumSource(Listing.ListingStatus.class)
  @DisplayName("반려는 어느 상태에서든 할 수 있다")
  void rejectAllowedFromAnyStatus(Listing.ListingStatus status) {
    // 심사 대기 매물의 1차 반려뿐 아니라, 공개 매물을 내리는 사후 반려(PUBLISHED)와
    // 이미 반려한 매물의 사유 정정(REJECTED)이 모두 정상 경로다.
    Listing listing = pending().toBuilder().status(status).rejectionReason("이전 사유").build();

    Listing rejected = listing.reject("새 사유", NOW);

    assertThat(rejected.getStatus()).isEqualTo(Listing.ListingStatus.REJECTED);
    assertThat(rejected.getRejectionReason()).isEqualTo("새 사유");
  }

  /** 전이 검증에 필요한 필드만 채운 심사 대기 매물이다. */
  private static Listing pending() {
    return Listing.builder()
        .id("68e0000000000000000000a1")
        .schemaVersion(4)
        .status(Listing.ListingStatus.PENDING)
        .createdAt(EARLIER)
        .updatedAt(EARLIER)
        .build();
  }
}
