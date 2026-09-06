package com.kohere.notification.application;

import com.kohere.notification.domain.NotificationPreference;
import com.kohere.notification.domain.NotificationPreferenceRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자별 채팅 푸시 기본값 조회와 명시적인 수신 설정 변경을 담당하는 응용 서비스다. */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

  /** 설정을 저장한 적 없는 사용자에게 적용하는 채팅 푸시 기본값이다. */
  private static final boolean DEFAULT_CHAT_PUSH_ENABLED = true;

  private final NotificationPreferenceRepository preferenceRepository;

  /**
   * 현재 사용자의 채팅 푸시 허용 여부를 반환한다.
   *
   * <p>기존·신규 사용자 행을 미리 만들지 않고 설정 행이 없으면 {@code true}로 해석한다. 조회 자체는 DB를 변경하지 않는다.
   *
   * @param userId 조회할 사용자 ID
   * @return 명시적으로 저장된 값 또는 기본값 {@code true}
   */
  @Transactional(readOnly = true)
  public boolean isChatPushEnabled(long userId) {
    validateUserId(userId);
    return preferenceRepository
        .findByUserId(userId)
        .map(NotificationPreference::chatPushEnabled)
        .orElse(DEFAULT_CHAT_PUSH_ENABLED);
  }

  /**
   * 현재 사용자의 채팅 푸시 설정을 생성하거나 갱신한다.
   *
   * @param userId 변경할 사용자 ID
   * @param chatPushEnabled 새 채팅 푸시 허용 여부
   * @return DB에 저장한 최종 값
   */
  @Transactional
  public boolean updateChatPushEnabled(long userId, boolean chatPushEnabled) {
    NotificationPreference preference = new NotificationPreference(userId, chatPushEnabled);
    preferenceRepository.upsert(preference, Instant.now());
    return chatPushEnabled;
  }

  /** 서비스가 JWT에서 받은 사용자 식별자의 최소 도메인 조건을 공통 검증한다. */
  private static void validateUserId(long userId) {
    if (userId < 1) {
      throw new IllegalArgumentException("userId must be positive");
    }
  }
}
