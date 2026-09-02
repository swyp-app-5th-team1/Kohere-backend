package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.kohere.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LandlordNameLookup}이 <b>호출자의 트랜잭션을 망가뜨리지 않는지</b> 검증한다.
 *
 * <p><b>왜 통합 테스트여야 하는가</b> — 이 클래스가 막는 결함은 스프링의 트랜잭션 전파에서만 나타난다. {@code UserAccountService}를 목으로
 * 대체하면 트랜잭션 인터셉터가 아예 끼지 않아 <b>결함이 있어도 초록</b>이다({@code AdminListingServiceTest}·{@code
 * AdminListingDocsTest}가 둘 다 목을 쓴다 — 그래서 이 테스트가 따로 있다). 실제 {@code user} 빈과 실제 MySQL이 있어야 재현된다.
 *
 * <p>확인하는 것: 트랜잭션 안에서 <b>없는 계정</b>의 이름을 물어도 (1) 예외가 새어 나오지 않고 (2) 그 트랜잭션이 rollback-only로 표시되지 않아
 * 호출자가 정상 커밋한다. 조회를 트랜잭션 밖으로 빼지 않으면 (2)가 깨져 커밋 시점에 {@code UnexpectedRollbackException} → {@code
 * 500}이 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, LandlordNameLookupIntegrationTest.Config.class})
class LandlordNameLookupIntegrationTest {

  /** 어떤 시드에도 없는 계정 식별자다. */
  private static final long MISSING_USER_ID = 999_999L;

  @Autowired private CallerStub caller;
  @Autowired private LandlordNameLookup landlordNameLookup;

  @Test
  void 없는_계정을_물어도_호출자의_트랜잭션이_멀쩡하다() {
    // 심사 목록·상세·승인·반려가 모두 @Transactional이라 이 성질이 곧 200과 500을 가른다.
    assertThatCode(() -> caller.lookupInsideTransaction(MISSING_USER_ID))
        .doesNotThrowAnyException();
  }

  @Test
  void 없는_계정의_이름은_null이다() {
    assertThat(landlordNameLookup.nameOf(MISSING_USER_ID)).isNull();
  }

  @Test
  void 식별자가_없으면_user를_묻지도_않는다() {
    assertThat(landlordNameLookup.nameOf(null)).isNull();
  }

  @TestConfiguration
  static class Config {
    @Bean
    CallerStub callerStub(LandlordNameLookup landlordNameLookup) {
      return new CallerStub(landlordNameLookup);
    }
  }

  /**
   * {@code AdminListingService}의 조회 메서드와 같은 모양 — {@code @Transactional} 안에서 이름을 묻고 정상 반환한다. 실제 서비스를
   * 쓰지 않는 것은 그쪽이 관리자 계정·매물 시드까지 요구해 <b>검증하려는 성질이 부수 조건에 묻히기</b> 때문이다.
   */
  static class CallerStub {
    private final LandlordNameLookup landlordNameLookup;

    CallerStub(LandlordNameLookup landlordNameLookup) {
      this.landlordNameLookup = landlordNameLookup;
    }

    @Transactional(readOnly = true)
    public String lookupInsideTransaction(long landlordId) {
      return landlordNameLookup.nameOf(landlordId);
    }
  }
}
