package com.kohere.chat.infrastructure.translation;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Translation Worker가 STOMP 처리 thread와 분리돼 실행되도록 전용 실행기를 구성한다. */
@Configuration
@EnableScheduling
public class ChatTranslationWorkerConfig {

  /**
   * Google 호출 전용 bounded executor다.
   *
   * <p>queue가 꽉 차면 호출한 STOMP thread에서 Google을 실행하지 않고 거부한다. 원문과 PENDING은 이미 DB에 남아 있어 60초 복구 조회가 다시
   * 제출할 수 있으므로 실시간 SEND 응답 속도를 보호하는 편이 안전하다.
   */
  @Bean(name = "chatTranslationTaskExecutor")
  public ThreadPoolTaskExecutor chatTranslationTaskExecutor(ChatTranslationProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.getWorkerPoolSize());
    executor.setMaxPoolSize(properties.getWorkerPoolSize());
    executor.setQueueCapacity(properties.getRecoveryBatchSize() * 2);
    executor.setThreadNamePrefix("chat-translation-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.initialize();
    return executor;
  }
}
