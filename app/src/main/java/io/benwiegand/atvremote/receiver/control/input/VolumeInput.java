package io.benwiegand.atvremote.receiver.control.input;

import java.util.Optional;

public interface VolumeInput extends InputHandler {
    void volumeUp();
    void volumeDown();

    /**
     * empty if mute state unknown. In that case use toggleMute()
     * @return Optional with the mute state, or Optional.empty() if unknown
     */
    Optional<Boolean> getMute();
    void setMute(boolean muted);

    void toggleMute();
}
