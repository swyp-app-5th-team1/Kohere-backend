package com.kohere.booking.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA 리포지토리(내부). 도메인 포트 {@code BookingRepository}의 어댑터가 사용한다. 조회는 삭제·차단 제외 필터를 JPQL로
 * 내린다({@code BookingService.page()}가 별도 count로 페이지 메타를 유도하므로 응용 계층 후처리 필터는 페이지네이션을 어긋나게 한다).
 */
interface BookingJpaRepository extends JpaRepository<BookingJpaEntity, Long> {

  boolean existsByTenantIdAndRoomOfferId(Long tenantId, String roomOfferId);

  /** 배너 판단은 사용자별 목록 숨김과 무관하게 실제 신청 행 존재 여부만 확인한다. */
  boolean existsByTenantIdAndListingId(Long tenantId, String listingId);

  @Query(
      "select b from BookingJpaEntity b where b.tenantId = :tenantId "
          + "and b.tenantDeletedAt is null and b.landlordId not in :blockedIds")
  List<BookingJpaEntity> findVisibleByTenantId(
      @Param("tenantId") Long tenantId,
      @Param("blockedIds") Collection<Long> blockedIds,
      Pageable pageable);

  @Query(
      "select count(b) from BookingJpaEntity b where b.tenantId = :tenantId "
          + "and b.tenantDeletedAt is null and b.landlordId not in :blockedIds")
  long countVisibleByTenantId(
      @Param("tenantId") Long tenantId, @Param("blockedIds") Collection<Long> blockedIds);

  @Query(
      "select b from BookingJpaEntity b where b.id = :id and b.tenantId = :tenantId "
          + "and b.tenantDeletedAt is null and b.landlordId not in :blockedIds")
  Optional<BookingJpaEntity> findVisibleByIdAndTenantId(
      @Param("id") Long id,
      @Param("tenantId") Long tenantId,
      @Param("blockedIds") Collection<Long> blockedIds);

  @Query(
      "select b from BookingJpaEntity b where b.landlordId = :landlordId "
          + "and b.landlordDeletedAt is null and b.tenantId not in :blockedIds")
  List<BookingJpaEntity> findVisibleByLandlordId(
      @Param("landlordId") Long landlordId,
      @Param("blockedIds") Collection<Long> blockedIds,
      Pageable pageable);

  @Query(
      "select count(b) from BookingJpaEntity b where b.landlordId = :landlordId "
          + "and b.landlordDeletedAt is null and b.tenantId not in :blockedIds")
  long countVisibleByLandlordId(
      @Param("landlordId") Long landlordId, @Param("blockedIds") Collection<Long> blockedIds);

  @Query(
      "select b from BookingJpaEntity b where b.id = :id and b.landlordId = :landlordId "
          + "and b.landlordDeletedAt is null and b.tenantId not in :blockedIds")
  Optional<BookingJpaEntity> findVisibleByIdAndLandlordId(
      @Param("id") Long id,
      @Param("landlordId") Long landlordId,
      @Param("blockedIds") Collection<Long> blockedIds);

  @Modifying
  @Query("update BookingJpaEntity b set b.tenantDeletedAt = :deletedAt where b.id = :id")
  void markTenantDeleted(@Param("id") Long id, @Param("deletedAt") Instant deletedAt);

  @Modifying
  @Query("update BookingJpaEntity b set b.landlordDeletedAt = :deletedAt where b.id = :id")
  void markLandlordDeleted(@Param("id") Long id, @Param("deletedAt") Instant deletedAt);
}
