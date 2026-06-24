/**
 * 맞춤 진단·매물 추천 Bounded Context. 6단계 진단(① 지역 / ② 입국 목적(유학 여부) / ③ 대학·지역 / ④ 주거 조건 / ⑤ 월 예산 / ⑥ ARC 발급
 * 여부) 단계별 제출·저장, 진단 이력·최근 진단·단건 상세 조회, 진단 결과에 따른 추천 매물·지도 좌표 조회를 담당한다.
 *
 * <p>도메인 에러 코드 prefix: {@code DIAGNOSIS}. 스펙: docs/api/specs/02-diagnosis-recommendation.md.
 *
 * <p>모듈 경계·계층 규칙은 docs/convention/code-style.md §3을 따른다. 공유 커널 {@code common} 외에 두 모듈의 공개
 * API(NamedInterface "api")에 의존한다 — {@code listing :: api}는 추천 매물 동기 조회({@code
 * recommendByCriteria}), {@code user :: api}는 진단 문항·추천 제안 라벨 번역을 위한 표시 언어 조회용이다(ADR-0002 Decision
 * 5, 즉시 결과가 필요한 동기 공개 쿼리).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Diagnosis",
    allowedDependencies = {"common", "listing :: api", "user :: api"})
package com.kohere.diagnosis;
