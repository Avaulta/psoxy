package com.avaulta.gateway.pseudonyms.impl;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

class PseudonymizationStrategyDelimImplTest {

    PseudonymizationStrategyDelimImpl encryptionStrategy;

    @SneakyThrows
    @BeforeEach
    void setUp() {
        encryptionStrategy = new PseudonymizationStrategyDelimImpl("salt", TestUtils.testKey());
    }


    @Test
    void roundtrip() {

        String encrypted = encryptionStrategy.getKeyedPseudonym("blah", Function.identity());
        assertNotEquals("blah", encrypted);

        //something else shouldn't match
        String encrypted2 = encryptionStrategy.getKeyedPseudonym("blah2", Function.identity());
        assertNotEquals(encrypted2, encrypted);

        String decrypted = encryptionStrategy.getIdentifier(encrypted);
        assertEquals("blah", decrypted);
    }

    @Test
    void decrypt() {
        //given 'secret' and 'salt' the same, should be able to decrypt
        // (eg, our key-generation isn't random and nothing has any randomized state persisted
        //  somehow between tests)

        assertEquals("blah",
            encryptionStrategy.getIdentifier("NHXWS5CZDysDs3ETExXiMZxM2DfffirkjgmA64R9hCc:px2zWz7DreFvh8fEg1GkGA"));
    }
}