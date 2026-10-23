package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlUiControllerTest {
    @Test void requiresBothManagementAndExplicitUiOptIn(){
        for(String[] values:new String[][]{ {},{"app.control.enabled=true"},{"app.control.ui-enabled=true"} })
            new ApplicationContextRunner().withUserConfiguration(ControlUiController.class).withPropertyValues(values)
                    .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ControlUiController.class));
    }
    @Test void explicitManagementBundleHasTheEntryHandler(){
        new ApplicationContextRunner().withUserConfiguration(ControlUiController.class)
                .withPropertyValues("app.control.enabled=true","app.control.ui-enabled=true")
                .run(c->assertThat(c).hasNotFailed().hasSingleBean(ControlUiController.class));
    }
    @Test void bothEntryPathsRedirectToExactBundleIndex()throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ControlUiController()).build();
        for(String path:new String[]{"/control-ui","/control-ui/"})
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/control-ui/index.html"));
    }
}
