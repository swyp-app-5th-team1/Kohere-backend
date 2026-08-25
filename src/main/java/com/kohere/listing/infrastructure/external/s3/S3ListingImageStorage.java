package com.kohere.listing.infrastructure.external.s3;

import com.kohere.listing.domain.image.ListingImageKeyNotFoundException;
import com.kohere.listing.domain.image.ListingImageStorage;
import com.kohere.listing.domain.image.ListingImageUpload;
import com.kohere.listing.domain.image.ListingImageUploadException;
import com.kohere.listing.domain.image.StoredListingImage;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3(및 S3 호환 저장소)에 매물 사진을 올리는 어댑터다.
 *
 * <p>로컬 MinIO와 배포 환경 S3를 같은 구현으로 다룬다 — endpoint와 자격증명만 다르고 프로토콜이 같아, 로컬에서도 실제 업로드 경로를 그대로 태울 수
 * 있다(ADR-0041 §5).
 */
@Slf4j
@RequiredArgsConstructor
public class S3ListingImageStorage implements ListingImageStorage {

  private final S3Client s3Client;
  private final ListingImageProperties properties;

  @Override
  public StoredListingImage upload(ListingImageUpload image) {
    PutObjectRequest request =
        PutObjectRequest.builder()
            .bucket(properties.getBucket())
            .key(image.key())
            .contentType(image.contentType().mediaType())
            .contentLength(image.contentLength())
            .build();
    try {
      s3Client.putObject(
          request, RequestBody.fromInputStream(image.content(), image.contentLength()));
    } catch (RuntimeException e) {
      throw new ListingImageUploadException(e);
    }
    return new StoredListingImage(image.key(), toUrl(image.key()));
  }

  /**
   * 임시 위치의 객체를 확정 위치로 복사한다. 바이트가 서버로 내려오지 않고 저장소 안에서 처리된다.
   *
   * <p>{@code sourceBucket}/{@code sourceKey}를 쓴다 — 예전 {@code copySource(String)}은 {@code 버킷/키}를 직접
   * 잇고 URL 인코딩까지 직접 해야 해서 키에 특수문자가 섞이면 깨진다.
   *
   * <p>원본 부재({@code NoSuchKey})만 사용자 오류로 가른다. 이 구분이 없으면 잘못된 키를 보낸 요청이 "서버 장애니 재시도하라"는 502를 받고,
   * 재시도해도 영원히 실패한다.
   */
  @Override
  public StoredListingImage copy(String sourceKey, String targetKey) {
    try {
      s3Client.copyObject(
          CopyObjectRequest.builder()
              .sourceBucket(properties.getBucket())
              .sourceKey(sourceKey)
              .destinationBucket(properties.getBucket())
              .destinationKey(targetKey)
              .build());
    } catch (NoSuchKeyException e) {
      throw new ListingImageKeyNotFoundException(e);
    } catch (RuntimeException e) {
      throw new ListingImageUploadException(e);
    }
    return new StoredListingImage(targetKey, toUrl(targetKey));
  }

  /**
   * 올린 객체를 지운다.
   *
   * <p>한 건씩 지운다. 배치 {@code DeleteObjects}는 왕복이 한 번이지만 <b>요청 하나가 통째로 거절되면 아무것도 지워지지 않고</b>, 본문 체크섬
   * 요구가 S3 호환 구현마다 달라 로컬(MinIO)에서 조용히 실패하는 것을 실제로 확인했다. 보상 경로에서는 한 키의 실패가 나머지를 막지 않는 쪽이 낫다.
   *
   * <p>삭제에 실패해도 예외를 던지지 않고 키를 로그에 남긴다. 남은 객체는 아무 매물도 참조하지 않으므로 사용자에게는 영향이 없고, 나중에 대조 정리로 걷어낼 수 있다.
   */
  @Override
  public void deleteQuietly(List<String> keys) {
    for (String key : keys) {
      try {
        s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
      } catch (RuntimeException e) {
        log.error("매물 사진 보상 삭제 실패 — 참조 없는 객체가 남는다. key={}", key, e);
      }
    }
  }

  @Override
  public Optional<String> keyOf(String url) {
    if (url == null) {
      return Optional.empty();
    }
    String prefix = properties.publicBaseUrl() + "/";
    if (!url.startsWith(prefix) || url.length() == prefix.length()) {
      return Optional.empty();
    }
    return Optional.of(url.substring(prefix.length()));
  }

  /** 버킷이 비공개(OAC)라 읽기 주소는 업로드 주소가 아니라 CDN 기준으로 만든다. */
  private String toUrl(String key) {
    return "%s/%s".formatted(properties.publicBaseUrl(), key);
  }
}
