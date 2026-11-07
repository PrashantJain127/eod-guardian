package com.caixa.eodguardian.spring;

import com.caixa.eodguardian.EodGuardian;
import com.caixa.eodguardian.api.ApiFacade;
import com.caixa.eodguardian.audit.AuditSink;
import com.caixa.eodguardian.common.GuardianConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the dependency-free core into Spring. */
@Configuration
public class GuardianConfiguration {

    @Bean
    public GuardianConfig guardianConfig() {
        return GuardianConfig.fromEnvironment();
    }

    @Bean
    public AuditSink auditSink(JdbcTemplate jdbc) {
        return new JdbcAuditSink(jdbc);
    }

    @Bean(destroyMethod = "close")
    public EodGuardian eodGuardian(GuardianConfig config, AuditSink auditSink) {
        return new EodGuardian(config, auditSink);
    }

    @Bean
    public ApiFacade apiFacade(EodGuardian guardian) {
        return new ApiFacade(guardian);
    }
}
