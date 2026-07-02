/**
 * 게이미피케이션(학습 퀴즈) Bounded Context. 외국인 세입자(userType=TENANT, ACTIVE) 대상의 무상태 랜덤 4지선다 학습 퀴즈를 담당한다 —
 * 요청마다 활성 퀴즈 풀에서 무작위 1개를 사용자 언어로 번역해 조회({@code GET /api/v1/quizzes/random})하고, 제출한 보기를 저장된 정답과 대조해
 * 즉시 채점한다({@code POST /api/v1/quizzes/{quizId}/answer}). 제출 기록·포인트는 없다(ADR-0035).
 *
 * <p>도메인 에러 코드 prefix: {@code QUIZ}. 스펙: docs/api/specs/06-gamification.md, 결정: ADR-0035.
 *
 * <p>표시 언어는 {@code user} 공개 API {@code getLanguage}(등록 국가 → 언어)로 동기 취득한다(ADR-0002 Decision
 * 5·ADR-0029) — 다른 모듈 타입을 직접 import 하지 않고 공개 API로만 협력한다. 퀴즈 콘텐츠는 MongoDB 카탈로그로 둔다(ADR-0005).
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Gamification",
    allowedDependencies = {"common", "user :: api"})
package com.kohere.gamification;
