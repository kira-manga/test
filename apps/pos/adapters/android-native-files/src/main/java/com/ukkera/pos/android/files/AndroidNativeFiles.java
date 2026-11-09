package com.ukkera.pos.android.files;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;

/** API24 native file facilities unavailable through the older public android.system API.
 * Owns every opened descriptor; callers borrow it and must close through this class once.
 * Uses public ParcelFileDescriptor ownership, not reflection into FileDescriptor internals.
 */
public final class AndroidNativeFiles {
    private static final IdentityHashMap<FileDescriptor, ParcelFileDescriptor> OWNERS = new IdentityHashMap<>();
    static { System.loadLibrary("ukkera_native_files"); }
    private AndroidNativeFiles() {}

    /** Acquisition failed and the unreturned native handle could not be proved closed. */
    public static final class ClosureUncertainException extends IOException {
        public ClosureUncertainException(String message) { super(message); }
        private ClosureUncertainException(String message, Throwable cause) { super(message, cause); }
        private ClosureUncertainException(Throwable acquisition, Throwable cleanup) {
            super("native.file.acquisition-close-uncertain", acquisition);
            addSuppressed(cleanup);
        }
    }

    /** Atomic close-on-exec and final-component no-follow, in addition to requested flags. */
    public static FileDescriptor open(String path, int flags, int mode) throws IOException, ErrnoException {
        int raw = nativeOpen(pathBytes(path), flags, mode);
        ParcelFileDescriptor owner;
        try { owner = ParcelFileDescriptor.adoptFd(raw); }
        catch (Throwable failure) {
            try { nativeClose(raw); } catch (Throwable cleanup) { throw new ClosureUncertainException(failure, cleanup); }
            throw failure;
        }
        FileDescriptor descriptor = owner.getFileDescriptor();
        try { synchronized (OWNERS) { OWNERS.put(descriptor, owner); } }
        catch (Throwable failure) {
            synchronized (OWNERS) { OWNERS.remove(descriptor); }
            try { nativeClose(owner.detachFd()); }
            catch (Throwable cleanup) { throw new ClosureUncertainException(failure, cleanup); }
            throw failure;
        }
        return descriptor;
    }

    /** The close obligation is consumed even if the native close reports uncertainty. */
    public static void close(FileDescriptor descriptor) throws IOException, ErrnoException {
        ParcelFileDescriptor owner;
        synchronized (OWNERS) { owner = OWNERS.remove(descriptor); }
        if (owner == null) throw new IOException("native.file.not-owned");
        // Detach invalidates the Java owner before the one native close. It also prevents
        // a later finalizer from retrying an uncertain close against a reused fd number.
        try { nativeClose(owner.detachFd()); }
        catch (Throwable failure) { throw new ClosureUncertainException("native.file.close-uncertain", failure); }
    }

    public static int statusFlags(FileDescriptor descriptor) throws IOException, ErrnoException {
        synchronized (OWNERS) { return nativeStatusFlags(owner(descriptor).getFd()); }
    }

    public static boolean closeOnExec(FileDescriptor descriptor) throws IOException, ErrnoException {
        synchronized (OWNERS) { return nativeCloseOnExec(owner(descriptor).getFd()); }
    }

    private static ParcelFileDescriptor owner(FileDescriptor descriptor) throws IOException, ErrnoException {
        ParcelFileDescriptor owner = OWNERS.get(descriptor);
        if (owner == null || !descriptor.valid()) throw new IOException("native.file.not-owned");
        return owner;
    }

    public static DirectoryCursor openDirectory(String path) throws IOException, ErrnoException {
        byte[] bytes = pathBytes(path);
        DirectoryCursor cursor = new DirectoryCursor();
        cursor.pointer = nativeOpenDirectory(bytes);
        return cursor;
    }

    /** Streaming enumeration: no unbounded File.list allocation and no API26 java.nio.file. */
    public static final class DirectoryCursor implements AutoCloseable {
        private long pointer;
        private DirectoryCursor() {}
        public synchronized String nextName() throws IOException, ErrnoException {
            if (pointer == 0) throw new IOException("native.directory.closed");
            byte[] name = nativeNextName(pointer);
            if (name == null) return null;
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(name)).toString();
            } catch (CharacterCodingException failure) {
                throw new IOException("native.directory.invalid-name", failure);
            }
        }
        @Override public synchronized void close() throws IOException, ErrnoException {
            if (pointer == 0) throw new IOException("native.directory.close-already-consumed");
            long closing = pointer;
            pointer = 0;
            try { nativeCloseDirectory(closing); }
            catch (Throwable failure) { throw new ClosureUncertainException("native.directory.close-uncertain", failure); }
        }
    }

    private static byte[] pathBytes(String path) throws IOException, ErrnoException {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) throw new IOException("native.file.invalid-path");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(path));
            if (encoded.remaining() > 4096) throw new IOException("native.file.path-too-long");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) { throw new IOException("native.file.invalid-path", failure); }
    }

    private static native int nativeOpen(byte[] path, int flags, int mode) throws IOException, ErrnoException;
    private static native void nativeClose(int descriptor) throws IOException, ErrnoException;
    private static native int nativeStatusFlags(int descriptor) throws IOException, ErrnoException;
    private static native boolean nativeCloseOnExec(int descriptor) throws IOException, ErrnoException;
    private static native long nativeOpenDirectory(byte[] path) throws IOException, ErrnoException;
    private static native byte[] nativeNextName(long pointer) throws IOException, ErrnoException;
    private static native void nativeCloseDirectory(long pointer) throws IOException, ErrnoException;
}
