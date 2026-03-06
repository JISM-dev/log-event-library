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

@AutoConfiguration
@AutoConfigureAfter({
        DataSourceAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class,
        DataRedisAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "library.log", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass({DataSource.class, RedisConnectionFactory.class, RedisTemplate.class})
public class LogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LogIdGenerator.class)
    @ConditionalOnBean({DataSource.class, RedisConnectionFactory.class})
    public LogIdGenerator logIdGenerator(
            @Value("${server.id:1}") long serverId
    ) {
        return new SnowflakeLogIdGenerator(serverId);
    }

    @Bean
    @ConditionalOnMissingBean(LogBulkRepository.class)
    @ConditionalOnBean({DataSource.class, RedisConnectionFactory.class})
    public LogBulkRepository logBulkRepository(
            DataSource dataSource
    ) {
        return new LogBulkRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource));
    }

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

    @Bean
    @ConditionalOnMissingBean(LogClient.class)
    @ConditionalOnBean(LogService.class)
    public LogClient logClient(LogService logService) {
        return logService;
    }

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
