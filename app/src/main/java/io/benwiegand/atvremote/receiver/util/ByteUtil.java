package io.benwiegand.atvremote.receiver.util;

import android.os.Build;

import java.util.HexFormat;

public class ByteUtil {

    private static final char[] HEX_DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    private static final char[] HEX_DIGITS_UPPER = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
    public static String hexOf(byte[] input) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            return HexFormat.of().formatHex(input);

        return hexOf(input, "", false);
    }

    public static String hexOf(byte[] input, String separator, boolean upper) {
        char[] digits = upper ? HEX_DIGITS_UPPER : HEX_DIGITS;

        StringBuilder sb = new StringBuilder(input.length * (2 + separator.length()) - separator.length());
        for (int i = 0; i < input.length; i++) {
            byte b = input[i];
            sb.append(digits[(0xF0 & b) >>> 4])
                    .append(digits[0x0F & b]);

            if (i != input.length - 1) sb.append(separator);
        }

        return sb.toString();
    }
}
