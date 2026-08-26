package com.kohere.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 예약 영속 포트. 구현은 infrastructure 계층에 두어 의존성을 역전한다(docs/convention/code-style.md §3-3). 도메인은 영속 기술을
 * 모른다.
 *
 * <p>조회는 표시 가능한 예약만 반환한다 — 요청자 쪽 소프트삭제가 없고({@code *_deleted_at IS NULL}), 요청자가 차단한 상대의 예약이 아닌 것만
 * ({@code 상대 id NOT IN blockedIds}). {@code blockedIds}는 {@code user :: api}로 취득한 차단 상대 집합을 응용 계층이
 * 넘긴다(애플리케이션 레벨 조인). 반대로 변이·신고 대상 검증은 삭제·차단 상태와 무관해야 하므로 {@link #findByIdForMutation} 비필터 조회를 쓴다.
 */
public interface BookingRepository {

  /**
   * 예약을 저장한다. 동일 세입자·동일 방 상품 중복(유니크 {@code (tenant_id, room_offer_id)} 위반)은 {@link
   * BookingAlreadyExistsException}으로 변환한다(동시성 경합 포함 — 사전 조회만으로는 경합에서 샌다).
   */
  Booking save(Booking booking);

  /** 동일 세입자·동일 방 상품에 이미 예약이 있는지(신규 신청 중복 방지 사전 검사). */
  boolean existsByTenantIdAndRoomOfferId(long tenantId, String roomOfferId);

  /**
   * 동일 세입자가 해당 매물의 방 상품 중 하나에라도 신청한 기록이 있는지 확인한다.
   *
   * <p>채팅방 신청 배너는 매물 단위라 방 상품을 구분하지 않는다. 사용자별 삭제 시각도 목록 표시 상태일 뿐 신청 취소가 아니므로 이 조회에서는 제외 조건으로 쓰지
   * 않는다.
   */
  boolean existsByTenantIdAndListingId(long tenantId, String listingId);

  /** 표시 가능한 내 예약을 최신순(createdAt desc)으로 오프셋 조회한다(세입자 분기). */
  List<Booking> findVisibleByTenantId(
      Long tenantId, Collection<Long> blockedIds, int page, int size);

  long countVisibleByTenantId(Long tenantId, Collection<Long> blockedIds);

  /** 표시 가능한 내 예약 단건 조회. 없거나 타인 예약·삭제·차단 대상이면 빈 값(호출측이 404로 통일). */
  Optional<Booking> findVisibleByIdAndTenantId(Long id, Long tenantId, Collection<Long> blockedIds);

  /** 표시 가능한 내 소유 매물 신청을 최신순으로 오프셋 조회한다(임대인 분기 · landlord_id 스코프). */
  List<Booking> findVisibleByLandlordId(
      Long landlordId, Collection<Long> blockedIds, int page, int size);

  long countVisibleByLandlordId(Long landlordId, Collection<Long> blockedIds);

  /** 표시 가능한 내 소유 매물 신청 단건 조회. 없거나 내 소유가 아님·삭제·차단 대상이면 빈 값(호출측이 404로 통일). */
  Optional<Booking> findVisibleByIdAndLandlordId(
      Long id, Long landlordId, Collection<Long> blockedIds);

  /**
   * 변이·신고 대상 검증용 비필터 조회(삭제·차단 상태와 무관). 멱등 DELETE·차단 상대 도출·신고 대상 검증에 쓴다 — 이미 삭제·차단한 예약도 신고할 수 있어야
   * 하고, 필터된 조회를 쓰면 두 번째 DELETE가 404가 되어 멱등이 깨진다.
   */
  Optional<Booking> findByIdForMutation(Long id);

  /** 세입자 쪽 소프트삭제 표시(요청자가 세입자일 때). */
  void markTenantDeleted(Long id, Instant deletedAt);

  /** 임대인 쪽 소프트삭제 표시(요청자가 임대인일 때). */
  void markLandlordDeleted(Long id, Instant deletedAt);
}
