package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ApiSurfaceContractTest extends MvcContractSupport {
    @Test
    void existingBusinessRoutesRemainAvailableWithTheirOriginalHttpMethods() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        var environment=new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("business-contract",java.util.Map.of("app.account.enabled","true")));
        scanner.setEnvironment(environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var controllers = new ArrayList<Object>();
        for (var bean : scanner.findCandidateComponents("you.v50to.eatwhat.controller")) {
            var constructor = Class.forName(bean.getBeanClassName()).getDeclaredConstructors()[0];
            Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(type -> mock(type)).toArray();
            controllers.add(constructor.newInstance(dependencies));
        }
        var mapping = mvc(controllers.toArray()).getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerMapping.class);
        Set<String> actual = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            Set<String> methods = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name).collect(Collectors.toSet());
            if (methods.isEmpty()) {
                methods = Set.of("ANY");
            }
            for (String path : info.getPatternValues()) {
                for (String method : methods) {
                    actual.add(method + " " + path
                            + " | params=" + info.getParamsCondition().getExpressions()
                            + ";headers=" + info.getHeadersCondition().getExpressions()
                            + ";consumes=" + info.getConsumesCondition().getConsumableMediaTypes()
                            + ";produces=" + info.getProducesCondition().getProducibleMediaTypes());
                }
            }
        });
        String snapshot = new ClassPathResource("contracts/business-routes.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        Set<String> missing = snapshot.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .filter(line -> !actual.contains(line)).collect(Collectors.toCollection(TreeSet::new));
        // New routes are additive. Removing/changing an existing route needs an explicit compatibility decision.
        assertTrue(missing.isEmpty(), () -> "Existing API routes changed: " + missing);
    }
}
