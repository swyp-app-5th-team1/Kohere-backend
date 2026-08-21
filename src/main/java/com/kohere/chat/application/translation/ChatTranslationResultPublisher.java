package com.kohere.chat.application.translation;

import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.translation.ChatMessageTranslation;

/** 최종 번역 상태와 원문을 해당 수신자에게 함께 전달하는 출력 포트다. */
public interface ChatTranslationResultPublisher {

  /** DB 최종 상태가 커밋된 뒤 수신자의 개인 STOMP queue로 한 번 발행한다. */
  void publish(Message originalMessage, ChatMessageTranslation translation);
}
