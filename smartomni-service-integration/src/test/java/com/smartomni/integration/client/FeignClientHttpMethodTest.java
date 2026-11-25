package com.smartomni.integration.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feign runs on its default HttpURLConnection client here, which rejects PATCH at runtime ("Invalid HTTP method")
 * while unit tests with mocked clients still pass. The TikTok PACKAGE_UPDATE tracking call failed that way.
 */
@DisplayName("Feign clients never use PATCH")
class FeignClientHttpMethodTest {

    @Test
    void noFeignClientMethodUsesPatch() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));

        List<String> clients = new ArrayList<>();
        List<String> patchMethods = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.smartomni.integration")) {
            Class<?> client = Class.forName(definition.getBeanClassName());
            clients.add(client.getSimpleName());
            for (Method method : client.getMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null && Arrays.asList(mapping.method()).contains(RequestMethod.PATCH)) {
                    patchMethods.add(client.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertTrue(clients.contains("OrderServiceClient"), "scan found no Feign clients: " + clients);
        assertFalse(!patchMethods.isEmpty(), "Feign PATCH is not supported by the default client: " + patchMethods);
    }
}
