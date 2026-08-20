package com.kohere.docs;

/**
 * Swagger 오퍼레이션 태그. 표시 순서는 {@code swagger-ui-initializer.js}의 {@code TAG_ORDER}와 1:1로 유지한다(#151).
 *
 * <p>{@code resourceDetails().tag(...)}를 생략하면 {@code ResourceSnippet}이 URI 첫 세그먼트를 태그로 넣는다 — 우리 경로는
 * 전부 {@code /api/v1/...}이라 46개 오퍼레이션이 {@code api} 하나로 뭉친다(1단계 실측). 그리고 오퍼레이션 태그는 그 오퍼레이션에 속한 스니펫
 * 태그의 union이므로, <b>한 스니펫만 빠뜨려도 두 그룹에 중복 노출된다</b>.
 *
 * <p>귀속 기준은 경로가 아니라 <b>소유 모듈(스펙 문서)</b>이다 — {@code /api/v2/users/me/favorites}는 listing 모듈 ({@code
 * MyListingV2Controller}) 소유라 {@link #LISTINGS}, {@code /api/v1/users/me/blocks}는 user 모듈 소유라
 * {@link #USERS}다.
 *
 * <p>deepLinking 앵커가 태그명을 URL 인코딩하므로 이름은 ASCII로 둔다. 태그 <b>설명</b>은 넣지 않는다 — 테스트가 정할 수 없고 빌드 시점 별도
 * 파일로만 넣을 수 있어 「문서의 단일 소스는 테스트」 원칙에서 벗어난다(ADR-0017).
 */
public final class ApiDocsTags {

  /** 소셜 로그인 · 약관 동의 · 온보딩 · 연락처/사업자 검증 · 토큰 재발급 (specs/01-auth-onboarding.md). */
  public static final String AUTH = "Auth";

  /** 내 프로필 조회·수정·탈퇴 · 차단 목록 (specs/01-auth-onboarding.md). 찜·최근 본 매물은 {@link #LISTINGS}. */
  public static final String USERS = "Users";

  /** 5단계 맞춤 진단(v1 회원 전용 · v2 서버 주도/게스트 허용)과 진단 기반 추천 (specs/02). */
  public static final String DIAGNOSIS = "Diagnosis";

  /** 매물 신청(예약) 생성·조회·삭제·차단·신고 (specs/04-booking-inquiry-chat.md). */
  public static final String BOOKINGS = "Bookings";

  /** 1:1 매물 채팅방·메시지·차단·신고 (architecture/chat, specs/04-booking-inquiry-chat.md). */
  public static final String CHATS = "Chats";

  /** 한국 생활 학습 퀴즈 조회·채점 (specs/06-gamification.md). */
  public static final String QUIZ = "Quiz";

  /** 주제별 생활 팁 (specs/08-life-tips.md). */
  public static final String LIFE_TIPS = "LifeTips";

  /** 매물 탐색·지도·검색·찜·최근 본 매물 (specs/03-listings-favorites.md). */
  public static final String LISTINGS = "Listings";

  private ApiDocsTags() {}
}
