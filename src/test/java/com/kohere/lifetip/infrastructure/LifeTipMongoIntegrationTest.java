package com.kohere.lifetip.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.kohere.lifetip.application.LifeTipService;
import com.kohere.lifetip.application.dto.TipListResponse;
import com.kohere.lifetip.application.dto.TopicListResponse;
import com.kohere.lifetip.domain.LifeTipTopicNotFoundException;
import com.kohere.user.api.UserAccountService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 생활 팁 모듈 MongoDB 통합 테스트. 실제 Mongo 컨테이너로 주제·팁 영속·정렬·번역·en 폴백·사진 nullable·404를 end-to-end 검증한다. 표시
 * 언어(user getLanguage)는 mock으로 대체한다(test-strategy §4). Mongock(@ChangeUnit)은 슬라이스 테스트에서 비활성화한다.
 */
@DataMongoTest
@Testcontainers
@TestPropertySource(properties = "mongock.enabled=false")
@Import({LifeTipService.class, LifeTipCatalogImpl.class})
class LifeTipMongoIntegrationTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired LifeTipService lifeTipService;
  @Autowired LifeTipTopicMongoRepository topicRepository;
  @Autowired LifeTipMongoRepository tipRepository;

  @MockitoBean UserAccountService userAccountService;

  @BeforeEach
  void clean() {
    topicRepository.deleteAll();
    tipRepository.deleteAll();
  }

  @Test
  @DisplayName("주제 목록은 노출 순서로 정렬되고 등록 국가 언어로 번역된다")
  void topicsTranslatedAndOrdered() {
    topicRepository.save(topic("TRANSPORT", 2, Map.of("en", "Transport", "ko", "교통")));
    topicRepository.save(topic("MOVING_IN", 1, Map.of("en", "Moving In", "ko", "입주")));
    given(userAccountService.getLanguage(1L)).willReturn("ko");

    TopicListResponse res = lifeTipService.getTopics(1L);

    assertThat(res.topics())
        .extracting(TopicListResponse.Topic::code)
        .containsExactly("MOVING_IN", "TRANSPORT");
    assertThat(res.topics().get(0).name()).isEqualTo("입주");
  }

  @Test
  @DisplayName("미지원 언어는 영어(en)로 폴백하고 code는 언어 무관하다")
  void topicsFallbackToEnglish() {
    topicRepository.save(topic("MOVING_IN", 1, Map.of("en", "Moving In", "ko", "입주")));
    given(userAccountService.getLanguage(2L)).willReturn("ja");

    TopicListResponse res = lifeTipService.getTopics(2L);

    assertThat(res.topics().get(0).name()).isEqualTo("Moving In");
    assertThat(res.topics().get(0).code()).isEqualTo("MOVING_IN");
  }

  @Test
  @DisplayName("주제별 팁은 노출 순서로 번역돼 반환되고, 사진 없는 팁은 imageUrl=null")
  void tipsTranslatedWithNullableImage() {
    topicRepository.save(topic("MOVING_IN", 1, Map.of("en", "Moving In", "ko", "입주")));
    tipRepository.save(
        tip(
            "MOVING_IN",
            1,
            Map.of("en", "Registration", "ko", "전입신고"),
            Map.of("en", "Do it within 14 days.", "ko", "14일 이내에 하세요."),
            "http://img/1.png"));
    tipRepository.save(
        tip(
            "MOVING_IN",
            2,
            Map.of("en", "Utilities", "ko", "공과금"),
            Map.of("en", "Set up electricity.", "ko", "전기를 개통하세요."),
            null));
    given(userAccountService.getLanguage(3L)).willReturn("ko");

    TipListResponse res = lifeTipService.getTips(3L, "MOVING_IN");

    assertThat(res.tips()).extracting(TipListResponse.Tip::title).containsExactly("전입신고", "공과금");
    assertThat(res.tips().get(0).content()).isEqualTo("14일 이내에 하세요.");
    assertThat(res.tips().get(0).imageUrl()).isEqualTo("http://img/1.png");
    assertThat(res.tips().get(1).imageUrl()).isNull();
    assertThat(res.tips().get(0).id()).isNotBlank();
  }

  @Test
  @DisplayName("존재하지 않는 주제 코드로 팁을 조회하면 LIFE_TIP_TOPIC_NOT_FOUND")
  void tipsUnknownTopic() {
    assertThatThrownBy(() -> lifeTipService.getTips(4L, "UNKNOWN"))
        .isInstanceOf(LifeTipTopicNotFoundException.class);
  }

  @Test
  @DisplayName("주제는 존재하나 팁이 0건이면 빈 목록")
  void tipsEmpty() {
    topicRepository.save(topic("FINANCE", 1, Map.of("en", "Finance", "ko", "금융")));
    given(userAccountService.getLanguage(anyLong())).willReturn("en");
    assertThat(lifeTipService.getTips(5L, "FINANCE").tips()).isEmpty();
  }

  private static LifeTipTopicDocument topic(String code, int order, Map<String, String> name) {
    return LifeTipTopicDocument.builder().id(code).name(name).order(order).build();
  }

  private static LifeTipDocument tip(
      String topicCode,
      int order,
      Map<String, String> title,
      Map<String, String> content,
      String imageUrl) {
    return LifeTipDocument.builder()
        .topicCode(topicCode)
        .order(order)
        .title(title)
        .content(content)
        .imageUrl(imageUrl)
        .build();
  }
}
