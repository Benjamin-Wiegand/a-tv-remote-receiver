package io.benwiegand.atvremote.receiver.stuff;

import android.util.Log;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * allows translating identifiers that might be private/sensitive into randomly generated UUIDs.
 * intended to be used in conjunction with a second hashmap to resolve the UUIDs to the things they represent.
 * for example, playback session tokens are translated using this, since they are both sensitive and the only unique identifier of the playback session.
 */
public class AnonymousUUIDTranslator<T> {
    private static final String TAG = AnonymousUUIDTranslator.class.getSimpleName();

    private final Map<T, UUID> identifierMap = new ConcurrentHashMap<>();

    public UUID getUUIDOrRegister(T identifier) {
        UUID uuid = UUID.randomUUID();
        UUID existingUUID = identifierMap.putIfAbsent(identifier, uuid);
        return existingUUID != null ? existingUUID : uuid;
    }

    /**
     * get the mapped uuid for the given identifier.
     * @param identifier the identifier
     * @return the uuid that represents it, or null if it isn't registered
     */
    public UUID uuidOf(T identifier) {
        return identifierMap.get(identifier);
    }

    /**
     * registers an identifier to give it a uuid mapping.
     * if a mapping already exists, it logs a warning and returns the existing mapping.
     * @param identifier the identifier
     * @return the new uuid it's mapped to
     */
    public UUID register(T identifier) {
        UUID uuid = UUID.randomUUID();
        UUID existingUUID = identifierMap.putIfAbsent(identifier, uuid);
        if (existingUUID != null) {
            Log.w(TAG, "identifier was already mapped to uuid: " + uuid);
            return existingUUID;
        }
        return uuid;
    }

    /**
     * unregisters an identifier to delete its uuid mapping.
     * if the mapping doesn't exist, a warning is logged and nothing happens.
     * @param identifier the identifier
     */
    public void unregister(T identifier) {
        UUID existingUUID = identifierMap.remove(identifier);
        if (existingUUID == null) Log.w(TAG, "attempt to unregister an identifier that wasn't registered");
    }
}
