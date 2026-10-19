package rikka.shizuku;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import moe.shizuku.server.IShizukuServiceConnection;

class ShizukuServiceConnection extends IShizukuServiceConnection.Stub {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Set<ServiceConnection> connections = new CopyOnWriteArraySet<>();
    private final ComponentName componentName;
    private final IBinder.DeathRecipient deathRecipient = this::died;
    private IBinder binder;

    public ShizukuServiceConnection(Shizuku.UserServiceArgs args) {
        this.componentName = args.componentName;
    }

    private volatile boolean dead = false;

    public void addConnection(@Nullable ServiceConnection conn) {
        if (conn != null) {
            connections.add(conn);
        }
    }

    public void removeConnection(@Nullable ServiceConnection conn) {
        if (conn != null) {
            connections.remove(conn);
        }
    }

    public void clearConnections() {
        connections.clear();
    }

    @Override
    public void connected(IBinder binder) {
        MAIN_HANDLER.post(() -> {
            for (ServiceConnection conn : connections) {
                conn.onServiceConnected(componentName, binder);
            }
        });

        this.binder = binder;

        try {
            this.binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void died() {
        IBinder currentBinder = binder;
        binder = null;
        if (currentBinder != null) {
            try {
                currentBinder.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
        }

        if (dead) return;
        dead = true;

        MAIN_HANDLER.post(() -> {
            for (ServiceConnection conn : connections) {
                conn.onServiceDisconnected(componentName);
            }

            connections.clear();
            ShizukuServiceConnections.remove(this);
        });
    }
}
