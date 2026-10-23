#include <jni.h>
#include <functional>
#include <string>

using ProgressFn = std::function<bool(int, const std::string&)>;
std::string RunExtract(const std::string&, const std::string&, const ProgressFn&);

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_pkgextractor_NativeBridge_extract(JNIEnv* env, jobject, jstring jpkg, jstring jout, jobject cb) {
    const char* p = env->GetStringUTFChars(jpkg, nullptr);
    const char* o = env->GetStringUTFChars(jout, nullptr);
    std::string pkg(p), out(o);
    env->ReleaseStringUTFChars(jpkg, p);
    env->ReleaseStringUTFChars(jout, o);

    jclass cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(ILjava/lang/String;)Z");

    ProgressFn fn = [&](int pct, const std::string& file) {
        jstring jf = env->NewStringUTF(file.c_str());
        jboolean cont = env->CallBooleanMethod(cb, mid, pct, jf);
        env->DeleteLocalRef(jf);
        return cont == JNI_TRUE;
    };
    std::string err = RunExtract(pkg, out, fn);
    return env->NewStringUTF(err.c_str());
}
