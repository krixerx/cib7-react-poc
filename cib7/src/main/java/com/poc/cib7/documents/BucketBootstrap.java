package com.poc.cib7.documents;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Idempotent on-startup setup against RustFS:
 *
 * <ol>
 *   <li>Create the bucket if it doesn't exist.</li>
 *   <li>Apply a CORS policy so the browser's preflight on direct
 *       PUT / GET succeeds.</li>
 *   <li>Apply a lifecycle rule that expires {@code pending/} keys after
 *       1 day — handles abandoned uploads where the applicant picked a
 *       file but never submitted the form.</li>
 * </ol>
 *
 * <p>Runs after the engine is up; any failure is logged loudly but does
 * NOT crash the application — RustFS may be temporarily unhealthy and the
 * BPMN tasks would surface a more useful error than a startup crash.
 */
@Component
public class BucketBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(BucketBootstrap.class);

    private final S3Client s3;
    private final S3Properties props;

    public BucketBootstrap(S3Client s3, S3Properties props) {
        this.s3 = s3;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            ensureBucketExists();
            applyCors();
            applyLifecycle();
            LOG.info("RustFS bucket '{}' ready (CORS allowed origin: {})",
                    props.getBucket(), props.getCorsAllowedOrigin());
        } catch (Exception e) {
            LOG.error("Failed to bootstrap RustFS bucket '{}': {}",
                    props.getBucket(), e.getMessage(), e);
        }
    }

    private void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.getBucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.getBucket()).build());
            LOG.info("Created RustFS bucket '{}'", props.getBucket());
        } catch (S3Exception e) {
            // RustFS returns 404 for missing buckets but the SDK doesn't always
            // narrow it to NoSuchBucketException — fall back to the status code.
            if (e.statusCode() == 404) {
                s3.createBucket(CreateBucketRequest.builder().bucket(props.getBucket()).build());
                LOG.info("Created RustFS bucket '{}'", props.getBucket());
            } else {
                throw e;
            }
        }
    }

    private void applyCors() {
        CORSRule rule = CORSRule.builder()
                .allowedOrigins(props.getCorsAllowedOrigin())
                .allowedMethods("PUT", "GET", "HEAD")
                .allowedHeaders("*")
                .exposeHeaders("ETag")
                .maxAgeSeconds(3600)
                .build();
        s3.putBucketCors(PutBucketCorsRequest.builder()
                .bucket(props.getBucket())
                .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                .build());
    }

    private void applyLifecycle() {
        LifecycleRule pendingExpiry = LifecycleRule.builder()
                .id("expire-pending-uploads")
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("pending/").build())
                .expiration(LifecycleExpiration.builder().days(1).build())
                .build();
        s3.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                .bucket(props.getBucket())
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                        .rules(List.of(pendingExpiry))
                        .build())
                .build());
    }
}
