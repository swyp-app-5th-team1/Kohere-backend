// Swagger UI 부트스트랩 — 기본 petstore 대신 우리 OpenAPI 명세를 가리킨다(ADR-0016).
// webjar 기본 swagger-initializer.js 를 빌드 시 이 파일로 대체한다(build.gradle prepareSwaggerUi).

// 그룹 표시 순서. com.kohere.docs.ApiDocsTags 의 상수와 일치시킨다(#151).
// 태그를 추가하면 여기에도 넣어야 정렬된다 — 목록에 없으면 맨 뒤로 밀린다.
// 목록에 없는 태그는 뒤로 밀고 그들끼리 사전순으로 정렬한다.
const TAG_ORDER = [
  "Auth",
  "Users",
  "Diagnosis",
  "Bookings",
  "Chats",
  "Quiz",
  "LifeTips",
  "Listings",
];

window.onload = function () {
  window.ui = SwaggerUIBundle({
    url: "openapi3.yaml",
    dom_id: "#swagger-ui",
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    layout: "StandaloneLayout",
    tagsSorter: function (a, b) {
      var ia = TAG_ORDER.indexOf(a);
      var ib = TAG_ORDER.indexOf(b);
      if (ia === -1 && ib === -1) return a.localeCompare(b);
      if (ia === -1) return 1;
      if (ib === -1) return -1;
      return ia - ib;
    },
    // API 가 많아 원하는 것을 찾기 어렵다는 문제(#151-6) 보완 — 상단 태그 필터 입력창
    filter: true,
    // enum·nullable 이 Example Value 가 아니라 Model 뷰에서 바로 보이게 한다
    defaultModelRendering: "model",
    // 하단 Schemas 덩어리는 접고, 오퍼레이션 안의 모델은 3단계까지 펼친다
    defaultModelsExpandDepth: -1,
    defaultModelExpandDepth: 3,
    validatorUrl: null,
    persistAuthorization: true,
    // try-it-out 을 기본으로 켜지 않는다 — 켜면 요청 본문이 편집기로 바뀌면서
    // Example Value | Schema 토글이 사라져 필수 표시·enum·필드 설명을 볼 자리가 없어진다
    // (ADR-0017 은 "필수/선택은 스키마 required 로 알린다"고 정해 두었다).
    // 실행은 "Try it out" 을 한 번 눌러서 한다.
    tryItOutEnabled: false,
    displayRequestDuration: true,
  });
};
