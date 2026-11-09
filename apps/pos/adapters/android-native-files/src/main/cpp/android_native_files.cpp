#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

namespace {
void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message);
}

void failErrno(JNIEnv* env, const char* operation, int error) {
    jclass type = env->FindClass("android/system/ErrnoException");
    if (type == nullptr) return;
    jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;I)V");
    if (constructor == nullptr) return;
    jstring name = env->NewStringUTF(operation);
    if (name == nullptr) return;
    jobject failure = env->NewObject(type, constructor, name, error);
    if (failure != nullptr) env->Throw(static_cast<jthrowable>(failure));
}

bool path(JNIEnv* env, jbyteArray bytes, char (&result)[4097]) {
    if (bytes == nullptr) { fail(env, "native.file.invalid-path"); return false; }
    jsize length = env->GetArrayLength(bytes);
    if (length <= 0 || length > 4096) { fail(env, "native.file.invalid-path"); return false; }
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(result));
    if (env->ExceptionCheck()) return false;
    if (std::memchr(result, '\0', static_cast<size_t>(length)) != nullptr) { fail(env, "native.file.invalid-path"); return false; }
    result[length] = '\0';
    return true;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ukkera_pos_android_files_AndroidNativeFiles_nativeOpen(JNIEnv* env, jclass, jbyteArray bytes, jint flags, jint mode) {
    char name[4097];
    if (!path(env, bytes, name)) return -1;
    int descriptor;
    do { descriptor = open(name, flags | O_CLOEXEC | O_NOFOLLOW, static_cast<mode_t>(mode)); }
    while (descriptor == -1 && errno == EINTR);
    if (descriptor == -1) failErrno(env, "open", errno);
    return descriptor;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ukkera_pos_android_files_AndroidNativeFiles_nativeClose(JNIEnv* env, jclass, jint descriptor) {
    // Never retry close: after an interrupted close the descriptor number may be reused.
    if (close(descriptor) != 0) failErrno(env, "close", errno);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ukkera_pos_android_files_AndroidNativeFiles_nativeStatusFlags(JNIEnv* env, jclass, jint descriptor) {
    int flags;
    do { flags = fcntl(descriptor, F_GETFL); } while (flags == -1 && errno == EINTR);
    if (flags == -1) failErrno(env, "fcntl", errno);
    return flags;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ukkera_pos_android_files_AndroidNativeFiles_nativeCloseOnExec(JNIEnv* env, jclass, jint descriptor) {
    int flags;
    do { flags = fcntl(descriptor, F_GETFD); } while (flags == -1 && errno == EINTR);
    if (flags == -1) { failErrno(env, "fcntl", errno); return JNI_FALSE; }
    return (flags & FD_CLOEXEC) != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ukkera_pos_android_files_AndroidNativeFiles_nativeOpenDirectory(JNIEnv* env, jclass, jbyteArray bytes) {
    char name[4097];
    if (!path(env, bytes, name)) return 0;
    int descriptor;
    do { descriptor = open(name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); }
    while (descriptor == -1 && errno == EINTR);
    if (descriptor == -1) { fail(env, "native.directory.open-failed"); return 0; }
    DIR* directory = fdopendir(descriptor);
    if (directory == nullptr) {
        int error = errno;
        if (close(descriptor) != 0) {
            jclass type = env->FindClass("com/ukkera/pos/android/files/AndroidNativeFiles$ClosureUncertainException");
            if (type != nullptr) env->ThrowNew(type, "native.directory.acquisition-close-uncertain");
        } else failErrno(env, "fdopendir", error);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(directory));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ukkera_pos_android_files_AndroidNativeFiles_nativeNextName(JNIEnv* env, jclass, jlong pointer) {
    auto* directory = reinterpret_cast<DIR*>(static_cast<uintptr_t>(pointer));
    if (directory == nullptr) { fail(env, "native.directory.closed"); return nullptr; }
    for (;;) {
        errno = 0;
        dirent* entry = readdir(directory);
        if (entry == nullptr) {
            if (errno != 0) fail(env, "native.directory.read-failed");
            return nullptr;
        }
        if (std::strcmp(entry->d_name, ".") == 0 || std::strcmp(entry->d_name, "..") == 0) continue;
        size_t length = std::strlen(entry->d_name);
        jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
        if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(entry->d_name));
        return result;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ukkera_pos_android_files_AndroidNativeFiles_nativeCloseDirectory(JNIEnv* env, jclass, jlong pointer) {
    auto* directory = reinterpret_cast<DIR*>(static_cast<uintptr_t>(pointer));
    if (directory == nullptr || closedir(directory) != 0) fail(env, "native.directory.close-uncertain");
}
