package com.avaulta.gateway.pseudonyms;

import java.util.function.Function;

/**
 *
 */
public interface ReversiblePseudonymStrategy {


    /**
     * @param identifier       to pseudonymize
     * @param canonicalization used to consistently pseudonymize identifiers that are 'canonically
     *                         equivalent'; not byte-wise equal, but are intended to reference
     *                         the same entity - differences are formatting
     * @return base64-url-encoding of pseudonym + key for reversing that pseudonym
     * NOTE: ability to reverse may depend on state of this implementation, eg, secrets that
     * it holds, passage of time, etc.
     */
    byte[] getReversiblePseudonym(String identifier, Function<String, String> canonicalization);

    /**
     *
     * @param reversiblePseudonym ciphertext, if it was created with this PseudonymizationStrategy
     * @return plaintext that was originally passed to this EncryptionStrategy
     */
    String getIdentifier(byte[] reversiblePseudonym);


}