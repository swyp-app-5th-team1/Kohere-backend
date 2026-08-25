package com.kohere.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalAccount} 도메인 단위 테스트 — 저장소·프레임워크 없이 상태 전이만 본다.
 *
 * <p>초점은 {@link LocalAccount#resetPassword}다. <b>이 한 메서드가 잠금 해제 API의 실체</b>이고(US-1-17 · 스펙 §1-10),
 * 별도 {@code unlock()}이 없는 것이 의도이므로, 여기서 세 값이 함께 되돌아가지 않으면 계정 복구 전체가 성립하지 않는다. 통합 테스트({@code
 * WebAccountRecoveryIntegrationTest})가 같은 사실을 HTTP로도 확인하지만, 거기서는 <b>왜 실패했는지</b>가 드러나지 않는다 — 어느 필드가
 * 어긋났는지는 이 파일이 말한다.
 */
class LocalAccountTest {

  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant LOCKED_AT = Instant.parse("2026-02-01T00:00:00Z");
  private static final Instant NOW = Instant.parse("2026-03-01T12:34:56Z");
  private static final LocalDate BIRTH_DATE = LocalDate.of(1990, 1, 1);

  @Nested
  @DisplayName("resetPassword")
  class ResetPassword {

    @Test
    @DisplayName("해시를 갈아 끼우고 실패 카운터·잠금·갱신 시각을 함께 되돌린다")
    void replacesHashAndClearsLock() {
      LocalAccount locked = lockedAccount();

      LocalAccount reset = locked.resetPassword("$2a$10$NEW", NOW);

      assertThat(reset.getPasswordHash()).isEqualTo("$2a$10$NEW");
      // 셋 중 하나라도 남으면 "비밀번호는 바뀌었는데 맞는 비밀번호로도 423"인 계정이 만들어진다.
      assertThat(reset.getFailedLoginAttempts()).isZero();
      assertThat(reset.getLockedAt()).isNull();
      assertThat(reset.isLocked()).isFalse();
      assertThat(reset.getUpdatedAt()).isEqualTo(NOW);
    }

    /**
     * <b>부분 인스턴스 저장 사고를 막는 단정이다.</b> {@code toBuilder()}가 아니라 새 {@code builder()}로 바꿔 쓰는 순간 여기 담기지
     * 않은 필드는 조용히 {@code null}·{@code 0}이 되고, 그 객체가 그대로 {@code save}로 흘러가면 <b>가입 폼 스냅샷과 생성 시각이 NULL로
     * 덮인다</b> — 로그인은 계속 되므로 아무 화면도 깨지지 않고, 사라진 것은 감사 흔적뿐이라 나중에 복구할 수도 없다.
     */
    @Test
    @DisplayName("식별자·가입 폼 스냅샷·생성 시각은 그대로 보존한다")
    void preservesIdentityAndSignupSnapshot() {
      LocalAccount locked = lockedAccount();

      LocalAccount reset = locked.resetPassword("$2a$10$NEW", NOW);

      assertThat(reset.getId()).isEqualTo(locked.getId());
      assertThat(reset.getUserId()).isEqualTo(locked.getUserId());
      assertThat(reset.getEmail()).isEqualTo(locked.getEmail());
      assertThat(reset.getName()).isEqualTo(locked.getName());
      assertThat(reset.getBirthDate()).isEqualTo(BIRTH_DATE);
      assertThat(reset.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    /** 원본을 건드리지 않는다 — 값 객체 전이라, 저장에 실패해도 메모리에 남은 애그리거트가 이미 바뀐 상태여서는 안 된다. */
    @Test
    @DisplayName("원본 인스턴스는 바뀌지 않는다")
    void doesNotMutateSource() {
      LocalAccount locked = lockedAccount();

      locked.resetPassword("$2a$10$NEW", NOW);

      assertThat(locked.getPasswordHash()).isEqualTo("$2a$10$OLD");
      assertThat(locked.getFailedLoginAttempts()).isEqualTo(10);
      assertThat(locked.getLockedAt()).isEqualTo(LOCKED_AT);
    }

    /**
     * 잠기지 않은 계정(비밀번호를 잊었을 뿐인 경우)도 같은 메서드를 탄다 — 재설정과 잠금 해제가 <b>같은 API</b>이므로 분기가 없다. 이미 {@code
     * null}인 {@code lockedAt}이 그대로 {@code null}이어야 한다.
     */
    @Test
    @DisplayName("잠기지 않은 계정도 같은 경로로 비밀번호만 바뀐다")
    void worksOnUnlockedAccount() {
      LocalAccount healthy =
          LocalAccount.register(7L, "kim@work.com", "$2a$10$OLD", "김임대", BIRTH_DATE, CREATED_AT);

      LocalAccount reset = healthy.resetPassword("$2a$10$NEW", NOW);

      assertThat(reset.getPasswordHash()).isEqualTo("$2a$10$NEW");
      assertThat(reset.getLockedAt()).isNull();
      assertThat(reset.getFailedLoginAttempts()).isZero();
      assertThat(reset.getUpdatedAt()).isEqualTo(NOW);
      assertThat(reset.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    /**
     * 재설정 뒤의 첫 실패는 <b>1부터</b> 센다. {@link LocalAccount#recordLoginFailure}에는 "잠기지 않았는데 카운터가 상한 이상이면
     * 새 창으로 본다"는 보정이 있어 카운터가 0이 아니어도 겉보기 결과는 같지만, 그 보정은 <b>운영자가 {@code locked_at}만 비운</b> 예외 경로를 위한
     * 것이다 — 재설정이 카운터를 남겨 두면 정상 경로가 그 보정에 의존하게 되고, 보정을 지우는 리팩터링이 재설정을 조용히 망가뜨린다.
     */
    @Test
    @DisplayName("재설정 뒤 첫 실패는 1부터 세고 곧바로 재잠금되지 않는다")
    void nextFailureStartsFromOne() {
      LocalAccount reset = lockedAccount().resetPassword("$2a$10$NEW", NOW);

      LocalAccount failedOnce = reset.recordLoginFailure(10, NOW.plusSeconds(60));

      assertThat(failedOnce.getFailedLoginAttempts()).isEqualTo(1);
      assertThat(failedOnce.isLocked()).isFalse();
    }
  }

  /** 상한(10회)까지 틀려 잠긴 계정 — 재설정이 되돌려야 하는 출발 상태다. */
  private static LocalAccount lockedAccount() {
    return LocalAccount.builder()
        .id(42L)
        .userId(7L)
        .email("kim@work.com")
        .passwordHash("$2a$10$OLD")
        .name("김임대")
        .birthDate(BIRTH_DATE)
        .failedLoginAttempts(10)
        .lockedAt(LOCKED_AT)
        .createdAt(CREATED_AT)
        .updatedAt(LOCKED_AT)
        .build();
  }
}
