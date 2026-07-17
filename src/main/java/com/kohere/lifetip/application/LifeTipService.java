package com.kohere.lifetip.application;

import com.kohere.lifetip.application.dto.TipListResponse;
import com.kohere.lifetip.application.dto.TopicListResponse;
import com.kohere.lifetip.domain.LifeTip;
import com.kohere.lifetip.domain.LifeTipCatalog;
import com.kohere.lifetip.domain.LifeTipTopic;
import com.kohere.lifetip.domain.LifeTipTopicNotFoundException;
import com.kohere.lifetip.domain.TenantOnlyException;
import com.kohere.user.api.UserAccountService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 생활 팁 조회 유스케이스 조율. 운영이 시드로 적재한 읽기 전용 큐레이션 카탈로그를 사용자 표시 언어로 번역해 내려준다(US-8, ADR-0029). 표시 언어는 {@link
 * UserAccountService#getLanguage(long)} 동기 공개 쿼리로 취득하고({@code users.lang}, 미설정이면 en — ADR-0002
 * D5·#141), 미지원 언어는 영어({@code en})로 폴백한다(에러 아님).
 *
 * <p>대상은 외국인 세입자(userType=TENANT, ACTIVE)라 임대인 등은 403(FORBIDDEN)으로 거부한다(US-8, 조회 진입 시 게이트). 읽기 전용이라
 * 상태 변경·발행 이벤트가 없다. 스펙: docs/api/specs/08-life-tips.md.
 */
@Service
@RequiredArgsConstructor
public class LifeTipService {

  /** 표시 문자열 언어-키 맵에서 사용자 언어 값이 없을 때의 폴백 언어(ADR-0029). */
  private static final String DEFAULT_LANGUAGE = "en";

  /** 세입자 전용 게이트 기준 userType(US-8). */
  private static final String USER_TYPE_TENANT = "TENANT";

  private final LifeTipCatalog lifeTipCatalog;
  private final UserAccountService userAccountService;

  /** 주제 전체 목록(노출 순서, 사용자 표시 언어로 표시명 번역). 세입자 전용. */
  public TopicListResponse getTopics(long userId) {
    assertTenant(userId);
    String language = resolveLanguage(userId);
    List<TopicListResponse.Topic> topics =
        lifeTipCatalog.findAllTopics().stream().map(t -> toTopic(t, language)).toList();
    return new TopicListResponse(topics);
  }

  /** 특정 주제의 팁 전체(노출 순서, 제목·내용 번역). 세입자 전용. 미존재 주제는 {@code LIFE_TIP_TOPIC_NOT_FOUND}(404). */
  public TipListResponse getTips(long userId, String topicCode) {
    assertTenant(userId);
    if (!lifeTipCatalog.topicExists(topicCode)) {
      throw new LifeTipTopicNotFoundException();
    }
    String language = resolveLanguage(userId);
    List<TipListResponse.Tip> tips =
        lifeTipCatalog.findTipsByTopicCode(topicCode).stream()
            .map(t -> toTip(t, language))
            .toList();
    return new TipListResponse(tips);
  }

  /** 외국인 세입자(userType=TENANT) 전용 게이트. 임대인 등은 403(FORBIDDEN)으로 거부한다(US-8). */
  private void assertTenant(long userId) {
    if (!USER_TYPE_TENANT.equals(userAccountService.getUserType(userId))) {
      throw new TenantOnlyException();
    }
  }

  private String resolveLanguage(long userId) {
    return userAccountService.getLanguage(userId);
  }

  private static TopicListResponse.Topic toTopic(LifeTipTopic topic, String language) {
    return new TopicListResponse.Topic(
        topic.code(),
        pickLabel(topic.name(), language),
        pickLabel(topic.shortDescription(), language),
        pickLabel(topic.longDescription(), language),
        topic.imageUrl(),
        topic.backgroundImageUrl());
  }

  private static TipListResponse.Tip toTip(LifeTip tip, String language) {
    return new TipListResponse.Tip(
        tip.id(),
        pickLabel(tip.title(), language),
        pickLabel(tip.content(), language),
        tip.imageUrl());
  }

  private static String pickLabel(Map<String, String> labels, String language) {
    if (labels == null) {
      return null;
    }
    String value = labels.get(language);
    if (value != null) {
      return value;
    }
    return labels.getOrDefault(DEFAULT_LANGUAGE, labels.values().stream().findFirst().orElse(null));
  }
}
