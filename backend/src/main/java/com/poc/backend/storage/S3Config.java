package com.poc.backend.storage;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Wires the two AWS SDK clients used to talk to RustFS:
 *
 * <ul>
 *   <li>{@link S3Client} bound to the <em>internal</em> docker-network
 *       endpoint. Used for server-side ops (HeadObject, CopyObject,
 *       DeleteObject, PutObject for engine-generated PDFs) and by
 *       {@link BucketBootstrap} on startup.</li>
 *   <li>{@link S3Presigner} bound to the <em>public</em> endpoint. URLs
 *       it mints are handed to the browser, so the host portion has to
 *       match what the browser can actually reach — never the docker
 *       alias.</li>
 * </ul>
 *
 * <p>Path-style addressing is forced because RustFS, like MinIO, doesn't
 * synthesize per-bucket DNS subdomains. With virtual-host style the
 * SDK would build URLs like {@code http://cib7-documents.localhost:9000}
 * that don't resolve.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(S3Properties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getPublicEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
