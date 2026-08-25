package com.kohere.listing.domain.image;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 수정 요청이 참조한 사진 키 묶음이다. 등록과 달리 <b>임시 키와 기존 확정 키가 섞여</b> 온다.
 *
 * <p>수정 화면이 가진 것은 파일이 아니라 이미 올라간 사진의 참조뿐이라, 다섯 장 중 한 장만 바꾸는 요청은 "네 장은 그대로, 한 장은 새로 올린 것"을 표현할 수 있어야
 * 한다. 그래서 같은 배열에 두 종류를 함께 받고 서버가 접두로 가른다.
 *
 * <p><b>사진의 역할(대표/방)을 무엇이 정하는지는 키 종류마다 다르다.</b>
 *
 * <ul>
 *   <li>임시 키({@code uploads/{landlordId}/…})에는 역할 정보가 없다 — 등록과 같이 <b>요청 JSON의 위치</b>가 정한다.
 *   <li>확정 키({@code listings/{listingId}/…})는 경로에 역할이 이미 박혀 있다 — 그래서 <b>요청 위치와 일치해야</b> 한다.
 * </ul>
 *
 * <p>확정 키의 판정은 접두 비교가 아니라 <b>현재 문서에서 파생한 허용 집합과의 대조</b>다. 그래야 남의 매물 사진({@code listings/{다른 매물}/…})이
 * 접두만 맞다는 이유로 통과하지 않는다 — <b>멤버십 대조가 소유권 검사를 겸한다</b>. 임시 키의 소유권은 등록과 같이 키에 박힌 임대인 식별자가 판정한다.
 *
 * @param coverRefs 지점 대표사진. 요청 순서를 유지하며 첫 값이 대표 이미지가 된다
 * @param roomRefs 방별 사진. {@code roomRefs.get(i)}가 {@code roomOffers[i]}의 사진이다
 */
public record ListingImageEditKeys(List<Ref> coverRefs, List<List<Ref>> roomRefs) {

  public ListingImageEditKeys {
    coverRefs = List.copyOf(coverRefs);
    roomRefs = roomRefs.stream().map(List::copyOf).toList();
  }

  /**
   * 키 하나와 그것이 새로 올린 것인지 여부다.
   *
   * @param key 저장 키
   * @param pending {@code true}면 확정 위치로 복사해야 하고, {@code false}면 이미 확정 위치에 있어 그대로 둔다
   */
  public record Ref(String key, boolean pending) {}

  /**
   * 장수·소유권·자리 일치를 확인해 묶음을 만든다.
   *
   * <p>장수는 <b>병합 후 최종 배열</b> 기준이다 — 유지분과 신규분을 합쳐 커버 1~5장, 방마다 2~5장이어야 한다.
   *
   * @param landlordId 요청자(토큰에서 얻은 값)
   * @param coverKeys 요청의 대표사진 키
   * @param allowedCoverKeys 현재 문서의 대표사진에서 파생한 확정 키 집합
   * @param roomKeys 요청의 방별 사진 키. 바깥 리스트 길이가 방 개수와 같아야 한다
   * @param allowedRoomKeys 방마다의 허용 확정 키 집합. <b>신규 방은 빈 집합</b>이라 임시 키만 통과한다
   * @throws ListingImageException 장수가 범위를 벗어난 경우
   * @throws ListingImageKeyNotFoundException 남의 임시 키이거나, 이 자리의 확정 키가 아닌 경우
   */
  public static ListingImageEditKeys of(
      long landlordId,
      List<String> coverKeys,
      Set<String> allowedCoverKeys,
      List<List<String>> roomKeys,
      List<Set<String>> allowedRoomKeys) {
    requireCount(
        coverKeys.size(), ListingImageKeySet.MIN_COVER_IMAGES, ListingImageKeySet.MAX_COVER_IMAGES);
    roomKeys.forEach(
        keys ->
            requireCount(
                keys.size(),
                ListingImageKeySet.MIN_ROOM_IMAGES,
                ListingImageKeySet.MAX_ROOM_IMAGES));

    List<Ref> covers = classify(coverKeys, landlordId, allowedCoverKeys);
    List<List<Ref>> rooms =
        java.util.stream.IntStream.range(0, roomKeys.size())
            .mapToObj(i -> classify(roomKeys.get(i), landlordId, allowedRoomKeys.get(i)))
            .toList();
    return new ListingImageEditKeys(covers, rooms);
  }

  /** 확정 위치로 복사해야 하는 임시 키만 모은다. 순서는 커버 → 방 순이며 되돌릴 대상을 셀 때 쓴다. */
  public List<String> pendingKeys() {
    return refs().filter(Ref::pending).map(Ref::key).toList();
  }

  private Stream<Ref> refs() {
    return Stream.concat(coverRefs.stream(), roomRefs.stream().flatMap(List::stream));
  }

  /**
   * 키 하나하나를 임시/유지로 가른다.
   *
   * <p>어느 쪽도 아니면 이유를 구분해 알려주지 않는다 — 남의 매물 키인지, 다른 자리의 키인지, 아예 없는 키인지를 응답으로 나누면 그것 자체가 남의 매물 사진의 존재를
   * 확인해 주는 통로가 된다.
   */
  private static List<Ref> classify(List<String> keys, long landlordId, Set<String> allowed) {
    return keys.stream()
        .map(
            key -> {
              if (ListingImageKeys.isPendingOf(key, landlordId)) {
                return new Ref(key, true);
              }
              if (allowed.contains(key)) {
                return new Ref(key, false);
              }
              throw new ListingImageKeyNotFoundException();
            })
        .toList();
  }

  private static void requireCount(int count, int min, int max) {
    if (count < min || count > max) {
      throw ListingImageException.countOutOfRange();
    }
  }
}
