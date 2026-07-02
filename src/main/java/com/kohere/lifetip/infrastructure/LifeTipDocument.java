package com.kohere.lifetip.infrastructure;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 생활 팁 카탈로그 MongoDB 도큐먼트({@code lifeTips}). 하나의 주제({@code topicCode})에 속하며(주제 : 팁 = 1 : N) 표시
 * 문자열(title·content)은 언어-키 맵으로, 사진 {@code imageUrl}은 언어 무관(없으면 {@code null})으로 임베드한다(ADR-0029). 복합
 * 인덱스({@code topicCode, order})는 부트스트랩에서 멱등 생성한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "lifeTips")
@CompoundIndex(name = "topicCode_order_idx", def = "{'topicCode': 1, 'order': 1}")
public class LifeTipDocument {

  @Id private String id;
  private String topicCode;
  private Map<String, String> title;
  private Map<String, String> content;
  private String imageUrl;
  private int order;
}
