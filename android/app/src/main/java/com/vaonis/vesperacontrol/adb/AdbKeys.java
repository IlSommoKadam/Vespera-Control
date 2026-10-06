package com.vaonis.vesperacontrol.adb;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;

/** Chiave RSA del client ADB, nel formato atteso da adbd. */
final class AdbKeys {

    private static final int MODULUS_BYTES = 256;

    private final RSAPrivateCrtKey privateKey;

    private AdbKeys(RSAPrivateCrtKey privateKey) {
        this.privateKey = privateKey;
    }

    static AdbKeys load(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "adbkey.pkcs8");
        if (!file.isFile()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Files.write(file.toPath(), pair.getPrivate().getEncoded());
        }
        byte[] encoded = Files.readAllBytes(file.toPath());
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        if (key.getModulus().bitLength() != 2048) {
            throw new IllegalStateException("Chiave ADB non a 2048 bit");
        }
        return new AdbKeys(key);
    }

    byte[] sign(byte[] token) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(token);
    }

    /** Payload AUTH public key: base64(android pubkey) + " user@host\\0". */
    byte[] publicKeyPacket() {
        byte[] blob = encodeAndroidPublicKey(privateKey.getModulus(), privateKey.getPublicExponent());
        String line = Base64.encodeToString(blob, Base64.NO_WRAP) + " vesperacontrol@android";
        byte[] text = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] packet = new byte[text.length + 1];
        System.arraycopy(text, 0, packet, 0, text.length);
        return packet;
    }

    /**
     * Layout AOSP {@code RSAPublicKey}: 64 word, n0inv, modulus LE, rr LE, exponent.
     */
    private static byte[] encodeAndroidPublicKey(BigInteger modulus, BigInteger exponent) {
        byte[] mod = toFixedLe(modulus, MODULUS_BYTES);
        byte[] rr = toFixedLe(BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8 * 2).mod(modulus), MODULUS_BYTES);
        int n0inv = modulus.modInverse(BigInteger.ONE.shiftLeft(32)).negate().intValue();
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MODULUS_BYTES / 4);
        buf.putInt(n0inv);
        buf.put(mod);
        buf.put(rr);
        buf.putInt(exponent.intValue());
        return buf.array();
    }

    private static byte[] toFixedLe(BigInteger value, int size) {
        byte[] be = value.toByteArray();
        int start = (be.length > 0 && be[0] == 0) ? 1 : 0;
        int len = be.length - start;
        if (len > size) {
            throw new IllegalArgumentException("intero troppo grande");
        }
        byte[] le = new byte[size];
        for (int i = 0; i < len; i++) {
            le[i] = be[start + len - 1 - i];
        }
        return le;
    }
}
