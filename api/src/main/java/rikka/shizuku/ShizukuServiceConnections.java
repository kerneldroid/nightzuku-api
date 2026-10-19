package rikka.shizuku;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

class ShizukuServiceConnections {

    private static final Map<String, ShizukuServiceConnection> CACHE = Collections.synchronizedMap(new HashMap<>());

    @NonNull
    static ShizukuServiceConnection get(Shizuku.UserServiceArgs args) {
        String key = args.tag != null ? args.tag : args.componentName.getClassName();
        synchronized (CACHE) {
            ShizukuServiceConnection connection = CACHE.get(key);
            if (connection == null) {
                connection = new ShizukuServiceConnection(args);
                CACHE.put(key, connection);
            }
            return connection;
        }
    }

    static void remove(ShizukuServiceConnection connection) {
        synchronized (CACHE) {
            CACHE.values().removeIf(val -> val == connection);
        }
    }
}
