package com.kohere.diagnosis.application.dto;

import java.util.List;

/**
 * 단계별 질문 응답 DTO. 클라이언트가 path로 지정한 step(1..6)의 질문 1개를 서버가 선정·번역해 내려준다. ③(step 3)은 진행 중 진단에 저장된
 * purpose에 따라 서버가 university 또는 district 질문을 선택해 반환한다(클라 분기 아님). 다음 step 번호는 클라이언트가 정한다.
 *
 * <p>표시 {@code question} 라벨과 선택지 {@code label}만 사용자 등록 국가→언어로 번역하며(US-2-6), 선택지 {@code code}는 언어 무관
 * 동일하다. 미지원 언어는 영어로 폴백한다(에러 아님). 실제 카탈로그·서버 분기·번역은 MongoDB({@code diagnosisQuestions})에서 조회하며
 * #34(Mongo 선행) 이후 구현한다 — 현재는 스켈레톤 placeholder 구조만 둔다(과설계 금지).
 *
 * <p>docs/api/specs/02-diagnosis-recommendation.md.
 *
 * @param step 질문의 단계 번호(1..6)
 * @param field 제출 필드명(예: region·purpose·university·district·conditions·monthlyRent·arcStatus)
 * @param question 등록 국가 언어로 번역된 질문 라벨
 * @param select 선택 방식(단일/다중·최대 개수)
 * @param options 선택지 목록(③ 단계는 purpose에 따라 대학 또는 지역구 목록만 담는다)
 */
public record QuestionResponse(
    Integer step, String field, String question, Select select, List<Option> options) {

  /**
   * 선택 방식.
   *
   * @param type SINGLE 또는 MULTIPLE
   * @param max 최대 선택 개수
   */
  public record Select(String type, int max) {}

  /**
   * 선택지 1건.
   *
   * @param code 언어 무관 동일한 선택지 코드(enum 이름)
   * @param label 등록 국가 언어로 번역된 표시 라벨
   */
  public record Option(String code, String label) {}
}
