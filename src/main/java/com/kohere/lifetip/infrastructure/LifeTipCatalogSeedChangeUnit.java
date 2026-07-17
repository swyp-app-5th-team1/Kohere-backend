package com.kohere.lifetip.infrastructure;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * 생활 팁 카탈로그 캐노니컬 베이스라인(Mongock init changeUnit, order 0000). 레퍼런스 카탈로그 컬렉션 {@code
 * lifeTipTopics}·{@code lifeTips}를 비우고 캐노니컬 시드를 새로 적재한다 — Mongock changelog로 환경당 정확히 1회(진단 카탈로그와 동일
 * 방식, ADR-0032). 표시 문자열(주제명·주제 짧은/긴 설명·팁 제목·내용)은 언어-키 맵으로, 이미지({@code imageUrl}·주제 {@code
 * backgroundImageUrl})는 언어 무관으로 임베드한다(ADR-0029).
 *
 * <p>주제의 신설 4필드({@code shortDescription}·{@code longDescription}·{@code imageUrl}·{@code
 * backgroundImageUrl})는 <b>예시 기본값</b>({@link #EXAMPLE_SHORT_DESCRIPTION} 등)으로 채운다 — 운영이 실제 값을 DB에서
 * 갱신한다. 이미 order 0000을 실행한 환경 수렴은 {@link LifeTipTopicMediaBackfillChangeUnit}(order 0001)이 같은 예시
 * 기본값으로 모든 주제를 백필한다.
 *
 * <p>{@code test} 프로파일은 {@code mongock.enabled=false}라 실행되지 않는다(테스트는 자체 시드).
 */
@ChangeUnit(id = "lifetip-catalog-seed", order = "0000", author = "kohere")
public class LifeTipCatalogSeedChangeUnit {

  /** 주제 짧은 설명 예시 기본값(order 0001 백필과 공유). 운영이 실제 값을 DB에서 갱신한다. */
  static final Map<String, String> EXAMPLE_SHORT_DESCRIPTION =
      Map.of("ko", "예시 짧은 설명", "en", "Example short description", "ja", "例の短い説明");

  /** 주제 긴 설명 예시 기본값(order 0001 백필과 공유). 운영이 실제 값을 DB에서 갱신한다. */
  static final Map<String, String> EXAMPLE_LONG_DESCRIPTION =
      Map.of("ko", "예시 긴 설명", "en", "Example long description", "ja", "例の長い説明");

  /** 주제 홈 카드 이미지 예시 URL(order 0001 백필과 공유). 운영이 실제 CDN 자산 URL을 DB에서 갱신한다. */
  static final String EXAMPLE_IMAGE_URL =
      "https://cdn.kohere.app/life-tips/topics/example/card.png";

  /** 주제 상세 상단 배경 이미지 예시 URL(order 0001 백필과 공유). 운영이 실제 CDN 자산 URL을 DB에서 갱신한다. */
  static final String EXAMPLE_BACKGROUND_IMAGE_URL =
      "https://cdn.kohere.app/life-tips/topics/example/background.png";

  @Execution
  public void execution(MongoTemplate mongo) {
    mongo.remove(new Query(), LifeTipTopicDocument.class);
    mongo.remove(new Query(), LifeTipDocument.class);
    mongo.insertAll(topics());
    mongo.insertAll(tips());
  }

  private static List<LifeTipTopicDocument> topics() {
    return List.of(
        topic("MOVING_IN", 1, m("입주·이사", "Moving In", "入居・引っ越し")),
        topic("ADMINISTRATION", 2, m("행정 처리", "Administration", "行政手続き")),
        topic("TRANSPORT", 3, m("교통", "Transport", "交通")),
        topic("FINANCE", 4, m("은행·금융", "Banking & Finance", "銀行・金融")),
        topic("HOUSING", 5, m("주거 생활", "Housing", "住まい")));
  }

  private static List<LifeTipDocument> tips() {
    return List.of(
        tip(
            "MOVING_IN",
            1,
            m("전입신고 하기", "Resident registration", "転入届の手続き"),
            m(
                "입주 후 14일 이내에 거주지 관할 주민센터에서 전입신고를 합니다. 여권·외국인등록증·임대차계약서가 필요합니다.",
                "File your resident registration at the local community center within 14 days of"
                    + " moving in. Bring your passport, ARC, and lease contract.",
                "入居後14日以内に、お住まいの地域の住民センターで転入届を提出します。パスポート・在留カード・賃貸借契約書が必要です。"),
            "https://cdn.kohere.app/life-tips/moving-in/resident-registration.png"),
        tip(
            "MOVING_IN",
            2,
            m("공과금 개통·명의 변경", "Setting up utilities", "公共料金の開通・名義変更"),
            m(
                "전기·수도·가스는 관리사무소 또는 각 공급회사에 연락해 개통·명의 변경합니다. 관리비에 포함되는 경우도 있으니 계약 내용을 확인하세요.",
                "Contact the management office or each provider to set up electricity, water, and"
                    + " gas. Some are bundled into the maintenance fee — check your contract.",
                "電気・ガス・水道は管理事務所または各供給会社に連絡して開通・名義変更します。管理費に含まれる場合もあるため契約内容を確認してください。"),
            null),
        tip(
            "ADMINISTRATION",
            1,
            m("외국인등록증(ARC) 발급", "Getting your ARC", "外国人登録証（ARC）の発給"),
            m(
                "입국 후 90일 이내에 출입국·외국인청에서 외국인등록을 신청합니다. 하이코리아 사전 예약이 필요합니다.",
                "Apply for foreigner registration at the immigration office within 90 days of"
                    + " arrival. A prior reservation via HiKorea is required.",
                "入国後90日以内に、出入国・外国人庁で外国人登録を申請します。HiKoreaでの事前予約が必要です。"),
            "https://cdn.kohere.app/life-tips/administration/arc.png"),
        tip(
            "TRANSPORT",
            1,
            m("교통카드(T-money) 준비", "Get a T-money card", "交通カード（T-money）の準備"),
            m(
                "편의점에서 티머니 카드를 구입해 충전하면 지하철·버스를 환승 할인과 함께 이용할 수 있습니다.",
                "Buy and top up a T-money card at any convenience store to ride the subway and buses"
                    + " with transfer discounts.",
                "コンビニでT-moneyカードを購入・チャージすれば、地下鉄・バスを乗り換え割引付きで利用できます。"),
            null),
        tip(
            "FINANCE",
            1,
            m("은행 계좌 개설", "Opening a bank account", "銀行口座の開設"),
            m(
                "외국인등록증과 여권을 지참해 은행 지점을 방문하면 계좌를 개설할 수 있습니다. 일부 은행은 영어 상담이 가능합니다.",
                "Visit a bank branch with your ARC and passport to open an account. Some banks offer"
                    + " English-speaking service.",
                "在留カードとパスポートを持って銀行の支店を訪れると口座を開設できます。一部の銀行では英語対応が可能です。"),
            null),
        tip(
            "HOUSING",
            1,
            m("쓰레기 분리배출", "Waste separation rules", "ゴミの分別ルール"),
            m(
                "한국은 종량제봉투와 음식물 쓰레기 분리배출이 필수입니다. 지역마다 수거 요일이 다르니 게시판을 확인하세요.",
                "In Korea, volume-rate garbage bags and food-waste separation are mandatory."
                    + " Collection days vary by area — check the notice board.",
                "韓国では従量制ごみ袋と食品ごみの分別が必須です。地域ごとに収集日が異なるので掲示を確認してください。"),
            "https://cdn.kohere.app/life-tips/housing/waste-separation.png"));
  }

  private static LifeTipTopicDocument topic(String code, int order, Map<String, String> name) {
    return LifeTipTopicDocument.builder()
        .id(code)
        .name(name)
        .shortDescription(EXAMPLE_SHORT_DESCRIPTION)
        .longDescription(EXAMPLE_LONG_DESCRIPTION)
        .imageUrl(EXAMPLE_IMAGE_URL)
        .backgroundImageUrl(EXAMPLE_BACKGROUND_IMAGE_URL)
        .order(order)
        .build();
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

  private static Map<String, String> m(String ko, String en, String ja) {
    return Map.of("ko", ko, "en", en, "ja", ja);
  }

  /** forward-only: 시드는 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
