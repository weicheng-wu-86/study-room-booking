package com.studyroom.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 基础设施 Bean：系统时钟、密码加密器、MyBatis-Plus 分页插件、定时任务开关。
 */
@Configuration
@MapperScan("com.studyroom.mapper")
public class AppConfig {

    /**
     * 所有业务代码通过注入的 Clock 取“当前时间”，而不是直接调用 LocalDateTime.now()。
     * 这样测试时可以替换成可调的时钟，验证“超时未签到”等与时间有关的规则。
     */
    @Bean
    public Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.getZone()));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /** 定时任务开关：测试环境关闭自动扫描，由测试代码手动触发 */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "studyroom.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
