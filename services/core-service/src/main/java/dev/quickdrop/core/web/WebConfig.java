package dev.quickdrop.core.web;

import dev.quickdrop.core.observability.SqlStatementInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SqlStatementInterceptor sqlStatements;

    public WebConfig(SqlStatementInterceptor sqlStatements) {
        this.sqlStatements = sqlStatements;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sqlStatements).addPathPatterns("/api/**");
    }
}
