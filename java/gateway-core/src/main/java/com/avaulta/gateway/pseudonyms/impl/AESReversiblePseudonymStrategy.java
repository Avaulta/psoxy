package com.avaulta.gateway.pseudonyms.impl;

import com.avaulta.gateway.pseudonyms.DeterministicPseudonymStrategy;
import com.avaulta.gateway.pseudonyms.ReversiblePseudonymStrategy;
import lombok.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.function.Function;

@Builder
@RequiredArgsConstructor
public class AESReversiblePseudonymStrategy implements ReversiblePseudonymStrategy {


    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    public static CipherSuite GCM = GenericCipherSuite.builder()
        .cipher("AES/GCM/NoPadding")
        .parameterSpecGenerator( (byte[] deterministicPseudonym) ->
            new GCMParameterSpec(GCM_TAG_LENGTH*8, Arrays.copyOfRange(deterministicPseudonym, 0, GCM_IV_LENGTH)))
        .build();

    static final int CIPHER_BLOCK_SIZE_BYTES = 16; //128-bits; AES uses 128-bit blocks, regardless of key-length


    public static CipherSuite CBC = GenericCipherSuite.builder()
        .cipher("AES/CBC/PKCS5Padding")
        .parameterSpecGenerator((byte[] deterministicPseudonym) ->
            new IvParameterSpec(Arrays.copyOfRange(deterministicPseudonym, 0, CIPHER_BLOCK_SIZE_BYTES)))

        .build();

    interface CipherSuite {

        String getCipher();

        Function<byte[], AlgorithmParameterSpec> getParameterSpecGenerator();
    }


    @Builder
    @Value
    static class GenericCipherSuite implements CipherSuite {

        private String cipher;

        final Function<byte[], AlgorithmParameterSpec> parameterSpecGenerator;
    }

    final CipherSuite cipherSuite;

    @Getter
    final DeterministicPseudonymStrategy deterministicPseudonymStrategy;

    @Getter
    final SecretKeySpec key;

    @SneakyThrows
    Cipher getCipherInstance() {
        return Cipher.getInstance(cipherSuite.getCipher());
    }

    //64-bytes
    @SneakyThrows
    @Override
    public byte[] getReversiblePseudonym(@NonNull String identifier, Function<String, String> canonicalization) {
        Cipher cipher = getCipherInstance();

        byte[] deterministicPseudonym = deterministicPseudonymStrategy.getPseudonym(identifier, canonicalization);

        cipher.init(Cipher.ENCRYPT_MODE, getKey(), cipherSuite.getParameterSpecGenerator().apply(deterministicPseudonym));
        byte[] ciphertext = cipher.doFinal(identifier.getBytes(StandardCharsets.UTF_8));

        return arrayConcat(deterministicPseudonym, ciphertext);
    }

    //q: is there not a lib method for this??
    byte[] arrayConcat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    @SneakyThrows
    @Override
    public String getIdentifier(@NonNull byte[] reversiblePseudonym) {

        byte[] cryptoText = Arrays.copyOfRange(reversiblePseudonym, deterministicPseudonymStrategy.getPseudonymLength(), reversiblePseudonym.length);

        Cipher cipher = getCipherInstance();

        cipher.init(Cipher.DECRYPT_MODE, getKey(), cipherSuite.getParameterSpecGenerator().apply(reversiblePseudonym));

        byte[] plain = cipher.doFinal(cryptoText);
        return new String(plain, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "AESReversiblePseudonymStrategy(" + cipherSuite.getCipher() + ")";
    }


}
