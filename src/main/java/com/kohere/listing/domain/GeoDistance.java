package com.kohere.listing.domain;

/**
 * WGS84 두 좌표 사이의 하버사인 거리(미터)다.
 *
 * <p><b>반경 필터·거리순 정렬·응답 {@code distanceMeters}가 이 한 벌을 공유한다.</b> 정렬만 다른 근사식을 쓰면 카드에 표시된 거리와 나열 순서가
 * 어긋난다 — 위·경도 차의 제곱합은 경도 1도와 위도 1도를 같은 거리로 취급해 서울 위도에서 동서 방향을 약 1.26배 멀게 평가한다.
 */
public final class GeoDistance {

  private static final double EARTH_RADIUS_METERS = 6_371_000.0;

  private GeoDistance() {}

  /** 두 좌표 사이의 대권 거리(미터)를 반환한다. */
  public static double meters(double lat1, double lng1, double lat2, double lng2) {
    double radLat1 = Math.toRadians(lat1);
    double radLat2 = Math.toRadians(lat2);
    double latDelta = radLat2 - radLat1;
    double lngDelta = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(latDelta / 2.0) * Math.sin(latDelta / 2.0)
            + Math.cos(radLat1)
                * Math.cos(radLat2)
                * Math.sin(lngDelta / 2.0)
                * Math.sin(lngDelta / 2.0);
    double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    return EARTH_RADIUS_METERS * c;
  }
}
