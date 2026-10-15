package com.opsflow.testsupport;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Initializes the MyBatis-Plus lambda metadata normally populated by mapper
 * scanning in a running Spring application. Pure Mockito service tests do not
 * start that application context, but they still build lambda query wrappers.
 */
public final class MyBatisPlusTableInfoExtension implements BeforeAllCallback {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));

        for (var candidate : scanner.findCandidateComponents("com.opsflow")) {
            String className = candidate.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> entityType = ClassUtils.forName(className, getClass().getClassLoader());
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, className);
            assistant.setCurrentNamespace(className);
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
    }
}
