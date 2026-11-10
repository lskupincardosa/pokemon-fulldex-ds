#pragma once
#include <cstdint>

namespace DsDisplayState
{
constexpr uint64_t Ready = 1ULL << 32;
constexpr uint64_t AcceptingFrames = 1ULL << 33;
inline int Mode(uint64_t state) { return (state >> 28) & 7; }
inline const char* ModeName(int mode)
{
    return mode == 4 ? "COMPANION" : mode == 3 ? "DS SWAPPED" : mode == 2 ? "DS SPLIT" : mode == 1 ? "EXTRA TOUCH" : "OFF";
}
inline bool Physical(uint64_t state) { return (state & Ready) && (Mode(state) == 2 || Mode(state) == 3); }
inline bool Swapped(uint64_t state) { return (state & Ready) && Mode(state) == 3; }
// EXTRA TOUCH: the widescreen view here, the DS's bottom screen on the second display.
inline bool BottomElsewhere(uint64_t state) { return (state & Ready) && Mode(state) == 1; }
inline bool Touch(uint64_t state, int& x, int& y)
{
    if (!(state & Ready) || (Mode(state) == 0 || Mode(state) >= 3) || !(state & (1ULL << 24))) return false;
    x = state & 4095;
    y = (state >> 12) & 4095;
    return x < 256 && y < 192;
}
}

#if defined(__ANDROID__) && (defined(SSR_PC_LAUNCHER) || defined(GBAW_EMBEDDED))
#include <jni.h>

// All JNI calls stay on SDL's main thread. Java owns the second window and a bounded
// latest-frame mailbox; it never accesses the emulator or its mutable framebuffer.
class AndroidSecondScreen
{
    JNIEnv* env = nullptr;
    jclass activity = nullptr;
    jintArray frame = nullptr;
    jmethodID stateMethod = nullptr, modeMethod = nullptr, runningMethod = nullptr, frameMethod = nullptr;
    bool running = false;
    int framePixels=256*192;
    bool Failed()
    {
        if (!env || !env->ExceptionCheck()) return false;
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
public:
    explicit AndroidSecondScreen(bool enabled)
    {
        if (!enabled) return;
        env = (JNIEnv*)SDL_AndroidGetJNIEnv();
        if (!env) return;
        jobject host = (jobject)SDL_AndroidGetActivity();
        if (!host) return;
        jclass local = env->GetObjectClass(host);
        activity = (jclass)env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        env->DeleteLocalRef(host);
        if (!activity || Failed()) return;
        stateMethod = env->GetStaticMethodID(activity, "secondScreenState", "()J");
        if (Failed()) return;
        modeMethod = env->GetStaticMethodID(activity, "setSecondScreenMode", "(I)V");
        if (Failed()) return;
        runningMethod = env->GetStaticMethodID(activity, "setDsRunning", "(Z)V");
        if (Failed()) return;
        frameMethod = env->GetStaticMethodID(activity, "submitDsFrame", "([III)V");
        if (Failed()) return;
        jintArray localFrame = env->NewIntArray(256 * 192);
        if (Failed() || !localFrame) return;
        frame = (jintArray)env->NewGlobalRef(localFrame);
        env->DeleteLocalRef(localFrame);
        if (Failed() || !frame) return;
        env->CallStaticVoidMethod(activity, runningMethod, JNI_TRUE);
        running = !Failed();
    }
    ~AndroidSecondScreen()
    {
        Stop();
        if (env && frame) env->DeleteGlobalRef(frame);
        if (env && activity) env->DeleteGlobalRef(activity);
    }
    void Stop()
    {
        if (running) { env->CallStaticVoidMethod(activity, runningMethod, JNI_FALSE); Failed(); }
        running = false;
    }
    uint64_t State()
    {
        if (!running) return 0;
        jlong value = env->CallStaticLongMethod(activity, stateMethod);
        return Failed() ? 0 : (uint64_t)value;
    }
    void SetMode(int mode)
    {
        if (!running) return;
        env->CallStaticVoidMethod(activity, modeMethod, (jint)mode);
        Failed();
    }
    void Submit(const uint32_t* pixels,int width=256,int height=192)
    {
        if (!running || !pixels || width<=0 || height<=0 || width>1024 || height>768 || !(State() & DsDisplayState::AcceptingFrames)) return;
        const int count=width*height;
        if(count!=framePixels)
        {
            jintArray local=env->NewIntArray(count);
            if(Failed()||!local)return;
            if(frame)env->DeleteGlobalRef(frame);
            frame=(jintArray)env->NewGlobalRef(local);env->DeleteLocalRef(local);
            if(Failed()||!frame)return;
            framePixels=count;
        }
        env->SetIntArrayRegion(frame, 0, count, (const jint*)pixels);
        if (Failed()) return;
        env->CallStaticVoidMethod(activity, frameMethod, frame, (jint)width, (jint)height);
        Failed();
    }
};
#else
class AndroidSecondScreen
{
public:
    explicit AndroidSecondScreen(bool) {}
    void Stop() {}
    uint64_t State() { return 0; }
    void SetMode(int) {}
    void Submit(const uint32_t*,int=256,int=192) {}
};
#endif
