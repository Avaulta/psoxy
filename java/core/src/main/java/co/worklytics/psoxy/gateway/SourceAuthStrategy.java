package co.worklytics.psoxy.gateway;

import com.google.auth.Credentials;

import java.util.Optional;

/**
 * encapsulates strategy for how to authenticate with source
 *
 * Options:
 *   - key (eg, Google Service Account key)
 *   - oauth 2.0 client credentials flow ...
 *        - MSFT supposedly, although can use certificate to build assertion; get accessToken that
 *          way instead of canonical 3-legged oauth
 *        - Slack
 *   - JWT assertion (atlassian) - but construction of such assertions seems source dependent? not
 *      really standard, although many are similar
 *
 * doubts:
 *   - whether stuff is completely re-usable, or if it's more variations on a theme
 *      - eg, google service account key *is* actually Oauth, it's just using the service account
 *        key to build assertions to get accessTokens, rather than
 *      - Microsoft/Atlassian have flows that are similar, but differ a bit in the protocol vs
 *        Google and we have to implement more of it
 *   - encapsulating this way might make it *less* reusable. May need two parts:
 *      - CredentialStrategy - how to obtain/maintailln credential
 *      - RequestAuthStrategy - given credential, how to use it to auth the request
 *
 */
public interface SourceAuthStrategy {


    Credentials getCredentials(Optional<String> userToImpersonate);
}
