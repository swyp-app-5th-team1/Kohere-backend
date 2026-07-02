package com.kohere.lifetip.infrastructure;

import com.kohere.lifetip.domain.LifeTip;
import com.kohere.lifetip.domain.LifeTipCatalog;
import com.kohere.lifetip.domain.LifeTipTopic;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 생활 팁 카탈로그 영속 어댑터. 도메인 포트 {@link LifeTipCatalog}를 MongoDB로 구현하고 도큐먼트↔도메인 값을 매핑한다. 표시 문자열은 언어-키 맵
 * 그대로 도메인에 싣고, 번역(사용자 언어 선택·en 폴백)은 응용 계층이 수행한다(ADR-0029).
 */
@Repository
@RequiredArgsConstructor
public class LifeTipCatalogImpl implements LifeTipCatalog {

  private final LifeTipTopicMongoRepository topicRepository;
  private final LifeTipMongoRepository tipRepository;

  @Override
  public List<LifeTipTopic> findAllTopics() {
    return topicRepository.findAllByOrderByOrderAsc().stream()
        .map(LifeTipCatalogImpl::toTopic)
        .toList();
  }

  @Override
  public boolean topicExists(String topicCode) {
    return topicRepository.existsById(topicCode);
  }

  @Override
  public List<LifeTip> findTipsByTopicCode(String topicCode) {
    return tipRepository.findByTopicCodeOrderByOrderAsc(topicCode).stream()
        .map(LifeTipCatalogImpl::toTip)
        .toList();
  }

  private static LifeTipTopic toTopic(LifeTipTopicDocument d) {
    return new LifeTipTopic(d.getId(), d.getName(), d.getOrder());
  }

  private static LifeTip toTip(LifeTipDocument d) {
    return new LifeTip(
        d.getId(), d.getTopicCode(), d.getTitle(), d.getContent(), d.getImageUrl(), d.getOrder());
  }
}
