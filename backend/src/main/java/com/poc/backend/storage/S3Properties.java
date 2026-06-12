package com.poc.backend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.s3.*} block in application.yaml. Holds the two URLs (internal docker
 * network vs browser-visible), the bucket name, the access/secret pair, and the byte cap that
 * downstream code enforces as a signed PUT header.
 */
@ConfigurationProperties(prefix = "app.s3")
public class S3Properties {

  /** Docker-network URL the backend uses. */
  private String endpoint;

  /** Browser-visible URL embedded in presigned URLs. */
  private String publicEndpoint;

  private String bucket;
  private String accessKey;
  private String secretKey;
  private String region;
  private long maxBytes;
  private String corsAllowedOrigin;

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getPublicEndpoint() {
    return publicEndpoint;
  }

  public void setPublicEndpoint(String publicEndpoint) {
    this.publicEndpoint = publicEndpoint;
  }

  public String getBucket() {
    return bucket;
  }

  public void setBucket(String bucket) {
    this.bucket = bucket;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public void setAccessKey(String accessKey) {
    this.accessKey = accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public long getMaxBytes() {
    return maxBytes;
  }

  public void setMaxBytes(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  public String getCorsAllowedOrigin() {
    return corsAllowedOrigin;
  }

  public void setCorsAllowedOrigin(String corsAllowedOrigin) {
    this.corsAllowedOrigin = corsAllowedOrigin;
  }
}
