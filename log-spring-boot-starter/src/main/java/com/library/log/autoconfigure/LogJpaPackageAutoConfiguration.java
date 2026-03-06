package com.library.log.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;

/**
 * 로그 엔티티 패키지를 JPA auto-configuration 스캔 대상에 등록하는 설정.
 *
 * <p>소비 애플리케이션의 기본 컴포넌트 스캔 범위 밖에 로그 엔티티가 있어도
 * JPA 메타모델에서 누락되지 않도록 {@code com.library.log} 패키지를 강제 등록한다.</p>
 */
@AutoConfiguration
@AutoConfigureBefore({
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
})
@AutoConfigurationPackage(basePackages = "com.library.log")
public class LogJpaPackageAutoConfiguration {
}
