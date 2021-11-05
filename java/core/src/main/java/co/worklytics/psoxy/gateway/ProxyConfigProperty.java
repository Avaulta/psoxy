package co.worklytics.psoxy.gateway;

/**
 * config properties that control basic proxy behavior
 */
public enum ProxyConfigProperty implements ConfigService.ConfigProperty {
    PSOXY_SALT,
    SOURCE,
    SOURCE_AUTH_STRATEGY_IDENTIFIER,
    IDENTIFIER_SCOPE_ID,
    //target API endpoint to forward request to
    TARGET_HOST,
}