package co.worklytics.psoxy.storage.impl;

import co.worklytics.psoxy.PsoxyModule;
import co.worklytics.psoxy.Rules;
import co.worklytics.psoxy.Sanitizer;
import co.worklytics.psoxy.SanitizerFactory;
import co.worklytics.test.MockModules;
import co.worklytics.test.TestModules;
import dagger.Component;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CSVFileHandlerTest {

    @Inject
    CSVFileHandler csvFileHandler;

    @Inject
    SanitizerFactory sanitizerFactory;

    @Singleton
    @Component(modules = {
            PsoxyModule.class,
            TestModules.ForFixedClock.class,
            TestModules.ForFixedUUID.class,
            MockModules.ForConfigService.class,
    })
    public interface Container {
        void inject(CSVFileHandlerTest test);
    }

    @BeforeEach
    public void setup() {
        Container container = DaggerCSVFileHandlerTest_Container.create();
        container.inject(this);
    }

    @Test
    @SneakyThrows
    void handle_pseudonymize() {
        final String EXPECTED = "EMPLOYEE_ID,EMPLOYEE_EMAIL,DEPARTMENT,EFFECTIVE_ISOWEEK\r\n" +
                "1,\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"worklytics.co\"\",\"\"hash\"\":\"\"Qf4dLJ4jfqZLn9ef4VirvYjvOnRaVI5tf5oLnM65YOA\"\"}\",Engineering,2020-01-06\r\n" +
                "2,\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"workltyics.co\"\",\"\"hash\"\":\"\"al4JK5KlOIsneC2DM__P_HRYe28LWYTBSf3yWKGm5yQ\"\"}\",Sales,2020-01-06\r\n" +
                "3,\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"workltycis.co\"\",\"\"hash\"\":\"\"BlQB8Vk0VwdbdWTGAzBF.ote1357Ajr0fFcgFf72kdk\"\"}\",Engineering,2020-01-06\r\n" +
                "4,,Engineering,2020-01-06\r\n"; //blank ID

        Sanitizer sanitizer = sanitizerFactory.create(Sanitizer.Options.builder()
                .rules(Rules.builder()
                        .pseudonymization(Rules.Rule.builder()
                                .csvColumns(Collections.singletonList("EMPLOYEE_EMAIL"))
                                .build())
                        .build())
                .pseudonymizationSalt("salt")
                .defaultScopeId("hris")
                .build());

        File inputFile = new File(getClass().getResource("/csv/hris-example.csv").getFile());

        try (FileReader in = new FileReader(inputFile)) {
            byte[] result  = csvFileHandler.handle(in, sanitizer);

            assertEquals(EXPECTED, new String(result));
        }
    }

    @Test
    @SneakyThrows
    void handle_redaction() {
        final String EXPECTED = "EMPLOYEE_ID,EMPLOYEE_EMAIL,EFFECTIVE_ISOWEEK\r\n" +
                "1,\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"worklytics.co\"\",\"\"hash\"\":\"\"Qf4dLJ4jfqZLn9ef4VirvYjvOnRaVI5tf5oLnM65YOA\"\"}\",2020-01-06\r\n" +
                "2,\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"workltyics.co\"\",\"\"hash\"\":\"\"al4JK5KlOIsneC2DM__P_HRYe28LWYTBSf3yWKGm5yQ\"\"}\",2020-01-06\r\n" +
                "3,\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"workltycis.co\"\",\"\"hash\"\":\"\"BlQB8Vk0VwdbdWTGAzBF.ote1357Ajr0fFcgFf72kdk\"\"}\",2020-01-06\r\n" +
                "4,,2020-01-06\r\n"; //blank ID

        Sanitizer sanitizer = sanitizerFactory.create(Sanitizer.Options.builder()
                .rules(Rules.builder()
                        .pseudonymization(Rules.Rule.builder()
                                .csvColumns(Collections.singletonList("EMPLOYEE_EMAIL"))
                                .build())
                        .redaction(Rules.Rule.builder()
                                .csvColumns(Collections.singletonList("DEPARTMENT"))
                                .build())
                        .build())
                .pseudonymizationSalt("salt")
                .defaultScopeId("hris")
                .build());

        File inputFile = new File(getClass().getResource("/csv/hris-example.csv").getFile());

        try (FileReader in = new FileReader(inputFile)) {
            byte[] result  = csvFileHandler.handle(in, sanitizer);

            assertEquals(EXPECTED, new String(result));
        }
    }

    @Test
    @SneakyThrows
    void handle_cased() {
        final String EXPECTED = "EMPLOYEE_ID,AN EMAIL,SOME DEPARTMENT\r\n" +
                "\"{\"\"scope\"\":\"\"hris\"\",\"\"hash\"\":\"\"SappwO4KZKGprqqUNruNreBD2BVR98nEM6NRCu3R2dM\"\"}\",\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"worklytics.co\"\",\"\"hash\"\":\"\"Qf4dLJ4jfqZLn9ef4VirvYjvOnRaVI5tf5oLnM65YOA\"\"}\",Engineering\r\n";

        Sanitizer sanitizer = sanitizerFactory.create(Sanitizer.Options.builder()
                .rules(Rules.builder()
                        .pseudonymization(Rules.Rule.builder()
                                .csvColumns(Arrays.asList("EMPLOYEE_ID", "AN EMAIL"))
                                .build())
                        .build())
                .pseudonymizationSalt("salt")
                .defaultScopeId("hris")
                .build());

        File inputFile = new File(getClass().getResource("/csv/hris-example-headers-w-spaces.csv").getFile());

        try (FileReader in = new FileReader(inputFile)) {
            byte[] result  = csvFileHandler.handle(in, sanitizer);

            assertEquals(EXPECTED, new String(result));
        }
    }

    @Test
    @SneakyThrows
    void handle_quotes() {
        final String EXPECTED = "EMPLOYEE_ID,EMAIL,DEPARTMENT\r\n" +
                "\"{\"\"scope\"\":\"\"hris\"\",\"\"hash\"\":\"\"SappwO4KZKGprqqUNruNreBD2BVR98nEM6NRCu3R2dM\"\"}\",\"{\"\"scope\"\":\"\"email\"\",\"\"domain\"\":\"\"worklytics.co\"\",\"\"hash\"\":\"\"Qf4dLJ4jfqZLn9ef4VirvYjvOnRaVI5tf5oLnM65YOA\"\"}\",\",,,\"\r\n";

        Sanitizer sanitizer = sanitizerFactory.create(Sanitizer.Options.builder()
                .rules(Rules.builder()
                        .pseudonymization(Rules.Rule.builder()
                                .csvColumns(Arrays.asList("EMPLOYEE_ID", "EMAIL"))
                                .build())
                        .build())
                .pseudonymizationSalt("salt")
                .defaultScopeId("hris")
                .build());

        File inputFile = new File(getClass().getResource("/csv/hris-example-quotes.csv").getFile());

        try (FileReader in = new FileReader(inputFile)) {
            byte[] result  = csvFileHandler.handle(in, sanitizer);

            assertEquals(EXPECTED, new String(result));
        }
    }
}