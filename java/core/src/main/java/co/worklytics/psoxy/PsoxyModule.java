package co.worklytics.psoxy;

import co.worklytics.psoxy.gateway.ConfigService;
import co.worklytics.psoxy.gateway.ProxyConfigProperty;
import co.worklytics.psoxy.gateway.SourceAuthStrategy;
import co.worklytics.psoxy.gateway.impl.oauth.OAuthRefreshTokenSourceAuthStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import dagger.Module;
import dagger.Provides;


import javax.inject.Named;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * provides implementations for platform-independent dependencies of 'core' module
 *
 */
@Module
public class PsoxyModule {


    @Provides
    ObjectMapper providesObjectMapper() {
        return new ObjectMapper();
    }

    @Provides
    @Named("ForYAML")
    ObjectMapper providesYAMLObjectMapper() {
        return new ObjectMapper(new YAMLFactory());
    }

    @Provides
    Configuration providesJSONConfiguration() {
        //jackson here because it's our common JSON stack, but adds dependency beyond the one pkg'd
        // with JsonPath.
        return Configuration.defaultConfiguration()
            .jsonProvider(new JacksonJsonProvider()) //TODO: DI here (share jackson with rest of app)
            .mappingProvider(new JacksonMappingProvider()) // TODO: DI here (share jackson with rest of app)
            .setOptions(Option.SUPPRESS_EXCEPTIONS); //we specifically want to ignore PATH_NOT_FOUND cases
    }

    @Provides
    JsonFactory jsonFactory() {
        return GsonFactory.getDefaultInstance();
    }

    @Provides
    static Logger logger() {
        return Logger.getLogger(PsoxyModule.class.getCanonicalName());
    }

    @Provides
    static SourceAuthStrategy sourceAuthStrategy(ConfigService configService, Set<SourceAuthStrategy> sourceAuthStrategies) {
        String identifier = configService.getConfigPropertyOrError(ProxyConfigProperty.SOURCE_AUTH_STRATEGY_IDENTIFIER);
        return sourceAuthStrategies
            .stream()
            .filter(impl -> Objects.equals(identifier, impl.getConfigIdentifier()))
            .findFirst()
            .orElseThrow(() -> new Error("No SourceAuthStrategy impl matching configured identifier: " + identifier));
    }

    @Provides
    static OAuthRefreshTokenSourceAuthStrategy.TokenRequestPayloadBuilder tokenRequestPayloadBuilder(ConfigService configService,
                                                                                                     Set<OAuthRefreshTokenSourceAuthStrategy.TokenRequestPayloadBuilder> payloadBuilders) {
        String identifier =
            configService.getConfigPropertyOrError(OAuthRefreshTokenSourceAuthStrategy.ConfigProperty.GRANT_TYPE);
        return payloadBuilders
            .stream()
            .filter(impl -> Objects.equals(identifier, impl.getGrantType()))
            .findFirst()
            .orElseThrow(() -> new Error("No TokenRequestPayloadBuilder impl supporting oauth grant type: " + identifier));
    }


}