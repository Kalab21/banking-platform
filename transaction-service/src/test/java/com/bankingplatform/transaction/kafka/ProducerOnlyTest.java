package com.bankingplatform.transaction.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service publishes and does not consume, and that is load-bearing.
 *
 * <p>It used to carry a {@code spring.kafka.consumer} block and
 * {@code @EnableKafka} for listeners it does not have. The unused
 * configuration was not merely noise: it made the shared start-up guard report
 * that a malformed record would permanently stop a partition here, which was
 * false, and a reliability alarm that is routinely false is one people learn
 * to scroll past.
 *
 * <p>The configuration is gone, so the guard is quiet for the right reason.
 * This test is the other half of that: adding a listener without restoring an
 * {@code ErrorHandlingDeserializer} would make the guard newly correct and
 * newly ignored. If this fails, the consumer configuration has to come back
 * with it — wrapped deserializer, narrow trusted package, and a
 * malformed-record recovery test.
 */
@DisplayName("transaction-service publishes without consuming")
class ProducerOnlyTest {

    private static final String PACKAGE_SCAN =
            "classpath*:com/bankingplatform/transaction/**/*.class";

    @Test
    @DisplayName("no class declares a Kafka listener")
    void noKafkaListeners() throws Exception {
        assertThat(classesDeclaringListeners())
                .as("a listener here needs the consumer configuration this service no longer has")
                .isEmpty();
    }

    private List<String> classesDeclaringListeners() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadata = new CachingMetadataReaderFactory(resolver);

        List<String> declaring = new ArrayList<>();
        for (Resource resource : resolver.getResources(PACKAGE_SCAN)) {
            if (!resource.isReadable()) {
                continue;
            }
            var reader = metadata.getMetadataReader(resource);
            if (reader.getAnnotationMetadata().hasAnnotatedMethods(KafkaListener.class.getName())) {
                declaring.add(reader.getClassMetadata().getClassName());
            }
        }
        return declaring;
    }
}
