package com.library.log.autoconfigure;

import com.library.log.batch.LogBatchService;
import com.library.log.core.LogClient;
import com.library.log.core.LogIdGenerator;
import com.library.log.core.SnowflakeLogIdGenerator;
import com.library.log.repository.LogBulkRepository;
import com.library.log.repository.LogRepository;
import com.library.log.service.LogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * 로그 라이브러리 스타터 자동 설정.
 *
 * <p>DataSource + Redis 환경에서 로그 코어 빈들을 기본 구성으로 등록한다.
 * 소비 애플리케이션이 동일 타입 빈을 직접 선언하면 자동 설정 빈은 생성되지 않는다.</p>
 *
 * <p>{@code library.log.enabled=false}면 전체 자동 설정을 비활성화할 수 있다.</p>
 */
@AutoConfiguration
@AutoConfigureAfter({
        DataSourceAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class,
        DataRedisAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "library.log", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass({DataSource.class, RedisConnectionFactory.class, RedisTemplate.class})
public class LogAutoConfiguration {

    /**
     * 로그 ID 생성기 기본 빈(Snowflake 기반).
     *
     * @param serverId 서버/노드 식별자
     * @return 기본 {@link LogIdGenerator} 구현체
     */
    @Bean
    @ConditionalOnMissingBean(LogIdGenerator.class)
    @ConditionalOnBean({DataSource.class, RedisConnectionFactory.class})
    public LogIdGenerator logIdGenerator(
            @Value("${server.id:1}") long serverId
    ) {
        return new SnowflakeLogIdGenerator(serverId);
    }

    /**
     * 로그 DB 벌크 삽입 저장소 기본 빈.
     *
     * @param dataSource JDBC DataSource
     * @return {@link LogBulkRepository} 구현체
     */
    @Bean
    @ConditionalOnMissingBean(LogBulkRepository.class)
    @ConditionalOnBean({DataSource.class, RedisConnectionFactory.class})
    public LogBulkRepository logBulkRepository(
            DataSource dataSource
    ) {
        return new LogBulkRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource));
    }

    /**
     * 로그 핵심 서비스 기본 빈.
     *
     * @param logIdGenerator 로그 ID 생성기
     * @param objectMapper JSON 직렬화/역직렬화 매퍼
     * @param logRepository 로그 조회 저장소
     * @param redisTemplate Redis 템플릿
     * @return 기본 {@link LogService}
     */
    @Bean
    @ConditionalOnMissingBean(LogService.class)
    @ConditionalOnBean({DataSource.class, RedisConnectionFactory.class, LogRepository.class, LogBulkRepository.class})
    public LogService logService(
            LogIdGenerator logIdGenerator,
            ObjectMapper objectMapper,
            LogRepository logRepository,
            RedisTemplate<String, String> redisTemplate
    ) {
        return new LogService(logIdGenerator, objectMapper, logRepository, redisTemplate);
    }

    /**
     * 외부 주입용 {@link LogClient} 빈.
     *
     * @param logService 실제 구현 서비스
     * @return {@link LogClient} 인터페이스로 노출된 서비스
     */
    @Bean
    @ConditionalOnMissingBean(LogClient.class)
    @ConditionalOnBean(LogService.class)
    public LogClient logClient(LogService logService) {
        return logService;
    }

    /**
     * 큐 flush 배치 서비스 기본 빈.
     *
     * @param redisTemplate Redis 템플릿
     * @param bulkRepository 벌크 저장소
     * @param objectMapper JSON 매퍼
     * @return 기본 {@link LogBatchService}
     */
    @Bean
    @ConditionalOnMissingBean(LogBatchService.class)
    @ConditionalOnBean({LogService.class, LogBulkRepository.class})
    public LogBatchService logBatchService(
            RedisTemplate<String, String> redisTemplate,
            LogBulkRepository bulkRepository,
            ObjectMapper objectMapper
    ) {
        return new LogBatchService(redisTemplate, bulkRepository, objectMapper);
    }
}
