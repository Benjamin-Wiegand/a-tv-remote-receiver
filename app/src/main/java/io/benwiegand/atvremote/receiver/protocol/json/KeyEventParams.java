package io.benwiegand.atvremote.receiver.protocol.json;

import io.benwiegand.atvremote.receiver.protocol.KeyEventType;

public record KeyEventParams(int keyCode, KeyEventType type) {

}
