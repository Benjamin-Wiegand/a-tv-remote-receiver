package io.benwiegand.atvremote.receiver.control;

public record ControlSourceErrors(
        String cursorInputException,
        String directionalPadInputException,
        String keyboardInputException,
        String mediaInputException,
        String navigationInputException,
        String scrollInputException,
        String volumeInputException,
        String overlayOutputException
        ) {
}
