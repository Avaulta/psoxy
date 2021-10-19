package co.worklytics.psoxy.impl;

import co.worklytics.psoxy.PseudonymizedIdentity;
import co.worklytics.psoxy.Rules;
import co.worklytics.psoxy.Sanitizer;
import com.google.api.client.http.GenericUrl;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressParser;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressValidator;

import javax.mail.internet.InternetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Log
@RequiredArgsConstructor
public class SanitizerImpl implements Sanitizer {

    final Options options;

    List<Pair<Pattern, List<JsonPath>>> compiledPseudonymizations;
    List<Pair<Pattern, List<JsonPath>>> compiledRedactions;
    List<Pair<Pattern, List<JsonPath>>> compiledEmailHeaderPseudonymizations;

    @Getter(onMethod_ = {@VisibleForTesting})
    Configuration jsonConfiguration;

    public void initConfiguration() {
        //jackson here because it's our common JSON stack, but adds dependency beyond the one pkg'd
        // with JsonPath.
        this.jsonConfiguration = Configuration.defaultConfiguration()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .setOptions(Option.SUPPRESS_EXCEPTIONS); //we specifically want to ignore PATH_NOT_FOUND cases
    }

    List<JsonPath> applicablePaths(List<Pair<Pattern, List<JsonPath>>> rules, String relativeUrl) {
        return rules.stream()
            .filter(compiled -> compiled.getKey().asMatchPredicate().test(relativeUrl))
            .map(Pair::getValue)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    @Override
    public String sanitize(URL url, String jsonResponse) {

        if (compiledPseudonymizations == null) {
            compiledPseudonymizations = compile(options.getRules().getPseudonymizations());
        }
        if (compiledRedactions == null) {
            compiledRedactions = compile(options.getRules().getRedactions());
        }
        if (compiledEmailHeaderPseudonymizations == null) {
            compiledEmailHeaderPseudonymizations = compile(options.getRules().getEmailHeaderPseudonymizations());
        }
        if (jsonConfiguration == null) {
            initConfiguration();
        }

        String relativeUrl = url.getPath() + "?" + url.getQuery();

        List<JsonPath> pseudonymizationsToApply =
            applicablePaths(compiledPseudonymizations, relativeUrl);

        List<JsonPath> redactionsToApply = applicablePaths(compiledRedactions, relativeUrl);

        List<JsonPath> emailHeaderPseudonymizationsToApply =
            applicablePaths(compiledEmailHeaderPseudonymizations, relativeUrl);



        if (pseudonymizationsToApply.isEmpty() && redactionsToApply.isEmpty() && emailHeaderPseudonymizationsToApply.isEmpty()) {
            return jsonResponse;
        } else {
            Object document = jsonConfiguration.jsonProvider().parse(jsonResponse);

            for (JsonPath redaction : redactionsToApply) {
                document = redaction
                    .delete(document, jsonConfiguration);
            }

            for (JsonPath pseudonymization : pseudonymizationsToApply) {
                document = pseudonymization
                    .map(document, this::pseudonymizeToJson, jsonConfiguration);
            }

            for (JsonPath pseudonymization : emailHeaderPseudonymizationsToApply) {
                document = pseudonymization
                    .map(document, this::pseudonymizeEmailHeaderToJson, jsonConfiguration);
            }


            return jsonConfiguration.jsonProvider().toJson(document);
        }
    }



    List<PseudonymizedIdentity> pseudonymizeEmailHeader(Object value) {
        if (value == null) {
            return null;
        }

        Preconditions.checkArgument(value instanceof String,"Value must be string");

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
            scope = "email";

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
            //absence of scope/domain don't mean this identifier ISN'T qualified by them. it just
            // means they're implicit in context, perhaps implied by something at higher level
            //so how do we fill scope in such cases?
            // leave to orchestration layer, which knows the context??
            canonicalValue = value.toString();
            scope = options.getDefaultScopeId();
        }
        if (canonicalValue != null) {
            builder.scope(scope);
            builder.hash(hash(canonicalValue, options.getPseudonymizationSalt(), scope.equals(PseudonymizedIdentity.EMAIL_SCOPE) ? "" : scope));
        }
        return builder.build();
    }

    @VisibleForTesting
    public String pseudonymizeToJson(Object value, Configuration configuration) {
        return configuration.jsonProvider().toJson(pseudonymize(value));
    }

    String pseudonymizeEmailHeaderToJson(Object value, Configuration configuration) {
        return configuration.jsonProvider().toJson(pseudonymizeEmailHeader(value));
    }

    private List<Pair<Pattern, List<JsonPath>>> compile(List<Rules.Rule> rules) {
        return rules.stream()
            .map(configured -> Pair.of(Pattern.compile(configured.getRelativeUrlRegex()),
                configured.getJsonPaths().stream()
                    .map(JsonPath::compile)
                    .collect(Collectors.toList())))
            .collect(Collectors.toList());
    }

    public String hash(String... fragments) {
        // No padding saves us the '=' character
        // https://www.baeldung.com/java-base64-encode-and-decode#2-java-8-base64-encoding-without-padding
        String hash = new String(
            Base64.getEncoder()
                .withoutPadding()
                .encode(DigestUtils.sha256(String.join("", fragments))),
            StandardCharsets.UTF_8);

        // To avoid urlencoding issues (especially with handlebars/template rendering)
        // while not increasing % of collisions, replace base64 non-alphanumeric characters
        // with urlencode unreserved alternatives (plus no padding from before)
        // See: https://handlebarsjs.com/guide/#html-escaping
        // https://en.wikipedia.org/wiki/Base64#Base64_table
        // https://en.wikipedia.org/wiki/Percent-encoding#Types_of_URI_characters
        return StringUtils.replaceChars(hash, "/+", "_.");
    }

    @Override
    public PseudonymizedIdentity pseudonymize(String value) {
        return pseudonymize((Object)  value);
    }

    @Override
    public PseudonymizedIdentity pseudonymize(Number value) {
        return pseudonymize((Object) value);
    }
}
