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
    jobject sink;       /* global ref to the Kotlin OutputSink instance   */
    jmethodID onStdout;  /* void onStdout(String)                        */
    jmethodID onStderr;  /* void onStderr(String)                        */
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

    /* Override print() with our routed version. Bound as a closure over ctx. */
    lua_pushlightuserdata(L, ctx);
    lua_pushcclosure(L, bridge_print, 1);
    lua_setglobal(L, "print");

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
