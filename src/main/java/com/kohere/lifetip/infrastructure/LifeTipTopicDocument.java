package com.kohere.lifetip.infrastructure;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 생활 팁 주제 카탈로그 MongoDB 도큐먼트({@code lifeTipTopics}). {@code _id}는 언어 무관 주제 코드(UPPER_SNAKE)이고
 * 표시명({@code name})은 언어-키 맵으로 임베드한다(ADR-0028·ADR-0029). 노출 순서 정렬 인덱스({@code order})는 부트스트랩에서 멱등
 * 생성한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "lifeTipTopics")
public class LifeTipTopicDocument {

  @Id private String id;
  private Map<String, String> name;
  private int order;
}
