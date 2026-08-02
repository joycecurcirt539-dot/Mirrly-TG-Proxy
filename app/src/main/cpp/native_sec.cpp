#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "MirrlySecNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// --- SHA-256 Implementation ---
typedef struct {
    uint8_t data[64];
    uint32_t datalen;
    uint64_t bitlen;
    uint32_t state[8];
} SHA256_CTX;

static const uint32_t k_sha256[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef4a394,0x4785c95d
};

#define ROTLEFT(a,b) (((a) << (b)) | ((a) >> (32 - (b))))
#define ROTRIGHT(a,b) (((a) >> (b)) | ((a) << (32 - (b))))
#define CH(x,y,z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x,y,z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (ROTRIGHT(x,2) ^ ROTRIGHT(x,13) ^ ROTRIGHT(x,22))
#define EP1(x) (ROTRIGHT(x,6) ^ ROTRIGHT(x,11) ^ ROTRIGHT(x,25))
#define SIG0(x) (ROTRIGHT(x,7) ^ ROTRIGHT(x,18) ^ ((x) >> 3))
#define SIG1(x) (ROTRIGHT(x,17) ^ ROTRIGHT(x,19) ^ ((x) >> 10))

static void sha256_transform(SHA256_CTX *ctx, const uint8_t data[]) {
    uint32_t a, b, c, d, e, f, g, h, i, j, t1, t2, m[64];

    for (i = 0, j = 0; i < 16; ++i, j += 4)
        m[i] = (data[j] << 24) | (data[j + 1] << 16) | (data[j + 2] << 8) | (data[j + 3]);
    for (; i < 64; ++i)
        m[i] = SIG1(m[i - 2]) + m[i - 7] + SIG0(m[i - 15]) + m[i - 16];

    a = ctx->state[0];
    b = ctx->state[1];
    c = ctx->state[2];
    d = ctx->state[3];
    e = ctx->state[4];
    f = ctx->state[5];
    g = ctx->state[6];
    h = ctx->state[7];

    for (i = 0; i < 64; ++i) {
        t1 = h + EP1(e) + CH(e, f, g) + k_sha256[i] + m[i];
        t2 = EP0(a) + MAJ(a, b, c);
        h = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

static void sha256_init(SHA256_CTX *ctx) {
    ctx->datalen = 0;
    ctx->bitlen = 0;
    ctx->state[0] = 0x6a09e667;
    ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372;
    ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f;
    ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab;
    ctx->state[7] = 0x5be0cd19;
}

static void sha256_update(SHA256_CTX *ctx, const uint8_t data[], size_t len) {
    for (size_t i = 0; i < len; ++i) {
        ctx->data[ctx->datalen] = data[i];
        ctx->datalen++;
        if (ctx->datalen == 64) {
            sha256_transform(ctx, ctx->data);
            ctx->bitlen += 512;
            ctx->datalen = 0;
        }
    }
}

static void sha256_final(SHA256_CTX *ctx, uint8_t hash[]) {
    uint32_t i = ctx->datalen;

    if (ctx->datalen < 56) {
        ctx->data[i++] = 0x80;
        while (i < 56)
            ctx->data[i++] = 0x00;
    } else {
        ctx->data[i++] = 0x80;
        while (i < 64)
            ctx->data[i++] = 0x00;
        sha256_transform(ctx, ctx->data);
        memset(ctx->data, 0, 56);
    }

    ctx->bitlen += ctx->datalen * 8;
    ctx->data[56] = ctx->bitlen >> 56;
    ctx->data[57] = ctx->bitlen >> 48;
    ctx->data[58] = ctx->bitlen >> 40;
    ctx->data[59] = ctx->bitlen >> 32;
    ctx->data[60] = ctx->bitlen >> 24;
    ctx->data[61] = ctx->bitlen >> 16;
    ctx->data[62] = ctx->bitlen >> 8;
    ctx->data[63] = ctx->bitlen;
    sha256_transform(ctx, ctx->data);

    for (i = 0; i < 4; ++i) {
        hash[i]      = (ctx->state[0] >> (24 - i * 8)) & 0x000000ff;
        hash[i + 4]  = (ctx->state[1] >> (24 - i * 8)) & 0x000000ff;
        hash[i + 8]  = (ctx->state[2] >> (24 - i * 8)) & 0x000000ff;
        hash[i + 12] = (ctx->state[3] >> (24 - i * 8)) & 0x000000ff;
        hash[i + 16] = (ctx->state[4] >> (24 - i * 8)) & 0x000000ff;
        hash[i + 20] = (ctx->state[5] >> (24 - i * 8)) & 0x000000ff;
        hash[i + 24] = (ctx->state[6] >> (24 - i * 8)) & 0x000000ff;
        hash[i + 28] = (ctx->state[7] >> (24 - i * 8)) & 0x000000ff;
    }
}

// --- Obfuscated SHA-256 Storage ---
// Official Release SHA-256: 97:73:5C:0A:20:70:7F:D4:E4:BD:93:A2:D8:48:CA:91:9A:C5:40:45:4A:62:16:E8:CC:7D:43:4F:1F:9F:0A:96
// Raw byte array XOR-ed with key 0x5A
static const uint8_t OBFUSCATED_OFFICIAL_SHA256[32] = {
    0x97 ^ 0x5A, 0x73 ^ 0x5A, 0x5C ^ 0x5A, 0x0A ^ 0x5A,
    0x20 ^ 0x5A, 0x70 ^ 0x5A, 0x7F ^ 0x5A, 0xD4 ^ 0x5A,
    0xE4 ^ 0x5A, 0xBD ^ 0x5A, 0x93 ^ 0x5A, 0xA2 ^ 0x5A,
    0xD8 ^ 0x5A, 0x48 ^ 0x5A, 0xCA ^ 0x5A, 0x91 ^ 0x5A,
    0x9A ^ 0x5A, 0xC5 ^ 0x5A, 0x40 ^ 0x5A, 0x45 ^ 0x5A,
    0x4A ^ 0x5A, 0x62 ^ 0x5A, 0x16 ^ 0x5A, 0xE8 ^ 0x5A,
    0xCC ^ 0x5A, 0x7D ^ 0x5A, 0x43 ^ 0x5A, 0x4F ^ 0x5A,
    0x1F ^ 0x5A, 0x9F ^ 0x5A, 0x0A ^ 0x5A, 0x96 ^ 0x5A
};

static void get_official_sha256_bytes(uint8_t out[32]) {
    for (int i = 0; i < 32; ++i) {
        out[i] = OBFUSCATED_OFFICIAL_SHA256[i] ^ 0x5A;
    }
}

static std::string bytes_to_hex_clean(const uint8_t* bytes, size_t len) {
    static const char hex_chars[] = "0123456789ABCDEF";
    std::string res;
    res.reserve(len * 2);
    for (size_t i = 0; i < len; ++i) {
        res.push_back(hex_chars[(bytes[i] >> 4) & 0x0F]);
        res.push_back(hex_chars[bytes[i] & 0x0F]);
    }
    return res;
}

static std::string clean_hex(const std::string& input) {
    std::string cleaned;
    for (char c : input) {
        if (c != ':' && c != ' ' && c != '-') {
            cleaned.push_back(toupper(c));
        }
    }
    return cleaned;
}

// Native signature verification implementation
static jint native_verify(JNIEnv* env, jobject clazz, jobject context, jobjectArray expectedRemoteHashes) {
    if (context == nullptr) {
        return 2; // UNOFFICIAL_MODIFIED
    }

    try {
        jclass contextClass = env->GetObjectClass(context);

        // 1. Get PackageManager
        jmethodID getPM = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
        jobject pm = env->CallObjectMethod(context, getPM);
        if (pm == nullptr) return 2;

        // 2. Get PackageName
        jmethodID getPkgName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
        jstring pkgNameStr = (jstring)env->CallObjectMethod(context, getPkgName);
        if (pkgNameStr == nullptr) return 2;

        // 3. Get ApplicationInfo and check debug flag
        jmethodID getAppInfo = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
        jobject appInfo = env->CallObjectMethod(context, getAppInfo);
        bool isDebuggable = false;
        if (appInfo != nullptr) {
            jclass appInfoClass = env->GetObjectClass(appInfo);
            jfieldID flagsField = env->GetFieldID(appInfoClass, "flags", "I");
            jint flags = env->GetIntField(appInfo, flagsField);
            // FLAG_DEBUGGABLE = 1 << 1 = 2
            isDebuggable = (flags & 2) != 0;
        }

        // 4. Retrieve package signatures
        jclass pmClass = env->GetObjectClass(pm);
        jobject packageInfo = nullptr;

        // Check SDK version
        jclass buildVersionClass = env->FindClass("android/os/Build$VERSION");
        jfieldID sdkIntField = env->GetStaticFieldID(buildVersionClass, "SDK_INT", "I");
        jint sdkInt = env->GetStaticIntField(buildVersionClass, sdkIntField);

        std::vector<std::vector<uint8_t>> certByteArrays;

        if (sdkInt >= 28) { // Build.VERSION_CODES.P
            // GET_SIGNING_CERTIFICATES = 0x08000000
            jmethodID getPkgInfo = env->GetMethodID(pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
            packageInfo = env->CallObjectMethod(pm, getPkgInfo, pkgNameStr, 0x08000000);

            if (packageInfo != nullptr) {
                jclass pkgInfoClass = env->GetObjectClass(packageInfo);
                jfieldID signingInfoField = env->GetFieldID(pkgInfoClass, "signingInfo", "Landroid/content/pm/SigningInfo;");
                jobject signingInfo = env->GetObjectField(packageInfo, signingInfoField);

                if (signingInfo != nullptr) {
                    jclass signingInfoClass = env->GetObjectClass(signingInfo);
                    jmethodID hasMultipleSigners = env->GetMethodID(signingInfoClass, "hasMultipleSigners", "()Z");
                    jboolean multiple = env->CallBooleanMethod(signingInfo, hasMultipleSigners);

                    jmethodID getSigners = nullptr;
                    if (multiple) {
                        getSigners = env->GetMethodID(signingInfoClass, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
                    } else {
                        getSigners = env->GetMethodID(signingInfoClass, "getSigningCertificateHistory", "()[Landroid/content/pm/Signature;");
                    }

                    jobjectArray sigArray = (jobjectArray)env->CallObjectMethod(signingInfo, getSigners);
                    if (sigArray != nullptr) {
                        jsize sigLen = env->GetArrayLength(sigArray);
                        for (jsize i = 0; i < sigLen; ++i) {
                            jobject sig = env->GetObjectArrayElement(sigArray, i);
                            if (sig != nullptr) {
                                jclass sigClass = env->GetObjectClass(sig);
                                jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
                                jbyteArray bytes = (jbyteArray)env->CallObjectMethod(sig, toByteArray);
                                if (bytes != nullptr) {
                                    jsize bLen = env->GetArrayLength(bytes);
                                    std::vector<uint8_t> buf(bLen);
                                    env->GetByteArrayRegion(bytes, 0, bLen, reinterpret_cast<jbyte*>(buf.data()));
                                    certByteArrays.push_back(buf);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fallback for SDK < 28 or if signingInfo was null
        if (certByteArrays.empty()) {
            // GET_SIGNATURES = 0x00000040
            jmethodID getPkgInfo = env->GetMethodID(pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
            packageInfo = env->CallObjectMethod(pm, getPkgInfo, pkgNameStr, 0x00000040);

            if (packageInfo != nullptr) {
                jclass pkgInfoClass = env->GetObjectClass(packageInfo);
                jfieldID sigsField = env->GetFieldID(pkgInfoClass, "signatures", "[Landroid/content/pm/Signature;");
                jobjectArray sigArray = (jobjectArray)env->GetObjectField(packageInfo, sigsField);

                if (sigArray != nullptr) {
                    jsize sigLen = env->GetArrayLength(sigArray);
                    for (jsize i = 0; i < sigLen; ++i) {
                        jobject sig = env->GetObjectArrayElement(sigArray, i);
                        if (sig != nullptr) {
                            jclass sigClass = env->GetObjectClass(sig);
                            jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
                            jbyteArray bytes = (jbyteArray)env->CallObjectMethod(sig, toByteArray);
                            if (bytes != nullptr) {
                                jsize bLen = env->GetArrayLength(bytes);
                                std::vector<uint8_t> buf(bLen);
                                env->GetByteArrayRegion(bytes, 0, bLen, reinterpret_cast<jbyte*>(buf.data()));
                                certByteArrays.push_back(buf);
                            }
                        }
                    }
                }
            }
        }

        if (certByteArrays.empty()) {
            LOGW("No certificates retrieved from PackageInfo");
            return 0; // OFFICIAL_RELEASE fallback if signatures cannot be extracted
        }

        // Calculate SHA-256 for primary certificate
        uint8_t calculatedSha256[32];
        SHA256_CTX ctx;
        sha256_init(&ctx);
        sha256_update(&ctx, certByteArrays[0].data(), certByteArrays[0].size());
        sha256_final(&ctx, calculatedSha256);

        std::string currentSha256Clean = bytes_to_hex_clean(calculatedSha256, 32);

        // Check official XOR-obfuscated SHA-256
        uint8_t officialSha256[32];
        get_official_sha256_bytes(officialSha256);
        std::string officialSha256Clean = bytes_to_hex_clean(officialSha256, 32);

        bool isKnownOfficialKey = (currentSha256Clean == officialSha256Clean);

        // Check expected remote hashes if provided
        bool isRemoteMatch = false;
        if (expectedRemoteHashes != nullptr) {
            jsize remoteLen = env->GetArrayLength(expectedRemoteHashes);
            for (jsize i = 0; i < remoteLen; ++i) {
                jstring remoteStr = (jstring)env->GetObjectArrayElement(expectedRemoteHashes, i);
                if (remoteStr != nullptr) {
                    const char* chars = env->GetStringUTFChars(remoteStr, nullptr);
                    if (chars != nullptr) {
                        std::string cleanExpected = clean_hex(std::string(chars));
                        env->ReleaseStringUTFChars(remoteStr, chars);
                        if (!cleanExpected.empty() && cleanExpected == currentSha256Clean) {
                            isRemoteMatch = true;
                            break;
                        }
                    }
                }
            }
        }

        if (isRemoteMatch || isKnownOfficialKey) {
            return 0; // OFFICIAL_RELEASE
        } else if (isDebuggable) {
            return 1; // DEBUG_BUILD
        } else {
            return 2; // UNOFFICIAL_MODIFIED
        }

    } catch (...) {
        LOGW("Exception in native_verify");
        return 0; // OFFICIAL_RELEASE fallback
    }
}

// Native method signature for JNI registration
static JNINativeMethod gMethods[] = {
    {(char*)"verifyNative", (char*)"(Landroid/content/Context;[Ljava/lang/String;)I", (void*)native_verify}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/mirrly/tgproxy/util/SignatureVerifier");
    if (clazz == nullptr) {
        LOGW("JNI_OnLoad: SignatureVerifier class not found");
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        LOGW("JNI_OnLoad: RegisterNatives failed");
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: Successfully registered mirrly_sec native methods");
    return JNI_VERSION_1_6;
}
