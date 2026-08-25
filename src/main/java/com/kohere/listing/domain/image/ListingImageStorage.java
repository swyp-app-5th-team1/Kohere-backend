package com.kohere.listing.domain.image;

import java.util.List;
import java.util.Optional;

/**
 * 매물 사진 저장 포트다. 구현은 infrastructure 계층에 둔다(docs/convention/code-style.md §3-3).
 *
 * <p>응용 계층은 S3의 버킷 이름·리전·SDK 타입을 알지 않고 이 계약만 호출한다. 로컬은 MinIO, 배포 환경은 S3지만 둘 다 같은 어댑터가 endpoint만 바꿔
 * 처리하므로 이 포트 위에서는 구분이 없다(ADR-0041 §5).
 */
public interface ListingImageStorage {

  /**
   * 사진 한 장을 올린다.
   *
   * @param image 저장 키와 내용을 담은 업로드 요청
   * @return 저장된 키와 읽기 URL
   * @throws ListingImageUploadException 저장소를 호출하지 못했거나 저장소가 실패를 응답한 경우
   */
  StoredListingImage upload(ListingImageUpload image);

  /**
   * 임시 위치의 사진을 확정 위치로 복사한다. <b>원본은 남는다</b> — 지우는 것은 호출자 몫이다.
   *
   * <p>존재 확인을 따로 하지 않는 이유는 복사가 겸하기 때문이다. 원본이 없으면 {@link ListingImageKeyNotFoundException}으로 알려주고,
   * 확인과 복사 사이에 만료가 걸릴 수 있어 복사 실패 경로는 어차피 있어야 한다 — 선검사는 그 경로를 없애 주지 못하고 파일 수만큼 왕복만 늘린다(ADR-0041 §4).
   *
   * @param sourceKey 임시 위치의 키
   * @param targetKey 확정 위치의 키
   * @return 확정 위치의 키와 읽기 URL
   * @throws ListingImageKeyNotFoundException 원본이 없는 경우(오타·만료)
   * @throws ListingImageUploadException 그 밖의 저장소 실패
   */
  StoredListingImage copy(String sourceKey, String targetKey);

  /**
   * 읽기 URL에서 저장 키를 되돌린다. {@link StoredListingImage}가 만든 URL의 역함수다.
   *
   * <p>매물 문서에는 URL만 저장되는데(사진 키는 저장하지 않는다) <b>수정 요청이 「그대로 둘 사진」을 확정 키로 가리키므로</b>, 서버가 그 키를 현재 문서와
   * 대조하려면 URL을 키로 되돌려야 한다. 주소 형식은 저장 기술이 정하므로(버킷·CDN 기준) 역변환도 어댑터가 소유한다 — 응용 계층이 URL을 문자열로 자르면 CDN
   * 주소가 바뀔 때 함께 깨진다.
   *
   * @param url 문서에 저장된 읽기 URL
   * @return 저장 키. 이 저장소가 만든 형식이 아니면 빈 값
   */
  Optional<String> keyOf(String url);

  /**
   * 올린 사진을 지운다. 매물 저장이 실패했을 때 되돌리는 보상 경로다(ADR-0041 §4).
   *
   * <p>이미 실패를 처리하는 중에 불리므로 <strong>예외를 던지지 않는다.</strong> 여기서 다시 예외가 나면 원래의 실패 원인이 가려지고, 호출자가 할 수 있는
   * 일도 없다. 지우지 못한 객체는 아무도 참조하지 않는 채 남는데, 그 편이 실패 원인을 잃는 것보다 낫다.
   *
   * @param keys 지울 저장 키 목록. 비어 있으면 아무것도 하지 않는다
   */
  void deleteQuietly(List<String> keys);
}
