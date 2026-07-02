package ai.conduit.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/** Enables {@code @CreatedDate}/{@code @LastModifiedDate} auditing on documents. */
@Configuration
@EnableMongoAuditing
public class MongoConfig {
}
