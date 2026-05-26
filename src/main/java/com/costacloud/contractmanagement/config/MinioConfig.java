package com.costacloud.contractmanagement.config;

import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Bean
    public MinioClient minioClient() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        boolean exists = client.bucketExists(
            BucketExistsArgs.builder().bucket(bucketName).build()
        );
        if (!exists) {
            client.makeBucket(
                MakeBucketArgs.builder().bucket(bucketName).build()
            );
        }

        client.setBucketVersioning(SetBucketVersioningArgs.builder()
                .bucket(bucketName)
                .config(new VersioningConfiguration(
                        VersioningConfiguration.Status.ENABLED, null))
                .build());

        return client;
    }

    @Bean
    public CustomMinioClient customMinioClient() {
        MinioAsyncClient asyncClient = MinioAsyncClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        return new CustomMinioClient(asyncClient);
    }
}
