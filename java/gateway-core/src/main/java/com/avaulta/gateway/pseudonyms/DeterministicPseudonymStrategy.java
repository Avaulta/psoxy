package com.avaulta.gateway.pseudonyms;

import java.util.function.Function;

public interface DeterministicPseudonymStrategy {



    /**
     * @param identifier       to pseudonymize
     * @param canonicalization
     * @return pseudonym (deterministic pseudonym)
     */
    byte[] getPseudonym(String identifier, Function<String, String> canonicalization);


    /**
     * @return length, in bytes, of pseudonyms generated by this strategy
     *
     * q: too coupled to hash-based implementation?
     */
    int getPseudonymLength();
}
