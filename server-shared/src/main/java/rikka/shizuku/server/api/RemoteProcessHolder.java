package rikka.shizuku.server.api;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.ParcelFileDescriptorUtil;

public class RemoteProcessHolder extends IRemoteProcess.Stub {

    private static final Logger LOGGER = new Logger("RemoteProcessHolder");

    private final Process process;
    private final IBinder token;
    private IBinder.DeathRecipient deathRecipient;
    private ParcelFileDescriptor in;
    private ParcelFileDescriptor out;

    public RemoteProcessHolder(Process process, IBinder token) {
        this.process = process;
        this.token = token;

        if (token != null) {
            try {
                this.deathRecipient = () -> {
                    try {
                        if (alive()) {
                            destroy();
                            LOGGER.i("destroy process because the owner is dead");
                        }
                    } catch (Throwable e) {
                        LOGGER.w(e, "failed to destroy process");
                    }
                };
                token.linkToDeath(this.deathRecipient, 0);
            } catch (Throwable e) {
                LOGGER.w(e, "linkToDeath");
            }
        }
    }

    private synchronized void unlinkTokenToDeath() {
        if (token != null && deathRecipient != null) {
            try {
                token.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
            deathRecipient = null;
        }
    }

    @Override
    public synchronized ParcelFileDescriptor getOutputStream() {
        if (out == null) {
            try {
                out = ParcelFileDescriptorUtil.pipeTo(process.getOutputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    @Override
    public synchronized ParcelFileDescriptor getInputStream() {
        if (in == null) {
            try {
                in = ParcelFileDescriptorUtil.pipeFrom(process.getInputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return in;
    }

    @Override
    public ParcelFileDescriptor getErrorStream() {
        try {
            return ParcelFileDescriptorUtil.pipeFrom(process.getErrorStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int waitFor() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            unlinkTokenToDeath();
        }
    }

    @Override
    public int exitValue() {
        int code = process.exitValue();
        unlinkTokenToDeath();
        return code;
    }

    @Override
    public void destroy() {
        try {
            process.destroy();
        } finally {
            unlinkTokenToDeath();
        }
    }

    @Override
    public boolean alive() throws RemoteException {
        try {
            this.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) throws RemoteException {
        TimeUnit unit = TimeUnit.valueOf(unitName);
        long startTime = System.nanoTime();
        long rem = unit.toNanos(timeout);

        do {
            try {
                exitValue();
                return true;
            } catch (IllegalThreadStateException ex) {
                if (rem > 0) {
                    try {
                        Thread.sleep(
                                Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException();
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime);
        } while (rem > 0);
        return false;
    }
}
