package com.kohere.chat.infrastructure.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** WebSocket heartbeat와 JWT 만료 close가 공용 비즈니스 작업을 막지 않도록 전용 scheduler를 만든다. */
@Configuration(proxyBeanMethods = false)
public class ChatWebSocketSchedulerConfig {

  /**
   * heartbeat와 만료 close가 동시에 실행될 수 있도록 작은 전용 pool을 사용한다.
   *
   * <p>서버 종료 때 한 시간 뒤의 JWT 만료 작업을 기다리면 배포가 멈춘다. 주기·지연 작업을 이어서 실행하지 않도록 명시해 종료와 함께 안전하게 취소한다.
   */
  @Bean(name = "chatWebSocketTaskScheduler")
  public ThreadPoolTaskScheduler chatWebSocketTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("chat-websocket-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    return scheduler;
  }
}
