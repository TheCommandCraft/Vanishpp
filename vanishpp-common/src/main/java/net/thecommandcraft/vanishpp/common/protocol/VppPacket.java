package net.thecommandcraft.vanishpp.common.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes packets sent over the vanishpp:proxy plugin messaging channel.
 *
 * Wire format:
 *   [1 byte]  VppMessage ordinal
 *   [4 bytes] payload length in bytes (big-endian int)
 *   [N bytes] UTF-8 JSON payload
 */
public final class VppPacket {

    private VppPacket() {}

    public static byte[] encode(VppMessage type, String jsonPayload) {
        byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(5 + payloadBytes.length);
        DataOutputStream out = new DataOutputStream(baos);
        try {
            out.writeByte(type.ordinal());
            out.writeInt(payloadBytes.length);
            out.write(payloadBytes);
        } catch (IOException e) {
            // ByteArrayOutputStream never throws IOException
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public static DecodedPacket decode(byte[] data) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            int typeOrdinal = in.readUnsignedByte();
            VppMessage[] values = VppMessage.values();
            if (typeOrdinal >= values.length) {
                throw new IllegalArgumentException("Unknown VppMessage ordinal: " + typeOrdinal);
            }
            VppMessage type = values[typeOrdinal];
            int length = in.readInt();
            byte[] payloadBytes = new byte[length];
            in.readFully(payloadBytes);
            String json = new String(payloadBytes, StandardCharsets.UTF_8);
            return new DecodedPacket(type, json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed VppPacket", e);
        }
    }

    public record DecodedPacket(VppMessage type, String jsonPayload) {}
}
