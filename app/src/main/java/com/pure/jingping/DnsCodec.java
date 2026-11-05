package com.pure.jingping;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class DnsCodec {
    private DnsCodec() {
    }

    static Question parseQuestion(byte[] dns) {
        if (dns == null || dns.length < 12 || readU16(dns, 4) != 1) {
            return null;
        }
        int offset = 12;
        StringBuilder name = new StringBuilder();
        for (int labels = 0; labels < 128; labels++) {
            if (offset >= dns.length) {
                return null;
            }
            int labelLength = dns[offset++] & 0xff;
            if (labelLength == 0) {
                int end = offset + 4;
                if (end > dns.length) {
                    return null;
                }
                return new Question(name.toString().toLowerCase(Locale.ROOT), end);
            }
            if ((labelLength & 0xc0) != 0 || labelLength > 63
                    || offset + labelLength > dns.length) {
                return null;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(dns, offset, labelLength, StandardCharsets.US_ASCII));
            offset += labelLength;
        }
        return null;
    }

    static byte[] errorResponse(byte[] query, Question question, int rcode) {
        int questionLength = question.end - 12;
        byte[] response = new byte[12 + questionLength];
        response[0] = query[0];
        response[1] = query[1];
        int flags = 0x8000 | (readU16(query, 2) & 0x0100) | 0x0080 | (rcode & 0x0f);
        putU16(response, 2, flags);
        putU16(response, 4, 1);
        putU16(response, 6, 0);
        putU16(response, 8, 0);
        putU16(response, 10, 0);
        System.arraycopy(query, 12, response, 12, questionLength);
        return response;
    }

    static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }

    static final class Question {
        final String name;
        final int end;

        Question(String name, int end) {
            this.name = name;
            this.end = end;
        }
    }
}
