package io.benwiegand.atvremote.receiver.util;

public class ErrorUtil {
    private static String getStackTraceExceptionLine(Throwable t) {
        return t.getClass().getName() + ": " + t.getMessage();
    }

    private static String getStackTraceElementLine(StackTraceElement element) {
        return "    at "
                + element.getClassName()
                + "."
                + element.getMethodName()
                + "("
                + element.getFileName()
                + ":"
                + element.getLineNumber()
                + ")";
    }

    public static String getLightStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        boolean top = true;

        do {
            if (!top) sb.append("Caused by: ");
            top = false;

            sb.append(getStackTraceExceptionLine(t)).append("\n");

            for (StackTraceElement element : t.getStackTrace()) {
                // filter for my app package
                if (!element.getClassName().startsWith("io.benwiegand.atvremote.receiver")) continue;
                sb.append(getStackTraceElementLine(element)).append("\n");
            }

        } while ((t = t.getCause()) != null);

        return sb.toString();
    }
}
