/**
 * 매물 탐색·찜 Bounded Context. 매물 리스트/지도/키워드 검색, 매물 상세, 찜 토글·찜 목록, 최근 본 매물을 담당한다.
 *
 * <p>도메인 에러 코드 prefix: {@code LISTING}. 스펙: docs/api/specs/03-listings-favorites.md.
 *
 * <p>모듈 경계·계층 규칙은 docs/convention/code-style.md §3을 따른다. 공유 커널 {@code common}과 표시 언어 조회를 위한 {@code
 * user :: api}에만 의존한다. 다른 모듈이 매물 정보를 필요로 하면 listing 공개 API·이벤트로 가져간다. OPEN 모듈이라도 의존은 화이트리스트에 명시해야
 * 한다.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Listing",
    allowedDependencies = {"common", "user :: api"})
package com.kohere.listing;
