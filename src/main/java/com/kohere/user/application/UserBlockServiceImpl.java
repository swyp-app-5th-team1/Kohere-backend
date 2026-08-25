package com.kohere.user.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.user.api.BlockedUserView;
import com.kohere.user.api.UserBlockService;
import com.kohere.user.domain.User;
import com.kohere.user.domain.UserBlock;
import com.kohere.user.domain.UserBlockRepository;
import com.kohere.user.domain.UserRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 차단 유스케이스 구현({@link UserBlockService}). 차단 등록·해제·조회를 조율한다. 등록·해제는 멱등이며(관측 결과가 이미 성립하면 성공으로
 * 흡수), 목록은 상대 표시명을 함께 조립해 해제 UI에 제공한다. 표시명 조립은 user가 {@link User} 애그리거트를 소유하므로 자체 완결로 처리한다(엔티티
 * 비공유).
 */
@Service
@RequiredArgsConstructor
public class UserBlockServiceImpl implements UserBlockService {

  private static final int MAX_PAGE_SIZE = 100;

  private final UserBlockRepository userBlockRepository;
  private final AppUserGuard appUserGuard;
  private final UserRepository userRepository;

  @Override
  @Transactional
  public void block(long blockerId, long blockedUserId) {
    if (userBlockRepository.existsByBlockerIdAndBlockedUserId(blockerId, blockedUserId)) {
      return;
    }
    try {
      userBlockRepository.save(UserBlock.create(blockerId, blockedUserId, Instant.now()));
    } catch (DataIntegrityViolationException e) {
      // 동시 요청 경합 — 유니크 (blocker_id, blocked_user_id) 위반은 이미 차단된 것과 결과가 같으므로 흡수한다(멱등).
    }
  }

  @Override
  @Transactional
  public void unblock(long blockerId, long blockedUserId) {
    userBlockRepository.deleteByBlockerIdAndBlockedUserId(blockerId, blockedUserId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findBlockedUserIds(long blockerId) {
    return userBlockRepository.findBlockedUserIdsByBlockerId(blockerId);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isBlockedBetween(long userA, long userB) {
    return userBlockRepository.existsBetween(userA, userB);
  }

  /**
   * 범위 밖 {@code page}·{@code size}는 보정하지 않고 {@code 400 INVALID_INPUT}으로 거절한다 — 조용히 깎으면 클라이언트가 「원래
   * 그만큼」인지 「잘린 것」인지 구분할 수 없다. 저장소의 다른 목록 API와 같은 규칙이다.
   */
  @Override
  @Transactional(readOnly = true)
  public PageResponse<BlockedUserView> listBlocks(long blockerId, int page, int size) {
    validatePage(page, size);
    List<BlockedUserView> content =
        userBlockRepository.findByBlockerId(blockerId, page, size).stream()
            .map(this::toView)
            .toList();
    long total = userBlockRepository.countByBlockerId(blockerId);
    int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
    boolean hasNext = page + 1 < totalPages;
    return PageResponse.of(content, new PageInfo(page, size, total, totalPages, hasNext));
  }

  /** 페이지 번호와 크기가 API 약속 범위 안에 있는지 확인한다. */
  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new InvalidInputException("page", "validation.min", 0, page);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidInputException("size", "validation.range", 1, MAX_PAGE_SIZE, size);
    }
  }

  private BlockedUserView toView(UserBlock block) {
    return new BlockedUserView(
        block.getBlockedUserId(), resolveName(block.getBlockedUserId()), block.getCreatedAt());
  }

  /** 상대 표시명을 조립한다. 탈퇴·부재 등으로 이름이 없으면 빈 문자열(예외 없이) — 해제 목록은 계속 노출돼야 한다. */
  private String resolveName(long userId) {
    return userRepository
        .findById(userId)
        .map(u -> u.getName() == null ? "" : u.getName())
        .orElse("");
  }
}
