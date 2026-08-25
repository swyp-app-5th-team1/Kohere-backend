package com.kohere.docs;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.snippet.Attributes.key;

import com.epages.restdocs.apispec.EnumFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;

/**
 * 문서 테스트가 공유하는 응답/요청 필드 기술자 팩토리(#151).
 *
 * <p><b>왜 공용인가</b> — 같은 {@code (path, method, status)}를 캡처하는 스니펫들의 필드 목록은 이어붙여진 뒤 {@code
 * JsonSchemaFromFieldDescriptorsGenerator.reduceFieldDescriptors}가 {@code (path, type)} 기준으로 접는다.
 * 중복은 <b>버려지고</b> 승자는 {@code FilesKt.walkTopDown()} 파일 순회 순서에 좌우된다. 두 파일이 같은 오퍼레이션을 서로 다른 기술자로
 * 문서화하면 한쪽의 enum·optional이 조용히 사라지고, 로컬(NTFS)과 CI(ext4)에서 결과가 달라질 수 있다. 그래서 같은 오퍼레이션의 스니펫은 <b>반드시
 * 같은 헬퍼를 호출</b>해야 한다.
 */
public final class ApiDocsFields {

  private ApiDocsFields() {}

  public static FieldDescriptor field(String path, JsonFieldType type, String description) {
    return fieldWithPath(path).type(type).description(description);
  }

  /**
   * 값이 null이거나 응답에서 생략될 수 있는 필드. {@code optional()}만이 스키마에 {@code nullable: true}를 만든다 ({@code
   * checkNullable()}은 {@code if (optional) nullable(true)}가 전부다).
   *
   * <p>OpenAPI 3.0은 「null」과 「키 부재」를 구분할 수 없으므로, 필드 자체가 생략되는 경우에는 description에 「null이 아니라 필드 자체가
   * 생략된다」를 명시한다.
   */
  public static FieldDescriptor optField(String path, JsonFieldType type, String description) {
    return fieldWithPath(path).type(type).optional().description(description);
  }

  /**
   * enum 상수 전체를 스키마 {@code enum}으로 싣는다. 상수가 바뀌면 문서가 자동으로 따라간다(드리프트 0).
   *
   * <p><b>주의</b> — {@link EnumFields}는 {@code Enum.toString()}을 값으로 쓴다. 와이어 값이 상수명과 다르거나 (예: 언어 코드
   * {@code en|ko|ja}), 요청/응답의 허용 집합이 다르거나, 실제 도달 불가한 값이 섞이면 {@link #codeField}로 값을 직접 나열한다.
   */
  public static FieldDescriptor enumField(
      String path, Class<? extends Enum<?>> enumType, String description) {
    return new EnumFields(enumType).withPath(path).description(description);
  }

  /**
   * enum 상수를 파라미터 설명에 끼워 넣을 한 줄 목록으로 만든다.
   *
   * <p>쿼리 파라미터는 REST Docs가 타입·enum을 스키마에 싣지 않아 설명 문자열이 값을 알리는 유일한 통로다. 손으로 나열하면 enum만 바뀌고 문서가 남으므로
   * 여기서 뽑는다.
   */
  public static String codeList(Class<? extends Enum<?>> enumType) {
    return String.join(", ", enumValues(enumType));
  }

  public static FieldDescriptor optEnumField(
      String path, Class<? extends Enum<?>> enumType, String description) {
    return new EnumFields(enumType).withPath(path).optional().description(description);
  }

  /**
   * 스칼라 코드값. enum 클래스가 없거나(DB 카탈로그) 와이어 값이 상수명과 다를 때 쓴다.
   *
   * <p>{@code type("enum")}은 {@link JsonFieldType}이 아니라 문자열이며, 뒤에 {@code .type(...)}을 다시 부르면 enum이
   * 날아간다. 생성기는 이를 {@code oneOf(StringSchema, EnumSchema).isSynthetic}으로 만들어 최종적으로 {@code {type:
   * string, enum: [...]}}을 낸다.
   *
   * <p><b>배열 필드에 쓰면 안 된다</b> — 배열이 문자열로 잘못 문서화되는데, REST Docs 타입 검증은 기술자 타입이 {@link JsonFieldType}이
   * 아니면 건너뛰므로 <b>테스트는 통과하고 문서만 틀린다</b>. {@link #codeArrayField}를 쓴다.
   */
  public static FieldDescriptor codeField(
      String path, List<String> allowedValues, String description) {
    return fieldWithPath(path)
        .type("enum")
        .attributes(key("enumValues").value(allowedValues))
        .description(description);
  }

  public static FieldDescriptor optCodeField(
      String path, List<String> allowedValues, String description) {
    return codeField(path, allowedValues, description).optional();
  }

  /**
   * 배열 원소가 코드값인 경우. {@code arrayItemsSchema()}가 {@code itemsType}을 소문자화해 {@code typeToSchema}에 넘기므로
   * {@code {type: array, items: {type: string, enum: [...]}}}이 정확히 나온다.
   */
  public static FieldDescriptor codeArrayField(
      String path, List<String> allowedValues, String description) {
    return fieldWithPath(path)
        .type(JsonFieldType.ARRAY)
        .attributes(key("itemsType").value("enum"), key("enumValues").value(allowedValues))
        .description(description);
  }

  public static FieldDescriptor optCodeArrayField(
      String path, List<String> allowedValues, String description) {
    return codeArrayField(path, allowedValues, description).optional();
  }

  /**
   * 배열 원소가 enum 코드값인 경우. {@link #enumField}의 배열판이라 상수가 바뀌면 문서가 따라간다(드리프트 0).
   *
   * <p>{@link EnumFields}는 스칼라 경로만 다루므로 값을 직접 뽑아 {@link #codeArrayField}에 넘긴다. 값은 Jackson 기본 직렬화와
   * 같은 {@code name()}이다 — 와이어 값이 상수명과 다르면 {@link #codeArrayField}로 직접 나열한다.
   */
  public static FieldDescriptor enumArrayField(
      String path, Class<? extends Enum<?>> enumType, String description) {
    return codeArrayField(path, enumValues(enumType), description);
  }

  /**
   * {@link #enumArrayField}의 optional 판. 요청 필드를 선택으로 낮추면 이걸 써야 OpenAPI 요청 스키마의 {@code required}에서
   * 빠진다.
   *
   * <p><b>자바 애너테이션만 떼고 기술자를 그대로 두면 문서만 조용히 틀린다</b> — 예시 페이로드에 값이 남아 있어 REST Docs는 실패하지 않고, 생성된 스키마만
   * 계속 필수라고 말한다({@code !optional}이 그대로 {@code required}로 옮겨간다).
   */
  public static FieldDescriptor optEnumArrayField(
      String path, Class<? extends Enum<?>> enumType, String description) {
    return enumArrayField(path, enumType, description).optional();
  }

  private static List<String> enumValues(Class<? extends Enum<?>> enumType) {
    return Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();
  }

  /**
   * 성공 응답의 {@code error}(항상 null).
   *
   * <p><b>{@link JsonFieldType#NULL}을 쓰면 안 된다</b> — {@code typeToSchema("null")}이 everit {@code
   * NullSchema}({@code {"type":"null"}})를 만드는데 swagger 역직렬화의 타입 분기에 {@code null}이 없어 프로퍼티가 통째로
   * 사라진다(1단계 실측: 46개 오퍼레이션 전부에서 누락). {@link JsonFieldType#OBJECT} + {@code optional()}이면 {@code
   * {type: object, nullable: true}}로 정상 출력되고, object는 이 필드가 값을 가질 때의 실제 모양(4xx의 {@code {code,
   * message, errors[]}})이라 거짓이 아니다.
   */
  public static FieldDescriptor errorNull() {
    return fieldWithPath("error")
        .type(JsonFieldType.OBJECT)
        .optional()
        .description("에러 정보 — 성공 응답은 항상 null. 실패 시 형태는 이 오퍼레이션의 4xx 응답 스키마 참조");
  }

  /**
   * 공통 실패 래퍼(ADR-0004). 인자를 주지 않으면 {@code error.code}를 타입만 있는 문자열로 둔다.
   *
   * <p>{@code allowedCodes}는 <b>오퍼레이션+status 단위 상수</b>여야 한다 — 같은 status의 스니펫이 서로 다른 코드 배열을 넘기면
   * {@code (path, type)} dedup 때문에 하나만 살아남고 승자가 파일 순회 순서에 좌우된다. {@code ErrorCode} 전체 카탈로그(50개 초과)는
   * 넘기지 않는다. 오히려 못 읽는다.
   */
  /**
   * 실패 봉투에 <b>그 오퍼레이션 전용 {@code error.details.*}</b>를 끼운 변형(#270).
   *
   * <p>공용 {@link #errorFields}에 {@code error.details}를 넣지 않는 이유는 그러면 <b>모든 오퍼레이션</b>의 에러 스키마에 그 키가
   * 생기기 때문이다 — 런타임에는 나가지도 않는 필드가 문서에만 붙는다. 대신 필요한 오퍼레이션이 이 변형으로 자기 키만 선언한다.
   *
   * <p><b>같은 {@code (path, method, status)}를 캡처하는 스니펫은 전부 이 결과를 써야 한다.</b> 응답 스키마는 {@code (path,
   * type)} 기준 dedup·last-wins라, 한쪽만 상세를 선언하면 승자가 파일 순회 순서에 좌우된다(ADR-0017). 그래서 {@code details}가 실리지
   * 않는 케이스도 같은 기술자를 쓰고 {@code jsonPath(...).doesNotExist()} 단정으로 부재를 되메운다.
   */
  public static List<FieldDescriptor> errorFieldsWith(
      List<FieldDescriptor> details, String... allowedCodes) {
    List<FieldDescriptor> descriptors = new ArrayList<>(errorFields(allowedCodes));
    descriptors.add(
        fieldWithPath("error.details")
            .type(JsonFieldType.OBJECT)
            .optional()
            .description("코드별 부가 데이터 — 값이 없으면 null이 아니라 필드 자체가 생략된다"));
    descriptors.addAll(details);
    return List.copyOf(descriptors);
  }

  public static List<FieldDescriptor> errorFields(String... allowedCodes) {
    List<FieldDescriptor> descriptors = new ArrayList<>();
    descriptors.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 실패 응답은 항상 false"));
    descriptors.add(
        fieldWithPath("data").type(JsonFieldType.OBJECT).optional().description("실패 응답은 항상 null"));
    descriptors.add(field("error", JsonFieldType.OBJECT, "에러 정보"));
    descriptors.add(
        allowedCodes.length == 0
            ? field("error.code", JsonFieldType.STRING, "에러 식별 코드(UPPER_SNAKE_CASE) — 클라이언트 분기 기준")
            : codeField(
                "error.code", List.of(allowedCodes), "에러 식별 코드(UPPER_SNAKE_CASE) — 클라이언트 분기 기준"));
    descriptors.add(
        field("error.message", JsonFieldType.STRING, "사람이 읽는 설명(민감정보 미포함, message로 분기 금지)"));
    descriptors.add(
        field(
            "error.errors",
            JsonFieldType.ARRAY,
            "입력 검증 실패 시 필드별 상세 목록. 필드를 특정할 수 없거나 그 외 에러는 빈 배열"));
    descriptors.add(
        optField(
            "error.errors[].field",
            JsonFieldType.STRING,
            "검증에 실패한 요청 필드·쿼리 파라미터·경로 변수 이름(INVALID_INPUT에서만) — 보낸 이름 그대로라 입력 폼에 매핑할 수 있다"));
    descriptors.add(
        optField("error.errors[].reason", JsonFieldType.STRING, "해당 필드의 실패 사유(INVALID_INPUT에서만)"));
    return List.copyOf(descriptors);
  }
}
