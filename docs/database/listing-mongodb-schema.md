# Listing MongoDB 상세 스키마 초안

> 기준 데이터: `임시) 매물 상세 수집 가정 데이터.numbers`의 첫 번째 데이터 행(`고시원001`)
>
> 상태: **Draft** — 실제 MongoDB validator·Document 클래스 구현 전 검토용. 한 문서는 건물/매물 전체를 나타내고, 동일한 가격·조건의 방은 `roomOffers[]`에서 수량으로 묶어 관리한다.

## 1. 저장 경계

- MongoDB에는 매물 검색·지도·상세 화면에 필요한 정보만 저장한다.
- `landlordId`는 소유권 검증과 채팅 연결을 위해 유지하되, MySQL `users.id`를 FK 없이 값으로 참조한다.
- Numbers의 빨간 컬럼 중 `contact_name`·`contact_phone`·`contact_kakao`·`contact_languages`는 저장하지 않는다.
- 금액은 Numbers의 만원 단위를 KRW 정수로 변환한다(`30` → `300000`).
- 위치는 GeoJSON `Point`로 저장하고 좌표 순서는 반드시 `[경도, 위도]`를 사용한다.
- 건물 공통정보는 문서 루트에, 가격·계약·성별·방 특징·입주 가능 수량은 `roomOffers[]`에 저장한다.
- 실제 방 번호를 관리할 필요가 생기기 전까지 동일 조건의 방은 개별 저장하지 않고 `totalCount`·`availableCount`로 관리한다.

## 2. 첫 번째 임시 데이터 매핑 예시

아래 예시는 설명을 위해 주석을 포함한 JSONC다. MongoDB에 직접 삽입할 때는 주석을 제거해야 한다.

```javascript
{
  // MongoDB가 생성하는 매물 식별자다. API에서는 문자열 listingId로 변환한다.
  _id: ObjectId("6858e2000000000000000001"),

  // Mongo 문서 구조가 바뀔 때 점진적으로 마이그레이션하기 위한 버전이다.
  schemaVersion: 1,

  // MySQL users.id를 값으로만 참조한다.
  // 예시에서는 매물을 등록한 MySQL 사용자 ID가 1이라고 가정한다.
  landlordId: 1,

  // 매물 카드와 상세 화면에서 사용하는 기본 정보다.
  title: "고시원001",
  type: "GOSIWON",

  // 공개 여부를 boolean 하나보다 명확하게 표현한다.
  // DRAFT, PUBLISHED, PAUSED, DELETED 등의 상태 확장이 가능하다.
  status: "PUBLISHED",

  // 2dsphere 인덱스가 적용되는 지도 검색 전용 GeoJSON 필드다.
  // Numbers의 latitude=37.459471, longitude=126.951422를 [lng, lat]로 저장한다.
  location: {
    type: "Point",
    coordinates: [126.951422, 37.459471]
  },

  // 표시 주소는 지오 좌표와 분리한다. 향후 행정구역 코드 검색에도 사용할 수 있다.
  address: {
    city: "SEOUL",
    district: "GWANAK_GU",
    fullAddress: "서울특별시 Gwanak-gu Sillim-dong 나로 56-15",
    detail: null
  },

  // 지도·상세 화면에서 가까운 교통수단과 도보 시간을 보여준다.
  nearestTransit: {
    type: "SUBWAY",
    name: "Seoul Nat'l Univ.",
    walkMinutes: 5
  },

  // 집주인이 입력한 주변 시설 안내를 그대로 저장해 상세 화면에 표시한다.
  // 검색·필터 조건으로 사용하지 않으므로 인덱스나 별도 구조화는 하지 않는다.
  nearbyPlacesDescription: "CU, 스타벅스, 약국, 헬스장",

  // 학교 검색과 추천 조건에 사용한다. 운영 전 정식 학교 코드 사전과 연결해야 한다.
  nearbyUniversityCodes: ["SNU", "CAU", "SOONGSIL"],

  // 건물 자체의 물리적 특성이다.
  building: {
    type: "VILLA",
    usedFloorMin: 1,
    usedFloorMax: 2,
    totalFloors: 4,
    parkingAvailable: true,
    elevatorAvailable: true,
    heatingSystem: "CENTRAL"
  },

  // 건물 또는 모든 방 상품에 공통으로 적용되는 입주·운영 정책이다.
  // 방마다 달라질 수 있는 성별·개인 욕실·2인실 조건은 roomOffers[]에 둔다.
  propertyPolicies: {
    arcRequired: false,
    residentRegistrationAvailable: true,
    studySuitable: true,
    mealsProvided: true,

    // 원본 english_available=false를 정본으로 사용했다.
    // extra_notes에는 영어 안내 가능이라고 적혀 있어 적재 전 확인이 필요하다.
    englishAvailable: false
  },

  // 상세 화면의 시설 정보를 기능별 배열로 구분한다.
  facilities: {
    laundry: ["COIN_LAUNDRY"],
    livingAmenities: ["WIFI", "TV", "SOFA"],
    securityFeatures: ["CCTV", "ENTRANCE_DOOR_LOCK", "FIRE_EXTINGUISHER"],
    commonSpaces: [
      { type: "SHARED_TOILET", count: 6 },
      { type: "STUDY_ROOM", count: null }
    ],
    providedSupplies: ["BEDDING", "SEASONING", "TISSUE"]
  },

  // 동일한 가격·계약조건·방 특징을 가진 실제 방들을 하나의 방 상품으로 묶는다.
  // 가격이나 개인 욕실 여부 등이 다르면 별도의 roomOffer를 추가한다.
  roomOffers: [
    {
      // 문서 내부 방 상품 식별자다. API에서는 문자열 roomOfferId로 변환한다.
      roomOfferId: ObjectId("6858e2000000000000000101"),

      // 사용자에게 노출되는 방 상품명과 운영 상태다.
      name: "스탠다드 1인실",
      status: "ACTIVE",
      rentalType: "MONTHLY_RENT",

      // 집주인이 이 방 상품에 설정한 실제 단일 가격이다.
      // 앱 가격 필터의 최소·최대 금액은 조회 조건일 뿐 여기에 저장하지 않는다.
      pricing: {
        monthlyRent: 300000,
        deposit: 300000,
        maintenanceFee: 0,
        currency: "KRW"
      },

      // 이 방 상품에 적용되는 최소·최대 계약기간과 환불 안내다.
      contract: {
        minStayMonths: 2,
        maxStayMonths: 6,
        refundPolicy: {
          code: "FULL_REFUND_BEFORE_7_DAYS",
          description: "입주 7일 전 취소 시 전액 환불"
        }
      },

      // 동일 조건의 실제 방 수량과 현재 계약 가능한 수량을 관리한다.
      // totalCount=10은 구조 설명용 가정값이고, availableCount=0은 원본 availableNow=false를 반영했다.
      inventory: {
        totalCount: 10,
        availableCount: 0,

        // 지금 가능한 방이 없을 때 가장 빠른 예상 입주일이다.
        // 원본에 날짜가 없어 null이며 실제 등록 과정에서는 집주인이 입력한다.
        nextAvailableFrom: null
      },

      // 방·층별로 달라질 수 있는 성별 정책이다.
      genderPolicy: "FEMALE_ONLY",

      // 이 방 상품 자체의 시설·형태다. 공용시설은 루트 facilities에 둔다.
      features: [
        "SINGLE_ROOM",
        "PRIVATE_REFRIGERATOR",
        "MICROWAVE",
        "ELECTRIC_KETTLE"
      ],

      // 필터가 같은 roomOffer 안의 가격·성별·특징을 함께 검사할 수 있도록
      // 정본 필드와 propertyPolicies에서 서버가 생성하는 검색용 태그다.
      filterTags: [
        "FEMALE_ONLY",
        "RESIDENT_REGISTRATION",
        "NO_MAINTENANCE_FEE",
        "MEALS_PROVIDED"
      ],

      // 방 상품 전용 이미지다. 건물 공용 이미지는 루트 imageUrls에 둔다.
      roomImageUrls: []
    }
  ],

  // 상세 화면의 '모든 방 특징' 영역을 위한 활성 roomOffer들의 태그 합집합이다.
  // 표시용 요약값이며 실제 필터는 반드시 roomOffers에 $elemMatch를 사용한다.
  featureSummary: [
    "FEMALE_ONLY",
    "RESIDENT_REGISTRATION",
    "NO_MAINTENANCE_FEE",
    "MEALS_PROVIDED"
  ],

  // 다국어 상세 설명을 담당한다.
  descriptions: {
    ko: "지하철역 도보 5분 이내, 교통이 편리한 위치의 코리빙 하우스입니다.",
    en: "A well-maintained gosiwon welcoming foreign residents. English support available."
  },

  // 자유 입력 주의사항이다. 검색 조건으로 사용하지 않는다.
  extraNotes: "외국인 환영, 영어 안내 가능합니다.",

  // 이미지 저장소 URL을 노출 순서대로 보관한다. 첫 항목을 목록 썸네일로 사용한다.
  // 현재 Numbers 데이터에는 이미지 정보가 없어 빈 배열이다.
  imageUrls: [],

  // favorites 컬렉션 집계의 조회 최적화용 캐시다.
  favoriteCount: 0,

  // 원본 타임존이 UTC라고 가정한 예시다. 실제 적재 전 원본 타임존을 확인해야 한다.
  createdAt: ISODate("2024-09-15T00:00:00Z"),
  updatedAt: ISODate("2024-10-04T00:35:00Z")
}
```

## 3. 필드 그룹별 책임

| 필드 그룹 | 담당 기능 |
| --- | --- |
| `_id`, `schemaVersion` | 문서 식별과 구조 버전 관리 |
| `landlordId` | MySQL 회원과 연결, 매물 소유권 검증, 임대인 채팅방 생성 |
| `title`, `type`, `status` | 건물 매물 카드 표시, 유형 필터, 공개 상태 제어 |
| `location` | `2dsphere` 기반 bbox·반경·거리순 지도 검색 |
| `address`, `nearestTransit`, `nearbyPlacesDescription`, `nearbyUniversityCodes` | 주소·교통·학교 검색과 주변 시설 안내 표시 |
| `building` | 건물 유형·층수·주차·엘리베이터·난방 표시 및 필터 |
| `propertyPolicies` | 모든 방에 공통인 ARC·전입신고·식사·영어 안내 정책 |
| `facilities` | 상세 화면의 편의·보안·공용시설 정보 |
| `roomOffers` | 동일한 가격·계약·특징의 방 묶음과 판매 가능 수량 관리 |
| `roomOffers[].pricing` | 방 상품의 월세·보증금·관리비 표시, 예산 필터와 가격 정렬 |
| `roomOffers[].contract` | 방 상품의 계약기간과 환불 안내 |
| `roomOffers[].inventory` | 동일 조건 방의 전체 수량·현재 가능 수량·다음 입주일 관리 |
| `roomOffers[].genderPolicy`, `features` | 방 상품별 성별 정책과 개인시설·방 형태 |
| `roomOffers[].filterTags` | 같은 방 상품이 복수 필터 조건을 모두 만족하는지 검색 |
| `featureSummary` | 활성 방 상품들의 특징 합집합을 상세 화면에 표시하는 요약값 |
| `descriptions`, `extraNotes`, `imageUrls` | 다국어 상세 콘텐츠와 이미지 표시 |
| `favoriteCount` | 목록·상세의 찜 수를 빠르게 반환하기 위한 비정규화 캐시 |
| `createdAt`, `updatedAt` | 생성·수정 이력과 정렬 기준 |

## 4. 인덱스 초안

```javascript
// 지도 bbox·반경·거리순 검색
db.listings.createIndex(
  { location: "2dsphere" },
  { name: "listings_location_2dsphere" }
);

// 공개 건물의 매물 유형과 roomOffer 월세 필터
db.listings.createIndex(
  { status: 1, type: 1, "roomOffers.pricing.monthlyRent": 1 },
  { name: "listings_status_type_rent" }
);

// 임대인의 매물 관리 목록
db.listings.createIndex(
  { landlordId: 1, status: 1, updatedAt: -1 },
  { name: "listings_landlord_status_updated" }
);

// 공개 매물의 방 상품 옵션 필터
// 여성 전용·개인 화장실·영어 가능 등의 조건을 같은 roomOffer에서 찾는다.
db.listings.createIndex(
  { status: 1, "roomOffers.filterTags": 1 },
  { name: "listings_status_room_filter_tags" }
);

// 지금 계약 가능한 roomOffer가 있는 건물 검색
db.listings.createIndex(
  { status: 1, "roomOffers.inventory.availableCount": 1 },
  { name: "listings_status_room_available_count" }
);

// ARC가 없는 사용자를 위한 매물 매칭
// 사용자가 ARC를 보유했다면 필요·불필요 매물을 모두 조회할 수 있으므로
// 이 인덱스는 주로 arcRequired=false 조건을 빠르게 찾는 데 사용한다.
db.listings.createIndex(
  { status: 1, "propertyPolicies.arcRequired": 1 },
  { name: "listings_status_arc_required" }
);
```

복수 필터는 합집합인 `featureSummary`가 아니라 동일한 `roomOffer`에 `$elemMatch`를 적용한다.

```javascript
db.listings.find({
  status: "PUBLISHED",
  type: "GOSIWON",
  roomOffers: {
    $elemMatch: {
      status: "ACTIVE",
      "pricing.monthlyRent": { $lte: 500000 },
      "inventory.availableCount": { $gt: 0 },
      filterTags: {
        $all: ["FEMALE_ONLY", "PRIVATE_TOILET", "ENGLISH_AVAILABLE"]
      }
    }
  }
});
```

## 5. 첫 번째 임시 데이터에서 확인된 정제 항목

| 항목 | 원본 값 | 처리 |
| --- | --- | --- |
| 관리비 태그 | `maintenance_fee=0`, `no_maintenance_fee=false` | 금액 필드를 정본으로 보고 `NO_MAINTENANCE_FEE` 파생 |
| 영어 가능 여부 | `english_available=false`, 안내문은 영어 가능 | boolean을 임시 정본으로 사용, 실제 운영 전 확인 필요 |
| 유형과 한글 설명 | `property_type=고시원`, 설명은 코리빙 | 유형 또는 설명 수정 필요 |
| 이미지 | 컬럼 없음 | 건물 `imageUrls=[]`, 방 상품 `roomImageUrls=[]`; 운영 데이터 수집 항목 추가 필요 |
| 방 재고 | `is_available_now`만 존재 | `totalCount`·`availableCount`·`nextAvailableFrom` 수집 항목 추가 필요 |

## 6. 건물과 방 상품 저장 단위

- 한 MongoDB 문서는 주소와 공용시설을 공유하는 건물 매물 하나를 나타낸다.
- 동일한 가격·계약조건·성별 정책·방 특징을 가진 실제 방들은 `roomOffer` 하나로 묶는다.
- `totalCount`는 묶인 실제 방의 전체 수, `availableCount`는 현재 계약 가능한 방의 수다.
- 계약이 확정되면 `availableCount > 0` 조건에서 원자적으로 1 감소시키고, 취소·퇴실로 방이 다시 가능해지면 1 증가시킨다.
- 가격이나 개인 욕실·2인실 여부 등이 다르면 같은 건물 안에 별도의 `roomOffer`를 추가한다.
- 사용자가 선택한 최소·최대 가격은 API 조회 조건이며, 각 `roomOffer`에는 집주인이 정한 실제 단일 가격만 저장한다.
- `featureSummary`는 화면 표시용 합집합이고, 필터는 반드시 같은 `roomOffer`가 가격·재고·옵션을 모두 만족하는지 검사한다.
- 향후 실제 방 번호별 수리·청소·계약 종료일 관리가 필요해지면 내부 관리용 `roomUnits` 컬렉션을 추가하고 `roomOfferId`로 연결한다.
