package vibecloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(S3Config.S3Properties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties properties) {
        var serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccessEnabled())
                .build();

        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())
                ))
                .serviceConfiguration(serviceConfiguration);

        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }

        return builder.build();
    }

    @ConfigurationProperties(prefix = "app.s3")
    public record S3Properties(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            String bucket,
            boolean pathStyleAccessEnabled
    ) {
        public S3Properties {
            region = StringUtils.hasText(region) ? region : "us-east-1";
            accessKey = StringUtils.hasText(accessKey) ? accessKey : "minioadmin";
            secretKey = StringUtils.hasText(secretKey) ? secretKey : "minioadmin";
            bucket = StringUtils.hasText(bucket) ? bucket : "vibecloud";
        }
    }
}
