/*
 * luabridge — JNI glue between the Kotlin IDE shell and an embedded Lua 5.4 VM.
 *
 * Design constraints (see project spec, sections 3/5/7):
 *  - One Lua state per open project, kept alive across calls so `require()`
 *    caches and globals persist between REPL lines.
 *  - print()/error output is NOT written to stdout (Android app processes
 *    don't have a usable stdout for this) — it's routed through a Java
 *    callback so the Kotlin terminal view can render it.
 *  - package.path/package.cpath are rewritten to only ever point inside the
 *    app's own storage for this project (files dir + project's rocks dir).
 *    We never add /system/lib, /vendor/lib, or any other app's directory —
 *    Android's post-7.0 linker namespacing would refuse those loads anyway,
 *    but we don't want to imply support for it either.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "lua/lua.h"
#include "lua/lualib.h"
#include "lua/lauxlib.h"

#define LOG_TAG "luabridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Stashed on the JNI side so our custom print() can call back into Kotlin. */
typedef struct {
    JavaVM *jvm;
    jobject sink;
    jmethodID onStdout;
    jmethodID onStderr;
    jmethodID androidToast;
    jmethodID androidClipboardCopy;
    jmethodID androidClipboardPaste;
    jmethodID androidDeviceModel;
    jmethodID androidDeviceBrand;
    jmethodID androidSdkInt;
    jmethodID androidStoragePath;
    jmethodID androidHttpGet;
    jmethodID androidOverlayShow;
    jmethodID androidOverlayHide;
    jmethodID androidOverlayHasPermission;
    jmethodID androidOverlayRequestPermission;
    jmethodID androidGlClear;
} BridgeCtx;

static JNIEnv *attachEnv(BridgeCtx *ctx, int *didAttach) {
    JNIEnv *env;
    *didAttach = 0;
    if ((*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);
        *didAttach = 1;
    }
    return env;
}

static void emit(lua_State *L, jmethodID method, const char *text) {
    BridgeCtx *ctx = (BridgeCtx *)lua_touserdata(L, lua_upvalueindex(1));
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jstring j = (*env)->NewStringUTF(env, text);
    (*env)->CallVoidMethod(env, ctx->sink, method, j);
    (*env)->DeleteLocalRef(env, j);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
}

/* Lua-facing print() that forwards to the Kotlin terminal instead of stdout. */
static int bridge_print(lua_State *L) {
    BridgeCtx *ctx = (BridgeCtx *)lua_touserdata(L, lua_upvalueindex(1));
    int n = lua_gettop(L);
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    for (int i = 1; i <= n; i++) {
        if (i > 1) luaL_addchar(&b, '\t');
        luaL_addstring(&b, luaL_tolstring(L, i, NULL));
        lua_pop(L, 1);
    }
    luaL_pushresult(&b);
    emit(L, ctx->onStdout, lua_tostring(L, -1));
    lua_pop(L, 1);
    return 0;
}

/* ---- android.* API (spec section 7): every function here is a real JNI
   round-trip into an actual Android API — no stubs. ---- */

static BridgeCtx *ctxFromClosure(lua_State *L) {
    return (BridgeCtx *)lua_touserdata(L, lua_upvalueindex(1));
}

static int android_toast(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    const char *msg = luaL_checkstring(L, 1);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jstring j = (*env)->NewStringUTF(env, msg);
    (*env)->CallVoidMethod(env, ctx->sink, ctx->androidToast, j);
    (*env)->DeleteLocalRef(env, j);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 0;
}

static int android_clipboard_copy(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    const char *text = luaL_checkstring(L, 1);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jstring j = (*env)->NewStringUTF(env, text);
    (*env)->CallVoidMethod(env, ctx->sink, ctx->androidClipboardCopy, j);
    (*env)->DeleteLocalRef(env, j);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 0;
}

static int android_clipboard_paste(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jstring result = (jstring)(*env)->CallObjectMethod(env, ctx->sink, ctx->androidClipboardPaste);
    const char *chars = (*env)->GetStringUTFChars(env, result, NULL);
    lua_pushstring(L, chars);
    (*env)->ReleaseStringUTFChars(env, result, chars);
    (*env)->DeleteLocalRef(env, result);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 1;
}

static int android_device(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);

    jstring model = (jstring)(*env)->CallObjectMethod(env, ctx->sink, ctx->androidDeviceModel);
    jstring brand = (jstring)(*env)->CallObjectMethod(env, ctx->sink, ctx->androidDeviceBrand);
    jint sdk = (*env)->CallIntMethod(env, ctx->sink, ctx->androidSdkInt);

    const char *modelChars = (*env)->GetStringUTFChars(env, model, NULL);
    const char *brandChars = (*env)->GetStringUTFChars(env, brand, NULL);

    lua_newtable(L);
    lua_pushstring(L, modelChars); lua_setfield(L, -2, "model");
    lua_pushstring(L, brandChars); lua_setfield(L, -2, "brand");
    lua_pushinteger(L, sdk); lua_setfield(L, -2, "sdk_int");

    (*env)->ReleaseStringUTFChars(env, model, modelChars);
    (*env)->ReleaseStringUTFChars(env, brand, brandChars);
    (*env)->DeleteLocalRef(env, model);
    (*env)->DeleteLocalRef(env, brand);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 1;
}

static int android_storage_path(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    const char *kind = luaL_checkstring(L, 1); /* "files" | "cache" */
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jstring kindJ = (*env)->NewStringUTF(env, kind);
    jstring result = (jstring)(*env)->CallObjectMethod(env, ctx->sink, ctx->androidStoragePath, kindJ);
    const char *chars = (*env)->GetStringUTFChars(env, result, NULL);
    lua_pushstring(L, chars);
    (*env)->ReleaseStringUTFChars(env, result, chars);
    (*env)->DeleteLocalRef(env, kindJ);
    (*env)->DeleteLocalRef(env, result);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 1;
}

static int android_http_get(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    const char *url = luaL_checkstring(L, 1);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jstring urlJ = (*env)->NewStringUTF(env, url);
    /* Blocking network call on whatever thread eval() runs on — same
       trade-off Lua's own io/socket libs make; caller's responsibility
       not to do this on a latency-sensitive UI path. */
    jstring result = (jstring)(*env)->CallObjectMethod(env, ctx->sink, ctx->androidHttpGet, urlJ);
    const char *chars = (*env)->GetStringUTFChars(env, result, NULL);
    lua_pushstring(L, chars);
    (*env)->ReleaseStringUTFChars(env, result, chars);
    (*env)->DeleteLocalRef(env, urlJ);
    (*env)->DeleteLocalRef(env, result);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 1;
}

/* ---- android.overlay_*() : real TYPE_APPLICATION_OVERLAY window ---- */

static int android_overlay_show(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    const char *text = luaL_checkstring(L, 1);
    lua_Integer x = luaL_optinteger(L, 2, 100);
    lua_Integer y = luaL_optinteger(L, 3, 300);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jstring j = (*env)->NewStringUTF(env, text);
    (*env)->CallVoidMethod(env, ctx->sink, ctx->androidOverlayShow, j, (jint)x, (jint)y);
    (*env)->DeleteLocalRef(env, j);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 0;
}

static int android_overlay_hide(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    (*env)->CallVoidMethod(env, ctx->sink, ctx->androidOverlayHide);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 0;
}

static int android_overlay_has_permission(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    jboolean has = (*env)->CallBooleanMethod(env, ctx->sink, ctx->androidOverlayHasPermission);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    lua_pushboolean(L, has);
    return 1;
}

static int android_overlay_request_permission(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    (*env)->CallVoidMethod(env, ctx->sink, ctx->androidOverlayRequestPermission);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 0;
}

/* android.gl_clear(r,g,b,a) — must only ever be called from a Lua state
   that's exclusively driven by the GL thread (see LuaGLSurfaceView); this
   function assumes it's already executing on that thread, same as every
   other GLES20.* call in a frame callback. */
static int android_gl_clear(lua_State *L) {
    BridgeCtx *ctx = ctxFromClosure(L);
    lua_Number r = luaL_checknumber(L, 1);
    lua_Number g = luaL_checknumber(L, 2);
    lua_Number b = luaL_checknumber(L, 3);
    lua_Number a = luaL_optnumber(L, 4, 1.0);
    int didAttach;
    JNIEnv *env = attachEnv(ctx, &didAttach);
    (*env)->CallVoidMethod(env, ctx->sink, ctx->androidGlClear, (jfloat)r, (jfloat)g, (jfloat)b, (jfloat)a);
    if (didAttach) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    return 0;
}

static const luaL_Reg android_funcs[] = {
    {"toast", android_toast},
    {"clipboard_copy", android_clipboard_copy},
    {"clipboard_paste", android_clipboard_paste},
    {"device", android_device},
    {"storage_path", android_storage_path},
    {"http_get", android_http_get},
    {"overlay_show", android_overlay_show},
    {"overlay_hide", android_overlay_hide},
    {"overlay_has_permission", android_overlay_has_permission},
    {"overlay_request_permission", android_overlay_request_permission},
    {"gl_clear", android_gl_clear},
    {NULL, NULL}
};

static void register_android_bridge(lua_State *L, BridgeCtx *ctx) {
    lua_newtable(L); /* the "android" table */
    for (const luaL_Reg *f = android_funcs; f->name; f++) {
        lua_pushlightuserdata(L, ctx);
        lua_pushcclosure(L, f->func, 1);
        lua_setfield(L, -2, f->name);
    }
    lua_setglobal(L, "android");
}

JNIEXPORT jlong JNICALL
Java_com_luaide_app_LuaBridge_nativeOpen(JNIEnv *env, jobject thiz,
                                          jobject sink, jstring projectRocksPath) {
    lua_State *L = luaL_newstate();
    if (!L) return 0;
    luaL_openlibs(L);

    BridgeCtx *ctx = malloc(sizeof(BridgeCtx));
    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->sink = (*env)->NewGlobalRef(env, sink);
    jclass sinkClass = (*env)->GetObjectClass(env, sink);
    ctx->onStdout = (*env)->GetMethodID(env, sinkClass, "onStdout", "(Ljava/lang/String;)V");
    ctx->onStderr = (*env)->GetMethodID(env, sinkClass, "onStderr", "(Ljava/lang/String;)V");
    /* android.* bridge methods live on the same sink object (LuaBridge.OutputSink
       now declares them with default no-op impls, so any existing sink still works). */
    ctx->androidToast = (*env)->GetMethodID(env, sinkClass, "androidToast", "(Ljava/lang/String;)V");
    ctx->androidClipboardCopy = (*env)->GetMethodID(env, sinkClass, "androidClipboardCopy", "(Ljava/lang/String;)V");
    ctx->androidClipboardPaste = (*env)->GetMethodID(env, sinkClass, "androidClipboardPaste", "()Ljava/lang/String;");
    ctx->androidDeviceModel = (*env)->GetMethodID(env, sinkClass, "androidDeviceModel", "()Ljava/lang/String;");
    ctx->androidDeviceBrand = (*env)->GetMethodID(env, sinkClass, "androidDeviceBrand", "()Ljava/lang/String;");
    ctx->androidSdkInt = (*env)->GetMethodID(env, sinkClass, "androidSdkInt", "()I");
    ctx->androidStoragePath = (*env)->GetMethodID(env, sinkClass, "androidStoragePath", "(Ljava/lang/String;)Ljava/lang/String;");
    ctx->androidHttpGet = (*env)->GetMethodID(env, sinkClass, "androidHttpGet", "(Ljava/lang/String;)Ljava/lang/String;");
    ctx->androidOverlayShow = (*env)->GetMethodID(env, sinkClass, "androidOverlayShow", "(Ljava/lang/String;II)V");
    ctx->androidOverlayHide = (*env)->GetMethodID(env, sinkClass, "androidOverlayHide", "()V");
    ctx->androidOverlayHasPermission = (*env)->GetMethodID(env, sinkClass, "androidOverlayHasPermission", "()Z");
    ctx->androidOverlayRequestPermission = (*env)->GetMethodID(env, sinkClass, "androidOverlayRequestPermission", "()V");
    ctx->androidGlClear = (*env)->GetMethodID(env, sinkClass, "androidGlClear", "(FFFF)V");

    /* Override print() with our routed version. Bound as a closure over ctx. */
    lua_pushlightuserdata(L, ctx);
    lua_pushcclosure(L, bridge_print, 1);
    lua_setglobal(L, "print");

    register_android_bridge(L, ctx);

    /* Scope package.path / package.cpath to this project's rocks dir only. */
    const char *rocks = (*env)->GetStringUTFChars(env, projectRocksPath, NULL);
    lua_getglobal(L, "package");
    char pathbuf[1024];
    snprintf(pathbuf, sizeof(pathbuf),
             "%s/share/lua/5.4/?.lua;%s/share/lua/5.4/?/init.lua;./?.lua", rocks, rocks);
    lua_pushstring(L, pathbuf);
    lua_setfield(L, -2, "path");
    char cpathbuf[1024];
    snprintf(cpathbuf, sizeof(cpathbuf), "%s/lib/lua/5.4/?.so", rocks);
    lua_pushstring(L, cpathbuf);
    lua_setfield(L, -2, "cpath");
    lua_pop(L, 1);
    (*env)->ReleaseStringUTFChars(env, projectRocksPath, rocks);

    /* Stash ctx pointer as extra space so nativeClose/nativeEval can find it. */
    lua_pushlightuserdata(L, ctx);
    lua_setfield(L, LUA_REGISTRYINDEX, "__bridge_ctx");

    return (jlong)(intptr_t)L;
}

JNIEXPORT jboolean JNICALL
Java_com_luaide_app_LuaBridge_nativeEval(JNIEnv *env, jobject thiz,
                                          jlong statePtr, jstring code, jstring chunkName) {
    lua_State *L = (lua_State *)(intptr_t)statePtr;
    if (!L) return JNI_FALSE;

    lua_getfield(L, LUA_REGISTRYINDEX, "__bridge_ctx");
    BridgeCtx *ctx = (BridgeCtx *)lua_touserdata(L, -1);
    lua_pop(L, 1);

    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    const char *name = (*env)->GetStringUTFChars(env, chunkName, NULL);

    int status = luaL_loadbuffer(L, src, strlen(src), name);
    if (status == LUA_OK) {
        status = lua_pcall(L, 0, LUA_MULTRET, 0);
    }
    jboolean ok = JNI_TRUE;
    if (status != LUA_OK) {
        const char *msg = lua_tostring(L, -1);
        emit(L, ctx->onStderr, msg ? msg : "unknown Lua error");
        lua_pop(L, 1);
        ok = JNI_FALSE;
    }

    (*env)->ReleaseStringUTFChars(env, code, src);
    (*env)->ReleaseStringUTFChars(env, chunkName, name);
    return ok;
}

JNIEXPORT jstring JNICALL
Java_com_luaide_app_LuaBridge_nativeCheckSyntax(JNIEnv *env, jobject thiz,
                                                 jlong statePtr, jstring code) {
    lua_State *L = (lua_State *)(intptr_t)statePtr;
    if (!L) return NULL;

    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    /* luaL_loadbuffer only compiles; it never executes anything, so this is
       safe to call on every keystroke without side effects on the running
       program's globals/state. */
    int status = luaL_loadbuffer(L, src, strlen(src), "=diagnostic");
    (*env)->ReleaseStringUTFChars(env, code, src);

    if (status == LUA_OK) {
        lua_pop(L, 1); /* discard the compiled chunk, we only wanted to know if it compiled */
        return NULL;
    }
    jstring msg = (*env)->NewStringUTF(env, lua_tostring(L, -1));
    lua_pop(L, 1);
    return msg;
}

JNIEXPORT void JNICALL
Java_com_luaide_app_LuaBridge_nativeClose(JNIEnv *env, jobject thiz, jlong statePtr) {
    lua_State *L = (lua_State *)(intptr_t)statePtr;
    if (!L) return;
    lua_getfield(L, LUA_REGISTRYINDEX, "__bridge_ctx");
    BridgeCtx *ctx = (BridgeCtx *)lua_touserdata(L, -1);
    if (ctx) {
        (*env)->DeleteGlobalRef(env, ctx->sink);
        free(ctx);
    }
    lua_close(L);
}
