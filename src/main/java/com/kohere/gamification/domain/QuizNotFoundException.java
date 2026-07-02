package com.kohere.gamification.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 활성 퀴즈 풀이 비어 있거나 채점 대상 {@code quizId}가 존재하지 않는 경우. 전역 핸들러가 404 {@code QUIZ_NOT_FOUND}로 변환한다. */
public class QuizNotFoundException extends BusinessException {

  public QuizNotFoundException() {
    super(ErrorCode.QUIZ_NOT_FOUND);
  }
}
