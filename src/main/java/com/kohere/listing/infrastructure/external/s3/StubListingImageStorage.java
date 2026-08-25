package com.kohere.listing.infrastructure.external.s3;

import com.kohere.listing.domain.image.ListingImageStorage;
import com.kohere.listing.domain.image.ListingImageUpload;
import com.kohere.listing.domain.image.StoredListingImage;
import java.util.List;
import java.util.Optional;

/**
 * 저장소 없이 기동해야 하는 환경(test·MinIO를 띄우지 않은 로컬)용 {@link ListingImageStorage} 폴백이다.
 *
 * <p>업로드한 척하고 실제 저장소에 쓰던 것과 같은 형태의 URL을 만들어 준다. 등록 흐름의 나머지(검증·키 조립·응답 구조)는 실제와 같은 경로를 타므로, 저장소가 없다는
 * 이유로 등록 API 전체를 못 돌리는 상황을 피한다. {@code app.images.enabled=true}면 {@link S3ListingImageStorage}가 대신
 * 등록된다. 운영에서 쓰지 않는다.
 *
 * <p>버킷에 아무것도 남기지 않으므로 보상 삭제는 할 일이 없다.
 */
public class StubListingImageStorage implements ListingImageStorage {

  private final String publicBaseUrl;

  StubListingImageStorage(String publicBaseUrl) {
    this.publicBaseUrl = publicBaseUrl;
  }

  @Override
  public StoredListingImage upload(ListingImageUpload image) {
    return new StoredListingImage(image.key(), "%s/%s".formatted(publicBaseUrl, image.key()));
  }

  @Override
  public StoredListingImage copy(String sourceKey, String targetKey) {
    // 올린 적이 없으니 옮길 것도 없다. 등록 흐름은 실제와 같은 형태의 URL을 그대로 받는다.
    return new StoredListingImage(targetKey, "%s/%s".formatted(publicBaseUrl, targetKey));
  }

  @Override
  public Optional<String> keyOf(String url) {
    // 실제 저장소와 같은 형태로 만들어 준 URL이므로 역변환도 같은 규칙을 따른다.
    if (url == null) {
      return Optional.empty();
    }
    String prefix = publicBaseUrl + "/";
    if (!url.startsWith(prefix) || url.length() == prefix.length()) {
      return Optional.empty();
    }
    return Optional.of(url.substring(prefix.length()));
  }

  @Override
  public void deleteQuietly(List<String> keys) {
    // 올린 적이 없으니 지울 것도 없다.
  }
}
