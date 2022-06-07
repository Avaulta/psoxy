package co.worklytics.psoxy.impl;

import co.worklytics.psoxy.*;
import co.worklytics.psoxy.rules.Rules1;
import co.worklytics.psoxy.rules.Rules2;
import co.worklytics.psoxy.utils.URLUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.MapFunction;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressParser;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressValidator;

import javax.inject.Inject;
import javax.mail.internet.InternetAddress;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Log
@RequiredArgsConstructor //for tests to compile for now
public class SanitizerImpl implements Sanitizer {

    @Getter
    final Options options;

    List<Pair<Pattern, List<JsonPath>>> compiledPseudonymizations;
    List<Pair<Pattern, List<JsonPath>>> compiledRedactions;
    List<Pair<Pattern, List<JsonPath>>> compiledEmailHeaderPseudonymizations;
    List<Pair<Pattern, List<JsonPath>>> compiledPseudonymizationsWithOriginals;
    List<Pattern> compiledAllowedEndpoints;


    List<Pair<Pattern, Rules2.Endpoint>> compiledEndpointRules;
    Map<Rules2.Transform, List<JsonPath>> compiledTransforms = new HashMap<>();

    @AssistedInject
    public SanitizerImpl(HashUtils hashUtils, @Assisted Options options) {
        this.hashUtils = hashUtils;
        this.options = options;
    }

    @Getter(onMethod_ = {@VisibleForTesting})
    @Inject Configuration jsonConfiguration;


    @Inject
    HashUtils hashUtils;

    List<JsonPath> applicablePaths(@NonNull List<Pair<Pattern, List<JsonPath>>> rules,
                                   @NonNull String relativeUrl) {
        return rules.stream()
            .filter(compiled -> compiled.getKey().asMatchPredicate().test(relativeUrl))
            .map(Pair::getValue)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    @Override
    public boolean isAllowed(@NonNull URL url) {
        if (options.getRules2() == null) {
            if (options.getRules().getAllowedEndpointRegexes() == null
                || options.getRules().getAllowedEndpointRegexes().isEmpty()) {
                return true;
            } else {
                if (compiledAllowedEndpoints == null) {
                    compiledAllowedEndpoints = options.getRules().getAllowedEndpointRegexes().stream()
                        .map(Pattern::compile)
                        .collect(Collectors.toList());
                }
                String relativeUrl = URLUtils.relativeURL(url);
                return compiledAllowedEndpoints.stream().anyMatch(p -> p.matcher(relativeUrl).matches());
            }
        } else {
            if (compiledAllowedEndpoints == null) {
                compiledAllowedEndpoints = options.getRules2().getEndpoints().stream()
                    .map(Rules2.Endpoint::getPathRegex)
                    .map(Pattern::compile)
                    .collect(Collectors.toList());
            }
            String relativeUrl = URLUtils.relativeURL(url);

            return options.getRules2().getAllowAllEndpoints() ||
                compiledAllowedEndpoints.stream().anyMatch(p -> p.matcher(relativeUrl).matches());
        }
    }


    @Override
    public String sanitize(@NonNull URL url, @NonNull String jsonResponse) {
        //extra check ...
        if (!isAllowed(url)) {
            throw new IllegalStateException(String.format("Sanitizer called to sanitize response that should not have been retrieved: %s", url.toString()));
        }
        if (StringUtils.isEmpty(jsonResponse)) {
            // Nothing to do
            return jsonResponse;
        }

        if (getOptions().getRules2() == null) {
            return legacyTransform(url, jsonResponse);
        } else {
            return transform(url, jsonResponse);
        }
    }


    String transform(@NonNull URL url, @NonNull String jsonResponse) {
        if (compiledEndpointRules == null) {
            compiledEndpointRules = options.getRules2().getEndpoints().stream()
                .map(endpoint -> Pair.of(Pattern.compile(endpoint.getPathRegex()), endpoint))
                .collect(Collectors.toList());
        }

        String relativeUrl = URLUtils.relativeURL(url);
        Optional<Pair<Pattern, Rules2.Endpoint>> matchingEndpoint = compiledEndpointRules.stream()
            .filter(compiledEndpoint -> compiledEndpoint.getKey().asMatchPredicate().test(relativeUrl))
            .findFirst();

        return matchingEndpoint.map(match -> {

            Object document = jsonConfiguration.jsonProvider().parse(jsonResponse);

            for (Rules2.Transform transform : match.getValue().getTransforms()) {
                document = applyTransform(transform, document);
            }
            return jsonConfiguration.jsonProvider().toJson(document);
        }).orElse(jsonResponse);
    }


    Object applyTransform(Rules2.Transform transform, Object document ) {
        List<JsonPath> paths = compiledTransforms.computeIfAbsent(transform,
            t -> t.getJsonPaths().stream()
                .map(JsonPath::compile)
                .collect(Collectors.toList()));

        if (transform instanceof Rules2.Redact) {
            for (JsonPath path : paths) {
                document = path.delete(document, jsonConfiguration);
            }
        } else if (transform instanceof Rules2.Pseudonymize) {

            //curry the defaultScopeId from the transform into the pseudonymization method
            MapFunction f =
                ((Rules2.Pseudonymize) transform).getIncludeOriginal() ? this::pseudonymizeWithOriginalToJson : this::pseudonymizeToJson;
            for (JsonPath path : paths) {
                document = path.map(document, f, jsonConfiguration);
            }

        } else if (transform instanceof Rules2.PseudonymizeEmailHeader) {
            for (JsonPath path : paths) {
                document = path.map(document, this::pseudonymizeEmailHeaderToJson, jsonConfiguration);
            }
        } else {
            throw new IllegalArgumentException("Unknown transform type: " + transform.getClass().getName());
        }
        return document;
    }



    String legacyTransform(@NonNull URL url, @NonNull String jsonResponse) {        //q: move this stuff to initialization / DI provider??
        if (compiledPseudonymizations == null) {
            compiledPseudonymizations = compile(options.getRules().getPseudonymizations());
        }
        if (compiledRedactions == null) {
            compiledRedactions = compile(options.getRules().getRedactions());
        }
        if (compiledEmailHeaderPseudonymizations == null) {
            compiledEmailHeaderPseudonymizations =
                compile(options.getRules().getEmailHeaderPseudonymizations());
        }
        if (compiledPseudonymizationsWithOriginals == null) {
            compiledPseudonymizationsWithOriginals =
                compile(options.getRules().getPseudonymizationWithOriginals());
        }

        String relativeUrl = URLUtils.relativeURL(url);

        List<JsonPath> pseudonymizationsToApply =
            applicablePaths(compiledPseudonymizations, relativeUrl);

        List<JsonPath> redactionsToApply = applicablePaths(compiledRedactions, relativeUrl);

        List<JsonPath> emailHeaderPseudonymizationsToApply =
            applicablePaths(compiledEmailHeaderPseudonymizations, relativeUrl);

        List<JsonPath> pseudonymizationWithOriginalsToApply =
            applicablePaths(compiledPseudonymizationsWithOriginals, relativeUrl);


        if (pseudonymizationsToApply.isEmpty()
            && redactionsToApply.isEmpty()
            && emailHeaderPseudonymizationsToApply.isEmpty()
            && pseudonymizationWithOriginalsToApply.isEmpty()) {
            return jsonResponse;
        } else {
            Object document = jsonConfiguration.jsonProvider().parse(jsonResponse);

            for (JsonPath redaction : redactionsToApply) {
                document = redaction
                    .delete(document, jsonConfiguration);
            }

            //TODO: error handling within the map functions. any exceptions thrown within the map
            //      function seem to be suppressed, and an empty [] left as the 'document'.
            // ideas:
            // jsonConfiguration.addEvaluationListeners(); -->

            for (JsonPath pseudonymization : pseudonymizationsToApply) {
                document = pseudonymization
                    .map(document, this::pseudonymizeToJson, jsonConfiguration);
            }

            for (JsonPath pseudonymization : emailHeaderPseudonymizationsToApply) {
                document = pseudonymization
                    .map(document, this::pseudonymizeEmailHeaderToJson, jsonConfiguration);
            }

            for (JsonPath pseudonymization : pseudonymizationWithOriginalsToApply) {
                document = pseudonymization
                    .map(document, this::pseudonymizeWithOriginalToJson, jsonConfiguration);
            }

            return jsonConfiguration.jsonProvider().toJson(document);
        }
    }



    List<PseudonymizedIdentity> pseudonymizeEmailHeader(Object value) {
        if (value == null) {
            return null;
        }

        Preconditions.checkArgument(value instanceof String, "Value must be string");

        if (StringUtils.isBlank((String) value)) {
            return new ArrayList<>();
        } else {
            //NOTE: this does NOT seem to work for lists containing empty values (eg ",,"), which
            // per RFC should be allowed ....
            if (EmailAddressParser.isValidAddressList((String) value, EmailAddressCriteria.DEFAULT)) {
                InternetAddress[] addresses =
                    EmailAddressParser.extractHeaderAddresses((String) value, EmailAddressCriteria.DEFAULT, true);
                return Arrays.stream(addresses)
                    .map(InternetAddress::getAddress)
                    .map(this::pseudonymize)
                    .collect(Collectors.toList());
            } else {
                log.log(Level.WARNING, "Valued matched by emailHeader rule is not valid address list, but not blank");
                return null;
            }
        }
    }


    public PseudonymizedIdentity pseudonymize(Object value) {
        return pseudonymize(value, false);
    }

    public PseudonymizedIdentity pseudonymize(Object value, boolean includeOriginal) {
        if (value == null) {
            return null;
        }

        Preconditions.checkArgument(value instanceof String || value instanceof Number,
            "Value must be some basic type (eg JSON leaf, not node)");

        PseudonymizedIdentity.PseudonymizedIdentityBuilder builder = PseudonymizedIdentity.builder();

        String canonicalValue, scope;
        //q: this auto-detect a good idea? Or invert control and let caller specify with a header
        // or something??
        //NOTE: use of EmailAddressValidator/Parser here is probably overly permissive, as there
        // are many cases where we expect simple emails (eg, alice@worklytics.co), not all the
        // possible variants with personal names / etc that may be allowed in email header values
        if (value instanceof String && EmailAddressValidator.isValid((String) value)) {

            String domain = EmailAddressParser.getDomain((String) value, EmailAddressCriteria.DEFAULT, true);
            builder.domain(domain);
            scope = PseudonymizedIdentity.EMAIL_SCOPE;

            //NOTE: lower-case here is NOT stipulated by RFC
            canonicalValue =
                EmailAddressParser.getLocalPart((String) value, EmailAddressCriteria.DEFAULT, true)
                    .toLowerCase()
                + "@"
                + domain.toLowerCase();

            //q: do something with the personal name??
            // NO --> it is not going to be reliable (except for From, will fill with whatever
            // sender has for the person in their Contacts), and in enterprise use-cases we
            // shouldn't need it for matching
        } else {
            canonicalValue = value.toString();
            scope = options.getDefaultScopeId();
        }

        if (canonicalValue != null) {
            builder.scope(scope);
            builder.hash(hashUtils.hash(canonicalValue, options.getPseudonymizationSalt(), asLegacyScope(scope)));
        }

        if (includeOriginal) {
            builder.original(Objects.toString(value));
        }

        return builder.build();
    }

    //converts 'scope' to legacy value (eg, equivalents to original Worklytics scheme, where no scope
    // meant 'email'
    private String asLegacyScope(@NonNull String scope) {
        return scope.equals(PseudonymizedIdentity.EMAIL_SCOPE) ? "" : scope;
    }

    @VisibleForTesting
    public String pseudonymizeToJson(Object value, @NonNull Configuration configuration) {
        return configuration.jsonProvider().toJson(pseudonymize(value));
    }

    public String pseudonymizeWithOriginalToJson(Object value, @NonNull Configuration configuration) {
        return configuration.jsonProvider().toJson(pseudonymize(value, true));
    }


    String pseudonymizeEmailHeaderToJson(@NonNull Object value, @NonNull Configuration configuration) {
        return configuration.jsonProvider().toJson(pseudonymizeEmailHeader(value));
    }

    private List<Pair<Pattern, List<JsonPath>>> compile(List<Rules1.Rule> rules) {
        return rules.stream()
            .map(configured -> Pair.of(Pattern.compile(configured.getRelativeUrlRegex()),
                configured.getJsonPaths().stream()
                    .map(JsonPath::compile)
                    .collect(Collectors.toList())))
            .collect(Collectors.toList());
    }

    @Override
    public PseudonymizedIdentity pseudonymize(@NonNull String value) {
        return pseudonymize((Object)  value);
    }

    @Override
    public PseudonymizedIdentity pseudonymize(@NonNull Number value) {
        return pseudonymize((Object) value);
    }

}
